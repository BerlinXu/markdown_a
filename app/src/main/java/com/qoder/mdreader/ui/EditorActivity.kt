package com.qoder.mdreader.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import com.qoder.mdreader.core.EditHistory
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.qoder.mdreader.MdApp
import com.qoder.mdreader.R
import com.qoder.mdreader.onMain
import com.qoder.mdreader.core.DocRef
import com.qoder.mdreader.core.DocRenderer
import com.qoder.mdreader.core.DocStore
import com.qoder.mdreader.core.DocTheme
import com.qoder.mdreader.core.MarkdownParser
import com.qoder.mdreader.core.Prefs
import com.qoder.mdreader.databinding.ActivityEditorBinding
import java.io.File

class EditorViewModel(app: android.app.Application) : AndroidViewModel(app) {
    var key: String? = null
    private var revision = DocStore.nextDraftRevision()
    var text: String? = null
        set(value) { if (value != field) revision = DocStore.nextDraftRevision(); field = value }
    val saved = MutableStateFlow<String?>(null)
    val draftError = MutableStateFlow<String?>(null)
    var savedText: String? = null
        set(value) { field = value; saved.value = value }
    var loaded = false
    var document: DocRef? = null
    val writes = Mutex()
    val parses = Mutex()
    val history = EditHistory()
    private var pendingSaves = 0
    val saving: Boolean get() = pendingSaves > 0
    var discard = false
    var cursor = 0
    var scroll = 0
    fun save(text: String, done: (Result<Unit>) -> Unit) {
        val ref = document ?: return
        val snapshotRevision = if (text == this.text) revision else 0L
        pendingSaves++
        viewModelScope.launch {
            try {
                val result = writes.withLock {
                    val written = withContext(Dispatchers.IO) {
                        runCatching {
                            val store = DocStore(getApplication())
                            store.saveDraft(ref.key, text, snapshotRevision)
                            store.write(ref, text)
                            store.clearDraftIfMatches(ref.key, text)
                        }
                    }
                    if (written.isSuccess) savedText = text
                    written
                }
                done(result)
            } finally { pendingSaves-- }
        }
    }
    fun draft(text: String) {
        val ref = document ?: return
        if (discard) return
        viewModelScope.launch {
            writes.withLock {
                if (discard) return@withLock
                val latest = this@EditorViewModel.text ?: text
                val snapshotRevision = revision
                val savedSnapshot = savedText
                val result = withContext(Dispatchers.IO) { runCatching {
                    val store = DocStore(getApplication())
                    if (latest != savedSnapshot) store.saveDraft(ref.key, latest, snapshotRevision)
                    else store.clearDraftIfMatches(ref.key, latest)
                } }
                if (result.isFailure) draftError.value = "草稿保存失败，请保留页面或另存为：${result.exceptionOrNull()?.message}"
            }
        }
    }
}

/**
 * 编辑页。工具栏只做"在光标处插入 Markdown 标记"，不接管输入，
 * 这样系统输入法、撤销、选择都保持原生行为。
 *
 * 横屏时自动变成左写右看：分割方向在 onCreate 里按当前 orientation 决定，
 * 转屏会重建 Activity，文本从 ViewModel 取回，不会丢。
 */
class EditorActivity : AppCompatActivity() {
    private var landscapeChrome: LandscapeChrome? = null

    private lateinit var b: ActivityEditorBinding
    private lateinit var store: DocStore
    private lateinit var prefs: Prefs
    private lateinit var th: DocTheme
    private val vm: EditorViewModel by viewModels { ViewModelProvider.AndroidViewModelFactory(application) }

    private lateinit var files: DocumentActions
    private var ref: DocRef? = null
    private var dirty = false
    private val handler = Handler(Looper.getMainLooper())
    private var autoSaveTask: Runnable? = null
    private var previewTask: Runnable? = null
    private var suppressWatch = true
    private var previewOn = false
    private var previewRenderer: DocRenderer? = null

