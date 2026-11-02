package com.qoder.mdreader.core

/**
 * 手写 Markdown 解析器，覆盖日常写作用的语法子集。
 *
 * 每个块都带 line = 源文件里的行号，这是"点待办能精确改回原文那一行"的基础。
 */
sealed class Block {
    abstract val line: Int

    /** # .. ###### */
    data class Heading(val level: Int, val inline: List<Inline>, override val line: Int) : Block()

    /** 普通段落；inline 里可能含 Break 表示段内换行 */
    data class Paragraph(val inline: List<Inline>, override val line: Int) : Block()

    /** ``` 围栏代码块，或缩进 4 空格代码块 */
    data class CodeBlock(val lang: String?, val text: String, override val line: Int) : Block()

    /** > 引用，内部可再嵌任意块 */
    data class Quote(val kids: List<Block>, override val line: Int) : Block()

    /** 列表（含任务列表） */
    data class ListBlock(
        val ordered: Boolean,
        val start: Int,
        val items: List<ListItem>,
        override val line: Int
    ) : Block()

    /** GFM 表格 */
    data class Table(
        val header: List<List<Inline>>,
        val align: List<Align>,
        val rows: List<List<List<Inline>>>,
        override val line: Int
    ) : Block()

    data class Rule(override val line: Int) : Block()

    /** 独占一行的 ![](url) */
    data class ImageBlock(val alt: String, val url: String, override val line: Int) : Block()
}

data class ListItem(
    val blocks: List<Block>,
    val task: TaskState,
    /** 任务标记所在源文件行号，勾选时改这一行 */
    val markerLine: Int,
    val continuation: Boolean = false
)

enum class TaskState { NONE, UNCHECKED, CHECKED }
enum class Align { LEFT, CENTER, RIGHT }

sealed class Inline {
    data class Text(val s: String) : Inline()
    data class Code(val s: String) : Inline()
    data class Strong(val kids: List<Inline>) : Inline()
    data class Em(val kids: List<Inline>) : Inline()
    data class Both(val kids: List<Inline>) : Inline()
    data class Strike(val kids: List<Inline>) : Inline()
    /** ==高亮==，Typora / Obsidian / 微信笔记里常见 */
    data class Highlight(val kids: List<Inline>) : Inline()
    data class Link(val kids: List<Inline>, val url: String) : Inline()
    data class Image(val alt: String, val url: String) : Inline()
    data object Break : Inline()
}

/** 行内树拍平成纯文本 */
fun flatten(inlines: List<Inline>): String {
    val sb = StringBuilder()
    fun walk(ns: List<Inline>) {
        ns.forEach { n ->
            when (n) {
                is Inline.Text -> sb.append(n.s)
                is Inline.Code -> sb.append(n.s)
                is Inline.Strong -> walk(n.kids)
                is Inline.Em -> walk(n.kids)
                is Inline.Both -> walk(n.kids)
                is Inline.Strike -> walk(n.kids)
                is Inline.Highlight -> walk(n.kids)
                is Inline.Link -> walk(n.kids)
                is Inline.Image -> sb.append(n.alt)
                is Inline.Break -> sb.append('\n')
            }
        }
    }
    walk(inlines)
    return sb.toString()
}

fun blocksToPlainText(blocks: List<Block>): String = buildString {
    blocks.forEach { b ->
        when (b) {
            is Block.Heading -> append(flatten(b.inline)).append("\n\n")
            is Block.Paragraph -> append(flatten(b.inline)).append("\n\n")
            is Block.CodeBlock -> append(b.text).append("\n\n")
            is Block.Quote -> append(blocksToPlainText(b.kids))
            is Block.ListBlock -> {
                b.items.forEach {
                    when (it.task) {
                        TaskState.CHECKED -> append("[x] ")
                        TaskState.UNCHECKED -> append("[ ] ")
                        TaskState.NONE -> append(if (b.ordered) "- " else "- ")
                    }
                    append(blocksToPlainText(it.blocks).trim()).append("\n")
                }
                append("\n")
            }
            is Block.Table -> {
                b.header.forEach { append(flatten(it)).append(" | ") }
                append('\n')
                b.rows.forEach { r -> r.forEach { append(flatten(it)).append(" | ") }; append('\n') }
                append('\n')
            }
            is Block.Rule -> append("———\n\n")
            is Block.ImageBlock -> append(b.alt).append("\n\n")
        }
    }
}

object MarkdownParser {
    private data class ParseContext(val references: Map<String, String>, val footnotes: Map<String, Int>, var inlineDepth: Int = 0, var work: Int = 0)
    private val context = ThreadLocal<ParseContext>()
    private fun referenceKey(s: String) = s.trim().lowercase().replace(Regex("\\s+"), " ")


