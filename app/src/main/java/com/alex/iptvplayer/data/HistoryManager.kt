package com.alex.iptvplayer.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.Serializable
import java.util.concurrent.TimeUnit

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

data class CloudSyncPayload(
    val user: String,
    val history: List<HistoryItem>,
    val recentChannels: List<LiveStream>? = null,
    val settings: Map<String, String>? = null
)

class HistoryManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("alex_iptv_history", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    private val cloudSyncUrl = "https://iptvproxy-x8rs.onrender.com/api/sync"

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
        if (positionMs < 5000 && type != "LIVE") return

        val list = getHistory().toMutableList()
        list.removeAll { it.id == id || (it.streamUrl == streamUrl && streamUrl.isNotEmpty()) }

        if (durationMs > 0 && (positionMs.toFloat() / durationMs.toFloat()) > 0.92f) {
            saveList(list)
            uploadToCloud()
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
        list.add(0, item)

        val trimmed = if (list.size > 50) list.take(50) else list
        saveList(trimmed)
        uploadToCloud()
    }

    fun saveLiveChannel(stream: LiveStream) {
        val list = getRecentLiveChannels().toMutableList()
        list.removeAll { it.streamId == stream.streamId }
        list.add(0, stream)
        val trimmed = if (list.size > 10) list.take(10) else list
        val json = gson.toJson(trimmed)
        prefs.edit().putString("recent_live_channels", json).apply()
        uploadToCloud()
    }

    fun getRecentLiveChannels(): List<LiveStream> {
        val json = prefs.getString("recent_live_channels", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<LiveStream>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
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

    private fun saveList(list: List<HistoryItem>) {
        val json = gson.toJson(list)
        prefs.edit().putString("history_items", json).apply()
    }

    // Bidirektionale Synchronisation mit der Cloud
    fun syncWithCloud(user: String, onComplete: (() -> Unit)? = null) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Zuerst aktuelle Cloud-Daten abrufen
                val req = Request.Builder()
                    .url("$cloudSyncUrl/load?user=$user")
                    .get()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string()
                        if (!body.isNullOrEmpty()) {
                            val payload = gson.fromJson(body, CloudSyncPayload::class.java)

                            // Historie mergen
                            val local = getHistory().toMutableList()
                            var changed = false
                            payload?.history?.forEach { cloudItem ->
                                if (local.none { it.id == cloudItem.id }) {
                                    local.add(cloudItem)
                                    changed = true
                                }
                            }
                            if (changed) {
                                local.sortByDescending { it.timestamp }
                                saveList(local.take(50))
                            }

                            // Zuletzt gesehene TV-Sender mergen
                            if (!payload?.recentChannels.isNullOrEmpty()) {
                                val localChans = getRecentLiveChannels().toMutableList()
                                var chanChanged = false
                                payload?.recentChannels?.forEach { c ->
                                    if (localChans.none { it.streamId == c.streamId }) {
                                        localChans.add(c)
                                        chanChanged = true
                                    }
                                }
                                if (chanChanged) {
                                    val trimmed = if (localChans.size > 10) localChans.take(10) else localChans
                                    prefs.edit().putString("recent_live_channels", gson.toJson(trimmed)).apply()
                                }
                            }
                        }
                    }
                }

                // 2. Lokale Daten nach oben pushen
                uploadToCloudDirect(user)
            } catch (e: Exception) {
                // Offline Fallback
            } finally {
                onComplete?.invoke()
            }
        }
    }

    fun uploadToCloud() {
        uploadToCloudDirect("fb5940d0a3a0")
    }

    private fun uploadToCloudDirect(user: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val list = getHistory()
                val channels = getRecentLiveChannels()
                if (list.isEmpty() && channels.isEmpty()) return@launch

                val payload = CloudSyncPayload(
                    user = user,
                    history = list,
                    recentChannels = channels
                )
                val json = gson.toJson(payload)
                val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
                val req = Request.Builder()
                    .url("$cloudSyncUrl/save")
                    .post(body)
                    .build()
                httpClient.newCall(req).execute().close()
            } catch (e: Exception) {
                // Silent
            }
        }
    }
}
