package com.phonepilot.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Thread-safe logger that mirrors all log output to:
 * 1. Standard Android Logcat
 * 2. On-device text file: /sdcard/Android/data/com.phonepilot/files/phonepilot.log
 *
 * Designed specifically for high-throughput audio listening loops:
 * uses a dedicated single-thread worker so file I/O never blocks the audio loop.
 */
object AppLogger {

    private const val DEFAULT_TAG = "PhonePilot"
    private const val MAX_LOG_SIZE_BYTES = 3 * 1024 * 1024L // 3 MB

    private var logFile: File? = null
    private var downloadLogFile: File? = null
    private val fileExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PhonePilot-FileLogger").apply { priority = Thread.MIN_PRIORITY }
    }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) {
        try {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            if (!dir.exists()) {
                dir.mkdirs()
            }
            logFile = File(dir, "phonepilot.log")

            try {
                val publicDownloads = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                if (publicDownloads != null) {
                    if (!publicDownloads.exists()) publicDownloads.mkdirs()
                    downloadLogFile = File(publicDownloads, "phonepilot.log")
                }
            } catch (_: Exception) {}

            i("AppLogger", "=======================================================")
            i("AppLogger", "PhonePilot session started.")
            i("AppLogger", "App internal log: ${logFile?.absolutePath}")
            i("AppLogger", "Public Downloads log: ${downloadLogFile?.absolutePath}")
            i("AppLogger", "=======================================================")
        } catch (e: Exception) {
            Log.e(DEFAULT_TAG, "Failed to initialize log file: ${e.message}", e)
        }
    }

    fun i(tag: String = DEFAULT_TAG, msg: String) {
        Log.i(tag, msg)
        writeToFile("INFO", tag, msg)
    }

    fun d(tag: String = DEFAULT_TAG, msg: String) {
        Log.d(tag, msg)
        writeToFile("DEBUG", tag, msg)
    }

    fun w(tag: String = DEFAULT_TAG, msg: String, tr: Throwable? = null) {
        if (tr != null) Log.w(tag, msg, tr) else Log.w(tag, msg)
        val fullMsg = if (tr != null) "$msg\n${Log.getStackTraceString(tr)}" else msg
        writeToFile("WARN", tag, fullMsg)
    }

    fun e(tag: String = DEFAULT_TAG, msg: String, tr: Throwable? = null) {
        if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
        val fullMsg = if (tr != null) "$msg\n${Log.getStackTraceString(tr)}" else msg
        writeToFile("ERROR", tag, fullMsg)
    }

    private fun writeToFile(level: String, tag: String, msg: String) {
        val targets = listOfNotNull(logFile, downloadLogFile)
        if (targets.isEmpty()) return

        fileExecutor.execute {
            val timestamp = dateFormat.format(Date())
            val formattedLine = "[$timestamp] [$level] [$tag] $msg\n"

            for (file in targets) {
                try {
                    // Check for log rotation if size exceeds limit
                    if (file.exists() && file.length() > MAX_LOG_SIZE_BYTES) {
                        val backupFile = File(file.parentFile, "${file.name}.old")
                        if (backupFile.exists()) backupFile.delete()
                        file.renameTo(backupFile)
                    }

                    FileWriter(file, true).use { writer ->
                        writer.write(formattedLine)
                        writer.flush()
                    }
                } catch (_: Exception) {
                    // Ignore file write errors to avoid crashing audio process
                }
            }
        }
    }
}
