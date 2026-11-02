package com.proactive.presence

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.PowerManager
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors

/**
 * Quiet guard: one low-res frame every 5s, no preview, no overlay.
 * - owner seen -> refresh wake window (screen stays on naturally)
 * - no face 15s -> lockNow()
 * - stranger 2x in a row -> lockNow() immediately
 * Never touches the foreground app: no kills, no brightness hacks.
 */
class GuardService : LifecycleService() {

    companion object {
        const val ABSENT_TIMEOUT_MS = 15_000L
        const val TICK_MS = 5_000L
        const val STRANGER_STRIKES = 2
    }

    private val exec = Executors.newSingleThreadExecutor()
    private lateinit var verifier: FaceVerifier
    private var lastTick = 0L
    private var lastOwnerSeen = System.currentTimeMillis()
    private var strangerStrikes = 0
    private var wakeLock: PowerManager.WakeLock? = null

    private val detector by lazy {
        val opts = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.25f)
            .build()
        FaceDetection.getClient(opts)
    }

    override fun onCreate() {
        super.onCreate()
        verifier = FaceVerifier(this)
        startForeground(1, notif())
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
            "presence:owner-window"
        ).apply { setReferenceCounted(false) }
        bindCamera()
    }

    private fun notif(): Notification {
        val ch = "guard"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(ch, "Presence guard", NotificationManager.IMPORTANCE_MIN)
        )
        return NotificationCompat.Builder(this, ch)
            .setContentTitle("Presence guard watching quietly")
            .setContentText("On-device only. No video leaves your phone.")
            .setSmallIcon(android.R.drawable.presence_online)
            .setOngoing(true)
            .build()
    }

    private fun bindCamera() {
        val fut = ProcessCameraProvider.getInstance(this)
        fut.addListener({
            val provider = fut.get()
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(android.util.Size(640, 480))
                .build()
            analysis.setAnalyzer(exec) { proxy -> onFrame(proxy) }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis
                )
            } catch (e: Exception) {
                Log.w("Guard", "camera bind failed", e)
            }
        }, androidx.core.content.ContextCompat.getMainExecutor(this))
    }

    @androidx.camera.core.ExperimentalGetImage
    private fun onFrame(proxy: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastTick < TICK_MS) return
            lastTick = now
            val media = proxy.image ?: return
            val rot = proxy.imageInfo.rotationDegrees
            val input = InputImage.fromMediaImage(media, rot)
            detector.process(input)
                .addOnSuccessListener { faces ->
                    if (faces.isEmpty()) {
                        checkAbsent(now)
                    } else {
                        // Crop first (largest) face to 160px for embedding.
                        val bmp = proxy.toBitmap()
                        if (bmp != null && verifier.hasOwner()) {
                            val crop = cropFace(bmp, faces[0].boundingBox)
                            val sim = if (crop != null) verifier.similarity(crop) else null
                            if (sim == null) {
                                // No model yet: any face counts as owner (presence mode).
                                onOwnerSeen()
                            } else if (sim >= verifier.threshold) {
                                onOwnerSeen()
                            } else {
                                onStranger(now)
                            }
                        } else {
                            onOwnerSeen()
                        }
                    }
                }
                .addOnFailureListener { checkAbsent(now) }
        } finally {
            proxy.close()
        }
    }

    private fun onOwnerSeen() {
        lastOwnerSeen = System.currentTimeMillis()
        strangerStrikes = 0
        // Keep screen alive a short window; auto-releases. No overlay.
        try {
            val wl = wakeLock
            if (wl != null && !wl.isHeld) wl.acquire(30_000L)
        } catch (_: Exception) {}
        sendBroadcast(Intent("com.proactive.presence.OWNER_SEEN"))
    }

    private fun checkAbsent(now: Long) {
        strangerStrikes = 0
        if (now - lastOwnerSeen >= ABSENT_TIMEOUT_MS) lock("absent-15s")
    }

    private fun onStranger(now: Long) {
        strangerStrikes++
        if (strangerStrikes >= STRANGER_STRIKES) {
            lock("stranger")
        } else {
            // Give one more tick before acting; still counts absence.
            checkAbsent(now)
        }
    }

    private fun lock(reason: String) {
        try {
            val dpm = getSystemService(DevicePolicyManager::class.java)
            val admin = ComponentName(this, GuardAdminReceiver::class.java)
            if (dpm.isAdminActive(admin)) {
                dpm.lockNow()
                sendBroadcast(Intent("com.proactive.presence.LOCKED").putExtra("reason", reason))
                // Reset so we don't spam lock calls.
                lastOwnerSeen = System.currentTimeMillis()
                strangerStrikes = 0
            }
        } catch (e: Exception) {
            Log.w("Guard", "lockNow failed", e)
        }
    }

    override fun onDestroy() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        try { detector.close() } catch (_: Exception) {}
        verifier.close()
        exec.shutdownNow()
        super.onDestroy()
    }

    // --- small bitmap helpers (kept local to avoid extra files) ---
    private fun ImageProxy.toBitmap(): Bitmap? {
        return try {
            val y = planes[0].buffer
            val u = planes[1].buffer
            val v = planes[2].buffer
            val ySize = y.remaining()
            val uSize = u.remaining()
            val vSize = v.remaining()
            val nv21 = ByteArray(ySize + uSize + vSize)
            y.get(nv21, 0, ySize)
            v.get(nv21, ySize, vSize)
            u.get(nv21, ySize + vSize, uSize)
            val yuv = android.graphics.YuvImage(
                nv21, android.graphics.ImageFormat.NV21, width, height, null
            )
            val out = java.io.ByteArrayOutputStream()
            yuv.compressToJpeg(android.graphics.Rect(0, 0, width, height), 80, out)
            val bytes = out.toByteArray()
            var bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val rot = imageInfo.rotationDegrees
            if (rot != 0 && bmp != null) {
                val m = android.graphics.Matrix().apply { postRotate(rot.toFloat()) }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            bmp
        } catch (_: Exception) {
            null
        }
    }

    private fun cropFace(bmp: Bitmap, box: android.graphics.Rect): Bitmap? {
        return try {
            val l = box.left.coerceIn(0, bmp.width - 1)
            val t = box.top.coerceIn(0, bmp.height - 1)
            val r = box.right.coerceIn(l + 1, bmp.width)
            val b = box.bottom.coerceIn(t + 1, bmp.height)
            val w = (r - l).coerceAtLeast(16)
            val h = (b - t).coerceAtLeast(16)
            val sq = kotlin.math.max(w, h)
            val cx = (l + w / 2).coerceIn(sq / 2, bmp.width - sq / 2)
            val cy = (t + h / 2).coerceIn(sq / 2, bmp.height - sq / 2)
            Bitmap.createBitmap(bmp, cx - sq / 2, cy - sq / 2, sq, sq)
        } catch (_: Exception) {
            null
        }
    }
}
