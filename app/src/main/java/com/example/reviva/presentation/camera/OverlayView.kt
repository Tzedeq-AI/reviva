package com.example.reviva.presentation.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f   // ~2dp
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = context.getColor(com.example.reviva.R.color.white)
    }


    private var boxes: List<Box> = emptyList()
    private val rectBuffer = ArrayList<RectF>(16)

    private var srcWidth = 1
    private var srcHeight = 1

    fun setBoxes(boxes: List<Box>, imageWidth: Int, imageHeight: Int) {
        this.boxes = boxes
        this.srcWidth = imageWidth
        this.srcHeight = imageHeight

        // ensure buffer size
        if (rectBuffer.size < boxes.size) {
            repeat(boxes.size - rectBuffer.size) {
                rectBuffer.add(RectF())
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val sx = width.toFloat() / srcWidth
        val sy = height.toFloat() / srcHeight

        for (i in boxes.indices) {
            val b = boxes[i]
            val r = rectBuffer[i]

            r.set(
                b.left * sx,
                b.top * sy,
                b.right * sx,
                b.bottom * sy
            )
            canvas.drawRect(r, paint)
        }
    }
}
