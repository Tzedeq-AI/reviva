package com.example.reviva.presentation.camera

import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.PointF
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.View
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.hilt.navigation.fragment.hiltNavGraphViewModels
import com.example.reviva.R
import com.example.reviva.data.cv.ClassicalPhotoDetector
import com.example.reviva.data.cv.PhotoSegmentationProcessor
import com.example.reviva.data.cv.PhotoBorder
import com.example.reviva.data.ml.YoloTFLiteDetector
import com.example.reviva.data.ml.YoloSegResult
import com.example.reviva.presentation.camera.state.ScanFlowViewModel
import org.opencv.android.OpenCVLoader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class LiveScanFragment : Fragment(R.layout.frag_livescan) {
    private val scanViewModel: ScanFlowViewModel by hiltNavGraphViewModels(R.id.app_nav_graph)
    private var yoloDetector: YoloTFLiteDetector? = null
    private var analysisExecutor: ExecutorService? = null
    private var previewView: PreviewView? = null
    private var overlay: OverlayView? = null
    private var lastDetectMs = 0L
    private val detectionIntervalMs = 200L
    private var cameraProvider: ProcessCameraProvider? = null
    @Volatile private var detectorActive = false
    private val isProcessing = AtomicBoolean(false)

    private var currentPhotoId = 0
    private val photoImageViews = mutableMapOf<String, ImageView>()

    // detection mode flag
    private var useClassicalDetector = true

    // speed: only run classical detector every N frames to reduce CPU
    private val boxStabilizer = com.example.reviva.data.cv.BoxStabilizer(
        iouMatchThreshold = 0.35f,
        alpha = 0.75f,
        maxMissFrames = 1
    )


    // speed: only run classical detector every N frames to reduce CPU
    private val CLASSICAL_FRAME_SKIP = 2
    private var classicalFrameCounter = 0

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            imageAnalysis?.clearAnalyzer()
            detectorActive = false

            val startWait = System.currentTimeMillis()
            while (isProcessing.get() && System.currentTimeMillis() - startWait < 500) {
                Thread.sleep(10)
            }

            cameraProvider?.unbindAll()

            analysisExecutor?.shutdown()
            analysisExecutor?.awaitTermination(500, TimeUnit.MILLISECONDS)
            analysisExecutor = null

            yoloDetector?.close()
            yoloDetector = null

            imageAnalysis = null
            cameraProvider = null
            previewView = null
            overlay = null
            photoImageViews.clear()
        } catch (e: Exception) {
            Log.e("LiveScanFragment", "Cleanup error", e)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        try {
            previewView = view.findViewById(R.id.previewView)
            overlay = view.findViewById(R.id.overlay)
            previewView?.scaleType = PreviewView.ScaleType.FIT_CENTER

            val modeSpinner = view.findViewById<Spinner>(R.id.detectionModeSpinner)
            val modes = listOf("YOLO Segmentation", "Classical CV")
            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, modes)
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            modeSpinner.adapter = adapter
            modeSpinner.setSelection(0)

            modeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    useClassicalDetector = (position == 1)
                    classicalFrameCounter = 0
                    Log.d("LiveScanFragment", "Detection mode = ${modes[position]}")
                }

                override fun onNothingSelected(parent: AdapterView<*>?) { }
            }


            if (analysisExecutor == null) analysisExecutor = Executors.newSingleThreadExecutor()
            scanViewModel.setStatus("Scanning for photos...")

            if (!OpenCVLoader.initDebug()) {
                Log.w("LiveScanFragment", "OpenCV init failed")
            } else {
                Log.d("LiveScanFragment", "OpenCV initialized")
            }

            try {
                yoloDetector = YoloTFLiteDetector(
                    context = requireContext(),
                    modelAssetPath = "best_float16.tflite",
                    inputSize = 640
                )
                detectorActive = true
                Log.d("LiveScanFragment", "YOLO detector initialized successfully")
            } catch (e: Exception) {
                Log.e("LiveScanFragment", "Failed to initialize YOLO detector", e)
                scanViewModel.setStatus("Error: Failed to load detection model")
                detectorActive = false
                return
            }

            startCamera()

            view.findViewById<View>(R.id.captureBtn)?.setOnClickListener {
                scanViewModel.incrementCaptured()
                Log.d("LiveScanFragment", "Photo captured")
            }

            view.findViewById<View>(R.id.nextBtn)?.setOnClickListener {
                Log.d("LiveScanFragment", "Next button clicked")
            }
        } catch (e: Exception) {
            Log.e("LiveScanFragment", "Error in onViewCreated", e)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                cameraProvider?.let { bindCameraUseCases(it) }
            } catch (e: Exception) {
                Log.e("LiveScanFragment", "Failed to get camera provider", e)
                scanViewModel.setStatus("Camera error: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private var imageAnalysis: ImageAnalysis? = null

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases(cameraProvider: ProcessCameraProvider) {
        val executor = analysisExecutor ?: return
        val previewViewRef = previewView ?: return

        try {
            val currentRotation = previewViewRef.display?.rotation ?: Surface.ROTATION_0
            Log.d("LiveScanFragment", "Binding camera with rotation: $currentRotation")

            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                )
                .build()

            val preview = Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .setTargetRotation(currentRotation)
                .build()

            val imageAnalysisBuilder = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setTargetRotation(currentRotation)

            val imageAnalysis = imageAnalysisBuilder.build()
            this.imageAnalysis = imageAnalysis

            imageAnalysis.setAnalyzer(executor) { imageProxy ->
                processImageFrame(imageProxy, previewViewRef)
            }

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()

            try { cameraProvider.unbindAll() } catch (_: Exception) {}

            preview.setSurfaceProvider(previewViewRef.surfaceProvider)
            cameraProvider.bindToLifecycle(viewLifecycleOwner, cameraSelector, preview, imageAnalysis)
            Log.d("LiveScanFragment", "Camera bound successfully with rotation: $currentRotation")
        } catch (e: Exception) {
            Log.e("LiveScanFragment", "Failed to bind camera use cases", e)
            scanViewModel.setStatus("Camera binding error: ${e.message}")
        }
    }

    private fun rotatePointForDegrees(point: PointF, degrees: Int, width: Int, height: Int): PointF {
        return when ((degrees % 360 + 360) % 360) {
            0 -> PointF(point.x, point.y)
            90 -> PointF(height - point.y, point.x)
            180 -> PointF(width - point.x, height - point.y)
            270 -> PointF(point.y, width - point.x)
            else -> PointF(point.x, point.y)
        }
    }

    private fun processImageFrame(imageProxy: ImageProxy, previewViewRef: PreviewView) {
        val start = System.currentTimeMillis()
        if (!isProcessing.compareAndSet(false, true)) {
            try { imageProxy.close() } catch (e: Exception) { Log.e("LiveScanFragment","close busy",e) }
            return
        }

        try {
            val now = System.currentTimeMillis()
            if (now - lastDetectMs < detectionIntervalMs) {
                try { imageProxy.close() } catch (_: Exception) {}
                isProcessing.set(false)
                return
            }
            lastDetectMs = now

            if (!detectorActive) {
                Log.w("LiveScanFragment", "Detector not active, skipping frame")
                try { imageProxy.close() } catch (_: Exception) {}
                isProcessing.set(false)
                return
            }

            val bitmap = ImageUtils.imageProxyToBitmap(imageProxy)
            if (bitmap == null) {
                Log.w("LiveScanFragment", "Failed to convert image proxy to bitmap")
                try { imageProxy.close() } catch (_: Exception) {}
                isProcessing.set(false)
                return
            }

            // scaled used only for YOLO. Avoid creating if we will use classical detector
            val scaled: Bitmap? = if (!useClassicalDetector) {
                Bitmap.createScaledBitmap(bitmap, 640, 640, true)
            } else null

            val photoBorders: List<PhotoBorder> = try {
                if (useClassicalDetector) {
                    // Skip every N frames to save CPU
                    classicalFrameCounter = (classicalFrameCounter + 1) % CLASSICAL_FRAME_SKIP
                    if (classicalFrameCounter != 0) {
                        emptyList()
                    } else {
                        ClassicalPhotoDetector.detectPhotoPolygons(bitmap)
                    }
                } else {
                    val detector = yoloDetector
                    if (detector == null || scaled == null) {
                        Log.w("LiveScanFragment", "YOLO detector not initialized or scaled bitmap is null")
                        emptyList()
                    } else {
                        val segResult: YoloSegResult? = try { detector.detectSeg(scaled) } catch (e: Exception) {
                            Log.e("LiveScanFragment", "Segmentation failed", e)
                            null
                        }
                        if (segResult == null || segResult.detections.isEmpty() || segResult.proto == null) {
                            emptyList()
                        } else {
                            PhotoSegmentationProcessor.processPhotoBorders(frameBitmap = bitmap, proto = segResult.proto, detections = segResult.detections)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("LiveScanFragment", "Photo processing failed", e)
                emptyList()
            }

            // If no results and we used classical but skipped this frame, clear overlay
            if (photoBorders.isEmpty()) {
                overlay?.post {
                    overlay?.setBoxes(
                        emptyList(),
                        bitmap.width,
                        bitmap.height,
                        emptyList(),
                        emptyList(),
                        emptyList(),
                        emptyList(),
                        false,
                        previewViewRef.width,
                        previewViewRef.height
                    )
                }
            }

            val rotationDegrees = imageProxy.imageInfo.rotationDegrees

            val previewViewWidth = previewViewRef.width.toFloat()
            val previewViewHeight = previewViewRef.height.toFloat()

            if (previewViewWidth <= 0 || previewViewHeight <= 0) {
                Log.w("LiveScanFragment", "Invalid preview view dimensions")
                try { scaled?.recycle() } catch (_: Exception) {}
                try { imageProxy.close() } catch (_: Exception) {}
                isProcessing.set(false)
                return
            }

            val desiredAspect = if (previewViewHeight >= previewViewWidth) 3f / 4f else 4f / 3f
            val visibleWidth: Float
            val visibleHeight: Float
            val offsetX: Float
            val offsetY: Float
            val viewAspect = previewViewWidth / previewViewHeight

            if (viewAspect > desiredAspect) {
                visibleHeight = previewViewHeight
                visibleWidth = previewViewHeight * desiredAspect
                offsetX = (previewViewWidth - visibleWidth) / 2f
                offsetY = 0f
            } else {
                visibleWidth = previewViewWidth
                visibleHeight = previewViewWidth / desiredAspect
                offsetX = 0f
                offsetY = (previewViewHeight - visibleHeight) / 2f
            }

            val bitmapWidth = bitmap.width.toFloat()
            val bitmapHeight = bitmap.height.toFloat()
            val rotationNormalized = ((rotationDegrees % 360) + 360) % 360
            val srcRotatedWidth = if (rotationNormalized == 90 || rotationNormalized == 270) bitmapHeight else bitmapWidth
            val srcRotatedHeight = if (rotationNormalized == 90 || rotationNormalized == 270) bitmapWidth else bitmapHeight
            val scaleX = if (srcRotatedWidth > 0) visibleWidth / srcRotatedWidth else 1f
            val scaleY = if (srcRotatedHeight > 0) visibleHeight / srcRotatedHeight else 1f

            val scaledCornersList = photoBorders.map { border ->
                border.corners.map { corner ->
                    val rotated = rotatePointForDegrees(corner, rotationDegrees, bitmap.width, bitmap.height)
                    PointF((rotated.x * scaleX) + offsetX, (rotated.y * scaleY) + offsetY)
                }
            }

            // Stabilize polygons across frames
            val stabilizedCorners = try {
                boxStabilizer.stabilize(scaledCornersList)
            } catch (e: Exception) {
                // in case of any stabilizer error, fall back to raw
                scaledCornersList
            }

            val allProper = photoBorders.all { it.detectionQuality == 0 }

            overlay?.post {
                overlay?.setBoxes(
                    emptyList(),
                    bitmap.width,
                    bitmap.height,
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    stabilizedCorners,
                    allProper,
                    previewViewRef.width,
                    previewViewRef.height
                )
            }

            // display crops (classical detector produces crops; segmentation already did)
            displayPhotoCrops(photoBorders)

            val took = System.currentTimeMillis() - start
            if (took > 50) Log.d("PHOTO-SCAN", "Found ${photoBorders.size} photos, took ${took}ms")

            try { scaled?.recycle() } catch (_: Exception) {}
            try { imageProxy.close() } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.e("LiveScanFragment", "Processing error", e)
            try { imageProxy.close() } catch (_: Exception) {}
        } finally {
            isProcessing.set(false)
        }
    }

    private fun displayPhotoCrops(borders: List<PhotoBorder>) {
        try {
            val activity = activity ?: return
            activity.runOnUiThread {
                try {
                    val container = view?.findViewById<LinearLayout>(R.id.photoContainer)
                    container?.let {
                        it.removeAllViews()
                        photoImageViews.clear()
                        currentPhotoId = 0

                        borders.forEachIndexed { _, border ->
                            border.crop?.let { cropBitmap ->
                                val winName = "photo$currentPhotoId"

                                // scale down for UI
                                var displayBitmap = if (cropBitmap.width > 300 || cropBitmap.height > 300) {
                                    val scale = 300f / maxOf(cropBitmap.width, cropBitmap.height)
                                    Bitmap.createScaledBitmap(cropBitmap, (cropBitmap.width * scale).toInt(), (cropBitmap.height * scale).toInt(), true)
                                } else {
                                    cropBitmap
                                }

                                // If YOLO produced this crop, flip horizontally to match expected preview
                                if (!useClassicalDetector) {
                                    try {
                                        val matrix = android.graphics.Matrix().apply { preScale(-1f, 1f) }
                                        displayBitmap = Bitmap.createBitmap(displayBitmap, 0, 0, displayBitmap.width, displayBitmap.height, matrix, true)
                                    } catch (e: Exception) {
                                        // fallback: keep unflipped
                                    }
                                }


                                val imageView = ImageView(requireContext()).apply {
                                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                                        setMargins(8, 8, 8, 8)
                                    }
                                    setImageBitmap(displayBitmap)
                                    scaleType = ImageView.ScaleType.CENTER_CROP
                                    setBackgroundResource(R.drawable.photo_frame)
                                    elevation = 12f
                                    setOnClickListener { /* Save photo logic */ }
                                }

                                it.addView(imageView)
                                photoImageViews[winName] = imageView
                                currentPhotoId++
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("LiveScanFragment", "Error updating UI", e)
                }
            }
        } catch (e: Exception) {
            Log.e("LiveScanFragment", "Failed to display photo crops", e)
        }
    }
}
