package com.example.androidtvfilepicker

import android.database.Cursor
import android.database.MatrixCursor
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
 *
 * The document ID of a normal file is its absolute path.
 * The document ID of a file in a protected folder (Android/data, Android/obb)
 * is "root:" and the absolute path. The provider serves these files only
 * if the user turned on root support.
 * The document ID of an item on a network share is "net:", the ID of the share,
 * ":", and the path on the share. Refer to [NetAccess].
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
        for (share in Shares.list(ctx)) {
            result.newRow().apply {
                add(Root.COLUMN_ROOT_ID, NetAccess.rootId(share.id))
                add(Root.COLUMN_DOCUMENT_ID, NetAccess.documentId(share.id, "/"))
                add(Root.COLUMN_TITLE, share.name)
                add(
                    Root.COLUMN_FLAGS,
                    Root.FLAG_SUPPORTS_IS_CHILD or
                        (if (share.readOnly) 0 else Root.FLAG_SUPPORTS_CREATE)
                )
                add(Root.COLUMN_ICON, R.drawable.ic_network)
            }
        }
        return result
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val ctx = context ?: throw FileNotFoundException("No context")
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        NetAccess.parse(documentId)?.let { target ->
            val share = netCall { NetAccess.share(ctx, target.shareId) }
            val entry = netCall { NetAccess.stat(ctx, target.shareId, target.path) }
            includeNetEntry(result, target, entry, share, target.path == "/")
            return result
        }
        if (isRootId(documentId)) {
            val path = pathOf(documentId)
            val entry = rootCall { RootAccess.stat(ctx, path) }
            // Android/data and Android/obb themselves cannot be renamed.
            val canRename = File(path).parentFile?.let { RootAccess.isRestricted(ctx, it) } == true
            includeRootEntry(result, path, entry, canRename)
            return result
        }
        val file = Storage.resolve(ctx, documentId)
        if (!file.exists()) throw FileNotFoundException("Not found: $documentId")
        includeFile(result, file, Storage.roots(ctx))
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val ctx = context ?: throw FileNotFoundException("No context")
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        val net = NetAccess.parse(parentDocumentId)
        if (net != null) {
            val share = netCall { NetAccess.share(ctx, net.shareId) }
            netCall { NetAccess.list(ctx, net.shareId, net.path) }
                .sortedWith(compareBy<RemoteEntry>({ !it.isDir }, { it.name.lowercase() }))
                .forEach {
                    val target = NetAccess.Target(net.shareId, NetAccess.child(net.path, it.name))
                    includeNetEntry(result, target, it, share, false)
                }
        } else if (isRootId(parentDocumentId)) {
            val parentPath = pathOf(parentDocumentId)
            rootCall { RootAccess.list(ctx, parentPath) }
                .sortedWith(compareBy<RootAccess.Entry>({ !it.isDir }, { it.name.lowercase() }))
                .forEach { includeRootEntry(result, "$parentPath/${it.name}", it, true) }
        } else {
            val parent = Storage.resolve(ctx, parentDocumentId)
            if (!parent.isDirectory) throw FileNotFoundException("Not a folder: $parentDocumentId")
            val roots = Storage.roots(ctx)
            // Protected folders are not in a normal listing.
            parent.listFiles()
                ?.filterNot { RootAccess.isRestricted(roots, it) }
                ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
                ?.forEach { includeFile(result, it, roots) }
        }
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
        NetAccess.parse(documentId)?.let { target ->
            return netCall { NetAccess.open(ctx, target.shareId, target.path, mode) }
        }
        if (isRootId(documentId)) {
            return rootCall { RootAccess.open(ctx, pathOf(documentId), mode) }
        }
        val file = Storage.resolve(ctx, documentId)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String {
        val ctx = context ?: throw FileNotFoundException("No context")
        NetAccess.parse(parentDocumentId)?.let { target ->
            val name = netCall {
                NetAccess.create(
                    ctx,
                    target.shareId,
                    target.path,
                    displayName,
                    mimeType == Document.MIME_TYPE_DIR
                )
            }
            notifyChildren(parentDocumentId)
            return NetAccess.documentId(target.shareId, NetAccess.child(target.path, name))
        }
        if (isRootId(parentDocumentId)) {
            val parentPath = pathOf(parentDocumentId)
            val name = rootCall {
                RootAccess.create(ctx, parentPath, displayName, mimeType == Document.MIME_TYPE_DIR)
            }
            notifyChildren(parentDocumentId)
            return RootAccess.ID_PREFIX + parentPath + "/" + name
        }
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
        NetAccess.parse(documentId)?.let { target ->
            netCall { NetAccess.delete(ctx, target.shareId, target.path) }
            notifyChildren(NetAccess.documentId(target.shareId, NetAccess.parentPath(target.path)))
            return
        }
        if (isRootId(documentId)) {
            throw FileNotFoundException("Delete is not supported in protected folders")
        }
        val file = Storage.resolve(ctx, documentId)
        if (Storage.isRoot(ctx, file)) throw FileNotFoundException("Cannot delete a storage root")
        if (!file.deleteRecursively()) throw FileNotFoundException("Cannot delete: $documentId")
        file.parentFile?.let { notifyChildren(it.absolutePath) }
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val ctx = context ?: throw FileNotFoundException("No context")
        NetAccess.parse(documentId)?.let { target ->
            val newName = netCall { NetAccess.rename(ctx, target.shareId, target.path, displayName) }
            val parent = NetAccess.parentPath(target.path)
            notifyChildren(NetAccess.documentId(target.shareId, parent))
            return NetAccess.documentId(target.shareId, NetAccess.child(parent, newName))
        }
        if (isRootId(documentId)) {
            val path = pathOf(documentId)
            val newName = rootCall { RootAccess.rename(ctx, path, displayName) }
            val parentPath = path.substringBeforeLast('/')
            notifyChildren(RootAccess.ID_PREFIX + parentPath)
            return RootAccess.ID_PREFIX + parentPath + "/" + newName
        }
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

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        // A grant for a share never includes another share, local storage, or a protected folder.
        val parentNet = NetAccess.parse(parentDocumentId)
        val childNet = NetAccess.parse(documentId)
        if (parentNet != null || childNet != null) {
            return parentNet != null && childNet != null &&
                parentNet.shareId == childNet.shareId &&
                NetAccess.isInside(parentNet.path, childNet.path)
        }
        val parentIsRoot = isRootId(parentDocumentId)
        // A normal grant never includes a protected document, and the reverse.
        if (parentIsRoot != isRootId(documentId)) return false
        val parent = pathOf(parentDocumentId)
        val child = pathOf(documentId)
        if (parentIsRoot) {
            return RootAccess.isCleanPath(parent) && RootAccess.isCleanPath(child) &&
                child.startsWith("$parent/")
        }
        return Storage.isDescendant(File(parent), File(child))
    }

    private fun isRootId(documentId: String): Boolean = documentId.startsWith(RootAccess.ID_PREFIX)

    private fun pathOf(documentId: String): String = documentId.removePrefix(RootAccess.ID_PREFIX)

    private inline fun <T> rootCall(block: () -> T): T =
        try {
            block()
        } catch (e: FileNotFoundException) {
            throw e
        } catch (e: IOException) {
            throw FileNotFoundException(e.message ?: "Root access failed")
        }

    private inline fun <T> netCall(block: () -> T): T =
        try {
            block()
        } catch (e: FileNotFoundException) {
            throw e
        } catch (e: IOException) {
            throw FileNotFoundException(e.message ?: "Network error")
        }

    private fun notifyChildren(parentDocumentId: String) {
        val ctx = context ?: return
        ctx.contentResolver.notifyChange(
            DocumentsContract.buildChildDocumentsUri(Storage.authority(ctx), parentDocumentId),
            null
        )
    }

    private fun includeNetEntry(
        cursor: MatrixCursor,
        target: NetAccess.Target,
        entry: RemoteEntry,
        share: NetShare,
        isShareRoot: Boolean
    ) {
        var flags = 0
        if (!share.readOnly) {
            flags = if (entry.isDir) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE
            if (target.path != "/") {
                flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
            }
        }
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, NetAccess.documentId(target.shareId, target.path))
            add(Document.COLUMN_DISPLAY_NAME, if (isShareRoot) share.name else entry.name)
            add(Document.COLUMN_SIZE, if (entry.isDir) null else entry.size)
            add(
                Document.COLUMN_MIME_TYPE,
                if (entry.isDir) Document.MIME_TYPE_DIR else Storage.mimeOfName(entry.name)
            )
            add(Document.COLUMN_LAST_MODIFIED, entry.modified)
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    private fun includeRootEntry(
        cursor: MatrixCursor,
        path: String,
        entry: RootAccess.Entry,
        canRename: Boolean
    ) {
        var flags = if (entry.isDir) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE
        if (canRename) flags = flags or Document.FLAG_SUPPORTS_RENAME
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, RootAccess.ID_PREFIX + path)
            add(Document.COLUMN_DISPLAY_NAME, entry.name)
            add(Document.COLUMN_SIZE, if (entry.isDir) null else entry.size)
            add(
                Document.COLUMN_MIME_TYPE,
                if (entry.isDir) Document.MIME_TYPE_DIR else Storage.mimeOfName(entry.name)
            )
            add(Document.COLUMN_LAST_MODIFIED, entry.modified)
            add(Document.COLUMN_FLAGS, flags)
        }
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
