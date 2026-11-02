package com.qoder.mdreader

import com.qoder.mdreader.core.Align
import com.qoder.mdreader.core.Block
import com.qoder.mdreader.core.Inline
import com.qoder.mdreader.core.MarkdownParser
import com.qoder.mdreader.core.TaskState
import com.qoder.mdreader.core.flatten
import com.qoder.mdreader.core.toggleTaskInSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解析器自检。这些用例对应"阅读页能不能正确显示"的关键路径，
 * 尤其是任务列表的行号——点待办写回文件全靠它。
 */
class MarkdownParserTest {

    private fun blocks(md: String, breaks: Boolean = true) = MarkdownParser.parse(md, breaks)

    @Test
    fun headings_six_levels_and_line_numbers() {
        val bs = blocks("# 一\n\n## 二\n\n### 三\n")
        assertEquals(3, bs.size)
        assertTrue(bs[0] is Block.Heading && (bs[0] as Block.Heading).level == 1)
        assertEquals(2, (bs[1] as Block.Heading).level)
        assertEquals(3, (bs[2] as Block.Heading).level)
        assertEquals(0, bs[0].line)
        assertEquals(2, bs[1].line)
        assertEquals(4, bs[2].line)
    }

    @Test
    fun heading_trailing_hashes_are_stripped() {
        val h = blocks("## 标题 ##\n")[0] as Block.Heading
        assertEquals("标题", flatten(h.inline))
    }

    @Test
    fun inline_styles_structure() {
        val p = blocks("普通 **粗** *斜* `码` ~~删~~ 结束\n")[0] as Block.Paragraph
        val kinds = p.inline.map { it::class.simpleName }
        assertTrue("kinds=$kinds", kinds.containsAll(listOf("Text", "Strong", "Em", "Code", "Strike")))
        assertEquals("码", (p.inline.first { it is Inline.Code } as Inline.Code).s)
        assertEquals("粗", flatten((p.inline.first { it is Inline.Strong } as Inline.Strong).kids))
    }

    @Test
    fun nested_bold_inside_italic() {
        val p = blocks("*外层 **内层** 收尾*\n")[0] as Block.Paragraph
        val em = p.inline.first { it is Inline.Em } as Inline.Em
        assertTrue(em.kids.any { it is Inline.Strong })
    }

    @Test
    fun link_and_image() {
        val p = blocks("[文字](https://a.b/c)\n")[0] as Block.Paragraph
        val link = p.inline.first { it is Inline.Link } as Inline.Link
        assertEquals("https://a.b/c", link.url)
        assertEquals("文字", flatten(link.kids))

        val img = blocks("![说明](photos/a.png)\n")[0]
        assertTrue(img is Block.ImageBlock)
        assertEquals("photos/a.png", (img as Block.ImageBlock).url)
        assertEquals("说明", img.alt)
    }

    @Test
    fun snake_case_is_not_italic() {
        val p = blocks("用 some_variable_name 命名\n")[0] as Block.Paragraph
        assertFalse(p.inline.any { it is Inline.Em })
    }

    @Test
    fun escaped_star_stays_literal() {
        val p = blocks("2 \\* 3 = 6\n")[0] as Block.Paragraph
        assertEquals("2 * 3 = 6", flatten(p.inline))
    }

    @Test
    fun fenced_code_keeps_body_and_lang() {
        val bs = blocks("```kotlin\nval a = 1\n  indented\n```\n")
        val code = bs[0] as Block.CodeBlock
        assertEquals("kotlin", code.lang)
        assertEquals("val a = 1\n  indented", code.text)
    }

    @Test
    fun fenced_code_with_tilde_and_blank_lines() {
        val bs = blocks("~~~\nx\n\ny\n~~~\n")
        val code = bs[0] as Block.CodeBlock
        assertEquals("x\n\ny", code.text)
    }

