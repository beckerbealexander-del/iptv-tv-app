package com.alex.iptvplayer.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.Serializable

data class HistoryItem(
    val id: String,
    val title: String,
    val streamUrl: String,
    val posterUrl: String? = null,
    val type: String, // "LIVE", "VOD", "SERIES"
    val streamId: Int = 0,
    var positionMs: Long = 0L,
    var durationMs: Long = 0L,
    val season: Int = 1,
    val episodeNum: Int = 1,
    var timestamp: Long = System.currentTimeMillis()
) : Serializable {
    val progressPercent: Int
        get() = if (durationMs > 0) ((positionMs * 100) / durationMs).toInt().coerceIn(0, 100) else 0
}

class HistoryManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("alex_iptv_history", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun saveProgress(
        id: String,
        title: String,
        streamUrl: String,
        posterUrl: String?,
        type: String,
        streamId: Int,
        positionMs: Long,
        durationMs: Long,
        season: Int = 1,
        episodeNum: Int = 1
    ) {
        if (positionMs < 5000 && type != "LIVE") return // Unter 5s nicht als Verlauf speichern

        val list = getHistory().toMutableList()
        list.removeAll { it.id == id || (it.streamUrl == streamUrl && streamUrl.isNotEmpty()) }

        // Wenn fast zu Ende geschaut (>92%), aus Weiterschauen entfernen
        if (durationMs > 0 && (positionMs.toFloat() / durationMs.toFloat()) > 0.92f) {
            saveList(list)
            return
        }

        val item = HistoryItem(
            id = id,
            title = title,
            streamUrl = streamUrl,
            posterUrl = posterUrl,
            type = type,
            streamId = streamId,
            positionMs = positionMs,
            durationMs = durationMs,
            season = season,
            episodeNum = episodeNum,
            timestamp = System.currentTimeMillis()
        )
        list.add(0, item) // Neuestes nach oben

        // Maximal 50 Einträge im Verlauf halten
        val trimmed = if (list.size > 50) list.take(50) else list
        saveList(trimmed)
    }

    fun getHistory(): List<HistoryItem> {
        val json = prefs.getString("history_items", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<HistoryItem>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getResumePosition(streamUrl: String): Long {
        val item = getHistory().firstOrNull { it.streamUrl == streamUrl }
        return item?.positionMs ?: 0L
    }

    fun clearHistory() {
        prefs.edit().remove("history_items").apply()
    }

    private fun saveList(list: List<HistoryItem>) {
        val json = gson.toJson(list)
        prefs.edit().putString("history_items", json).apply()
    }
}
