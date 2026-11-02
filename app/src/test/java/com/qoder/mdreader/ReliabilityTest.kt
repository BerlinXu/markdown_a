package com.qoder.mdreader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.qoder.mdreader.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReliabilityTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun encoding_bom_and_newlines_survive_new_store_instance() {
        for (format in listOf(TextFormat("UTF-8", true, "\r\n"), TextFormat("GB18030", false, "\r\n"), TextFormat("UTF-16LE", true, "\r"))) {
            val store = DocStore(ctx)
            val ref = store.createBlank("编码")
            val original = format.encode("标题\n正文\n")
            File(ref.localPath!!).writeBytes(original)
            assertEquals("标题\n正文\n", store.read(ref))
            DocStore(ctx).write(ref, "标题\n改动\n")
            assertArrayEquals(format.encode("标题\n改动\n"), File(ref.localPath).readBytes())
            store.delete(ref)
        }
    }
    @Test fun conflicting_editor_does_not_overwrite_external_change() {
        val store = DocStore(ctx)
        val ref = store.importText("冲突", "原文")
        store.read(ref)
        File(ref.localPath!!).writeText("外部新内容")
        assertTrue(runCatching { store.write(ref, "旧编辑") }.isFailure)
        assertEquals("外部新内容", File(ref.localPath).readText())
        store.delete(ref)
    }
    @Test fun independent_concurrent_writers_cannot_silently_lose_edits() {
        val store = DocStore(ctx)
        val a = store.importText("并发", "base")
        val b = a.copy()
        store.read(a); store.read(b)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val writes = listOf(a to "a", b to "b").map { (ref, text) -> pool.submit<Boolean> {
                start.await(); runCatching { DocStore(ctx).write(ref, text) }.isSuccess
            } }
            start.countDown()
            assertEquals(1, writes.count { it.get() })
            assertTrue(File(a.localPath!!).readText() in listOf("a", "b"))
        } finally { pool.shutdownNow(); store.delete(a) }
    }
    @Test fun newer_draft_is_not_cleared_by_older_save() {
        val store = DocStore(ctx)
        val ref = store.importText("草稿", "disk")
        store.saveDraft(ref.key, "new")
        store.clearDraftIfMatches(ref.key, "old")
        assertEquals("new", DocStore(ctx).draftOf(ref.key))
        store.clearDraftIfMatches(ref.key, "new")
        assertFalse(store.hasUnsavedDraft(ref.key))
        store.delete(ref)
    }
    @Test fun an_older_save_snapshot_cannot_replace_a_newer_draft() {
        val store = DocStore(ctx)
        val ref = store.importText("草稿顺序", "disk")
        val older = DocStore.nextDraftRevision()
        val newer = DocStore.nextDraftRevision()
        store.saveDraft(ref.key, "new draft", newer)
        store.saveDraft(ref.key, "old snapshot", older)
        assertEquals("new draft", store.draftOf(ref.key))
        store.write(ref, "old snapshot")
        store.clearDraftIfMatches(ref.key, "old snapshot")
        assertEquals("new draft", store.draftOf(ref.key))
        store.delete(ref)
    }
    @Test fun recovery_preserves_previous_bytes() {
        val store = DocStore(ctx)
        val ref = store.importText("恢复", "one")
        store.read(ref); store.write(ref, "two")
        assertEquals("one", store.recoveryOf(ref.key)!!.readText())
        store.delete(ref)
    }
    @Test fun recent_sort_and_extension_rename_are_preserved() {
        val store = DocStore(ctx)
        val a = store.importText("一", "one")
        val b = store.importText("二", "two")
        store.markOpened(a); store.markOpened(b)
        assertEquals(b.key, store.sortedShelf().first().key)
        val file = File(store.appDir(), "保留.txt").apply { writeText("text") }
        val ref = store.find(DocRef.PREFIX_LOCAL + file.name)!!
        store.saveDraft(ref.key, "pending")
        ReadingState(ctx).setPosition(ref.key, ReadingAnchor(20, -10))
        ReadingState(ctx).add(ref.key, ReadingAnchor(20, label = "mark"))
        val renamed = store.rename(ref, "改名")!!
        assertEquals("txt", renamed.ext)
        assertTrue(File(renamed.localPath!!).exists())
        assertEquals("pending", store.draftOf(renamed.key))
        assertFalse(store.hasUnsavedDraft(ref.key))
        assertEquals(20, ReadingState(ctx).position(renamed.key)!!.line)
        assertEquals("mark", ReadingState(ctx).bookmarks(renamed.key).single().label)
        store.delete(a); store.delete(b); store.delete(renamed)
    }
    @Test fun stream_limit_stops_unknown_length_inputs() {
        assertTrue(runCatching { SafeIo.read(ByteArrayInputStream(ByteArray(10000)), 4096) }.isFailure)
        assertEquals(100, SafeIo.read(ByteArrayInputStream(ByteArray(100)), 4096).size)
    }
    @Test fun panorama_and_extreme_pixel_count_are_sampled() {
        assertTrue(ImagePolicy.sampleFor(30000, 300, 600) > 1)
        val sample = ImagePolicy.sampleFor(30000, 30000, 1000)
        assertTrue(30000L / sample * (30000L / sample) <= ImagePolicy.MAX_PIXELS)
    }
    @Test fun oversized_image_urls_are_rejected_before_network_work() {
        var rejected = false
        val request = ImageLoader.load(ctx, "https://" + "x".repeat(20000), null, 600, true) { bitmap, skipped ->
            rejected = bitmap == null && !skipped
        }
        assertTrue(request.isDone)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(rejected)
    }
    @Test fun image_paths_cannot_escape_document_directory() {
        val base = File(ctx.cacheDir, "image-scope").apply { mkdirs() }
        File(base, "a.png").writeText("a")
        assertNotNull(ImagePolicy.file("a.png", base))
        assertNull(ImagePolicy.file("../secret.png", base))
    }
    @Test fun incoming_file_uri_cannot_read_private_app_files() {
        val private = File(ctx.filesDir, "private-note.md").apply { writeText("private") }
        try { assertTrue(runCatching { DocStore(ctx).adopt(android.net.Uri.fromFile(private)) }.isFailure) }
        finally { private.delete() }
    }
    @Test fun reset_preferences_keeps_reading_position() {
        val prefs = Prefs(ctx)
        prefs.setReadPos("test", .5f); prefs.fontScale = 1.3f
        prefs.resetAll()
        assertEquals(.5f, prefs.readPos("test"), .001f)
        assertEquals(1f, prefs.fontScale, .001f)
    }
    @Test fun font_scaling_is_applied_once_and_uses_sp() {
        val prefs = Prefs(ctx); prefs.fontScale = 1f
        val theme = DocTheme(ctx)
        val base = theme.sp(prefs, theme.bodySp(prefs))
        prefs.fontScale = 1.3f
        assertEquals(base * 1.3f, theme.sp(prefs, theme.bodySp(prefs)), .01f)
    }
}
