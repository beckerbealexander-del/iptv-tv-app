package com.alex.iptvplayer.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.alex.iptvplayer.R
import com.alex.iptvplayer.data.EpisodeItem
import com.alex.iptvplayer.data.SeasonItem
import com.alex.iptvplayer.data.SeriesInfoResponse
import com.alex.iptvplayer.data.SeriesItem
import com.alex.iptvplayer.data.XtreamClient
import com.alex.iptvplayer.databinding.ActivitySeriesDetailBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import kotlinx.coroutines.launch

class SeriesDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySeriesDetailBinding
    private lateinit var client: XtreamClient
    private var seriesItem: SeriesItem? = null
    private var seriesInfo: SeriesInfoResponse? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySeriesDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        client = XtreamClient(this)

        @Suppress("DEPRECATION")
        seriesItem = intent.getSerializableExtra("SERIES_ITEM") as? SeriesItem

        if (seriesItem == null) {
            finish()
            return
        }

        binding.recyclerSeasons.apply {
            layoutManager = LinearLayoutManager(this@SeriesDetailActivity, LinearLayoutManager.HORIZONTAL, false)
            setHasFixedSize(true)
        }

        binding.recyclerEpisodes.apply {
            layoutManager = LinearLayoutManager(this@SeriesDetailActivity)
            setHasFixedSize(true)
            setItemViewCacheSize(30)
        }

        displayInitialInfo()
        loadFullSeriesInfo()
    }

    private fun displayInitialInfo() {
        val s = seriesItem ?: return
        binding.txtDetailSeriesTitle.text = s.name
        binding.txtDetailSeriesPlot.text = s.plot ?: "Lade Details..."
        binding.txtDetailSeriesRating.text = if (!s.rating.isNullOrEmpty()) "⭐ ${s.rating}" else "⭐ 8.0"

        if (!s.cover.isNullOrEmpty()) {
            Glide.with(this)
                .load(s.cover)
                .override(320, 420)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(R.drawable.tv_banner)
                .into(binding.imgDetailSeriesCover)
        }
    }

    private fun loadFullSeriesInfo() {
        val s = seriesItem ?: return
        binding.progressEpisodes.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val info = client.getSeriesInfo(s.seriesId)
                seriesInfo = info
                binding.progressEpisodes.visibility = View.GONE

                // Aktualisiere Beschreibung & Genre aus Info
                if (info.info != null) {
                    if (!info.info.plot.isNullOrEmpty()) binding.txtDetailSeriesPlot.text = info.info.plot
                    if (!info.info.genre.isNullOrEmpty()) binding.txtDetailSeriesGenre.text = info.info.genre
                    if (!info.info.rating.isNullOrEmpty()) binding.txtDetailSeriesRating.text = "⭐ ${info.info.rating}"
                }

                val seasons = info.seasons ?: emptyList()
                if (seasons.isNotEmpty()) {
                    binding.recyclerSeasons.adapter = SeasonAdapter(seasons) { season ->
                        loadEpisodesForSeason(season.seasonNumber)
                    }
                    // Staffel 1 standardmäßig laden
                    loadEpisodesForSeason(seasons[0].seasonNumber)
                } else {
                    // Fallback wenn keine seasons deklariert sind: alle Episoden
                    val all = mutableListOf<EpisodeItem>()
                    info.episodes?.values?.forEach { all.addAll(it) }
                    binding.recyclerEpisodes.adapter = EpisodeAdapter(all)
                }
            } catch (e: Exception) {
                binding.progressEpisodes.visibility = View.GONE
                Toast.makeText(this@SeriesDetailActivity, "Fehler: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadEpisodesForSeason(seasonNum: Int) {
        val epList = seriesInfo?.episodes?.get(seasonNum.toString()) ?: emptyList()
        binding.recyclerEpisodes.adapter = EpisodeAdapter(epList)
    }

    private fun playEpisode(ep: EpisodeItem) {
        val sName = seriesItem?.name ?: "Serie"
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("STREAM_URL", client.getSeriesStreamUrl(ep.id, ep.containerExtension ?: "mp4"))
            putExtra("STREAM_NAME", "$sName - S${ep.season}E${ep.episodeNum} ${ep.title}")
            putExtra("POSTER_URL", ep.info?.movieImage ?: seriesItem?.cover)
            putExtra("STREAM_TYPE", "SERIES")
            putExtra("STREAM_ID", ep.id)
            putExtra("SEASON_NUM", ep.season)
            putExtra("EPISODE_NUM", ep.episodeNum)
        }
        startActivity(intent)
    }

    inner class SeasonAdapter(
        private val seasons: List<SeasonItem>,
        private val onSelect: (SeasonItem) -> Unit
    ) : RecyclerView.Adapter<SeasonAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val txtName: TextView = view.findViewById(R.id.txtSeasonName)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_season_tab, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val season = seasons[position]
            holder.txtName.text = season.name ?: "Staffel ${season.seasonNumber}"
            holder.itemView.setOnClickListener { onSelect(season) }
            holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onSelect(season)
            }
        }

        override fun getItemCount() = seasons.size
    }

    inner class EpisodeAdapter(
        private val episodes: List<EpisodeItem>
    ) : RecyclerView.Adapter<EpisodeAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val txtTitle: TextView = view.findViewById(R.id.txtEpisodeNumAndTitle)
            val txtDuration: TextView = view.findViewById(R.id.txtEpisodeDuration)
            val txtPlot: TextView = view.findViewById(R.id.txtEpisodePlot)
            val imgThumb: ImageView = view.findViewById(R.id.imgEpisodeThumb)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_episode_card, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val ep = episodes[position]
            holder.txtTitle.text = "Folge ${ep.episodeNum}: ${ep.title}"
            holder.txtDuration.text = ep.info?.duration ?: ""
            val plotText = ep.info?.plot
            holder.txtPlot.text = if (!plotText.isNullOrEmpty()) plotText else "Keine Beschreibung verfügbar."

            val imgUrl = ep.info?.movieImage
            if (!imgUrl.isNullOrEmpty()) {
                Glide.with(holder.itemView)
                    .load(imgUrl)
                    .override(200, 120)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(R.drawable.tv_banner)
                    .into(holder.imgThumb)
            } else {
                holder.imgThumb.setImageResource(R.drawable.tv_banner)
            }

            holder.itemView.setOnClickListener { playEpisode(ep) }
        }

        override fun getItemCount() = episodes.size
    }
}
