package com.example.androidtvfilepicker

import android.content.Context
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.Date

/**
 * Saves the text of the last error that stopped the app. The start screen shows the text.
 * The app does not send the text to a network.
 */
object CrashLog {

    private const val FILE_NAME = "last_error.txt"

    /** True if the start screen already showed the report in this process. */
    @Volatile
    var shown = false

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (isLibraryThreadError(thread, error)) {
                // A thread of a library (for example the reader thread of the SMB library) ended
                // with an error. The thread ends, but the app does not need to stop.
                EventLog.add(
                    app,
                    "IGNORED error in library thread ${thread.name}: " +
                        "${error.javaClass.name}: ${error.message} at ${error.stackTrace.firstOrNull()}"
                )
                return@setDefaultUncaughtExceptionHandler
            }
            EventLog.add(app, "CRASH in ${thread.name}: ${error.javaClass.name}: ${error.message}")
            try {
                val text = "${Date()}\nThread: ${thread.name}\n" + Log.getStackTraceString(error)
                File(app.filesDir, FILE_NAME).writeText(text)
            } catch (e: Throwable) {
                // Do not hide the original error.
            }
            // The default handler stops the app, as before.
            previous?.uncaughtException(thread, error)
        }
    }

    private val LIBRARY_PACKAGES = listOf(
        "com.hierynomus.", "net.engio.", "okhttp3.", "okio.", "org.bouncycastle.", "org.slf4j."
    )

    // The threads that the app starts have these names. An error in such a thread is a bug of the app.
    private val APP_THREAD_NAME = Regex("Thread-\\d+|net-file-io|root-file-io")

    /**
     * True if the thread is not the main thread and not a thread of the app, and a library
     * is in the stack of the error. The guard is narrow on purpose. Each other error stops the app.
     */
    private fun isLibraryThreadError(thread: Thread, error: Throwable): Boolean {
        if (thread === Looper.getMainLooper().thread) return false
        if (APP_THREAD_NAME.matches(thread.name)) return false
        return error.stackTrace.any { frame -> LIBRARY_PACKAGES.any { frame.className.startsWith(it) } }
    }

    /** Returns the text of the last error, or null. */
    fun read(context: Context): String? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null
        return try {
            file.readText()
        } catch (e: Exception) {
            null
        }
    }

    fun clear(context: Context) {
        File(context.filesDir, FILE_NAME).delete()
    }
}
