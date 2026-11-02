package com.proactive.presence

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt
import org.json.JSONObject

/**
 * In-app enrollment: turns the guided 30s video into owner.json.
 * Same math as tools/enroll.py (laptop fallback), same MobileFaceNet file,
 * same normalization — either path produces an identical profile.
 *
 * Needs assets/mobilefacenet.tflite. Without it, enrollment refuses with a
 * plain sentence instead of a broken profile.
 */
object EnrollHelper {

    data class Result(val faces: Int, val minSim: Float, val meanSim: Float)

    fun buildProfile(
        context: Context,
        verifier: FaceVerifier,
        videoPath: String,
        onStep: (done: Int, total: Int) -> Unit,
    ): Result {
        if (!verifier.modelPresent()) {
            throw IllegalStateException("The face model is missing from this build.")
        }
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(videoPath)
        val durMs = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_DURATION
        )?.toLongOrNull() ?: 30_000L
        // Encoded frames are often landscape with a rotation tag the
        // retriever ignores. Upright the face before anyone looks at it.
        val rotation = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION
        )?.toIntOrNull() ?: 0

        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setMinFaceSize(0.2f)
                .build()
        )
        try {
            val picks = 60
            val vecs = mutableListOf<FloatArray>()
            for (i in 0 until picks) {
                val tUs = (durMs * 1000 * i / picks).coerceAtLeast(0)
                var frame: Bitmap? = try {
                    retriever.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST)
                } catch (_: Exception) {
                    null
                }
                if (frame != null && rotation != 0) {
                    val m = android.graphics.Matrix().apply { postRotate(rotation.toFloat()) }
                    frame = Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, m, true)
                }
                if (frame != null) {
                    embedFrame(detector, verifier, frame)?.let { vecs += it }
                    if (!frame.isRecycled) frame.recycle()
                }
                onStep(i + 1, picks)
            }
            if (vecs.size < 10) {
                throw IllegalStateException(
                    "Only ${vecs.size} usable faces. Re-shoot in daylight, slower turns."
                )
            }
            val dim = vecs[0].size
            val mean = FloatArray(dim)
            for (v in vecs) for (d in 0 until dim) mean[d] += v[d] / vecs.size
            var n = 0f
            for (x in mean) n += x * x
            n = sqrt(n) + 1e-9f
            for (d in 0 until dim) mean[d] /= n

            var min = 1f
            var sum = 0f
            for (v in vecs) {
                var dot = 0f
                for (d in 0 until dim) dot += v[d] * mean[d]
                if (dot < min) min = dot
                sum += dot
            }
            OwnerStore.write(
                context,
                JSONObject()
                    .put("mean", org.json.JSONArray(mean.toList()))
                    .put("threshold", 0.62)
                    .put("faces", vecs.size)
                    .put("source", "in-app")
                    .toString()
            )
            verifier.reload()
            // The raw video has served its purpose; the profile is all we keep.
            try { File(videoPath).delete() } catch (_: Exception) {}
            return Result(vecs.size, min, sum / vecs.size)
        } finally {
            try { detector.close() } catch (_: Exception) {}
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun embedFrame(
        detector: com.google.mlkit.vision.face.FaceDetector,
        verifier: FaceVerifier,
        frame: Bitmap,
    ): FloatArray? {
        return try {
            val latch = CountDownLatch(1)
            var vec: FloatArray? = null
            detector.process(InputImage.fromBitmap(frame, 0))
                .addOnSuccessListener { faces ->
                    val biggest = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                    if (biggest != null) {
                        val crop = FaceCrop.square(frame, biggest.boundingBox)
                        if (crop != null) vec = verifier.embed(crop)
                    }
                    latch.countDown()
                }
                .addOnFailureListener { latch.countDown() }
            latch.await(8, TimeUnit.SECONDS)
            vec
        } catch (e: Exception) {
            Log.w("Enroll", "frame skipped", e)
            null
        }
    }
}
