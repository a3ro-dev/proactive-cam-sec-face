package com.proactive.presence

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import kotlin.math.sqrt
import kotlin.system.measureNanoTime
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp

/**
 * Owner-only verifier. Tiny MobileFaceNet (192-d, int8, ~1MB).
 *
 * Engine order is user-controlled (onboarding choice):
 *   NPU  -> NNAPI, which reaches Hexagon on Snapdragon and the APU on
 *           MediaTek with zero vendor SDKs.
 *   GPU  -> GPU delegate, for NPU-less or weak-NPU phones.
 *   CPU  -> 2 threads, works everywhere.
 *   AUTO -> benchmarked once in onboarding, fastest wins, stored in prefs.
 *
 * Enrollment runs on-device (EnrollHelper) so no video leaves the phone.
 * Without a bundled model (assets/mobilefacenet.tflite) the guard runs in
 * presence-only mode: any face counts as the owner.
 */
class FaceVerifier(private val context: Context) {

    private var interpreter: Interpreter? = null
    private var activeAccel: Accel = Accel.CPU
    private var ownerMean: FloatArray? = null
    var threshold: Float = 0.62f
        private set

    init {
        loadOwnerVector()
        configure(AccelStore.get(context), silent = true)
    }

    // ---------- owner profile ----------

    fun hasOwner(): Boolean = ownerMean != null

    fun reload() = loadOwnerVector()

    private fun loadOwnerVector() {
        val f = File(context.filesDir, "owner.json")
        ownerMean = null
        if (!f.exists()) return
        try {
            val o = JSONObject(f.readText())
            threshold = o.optDouble("threshold", 0.62).toFloat()
            val arr = o.getJSONArray("mean")
            ownerMean = FloatArray(arr.length()) { i -> arr.getDouble(i).toFloat() }
        } catch (e: Exception) {
            Log.w("Verifier", "owner.json unreadable", e)
        }
    }

    // ---------- engine ----------

    fun activeEngine(): Accel = activeAccel

    fun modelPresent(): Boolean = try {
        context.assets.openFd("mobilefacenet.tflite").close()
        true
    } catch (_: Exception) {
        false
    }

    fun configure(accel: Accel, silent: Boolean = false) {
        close()
        val order = when (accel) {
            Accel.NPU -> listOf(Accel.NPU, Accel.GPU, Accel.CPU)
            Accel.GPU -> listOf(Accel.GPU, Accel.CPU)
            Accel.CPU -> listOf(Accel.CPU)
            Accel.AUTO -> listOf(Accel.NPU, Accel.GPU, Accel.CPU)
        }
        for (a in order) {
            if (tryEngine(a)) {
                activeAccel = a
                if (!silent) AccelStore.set(context, accel)
                return
            }
        }
        activeAccel = Accel.CPU
    }

    private fun tryEngine(a: Accel): Boolean {
        if (!modelPresent()) return a == Accel.CPU // presence-only mode
        return try {
            val bytes = context.assets.open("mobilefacenet.tflite").readBytes()
            val buf = java.nio.ByteBuffer.allocateDirect(bytes.size)
                .order(java.nio.ByteOrder.nativeOrder())
            buf.put(bytes)
            buf.rewind()
            val opts = Interpreter.Options().setNumThreads(2)
            when (a) {
                Accel.NPU -> opts.addDelegate(NnApiDelegate())
                Accel.GPU -> {
                    if (!CompatibilityList().isDelegateSupportedOnThisDevice) return false
                    opts.addDelegate(GpuDelegate())
                }
                else -> {}
            }
            interpreter = Interpreter(buf, opts)
            true
        } catch (e: Exception) {
            Log.w("Verifier", "$a delegate failed, trying next", e)
            false
        }
    }

    /** Mean ms per inference over [runs] runs. Null when no model. */
    fun benchmark(runs: Int = 8): Double? {
        val interp = interpreter ?: return null
        val dim = 192
        val probe = Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888)
        // Warm up (delegate init, shader compile, APU boot).
        repeat(2) { embedRaw(interp, probe, dim) }
        val total = measureNanoTime { repeat(runs) { embedRaw(interp, probe, dim) } }
        return total / 1e6 / runs
    }

    // ---------- inference ----------

    /** Raw 192-d embedding, or null. Used by guard and enrollment. */
    fun embed(faceCrop: Bitmap): FloatArray? {
        val interp = interpreter ?: return null
        return embedRaw(interp, faceCrop, ownerMean?.size ?: 192)
    }

    private fun embedRaw(interp: Interpreter, faceCrop: Bitmap, dim: Int): FloatArray? {
        return try {
            val img = TensorImage.fromBitmap(faceCrop)
            val resized = ResizeOp(160, 160, ResizeOp.ResizeMethod.BILINEAR).apply(img)
            val out = Array(1) { FloatArray(dim) }
            interp.run(resized.buffer, out)
            val v = out[0]
            var n = 0f
            for (x in v) n += x * x
            n = kotlin.math.sqrt(n) + 1e-9f
            for (i in v.indices) v[i] /= n
            v
        } catch (e: Exception) {
            Log.w("Verifier", "embed failed", e)
            null
        }
    }

    /** Cosine similarity to owner, or null if no model/owner. */
    fun similarity(faceCrop: Bitmap): Float? {
        val mean = ownerMean ?: return null
        val v = embed(faceCrop) ?: return null
        if (v.size != mean.size) return null
        return cosine(v, mean)
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        return dot / (sqrt(na) * sqrt(nb) + 1e-6f)
    }

    fun close() {
        try { interpreter?.close() } catch (_: Exception) {}
        interpreter = null
    }
}
