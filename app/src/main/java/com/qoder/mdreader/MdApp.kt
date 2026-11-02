package com.qoder.mdreader

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatDelegate
import com.qoder.mdreader.core.ImageCache
import com.qoder.mdreader.core.Prefs
import java.util.concurrent.Executors

/**
 * 全局入口：夜间模式、图片解码线程池、主线程 Handler。
 */
class MdApp : Application() {

    companion object {
        lateinit var instance: MdApp
            private set

        /** 图片解码用的小线程池，避免同时解码多张大图占内存 */
        val imagePool by lazy { Executors.newFixedThreadPool(2) }

        /** 渲染块时的轻量后台任务（读文件、统计） */
        val ioPool by lazy { Executors.newFixedThreadPool(2) }
        /** Slow cloud summaries cannot delay local editor reads and writes. */
        val previewPool by lazy { Executors.newFixedThreadPool(2) }

        val mainHandler = Handler(Looper.getMainLooper())

        fun dp(ctx: Context, v: Float): Int =
            (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        applyNightMode(Prefs(this).themeMode)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) ImageCache.trim(0.4f)
    }
}

/** 主题模式常量，和设置页的三个选项一一对应 */
object Night {
    const val LIGHT = 0
    const val DARK = 1
    const val AUTO = 2
}

fun applyNightMode(mode: Int) {
    AppCompatDelegate.setDefaultNightMode(
        when (mode) {
            Night.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            Night.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
    )
}

/** 主线程 post，供后台回调复用 */
fun onMain(delayMs: Long = 0, block: () -> Unit) {
    if (delayMs <= 0) MdApp.mainHandler.post { block() }
    else MdApp.mainHandler.postDelayed({ block() }, delayMs)
}
