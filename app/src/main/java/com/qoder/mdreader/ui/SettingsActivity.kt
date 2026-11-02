package com.qoder.mdreader.ui

import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.qoder.mdreader.Night
import com.qoder.mdreader.R
import com.qoder.mdreader.applyNightMode
import com.qoder.mdreader.core.DocTheme
import com.qoder.mdreader.core.Prefs
import com.qoder.mdreader.databinding.ActivitySettingsBinding

/**
 * 设置页。所有行都用代码构建，样式集中、加一项很便宜。
 * 外观类改动会即时 recreate，所以用户能马上看到效果。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var b: ActivitySettingsBinding
    private lateinit var prefs: Prefs
    private lateinit var th: DocTheme

    private val scales = floatArrayOf(0.85f, 0.92f, 1.0f, 1.15f, 1.35f)
    private val scaleNames = arrayOf("很小", "偏小", "标准", "偏大", "很大")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        th = DocTheme(this)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        applyInsets(b.root)
        b.btnBack.setOnClickListener { finish() }

        buildAppearance()
        buildReading()
        buildEditing()
        buildFiles()
        buildAbout()
    }

    // ------------------------------------------------------------ 分组

    private fun group(title: String) {
        val label = TextView(this).apply {
            text = title
            textSize = 11f
            setTextColor(th.accent)
            letterSpacing = 0.16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(20); marginStart = dp(4); bottomMargin = dp(9) }
        }
        b.setContainer.addView(label)
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = th.rounded(th.card, th.dpf(16f), th.line, th.dpf(1f))
        }
        b.setContainer.addView(card)
        currentCard = card
    }

    private var currentCard: LinearLayout? = null

    private fun row(
        title: String,
        sub: String?,
        widget: View?,
        onClick: (() -> Unit)? = null
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15), dp(13), dp(15), dp(13))
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            // 权重 1 + 最小宽度：右侧控件再宽也不会把标题挤成两行
            minimumWidth = (120 * resources.displayMetrics.density).toInt()
        }
        texts.addView(TextView(this).apply {
            text = title
            textSize = 13.5f
            setTextColor(th.ink)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        if (sub != null) texts.addView(TextView(this).apply {
            text = sub
            textSize = 11f
            setTextColor(th.ink3)
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(texts)
        if (widget != null) {
            val lp = widget.layoutParams as? LinearLayout.LayoutParams
                ?: LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            lp.marginStart = dp(10)
            if (widget.layoutParams !== lp) widget.layoutParams = lp
            row.addView(widget)
        }
        if (onClick != null) {
            row.setOnClickListener { onClick() }
            val tv = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            if (tv.resourceId != 0) row.setBackgroundResource(tv.resourceId)
        }
        currentCard?.addView(row)
        currentCard?.addView(divider())
        return row
    }

    private fun divider(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
            marginStart = dp(15)
        }
        setBackgroundColor(th.line)
        visibility = View.INVISIBLE
    }

    private fun switchRow(title: String, sub: String, get: () -> Boolean, set: (Boolean) -> Unit) {
        val sw = SwitchCompat(this).apply {
            isChecked = get()
            isClickable = false
            thumbTintList = android.content.res.ColorStateList.valueOf(
                if (get()) th.brand else th.ink3
            )
            trackTintList = android.content.res.ColorStateList.valueOf(
                if (get()) th.brandSoft else th.line
            )
        }
        row(title, sub, sw) {
            sw.isChecked = !sw.isChecked
            set(sw.isChecked)
            sw.thumbTintList = android.content.res.ColorStateList.valueOf(
                if (sw.isChecked) th.brand else th.ink3
            )
            sw.trackTintList = android.content.res.ColorStateList.valueOf(
                if (sw.isChecked) th.brandSoft else th.line
            )
        }
    }

    private fun segRow(
        title: String,
        sub: String,
        options: List<String>,
        selected: Int,
        onChange: (Int) -> Unit
    ) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        options.forEachIndexed { i, label ->
            val tv = TextView(this).apply {
                text = label
                textSize = 11.5f
                gravity = Gravity.CENTER
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setBackgroundResource(if (i == selected) R.drawable.bg_chip_on else R.drawable.bg_chip)
                setTextColor(if (i == selected) th.brandInk else th.ink2)
                if (i == selected) setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = if (i == 0) 0 else dp(5) }
                setOnClickListener { onChange(i) }
            }
            box.addView(tv)
        }
        row(title, sub, box)
    }

    private fun seekRow(
        title: String,
        sub: String,
        max: Int,
        progress: Int,
        valueText: (Int) -> String,
        onChange: (Int) -> Unit
    ) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(160), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val sb = SeekBar(this).apply {
            this.max = max
            this.progress = progress
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val value = TextView(this).apply {
            text = valueText(progress)
            textSize = 11f
            setTextColor(th.ink3)
            setPadding(dp(8), 0, 0, 0)
            minWidth = dp(40)
        }
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, p: Int, fromUser: Boolean) {
                value.text = valueText(p)
                if (fromUser) onChange(p)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}
            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        box.addView(sb)
        box.addView(value)
        row(title, sub, box)
    }

    // ------------------------------------------------------------ 各分组

    private fun buildAppearance() {
        group(getString(R.string.g_appearance))
        segRow(
            getString(R.string.s_theme), getString(R.string.s_theme_sub),
            listOf(getString(R.string.theme_light), getString(R.string.theme_dark), getString(R.string.theme_auto)),
            prefs.themeMode
        ) { i ->
            prefs.themeMode = i
            applyNightMode(i)
            recreate()
        }
        segRow(
            getString(R.string.s_face), getString(R.string.s_face_sub),
            listOf(getString(R.string.face_ink), getString(R.string.face_clean)),
            if (prefs.fontFamily == "clean") 1 else 0
        ) { i ->
            prefs.fontFamily = if (i == 1) "clean" else "ink"
            recreate()
        }
        seekRow(
            getString(R.string.s_fontsize), getString(R.string.s_fontsize_sub),
            scales.size - 1, scales.indexOfFirst { it == prefs.fontScale }.coerceAtLeast(2),
            { scaleNames[it] }
        ) { i ->
            prefs.fontScale = scales[i]
        }
        seekRow(
            getString(R.string.s_lineheight), getString(R.string.s_lineheight_sub),
            10, ((prefs.lineSpacing - 1.4f) * 10).toInt().coerceIn(0, 10),
            { String.format(java.util.Locale.CHINA, "%.1f", 1.4 + it / 10.0) }
        ) { i ->
            prefs.lineSpacing = 1.4f + i / 10f
        }
    }

    private fun buildReading() {
        group(getString(R.string.g_reading))
        switchRow(getString(R.string.s_toc_btn), getString(R.string.s_toc_btn_sub),
            { prefs.showToc }, { prefs.showToc = it })
        switchRow(getString(R.string.s_progress), getString(R.string.s_progress_sub),
            { prefs.showProgress }, { prefs.showProgress = it })
        switchRow(getString(R.string.s_remember), getString(R.string.s_remember_sub),
            { prefs.rememberPosition }, { prefs.rememberPosition = it })
        switchRow(getString(R.string.s_netimg), getString(R.string.s_netimg_sub),
            { prefs.loadNetImage }, { prefs.loadNetImage = it })
    }

    private fun buildEditing() {
        group(getString(R.string.g_editing))
        switchRow(getString(R.string.s_autosave), getString(R.string.s_autosave_sub),
            { prefs.autoSave }, { prefs.autoSave = it })
        switchRow(getString(R.string.s_mono_edit), getString(R.string.s_mono_edit_sub),
            { prefs.monoEditor }, { prefs.monoEditor = it })
        switchRow(getString(R.string.s_task_save), getString(R.string.s_task_save_sub),
            { prefs.taskWritesBack }, { prefs.taskWritesBack = it })
        switchRow(getString(R.string.s_breakline), getString(R.string.s_breakline_sub),
            { prefs.singleBreakNewline }, { prefs.singleBreakNewline = it })
    }

    private fun buildFiles() {
        group(getString(R.string.g_files))
        row(
            getString(R.string.s_default_dir),
            runCatching {
                (getExternalFilesDir("Documents") ?: filesDir).absolutePath
                    .replace("/Android/data/", "/Android/data/…/")
            }.getOrDefault("Documents"),
            actionLabel(getString(R.string.rename)) { showDirHelp() }
        )
    }

    private fun buildAbout() {
        group(getString(R.string.g_about))
        row(
            getString(R.string.s_version),
            "${getString(R.string.app_full_name)} ${packageManager.getPackageInfo(packageName, 0).versionName} · build ${com.qoder.mdreader.BuildConfig.VERSION_CODE}",
            null
        )
        row(getString(R.string.s_reset), null, actionLabel(getString(R.string.s_reset)) { confirmReset() })
        // 最后一行的分隔线藏掉，卡片底部更干净
        if (currentCard != null && currentCard!!.childCount >= 2) {
            currentCard!!.getChildAt(currentCard!!.childCount - 1).visibility = View.INVISIBLE
        }
    }

    private fun actionLabel(text: String, action: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 11.5f
        setTextColor(th.brand)
        setPadding(dp(6), dp(4), dp(2), dp(4))
        setOnClickListener { action() }
    }

    private fun showDirHelp() {
        val path = (getExternalFilesDir("Documents") ?: filesDir).absolutePath
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.s_default_dir)
            .setMessage(getString(R.string.dir_help, path))
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.s_reset)
            .setMessage(R.string.reset_confirm)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.s_reset) { _, _ ->
                prefs.resetAll()
                hint(R.string.reset_done)
                recreate()
            }
            .show()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
