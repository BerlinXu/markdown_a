package com.qoder.mdreader.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.qoder.mdreader.MdApp
import com.qoder.mdreader.core.*
import com.qoder.mdreader.onMain
import java.io.File

/** Prepare an immutable snapshot before opening the picker; a small token survives rotation. */
class DocumentActions(private val activity: ComponentActivity) {
    private val store = DocStore(activity.applicationContext)
    private var pending: File? = activity.savedStateRegistry.consumeRestoredStateForKey("export")?.getString("file")
        ?.takeIf { !it.contains('/') && !it.contains('\\') }?.let { File(activity.cacheDir, "exports/$it") }
    private val picker = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        val file = pending ?: return@registerForActivityResult
        pending = null
        if (uri == null) { file.delete(); return@registerForActivityResult }
        MdApp.ioPool.execute {
            val result = runCatching {
                activity.contentResolver.openOutputStream(uri, "wt")?.use { output -> file.inputStream().use { it.copyTo(output) } }
                    ?: error("无法写入所选文件")
            }
            if (result.isSuccess) file.delete()
            onMain { if (!activity.isDestroyed) activity.hint(if (result.isSuccess) "已导出" else "导出失败：${result.exceptionOrNull()?.message}") }
        }
    }
    init {
        activity.savedStateRegistry.registerSavedStateProvider("export") {
            Bundle().apply { putString("file", pending?.name) }
        }
    }
    fun export(ref: DocRef, text: String? = null, recovery: Boolean = false) {
        MdApp.ioPool.execute {
            val result = runCatching {
                val bytes = if (recovery) store.recoveryOf(ref.key)?.readBytes() ?: error("暂无恢复副本")
                    else if (text != null) (ref.format ?: TextFormat()).encode(text)
                    else store.snapshot(ref)
                val file = File(activity.cacheDir, "exports/${java.util.UUID.randomUUID()}.md")
                file.parentFile?.mkdirs(); file.writeBytes(bytes); file
            }
            onMain {
                if (activity.isDestroyed) { result.getOrNull()?.delete(); return@onMain }
                result.onSuccess { pending = it; picker.launch(ref.fileName()) }
                    .onFailure { activity.hint("无法导出：${it.message}") }
            }
        }
    }
    fun share(ref: DocRef) {
        MdApp.ioPool.execute {
            val result = runCatching { store.shareFile(ref) }
            onMain {
                if (activity.isDestroyed) return@onMain
                result.onSuccess { file ->
                    val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/markdown"; putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = android.content.ClipData.newRawUri(ref.fileName(), uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching { activity.startActivity(Intent.createChooser(send, ref.fileName())) }
                        .onFailure { activity.hint("未找到分享应用") }
                }.onFailure { activity.hint("读取失败：${it.message}") }
            }
        }
    }
}
