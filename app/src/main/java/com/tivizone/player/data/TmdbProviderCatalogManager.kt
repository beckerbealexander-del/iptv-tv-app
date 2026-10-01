package com.tivizone.player.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.Serializable
import java.util.concurrent.TimeUnit

data class TmdbMediaItem(
    val tmdbId: Int,
    val title: String,
    val originalTitle: String,
    val popularity: Float,
    val rating: Float,
    val genreIds: List<Int>
) : Serializable

data class TmdbProviderCache(
    val timestamp: Long,
    val moviesByProvider: Map<String, List<TmdbMediaItem>>,
    val seriesByProvider: Map<String, List<TmdbMediaItem>>
) : Serializable

data class ProviderDefinition(
    val key: String,
    val name: String,
    val tmdbProviderId: Int
)

data class GenreDefinition(
    val key: String,
    val name: String,
    val movieGenreIds: Set<Int>,
    val seriesGenreIds: Set<Int>,
    val keywords: List<String>
) {
    fun getMovieGenreParam(): String? = when (key) {
        "ACTION" -> "28|12"
        "COMEDY" -> "35"
        "THRILLER" -> "53|80"
        "HORROR" -> "27|9648"
        "SCIFI" -> "878|14"
        "DOCU" -> "99"
        "KIDS" -> "16|10751"
        else -> null
    }

    fun getSeriesGenreParam(): String? = when (key) {
        "ACTION" -> "10759"
        "COMEDY" -> "35"
        "THRILLER" -> "80"
        "HORROR" -> "9648"
        "SCIFI" -> "10765"
        "DOCU" -> "99"
        "KIDS" -> "10762|16"
        else -> null
    }
}

object TmdbProviderCatalogManager {

    private const val TMDB_API_KEY = "4e44d9029b1270a757cddc766a1bcb63"
    private const val CACHE_FILE_NAME = "cached_tmdb_provider_catalog.json"
    private const val CACHE_DURATION_MS = 24 * 3600 * 1000L // 24 Stunden Gültigkeit

    private val gson = Gson()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    val PROVIDERS = listOf(
        ProviderDefinition("netflix", "Netflix", 8),
        ProviderDefinition("prime", "Amazon Prime", 9),
        ProviderDefinition("disney", "Disney+", 337),
        ProviderDefinition("apple", "Apple TV+", 350),
        ProviderDefinition("paramount", "Paramount+", 531)
    )

    val GENRES = listOf(
        GenreDefinition("ALL", "Alle", emptySet(), emptySet(), emptyList()),
        GenreDefinition("TOP", "Top", emptySet(), emptySet(), emptyList()),
        GenreDefinition("ACTION", "Action & Abenteuer", setOf(28, 12), setOf(10759), listOf("action", "abenteuer", "mission", "bond", "spider", "batman", "avengers", "war", "krieg", "wick")),
        GenreDefinition("COMEDY", "Komödie", setOf(35), setOf(35), listOf("komödie", "comedy", "lustig", "spencer", "hill", "hangover", "ted", "bean")),
        GenreDefinition("THRILLER", "Thriller & Krimi", setOf(53, 80), setOf(80), listOf("thriller", "krimi", "crime", "tatort", "mord", "killer", "police", "fbi", "detective")),
        GenreDefinition("HORROR", "Horror & Mystery", setOf(27, 9648), setOf(9648), listOf("horror", "ghost", "zombie", "dead", "evil", "blood", "conjuring", "halloween", "dracula", "nightmare")),
        GenreDefinition("SCIFI", "Sci-Fi & Fantasy", setOf(878, 14), setOf(10765), listOf("sci-fi", "science", "alien", "star wars", "star trek", "matrix", "avatar", "dune", "marvel")),
        GenreDefinition("DOCU", "Dokumentation", setOf(99), setOf(99), listOf("doku", "dokumentation", "documentary", "planet", "history", "nature")),
        GenreDefinition("KIDS", "Kinder & Familie", setOf(16, 10751), setOf(16, 10751, 10762), listOf("kinder", "disney", "pixar", "animation", "kids", "familie", "family", "shrek", "minions"))
    )