    // ---- 行级模式（全部预编译）----
    private val ATX = Regex("^(#{1,6})[ \\t]+(.*?)[ \\t]*#*[ \\t]*$")
    private val ATX_EMPTY = Regex("^(#{1,6})[ \\t]*$")
    private val FENCE = Regex("^( {0,3})(`{3,}|~{3,})[ \\t]*([^`\\s]*)[^\\n]*$")
    private val HR = Regex("^ {0,3}([-*_])([ \\t]*\\1){2,}[ \\t]*$")
    private val SETEXT_EQ = Regex("^ {0,3}=+[ \\t]*$")
    private val SETEXT_DASH = Regex("^ {0,3}-+[ \\t]*$")
    private val BULLET = Regex("^(\\s*)([-*+•·])([ \\t]+)(.*)$")
    private val ORDERED = Regex("^(\\s*)(\\d{1,9})([.)])([ \\t]+)(.*)$")
    private val QUOTE = Regex("^ {0,3}>[ \\t]?(.*)$")
    private val TASK = Regex("^\\[([ xX✓√])\\][ \\t]*(.*)$")
    private val TABLE_SEP = Regex("^\\s*\\|?[ \\t]*:?-{2,}:?[ \\t]*(\\|[ \\t]*:?-{2,}:?[ \\t]*)*\\|?\\s*$")
    private val ONLY_IMAGE = Regex("^ {0,3}!\\[([^\\]]*)\\]\\(([^)]*)\\)[ \\t]*$")
    private val INDENT_CODE = Regex("^(?:[ ]{4}|\\t)(.*)$")
    private val BLANK = Regex("^\\s*$")

    private const val ESCAPABLE = "\\`*_{}[]()#+-.!<>|~\"'&\$^"

    fun parse(src: String, singleBreakNewline: Boolean = true): List<Block> {
        require(src.length <= SafeIo.DOCUMENT_LIMIT) { "文档太大" }
        val normalized = InlineHtml.stripFrontMatterKeepingLines(src.replace("\r\n", "\n").replace('\r', '\n'))
        require(normalized.count { it == '\n' } < 100_000) { "文档超过 100,000 行限制" }
        val raw = normalized.split('\n')
        val lines = ArrayList<Ln>(raw.size)
        val refs = LinkedHashMap<String, String>()
        val notes = LinkedHashMap<String, Pair<Int, String>>()
        val definition = Regex("^ {0,3}\\[([^]]+)]:\\s*(.*)$")
        var fence: String? = null
        for (i in raw.indices) {
            val marker = FENCE.matchEntire(raw[i])?.groupValues?.get(2)
            if (marker != null) {
                if (fence == null) fence = marker else if (marker[0] == fence!![0] && marker.length >= fence!!.length && raw[i].trim().all { it == marker[0] }) fence = null
                lines += Ln(raw[i], i); continue
            }
            val match = if (fence == null) definition.matchEntire(raw[i]) else null
            if (match != null) {
                val id = match.groupValues[1]
                if (id.startsWith("^")) notes.putIfAbsent(referenceKey(id.drop(1)), i to match.groupValues[2])
                else refs.putIfAbsent(referenceKey(id), cleanUrl(match.groupValues[2]))
                lines += Ln("", i)
            } else lines += Ln(raw[i], i)
        }
        context.set(ParseContext(refs, notes.keys.withIndex().associate { it.value to it.index + 1 }))
        try {
            val result = parseBlocks(lines, singleBreakNewline, 0).toMutableList()
            notes.forEach { (id, note) ->
                val number = checkNotNull(context.get()).footnotes[id] ?: 1
                result += Block.Paragraph(listOf(Inline.Link(listOf(Inline.Text("[$number] ")), "#footnote:$id")) + parseInline(note.second), note.first)
            }
            return result
        } finally { context.remove() }
    }

    private class Ln(val text: String, val src: Int) {
        val blank get() = BLANK.matches(text)
    }

    // ============================================================ 块解析

