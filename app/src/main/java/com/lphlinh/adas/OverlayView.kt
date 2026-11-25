package com.lphlinh.adas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val paint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 36f
        style = Paint.Style.FILL
    }
    private var targetBox: RectF? = null
    private var boxColor: Int = Color.GREEN
    private var infoText: String = ""

    fun updateTarget(box: RectF?, color: Int, text: String) {
        targetBox = box
        boxColor = color
        infoText = text
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        targetBox?.let {
            paint.color = boxColor
            canvas.drawRect(it, paint)
            canvas.drawText(infoText, it.left, Math.max(it.top - 10f, 40f), textPaint)
        }
    }
}
