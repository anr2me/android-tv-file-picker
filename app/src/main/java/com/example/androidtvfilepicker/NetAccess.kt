package com.example.androidtvfilepicker

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Access to the network shares (SMB and WebDAV).
 *
 * The document ID of an item on a share is "net:", the ID of the share, ":", and the path
 * in the share. The path starts with "/". The root of a share has the path "/".
 * A grant for a share never includes another share, local storage, or a protected folder.
 */
object NetAccess {

    const val ID_PREFIX = "net:"

    /** The place of the virtual files that stand for network locations in the picker. */
    private const val VIRTUAL_ROOT = "/.network-shares"
    private const val BLOCK_SIZE = 1024 * 1024
    private val MODES = setOf("r", "w", "wt", "wa", "rw", "rwt")
    private val INVALID_NAME = Regex("[\\\\/:*?\"<>|\u0000]")

    /** The share and the path of a document. */
    data class Target(val shareId: String, val path: String)

    // ---- Document IDs and paths ----

    fun rootId(shareId: String): String = ID_PREFIX + shareId

    fun documentId(shareId: String, path: String): String = "$ID_PREFIX$shareId:$path"

    /** Returns null if the ID is not an ID of a network document, or the path is not valid. */
    fun parse(documentId: String): Target? {
        if (!documentId.startsWith(ID_PREFIX)) return null
        val rest = documentId.substring(ID_PREFIX.length)
        val colon = rest.indexOf(':')
        if (colon <= 0) return null
        val path = rest.substring(colon + 1)
        if (!isCleanPath(path)) return null
        return Target(rest.substring(0, colon), path)
    }

    /** True for "/" and for an absolute path with no empty, ".", "..", or backslash part. */
    fun isCleanPath(path: String): Boolean {
        if (path == "/") return true
        return path.startsWith("/") && !path.contains('\u0000') && !path.contains('\\') &&
            path.split('/').drop(1).none { it.isEmpty() || it == "." || it == ".." }
    }

    fun child(parentPath: String, name: String): String =
        if (parentPath == "/") "/$name" else "$parentPath/$name"

    fun parentPath(path: String): String = path.substringBeforeLast('/').ifEmpty { "/" }

    /** True if [childPath] is a descendant of [parentPath] on the same share. */
    fun isInside(parentPath: String, childPath: String): Boolean {
        if (!isCleanPath(parentPath) || !isCleanPath(childPath) || parentPath == childPath) return false
        val base = if (parentPath == "/") "/" else "$parentPath/"
        return childPath.startsWith(base)
    }

    private fun cleanName(name: String): String {
        val clean = name.replace(INVALID_NAME, "_").trim()
        return if (clean.isEmpty() || clean == "." || clean == "..") "untitled" else clean
    }

    private fun uniqueName(taken: Set<String>, name: String): String {
        val lower = taken.map { it.lowercase() }.toSet()
        if (name.lowercase() !in lower) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var index = 1
        while ("$base ($index)$extension".lowercase() in lower) index++
        return "$base ($index)$extension"
    }

    // ---- Virtual files for the picker ----
    // The picker shows a network location as a File object. The app never reads this
    // file from the disk. The path of the file has the ID of the share and the path in the share.

    fun virtualFile(shareId: String, path: String = "/"): File =
        File(VIRTUAL_ROOT + "/" + shareId + (if (path == "/") "" else path))

    fun isVirtual(file: File): Boolean = file.absolutePath.startsWith("$VIRTUAL_ROOT/")

    fun fromVirtual(file: File): Target? {
        val path = file.absolutePath
        if (!path.startsWith("$VIRTUAL_ROOT/")) return null
        val rest = path.substring(VIRTUAL_ROOT.length + 1)
        val slash = rest.indexOf('/')
        return if (slash < 0) Target(rest, "/") else Target(rest.substring(0, slash), rest.substring(slash))
    }

    fun isShareRoot(file: File): Boolean = fromVirtual(file)?.path == "/"

    /** Text for the path bar of the picker, for example "NAS: /Movies". */
    fun displayPath(context: Context, file: File): String {
        val target = fromVirtual(file) ?: return file.absolutePath
        val name = Shares.get(context, target.shareId)?.name ?: target.shareId
        return "$name: ${target.path}"
    }

