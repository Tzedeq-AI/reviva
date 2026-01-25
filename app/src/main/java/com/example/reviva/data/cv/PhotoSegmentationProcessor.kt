package com.example.reviva.data.cv

import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.exp
import kotlin.math.sqrt

data class PhotoBorder(
    val corners: List<PointF>,
    val score: Float,
    val mask: Bitmap? = null,
    val crop: Bitmap? = null,
    val detectionQuality: Int = 0  // âœ… NEW: 0=proper, 1=overlap, 2=out_of_frame
)

object PhotoSegmentationProcessor {
    private const val CONF_THR = 0.4f
    private const val MASK_THR = 0.45
    private const val MIN_CONTOUR_AREA = 2000.0

    private fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))

    fun processPhotoBorders(
        frameBitmap: Bitmap,
        proto: Array<Array<FloatArray>>,
        detections: List<com.example.reviva.data.ml.YBox>
    ): List<PhotoBorder> {
        val results = mutableListOf<PhotoBorder>()

        if (detections.isEmpty()) {
            Log.d("PhotoSegmentationProcessor", "No detections found")
            return results
        }

        // Convert bitmap to OpenCV Mat
        val frameMat = Mat()
        try {
            Utils.bitmapToMat(frameBitmap, frameMat)
        } catch (e: Exception) {
            Log.e("PhotoSegmentationProcessor", "Failed to convert bitmap to mat", e)
            return results
        }

        // Pre-calculate model input size
        val modelSize = 640
        val scaleX = frameBitmap.width.toFloat() / modelSize
        val scaleY = frameBitmap.height.toFloat() / modelSize

        // Process each detection
        for (det in detections) {
            if (det.score < CONF_THR) continue

            val coeffs = det.maskCoeff ?: continue

            try {
                // 1. Build mask: proto Ã— coeffs
                val maskProto = Mat(160, 160, CvType.CV_32F)

                // Compute mask using tensordot equivalent
                for (y in 0 until 160) {
                    for (x in 0 until 160) {
                        if (y >= proto.size || x >= proto[y].size) continue

                        var sum = 0.0
                        val protoRow = proto[y][x]
                        val coeffSize = minOf(protoRow.size, coeffs.size)

                        for (k in 0 until coeffSize) {
                            sum += protoRow[k] * coeffs[k]
                        }
                        val value = sigmoid(sum)
                        maskProto.put(y, x, value)
                    }
                }

                // 2. Resize to model input size (640x640)
                val maskScaled = Mat()
                Imgproc.resize(maskProto, maskScaled, Size(640.0, 640.0))
                maskProto.release()

                // 3. Threshold
                val maskThresh = Mat()
                Imgproc.threshold(maskScaled, maskThresh, MASK_THR, 255.0, Imgproc.THRESH_BINARY)
                maskScaled.release()

                // 4. Convert to 8-bit
                val mask8u = Mat()
                maskThresh.convertTo(mask8u, CvType.CV_8UC1)
                maskThresh.release()

                // 5. Apply blur
                val maskBlur = Mat()
                Imgproc.medianBlur(mask8u, maskBlur, 7)
                mask8u.release()

                // 6. Resize to original frame size
                val maskFull = Mat()
                Imgproc.resize(maskBlur, maskFull,
                    Size(frameBitmap.width.toDouble(), frameBitmap.height.toDouble()))
                maskBlur.release()

                // 7. Find contours
                val contours = mutableListOf<MatOfPoint>()
                val hierarchy = Mat()
                Imgproc.findContours(maskFull, contours, hierarchy,
                    Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                hierarchy.release()

                if (contours.isEmpty()) {
                    maskFull.release()
                    continue
                }

                // 8. Find largest contour
                val largestContour = contours.maxByOrNull { Imgproc.contourArea(it) }
                if (largestContour == null) {
                    maskFull.release()
                    contours.forEach { it.release() }
                    continue
                }

                // 9. Filter by area
                if (Imgproc.contourArea(largestContour) < MIN_CONTOUR_AREA) {
                    maskFull.release()
                    contours.forEach { it.release() }
                    continue
                }

                // 10. Get rotated rectangle
                val pointsMat = MatOfPoint2f(*largestContour.toArray())
                val rect = Imgproc.minAreaRect(pointsMat)
                pointsMat.release()

                // 11. Get 4 corner points
                val points = arrayOfNulls<Point>(4)
                rect.points(points)

                // 12. Order points (TL, TR, BR, BL)
                val orderedPoints = orderPoints(points.filterNotNull().toTypedArray())

                // 13. Convert to PointF for Android
                val corners = orderedPoints.map {
                    PointF(it.x.toFloat(), it.y.toFloat())
                }

                // 14. Create warped crop
                val cropBitmap = try {
                    val warped = fourPointTransform(frameMat, orderedPoints)

                    // Downscale like Python (//4)
                    val scaledWarped = Mat()
                    val newWidth = maxOf(1, warped.cols() / 4)
                    val newHeight = maxOf(1, warped.rows() / 4)

                    if (newWidth > 0 && newHeight > 0) {
                        Imgproc.resize(warped, scaledWarped, Size(newWidth.toDouble(), newHeight.toDouble()))
                        val crop = Bitmap.createBitmap(scaledWarped.cols(), scaledWarped.rows(), Bitmap.Config.ARGB_8888)
                        Utils.matToBitmap(scaledWarped, crop)
                        scaledWarped.release()
                        warped.release()
                        crop
                    } else {
                        warped.release()
                        null
                    }
                } catch (e: Exception) {
                    Log.e("PhotoSegProcessor", "Crop failed: ${e.message}")
                    null
                }

                // 15. Create mask bitmap for visualization
                val maskBitmap = try {
                    val maskVis = Bitmap.createBitmap(maskFull.cols(), maskFull.rows(), Bitmap.Config.ARGB_8888)
                    Utils.matToBitmap(maskFull, maskVis)
                    maskFull.release()
                    maskVis
                } catch (e: Exception) {
                    Log.e("PhotoSegProcessor", "Mask bitmap creation failed: ${e.message}")
                    maskFull.release()
                    null
                }

                results.add(
                    PhotoBorder(
                        corners = corners,
                        score = det.score,
                        mask = maskBitmap,
                        crop = cropBitmap
                    )
                )

                contours.forEach { it.release() }

                if (results.size >= 5) break  // Limit to top 5

            } catch (e: Exception) {
                Log.e("PhotoSegProcessor", "Error processing detection: ${e.message}", e)
                continue
            }
        }

        frameMat.release()
        return results
    }

    private fun orderPoints(pts: Array<Point>): Array<Point> {
        val rect = Array(4) { Point(0.0, 0.0) }

        if (pts.isEmpty()) return rect

        // Sum of x+y
        val sum = pts.map { it.x + it.y }
        val diff = pts.map { it.x - it.y }

        val minSumIdx = sum.indices.minByOrNull { sum[it] } ?: 0
        val maxSumIdx = sum.indices.maxByOrNull { sum[it] } ?: 0
        val minDiffIdx = diff.indices.minByOrNull { diff[it] } ?: 0
        val maxDiffIdx = diff.indices.maxByOrNull { diff[it] } ?: 0

        rect[0] = pts[minSumIdx]  // Top-left
        rect[2] = pts[maxSumIdx]  // Bottom-right
        rect[1] = pts[minDiffIdx] // Top-right
        rect[3] = pts[maxDiffIdx] // Bottom-left

        return rect
    }

    private fun fourPointTransform(image: Mat, pts: Array<Point>): Mat {
        val rect = orderPoints(pts)
        val (tl, tr, br, bl) = rect

        // Calculate width
        val widthA = sqrt((br.x - bl.x) * (br.x - bl.x) + (br.y - bl.y) * (br.y - bl.y))
        val widthB = sqrt((tr.x - tl.x) * (tr.x - tl.x) + (tr.y - tl.y) * (tr.y - tl.y))
        val maxWidth = maxOf(widthA, widthB)

        // Calculate height
        val heightA = sqrt((tr.x - br.x) * (tr.x - br.x) + (tr.y - br.y) * (tr.y - br.y))
        val heightB = sqrt((tl.x - bl.x) * (tl.x - bl.x) + (tl.y - bl.y) * (tl.y - bl.y))
        val maxHeight = maxOf(heightA, heightB)

        // Destination points
        val dst = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(maxWidth - 1.0, 0.0),
            Point(maxWidth - 1.0, maxHeight - 1.0),
            Point(0.0, maxHeight - 1.0)
        )

        // Get perspective transform
        val srcPoints = MatOfPoint2f(*rect)
        val M = Imgproc.getPerspectiveTransform(srcPoints, dst)

        // Warp perspective
        val warped = Mat()
        Imgproc.warpPerspective(image, warped, M,
            Size(maxWidth, maxHeight), Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar(255.0, 255.0, 255.0))

        srcPoints.release()
        dst.release()
        M.release()

        return warped
    }
}
