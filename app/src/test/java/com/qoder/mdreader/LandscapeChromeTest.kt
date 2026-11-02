package com.qoder.mdreader

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import com.qoder.mdreader.core.DocStore
import com.qoder.mdreader.ui.EditorActivity
import com.qoder.mdreader.ui.ReaderActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "land")
class LandscapeChromeTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun <T : AppCompatActivity> page(type: Class<T>, check: (ActivityController<T>) -> Unit) {
        val store = DocStore(context)
        val ref = store.importText("横屏检查", "original text")
        val controller = Robolectric.buildActivity(type,
            Intent(context, type).putExtra(ReaderActivity.EXTRA_KEY, ref.key)).setup().visible()
        try { check(controller) }
        finally { controller.pause().stop().destroy(); store.delete(ref) }
    }
    private fun waitForText(editor: EditText) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (editor.text.toString() != "original text" && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10)
        }
        assertEquals("original text", editor.text.toString())
    }
    private fun advance(ms: Long) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)) }
    private fun layout(activity: AppCompatActivity) {
        activity.window.decorView.apply {
            measure(View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY))
            layout(0, 0, 900, 480)
        }
    }
    private fun touch(activity: AppCompatActivity, action: Int) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, 120f, 200f, 0)
        try { activity.dispatchTouchEvent(event) } finally { event.recycle() }
    }

    @Test fun reader_reclaims_header_height_and_toggles_from_the_top_handle() = page(ReaderActivity::class.java) { c ->
        val a = c.get()
        val bar = a.findViewById<View>(R.id.top_bar)
        val toggle = a.findViewById<View>(R.id.landscape_chrome_toggle)
        assertEquals(View.GONE, bar.visibility)
        assertEquals(View.GONE, a.findViewById<View>(R.id.top_divider).visibility)
        layout(a)
        val fullHeight = a.findViewById<View>(R.id.content).height
        toggle.performClick(); layout(a)
        assertEquals(View.VISIBLE, bar.visibility)
        assertTrue("展开标题栏才占用阅读高度", a.findViewById<View>(R.id.content).height < fullHeight)
        toggle.performClick(); layout(a)
        assertEquals(View.GONE, bar.visibility)
        assertEquals(fullHeight, a.findViewById<View>(R.id.content).height)
        toggle.performClick()
        advance(6_001)
        assertEquals(View.GONE, bar.visibility)
    }

    @Test fun idle_timer_waits_for_touch_and_popup_focus_to_finish() = page(ReaderActivity::class.java) { c ->
        val a = c.get()
        val bar = a.findViewById<View>(R.id.top_bar)
        a.findViewById<View>(R.id.landscape_chrome_toggle).performClick()
        advance(5_000)
        touch(a, MotionEvent.ACTION_DOWN)
        advance(10_000)
        assertEquals(View.VISIBLE, bar.visibility)
        touch(a, MotionEvent.ACTION_UP)
        advance(5_000)
        assertEquals(View.VISIBLE, bar.visibility)
        a.onWindowFocusChanged(false)
        advance(10_000)
        assertEquals(View.VISIBLE, bar.visibility)
        a.onWindowFocusChanged(true)
        advance(5_999)
        assertEquals(View.VISIBLE, bar.visibility)
        advance(2)
        assertEquals(View.GONE, bar.visibility)
    }

    @Test fun editor_keeps_focus_text_and_keyboard_space_when_controls_collapse() = page(EditorActivity::class.java) { c ->
        val a = c.get()
        val editor = a.findViewById<EditText>(R.id.editor)
        waitForText(editor)
        layout(a)
        assertTrue("横屏编辑栏应获得实际宽高", editor.width > 0 && editor.height > 0)
        val preview = a.findViewById<View>(R.id.preview_wrap)
        assertTrue("横屏预览栏应获得实际宽高", preview.width > 0 && preview.height > 0)
        assertTrue(editor.requestFocus()); editor.setSelection(4)
        val root = a.findViewById<View>(R.id.editor_root)
        val hiddenBars = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.systemBars(), Insets.NONE)
            .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(30, 0, 0, 0))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 160)).build()
        ViewCompat.dispatchApplyWindowInsets(root, hiddenBars)
        assertEquals(0, root.paddingTop)
        assertEquals(30, root.paddingLeft)
        assertEquals(160, root.paddingBottom)
        assertEquals(View.GONE, a.findViewById<View>(R.id.tools_scroll).visibility)
        a.findViewById<View>(R.id.landscape_chrome_toggle).performClick()
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.tools_scroll).visibility)
        a.findViewById<View>(R.id.landscape_chrome_toggle).performClick()
        assertEquals(View.GONE, a.findViewById<View>(R.id.top_bar).visibility)
        assertTrue(editor.hasFocus())
        assertEquals(4, editor.selectionStart)
        assertEquals("original text", editor.text.toString())
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.preview_wrap).visibility)
        assertEquals(View.VISIBLE, a.findViewById<View>(R.id.fab_save).visibility)
    }

    @Test @Config(qualifiers = "port")
    fun rotation_restores_portrait_controls_and_keeps_editor_text() = page(EditorActivity::class.java) { c ->
        val first = c.get()
        waitForText(first.findViewById(R.id.editor))
        first.findViewById<EditText>(R.id.editor).setText("retained edit")
        assertNull(first.findViewById<View>(R.id.landscape_chrome_toggle))
        c.configurationChange(Configuration(first.resources.configuration).apply { orientation = Configuration.ORIENTATION_LANDSCAPE })
        val landscape = c.get()
        assertEquals(View.GONE, landscape.findViewById<View>(R.id.top_bar).visibility)
        landscape.findViewById<View>(R.id.landscape_chrome_toggle).performClick()
        c.configurationChange(Configuration(landscape.resources.configuration).apply { orientation = Configuration.ORIENTATION_PORTRAIT })
        val portrait = c.get()
        assertNull(portrait.findViewById<View>(R.id.landscape_chrome_toggle))
        assertEquals(View.VISIBLE, portrait.findViewById<View>(R.id.top_bar).visibility)
        assertEquals(View.VISIBLE, portrait.findViewById<View>(R.id.tools_scroll).visibility)
        advance(6_001)
        assertEquals(View.VISIBLE, portrait.findViewById<View>(R.id.top_bar).visibility)
        assertEquals("retained edit", portrait.findViewById<EditText>(R.id.editor).text.toString())
    }
}
