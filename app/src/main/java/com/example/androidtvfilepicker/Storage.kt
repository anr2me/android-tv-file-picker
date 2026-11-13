package com.example.androidtvfilepicker

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract.Document
import android.provider.Settings
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** Helper functions for storage volumes, paths, MIME types, and permission. */
object Storage {

    /** A mounted storage volume, for example internal storage or a USB drive. */
    data class Root(val id: String, val title: String, val dir: File)

    /** The authority of the document provider of this app. */
    fun authority(context: Context): String = context.packageName + ".documents"

    fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

    /**
     * Opens the settings screen for All files access.
     * Returns false if the device has no such screen.
     */
    fun openAllFilesSettings(activity: Activity): Boolean {
        val packageUri = Uri.parse("package:" + activity.packageName)
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri),
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        )
        for (intent in intents) {
            try {
                activity.startActivity(intent)
                return true
            } catch (e: ActivityNotFoundException) {
                // Try the next intent.
            } catch (e: SecurityException) {
                // Try the next intent.
            }
        }
        return false
    }

    /** Returns all mounted storage volumes. */
    fun roots(context: Context): List<Root> {
        val manager = context.getSystemService(StorageManager::class.java) ?: return emptyList()
        return manager.storageVolumes.mapNotNull { volume ->
            val state = volume.state
            val mounted = state == Environment.MEDIA_MOUNTED ||
                state == Environment.MEDIA_MOUNTED_READ_ONLY
            val dir = volume.directory
            if (!mounted || dir == null) {
                null
            } else {
                Root(dir.absolutePath, volume.getDescription(context), dir)
            }
        }
    }

    fun isRoot(context: Context, file: File): Boolean =
        roots(context).any { it.dir.absolutePath == file.absolutePath }

    /** True if the file is inside a mounted storage volume. */
    fun isInsideStorage(context: Context, file: File): Boolean {
        val path = try {
            file.canonicalPath
        } catch (e: IOException) {
            return false
        }
        return roots(context).any { root ->
            val base = try {
                root.dir.canonicalPath
            } catch (e: IOException) {
                root.dir.absolutePath
            }
            path == base || path.startsWith("$base/")
        }
    }

    /** True if [child] is a descendant of [parent]. */
    fun isDescendant(parent: File, child: File): Boolean {
        return try {
            child.canonicalPath.startsWith(parent.canonicalPath + "/")
        } catch (e: IOException) {
            false
        }
    }

    /**
     * Converts a document ID to a file. Fails if the file is outside the storage volumes.
     * Also fails for protected folders. Only the "root:" document IDs give access to them.
     */
    @Throws(FileNotFoundException::class)
    fun resolve(context: Context, documentId: String): File {
        val file = File(documentId)
        if (!isInsideStorage(context, file) || RootAccess.isRestricted(context, file)) {
            throw FileNotFoundException("Not available: $documentId")
        }
        return file
    }

    fun mimeOf(file: File): String {
        if (file.isDirectory) return Document.MIME_TYPE_DIR
        return mimeOfName(file.name)
    }

    /** Returns the MIME type from the extension of a file name. */
    fun mimeOfName(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }

    /** Checks a MIME type against filters such as star/star or image/star. */
    fun mimeMatches(mime: String, filters: List<String>): Boolean =
        filters.any { filter ->
            when {
                filter == "*/*" || filter == "*" -> true
                filter.endsWith("/*") -> mime.startsWith(filter.dropLast(1), ignoreCase = true)
                else -> mime.equals(filter, ignoreCase = true)
            }
        }

    fun sanitizeName(name: String): String {
        val clean = name.replace('/', '_').replace('\u0000', '_').trim()
        return if (clean.isEmpty() || clean == "." || clean == "..") "untitled" else clean
    }

    /** Returns a file in [dir] that does not exist yet. Adds " (1)", " (2)" to the name if needed. */
    fun uniqueFile(dir: File, name: String): File {
        val clean = sanitizeName(name)
        var candidate = File(dir, clean)
        if (!candidate.exists()) return candidate
        val dot = clean.lastIndexOf('.')
        val base = if (dot > 0) clean.substring(0, dot) else clean
        val extension = if (dot > 0) clean.substring(dot) else ""
        var index = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base ($index)$extension")
            index++
        }
        return candidate
    }
}
