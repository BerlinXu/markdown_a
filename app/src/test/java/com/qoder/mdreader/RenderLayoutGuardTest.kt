package com.qoder.mdreader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.qoder.mdreader.core.Block
import com.qoder.mdreader.core.DocRenderer
import com.qoder.mdreader.core.DocTheme
import com.qoder.mdreader.core.MarkdownParser
import com.qoder.mdreader.core.Prefs
import com.qoder.mdreader.core.flatten
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 布局守卫。
 *
 * 之前"列表圆点看不见、文字错位、留一大块空白"我连改三轮都没中，因为我看不到真机上的
 * measure/layout 结果，只能靠截图猜。这些测试直接量出每个 View 的真实宽高，
 * 并断言"文字容器必须有宽度、圆点必须是精确小方块、不该有占高没内容的大空块"。
 *
 * 任何让宽度重新归零的改动，都会在这里当场失败。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RenderLayoutGuardTest {

    private val width = 1000

    private class Rendered(val root: LinearLayout, val tree: StringBuilder)

    private fun render(md: String, dark: Boolean = false): Rendered {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val th = DocTheme(ctx)
        val prefs = Prefs(ctx)
        val blocks = MarkdownParser.parse(md, true)
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.layoutParams = ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        DocRenderer(ctx, th, prefs, null, { }, { _, _ -> }).renderAll(col, blocks)
        col.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        col.layout(0, 0, width, col.measuredHeight)
        val sb = StringBuilder()
        dump(col, 0, sb)
        return Rendered(col, sb)
    }

    private fun dump(v: View, depth: Int, sb: StringBuilder) {
        sb.append("  ".repeat(depth))
            .append(v.javaClass.simpleName)
            .append(" ${v.width}x${v.height} @${v.left},${v.top}")
        if (v is TextView) sb.append(" \"").append(v.text.toString().take(10)).append("\"")
        sb.append('\n')
        if (v is ViewGroup) for (i in 0 until v.childCount) dump(v.getChildAt(i), depth + 1, sb)
    }

    private fun TextView?.textOf(): String = this?.text?.toString() ?: ""

    /** 找出树里所有"装了正文的 TextView" */
    private fun contentTexts(v: View, out: ArrayList<View> = ArrayList()): ArrayList<View> {
        if (v is TextView && v.text.isNotBlank()) out += v
        if (v is ViewGroup) for (i in 0 until v.childCount) contentTexts(v.getChildAt(i), out)
        return out
    }

    @Test
    fun unordered_list_text_gets_real_width() {
        val r = render(
            """- 直接DFT的计算规模大约随1024x1024增长。
- FFT的计算规模大约随1024x10增长，因为1024连续对半分10次到1。
"""
        )
        val texts = contentTexts(r.root)
        assertTrue("列表项文字没被渲染出来:\n${r.tree}", texts.isNotEmpty())
        texts.forEach {
            assertTrue("列表文字宽度只有 ${it.width}，几乎看不见:\n${r.tree}", it.width > width * 0.7f)
        }
        // 圆点必须是精确小尺寸，绝不能吃掉整行
        val row = (r.root.getChildAt(0) as ViewGroup).getChildAt(0) as ViewGroup
        val dot = row.getChildAt(0)
        assertTrue("圆点宽度 ${dot.width}px 过大，会挤掉文字:\n${r.tree}", dot.width in 1..40)
        assertTrue("圆点高度 ${dot.height}px 异常", dot.height in 1..40)
    }

    @Test
    fun ordered_list_and_task_list_also_get_width() {
        val r = render(
            """1. 第一项内容，需要拿到绝大部分宽度才对
1. 第二项

- [x] 已完成的一条待办，文字必须可见
- [ ] 未完成的一条待办，文字必须可见
"""
        )
        // 只量"装正文的容器"。序号 "1." 这类记号是按内容定宽的 TextView，
        // Robolectric 的字体度量会把它量成 2px（真机不会），拿它断言会误报。
        var checked = 0
        for (li in 0 until r.root.childCount) {
            val list = r.root.getChildAt(li) as? ViewGroup ?: continue
            for (i in 0 until list.childCount) {
                val row = list.getChildAt(i) as? ViewGroup ?: continue
                if (row.childCount < 2) continue
                val body = row.getChildAt(1)
                assertTrue("列表第 $i 项正文容器只有 ${body.width}px:\n${r.tree}", body.width > width * 0.6f)
                checked++
            }
        }
        assertTrue("没检查到任何列表项:\n${r.tree}", checked >= 4)
    }

    /** 合成的中文链接与长文件名案例，逐块量宽度 */
    @Test
    fun synthetic_section_9_list_text_is_visible() {
        val r = render(
            """## 9. 来源与本教材的边界

本教材的厂商行为和初稿定位来自以下本地材料：

- `ExampleDevice_Chapter32_Signal_English.md`：核心参考，重点32.2–32.12。
- `示例需求跟踪表.xlsx`：FFT!D6–D39、D42–D46；Requirement Summary；设备A对比设备B。
- 合成的五张示例需求图。

配套详细核查可看[已核对初稿的评审](示例需求评审.md)与[ST FFT提取](示例信号提取.md)。本教材是另一份从零讲起的学习文件。"""
        )
        org.junit.Assert.assertEquals("应为 标题/段/列表/段 四块:\n${r.tree}", 4, r.root.childCount)
        val list = r.root.getChildAt(2) as ViewGroup
        org.junit.Assert.assertEquals(3, list.childCount)
        for (i in 0 until list.childCount) {
            val row = list.getChildAt(i) as ViewGroup
            val body = row.getChildAt(1)
            assertTrue("第 $i 项正文容器只有 ${body.width}px，文字看不见:\n${r.tree}",
                body.width > width * 0.8f)
        }
        val tail = r.root.getChildAt(3)
        assertTrue("末段（含中文链接）宽度只有 ${tail.width}:\n${r.tree}", tail.width > width * 0.9f)
    }

    @Test
    fun no_tall_block_without_content() {
        // 用户报的"一大段空白"：某个块很高却没有文字
        val r = render(
            """## 标题

正常段落，内容不长。

- 列表甲
- 列表乙

> 引用一行

| 列一 | 列二 |
|:-----|-----:|
| a | b |

```text
一行代码
```
"""
        )
        val root = r.root
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            val hasText = contentTexts(child).isNotEmpty()
            if (child.height > 260 && !hasText) {
                assertTrue("第 $i 块高 ${child.height}px 却没有文字（空白块）:\n${r.tree}", false)
            }
        }
    }

    @Test
    fun table_columns_all_get_positive_width() {
        val r = render(
            """| 名字 | 小白解释 | 例子 |
|:-----|:---------|:-----|
| Fs，采样率 | 每秒记多少个数字 | 16000个/秒 |
| N，FFT点数 | 一次拿多少个数字一起分析 | 1024个 |
"""
        )
        val cells = ArrayList<View>()
        fun walk(v: View) {
            if (v is TextView && v.text.isNotBlank()) cells += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(r.root)
        assertTrue("表格没渲染出单元格:\n${r.tree}", cells.size >= 8)
        cells.forEach {
            assertTrue("有单元格宽度 ${it.width}，列被挤没了:\n${r.tree}", it.width > 60)
        }
    }

    @Test
    fun code_block_and_paragraph_fill_the_column() {
        val r = render(
            """这是一段中文正文，用来确认段落宽度会占满可用空间，不会被任何兄弟 View 挤掉。

```python
def gain(track):
    return 0
```
"""
        )
        val top = r.root
        assertTrue("块数不足:\n${r.tree}", top.childCount >= 2)
        for (i in 0 until top.childCount) {
            val c = top.getChildAt(i)
            assertTrue("第 $i 块宽度只有 ${c.width}:\n${r.tree}", c.width > width * 0.9f)
        }
    }

    @Test
    fun render_sample_document_to_png_for_visual_check() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val md = """# 渲染自检

普通段落里带 **加粗**、*斜体*、`行内代码`、~~删除~~、==高亮== 和 [一个链接](https://example.com)。

## 列表

- 第一项，前面应该有朱砂圆点
  - 嵌套子项
1. 有序第一项
- [x] 已完成待办
- [ ] 未完成待办

> 引用块，左侧应有朱砂竖条。

```python
def f(x):
    return x + 1   # 注释
```

| 名字 | 解释 | 例子 |
|:-----|:-----|-----:|
| Fs | 采样率 | 16000 |
| N | 点数 | 1024 |

---

结尾段落，带一个很长的英文词用于测试折行：supercalifragilisticexpialidociousandthensome-more-words-here-to-test-wrap。
"""
        val th = DocTheme(ctx)
        val prefs = Prefs(ctx)
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.setBackgroundColor(th.paper)
        col.setPadding(24, 24, 24, 24)
        col.layoutParams = ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        DocRenderer(ctx, th, prefs, null, { }, { _, _ -> }).renderAll(col, MarkdownParser.parse(md, true))
        col.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        col.layout(0, 0, width, col.measuredHeight)
        val h = col.measuredHeight.coerceIn(1, 4000)
        val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
        col.draw(Canvas(bmp))
        val out = File("build/reports")
        out.mkdirs()
        val png = File(out, "render-check.png")
        png.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("PROBE_PNG=" + png.absolutePath + " size=" + width + "x" + h)
        assertTrue("没生成图片", png.length() > 0)
    }
}
