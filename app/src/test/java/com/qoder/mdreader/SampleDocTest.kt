package com.qoder.mdreader

import com.qoder.mdreader.core.Block
import com.qoder.mdreader.core.Inline
import com.qoder.mdreader.core.MarkdownParser
import com.qoder.mdreader.core.TaskState
import com.qoder.mdreader.core.blocksToPlainText
import com.qoder.mdreader.core.flatten
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 直接吃 assets/sample.md。这是用户装好后看到的第一篇，
 * 解析结果不对，第一印象就毁了。
 */
class SampleDocTest {

    private fun asset(name: String): File {
        val candidates = listOf(
            "src/main/assets/$name",
            "app/src/main/assets/$name",
            "../src/main/assets/$name"
        )
        val f = candidates.map { File(it) }.firstOrNull { it.isFile }
            ?: throw AssertionError("找不到 sample.md，工作目录=${File(".").absolutePath}")
        return f
    }
    private fun sampleText() = asset("sample.md").readText(Charsets.UTF_8)

    @Test
    fun sample_covers_every_supported_construct() {
        val bs = MarkdownParser.parse(sampleText(), true)
        val kinds = bs.map { it::class.simpleName }

        listOf(
            "Heading", "Quote", "ListBlock", "Table", "CodeBlock", "Rule", "ImageBlock", "Paragraph"
        ).forEach { need ->
            assertTrue("示例里应包含 $need，实际=$kinds", kinds.contains(need))
        }

        // 学习清单初始未完成；勾选语法在独立用例验证。
        val tasks = bs.filterIsInstance<Block.ListBlock>().flatMap { it.items }.map { it.task }
        assertTrue("应有学习清单", tasks.contains(TaskState.UNCHECKED))

        // 表格要有列对齐信息
        val table = bs.filterIsInstance<Block.Table>().first()
        assertTrue(table.align.size >= 2)
        assertTrue(table.rows.isNotEmpty())

        // 代码块语言标签被保留
        val code = bs.filterIsInstance<Block.CodeBlock>().first { it.lang == "python" }
        assertEquals("python", code.lang)
        assertTrue(code.text.contains("def average"))
    }

    @Test
    fun sample_has_no_unparsed_markers_left_in_text() {
        val bs = MarkdownParser.parse(sampleText(), true)
        // 代码块里出现 # ~ 是内容本身（Python 注释就用 #），检查时先排除
        val text = blocksToPlainText(bs.filter { it !is Block.CodeBlock })
        listOf("**", "~~", "```", "|:---").forEach { raw ->
            val index = text.indexOf(raw)
            assertTrue("文本里残留了 $raw：${if (index < 0) "" else text.substring(maxOf(0, index - 30), minOf(text.length, index + 70))}", index < 0)
        }
        // 行内代码本身可以写 # （示例里就有"`#` 的个数"），所以标题标记单独判
        val leakedHeading = bs.filterIsInstance<Block.Paragraph>().any {
            flatten(it.inline).trimStart().startsWith("# ")
        }
        assertTrue("段落里出现了未解析的 # 标题标记", !leakedHeading)
        // 加粗确实变成了 Strong 节点，而不是留下星号
        val hasStrong = bs.filterIsInstance<Block.Paragraph>().any { p ->
            p.inline.any { it is Inline.Strong }
        }
        assertTrue("示例中的加粗没有被解析", hasStrong)
    }

    @Test
    fun sample_headings_make_a_sane_outline() {
        val toc = MarkdownParser.headings(MarkdownParser.parse(sampleText(), true))
        assertTrue("长文目录至少 70 项", toc.size >= 70)
        assertTrue("目录标题不该带 # 号", toc.none { it.title.contains('#') })
        assertTrue("行号必须递增", toc.zipWithNext().all { (a, b) -> b.line > a.line })
        assertEquals(1, toc.first().level)
    }

    @Test
    fun sample_images_are_recognised_as_picture_blocks() {
        val imgs = MarkdownParser.parse(sampleText(), true).filterIsInstance<Block.ImageBlock>()
        assertEquals("八幅离线配图", 8, imgs.size)
        assertEquals(8, imgs.map { it.url }.toSet().size)
        imgs.forEach { image ->
            assertTrue(image.url.startsWith("sample-images/"))
            assertTrue("配图应随包存在：${image.url}", asset(image.url).isFile)
        }
    }

    @Test fun task_states_and_network_image_syntax_do_not_depend_on_sample_contents() {
        val blocks = MarkdownParser.parse("- [x] done\n- [ ] later\n\n![remote](https://example.com/demo.png)")
        assertEquals(listOf(TaskState.CHECKED, TaskState.UNCHECKED),
            blocks.filterIsInstance<Block.ListBlock>().flatMap { it.items }.map { it.task })
        assertEquals("https://example.com/demo.png", blocks.filterIsInstance<Block.ImageBlock>().single().url)
    }
}
