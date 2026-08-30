package com.alex.iptvplayer.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.alex.iptvplayer.R
import com.alex.iptvplayer.data.LiveStream
import com.alex.iptvplayer.data.SeriesItem
import com.alex.iptvplayer.data.VodStream
import com.alex.iptvplayer.data.XtreamClient
import com.alex.iptvplayer.databinding.ActivitySearchBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private lateinit var client: XtreamClient
    private var searchJob: Job? = null

    // Cache aller Einträge für blitzschnelle Suche
    private var allLiveStreams = mutableListOf<LiveStream>()
    private var allVodStreams = mutableListOf<VodStream>()
    private var allSeries = mutableListOf<SeriesItem>()
    private var isDataLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        client = XtreamClient(this)

        binding.recyclerSearchChannels.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.recyclerSearchMovies.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.recyclerSearchSeries.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        binding.btnSearchClear.setOnClickListener {
            binding.editSearchQuery.setText("")
        }

        binding.editSearchQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s?.toString()?.trim() ?: ""
                performSearch(query)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        loadAllDataForSearch()
    }

    private fun loadAllDataForSearch() {
        binding.progressSearch.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                // Lade Live Streams, Movies und Series parallel
                val live = client.getLiveStreams()
                val vod = client.getVodStreams()
                val series = client.getSeries()

                allLiveStreams.addAll(live)
                allVodStreams.addAll(vod)
                allSeries.addAll(series)
                isDataLoaded = true
                binding.progressSearch.visibility = View.GONE

                val currentQuery = binding.editSearchQuery.text.toString().trim()
                if (currentQuery.isNotEmpty()) {
                    performSearch(currentQuery)
                }
            } catch (e: Exception) {
                binding.progressSearch.visibility = View.GONE
            }
        }
    }

    private fun performSearch(query: String) {
        searchJob?.cancel()
        if (query.length < 2) {
            binding.headerChannels.visibility = View.GONE
            binding.recyclerSearchChannels.visibility = View.GONE
            binding.headerMovies.visibility = View.GONE
            binding.recyclerSearchMovies.visibility = View.GONE
            binding.headerSeries.visibility = View.GONE
            binding.recyclerSearchSeries.visibility = View.GONE
            binding.txtNoResults.text = "Tippe mindestens 2 Buchstaben ein"
            binding.txtNoResults.visibility = View.VISIBLE
            return
        }

        searchJob = lifecycleScope.launch {
            delay(250) // 250ms Debounce für flüssiges Tippen

            val q = query.lowercase()
            val matchedChannels = allLiveStreams.filter { it.name.lowercase().contains(q) }.take(30)
            val matchedMovies = allVodStreams.filter { it.name.lowercase().contains(q) }.take(30)
            val matchedSeries = allSeries.filter { it.name.lowercase().contains(q) }.take(30)

            val hasResults = matchedChannels.isNotEmpty() || matchedMovies.isNotEmpty() || matchedSeries.isNotEmpty()
            binding.txtNoResults.visibility = if (hasResults) View.GONE else View.VISIBLE
            if (!hasResults) {
                binding.txtNoResults.text = "Keine Treffer für „$query“ gefunden."
            }

            // 1. Channels
            if (matchedChannels.isNotEmpty()) {
                binding.headerChannels.visibility = View.VISIBLE
                binding.recyclerSearchChannels.visibility = View.VISIBLE
                binding.recyclerSearchChannels.adapter = ChannelSearchAdapter(matchedChannels)
            } else {
                binding.headerChannels.visibility = View.GONE
                binding.recyclerSearchChannels.visibility = View.GONE
            }

            // 2. Movies
            if (matchedMovies.isNotEmpty()) {
                binding.headerMovies.visibility = View.VISIBLE
                binding.recyclerSearchMovies.visibility = View.VISIBLE
                binding.recyclerSearchMovies.adapter = MovieSearchAdapter(matchedMovies)
            } else {
                binding.headerMovies.visibility = View.GONE
                binding.recyclerSearchMovies.visibility = View.GONE
            }

            // 3. Series
            if (matchedSeries.isNotEmpty()) {
                binding.headerSeries.visibility = View.VISIBLE
                binding.recyclerSearchSeries.visibility = View.VISIBLE
                binding.recyclerSearchSeries.adapter = SeriesSearchAdapter(matchedSeries)
            } else {
                binding.headerSeries.visibility = View.GONE
                binding.recyclerSearchSeries.visibility = View.GONE
            }
        }
    }

    // --- Adapter für Gefundene Sender ---
    inner class ChannelSearchAdapter(private val list: List<LiveStream>) :
        RecyclerView.Adapter<ChannelSearchAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val img: ImageView = view.findViewById(R.id.imgChannelLogo)
            val txt: TextView = view.findViewById(R.id.txtChannelName)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = list[position]
            holder.txt.text = item.name
            if (!item.streamIcon.isNullOrEmpty()) {
                Glide.with(holder.itemView).load(item.streamIcon).override(80, 80).into(holder.img)
            } else {
                holder.img.setImageResource(R.drawable.tv_banner)
            }
            holder.itemView.setOnClickListener {
                val intent = Intent(this@SearchActivity, PlayerActivity::class.java).apply {
                    putExtra("STREAM_URL", client.getLiveStreamUrl(item.streamId))
                    putExtra("STREAM_NAME", item.name)
                    putExtra("STREAM_ID", item.streamId)
                }
                startActivity(intent)
            }
        }

        override fun getItemCount() = list.size
    }

    // --- Adapter für Gefundene Filme ---
    inner class MovieSearchAdapter(private val list: List<VodStream>) :
        RecyclerView.Adapter<MovieSearchAdapter.ViewHolder>() {

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
                    .override(180, 260)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .into(holder.img)
            } else {
                holder.img.setImageResource(R.drawable.tv_banner)
            }
            holder.itemView.setOnClickListener {
                val intent = Intent(this@SearchActivity, PlayerActivity::class.java).apply {
                    putExtra("STREAM_URL", client.getVodStreamUrl(movie.streamId, movie.containerExtension ?: "mp4"))
                    putExtra("STREAM_NAME", movie.name)
                }
                startActivity(intent)
            }
        }

        override fun getItemCount() = list.size
    }

    // --- Adapter für Gefundene Serien ---
    inner class SeriesSearchAdapter(private val list: List<SeriesItem>) :
        RecyclerView.Adapter<SeriesSearchAdapter.ViewHolder>() {

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
                    .override(180, 260)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .into(holder.img)
            } else {
                holder.img.setImageResource(R.drawable.tv_banner)
            }
            holder.itemView.setOnClickListener {
                val intent = Intent(this@SearchActivity, SeriesDetailActivity::class.java).apply {
                    putExtra("SERIES_ITEM", s)
                }
                startActivity(intent)
            }
        }

        override fun getItemCount() = list.size
    }
}
