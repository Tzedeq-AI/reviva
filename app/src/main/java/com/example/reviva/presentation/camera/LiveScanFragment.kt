package com.example.reviva.presentation.camera

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Size
import android.view.Surface
import android.view.View
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.hilt.navigation.fragment.hiltNavGraphViewModels
import com.example.reviva.R
import com.example.reviva.presentation.camera.state.ScanFlowViewModel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.navigation.fragment.findNavController
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy


class LiveScanFragment : Fragment(R.layout.frag_livescan) {

    private val scanViewModel: ScanFlowViewModel
            by hiltNavGraphViewModels(R.id.app_nav_graph)

    private lateinit var analysisExecutor: ExecutorService

    private lateinit var previewView: PreviewView
    private lateinit var overlay: OverlayView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        analysisExecutor = Executors.newSingleThreadExecutor()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        analysisExecutor.shutdown()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        previewView = view.findViewById(R.id.previewView)
        overlay = view.findViewById(R.id.overlay)

        scanViewModel.setStatus("Scanning")

        startCamera()

        view.findViewById<View>(R.id.captureBtn).setOnClickListener {
            scanViewModel.incrementCaptured()
            findNavController().navigate(R.id.action_camera_to_review)
        }

        view.findViewById<View>(R.id.nextBtn).setOnClickListener {
            findNavController().navigate(R.id.action_camera_to_review)
        }
    }


    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases(cameraProvider)
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCameraUseCases(cameraProvider: ProcessCameraProvider) {

        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0

        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(1920, 1080),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()

        val preview = Preview.Builder()
            .setResolutionSelector(resolutionSelector)
            .setTargetRotation(rotation)
            .build()

        val imageAnalysis = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()


        imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
            try {
                val bitmap: Bitmap? = ImageUtils.imageProxyToBitmap(imageProxy)

                val w = bitmap?.width ?: imageProxy.width
                val h = bitmap?.height ?: imageProxy.height

                val box = OverlayView.Box(
                    left = w * 0.25f,
                    top = h * 0.25f,
                    right = w * 0.75f,
                    bottom = h * 0.75f
                )

                overlay.post {
                    overlay.setBoxes(listOf(box), imageProxy.width, imageProxy.height)
                }
            } finally {
                imageProxy.close()
            }
        }

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        cameraProvider.unbindAll()
        preview.setSurfaceProvider(previewView.surfaceProvider)

        cameraProvider.bindToLifecycle(
            viewLifecycleOwner,
            cameraSelector,
            preview,
            imageAnalysis
        )
    }
}
