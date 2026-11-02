package com.qoder.mdreader

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import com.qoder.mdreader.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SampleDocumentsTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val state get() = context.getSharedPreferences("md_state", Context.MODE_PRIVATE)
    private fun imageTargets(text: String) = Regex("!\\[[^]]*]\\(([^)]+)\\)").findAll(text).map { it.groupValues[1] }.toList()

    @Test fun complete_guide_and_all_eight_images_work_offline_after_install() {
        val store = DocStore(context)
        assertTrue(SampleDocuments.installIfNeeded(context))
        val ref = store.listAll().single()
        val text = store.read(ref)
        assertTrue(text.length > 20_000)
        val blocks = MarkdownParser.parse(text)
        assertEquals(8, blocks.filterIsInstance<Block.ImageBlock>().size)
        assertTrue(MarkdownParser.headings(blocks).size > 70)
        assertTrue(ReadingBlocks.prepare(blocks).isNotEmpty())
        for (target in imageTargets(text)) {
            assertFalse(target.contains(":"))
            val file = ImagePolicy.file(target, File(ref.localPath!!).parentFile)
            assertNotNull("配图必须在授权的本地目录中: $target", file)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file!!.path, bounds)
            assertTrue(bounds.outWidth > 0 && bounds.outHeight > 0)
            assertTrue(bounds.outWidth.toLong() * bounds.outHeight <= ImagePolicy.MAX_PIXELS)
        }
    }

    @Test fun upgrade_preserves_existing_edits_and_installs_only_once_even_after_delete() {
        val store = DocStore(context)
        state.edit().putBoolean("seeded", true).commit()
        val old = store.importText("山间笔记", "user changed this document")
        store.saveDraft(old.key, "unsaved user draft")
        val collision = store.importText("从计算机到人工智能 · 图解长文", "my own text")
        assertTrue(SampleDocuments.installIfNeeded(context))
        assertEquals("user changed this document", store.read(old))
        assertEquals("unsaved user draft", store.draftOf(old.key))
        assertEquals("my own text", store.read(collision))
        val added = store.listAll().single { it.key != old.key && it.key != collision.key }
        val before = store.appDir().listFiles()!!.map { it.name }.toSet()
        assertFalse(SampleDocuments.installIfNeeded(context))
        assertEquals(before, store.appDir().listFiles()!!.map { it.name }.toSet())
        assertTrue(store.delete(added))
        assertFalse(SampleDocuments.installIfNeeded(context))
        assertEquals(2, store.listAll().size)
    }

    @Test fun failed_install_rolls_back_files_and_can_retry() {
        val store = DocStore(context)
        val bad = object : ContextWrapper(context) {
            override fun getAssets(): AssetManager = error("simulated unavailable assets")
        }
        assertTrue(runCatching { SampleDocuments.installIfNeeded(bad) }.isFailure)
        assertNull(state.getString("sample_bundle", null))
        assertTrue(store.appDir().listFiles().isNullOrEmpty())
        assertTrue(SampleDocuments.installIfNeeded(context))
        assertEquals(1, store.listAll().size)
    }
}