    private fun parseBlocks(lines: List<Ln>, breaks: Boolean, depth: Int): List<Block> {
        if (depth >= 48) return lines.filter { !it.blank }.map { Block.Paragraph(listOf(Inline.Text(it.text)), it.src) }
        val out = ArrayList<Block>()
        var i = 0
        val n = lines.size

        loop@ while (i < n) {
            val ln = lines[i]
            if (ln.blank) { i++; continue@loop }
            val t = ln.text
            val tt = t.trimEnd()

            // ---- 围栏代码块 ----
            val fm = FENCE.matchEntire(tt)
            if (fm != null) {
                val marker = fm.groupValues[2]
                val lang = fm.groupValues[3].trim().ifEmpty { null }
                val closer = Regex("^ {0,3}" + Regex.escape(marker[0].toString()) + "{" + marker.length + ",}[ \\t]*$")
                val body = ArrayList<String>()
                var j = i + 1
                while (j < n && !closer.matches(lines[j].text.trimEnd())) {
                    body += dedent(lines[j].text, fm.groupValues[1].length)
                    j++
                }
                out += Block.CodeBlock(lang, body.joinToString("\n"), ln.src)
                i = if (j < n) j + 1 else n
                continue@loop
            }

            // ---- ATX 标题 ----
            val am = ATX.matchEntire(tt) ?: ATX_EMPTY.matchEntire(tt)
            if (am != null) {
                val level = am.groupValues[1].length
                val content = am.groupValues.getOrNull(2)?.trim().orEmpty()
                out += Block.Heading(level, if (content.isEmpty()) emptyList() else parseInline(content), ln.src)
                i++
                continue@loop
            }

            // ---- 引用 ----
            if (QUOTE.matches(t)) {
                val inner = ArrayList<Ln>()
                var j = i
                while (j < n) {
                    val q = QUOTE.matchEntire(lines[j].text)
                    if (q != null) {
                        inner += Ln(q.groupValues[1], lines[j].src)
                    } else if (!lines[j].blank && depth < 4 && isLazyContinuation(lines[j].text)) {
                        inner += Ln(lines[j].text, lines[j].src)
                    } else break
                    j++
                }
                out += Block.Quote(parseBlocks(inner, breaks, depth + 1), ln.src)
                i = j
                continue@loop
            }

            // ---- 表格 ----
            if (t.contains('|') && i + 1 < n && TABLE_SEP.matches(lines[i + 1].text)) {
                val res = parseTable(lines, i)
                if (res != null) {
                    out += res.first
                    i = res.second
                    continue@loop
                }
            }

            // ---- 宽松表格：从 Excel / Notion 粘来的表常常没有 |---| 分隔行 ----
            if (pipeRow(t) && i + 1 < n && pipeRow(lines[i + 1].text) && sameColCount(t, lines[i + 1].text)) {
                val res = parseLooseTable(lines, i)
                if (res != null) {
                    out += res.first
                    i = res.second
                    continue@loop
                }
            }

            // ---- 独占一行的图片 ----
            val im = ONLY_IMAGE.matchEntire(tt)
            if (im != null) {
                out += Block.ImageBlock(im.groupValues[1], cleanUrl(im.groupValues[2]), ln.src)
                i++
                continue@loop
            }

            if (HR.matches(tt)) { out += Block.Rule(ln.src); i++; continue@loop }
            // ---- 列表 ----
            if (BULLET.matchEntire(t) != null || ORDERED.matchEntire(t) != null) {
                i = collectList(lines, i, breaks, depth, out)
                continue@loop
            }

            // ---- 分割线 ----
            if (HR.matches(tt)) {
                out += Block.Rule(ln.src)
                i++
                continue@loop
            }

            // ---- 缩进 4 空格代码块 ----
            if (INDENT_CODE.matchEntire(t) != null) {
                val body = ArrayList<String>()
                var k = i
                while (k < n) {
                    val m = INDENT_CODE.matchEntire(lines[k].text)
                    if (m != null) { body += m.groupValues[1]; k++; continue }
                    if (lines[k].blank && k + 1 < n && INDENT_CODE.matchEntire(lines[k + 1].text) != null) {
                        body += ""; k++; continue
                    }
                    break
                }
                while (body.isNotEmpty() && body.last().isBlank()) body.removeAt(body.size - 1)
                out += Block.CodeBlock(null, body.joinToString("\n"), ln.src)
                i = k
                continue@loop
            }

            // ---- 段落（含 setext 标题识别）----
            i = readParagraph(lines, i, breaks, out)
        }
        return out
    }

    /** 段落可能吃掉多行；若紧随其后是 === / --- 则整段升级为 setext 标题 */
    private fun readParagraph(lines: List<Ln>, start: Int, breaks: Boolean, out: ArrayList<Block>): Int {
        val parts = ArrayList<String>()
        val srcs = ArrayList<Int>()
        var i = start
        val n = lines.size

        fun heading(level: Int, line: Int): Block =
            Block.Heading(level, parseInline(parts.joinToString(" ") { it.trimEnd() }), line)

        while (i < n) {
            val cur = lines[i]
            if (cur.blank) break
            if (i > start && startsNewBlock(lines, i)) break
            parts += cur.text.trimStart()      // 保留行尾两空格，那是标准 Markdown 的硬换行
            srcs += cur.src
            i++
            if (i < n && parts.size <= 3) {
                val nx = lines[i].text
                if (SETEXT_EQ.matches(nx)) {
                    out += heading(1, srcs.first())
                    return i + 1
                }
                if (SETEXT_DASH.matches(nx)) {
                    // 紧跟段落的 --- 是二级标题（setext 优先于分割线）；
                    // 独立成段的 --- 会在块循环里走分割线分支
                    out += heading(2, srcs.first())
                    return i + 1
                }
            }
        }
        if (parts.isEmpty()) return start + 1
        val sb = StringBuilder()
        parts.forEachIndexed { idx, raw ->
            if (idx > 0) {
                val hard = breaks || parts[idx - 1].endsWith("  ")
                sb.append(if (hard) '\n' else ' ')
            }
            sb.append(raw.trimEnd())
        }
        out += Block.Paragraph(collapseBreaks(parseInline(sb.toString())), srcs.first())
        return i
    }

