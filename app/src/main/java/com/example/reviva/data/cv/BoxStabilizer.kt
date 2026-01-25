package com.example.reviva.data.cv

import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/**
 * Robust BoxStabilizer:
 * - Matches detections to existing tracks by IoU of bounding boxes
 * - EMA smooths per-corner coordinates
 * - If detections are empty, returns current tracked polygons so overlay does not flicker
 * - Thread-safe via synchronized methods
 */
class BoxStabilizer(
    private val iouMatchThreshold: Float = 0.35f,
    private val alpha: Float = 0.75f,
    private val maxMissFrames: Int = 6
) {
    private data class Track(
        var corners: List<PointF>,
        var miss: Int = 0
    )

    private val tracks = ArrayList<Track>()

    private fun rectFromCorners(corners: List<PointF>): RectF {
        var l = Float.POSITIVE_INFINITY
        var t = Float.POSITIVE_INFINITY
        var r = Float.NEGATIVE_INFINITY
        var b = Float.NEGATIVE_INFINITY
        for (p in corners) {
            l = min(l, p.x)
            t = min(t, p.y)
            r = max(r, p.x)
            b = max(b, p.y)
        }
        return RectF(l, t, r, b)
    }

    private fun iouRect(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        val interW = (right - left).coerceAtLeast(0f)
        val interH = (bottom - top).coerceAtLeast(0f)
        val inter = interW * interH
        val union = (a.width() * a.height()) + (b.width() * b.height()) - inter
        return if (union <= 0f) 0f else inter / union
    }

    private fun lerp(a: Float, b: Float, alpha: Float) = a * alpha + b * (1f - alpha)

    private fun smoothCorners(prev: List<PointF>, cur: List<PointF>): List<PointF> {
        if (prev.size != cur.size) return cur.map { PointF(it.x, it.y) }
        return prev.indices.map { i ->
            val p = prev[i]
            val c = cur[i]
            PointF(lerp(p.x, c.x, alpha), lerp(p.y, c.y, alpha))
        }
    }

    /**
     * Stabilize input detections.
     * If detections is empty, returns current track polygons (so overlay remains visible).
     * If detections present, returns polygons aligned to input order (smoothed) where possible.
     */
    @Synchronized
    fun stabilize(detections: List<List<PointF>>): List<List<PointF>> {
        // If no tracks yet and no detections, return empty immediately
        if (detections.isEmpty() && tracks.isEmpty()) return emptyList()

        // If no detections but we have tracks: age them and return current tracks so UI stays stable
        if (detections.isEmpty()) {
            // increment miss counters, remove stale tracks
            val it = tracks.iterator()
            while (it.hasNext()) {
                val t = it.next()
                t.miss++
                if (t.miss > maxMissFrames) it.remove()
            }
            return tracks.map { t -> t.corners.map { PointF(it.x, it.y) } }
        }

        // Snapshot current tracks rects for matching
        val trackCount = tracks.size
        val usedTrack = BooleanArray(trackCount)
        val out = MutableList(detections.size) { emptyList<PointF>() }

        // Match detections to tracks by IoU (tracks snapshot used for matching)
        for ((di, det) in detections.withIndex()) {
            val dRect = rectFromCorners(det)
            var bestIoU = 0f
            var bestTi = -1
            for (ti in 0 until trackCount) {
                if (usedTrack[ti]) continue
                val tRect = rectFromCorners(tracks[ti].corners)
                val iou = iouRect(dRect, tRect)
                if (iou > bestIoU) {
                    bestIoU = iou
                    bestTi = ti
                }
            }

            if (bestTi >= 0 && bestIoU >= iouMatchThreshold) {
                val track = tracks[bestTi]
                track.corners = smoothCorners(track.corners, det)
                track.miss = 0
                usedTrack[bestTi] = true
                out[di] = track.corners.map { PointF(it.x, it.y) }
            } else {
                // new track
                val copy = det.map { PointF(it.x, it.y) }
                tracks.add(Track(corners = copy, miss = 0))
                out[di] = copy
            }
        }

        // Update miss counters for tracks and remove stale ones
        for (ti in tracks.indices.reversed()) {
            val wasUsed = if (ti < usedTrack.size) usedTrack[ti] else false
            val t = tracks[ti]
            if (!wasUsed) t.miss++ else t.miss = 0
            if (t.miss > maxMissFrames) tracks.removeAt(ti)
        }

        return out.filter { it.isNotEmpty() }
    }

    @Synchronized
    fun reset() {
        tracks.clear()
    }
}
