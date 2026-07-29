package com.mrhish.cinecam

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class HistogramView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private var bins = IntArray(32)
    private val paint = Paint().apply { color = Color.parseColor("#E30613") }

    fun update(newBins: IntArray) {
        bins = newBins
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (bins.isEmpty()) return
        val max = bins.maxOrNull()?.coerceAtLeast(1) ?: 1
        val barWidth = width.toFloat() / bins.size
        for (i in bins.indices) {
            val barHeight = (bins[i].toFloat() / max) * height
            canvas.drawRect(
                i * barWidth, height - barHeight,
                (i + 1) * barWidth - 1f, height.toFloat(),
                paint
            )
        }
    }
}