    /** 段落续行判断：某行是否开启了新的块 */
    private fun startsNewBlock(lines: List<Ln>, idx: Int): Boolean {
        val t = lines[idx].text
        val tt = t.trimEnd()
        if (FENCE.matchEntire(tt) != null) return true
        if (ATX.matchEntire(tt) != null || ATX_EMPTY.matchEntire(tt) != null) return true
        if (HR.matches(tt)) return true
        if (QUOTE.matches(t)) return true
        if (BULLET.matchEntire(t) != null || ORDERED.matchEntire(t) != null) return true
        if (ONLY_IMAGE.matchEntire(tt) != null) return true
        if (t.contains('|') && idx + 1 < lines.size && TABLE_SEP.matches(lines[idx + 1].text)) return true
        if (idx + 1 < lines.size && SETEXT_EQ.matches(lines[idx + 1].text)) return true
        if (INDENT_CODE.matchEntire(t) != null) return true
        return false
    }

    private fun isLazyContinuation(text: String): Boolean {
        val t = text.trimStart()
        if (t.isEmpty()) return false
        return !t.startsWith('#') && !t.startsWith('>') &&
            BULLET.matchEntire(text) == null && ORDERED.matchEntire(text) == null
    }

    private fun dedent(text: String, by: Int): String {
        if (by <= 0) return text
        var cut = 0
        var i = 0
        while (i < text.length && cut < by && text[i] == ' ') { i++; cut++ }
        return text.substring(i)
    }

    private fun collapseBreaks(list: List<Inline>): List<Inline> {
        val out = ArrayList<Inline>(list.size)
        list.forEach {
            if (it is Inline.Break && (out.isEmpty() || out.last() is Inline.Break)) return@forEach
            out += it
        }
        while (out.isNotEmpty() && out.last() is Inline.Break) out.removeAt(out.size - 1)
        return out
    }

    // ============================================================ 列表

    private fun collectList(
        lines: List<Ln>,
        start: Int,
        breaks: Boolean,
        depth: Int,
        sink: ArrayList<Block>
    ): Int {
        val firstText = lines[start].text
        val firstOrdered = ORDERED.matchEntire(firstText)
        val firstBullet = if (firstOrdered == null) BULLET.matchEntire(firstText) else null
        val ordered = firstOrdered != null
        val baseIndent = leadingWidth(if (ordered) firstOrdered!!.groupValues[1] else firstBullet!!.groupValues[1])
        val startNum = if (ordered) firstOrdered!!.groupValues[2].toIntOrNull() ?: 1 else 1
        val items = ArrayList<ListItem>()
        var i = start
        val n = lines.size

        while (i < n) {
            val text = lines[i].text
            val bm = if (ordered) null else BULLET.matchEntire(text)
            val om = if (ordered) ORDERED.matchEntire(text) else null
            if (bm == null && om == null) {
                if (lines[i].blank) {
                    // 松列表：空行后仍有同级项则继续
                    var k = i + 1
                    while (k < n && lines[k].blank) k++
                    if (k < n) {
                        val nxt = lines[k].text
                        val isItem = BULLET.matchEntire(nxt) != null || ORDERED.matchEntire(nxt) != null
                        if (isItem && leadingWidth(nxt) == baseIndent) { i = k; continue }
                    }
                }
                break
            }
            val m = bm ?: om!!
            val indent = leadingWidth(m.groupValues[1])
            if (indent != baseIndent) break

            val markerLen: Int
            val spaces: String
            val content: String
            if (bm != null) {
                markerLen = bm.groupValues[2].length
                spaces = bm.groupValues[3]
                content = bm.groupValues[4]
            } else {
                markerLen = om!!.groupValues[2].length + om.groupValues[3].length
                spaces = om.groupValues[4]
                content = om.groupValues[5]
            }
            val contentCol = indent + markerLen + leadingWidth(spaces)
            val markerSrc = lines[i].src

            val child = ArrayList<Ln>()
            child += Ln(content, markerSrc)
            var j = i + 1
            while (j < n) {
                val cl = lines[j]
                if (cl.blank) {
                    var k = j + 1
                    while (k < n && lines[k].blank) k++
                    if (k < n && leadingWidth(lines[k].text) >= contentCol) {
                        child += Ln("", cl.src)
                        j++
                        continue
                    }
                    break
                }
                val lw = leadingWidth(cl.text)
                val isItemStart = BULLET.matchEntire(cl.text) != null || ORDERED.matchEntire(cl.text) != null
                if (isItemStart && lw <= indent) break
                if (lw >= contentCol) {
                    child += Ln(dropIndent(cl.text, contentCol), cl.src)
                    j++
                } else if (!isItemStart && depth < 6) {
                    // 惰性续行：同一段落的第二行不写缩进也算
                    child += Ln(cl.text.trimStart(), cl.src)
                    j++
                } else break
            }

            var task = TaskState.NONE
            var inner = child
            TASK.matchEntire(child.first().text)?.let {
                val mark = it.groupValues[1]
                task = if (mark == " ") TaskState.UNCHECKED else TaskState.CHECKED
                inner = ArrayList(child)
                inner[0] = Ln(it.groupValues[2], markerSrc)
            }

            items += ListItem(parseBlocks(inner, breaks, depth + 1), task, markerSrc)
            i = j
        }

        sink += Block.ListBlock(ordered, startNum, items, lines[start].src)
        return if (i == start) start + 1 else i
    }

