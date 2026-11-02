package com.qoder.mdreader.core

import android.content.Context
import android.content.SharedPreferences
import com.qoder.mdreader.Night

/**
 * 所有用户可改的开关都集中在这里，键名固定，改默认值只需动这一处。
 */
class Prefs(ctx: Context) {

    private val sp: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("md_prefs", Context.MODE_PRIVATE)

    // ---- 外观 ----
    var themeMode: Int
        get() = sp.getInt(K_THEME, Night.AUTO)
        set(v) = sp.edit().putInt(K_THEME, v).apply()

    /** "ink" = 衬线纸墨；"clean" = 无衬线清爽 */
    var fontFamily: String
        get() = sp.getString(K_FACE, "ink") ?: "ink"
        set(v) = sp.edit().putString(K_FACE, v).apply()

    var fontScale: Float
        get() = sp.getFloat(K_SCALE, 1.0f)
        set(v) = sp.edit().putFloat(K_SCALE, v.coerceIn(0.82f, 1.6f)).apply()

    var lineSpacing: Float
        get() = sp.getFloat(K_LINE, 1.85f)
        set(v) = sp.edit().putFloat(K_LINE, v.coerceIn(1.4f, 2.4f)).apply()

    // ---- 阅读 ----
    var showToc: Boolean
        get() = sp.getBoolean(K_TOC, true)
        set(v) = sp.edit().putBoolean(K_TOC, v).apply()

    var showProgress: Boolean
        get() = sp.getBoolean(K_PROGRESS, true)
        set(v) = sp.edit().putBoolean(K_PROGRESS, v).apply()

    var rememberPosition: Boolean
        get() = sp.getBoolean(K_REMEMBER, true)
        set(v) = sp.edit().putBoolean(K_REMEMBER, v).apply()

    var loadNetImage: Boolean
        get() = sp.getBoolean(K_NETIMG, false)
        set(v) = sp.edit().putBoolean(K_NETIMG, v).apply()

    // ---- 编辑 ----
    var autoSave: Boolean
        get() = sp.getBoolean(K_AUTOSAVE, true)
        set(v) = sp.edit().putBoolean(K_AUTOSAVE, v).apply()

    var monoEditor: Boolean
        get() = sp.getBoolean(K_MONO_EDIT, true)
        set(v) = sp.edit().putBoolean(K_MONO_EDIT, v).apply()

    var taskWritesBack: Boolean
        get() = sp.getBoolean(K_TASK_SAVE, true)
        set(v) = sp.edit().putBoolean(K_TASK_SAVE, v).apply()

    /** 单个回车就换行（更贴合中文写作习惯），关掉则遵循标准 Markdown */
    var singleBreakNewline: Boolean
        get() = sp.getBoolean(K_BREAKLINE, true)
        set(v) = sp.edit().putBoolean(K_BREAKLINE, v).apply()

    // ---- 阅读位置：key -> 0..1 百分比 ----
    fun readPos(key: String): Float = sp.getFloat("$K_POS|$key", -1f)

    fun setReadPos(key: String, ratio: Float) {
        sp.edit().putFloat("$K_POS|$key", ratio.coerceIn(0f, 1f)).apply()
    }

    /**
     * 只清外观/阅读/编辑偏好。书架与阅读位置存在另一个 SharedPreferences 文件里，
     * 重置设置不应该把用户的文档列表清空。
     */
    fun resetAll() {
        val e = sp.edit()
        sp.all.keys.filter { !it.startsWith("$K_POS|") }.forEach { e.remove(it) }
        e.apply()
    }

    companion object {
        private const val K_THEME = "theme_mode"
        private const val K_FACE = "font_face"
        private const val K_SCALE = "font_scale"
        private const val K_LINE = "line_spacing"
        private const val K_TOC = "show_toc"
        private const val K_PROGRESS = "show_progress"
        private const val K_REMEMBER = "remember_pos"
        private const val K_NETIMG = "net_image"
        private const val K_AUTOSAVE = "auto_save"
        private const val K_MONO_EDIT = "mono_editor"
        private const val K_TASK_SAVE = "task_write_back"
        private const val K_BREAKLINE = "single_break_newline"
        private const val K_POS = "pos"
    }
}

