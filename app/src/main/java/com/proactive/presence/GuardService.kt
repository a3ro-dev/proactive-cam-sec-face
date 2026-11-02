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

        @Volatile var running = false
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
        running = true
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
        val now = System.currentTimeMillis()
        val media = proxy.image
        if (now - lastTick < TICK_MS || media == null) { proxy.close(); return }
        lastTick = now
        // The proxy must stay open until ML Kit and the crop are done with it.
        detector.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
            .addOnSuccessListener(exec) { faces ->
                try {
                    val biggest = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                    if (biggest == null) {
                        Log.i("Guard", "tick: no face")
                        checkAbsent(now)
                    } else if (!verifier.hasOwner()) {
                        onOwnerSeen()
                    } else {
                        val bmp = FaceCrop.fromProxy(proxy)
                        val crop = bmp?.let { FaceCrop.square(it, biggest.boundingBox) }
                        val sim = crop?.let { verifier.similarity(it) }
                        Log.i("Guard", "tick: face sim=$sim thr=${verifier.threshold}")
                        // No model/embedding (sim null): presence mode, any face is the owner.
                        if (sim == null || sim >= verifier.threshold) onOwnerSeen() else onStranger(now)
                    }
                } finally {
                    proxy.close()
                }
            }
            .addOnFailureListener(exec) { e ->
                Log.w("Guard", "detect failed", e)
                proxy.close()
                checkAbsent(now)
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
        running = false
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        try { detector.close() } catch (_: Exception) {}
        verifier.close()
        exec.shutdownNow()
        super.onDestroy()
    }
}
