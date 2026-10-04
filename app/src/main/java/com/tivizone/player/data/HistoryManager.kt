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
    val settings: Map<String, String>? = null,
    val deletedHistoryIds: List<String>? = null,
    val deletedChannelIds: List<Int>? = null
)

class HistoryManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("tivizone_history", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val cloudSyncUrl = "https://iptvproxy-x8rs.onrender.com/api/sync"
    private var lastCloudUploadTimestamp = 0L
    var hasSuccessfullySyncedOnce = false
        private set

    private val syncLock = Any()
    @Volatile
    private var isSyncing = false
    private val syncCallbacks = mutableListOf<() -> Unit>()

    init {
        migrateOldHistoryIfNeeded()
        cleanLocalHistory()
    }

    private fun cleanLocalHistory() {
        try {
            val cleanList = getHistory()
            saveList(cleanList)
            val cleanChans = getRecentLiveChannels()
            prefs.edit().putString("recent_live_channels", gson.toJson(cleanChans)).apply()
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun migrateOldHistoryIfNeeded() {
        try {
            if (getHistory().isEmpty() && getRecentLiveChannels().isEmpty()) {
                val old = context.getSharedPreferences("alex_iptv_history", Context.MODE_PRIVATE)
                if (old.all.isNotEmpty()) {
                    val edit = prefs.edit()
                    old.all.forEach { (k, v) ->
                        if (v is String && !prefs.contains(k)) {
                            edit.putString(k, v)
                        }
                    }
                    edit.apply()
                }
            }
        } catch (e: Exception) {
            // Ignore migration failure
        }
    }

    fun isAdultContent(title: String?, streamUrl: String? = null, categoryId: String? = null): Boolean {
        if (categoryId == "16" || categoryId == "MAIN_PRIVAT" || categoryId == "ADULT_MOVIES") return true
        val url = streamUrl?.lowercase() ?: ""
        if (url.contains("/adult/") || url.contains("category_id=16") || url.contains("/xxx/")) return true
        val t = title?.uppercase() ?: ""
        if (t.contains("ADULT SWIM") || t.contains("ADULT-SWIM") || t.contains("ADULT_SWIM")) return false
        return t.contains("FOR ADULTS") || t.contains("ADULT") || t.contains("XXX") ||
                t.contains("18+") || t.contains("PORN") || t.contains("EROTIC") ||
                t.contains("SEX") || t.contains("REDLIGHT") || t.contains("HUSTLER") ||
                t.contains("BRAZZERS") || t.contains("PENTHOUSE") || t.contains("PLAYBOY")
    }

    fun isTestItem(title: String?): Boolean {
        val t = title?.uppercase() ?: return false
        return t.contains("TEST_SZ") || t.contains("SZ_TEST") || t.contains("SZ_UPLOAD") ||
                t.contains("TEST_UPLOAD") || t.contains("UPLOAD_TEST")
    }

    private fun getDeletedHistoryIds(): MutableSet<String> {
        val set = prefs.getStringSet("deleted_history_ids", null) ?: emptySet()
        return HashSet(set)
    }

    private fun addDeletedHistoryId(id: String) {
        if (id.isEmpty()) return
        val set = getDeletedHistoryIds()
        set.add(id)
        prefs.edit().putStringSet("deleted_history_ids", set).apply()
    }

    private fun getDeletedChannelIds(): MutableSet<Int> {
        val json = prefs.getString("deleted_channel_ids", null) ?: return mutableSetOf()
        return try {
            val type = object : TypeToken<MutableSet<Int>>() {}.type
            gson.fromJson(json, type) ?: mutableSetOf()
        } catch (e: Exception) {
            mutableSetOf()
        }
    }

    private fun addDeletedChannelId(channelId: Int) {
        if (channelId <= 0) return
        val set = getDeletedChannelIds()
        set.add(channelId)
        prefs.edit().putString("deleted_channel_ids", gson.toJson(set)).apply()
    }

    fun deleteHistoryItem(item: HistoryItem) {
        addDeletedHistoryId(item.id)
        if (item.seriesId > 0) {
            addDeletedHistoryId("series_${item.seriesId}")
        }
        val list = getHistory().toMutableList()
        list.removeAll {
            it.id == item.id ||
            (item.seriesId > 0 && it.seriesId == item.seriesId) ||
            (it.streamUrl.isNotEmpty() && it.streamUrl == item.streamUrl)
        }
        saveList(list)
        uploadToCloud()
    }

    fun deleteRecentChannel(stream: LiveStream) {
        addDeletedChannelId(stream.streamId)
        val list = getRecentLiveChannels().toMutableList()
        list.removeAll { it.streamId == stream.streamId }
        val json = gson.toJson(list)
        prefs.edit().putString("recent_live_channels", json).apply()
        uploadToCloud()
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
        if (isAdultContent(title, streamUrl) || isTestItem(title)) return
        if (positionMs < 5000 && durationMs <= 0) return

        // Falls dieser Titel zuvor als gelöscht markiert war, Reaktivierung:
        val delSet = getDeletedHistoryIds()
        var changedDel = false
        if (delSet.remove(id)) changedDel = true
        if (seriesId > 0 && delSet.remove("series_$seriesId")) changedDel = true
        if (changedDel) {
            prefs.edit().putStringSet("deleted_history_ids", delSet).apply()
        }

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

        if (forceCloudUpload) {
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
            val deleted = getDeletedChannelIds()
            raw.filterNot { isAdultContent(it.name, categoryId = it.categoryId) || deleted.contains(it.streamId) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getHistory(): List<HistoryItem> {
        val json = prefs.getString("history_items", null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<HistoryItem>>() {}.type
            val raw = gson.fromJson<List<HistoryItem>>(json, type) ?: emptyList()
            val deleted = getDeletedHistoryIds()
            raw.filterNot { 
                isAdultContent(it.title, it.streamUrl) || 
                isTestItem(it.title) || 
                deleted.contains(it.id) || 
                (it.seriesId > 0 && deleted.contains("series_${it.seriesId}"))
            }
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
        val shouldStart: Boolean
        synchronized(syncLock) {
            if (onComplete != null) {
                syncCallbacks.add(onComplete)
            }
            if (isSyncing) {
                // Ein Sync läuft bereits, Callback wurde in die Warteschlange eingetragen
                return
            }
            isSyncing = true
            shouldStart = true
        }
        if (shouldStart) {
            startSyncJob(user)
        }
    }

    private fun startSyncJob(user: String) {
        prefs.edit().putString("sync_username", user).apply()
        CoroutineScope(Dispatchers.IO).launch {
            var downloadSuccess = false
            for (attempt in 1..3) {
                try {
                    // 1. Zuerst aktuelle Cloud-Daten abrufen
                    com.tivizone.player.util.AppLogger.i("HistoryManager", "syncWithCloud: Starte Download für User '$user'...")
                    val req = Request.Builder()
                        .url("$cloudSyncUrl/load?user=$user")
                        .get()
                        .build()
                    httpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            throw java.io.IOException("HTTP error ${resp.code}: ${resp.message}")
                        }
                        hasSuccessfullySyncedOnce = true
                        val body = resp.body?.string()
                        if (!body.isNullOrEmpty()) {
                            val payload = gson.fromJson(body, CloudSyncPayload::class.java)
                            com.tivizone.player.util.AppLogger.i("HistoryManager", "syncWithCloud: ${payload?.history?.size ?: 0} Einträge aus Cloud geladen.")

                            // Gelöschte Einträge aus Cloud mergen
                            payload?.deletedHistoryIds?.forEach { addDeletedHistoryId(it) }
                            payload?.deletedChannelIds?.forEach { addDeletedChannelId(it) }

                            val activeDeletedHistory = getDeletedHistoryIds()
                            val activeDeletedChannels = getDeletedChannelIds()

                            // Historie intelligent mergen: Neuere Zeitstempel überschreiben ältere lokale Stände
                            val local = getHistory().toMutableList()
                            local.removeAll { 
                                activeDeletedHistory.contains(it.id) || 
                                (it.seriesId > 0 && activeDeletedHistory.contains("series_${it.seriesId}")) 
                            }
                            var changed = false
                            payload?.history
                                ?.filterNot { 
                                    isAdultContent(it.title, it.streamUrl) || 
                                    isTestItem(it.title) || 
                                    activeDeletedHistory.contains(it.id) || 
                                    (it.seriesId > 0 && activeDeletedHistory.contains("series_${it.seriesId}")) 
                                }
                                ?.forEach { cloudItem ->
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

                            // Zuletzt gesehene TV-Sender mergen (ohne Adult & ohne gelöschte)
                            if (!payload?.recentChannels.isNullOrEmpty()) {
                                val localChans = getRecentLiveChannels().toMutableList()
                                val mergedChans = mutableListOf<LiveStream>()
                                payload?.recentChannels
                                    ?.filterNot { isAdultContent(it.name, categoryId = it.categoryId) || activeDeletedChannels.contains(it.streamId) }
                                    ?.forEach { c ->
                                        if (mergedChans.none { it.streamId == c.streamId }) {
                                            mergedChans.add(c)
                                        }
                                    }
                                localChans
                                    .filterNot { isAdultContent(it.name, categoryId = it.categoryId) || activeDeletedChannels.contains(it.streamId) }
                                    .forEach { c ->
                                        if (mergedChans.none { it.streamId == c.streamId }) {
                                            mergedChans.add(c)
                                        }
                                    }
                                val trimmed = if (mergedChans.size > 20) mergedChans.take(20) else mergedChans
                                val json = gson.toJson(trimmed)
                                prefs.edit().putString("recent_live_channels", json).apply()
                            }

                            // Suchverlauf mergen
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
                    downloadSuccess = true
                    break
                } catch (e: Exception) {
                    com.tivizone.player.util.AppLogger.e("HistoryManager", "syncWithCloud attempt $attempt error: ${e.message}", e)
                    if (attempt < 3) {
                        delay(attempt * 3000L)
                    }
                }
            }

            try {
                if (downloadSuccess) {
                    // 2. Nach erfolgreichem Abgleich lokale Daten nach oben synchronisieren (im selben IO-Thread)
                    uploadToCloudInternal(user)
                }
            } catch (e: Exception) {
                com.tivizone.player.util.AppLogger.e("HistoryManager", "upload after sync error: ${e.message}", e)
            } finally {
                val callbacks: List<() -> Unit>
                synchronized(syncLock) {
                    isSyncing = false
                    callbacks = syncCallbacks.toList()
                    syncCallbacks.clear()
                }
                callbacks.forEach { cb ->
                    try {
                        cb.invoke()
                    } catch (t: Throwable) {
                        // Ignore
                    }
                }
            }
        }
    }

    fun uploadToCloud() {
        val defaultUser = try {
            XtreamClient(context).username
        } catch (e: Exception) {
            "fb5940d0a3a0"
        }
        val user = prefs.getString("sync_username", defaultUser) ?: defaultUser
        CoroutineScope(Dispatchers.IO).launch {
            uploadToCloudInternal(user)
        }
    }

    private fun uploadToCloudInternal(user: String) {
        try {
            val list = getHistory()
            val channels = getRecentLiveChannels()
            val search = getAllSearchHistory()
            val delHistory = getDeletedHistoryIds().toList()
            val delChannels = getDeletedChannelIds().toList()
            if (list.isEmpty() && channels.isEmpty() && search.values.all { it.isEmpty() } && delHistory.isEmpty() && delChannels.isEmpty()) {
                com.tivizone.player.util.AppLogger.i("HistoryManager", "uploadToCloud: Keine Daten zum Hochladen.")
                return
            }

            com.tivizone.player.util.AppLogger.i("HistoryManager", "uploadToCloud: Sende ${list.size} Einträge für User '$user'...")
            val payload = CloudSyncPayload(
                user = user,
                history = list,
                recentChannels = channels,
                searchHistory = search,
                deletedHistoryIds = delHistory,
                deletedChannelIds = delChannels
            )
            val json = gson.toJson(payload)
            val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder()
                .url("$cloudSyncUrl/save")
                .post(body)
                .build()

            val resp = httpClient.newCall(req).execute()
            val code = resp.code
            val respBody = resp.body?.string() ?: ""
            resp.close()

            if (resp.isSuccessful) {
                com.tivizone.player.util.AppLogger.i("HistoryManager", "uploadToCloud: ERFOLGREICH (HTTP $code)")
            } else {
                com.tivizone.player.util.AppLogger.e("HistoryManager", "uploadToCloud: FEHLGESCHLAGEN (HTTP $code): $respBody")
            }
        } catch (e: Exception) {
            com.tivizone.player.util.AppLogger.e("HistoryManager", "uploadToCloud Fehler: ${e.message}", e)
        }
    }
}
