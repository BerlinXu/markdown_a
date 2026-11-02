package com.qoder.mdreader.ui

import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.qoder.mdreader.R

/** One idle timer per landscape page; never intercepts document/editor gestures. */
internal class LandscapeChrome private constructor(
    private val activity: AppCompatActivity,
    private val root: FrameLayout,
    private val bars: List<View>
) : DefaultLifecycleObserver {
    private val handler = Handler(Looper.getMainLooper())
    private val controller = WindowCompat.getInsetsController(activity.window, root)
    private val initialVisibility = bars.map { it.visibility }
    private var expanded = false
    private var resumed = false
    private var focused = true
    private var touching = false
    private val hide = Runnable { setExpanded(false) }
    private fun dp(value: Int) = (value * root.resources.displayMetrics.density + .5f).toInt()
    private val toggle = ImageButton(activity).apply {
        id = R.id.landscape_chrome_toggle
        setImageResource(R.drawable.ic_chrome_chevron)
        imageTintList = ContextCompat.getColorStateList(activity, R.color.ink2)
        setBackgroundResource(R.drawable.bg_card)
        setPadding(dp(30), dp(6), dp(30), dp(6))
        // Do not take keyboard focus away from the editor or summon/dismiss the IME.
        isFocusable = false
        elevation = dp(2).toFloat()
        setOnClickListener { setExpanded(!expanded) }
    }

    init {
        if (Build.VERSION.SDK_INT >= 28) {
            activity.window.attributes = activity.window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        root.addView(toggle, FrameLayout.LayoutParams(dp(80), dp(28), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(2)
        })
        setExpanded(false)
        activity.lifecycle.addObserver(this)
    }

    private fun setExpanded(show: Boolean) {
        expanded = show
        bars.forEachIndexed { index, view -> view.visibility = if (show) initialVisibility[index] else View.GONE }
        toggle.rotation = if (show) 180f else 0f
        toggle.contentDescription = activity.getString(if (show) R.string.landscape_hide_controls else R.string.landscape_show_controls)
        toggle.tooltipText = toggle.contentDescription
        applySystemBars()
        scheduleHide()
    }

    private fun applySystemBars() {
        // IME visibility is independent: hiding page controls must never hide the keyboard.
        if (expanded) controller.show(WindowInsetsCompat.Type.systemBars())
        else controller.hide(WindowInsetsCompat.Type.systemBars())
        ViewCompat.requestApplyInsets(root)
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hide)
        if (expanded && resumed && focused && !touching) handler.postDelayed(hide, IDLE_MILLIS)
    }

    fun onTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { touching = true; handler.removeCallbacks(hide) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { touching = false; scheduleHide() }
        }
    }

    fun onWindowFocusChanged(hasFocus: Boolean) {
        focused = hasFocus
        if (hasFocus && resumed) applySystemBars()
        // Popup menus and dialogs own another window; do not collapse the page behind them.
        scheduleHide()
    }

    override fun onResume(owner: LifecycleOwner) { resumed = true; applySystemBars(); scheduleHide() }
    override fun onPause(owner: LifecycleOwner) { resumed = false; touching = false; handler.removeCallbacks(hide) }
    override fun onDestroy(owner: LifecycleOwner) { handler.removeCallbacksAndMessages(null); owner.lifecycle.removeObserver(this) }

    companion object {
        const val IDLE_MILLIS = 6_000L
        fun install(activity: AppCompatActivity, root: FrameLayout, bars: List<View>): LandscapeChrome? =
            if (activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                LandscapeChrome(activity, root, bars)
            else null
    }
}
