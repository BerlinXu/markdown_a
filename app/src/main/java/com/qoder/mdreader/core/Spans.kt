package com.qoder.mdreader.core

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import android.text.style.MetricAffectingSpan
import android.view.View

/** 标题左侧的墨绿竖条：多行标题也能被完整包住 */
class HeadingBarSpan(
    private val color: Int,
    private val barWidthPx: Float,
    private val gapPx: Float,
    private val cornerPx: Float
) : LeadingMarginSpan {

    override fun getLeadingMargin(first: Boolean): Int = (barWidthPx + gapPx).toInt()

    override fun drawLeadingMargin(
        c: Canvas, p: Paint, x: Int, dir: Int,
        top: Int, y: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, first: Boolean, layout: android.text.Layout?
    ) {
        val saved = p.color
        val savedStyle = p.style
        p.isAntiAlias = true
        p.style = Paint.Style.FILL
        p.color = color
        val left = if (dir >= 0) x.toFloat() else x - barWidthPx
        val rect = RectF(left, top + p.textSize * 0.05f, left + barWidthPx, bottom - p.textSize * 0.05f)
        c.drawRoundRect(rect, cornerPx, cornerPx, p)
        p.color = saved
        p.style = savedStyle
    }
}

/**
 * `行内代码`：等宽 + 圆角浅底 + 细描边。
 * 背景走 LineBackgroundSpan，能拿到准确行盒，不会裁掉下沿。
 */
class InlineCodeSpan(
    private val bg: Int,
    private val fg: Int,
    private val border: Int,
    private val textSizePx: Float,
    private val radiusPx: Float,
    private val padHPx: Float,
    private val padVPx: Float
) : MetricAffectingSpan(), LineBackgroundSpan {

    override fun updateMeasureState(p: TextPaint) {
        p.typeface = Typeface.MONOSPACE
        p.textSize = textSizePx
    }

    override fun updateDrawState(p: TextPaint) {
        updateMeasureState(p)
        p.color = fg
    }

    override fun drawBackground(
        c: Canvas, paint: Paint, left: Int, right: Int,
        top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, lnum: Int
    ) {
        val savedStyle = paint.style
        val savedStroke = paint.strokeWidth
        val savedColor = paint.color

        paint.isAntiAlias = true
        // left/right 是系统算好的"本行里这段 span 的边界"，直接用它。
        // 之前自己 measureText 求宽度，而行画笔已被改成正文而非等宽小字号，
        // 量出来的宽度和实际排版不一致，框就和字错开了。
        val rect = RectF(
            left - padHPx,
            baseline + paint.fontMetrics.ascent - padVPx,
            right + padHPx,
            baseline + paint.fontMetrics.descent + padVPx
        )
        paint.style = Paint.Style.FILL
        paint.color = bg
        c.drawRoundRect(rect, radiusPx, radiusPx, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = maxOf(1f, radiusPx * 0.12f)
        paint.color = border
        c.drawRoundRect(rect, radiusPx, radiusPx, paint)

        paint.style = savedStyle
        paint.strokeWidth = savedStroke
        paint.color = savedColor
    }
}

/** ==高亮==：整段淡色底，不描边。边界同样直接用系统给的 left/right */
class HighlightBgSpan(
    private val bg: Int,
    private val padVPx: Float
) : LineBackgroundSpan {

    override fun drawBackground(
        c: Canvas, paint: Paint, left: Int, right: Int,
        top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, lnum: Int
    ) {
        val savedStyle = paint.style
        val savedColor = paint.color
        paint.style = Paint.Style.FILL
        paint.color = bg
        c.drawRect(
            left.toFloat(),
            baseline + paint.fontMetrics.ascent - padVPx,
            right.toFloat(),
            baseline + paint.fontMetrics.descent + padVPx,
            paint
        )
        paint.style = savedStyle
        paint.color = savedColor
    }
}

/** 链接：墨绿字色，点击交给回调（不直接开浏览器，方便加确认弹窗） */
class LinkSpan(private val url: String, private val color: Int, private val onOpen: (String) -> Unit) :
    ClickableSpan() {

    override fun updateDrawState(ds: TextPaint) {
        ds.color = color
        ds.isUnderlineText = false
        ds.linkColor = color
    }

    override fun onClick(widget: View) = onOpen(url)

    fun target(): String = url
}

/** 紧贴基线下方的朱砂细线 */
class AccentUnderlineSpan(
    private val color: Int,
    private val thicknessPx: Float,
    private val gapPx: Float
) : LineBackgroundSpan {

    override fun drawBackground(
        c: Canvas, paint: Paint, left: Int, right: Int,
        top: Int, baseline: Int, bottom: Int,
        text: CharSequence, start: Int, end: Int, lnum: Int
    ) {
        val savedStyle = paint.style
        val savedStroke = paint.strokeWidth
        val savedColor = paint.color

        val y = baseline + gapPx
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = thicknessPx
        paint.isAntiAlias = true
        paint.color = color
        c.drawLine(left.toFloat(), y, right.toFloat(), y, paint)

        paint.style = savedStyle
        paint.strokeWidth = savedStroke
        paint.color = savedColor
    }
}


