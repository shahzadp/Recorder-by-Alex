package com.alex.cam

import android.app.Application
import android.util.Log
import com.google.android.material.color.DynamicColors
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Application class.
 *
 * Applies the Material 3 dynamic colour scheme, so the app follows the user's
 * wallpaper palette on Android 12 and newer.
 *
 * It also catches crashes: a recorder that dies silently is impossible to
 * debug from the outside, so the reason is written to a file and shown on the
 * live dashboard the next time the app starts.
 */
class RecorderApp : Application() {

    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
        installCrashLogger()
    }

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val writer = StringWriter()
                error.printStackTrace(PrintWriter(writer))
                val text = buildString {
                    append(TimeLog.now())
                    append("  thread: ")
                    append(thread.name)
                    append('\n')
                    append(writer.toString())
                }
                openFileOutput(CRASH_FILE, MODE_APPEND).use {
                    it.write(text.toByteArray())
                }
            }.onFailure { Log.e("RecorderApp", "could not write the crash log", it) }
            // Let the system show its usual crash dialog.
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        const val CRASH_FILE = "crash.log"

        /** The newest crash, if the app died before. */
        fun lastCrash(context: android.content.Context): String? = runCatching {
            val file = context.getFileStreamPath(CRASH_FILE)
            if (!file.exists() || file.length() == 0L) return null
            val text = file.readText()
            file.delete()
            val lines = text.lines()
            val when_ = lines.firstOrNull()?.trim().orEmpty()
            val what = lines.getOrNull(1)?.trim().orEmpty()
            val cause = lines.getOrNull(2)?.trim().orEmpty()
            listOf(when_, what, cause).filter { it.isNotBlank() }.joinToString(" | ")
                .takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}

/** A timestamp that needs no extra imports. */
private object TimeLog {
    fun now(): String = java.text.SimpleDateFormat(
        "yyyy-MM-dd HH:mm:ss",
        java.util.Locale.US
    ).format(java.util.Date())
}
