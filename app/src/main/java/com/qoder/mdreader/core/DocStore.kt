package com.qoder.mdreader.core

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 一份文档的稳定身份。
 *
 * 两类来源：
 *  - LOCAL：App 自己的 Documents 目录，直接 File 读写，不需要任何权限
 *  - SAF ：用户在系统选择器里挑中的文件，靠持久化 URI 授权读写
 */
data class DocRef(
    val key: String,
    var name: String,
    var ext: String,
    val localPath: String?,
    val uriString: String?,
    var size: Long = 0,
    var modified: Long = 0,
    var lastOpened: Long = 0,
    var fingerprint: String? = null,
    var writable: Boolean = true,
    var format: TextFormat? = null
) {
    val isLocal: Boolean get() = localPath != null
    fun fileName(): String = if (ext.isEmpty()) name else "$name.$ext"
    fun title(): String = name

    companion object {
        const val PREFIX_LOCAL = "L:"
        const val PREFIX_SAF = "S:"
    }
}

/** 支持的扩展名，顺序也决定新建文件默认用哪个 */
val SUPPORTED_EXT = setOf("md", "markdown", "mdown", "mkd", "txt")

class DocStore(private val ctx: Context) {

    private val sp = ctx.getSharedPreferences("md_docs", Context.MODE_PRIVATE)
    private val cr get() = ctx.contentResolver