    // Globaler, permanenter Memory-Cache für gefilterte Kategorien & Anzahlen
    val movieCategoryCache = java.util.concurrent.ConcurrentHashMap<String, List<VodStream>>()
    val movieCategoryCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    val seriesCategoryCache = java.util.concurrent.ConcurrentHashMap<String, List<SeriesItem>>()
    val seriesCategoryCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    @Volatile
    private var memoryCache: TmdbProviderCache? = null

    private val REGEX_PREFIX = Regex("^(?:DE|4K-DE|DE-DO|DE-DV|FHD-DE|UHD-DE|RU|TR|EN)\\s*[-:|]\\s*", RegexOption.IGNORE_CASE)
    private val REGEX_YEAR = Regex("\\s*\\(\\d{4}\\)")
    private val REGEX_COUNTRY = Regex("\\s*\\([A-Za-z]{2,3}\\)")
    private val REGEX_NON_ALPHA = Regex("[^\\p{L}\\p{Nd}\\s]")

    fun normalize(name: String): String {
        if (name.isEmpty()) return ""
        var s = name.replace(REGEX_PREFIX, "")
        s = s.replace(REGEX_YEAR, "")
        s = s.replace(REGEX_COUNTRY, "")
        s = s.replace(REGEX_NON_ALPHA, " ")
        return s.trim().lowercase().replace(Regex("\\s+"), " ")
    }

    suspend fun getCatalog(context: Context): TmdbProviderCache = withContext(Dispatchers.IO) {
        memoryCache?.let { return@withContext it }

        val cacheFile = File(context.filesDir, CACHE_FILE_NAME)
        if (cacheFile.exists() && cacheFile.length() > 0L) {
            try {
                cacheFile.reader().use { reader ->
                    val cached = gson.fromJson(reader, TmdbProviderCache::class.java)
                    if (cached != null && (System.currentTimeMillis() - cached.timestamp < CACHE_DURATION_MS)) {
                        memoryCache = cached
                        return@withContext cached
                    }
                }
            } catch (e: Exception) {
                // Fallback to fresh fetch
            }
        }

        // Neu von TMDb laden
        val fresh = fetchFreshCatalogFromTmdb()
        try {
            cacheFile.writer().use { writer ->
                gson.toJson(fresh, writer)
            }
            memoryCache = fresh
        } catch (e: Exception) {
            // Ignore write error
        }
        fresh
    }

    private suspend fun fetchFreshCatalogFromTmdb(): TmdbProviderCache = coroutineScope {
        val moviesMap = mutableMapOf<String, List<TmdbMediaItem>>()
        val seriesMap = mutableMapOf<String, List<TmdbMediaItem>>()

        val movieJobs = PROVIDERS.map { provider ->
            provider.key to (1..4).map { page ->
                async(Dispatchers.IO) {
                    val url = "https://api.themoviedb.org/3/discover/movie?api_key=$TMDB_API_KEY&watch_region=DE&with_watch_providers=${provider.tmdbProviderId}&sort_by=popularity.desc&language=de-DE&page=$page"
                    fetchTmdbPage(url, isMovie = true)
                }
            }
        }

        val seriesJobs = PROVIDERS.map { provider ->
            provider.key to (1..4).map { page ->
                async(Dispatchers.IO) {
                    val url = "https://api.themoviedb.org/3/discover/tv?api_key=$TMDB_API_KEY&watch_region=DE&with_watch_providers=${provider.tmdbProviderId}&sort_by=popularity.desc&language=de-DE&page=$page"
                    fetchTmdbPage(url, isMovie = false)
                }
            }
        }

        for ((key, deferredList) in movieJobs) {
            val list = mutableListOf<TmdbMediaItem>()
            for (def in deferredList) {
                list.addAll(def.await())
            }
            moviesMap[key] = list
        }

        for ((key, deferredList) in seriesJobs) {
            val list = mutableListOf<TmdbMediaItem>()
            for (def in deferredList) {
                list.addAll(def.await())
            }
            seriesMap[key] = list
        }

        TmdbProviderCache(
            timestamp = System.currentTimeMillis(),
            moviesByProvider = moviesMap,
            seriesByProvider = seriesMap
        )
    }

