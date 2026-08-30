package com.alex.iptvplayer.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.alex.iptvplayer.R
import com.alex.iptvplayer.data.HistoryItem
import com.alex.iptvplayer.data.HistoryManager
import com.alex.iptvplayer.data.LiveStream
import com.alex.iptvplayer.data.SeriesItem
import com.alex.iptvplayer.data.XtreamClient
import com.alex.iptvplayer.databinding.ActivityMainBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var client: XtreamClient
    private lateinit var historyManager: HistoryManager

    private val posterLookupMap = HashMap<Int, String>()
    private var allSeriesList: List<SeriesItem> = emptyList()
    private val episodePattern = Regex(" - S\\d+E\\d+", RegexOption.IGNORE_CASE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        client = XtreamClient(this)
        historyManager = HistoryManager(this)

        setupSidebar()
        setupRecyclers()
        preloadPosters()

        historyManager.syncWithCloud(client.username) {
            runOnUiThread { loadAllHistoryRows() }
        }
    }

    override fun onResume() {
        super.onResume()
        loadAllHistoryRows()
    }

    private fun preloadPosters() {
        lifecycleScope.launch {
            try {
                val movies = client.getAllVodStreams()
                movies.forEach { m ->
                    if (!m.streamIcon.isNullOrEmpty()) posterLookupMap[m.streamId] = m.streamIcon
                }
                val series = client.getAllSeries()
                allSeriesList = series
                series.forEach { s ->
                    if (!s.cover.isNullOrEmpty()) posterLookupMap[s.seriesId] = s.cover
                }
                loadAllHistoryRows()
            } catch (e: Exception) {
                // Silent
            }
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
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            setHasFixedSize(true)
        }
        binding.recyclerSeriesHistory.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            setHasFixedSize(true)
        }
        binding.recyclerMovieHistory.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
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
            val seriesTitle = if (episodePattern.containsMatchIn(item.title)) {
                item.title.substringBefore(" - S").trim().lowercase()
            } else {
                item.title.trim().lowercase()
            }
            if (!distinctSeriesMap.containsKey(seriesTitle)) {
                distinctSeriesMap[seriesTitle] = item
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
        val seriesTitle = item.title.substringBefore(" - S").trim()
        val matchedSeries = allSeriesList.firstOrNull { it.name.trim().equals(seriesTitle, ignoreCase = true) }
            ?: allSeriesList.firstOrNull { it.name.contains(seriesTitle, ignoreCase = true) }

        val intent = Intent(this, SeriesDetailActivity::class.java).apply {
            if (matchedSeries != null) {
                putExtra("SERIES_ITEM", matchedSeries)
                putExtra("SERIES_ID", matchedSeries.seriesId)
            } else {
                putExtra("SERIES_ID", item.streamId)
            }
            putExtra("SERIES_NAME", seriesTitle)
            putExtra("TARGET_SEASON", item.season)
            putExtra("TARGET_EPISODE", item.episodeNum)
            putExtra("AUTO_PLAY", true)
        }
        startActivity(intent)
    }

    private fun playMovieHistoryItem(item: HistoryItem) {
        val poster = item.posterUrl ?: posterLookupMap[item.streamId]
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("STREAM_URL", item.streamUrl)
            putExtra("STREAM_NAME", item.title)
            putExtra("POSTER_URL", poster)
            putExtra("STREAM_TYPE", "VOD")
            putExtra("STREAM_ID", item.streamId)
        }
        startActivity(intent)
    }

    private fun playLiveChannel(s: LiveStream, list: List<LiveStream>, position: Int) {
        historyManager.saveLiveChannel(s)
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("STREAM_URL", client.getLiveStreamUrl(s.streamId))
            putExtra("STREAM_NAME", s.name)
            putExtra("POSTER_URL", s.streamIcon)
            putExtra("STREAM_ID", s.streamId)
            putExtra("STREAM_TYPE", "LIVE")
            putExtra("STREAM_LIST", ArrayList(list))
            putExtra("CURRENT_INDEX", position)
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
            holder.sub.text = "Bei ${formatTime(item.positionMs)}"
            holder.bar.progress = item.progressPercent

            val poster = item.posterUrl ?: posterLookupMap[item.streamId]

            if (!poster.isNullOrEmpty()) {
                Glide.with(holder.itemView)
                    .load(poster)
                    .override(130, 115)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(R.drawable.tv_banner)
                    .into(holder.img)
            } else {
                holder.img.setImageResource(R.drawable.tv_banner)
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
                    .into(holder.img)
            } else {
                holder.img.setImageResource(R.drawable.tv_banner)
            }

            holder.itemView.setOnClickListener { onClick(stream, position) }
        }

        override fun getItemCount() = list.size
    }
}
