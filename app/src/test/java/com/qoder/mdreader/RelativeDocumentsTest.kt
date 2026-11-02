package com.qoder.mdreader

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.qoder.mdreader.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RelativeDocumentsTest {
    @Test fun folder_resolution_uses_one_metadata_query_per_component_and_rejects_escape() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = object : ContentProvider() {
            var queries = 0
            override fun onCreate() = true
            override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
                queries++
                val cursor = MatrixCursor(projection!!)
                if (DocumentsContract.getDocumentId(uri) == "primary:Root") {
                    repeat(1000) { cursor.addRow(arrayOf("primary:Root/file$it", "file$it", "text/plain")) }
                    cursor.addRow(arrayOf("primary:Root/images", "images", DocumentsContract.Document.MIME_TYPE_DIR))
                } else cursor.addRow(arrayOf("primary:Root/images/a.png", "a.png", "image/png"))
                return cursor
            }
            override fun getType(uri: Uri) = "text/plain"
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        }
        provider.attachInfo(context, ProviderInfo().apply { authority = "relative.test" })
        ShadowContentResolver.registerProviderInternal("relative.test", provider)
        val source = Uri.parse("content://relative.test/document/primary%3ARoot%2Fnote.md")
        val tree = "content://relative.test/tree/primary%3ARoot"
        val ref = DocRef("S:$source", "note", "md", null, source.toString())
        val target = RelativeDocuments.resolve(context, ref, "images/a.png", tree)!!
        assertEquals("primary:Root/images/a.png", DocumentsContract.getDocumentId(target))
        assertEquals(2, provider.queries)
        assertNull(RelativeDocuments.resolve(context, ref, "../private.md", tree))
        assertEquals(2, provider.queries)
    }
}
