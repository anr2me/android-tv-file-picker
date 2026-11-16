package com.example.androidtvfilepicker

import android.content.Context
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
