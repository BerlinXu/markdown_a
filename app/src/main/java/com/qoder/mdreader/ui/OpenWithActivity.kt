package com.qoder.mdreader.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.qoder.mdreader.core.DocStore
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*

/**
 * 系统"打开方式/发送给"的中转页：拿到 uri 后立刻转交阅读页，自己不显示任何界面。
 */
class OpenWithActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
            }
            else -> null
        }
        lifecycleScope.launch {
        if (uri != null && uri.scheme != "content") { hint("请使用系统文件选择器打开此文件"); finish(); return@launch }
        if (uri == null) {
            // 别处"分享纯文本"过来：直接落成一篇新笔记，比丢弃更有用
            val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            if (!text.isNullOrBlank()) {
                val ref = withContext(Dispatchers.IO) { runCatching { DocStore(applicationContext).importText("收到文本", text) } }.getOrElse {
                    hint("导入失败：${it.message}"); finish(); return@launch
                }
                startActivity(
                    Intent(this@OpenWithActivity, ReaderActivity::class.java)
                        .putExtra(ReaderActivity.EXTRA_KEY, ref.key)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            finish()
            return@launch
        }

        val store = DocStore(applicationContext)
        val ref = withContext(Dispatchers.IO) { runCatching { store.adopt(uri) } }.getOrElse {
            hint("打开失败：${it.message}"); finish(); return@launch
        }
        val next = Intent(this@OpenWithActivity, ReaderActivity::class.java)
        if (ref != null) next.putExtra(ReaderActivity.EXTRA_KEY, ref.key)
        else next.putExtra(ReaderActivity.EXTRA_URI, uri)
        next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        next.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(next)
        finish()
        }
    }
}
