package com.alex.iptvplayer.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.alex.iptvplayer.R
import com.alex.iptvplayer.data.Category
import com.alex.iptvplayer.data.EpisodeItem
import com.alex.iptvplayer.data.SeriesItem
import com.alex.iptvplayer.data.XtreamClient
import com.alex.iptvplayer.databinding.ActivitySeriesBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import kotlinx.coroutines.launch

class SeriesActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySeriesBinding
    private lateinit var client: XtreamClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySeriesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        client = XtreamClient(this)

        binding.recyclerSeriesCategories.apply {
            layoutManager = LinearLayoutManager(this@SeriesActivity)
            setHasFixedSize(true)
            setItemViewCacheSize(25)
        }

        binding.recyclerSeriesGrid.apply {
            layoutManager = GridLayoutManager(this@SeriesActivity, 5)
            setHasFixedSize(true)
            setItemViewCacheSize(30)
        }

        loadCategories()
    }

    private fun loadCategories() {
        lifecycleScope.launch {
            try {
                val cats = client.getSeriesCategories()
                binding.recyclerSeriesCategories.adapter = SeriesCategoryAdapter(cats) { category ->
                    loadSeries(category)
                }
                if (cats.isNotEmpty()) {
                    loadSeries(cats[0])
                }
            } catch (e: Exception) {
                Toast.makeText(this@SeriesActivity, "Fehler: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadSeries(category: Category) {
        binding.txtSeriesCategoryTitle.text = category.name
        binding.progressSeries.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val list = client.getSeries(category.id)
                binding.progressSeries.visibility = View.GONE
                binding.recyclerSeriesGrid.adapter = SeriesAdapter(list) { series ->
                    showEpisodesDialog(series)
                }
            } catch (e: Exception) {
                binding.progressSeries.visibility = View.GONE
                Toast.makeText(this@SeriesActivity, "Fehler beim Laden: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showEpisodesDialog(series: SeriesItem) {
        lifecycleScope.launch {
            try {
                val info = client.getSeriesInfo(series.seriesId)
                val allEpisodes = mutableListOf<EpisodeItem>()
                info.episodes?.values?.forEach { list -> allEpisodes.addAll(list) }

                if (allEpisodes.isEmpty()) {
                    Toast.makeText(this@SeriesActivity, "Keine Episoden verfügbar", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val titles = allEpisodes.map { "Staffel ${it.season} - Ep ${it.episodeNum}: ${it.title}" }.toTypedArray()
                AlertDialog.Builder(this@SeriesActivity, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
                    .setTitle(series.name)
                    .setItems(titles) { _, which ->
                        val ep = allEpisodes[which]
                        playEpisode(series.name, ep)
                    }
                    .setNegativeButton("Zurück", null)
                    .show()
            } catch (e: Exception) {
                Toast.makeText(this@SeriesActivity, "Fehler beim Laden der Episoden: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playEpisode(seriesName: String, ep: EpisodeItem) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("STREAM_URL", client.getSeriesStreamUrl(ep.id, ep.containerExtension ?: "mp4"))
            putExtra("STREAM_NAME", "$seriesName - S${ep.season}E${ep.episodeNum}")
        }
        startActivity(intent)
    }

    inner class SeriesCategoryAdapter(
        private val items: List<Category>,
        private val onSelect: (Category) -> Unit
    ) : RecyclerView.Adapter<SeriesCategoryAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val txtName: TextView = view.findViewById(R.id.txtCategoryName)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val cat = items[position]
            holder.txtName.text = cat.name
            holder.itemView.setOnClickListener { onSelect(cat) }
            holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onSelect(cat)
            }
        }

        override fun getItemCount() = items.size
    }

    inner class SeriesAdapter(
        private val items: List<SeriesItem>,
        private val onClick: (SeriesItem) -> Unit
    ) : RecyclerView.Adapter<SeriesAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val txtTitle: TextView = view.findViewById(R.id.txtPosterTitle)
            val imgPoster: ImageView = view.findViewById(R.id.imgPoster)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_poster, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val s = items[position]
            holder.txtTitle.text = s.name

            if (!s.cover.isNullOrEmpty()) {
                Glide.with(holder.itemView)
                    .load(s.cover)
                    .override(180, 260)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(R.drawable.tv_banner)
                    .into(holder.imgPoster)
            } else {
                holder.imgPoster.setImageResource(R.drawable.tv_banner)
            }

            holder.itemView.setOnClickListener { onClick(s) }
        }

        override fun getItemCount() = items.size
    }
}