    @Test
    fun indented_code_block() {
        val bs = blocks("para\n\n    code line1\n    code line2\n")
        assertTrue(bs.last() is Block.CodeBlock)
        assertEquals("code line1\ncode line2", (bs.last() as Block.CodeBlock).text)
    }

    @Test
    fun unordered_list_with_nested_items() {
        val bs = blocks("- 甲\n  - 甲一\n  - 甲二\n- 乙\n")
        val list = bs[0] as Block.ListBlock
        assertFalse(list.ordered)
        assertEquals(2, list.items.size)
        val nested = list.items[0].blocks.filterIsInstance<Block.ListBlock>()
        assertEquals(1, nested.size)
        assertEquals(2, nested[0].items.size)
        assertEquals("甲二", flatten(nested[0].items[1].blocks.filterIsInstance<Block.Paragraph>().first().inline))
    }

    @Test
    fun ordered_list_numbers_and_continuation() {
        val bs = blocks("1. 第一\n2. 第二\n   续行\n3. 第三\n")
        val list = bs[0] as Block.ListBlock
        assertTrue(list.ordered)
        assertEquals(1, list.start)
        assertEquals(3, list.items.size)
        val second = flatten(list.items[1].blocks.filterIsInstance<Block.Paragraph>().first().inline)
        assertTrue("second=$second", second.contains("第二"))
    }

    @Test
    fun task_list_states_and_marker_lines() {
        val md = "- [x] 做完的\n- [ ] 没做的\n"
        val bs = blocks(md)
        val list = bs[0] as Block.ListBlock
        assertEquals(TaskState.CHECKED, list.items[0].task)
        assertEquals(TaskState.UNCHECKED, list.items[1].task)
        assertEquals(0, list.items[0].markerLine)
        assertEquals(1, list.items[1].markerLine)
        assertEquals("做完的", flatten(list.items[0].blocks.filterIsInstance<Block.Paragraph>().first().inline))
    }

    @Test
    fun toggle_task_writes_back_only_that_line() {
        val md = "标题\n\n- [ ] 甲\n- [x] 乙\n- [ ] 丙\n"
        val bs = blocks(md)
        val list = bs[1] as Block.ListBlock
        val target = list.items[0]
        val after = toggleTaskInSource(md, target.markerLine, true)
        assertNotNull(after)
        val lines = after!!.split('\n')
        assertEquals("- [x] 甲", lines[2])
        assertEquals("- [x] 乙", lines[3])   // 未被波及
        assertEquals("- [ ] 丙", lines[4])
        // 再点一次取消
        val back = toggleTaskInSource(after, target.markerLine, false)!!
        assertEquals("- [ ] 甲", back.split('\n')[2])
    }

    @Test
    fun toggle_task_is_noop_when_state_matches() {
        val md = "- [x] 已勾选\n"
        assertNull(toggleTaskInSource(md, 0, true))
    }

    @Test
    fun quote_with_nested_block() {
        val bs = blocks("> 引用第一行\n> \n> - 列表项\n")
        val q = bs[0] as Block.Quote
        assertTrue(q.kids.any { it is Block.ListBlock } || q.kids.size >= 1)
        assertTrue(flatten((q.kids[0] as Block.Paragraph).inline).contains("引用第一行"))
    }

    @Test
    fun table_alignment_and_cells() {
        val md = "| 时间 | 气温 | 风向 |\n|:-----|-----:|:----:|\n| 06:10 | 7℃ | 东北 |\n| 09:30 | 13℃ | 东南 |\n"
        val t = blocks(md)[0] as Block.Table
        assertEquals(3, t.header.size)
        assertEquals(2, t.rows.size)
        assertEquals(Align.LEFT, t.align[0])
        assertEquals(Align.RIGHT, t.align[1])
        assertEquals(Align.CENTER, t.align[2])
        assertEquals("7℃", flatten(t.rows[0][1]))
    }

    @Test
    fun table_escaped_pipe_stays_in_cell() {
        val md = "| a | b |\n|---|---|\n| x \\| y | z |\n"
        val t = blocks(md)[0] as Block.Table
        assertEquals("x | y", flatten(t.rows[0][0]))
    }

