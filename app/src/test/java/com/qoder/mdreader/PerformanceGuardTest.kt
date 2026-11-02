package com.qoder.mdreader

import com.qoder.mdreader.core.*
import org.junit.Assert.*
import org.junit.Test

class PerformanceGuardTest {
    @Test(timeout = 60000) fun representative_large_documents_have_bounded_render_rows() {
        val unit = "# Section\n\n" + "plain text 中文段落 ".repeat(64) + "\n\n- [ ] task\n- item\n\n```kotlin\nval value = 1\n```\n\n"
        for (size in listOf(100_000, 1_000_000, 5_000_000)) {
            val source = unit.repeat(size / unit.length)
            val start = System.nanoTime()
            val blocks = MarkdownParser.parse(source)
            val rows = ReadingBlocks.prepare(blocks)
            println("parser chars=${source.length} rows=${rows.size} ms=${(System.nanoTime() - start) / 1_000_000}")
            assertTrue(rows.isNotEmpty())
            assertTrue(rows.none { it is Block.ListBlock && it.items.size > 1 })
        }
    }
    @Test(timeout = 3000) fun unmatched_brackets_do_not_cause_quadratic_scanning() {
        val source = "[".repeat(30000)
        assertEquals(source, flatten(MarkdownParser.parseInline(source)))
        val angles = "<".repeat(30000)
        assertEquals(angles, flatten(MarkdownParser.parseInline(angles)))
        val links = "[a](".repeat(7000)
        assertEquals(links, flatten(MarkdownParser.parseInline(links)))
    }
    @Test fun huge_lines_and_wide_tables_have_bounded_fallbacks() {
        val code = ReadingBlocks.prepare(listOf(Block.CodeBlock("text", "x".repeat(200000), 0)))
        assertTrue(code.all { (it as Block.CodeBlock).text.length <= 12000 })
        val paragraph = ReadingBlocks.prepare(listOf(Block.Paragraph(listOf(Inline.Text("p".repeat(200000))), 0)))
        assertTrue(paragraph.all { flatten((it as Block.Paragraph).inline).length <= 16000 })
        val table = Block.Table(List(100) { listOf(Inline.Text("header")) }, List(100) { Align.LEFT }, listOf(List(100) { listOf(Inline.Text("cell")) }), 0)
        assertTrue(ReadingBlocks.prepare(listOf(table)).all { it is Block.CodeBlock })
    }
    @Test fun invalid_bom_and_unrepresentable_characters_are_rejected() {
        assertTrue(runCatching { TextCodec.decode(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte(), 0xff.toByte())) }.isFailure)
        assertTrue(runCatching { TextFormat("windows-1252").encode("汉字") }.isFailure)
        val bytes = "繁體中文測試".toByteArray(java.nio.charset.Charset.forName("Big5"))
        assertEquals("繁體中文測試", TextCodec.decode(bytes, "Big5").first)
    }
}
