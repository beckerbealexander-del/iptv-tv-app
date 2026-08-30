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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.alex.iptvplayer.R
import com.alex.iptvplayer.data.Category
import com.alex.iptvplayer.data.LangFilter
import com.alex.iptvplayer.data.VodStream
import com.alex.iptvplayer.data.XtreamClient
import com.alex.iptvplayer.databinding.ActivityVodBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import kotlinx.coroutines.launch

class VodActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVodBinding
    private lateinit var client: XtreamClient
    private var allCategories: List<Category> = emptyList()
    private var currentFilter = LangFilter.AUTO_DE_RU_ADULT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVodBinding.inflate(layoutInflater)
        setContentView(binding.root)

        client = XtreamClient(this)

        binding.recyclerVodCategories.apply {
            layoutManager = LinearLayoutManager(this@VodActivity)
            setHasFixedSize(true)
            setItemViewCacheSize(25)
        }

        binding.recyclerVodGrid.apply {
            layoutManager = GridLayoutManager(this@VodActivity, 5)
            setHasFixedSize(true)
            setItemViewCacheSize(30)
        }

        setupFilterButtons()
        loadCategories()
    }

    private fun setupFilterButtons() {
        binding.btnVodFilterDe.setOnClickListener { applyFilter(LangFilter.DE) }
        binding.btnVodFilterRu.setOnClickListener { applyFilter(LangFilter.RU) }
        binding.btnVodFilterAdult.setOnClickListener { applyFilter(LangFilter.ADULT) }
        binding.btnVodFilterAll.setOnClickListener { applyFilter(LangFilter.ALL) }
    }

    private fun applyFilter(filter: LangFilter) {
        currentFilter = filter
        val filtered = client.filterCategories(allCategories, filter)
        binding.recyclerVodCategories.adapter = VodCategoryAdapter(filtered) { cat ->
            loadMovies(cat)
        }
        if (filtered.isNotEmpty()) {
            loadMovies(filtered[0])
        }
    }

    private fun loadCategories() {
        binding.progressVodCats.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                allCategories = client.getVodCategories()
                binding.progressVodCats.visibility = View.GONE
                applyFilter(currentFilter)
            } catch (e: Exception) {
                binding.progressVodCats.visibility = View.GONE
                Toast.makeText(this@VodActivity, "Fehler: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadMovies(category: Category) {
        binding.txtVodCategoryTitle.text = category.name
        binding.progressVod.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val movies = client.getVodStreams(category.id)
                binding.progressVod.visibility = View.GONE
                binding.recyclerVodGrid.adapter = MovieAdapter(movies, { movie ->
                    updateHeroBanner(movie)
                }, { movie ->
                    playMovie(movie)
                })

                if (movies.isNotEmpty()) {
                    updateHeroBanner(movies[0])
                }
            } catch (e: Exception) {
                binding.progressVod.visibility = View.GONE
                Toast.makeText(this@VodActivity, "Fehler beim Laden: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateHeroBanner(movie: VodStream) {
        binding.txtHeroTitle.text = movie.name
        binding.txtHeroRating.text = if (!movie.rating.isNullOrEmpty()) "⭐ ${movie.rating} | VOD" else "⭐ 8.0 | VOD"

        if (!movie.streamIcon.isNullOrEmpty()) {
            Glide.with(this)
                .load(movie.streamIcon)
                .override(180, 260)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(R.drawable.tv_banner)
                .into(binding.imgHeroPoster)
        } else {
            binding.imgHeroPoster.setImageResource(R.drawable.tv_banner)
        }
    }

    private fun playMovie(movie: VodStream) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("STREAM_URL", client.getVodStreamUrl(movie.streamId, movie.containerExtension ?: "mp4"))
            putExtra("STREAM_NAME", movie.name)
        }
        startActivity(intent)
    }

    inner class VodCategoryAdapter(
        private val items: List<Category>,
        private val onSelect: (Category) -> Unit
    ) : RecyclerView.Adapter<VodCategoryAdapter.ViewHolder>() {

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

    inner class MovieAdapter(
        private val items: List<VodStream>,
        private val onFocus: (VodStream) -> Unit,
        private val onClick: (VodStream) -> Unit
    ) : RecyclerView.Adapter<MovieAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val txtTitle: TextView = view.findViewById(R.id.txtPosterTitle)
            val imgPoster: ImageView = view.findViewById(R.id.imgPoster)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_poster, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val movie = items[position]
            holder.txtTitle.text = movie.name

            if (!movie.streamIcon.isNullOrEmpty()) {
                Glide.with(holder.itemView)
                    .load(movie.streamIcon)
                    .override(180, 260)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(R.drawable.tv_banner)
                    .into(holder.imgPoster)
            } else {
                holder.imgPoster.setImageResource(R.drawable.tv_banner)
            }

            holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onFocus(movie)
            }

            holder.itemView.setOnClickListener { onClick(movie) }
        }

        override fun getItemCount() = items.size
    }
}