    // ---- Client access ----

    @Throws(FileNotFoundException::class)
    fun share(context: Context, shareId: String): NetShare =
        Shares.get(context, shareId) ?: throw FileNotFoundException("Unknown share: $shareId")

    /**
     * Runs [block] with the client of a share. If a read action fails because the connection
     * is old, and [retry] is true, the function connects again and runs [block] one more time.
     */
    private inline fun <T> withClient(
        context: Context,
        shareId: String,
        retry: Boolean,
        block: (NetShare, RemoteClient) -> T
    ): T {
        val share = share(context, shareId)
        var attempt = 0
        while (true) {
            try {
                return block(share, NetClients.get(share))
            } catch (e: FileNotFoundException) {
                throw e
            } catch (e: IOException) {
                NetClients.invalidate(shareId)
                if (!retry || attempt++ >= 1) throw e
            } catch (e: RuntimeException) {
                NetClients.invalidate(shareId)
                throw IOException(e.message ?: e.javaClass.simpleName, e)
            }
        }
    }

    private fun requireWritable(share: NetShare) {
        if (share.readOnly) throw FileNotFoundException("This share is read-only")
    }

    /** Tests a share. Returns null if it works, or the text of the error. */
    fun test(share: NetShare): String? =
        try {
            NetClients.create(share).use { it.list("/") }
            null
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }

    // ---- Information ----

    @Throws(IOException::class)
    fun list(context: Context, shareId: String, path: String): List<RemoteEntry> =
        withClient(context, shareId, true) { _, client -> client.list(path) }

    @Throws(IOException::class)
    fun stat(context: Context, shareId: String, path: String): RemoteEntry =
        withClient(context, shareId, true) { _, client ->
            client.stat(path) ?: throw FileNotFoundException("Not found: $path")
        }

    // ---- Change ----

    /** Creates a file or folder. Returns the name that the app used. */
    @Throws(IOException::class)
    fun create(
        context: Context,
        shareId: String,
        parentPath: String,
        name: String,
        isDirectory: Boolean
    ): String =
        withClient(context, shareId, false) { share, client ->
            requireWritable(share)
            val taken = client.list(parentPath).map { it.name }.toSet()
            val finalName = uniqueName(taken, cleanName(name))
            val path = child(parentPath, finalName)
            if (isDirectory) client.mkdir(path) else client.createEmpty(path)
            finalName
        }

    @Throws(IOException::class)
    fun delete(context: Context, shareId: String, path: String) {
        withClient(context, shareId, false) { share, client ->
            requireWritable(share)
            if (path == "/") throw FileNotFoundException("Cannot delete the root of a share")
            client.delete(path)
        }
    }

    /** Renames an item. Fails if an item with the new name exists. Returns the new name. */
    @Throws(IOException::class)
    fun rename(context: Context, shareId: String, path: String, newName: String): String =
        withClient(context, shareId, false) { share, client ->
            requireWritable(share)
            if (path == "/") throw FileNotFoundException("Cannot rename the root of a share")
            val finalName = cleanName(newName)
            if (finalName == path.substringAfterLast('/')) return@withClient finalName
            val newPath = child(parentPath(path), finalName)
            if (client.stat(newPath) != null) throw FileNotFoundException("The name exists: $finalName")
            client.rename(path, newPath)
            finalName
        }

    // ---- Open ----

