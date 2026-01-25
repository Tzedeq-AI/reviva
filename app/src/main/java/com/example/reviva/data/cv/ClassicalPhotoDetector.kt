package com.example.reviva.data.cv

import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

object ClassicalPhotoDetector {
    // Tunables
    private const val MAX_WIDTH = 640                    // reduce to 480 if you want faster
    private const val MIN_AREA_RATIO = 0.02
    private const val MAX_AREA_RATIO = 0.95
    private const val ASPECT_MIN = 0.6
    private const val ASPECT_MAX = 1.7
    private const val RECT_FILL_MIN = 0.70
    private const val EPS_RATIO = 0.02
    private const val ANGLE_TOL = 20.0                   // degrees from 90
    private const val TEXTURE_MIN = 8.0
    private const val FLIP_CROPS = true                  // fix mirrored thumbnails
    private const val MAX_RESULTS = 5

    private fun meanOfMat(mat: Mat): Double = Core.mean(mat).`val`[0]

    private fun polygonAngles(pts: Array<Point>): DoubleArray {
        val angles = DoubleArray(4)
        for (i in 0..3) {
            val p0 = pts[i]
            val p1 = pts[(i + 1) % 4]
            val p2 = pts[(i + 2) % 4]
            val v1x = p0.x - p1.x
            val v1y = p0.y - p1.y
            val v2x = p2.x - p1.x
            val v2y = p2.y - p1.y
            val dot = v1x * v2x + v1y * v2y
            val n1 = Math.hypot(v1x, v1y)
            val n2 = Math.hypot(v2x, v2y)
            val cosang = (dot / (n1 * n2 + 1e-9)).coerceIn(-1.0, 1.0)
            angles[i] = Math.toDegrees(Math.acos(cosang))
        }
        return angles
    }

    private fun textureScore(gray: Mat, poly: MatOfPoint): Double {
        val mask = Mat.zeros(gray.size(), CvType.CV_8UC1)
        try {
            Imgproc.fillPoly(mask, listOf(poly), Scalar(255.0))
            val roi = Mat()
            Core.bitwise_and(gray, gray, roi, mask)
            val lap = Mat()
            Imgproc.Laplacian(roi, lap, CvType.CV_64F)
            val mean = MatOfDouble()
            val stddev = MatOfDouble()
            Core.meanStdDev(lap, mean, stddev, Mat())
            val sd = stddev.toArray().getOrNull(0) ?: 0.0
            return sd * sd
        } finally {
            mask.release()
        }
    }

    // Warps given source Mat using rect pts (ordered TL,TR,BR,BL) and returns Bitmap crop
    private fun fourPointCropBitmap(src: Mat, rectPts: Array<Point>, downscaleDiv: Int = 4): Bitmap? {
        if (rectPts.size < 4) return null
        val rect = orderPoints(rectPts)
        val (tl, tr, br, bl) = rect

        val widthA = Math.hypot(br.x - bl.x, br.y - bl.y)
        val widthB = Math.hypot(tr.x - tl.x, tr.y - tl.y)
        val maxWidth = max(widthA, widthB).coerceAtLeast(1.0)

        val heightA = Math.hypot(tr.x - br.x, tr.y - br.y)
        val heightB = Math.hypot(tl.x - bl.x, tl.y - bl.y)
        val maxHeight = max(heightA, heightB).coerceAtLeast(1.0)

        val dst = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(maxWidth - 1.0, 0.0),
            Point(maxWidth - 1.0, maxHeight - 1.0),
            Point(0.0, maxHeight - 1.0)
        )
        val srcPts = MatOfPoint2f(*rect)
        val M = Imgproc.getPerspectiveTransform(srcPts, dst)

        val warped = Mat()
        Imgproc.warpPerspective(src, warped, M, Size(maxWidth, maxHeight), Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, Scalar(255.0, 255.0, 255.0))

