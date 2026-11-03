package ru.receipt.scanner

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

class RecognitionOverlay(context: Context) : View(context) {
    private val qrPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 200, 115); style = Paint.Style.STROKE; strokeWidth = 6f }
    private val totalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 170, 0); style = Paint.Style.STROKE; strokeWidth = 5f }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 32f; style = Paint.Style.FILL; setShadowLayer(4f, 1f, 1f, Color.BLACK) }
    private var qrBox: RectF? = null
    private var totalBox: RectF? = null

    fun setQrBox(box: RectF?) { qrBox = box; invalidate() }
    fun setTotalBox(box: RectF?) { totalBox = box; invalidate() }
    fun clear() { qrBox = null; totalBox = null; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        qrBox?.let { box -> canvas.drawRoundRect(box, 12f, 12f, qrPaint); canvas.drawText("QR", box.left, (box.top - 9f).coerceAtLeast(35f), textPaint) }
        totalBox?.let { box -> canvas.drawRoundRect(box, 8f, 8f, totalPaint); canvas.drawText("ИТОГ", box.left, (box.top - 9f).coerceAtLeast(35f), textPaint) }
    }
}