    /**
     * Opens a file. The result is a proxy file descriptor.
     * Supported modes: r, w, wt, wa, rw, rwt. A WebDAV share supports r, w, and wt.
     */
    @Throws(IOException::class)
    fun open(context: Context, shareId: String, path: String, mode: String): ParcelFileDescriptor {
        if (mode !in MODES) throw FileNotFoundException("Mode is not supported: $mode")
        val canRead = mode.startsWith("r")
        val canWrite = mode != "r"
        val storage = context.getSystemService(StorageManager::class.java)
            ?: throw IOException("No storage service")

        return withClient(context, shareId, !canWrite) { share, client ->
            if (canWrite) requireWritable(share)
            if (canRead && canWrite && !client.supportsRandomWrite) {
                throw FileNotFoundException("This share does not support read and write together")
            }
            val existing = client.stat(path)
            if (existing?.isDir == true) throw FileNotFoundException("This is a folder: $path")
            if (canRead && existing == null) throw FileNotFoundException("Not found: $path")

            val reader = if (canRead) client.openRead(path) else null
            val writer = if (canWrite) {
                val writeMode = when (mode) {
                    "wa" -> WriteMode.APPEND
                    "w", "wt", "rwt" -> WriteMode.TRUNCATE
                    else -> WriteMode.KEEP
                }
                try {
                    client.openWrite(path, writeMode)
                } catch (e: Exception) {
                    reader?.close()
                    throw e
                }
            } else {
                null
            }

            val fileMode = when {
                canRead && canWrite -> ParcelFileDescriptor.MODE_READ_WRITE
                canWrite -> ParcelFileDescriptor.MODE_WRITE_ONLY
                else -> ParcelFileDescriptor.MODE_READ_ONLY
            }
            val thread = HandlerThread("net-file-io")
            thread.start()
            try {
                storage.openProxyFileDescriptor(
                    fileMode,
                    NetFileCallback(reader, writer, mode == "wa", thread),
                    Handler(thread.looper)
                )
            } catch (e: Exception) {
                thread.quitSafely()
                reader?.close()
                writer?.close()
                throw e
            }
        }
    }

    /**
     * Serves the reads and writes of the app. The reads use a block of 1 MiB. This keeps
     * the number of network requests low if the app reads in small parts.
     */
    private class NetFileCallback(
        private val reader: RemoteReader?,
        private val writer: RemoteWriter?,
        private val append: Boolean,
        private val thread: HandlerThread
    ) : ProxyFileDescriptorCallback() {

        private val cache = ByteArray(if (reader != null) BLOCK_SIZE else 0)
        private var cacheStart = -1L
        private var cacheLength = 0
        private var written = writer?.startOffset ?: 0L

        override fun onGetSize(): Long = maxOf(reader?.size ?: 0L, written)

        override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
            val source = reader ?: throw ErrnoException("onRead", OsConstants.EBADF)
            try {
                val limit = onGetSize()
                var total = 0
                var position = offset
                while (total < size && position < limit) {
                    if (cacheStart < 0 || position < cacheStart || position >= cacheStart + cacheLength) {
                        fill(source, position)
                    }
                    val inCache = (position - cacheStart).toInt()
                    val count = minOf(size - total, cacheLength - inCache)
                    if (count <= 0) break
                    System.arraycopy(cache, inCache, data, total, count)
                    total += count
                    position += count
                }
                return total
            } catch (e: IOException) {
                throw ErrnoException("onRead", OsConstants.EIO)
            }
        }

        private fun fill(source: RemoteReader, position: Long) {
            val start = position - position % BLOCK_SIZE
            val wanted = minOf(BLOCK_SIZE.toLong(), maxOf(source.size - start, 0L)).toInt()
            var total = 0
            while (total < wanted) {
                val count = source.readAt(start + total, cache, total, wanted - total)
                if (count <= 0) break
                total += count
            }
            cacheStart = start
            cacheLength = total
        }

        override fun onWrite(offset: Long, size: Int, data: ByteArray): Int {
            val target = writer ?: throw ErrnoException("onWrite", OsConstants.EBADF)
            try {
                val at = if (append) written else offset
                target.write(at, data, 0, size)
                written = maxOf(written, at + size)
                cacheStart = -1L
                return size
            } catch (e: IOException) {
                throw ErrnoException("onWrite", OsConstants.EIO)
            }
        }

        override fun onFsync() {
            try {
                writer?.flush()
            } catch (e: IOException) {
                throw ErrnoException("onFsync", OsConstants.EIO)
            }
        }

        override fun onRelease() {
            try {
                reader?.close()
            } catch (e: IOException) {
                // Ignore.
            }
            try {
                writer?.close()
            } catch (e: IOException) {
                // The app cannot get this error. The data can be incomplete.
            }
            thread.quitSafely()
        }
    }
}
