package com.lphlinh.adas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

data class DetectedBox(
    val rect: RectF,
    val label: String,
    val distance: Float,
    val isDanger: Boolean,
    val isWarning: Boolean
)

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val boxes = ArrayList<DetectedBox>()

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }

    private val textBgPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#CC000000")
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 28f
        isFakeBoldText = true
        isAntiAlias = true
    }

    fun setDetections(newBoxes: List<DetectedBox>) {
        boxes.clear()
        boxes.addAll(newBoxes)
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        for (box in boxes) {
            when {
                box.isDanger -> {
                    boxPaint.color = Color.parseColor("#EF4444") // Đỏ
                }
                box.isWarning -> {
                    boxPaint.color = Color.parseColor("#F59E0B") // Vàng cam
                }
                else -> {
                    boxPaint.color = Color.parseColor("#10B981") // Xanh lá an toàn
                }
            }

            // Vẽ khung bo góc quanh xe
            canvas.drawRoundRect(box.rect, 8f, 8f, boxPaint)

            // Nhãn hiển thị ngay trên nóc xe
            val statusTag = if (box.isDanger) " [NGUY HIỂM]" else if (box.isWarning) " [CẢNH BÁO]" else ""
            val labelText = "${box.label}: ${String.format("%.1f", box.distance)}m$statusTag"

            val textWidth = textPaint.measureText(labelText)
            val tagTop = (box.rect.top - 40f).coerceAtLeast(10f)
            val tagRect = RectF(box.rect.left, tagTop, box.rect.left + textWidth + 24f, tagTop + 36f)

            canvas.drawRoundRect(tagRect, 6f, 6f, textBgPaint)
            canvas.drawText(labelText, box.rect.left + 12f, tagTop + 26f, textPaint)
        }
    }
}