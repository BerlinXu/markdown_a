package com.qoder.mdreader.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.qoder.mdreader.R
import com.qoder.mdreader.core.*
import com.qoder.mdreader.databinding.ActivityReaderBinding
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class ReaderViewModel(app: android.app.Application) : AndroidViewModel(app) {
    var document: DocRef? = null
    var raw: String? = null
    var blocks: List<Block> = emptyList()
    var rows: List<Block> = emptyList()
    var toc: List<TocEntry> = emptyList()
    val collapsed = HashSet<Int>()
    var anchor: ReadingAnchor? = null
    var generation = 0
    var busy = false
    val parses = Mutex()
}

class ReaderActivity : AppCompatActivity() {
    private var landscapeChrome: LandscapeChrome? = null
    private lateinit var b: ActivityReaderBinding
    private lateinit var store: DocStore
    private lateinit var prefs: Prefs
    private lateinit var state: ReadingState
    private lateinit var th: DocTheme
    private lateinit var files: DocumentActions
    private lateinit var layout: LinearLayoutManager
    private val vm: ReaderViewModel by viewModels { ViewModelProvider.AndroidViewModelFactory(application) }
    private var renderer: DocRenderer? = null
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var rows: List<Block> = emptyList()
    private var drawerOpen = false
    private var searchQuery = ""
    private val documentAdapter = DocumentAdapter()
    private val tocAdapter = TocAdapter()
    private var pendingLink: String? = null
    private var pendingAnchor: String? = null
    private val treePicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        val result = runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }.recoverCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        if (result.isFailure) { hint("未获得目录访问权限"); return@registerForActivityResult }
        val r = vm.document ?: return@registerForActivityResult
        store.setTree(r.key, uri)
        remember(); render()
        pendingLink?.let { pendingLink = null; openRelative(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(b.root)
        applyInsets(b.root)
        landscapeChrome = LandscapeChrome.install(this, b.root, listOf(b.topBar, b.topDivider))
        store = DocStore(applicationContext); prefs = Prefs(this); state = ReadingState(this); th = DocTheme(this)
        files = DocumentActions(this)
        pendingAnchor = if (savedInstanceState == null) intent.getStringExtra("anchor") else null
        layout = LinearLayoutManager(this)
        b.content.layoutManager = layout
        b.content.adapter = documentAdapter
        b.content.itemAnimator = null
        b.content.setItemViewCacheSize(3)
        b.tocList.layoutManager = LinearLayoutManager(this)
        b.tocList.adapter = tocAdapter
        b.progressLine.trackColor = th.line2; b.progressLine.fillColor = th.accent
        b.progressLine.visibility = if (prefs.showProgress) View.VISIBLE else View.GONE
        b.btnToc.visibility = if (prefs.showToc) View.VISIBLE else View.GONE
        b.btnBack.setOnClickListener { finish() }
        b.btnTop.setOnClickListener { layout.scrollToPositionWithOffset(0, 0) }
        b.btnEdit.setOnClickListener {
            if (vm.busy) { hint("请等待保存完成"); return@setOnClickListener }
            remember(); vm.document?.let { EditorActivity.start(this, it.key) }
        }
        b.btnMore.setOnClickListener { showMore(it) }
        b.btnToc.setOnClickListener { toggleDrawer(!drawerOpen) }
        b.scrim.setOnClickListener { toggleDrawer(false) }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (drawerOpen) toggleDrawer(false) else finish() }
        })
        b.btnFont.setOnClickListener {
            ReaderOptionsSheet(this, prefs, th, { rebuild -> if (!rebuild) { remember(); render() } }, { shareDiagnostic() }).show()
        }
        b.content.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = updateProgress()
        })
        vm.anchor = savedInstanceState?.takeIf { it.containsKey("line") }?.let { ReadingAnchor(it.getInt("line"), it.getInt("offset"), segment = it.getInt("segment")) } ?: vm.anchor
        val key = intent.getStringExtra(EXTRA_KEY)
        val uri = intent.getParcelableExtra<Uri>(EXTRA_URI)
        vm.viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { key?.let { store.find(it) } ?: uri?.let { store.adopt(it) } } }
            if (isDestroyed) return@launch
            val r = result.getOrNull() ?: run { hint("文档不可用：${result.exceptionOrNull()?.message ?: "请重新选择"}"); finish(); return@launch }
            vm.document = r
            store.markOpened(r)
            if (vm.anchor == null && prefs.rememberPosition) vm.anchor = state.position(r.key)
            reload()
        }
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        landscapeChrome?.onTouch(event)
        return super.dispatchTouchEvent(event)
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        landscapeChrome?.onWindowFocusChanged(hasFocus)
    }
    override fun onResume() { super.onResume(); if (::store.isInitialized && vm.document != null && !vm.busy) reload() }
    override fun onPause() { remember(); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) {
        remember(); vm.anchor?.let { outState.putInt("line", it.line); outState.putInt("offset", it.offset); outState.putInt("segment", it.segment) }
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() { loadJob?.cancel(); searchJob?.cancel(); super.onDestroy() }

    private fun reload() {
        val r = vm.document?.copy() ?: return
        val generation = ++vm.generation
        loadJob?.cancel()
        loadJob = vm.viewModelScope.launch {
            val result = vm.parses.withLock { withContext(Dispatchers.IO) {
                runCatching {
                    val text = store.read(r)
                    ensureActive()
                    val blocks = MarkdownParser.parse(text, prefs.singleBreakNewline)
                    Loaded(text, blocks, ReadingBlocks.prepare(blocks), MarkdownParser.wordCount(blocks), MarkdownParser.headings(blocks))
                }
            } }
            if (isDestroyed || generation != vm.generation) return@launch
            result.onSuccess { (text, blocks, prepared, words, toc) ->
                vm.document = r
                vm.raw = text; vm.blocks = blocks; vm.rows = prepared; vm.toc = toc
                b.docTitle.text = r.name + if (r.writable) "" else " · 只读"
                b.docSub.text = getString(R.string.reading_meta, words, (words / 320).coerceAtLeast(1), r.fileName())
                render()
                pendingAnchor?.takeIf { it.isNotBlank() }?.let { id ->
                    pendingAnchor = null
                    b.content.post { handleLink("#anchor:$id") }
                }
            }.onFailure { hint("读取失败：${it.message}"); b.loading.text = "文件暂不可用，请重新打开或授权" }
        }
    }

    private fun render() {
        val r = vm.document ?: return
        val width = resources.displayMetrics.widthPixels
        val pad = resources.getDimensionPixelSize(R.dimen.content_h_pad)
        val max = resources.getDimensionPixelSize(R.dimen.content_max_width)
        val side = maxOf(pad, (width - max) / 2)
        b.content.setPadding(side, b.content.paddingTop, side, b.content.paddingBottom)
        renderer = DocRenderer(this, th, prefs, r.localPath?.let { File(it).parentFile }, { handleLink(it) }, { line, _ -> toggleTask(line) },
            { url -> RelativeDocuments.resolve(applicationContext, r, url, store.treeFor(r.key))?.toString() })
        var hiddenLevel: Int? = null
        rows = vm.rows.filter { block ->
            if (block is Block.Heading) {
                if (hiddenLevel != null && block.level <= hiddenLevel!!) hiddenLevel = null
                if (hiddenLevel == null) {
                    if (block.line in vm.collapsed) hiddenLevel = block.level
                    true
                } else false
            } else hiddenLevel == null
        }
        documentAdapter.notifyDataSetChanged(); tocAdapter.notifyDataSetChanged()
        b.tocCount.text = getString(R.string.toc_fmt, vm.toc.size)
        b.loading.visibility = View.GONE
        vm.anchor?.let { anchor -> b.content.post { jump(anchor.line, anchor.offset, false, anchor.segment) } }
    }

    private fun remember() {
        if (!::layout.isInitialized) return
        val index = layout.findFirstVisibleItemPosition()
        val row = rows.getOrNull(index) ?: return
        val anchor = ReadingAnchor(row.line, (layout.findViewByPosition(index)?.top ?: b.content.paddingTop) - b.content.paddingTop,
            segment = rows.take(index).count { it.line == row.line })
        vm.anchor = anchor
        vm.document?.let { if (prefs.rememberPosition) { state.setPosition(it.key, anchor); prefs.setReadPos(it.key, b.progressLine.ratio) } }
    }
    private fun jump(line: Int, offset: Int = 0, expand: Boolean = true, segment: Int = 0) {
        if (expand && vm.collapsed.isNotEmpty()) { vm.collapsed.clear(); vm.anchor = ReadingAnchor(line, offset, segment = segment); render(); return }
        val index = rows.indices.filter { rows[it].line == line }.getOrNull(segment)
            ?: rows.indices.filter { rows[it].line <= line }.maxByOrNull { rows[it].line } ?: 0
        layout.scrollToPositionWithOffset(index, offset)
        b.content.post {
            val root = layout.findViewByPosition(index) as? ViewGroup
            val target = root?.let { renderer?.findViewForLine(it, line) }
            if (target != null && target != root && offset == 0) {
                val delta = renderer?.offsetFrom(target, root) ?: 0
                if (delta > 0) b.content.scrollBy(0, delta)
            }
        }
    }
    private fun updateProgress() {
        val first = layout.findFirstVisibleItemPosition().coerceAtLeast(0)
        val last = layout.findLastVisibleItemPosition().coerceAtLeast(first)
        val ratio = if (rows.isEmpty()) 0f else if (!b.content.canScrollVertically(1)) 1f else first.toFloat() / rows.size
        b.progressLine.ratio = ratio
        b.btnTop.visibility = if (first > 1) View.VISIBLE else View.INVISIBLE
        val line = rows.getOrNull((first + last) / 2)?.line ?: 0
        tocAdapter.current = vm.toc.indexOfLast { it.line <= line }
    }
    private fun toggleDrawer(open: Boolean) {
        drawerOpen = open
        b.drawer.visibility = if (open) View.VISIBLE else View.INVISIBLE
        b.scrim.visibility = if (open) View.VISIBLE else View.GONE
    }

    private fun toggleTask(line: Int) {
        val r = vm.document?.copy() ?: return
        val source = vm.raw ?: return
        if (!prefs.taskWritesBack || !r.writable) { hint("来源只读，请在编辑页另存为"); documentAdapter.notifyDataSetChanged(); return }
        if (vm.busy) { hint("请等待保存完成"); return }
        val marker = source.lineSequence().drop(line).firstOrNull()?.let { Regex("\\[([ xX✓√])\\]").find(it) } ?: return
        val updated = toggleTaskInSource(source, line, marker.groupValues[1] == " ") ?: return
        remember(); vm.busy = true
        loadJob?.cancel(); vm.generation++
        vm.viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { store.write(r, updated) } }
            vm.busy = false
            if (isDestroyed) return@launch
            result.onSuccess {
                vm.document = r; vm.raw = updated
                val checked = marker.groupValues[1] == " "
                vm.blocks = updateTask(vm.blocks, line, checked)
                vm.rows = updateTask(vm.rows, line, checked)
                rows = updateTask(rows, line, checked)
                rows.forEachIndexed { index, block -> if (containsTask(block, line)) documentAdapter.notifyItemChanged(index) }
            }.onFailure { hint("保存失败：${it.message}"); reload() }
        }
    }
    private fun containsTask(block: Block, line: Int): Boolean = when (block) {
        is Block.ListBlock -> block.items.any { (it.task != TaskState.NONE && it.markerLine == line) || it.blocks.any { child -> containsTask(child, line) } }
        is Block.Quote -> block.kids.any { containsTask(it, line) }
        else -> false
    }
    private fun updateTask(blocks: List<Block>, line: Int, checked: Boolean): List<Block> = blocks.map { block ->
        if (!containsTask(block, line)) block else when (block) {
            is Block.ListBlock -> block.copy(items = block.items.map { item -> item.copy(
                task = if (item.task != TaskState.NONE && item.markerLine == line) { if (checked) TaskState.CHECKED else TaskState.UNCHECKED } else item.task,
                blocks = updateTask(item.blocks, line, checked)) })
            is Block.Quote -> block.copy(kids = updateTask(block.kids, line, checked))
            else -> block
        }
    }
    private fun handleLink(url: String) {
        if (url.startsWith("#footnote:")) {
            val line = vm.blocks.filterIsInstance<Block.Paragraph>().firstOrNull { block ->
                (block.inline.firstOrNull() as? Inline.Link)?.url == url
            }?.line
            if (line != null) jump(line) else hint("脚注不存在")
            return
        }
        if (url.startsWith("#anchor:")) {
            val id = Uri.decode(url.removePrefix("#anchor:"))
            vm.toc.firstOrNull { slug(it.title) == id || it.title == id }?.let { jump(it.line) } ?: hint(R.string.toc_missing)
            return
        }
        val uri = Uri.parse(url)
        if (uri.scheme == null) { openRelative(url); return }
        if (uri.scheme !in setOf("https", "http", "mailto", "tel")) { hint("不支持此链接类型"); return }
        MaterialAlertDialogBuilder(this).setTitle(R.string.open_link_title).setMessage(url)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.open) { _, _ ->
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }.onFailure { hint(R.string.no_app_for_link) }
            }.show()
    }
    private fun openRelative(url: String) {
        val r = vm.document ?: return
        val path = url.substringBefore('#')
        if (path.substringAfterLast('.', "").lowercase() !in SUPPORTED_EXT) { hint("仅支持打开相对文档链接"); return }
        if (!r.isLocal && store.treeFor(r.key) == null) {
            MaterialAlertDialogBuilder(this).setTitle("选择文档所在目录").setMessage("访问相邻文档需要该目录的授权。")
                .setNegativeButton(R.string.cancel, null).setPositiveButton("选择目录") { _, _ -> pendingLink = url; treePicker.launch(null) }.show()
            return
        }
        vm.viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                val tree = store.treeFor(r.key)
                val uri = RelativeDocuments.resolve(this@ReaderActivity, r, url, tree) ?: error("文档不存在或超出授权目录")
                if (uri.scheme == "file" && File(uri.path!!).parentFile == store.appDir()) store.find(DocRef.PREFIX_LOCAL + File(uri.path!!).name)
                else store.adopt(uri, tree?.let { Uri.parse(it) })
            } }
            if (isDestroyed) return@launch
            result.getOrNull()?.let { doc -> startActivity(Intent(this@ReaderActivity, ReaderActivity::class.java)
                .putExtra(EXTRA_KEY, doc.key).putExtra("anchor", url.substringAfter('#', ""))) }
                ?: hint("打开失败：${result.exceptionOrNull()?.message ?: "无法读取"}")
        }
    }
    private fun slug(text: String) = text.trim().lowercase().replace(Regex("\\s+"), "-").replace(Regex("[^\\w\\u4e00-\\u9fff-]"), "")
    private fun showMore(anchor: View) {
        val r = vm.document ?: return
        val menu = PopupMenu(this, anchor)
        listOf("文内搜索", "添加书签", "我的书签", "导出文档", "分享文件", "导出原文恢复副本", "关联文档目录", "指定源文件编码").forEachIndexed { i, title -> menu.menu.add(0, i, i, title) }
        menu.menu.findItem(6).isVisible = !r.isLocal
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                0 -> search()
                1 -> { remember(); vm.anchor?.let { a -> state.add(r.key, a.copy(label = vm.toc.lastOrNull { h -> h.line <= a.line }?.title ?: "第 ${a.line + 1} 行")); hint("书签已添加") } }
                2 -> bookmarks()
                3 -> files.export(r)
                4 -> files.share(r)
                5 -> files.export(r, recovery = true)
                6 -> treePicker.launch(null)
                7 -> chooseEncoding()
            }; true
        }
        menu.show()
    }
    private fun chooseEncoding() {
        val r = vm.document ?: return
        val names = arrayOf("自动识别", "UTF-8", "GB18030", "Big5", "windows-1252", "UTF-16LE", "UTF-16BE")
        MaterialAlertDialogBuilder(this).setTitle("源文件编码（不转换文件）").setItems(names) { _, index ->
            val charset = names[index].takeIf { index != 0 }
            vm.viewModelScope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { store.read(r.copy(), charset); store.setEncoding(r.key, charset) } }
                if (isDestroyed) return@launch
                result.onSuccess { remember(); reload() }.onFailure { hint("无法按此编码读取：${it.message}") }
            }
        }.show()
    }
    private fun bookmarks() {
        val r = vm.document ?: return
        val marks = state.bookmarks(r.key)
        if (marks.isEmpty()) { hint("暂无书签"); return }
        MaterialAlertDialogBuilder(this).setTitle("书签（长按正文标题可折叠章节）")
            .setItems(marks.map { it.label }.toTypedArray()) { _, index -> jump(marks[index].line, marks[index].offset, segment = marks[index].segment) }
            .setNeutralButton("删除书签") { _, _ ->
                MaterialAlertDialogBuilder(this).setItems(marks.map { it.label }.toTypedArray()) { _, index -> state.remove(r.key, marks[index].line) }.show()
            }.setNegativeButton(R.string.cancel, null).show()
    }
    private fun search() {
        val input = EditText(this).apply { hint = "搜索当前文档"; setSingleLine(); setText(searchQuery) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 16, 32, 16); addView(input) }
        val label = TextView(this).apply { text = "输入关键词" }; box.addView(label)
        var hits = emptyList<Int>(); var index = -1
        fun move(step: Int) {
            if (hits.isEmpty()) return
            index = (index + step + hits.size) % hits.size
            jump(hits[index]); label.text = "${index + 1} / ${hits.size} 个段落"
        }
        val dialog = MaterialAlertDialogBuilder(this).setTitle("文内搜索").setView(box)
            .setPositiveButton("下一处", null).setNegativeButton("上一处", null).setNeutralButton("完成", null).create()
        fun update(query: String) {
            searchQuery = query
            searchJob?.cancel()
            searchJob = vm.viewModelScope.launch {
                delay(200)
                hits = withContext(Dispatchers.Default) {
                    if (query.isBlank()) emptyList() else vm.rows.filter { block ->
                        ensureActive(); blocksToPlainText(listOf(block)).contains(query, true)
                    }.map { it.line }.distinct().take(1000)
                }
                if (!isDestroyed) { index = -1; label.text = if (hits.isEmpty()) "没有匹配" else "找到 ${hits.size} 个段落"; documentAdapter.notifyDataSetChanged(); move(1) }
            }
        }
        input.doAfterTextChanged { update(it?.toString() ?: "") }
        dialog.setOnShowListener {
            dialog.getButton(-1).setOnClickListener { move(1) }
            dialog.getButton(-2).setOnClickListener { move(-1) }
            update(searchQuery)
        }
        dialog.setOnDismissListener { searchJob?.cancel(); searchQuery = ""; documentAdapter.notifyDataSetChanged() }
        dialog.show()
    }
    private fun shareDiagnostic() {
        val text = "${getString(R.string.app_name)}：Android ${android.os.Build.VERSION.SDK_INT}；文档块 ${vm.rows.size}；已挂载视图 ${b.content.childCount}；字号 ${prefs.fontScale}"
        runCatching { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "分享诊断")) }
    }
    private inner class DocumentAdapter : RecyclerView.Adapter<DocumentAdapter.Holder>() {
        override fun getItemCount() = rows.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val block = rows[position]
            holder.root.removeAllViews()
            renderer?.viewFor(block)?.let { holder.root.addView(it) }
            holder.root.setBackgroundColor(if (searchQuery.isNotBlank() && blocksToPlainText(listOf(block)).contains(searchQuery, true)) th.accentSoft else android.graphics.Color.TRANSPARENT)
            holder.root.setOnLongClickListener(if (block is Block.Heading) View.OnLongClickListener {
                remember(); if (!vm.collapsed.add(block.line)) vm.collapsed.remove(block.line); render(); true
            } else null)
            if (block is Block.Heading) holder.root.getChildAt(0)?.setOnLongClickListener { holder.root.performLongClick() }
        }
        override fun onViewRecycled(holder: Holder) { holder.root.removeAllViews() }
        inner class Holder(val root: LinearLayout) : RecyclerView.ViewHolder(root)
    }
    private inner class TocAdapter : RecyclerView.Adapter<TocAdapter.Holder>() {
        var current = -1
            set(value) { if (field == value) return; val old = field; field = value; if (old >= 0) notifyItemChanged(old); if (value >= 0) notifyItemChanged(value) }
        override fun getItemCount() = vm.toc.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(TextView(parent.context).apply { textSize = 14f; layoutParams = RecyclerView.LayoutParams(-1, -2) })
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = vm.toc[position]
            holder.text.text = (if (entry.line in vm.collapsed) "▸ " else "") + entry.title
            holder.text.setPadding(th.dpf(14f + entry.level * 10f).toInt(), th.dpf(12f).toInt(), 16, th.dpf(12f).toInt())
            holder.text.setTextColor(if (position == current) th.accent else th.ink)
            holder.text.setOnClickListener { jump(entry.line); toggleDrawer(false) }
            holder.text.setOnLongClickListener { remember(); if (!vm.collapsed.add(entry.line)) vm.collapsed.remove(entry.line); render(); true }
        }
        inner class Holder(val text: TextView) : RecyclerView.ViewHolder(text)
    }
    companion object {
        const val EXTRA_KEY = "doc_key"
        const val EXTRA_URI = "doc_uri"
        fun start(ctx: Context, key: String) { ctx.startActivity(Intent(ctx, ReaderActivity::class.java).putExtra(EXTRA_KEY, key)) }
    }
    private data class Loaded(val text: String, val blocks: List<Block>, val rows: List<Block>, val words: Int, val toc: List<TocEntry>)
}
