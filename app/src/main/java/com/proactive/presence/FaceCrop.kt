package com.proactive.presence

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/** Shared bitmap math: ImageProxy -> Bitmap, largest-face square crop. */
object FaceCrop {

    fun fromProxy(proxy: ImageProxy): Bitmap? {
        return try {
            val y = proxy.planes[0].buffer
            val u = proxy.planes[1].buffer
            val v = proxy.planes[2].buffer
            val nv21 = ByteArray(y.remaining() + u.remaining() + v.remaining())
            y.get(nv21, 0, y.remaining())
            v.get(nv21, y.remaining(), v.remaining())
            u.get(nv21, y.remaining() + v.remaining(), u.remaining())
            val yuv = YuvImage(nv21, ImageFormat.NV21, proxy.width, proxy.height, null)
            val out = ByteArrayOutputStream()
            yuv.compressToJpeg(Rect(0, 0, proxy.width, proxy.height), 80, out)
            val bytes = out.toByteArray()
            var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val rot = proxy.imageInfo.rotationDegrees
            if (rot != 0) {
                val m = Matrix().apply { postRotate(rot.toFloat()) }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            }
            bmp
        } catch (_: Exception) {
            null
        }
    }

    /** Square crop around [box], clamped. Null when the box is unusable. */
    fun square(bmp: Bitmap, box: Rect): Bitmap? {
        return try {
            val l = box.left.coerceIn(0, bmp.width - 1)
            val t = box.top.coerceIn(0, bmp.height - 1)
            val r = box.right.coerceIn(l + 1, bmp.width)
            val b = box.bottom.coerceIn(t + 1, bmp.height)
            val side = kotlin.math.max(r - l, b - t).coerceAtLeast(16)
            val cx = (l + (r - l) / 2).coerceIn(side / 2, bmp.width - side / 2)
            val cy = (t + (b - t) / 2).coerceIn(side / 2, bmp.height - side / 2)
            Bitmap.createBitmap(bmp, cx - side / 2, cy - side / 2, side, side)
        } catch (_: Exception) {
            null
        }
    }
}
