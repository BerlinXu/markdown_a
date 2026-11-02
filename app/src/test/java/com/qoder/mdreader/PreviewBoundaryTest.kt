package com.qoder.mdreader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.qoder.mdreader.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreviewBoundaryTest {
    @Test fun actual_character_boundaries_cross_the_4096_byte_cut() {
        val store = DocStore(ApplicationProvider.getApplicationContext<Context>())
        val reader = DocStore::class.java.getDeclaredMethod("readHead", DocRef::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
        val samples = HashSet<Int>()
        for (pad in 0 until 8) {
            val ref = store.importText("边界", "# 标题\n\n" + "a".repeat(pad) + "中文四字".repeat(1000))
            try {
                val head = reader.invoke(store, ref, 4096) as ByteArray
                samples += head.size
                val decoded = TextCodec.decode(head).first
                assertTrue(decoded.startsWith("# 标题"))
                assertNotNull("decoded prefix: ${decoded.take(30)}, head=${head.size}", store.preview(ref))
            } finally { store.delete(ref) }
        }
        assertTrue("截断点应覆盖不同字符边界", samples.size >= 3)
    }
}
