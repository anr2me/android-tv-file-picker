package com.example.androidtvfilepicker

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * A document provider for the storage volumes of the device.
 * The document ID of each file is its absolute path.
 */
class StorageDocumentsProvider : DocumentsProvider() {

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val ctx = context ?: return result
        for (root in Storage.roots(ctx)) {
            result.newRow().apply {
                add(Root.COLUMN_ROOT_ID, root.id)
                add(Root.COLUMN_DOCUMENT_ID, root.id)
                add(Root.COLUMN_TITLE, root.title)
                add(
                    Root.COLUMN_FLAGS,
                    Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD
                )
                add(Root.COLUMN_ICON, R.drawable.ic_storage)
                add(Root.COLUMN_AVAILABLE_BYTES, root.dir.freeSpace)
            }
        }
        return result
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val ctx = context ?: throw FileNotFoundException("No context")
        val file = Storage.resolve(ctx, documentId)
        if (!file.exists()) throw FileNotFoundException("Not found: $documentId")
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        includeFile(result, file, Storage.roots(ctx))
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val ctx = context ?: throw FileNotFoundException("No context")
        val parent = Storage.resolve(ctx, parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException("Not a folder: $parentDocumentId")
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val roots = Storage.roots(ctx)
        parent.listFiles()
            ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            ?.forEach { includeFile(result, it, roots) }
        result.setNotificationUri(
            ctx.contentResolver,
            DocumentsContract.buildChildDocumentsUri(Storage.authority(ctx), parentDocumentId)
        )
        return result
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException("No context")
        val file = Storage.resolve(ctx, documentId)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String {
        val ctx = context ?: throw FileNotFoundException("No context")
        val parent = Storage.resolve(ctx, parentDocumentId)
        if (!parent.isDirectory || !parent.canWrite()) {
            throw FileNotFoundException("Cannot write to: $parentDocumentId")
        }
        val target = Storage.uniqueFile(parent, displayName)
        val created = try {
            if (mimeType == Document.MIME_TYPE_DIR) target.mkdir() else target.createNewFile()
        } catch (e: IOException) {
            false
        }
        if (!created) throw FileNotFoundException("Cannot create: ${target.name}")
        notifyChildren(parentDocumentId)
        return target.absolutePath
    }

    override fun deleteDocument(documentId: String) {
        val ctx = context ?: throw FileNotFoundException("No context")
        val file = Storage.resolve(ctx, documentId)
        if (Storage.isRoot(ctx, file)) throw FileNotFoundException("Cannot delete a storage root")
        if (!file.deleteRecursively()) throw FileNotFoundException("Cannot delete: $documentId")
        file.parentFile?.let { notifyChildren(it.absolutePath) }
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val ctx = context ?: throw FileNotFoundException("No context")
        val file = Storage.resolve(ctx, documentId)
        if (Storage.isRoot(ctx, file)) throw FileNotFoundException("Cannot rename a storage root")
        val parent = file.parentFile ?: throw FileNotFoundException("No parent folder")
        val target = File(parent, Storage.sanitizeName(displayName))
        if (target.exists() || !file.renameTo(target)) {
            throw FileNotFoundException("Cannot rename: $documentId")
        }
        notifyChildren(parent.absolutePath)
        return target.absolutePath
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        Storage.isDescendant(File(parentDocumentId), File(documentId))

    private fun notifyChildren(parentDocumentId: String) {
        val ctx = context ?: return
        ctx.contentResolver.notifyChange(
            DocumentsContract.buildChildDocumentsUri(Storage.authority(ctx), parentDocumentId),
            null
        )
    }

    private fun includeFile(cursor: MatrixCursor, file: File, roots: List<Storage.Root>) {
        val isDir = file.isDirectory
        val rootTitle = roots.firstOrNull { it.dir.absolutePath == file.absolutePath }?.title
        var flags = 0
        if (file.canWrite()) {
            flags = if (isDir) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE
            if (rootTitle == null) {
                flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
            }
        }
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, file.absolutePath)
            add(Document.COLUMN_DISPLAY_NAME, rootTitle ?: file.name)
            add(Document.COLUMN_SIZE, if (isDir) null else file.length())
            add(Document.COLUMN_MIME_TYPE, Storage.mimeOf(file))
            add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    companion object {
        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_AVAILABLE_BYTES
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE
        )
    }
}
