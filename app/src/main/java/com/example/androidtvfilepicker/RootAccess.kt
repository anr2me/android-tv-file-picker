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
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * Optional root access for the folders that Android protects (Android/data and Android/obb).
 * The user must turn this function on. It is off by default.
 *
 * A protected document has the ID prefix "root:". A normal document ID never gives access
 * to a protected folder. A grant for a normal folder therefore never includes protected folders.
 */
object RootAccess {

    const val ID_PREFIX = "root:"

    private const val PREFS = "settings"
    private const val KEY_ENABLED = "root_enabled"
    private const val STAT_FORMAT = "%F|%s|%Y|%n"
    private val RESTRICTED = listOf("Android/data", "Android/obb")

    data class Entry(val name: String, val isDir: Boolean, val size: Long, val modified: Long)

    // ---- Setting ----

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    // ---- Path rules ----

    /** True if the file is in Android/data or Android/obb of a storage volume. */
    fun isRestricted(roots: List<Storage.Root>, file: File): Boolean {
        val path = file.absolutePath
        for (root in roots) {
            val base = root.dir.absolutePath + "/"
            if (path.startsWith(base)) {
                val relative = path.substring(base.length)
                return RESTRICTED.any { relative == it || relative.startsWith("$it/") }
            }
        }
        return false
    }

    fun isRestricted(context: Context, file: File): Boolean =
        isRestricted(Storage.roots(context), file)

    /** True if the path is absolute and has no empty, "." or ".." parts. */
    fun isCleanPath(path: String): Boolean =
        path.startsWith("/") && !path.contains('\u0000') &&
            path.split('/').drop(1).none { it.isEmpty() || it == "." || it == ".." }

    /** Converts a storage path to the path that is not filtered by Android. */
    private fun toRaw(path: String): String = when {
        path.startsWith("/storage/emulated/") -> "/data/media/" + path.removePrefix("/storage/emulated/")
        path.startsWith("/storage/") -> "/mnt/media_rw/" + path.removePrefix("/storage/")
        else -> path
    }

    /**
     * Checks a protected path and returns its real path. Needs root.
     * Fails if the real path is outside Android/data and Android/obb.
     */
    @Throws(IOException::class)
    fun resolve(context: Context, path: String): String {
        if (!isEnabled(context)) throw FileNotFoundException("Root support is off")
        val roots = Storage.roots(context)
        val volume = roots.firstOrNull { path.startsWith(it.dir.absolutePath + "/") }
        if (volume == null || !isCleanPath(path) || !isRestricted(roots, File(path))) {
            throw FileNotFoundException("Not a protected path: $path")
        }
        val volumePath = volume.dir.absolutePath
        val rawRoot = toRaw(volumePath)
        val real = RootShell.realPaths(
            listOf(
                rawRoot + path.substring(volumePath.length),
                "$rawRoot/Android/data",
                "$rawRoot/Android/obb"
            )
        )
        val target = real[0]
        if (target.isEmpty()) throw FileNotFoundException("Not found: $path")
        val inside = real.drop(1).any { it.isNotEmpty() && (target == it || target.startsWith("$it/")) }
        if (!inside) throw FileNotFoundException("The path leaves the protected folders: $path")
        return target
    }

    // ---- Folder and file information ----

    @Throws(IOException::class)
    fun stat(context: Context, path: String): Entry = statReal(resolve(context, path), File(path).name)

    @Throws(IOException::class)
    fun list(context: Context, path: String): List<Entry> = listReal(resolve(context, path))

    @Throws(IOException::class)
    private fun statReal(real: String, name: String): Entry {
        val line = RootShell.run("stat -L -c '$STAT_FORMAT' ${RootShell.quote(real)}").firstOrNull()
        return line?.let { parseStat(it, name) } ?: throw FileNotFoundException("Not found: $name")
    }

    @Throws(IOException::class)
    private fun listReal(real: String): List<Entry> {
        val script = "cd ${RootShell.quote(real)} || exit 1; " +
            "stat -L -c '$STAT_FORMAT' ./.[!.]* ./..?* ./* 2>/dev/null; exit 0"
        return RootShell.run(script).mapNotNull { parseStat(it, null) }
    }

    private fun parseStat(line: String, name: String?): Entry? {
        val parts = line.split("|", limit = 4)
        if (parts.size < 4) return null
        return Entry(
            name = name ?: parts[3].removePrefix("./"),
            isDir = parts[0].startsWith("directory"),
            size = parts[1].toLongOrNull() ?: 0L,
            modified = (parts[2].toLongOrNull() ?: 0L) * 1000L
        )
    }

    // ---- Create ----

    /**
     * Creates a file or folder in a protected folder. Returns the name that the picker used.
     * The new item gets the owner, group, and SELinux label of the parent folder.
     */
    @Throws(IOException::class)
    fun create(context: Context, parentPath: String, name: String, isDirectory: Boolean): String {
        val parent = resolve(context, parentPath)
        val taken = listReal(parent).map { it.name }.toSet()
        val finalName = uniqueName(taken, Storage.sanitizeName(name))
        val target = RootShell.quote("$parent/$finalName")
        val owner = RootShell.quote(parent)
        val make = if (isDirectory) "mkdir $target" else "touch $target"
        val mode = if (isDirectory) "\"\$(stat -c %a $owner)\"" else "660"
        val script = "if $make; then " +
            "chown \"\$(stat -c %u:%g $owner)\" $target; " +
            "chmod $mode $target; " +
            "restorecon $target >/dev/null 2>&1; " +
            "echo ok; fi; exit 0"
        if (RootShell.run(script).none { it.trim() == "ok" }) {
            throw FileNotFoundException("Cannot create: $finalName")
        }
        return finalName
    }

