package com.example.reviva.presentation.camera

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.util.Log
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

object ImageUtils {

    /**
     * Converts ImageProxy (YUV420_888) to Bitmap using NV21 format
     * Compatible with all Android versions including API 33+
     */
    fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            val format = imageProxy.format
            val width = imageProxy.width
            val height = imageProxy.height

            if (width <= 0 || height <= 0) {
                Log.w("ImageUtils", "Invalid dimensions: ${width}x$height")
                imageProxy.close()
                return null
            }

            // Convert YUV_420_888 to NV21 format
            val nv21Data = yuv420ToNv21(imageProxy)
            if (nv21Data == null) {
                Log.e("ImageUtils", "Failed to convert YUV420 to NV21")
                return null
            }

            // Create YuvImage with NV21 format (supported format!)
            val yuvImage = android.graphics.YuvImage(
                nv21Data,
                ImageFormat.NV21,  // ✅ Using NV21 instead of YUV_420_888
                width,
                height,
                null
            )

            // Convert to Bitmap
            val out = java.io.ByteArrayOutputStream()
            val compressed = yuvImage.compressToJpeg(android.graphics.Rect(0, 0, width, height), 100, out)

            if (!compressed) {
                Log.e("ImageUtils", "Failed to compress YUV to JPEG")
                return null
            }

            val imageBytes = out.toByteArray()
            out.close()

            val bitmap = android.graphics.BitmapFactory.decodeByteArray(
                imageBytes,
                0,
                imageBytes.size
            )

            if (bitmap == null) {
                Log.e("ImageUtils", "Failed to decode JPEG bytes to bitmap")
            } else {
                Log.d("ImageUtils", "Successfully converted ImageProxy to bitmap (${width}x${height})")
            }

            bitmap

        } catch (e: Exception) {
            Log.e("ImageUtils", "Error converting ImageProxy to Bitmap: ${e.message}", e)
            null
        }
    }

    /**
     * Convert YUV_420_888 to NV21 byte array
     * This is the KEY FIX for API 33+ compatibility
     */
    private fun yuv420ToNv21(imageProxy: ImageProxy): ByteArray? {
        return try {
            val planes = imageProxy.planes
            if (planes.size < 3) {
                Log.e("ImageUtils", "Invalid number of planes: ${planes.size}")
                return null
            }

            val width = imageProxy.width
            val height = imageProxy.height
            val pixelStride = planes[1].pixelStride

            // Y plane
            val yPlane = planes[0]
            val ySize = yPlane.buffer.remaining()
            val yData = ByteArray(ySize)
            yPlane.buffer.get(yData)

            // U and V planes
            val uPlane = planes[1]
            val vPlane = planes[2]

            val uvPixelStride = uPlane.pixelStride
            if (uvPixelStride == 1) {
                // Already in correct format
                val uvSize = uPlane.buffer.remaining() + vPlane.buffer.remaining()
                val nv21 = ByteArray(ySize + uvSize)
                System.arraycopy(yData, 0, nv21, 0, ySize)

                val uData = ByteArray(uPlane.buffer.remaining())
                uPlane.buffer.get(uData)
                val vData = ByteArray(vPlane.buffer.remaining())
                vPlane.buffer.get(vData)

                System.arraycopy(vData, 0, nv21, ySize, vData.size)
                System.arraycopy(uData, 0, nv21, ySize + vData.size, uData.size)

                nv21
            } else if (uvPixelStride == 2) {
                // Interleaved format - need to de-interleave
                val uvBuffer = uPlane.buffer
                val uvSize = uvBuffer.remaining()
                val nv21 = ByteArray(ySize + uvSize)
                System.arraycopy(yData, 0, nv21, 0, ySize)

                val uvData = ByteArray(uvSize)
                uvBuffer.get(uvData)

                // De-interleave UV data
                var uvIndex = ySize
                for (i in 0 until uvSize step 2) {
                    if (i + 1 < uvSize) {
                        // Swap U and V to get NV21 format
                        nv21[uvIndex] = uvData[i + 1]  // V
                        nv21[uvIndex + 1] = uvData[i]  // U
                        uvIndex += 2
                    }
                }

                nv21
            } else {
                Log.e("ImageUtils", "Unsupported pixel stride: $uvPixelStride")
                null
            }

        } catch (e: Exception) {
            Log.e("ImageUtils", "Error converting YUV420 to NV21: ${e.message}", e)
            null
        }
    }

    /**
     * Resize bitmap to specific dimensions
     */
    fun resizeBitmap(bitmap: Bitmap, width: Int, height: Int): Bitmap {
        return if (bitmap.width == width && bitmap.height == height) {
            bitmap
        } else {
            try {
                Bitmap.createScaledBitmap(bitmap, width, height, true)
                    .also { Log.d("ImageUtils", "Resized bitmap to ${width}x$height") }
            } catch (e: Exception) {
                Log.e("ImageUtils", "Error resizing bitmap: ${e.message}", e)
                bitmap
            }
        }
    }

    /**
     * Convert Bitmap to ByteBuffer for model input
     */
    fun bitmapToByteBuffer(bitmap: Bitmap, inputSize: Int = 640): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3)
        byteBuffer.order(java.nio.ByteOrder.nativeOrder())

        val intValues = IntArray(inputSize * inputSize)
        val resized = if (bitmap.width != inputSize || bitmap.height != inputSize) {
            Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        } else {
            bitmap
        }

        try {
            resized.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)

            for (i in intValues.indices) {
                val v = intValues[i]
                byteBuffer.putFloat(((v shr 16) and 0xFF) / 255f)
                byteBuffer.putFloat(((v shr 8) and 0xFF) / 255f)
                byteBuffer.putFloat((v and 0xFF) / 255f)
            }
            byteBuffer.rewind()
        } catch (e: Exception) {
            Log.e("ImageUtils", "Error converting bitmap to byte buffer: ${e.message}", e)
        }

        return byteBuffer
    }

    /**
     * Check if bitmap is valid
     */
    fun isBitmapValid(bitmap: Bitmap?): Boolean {
        return bitmap != null && bitmap.width > 0 && bitmap.height > 0 && !bitmap.isRecycled
    }

    /**
     * Safely recycle bitmap
     */
    fun recycleBitmap(bitmap: Bitmap?) {
        try {
            if (bitmap != null && !bitmap.isRecycled) {
                bitmap.recycle()
            }
        } catch (e: Exception) {
            Log.e("ImageUtils", "Error recycling bitmap: ${e.message}")
        }
    }
}