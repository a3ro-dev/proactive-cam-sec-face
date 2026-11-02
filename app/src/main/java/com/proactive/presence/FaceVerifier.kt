package com.proactive.presence

import android.content.Context
import android.graphics.Bitmap
import java.io.File
import kotlin.math.sqrt
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp

/**
 * Owner-only verifier. Tiny MobileFaceNet (192-d, int8, ~1MB) runs via NNAPI
 * so MediaTek APU handles it. Falls back to CPU if NNAPI missing.
 * Enrollment happens on the laptop (tools/enroll.py) -> owner.json.
 */
class FaceVerifier(context: Context) {

    private var interpreter: Interpreter? = null
    private var ownerMean: FloatArray? = null
    var threshold: Float = 0.62f

    init {
        loadOwnerVector(context)
        initInterpreter(context)
    }

    private fun loadOwnerVector(context: Context) {
        val f = File(context.filesDir, "owner.json")
        if (!f.exists()) return
        try {
            val o = JSONObject(f.readText())
            threshold = o.optDouble("threshold", 0.62).toFloat()
            val arr = o.getJSONArray("mean")
            ownerMean = FloatArray(arr.length()) { i -> arr.getDouble(i).toFloat() }
        } catch (_: Exception) {
            ownerMean = null
        }
    }

    fun hasOwner(): Boolean = ownerMean != null

    fun reload(context: Context) = loadOwnerVector(context)

    private fun initInterpreter(context: Context) {
        try {
            val model = context.assets.open("mobilefacenet.tflite").readBytes()
            val buf = java.nio.ByteBuffer.allocateDirect(model.size)
                .order(java.nio.ByteOrder.nativeOrder())
            buf.put(model)
            val opts = Interpreter.Options()
            // Prefer NNAPI (MediaTek Neuron / APU path). GpuDelegate as 2nd try.
            try {
                opts.addDelegate(NnApiDelegate())
            } catch (_: Exception) {
                try {
                    if (CompatibilityList().isDelegateSupportedOnThisDevice) {
                        opts.addDelegate(GpuDelegate())
                    }
                } catch (_: Exception) { /* CPU fallback */ }
            }
            opts.setNumThreads(2)
            interpreter = Interpreter(buf, opts)
        } catch (_: Exception) {
            interpreter = null // model not bundled yet; presence-only mode
        }
    }

    /** Returns cosine similarity to owner, or null if no model/owner. */
    fun similarity(faceCrop: Bitmap): Float? {
        val interp = interpreter ?: return null
        val mean = ownerMean ?: return null
        return try {
            val img = TensorImage.fromBitmap(faceCrop)
            val resized = ResizeOp(160, 160, ResizeOp.ResizeMethod.BILINEAR)
                .apply(img)
            val input = resized.buffer
            val out = Array(1) { FloatArray(mean.size) }
            interp.run(input, out)
            cosine(out[0], mean)
        } catch (_: Exception) {
            null
        }
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
