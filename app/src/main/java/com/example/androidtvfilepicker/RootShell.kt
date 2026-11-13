package com.example.androidtvfilepicker

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Runs shell commands as root with the su command. */
object RootShell {

    private const val TIMEOUT_SECONDS = 30L

    /** Puts single quotes around a value. A shell then reads the value as one word. */
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /** Starts su with a script. The caller must stop the process. */
    @Throws(IOException::class)
    fun start(script: String): Process =
        ProcessBuilder("su", "-c", script)
            .redirectError(ProcessBuilder.Redirect.to(File("/dev/null")))
            .start()

    /** Runs a script and returns the lines of its output. Fails if the exit code is not 0. */
    @Throws(IOException::class)
    fun run(script: String): List<String> {
        val process = start(script)
        try {
            process.outputStream.close()
            val lines = process.inputStream.bufferedReader().readLines()
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw IOException("The su command timed out")
            }
            val code = process.exitValue()
            if (code != 0) throw IOException("The su command failed with code $code")
            return lines
        } finally {
            process.destroy()
        }
    }

    /** True if su runs and the user ID is 0. A root manager can show a request to the user. */
    fun isRootAvailable(): Boolean = try {
        run("id -u").firstOrNull()?.trim() == "0"
    } catch (e: Exception) {
        false
    }

    /** Returns the real path of each path. An entry is empty if the path does not exist. */
    @Throws(IOException::class)
    fun realPaths(paths: List<String>): List<String> {
        val words = paths.joinToString(" ") { quote(it) }
        val lines = run(
            "for p in $words; do r=\$(readlink -f \"\$p\" 2>/dev/null); echo \"\$r\"; done"
        )
        return paths.indices.map { lines.getOrElse(it) { "" } }
    }
}