    /** Locks are shared across store instances; encoding and byte version live on DocRef. */
    companion object {
        private val locks = Array(64) { Any() }
        private val revisions = java.util.concurrent.atomic.AtomicLong()
        fun nextDraftRevision(): Long = revisions.updateAndGet { maxOf(it + 1, System.currentTimeMillis()) }
        private fun lock(key: String) = locks[(key.hashCode() and Int.MAX_VALUE) % locks.size]
    }
    private fun stateFile(key: String, dir: String): File = File(ctx.filesDir, "$dir/${SafeIo.hash(key.toByteArray())}")
    private fun atomicWrite(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, ".${file.name}.${java.util.UUID.randomUUID()}.pending")
        try {
            java.io.FileOutputStream(temporary).use { output -> output.write(bytes); output.fd.sync() }
            java.nio.file.Files.move(temporary.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
    private fun rawBytes(ref: DocRef): ByteArray = ref.localPath?.let {
        File(it).inputStream().use { input -> SafeIo.read(input, SafeIo.DOCUMENT_LIMIT) }
    } ?: cr.openInputStream(Uri.parse(ref.uriString ?: error("文档身份丢失")))?.use {
        SafeIo.read(it, SafeIo.DOCUMENT_LIMIT)
    } ?: error("文件流为空")

    fun snapshot(ref: DocRef): ByteArray = synchronized(lock(ref.key)) { rawBytes(ref) }
    fun export(ref: DocRef, target: Uri) = synchronized(lock(ref.key)) {
        require(target.toString() != ref.uriString) { "请选择另一个文件" }
        val bytes = rawBytes(ref)
        cr.openOutputStream(target, "wt")?.use { it.write(bytes) } ?: error("无法导出")
    }
    fun shareFile(ref: DocRef): File = synchronized(lock(ref.key)) {
        val file = File(ctx.cacheDir, "shared/${SafeIo.hash(ref.key.toByteArray()).take(16)}/${sanitizeName(ref.name)}.${ref.ext}")
        atomicWrite(file, rawBytes(ref)); file
    }
    fun recoveryOf(key: String): File? = stateFile(key, "recovery").takeIf { it.exists() }
    fun treeFor(key: String): String? = sp.getString("tree|$key", null)
    fun setTree(key: String, uri: Uri) { sp.edit().putString("tree|$key", uri.toString()).apply() }


    /** App 专属外部目录：无需权限，卸载即清理 */
    fun appDir(): File {
        val base = ctx.getExternalFilesDir("Documents") ?: File(ctx.filesDir, "Documents")
        if (!base.exists()) base.mkdirs()
        return base
    }

    // ---------------------------------------------------------------- 读

    fun read(ref: DocRef, charset: String? = sp.getString("encoding|${ref.key}", null)): String = synchronized(lock(ref.key)) {
        val bytes = rawBytes(ref)
        val (text, format) = TextCodec.decode(bytes, charset)
        ref.fingerprint = SafeIo.hash(bytes)
        ref.format = format
        ref.size = bytes.size.toLong()
        ref.modified = fileOf(ref)?.lastModified() ?: ref.modified
        text
    }
    fun setEncoding(key: String, charset: String?) { sp.edit().putString("encoding|$key", charset).apply() }

    fun canRead(ref: DocRef): Boolean = runCatching { read(ref) }.isSuccess

    /**
     * UTF-8 优先；失败再退 GBK。国内流传的 .md 有不少是 GBK 编码，
     * 直接按 UTF-8 强解会得到一串乱码方块。
     */
    private fun decodeSmart(bytes: ByteArray): String = TextCodec.decode(bytes).first

    // ---------------------------------------------------------------- 写

    /** 返回写入后的真实字节数与时间戳 */
    fun write(ref: DocRef, text: String) = synchronized(lock(ref.key)) {
        require(ref.writable) { "这是只读文件，请使用另存为" }
        require(!text.contains('\u0000') && text.count { it.code < 32 && it !in "\n\r\t" } <= 2) { "文档包含不支持的控制字符，请先检查或另存" }
        val old = rawBytes(ref)
        val currentHash = SafeIo.hash(old)
        require(ref.fingerprint != null && ref.fingerprint == currentHash) {
            "文件已被其他页面或应用修改。已保留草稿，请另存为或重新打开。"
        }
        val format = ref.format ?: TextCodec.decode(old).second
        val bytes = format.encode(text)
        require(bytes.size <= SafeIo.DOCUMENT_LIMIT) { "文档超过 10 MB 限制" }
        if (old.contentEquals(bytes)) return@synchronized
        atomicWrite(stateFile(ref.key, "recovery"), old)
        ref.localPath?.let { atomicWrite(File(it), bytes) } ?: run {
            val uri = Uri.parse(ref.uriString ?: error("文档身份丢失"))
            cr.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("无法打开输出流；原文恢复副本已保留")
            check(rawBytes(ref).contentEquals(bytes)) { "提供方未完整保存，原文恢复副本和草稿已保留" }
        }
        ref.fingerprint = SafeIo.hash(bytes)
        ref.format = format
        ref.size = bytes.size.toLong()
        ref.modified = fileOf(ref)?.lastModified() ?: System.currentTimeMillis()
        touch(ref)
    }

    // ---------------------------------------------------------------- 创建 / 改名 / 删除

    fun createBlank(rawName: String): DocRef {
        val base = sanitizeName(rawName)
        var candidate = base
        var i = 2
        while (File(appDir(), "$candidate.md").exists()) {
            candidate = "$base($i)"
            i++
        }
        val file = File(appDir(), "$candidate.md")
        check(file.createNewFile()) { "文件名冲突，请重试" }
        return DocRef(
            key = DocRef.PREFIX_LOCAL + file.name,
            name = candidate,
            ext = "md",
            localPath = file.absolutePath,
            uriString = null,
            size = 0,
            modified = file.lastModified(),
            fingerprint = SafeIo.hash(ByteArray(0))
        ).also { touch(it) }
    }

    fun importText(name: String, text: String): DocRef {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= SafeIo.DOCUMENT_LIMIT) { "文档超过 10 MB 限制" }
        val format = TextCodec.decode(bytes).second
        val ref = createBlank(name)
        ref.format = format
        try { write(ref, text) } catch (error: Exception) { delete(ref); throw error }
        return ref
    }

    fun rename(ref: DocRef, rawNewName: String): DocRef? = synchronized(lock(ref.key)) renameFile@ {
        val clean = sanitizeName(rawNewName)
        if (clean.isEmpty()) return@renameFile null
        val old = File(ref.localPath ?: return@renameFile null)
        val target = File(old.parentFile, "$clean.${ref.ext}")
        if (target.absolutePath == old.absolutePath) return@renameFile ref
        if (target.exists()) return@renameFile null
        if (!old.renameTo(target)) return@renameFile null
        val renamed = DocRef(
            key = DocRef.PREFIX_LOCAL + target.name,
            name = clean,
            ext = ref.ext,
            localPath = target.absolutePath,
            uriString = null,
            size = target.length(),
            modified = target.lastModified(),
            lastOpened = ref.lastOpened
        )
        run {
            draftOf(ref.key)?.let { saveDraft(renamed.key, it); clearDraft(ref.key) }
            recoveryOf(ref.key)?.let { atomicWrite(stateFile(renamed.key, "recovery"), it.readBytes()); it.delete() }
            for (prefsName in listOf("md_prefs", "md_reading_state")) {
                val state = ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
                val editor = state.edit()
                state.all.filterKeys { it.endsWith("|${ref.key}") }.forEach { (key, value) ->
                    val newKey = key.removeSuffix(ref.key) + renamed.key
                    when (value) {
                        is String -> editor.putString(newKey, value)
                        is Float -> editor.putFloat(newKey, value)
                        is Int -> editor.putInt(newKey, value)
                    }
                    editor.remove(key)
                }
                editor.apply()
            }
            val opened = lastOpened(ref.key)
            forget(ref.key)
            touch(renamed)
            if (opened > 0) markOpened(renamed)
        }
        renamed
    }

    /** A failed provider delete must never be replaced by truncating the file. */
    fun delete(ref: DocRef): Boolean = synchronized(lock(ref.key)) {
        val ok = ref.localPath?.let { File(it).delete() } ?: runCatching {
            DocumentsContract.deleteDocument(cr, Uri.parse(ref.uriString ?: return@synchronized false))
        }.getOrDefault(false)
        if (ok) { clearDraft(ref.key); stateFile(ref.key, "recovery").delete(); release(ref) }
        ok
    }

    // ---------------------------------------------------------------- 列表

    /** App 目录里的全部文档 + 用户授权过的外部文档 */
    fun listAll(): List<DocRef> {
        val out = ArrayList<DocRef>()
        appDir().listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            val ext = f.extension.lowercase()
            if (ext !in SUPPORTED_EXT) return@forEach
            out += DocRef(
                key = DocRef.PREFIX_LOCAL + f.name,
                name = f.nameWithoutExtension,
                ext = ext,
                localPath = f.absolutePath,
                uriString = null,
                size = f.length(),
                modified = f.lastModified()
            )
        }
        out += knownSafDocs()
        return out
    }