    // 停顿 700ms 或工具栏操作时记录差量编辑；历史受总字符预算约束。
    private var previewJob: Job? = null
    private var statusTask: Runnable? = null
    private var statusJob: Job? = null
    private var statusGeneration = 0
    private var previewGeneration = 0
    private var textGeneration = 0
    private var toolbarEdit = false
    private var insertedNewline = false
    private var draftTask: Runnable? = null
    private var pendingBase: String? = null
    private var pendingBaseCursor = 0
    private var commitTask: Runnable? = null
    private var btnUndo: android.widget.ImageView? = null
    private var btnRedo: android.widget.ImageView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = DocStore(this)
        prefs = Prefs(this)
        th = DocTheme(this)
        b = ActivityEditorBinding.inflate(layoutInflater)
        setContentView(b.root)
        applyInsets(b.root)
        landscapeChrome = LandscapeChrome.install(this, b.root, listOf(b.topBar, b.toolsScroll, b.topDivider))
        files = DocumentActions(this)
        b.editor.isSaveEnabled = false
        lifecycleScope.launch {
            vm.saved.collect {
                if (vm.loaded) { markDirty(vm.text != vm.savedText); if (!dirty) b.saveHint.text = getString(R.string.saved) }
            }
        }
        lifecycleScope.launch { vm.draftError.collect { if (it != null) hint(it) } }

        buildToolbar()
        applyEditorStyle()
        setupLandscapeSplit()

        b.btnBack.setOnClickListener { leaveOrSave() }
        b.btnPreview.setOnClickListener { togglePreview() }
        b.btnMore.setOnClickListener { showMore(it) }
        b.fabSave.setOnClickListener { saveNow(true) }

        val key = intent.getStringExtra(EXTRA_KEY) ?: run { fail(); return }
        val r = store.find(key) ?: run { fail(); return }
        ref = if (vm.loaded && vm.key == key) vm.document ?: r else r
        vm.document = ref
        vm.discard = false
        b.docTitle.text = r.name + if (r.writable) "" else " · 只读来源"

        if (vm.loaded && vm.key == key) {
            installText(vm.text ?: "", vm.savedText ?: "")
        } else {
            MdApp.ioPool.execute {
                val draftResult = runCatching { store.draftOf(r.key) }
                val draft = draftResult.getOrNull()
                val disk = runCatching { store.read(r) }.getOrNull()
                val text = draft ?: disk
                onMain {
                    if (isFinishing || isDestroyed || text == null) { if (text == null) fail(); return@onMain }
                    vm.key = r.key
                    if (draftResult.isFailure) hint("草稿暂不可读，已尝试载入原文件")
                    vm.loaded = true
                    if (disk == null) { r.writable = false; hint("原文暂不可读，已恢复草稿，请另存为") }
                    vm.savedText = disk ?: ""
                    installText(text, disk ?: "")
                    if (draft != null && draft == disk) MdApp.ioPool.execute { store.clearDraftIfMatches(r.key, draft) }
                    else if (draft != null) markDirty(true, b.editor.text.toString())
                }
            }
        }

