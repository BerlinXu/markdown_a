package com.qoder.mdreader.core

import android.content.Context
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import androidx.core.content.ContextCompat
import com.qoder.mdreader.MdApp
import com.qoder.mdreader.R

/**
 * 把"纸墨"这套配色和字号集中在这里，渲染层只跟它打交道。
 *
 * 注意：取色必须用传进来的 Activity context。AppCompat 的夜间模式是通过覆盖
 * Activity 的资源配置实现的，applicationContext 拿不到 values-night，
 * 用错 context 会导致深色模式下仍然取到浅色值（正文几乎看不见）。
 */
class DocTheme(private val ctx: Context) {

    private val res = ctx.resources

    val paper = color(R.color.paper)
    val card = color(R.color.card)
    val ink = color(R.color.ink)
    val ink2 = color(R.color.ink2)
    val ink3 = color(R.color.ink3)
    val line = color(R.color.line)
    val line2 = color(R.color.line2)
    val brand = color(R.color.brand)
    val brandInk = color(R.color.brand_ink)
    val brandSoft = color(R.color.brand_soft)
    val accent = color(R.color.accent)
    val accentSoft = color(R.color.accent_soft)
    val codeBg = color(R.color.code_bg)
    val quoteBg = color(R.color.quote_bg)

    /** 阅读正文基准字号（sp 数值），再乘用户缩放 */
    fun bodySp(prefs: Prefs) = res.getInteger(R.integer.reader_base_sp).toFloat()

    fun sp(prefs: Prefs, v: Float) = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, v * prefs.fontScale, res.displayMetrics)

    /** 标题级别 -> 相对正文的倍率，1 级最大 */
    fun headingScale(level: Int): Float = when (level) {
        1 -> 1.62f
        2 -> 1.28f
        3 -> 1.12f
        4 -> 1.02f
        5 -> 0.94f
        else -> 0.88f
    }

    fun codeSp(prefs: Prefs) = res.getInteger(R.integer.code_base_sp).toFloat()

    fun dp(v: Float) = MdApp.dp(ctx, v).toFloat()
    fun dpf(v: Float) = v * res.displayMetrics.density

    private val tfCache = HashMap<Int, Typeface>()

    /** Typeface.create 有系统缓存，但一次渲染要取几百次，这里自己留一份更省 */
    private fun face(serif: Boolean, bold: Boolean, italic: Boolean): Typeface {
        val key = (if (serif) 1 else 0) or (if (bold) 2 else 0) or (if (italic) 4 else 0)
        tfCache[key]?.let { return it }
        val base = if (serif) Typeface.SERIF else Typeface.SANS_SERIF
        val style = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return Typeface.create(base, style).also { tfCache[key] = it }
    }

    val serifBold: Typeface get() = face(true, true, false)
    val sansBold: Typeface get() = face(false, true, false)

    /** 正文用哪种气质：纸墨=衬线，清爽=无衬线 */
    fun bodyTypeface(prefs: Prefs, bold: Boolean = false, italic: Boolean = false): Typeface =
        face(prefs.fontFamily != "clean", bold, italic)

    fun headingTypeface(prefs: Prefs): Typeface = face(prefs.fontFamily != "clean", true, false)

    private fun color(id: Int) = ContextCompat.getColor(ctx, id)

    /**
     * 圆角 + 描边的通用背景。
     *
     * 参数一律是**像素**，调用方自己用 dpf() 换算。
     * 之前这里又乘了一次 density，而调用方传进来的已经是像素，
     * 结果 10dp 圆角在 2.6 倍屏上变成 26dp，矮框直接被画成胶囊。
     */
    fun rounded(bg: Int, radiusPx: Float, strokeColor: Int? = null, strokePx: Float = 0f): GradientDrawable =
        GradientDrawable().apply {
            setColor(bg)
            cornerRadius = radiusPx.coerceAtLeast(0f)
            if (strokeColor != null && strokePx > 0f) setStroke(strokePx.toInt().coerceAtLeast(1), strokeColor)
        }

    /** 引用块背景：圆角底 + 左侧朱砂竖条，一次画完，不额外占用布局高度 */
    fun quotePanel(): android.graphics.drawable.Drawable {
        val barW = dpf(3f)
        val radius = dpf(10f)
        return object : android.graphics.drawable.Drawable() {
            private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = quoteBg }
            private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
            private val box = RectF()

            override fun draw(c: android.graphics.Canvas) {
                val b = bounds
                box.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())
                c.drawRoundRect(box, radius, radius, bgPaint)
                box.set(b.left.toFloat(), b.top.toFloat(), b.left + barW, b.bottom.toFloat())
                c.drawRoundRect(box, barW / 2f, barW / 2f, barPaint)
            }

            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {}
            override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
        }
    }

    /** 只有底边线的单元格背景。画笔和矩形都复用，绘制时不再造对象 */
    fun bottomBorder(bg: Int, borderColor: Int, thicknessDp: Float = 1f): GradientDrawable =
        object : GradientDrawable() {
            private val line = Paint().apply { color = borderColor }
            private val rect = RectF()

            init {
                setColor(bg)
            }

            override fun draw(canvas: android.graphics.Canvas) {
                super.draw(canvas)
                val b = bounds
                val t = (thicknessDp * res.displayMetrics.density).coerceAtLeast(1f)
                rect.set(b.left.toFloat(), b.bottom - t, b.right.toFloat(), b.bottom.toFloat())
                canvas.drawRect(rect, line)
            }
        }
}
