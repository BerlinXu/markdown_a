package com.qoder.mdreader

import com.qoder.mdreader.core.Block
import com.qoder.mdreader.core.Inline
import com.qoder.mdreader.core.MarkdownParser
import com.qoder.mdreader.core.TaskState
import com.qoder.mdreader.core.flatten
import com.qoder.mdreader.core.toggleTaskInSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 常见但容易踩空的语法：front matter、==高亮==、少量行内 HTML。
 * 这些在从 Word / 网页 / 语雀导出的 .md 里非常常见。
 */
class FormatSupportTest {

    private fun blocks(md: String) = MarkdownParser.parse(md, true)

    @Test
    fun front_matter_is_blanked_not_rendered() {
        val md = """---
title: FFT 模块需求
date: 2026-10-08
tags: [fft, gd32]
---

# 正文开始

第一段。
"""
        val bs = blocks(md)
        assertEquals("front matter 不该产生任何块，实际=${bs.map { it.javaClass.simpleName }}", 2, bs.size)
        assertTrue(bs[0] is Block.Heading)
        assertEquals("正文开始", flatten((bs[0] as Block.Heading).inline))
        assertFalse("不该出现分割线", bs.any { it is Block.Rule })
    }

    @Test
    fun front_matter_keeps_line_numbers_for_task_writeback() {
        val md = """---
title: x
---

- [ ] 待办在第六行
"""
        val bs = blocks(md)
        val list = bs.first { it is Block.ListBlock } as Block.ListBlock
        assertEquals(4, list.items[0].markerLine)
        val after = toggleTaskInSource(md, list.items[0].markerLine, true)
        assertTrue("行号必须仍指向那一行", after != null && after.contains("- [x] 待办在第六行"))
    }

    @Test
    fun highlight_pairs_are_parsed() {
        val p = blocks("重点是==这一段要看==，其余略\n")[0] as Block.Paragraph
        val hl = p.inline.filterIsInstance<Inline.Highlight>()
        assertEquals(1, hl.size)
        assertEquals("这一段要看", flatten(hl[0].kids))
    }

    @Test
    fun single_equals_is_not_highlight() {
        val p = blocks("a = b 且 3 = 3\n")[0] as Block.Paragraph
        assertTrue("单个等号不该触发高亮", p.inline.none { it is Inline.Highlight })
    }

    @Test
    fun html_break_and_bold_tags_become_markdown() {
        val p = blocks("第一行<br>第二行<b>加粗</b>普通\n")[0] as Block.Paragraph
        assertTrue("br 应变成换行", p.inline.any { it is Inline.Break })
        assertTrue("b 应变成加粗", p.inline.any { it is Inline.Strong })
        assertFalse("不该残留尖括号", flatten(p.inline).contains("<"))
    }

    @Test
    fun comparison_operators_are_not_mistaken_for_tags() {
        val text = "当值 <b 且 c > d 时，寄存器 0x10 < 0x20\n"
        val p = blocks(text)[0] as Block.Paragraph
        val flat = flatten(p.inline)
        assertTrue("小于号后的内容不能被当标签吃掉: $flat", flat.contains("<b 且 c > d"))
    }

    @Test
    fun synthetic_section_9_parses_with_long_codes_and_cjk_links() {
        val md = """## 9. 来源与本教材的边界

本教材的厂商行为和初稿定位来自以下本地材料：

- `ExampleDevice_Chapter32_Signal_English.md`：核心参考，重点32.2–32.12。
- `示例需求跟踪表.xlsx`：FFT!D6–D39、D42–D46；Requirement Summary；设备A对比设备B；Sheet2。
- 合成的五张示例需求图。

配套详细核查可看[已核对初稿的评审](示例需求评审.md)与[ST FFT提取](示例信号提取.md)。本教材是另一份从零讲起的学习文件，不修改原Excel和Word。"""
        val bs = blocks(md)
        assertEquals("应为 标题/段/列表/段，实际=${bs.map { it.javaClass.simpleName }}", 4, bs.size)

        val list = bs[2] as Block.ListBlock
        assertEquals(3, list.items.size)
        list.items.forEach { item ->
            val para = item.blocks.filterIsInstance<Block.Paragraph>().first()
            assertTrue("每个列表项都应有内容", flatten(para.inline).isNotBlank())
            org.junit.Assert.assertNotNull(
                "行内代码应被解析出来",
                para.inline.firstOrNull { it is Inline.Code || it is Inline.Text }
            )
        }
        val firstItem = flatten(list.items[0].blocks.filterIsInstance<Block.Paragraph>().first().inline)
        assertTrue("文件名应完整保留: $firstItem", firstItem.contains("ExampleDevice_Chapter32_Signal_English.md"))
        assertTrue("破折号区间应保留", firstItem.contains("32.2–32.12"))

        val last = bs[3] as Block.Paragraph
        val links = last.inline.filterIsInstance<Inline.Link>()
        assertEquals("两个中文链接都应识别", 2, links.size)
        assertEquals("示例需求评审.md", links[0].url)
        assertEquals("已核对初稿的评审", flatten(links[0].kids))
    }

    @Test
    fun task_states_survive_in_long_mixed_list() {
        val md = """## 待办

- [x] 已完成项，带 `代码` 和 **粗体**
- [ ] 未完成项，带[链接](https://a.b)
- 普通项
"""
        val list = blocks(md).last() as Block.ListBlock
        assertEquals(3, list.items.size)
        assertEquals(TaskState.CHECKED, list.items[0].task)
        assertEquals(TaskState.UNCHECKED, list.items[1].task)
        assertEquals(TaskState.NONE, list.items[2].task)
        assertTrue(list.items[0].blocks.any { b ->
            (b as? Block.Paragraph)?.inline?.any { it is Inline.Code || it is Inline.Strong } == true
        })
        assertTrue(list.items[1].blocks.any { b ->
            (b as? Block.Paragraph)?.inline?.any { it is Inline.Link } == true
        })
    }
}
