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
                        // Largest face, square-cropped for the embedding.
                        val biggest = faces.maxByOrNull {
                            it.boundingBox.width() * it.boundingBox.height()
                        }!!
                        val bmp = FaceCrop.fromProxy(proxy)
                        if (bmp != null && verifier.hasOwner()) {
                            val crop = FaceCrop.square(bmp, biggest.boundingBox)
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
        sendBroadcast(Intent("com.proactive.presence.OWNER_SEEN").setPackage(packageName))
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
                sendBroadcast(
                    Intent("com.proactive.presence.LOCKED")
                        .putExtra("reason", reason)
                        .setPackage(packageName)
                )
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
}
