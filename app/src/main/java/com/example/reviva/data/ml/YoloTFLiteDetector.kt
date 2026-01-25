package com.example.reviva.data.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.reviva.presentation.camera.OverlayView
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

data class YBox(
    val box: OverlayView.Box,
    val score: Float,
    val classId: Int = 0,
    val maskCoeff: FloatArray? = null
)

data class YoloSegResult(
    val detections: List<YBox>,
    val proto: Array<Array<FloatArray>>? = null  // [160][160][32]
)

class YoloTFLiteDetector(
    context: Context,
    modelAssetPath: String = "best_float16.tflite",
    private val inputSize: Int = 640,
    private val numThreads: Int = 4,
    private val scoreThreshold: Float = 0.25f
) {
    private val interpreter: Interpreter
    private var gpuDelegate: Any? = null
    private val DEBUG = true
    private val TAG = "YoloTFLiteDetector"

    init {
        val bb = loadModelFile(context, modelAssetPath)
        val opts = Interpreter.Options().apply {
            setNumThreads(numThreads)

            // ✅ SAFE GPU delegate initialization with comprehensive error handling
            try {
                Log.d(TAG, "Attempting GPU delegate initialization...")

                // Try to initialize GPU delegate safely
                gpuDelegate = tryInitializeGpuDelegate()

                if (gpuDelegate != null) {
                    try {
                        // Use reflection to safely call addDelegate
                        val addDelegateMethod = Interpreter.Options::class.java.getMethod(
                            "addDelegate",
                            Any::class.java
                        )
                        addDelegateMethod.invoke(this, gpuDelegate)
                        Log.d(TAG, "✅ GPU delegate added successfully")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to add GPU delegate via reflection: ${e.message}")
                        gpuDelegate = null
                        // Continue with CPU only
                    }
                } else {
                    Log.d(TAG, "GPU delegate not available, using CPU only")
                }
            } catch (e: Exception) {
                Log.w(TAG, "GPU delegate initialization failed: ${e.message}", e)
                gpuDelegate = null
                // Continue with CPU - this is acceptable
            }
        }

        interpreter = Interpreter(bb, opts)
        Log.d(TAG, "✅ TFLite interpreter initialized successfully")
    }

    /**
     * Try to initialize GPU delegate safely
     * Returns null if not available or if class loading fails
     */
    private fun tryInitializeGpuDelegate(): Any? {
        return try {
            // Check if GPU delegate is available
            val compatListClass = try {
                Class.forName("org.tensorflow.lite.gpu.CompatibilityList")
            } catch (e: ClassNotFoundException) {
                Log.d(TAG, "CompatibilityList not found, GPU support unavailable")
                return null
            }

            val compatList = compatListClass.newInstance()
            val isDelegateSupported = compatListClass.getMethod("isDelegateSupportedOnThisDevice")
                .invoke(compatList) as? Boolean ?: false

            if (!isDelegateSupported) {
                Log.d(TAG, "GPU delegate not supported on this device")
                return null
            }

            // Try to create GpuDelegate
            try {
                val gpuDelegateClass = Class.forName("org.tensorflow.lite.gpu.GpuDelegate")
                gpuDelegateClass.getConstructor().newInstance()
            } catch (e: ClassNotFoundException) {
                Log.d(TAG, "GpuDelegate class not found: ${e.message}")
                null
            } catch (e: NoSuchMethodException) {
                Log.d(TAG, "GpuDelegate constructor not found: ${e.message}")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize GPU delegate: ${e.message}", e)
            null
        }
    }

    private fun loadModelFile(context: Context, assetPath: String): ByteBuffer {
        return try {
            val afd = context.assets.openFd(assetPath)
            val input = FileInputStream(afd.fileDescriptor)
            val fc = input.channel
            val m = fc.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            m.order(ByteOrder.nativeOrder())
            afd.close()
            input.close()
            m
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model file: $assetPath", e)
            throw e
        }
    }

    fun detectSeg(bitmap: Bitmap): YoloSegResult? {
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            Log.w(TAG, "Invalid bitmap dimensions: ${bitmap.width}x${bitmap.height}")
            return null
        }

        val inputBmp = if (bitmap.width == inputSize && bitmap.height == inputSize)
            bitmap
        else
            Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)

        val inputBuf = convertBitmapToByteBuffer(inputBmp)

        // Outputs based on model architecture
        val detOut = Array(1) { Array(300) { FloatArray(38) } }
        val protoOut = Array(1) { Array(160) { Array(160) { FloatArray(32) } } }

        val outputs = HashMap<Int, Any>()
        outputs[0] = detOut
        outputs[1] = protoOut

        return try {
            interpreter.runForMultipleInputsOutputs(arrayOf(inputBuf), outputs)

            val det = detOut[0]
            val proto = protoOut[0]
            val found = ArrayList<YBox>()

            for (i in det.indices) {
                val row = det[i]

                // Check if detection is valid (not all zeros)
                if (row.all { it == 0f }) continue

                val x = row[0]
                val y = row[1]
                val w = row[2]
                val h = row[3]
                val obj = row[4]
                val cls = row[5]

                val score = obj * cls
                if (score < scoreThreshold) continue

                val left = ((x - w / 2f) * inputSize).coerceIn(0f, inputSize.toFloat())
                val top = ((y - h / 2f) * inputSize).coerceIn(0f, inputSize.toFloat())
                val right = ((x + w / 2f) * inputSize).coerceIn(0f, inputSize.toFloat())
                val bottom = ((y + h / 2f) * inputSize).coerceIn(0f, inputSize.toFloat())

                // Safely extract coefficients
                val coeffs = if (row.size > 38) {
                    row.copyOfRange(6, minOf(38, row.size))
                } else {
                    row.copyOfRange(6, row.size)
                }

                if (DEBUG && found.size < 3) {
                    Log.d(
                        "YOLO-SEG",
                        "i=$i score=${String.format("%.3f", score)} " +
                                "box=[${left.toInt()},${top.toInt()},${right.toInt()},${bottom.toInt()}]"
                    )
                }

                found.add(
                    YBox(
                        OverlayView.Box(left, top, right, bottom),
                        score,
                        0,
                        coeffs
                    )
                )
            }

            found.sortByDescending { it.score }
            YoloSegResult(found.take(10), proto)  // Limit to top 10
        } catch (e: Exception) {
            Log.e(TAG, "Segmentation inference failed", e)
            YoloSegResult(emptyList(), null)
        }
    }

    fun detect(bitmap: Bitmap): List<YBox> {
        return detectSeg(bitmap)?.detections ?: emptyList()
    }

    private fun convertBitmapToByteBuffer(bitmap: Bitmap): ByteBuffer {
        val byteBuffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3)
        byteBuffer.order(ByteOrder.nativeOrder())
        val intValues = IntArray(inputSize * inputSize)

        try {
            bitmap.getPixels(intValues, 0, inputSize, 0, 0, inputSize, inputSize)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get pixels from bitmap", e)
            throw e
        }

        var i = 0
        for (y in 0 until inputSize) {
            for (x in 0 until inputSize) {
                val v = intValues[i++]
                byteBuffer.putFloat(((v shr 16) and 0xFF) / 255f)
                byteBuffer.putFloat(((v shr 8) and 0xFF) / 255f)
                byteBuffer.putFloat((v and 0xFF) / 255f)
            }
        }
        byteBuffer.rewind()
        return byteBuffer
    }

    fun close() {
        try {
            // Try to close GPU delegate safely
            if (gpuDelegate != null) {
                try {
                    val closeMethod = gpuDelegate!!::class.java.getMethod("close")
                    closeMethod.invoke(gpuDelegate)
                    Log.d(TAG, "GPU delegate closed")
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing GPU delegate: ${e.message}")
                }
            }

            interpreter.close()
            Log.d(TAG, "Detector closed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing detector", e)
        }
    }
}