/**
 * Responsible for: writing a download into a folder the user picked (SAF): name.part while writing, rename when complete, resume from the .part length.
 * Not responsible for: HTTP (Downloader.kt), MediaStore (MediaStoreSink.kt) or starting a download (FileDownload.kt).
 */
package com.example.yicam

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns

class TreeDownloadTarget(
    private val context: Context,
    private val treeUri: Uri,
) : DownloadTarget {
    private val resolver = context.contentResolver
    private val etags = context.getSharedPreferences(MediaStoreTarget.ETAG_PREFS, Context.MODE_PRIVATE)

    override fun availableBytes(): Long = Long.MAX_VALUE

    override fun existingComplete(file: FileEntry): String? {
        val found = child(file.name) ?: return null
        return if (found.size == file.sizeBytes && file.sizeBytes > 0L) found.uri.toString() else null
    }

    override fun open(file: FileEntry): DownloadSink {
        val partName = file.name + MediaStoreTarget.PART_SUFFIX
        val existing = child(partName)
        val uri = existing?.uri ?: DocumentsContract.createDocument(
            resolver,
            parentDocumentUri(),
            file.mimeType(),
            partName,
        ) ?: throw IllegalStateException("could not create $partName")
        val already = existing?.size ?: querySize(uri)
        val key = key(file)
        return StreamSink(
            length = already,
            tag = etags.getString(key, null),
            openOut = { append -> resolver.openOutputStream(uri, if (append && already > 0L) "wa" else "wt")!! },
            onEtag = { etags.edit().putString(key, it).apply() },
            onComplete = {
                val renamed = DocumentsContract.renameDocument(resolver, uri, file.name) ?: uri
                renamed.toString()
            },
        )
    }

    override fun removeComplete(file: FileEntry) {
        child(file.name)?.let { DocumentsContract.deleteDocument(resolver, it.uri) }
    }

    private fun parentDocumentUri(): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    private fun child(name: String): Row? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        resolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            while (cursor.moveToNext()) {
                if (cursor.getString(nameCol) != name) continue
                val id = cursor.getString(idCol)
                val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                return Row(DocumentsContract.buildDocumentUriUsingTree(treeUri, id), size)
            }
        }
        return null
    }

    private fun querySize(uri: Uri): Long {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        return 0L
    }

    private fun key(file: FileEntry): String = "$treeUri/${file.folder}/${file.name}"

    private data class Row(val uri: Uri, val size: Long)
}