    private fun leadingWidth(s: String): Int {
        var w = 0
        for (c in s) {
            if (c == ' ') w++
            else if (c == '\t') w += 4 - (w % 4)
            else break
        }
        return w
    }

    private fun dropIndent(s: String, by: Int): String {
        if (by <= 0) return s
        var w = 0
        var i = 0
        while (i < s.length && w < by) {
            val c = s[i]
            if (c == ' ') { w++; i++ }
            else if (c == '\t') { w += 4 - (w % 4); i++ }
            else break
        }
        return s.substring(i)
    }

    // ============================================================ 表格

    private fun parseTable(lines: List<Ln>, start: Int): Pair<Block.Table, Int>? {
        val headCells = splitRow(lines[start].text) ?: return null
        val seps = splitRow(lines[start + 1].text) ?: return null
        if (seps.isEmpty()) return null
        val align = seps.map { cell ->
            val c = cell.trim()
            when {
                c.startsWith(":") && c.endsWith(":") -> Align.CENTER
                c.endsWith(":") -> Align.RIGHT
                else -> Align.LEFT
            }
        }
        val size = minOf(headCells.size, seps.size)
        val header = (0 until size).map { parseInline(headCells[it].trim()) }
        val rows = ArrayList<List<List<Inline>>>()
        var i = start + 2
        while (i < lines.size && !lines[i].blank && lines[i].text.contains('|')) {
            if (startsNewBlock(lines, i) && i > start + 2) break
            val cells = splitRow(lines[i].text) ?: break
            val row = ArrayList<List<Inline>>()
            for (k in header.indices) row += parseInline(cells.getOrElse(k) { "" }.trim())
            rows += row
            i++
        }
        return Block.Table(header, align.take(size), rows, lines[start].src) to i
    }

    /** 一行是否像表格行：以竖线开头且至少两个竖线，避免把普通"a | b"句子误判成表 */
    private fun pipeRow(text: String): Boolean {
        val t = text.trim()
        return t.startsWith("|") && t.count { it == '|' } >= 2
    }

    private fun sameColCount(a: String, b: String): Boolean {
        val ca = splitRow(a.trim()) ?: return false
        val cb = splitRow(b.trim()) ?: return false
        return ca.size == cb.size && ca.size >= 2
    }

    /** 无分隔行的表：第一行当表头，之后每行列数一致就算一行数据 */
    private fun parseLooseTable(lines: List<Ln>, start: Int): Pair<Block.Table, Int>? {
        val first = splitRow(lines[start].text.trim()) ?: return null
        if (first.size < 2) return null
        val rows = ArrayList<List<List<Inline>>>()
        var i = start + 1
        while (i < lines.size) {
            val cur = lines[i].text
            if (cur.isBlank() || !cur.trim().startsWith("|")) break
            val cells = splitRow(cur.trim()) ?: break
            if (cells.size != first.size) break
            rows += (0 until first.size).map { parseInline(cells[it].trim()) }
            i++
        }
        if (rows.isEmpty()) return null
        val header = first.map { parseInline(it.trim()) }
        return Block.Table(header, List(first.size) { Align.LEFT }, rows, lines[start].src) to i
    }

    /** 拆一行表格，支持 \| 转义竖线 */
    private fun splitRow(text: String): List<String>? {
        var t = text.trim()
        if (t.startsWith("|")) t = t.substring(1)
        if (t.endsWith("|") && !t.endsWith("\\|")) t = t.substring(0, t.length - 1)
        val cells = ArrayList<String>()
        val buf = StringBuilder()
        var i = 0
        var codeRun = 0
        while (i < t.length) {
            val c = t[i]
            if (c == '`') {
                val run = runLength(t, i, t.length, '`')
                if (codeRun == 0) codeRun = run else if (run == codeRun) codeRun = 0
                buf.append(t.substring(i, i + run)); i += run; continue
            }
            if (c == '\\' && i + 1 < t.length && t[i + 1] == '|') { buf.append('|'); i += 2; continue }
            if (c == '|' && codeRun == 0) { cells += buf.toString(); buf.setLength(0); i++; continue }
            buf.append(c); i++
        }
        cells += buf.toString()
        return cells.ifEmpty { null }
    }

