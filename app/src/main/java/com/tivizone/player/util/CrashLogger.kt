package com.tivizone.player.util

import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashLogger {

    private const val TAG = "CrashLogger"
    private var isInitialized = false

    fun init(context: Context) {
        if (isInitialized) return
        isInitialized = true

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                handleCrash(context, thread, throwable)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Fehler beim Schreiben des Crashlogs: ${e.message}", e)
            } finally {
                defaultHandler?.uncaughtException(thread, throwable) ?: run {
                    Process.killProcess(Process.myPid())
                    System.exit(10)
                }
            }
        }
        AppLogger.i(TAG, "CrashLogger erfolgreich initialisiert.")
    }

    private fun handleCrash(context: Context, thread: Thread, throwable: Throwable) {
        val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        val stackTrace = sw.toString()

        val sb = StringBuilder()
        sb.append("====================================================\n")
        sb.append("💥 TIVIZONE CRASH REPORT - $timeStr\n")
        sb.append("====================================================\n")
        sb.append("Gerät:        ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})\n")
        sb.append("Android:      API ${Build.VERSION.SDK_INT} (Android ${Build.VERSION.RELEASE})\n")
        sb.append("Thread:       ${thread.name} (id: ${thread.id})\n")
        sb.append("Exception:    ${throwable.javaClass.name}\n")
        sb.append("Meldung:      ${throwable.message}\n")
        sb.append("----------------------------------------------------\n")
        sb.append("STACKTRACE:\n")
        sb.append(stackTrace)
        sb.append("====================================================\n\n")

        val crashReport = sb.toString()

        // 1. In Logcat schreiben
        AppLogger.e(TAG, crashReport)

        // 2. In /files/last_crash.txt überschreiben (sofort lesbar)
        try {
            val lastCrashFile = File(context.filesDir, "last_crash.txt")
            lastCrashFile.writeText(crashReport)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Konnte last_crash.txt nicht schreiben: ${e.message}")
        }

        // 3. In /files/crash_history.log anhängen
        try {
            val historyFile = File(context.filesDir, "crash_history.log")
            historyFile.appendText(crashReport)
        } catch (e: Exception) {
            AppLogger.e(TAG, "Konnte crash_history.log nicht schreiben: ${e.message}")
        }
    }

    fun getLastCrash(context: Context): String? {
        val file = File(context.filesDir, "last_crash.txt")
        return if (file.exists()) file.readText() else null
    }

    fun clearCrashLog(context: Context) {
        val file = File(context.filesDir, "last_crash.txt")
        if (file.exists()) file.delete()
    }
}
