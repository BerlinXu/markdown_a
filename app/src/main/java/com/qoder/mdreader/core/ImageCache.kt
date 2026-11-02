package com.qoder.mdreader.core

import android.graphics.Bitmap
import android.util.LruCache

/** 位图内存缓存，按堆大小自动定容 */
object ImageCache {
    private val maxMem = (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceIn(6 * 1024, 24 * 1024)

    private val cache = object : LruCache<String, Bitmap>(maxMem) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun get(key: String): Bitmap? = cache.get(key)
    fun put(key: String, bmp: Bitmap) { if (!bmp.isRecycled) cache.put(key, bmp) }
    fun trim(factor: Float) { cache.trimToSize((maxMem * factor).toInt()) }
    fun clear() = cache.evictAll()
}
