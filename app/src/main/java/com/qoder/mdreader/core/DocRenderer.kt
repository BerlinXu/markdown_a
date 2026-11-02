package com.qoder.mdreader.core

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import com.qoder.mdreader.ui.hint

/**
 * Block 树 -> 安卓 View 树。
 *
 * 每个块都是独立 View 并把自己在源文件里的行号写进 tag，
 * 于是"目录跳转""点待办改回那一行"都只需要按 tag 找 View。
 */
class DocRenderer(
    private val ctx: Context,
    private val th: DocTheme,
    private val prefs: Prefs,
    private val baseDir: File?,
    private val onLink: (String) -> Unit,
    private val onTaskToggle: (Int, Boolean) -> Unit,
    private val resolveImage: ((String) -> String?)? = null
) {

    private val urlRegex = Regex("""https?://[^\s<>()\u4e00-\u9fa5，。；：、]+|www\.[^\s<>()\u4e00-\u9fa5，。；：、]+""")

    fun renderAll(container: LinearLayout, blocks: List<Block>) {
        container.removeAllViews()
        blocks.forEach { b ->
            if (!isBlankBlock(b)) container.addView(viewFor(b, 0))
        }
    }

    /**
     * 内容为空的块直接不生成 View。
     *
     * 长文档里偶尔会出现"只剩符号没有内容"的块（空引用、空表格行、被吃掉的段落），
     * 照样渲染就是一段没有文字的空白，滚起来非常像 bug。
     */
    private fun isBlankBlock(b: Block): Boolean = when (b) {
        is Block.Paragraph -> flatten(b.inline).isBlank()
        is Block.Heading -> flatten(b.inline).isBlank()
        is Block.CodeBlock -> b.text.isBlank()
        is Block.Quote -> b.kids.all { isBlankBlock(it) }
        is Block.ListBlock -> b.items.all { it.task == TaskState.NONE && it.blocks.all { sub -> isBlankBlock(sub) } }
        is Block.Table -> b.header.all { flatten(it).isBlank() } &&
            b.rows.all { r -> r.all { flatten(it).isBlank() } }
        is Block.Rule -> false
        is Block.ImageBlock -> false
    }

    fun viewFor(b: Block, depth: Int = 0): View = when (b) {
        is Block.Heading -> headingView(b, depth)
        is Block.Paragraph -> paragraphView(b, depth)
        is Block.CodeBlock -> codeView(b, depth)
        is Block.Quote -> quoteView(b, depth)
        is Block.ListBlock -> listView(b, depth)
        is Block.Table -> tableView(b, depth)
        is Block.Rule -> ruleView(depth)
        is Block.ImageBlock -> imageBlockView(b.alt, b.url, b.line, depth)
    }

    // ------------------------------------------------------------ 标题

    private fun headingView(h: Block.Heading, depth: Int): View {
        val bodyPx = th.sp(prefs, th.bodySp(prefs))
        val size = bodyPx * th.headingScale(h.level)
        val tv = textView(size, th.headingTypeface(prefs))
        tv.letterSpacing = if (h.level <= 2) -0.012f else -0.006f
        tv.includeFontPadding = false

        val sb = SpannableStringBuilder()
        appendInlines(sb, h.inline, size)
        val barW = th.dpf(if (h.level == 1) 4f else 3f)
        sb.setSpan(
            HeadingBarSpan(th.brand, barW, th.dpf(if (h.level <= 2) 11f else 9f), barW / 2f),
            0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        tv.text = sb
        applyMovement(tv, sb)

        val topRatio = when (h.level) {
            1 -> 0.30f
            2 -> 0.95f
            else -> 0.85f
        }
        val lp = linearParams(depth, (size * topRatio).toInt(), (size * 0.34f).toInt())
        tv.layoutParams = lp
        tv.tag = h.line
        // 一级标题用主色文字，形成书面层级
        if (h.level == 1) tv.setTextColor(th.ink)
        return tv
    }

    // ------------------------------------------------------------ 段落

    private fun paragraphView(p: Block.Paragraph, depth: Int): View {
        val bodyPx = th.sp(prefs, th.bodySp(prefs))
        // 段落里若混有图片，就切成"文字/图片/文字…"交替
        val segs = splitImages(p.inline)
        if (segs.size == 1 && segs[0] is TextSeg) {
            val tv = bodyTextView(bodyPx)
            val sb = SpannableStringBuilder()
            appendInlines(sb, (segs[0] as TextSeg).nodes, bodyPx)
            tv.text = sb
            applyMovement(tv, sb)
            tv.layoutParams = linearParams(depth, (bodyPx * 0.30f).toInt(), (bodyPx * 0.30f).toInt())
            tv.tag = p.line
            return tv
        }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.tag = p.line
        col.layoutParams = linearParams(depth, (bodyPx * 0.3f).toInt(), (bodyPx * 0.3f).toInt())
        segs.forEachIndexed { idx, seg ->
            when (seg) {
                is TextSeg -> {
                    if (seg.nodes.isEmpty()) return@forEachIndexed
                    val tv = bodyTextView(bodyPx)
                    val sb = SpannableStringBuilder()
                    appendInlines(sb, seg.nodes, bodyPx)
                    tv.text = sb
                    applyMovement(tv, sb)
                    if (idx > 0) tv.layoutParams = linearParams(0, (bodyPx * 0.34f).toInt(), 0)
                    col.addView(tv)
                }
                is ImgSeg -> col.addView(
                    imageBlockView(seg.alt, seg.url, p.line, 0),
                    linearParams(0, (bodyPx * 0.5f).toInt(), (bodyPx * 0.2f).toInt())
                )
            }
        }
        return col
    }

    private sealed class Seg
    private class TextSeg(val nodes: List<Inline>) : Seg()
    private class ImgSeg(val alt: String, val url: String) : Seg()

    private fun splitImages(nodes: List<Inline>): List<Seg> {
        val out = ArrayList<Seg>()
        val cur = ArrayList<Inline>()
        nodes.forEach { n ->
            if (n is Inline.Image) {
                if (cur.isNotEmpty()) { out += TextSeg(cur.toList()); cur.clear() }
                out += ImgSeg(n.alt, n.url)
            } else cur += n
        }
        if (cur.isNotEmpty()) out += TextSeg(cur.toList())
        if (out.isEmpty()) out += TextSeg(emptyList())
        return out
    }

    // ------------------------------------------------------------ 代码块

    private fun codeView(c: Block.CodeBlock, depth: Int): View {
        val sizePx = th.sp(prefs, th.codeSp(prefs))
        val pad = th.dpf(13f).toInt()
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = th.rounded(th.codeBg, th.dpf(16f), th.line, th.dpf(1f))
            tag = c.line
            layoutParams = linearParams(depth, (sizePx * 0.75f).toInt(), (sizePx * 0.75f).toInt())
        }

        val code = TextView(ctx).apply {
            text = highlightCode(c.text, c.lang)
            setTextColor(th.ink)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
            typeface = Typeface.MONOSPACE
            setLineSpacing(th.dpf(5f), 1f)
            setPadding(pad, th.dpf(11f).toInt(), pad, th.dpf(12f).toInt())
            setTextIsSelectable(true)
        }
        // fillViewport 让短代码也撑满宽度，长代码仍可横向滚动
        val scroll = HorizontalScrollView(ctx).apply {
            isFillViewport = true
            isHorizontalScrollBarEnabled = true
            addView(code, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val copy = TextView(ctx).apply {
            text = "复制代码"
            textSize = 11f
            setTextColor(th.brand)
            setPadding(pad, pad / 2, pad, pad / 2)
            setOnClickListener {
                (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("代码", c.text))
                ctx.hint("代码已复制")
            }
        }
        var wrapped = false
        val toggle = TextView(ctx).apply {
            text = "自动折行"
            textSize = 11f
            setTextColor(th.brand)
            setPadding(pad, pad / 2, pad, pad / 2)
            setOnClickListener {
                wrapped = !wrapped
                text = if (wrapped) "横向滚动" else "自动折行"
                code.layoutParams.width = if (wrapped) (wrap.width - pad * 2).coerceAtLeast(100) else ViewGroup.LayoutParams.WRAP_CONTENT
                code.requestLayout()
            }
        }
        actions.addView(copy); actions.addView(toggle)
        wrap.addView(actions)
        wrap.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        return wrap
    }

    // ------------------------------------------------------------ 引用

    private fun quoteView(q: Block.Quote, depth: Int): View {
        val bodyPx = th.sp(prefs, th.bodySp(prefs))
        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            // 竖条画在背景里：以前放一个高度 MATCH_PARENT 的子 View，
            // 在 ScrollView 的测量下容易出怪高度
            background = th.quotePanel()
            setPadding(
                th.dpf(15f).toInt(), th.dpf(11f).toInt(),
                th.dpf(13f).toInt(), th.dpf(11f).toInt()
            )
            tag = q.line
            layoutParams = linearParams(depth, (bodyPx * 0.5f).toInt(), (bodyPx * 0.5f).toInt())
        }
        q.kids.forEach { inner.addView(viewFor(it, 0)) }
        return inner
    }

    // ------------------------------------------------------------ 列表

    private fun listView(l: Block.ListBlock, depth: Int): View {
        val bodyPx = th.sp(prefs, th.bodySp(prefs))
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            tag = l.line
            layoutParams = linearParams(depth, (bodyPx * 0.26f).toInt(), (bodyPx * 0.34f).toInt())
        }
        var num = l.start
        l.items.forEach { item ->
            col.addView(listItemView(item, l, num, depth, bodyPx))
            if (l.ordered) num++
        }
        return col
    }

    private fun listItemView(
        item: ListItem,
        list: Block.ListBlock,
        ordinal: Int,
        depth: Int,
        bodyPx: Float
    ): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }

        // 记号：任务框 / 数字 / 圆点
        val dotSize = th.dpf(if (depth == 0) 6f else 5f).toInt()
        val isDot = item.task == TaskState.NONE && !list.ordered
        val marker: View = when {
            item.task != TaskState.NONE -> TaskBoxView(
                ctx,
                checked = item.task == TaskState.CHECKED,
                size = th.dpf(17f),
                colorOn = th.brand,
                colorOff = th.line2,
                colorBg = th.card,
                colorCheck = th.brandInk
            ) {
                onTaskToggle(item.markerLine, item.task != TaskState.CHECKED)
            }

            list.ordered -> TextView(ctx).apply {
                text = "$ordinal."
                setTextColor(th.ink3)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, bodyPx * 0.86f)
                typeface = th.bodyTypeface(prefs)
                includeFontPadding = false
            }

            else -> View(ctx).apply {
                // 只画背景，尺寸交给外层 LayoutParams 明确给出
                background = th.rounded(if (depth == 0) th.accent else th.ink3, dotSize / 2f)
            }
        }

        if (item.continuation) marker.visibility = View.INVISIBLE
        val content = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        item.blocks.forEachIndexed { idx, b ->
            // 列表项内的首个段落不再重复段距，避免行距过大
            val v = viewFor(b, 0)
            if (v is TextView && idx == 0) {
                v.setTextColor(if (item.task == TaskState.CHECKED) th.ink3 else th.ink)
                if (item.task == TaskState.CHECKED) v.paintFlags =
                    v.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                v.layoutParams = linearParams(0, 0, 0)
            }
            content.addView(v)
        }
        // 圆点必须给**精确像素尺寸**：裸 View 在 WRAP_CONTENT 下会把父给的最大宽度
        // 全吃掉（getDefaultSize 对 AT_MOST 的行为），于是 weight=1 的文字容器被挤成 0 宽，
        // 表现为"列表没有圆点、文字看不见、还留下一大块空白"。
        val markerLp = LinearLayout.LayoutParams(
            if (isDot) dotSize else ViewGroup.LayoutParams.WRAP_CONTENT,
            if (isDot) dotSize else ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = when {
                item.task != TaskState.NONE -> (bodyPx * 0.34f).toInt()
                isDot -> (bodyPx * 0.42f).toInt()
                else -> 0
            }
            marginEnd = th.dpf(if (item.task != TaskState.NONE) 10f else 9f).toInt()
        }
        row.addView(marker, markerLp)
        row.addView(
            content,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.tag = item.markerLine
        row.layoutParams = linearParams(depth, (bodyPx * 0.12f).toInt(), (bodyPx * 0.14f).toInt())
        if (item.task != TaskState.NONE) row.setOnClickListener {
            onTaskToggle(item.markerLine, item.task != TaskState.CHECKED)
        }
        return row
    }

    // ------------------------------------------------------------ 表格

    /**
     * 表格：列按权重平分可用宽度，单元格允许换行。
     *
     * 之前套了横向滚动且列宽按内容，结果是右侧空出一大块、外框还溢出屏幕。
     * 现在不滚动、等分、可换行——宁可高一点，也不能把内容甩到屏幕外。
     */
    /**
     * 表格。
     *
     * 不再用 TableLayout：列宽由它自己按内容测量，一旦某列文字换行变高，
     * 各单元格自己画的底线就会长短不一、看着像错位。
     * 这里改成"行 = 横向 LinearLayout，每列 weight=1 严格平分"，
     * 分隔线由表格统一画一条通栏的，永远不会断。
     */
    private fun tableView(t: Block.Table, depth: Int): View {
        val bodyPx = th.sp(prefs, th.bodySp(prefs))
        val cellPx = bodyPx * 0.86f
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            tag = t.line
            layoutParams = linearParams(depth, (bodyPx * 0.7f).toInt(), (bodyPx * 0.7f).toInt())
        }

        col.addView(tableRow(t.header, t.align, cellPx, header = true))
        col.addView(ruleLine(th.brand, th.dpf(1.4f).toInt().coerceAtLeast(2)))

        t.rows.forEach { row ->
            col.addView(tableRow(row, t.align, cellPx, header = false))
            col.addView(ruleLine(th.line, th.dpf(1f).toInt().coerceAtLeast(1)))
        }
        if (t.header.size <= 4) return col
        val width = th.dpf(t.header.size * 110f).toInt()
        col.layoutParams = ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        return HorizontalScrollView(ctx).apply {
            layoutParams = linearParams(depth, (bodyPx * 0.7f).toInt(), (bodyPx * 0.7f).toInt())
            tag = t.line
            addView(col)
        }
    }

    private fun tableRow(
        cells: List<List<Inline>>,
        align: List<Align>,
        sizePx: Float,
        header: Boolean
    ): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            if (header) setBackgroundColor(th.brandSoft)
        }
        cells.forEachIndexed { i, content ->
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (i < cells.size - 1) lp.marginEnd = th.dpf(6f).toInt()
            row.addView(cellView(content, sizePx, header, align.getOrElse(i) { Align.LEFT }), lp)
        }
        return row
    }

    private fun ruleLine(color: Int, thickness: Int): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, thickness)
        setBackgroundColor(color)
    }

    private fun cellView(
        nodes: List<Inline>,
        sizePx: Float,
        header: Boolean,
        align: Align
    ): TextView {
        val sb = SpannableStringBuilder()
        appendInlines(sb, nodes, sizePx)
        return TextView(ctx).apply {
            text = sb
            setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
            typeface = if (header) th.sansBold else th.bodyTypeface(prefs)
            setTextColor(if (header) th.brand else th.ink)
            setPadding(0, th.dpf(9f).toInt(), th.dpf(2f).toInt(), th.dpf(9f).toInt())
            gravity = when (align) {
                Align.CENTER -> Gravity.CENTER_HORIZONTAL
                Align.RIGHT -> Gravity.END
                else -> Gravity.START
            }
        }
    }

    // ------------------------------------------------------------ 分割线

    private fun ruleView(depth: Int): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, th.dpf(10f).toInt(), 0, th.dpf(10f).toInt())
        }
        repeat(3) {
            val d = th.dpf(4f).toInt()
            val dot = View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(d, d).apply { marginStart = d; marginEnd = d }
                background = th.rounded(th.accent, d / 2f)
            }
            row.addView(dot)
        }
        row.layoutParams = linearParams(depth, 0, 0)
        return row
    }

    // ------------------------------------------------------------ 图片

    private fun imageBlockView(alt: String, url: String, line: Int, depth: Int): View {
        val displayAlt = alt.take(500)
        val bodyPx = th.sp(prefs, th.bodySp(prefs))
        val frame = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = th.rounded(th.codeBg, th.dpf(12f), th.line, th.dpf(1f))
            tag = line
            layoutParams = linearParams(depth, (bodyPx * 0.6f).toInt(), (bodyPx * 0.4f).toInt())
        }
        val holder = FrameLayout(ctx)
        val place = TextView(ctx).apply {
            text = if (displayAlt.isBlank()) "图片加载中…" else displayAlt
            setTextColor(th.ink3)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(th.dpf(12f).toInt(), th.dpf(34f).toInt(), th.dpf(12f).toInt(), th.dpf(34f).toInt())
        }
        val iv = ImageView(ctx).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            // 原来按字号乘 22 相当于不限高，长图会把整屏顶掉
            maxHeight = (ctx.resources.displayMetrics.heightPixels * 0.55f).toInt()
            minimumHeight = th.dpf(90f).toInt()
        }
        holder.addView(place, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        holder.addView(iv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        frame.addView(holder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        run {
            val cap = TextView(ctx).apply {
                text = displayAlt.ifBlank { url.takeLast(200).substringAfterLast('/') }
                setTextColor(th.ink3)
                textSize = 11.5f
                gravity = Gravity.CENTER
                setPadding(th.dpf(10f).toInt(), th.dpf(7f).toInt(), th.dpf(10f).toInt(), th.dpf(9f).toInt())
            }
            frame.addView(cap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        var request: java.util.concurrent.Future<*>? = null
        var started = false
        var loaded = false
        var resolvedUrl = url
        lateinit var observer: android.view.ViewTreeObserver.OnPreDrawListener
        observer = android.view.ViewTreeObserver.OnPreDrawListener {
            if (!started && frame.isAttachedToWindow) {
                val pos = IntArray(2)
                frame.getLocationOnScreen(pos)
                val height = ctx.resources.displayMetrics.heightPixels
                if (pos[1] < height * 2 && pos[1] + frame.height > -height) {
                    started = true
                    val width = frame.width.coerceAtLeast(128)
                    request = ImageLoader.load(ctx, url, baseDir, width, prefs.loadNetImage,
                        resolve = { raw -> resolveImage?.invoke(raw)?.also { resolvedUrl = it } }) { bitmap, skipped ->
                        if (!frame.isAttachedToWindow || (ctx as? android.app.Activity)?.isDestroyed == true) return@load
                        loaded = true
                        if (frame.viewTreeObserver.isAlive) frame.viewTreeObserver.removeOnPreDrawListener(observer)
                        if (bitmap != null) {
                            iv.setImageBitmap(bitmap)
                            place.visibility = View.GONE
                            iv.setOnClickListener {
                                ctx.startActivity(android.content.Intent(ctx, com.qoder.mdreader.ui.ImageViewerActivity::class.java)
                                    .putExtra("url", resolvedUrl).putExtra("base", baseDir?.path))
                            }
                        } else {
                            iv.visibility = View.GONE
                            place.setPadding(th.dpf(12f).toInt(), th.dpf(9f).toInt(), th.dpf(12f).toInt(), th.dpf(9f).toInt())
                            place.gravity = Gravity.START
                            place.text = if (skipped) "［网络图片未加载］$displayAlt" else "［图片不可用］$displayAlt"
                        }
                    }
                }
            }
            true
        }
        frame.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { if (!loaded) frame.viewTreeObserver.addOnPreDrawListener(observer) }
            override fun onViewDetachedFromWindow(v: View) {
                if (frame.viewTreeObserver.isAlive) frame.viewTreeObserver.removeOnPreDrawListener(observer)
                if (!loaded) { request?.cancel(true); started = false }
            }
        })
        return frame
    }

    private fun highlightCode(text: String, lang: String?): CharSequence {
        if (text.length > 12000 || lang?.lowercase() !in setOf("kotlin", "java", "python", "javascript", "js", "c", "cpp", "json", "sh", "bash")) return text
        val span = SpannableStringBuilder(text)
        val pattern = Regex("\\b(?:val|var|fun|class|object|if|else|for|while|return|import|from|def|function|const|let|public|private|static|void|int|float|true|false|null|None|True|False)\\b|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|(?m://[^\\n]*|#[^\\n]*)")
        pattern.findAll(text).take(1000).forEach { match ->
            span.setSpan(ForegroundColorSpan(if (match.value.startsWith("\"") || match.value.startsWith("'")) th.accent else th.brand), match.range.first, match.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return span
    }

    // ------------------------------------------------------------ 行内 -> Span

    private fun appendInlines(sb: SpannableStringBuilder, nodes: List<Inline>, sizePx: Float) {
        nodes.forEach { n ->
            val start = sb.length
            when (n) {
                is Inline.Text -> {
                    sb.append(n.s)
                    applyBareUrls(sb, start, sb.length, sizePx)
                }
                is Inline.Code -> {
                    sb.append(n.s)
                    sb.setSpan(
                        InlineCodeSpan(
                            bg = th.codeBg,
                            fg = th.brand,
                            border = th.line,
                            textSizePx = sizePx * 0.86f,
                            radiusPx = th.dpf(5f),
                            padHPx = th.dpf(4f),
                            padVPx = th.dpf(1.5f)
                        ),
                        start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                is Inline.Strong -> {
                    appendInlines(sb, n.kids, sizePx)
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is Inline.Em -> {
                    appendInlines(sb, n.kids, sizePx)
                    sb.setSpan(
                        StyleSpan(Typeface.ITALIC), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    sb.setSpan(ForegroundColorSpan(th.brand), start, sb.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                }
                is Inline.Both -> {
                    appendInlines(sb, n.kids, sizePx)
                    sb.setSpan(StyleSpan(Typeface.BOLD_ITALIC), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                is Inline.Strike -> {
                    appendInlines(sb, n.kids, sizePx)
                    sb.setSpan(StrikethroughSpan(), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(th.ink3), start, sb.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                }
                is Inline.Highlight -> {
                    appendInlines(sb, n.kids, sizePx)
                    sb.setSpan(
                        HighlightBgSpan(th.accentSoft, th.dpf(1.5f)),
                        start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    sb.setSpan(ForegroundColorSpan(th.ink), start, sb.length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
                }
                is Inline.Link -> {
                    appendInlines(sb, n.kids, sizePx)
                    val url = n.url
                    sb.setSpan(LinkSpan(url, th.brand) { onLink(it) }, start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(
                        AccentUnderlineSpan(th.accent, th.dpf(1.4f), th.dpf(2.6f)),
                        start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                is Inline.Image -> sb.append(n.alt)   // 段落层已把图片拆成独立 View，这里兜底
                is Inline.Break -> sb.append('\n')
            }
        }
    }

    private fun applyBareUrls(sb: SpannableStringBuilder, from: Int, to: Int, sizePx: Float) {
        val s = sb.substring(from, to)
        urlRegex.findAll(s).forEach { m ->
            val a = from + m.range.first
            val b = from + m.range.last + 1
            var url = m.value
            if (url.startsWith("www.")) url = "https://$url"
            sb.setSpan(LinkSpan(url, th.brand) { onLink(it) }, a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(AccentUnderlineSpan(th.accent, th.dpf(1.2f), th.dpf(2.4f)), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    // ------------------------------------------------------------ 视图工具

    private fun bodyTextView(sizePx: Float): TextView = textView(sizePx, th.bodyTypeface(prefs))

    private fun textView(sizePx: Float, typeface: Typeface): TextView = TextView(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, sizePx)
        this.typeface = typeface
        setTextColor(th.ink)
        includeFontPadding = true
        setLineSpacing(0f, 1f)
        applyLineSpacing(this, sizePx)
    }

    /**
     * 只有真的含链接/可点元素时才挂 LinkMovementMethod。
     * 无条件挂上会让长按选字的手感变怪，而大部分段落根本没有链接。
     */
    fun applyMovement(tv: TextView, sb: SpannableStringBuilder) {
        val clickable = sb.getSpans(0, sb.length, android.text.style.ClickableSpan::class.java).isNotEmpty()
        tv.movementMethod = if (clickable) android.text.method.LinkMovementMethod.getInstance()
        else android.text.method.ArrowKeyMovementMethod.getInstance()
    }

    private fun applyLineSpacing(tv: TextView, sizePx: Float) {
        tv.setLineSpacing((prefs.lineSpacing - 1f) * sizePx * 0.9f, 1f)
    }

    private fun linearParams(depth: Int, top: Int, bottom: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = top
            bottomMargin = bottom
            marginStart = (depth * th.dpf(16f)).toInt()
        }

    /** 找到 tag 里记录着某个源文件行号的 View，用于目录跳转 */
    fun findViewForLine(root: ViewGroup, line: Int): View? {
        if (root.tag == line) return root
        for (i in 0 until root.childCount) {
            val c = root.getChildAt(i)
            if (c.tag == line) return c
            if (c is ViewGroup) {
                val r = findViewForLine(c, line)
                if (r != null) return r
            }
        }
        return null
    }

    /**
     * 只翻转某一行的待办显示，避免两万字文档点一下就把整棵 View 树重建。
     *
     * @return 是否真的找到了对应行；找不到时调用方应退回全量重渲染
     */
    fun setTaskState(root: ViewGroup, line: Int, checked: Boolean): Boolean {
        fun taskRow(group: ViewGroup): ViewGroup? {
            if (group.tag == line && (0 until group.childCount).any { group.getChildAt(it) is TaskBoxView }) return group
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i) as? ViewGroup ?: continue
                taskRow(child)?.let { return it }
            }
            return null
        }
        val row = taskRow(root) ?: return false
        var touched = false
        for (i in 0 until row.childCount) {
            when (val c = row.getChildAt(i)) {
                is TaskBoxView -> { c.setCheckedState(checked); touched = true }
                is LinearLayout -> {
                    val first = if (c.childCount > 0) c.getChildAt(0) else null
                    if (first is TextView) {
                        first.setTextColor(if (checked) th.ink3 else th.ink)
                        first.paintFlags = if (checked) first.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
                        else first.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
                        touched = true
                    }
                }
                else -> {}
            }
        }
        return touched
    }

    /** 目标 View 距离滚动容器内容顶部的像素偏移 */
    fun offsetFrom(target: View, scroll: View): Int {
        val a = IntArray(2)
        val b = IntArray(2)
        target.getLocationInWindow(a)
        scroll.getLocationInWindow(b)
        return (a[1] - b[1] + scroll.scrollY).coerceAtLeast(0)
    }
}

/** 任务列表方框：自绘，避免 CheckBox 的 Material 水波纹喧宾夺主 */
class TaskBoxView(
    context: Context,
    private var checked: Boolean,
    private val size: Float,
    private val colorOn: Int,
    private val colorOff: Int,
    private val colorBg: Int,
    private val colorCheck: Int,
    private val onToggle: () -> Unit
) : View(context) {

    /** 局部刷新用：点待办时只改这一个方框，不重建整篇文档 */
    fun setCheckedState(v: Boolean) {
        if (checked == v) return
        checked = v
        invalidate()
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private val checkPath by lazy { Path0() }
    private val corner = size * 0.30f

    init {
        val touch = (size * 0.42f)
        setPadding(touch.toInt(), touch.toInt(), touch.toInt(), touch.toInt())
        isClickable = true
        setOnClickListener {
            onToggle()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = (size + paddingLeft + paddingRight).toInt()
        setMeasuredDimension(w, w)
    }

    override fun onDraw(c: Canvas) {
        val left = paddingLeft.toFloat()
        val top = paddingTop.toFloat()
        val r = bounds.apply { set(left, top, left + size, top + size) }
        paint.style = Paint.Style.FILL
        paint.color = if (checked) colorOn else colorBg
        c.drawRoundRect(r, corner, corner, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = size * 0.11f
        paint.color = if (checked) colorOn else colorOff
        c.drawRoundRect(r, corner, corner, paint)
        if (checked) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = size * 0.15f
            paint.color = colorCheck
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            c.drawPath(checkPath, paint)
        }
    }

    /** 勾的三段折线 */
    private fun Path0(): android.graphics.Path = android.graphics.Path().apply {
        val l = paddingLeft.toFloat()
        val t = paddingTop.toFloat()
        moveTo(l + size * 0.24f, t + size * 0.52f)
        lineTo(l + size * 0.42f, t + size * 0.70f)
        lineTo(l + size * 0.76f, t + size * 0.30f)
    }
}
