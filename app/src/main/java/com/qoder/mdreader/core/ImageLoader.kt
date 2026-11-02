package com.qoder.mdreader.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.qoder.mdreader.MdApp
import com.qoder.mdreader.onMain
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Future

object ImagePolicy {
    const val MAX_BYTES = 12 * 1024 * 1024
    const val MAX_PIXELS = 4_000_000L
    const val MAX_URL_CHARS = 8192
    fun sampleFor(w: Int, h: Int, target: Int): Int {
        require(w > 0 && h > 0)
        var sample = 1
        val width = target.coerceIn(128, 4096)
        while (w / sample > width * 2 || (w.toLong() / sample) * (h.toLong() / sample) > MAX_PIXELS || h / sample > 16384) sample *= 2
        return sample
    }
    fun file(url: String, baseDir: File?): File? {
        val raw = Uri.decode(url.substringBefore('#'))
        val candidate = when {
            raw.startsWith("file://") -> File(Uri.parse(raw).path ?: return null)
            raw.startsWith("/") -> File(raw)
            baseDir != null && !raw.contains(":") -> File(baseDir, raw)
            else -> return null
        }.canonicalFile
        val base = baseDir?.canonicalFile ?: return null
        if (!candidate.path.startsWith(base.path + File.separator)) return null
        return candidate.takeIf { it.isFile }
    }
}

object ImageLoader {
    private val locks = Array(32) { Any() }
    fun load(ctx: Context, rawUrl: String, baseDir: File?, targetPx: Int, allowNetwork: Boolean,
             resolve: ((String) -> String?)? = null,
             cb: (Bitmap?, Boolean) -> Unit): Future<*> {
        if (rawUrl.length > ImagePolicy.MAX_URL_CHARS) {
            onMain { cb(null, false) }
            return java.util.concurrent.CompletableFuture.completedFuture(null)
        }
        val app = ctx.applicationContext
        return MdApp.imagePool.submit {
            val raw = rawUrl.trim()
            val url = if (baseDir == null && !raw.contains(':')) runCatching { resolve?.invoke(raw) }.getOrNull() ?: raw else raw
            val network = url.startsWith("https://", true) || url.startsWith("http://", true)
            if (network && !allowNetwork) { onMain { cb(null, true) }; return@submit }
            val file = runCatching { ImagePolicy.file(url, baseDir) }.getOrNull()
            val key = if (file != null) "${file.path}|${file.lastModified()}|${file.length()}|$targetPx" else "$url|$targetPx"
            var bitmap: Bitmap? = null
            try {
                synchronized(locks[(key.hashCode() and Int.MAX_VALUE) % locks.size]) {
                    if (Thread.currentThread().isInterrupted) return@submit
                    bitmap = ImageCache.get(key)
                    if (bitmap == null) {
                        val bytes = when {
                            network && url.startsWith("https://", true) -> download(url)
                            file != null -> file.inputStream().use { SafeIo.read(it, ImagePolicy.MAX_BYTES) }
                            url.startsWith("content://") -> app.contentResolver.openInputStream(Uri.parse(url))?.use { SafeIo.read(it, ImagePolicy.MAX_BYTES) }
                            else -> null
                        }
                        if (bytes != null && !Thread.currentThread().isInterrupted) {
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                            if (bounds.outWidth > 0 && bounds.outHeight > 0) {
                                val options = BitmapFactory.Options().apply { inSampleSize = ImagePolicy.sampleFor(bounds.outWidth, bounds.outHeight, targetPx) }
                                bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                                bitmap?.let { ImageCache.put(key, it) }
                            }
                        }
                    }
                }
            } catch (_: Exception) { } catch (_: OutOfMemoryError) { ImageCache.clear() }
            if (!Thread.currentThread().isInterrupted) onMain { cb(bitmap, false) }
        }
    }
    private fun download(raw: String): ByteArray? {
        var url = URL(raw)
        repeat(4) {
            if (Thread.currentThread().isInterrupted || url.protocol != "https") return null
            val connection = url.openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", "MarkdownReader/1.1")
                val code = connection.responseCode
                if (code in 300..399) url = URL(url, connection.getHeaderField("Location") ?: return null)
                else {
                    if (code !in 200..299 || connection.contentLengthLong > ImagePolicy.MAX_BYTES) return null
                    return connection.inputStream.use { SafeIo.read(it, ImagePolicy.MAX_BYTES) }
                }
            } finally { connection.disconnect() }
        }
        return null
    }
}
