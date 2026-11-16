package com.example.androidtvfilepicker

import android.content.Context
import android.os.Process
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A short log of events. It helps to find the cause of a problem on a device that has no PC.
 * The log has the last 80 lines. The start screen can show it. The app does not send it.
 */
object EventLog {

    private const val FILE_NAME = "events.txt"
    private const val MAX_LINES = 80

    @Synchronized
    fun add(context: Context, message: String) {
        try {
            val file = File(context.applicationContext.filesDir, FILE_NAME)
            val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val line = "$time [${Process.myPid()}] ${message.replace('\n', ' ')}"
            val lines = if (file.exists()) file.readLines() else emptyList()
            file.writeText((lines + line).takeLast(MAX_LINES).joinToString("\n") + "\n")
        } catch (e: Throwable) {
            // The log must not cause an error.
        }
    }

    @Synchronized
    fun read(context: Context): String {
        return try {
            val file = File(context.applicationContext.filesDir, FILE_NAME)
            if (file.exists()) file.readText() else ""
        } catch (e: Exception) {
            ""
        }
    }

    @Synchronized
    fun clear(context: Context) {
        File(context.applicationContext.filesDir, FILE_NAME).delete()
    }
}