        b.editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {
                if (!suppressWatch && pendingBase == null) {
                    pendingBase = s?.toString()
                    pendingBaseCursor = b.editor.selectionStart
                }
            }
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {
                insertedNewline = !suppressWatch && d == 1 && c == 0 && s?.getOrNull(a) == '\n'
            }
            override fun afterTextChanged(s: Editable?) {
                if (suppressWatch) return
                if (insertedNewline) continueLine(s)
                insertedNewline = false
                textGeneration++
                vm.text = s?.toString() ?: ""
                markDirty(vm.text != vm.savedText)
                scheduleAutoSave()
                schedulePreview()
                scheduleUndoCommit()
                scheduleDraft()
                updateStatus()
            }
        })
        b.editor.setOnClickListener { updateStatus() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leaveOrSave()
        })
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        landscapeChrome?.onTouch(event)
        return super.dispatchTouchEvent(event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        landscapeChrome?.onWindowFocusChanged(hasFocus)
    }

    override fun onResume() {
        super.onResume()
        applyEditorStyle()
        if (vm.loaded && dirty) scheduleAutoSave()
    }

    override fun onPause() {
        super.onPause()
        // 有改动没落盘就先存草稿，书架上会显示朱砂点
        val r = ref
        vm.text = b.editor.text.toString()
        vm.cursor = b.editor.selectionStart
        vm.scroll = b.editor.scrollY
        if (r != null && dirty) vm.draft(vm.text ?: "")
        autoSaveTask?.let { handler.removeCallbacks(it) }
        commitTask?.let { handler.removeCallbacks(it) }
        draftTask?.let { handler.removeCallbacks(it) }
        previewTask?.let { handler.removeCallbacks(it) }
        previewJob?.cancel()
        statusJob?.cancel()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        vm.text = b.editor.text.toString()
        vm.key = ref?.key
        outState.putInt("scrollY", b.editor.scrollY.toInt())
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        previewJob?.cancel()
        previewGeneration++
        super.onDestroy()
    }

    // ------------------------------------------------------------ 装载

    private fun installText(text: String, saved: String) {
        suppressWatch = true
        b.editor.setText(text)
        suppressWatch = false
        vm.text = text
        vm.savedText = saved
        markDirty(text != saved)
        updateStatus()
        b.editor.setSelection(vm.cursor.coerceIn(0, text.length))
        b.editor.post { b.editor.scrollTo(0, vm.scroll) }
        if (previewOn) renderPreview()
    }

    private fun fail() {
        hint(R.string.err_read_simple)
        finish()
    }

    private fun applyEditorStyle() {
        val size = 14f * prefs.fontScale
        b.editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        b.editor.typeface = if (prefs.monoEditor) Typeface.MONOSPACE else th.bodyTypeface(prefs)
        b.editor.setLineSpacing(th.dpf(if (prefs.fontFamily == "clean") 6f else 7f), 1f)
        b.editor.setTextColor(th.ink)
        b.editor.setHintTextColor(th.ink3)
        b.editor.hint = getString(R.string.editor_hint)
    }

    /** 横屏：左右分栏，写一边看一边 */
    private fun setupLandscapeSplit() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (!landscape) return
        b.split.orientation = LinearLayout.HORIZONTAL
        b.splitDivider.visibility = View.VISIBLE
        b.previewWrap.visibility = View.VISIBLE
        b.previewEmpty.visibility = View.GONE
        (b.editor.layoutParams as LinearLayout.LayoutParams).apply { weight = 1f; width = 0; height = ViewGroup.LayoutParams.MATCH_PARENT }
        (b.previewWrap.layoutParams as LinearLayout.LayoutParams).apply { weight = 1f; width = 0; height = ViewGroup.LayoutParams.MATCH_PARENT }
        b.splitDivider.layoutParams = LinearLayout.LayoutParams((th.dpf(1f) + .5f).toInt(), ViewGroup.LayoutParams.MATCH_PARENT)
        // 分屏后每栏只有半屏宽，再用横屏的 48dp 页边距就太挤了
        val narrow = (20 * resources.displayMetrics.density).toInt()
        b.previewContent.setPadding(narrow, b.previewContent.paddingTop, narrow, b.previewContent.paddingBottom)
        b.editor.setPadding(narrow, b.editor.paddingTop, narrow, b.editor.paddingBottom)
        previewOn = true
    }

    private fun togglePreview() {
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            // 横屏分屏常驻，这个按钮退回到"只看预览"
            previewOn = !previewOn
            b.editor.visibility = if (previewOn) View.VISIBLE else View.GONE
            b.previewWrap.visibility = View.VISIBLE
        } else {
            previewOn = !previewOn
            b.previewWrap.visibility = if (previewOn) View.VISIBLE else View.GONE
            b.splitDivider.visibility = if (previewOn) View.VISIBLE else View.GONE
            b.editor.visibility = if (previewOn) View.GONE else View.VISIBLE
        }
        b.btnPreview.isActivated = previewOn
        if (previewOn) renderPreview()
    }

    // ------------------------------------------------------------ 工具栏

    private fun buildToolbar() {
        b.toolbar.removeAllViews()
        btnUndo = toolIcon(R.drawable.ic_undo) { doUndo() }
        btnRedo = toolIcon(R.drawable.ic_redo) { doRedo() }
        b.toolbar.addView(btnUndo)
        b.toolbar.addView(btnRedo)
        b.toolbar.addView(makeSep())
        updateUndoButtons()
        // (标签, 图标, 动作)
        val tools: List<Triple<String?, Int?, () -> Unit>> = listOf(
            Triple("H1", null) { prefixLine("# ") },
            Triple("H2", null) { prefixLine("## ") },
            Triple("B", null) { wrap("**", "**", "粗体") },
            Triple("I", null) { wrap("*", "*", "斜体") },
            Triple("S", null) { wrap("~~", "~~", "删除") },
            Triple("`", null) { wrap("`", "`", "code") },
            Triple(null, R.drawable.ic_code) { block("\n```\n", "\n```\n", "代码") },
            Triple(null, R.drawable.ic_list_ul) { prefixLine("- ") },
            Triple(null, R.drawable.ic_list_ol) { prefixLine("1. ") },
            Triple(null, R.drawable.ic_list_task) { prefixLine("- [ ] ") },
            Triple(null, R.drawable.ic_quote) { prefixLine("> ") },
            Triple(null, R.drawable.ic_link) { wrap("[", "](https://)", "链接文字") },
            Triple(null, R.drawable.ic_image) { insert("![", "](photos/)", "图片说明") },
            Triple(null, R.drawable.ic_table) { insert(tableTemplate(), null, null) },
            Triple(null, R.drawable.ic_hr) { insert("\n---\n\n", null, null) },
            Triple(null, R.drawable.ic_braces) { insert("{ }", null, null) }
        )
        tools.forEachIndexed { idx, t ->
            if (idx == 2 || idx == 6 || idx == 10 || idx == 13) b.toolbar.addView(makeSep())
            b.toolbar.addView(makeTool(t.first, t.second, t.third))
        }
    }

    private fun rippleBg(): Int {
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        return if (tv.resourceId != 0) tv.resourceId else android.R.color.transparent
    }

    private fun makeTool(label: String?, icon: Int?, action: () -> Unit): View {
        val h = resources.getDimensionPixelSize(R.dimen.toolbar_height)
        val bg = rippleBg()
        return if (label != null) {
            TextView(this).apply {
                text = label
                textSize = if (label.length > 1) 12f else 15f
                gravity = Gravity.CENTER
                setTextColor(th.ink2)
                minWidth = (38 * resources.displayMetrics.density).toInt()
                when (label) {
                    "B" -> setTypeface(Typeface.create("serif", Typeface.BOLD))
                    "I" -> setTypeface(Typeface.create("serif", Typeface.ITALIC))
                    "S" -> {
                        setTypeface(Typeface.create("serif", Typeface.NORMAL))
                        paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
                    }
                    else -> typeface = Typeface.MONOSPACE
                }
                setBackgroundResource(bg)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, h)
                setOnClickListener { action(); refreshAfterEdit() }
            }
        } else {
            android.widget.ImageView(this).apply {
                setImageResource(icon!!)
                setColorFilter(th.ink2)
                scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                val pad = (9 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
                minimumWidth = (36 * resources.displayMetrics.density).toInt()
                setBackgroundResource(bg)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, h)
                setOnClickListener { action(); refreshAfterEdit() }
            }
        }
    }

    private fun toolIcon(res: Int, action: () -> Unit): android.widget.ImageView {
        val h = resources.getDimensionPixelSize(R.dimen.toolbar_height)
        val pad = (9 * resources.displayMetrics.density).toInt()
        return android.widget.ImageView(this).apply {
            setImageResource(res)
            setColorFilter(th.ink2)
            scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
            setPadding(pad, pad, pad, pad)
            minimumWidth = (36 * resources.displayMetrics.density).toInt()
            setBackgroundResource(rippleBg())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, h)
            setOnClickListener { action() }
        }
    }

    /** 把"这一段编辑"作为一个整体压进撤销栈 */
    private fun scheduleUndoCommit() {
        commitTask?.let { handler.removeCallbacks(it) }
        val task = Runnable { flushPendingEdit() }
        commitTask = task
        handler.postDelayed(task, 700)
    }

    private fun flushPendingEdit() {
        commitTask?.let { handler.removeCallbacks(it) }
        commitTask = null
        val base = pendingBase ?: return
        pendingBase = null
        if (base == b.editor.text.toString()) return
        vm.history.record(base, b.editor.text.toString(), pendingBaseCursor, b.editor.selectionStart)
        updateUndoButtons()
    }

    private fun pushUndo() {
        flushPendingEdit()
        pendingBase = b.editor.text.toString()
        pendingBaseCursor = b.editor.selectionStart
        toolbarEdit = true
    }
    private fun doUndo() {
        flushPendingEdit()
        val change = vm.history.undo(b.editor.text.toString()) ?: run { hint(R.string.nothing_to_undo); return }
        applyText(change.first, change.second)
    }
    private fun doRedo() {
        flushPendingEdit()
        val change = vm.history.redo(b.editor.text.toString()) ?: run { hint(R.string.nothing_to_redo); return }
        applyText(change.first, change.second)
    }

    private fun applyText(text: String, cursor: Int) {
        suppressWatch = true
        b.editor.setText(text)
        b.editor.setSelection(cursor.coerceIn(0, text.length))
        suppressWatch = false
        textGeneration++
        markDirty(text != vm.savedText, text)
        scheduleAutoSave()
        schedulePreview()
        updateStatus()
        updateUndoButtons()
    }

    private fun updateUndoButtons() {
        btnUndo?.alpha = if (!vm.history.canUndo) 0.28f else 1f
        btnRedo?.alpha = if (!vm.history.canRedo) 0.28f else 1f
    }

    private fun makeSep(): View = View(this).apply {
        val d = resources.displayMetrics.density
        layoutParams = LinearLayout.LayoutParams(1, (20 * d).toInt()).apply {
            marginStart = (5 * d).toInt(); marginEnd = (5 * d).toInt()
        }
        setBackgroundColor(th.line)
    }

    private fun tableTemplate(): String =
        "\n| 列一 | 列二 | 列三 |\n|:-----|:----:|-----:|\n| 内容 | 内容 | 内容 |\n\n"

    // ------------------------------------------------------------ 编辑动作

    private fun selStart() = b.editor.selectionStart.coerceAtLeast(0)
    private fun selEnd() = b.editor.selectionEnd.coerceAtLeast(0)
    private fun editable(): Editable = b.editor.text

    /** 包住选中内容；没选中就插入占位文字并选中它 */
    private fun wrap(open: String, close: String, placeholder: String) {
        pushUndo()
        val e = editable()
        val s = selStart()
        val en = selEnd()
        suppressWatch = true
        if (s == en) {
            e.replace(s, en, "$open$placeholder$close")
            b.editor.setSelection(s + open.length, s + open.length + placeholder.length)
        } else {
            val body = e.substring(s, en)
            e.replace(s, en, "$open$body$close")
            b.editor.setSelection(s + open.length, s + open.length + body.length)
        }
        suppressWatch = false
        markDirty(true, b.editor.text.toString())
        scheduleAutoSave()
        schedulePreview()
    }

    /** 在光标处插入，可选把光标停在中间 */
    private fun insert(head: String, tail: String?, placeholder: String?) {
        pushUndo()
        val e = editable()
        val s = selStart()
        val en = selEnd()
        suppressWatch = true
        if (tail == null) {
            e.replace(s, en, head)
            b.editor.setSelection(s + head.length)
        } else {
            val ph = placeholder ?: ""
            e.replace(s, en, "$head$ph$tail")
            b.editor.setSelection(s + head.length, s + head.length + ph.length)
        }
        suppressWatch = false
        markDirty(true, b.editor.text.toString())
        scheduleAutoSave()
        schedulePreview()
    }

    /** 块级插入：整块替换，光标落在内容区 */
    private fun block(head: String, tail: String, placeholder: String) = insert(head, tail, placeholder)

    /** 给当前行（或多行的每一行）加前缀，已有则去掉 */
    private fun prefixLine(prefix: String) {
        pushUndo()
        val e = editable()
        val s = selStart()
        val en = selEnd()
        val startLine = e.toString().lastIndexOf('\n', (s - 1).coerceAtLeast(0)) + 1
        var endLine = e.indexOf('\n', en)
        if (endLine < 0) endLine = e.length
        val segment = e.substring(startLine, endLine)
        val lines = segment.split('\n')
        val allHave = lines.all { it.trimStart().startsWith(prefix) || it.isBlank() }
        val rebuilt = lines.joinToString("\n") { line ->
            if (line.isBlank()) line
            else if (allHave) line.replaceFirst(Regex("(\\s*)" + Regex.escape(prefix)), "$1")
            else {
                val indent = line.takeWhile { it == ' ' }
                indent + prefix + line.drop(indent.length).replace(Regex("^(#\\s|>\\s|-\\s\\[[ x]\\]\\s|\\d+\\.\\s)"), "")
            }
        }
        suppressWatch = true
        e.replace(startLine, endLine, rebuilt)
        b.editor.setSelection((startLine + rebuilt.length).coerceAtMost(e.length))
        suppressWatch = false
        markDirty(true, b.editor.text.toString())
        scheduleAutoSave()
        schedulePreview()
    }

    private fun refreshAfterEdit() {
        toolbarEdit = false
        textGeneration++
        flushPendingEdit()
        val text = b.editor.text.toString()
        markDirty(text != vm.savedText, text)
        updateStatus()
        schedulePreview()
    }

    // ------------------------------------------------------------ 保存

    private fun markDirty(v: Boolean, text: String = vm.text ?: "") {
        vm.text = text
        dirty = v
        val sub = StringBuilder()
        sub.append(if (v) getString(R.string.unsaved_state) else getString(R.string.saved))
        if (v) sub.append(" · ") else sub.append(" · ")
        sub.append(if (prefs.autoSave) getString(R.string.autosave_on) else getString(R.string.autosave_off))
        b.docSub.text = sub
        b.docSub.setTextColor(if (v) th.accent else th.ink3)
        b.fabSave.alpha = if (v) 1f else 0.45f
    }

    private fun scheduleAutoSave() {
        scheduleDraft()
        if (!prefs.autoSave || ref?.writable != true) return
        autoSaveTask?.let { handler.removeCallbacks(it) }
        val task = Runnable { saveNow(false) }
        autoSaveTask = task
        handler.postDelayed(task, 1500)
        b.saveHint.text = getString(R.string.autosave_pending)
    }

    private fun scheduleDraft() {
        draftTask?.let { handler.removeCallbacks(it) }
        draftTask = Runnable { if (dirty) vm.draft(vm.text ?: "") }.also { handler.postDelayed(it, 1000) }
    }

    private fun schedulePreview() {
        if (!previewOn) return
        previewTask?.let { handler.removeCallbacks(it) }
        // 文档越长，刷新间隔拉得越开；否则边打字边卡
        val len = b.editor.text.length
        val delay = when {
            len > 20000 -> 1400L
            len > 8000 -> 900L
            else -> 380L
        }
        val task = Runnable { renderPreview() }
        previewTask = task
        handler.postDelayed(task, delay)
    }

    private fun saveNow(showToast: Boolean, afterSave: (() -> Unit)? = null) {
        val r = ref ?: return
        val text = b.editor.text.toString()
        if (!dirty && !store.hasUnsavedDraft(r.key)) { afterSave?.invoke(); return }
        if (!r.writable) { hint("来源只读，请使用另存为"); return }
        autoSaveTask?.let { handler.removeCallbacks(it) }
        b.saveHint.text = "正在保存…"
        vm.save(text) { result ->
            if (isFinishing || isDestroyed) return@save
            val text = b.editor.text.toString()
            markDirty(text != vm.savedText, text)
            if (result.isSuccess) {
                b.saveHint.text = if (dirty) "仍有新修改未保存" else getString(R.string.saved)
                if (showToast && !dirty) hint(R.string.save_ok)
                if (!dirty) afterSave?.invoke() else scheduleAutoSave()
            } else {
                b.saveHint.text = "保存失败，请保留页面或另存为"
                hint(getString(R.string.save_fail, result.exceptionOrNull()?.message ?: "?"))
            }
        }
    }

    private fun leaveOrSave() {
        if (vm.saving) { hint("正在保存，请稍候再返回"); return }
        if (!dirty) { finish(); return }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.unsaved_title)
            .setMessage(R.string.unsaved_msg)
            .setNeutralButton(R.string.discard) { _, _ ->
                vm.discard = true
                handler.removeCallbacksAndMessages(null)
                vm.viewModelScope.launch { vm.writes.withLock {
                    withContext(Dispatchers.IO) { ref?.let { store.clearDraft(it.key) } }
                    if (!isDestroyed) finish()
                } }
            }
            .setNegativeButton(R.string.keep_editing, null)
            .setPositiveButton(R.string.save) { _, _ ->
                saveNow(false) { finish() }
            }
            .show()
    }

    private fun showMore(anchor: View) {
        val pm = PopupMenu(this, anchor, Gravity.END)
        pm.menu.add(0, 1, 0, R.string.save)
        pm.menu.add(0, 2, 1, if (previewOn) R.string.only_edit else R.string.only_preview)
        pm.menu.add(0, 3, 2, R.string.read_mode)
        pm.menu.add(0, 4, 3, R.string.settings)
        pm.menu.add(0, 5, 4, "另存为…")
        pm.menu.add(0, 6, 5, "导出原文恢复副本")
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> saveNow(true)
                2 -> togglePreview()
                3 -> saveNow(false) { ref?.let { r -> startActivity(Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_KEY, r.key)) } }
                4 -> startActivity(Intent(this, SettingsActivity::class.java))
                5 -> ref?.let { files.export(it, b.editor.text.toString()) }
                6 -> ref?.let { files.export(it, recovery = true) }
            }
            true
        }
        pm.show()
    }

    // ------------------------------------------------------------ 预览

    @SuppressLint("SetTextI18n")
    private fun updateStatus() {
        val text = vm.text ?: ""
        val s = selStart()
        statusTask?.let { handler.removeCallbacks(it) }
        statusJob?.cancel()
        val generation = ++statusGeneration
        statusTask = Runnable {
            statusJob = vm.viewModelScope.launch {
                val (line, col, words) = withContext(Dispatchers.Default) {
                    var line = 1
                    for (i in 0 until minOf(s, text.length)) if (text[i] == '\n') line++
                    val col = s - (text.lastIndexOf('\n', s - 1) + 1) + 1
                    val cjk = text.count { it.code in 0x4E00..0x9FFF }
                    val other = text.count { it.isLetterOrDigit() && it.code !in 0x4E00..0x9FFF }
                    Triple(line, col, cjk + other / 5)
                }
                if (!isDestroyed && generation == statusGeneration) {
                    b.posLabel.text = getString(R.string.cursor_fmt, line, col)
                    b.lenLabel.text = getString(R.string.words_fmt, words.toString())
                }
            }
        }
        handler.postDelayed(statusTask!!, 350)
    }

    private fun continueLine(editable: Editable?) {
        if (editable != null && android.view.inputmethod.BaseInputConnection.getComposingSpanStart(editable) >= 0) return
        val e = editable ?: return
        val cursor = b.editor.selectionStart
        if (toolbarEdit || cursor <= 1 || e[cursor - 1] != '\n') return
        val previousStart = e.lastIndexOf('\n', cursor - 2) + 1
        val previous = e.substring(previousStart, cursor - 1)
        val match = Regex("^(\\s*)(?:(- \\[[ xX]\\] )|([-*+] )|(\\d+)([.)] )|(> ))(.*)$").matchEntire(previous) ?: return
        suppressWatch = true
        if (match.groupValues[7].isBlank()) {
            e.delete(previousStart, cursor - 1)
        } else {
            val prefix = match.groupValues[1] + when {
                match.groupValues[2].isNotEmpty() -> "- [ ] "
                match.groupValues[3].isNotEmpty() -> match.groupValues[3]
                match.groupValues[4].isNotEmpty() -> ((match.groupValues[4].toLongOrNull() ?: 0) + 1).toString() + match.groupValues[5]
                else -> match.groupValues[6]
            }
            e.insert(cursor, prefix)
        }
        suppressWatch = false
    }

    private fun renderPreview() {
        val r = ref ?: return
        val text = b.editor.text.toString()
        b.previewEmpty.visibility = if (text.isBlank()) View.VISIBLE else View.GONE
        val generation = ++previewGeneration
        previewJob?.cancel()
        previewJob = vm.viewModelScope.launch {
            val result = vm.parses.withLock { withContext(Dispatchers.Default) { ensureActive(); runCatching {
                com.qoder.mdreader.core.ReadingBlocks.prepare(MarkdownParser.parse(text, prefs.singleBreakNewline))
            } } }
            val prepared = result.getOrElse {
                if (!isDestroyed && generation == previewGeneration) hint("预览不可用：${it.message}")
                return@launch
            }
            // Keep the ScrollView preview bounded; full reading uses recycled rows.
            var chars = 0
            val blocks = prepared.take(120).takeWhile { block ->
                chars += com.qoder.mdreader.core.blocksToPlainText(listOf(block)).length
                chars <= 30000
            }
            val truncated = prepared.size > blocks.size
            run {
                if (isFinishing || isDestroyed || generation != previewGeneration || text != b.editor.text.toString()) return@launch
                val renderer = DocRenderer(
                    ctx = this@EditorActivity,
                    th = th,
                    prefs = prefs,
                    baseDir = if (r.isLocal) File(r.localPath!!).parentFile else null,
                    onLink = { },
                    resolveImage = { url -> com.qoder.mdreader.core.RelativeDocuments.resolve(applicationContext, r, url, store.treeFor(r.key))?.toString() },
                    onTaskToggle = task@ { line, _ ->
                        if (text != vm.text) { hint("预览刷新中，请稍后再操作"); return@task }
                        // 预览里勾选：只替换源文本的那一行（保住撤销栈和光标），
                        // 再局部更新预览方块；两万字文档不该点一下就重建整篇
                        val cur = b.editor.text
                        val range = lineRange(cur, line)
                        val rowText = if (range != null) cur.substring(range.first, range.second) else null
                        val mark = rowText?.let { Regex("\\[([ xX✓√])\\]").find(it) }
                        val grp = mark?.groups?.get(1)
                        if (range != null && rowText != null && mark != null && grp != null) {
                            val makeChecked = mark.groupValues[1] == " "
                            val newLine = rowText.replaceRange(
                                grp.range.first, grp.range.last + 1, if (makeChecked) "x" else " "
                            )
                            val at = b.editor.selectionStart
                            pushUndo()
                            suppressWatch = true
                            cur.replace(range.first, range.second, newLine)
                            suppressWatch = false
                            b.editor.setSelection(at.coerceIn(0, cur.length))
                            refreshAfterEdit()
                            scheduleAutoSave()
                            previewRenderer?.setTaskState(b.previewContent, line, makeChecked)
                        }
                    }
                )
                previewRenderer = renderer
                renderer.renderAll(b.previewContent, blocks)
                if (truncated) {
                    val note = android.widget.TextView(this@EditorActivity).apply {
                        setText(getString(R.string.preview_truncated, prepared.size - blocks.size))
                        setTextColor(th.ink3)
                        textSize = 12f
                        setPadding(0, th.dpf(14f).toInt(), 0, th.dpf(10f).toInt())
                    }
                    b.previewContent.addView(note)
                }
            }
        }
    }

    /** 第 line 行在整篇文本里的 [start, end) 区间 */
    private fun lineRange(text: CharSequence, line: Int): Pair<Int, Int>? {
        var idx = 0
        var count = 0
        while (count < line) {
            val nl = text.indexOf('\n', idx)
            if (nl < 0) return null
            idx = nl + 1
            count++
        }
        val end = text.indexOf('\n', idx).let { if (it < 0) text.length else it }
        return idx to end
    }

    companion object {
        const val EXTRA_KEY = "doc_key"
        fun start(ctx: Context, key: String) {
            ctx.startActivity(Intent(ctx, EditorActivity::class.java).putExtra(EXTRA_KEY, key))
        }
    }
}
