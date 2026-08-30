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
import com.alex.iptvplayer.data.LangFilter
import com.alex.iptvplayer.data.LiveStream
import com.alex.iptvplayer.data.SeriesItem
import com.alex.iptvplayer.data.VodStream
import com.alex.iptvplayer.data.XtreamClient
import com.alex.iptvplayer.databinding.ActivityMainBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var client: XtreamClient
    private lateinit var historyManager: HistoryManager

    private var activeHeroPlayAction: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        client = XtreamClient(this)
        historyManager = HistoryManager(this)

        setupSidebar()
        setupCarousels()
    }

    override fun onResume() {
        super.onResume()
        loadHistoryRow()
        loadCarouselsContent()
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
        binding.btnMainHeroPlay.setOnClickListener {
            activeHeroPlayAction?.invoke()
        }

        binding.navHome.requestFocus()
    }

    private fun setupCarousels() {
        binding.recyclerMainHistory.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            setHasFixedSize(true)
        }
        binding.recyclerMainMovies.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            setHasFixedSize(true)
            setItemViewCacheSize(30)
        }
        binding.recyclerMainSeries.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            setHasFixedSize(true)
            setItemViewCacheSize(30)
        }
        binding.recyclerMainLive.apply {
            layoutManager = LinearLayoutManager(this@MainActivity, LinearLayoutManager.HORIZONTAL, false)
            setHasFixedSize(true)
            setItemViewCacheSize(30)
        }
    }

    private fun loadHistoryRow() {
        val history = historyManager.getHistory()
        if (history.isNotEmpty()) {
            binding.layoutSectionHistory.visibility = View.VISIBLE
            binding.recyclerMainHistory.adapter = HistoryAdapter(history) { item ->
                playHistoryItem(item)
            }
            val top = history[0]
            updateHeroBanner(
                title = top.title,
                subtitle = "🕒 Fortsetzen bei ${formatTime(top.positionMs)}",
                posterUrl = top.posterUrl,
                btnText = "▶ Weiter ansehen"
            ) {
                playHistoryItem(top)
            }
        } else {
            binding.layoutSectionHistory.visibility = View.GONE
        }
    }

    private fun loadCarouselsContent() {
        binding.progressMainHome.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                // 1. Deutsche Filme laden
                val vodCats = client.getVodCategories()
                val deVodCat = client.filterCategories(vodCats, LangFilter.DE).firstOrNull() ?: vodCats.firstOrNull()
                if (deVodCat != null) {
                    val movies = client.getVodStreams(deVodCat.id)
                    binding.recyclerMainMovies.adapter = MovieRowAdapter(movies)
                }

                // 2. Serien laden
                val seriesCats = client.getSeriesCategories()
                val deSeriesCat = client.filterCategories(seriesCats, LangFilter.DE).firstOrNull() ?: seriesCats.firstOrNull()
                if (deSeriesCat != null) {
                    val series = client.getSeries(deSeriesCat.id)
                    binding.recyclerMainSeries.adapter = SeriesRowAdapter(series)
                }

                // 3. Live Sender laden
                val liveCats = client.getLiveCategories()
                val deLiveCat = client.filterCategories(liveCats, LangFilter.DE).firstOrNull() ?: liveCats.firstOrNull()
                if (deLiveCat != null) {
                    val channels = client.getLiveStreams(deLiveCat.id)
                    binding.recyclerMainLive.adapter = LiveRowAdapter(channels)
                }

                binding.progressMainHome.visibility = View.GONE
            } catch (e: Exception) {
                binding.progressMainHome.visibility = View.GONE
            }
        }
    }

    private fun updateHeroBanner(
        title: String,
        subtitle: String,
        posterUrl: String?,
        btnText: String,
        onPlay: () -> Unit
    ) {
        binding.txtMainHeroTitle.text = title
        binding.txtMainHeroSubtitle.text = subtitle
        binding.btnMainHeroPlay.text = btnText
        activeHeroPlayAction = onPlay

        if (!posterUrl.isNullOrEmpty()) {
            Glide.with(this)
                .load(posterUrl)
                .override(180, 240)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(R.drawable.tv_banner)
                .into(binding.imgMainHero)
        } else {
            binding.imgMainHero.setImageResource(R.drawable.tv_banner)
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

    private fun formatTime(ms: Long): String {
        val totalSecs = (ms / 1000).coerceAtLeast(0)
        val hours = totalSecs / 3600
        val minutes = (totalSecs % 3600) / 60
        val seconds = totalSecs % 60
        return if (hours > 0) "%02d:%02d:%02d".format(hours, minutes, seconds)
        else "%02d:%02d".format(minutes, seconds)
    }

    // --- Adapter 1: Weiterschauen ---
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
                    .override(130, 180)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(R.drawable.tv_banner)
                    .into(holder.img)
            } else {
                holder.img.setImageResource(R.drawable.tv_banner)
            }

            holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    updateHeroBanner(
                        title = item.title,
                        subtitle = "🕒 Fortsetzen bei ${formatTime(item.positionMs)}",
                        posterUrl = item.posterUrl,
                        btnText = "▶ Jetzt Weiterschauen"
                    ) {
                        onClick(item)
                    }
                }
            }

            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = list.size
    }

    // --- Adapter 2: Filme Reihe ---
    inner class MovieRowAdapter(private val list: List<VodStream>) :
        RecyclerView.Adapter<MovieRowAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val img: ImageView = view.findViewById(R.id.imgPoster)
            val txt: TextView = view.findViewById(R.id.txtPosterTitle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_poster, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val movie = list[position]
            holder.txt.text = movie.name

            if (!movie.streamIcon.isNullOrEmpty()) {
                Glide.with(holder.itemView)
                    .load(movie.streamIcon)
                    .override(130, 180)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .into(holder.img)
            } else {
                holder.img.setImageResource(R.drawable.tv_banner)
            }

            holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    updateHeroBanner(
                        title = movie.name,
                        subtitle = "⭐ ${movie.rating ?: "8.2"} | Film",
                        posterUrl = movie.streamIcon,
                        btnText = "▶ Film abspielen"
                    ) {
                        playMovie(movie)
                    }
                }
            }

            holder.itemView.setOnClickListener { playMovie(movie) }
        }

        private fun playMovie(movie: VodStream) {
            val intent = Intent(this@MainActivity, PlayerActivity::class.java).apply {
                putExtra("STREAM_URL", client.getVodStreamUrl(movie.streamId, movie.containerExtension ?: "mp4"))
                putExtra("STREAM_NAME", movie.name)
                putExtra("POSTER_URL", movie.streamIcon)
                putExtra("STREAM_ID", movie.streamId)
                putExtra("STREAM_TYPE", "VOD")
            }
            startActivity(intent)
        }

        override fun getItemCount() = list.size
    }

    // --- Adapter 3: Serien Reihe ---
    inner class SeriesRowAdapter(private val list: List<SeriesItem>) :
        RecyclerView.Adapter<SeriesRowAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val img: ImageView = view.findViewById(R.id.imgPoster)
            val txt: TextView = view.findViewById(R.id.txtPosterTitle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_poster, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val s = list[position]
            holder.txt.text = s.name

            if (!s.cover.isNullOrEmpty()) {
                Glide.with(holder.itemView)
                    .load(s.cover)
                    .override(130, 180)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .into(holder.img)
            } else {
                holder.img.setImageResource(R.drawable.tv_banner)
            }

            holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    updateHeroBanner(
                        title = s.name,
                        subtitle = "⭐ ${s.rating ?: "8.5"} | Staffeln & Folgen",
                        posterUrl = s.cover,
                        btnText = "▶ Staffeln ansehen"
                    ) {
                        openSeries(s)
                    }
                }
            }

            holder.itemView.setOnClickListener { openSeries(s) }
        }

        private fun openSeries(s: SeriesItem) {
            val intent = Intent(this@MainActivity, SeriesDetailActivity::class.java).apply {
                putExtra("SERIES_ITEM", s)
            }
            startActivity(intent)
        }

        override fun getItemCount() = list.size
    }

    // --- Adapter 4: Live TV Reihe ---
    inner class LiveRowAdapter(private val list: List<LiveStream>) :
        RecyclerView.Adapter<LiveRowAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val img: ImageView = view.findViewById(R.id.imgChannelLogo)
            val txt: TextView = view.findViewById(R.id.txtChannelName)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val s = list[position]
            holder.txt.text = s.name

            if (!s.streamIcon.isNullOrEmpty()) {
                Glide.with(holder.itemView)
                    .load(s.streamIcon)
                    .override(60, 60)
                    .into(holder.img)
            } else {
                holder.img.setImageResource(R.drawable.tv_banner)
            }

            holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    updateHeroBanner(
                        title = s.name,
                        subtitle = "🔴 Live TV Sender",
                        posterUrl = s.streamIcon,
                        btnText = "▶ Live einschalten"
                    ) {
                        playLive(s, position)
                    }
                }
            }

            holder.itemView.setOnClickListener { playLive(s, position) }
        }

        private fun playLive(s: LiveStream, position: Int) {
            val intent = Intent(this@MainActivity, PlayerActivity::class.java).apply {
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

        override fun getItemCount() = list.size
    }
}
