package com.tivizone.player.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    var timestamp: Long = System.currentTimeMillis(),
    val seriesId: Int = 0
) : Serializable {
    val progressPercent: Int
        get() = if (durationMs > 0) ((positionMs * 100) / durationMs).toInt().coerceIn(0, 100) else 0
}

data class CloudSyncPayload(
    val user: String,
    val history: List<HistoryItem>,
    val recentChannels: List<LiveStream>? = null,
    val searchHistory: Map<String, List<String>>? = null,
    val settings: Map<String, String>? = null
)

class HistoryManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("tivizone_history", Context.MODE_PRIVATE).apply {
            if (all.isEmpty()) {
                val old = context.getSharedPreferences("alex_iptv_history", Context.MODE_PRIVATE)
                if (old.all.isNotEmpty()) {
                    val edit = edit()
                    old.all.forEach { (k, v) ->
                        if (v is String) edit.putString(k, v)
                    }
                    edit.apply()
                }
            }
        }
    private val gson = Gson()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(35, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()

    private val cloudSyncUrl = "https://iptvproxy-x8rs.onrender.com/api/sync"
    private var lastCloudUploadTimestamp = 0L
    var hasSuccessfullySyncedOnce = false
        private set

    fun isAdultContent(title: String?, streamUrl: String? = null, categoryId: String? = null): Boolean {
        if (categoryId == "16" || categoryId == "MAIN_PRIVAT" || categoryId == "ADULT_MOVIES") return true
        val url = streamUrl?.lowercase() ?: ""
        if (url.contains("/adult/") || url.contains("category_id=16")) return true
        val t = title?.uppercase() ?: ""
        if (t.contains("ADULT SWIM") || t.contains("ADULT-SWIM") || t.contains("ADULT_SWIM")) return false
        return t.contains("FOR ADULTS") || t.contains("ADULT") || t.contains("XXX") ||
                t.contains("18+") || t.contains("PORN") || t.contains("EROTIC")
    }

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
        episodeNum: Int = 1,
        seriesId: Int = 0,
        forceCloudUpload: Boolean = false
    ) {
        if (type == "LIVE" || streamUrl.contains("/live/")) return
        if (isAdultContent(title, streamUrl)) return
        if (positionMs < 5000 && durationMs <= 0) return

        val list = getHistory().toMutableList()
        list.removeAll { 
            it.id == id || 
            (it.streamUrl.isNotEmpty() && it.streamUrl == streamUrl) ||
            (seriesId > 0 && it.seriesId == seriesId && it.season == season && it.episodeNum == episodeNum)
        }

        // Wenn >= 90% geschaut wurde, gilt der Titel als vollständig gesehen (100% Fortschrittsbalken)
        val isCompleted = durationMs > 0 && (positionMs.toFloat() / durationMs.toFloat()) >= 0.90f
        val finalPositionMs = if (isCompleted) durationMs else positionMs

        val item = HistoryItem(
            id = id,
            title = title,
            streamUrl = streamUrl,
            posterUrl = posterUrl,
            type = type,
            streamId = streamId,
            positionMs = finalPositionMs,
            durationMs = durationMs,
            season = season,
            episodeNum = episodeNum,
            timestamp = System.currentTimeMillis(),
            seriesId = seriesId
        )
        list.add(0, item)

        val trimmed = if (list.size > 2000) list.take(2000) else list
        saveList(trimmed)

        val now = System.currentTimeMillis()
        if (forceCloudUpload || (now - lastCloudUploadTimestamp >= 60_000L)) {
            lastCloudUploadTimestamp = now
            uploadToCloud()
        }
    }

    fun saveLiveChannel(stream: LiveStream) {
        if (isAdultContent(stream.name, categoryId = stream.categoryId)) return
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
            val raw = gson.fromJson<List<LiveStream>>(json, type) ?: emptyList()
            raw.filterNot { isAdultContent(it.name, categoryId = it.categoryId) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getHistory(): List<HistoryItem> {
        val json = prefs.getString("history_items", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<HistoryItem>>() {}.type
            val raw = gson.fromJson<List<HistoryItem>>(json, type) ?: emptyList()
            raw.filterNot { isAdultContent(it.title, it.streamUrl) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getResumePosition(streamUrl: String, streamId: Int = -1): Long {
        if (streamUrl.contains("/live/")) return 0L
        val item = getHistory().firstOrNull { 
            (streamId > 0 && it.streamId == streamId) || 
            (streamUrl.isNotEmpty() && it.streamUrl == streamUrl) 
        }
        if (item?.type == "LIVE") return 0L
        // Wenn bereits fertig geschaut (>= 90%), von vorne (0s) starten
        if (item != null && item.durationMs > 0 && (item.positionMs.toFloat() / item.durationMs.toFloat()) >= 0.90f) {
            return 0L
        }
        return item?.positionMs ?: 0L
    }

    private fun saveList(list: List<HistoryItem>) {
        val json = gson.toJson(list)
        prefs.edit().putString("history_items", json).apply()
    }

    fun getSearchHistory(type: String): List<String> {
        val json = prefs.getString("search_history_$type", null) ?: return emptyList()
        return try {
            val listType = object : TypeToken<List<String>>() {}.type
            gson.fromJson(json, listType) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getAllSearchHistory(): Map<String, List<String>> {
        return mapOf(
            "LIVE" to getSearchHistory("LIVE"),
            "VOD" to getSearchHistory("VOD"),
            "SERIES" to getSearchHistory("SERIES")
        )
    }

    fun addSearchQuery(type: String, query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        val list = getSearchHistory(type).toMutableList()
        list.remove(q)
        list.add(0, q)
        val trimmed = if (list.size > 50) list.take(50) else list
        prefs.edit().putString("search_history_$type", gson.toJson(trimmed)).apply()
        uploadToCloud()
    }

    // Bidirektionale Synchronisation mit der Cloud
    fun syncWithCloud(user: String, onComplete: (() -> Unit)? = null) {
        syncWithCloudInternal(user, isRetry = false, onComplete)
    }

    private fun syncWithCloudInternal(user: String, isRetry: Boolean = false, onComplete: (() -> Unit)? = null) {
        prefs.edit().putString("sync_username", user).apply()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 1. Zuerst aktuelle Cloud-Daten abrufen
                val req = Request.Builder()
                    .url("$cloudSyncUrl/load?user=$user")
                    .get()
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        hasSuccessfullySyncedOnce = true
                        val body = resp.body?.string()
                        if (!body.isNullOrEmpty()) {
                            val payload = gson.fromJson(body, CloudSyncPayload::class.java)

                            // Historie intelligent mergen: Neuere Zeitstempel überschreiben ältere lokale Stände
                            // Filtert gleichzeitig eventuelle alte Adult-Inhalte aus!
                            val local = getHistory().toMutableList()
                            var changed = false
                            payload?.history?.filterNot { isAdultContent(it.title, it.streamUrl) }?.forEach { cloudItem ->
                                val existingIndex = local.indexOfFirst {
                                    it.id == cloudItem.id ||
                                    (it.seriesId > 0 && it.seriesId == cloudItem.seriesId && it.season == cloudItem.season && it.episodeNum == cloudItem.episodeNum) ||
                                    (it.streamUrl.isNotEmpty() && it.streamUrl == cloudItem.streamUrl)
                                }
                                if (existingIndex >= 0) {
                                    val localItem = local[existingIndex]
                                    val cloudCompleted = cloudItem.durationMs > 0 && (cloudItem.positionMs.toFloat() / cloudItem.durationMs.toFloat()) >= 0.90f
                                    val localCompleted = localItem.durationMs > 0 && (localItem.positionMs.toFloat() / localItem.durationMs.toFloat()) >= 0.90f
                                    if (cloudItem.timestamp > localItem.timestamp || (cloudCompleted && !localCompleted)) {
                                        local[existingIndex] = cloudItem
                                        changed = true
                                    }
                                } else {
                                    local.add(cloudItem)
                                    changed = true
                                }
                            }
                            if (changed) {
                                local.sortByDescending { it.timestamp }
                                val trimmed = if (local.size > 2000) local.take(2000) else local
                                saveList(trimmed)
                            }

                            // Zuletzt gesehene TV-Sender mergen (ohne Adult)
                            if (!payload?.recentChannels.isNullOrEmpty()) {
                                val localChans = getRecentLiveChannels().toMutableList()
                                val mergedChans = mutableListOf<LiveStream>()
                                // Erst die aus der Cloud
                                payload?.recentChannels?.filterNot { isAdultContent(it.name, categoryId = it.categoryId) }?.forEach { c ->
                                    if (mergedChans.none { it.streamId == c.streamId }) {
                                        mergedChans.add(c)
                                    }
                                }
                                // Dann die lokalen ergänzen
                                localChans.filterNot { isAdultContent(it.name, categoryId = it.categoryId) }.forEach { c ->
                                    if (mergedChans.none { it.streamId == c.streamId }) {
                                        mergedChans.add(c)
                                    }
                                }
                                val trimmed = if (mergedChans.size > 20) mergedChans.take(20) else mergedChans
                                val json = gson.toJson(trimmed)
                                prefs.edit().putString("recent_live_channels", json).apply()
                            }

                            // 3. Suchverlauf mergen (Live, VOD, SERIES)
                            payload?.searchHistory?.forEach { (type, cloudQueries) ->
                                val localQueries = getSearchHistory(type).toMutableList()
                                var searchChanged = false
                                cloudQueries.reversed().forEach { q ->
                                    if (!localQueries.contains(q)) {
                                        localQueries.add(0, q)
                                        searchChanged = true
                                    }
                                }
                                if (searchChanged) {
                                    val trimmed = if (localQueries.size > 50) localQueries.take(50) else localQueries
                                    prefs.edit().putString("search_history_$type", gson.toJson(trimmed)).apply()
                                }
                            }
                        }
                    }
                }

                // 2. Lokale Daten nach oben pushen
                uploadToCloudDirect(user)
            } catch (e: Exception) {
                com.tivizone.player.util.AppLogger.e("HistoryManager", "syncWithCloud error: ${e.message}", e)
                // Retry einmalig nach 8 Sekunden (falls Render-Server gerade hochfährt)
                if (!isRetry) {
                    CoroutineScope(Dispatchers.IO).launch {
                        delay(8000)
                        syncWithCloudInternal(user, isRetry = true, onComplete)
                    }
                    return@launch
                }
            } finally {
                onComplete?.invoke()
            }
        }
    }

    fun uploadToCloud() {
        val user = prefs.getString("sync_username", "fb5940d0a3a0") ?: "fb5940d0a3a0"
        uploadToCloudDirect(user)
    }

    private fun uploadToCloudDirect(user: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val list = getHistory()
                val channels = getRecentLiveChannels()
                val search = getAllSearchHistory()
                if (list.isEmpty() && channels.isEmpty() && search.values.all { it.isEmpty() }) return@launch

                val payload = CloudSyncPayload(
                    user = user,
                    history = list,
                    recentChannels = channels,
                    searchHistory = search
                )
                val json = gson.toJson(payload)
                val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
                val req = Request.Builder()
                    .url("$cloudSyncUrl/save")
                    .post(body)
                    .build()
                httpClient.newCall(req).execute().close()
            } catch (e: Exception) {
                com.tivizone.player.util.AppLogger.e("HistoryManager", "uploadToCloud error: ${e.message}", e)
            }
        }
    }
}
