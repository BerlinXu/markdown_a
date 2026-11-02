package com.qoder.mdreader

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
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
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PageFlowTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private fun await(check: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!check() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("后台操作应在超时前完成", check())
    }
    private fun layout(view: View) {
        view.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 480, 800)
    }
    private fun text(view: View): String = when (view) {
        is TextView -> view.text.toString()
        is ViewGroup -> (0 until view.childCount).joinToString(" ") { text(view.getChildAt(it)) }
        else -> ""
    }
    @Test fun long_reader_recycles_rows_and_can_reach_the_end() {
        val store = DocStore(context)
        val ref = store.importText("页面列表", (1..1000).joinToString("\n") { "- item $it" })
        val controller = Robolectric.buildActivity(ReaderActivity::class.java,
            Intent(context, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_KEY, ref.key)).setup().visible()
        try {
            val activity = controller.get()
            val recycler = activity.findViewById<RecyclerView>(R.id.content)
            await { recycler.adapter?.itemCount == 1000 }
            layout(activity.window.decorView)
            assertTrue("只挂载屏幕附近的行，实际=${recycler.childCount}", recycler.childCount in 1..40)
            recycler.scrollToPosition(999)
            layout(activity.window.decorView)
            layout(recycler)
            assertTrue(text(recycler).contains("item 1000"))
            assertTrue(recycler.childCount < 40)
        } finally { controller.pause().stop().destroy(); store.delete(ref) }
    }
    @Test fun leaving_editor_waits_for_successful_save() {
        val store = DocStore(context)
        val ref = store.importText("保存页面", "old")
        val controller = Robolectric.buildActivity(EditorActivity::class.java,
            Intent(context, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_KEY, ref.key)).setup().visible()
        try {
            val activity = controller.get()
            val editor = activity.findViewById<EditText>(R.id.editor)
            await { editor.text.toString() == "old" }
            editor.setText("new content")
            activity.onBackPressedDispatcher.onBackPressed()
            val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
            assertFalse("保存回调返回前不可结束页面", activity.isFinishing)
            await { activity.isFinishing }
            assertEquals("new content", File(ref.localPath!!).readText())
            assertFalse(store.hasUnsavedDraft(ref.key))
        } finally { controller.pause().stop().destroy(); store.delete(ref) }
    }
    @Test fun failed_save_keeps_editor_and_draft_open() {
        val store = DocStore(context)
        val ref = store.importText("冲突页面", "old")
        val controller = Robolectric.buildActivity(EditorActivity::class.java,
            Intent(context, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_KEY, ref.key)).setup().visible()
        try {
            val activity = controller.get()
            val editor = activity.findViewById<EditText>(R.id.editor)
            await { editor.text.toString() == "old" }
            editor.setText("my draft")
            File(ref.localPath!!).writeText("external change")
            activity.onBackPressedDispatcher.onBackPressed()
            val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
            await { activity.findViewById<TextView>(R.id.save_hint).text.contains("保存失败") }
            assertFalse(activity.isFinishing)
            assertEquals("external change", File(ref.localPath).readText())
            val vm = androidx.lifecycle.ViewModelProvider(activity)[com.qoder.mdreader.ui.EditorViewModel::class.java]
            assertSame(activity.application, vm.getApplication<android.app.Application>())
            assertEquals("my draft", store.draftOf(ref.key))
        } finally { controller.pause().stop().destroy(); store.delete(ref) }
    }
    @Test fun rotating_during_save_preserves_text_and_updates_saved_state() {
        val store = DocStore(context)
        val ref = store.importText("旋转保存", "old")
        val controller = Robolectric.buildActivity(EditorActivity::class.java,
            Intent(context, EditorActivity::class.java).putExtra(EditorActivity.EXTRA_KEY, ref.key)).setup().visible()
        val activity = controller.get()
        val vm = androidx.lifecycle.ViewModelProvider(activity)[com.qoder.mdreader.ui.EditorViewModel::class.java]
        var released = false
        try {
            val editor = activity.findViewById<EditText>(R.id.editor)
            await { editor.text.toString() == "old" }
            kotlinx.coroutines.runBlocking { vm.writes.lock() }
            editor.setText("rotate snapshot")
            activity.findViewById<View>(R.id.fab_save).performClick()
            assertTrue(vm.saving)
            controller.configurationChange(android.content.res.Configuration(activity.resources.configuration).apply {
                orientation = android.content.res.Configuration.ORIENTATION_LANDSCAPE
            })
            val rotated = controller.get()
            assertNotSame(activity, rotated)
            assertEquals("rotate snapshot", rotated.findViewById<EditText>(R.id.editor).text.toString())
            vm.writes.unlock(); released = true
            await { rotated.findViewById<TextView>(R.id.doc_sub).text.contains(rotated.getString(R.string.saved)) }
            assertEquals("rotate snapshot", File(ref.localPath!!).readText())
            assertFalse(store.hasUnsavedDraft(ref.key))
        } finally {
            if (!released && vm.writes.isLocked) vm.writes.unlock()
            controller.pause().stop().destroy(); store.delete(ref)
        }
    }
}
