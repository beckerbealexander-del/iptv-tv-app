package com.alex.iptvplayer.util

import android.content.Context
import android.util.Log
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

object AppLogger {

    private const val TAG = "IPTV_DEBUG"
    private const val MAX_LOG_SIZE_BYTES = 5 * 1024 * 1024L // 5 MB Rolling buffer

    private val executor = Executors.newSingleThreadExecutor()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var logFile: File? = null

    fun init(context: Context) {
        try {
            val logsDir = File(context.getExternalFilesDir(null), "logs")
            if (!logsDir.exists()) {
                logsDir.mkdirs()
            }
            logFile = File(logsDir, "iptv_debug.log")
            i("LOGGER", "AppLogger initialized. Target file: ${logFile?.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init AppLogger file: ${e.message}", e)
        }
    }

    private fun writeEntry(level: String, category: String, message: String, throwable: Throwable? = null) {
        val time = dateFormat.format(Date())
        val thread = Thread.currentThread().name
        val formatted = "[$time] [$level] [$thread] [$category] $message"

        // 1. Android Logcat mirror
        when (level) {
            "DEBUG" -> Log.d(TAG, "[$category] $message", throwable)
            "INFO" -> Log.i(TAG, "[$category] $message", throwable)
            "WARN" -> Log.w(TAG, "[$category] $message", throwable)
            "ERROR" -> Log.e(TAG, "[$category] $message", throwable)
            else -> Log.d(TAG, "[$category] $message", throwable)
        }

        // 2. Rolling File I/O in single-thread worker
        executor.execute {
            val file = logFile ?: return@execute
            try {
                // Circular rotation wenn Datei >= 5MB
                if (file.exists() && file.length() >= MAX_LOG_SIZE_BYTES) {
                    val backup = File(file.parentFile, "iptv_debug.log.1")
                    if (backup.exists()) backup.delete()
                    file.renameTo(backup)
                }

                PrintWriter(FileWriter(file, true)).use { writer ->
                    writer.println(formatted)
                    throwable?.printStackTrace(writer)
                }
            } catch (e: Exception) {
                // Silent fallback
            }
        }
    }

    fun d(category: String, message: String) = writeEntry("DEBUG", category, message)
    fun i(category: String, message: String) = writeEntry("INFO", category, message)
    fun w(category: String, message: String) = writeEntry("WARN", category, message)
    fun e(category: String, message: String, tr: Throwable? = null) = writeEntry("ERROR", category, message, tr)

    // --- Spezifische Logging-Events nach Vorgabe ---

    fun logPlayerState(state: Int, playWhenReady: Boolean, streamUrl: String) {
        val stateName = when (state) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "UNKNOWN($state)"
        }
        i("PLAYER_STATE", "State=$stateName, playWhenReady=$playWhenReady, url=$streamUrl")
    }

    fun logPlayerState(tag: String, message: String) {
        i("PLAYER_STATE", "[$tag] $message")
    }

    fun logPlayerError(error: PlaybackException) {
        val errorCodeName = error.errorCodeName
        val msg = "ErrorCode=$errorCodeName (${error.errorCode}), Message=${error.message}, Cause=${error.cause?.javaClass?.simpleName}: ${error.cause?.message}"
        e("PLAYER_ERROR", msg, error)
    }

    fun logError(tag: String, message: String, tr: Throwable? = null) {
        e(tag, message, tr)
    }

    fun logFormatChange(videoFormat: Format?, audioFormat: Format?) {
        val vStr = if (videoFormat != null) {
            "${videoFormat.width}x${videoFormat.height}@${"%.1f".format(Locale.US, videoFormat.frameRate)}fps (${videoFormat.sampleMimeType}, ${videoFormat.bitrate / 1000}kbps)"
        } else "None"
        val aStr = if (audioFormat != null) {
            "${audioFormat.sampleMimeType} (${audioFormat.channelCount}ch, ${audioFormat.sampleRate}Hz)"
        } else "None"
        i("FORMAT_CHANGE", "Video: $vStr | Audio: $aStr")
    }

    fun logFormatChange(tag: String, videoFormat: Format?, audioFormat: Format?) {
        val vStr = if (videoFormat != null) {
            "${videoFormat.width}x${videoFormat.height}@${"%.1f".format(Locale.US, videoFormat.frameRate)}fps (${videoFormat.sampleMimeType}, ${videoFormat.bitrate / 1000}kbps)"
        } else "None"
        val aStr = if (audioFormat != null) {
            "${audioFormat.sampleMimeType} (${audioFormat.channelCount}ch, ${audioFormat.sampleRate}Hz)"
        } else "None"
        i("FORMAT_CHANGE", "[$tag] Video: $vStr | Audio: $aStr")
    }

    fun logNetwork(method: String, url: String, code: Int, latencyMs: Long, error: String? = null) {
        val safeUrl = if (url.contains("password=")) url.substringBefore("password=") + "password=***" else url
        if (error != null || code >= 400) {
            w("NETWORK", "$method $safeUrl -> HTTP $code in ${latencyMs}ms, error=$error")
        } else {
            d("NETWORK", "$method $safeUrl -> HTTP $code in ${latencyMs}ms")
        }
    }

    fun logNetwork(message: String) {
        i("NETWORK", message)
    }

    fun logLifecycle(component: String, event: String) {
        i("LIFECYCLE", "$component: $event")
    }

    fun logFocus(viewId: String, keyCode: Int) {
        d("FOCUS_REMOTE", "Key=$keyCode, FocusedViewId=$viewId")
    }

    fun logFocus(keyCode: Int, keyName: String, viewName: String) {
        d("FOCUS_REMOTE", "Key=$keyName($keyCode), FocusedView=$viewName")
    }
}