    /** 书架排序：最近打开优先，其次按修改时间 */
    fun sortedShelf(): List<DocRef> {
        val all = listAll().toMutableList()
        val opened = sp.getString("recent_order", "")!!.split("\u0001").filter { it.isNotBlank() }
        val rank = opened.withIndex().associate { (i, k) -> k to i }
        all.sortWith(compareBy<DocRef> { rank[it.key] ?: Int.MAX_VALUE }
            .thenByDescending { it.lastOpened }
            .thenByDescending { it.modified })
        return all
    }

    /** Keep cached SAF entries even when their provider is temporarily offline. */
    fun knownSafDocs(): List<DocRef> =
        (sp.getStringSet("saf_keys", emptySet()) ?: emptySet()).mapNotNull { find(it) }

    /** Only persistent grants enter the shelf; temporary shares are copied while their grant is valid. */
    fun adopt(uri: Uri, tree: Uri? = null): DocRef? {
        require(uri.scheme == "content" || (uri.scheme == "file" &&
            File(uri.path ?: error("无效文件路径")).canonicalPath.startsWith(appDir().canonicalPath + File.separator))) {
            "请通过系统文件选择器授权外部文件"
        }
        val display = safDisplayName(uri) ?: "收到文档.md"
        val ext = display.substringAfterLast('.', "md").lowercase()
        if (ext !in SUPPORTED_EXT) return null
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { cr.takePersistableUriPermission(uri, read or write) }
            .onFailure { runCatching { cr.takePersistableUriPermission(uri, read) } }
        val grant = cr.persistedUriPermissions.firstOrNull { (it.uri == uri || it.uri == tree) && it.isReadPermission }
        if (grant == null) {
            val bytes = cr.openInputStream(uri)?.use { SafeIo.read(it, SafeIo.DOCUMENT_LIMIT) } ?: return null
            TextCodec.decode(bytes)
            val ref = createBlank(display.substringBeforeLast('.'))
            atomicWrite(File(ref.localPath!!), bytes)
            read(ref)
            touch(ref)
            return ref
        }
        val key = DocRef.PREFIX_SAF + uri
        val set = HashSet(sp.getStringSet("saf_keys", emptySet()) ?: emptySet())
        set += key
        val flags = queryMeta(uri, DocumentsContract.Document.COLUMN_FLAGS)?.toIntOrNull() ?: 0
        val ref = DocRef(key, sanitizeName(display.substringBeforeLast('.')), ext, null, uri.toString(),
            size = safSize(uri), writable = grant.isWritePermission &&
                flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE != 0)
        sp.edit().putStringSet("saf_keys", set).putString("uri|$key", uri.toString())
            .putString("ext|$key", ext).putBoolean("write|$key", ref.writable).apply()
        if (tree != null) setTree(key, tree)
        touch(ref)
        return ref
    }

