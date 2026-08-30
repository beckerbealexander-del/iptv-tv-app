package com.alex.iptvplayer.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

enum class LangFilter {
    AUTO_DE_RU_ADULT, // Standard: Nur Deutsch, Russisch & Porno/Adult (XXX)
    DE,               // Nur Deutsch
    RU,               // Nur Russisch
    ADULT,            // Nur Porno/Adult (XXX)
    ALL               // Alle (DE, RU, Adult)
}

class XtreamClient(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("iptv_settings", Context.MODE_PRIVATE)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    // Original-Zugangsdaten vom Anbieter (cf.rilox.sbs)
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

    // Harter Filter: Nur Deutsch, Russisch und Porno/Adult (XXX) werden überhaupt durchgelassen
    fun filterCategories(categories: List<Category>, filter: LangFilter): List<Category> {
        // Zuerst alles Fremdsprachige (Türkei, Polen, UK, Arabisch, etc.) komplett entfernen
        val baseAllowed = categories.filter { isGerman(it.name) || isRussian(it.name) || isAdult(it.name) }

        return when (filter) {
            LangFilter.DE -> baseAllowed.filter { isGerman(it.name) }
            LangFilter.RU -> baseAllowed.filter { isRussian(it.name) }
            LangFilter.ADULT -> baseAllowed.filter { isAdult(it.name) }
            LangFilter.AUTO_DE_RU_ADULT, LangFilter.ALL -> baseAllowed
        }
    }

    private fun isGerman(name: String): Boolean {
        val u = name.uppercase()
        if (u.contains("SUBS") || u.contains("OMU") || u.contains("SUB ENG") || u.contains("SUB EN")) return false
        return u.startsWith("DE|") || u.startsWith("DE:") || u.startsWith("DE -") || u.startsWith("DE ") ||
                u.startsWith("AT|") || u.startsWith("AT:") || u.startsWith("AT -") ||
                u.startsWith("CH|") || u.startsWith("CH:") || u.startsWith("CH -") ||
                u.startsWith("GERMANY") || u.contains("GERMAN") || u.contains("DEUTSCH") ||
                u.startsWith("JOYN") || u.startsWith("SKY") || u.contains("MAGENTA") || u.contains("DAZN")
    }

    private fun isRussian(name: String): Boolean {
        val u = name.uppercase()
        return u.startsWith("RU|") || u.startsWith("RU:") || u.startsWith("RU -") || u.startsWith("RU ") ||
                u.contains("RUSSIAN") || u.contains("RUSSAIN") || u.contains("RUSSIA")
    }

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
        val extra = if (categoryId != null) "&category_id=$categoryId" else ""
        val url = buildApiUrl("get_live_streams", extra)
        val json = executeGet(url)
        val type = object : TypeToken<List<LiveStream>>() {}.type
        gson.fromJson(json, type) ?: emptyList()
    }

    // EPG
    suspend fun getEpg(streamId: Int): List<EpgProgram> = withContext(Dispatchers.IO) {
        try {
            val url = buildApiUrl("get_simple_data_table", "&stream_id=$streamId")
            val json = executeGet(url)
            val resp = gson.fromJson(json, EpgResponse::class.java)
            val list = mutableListOf<EpgProgram>()
            resp?.listings?.forEach { raw ->
                val title = decodeBase64(raw.title)
                val desc = decodeBase64(raw.description)
                val start = raw.start?.substringAfter(" ")?.take(5) ?: ""
                val end = raw.end?.substringAfter(" ")?.take(5) ?: ""
                val isNow = raw.nowPlaying == 1
                if (title.isNotEmpty()) {
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
        filterCategories(raw, LangFilter.AUTO_DE_RU_ADULT)
    }

    suspend fun getVodStreams(categoryId: String? = null): List<VodStream> = withContext(Dispatchers.IO) {
        val extra = if (categoryId != null) "&category_id=$categoryId" else ""
        val url = buildApiUrl("get_vod_streams", extra)
        val json = executeGet(url)
        val type = object : TypeToken<List<VodStream>>() {}.type
        gson.fromJson(json, type) ?: emptyList()
    }

    // 3. Serien
    suspend fun getSeriesCategories(): List<Category> = withContext(Dispatchers.IO) {
        val url = buildApiUrl("get_series_categories")
        val json = executeGet(url)
        val type = object : TypeToken<List<Category>>() {}.type
        val raw: List<Category> = gson.fromJson(json, type) ?: emptyList()
        filterCategories(raw, LangFilter.AUTO_DE_RU_ADULT)
    }

    suspend fun getSeries(categoryId: String? = null): List<SeriesItem> = withContext(Dispatchers.IO) {
        val extra = if (categoryId != null) "&category_id=$categoryId" else ""
        val url = buildApiUrl("get_series", extra)
        val json = executeGet(url)
        val type = object : TypeToken<List<SeriesItem>>() {}.type
        gson.fromJson(json, type) ?: emptyList()
    }

    suspend fun getSeriesInfo(seriesId: Int): SeriesInfoResponse = withContext(Dispatchers.IO) {
        val url = buildApiUrl("get_series_info", "&series_id=$seriesId")
        val json = executeGet(url)
        gson.fromJson(json, SeriesInfoResponse::class.java) ?: SeriesInfoResponse()
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
