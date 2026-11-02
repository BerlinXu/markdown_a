package com.qoder.mdreader.core

import android.content.Context
import java.io.File
import java.util.UUID

/** Installs this guide once, including upgrades from the old hiking sample. IO thread only. */
object SampleDocuments {
    private const val VERSION = "computer-ai-1"

    @Synchronized
    fun installIfNeeded(context: Context): Boolean {
        val state = context.getSharedPreferences("md_state", Context.MODE_PRIVATE)
        if (state.getString("sample_bundle", null) == VERSION) return false
        val store = DocStore(context)
        val parent = store.appDir().canonicalFile
        // A fresh private name prevents collision with user images or previous versions.
        val images = File(parent, "paper-ink-guide-${UUID.randomUUID()}")
        check(images.mkdir()) { "无法创建示例配图目录" }
        var document: DocRef? = null
        try {
            val assets = context.assets
            val names = assets.list("sample-images") ?: error("示例配图不存在")
            check(names.isNotEmpty()) { "示例配图为空" }
            names.forEach { name ->
                require(name == File(name).name && name.endsWith(".png"))
                assets.open("sample-images/$name").use { input ->
                    File(images, name).outputStream().use { output -> input.copyTo(output) }
                }
            }
            val text = assets.open("sample.md").bufferedReader(Charsets.UTF_8).use { it.readText() }
                .replace("](sample-images/", "](${images.name}/")
            // importText chooses a free filename; existing and user-edited documents survive.
            document = store.importText("从计算机到人工智能 · 图解长文", text)
            check(state.edit().putString("sample_bundle", VERSION).putBoolean("seeded", true).commit()) {
                "无法保存示例安装状态"
            }
            return true
        } catch (error: Exception) {
            document?.let { store.delete(it) }
            check(images.canonicalFile.parentFile == parent)
            images.deleteRecursively()
            throw error
        }
    }
}
