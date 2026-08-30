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
import com.alex.iptvplayer.data.VodStream
import com.alex.iptvplayer.data.XtreamClient
import com.alex.iptvplayer.databinding.ActivityVodBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import kotlinx.coroutines.launch

class VodActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVodBinding
    private lateinit var client: XtreamClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVodBinding.inflate(layoutInflater)
        setContentView(binding.root)

        client = XtreamClient(this)

        // Hardware-optimierte RecyclerViews gegen Lags
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

        loadCategories()
    }

    private fun loadCategories() {
        lifecycleScope.launch {
            try {
                val cats = client.getVodCategories()
                binding.recyclerVodCategories.adapter = VodCategoryAdapter(cats) { category ->
                    loadMovies(category)
                }
                if (cats.isNotEmpty()) {
                    loadMovies(cats[0])
                }
            } catch (e: Exception) {
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
                binding.recyclerVodGrid.adapter = MovieAdapter(movies) { movie ->
                    playMovie(movie)
                }
            } catch (e: Exception) {
                binding.progressVod.visibility = View.GONE
                Toast.makeText(this@VodActivity, "Fehler beim Laden: ${e.message}", Toast.LENGTH_SHORT).show()
            }
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

            // Kategorie nur bei echtem Klick laden, verhindert Scroll-Lags
            holder.itemView.setOnClickListener { onSelect(cat) }
            holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onSelect(cat)
            }
        }

        override fun getItemCount() = items.size
    }

    inner class MovieAdapter(
        private val items: List<VodStream>,
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

            // Performance-optimiertes Laden mit kleinem Thumbnail & Cache
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

            holder.itemView.setOnClickListener { onClick(movie) }
        }

        override fun getItemCount() = items.size
    }
}
