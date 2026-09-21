package com.alex.iptvplayer.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.alex.iptvplayer.R
import com.alex.iptvplayer.data.HistoryItem
import com.alex.iptvplayer.data.HistoryManager
import com.alex.iptvplayer.data.LiveStream
import com.alex.iptvplayer.data.SeriesItem
import com.alex.iptvplayer.data.TrendingItem
import com.alex.iptvplayer.data.VodStream
import com.alex.iptvplayer.data.XtreamClient
import com.alex.iptvplayer.databinding.ActivityMainBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.JsonReader
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var client: XtreamClient
    private lateinit var historyManager: HistoryManager

    private val posterLookupMap = HashMap<Int, String>()
    private var allSeriesList: List<SeriesItem> = emptyList()
    private val episodePattern = Regex(" - S\\d+E\\d+", RegexOption.IGNORE_CASE)

    private val tmdbHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val TMDB_API_KEY = "4e44d9029b1270a757cddc766a1bcb63"

    private val gson = Gson()
    private val trendingSeriesCacheFile: File
        get() = File(filesDir, "cached_trending_series.json")
    private val trendingMoviesCacheFile: File
        get() = File(filesDir, "cached_trending_movies.json")

    private fun loadCachedTrending(): Pair<List<TrendingItem>, List<TrendingItem>> {
        val series = try {
            if (trendingSeriesCacheFile.exists() && trendingSeriesCacheFile.length() > 0) {
                FileReader(trendingSeriesCacheFile).use { reader ->
                    val type = object : TypeToken<List<TrendingItem>>() {}.type
                    gson.fromJson<List<TrendingItem>>(reader, type) ?: emptyList()
                }
            } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        val movies = try {
            if (trendingMoviesCacheFile.exists() && trendingMoviesCacheFile.length() > 0) {
                FileReader(trendingMoviesCacheFile).use { reader ->
                    val type = object : TypeToken<List<TrendingItem>>() {}.type
                    gson.fromJson<List<TrendingItem>>(reader, type) ?: emptyList()
                }
            } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        return Pair(series, movies)
    }

    private fun saveCachedTrending(series: List<TrendingItem>, movies: List<TrendingItem>) {
        try {
            if (series.isNotEmpty()) {
                FileWriter(trendingSeriesCacheFile).use { writer ->
                    gson.toJson(series, writer)
                }
            }
            if (movies.isNotEmpty()) {
                FileWriter(trendingMoviesCacheFile).use { writer ->
                    gson.toJson(movies, writer)
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        client = XtreamClient(this)
        historyManager = HistoryManager(this)

        setupSidebar()
        setupRecyclers()

        // 1. Sofort aus lokalem Cache anzeigen falls vorhanden (Reaktionszeit < 5ms)
        val (cachedSeries, cachedMovies) = loadCachedTrending()
        if (cachedSeries.isNotEmpty()) {
            binding.layoutSectionTrendingSeries.visibility = View.VISIBLE
            binding.recyclerTrendingSeries.adapter = TrendingAdapter(cachedSeries) { onTrendingItemClicked(it) }
        }
        if (cachedMovies.isNotEmpty()) {
            binding.layoutSectionTrendingMovies.visibility = View.VISIBLE
            binding.recyclerTrendingMovies.adapter = TrendingAdapter(cachedMovies) { onTrendingItemClicked(it) }
        }

        // 2. Im Hintergrund Trends laden und abgleichen
        loadTrendingContent()

        // 3. Im Hintergrund ALLE Kataloge vorwärmen (Sender, Filme, Serien, TMDb) für sofortige Ladezeiten
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // 3a. Live-TV Kanäle in Memory cachen
                val liveCache = com.alex.iptvplayer.data.LiveTvCacheManager(this@MainActivity)
                val bundled = liveCache.loadBundledChannels()
                if (bundled != null) {
                    LiveTvActivity.multiStreamCategoriesMap.putAll(bundled)
                }

                // 3b. VOD Filme in Memory cachen
                val diskMovies = client.loadCachedVodCatalog()
                if (diskMovies.isNotEmpty()) {
                    VodActivity.cachedAllMoviesGlobal = diskMovies
                } else {
                    val freshMovies = client.getGermanVodStreamsStreamed()
                    VodActivity.cachedAllMoviesGlobal = freshMovies
                }

                // 3c. Serien in Memory cachen
                val diskSeries = client.loadCachedSeriesCatalog()
                if (diskSeries.isNotEmpty()) {
                    SeriesActivity.cachedAllSeriesGlobal = diskSeries
                    allSeriesList = diskSeries
                } else {
                    val freshSeries = client.getGermanSeriesStreamed()
                    SeriesActivity.cachedAllSeriesGlobal = freshSeries
                    allSeriesList = freshSeries
                }

                // 3d. TMDb Provider-Katalog vorwärmen
                com.alex.iptvplayer.data.TmdbProviderCatalogManager.getCatalog(this@MainActivity)
            } catch (e: Exception) {
                // Silent
            }
        }

        historyManager.syncWithCloud(client.username) {
            runOnUiThread { loadAllHistoryRows() }
        }
    }

    override fun onResume() {
        super.onResume()
        loadAllHistoryRows()
        historyManager.syncWithCloud(client.username) {
            runOnUiThread { loadAllHistoryRows() }
        }
    }

    private fun setupSidebar() {
        binding.navLiveTv.setOnClickListener {
            startActivity(Intent(this, LiveTvActivity::class.java))
        }
        binding.navMovies.setOnClickListener {
            startActivity(Intent(this, VodActivity::class.java))
        }
        binding.navSeries.setOnClickListener {
            startActivity(Intent(this, SeriesActivity::class.java))
        }
        binding.navSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun setupRecyclers() {
        binding.recyclerChannelHistory.apply {
            layoutManager = com.alex.iptvplayer.util.HorizontalLockingLayoutManager(this@MainActivity)
            setHasFixedSize(true)
        }
        binding.recyclerSeriesHistory.apply {
            layoutManager = com.alex.iptvplayer.util.HorizontalLockingLayoutManager(this@MainActivity)
            setHasFixedSize(true)
        }
        binding.recyclerMovieHistory.apply {
            layoutManager = com.alex.iptvplayer.util.HorizontalLockingLayoutManager(this@MainActivity)
            setHasFixedSize(true)
        }
        binding.recyclerTrendingSeries.apply {
            layoutManager = com.alex.iptvplayer.util.HorizontalLockingLayoutManager(this@MainActivity)
            setHasFixedSize(true)
        }
        binding.recyclerTrendingMovies.apply {
            layoutManager = com.alex.iptvplayer.util.HorizontalLockingLayoutManager(this@MainActivity)
            setHasFixedSize(true)
        }
    }

    private fun isSeriesHistoryItem(item: HistoryItem): Boolean {
        if (item.streamUrl.contains("/series/")) return true
        if (item.streamUrl.contains("/movie/")) return false
        if (episodePattern.containsMatchIn(item.title)) return true
        if (item.type == "SERIES") return true
        return false
    }

    private fun isMovieHistoryItem(item: HistoryItem): Boolean {
        if (item.streamUrl.contains("/movie/")) return true
        if (item.streamUrl.contains("/series/")) return false
        if (episodePattern.containsMatchIn(item.title)) return false
        if (item.type == "VOD") return true
        return false
    }

    private fun loadAllHistoryRows() {
        val allHistory = historyManager.getHistory()

        // 1. TV-Sender Verlauf (Zuletzt gesehen - 1. Reihe)
        val channelHistory = historyManager.getRecentLiveChannels()
        if (channelHistory.isNotEmpty()) {
            binding.txtNoChannelHistory.visibility = View.GONE
            binding.recyclerChannelHistory.visibility = View.VISIBLE
            binding.recyclerChannelHistory.adapter = ChannelHistoryAdapter(channelHistory) { stream, pos ->
                playLiveChannel(stream, channelHistory, pos)
            }
        } else {
            binding.txtNoChannelHistory.visibility = View.VISIBLE
            binding.recyclerChannelHistory.visibility = View.GONE
        }

        // 2. Serien-Verlauf (Eindeutige Serien: Nur der aktuellste Stand pro Serie!)
        val rawSeriesHistory = allHistory.filter { isSeriesHistoryItem(it) }
        val distinctSeriesMap = LinkedHashMap<String, HistoryItem>()
        for (item in rawSeriesHistory) {
            val seriesTitle = com.alex.iptvplayer.util.SeriesUtils.cleanSeriesTitle(item.title).lowercase()
            val seriesKey = if (item.seriesId > 0) "id_${item.seriesId}" else seriesTitle
            if (!distinctSeriesMap.containsKey(seriesKey)) {
                distinctSeriesMap[seriesKey] = item
            }
        }
        val seriesHistory = distinctSeriesMap.values.toList()

        if (seriesHistory.isNotEmpty()) {
            binding.txtNoSeriesHistory.visibility = View.GONE
            binding.recyclerSeriesHistory.visibility = View.VISIBLE
            binding.recyclerSeriesHistory.adapter = HistoryAdapter(seriesHistory) { item ->
                playSeriesHistoryItem(item)
            }
        } else {
            binding.txtNoSeriesHistory.visibility = View.VISIBLE
            binding.recyclerSeriesHistory.visibility = View.GONE
        }

        // 3. Film-Verlauf (Eindeutige Filme)
        val rawMoviesHistory = allHistory.filter { isMovieHistoryItem(it) }
        val distinctMoviesMap = LinkedHashMap<String, HistoryItem>()
        for (item in rawMoviesHistory) {
            val movieKey = if (item.streamId > 0) item.streamId.toString() else item.title.trim().lowercase()
            if (!distinctMoviesMap.containsKey(movieKey)) {
                distinctMoviesMap[movieKey] = item
            }
        }
        val moviesHistory = distinctMoviesMap.values.toList()

        if (moviesHistory.isNotEmpty()) {
            binding.txtNoMovieHistory.visibility = View.GONE
            binding.recyclerMovieHistory.visibility = View.VISIBLE
            binding.recyclerMovieHistory.adapter = HistoryAdapter(moviesHistory) { item ->
                playMovieHistoryItem(item)
            }
        } else {
            binding.txtNoMovieHistory.visibility = View.VISIBLE
            binding.recyclerMovieHistory.visibility = View.GONE
        }
    }

    private fun playSeriesHistoryItem(item: HistoryItem) {
        val rawTitle = item.title
        val cleanTitle = com.alex.iptvplayer.util.SeriesUtils.cleanSeriesTitle(rawTitle)
        val matchedSeries = if (item.seriesId > 0) {
            allSeriesList.firstOrNull { it.seriesId == item.seriesId }
        } else {
            com.alex.iptvplayer.util.SeriesUtils.findMatchingSeries(cleanTitle, allSeriesList)
        }

        val intent = Intent(this, SeriesDetailActivity::class.java).apply {
            if (matchedSeries != null) {
                putExtra("SERIES_ITEM", matchedSeries)
                putExtra("SERIES_ID", matchedSeries.seriesId)
            } else if (item.seriesId > 0) {
                putExtra("SERIES_ID", item.seriesId)
            } else {
                putExtra("SERIES_ID", -1)
                putExtra("EPISODE_STREAM_ID", item.streamId)
            }
            putExtra("SERIES_NAME", cleanTitle)
            if (!item.posterUrl.isNullOrEmpty()) {
                putExtra("SERIES_POSTER", item.posterUrl)
            }
            putExtra("TARGET_SEASON", item.season)
            putExtra("TARGET_EPISODE", item.episodeNum)
            val isCompleted = item.progressPercent >= 90
            putExtra("AUTO_PLAY", !isCompleted)
        }
        startActivity(intent)
    }

    private fun playMovieHistoryItem(item: HistoryItem) {
        val poster = item.posterUrl ?: posterLookupMap[item.streamId]
        val intent = Intent(this, MovieDetailActivity::class.java).apply {
            putExtra("STREAM_ID", item.streamId)
            putExtra("STREAM_NAME", item.title)
            putExtra("POSTER_URL", poster)
        }
        startActivity(intent)
    }

    private fun playLiveChannel(s: LiveStream, list: List<LiveStream>, position: Int) {
        historyManager.saveLiveChannel(s)
        val intent = Intent(this, LiveTvActivity::class.java).apply {
            putExtra("TARGET_STREAM_ID", s.streamId)
            putExtra("START_FULLSCREEN", true)
        }
        startActivity(intent)
    }

    private fun formatTime(ms: Long): String {
        val totalSecs = (ms / 1000).coerceAtLeast(0)
        val hours = totalSecs / 3600
        val minutes = (totalSecs % 3600) / 60
        val seconds = totalSecs % 60
        return if (hours > 0) "%02d:%02d:%02d".format(hours, minutes, seconds)
        else "%02d:%02d".format(minutes, seconds)
    }

    // --- Adapter 1: Film & Serien Weiterschauen ---
    inner class HistoryAdapter(
        private val list: List<HistoryItem>,
        private val onClick: (HistoryItem) -> Unit
    ) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val img: ImageView = view.findViewById(R.id.imgHistoryPoster)
            val bar: ProgressBar = view.findViewById(R.id.progressHistoryBar)
            val title: TextView = view.findViewById(R.id.txtHistoryTitle)
            val sub: TextView = view.findViewById(R.id.txtHistorySubtitle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_history_card, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            holder.title.text = item.title
            val isCompleted = item.progressPercent >= 90
            if (isCompleted) {
                holder.sub.text = "✓ Gesehen"
                holder.bar.progress = 100
            } else {
                holder.sub.text = "Bei ${formatTime(item.positionMs)}"
                holder.bar.progress = item.progressPercent
            }

            val poster = item.posterUrl ?: posterLookupMap[item.streamId]

            if (!poster.isNullOrEmpty()) {
                Glide.with(holder.itemView)
                    .load(poster)
                    .override(130, 115)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(android.R.color.transparent)
                    .into(holder.img)
            } else {
                holder.img.setImageResource(android.R.color.transparent)
            }

            holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                holder.title.isSelected = hasFocus
                holder.sub.isSelected = hasFocus
            }

            holder.itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && position >= list.size - 1) {
                    return@setOnKeyListener true
                }
                false
            }

            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = list.size
    }

    // --- Adapter 2: Kompakte TV-Sender Verlaufskacheln ---
    inner class ChannelHistoryAdapter(
        private val list: List<LiveStream>,
        private val onClick: (LiveStream, Int) -> Unit
    ) : RecyclerView.Adapter<ChannelHistoryAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val img: ImageView = view.findViewById(R.id.imgHistoryChannelLogo)
            val txtName: TextView = view.findViewById(R.id.txtHistoryChannelName)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_history_channel, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val stream = list[position]
            holder.txtName.text = stream.name

            if (!stream.streamIcon.isNullOrEmpty()) {
                Glide.with(holder.itemView)
                    .load(stream.streamIcon)
                    .override(40, 40)
                    .placeholder(android.R.color.transparent)
                    .into(holder.img)
            } else {
                holder.img.setImageResource(android.R.color.transparent)
            }

            holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                holder.txtName.isSelected = hasFocus
            }

            holder.itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && position >= list.size - 1) {
                    return@setOnKeyListener true
                }
                false
            }

            holder.itemView.setOnClickListener { onClick(stream, position) }
        }

        override fun getItemCount() = list.size
    }

    companion object {
        private val REGEX_PREFIX = Regex("^(DE|AT|CH|RU|EN|PL|4K-DE|4K-DE-DV|4K-NF|NF|D\\+)\\s*[:|\\-]\\s*", RegexOption.IGNORE_CASE)
        private val REGEX_BRACKETS = Regex("\\[.*?\\]|\\(.*?\\)")
        private val REGEX_SEASONS = Regex("\\bS\\d+(-S\\d+)?\\b", RegexOption.IGNORE_CASE)
        private val REGEX_SPECIAL = Regex("[^\\w\\s]")
        private val REGEX_WHITESPACE = Regex("\\s+")
    }

    // --- TMDb Trending: Beliebte Serien & Filme in deiner Playlist ---
    private fun normalizeTitle(name: String): String {
        if (name.isEmpty()) return ""
        var s = name.replace(REGEX_PREFIX, "")
        s = s.replace(REGEX_BRACKETS, "")
        s = s.replace(REGEX_SEASONS, "")
        s = s.replace(REGEX_SPECIAL, " ")
        return s.replace(REGEX_WHITESPACE, " ").trim().lowercase()
    }

    private fun isQuickMatch(nRaw: String, nTitle: String, nOrig: String): Boolean {
        if (nTitle.isNotEmpty() && (nRaw == nTitle || nRaw.startsWith("$nTitle ") || nRaw.contains(" $nTitle ") || nRaw.endsWith(" $nTitle"))) return true
        if (nOrig.isNotEmpty() && (nRaw == nOrig || nRaw.startsWith("$nOrig ") || nRaw.contains(" $nOrig ") || nRaw.endsWith(" $nOrig"))) return true
        return false
    }

    private data class TmdbTarget(
        val id: Int,
        val title: String,
        val originalTitle: String,
        val normTitle: String,
        val normOrigTitle: String,
        val rating: String,
        val overview: String,
        val matchedSeries: MutableList<SeriesItem> = mutableListOf(),
        val matchedMovies: MutableList<VodStream> = mutableListOf()
    )

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

    private fun loadTrendingContent() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                android.util.Log.i("TrendingDebug", "1. Lade TMDb Trending Series...")
                // 1. 50 TMDb Trending Series laden (Seiten 1, 2, 3)
                val targetSeries = mutableListOf<TmdbTarget>()
                for (page in 1..3) {
                    if (targetSeries.size >= 50) break
                    val tvUrl = "https://api.themoviedb.org/3/trending/tv/week?api_key=$TMDB_API_KEY&language=de-DE&page=$page"
                    val req = Request.Builder().url(tvUrl).header("User-Agent", "Mozilla/5.0").build()
                    val res = tmdbHttpClient.newCall(req).execute()
                    val jsonStr = res.body?.string() ?: ""
                    val results = JSONObject(jsonStr).optJSONArray("results") ?: continue
                    for (i in 0 until results.length()) {
                        if (targetSeries.size >= 50) break
                        val obj = results.getJSONObject(i)
                        val id = obj.optInt("id")
                        val name = obj.optString("name")
                        val origName = obj.optString("original_name")
                        val ratingVal = obj.optDouble("vote_average", 0.0)
                        val ratingStr = if (ratingVal > 0) "%.1f".format(Locale.US, ratingVal) else ""
                        val overview = obj.optString("overview")
                        targetSeries.add(TmdbTarget(id, name, origName, normalizeTitle(name), normalizeTitle(origName), ratingStr, overview))
                    }
                }
                android.util.Log.i("TrendingDebug", "TMDb Series geladen: ${targetSeries.size} Ziele")

                // 2. Playlist Series per Streaming abgleichen (kein 15MB RAM-Dump)
                val seriesApiUrl = "${client.serverUrl}/player_api.php?username=${client.username}&password=${client.password}&action=get_series"
                val seriesReq = Request.Builder().url(seriesApiUrl).header("User-Agent", "IPTVSmartersPro/1.0.0 (Linux; Android 11; TV)").build()
                val seriesResp = tmdbHttpClient.newCall(seriesReq).execute()
                seriesResp.body?.charStream()?.let { charStream ->
                    val reader = JsonReader(charStream)
                    reader.beginArray()
                    while (reader.hasNext()) {
                        reader.beginObject()
                        var sId = 0
                        var sName = ""
                        var sCover = ""
                        var sCatId = ""
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "series_id" -> sId = safeNextInt(reader)
                                "name" -> sName = safeNextString(reader)
                                "cover" -> sCover = safeNextString(reader)
                                "category_id" -> sCatId = safeNextString(reader)
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()

                        if (sName.isNotEmpty() && (client.isGermanMedia(sName) || client.isRussianMedia(sName, sCatId))) {
                            val nRaw = normalizeTitle(sName)
                            if (nRaw.isNotEmpty()) {
                                for (t in targetSeries) {
                                    if (isQuickMatch(nRaw, t.normTitle, t.normOrigTitle)) {
                                        t.matchedSeries.add(SeriesItem(seriesId = sId, name = sName, cover = sCover, categoryId = sCatId))
                                    }
                                }
                            }
                        }
                    }
                    reader.endArray()
                    reader.close()
                }

                val finalSeries = targetSeries.filter { it.matchedSeries.isNotEmpty() }.take(10).map { t ->
                    // Ausschliesslich Playlist-Cover verwenden!
                    val playlistCover = t.matchedSeries.firstOrNull { !it.cover.isNullOrEmpty() }?.cover ?: ""
                    TrendingItem(
                        id = t.id,
                        title = t.title,
                        originalTitle = t.originalTitle,
                        posterUrl = playlistCover,
                        rating = t.rating,
                        overview = t.overview,
                        mediaType = "SERIES",
                        matchedSeries = t.matchedSeries
                    )
                }
                android.util.Log.i("TrendingDebug", "Gematched Serien: ${finalSeries.size}")

                withContext(Dispatchers.Main) {
                    if (finalSeries.isNotEmpty()) {
                        binding.layoutSectionTrendingSeries.visibility = View.VISIBLE
                        binding.recyclerTrendingSeries.adapter = TrendingAdapter(finalSeries) { onTrendingItemClicked(it) }
                    }
                }

                // 3. 50 TMDb Trending Movies laden
                android.util.Log.i("TrendingDebug", "3. Lade TMDb Trending Movies...")
                val targetMovies = mutableListOf<TmdbTarget>()
                for (page in 1..3) {
                    if (targetMovies.size >= 50) break
                    val movieUrl = "https://api.themoviedb.org/3/trending/movie/week?api_key=$TMDB_API_KEY&language=de-DE&page=$page"
                    val req = Request.Builder().url(movieUrl).header("User-Agent", "Mozilla/5.0").build()
                    val res = tmdbHttpClient.newCall(req).execute()
                    val jsonStr = res.body?.string() ?: ""
                    val results = JSONObject(jsonStr).optJSONArray("results") ?: continue
                    for (i in 0 until results.length()) {
                        if (targetMovies.size >= 50) break
                        val obj = results.getJSONObject(i)
                        val id = obj.optInt("id")
                        val title = obj.optString("title")
                        val origTitle = obj.optString("original_title")
                        val ratingVal = obj.optDouble("vote_average", 0.0)
                        val ratingStr = if (ratingVal > 0) "%.1f".format(Locale.US, ratingVal) else ""
                        val overview = obj.optString("overview")
                        targetMovies.add(TmdbTarget(id, title, origTitle, normalizeTitle(title), normalizeTitle(origTitle), ratingStr, overview))
                    }
                }
                android.util.Log.i("TrendingDebug", "TMDb Movies geladen: ${targetMovies.size} Ziele")

                // 4. Playlist Movies per Streaming abgleichen (kein 40MB RAM-Dump)
                val movieApiUrl = "${client.serverUrl}/player_api.php?username=${client.username}&password=${client.password}&action=get_vod_streams"
                val movieReq = Request.Builder().url(movieApiUrl).header("User-Agent", "IPTVSmartersPro/1.0.0 (Linux; Android 11; TV)").build()
                val movieResp = tmdbHttpClient.newCall(movieReq).execute()
                movieResp.body?.charStream()?.let { charStream ->
                    val reader = JsonReader(charStream)
                    reader.beginArray()
                    while (reader.hasNext()) {
                        reader.beginObject()
                        var mId = 0
                        var mName = ""
                        var mIcon = ""
                        var mCatId = ""
                        var mExt = "mp4"
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "stream_id" -> mId = safeNextInt(reader)
                                "name" -> mName = safeNextString(reader)
                                "stream_icon" -> mIcon = safeNextString(reader)
                                "category_id" -> mCatId = safeNextString(reader)
                                "container_extension" -> mExt = safeNextString(reader)
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()

                        if (mName.isNotEmpty() && (client.isGermanMedia(mName) || client.isRussianMedia(mName, mCatId))) {
                            val nRaw = normalizeTitle(mName)
                            if (nRaw.isNotEmpty()) {
                                for (t in targetMovies) {
                                    if (isQuickMatch(nRaw, t.normTitle, t.normOrigTitle)) {
                                        t.matchedMovies.add(VodStream(streamId = mId, name = mName, streamIcon = mIcon, categoryId = mCatId, containerExtension = mExt))
                                    }
                                }
                            }
                        }
                    }
                    reader.endArray()
                    reader.close()
                }

                val finalMovies = targetMovies.filter { it.matchedMovies.isNotEmpty() }.take(10).map { t ->
                    // Ausschliesslich Playlist-Poster verwenden!
                    val playlistPoster = t.matchedMovies.firstOrNull { !it.streamIcon.isNullOrEmpty() }?.streamIcon ?: ""
                    TrendingItem(
                        id = t.id,
                        title = t.title,
                        originalTitle = t.originalTitle,
                        posterUrl = playlistPoster,
                        rating = t.rating,
                        overview = t.overview,
                        mediaType = "MOVIE",
                        matchedMovies = t.matchedMovies
                    )
                }
                android.util.Log.i("TrendingDebug", "Gematched Filme: ${finalMovies.size}")

                // Cache auf Disk speichern fuer sofortige Verfuegbarkeit beim naechsten Start
                saveCachedTrending(finalSeries, finalMovies)

                withContext(Dispatchers.Main) {
                    if (finalMovies.isNotEmpty()) {
                        binding.layoutSectionTrendingMovies.visibility = View.VISIBLE
                        binding.recyclerTrendingMovies.adapter = TrendingAdapter(finalMovies) { onTrendingItemClicked(it) }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("TrendingDebug", "Fehler beim Laden von Trending", e)
            }
        }
    }

    private fun onTrendingItemClicked(item: TrendingItem) {
        if (item.mediaType == "SERIES") {
            val seriesList = item.matchedSeries.filter { client.isGermanMedia(it.name) || client.isRussianMedia(it.name, it.categoryId) }
            val pool = if (seriesList.isNotEmpty()) seriesList else item.matchedSeries
            if (pool.size == 1) {
                openSeriesDetail(pool[0])
            } else if (pool.size > 1) {
                val titles = pool.map { it.name }.toTypedArray()
                AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("${item.title} – Version wählen:")
                    .setItems(titles) { _, which ->
                        openSeriesDetail(pool[which])
                    }
                    .setNegativeButton("Abbrechen", null)
                    .show()
            }
        } else {
            val movieList = item.matchedMovies.filter { client.isGermanMedia(it.name) || client.isRussianMedia(it.name, it.categoryId) }
            val pool = if (movieList.isNotEmpty()) movieList else item.matchedMovies
            if (pool.size == 1) {
                playMovie(pool[0])
            } else if (pool.size > 1) {
                val titles = pool.map { it.name }.toTypedArray()
                AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("${item.title} – Version wählen:")
                    .setItems(titles) { _, which ->
                        playMovie(pool[which])
                    }
                    .setNegativeButton("Abbrechen", null)
                    .show()
            }
        }
    }

    private fun openSeriesDetail(series: SeriesItem) {
        val intent = Intent(this, SeriesDetailActivity::class.java).apply {
            putExtra("SERIES_ITEM", series)
            putExtra("SERIES_ID", series.seriesId)
            putExtra("SERIES_NAME", series.name)
        }
        startActivity(intent)
    }

    private fun playMovie(movie: VodStream) {
        val intent = Intent(this, MovieDetailActivity::class.java).apply {
            putExtra("VOD_STREAM", movie)
            putExtra("STREAM_ID", movie.streamId)
            putExtra("STREAM_NAME", movie.name)
            putExtra("POSTER_URL", movie.streamIcon)
            putExtra("CONTAINER_EXT", movie.containerExtension ?: "mp4")
        }
        startActivity(intent)
    }

    // --- Adapter 3: Beliebte Serien & Filme Kacheln ---
    inner class TrendingAdapter(
        private val list: List<TrendingItem>,
        private val onClick: (TrendingItem) -> Unit
    ) : RecyclerView.Adapter<TrendingAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val imgPoster: ImageView = view.findViewById(R.id.imgTrendingPoster)
            val txtTitle: TextView = view.findViewById(R.id.txtTrendingTitle)
            val txtSubtitle: TextView = view.findViewById(R.id.txtTrendingSubtitle)
            val txtBadge: TextView = view.findViewById(R.id.txtTrendingBadge)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_trending_card, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            holder.txtTitle.text = item.title

            holder.txtSubtitle.visibility = View.GONE

            if (item.rating.isNotEmpty()) {
                holder.txtBadge.text = "★ ${item.rating}"
                holder.txtBadge.visibility = View.VISIBLE
            } else {
                holder.txtBadge.visibility = View.GONE
            }

            if (item.posterUrl.isNotEmpty()) {
                Glide.with(holder.itemView)
                    .load(item.posterUrl)
                    .override(120, 125)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(android.R.color.transparent)
                    .into(holder.imgPoster)
            } else {
                holder.imgPoster.setImageResource(android.R.color.transparent)
            }

            holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                holder.txtTitle.isSelected = hasFocus
            }

            holder.itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && position >= list.size - 1) {
                    return@setOnKeyListener true
                }
                false
            }

            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = list.size
    }
}

