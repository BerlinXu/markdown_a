package com.qoder.mdreader

import com.qoder.mdreader.core.*
import org.junit.Assert.*
import org.junit.Test

class ExtendedMarkdownTest {
    @Test fun unicode_sentence_punctuation_can_border_emphasis() {
        for (punctuation in listOf("。", "！", "？", "、", "؛")) {
            val block = MarkdownParser.parse("前文${punctuation}**“重点”**，继续。").single() as Block.Paragraph
            assertEquals("前文${punctuation}“重点”，继续。", flatten(block.inline))
            assertEquals("“重点”", flatten(block.inline.filterIsInstance<Inline.Strong>().single().kids))
        }
    }
    @Test fun longer_closing_fence_ends_code_block() {
        val blocks = MarkdownParser.parse("```\ncode\n````\n\n# tail")
        assertEquals("code", (blocks.first() as Block.CodeBlock).text)
        assertTrue(blocks.last() is Block.Heading)
    }
    @Test fun spaced_thematic_break_is_not_a_list() {
        assertTrue(MarkdownParser.parse("- - -").single() is Block.Rule)
    }
    @Test fun html_inside_inline_code_is_literal() {
        val block = MarkdownParser.parse("`<b>x</b>` and <b>bold</b>").single() as Block.Paragraph
        assertEquals("<b>x</b>", block.inline.filterIsInstance<Inline.Code>().single().s)
        assertTrue(block.inline.any { it is Inline.Strong })
    }
    @Test fun table_pipe_in_code_is_not_a_separator() {
        val table = MarkdownParser.parse("|a|b|\n|---|---|\n|`x|y`|z|").single() as Block.Table
        assertEquals("x|y", flatten(table.rows.single().first()))
        assertEquals("z", flatten(table.rows.single().last()))
    }
    @Test fun reference_links_and_footnotes_preserve_source_lines() {
        val blocks = MarkdownParser.parse("[label][id] note[^a]\n\n[id]: https://example.com\n[^a]: body\n\n- [ ] task")
        val paragraph = blocks.first() as Block.Paragraph
        assertEquals("https://example.com", paragraph.inline.filterIsInstance<Inline.Link>().first().url)
        assertTrue(paragraph.inline.filterIsInstance<Inline.Link>().any { it.url == "#footnote:a" })
        assertEquals(5, blocks.filterIsInstance<Block.ListBlock>().single().items.single().markerLine)
        assertEquals(3, blocks.last().line)
    }
    @Test fun definitions_inside_fences_do_not_become_links() {
        val blocks = MarkdownParser.parse("```\n[id]: https://example.com\n```\n\n[label][id]")
        assertFalse((blocks.last() as Block.Paragraph).inline.any { it is Inline.Link })
    }
    @Test(timeout = 3000) fun excessive_nesting_and_delimiters_have_bounded_depth() {
        assertTrue(MarkdownParser.parse(">".repeat(2000) + " text").isNotEmpty())
        assertTrue(MarkdownParser.parse(("x* ").repeat(10000)).isNotEmpty())
    }
    @Test fun changed_range_history_undo_redo_and_budget() {
        val history = EditHistory(100)
        history.record("abcd", "abXYZcd", 2, 5)
        val before = history.undo("abXYZcd")!!
        assertEquals("abcd", before.first)
        assertEquals("abXYZcd", history.redo(before.first)!!.first)
        history.record("x", "y".repeat(1000), 0, 0)
        assertFalse(history.canUndo)
    }
    @Test fun huge_lists_and_tables_are_split_into_bounded_rows() {
        val list = MarkdownParser.parse((1..1000).joinToString("\n") { "- item $it" })
        val rows = ReadingBlocks.prepare(list)
        assertEquals(1000, rows.size)
        assertTrue(rows.all { (it as Block.ListBlock).items.size == 1 })
    }
}
