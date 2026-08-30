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
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.alex.iptvplayer.R
import com.alex.iptvplayer.data.Category
import com.alex.iptvplayer.data.LiveStream
import com.alex.iptvplayer.data.XtreamClient
import com.alex.iptvplayer.databinding.ActivityLiveTvBinding
import com.bumptech.glide.Glide
import kotlinx.coroutines.launch

class LiveTvActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLiveTvBinding
    private lateinit var client: XtreamClient
    private var exoPlayer: ExoPlayer? = null

    private var currentStreams: List<LiveStream> = emptyList()
    private var selectedStream: LiveStream? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLiveTvBinding.inflate(layoutInflater)
        setContentView(binding.root)

        client = XtreamClient(this)
        setupPlayer()

        binding.recyclerCategories.layoutManager = LinearLayoutManager(this)
        binding.recyclerChannels.layoutManager = LinearLayoutManager(this)

        loadCategories()
    }

    private fun setupPlayer() {
        exoPlayer = com.alex.iptvplayer.util.PlayerUtils.createExoPlayer(this)
        binding.miniPlayerView.player = exoPlayer
    }

    private fun loadCategories() {
        binding.progressCategories.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val cats = client.getLiveCategories()
                binding.progressCategories.visibility = View.GONE
                binding.recyclerCategories.adapter = CategoryAdapter(cats) { category ->
                    loadChannels(category)
                }
                // Erste Kategorie automatisch laden
                if (cats.isNotEmpty()) {
                    loadChannels(cats[0])
                }
            } catch (e: Exception) {
                binding.progressCategories.visibility = View.GONE
                Toast.makeText(this@LiveTvActivity, "Fehler beim Laden: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadChannels(category: Category) {
        binding.txtCategoryTitle.text = category.name
        binding.progressChannels.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                currentStreams = client.getLiveStreams(category.id)
                binding.progressChannels.visibility = View.GONE
                binding.recyclerChannels.adapter = ChannelAdapter(currentStreams, { stream ->
                    playPreview(stream)
                }, { stream, position ->
                    openFullscreenPlayer(stream, position)
                })
            } catch (e: Exception) {
                binding.progressChannels.visibility = View.GONE
                Toast.makeText(this@LiveTvActivity, "Fehler beim Laden der Sender: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun playPreview(stream: LiveStream) {
        selectedStream = stream
        binding.txtSelectedChannelName.text = stream.name
        val streamUrl = client.getLiveStreamUrl(stream.streamId)
        val mediaItem = MediaItem.fromUri(streamUrl)
        exoPlayer?.setMediaItem(mediaItem)
        exoPlayer?.prepare()
        exoPlayer?.playWhenReady = true
    }

    private fun openFullscreenPlayer(stream: LiveStream, position: Int) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("STREAM_URL", client.getLiveStreamUrl(stream.streamId))
            putExtra("STREAM_NAME", stream.name)
            putExtra("STREAM_LIST", ArrayList(currentStreams))
            putExtra("CURRENT_INDEX", position)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
        exoPlayer = null
    }

    // --- Adapter für Kategorien ---
    inner class CategoryAdapter(
        private val items: List<Category>,
        private val onSelect: (Category) -> Unit
    ) : RecyclerView.Adapter<CategoryAdapter.ViewHolder>() {

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

    // --- Adapter für Sender ---
    inner class ChannelAdapter(
        private val items: List<LiveStream>,
        private val onFocus: (LiveStream) -> Unit,
        private val onClick: (LiveStream, Int) -> Unit
    ) : RecyclerView.Adapter<ChannelAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val txtName: TextView = view.findViewById(R.id.txtChannelName)
            val imgLogo: ImageView = view.findViewById(R.id.imgChannelLogo)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val s = items[position]
            holder.txtName.text = s.name

            if (!s.streamIcon.isNullOrEmpty()) {
                Glide.with(holder.itemView).load(s.streamIcon).into(holder.imgLogo)
            } else {
                holder.imgLogo.setImageResource(R.drawable.tv_banner)
            }

            holder.itemView.setOnClickListener { onClick(s, position) }
            holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onFocus(s)
            }
        }

        override fun getItemCount() = items.size
    }
}
