package com.qoder.mdreader.core

/**
 * 行内 HTML 白名单转换 + front matter 处理。
 *
 * 从 Word / 网页 / 语雀导出的 .md 里常混着少量 HTML 标签；文档开头常有 YAML 元信息块。
 * 整段 HTML 解析不现实，但把最常见的几个标签翻译成 Markdown 标记，成本极低、收益明显。
 */
object InlineHtml {

    private val BR = Regex("(?i)<br\\s*/?>")
    private val BOLD_OPEN = Regex("(?i)<(?:b|strong)>")
    private val BOLD_CLOSE = Regex("(?i)</(?:b|strong)>")
    private val ITALIC_OPEN = Regex("(?i)<(?:i|em)>")
    private val ITALIC_CLOSE = Regex("(?i)</(?:i|em)>")
    private val CODE_OPEN = Regex("(?i)<code>")
    private val CODE_CLOSE = Regex("(?i)</code>")
    private val DEL_OPEN = Regex("(?i)<(?:del|s|strike)>")
    private val DEL_CLOSE = Regex("(?i)</(?:del|s|strike)>")

    /**
     * 只认白名单，其它一律原样保留。
     * 技术文档里 "寄存器 < 0x10" 这类句子不能被当成标签吃掉。
     */
    fun sanitize(s: String): String {
        if (s.indexOf('<') < 0) return s          // 绝大多数文本走这一条就返回
        var out = s
        if (BR.containsMatchIn(out)) out = BR.replace(out, "\n")
        out = BOLD_OPEN.replace(out, "**")
        out = BOLD_CLOSE.replace(out, "**")
        out = ITALIC_OPEN.replace(out, "*")
        out = ITALIC_CLOSE.replace(out, "*")
        out = CODE_OPEN.replace(out, "`")
        out = CODE_CLOSE.replace(out, "`")
        out = DEL_OPEN.replace(out, "~~")
        out = DEL_CLOSE.replace(out, "~~")
        return out
    }

    /**
     * YAML front matter：文件开头被 --- 或 ... 包起来的元信息块。
     * 不处理的话，第一行 --- 会被当成 setext 标题或分割线，首屏直接乱掉。
     *
     * 这里是"抹成空行"而不是删掉：块级解析记着每个块的源文件行号，
     * 点待办要靠行号精确改回那一行，删行会让所有行号错位。
     */
    fun stripFrontMatterKeepingLines(src: String): String {
        val first = src.substringBefore('\n').trimEnd()
        if (first != "---" && first != "...") return src
        val lines = src.split('\n')
        var i = 1
        while (i < lines.size) {
            val t = lines[i].trimEnd()
            if (t == "---" || t == "...") {
                val out = ArrayList<String>(lines.size)
                for (k in 0..i) out += ""
                out += lines.subList(i + 1, lines.size)
                return out.joinToString("\n")
            }
            i++
            if (i > 60) return src                // 60 行内没闭合，认定不是 front matter
        }
        return src
    }
}
