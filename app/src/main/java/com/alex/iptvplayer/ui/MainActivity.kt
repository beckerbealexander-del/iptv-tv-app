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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.alex.iptvplayer.R
import com.alex.iptvplayer.data.HistoryItem
import com.alex.iptvplayer.data.HistoryManager
import com.alex.iptvplayer.data.LiveStream
import com.alex.iptvplayer.data.XtreamClient
import com.alex.iptvplayer.databinding.ActivityMainBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var client: XtreamClient
    private lateinit var historyManager: HistoryManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        client = XtreamClient(this)
        historyManager = HistoryManager(this)

        setupSidebar()
        setupRecyclers()

        historyManager.syncWithCloud(client.username) {
            runOnUiThread { loadAllHistoryRows() }
        }
    }

    override fun onResume() {
        super.onResume()
        loadAllHistoryRows()
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

        binding.navLiveTv.requestFocus()
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

        // 2. Serien-Verlauf
        val seriesHistory = allHistory.filter { 
            it.type == "SERIES" || 
            it.streamUrl.contains("/series/") || 
            it.title.contains(" - S") 
        }
        if (seriesHistory.isNotEmpty()) {
            binding.txtNoSeriesHistory.visibility = View.GONE
            binding.recyclerSeriesHistory.visibility = View.VISIBLE
            binding.recyclerSeriesHistory.adapter = HistoryAdapter(seriesHistory) { item ->
                playHistoryItem(item)
            }
        } else {
            binding.txtNoSeriesHistory.visibility = View.VISIBLE
            binding.recyclerSeriesHistory.visibility = View.GONE
        }

        // 3. Film-Verlauf
        val moviesHistory = allHistory.filter { 
            (it.type == "VOD" || it.streamUrl.contains("/movie/")) && 
            !it.streamUrl.contains("/series/") && 
            !it.title.contains(" - S") 
        }
        if (moviesHistory.isNotEmpty()) {
            binding.txtNoMovieHistory.visibility = View.GONE
            binding.recyclerMovieHistory.visibility = View.VISIBLE
            binding.recyclerMovieHistory.adapter = HistoryAdapter(moviesHistory) { item ->
                playHistoryItem(item)
            }
        } else {
            binding.txtNoMovieHistory.visibility = View.VISIBLE
            binding.recyclerMovieHistory.visibility = View.GONE
        }
    }

    private fun playHistoryItem(item: HistoryItem) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("STREAM_URL", item.streamUrl)
            putExtra("STREAM_NAME", item.title)
            putExtra("POSTER_URL", item.posterUrl)
            putExtra("STREAM_TYPE", item.type)
            putExtra("STREAM_ID", item.streamId)
            putExtra("SEASON_NUM", item.season)
            putExtra("EPISODE_NUM", item.episodeNum)
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

            if (!item.posterUrl.isNullOrEmpty()) {
                Glide.with(holder.itemView)
                    .load(item.posterUrl)
                    .override(110, 150)
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
