package com.qoder.mdreader.core

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

object RelativeDocuments {
    fun resolve(ctx: Context, ref: DocRef, target: String, tree: String?): Uri? {
        val path = Uri.decode(target.substringBefore('#'))
        if (path.isBlank() || path.contains(':') || path.startsWith('/')) return null
        if (ref.isLocal) {
            val parent = File(ref.localPath!!).canonicalFile.parentFile ?: return null
            val file = File(parent, path).canonicalFile
            return file.takeIf { it.isFile && it.path.startsWith(parent.path + File.separator) }?.let { Uri.fromFile(it) }
        }
        val rootUri = tree?.let { Uri.parse(it) } ?: return null
        val rootId = DocumentsContract.getTreeDocumentId(rootUri)
        val docId = DocumentsContract.getDocumentId(Uri.parse(ref.uriString))
        if (docId != rootId && !docId.startsWith("$rootId/")) return null
        val relative = docId.removePrefix(rootId).trimStart('/').substringBeforeLast('/', "")
        val parts = ArrayList<String>()
        for (part in (if (relative.isEmpty()) path else "$relative/$path").split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.lastIndex)
                else -> parts += part
            }
        }
        var current = DocumentsContract.buildDocumentUriUsingTree(rootUri, rootId)
        var mime: String? = DocumentsContract.Document.MIME_TYPE_DIR
        for (part in parts) {
            if (mime != DocumentsContract.Document.MIME_TYPE_DIR) return null
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(current, DocumentsContract.getDocumentId(current))
            val found = ctx.contentResolver.query(children, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
                var match: Pair<String, String?>? = null
                while (cursor.moveToNext()) {
                    if (Thread.currentThread().isInterrupted) break
                    if (cursor.getString(1) == part) { match = cursor.getString(0) to cursor.getString(2); break }
                }
                match
            } ?: return null
            current = DocumentsContract.buildDocumentUriUsingTree(rootUri, found.first)
            mime = found.second
        }
        return current.takeIf { mime != DocumentsContract.Document.MIME_TYPE_DIR }
    }
}
