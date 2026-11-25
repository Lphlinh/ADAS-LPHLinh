package com.lphlinh.adas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }
    private val textBgPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#CC000000")
    }
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 38f
        isFakeBoldText = true
    }

    private var targetBox: RectF? = null
    private var boxColor: Int = Color.GREEN
    private var labelText: String = ""

    fun updateTarget(box: RectF?, color: Int, label: String) {
        targetBox = box
        boxColor = color
        labelText = label
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        targetBox?.let { box ->
            boxPaint.color = boxColor
            canvas.drawRect(box, boxPaint)

            if (labelText.isNotEmpty()) {
                val textWidth = textPaint.measureText(labelText)
                val topPos = Math.max(box.top - 12f, 45f)
                canvas.drawRect(box.left, topPos - 40f, box.left + textWidth + 20f, topPos + 10f, textBgPaint)
                canvas.drawText(labelText, box.left + 10f, topPos, textPaint)
            }
        }
    }
}
