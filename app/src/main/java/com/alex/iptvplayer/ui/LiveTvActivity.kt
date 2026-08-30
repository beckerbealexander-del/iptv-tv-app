package com.alex.iptvplayer.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
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
import com.alex.iptvplayer.data.EpgProgram
import com.alex.iptvplayer.data.HistoryManager
import com.alex.iptvplayer.data.LangFilter
import com.alex.iptvplayer.data.LiveStream
import com.alex.iptvplayer.data.XtreamClient
import com.alex.iptvplayer.databinding.ActivityLiveTvBinding
import com.alex.iptvplayer.util.PlayerUtils
import com.bumptech.glide.Glide
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LiveTvActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLiveTvBinding
    private lateinit var client: XtreamClient
    private lateinit var historyManager: HistoryManager

    private var allCategories: List<Category> = emptyList()
    private var currentFilter = LangFilter.AUTO_DE_RU_ADULT
    private var currentStreams: List<LiveStream> = emptyList()
    private var selectedCategoryId: String? = null
    private val channelCategoryCache = HashMap<String, List<LiveStream>>()
    private val epgCache = HashMap<Int, List<EpgProgram>>()

    // PIP Mini-Player
    private var pipPlayer: ExoPlayer? = null
    private var activePipStream: LiveStream? = null

    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLiveTvBinding.inflate(layoutInflater)
        setContentView(binding.root)

        client = XtreamClient(this)
        historyManager = HistoryManager(this)

        setupPipPlayer()
        updateLiveTimeHeader()

        binding.recyclerCategories.apply {
            layoutManager = LinearLayoutManager(this@LiveTvActivity)
            setHasFixedSize(true)
            setItemViewCacheSize(60)
        }

        binding.recyclerChannels.apply {
            layoutManager = LinearLayoutManager(this@LiveTvActivity)
            setHasFixedSize(true)
            setItemViewCacheSize(80)
        }

        setupFilterButtons()
        setupSearch()
        loadCategories()
    }

    private fun setupPipPlayer() {
        pipPlayer = PlayerUtils.createExoPlayer(this).apply {
            binding.livePipPlayerView.player = this
            playWhenReady = true
        }
    }

    override fun onResume() {
        super.onResume()
        val lastWatched = historyManager.getRecentLiveChannels().firstOrNull()
        if (lastWatched != null) {
            playPipStream(lastWatched)
        } else if (activePipStream != null) {
            playPipStream(activePipStream!!)
        }
    }

    override fun onPause() {
        super.onPause()
        pipPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        pipPlayer?.release()
        pipPlayer = null
    }

    private fun playPipStream(stream: LiveStream) {
        if (activePipStream?.streamId == stream.streamId && pipPlayer?.isPlaying == true) return
        activePipStream = stream
        val url = client.getLiveStreamUrl(stream.streamId)
        val mediaItem = MediaItem.fromUri(url)
        pipPlayer?.setMediaItem(mediaItem)
        pipPlayer?.prepare()
        pipPlayer?.playWhenReady = true
    }

    private fun updateLiveTimeHeader() {
        val sdf = SimpleDateFormat("EEE, dd. MMM 'um' HH:mm", Locale.GERMANY)
        binding.txtCurrentLiveTime.text = "🔴 ${sdf.format(Date())}"
    }

    private fun setupSearch() {
        // Tastatur schließen bei Bestätigung
        binding.editLiveSearch.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(binding.editLiveSearch.windowToken, 0)
                binding.editLiveSearch.clearFocus()
                binding.recyclerChannels.requestFocus()
                true
            } else false
        }

        binding.editLiveSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchJob?.cancel()
                val q = s?.toString()?.trim()?.lowercase() ?: ""
                searchJob = lifecycleScope.launch {
                    delay(300) // Debounce
                    val filtered = if (q.isEmpty()) currentStreams else currentStreams.filter { it.name.lowercase().contains(q) }
                    binding.recyclerChannels.adapter = ChannelAdapter(filtered)
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupFilterButtons() {
        binding.btnLiveFilterDe.setOnClickListener { applyFilter(LangFilter.DE) }
        binding.btnLiveFilterRu.setOnClickListener { applyFilter(LangFilter.RU) }
        binding.btnLiveFilterAll.setOnClickListener { applyFilter(LangFilter.ALL) }
    }

    private fun applyFilter(filter: LangFilter) {
        currentFilter = filter
        val filtered = client.filterCategories(allCategories, filter)
        binding.recyclerCategories.adapter = CategoryAdapter(filtered) { category ->
            loadChannels(category)
        }

        // Vorauswahl: Zuletzt gesehener Sender
        val lastWatched = historyManager.getRecentLiveChannels().firstOrNull()
        if (lastWatched != null && !lastWatched.categoryId.isNullOrEmpty()) {
            val matchingCat = filtered.firstOrNull { it.id == lastWatched.categoryId }
            if (matchingCat != null) {
                loadChannels(matchingCat, preselectedStreamId = lastWatched.streamId)
                return
            }
        }

        if (filtered.isNotEmpty() && selectedCategoryId == null) {
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

    private fun loadChannels(category: Category, preselectedStreamId: Int? = null) {
        if (selectedCategoryId == category.id && preselectedStreamId == null && currentStreams.isNotEmpty()) return
        selectedCategoryId = category.id

        val cached = channelCategoryCache[category.id]
        if (cached != null && preselectedStreamId == null) {
            currentStreams = cached
            binding.progressChannels.visibility = View.GONE
            binding.recyclerChannels.adapter = ChannelAdapter(cached)
            return
        }

        binding.progressChannels.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val list = client.getLiveStreams(category.id)
                channelCategoryCache[category.id] = list
                currentStreams = list
                binding.progressChannels.visibility = View.GONE
                binding.recyclerChannels.adapter = ChannelAdapter(currentStreams)

                val targetStream = if (preselectedStreamId != null) {
                    currentStreams.firstOrNull { it.streamId == preselectedStreamId } ?: currentStreams.firstOrNull()
                } else {
                    currentStreams.firstOrNull()
                }

                if (targetStream != null) {
                    showChannelPreview(targetStream, null)
                    playPipStream(targetStream)

                    val targetPos = currentStreams.indexOf(targetStream).coerceAtLeast(0)
                    if (targetPos > 0) {
                        binding.recyclerChannels.scrollToPosition(targetPos)
                        binding.recyclerChannels.post {
                            val holder = binding.recyclerChannels.findViewHolderForAdapterPosition(targetPos) as? ChannelAdapter.ViewHolder
                            holder?.header?.requestFocus()
                        }
                    }
                }
            } catch (e: Exception) {
                binding.progressChannels.visibility = View.GONE
                Toast.makeText(this@LiveTvActivity, "Fehler beim Laden: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showChannelPreview(stream: LiveStream, program: EpgProgram?) {
        if (program != null) {
            binding.txtPreviewTitle.text = "${stream.name} – ${program.title}"
            binding.txtPreviewTime.text = "${program.start} - ${program.end}"
            binding.txtPreviewDesc.text = if (program.description.isNotEmpty()) program.description else "Keine Programmbeschreibung vorhanden."
        } else {
            val cached = epgCache[stream.streamId]?.firstOrNull { it.isNowPlaying } ?: epgCache[stream.streamId]?.firstOrNull()
            if (cached != null) {
                binding.txtPreviewTitle.text = "${stream.name} – ${cached.title}"
                binding.txtPreviewTime.text = "${cached.start} - ${cached.end}"
                binding.txtPreviewDesc.text = if (cached.description.isNotEmpty()) cached.description else "Keine Programmbeschreibung vorhanden."
            } else {
                binding.txtPreviewTitle.text = stream.name
                binding.txtPreviewTime.text = "🔴 LIVE"
                binding.txtPreviewDesc.text = "Drücke OK auf der Fernbedienung, um den Sender direkt zu starten."
            }
        }
    }

    private fun openFullscreenPlayer(stream: LiveStream, position: Int) {
        pipPlayer?.pause()
        historyManager.saveLiveChannel(stream)
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            val focused = currentFocus
            if (isViewInRecyclerView(focused, binding.recyclerChannels)) {
                val catHolder = binding.recyclerCategories.findViewHolderForAdapterPosition(0)
                catHolder?.itemView?.requestFocus() ?: binding.recyclerCategories.requestFocus()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // --- Absolute Hardware D-Pad Sperre: Fokus kann die Senderliste NIEMALS ungewollt verlassen ---
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val focused = currentFocus
            val channelPos = getFocusedChannelPosition(focused)

            if (channelPos != -1) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (channelPos < currentStreams.size - 1) {
                            val nextPos = channelPos + 1
                            binding.recyclerChannels.scrollToPosition(nextPos)
                            binding.recyclerChannels.post {
                                val holder = binding.recyclerChannels.findViewHolderForAdapterPosition(nextPos) as? ChannelAdapter.ViewHolder
                                holder?.header?.requestFocus()
                            }
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (channelPos > 0) {
                            val prevPos = channelPos - 1
                            binding.recyclerChannels.scrollToPosition(prevPos)
                            binding.recyclerChannels.post {
                                val holder = binding.recyclerChannels.findViewHolderForAdapterPosition(prevPos) as? ChannelAdapter.ViewHolder
                                holder?.header?.requestFocus()
                            }
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        // Links blockieren (Wechsel zurück zu Kategorien nur über BACK-Taste)
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isViewInRecyclerView(view: View?, rv: RecyclerView): Boolean {
        var current: View? = view
        while (current != null) {
            if (current == rv) return true
            val parent = current.parent
            current = parent as? View
        }
        return false
    }

    private fun getFocusedChannelPosition(view: View?): Int {
        var current: View? = view
        while (current != null && current != binding.recyclerChannels) {
            val parent = current.parent
            if (parent == binding.recyclerChannels) {
                return binding.recyclerChannels.getChildAdapterPosition(current)
            }
            current = parent as? View
        }
        return -1
    }

    // --- Adapter 1: Kategorien ---
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
            holder.itemView.isSelected = (cat.id == selectedCategoryId)

            // Kategorie wird AUSSCHLIESSLICH bei Klick mit OK gewechselt!
            holder.itemView.setOnClickListener {
                selectedCategoryId = cat.id
                holder.itemView.requestFocus()
                onSelect(cat)
            }
        }

        override fun getItemCount() = items.size
    }

    // --- Adapter 2: Senderzeilen mit horizontalem EPG Timeline Grid ---
    inner class ChannelAdapter(
        private val items: List<LiveStream>
    ) : RecyclerView.Adapter<ChannelAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val header: View = view.findViewById(R.id.channelHeader)
            val txtNum: TextView = view.findViewById(R.id.txtChannelNum)
            val imgLogo: ImageView = view.findViewById(R.id.imgChannelLogo)
            val txtName: TextView = view.findViewById(R.id.txtChannelName)
            val recyclerPrograms: RecyclerView = view.findViewById(R.id.recyclerChannelPrograms)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_epg_channel_row, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val s = items[position]
            holder.txtNum.text = "${position + 1}"
            holder.txtName.text = s.name

            if (!s.streamIcon.isNullOrEmpty()) {
                Glide.with(holder.itemView).load(s.streamIcon).override(36, 36).into(holder.imgLogo)
            } else {
                holder.imgLogo.setImageResource(R.drawable.tv_banner)
            }

            holder.header.setOnClickListener {
                openFullscreenPlayer(s, position)
            }

            holder.header.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    showChannelPreview(s, null)
                }
            }

            holder.recyclerPrograms.apply {
                layoutManager = LinearLayoutManager(holder.itemView.context, LinearLayoutManager.HORIZONTAL, false)
                setHasFixedSize(true)
            }

            val cached = epgCache[s.streamId]
            if (cached != null) {
                holder.recyclerPrograms.adapter = ProgramTimelineAdapter(s, position, cached, holder)
            } else {
                val fallback = listOf(EpgProgram("Lade EPG...", "", "16:00", "23:59", true))
                holder.recyclerPrograms.adapter = ProgramTimelineAdapter(s, position, fallback, holder)

                lifecycleScope.launch {
                    val epgList = client.getEpg(s.streamId)
                    if (epgList.isNotEmpty()) {
                        epgCache[s.streamId] = epgList
                        holder.recyclerPrograms.adapter = ProgramTimelineAdapter(s, position, epgList, holder)
                    }
                }
            }
        }

        override fun getItemCount() = items.size
    }

    // --- Adapter 3: Horizontale EPG-Sendungsblöcke (Timeline) ---
    inner class ProgramTimelineAdapter(
        private val stream: LiveStream,
        private val channelIndex: Int,
        private val programs: List<EpgProgram>,
        private val channelHolder: ChannelAdapter.ViewHolder
    ) : RecyclerView.Adapter<ProgramTimelineAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val txtTitle: TextView = view.findViewById(R.id.txtEpgProgramTitle)
            val txtTime: TextView = view.findViewById(R.id.txtEpgProgramTime)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_epg_program_block, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val p = programs[position]
            holder.txtTitle.text = p.title
            holder.txtTime.text = "${p.start} - ${p.end}"

            if (p.isNowPlaying) {
                holder.txtTitle.setTextColor(resources.getColor(R.color.netflix_red, null))
            } else {
                holder.txtTitle.setTextColor(resources.getColor(R.color.text_primary, null))
            }

            holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    showChannelPreview(stream, p)
                }
            }

            holder.itemView.setOnClickListener {
                openFullscreenPlayer(stream, channelIndex)
            }

            holder.itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && position == 0) {
                        channelHolder.header.requestFocus()
                        return@setOnKeyListener true
                    }
                }
                false
            }
        }

        override fun getItemCount() = programs.size
    }
}
