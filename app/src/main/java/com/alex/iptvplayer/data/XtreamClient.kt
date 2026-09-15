package com.alex.iptvplayer.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.JsonReader
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class LangFilter {
    AUTO_DE_RU_ADULT, // Standard
    DE,               // Nur Deutsch
    RU,               // Nur Russisch
    ALL               // Alle (DE, RU, Adult)
}

class XtreamClient(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("iptv_settings", Context.MODE_PRIVATE)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    // Global in-memory cache for instant global "enthält" search
    private var cachedAllMovies: List<VodStream>? = null
    private var cachedAllSeries: List<SeriesItem>? = null

    // Zugangsdaten vom Anbieter (cf.rilox.sbs)
    var serverUrl: String
        get() = prefs.getString("server_url", "http://cf.rilox.sbs")!!.trimEnd('/')
        set(value) = prefs.edit().putString("server_url", value.trimEnd('/')).apply()

    var username: String
        get() = prefs.getString("username", "fb5940d0a3a0")!!
        set(value) = prefs.edit().putString("username", value).apply()

    var password: String
        get() = prefs.getString("password", "b1d99e5206")!!
        set(value) = prefs.edit().putString("password", value).apply()

    private fun buildApiUrl(action: String, extraParams: String = ""): String {
        return "$serverUrl/player_api.php?username=$username&password=$password&action=$action$extraParams"
    }

    fun getLiveStreamUrl(streamId: Int): String {
        return "$serverUrl/live/$username/$password/$streamId.ts"
    }

    fun getVodStreamUrl(streamId: Int, extension: String = "mp4"): String {
        return "$serverUrl/movie/$username/$password/$streamId.$extension"
    }

    fun getSeriesStreamUrl(streamId: String, extension: String = "mp4"): String {
        return "$serverUrl/series/$username/$password/$streamId.$extension"
    }

    // Filter: Nur Deutsch, Russisch und Porno/Adult (unter Alle)
    private fun filterCategories(categories: List<Category>, filter: LangFilter): List<Category> {
        val baseAllowed = categories.filter { isGerman(it.name) || isRussian(it.name) || isAdult(it.name) }

        return when (filter) {
            LangFilter.DE -> baseAllowed.filter { isGerman(it.name) }
            LangFilter.RU -> baseAllowed.filter { isRussian(it.name) }
            LangFilter.AUTO_DE_RU_ADULT, LangFilter.ALL -> baseAllowed
        }
    }

    private fun isGerman(name: String): Boolean {
        val u = name.uppercase()
        return u.startsWith("DE|") || u.startsWith("DE:") || u.startsWith("DE -") || u.startsWith("DE ") ||
                u.startsWith("AT|") || u.startsWith("AT:") || u.startsWith("AT -") ||
                u.startsWith("CH|") || u.startsWith("CH:") || u.startsWith("CH -") ||
                u.startsWith("GERMANY") || u.contains("GERMAN") || u.contains("DEUTSCH") ||
                u.startsWith("JOYN") || u.startsWith("SKY") || u.contains("MAGENTA") || u.contains("DAZN")
    }

    fun isRussianMedia(name: String, categoryId: String? = null): Boolean {
        if (categoryId in listOf("81", "1664", "1663", "1105", "1046")) return true
        val u = name.uppercase()
        // Wenn es explizit ein deutsches Medium ist, ist es nicht russisch (verhindert Borussia)
        if (isGermanMedia(name)) return false

        if (u.startsWith("RU|") || u.startsWith("RU:") || u.startsWith("RU -") || u.startsWith("RU ") ||
            u.startsWith("4K-RU") || u.startsWith("4K RU") ||
            u.contains("RUSSIAN") || u.contains("RUSSAIN")) {
            return true
        }
        if (Regex("\\bRUSSIA\\b").containsMatchIn(u)) {
            return true
        }
        for (char in name) {
            val block = Character.UnicodeBlock.of(char)
            if (block == Character.UnicodeBlock.CYRILLIC ||
                block == Character.UnicodeBlock.CYRILLIC_SUPPLEMENTARY ||
                block == Character.UnicodeBlock.CYRILLIC_EXTENDED_A ||
                block == Character.UnicodeBlock.CYRILLIC_EXTENDED_B) {
                return true
            }
        }
        return false
    }

    private fun isRussian(name: String): Boolean = isRussianMedia(name)

    private fun isAdult(name: String): Boolean {
        val u = name.uppercase()
        if (u.contains("ADULT SWIM") || u.contains("ADULT-SWIM") || u.contains("ADULT_SWIM")) return false
        return u.contains("FOR ADULTS") || u.contains("ADULT") || u.contains("XXX") ||
                u.contains("18+") || u.contains("PORN") || u.contains("EROTIC")
    }

    // 1. Live TV
    suspend fun getLiveCategories(): List<Category> = withContext(Dispatchers.IO) {
        val url = buildApiUrl("get_live_categories")
        val json = executeGet(url)
        val type = object : TypeToken<List<Category>>() {}.type
        val raw: List<Category> = gson.fromJson(json, type) ?: emptyList()
        filterCategories(raw, LangFilter.AUTO_DE_RU_ADULT)
    }

    suspend fun getLiveStreams(categoryId: String? = null): List<LiveStream> = withContext(Dispatchers.IO) {
        if (categoryId == null || categoryId == "ALL_CHANNELS") {
            return@withContext getAllLiveStreams()
        }
        val extra = "&category_id=$categoryId"
        val url = buildApiUrl("get_live_streams", extra)
        val json = executeGet(url)
        val type = object : TypeToken<List<LiveStream>>() {}.type
        gson.fromJson(json, type) ?: emptyList()
    }

    private var cachedAllLiveStreams: List<LiveStream>? = null

    suspend fun getAllLiveStreams(): List<LiveStream> = withContext(Dispatchers.IO) {
        if (!cachedAllLiveStreams.isNullOrEmpty()) return@withContext cachedAllLiveStreams!!
        val url = buildApiUrl("get_live_streams")
        val json = executeGet(url)
        val type = object : TypeToken<List<LiveStream>>() {}.type
        val list: List<LiveStream> = gson.fromJson(json, type) ?: emptyList()
        cachedAllLiveStreams = list
        list
    }

    // EPG: Vergangene Daten werden komplett ignoriert (nur aktuelle "Jetzt" und zukünftige bis +12h)
    suspend fun getEpg(streamId: Int): List<EpgProgram> = withContext(Dispatchers.IO) {
        try {
            val url = buildApiUrl("get_simple_data_table", "&stream_id=$streamId")
            val json = executeGet(url)
            val resp = gson.fromJson(json, EpgResponse::class.java)
            val list = mutableListOf<EpgProgram>()
            val nowMs = System.currentTimeMillis()
            val windowEndMs = nowMs + (12 * 3600 * 1000L)  // +12 Stunden
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

            resp?.listings?.forEach { raw ->
                val title = decodeBase64(raw.title)
                val desc = decodeBase64(raw.description)
                val startTimeMs = try { raw.start?.let { sdf.parse(it)?.time } } catch (e: Exception) { null }
                val endTimeMs = try { raw.end?.let { sdf.parse(it)?.time } } catch (e: Exception) { null }

                // Vergangene Daten komplett ignorieren
                val inWindow = if (endTimeMs != null) {
                    endTimeMs >= nowMs && (startTimeMs == null || startTimeMs <= windowEndMs)
                } else {
                    raw.nowPlaying == 1
                }

                if (inWindow && title.isNotEmpty()) {
                    val start = raw.start?.substringAfter(" ")?.take(5) ?: ""
                    val end = raw.end?.substringAfter(" ")?.take(5) ?: ""
                    val isNow = raw.nowPlaying == 1 || (startTimeMs != null && endTimeMs != null && nowMs in startTimeMs..endTimeMs)
                    list.add(EpgProgram(title, desc, start, end, isNow))
                }
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun decodeBase64(str: String?): String {
        if (str.isNullOrEmpty()) return ""
        return try {
            val decodedBytes = Base64.decode(str.trim(), Base64.DEFAULT)
            String(decodedBytes, Charsets.UTF_8).trim()
        } catch (e: Exception) {
            str
        }
    }

    // 2. VOD Filme
    suspend fun getVodCategories(): List<Category> = withContext(Dispatchers.IO) {
        val url = buildApiUrl("get_vod_categories")
        val json = executeGet(url)
        val type = object : TypeToken<List<Category>>() {}.type
        val raw: List<Category> = gson.fromJson(json, type) ?: emptyList()
        val filtered = filterCategories(raw, LangFilter.AUTO_DE_RU_ADULT).toMutableList()
        filtered.add(0, Category(id = "ALL_MOVIES", name = "✨ Alle Filme"))
        filtered
    }

    suspend fun getVodStreams(categoryId: String? = null): List<VodStream> = withContext(Dispatchers.IO) {
        if (categoryId == "ALL_MOVIES" || categoryId == null) {
            return@withContext getAllVodStreams()
        }
        val url = buildApiUrl("get_vod_streams", "&category_id=$categoryId")
        val json = executeGet(url)
        val type = object : TypeToken<List<VodStream>>() {}.type
        gson.fromJson(json, type) ?: emptyList()
    }

    suspend fun getAllVodStreams(): List<VodStream> = withContext(Dispatchers.IO) {
        if (!cachedAllMovies.isNullOrEmpty()) return@withContext cachedAllMovies!!
        val url = buildApiUrl("get_vod_streams")
        val json = executeGet(url)
        val type = object : TypeToken<List<VodStream>>() {}.type
        val list: List<VodStream> = gson.fromJson(json, type) ?: emptyList()
        cachedAllMovies = list
        list
    }

    // 3. Serien
    suspend fun getSeriesCategories(): List<Category> = withContext(Dispatchers.IO) {
        val url = buildApiUrl("get_series_categories")
        val json = executeGet(url)
        val type = object : TypeToken<List<Category>>() {}.type
        val raw: List<Category> = gson.fromJson(json, type) ?: emptyList()
        val filtered = filterCategories(raw, LangFilter.AUTO_DE_RU_ADULT).toMutableList()
        filtered.add(0, Category(id = "ALL_SERIES", name = "✨ Alle Serien"))
        filtered
    }

    suspend fun getSeries(categoryId: String? = null): List<SeriesItem> = withContext(Dispatchers.IO) {
        if (categoryId == "ALL_SERIES" || categoryId == null) {
            return@withContext getAllSeries()
        }
        val url = buildApiUrl("get_series", "&category_id=$categoryId")
        val json = executeGet(url)
        val type = object : TypeToken<List<SeriesItem>>() {}.type
        gson.fromJson(json, type) ?: emptyList()
    }

    suspend fun getAllSeries(): List<SeriesItem> = withContext(Dispatchers.IO) {
        if (!cachedAllSeries.isNullOrEmpty()) return@withContext cachedAllSeries!!
        val url = buildApiUrl("get_series")
        val json = executeGet(url)
        val type = object : TypeToken<List<SeriesItem>>() {}.type
        val list: List<SeriesItem> = gson.fromJson(json, type) ?: emptyList()
        cachedAllSeries = list
        list
    }

    fun isGermanMedia(name: String): Boolean {
        val u = name.uppercase()
        if (u.contains("SUBS") || u.contains("OMU") || u.contains("SUB ENG") || u.contains("SUB EN")) return false
        return u.startsWith("DE -") || u.startsWith("DE:") || u.startsWith("DE|") || u.startsWith("DE ") ||
               u.startsWith("4K-DE") || u.startsWith("4K DE") || u.startsWith("GERMANY") ||
               u.contains("GERMAN") || u.contains("DEUTSCH")
    }

    private fun safeNextString(reader: JsonReader): String {
        return if (reader.peek() == android.util.JsonToken.NULL) {
            reader.nextNull()
            ""
        } else {
            reader.nextString()
        }
    }

    private fun safeNextInt(reader: JsonReader): Int {
        return if (reader.peek() == android.util.JsonToken.NULL) {
            reader.nextNull()
            0
        } else {
            try {
                reader.nextInt()
            } catch (e: Exception) {
                try { reader.nextString().toIntOrNull() ?: 0 } catch (ex: Exception) { 0 }
            }
        }
    }

    private val vodDiskCacheFile get() = java.io.File(context.filesDir, "cached_catalog_movies.json")
    private val seriesDiskCacheFile get() = java.io.File(context.filesDir, "cached_catalog_series.json")

    fun loadCachedVodCatalog(): List<VodStream> {
        if (!cachedAllMovies.isNullOrEmpty()) return cachedAllMovies!!
        try {
            if (vodDiskCacheFile.exists() && vodDiskCacheFile.length() > 10) {
                java.io.FileReader(vodDiskCacheFile).use { reader ->
                    val type = object : TypeToken<List<VodStream>>() {}.type
                    val list: List<VodStream> = gson.fromJson(reader, type) ?: emptyList()
                    if (list.isNotEmpty()) {
                        cachedAllMovies = list
                        return list
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore corrupted disk cache
        }
        return emptyList()
    }

    fun saveCachedVodCatalog(list: List<VodStream>) {
        cachedAllMovies = list
        try {
            java.io.FileWriter(vodDiskCacheFile).use { writer ->
                gson.toJson(list, writer)
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    fun loadCachedSeriesCatalog(): List<SeriesItem> {
        if (!cachedAllSeries.isNullOrEmpty()) return cachedAllSeries!!
        try {
            if (seriesDiskCacheFile.exists() && seriesDiskCacheFile.length() > 10) {
                java.io.FileReader(seriesDiskCacheFile).use { reader ->
                    val type = object : TypeToken<List<SeriesItem>>() {}.type
                    val list: List<SeriesItem> = gson.fromJson(reader, type) ?: emptyList()
                    if (list.isNotEmpty()) {
                        cachedAllSeries = list
                        return list
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return emptyList()
    }

    fun saveCachedSeriesCatalog(list: List<SeriesItem>) {
        cachedAllSeries = list
        try {
            java.io.FileWriter(seriesDiskCacheFile).use { writer ->
                gson.toJson(list, writer)
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    suspend fun getGermanSeriesStreamed(): List<SeriesItem> = withContext(Dispatchers.IO) {
        if (!cachedAllSeries.isNullOrEmpty()) return@withContext cachedAllSeries!!
        // Prüfe Disk-Cache
        val diskCached = loadCachedSeriesCatalog()
        if (diskCached.isNotEmpty()) {
            return@withContext diskCached
        }

        val url = buildApiUrl("get_series")
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
        val resp = httpClient.newCall(req).execute()
        val result = mutableListOf<SeriesItem>()
        resp.body?.charStream()?.let { charStream ->
            val reader = JsonReader(charStream)
            reader.beginArray()
            while (reader.hasNext()) {
                reader.beginObject()
                var sId = 0
                var sName = ""
                var sCover = ""
                var sPlot = ""
                var sCast = ""
                var sDirector = ""
                var sGenre = ""
                var sReleaseDate = ""
                var sRating = ""
                var sCatId = ""
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "series_id" -> sId = safeNextInt(reader)
                        "name" -> sName = safeNextString(reader)
                        "cover" -> sCover = safeNextString(reader)
                        "plot" -> sPlot = safeNextString(reader)
                        "cast" -> sCast = safeNextString(reader)
                        "director" -> sDirector = safeNextString(reader)
                        "genre" -> sGenre = safeNextString(reader)
                        "releaseDate", "release_date" -> sReleaseDate = safeNextString(reader)
                        "rating" -> sRating = safeNextString(reader)
                        "category_id" -> sCatId = safeNextString(reader)
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                if (sName.isNotEmpty() && (isGermanMedia(sName) || isRussianMedia(sName, sCatId))) {
                    result.add(
                        SeriesItem(
                            seriesId = sId,
                            name = sName,
                            cover = sCover,
                            plot = sPlot,
                            cast = sCast,
                            director = sDirector,
                            genre = sGenre,
                            releaseDate = sReleaseDate,
                            rating = sRating,
                            categoryId = sCatId
                        )
                    )
                }
            }
            reader.endArray()
            reader.close()
        }
        saveCachedSeriesCatalog(result)
        result
    }

    suspend fun getGermanVodStreamsStreamed(): List<VodStream> = withContext(Dispatchers.IO) {
        if (!cachedAllMovies.isNullOrEmpty()) return@withContext cachedAllMovies!!
        // Prüfe Disk-Cache
        val diskCached = loadCachedVodCatalog()
        if (diskCached.isNotEmpty()) {
            return@withContext diskCached
        }

        val url = buildApiUrl("get_vod_streams")
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
        val resp = httpClient.newCall(req).execute()
        val result = mutableListOf<VodStream>()
        resp.body?.charStream()?.let { charStream ->
            val reader = JsonReader(charStream)
            reader.beginArray()
            while (reader.hasNext()) {
                reader.beginObject()
                var mId = 0
                var mName = ""
                var mIcon = ""
                var mRating = ""
                var mCatId = ""
                var mExt = "mp4"
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "stream_id" -> mId = safeNextInt(reader)
                        "name" -> mName = safeNextString(reader)
                        "stream_icon" -> mIcon = safeNextString(reader)
                        "rating" -> mRating = safeNextString(reader)
                        "category_id" -> mCatId = safeNextString(reader)
                        "container_extension" -> mExt = safeNextString(reader)
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                if (mName.isNotEmpty() && (isGermanMedia(mName) || isRussianMedia(mName, mCatId))) {
                    result.add(
                        VodStream(
                            streamId = mId,
                            name = mName,
                            streamIcon = mIcon,
                            rating = mRating,
                            categoryId = mCatId,
                            containerExtension = mExt
                        )
                    )
                }
            }
            reader.endArray()
            reader.close()
        }
        saveCachedVodCatalog(result)
        result
    }

    fun loadCachedSeriesInfo(seriesId: Int): SeriesInfoResponse? {
        try {
            val file = java.io.File(context.filesDir, "cached_series_info_${seriesId}.json")
            if (file.exists() && file.length() > 20) {
                file.reader().use { reader ->
                    val info = gson.fromJson(reader, SeriesInfoResponse::class.java)
                    if (info != null && (!info.seasons.isNullOrEmpty() || !info.episodes.isNullOrEmpty())) {
                        return info
                    }
                }
            }
        } catch (e: Exception) {
            // Silent
        }
        return null
    }

    fun saveCachedSeriesInfo(seriesId: Int, info: SeriesInfoResponse) {
        try {
            val file = java.io.File(context.filesDir, "cached_series_info_${seriesId}.json")
            file.writer().use { writer ->
                gson.toJson(info, writer)
            }
        } catch (e: Exception) {
            // Silent
        }
    }

    suspend fun getSeriesInfo(seriesId: Int): SeriesInfoResponse = withContext(Dispatchers.IO) {
        if (seriesId <= 0) return@withContext SeriesInfoResponse()
        val cached = loadCachedSeriesInfo(seriesId)
        if (cached != null && (!cached.seasons.isNullOrEmpty() || !cached.episodes.isNullOrEmpty())) {
            return@withContext cached
        }
        val url = buildApiUrl("get_series_info", "&series_id=$seriesId")
        val json = try {
            executeGet(url).trim()
        } catch (e: Exception) {
            ""
        }
        if (json.isEmpty() || json.startsWith("[")) {
            // Xtream API gibt [] zurück, wenn die series_id nicht existiert (z. B. Episoden-ID statt Serien-ID)
            return@withContext SeriesInfoResponse()
        }
        val res = try {
            gson.fromJson(json, SeriesInfoResponse::class.java) ?: SeriesInfoResponse()
        } catch (e: Exception) {
            SeriesInfoResponse()
        }
        if (res.seasons?.isNotEmpty() == true || res.episodes?.isNotEmpty() == true) {
            saveCachedSeriesInfo(seriesId, res)
        }
        res
    }

    fun loadCachedVodInfo(streamId: Int): VodInfoResponse? {
        try {
            val file = java.io.File(context.filesDir, "cached_vod_info_${streamId}.json")
            if (file.exists() && file.length() > 20) {
                file.reader().use { reader ->
                    val info = gson.fromJson(reader, VodInfoResponse::class.java)
                    if (info?.info != null) {
                        return info
                    }
                }
            }
        } catch (e: Exception) {
            // Silent
        }
        return null
    }

    fun saveCachedVodInfo(streamId: Int, info: VodInfoResponse) {
        try {
            val file = java.io.File(context.filesDir, "cached_vod_info_${streamId}.json")
            file.writer().use { writer ->
                gson.toJson(info, writer)
            }
        } catch (e: Exception) {
            // Silent
        }
    }

    suspend fun getVodInfo(streamId: Int): VodInfoResponse = withContext(Dispatchers.IO) {
        if (streamId <= 0) return@withContext VodInfoResponse()
        val cached = loadCachedVodInfo(streamId)
        if (cached?.info != null) {
            return@withContext cached
        }
        val url = buildApiUrl("get_vod_info", "&vod_id=$streamId")
        val json = try {
            executeGet(url).trim()
        } catch (e: Exception) {
            ""
        }
        if (json.isEmpty() || json.startsWith("[")) {
            return@withContext VodInfoResponse()
        }
        val res = try {
            gson.fromJson(json, VodInfoResponse::class.java) ?: VodInfoResponse()
        } catch (e: Exception) {
            VodInfoResponse()
        }
        if (res.info != null) {
            saveCachedVodInfo(streamId, res)
        }
        res
    }

    private fun executeGet(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "IPTVSmartersPro/1.0.0 (Linux; Android 11; TV)")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP Error: ${response.code}")
            return response.body?.string() ?: ""
        }
    }
}
