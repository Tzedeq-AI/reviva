package com.example.reviva.data.cv

import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.pow

/**
 * Classical CV Photo Detector with rotated bounding boxes
 * Uses minAreaRect to detect rotated rectangles (matching Python cv2.boxPoints behavior)
 */
class ContourDetector(
    private val debug: Boolean = false,
    private val processWidth: Int = 640,
    private val minAreaPx: Double = 2000.0,
    private val aspectRatioMin: Double = 0.6,
    private val aspectRatioMax: Double = 1.8
) {

    private val TAG = "ContourDetector"

    // Tunables matching Python script
    private val MIN_AREA_RATIO = 0.02
    private val MAX_AREA_RATIO = 0.95
    private val RECT_FILL_MIN = 0.70
    private val EPS_RATIO = 0.02
    private val ANGLE_TOL = 20  // degrees from 90
    private val TEXTURE_MIN = 8.0  // Laplacian variance

    data class DetectedBox(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val confidence: Float,
        val polygon: Array<PointF>? = null,  // Exact contour points
        val rotatedBox: Array<PointF>? = null,  // 4 corners of rotated rect (from minAreaRect)
        val angle: Double = 0.0  // Rotation angle in degrees
    )

    /**
     * Detects rectangular photo regions and returns boxes with rotation
     *
     * Returns list of DetectedBox sorted by confidence (descending)
     * Each box includes:
     * - polygon: exact contour points
     * - rotatedBox: 4 corners of the fitted rotated rectangle
     * - angle: rotation angle in degrees
     */
    fun detect(bitmap: Bitmap): List<DetectedBox> {
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            Log.w(TAG, "Invalid bitmap dimensions")
            return emptyList()
        }

        // Scale to processWidth keeping aspect
        val scale = processWidth.toFloat() / bitmap.width
        val procW = processWidth
        val procH = max(1, (bitmap.height * scale).toInt())

        val procBmp = Bitmap.createScaledBitmap(bitmap, procW, procH, true)

        // Convert to Mat
        val mat = Mat()
        Utils.bitmapToMat(procBmp, mat)

        try {
            // Preprocessing
            val gray = Mat()
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_BGR2GRAY)

            // CLAHE
            val clahe = Imgproc.createCLAHE(2.5, Size(8.0, 8.0))
            clahe.apply(gray, gray)

            // Gaussian Blur
            val blur = Mat()
            Imgproc.GaussianBlur(gray, blur, Size(5.0, 5.0), 0.0)

            // Adaptive Canny
            val edges = Mat()
            adaptiveCanny(blur, edges)

            // Morphological Close
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)

            // Find Contours
            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(
                edges,
                contours,
                hierarchy,
                Imgproc.RETR_EXTERNAL,
                Imgproc.CHAIN_APPROX_SIMPLE
            )

            val detections = mutableListOf<DetectedBox>()
            val frameArea = (procW * procH).toDouble()

            for (cnt in contours) {
                // Area filter
                val area = Imgproc.contourArea(cnt)
                if (area < frameArea * MIN_AREA_RATIO || area > frameArea * MAX_AREA_RATIO) {
                    continue
                }

                // Approximate polygon
                val peri = Imgproc.arcLength(MatOfPoint2f(*cnt.toArray()), true)
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(MatOfPoint2f(*cnt.toArray()), approx, EPS_RATIO * peri, true)

                val approxPoints = approx.toArray()
                if (approxPoints.size != 4) {
                    continue
                }

                // Check if convex
                if (!Imgproc.isContourConvex(MatOfPoint(*approxPoints.map { Point(it.x, it.y) }
                        .toTypedArray()))) {
                    continue
                }

                // Check angles are close to 90 degrees
                val angles = polygonAngles(approxPoints)
                if (!angles.all { kotlin.math.abs(it - 90.0) < ANGLE_TOL }) {
                    continue
                }

                // Get rotated rectangle (THIS IS KEY FOR ROTATED BOXES!)
                val rect = Imgproc.minAreaRect(MatOfPoint2f(*approxPoints))
                val center = rect.center
                val size = rect.size
                val angle = rect.angle

                if (size.width <= 0 || size.height <= 0) {
                    continue
                }

                // Aspect ratio check
                val rw = size.width
                val rh = size.height
                val aspect = max(rw, rh) / min(rw, rh)
                if (aspect < aspectRatioMin || aspect > aspectRatioMax) {
                    continue
                }

                // Rectangularity check
                val rectArea = rw * rh
                val fill = area / rectArea
                if (fill < RECT_FILL_MIN) {
                    continue
                }

                // Texture check
                val tex = textureScore(gray, approxPoints)
                if (tex < TEXTURE_MIN) {
                    continue
                }

                // Get rotated box points (4 corners like cv2.boxPoints)
                val rotatedBoxPoints = Array(4) { Point() }
                Imgproc.boxPoints(rect, MatOfPoint2f(*rotatedBoxPoints))

                val rotatedBoxPointsF =
                    rotatedBoxPoints.map { PointF(it.x.toFloat(), it.y.toFloat()) }.toTypedArray()

                // Calculate bounding box (axis-aligned) for reference
                val contourPoints = approxPoints.map { Point(it.x, it.y) }.toTypedArray()
                val boundingRect = Imgproc.boundingRect(MatOfPoint(*contourPoints))

                // Confidence score (same as Python)
                val confidence = (
                        0.35 * (fill / 0.9) +
                                0.35 * (1 - kotlin.math.abs(aspect - 1.33) / 1.33) +
                                0.30 * (tex / 30)
                        ).toFloat().coerceIn(0f, 1f)

                val polygonPointsF =
                    approxPoints.map { PointF(it.x.toFloat(), it.y.toFloat()) }.toTypedArray()

                detections.add(
                    DetectedBox(
                        left = boundingRect.x.toFloat(),
                        top = boundingRect.y.toFloat(),
                        right = (boundingRect.x + boundingRect.width).toFloat(),
                        bottom = (boundingRect.y + boundingRect.height).toFloat(),
                        confidence = confidence,
                        polygon = polygonPointsF,
                        rotatedBox = rotatedBoxPointsF,  // KEY: Rotated corners!
                        angle = angle.toDouble()  // KEY: Rotation angle!
                    )
                )
            }

            // Sort by confidence descending
            detections.sortByDescending { it.confidence }

            Log.d(TAG, "Detected ${detections.size} photos")
            return detections

        } finally {
            mat.release()
            procBmp.recycle()
        }
    }

    private fun adaptiveCanny(gray: Mat, edges: Mat) {
        val grayArray = ByteArray((gray.total() * gray.channels()).toInt())
        gray.get(0, 0, grayArray)

        val median = grayArray.sorted()[grayArray.size / 2]
        val low = max(0, (0.66 * median).toInt())
        val high = min(255, (1.33 * median).toInt())

        Imgproc.Canny(gray, edges, low.toDouble(), high.toDouble())
    }

    private fun polygonAngles(points: Array<Point>): List<Double> {
        val angles = mutableListOf<Double>()

        for (i in 0..3) {
            val p0 = points[i]
            val p1 = points[(i + 1) % 4]
            val p2 = points[(i + 2) % 4]

            val v1x = p0.x - p1.x
            val v1y = p0.y - p1.y
            val v2x = p2.x - p1.x
            val v2y = p2.y - p1.y

            val dot = v1x * v2x + v1y * v2y
            val norm1 = sqrt(v1x * v1x + v1y * v1y)
            val norm2 = sqrt(v2x * v2x + v2y * v2y)

            val cosang = if (norm1 > 0 && norm2 > 0) dot / (norm1 * norm2) else 0.0
            val ang = Math.toDegrees(Math.acos(cosang.coerceIn(-1.0, 1.0)))
            angles.add(ang)
        }

        return angles
    }

    private fun textureScore(gray: Mat, points: Array<Point>): Double {
        val mask = Mat.zeros(gray.size(), CvType.CV_8U)
        val pts = MatOfPoint(*points)
        Imgproc.fillPoly(mask, listOf(pts), Scalar(255.0))

        val roi = Mat()
        gray.copyTo(roi, mask)

        val laplacian = Mat()
        Imgproc.Laplacian(roi, laplacian, CvType.CV_64F)

        val mean = MatOfDouble()
        val stddev = MatOfDouble()
        Core.meanStdDev(laplacian, mean, stddev)

        roi.release()
        laplacian.release()
        mask.release()

        val std = stddev.toArray()[0]
        return std * std
    }
}