package com.example.androidtvfilepicker

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.FileNotFoundException
import java.io.IOException
import java.util.EnumSet
import java.util.concurrent.TimeUnit
import com.hierynomus.smbj.share.File as SmbFile

/** SMB 2 and SMB 3 client. It uses the smbj library. */
class SmbRemoteClient(private val share: NetShare) : RemoteClient {

    private val client = SMBClient(
        SmbConfig.builder()
            .withTimeout(30, TimeUnit.SECONDS)
            .withSoTimeout(60, TimeUnit.SECONDS)
            .build()
    )
    private var connection: Connection? = null
    private var session: Session? = null
    private var disk: DiskShare? = null

    override val supportsRandomWrite: Boolean = true

    // ---- Connection ----

    @Synchronized
    private fun disk(): DiskShare {
        disk?.let { if (it.isConnected) return it }
        disconnect()
        val (host, port) = hostAndPort()
        val newConnection = client.connect(host, port)
        val auth = if (share.username.isEmpty()) {
            AuthenticationContext.guest()
        } else {
            AuthenticationContext(share.username, share.password.toCharArray(), share.domain)
        }
        val newSession = newConnection.authenticate(auth)
        val newDisk = newSession.connectShare(share.shareName) as? DiskShare
            ?: throw IOException("This is not a file share: ${share.shareName}")
        connection = newConnection
        session = newSession
        disk = newDisk
        return newDisk
    }

    private fun disconnect() {
        try {
            disk?.close()
            session?.close()
            connection?.close()
        } catch (e: Exception) {
            // Ignore.
        }
        disk = null
        session = null
        connection = null
    }

    @Synchronized
    override fun close() {
        disconnect()
        try {
            client.close()
        } catch (e: Exception) {
            // Ignore.
        }
    }

    private fun hostAndPort(): Pair<String, Int> {
        val address = share.address.trim()
        val colon = address.lastIndexOf(':')
        // One colon means "host:port". More than one colon is an IPv6 address.
        if (colon > 0 && address.indexOf(':') == colon) {
            val port = address.substring(colon + 1).toIntOrNull()
            if (port != null) return address.substring(0, colon) to port
        }
        return address to 445
    }

    /** Converts "/a/b" to the path in the share, for example "Media\\a\\b". */
    private fun smbPath(path: String): String {
        val full = share.basePath.trim('/', '\\') + "/" + path.trim('/')
        return full.trim('/').replace('/', '\\')
    }

    private fun isNotFound(e: SMBApiException): Boolean =
        e.status == NtStatus.STATUS_OBJECT_NAME_NOT_FOUND ||
            e.status == NtStatus.STATUS_OBJECT_PATH_NOT_FOUND ||
            e.status == NtStatus.STATUS_NO_SUCH_FILE

    /** Changes the exceptions of smbj to IOException. */
    private inline fun <T> smb(block: () -> T): T =
        try {
            block()
        } catch (e: IOException) {
            throw e
        } catch (e: SMBApiException) {
            if (isNotFound(e)) {
                throw FileNotFoundException(e.message)
            }
            throw IOException(e.message ?: e.status.toString(), e)
        } catch (e: RuntimeException) {
            throw IOException(e.message ?: e.javaClass.simpleName, e)
        }

    // ---- Information ----

    override fun list(path: String): List<RemoteEntry> = smb {
        disk().list(smbPath(path))
            .filter { it.fileName != "." && it.fileName != ".." }
            .map {
                RemoteEntry(
                    it.fileName,
                    (it.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value) != 0L,
                    it.endOfFile,
                    it.lastWriteTime.toEpochMillis()
                )
            }
    }

    override fun stat(path: String): RemoteEntry? = smb {
        if (path == "/") {
            RemoteEntry("", true, 0L, 0L)
        } else {
            try {
                val info = disk().getFileInformation(smbPath(path))
                RemoteEntry(
                    path.substringAfterLast('/'),
                    info.standardInformation.isDirectory,
                    info.standardInformation.endOfFile,
                    info.basicInformation.lastWriteTime.toEpochMillis()
                )
            } catch (e: SMBApiException) {
                if (isNotFound(e)) null else throw e
            }
        }
    }

    // ---- Read and write ----

    override fun openRead(path: String): RemoteReader = smb {
        val file = disk().openFile(
            smbPath(path),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null
        )
        SmbReader(file, file.fileInformation.standardInformation.endOfFile)
    }

    override fun openWrite(path: String, mode: WriteMode): RemoteWriter = smb {
        val disposition = when (mode) {
            WriteMode.TRUNCATE -> SMB2CreateDisposition.FILE_OVERWRITE_IF
            else -> SMB2CreateDisposition.FILE_OPEN_IF
        }
        val file = disk().openFile(
            smbPath(path),
            EnumSet.of(AccessMask.GENERIC_WRITE),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL,
            disposition,
            null
        )
        val start = if (mode == WriteMode.APPEND) file.fileInformation.standardInformation.endOfFile else 0L
        SmbWriter(file, start)
    }

    private inner class SmbReader(
        private val file: SmbFile,
        override val size: Long
    ) : RemoteReader {

        override fun readAt(offset: Long, buffer: ByteArray, bufferOffset: Int, length: Int): Int = smb {
            var total = 0
            while (total < length) {
                val count = file.read(buffer, offset + total, bufferOffset + total, length - total)
                if (count <= 0) break
                total += count
            }
            if (total == 0) -1 else total
        }

        override fun close() {
            try {
                file.close()
            } catch (e: Exception) {
                // Ignore.
            }
        }
    }

    private inner class SmbWriter(
        private val file: SmbFile,
        override val startOffset: Long
    ) : RemoteWriter {

        override fun write(offset: Long, data: ByteArray, dataOffset: Int, length: Int) = smb {
            var done = 0
            while (done < length) {
                // The write method of smbj returns the number of bytes as a Long.
                val count = file.write(data, offset + done, dataOffset + done, length - done).toInt()
                if (count <= 0) throw IOException("The server did not accept the data")
                done += count
            }
        }

        override fun close() = smb { file.close() }
    }

    // ---- Change ----

    override fun mkdir(path: String) = smb { disk().mkdir(smbPath(path)) }

    override fun createEmpty(path: String) = smb {
        disk().openFile(
            smbPath(path),
            EnumSet.of(AccessMask.GENERIC_WRITE),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_CREATE,
            null
        ).close()
    }

    override fun delete(path: String) = smb {
        val entry = stat(path) ?: throw FileNotFoundException(path)
        if (entry.isDir) disk().rmdir(smbPath(path), true) else disk().rm(smbPath(path))
    }

    override fun rename(path: String, newPath: String) = smb {
        disk().open(
            smbPath(path),
            EnumSet.of(AccessMask.DELETE),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null
        ).use { it.rename(smbPath(newPath)) }
    }
}