    // ---- Rename ----

    /**
     * Renames an item in a protected folder. The item stays in its folder.
     * Fails if an item with the new name exists. Returns the new name.
     * The picker does not rename Android/data and Android/obb themselves.
     */
    @Throws(IOException::class)
    fun rename(context: Context, path: String, newName: String): String {
        if (!isCleanPath(path)) throw FileNotFoundException("Not a protected path: $path")
        val parentPath = path.substringBeforeLast('/')
        val oldName = path.substringAfterLast('/')
        val finalName = Storage.sanitizeName(newName)
        if (finalName == oldName) return oldName

        // The parent must be in a protected folder. This also rejects Android/data itself.
        val parent = resolve(context, parentPath)
        val source = RootShell.quote("$parent/$oldName")
        val target = RootShell.quote("$parent/$finalName")
        val script = "if { [ -e $source ] || [ -L $source ]; } && " +
            "! { [ -e $target ] || [ -L $target ]; }; then " +
            "mv -n $source $target && { [ -e $target ] || [ -L $target ]; } && echo ok; fi; exit 0"
        if (RootShell.run(script).none { it.trim() == "ok" }) {
            throw FileNotFoundException("Cannot rename to: $finalName")
        }
        return finalName
    }

    private fun uniqueName(taken: Set<String>, name: String): String {
        if (name !in taken) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var index = 1
        while ("$base ($index)$extension" in taken) index++
        return "$base ($index)$extension"
    }

    // ---- Open ----

    /**
     * Opens a protected file. The result is a proxy file descriptor.
     * Reads can use random access. Writes must be sequential.
     * Supported modes: r, w, wt, wa.
     */
    @Throws(IOException::class)
    fun open(context: Context, path: String, mode: String): ParcelFileDescriptor {
        val real = resolve(context, path)
        val info = statReal(real, File(path).name)
        if (info.isDir) throw FileNotFoundException("This is a folder: $path")
        val storage = context.getSystemService(StorageManager::class.java)
            ?: throw IOException("No storage service")

        val thread = HandlerThread("root-file-io")
        thread.start()
        try {
            val handler = Handler(thread.looper)
            return when (mode) {
                "r" -> storage.openProxyFileDescriptor(
                    ParcelFileDescriptor.MODE_READ_ONLY,
                    ReadCallback(real, info.size, thread),
                    handler
                )
                "w", "wt" -> storage.openProxyFileDescriptor(
                    ParcelFileDescriptor.MODE_WRITE_ONLY,
                    WriteCallback(real, false, thread),
                    handler
                )
                "wa" -> storage.openProxyFileDescriptor(
                    ParcelFileDescriptor.MODE_WRITE_ONLY,
                    WriteCallback(real, true, thread),
                    handler
                )
                else -> throw FileNotFoundException("Mode is not supported here: $mode")
            }
        } catch (e: Exception) {
            thread.quitSafely()
            throw e
        }
    }

    /** Reads a file with the tail command. It starts a new process if the offset changes. */
    private class ReadCallback(
        private val realPath: String,
        private val length: Long,
        private val thread: HandlerThread
    ) : ProxyFileDescriptorCallback() {

        private var process: Process? = null
        private var input: InputStream? = null
        private var position = -1L

        override fun onGetSize(): Long = length

        override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
            if (offset >= length) return 0
            try {
                if (input == null || offset != position) restart(offset)
                val stream = input ?: return 0
                var total = 0
                while (total < size) {
                    val count = stream.read(data, total, size - total)
                    if (count < 0) break
                    total += count
                }
                position = offset + total
                return total
            } catch (e: IOException) {
                throw ErrnoException("onRead", OsConstants.EIO)
            }
        }

        private fun restart(offset: Long) {
            closeStream()
            val started = RootShell.start("tail -c +${offset + 1} ${RootShell.quote(realPath)}")
            started.outputStream.close()
            process = started
            input = started.inputStream
            position = offset
        }

        private fun closeStream() {
            try {
                input?.close()
            } catch (e: IOException) {
                // Ignore.
            }
            process?.destroy()
            process = null
            input = null
        }

        override fun onRelease() {
            closeStream()
            thread.quitSafely()
        }
    }

    /** Writes a file with the cat command. The writes must be in sequence. */
    private class WriteCallback(
        realPath: String,
        private val append: Boolean,
        private val thread: HandlerThread
    ) : ProxyFileDescriptorCallback() {

        private val process: Process =
            RootShell.start("cat ${if (append) ">>" else ">"} ${RootShell.quote(realPath)}")
        private val output: OutputStream = process.outputStream
        private var position = 0L

        override fun onGetSize(): Long = position

        override fun onWrite(offset: Long, size: Int, data: ByteArray): Int {
            if (!append && offset != position) throw ErrnoException("onWrite", OsConstants.ESPIPE)
            try {
                output.write(data, 0, size)
                position += size
                return size
            } catch (e: IOException) {
                throw ErrnoException("onWrite", OsConstants.EIO)
            }
        }

        override fun onFsync() {
            try {
                output.flush()
            } catch (e: IOException) {
                throw ErrnoException("onFsync", OsConstants.EIO)
            }
        }

        override fun onRelease() {
            try {
                output.close()
                process.waitFor(10, TimeUnit.SECONDS)
            } catch (e: IOException) {
                // Ignore.
            } finally {
                process.destroy()
                thread.quitSafely()
            }
        }
    }
}
