package com.qoder.mdreader.ui

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.qoder.mdreader.R
import com.qoder.mdreader.applyNightMode
import com.qoder.mdreader.core.DocTheme
import com.qoder.mdreader.core.Prefs

/**
 * 阅读时最常用的几项调节，直接放在正文页调，不用跑进设置页再回来。
 *
 * 每次选完重建面板内容，选中态才是准的；正文由调用方决定是重渲染还是重建。
 */
class ReaderOptionsSheet(
    private val ctx: Context,
    private val prefs: Prefs,
    private val th: DocTheme,
    /** 参数表示"是否需要整页重建"：只有主题切换需要 */
    private val onApplied: (Boolean) -> Unit,
    /** 导出当前文档的真实渲染结果（块类型 + 实际像素高度），用于远程定位显示问题 */
    private val onDiagnostic: () -> Unit
) {

    private var dialog: BottomSheetDialog? = null

    private val scales = floatArrayOf(0.85f, 0.92f, 1.0f, 1.15f, 1.35f)
    private val scaleNames = listOf("很小", "偏小", "标准", "偏大", "很大")
    private val lineHeights = floatArrayOf(1.55f, 1.85f, 2.15f)
    private val lineNames = listOf("紧凑", "标准", "宽松")

    fun show() {
        val d = BottomSheetDialog(ctx)
        d.setContentView(build())
        dialog = d
        d.show()
    }

    private fun build(): View {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(26))
        }
        root.addView(text("阅读选项", 17f, th.ink, serifBold()))
        root.addView(text("改完立刻生效，不会跳回顶部", 11f, th.ink3, null, dp(14)))

        root.addView(
            section("字号", scaleNames, index(scales, prefs.fontScale, 2)) { i ->
                prefs.fontScale = scales[i]
                refresh(); onApplied(false)
            }
        )
        root.addView(
            section(
                "正文气质",
                listOf("纸墨（衬线）", "清爽（无衬线）"),
                if (prefs.fontFamily == "clean") 1 else 0
            ) { i ->
                prefs.fontFamily = if (i == 1) "clean" else "ink"
                refresh(); onApplied(false)
            }
        )
        root.addView(
            section("行距", lineNames, index(lineHeights, prefs.lineSpacing, 1)) { i ->
                prefs.lineSpacing = lineHeights[i]
                refresh(); onApplied(false)
            }
        )
        val diag = TextView(ctx).apply {
            text = "导出渲染诊断（给开发者看真实块高度）"
            textSize = 12.5f
            setTextColor(th.brand)
            setPadding(0, dp(18), 0, dp(2))
            setOnClickListener { dialog?.dismiss(); onDiagnostic() }
        }
        root.addView(diag)

        root.addView(
            section(
                "主题",
                listOf("浅色", "深色", "跟随系统"),
                prefs.themeMode.coerceIn(0, 2)
            ) { i ->
                prefs.themeMode = i
                dialog?.dismiss()           // 切主题会重建页面，先把面板关掉
                applyNightMode(i)           // AppCompat 会自动重建页面
                onApplied(true)
            }
        )
        return root
    }

    private fun section(title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit): View {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        col.addView(text(title, 11f, th.ink3, null, dp(8)))
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        options.forEachIndexed { i, label ->
            val on = i == selected
            row.addView(TextView(ctx).apply {
                text = label
                textSize = 12.5f
                gravity = Gravity.CENTER
                setPadding(dp(13), dp(7), dp(13), dp(7))
                setBackgroundResource(if (on) R.drawable.bg_chip_on else R.drawable.bg_chip)
                setTextColor(if (on) th.brandInk else th.ink2)
                if (on) setTypeface(typeface, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(6) }
                setOnClickListener { onPick(i) }
            })
        }
        col.addView(row)
        return col
    }

    private fun refresh() { dialog?.setContentView(build()) }

    private fun index(arr: FloatArray, v: Float, fallback: Int): Int {
        val i = arr.indexOfFirst { kotlin.math.abs(it - v) < 0.011f }
        return if (i >= 0) i else fallback
    }

    private fun serifBold() = Typeface.create("serif", Typeface.BOLD)

    private fun text(s: String, size: Float, color: Int, tf: Typeface?, bottom: Int = 0): TextView =
        TextView(ctx).apply {
            text = s
            textSize = size
            setTextColor(color)
            if (tf != null) typeface = tf
            setPadding(0, 0, 0, bottom)
        }

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
}
