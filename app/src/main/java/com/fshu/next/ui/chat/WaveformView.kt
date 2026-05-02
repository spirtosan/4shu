package com.fshu.next.ui.chat

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var amplitudes: FloatArray = FloatArray(0)
        set(value) { field = value; invalidate() }

    var progress: Float = 0f
        set(value) { field = value; invalidate() }

    var playedColor: Int = Color.parseColor("#E8711A")
    var unplayedColor: Int = Color.parseColor("#66FFFFFF")

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()
    private val MAX_BARS = 80

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        if (amplitudes.isEmpty()) {
            paint.color = unplayedColor
            val mid = h / 2f
            rect.set(0f, mid - 1.5f, w, mid + 1.5f)
            canvas.drawRoundRect(rect, 1.5f, 1.5f, paint)
            return
        }

        val bars = if (amplitudes.size <= MAX_BARS) amplitudes else downsample(amplitudes, MAX_BARS)
        val n = bars.size
        val barTotalW = w / n
        val gap = (barTotalW * 0.25f).coerceAtLeast(0.8f)
        val bw = (barTotalW - gap).coerceAtLeast(1f)
        val progressX = progress * w

        for (i in bars.indices) {
            val x = i * barTotalW + gap / 2f
            val amp = bars[i].coerceIn(0.08f, 1f)
            val barH = amp * h * 0.9f
            val top = (h - barH) / 2f
            paint.color = if (x + bw / 2f <= progressX) playedColor else unplayedColor
            rect.set(x, top, x + bw, top + barH)
            canvas.drawRoundRect(rect, bw / 2f, bw / 2f, paint)
        }
    }

    private fun downsample(arr: FloatArray, target: Int): FloatArray {
        val result = FloatArray(target)
        val chunk = arr.size.toFloat() / target
        for (i in 0 until target) {
            val from = (i * chunk).toInt()
            val to = ((i + 1) * chunk).toInt().coerceAtMost(arr.size)
            result[i] = if (from >= to) arr.getOrElse(from) { 0f }
                        else arr.slice(from until to).average().toFloat()
        }
        return result
    }
}