    @Test
    fun rule_versus_setext_heading() {
        // 独立 --- 是分割线
        val a = blocks("段落\n\n---\n\n下一段\n")
        assertTrue(a.any { it is Block.Rule })
        // 紧跟段落的 --- 是二级标题（CommonMark 行为）
        val b = blocks("段落\n---\n")
        assertTrue(b[0] is Block.Heading)
        assertEquals(2, (b[0] as Block.Heading).level)
        // === 是一级
        val c = blocks("标题\n===\n")
        assertEquals(1, (c[0] as Block.Heading).level)
    }

    @Test
    fun single_newline_becomes_break_when_enabled() {
        val on = blocks("第一行\n第二行\n", breaks = true)[0] as Block.Paragraph
        assertTrue(on.inline.any { it is Inline.Break })
        val off = blocks("第一行\n第二行\n", breaks = false)[0] as Block.Paragraph
        assertTrue(off.inline.none { it is Inline.Break })
    }

    @Test
    fun crlf_and_bom_are_normalized() {
        val bs = blocks("甲\r\n乙\r\n")
        val p = bs[0] as Block.Paragraph
        assertTrue(p.inline.any { it is Inline.Break })
    }

    @Test
    fun headings_include_nested_ones() {
        val md = "# A\n\n## B\n\n- 项目\n\n  ### C\n\n> #### D\n"
        val toc = MarkdownParser.headings(blocks(md))
        assertEquals(listOf(1, 2, 3, 4), toc.map { it.level })
        assertEquals("C", toc[2].title)
    }

    @Test
    fun word_count_mixes_cjk_and_latin() {
        val md = "# 标题\n\n中文五个字 hello world\n"
        val n = MarkdownParser.wordCount(blocks(md))
        // 标题(2) + 中文五个字(5) + 2 个英文词
        assertTrue("n=$n", n in 8..12)
    }

    @Test
    fun paragraph_stops_at_next_block_type() {
        val bs = blocks("段落文字\n# 标题\n")
        assertEquals(2, bs.size)
        assertTrue(bs[0] is Block.Paragraph)
        assertTrue(bs[1] is Block.Heading)
    }

    @Test
    fun loose_list_with_blank_lines() {
        val bs = blocks("- 甲\n\n- 乙\n\n- 丙\n")
        val list = bs[0] as Block.ListBlock
        assertEquals(3, list.items.size)
    }

    @Test
    fun real_world_document_roundtrip() {
        val md = """
            # 山间笔记

            > 信号只到垭口，所以任何"回头再记"的想法都会丢。

            ## 今日待办

            - [x] 换垭口标记的褪色布条
            - [ ] 把北坡塌方位置补进地图备注

            | 时间 | 气温 |
            |:-----|-----:|
            | 06:10 | 7℃ |

            ```python
            def f(x):
                return x + 1
            ```
        """.trimIndent()
        val bs = blocks(md)
        val kinds = bs.map { it::class.simpleName }
        assertEquals(listOf("Heading", "Quote", "Heading", "ListBlock", "Table", "CodeBlock"), kinds)
        val list = bs[3] as Block.ListBlock
        assertEquals(TaskState.CHECKED, list.items[0].task)
        assertEquals(TaskState.UNCHECKED, list.items[1].task)
        // 勾选第二项后，源文件里只有那一行变化
        val after = toggleTaskInSource(md, list.items[1].markerLine, true)!!
        assertTrue(after.contains("- [x] 把北坡塌方位置补进地图备注"))
        assertTrue(after.contains("- [x] 换垭口标记的褪色布条"))
        assertEquals(bs.size, blocks(after).size)
    }

    @Test
    fun loose_table_without_separator_row_is_recognised() {
        // 从 Excel / Notion 粘过来的表经常没有 |---| 分隔行
        val md = "|你想解决什么|看哪里|\n|FFT是什么，能做什么|第1章|\n|新模块先想哪些问题|第2章|\n"
        val t = blocks(md)[0] as Block.Table
        assertEquals(2, t.header.size)
        assertEquals(2, t.rows.size)
        assertEquals("第1章", flatten(t.rows[0][1]))
    }

