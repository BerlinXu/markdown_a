package com.qoder.mdreader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.qoder.mdreader.core.DocStore
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.charset.Charset

/**
 * 书架摘要。
 *
 * 上一版为了不进主线程读全文，改成只读文件头 4KB，
 * 结果截断点切在汉字三个字节中间 → 严格 UTF-8 失败 → 误判成 GBK → 摘要变乱码。
 * 这里专门造一个"第 4096 字节正好落在汉字中间"的文件来守住它。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShelfPreviewTest {

    private fun store(): DocStore =
        DocStore(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun long_utf8_doc_preview_is_not_garbled() {
        val s = store()
        val first = "# FFT从零开始：原理、Excel参数与芯片设计师的五个问题"
        // 每个汉字 3 字节，重复 200 次必然跨过 4096 且大概率切在多字节中间
        val filler = "这是一段用于把文件撑过四千零九十六字节的中文正文内容。"
        val md = first + "\n\n" + filler.repeat(200)
        val ref = s.importText("长文档摘要测试", md)
        try {
            val p = s.preview(ref)
            assertTrue("摘要为空", !p.isNullOrBlank())
            assertTrue("摘要应取正文第一行标题，实际=「$p」", p!!.startsWith("FFT从零开始"))
            val gbkTell = charArrayOf('浠', '寮', '濮', '鎬', '\uFFFD', '浣', '鍒')
            assertTrue("摘要被按 GBK 误解：「$p」", p.none { it in gbkTell })
            assertTrue("摘要含替换符：「$p」", !p.contains('\uFFFD'))
        } finally {
            s.delete(ref)
        }
    }

    @Test
    fun preview_head_boundary_is_respected_at_every_cut() {
        val s = store()
        // 逐字节改变内容长度，让截断点扫过汉字的各种中间位置
        val base = "摘要边界测试：中文、English、数字12345、标点，。！？"
        var bad = 0
        for (pad in 0 until 60) {
            val md = "# $base" + "\n\n" + "a".repeat(pad) + base.repeat(220)
            val ref = s.importText("边界$pad", md)
            try {
                val p = s.preview(ref) ?: error("摘要读取失败")
                if (p.contains('\uFFFD') || p.contains("\uFFFD") || p.contains("浠")) bad++
            } finally {
                s.delete(ref)
            }
        }
        assertTrue("有 $bad 个截断位置产生乱码", bad == 0)
    }

    @Test
    fun real_gbk_file_still_decodes() {
        val s = store()
        val f = File(s.appDir(), "gbk编码文档.md")
        f.writeBytes("# 标题是中文的GBK文档\n\n正文也是GBK编码的中文内容。".toByteArray(Charset.forName("GBK")))
        try {
            val ref = s.listAll().firstOrNull { it.name == "gbk编码文档" }
            assertTrue("没找到测试文件", ref != null)
            val text = s.read(ref!!)
            assertTrue("GBK 文件应能读出中文，实际=「${text.take(20)}」", text.contains("标题是中文的GBK文档"))
        } finally {
            f.delete()
        }
    }

    @Test
    fun crlf_document_line_endings_survive_a_roundtrip() {
        val s = store()
        val ref = s.importText("换行测试", "第一行\r\n第二行\r\n- [ ] 待办\r\n")
        try {
            val text = s.read(ref)
            assertTrue("读取应归一成 LF", !text.contains('\r'))
            s.write(ref, text.replace("待办", "待办（已改）"))
            val raw = File(ref.localPath!!).readBytes().toList()
            assertTrue("写回应保持 CRLF", raw.contains('\r'.code.toByte()))
        } finally {
            s.delete(ref)
        }
    }
}
