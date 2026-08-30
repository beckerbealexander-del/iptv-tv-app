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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.alex.iptvplayer.R
import com.alex.iptvplayer.data.Category
import com.alex.iptvplayer.data.LangFilter
import com.alex.iptvplayer.data.LiveStream
import com.alex.iptvplayer.data.XtreamClient
import com.alex.iptvplayer.databinding.ActivityLiveTvBinding
import com.bumptech.glide.Glide
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LiveTvActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLiveTvBinding
    private lateinit var client: XtreamClient

    private var allCategories: List<Category> = emptyList()
    private var currentFilter = LangFilter.AUTO_DE_RU_ADULT
    private var currentStreams: List<LiveStream> = emptyList()
    private var selectedCategoryId: String? = null
    private var epgJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLiveTvBinding.inflate(layoutInflater)
        setContentView(binding.root)

        client = XtreamClient(this)

        binding.recyclerCategories.apply {
            layoutManager = LinearLayoutManager(this@LiveTvActivity)
            setHasFixedSize(true)
            setItemViewCacheSize(30)
        }

        binding.recyclerChannels.apply {
            layoutManager = LinearLayoutManager(this@LiveTvActivity)
            setHasFixedSize(true)
            setItemViewCacheSize(40)
        }

        setupFilterButtons()
        setupSearch()
        loadCategories()
    }

    private fun setupSearch() {
        binding.editLiveSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString()?.trim()?.lowercase() ?: ""
                val filtered = if (q.isEmpty()) currentStreams else currentStreams.filter { it.name.lowercase().contains(q) }
                binding.recyclerChannels.adapter = ChannelAdapter(filtered, { stream ->
                    showChannelDetailAndEpg(stream)
                }, { stream, position ->
                    openFullscreenPlayer(stream, position)
                })
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupFilterButtons() {
        binding.btnLiveFilterDe.setOnClickListener { applyFilter(LangFilter.DE) }
        binding.btnLiveFilterRu.setOnClickListener { applyFilter(LangFilter.RU) }
        binding.btnLiveFilterAdult.setOnClickListener { applyFilter(LangFilter.ADULT) }
        binding.btnLiveFilterAll.setOnClickListener { applyFilter(LangFilter.ALL) }
    }

    private fun applyFilter(filter: LangFilter) {
        currentFilter = filter
        val filtered = client.filterCategories(allCategories, filter)
        binding.recyclerCategories.adapter = CategoryAdapter(filtered) { category ->
            loadChannels(category)
        }
        if (filtered.isNotEmpty()) {
            loadChannels(filtered[0])
        }
    }

    private fun loadCategories() {
        binding.progressCategories.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                allCategories = client.getLiveCategories()
                binding.progressCategories.visibility = View.GONE
                applyFilter(currentFilter)
            } catch (e: Exception) {
                binding.progressCategories.visibility = View.GONE
                Toast.makeText(this@LiveTvActivity, "Fehler: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadChannels(category: Category) {
        if (selectedCategoryId == category.id) return
        selectedCategoryId = category.id

        binding.txtCategoryTitle.text = category.name
        binding.progressChannels.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                currentStreams = client.getLiveStreams(category.id)
                binding.progressChannels.visibility = View.GONE
                binding.recyclerChannels.adapter = ChannelAdapter(currentStreams, { stream ->
                    showChannelDetailAndEpg(stream)
                }, { stream, position ->
                    openFullscreenPlayer(stream, position)
                })

                if (currentStreams.isNotEmpty()) {
                    showChannelDetailAndEpg(currentStreams[0])
                }
            } catch (e: Exception) {
                binding.progressChannels.visibility = View.GONE
                Toast.makeText(this@LiveTvActivity, "Fehler beim Laden: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showChannelDetailAndEpg(stream: LiveStream) {
        binding.txtDetailName.text = stream.name

        if (!stream.streamIcon.isNullOrEmpty()) {
            Glide.with(this).load(stream.streamIcon).override(80, 80).into(binding.imgDetailLogo)
        } else {
            binding.imgDetailLogo.setImageResource(R.drawable.tv_banner)
        }

        epgJob?.cancel()
        binding.txtEpgCurrentTitle.text = "Lade EPG..."
        binding.txtEpgCurrentTime.text = ""
        binding.txtEpgCurrentDesc.text = ""
        binding.boxNextProgram.visibility = View.GONE

        epgJob = lifecycleScope.launch {
            val list = client.getEpg(stream.streamId)
            if (list.isNotEmpty()) {
                val current = list.firstOrNull { it.isNowPlaying } ?: list[0]
                binding.txtEpgCurrentTitle.text = current.title
                binding.txtEpgCurrentTime.text = "${current.start} - ${current.end}"
                binding.txtEpgCurrentDesc.text = current.description

                if (list.size > 1) {
                    val next = if (current == list[0]) list[1] else list.getOrNull(list.indexOf(current) + 1)
                    if (next != null) {
                        binding.boxNextProgram.visibility = View.VISIBLE
                        binding.txtEpgNextTitle.text = next.title
                        binding.txtEpgNextTime.text = "Ab ${next.start} Uhr"
                    }
                }
            } else {
                binding.txtEpgCurrentTitle.text = "Kein EPG verfügbar"
                binding.txtEpgCurrentTime.text = ""
                binding.txtEpgCurrentDesc.text = "Für diesen Sender liegen aktuell keine Programmdaten vor."
            }
        }
    }

    private fun openFullscreenPlayer(stream: LiveStream, position: Int) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("STREAM_URL", client.getLiveStreamUrl(stream.streamId))
            putExtra("STREAM_NAME", stream.name)
            putExtra("POSTER_URL", stream.streamIcon)
            putExtra("STREAM_ID", stream.streamId)
            putExtra("STREAM_TYPE", "LIVE")
            putExtra("STREAM_LIST", ArrayList(currentStreams))
            putExtra("CURRENT_INDEX", position)
        }
        startActivity(intent)
    }

    override fun onPause() {
        super.onPause()
        epgJob?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        epgJob?.cancel()
    }

    inner class CategoryAdapter(
        private val items: List<Category>,
        private val onSelect: (Category) -> Unit
    ) : RecyclerView.Adapter<CategoryAdapter.ViewHolder>() {

        private var focusJob: Job? = null

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
                if (hasFocus) {
                    focusJob?.cancel()
                    focusJob = lifecycleScope.launch {
                        delay(350)
                        onSelect(cat)
                    }
                }
            }
        }

        override fun getItemCount() = items.size
    }

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
                Glide.with(holder.itemView).load(s.streamIcon).override(60, 60).into(holder.imgLogo)
            } else {
                holder.imgLogo.setImageResource(R.drawable.tv_banner)
            }

            holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onFocus(s)
            }

            holder.itemView.setOnClickListener { onClick(s, position) }
        }

        override fun getItemCount() = items.size
    }
}