        // downscale to reduce memory and UI size (mimic //4)
        val outMat = if (downscaleDiv > 1) {
            val sw = (warped.cols() / downscaleDiv).coerceAtLeast(1)
            val sh = (warped.rows() / downscaleDiv).coerceAtLeast(1)
            val scaled = Mat()
            Imgproc.resize(warped, scaled, Size(sw.toDouble(), sh.toDouble()))
            warped.release()
            scaled
        } else warped

        try {
            if (FLIP_CROPS) {
                val flipped = Mat()
                Core.flip(outMat, flipped, 1) // horizontal flip
                outMat.release()
                val bmp = Bitmap.createBitmap(flipped.cols(), flipped.rows(), Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(flipped, bmp)
                flipped.release()
                return bmp
            } else {
                val bmp = Bitmap.createBitmap(outMat.cols(), outMat.rows(), Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(outMat, bmp)
                return bmp
            }
        } catch (e: Exception) {
            Log.w("ClassicalPhotoDetector", "crop bitmap failed: ${e.message}")
            return null
        } finally {
            outMat.release()
            srcPts.release()
            dst.release()
            M.release()
        }
    }

    private fun orderPoints(pts: Array<Point>): Array<Point> {
        val rect = Array(4) { Point(0.0, 0.0) }
        if (pts.isEmpty()) return rect
        val sum = pts.map { it.x + it.y }
        val diff = pts.map { it.x - it.y }
        val minSumIdx = sum.indices.minByOrNull { sum[it] } ?: 0
        val maxSumIdx = sum.indices.maxByOrNull { sum[it] } ?: 0
        val minDiffIdx = diff.indices.minByOrNull { diff[it] } ?: 0
        val maxDiffIdx = diff.indices.maxByOrNull { diff[it] } ?: 0
        rect[0] = pts[minSumIdx]
        rect[2] = pts[maxSumIdx]
        rect[1] = pts[minDiffIdx]
        rect[3] = pts[maxDiffIdx]
        return rect
    }

    // Main entry. Returns PhotoBorder with corners + score + crop
    fun detectPhotoPolygons(inputBmp: Bitmap): List<PhotoBorder> {
        val results = mutableListOf<PhotoBorder>()
        var mat = Mat()
        try {
            Utils.bitmapToMat(inputBmp, mat) // RGBA
            val originalW = mat.cols()
            var scale = 1.0
            if (originalW > MAX_WIDTH) {
                scale = MAX_WIDTH.toDouble() / originalW.toDouble()
                val scaled = Mat()
                Imgproc.resize(mat, scaled, Size(mat.cols() * scale, mat.rows() * scale))
                mat.release()
                mat = scaled
            }

            val gray = Mat()
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)

            val clahe = Imgproc.createCLAHE(2.5, Size(8.0, 8.0))
            val cl = Mat()
            clahe.apply(gray, cl)
            gray.release()

            val blur = Mat()
            Imgproc.GaussianBlur(cl, blur, Size(5.0, 5.0), 0.0)
            cl.release()

            val v = meanOfMat(blur)
            val low = (0.66 * v).toInt().coerceAtLeast(0)
            val high = (1.33 * v).toInt().coerceAtMost(255)
            val edges = Mat()
            Imgproc.Canny(blur, edges, low.toDouble(), high.toDouble())
            blur.release()

            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            val closed = Mat()
            Imgproc.morphologyEx(edges, closed, Imgproc.MORPH_CLOSE, kernel)
            edges.release()

            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(closed, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
            hierarchy.release()
            closed.release()

            val frameArea = mat.rows().toDouble() * mat.cols().toDouble()

            // sort contours by area desc to process big shapes first
            contours.sortByDescending { Imgproc.contourArea(it) }

            for (cnt in contours) {
                if (results.size >= MAX_RESULTS) break
                try {
                    val area = Imgproc.contourArea(cnt)
                    if (area < frameArea * MIN_AREA_RATIO || area > frameArea * MAX_AREA_RATIO) {
                        cnt.release(); continue
                    }

                    val cnt2f = MatOfPoint2f(*cnt.toArray())
                    val peri = Imgproc.arcLength(cnt2f, true)
                    val eps = EPS_RATIO * peri
                    val approx2f = MatOfPoint2f()
                    Imgproc.approxPolyDP(cnt2f, approx2f, eps, true)
                    val approx = MatOfPoint(*approx2f.toArray())

                    if (approx.total() != 4L || !Imgproc.isContourConvex(approx)) {
                        cnt2f.release(); approx2f.release(); approx.release(); continue
                    }

                    val pts = approx.toArray()
                    val angles = polygonAngles(pts)
                    var okAngles = true
                    for (a in angles) {
                        if (abs(a - 90.0) > ANGLE_TOL) { okAngles = false; break }
                    }
                    if (!okAngles) {
                        cnt2f.release(); approx2f.release(); approx.release(); continue
                    }

                    val rect = Imgproc.minAreaRect(cnt2f)
                    val rw = rect.size.width
                    val rh = rect.size.height
                    if (rw == 0.0 || rh == 0.0) {
                        cnt2f.release(); approx2f.release(); approx.release(); continue
                    }
                    val aspect = max(rw, rh) / min(rw, rh)
                    if (aspect < ASPECT_MIN || aspect > ASPECT_MAX) {
                        cnt2f.release(); approx2f.release(); approx.release(); continue
                    }

                    val rectArea = rw * rh
                    val fill = area / (rectArea + 1e-9)
                    if (fill < RECT_FILL_MIN) {
                        cnt2f.release(); approx2f.release(); approx.release(); continue
                    }

                    val tex = textureScore(mat, approx)
                    if (tex < TEXTURE_MIN) {
                        cnt2f.release(); approx2f.release(); approx.release(); continue
                    }

                    val conf = ((0.35 * (fill / 0.9)) +
                            (0.35 * (1.0 - abs(aspect - 1.33) / 1.33)) +
                            (0.30 * (tex / 30.0))
                            ).coerceIn(0.0, 1.0)

                    // rect points from minAreaRect
                    val ptsArr = arrayOf(Point(), Point(), Point(), Point())
                    rect.points(ptsArr)

                    // unscale back to original inputBmp coordinates
                    val invScale = if (scale != 0.0) 1.0 / scale else 1.0
                    val corners = ptsArr.map { p -> PointF((p.x * invScale).toFloat(), (p.y * invScale).toFloat()) }

                    // crop from original inputBmp by building Mat from the original bitmap again for high quality
                    val cropBitmap = try {
                        // rebuild original Mat from inputBmp for accurate crop
                        val origMat = Mat()
                        Utils.bitmapToMat(inputBmp, origMat)
                        val ordered = ptsArr.map { p -> Point(p.x * invScale, p.y * invScale) }.toTypedArray()
                        val bmp = fourPointCropBitmap(origMat, ordered, downscaleDiv = 4)
                        origMat.release()
                        bmp
                    } catch (e: Exception) {
                        Log.w("ClassicalPhotoDetector", "crop creation failed: ${e.message}")
                        null
                    }

                    val quality = 0
                    results.add(PhotoBorder(corners = corners, score = conf.toFloat(), mask = null, crop = cropBitmap, detectionQuality = quality))

                    cnt2f.release(); approx2f.release(); approx.release()
                } catch (ee: Exception) {
                    Log.w("ClassicalPhotoDetector", "inner: ${ee.message}")
                    try { cnt.release() } catch (_: Exception) {}
                    continue
                }
            }

            for (c in contours) try { c.release() } catch (_: Exception) {}
        } catch (e: Exception) {
            Log.e("ClassicalPhotoDetector", "detect failed: ${e.message}", e)
        } finally {
            try { mat.release() } catch (_: Exception) {}
        }

        return results
    }
}