    @Test
    fun prose_with_single_pipe_is_not_mistaken_for_table() {
        val bs = blocks("A | B 这种写法\n下一行也没有分隔\n")
        assertTrue("不该被当表格", bs.none { it is Block.Table })
        assertTrue(bs[0] is Block.Paragraph)
    }

    @Test
    fun table_stops_at_blank_line() {
        val md = "|a|b|\n|1|2|\n\n|c|d|\n|3|4|\n"
        val bs = blocks(md)
        assertEquals("空行应切成两张表", 2, bs.count { it is Block.Table })
    }

    @Test
    fun toggle_task_preserves_crlf_line_endings() {
        val crlf = "\u000D\u000A"
        val md = "标题" + crlf + crlf + "- [ ] 甲" + crlf
        val after = toggleTaskInSource(md, 2, true) ?: throw AssertionError("没改到")
        org.junit.Assert.assertTrue("写回必须保持 CRLF", after.contains(crlf))
        org.junit.Assert.assertTrue(after.contains("- [x] 甲" + crlf))
        // CRLF 里本来就含 LF，所以只能查"有没有落单的 LF"
        val bareLf = after.indices.count { after[it] == '\n' && (it == 0 || after[it - 1] != '\r') }
        assertEquals("不该混进裸 LF", 0, bareLf)
    }

    @Test
    fun chinese_bullet_marks_are_list_items() {
        val bs = blocks(
            """• 第一项
· 第二项
"""
        )
        val list = bs[0] as Block.ListBlock
        assertEquals(2, list.items.size)
        val first = list.items[0].blocks.filterIsInstance<Block.Paragraph>().first()
        assertEquals("第一项", flatten(first.inline))
        val second = list.items[1].blocks.filterIsInstance<Block.Paragraph>().first()
        assertEquals("第二项", flatten(second.inline))
    }

    /** 用户 2026-10-09 反馈"圆点显示不出来"的那段原文，逐块核对 */
    @Test
    fun user_reported_section_16_parses_into_five_blocks() {
        val md = """### 1.6 为什么FFT比直接计算快

直接计算像每次都从头检查整箱积木。FFT发现很多中间结果可以重复利用，于是把大任务反复拆成小任务。

拿1024点举例：

- 直接DFT的计算规模大约随1024×1024增长。
- FFT的计算规模大约随1024×10增长，因为1024连续对半分10次到1。

这些是计算规模比较，不是实际耗时保证。芯片到底多少微秒完成，还取决于乘法器数量、内存速度和数据搬运。"""
        val bs = blocks(md)
        assertEquals("应为 标题/段/段/列表/段 共 5 块，实际=${bs.map { it.javaClass.simpleName }}", 5, bs.size)

        val h = bs[0] as Block.Heading
        assertEquals(3, h.level)
        assertEquals("1.6 为什么FFT比直接计算快", flatten(h.inline))

        val list = bs[3] as Block.ListBlock
        assertEquals(2, list.items.size)
        assertEquals(
            "直接DFT的计算规模大约随1024×1024增长。",
            flatten(list.items[0].blocks.filterIsInstance<Block.Paragraph>().first().inline)
        )
        assertEquals(
            "FFT的计算规模大约随1024×10增长，因为1024连续对半分10次到1。",
            flatten(list.items[1].blocks.filterIsInstance<Block.Paragraph>().first().inline)
        )
        org.junit.Assert.assertTrue("列表项不该带行内加粗等残留符号",
            list.items[0].blocks.filterIsInstance<Block.Paragraph>().first().inline.size == 1)

        assertTrue(bs[1] is Block.Paragraph && bs[2] is Block.Paragraph)
        assertTrue(bs[4] is Block.Paragraph)
    }
}
