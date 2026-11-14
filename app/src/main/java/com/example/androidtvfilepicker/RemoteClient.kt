package com.example.androidtvfilepicker

import java.io.Closeable
import java.io.IOException

/** A file or folder on a remote server. [modified] is in milliseconds. */
data class RemoteEntry(val name: String, val isDir: Boolean, val size: Long, val modified: Long)

enum class WriteMode {
    /** Delete the old data. */
    TRUNCATE,

    /** Keep the old data. Overwrite at the offset of each write. */
    KEEP,

    /** Write at the end of the file. */
    APPEND
}

interface RemoteReader : Closeable {
    val size: Long

    /** Reads up to [length] bytes. Returns the number of bytes, or -1 at the end of the file. */
    @Throws(IOException::class)
    fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int, length: Int): Int
}

interface RemoteWriter : Closeable {
    /** The size of the file when it was opened for append. Else 0. */
    val startOffset: Long

    @Throws(IOException::class)
    fun write(offset: Long, data: ByteArray, dataOffset: Int, length: Int)

    @Throws(IOException::class)
    fun flush() {
    }
}

/**
 * A client for one share. All paths start with "/". The path "/" is the root of the share.
 * The methods block. Call them from a background thread.
 */
interface RemoteClient : Closeable {

    /** True if a writer accepts writes at any offset. */
    val supportsRandomWrite: Boolean

    @Throws(IOException::class)
    fun list(path: String): List<RemoteEntry>

    /** Returns null if the item does not exist. */
    @Throws(IOException::class)
    fun stat(path: String): RemoteEntry?

    @Throws(IOException::class)
    fun openRead(path: String): RemoteReader

    @Throws(IOException::class)
    fun openWrite(path: String, mode: WriteMode): RemoteWriter

    @Throws(IOException::class)
    fun mkdir(path: String)

    @Throws(IOException::class)
    fun createEmpty(path: String)

    @Throws(IOException::class)
    fun delete(path: String)

    @Throws(IOException::class)
    fun rename(path: String, newPath: String)
}

/** Keeps one client for each share. A client opens its connection when it is first used. */
object NetClients {

    private val clients = HashMap<String, Pair<NetShare, RemoteClient>>()

    @Synchronized
    fun get(share: NetShare): RemoteClient {
        val cached = clients[share.id]
        if (cached != null && cached.first == share) return cached.second
        cached?.second?.let { closeQuietly(it) }
        val client = create(share)
        clients[share.id] = share to client
        return client
    }

    @Synchronized
    fun invalidate(shareId: String) {
        clients.remove(shareId)?.second?.let { closeQuietly(it) }
    }

    /** Makes a new client that is not in the cache. The caller must close it. */
    fun create(share: NetShare): RemoteClient = when (share.type) {
        NetShare.Type.SMB -> SmbRemoteClient(share)
        NetShare.Type.WEBDAV -> WebDavRemoteClient(share)
    }

    private fun closeQuietly(client: RemoteClient) {
        try {
            client.close()
        } catch (e: Exception) {
            // Ignore.
        }
    }
}
