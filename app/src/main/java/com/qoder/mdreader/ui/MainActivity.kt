package com.qoder.mdreader.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.qoder.mdreader.MdApp
import com.qoder.mdreader.Night
import com.qoder.mdreader.R
import com.qoder.mdreader.onMain
import com.qoder.mdreader.applyNightMode
import com.qoder.mdreader.core.DocRef
import com.qoder.mdreader.core.DocStore
import com.qoder.mdreader.core.Prefs
import com.qoder.mdreader.core.SampleDocuments
import com.qoder.mdreader.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 书架：最近 / App 目录 / 已授权的外部文件。
 * 所有文件操作（新建、打开、改名、删除、分享）都从这里发起。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var store: DocStore
    private lateinit var prefs: Prefs
    private lateinit var adapter: ShelfAdapter
    private lateinit var files: DocumentActions
    private val previewVersions = HashMap<String, String>()
    private val previewLoading = HashSet<String>()

    private var docs: List<DocRef> = emptyList()
    private var shown: List<DocRef> = emptyList()
    private var query = ""
    private var section = SEC_RECENT
    private var loadingShelf = false
    private var scanCache: List<DocRef>? = null
    private var lastScanAt = 0L

    /** 书架内容变了（新建/删除/改名/授权）就必须绕开缓存 */
    private fun invalidateShelf() {
        scanCache = null
        lastScanAt = 0
    }
    private val previews = HashMap<String, String?>()

    private val openPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        MdApp.ioPool.execute {
            val result = runCatching { store.adopt(uri) ?: error(getString(R.string.not_md)) }
            onMain {
                if (isDestroyed) return@onMain
                result.onSuccess { ref -> store.markOpened(ref); ReaderActivity.start(this, ref.key) }
                    .onFailure { toast("打开失败：${it.message}") }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = DocStore(this)
        prefs = Prefs(this)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        applyInsets(b.root)
        files = DocumentActions(this)

        seedSampleIfNeeded()

        adapter = ShelfAdapter()
        b.fileList.layoutManager = LinearLayoutManager(this)
        b.fileList.adapter = adapter

        buildChips()
        b.btnTheme.setOnClickListener { cycleTheme() }
        b.btnSearch.setOnClickListener { showSearch() }
        b.fabNew.setOnClickListener { promptNew() }
        b.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        updateThemeIcon()
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh(force = true)
    }

    // ------------------------------------------------------------ 数据

    private fun refresh(force: Boolean = false) {
        if (loadingShelf) return
        val cached = scanCache
        if (!force && cached != null && SystemClock.elapsedRealtime() - lastScanAt < 4000) {
            docs = cached
            applyFilter()
            return
        }
        loadingShelf = true
        // 不逐个打开云端文件；暂时离线的授权项仍保留在书架。
        MdApp.ioPool.execute {
            val result = runCatching { store.sortedShelf() }
            onMain {
                loadingShelf = false
                if (isFinishing || isDestroyed) return@onMain
                val all = result.getOrElse { toast("书架读取失败：${it.message}"); return@onMain }
                scanCache = all
                lastScanAt = SystemClock.elapsedRealtime()
                docs = all
                applyFilter()
                b.shelfSub.text = "${docs.size} 篇 · ${getString(R.string.app_name)} · 本地存储"
                loadPreviews()
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun applyFilter() {
        val base = when (section) {
            SEC_APP -> docs.filter { it.isLocal }
            SEC_EXT -> docs.filter { !it.isLocal }
            else -> docs
        }
        shown = if (query.isBlank()) base
        else base.filter {
            it.name.contains(query, true) || (previews[it.key]?.contains(query, true) == true)
        }
        adapter.notifyDataSetChanged()
        b.emptyView.visibility = if (shown.isEmpty() && query.isBlank()) View.VISIBLE else View.GONE
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun loadPreviews() {
        val need = shown.filter { previewVersions[it.key] != "${it.modified}|${it.size}" && it.key !in previewLoading }.take(12)
        need.forEach { loadPreview(it) }
    }
    private fun loadPreview(ref: DocRef) {
        val version = "${ref.modified}|${ref.size}"
        if (ref.key in previewLoading || previewVersions[ref.key] == version) return
        previewLoading += ref.key
        MdApp.previewPool.execute {
            val p = store.preview(ref)
            onMain {
                if (isDestroyed) return@onMain
                previewLoading -= ref.key
                previews[ref.key] = p
                previewVersions[ref.key] = version
                val index = shown.indexOfFirst { it.key == ref.key }
                if (index >= 0) adapter.notifyItemChanged(index)
                if (query.isNotBlank()) applyFilter()
            }
        }
    }

    private fun seedSampleIfNeeded() {
        MdApp.ioPool.execute {
            val result = runCatching { SampleDocuments.installIfNeeded(applicationContext) }
            onMain {
                if (isDestroyed) return@onMain
                result.onSuccess { installed -> if (installed) { invalidateShelf(); refresh(force = true) } }
                    .onFailure { toast("示例暂未安装：${it.message}") }
            }
        }
    }

    // ------------------------------------------------------------ 顶部分段

    private fun buildChips() {
        b.chipRow.removeAllViews()
        listOf(
            SEC_RECENT to R.string.recent,
            SEC_APP to R.string.folder_app,
            SEC_EXT to R.string.starred
        ).forEach { (id, label) ->
            val tv = TextView(this).apply {
                text = getString(if (id == SEC_EXT) R.string.folder_ext else label)
                textSize = 12f
                setPadding(dp(14), dp(6), dp(14), dp(6))
                setBackgroundResource(if (id == section) R.drawable.bg_chip_on else R.drawable.bg_chip)
                setTextColor(
                    if (id == section) getColor(R.color.brand_ink) else getColor(R.color.ink2)
                )
                if (id == section) setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(6) }
                setOnClickListener { section = id; applyFilter(); buildChips() }
            }
            b.chipRow.addView(tv)
        }
    }

    // ------------------------------------------------------------ 操作

    private fun cycleTheme() {
        val next = (prefs.themeMode + 1) % 3
        prefs.themeMode = next
        applyNightMode(next)
        updateThemeIcon()
    }

    private fun promptNew() {
        val input = EditText(this).apply {
            hint = getString(R.string.new_file_hint)
            setText("新建笔记")
            setSingleLine()
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }
        val wrap = FrameLayout(this).apply {
            setPadding(dp(22), dp(10), dp(22), 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.new_file)
            .setView(wrap)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = input.text.toString().ifBlank { "新建笔记" }
                fileOperation { store.createBlank(name) }.invoke { ref ->
                    invalidateShelf(); toast(getString(R.string.created_fmt, ref.fileName())); store.markOpened(ref)
                    startActivity(Intent(this, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_KEY, ref.key))
                }
            }
            .show()
    }

    private fun openDoc(ref: DocRef) {
        store.markOpened(ref)
        ReaderActivity.start(this, ref.key)
    }

    private fun showMore(ref: DocRef, anchor: View) {
        val pm = PopupMenu(this, anchor, Gravity.END)
        // 外部授权的文件没有改名接口，不给这个入口，免得点了只报错
        if (ref.isLocal) pm.menu.add(0, 1, 0, R.string.rename)
        pm.menu.add(0, 2, 1, R.string.share)
        pm.menu.add(0, 5, 4, "导出文档")
        if (ref.isLocal) pm.menu.add(0, 3, 2, R.string.delete)
        else pm.menu.add(0, 4, 2, R.string.remove_grant)
        pm.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> promptRename(ref)
                2 -> shareDoc(ref)
                5 -> files.export(ref)
                3 -> confirmDelete(ref)
                4 -> {
                    fileOperation { store.release(ref) }.invoke {
                        toast(R.string.removed); invalidateShelf(); refresh(force = true)
                    }
                }
            }
            previews.clear()
            true
        }
        pm.show()
    }

    private fun promptRename(ref: DocRef) {
        val input = EditText(this).apply {
            setText(ref.name)
            setSingleLine()
            setSelection(ref.name.length)
        }
        val wrap = FrameLayout(this).apply {
            setPadding(dp(22), dp(10), dp(22), 0)
            addView(input)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename_title)
            .setView(wrap)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = input.text.toString()
                fileOperation { store.rename(ref, name) }.invoke { r ->
                if (r == null) toast(R.string.name_empty) else {
                    toast(R.string.renamed)
                    previews.clear()
                    previewVersions.clear()
                    invalidateShelf()
                    refresh(force = true)
                }
                }
            }
            .show()
    }

    private fun confirmDelete(ref: DocRef) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_confirm_fmt, ref.fileName()))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                fileOperation { store.delete(ref) }.invoke { deleted ->
                toast(if (deleted) getString(R.string.deleted) else "删除失败，文档仍保留")
                previews.clear()
                invalidateShelf()
                refresh(force = true)
                }
            }
            .show()
    }

    private fun shareDoc(ref: DocRef) { files.share(ref) }

    private fun <T> fileOperation(work: () -> T): ((T) -> Unit) -> Unit = { done ->
        MdApp.ioPool.execute {
            val result = runCatching(work)
            onMain { if (!isDestroyed) result.onSuccess(done).onFailure { toast("操作失败：${it.message}") } }
        }
    }

    private fun showSearch() {
        val input = EditText(this).apply {
            hint = getString(R.string.search_hint)
            setText(query)
            setSingleLine()
        }
        val wrap = FrameLayout(this).apply {
            setPadding(dp(22), dp(10), dp(22), 0)
            addView(input)
        }
        // 边打字边过滤书架；对话框关掉后 query 仍然生效
        input.doAfterTextChanged {
            query = it?.toString().orEmpty()
            applyFilter()
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.search_title)
            .setView(wrap)
            .setPositiveButton(R.string.ok) { d, _ -> d.dismiss() }
            .setNeutralButton(R.string.clear_filter) { _, _ ->
                query = ""
                applyFilter()
            }
            .show()
    }

    private fun toast(msg: String) = hint(msg)
    private fun toast(res: Int) = hint(res)

    /** 主题图标本身就是反馈，不再弹提示 */
    private fun updateThemeIcon() {
        b.btnTheme.setImageResource(
            when (prefs.themeMode) {
                Night.LIGHT -> R.drawable.ic_light_mode
                Night.DARK -> R.drawable.ic_dark_mode
                else -> R.drawable.ic_auto_mode
            }
        )
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ------------------------------------------------------------ 列表

    private inner class ShelfAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount(): Int = shown.size + 1

        override fun getItemViewType(position: Int): Int =
            if (position < shown.size) TYPE_FILE else TYPE_MORE

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_FILE) FileVh(inf.inflate(R.layout.item_file, parent, false))
            else MoreVh(inf.inflate(R.layout.item_open_more, parent, false))
        }

        @SuppressLint("SetTextI18n")
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is MoreVh) {
                holder.itemView.setOnClickListener { openPicker.launch(arrayOf("*/*")) }
                return
            }
            val ref = shown[position]
            loadPreview(ref)
            val vh = holder as FileVh
            vh.name.text = ref.name
            val preview = previews[ref.key]
            vh.preview.text = preview?.ifBlank { null } ?: ref.fileName()
            val pos = prefs.readPos(ref.key)
            val stamp = StringBuilder()
            if (pos in 0.02f..0.995f) stamp.append(getString(R.string.progress_fmt, (pos * 100).toInt())).append(" · ")
            stamp.append(when {
                ref.isLocal -> fmtTime(store.fileOf(ref)?.lastModified() ?: ref.modified)
                else -> fmtTime(store.lastOpened(ref.key))
            })
            if (ref.size <= 0 && ref.isLocal) {
                stamp.append(" · ").append(getString(R.string.empty_file))
            } else if (ref.size > 0) {
                stamp.append(" · ").append(fmtSize(ref.size))
            }
            vh.stamp.text = stamp
            vh.dirty.visibility = if (store.hasUnsavedDraft(ref.key)) View.VISIBLE else View.GONE
            vh.more.setOnClickListener { showMore(ref, it) }
            vh.itemView.setOnClickListener { openDoc(ref) }
            vh.itemView.setOnLongClickListener { showMore(ref, vh.more); true }
        }

        private fun fmtSize(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(Locale.CHINA, "%.1f KB", bytes / 1024.0)
            else -> String.format(Locale.CHINA, "%.1f MB", bytes / 1048576.0)
        }

        private fun fmtTime(ms: Long): String {
            if (ms <= 0) return "—"
            val now = System.currentTimeMillis()
            val d = Date(ms)
            val oneDay = 86400_000L
            return when {
                now - ms < oneDay && isSameDay(now, ms) ->
                    SimpleDateFormat("HH:mm", Locale.CHINA).format(d)
                isYesterday(now, ms) -> getString(R.string.yesterday_fmt)
                now - ms < 7 * oneDay -> SimpleDateFormat("EEEE", Locale.CHINA).format(d)
                else -> SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(d)
            }
        }

        private fun isSameDay(a: Long, b: Long): Boolean {
            val c1 = java.util.Calendar.getInstance().apply { timeInMillis = a }
            val c2 = java.util.Calendar.getInstance().apply { timeInMillis = b }
            return c1.get(java.util.Calendar.DAY_OF_YEAR) == c2.get(java.util.Calendar.DAY_OF_YEAR) &&
                c1.get(java.util.Calendar.YEAR) == c2.get(java.util.Calendar.YEAR)
        }

        private fun isYesterday(now: Long, then: Long): Boolean {
            val c1 = java.util.Calendar.getInstance().apply { timeInMillis = now }
            val c2 = java.util.Calendar.getInstance().apply { timeInMillis = then }
            c1.add(java.util.Calendar.DAY_OF_YEAR, -1)
            return c1.get(java.util.Calendar.DAY_OF_YEAR) == c2.get(java.util.Calendar.DAY_OF_YEAR) &&
                c1.get(java.util.Calendar.YEAR) == c2.get(java.util.Calendar.YEAR)
        }
    }

    private inner class FileVh(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.doc_name)
        val preview: TextView = v.findViewById(R.id.doc_preview)
        val stamp: TextView = v.findViewById(R.id.doc_stamp)
        val dirty: View = v.findViewById(R.id.dirty_dot)
        val more: View = v.findViewById(R.id.doc_more)
    }

    private inner class MoreVh(v: View) : RecyclerView.ViewHolder(v)

    companion object {
        private const val TYPE_FILE = 0
        private const val TYPE_MORE = 1
        private const val SEC_RECENT = 0
        private const val SEC_APP = 1
        private const val SEC_EXT = 2
    }
}