    // ============================================================ 行内解析
    //
    // 两趟：先把 code / 链接 / 图片 / 自动链接切成"原子"，剩下的 * _ ~ 用栈配对。
    // 必须栈式配对，*外层 **内层** 收尾* 才不会把内层星号当成结束符。

    private sealed class Pt {
        class Txt(var v: String) : Pt()
        class Atm(val n: Inline) : Pt()
        class Del(val ch: Char, var len: Int, val open: Boolean, val close: Boolean) : Pt()
    }

    private val PUNCT = ("!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~" +
        "\u3000\u3001\uff01\uff1f\uff1b\uff1a\u201c\u201d\u2018\u2019" +
        "\uff08\uff09\u3010\u3011\u300a\u300b\u2026\u2014\uff5e\u00b7\uff0c").toSet()

    private fun isWs(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r'
    private fun isPunct(c: Char) = c in PUNCT || when (Character.getType(c)) {
        Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
        Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
        Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
        Character.OTHER_PUNCTUATION.toInt() -> true
        else -> false
    }

    fun parseInline(s: String): List<Inline> {
        if (s.length > 32000) return listOf(Inline.Text(s))
        val ownsContext = context.get() == null
        if (ownsContext) context.set(ParseContext(emptyMap(), emptyMap()))
        checkNotNull(context.get()).work = 0
        try {
            val cleaned = sanitizeOutsideCode(s)
            return parseInlineRange(cleaned, 0, cleaned.length)
        } finally { if (ownsContext) context.remove() }
    }

    private fun sanitizeOutsideCode(s: String): String {
        if (!s.contains('<')) return s
        val out = StringBuilder()
        var at = 0
        var plain = 0
        while (at < s.length) {
            if (s[at] != '`') { at++; continue }
            val count = runLength(s, at, s.length, '`')
            val close = findRun(s, at + count, s.length, '`', count)
            if (close < 0) { at += count; continue }
            out.append(InlineHtml.sanitize(s.substring(plain, at)))
            out.append(s.substring(at, close + count))
            at = close + count; plain = at
        }
        return out.append(InlineHtml.sanitize(s.substring(plain))).toString()
    }

    private fun parseInlineRange(s: String, from: Int, to: Int): List<Inline> {
        val c = context.get()
        if (c != null && c.inlineDepth >= 32) return listOf(Inline.Text(s.substring(from, to)))
        if (c != null) c.inlineDepth++
        try { return buildInline(emphasis(tokenize(s, from, to))) }
        finally { if (c != null) c.inlineDepth-- }
    }

    /** 切成文本 / 原子 / 强调符号三类片段 */
    private fun tokenize(s: String, from: Int, to: Int): ArrayList<Pt> {
        val pts = ArrayList<Pt>()
        val buf = StringBuilder()
        var i = from
        fun flush() {
            if (buf.isNotEmpty()) { pts += Pt.Txt(buf.toString()); buf.setLength(0) }
        }

        while (i < to) {
            val state = context.get()
            if (state != null && state.work > 1_000_000) { buf.append(s, i, to); break }
            val c = s[i]
            when {
                c == '\\' && i + 1 < to && s[i + 1] in ESCAPABLE -> { buf.append(s[i + 1]); i += 2 }

                c == '`' -> {
                    val open = runLength(s, i, to, '`')
                    val close = findRun(s, i + open, to, '`', open)
                    if (close < 0) { buf.append(c); i++ }
                    else {
                        flush()
                        pts += Pt.Atm(Inline.Code(s.substring(i + open, close).replace('\n', ' ').trim()))
                        i = close + open
                    }
                }

                c == '!' && i + 1 < to && s[i + 1] == '[' -> {
                    val p = tryImage(s, i + 1, to)
                    if (p == null) { buf.append(c); i++ } else { flush(); pts += Pt.Atm(p.first); i = p.second }
                }

                c == '[' -> {
                    val p = tryLink(s, i, to)
                    if (p == null) { buf.append(c); i++ } else { flush(); pts += Pt.Atm(p.first); i = p.second }
                }

                c == '<' -> {
                    val end = findChar(s, '>', i + 1, to)
                    val body = if (end > 0) s.substring(i + 1, end) else ""
                    if (end > 0 && (body.startsWith("http://") || body.startsWith("https://") || body.startsWith("mailto:"))) {
                        flush()
                        pts += Pt.Atm(Inline.Link(listOf(Inline.Text(body.removePrefix("mailto:"))), body))
                        i = end + 1
                    } else { buf.append(c); i++ }
                }

                c == '\n' -> { flush(); pts += Pt.Atm(Inline.Break); i++ }

                c == '*' || c == '_' || c == '~' || c == '=' -> {
                    val len = runLength(s, i, to, c)
                    if ((c == '~' || c == '=') && len < 2) { buf.append(c); i++; continue }
                    val prev = if (i > from) s[i - 1] else ' '
                    val next = if (i + len < to) s[i + len] else ' '
                    val left = !isWs(next) && (!isPunct(next) || isWs(prev) || isPunct(prev))
                    val right = !isWs(prev) && (!isPunct(prev) || isWs(next) || isPunct(next))
                    val canOpen = if (c == '_') left && (!right || isPunct(prev)) else left
                    val canClose = if (c == '_') right && (!left || isPunct(next)) else right
                    flush()
                    pts += Pt.Del(c, len, canOpen, canClose)
                    i += len
                }

                else -> { buf.append(c); i++ }
            }
        }
        flush()
        return pts
    }

    /** 从每个闭合符往回找最近的开启符，配对后把中间内容递归解析 */
    private fun emphasis(pts: ArrayList<Pt>): ArrayList<Pt> {
        data class Frame(val opener: Pt.Del?, val body: ArrayList<Pt> = ArrayList())
        val stack = ArrayList<Frame>()
        stack += Frame(null)
        for (p in pts) {
            if (p !is Pt.Del) { stack.last().body += p; continue }
            val match = if (p.close) (stack.lastIndex downTo 1).firstOrNull { stack[it].opener?.ch == p.ch } else null
            if (match != null) {
                while (stack.lastIndex > match) {
                    val unfinished = stack.removeAt(stack.lastIndex)
                    stack.last().body += Pt.Txt(unfinished.opener!!.ch.toString().repeat(unfinished.opener.len))
                    stack.last().body.addAll(unfinished.body)
                }
                val frame = stack.removeAt(stack.lastIndex)
                val open = frame.opener!!
                val used = minOf(open.len, p.len)
                val nodes = buildInline(frame.body)
                if (open.len > used) stack.last().body += Pt.Txt(open.ch.toString().repeat(open.len - used))
                if (nodes.isEmpty()) stack.last().body += Pt.Txt(open.ch.toString().repeat(used * 2))
                else stack.last().body += Pt.Atm(when {
                    p.ch == '~' -> Inline.Strike(nodes)
                    p.ch == '=' -> Inline.Highlight(nodes)
                    used >= 3 -> Inline.Both(nodes)
                    used == 2 -> Inline.Strong(nodes)
                    else -> Inline.Em(nodes)
                })
                if (p.len > used) stack.last().body += Pt.Txt(p.ch.toString().repeat(p.len - used))
            } else if (p.open && stack.size < 48) stack += Frame(p)
            else stack.last().body += Pt.Txt(p.ch.toString().repeat(p.len))
        }
        while (stack.size > 1) {
            val frame = stack.removeAt(stack.lastIndex)
            stack.last().body += Pt.Txt(frame.opener!!.ch.toString().repeat(frame.opener.len))
            stack.last().body.addAll(frame.body)
        }
        return stack[0].body
    }

    private fun buildInline(pts: List<Pt>): List<Inline> {
        val out = ArrayList<Inline>()
        val sb = StringBuilder()
        pts.forEach { p ->
            when (p) {
                is Pt.Txt -> sb.append(p.v)
                is Pt.Atm -> {
                    if (sb.isNotEmpty()) { out += Inline.Text(sb.toString()); sb.setLength(0) }
                    out += p.n
                }
                is Pt.Del -> if (p.len > 0) sb.append(p.ch.toString().repeat(p.len))
            }
        }
        if (sb.isNotEmpty()) out += Inline.Text(sb.toString())
        return out
    }

    private fun runLength(s: String, at: Int, to: Int, ch: Char): Int {
        var k = at
        while (k < to && s[k] == ch) k++
        return k - at
    }

    private fun findChar(s: String, ch: Char, from: Int, to: Int): Int {
        val state = context.get()
        for (index in from until to) {
            if (state != null && ++state.work > 1_000_000) return -1
            if (s[index] == ch) return index
        }
        return -1
    }

    private fun findRun(s: String, from: Int, to: Int, ch: Char, min: Int): Int {
        var k = from
        while (k < to) {
            context.get()?.let { if (++it.work > 1_000_000) return -1 }
            if (s[k] == '\\') { k += 2; continue }
            if (s[k] == ch) {
                val len = runLength(s, k, to, ch)
                if (len == min) return k
                k += len; continue
            }
            k++
        }
        return -1
    }


    /** [text](url "title") */
    private fun tryLink(s: String, at: Int, to: Int): Pair<Inline, Int>? {
        val labelEnd = matchingBracket(s, at, to)
        if (labelEnd < 0) return null
        val label = s.substring(at + 1, labelEnd)
        if (label.startsWith("^")) {
            val id = referenceKey(label.drop(1))
            val number = context.get()?.footnotes?.get(id) ?: return null
            return Inline.Link(listOf(Inline.Text("[$number]")), "#footnote:$id") to (labelEnd + 1)
        }
        if (labelEnd + 1 >= to || s[labelEnd + 1] != '(') {
            var end = labelEnd + 1
            var id = label
            if (end < to && s[end] == '[') {
                val close = findChar(s, ']', end + 1, to)
                if (close < 0 || close >= to) return null
                id = s.substring(end + 1, close).ifEmpty { label }
                end = close + 1
            }
            val url = context.get()?.references?.get(referenceKey(id)) ?: return null
            return Inline.Link(parseInlineRange(label, 0, label.length), if (url.startsWith("#")) "#anchor:${url.drop(1)}" else url) to end
        }
        var k = labelEnd + 2
        var depth = 1
        val urlStart = k
        while (k < to) {
            context.get()?.let { if (++it.work > 1_000_000) return null }
            val c = s[k]
            if (c == '\\') { k += 2; continue }
            if (c == '(') depth++
            else if (c == ')') { depth--; if (depth == 0) break }
            else if (c == '<') {
                val gt = findChar(s, '>', k + 1, to)
                if (gt in 0 until to) k = gt
            }
            k++
        }
        if (k >= to || depth != 0) return null
        val rawTarget = s.substring(urlStart, k).trim()
        val url = cleanUrl(rawTarget.substringBefore(' '))
        val kids = parseInlineRange(label, 0, label.length)
        val target = if (url.startsWith("#")) "#anchor:${url.drop(1)}" else url
        return Inline.Link(kids, target) to (k + 1)
    }

    /** ![alt](url) */
    private fun tryImage(s: String, at: Int, to: Int): Pair<Inline, Int>? {
        val p = tryLink(s, at, to) ?: return null
        val inner = p.first
        return if (inner is Inline.Link) Inline.Image(flatten(inner.kids), inner.url) to p.second else null
    }

    private fun matchingBracket(s: String, at: Int, to: Int): Int {
        var depth = 0
        var i = at
        while (i < to) {
            context.get()?.let { if (++it.work > 1_000_000) return -1 }
            when (s[i]) {
                '\\' -> i++
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return -1
    }

    private fun cleanUrl(raw: String): String {
        var u = raw.trim()
        if (u.startsWith("<") && u.endsWith(">") && u.length > 2) u = u.substring(1, u.length - 1)
        val sp = u.indexOf(' ')
        if (sp > 0 && u.length > sp + 1 && (u[sp + 1] == '"' || u[sp + 1] == '\'')) u = u.substring(0, sp)
        return u.trim()
    }

    // ============================================================ 工具

    /** 文档里出现过的标题，用于目录 */
    fun headings(blocks: List<Block>): List<TocEntry> {
        val out = ArrayList<TocEntry>()
        fun walk(bs: List<Block>) {
            bs.forEach { b ->
                when (b) {
                    is Block.Heading -> {
                        val title = flatten(b.inline).trim()
                        if (title.isNotEmpty()) out += TocEntry(b.level, title, b.line)
                    }
                    is Block.Quote -> walk(b.kids)
                    is Block.ListBlock -> b.items.forEach { walk(it.blocks) }
                    else -> {}
                }
            }
        }
        walk(blocks)
        return out
    }

    /** 中文字按字计，英文按词计 */
    fun wordCount(blocks: List<Block>): Int {
        val text = blocksToPlainText(blocks)
        var cjk = 0
        var latin = 0
        var inWord = false
        for (ch in text) {
            when {
                ch.code in 0x4E00..0x9FFF || ch.code in 0x3400..0x4DBF -> { cjk++; inWord = false }
                ch.isLetterOrDigit() -> { if (!inWord) { latin++; inWord = true } }
                else -> inWord = false
            }
        }
        return cjk + latin
    }
}

data class TocEntry(val level: Int, val title: String, val line: Int)

/**
 * 把某一行任务项的 [ ] / [x] 翻转，返回新全文。
 * 只改目标行的第一个方括号，其它内容原样保留。
 */
fun toggleTaskInSource(src: String, lineIndex: Int, makeChecked: Boolean): String? {
    val normalized = src.replace("\r\n", "\n").replace('\r', '\n')
    val lines = normalized.split('\n').toMutableList()
    if (lineIndex !in lines.indices) return null
    val old = lines[lineIndex]
    val m = Regex("(\\[)([ xX✓√])(\\])").find(old) ?: return null
    // 只替换中间那一组，方括号本身必须原样留着
    val mark = m.groupValues[2]
    val group = m.groups[2] ?: return null
    val repl = if (makeChecked) "x" else " "
    if (mark == repl) return null
    val crlf = src.contains("\r\n")
    lines[lineIndex] = old.substring(0, group.range.first) + repl + old.substring(group.range.last + 1)
    return lines.joinToString(if (crlf) "\r\n" else "\n")
}
