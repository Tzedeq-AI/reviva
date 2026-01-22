package com.example.reviva.presentation.camera

import android.graphics.Bitmap
import android.graphics.*
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream


object ImageUtils {

    fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        return try {
            if (image.format == ImageFormat.YUV_420_888) {
                yuvToBitmap(image)
            } else {
                val plane = image.planes.firstOrNull() ?: return null
                plane.buffer.rewind()
                val bytes = ByteArray(plane.buffer.remaining())
                plane.buffer.get(bytes)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (e: Exception) {
            android.util.Log.e("ImageUtils", "imageProxyToBitmap failed", e)
            null
        }
    }

    private fun yuvToBitmap(image: ImageProxy): Bitmap? {
        return try {
            val yBuffer = image.planes[0].buffer
            val uBuffer = image.planes[1].buffer
            val vBuffer = image.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)

            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage = YuvImage(
                nv21,
                ImageFormat.NV21,
                image.width,
                image.height,
                null
            )

            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 95, out)
            val jpegBytes = out.toByteArray()

            BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)

        } catch (e: Exception) {
            android.util.Log.e("ImageUtils", "yuvToBitmap failed", e)
            null
        }
    }
}
