package com.example.reviva.presentation.camera

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.View

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

    private val corners = mutableListOf<List<PointF>>()
    private var frameWidth = 0
    private var frameHeight = 0
    private var isQualityGood = false

    private val cornersPaint = Paint().apply {
        color = Color.GREEN
        strokeWidth = 4f
        isAntiAlias = true
        style = Paint.Style.STROKE
    }

    private val statusCirclePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }

    fun setBoxes(
        newBoxes: List<Box> = emptyList(),
        width: Int,
        height: Int,
        newMasks: List<Bitmap?> = emptyList(),
        newLabels: List<String> = emptyList(),
        newClassIds: List<Int> = emptyList(),
        newCorners: List<List<PointF>> = emptyList(),
        qualityGood: Boolean = false,
        displayWidth: Int = width,
        displayHeight: Int = height
    ) {
        try {
            synchronized(this) {
                frameWidth = width
                frameHeight = height
                corners.clear()
                corners.addAll(newCorners)
                isQualityGood = qualityGood
            }
            postInvalidate()
        } catch (e: Exception) {
            Log.e("OverlayView", "Error setting boxes: ${e.message}", e)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        try {
            synchronized(this) {
                if (corners.isEmpty()) return
                for (cornersList in corners) drawCorners(canvas, cornersList)
                drawStatusCircle(canvas)
            }
        } catch (e: Exception) {
            Log.e("OverlayView", "Error in onDraw: ${e.message}", e)
        }
    }

    private fun drawCorners(canvas: Canvas, cornersList: List<PointF>) {
        try {
            if (cornersList.size < 4) return

            val actualWidth = this.width.toFloat()
            val actualHeight = this.height.toFloat()

            val bounded = cornersList.map { p -> PointF(p.x.coerceIn(0f, actualWidth), p.y.coerceIn(0f, actualHeight)) }

            for (p in bounded) canvas.drawCircle(p.x, p.y, 6f, cornersPaint)

            val path = Path().apply {
                val first = bounded[0]
                moveTo(first.x, first.y)
                for (i in 1 until bounded.size) {
                    val q = bounded[i]
                    lineTo(q.x, q.y)
                }
                lineTo(first.x, first.y)
            }
            canvas.drawPath(path, cornersPaint)

        } catch (e: Exception) {
            Log.w("OverlayView", "Error drawing corners: ${e.message}")
        }
    }

    private fun drawStatusCircle(canvas: Canvas) {
        try {
            val circleRadius = 10f
            val padding = 18f
            val x = this.width - padding
            val y = padding
            statusCirclePaint.color = if (isQualityGood) Color.GREEN else Color.RED
            canvas.drawCircle(x, y, circleRadius, statusCirclePaint)
        } catch (e: Exception) {
            Log.w("OverlayView", "Error drawing status circle: ${e.message}")
        }
    }

    fun clear() {
        synchronized(this) { corners.clear(); isQualityGood = false; invalidate() }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        clear()
    }
}