    fun release(ref: DocRef) {
        ref.uriString?.let {
            runCatching {
                ctx.contentResolver.releasePersistableUriPermission(
                    Uri.parse(it),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        val set = HashSet(sp.getStringSet("saf_keys", emptySet()) ?: emptySet())
        set -= ref.key
        sp.edit().putStringSet("saf_keys", set).apply()
        forget(ref.key)
    }

    private fun safDisplayName(uri: Uri): String? = queryMeta(uri, OpenableColumns.DISPLAY_NAME)

    private fun safSize(uri: Uri): Long =
        queryMeta(uri, OpenableColumns.SIZE)?.toLongOrNull() ?: 0L

    private fun queryMeta(uri: Uri, column: String): String? {
        var c: Cursor? = null
        return try {
            c = cr.query(uri, arrayOf(column), null, null, null)
            if (c != null && c.moveToFirst() && c.columnCount > 0 && !c.isNull(0)) c.getString(0) else null
        } catch (e: Exception) {
            null
        } finally {
            c?.close()
        }
    }

    // ---------------------------------------------------------------- 元数据

    /** 记住/刷新一个文档的时间戳，写文件后和打开后都要调 */
    fun touch(ref: DocRef) {
        val e = sp.edit()
        e.putLong("mod|${ref.key}", ref.modified)
        e.putLong("size|${ref.key}", ref.size)
        e.putString("name|${ref.key}", ref.name)
        e.apply()
    }

    fun markOpened(ref: DocRef) {
        val order = sp.getString("recent_order", "")!!.split("\u0001").filter { it.isNotBlank() }.toMutableList()
        order.remove(ref.key)
        order.add(0, ref.key)
        sp.edit().putString("recent_order", order.joinToString("\u0001")).putLong("open|${ref.key}", System.currentTimeMillis()).apply()
    }

    fun lastOpened(key: String): Long = sp.getLong("open|$key", 0)

    fun forget(key: String) {
        val order = sp.getString("recent_order", "")!!.split("\u0001").filter { it.isNotBlank() && it != key }
        sp.edit()
            .putString("recent_order", order.joinToString("\u0001"))
            .remove("mod|$key").remove("size|$key").remove("name|$key").remove("open|$key")
            .apply()
    }

    fun find(key: String): DocRef? {
        if (key.startsWith(DocRef.PREFIX_LOCAL)) {
            val name = key.removePrefix(DocRef.PREFIX_LOCAL)
            val dir = appDir().canonicalFile
            val file = File(dir, name).canonicalFile
            if (file.parentFile != dir || !file.isFile) return null
            return DocRef(key, file.nameWithoutExtension, file.extension.lowercase(), file.path, null,
                file.length(), file.lastModified(), lastOpened(key))
        }
        if (!key.startsWith(DocRef.PREFIX_SAF)) return null
        val uri = key.removePrefix(DocRef.PREFIX_SAF)
        return DocRef(key, sp.getString("name|$key", "外部文档") ?: "外部文档",
            sp.getString("ext|$key", "md") ?: "md", null, uri,
            sp.getLong("size|$key", 0), sp.getLong("mod|$key", 0), lastOpened(key),
            writable = sp.getBoolean("write|$key", false))
    }

    /** SAF 授权是否仍然有效（重启后可能被系统收回） */
    fun canAccessSaf(ref: DocRef): Boolean {
        if (ref.isLocal) return File(ref.localPath!!).exists()
        val uri = ref.uriString ?: return false
        return runCatching {
            cr.openInputStream(Uri.parse(uri))?.use { true } ?: false
        }.getOrDefault(false)
    }

    fun fileOf(ref: DocRef): File? = ref.localPath?.let { File(it) }

    /**
     * 只读文件开头若干字节。
     *
     * 书架一次要显示十几篇文档的摘要，其中不乏两万字长文；
     * 为了一行摘要把整篇读进内存再解码，是进书架卡顿的直接原因。
     */
    private fun readHead(ref: DocRef, bytes: Int): ByteArray {
        val ins = ref.localPath?.let { File(it).inputStream() }
            ?: ref.uriString?.let { cr.openInputStream(Uri.parse(it)) }
            ?: return ByteArray(0)
        return ins.use {
            val buf = ByteArray(bytes)
            var n = 0
            while (n < buf.size) {
                val count = it.read(buf, n, buf.size - n)
                if (count < 0) break
                if (count == 0) continue
                n += count
            }
            if (n <= 0) ByteArray(0) else trimToUtf8Boundary(buf.copyOfRange(0, n))
        }
    }

    /**
     * 按字节截断很容易把一个汉字的三个字节切成一半。
     * 那样严格 UTF-8 解码会失败，decodeSmart 就以为"这不是 UTF-8"，
     * 退去按 GBK 解，摘要于是变成「浠庨浛寮€€濮」这种乱码。
     * 所以先回退到最后一个完整的 UTF-8 字符边界。
     */
    private fun trimToUtf8Boundary(b: ByteArray): ByteArray {
        var i = b.size - 1
        var guard = 0
        while (i >= 0 && guard++ < 4) {
            val c = b[i].toInt() and 0xFF
            if (c and 0xC0 == 0x80) { i--; continue }          // 续字节，继续往前找起始字节
            val need = when {
                c and 0x80 == 0x00 -> 1
                c and 0xE0 == 0xC0 -> 2
                c and 0xF0 == 0xE0 -> 3
                c and 0xF8 == 0xF0 -> 4
                else -> 1
            }
            return if (i + need <= b.size) b.copyOfRange(0, i + need) else b.copyOfRange(0, i)
        }
        return b
    }

    /** 书架摘要：第一行非空文本，去掉 Markdown 标记 */
    fun preview(ref: DocRef, maxChars: Int = 46): String? = runCatching {
        val text = decodeSmart(readHead(ref, 4096))
        text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("```") }
            ?.replace(Regex("^#+\\s*"), "")
            ?.replace(Regex("^[>*+\\-\\s]+"), "")
            ?.replace(Regex("[*_`~]"), "")
            ?.replace(Regex("\\[([^]]*)]\\([^)]*\\)"), "$1")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(maxChars)
            ?.trimEnd('�', '?', ' ')
    }.onFailure { android.util.Log.w("DocStore", "摘要读取失败", it) }.getOrNull()

    // ------------------------------------------------------------ 未保存草稿

    /**
     * 编辑中途退到后台时把内容暂存下来，书架上显示朱砂点。
     * 真正写盘成功后立刻清掉，避免"看起来改了其实没存"的错觉。
     */
    fun saveDraft(key: String, text: String, revision: Long = nextDraftRevision()) = synchronized(lock(key)) {
        if (revision < draftTime(key)) return@synchronized
        atomicWrite(stateFile(key, "drafts"), text.toByteArray(StandardCharsets.UTF_8))
        sp.edit().remove("draft|$key").putLong("draftt|$key", revision).apply()
    }

    fun clearDraft(key: String) = synchronized(lock(key)) {
        stateFile(key, "drafts").delete()
        sp.edit().remove("draft|$key").remove("draftt|$key").apply()
    }
    fun clearDraftIfMatches(key: String, text: String) = synchronized(lock(key)) {
        if (draftOf(key) == text) clearDraft(key)
    }
    fun hasUnsavedDraft(key: String): Boolean = stateFile(key, "drafts").exists() || sp.contains("draft|$key")
    fun draftOf(key: String): String? = synchronized(lock(key)) {
        val revision = draftTime(key)
        revisions.updateAndGet { maxOf(it, revision) }
        val file = stateFile(key, "drafts")
        if (file.exists()) file.inputStream().use { String(SafeIo.read(it, SafeIo.DOCUMENT_LIMIT * 4), StandardCharsets.UTF_8) }
        else sp.getString("draft|$key", null)
    }
    fun draftTime(key: String): Long = sp.getLong("draftt|$key", 0)

    private fun sanitizeName(raw: String): String {
        val cleaned = raw.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim('.', ' ')
        return cleaned.ifEmpty { "未命名" }.take(60)
    }

}
