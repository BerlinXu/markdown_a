package com.qoder.mdreader.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.qoder.mdreader.R

/**
 * 右侧的朱砂阅读进度线，末端带一个小书签尖。
 */
class ProgressLineView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, style: Int = 0
) : View(context, attrs, style) {

    var ratio: Float = 0f
        set(v) {
            val nv = v.coerceIn(0f, 1f)
            val moved = kotlin.math.abs(nv - field)
            field = nv
            // 滚动时每帧都会更新；位移不到 0.2% 就不必重绘，白耗 GPU
            if (moved >= 0.002f) invalidate()
        }

    var trackColor = 0x22000000
    var fillColor = 0xFFB03A2E.toInt()
        set(v) {
            field = v
            paint.color = v
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val path = Path()
    private val rect = RectF()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (h <= 0f) return
        val thick = 2.4f * density
        val left = (w - thick) / 2

        paint.style = Paint.Style.FILL
        paint.color = trackColor
        rect.set(left, 0f, left + thick, h)
        c.drawRoundRect(rect, thick / 2f, thick / 2f, paint)

        val filled = (h * ratio).coerceAtLeast(thick * 3f)
        paint.color = fillColor
        rect.set(left, 0f, left + thick, filled)
        c.drawRoundRect(rect, thick / 2f, thick / 2f, paint)

        // 书签尖
        val s = 4.2f * density
        path.reset()
        path.moveTo(left - s * 0.9f, filled)
        path.lineTo(left - s * 0.9f, filled + s * 1.5f)
        path.lineTo(left + thick * 0.5f, filled + s * 0.75f)
        path.close()
        c.drawPath(path, paint)
    }
}