    private fun fetchTmdbPage(url: String, isMovie: Boolean): List<TmdbMediaItem> {
        val items = mutableListOf<TmdbMediaItem>()
        try {
            val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val jsonStr = resp.body?.string() ?: return emptyList()
                val results = JSONObject(jsonStr).optJSONArray("results") ?: return emptyList()
                for (i in 0 until results.length()) {
                    val obj = results.getJSONObject(i)
                    val id = obj.optInt("id")
                    val title = if (isMovie) obj.optString("title") else obj.optString("name")
                    val orig = if (isMovie) obj.optString("original_title") else obj.optString("original_name")
                    val pop = obj.optDouble("popularity", 0.0).toFloat()
                    val rating = obj.optDouble("vote_average", 0.0).toFloat()
                    val gArray = obj.optJSONArray("genre_ids")
                    val gList = mutableListOf<Int>()
                    if (gArray != null) {
                        for (k in 0 until gArray.length()) {
                            gList.add(gArray.getInt(k))
                        }
                    }
                    if (id > 0 && title.isNotEmpty()) {
                        items.add(TmdbMediaItem(id, title, orig, pop, rating, gList))
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
        return items
    }

    suspend fun filterMoviesByProviderAndGenre(
        context: Context,
        providerKey: String,
        genreKey: String,
        germanMovies: List<VodStream>
    ): List<VodStream> = withContext(Dispatchers.Default) {
        val cacheKey = "${providerKey}_${genreKey}"
        movieCategoryCache[cacheKey]?.let { return@withContext it }

        val catalog = getCatalog(context)

        // Indizes aufbauen für O(1) Matching
        val tmdbIndex = HashMap<String, VodStream>()
        val normIndex = HashMap<String, VodStream>()
        for (m in germanMovies) {
            if (!m.tmdb.isNullOrEmpty() && m.tmdb != "0") {
                tmdbIndex[m.tmdb] = m
            }
            val norm = normalize(m.name)
            if (norm.isNotEmpty()) {
                normIndex[norm] = m
            }
        }

        if (providerKey == "other") {
            // Alle Filme, die auf KEINEM Anbieter laufen
            val allProviderTmdbIds = HashSet<String>()
            val allProviderNormTitles = HashSet<String>()
            for (p in PROVIDERS) {
                catalog.moviesByProvider[p.key]?.forEach { t ->
                    allProviderTmdbIds.add(t.tmdbId.toString())
                    allProviderNormTitles.add(normalize(t.title))
                    allProviderNormTitles.add(normalize(t.originalTitle))
                }
            }
            val nonProviderPool = germanMovies.filter { m ->
                val tidMatch = !m.tmdb.isNullOrEmpty() && allProviderTmdbIds.contains(m.tmdb)
                val titleMatch = allProviderNormTitles.contains(normalize(m.name))
                !tidMatch && !titleMatch
            }

            val genreDef = GENRES.firstOrNull { it.key == genreKey } ?: return@withContext nonProviderPool
            val res = if (genreKey == "ALL" || genreKey == "TOP") {
                nonProviderPool.sortedByDescending { it.rating?.toFloatOrNull() ?: 0f }
            } else {
                nonProviderPool.filter { m ->
                    val lower = m.name.lowercase()
                    genreDef.keywords.any { lower.contains(it) }
                }.sortedByDescending { it.rating?.toFloatOrNull() ?: 0f }
            }
            movieCategoryCache[cacheKey] = res
            movieCategoryCounts[cacheKey] = res.size
            return@withContext res
        }

        // Bestimmter Anbieter (z.B. netflix, prime, disney, etc.)
        val filteredTmdb = when (genreKey) {
            "TOP" -> catalog.moviesByProvider[providerKey] ?: emptyList()
            "ALL" -> getAllTmdbItemsForProvider(context, providerKey, isMovie = true, catalog = catalog)
            else -> {
                val genreItems = getTmdbItemsForProviderAndGenre(context, providerKey, genreKey, isMovie = true)
                if (genreItems.isNotEmpty()) {
                    genreItems
                } else {
                    val genreDef = GENRES.firstOrNull { it.key == genreKey }
                    val providerTmdb = catalog.moviesByProvider[providerKey] ?: emptyList()
                    if (genreDef != null) {
                        providerTmdb.filter { t -> t.genreIds.any { genreDef.movieGenreIds.contains(it) } }
                    } else providerTmdb
                }
            }
        }

        val matched = mutableListOf<VodStream>()
        val matchedIds = HashSet<Int>()

        for (t in filteredTmdb) {
            val item = tmdbIndex[t.tmdbId.toString()]
                ?: normIndex[normalize(t.title)]
                ?: normIndex[normalize(t.originalTitle)]
            if (item != null && matchedIds.add(item.streamId)) {
                matched.add(item)
            }
        }

        movieCategoryCache[cacheKey] = matched
        movieCategoryCounts[cacheKey] = matched.size
        matched
    }

    suspend fun getAllTmdbItemsForProvider(
        context: Context,
        providerKey: String,
        isMovie: Boolean,
        catalog: TmdbProviderCache
    ): List<TmdbMediaItem> = withContext(Dispatchers.IO) {
        val cacheKey = "${if (isMovie) "mov" else "ser"}_${providerKey}_ALL"
        dynamicGenreCache[cacheKey]?.let { return@withContext it }

        val diskFile = File(context.filesDir, "tmdb_genre_${cacheKey}.json")
        if (diskFile.exists() && diskFile.length() > 0 && (System.currentTimeMillis() - diskFile.lastModified() < CACHE_DURATION_MS)) {
            try {
                diskFile.reader().use { r ->
                    val type = object : TypeToken<List<TmdbMediaItem>>() {}.type
                    val list: List<TmdbMediaItem> = gson.fromJson(r, type) ?: emptyList()
                    if (list.isNotEmpty()) {
                        dynamicGenreCache[cacheKey] = list
                        return@withContext list
                    }
                }
            } catch (e: Exception) {}
        }

        val provDef = PROVIDERS.firstOrNull { it.key == providerKey } ?: return@withContext emptyList()
        val endpoint = if (isMovie) "movie" else "tv"

        val discoverList = coroutineScope {
            (1..8).map { page ->
                async(Dispatchers.IO) {
                    val url = "https://api.themoviedb.org/3/discover/$endpoint?api_key=$TMDB_API_KEY&watch_region=DE&with_watch_providers=${provDef.tmdbProviderId}&sort_by=popularity.desc&language=de-DE&page=$page"
                    fetchTmdbPage(url, isMovie = isMovie)
                }
            }.flatMap { it.await() }
        }

        val combined = LinkedHashMap<Int, TmdbMediaItem>()
        val baseList = if (isMovie) catalog.moviesByProvider[providerKey] else catalog.seriesByProvider[providerKey]
        baseList?.forEach { combined[it.tmdbId] = it }
        discoverList.forEach { combined[it.tmdbId] = it }

        val resultList = combined.values.toList()
        if (resultList.isNotEmpty()) {
            dynamicGenreCache[cacheKey] = resultList
            try {
                diskFile.writer().use { w -> gson.toJson(resultList, w) }
            } catch (e: Exception) {}
        }
        resultList
    }

    private val dynamicGenreCache = HashMap<String, List<TmdbMediaItem>>()

    suspend fun getTmdbItemsForProviderAndGenre(
        context: Context,
        providerKey: String,
        genreKey: String,
        isMovie: Boolean
    ): List<TmdbMediaItem> = withContext(Dispatchers.IO) {
        val cacheKey = "${if (isMovie) "mov" else "ser"}_${providerKey}_${genreKey}"
        dynamicGenreCache[cacheKey]?.let { return@withContext it }

        val diskFile = File(context.filesDir, "tmdb_genre_${cacheKey}.json")
        if (diskFile.exists() && diskFile.length() > 0 && (System.currentTimeMillis() - diskFile.lastModified() < CACHE_DURATION_MS)) {
            try {
                diskFile.reader().use { r ->
                    val type = object : TypeToken<List<TmdbMediaItem>>() {}.type
                    val list: List<TmdbMediaItem> = gson.fromJson(r, type) ?: emptyList()
                    if (list.isNotEmpty()) {
                        dynamicGenreCache[cacheKey] = list
                        return@withContext list
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
        }

        val provDef = PROVIDERS.firstOrNull { it.key == providerKey } ?: return@withContext emptyList()
        val genreDef = GENRES.firstOrNull { it.key == genreKey }
        val genreParam = if (isMovie) genreDef?.getMovieGenreParam() else genreDef?.getSeriesGenreParam()
        val withGenres = if (!genreParam.isNullOrEmpty()) "&with_genres=$genreParam" else ""
        val endpoint = if (isMovie) "movie" else "tv"

        val list = coroutineScope {
            (1..4).map { page ->
                async(Dispatchers.IO) {
                    val url = "https://api.themoviedb.org/3/discover/$endpoint?api_key=$TMDB_API_KEY&watch_region=DE&with_watch_providers=${provDef.tmdbProviderId}$withGenres&sort_by=popularity.desc&language=de-DE&page=$page"
                    fetchTmdbPage(url, isMovie = isMovie)
                }
            }.flatMap { it.await() }
        }

        if (list.isNotEmpty()) {
            dynamicGenreCache[cacheKey] = list
            try {
                diskFile.writer().use { w ->
                    gson.toJson(list, w)
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
        list
    }

    suspend fun filterSeriesByProviderAndGenre(
        context: Context,
        providerKey: String,
        genreKey: String,
        germanSeries: List<SeriesItem>
    ): List<SeriesItem> = withContext(Dispatchers.Default) {
        val cacheKey = "${providerKey}_${genreKey}"
        seriesCategoryCache[cacheKey]?.let { return@withContext it }

        val catalog = getCatalog(context)

        val normIndex = HashMap<String, SeriesItem>()
        for (s in germanSeries) {
            val norm = normalize(s.name)
            if (norm.isNotEmpty()) {
                normIndex[norm] = s
            }
        }

        if (providerKey == "other") {
            val allProviderNormTitles = HashSet<String>()
            for (p in PROVIDERS) {
                catalog.seriesByProvider[p.key]?.forEach { t ->
                    allProviderNormTitles.add(normalize(t.title))
                    allProviderNormTitles.add(normalize(t.originalTitle))
                }
            }
            val nonProviderPool = germanSeries.filter { s ->
                !allProviderNormTitles.contains(normalize(s.name))
            }

            val genreDef = GENRES.firstOrNull { it.key == genreKey } ?: return@withContext nonProviderPool
            val res = if (genreKey == "ALL" || genreKey == "TOP") {
                nonProviderPool.sortedByDescending { it.rating?.toFloatOrNull() ?: 0f }
            } else {
                nonProviderPool.filter { s ->
                    val g = s.genre?.lowercase() ?: ""
                    val n = s.name.lowercase()
                    genreDef.keywords.any { g.contains(it) || n.contains(it) }
                }.sortedByDescending { it.rating?.toFloatOrNull() ?: 0f }
            }
            seriesCategoryCache[cacheKey] = res
            seriesCategoryCounts[cacheKey] = res.size
            return@withContext res
        }

        val filteredTmdb = when (genreKey) {
            "TOP" -> catalog.seriesByProvider[providerKey] ?: emptyList()
            "ALL" -> getAllTmdbItemsForProvider(context, providerKey, isMovie = false, catalog = catalog)
            else -> {
                val genreItems = getTmdbItemsForProviderAndGenre(context, providerKey, genreKey, isMovie = false)
                if (genreItems.isNotEmpty()) {
                    genreItems
                } else {
                    val genreDef = GENRES.firstOrNull { it.key == genreKey }
                    val providerTmdb = catalog.seriesByProvider[providerKey] ?: emptyList()
                    if (genreDef != null) {
                        providerTmdb.filter { t -> t.genreIds.any { genreDef.seriesGenreIds.contains(it) } }
                    } else providerTmdb
                }
            }
        }

        val matched = mutableListOf<SeriesItem>()
        val matchedIds = HashSet<Int>()

        for (t in filteredTmdb) {
            val item = normIndex[normalize(t.title)]
                ?: normIndex[normalize(t.originalTitle)]
            if (item != null && matchedIds.add(item.seriesId)) {
                matched.add(item)
            }
        }

        seriesCategoryCache[cacheKey] = matched
        seriesCategoryCounts[cacheKey] = matched.size
        matched
    }
}
