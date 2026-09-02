package com.alex.iptvplayer.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LiveTvActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLiveTvBinding
    private lateinit var client: XtreamClient
    private lateinit var historyManager: HistoryManager

    private var allCategories: List<Category> = emptyList()
    private var displayedCategories: List<Category> = emptyList()
    private var currentStreams: List<LiveStream> = emptyList()
    private var allLiveStreamsGlobal: List<LiveStream> = emptyList()
    private var selectedCategoryId: String? = null
    private val channelCategoryCache = HashMap<String, List<LiveStream>>()
    private val epgCache = HashMap<Int, List<EpgProgram>>()

    private var isInitialLoad = true
    private var currentSearchQuery: String? = null

    // PIP Mini-Player
    private var pipPlayer: ExoPlayer? = null
    private var activePipStream: LiveStream? = null

    // Vollbild-Suche Launcher
    private val searchLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val query = result.data?.getStringExtra("SEARCH_QUERY")?.trim() ?: ""
            if (query.isNotEmpty()) {
                applySearchQuery(query)
            }
        }
    }

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

        binding.btnOpenLiveSearch.setOnClickListener {
            val intent = Intent(this, SearchActivity::class.java).apply {
                putExtra("SEARCH_TYPE", "LIVE")
            }
            searchLauncher.launch(intent)
        }

        loadCategories()
        preloadGlobalChannels()
    }

    private fun preloadGlobalChannels() {
        lifecycleScope.launch {
            try {
                allLiveStreamsGlobal = client.getAllLiveStreams()
            } catch (e: Exception) {
                // Fallback
            }
        }
    }

    private fun setupPipPlayer() {
        pipPlayer = PlayerUtils.createExoPlayer(this).apply {
            binding.livePipPlayerView.player = this
            playWhenReady = true
        }
    }

    // 1. KEIN NEUAUFBAU NACH VOLLBILD:
    // Der State bleibt vollständig im Speicher erhalten, keine Neuladung bei Rückkehr aus dem Player
    override fun onResume() {
        super.onResume()
        if (pipPlayer != null && activePipStream != null && !pipPlayer!!.isPlaying) {
            pipPlayer?.play()
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

    // 5. SUCHE: DYNAMISCHE KATEGORIE "🔍 Aktuelle Suche" AN INDEX 0
    private fun applySearchQuery(query: String) {
        currentSearchQuery = query
        val searchCategory = Category(id = "CURRENT_SEARCH", name = "🔍 Aktuelle Suche")
        val newCategories = mutableListOf(searchCategory)
        newCategories.addAll(allCategories)
        displayedCategories = newCategories
        selectedCategoryId = "CURRENT_SEARCH"

        binding.recyclerCategories.adapter = CategoryAdapter(newCategories) { cat ->
            loadChannels(cat)
        }

        val pool = if (allLiveStreamsGlobal.isNotEmpty()) allLiveStreamsGlobal else currentStreams
        val filtered = pool.filter { it.name.contains(query, ignoreCase = true) }
        currentStreams = filtered
        binding.recyclerChannels.adapter = ChannelAdapter(filtered)

        if (filtered.isNotEmpty()) {
            showChannelPreview(filtered[0], null)
            focusTargetChannel(0)
        }
    }

    private fun loadCategories() {
        binding.progressCategories.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val raw = client.getLiveCategories()
                allCategories = client.filterCategories(raw, LangFilter.AUTO_DE_RU_ADULT)
                displayedCategories = allCategories
                binding.progressCategories.visibility = View.GONE
                binding.recyclerCategories.adapter = CategoryAdapter(displayedCategories) { category ->
                    loadChannels(category)
                }

                // Initialer Start: Letzter Sender oder erster Sender laden
                val lastWatched = historyManager.getRecentLiveChannels().firstOrNull()
                if (lastWatched != null && !lastWatched.categoryId.isNullOrEmpty()) {
                    val matchingCat = displayedCategories.firstOrNull { it.id == lastWatched.categoryId }
                    if (matchingCat != null) {
                        loadChannels(matchingCat, preselectedStreamId = lastWatched.streamId)
                        return@launch
                    }
                }

                if (displayedCategories.isNotEmpty()) {
                    loadChannels(displayedCategories[0])
                }
            } catch (e: Exception) {
                binding.progressCategories.visibility = View.GONE
                Toast.makeText(this@LiveTvActivity, "Fehler: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 3. KATEGORIEWECHSEL & PIP-VERHALTEN:
    // Fokus geht sofort auf den 1. Sender (niemals Suchfeld).
    // PIP spielt UNVERÄNDERT den aktuellen Sender weiter.
    private fun loadChannels(category: Category, preselectedStreamId: Int? = null) {
        if (category.id == "CURRENT_SEARCH" && currentSearchQuery != null) {
            applySearchQuery(currentSearchQuery!!)
            return
        }

        if (selectedCategoryId == category.id && preselectedStreamId == null && currentStreams.isNotEmpty()) {
            focusFirstChannel()
            return
        }

        selectedCategoryId = category.id
        binding.recyclerCategories.adapter?.notifyDataSetChanged()

        val cached = channelCategoryCache[category.id]
        if (cached != null && preselectedStreamId == null) {
            currentStreams = cached
            binding.progressChannels.visibility = View.GONE
            binding.recyclerChannels.adapter = ChannelAdapter(cached)
            if (cached.isNotEmpty()) {
                showChannelPreview(cached[0], null)
                // PIP bleibt unverändert beim bisherigen Sender!
                if (isInitialLoad) {
                    isInitialLoad = false
                    playPipStream(cached[0])
                }
                focusTargetChannel(0)
            }
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
                    if (isInitialLoad) {
                        isInitialLoad = false
                        playPipStream(targetStream)
                    }

                    val targetPos = currentStreams.indexOf(targetStream).coerceAtLeast(0)
                    focusTargetChannel(targetPos)
                }
            } catch (e: Exception) {
                binding.progressChannels.visibility = View.GONE
                Toast.makeText(this@LiveTvActivity, "Fehler beim Laden: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun focusTargetChannel(position: Int) {
        binding.recyclerChannels.scrollToPosition(position)
        binding.recyclerChannels.post {
            val holder = binding.recyclerChannels.findViewHolderForAdapterPosition(position) as? ChannelAdapter.ViewHolder
            holder?.header?.requestFocus()
        }
    }

    private fun focusFirstChannel() {
        focusTargetChannel(0)
    }

    // Zurück zur AKTIV ausgewählten Kategorie
    private fun focusCurrentCategory() {
        val catIndex = displayedCategories.indexOfFirst { it.id == selectedCategoryId }.coerceAtLeast(0)
        binding.recyclerCategories.scrollToPosition(catIndex)
        binding.recyclerCategories.post {
            val holder = binding.recyclerCategories.findViewHolderForAdapterPosition(catIndex)
            holder?.itemView?.requestFocus() ?: binding.recyclerCategories.requestFocus()
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
        playPipStream(stream)
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
                focusCurrentCategory()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // STRIKTE HARD LOCKS: DPAD_UP/DPAD_DOWN bricht niemals horizontal in eine andere Spalte aus!
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val focused = currentFocus
            val channelPos = getFocusedChannelPosition(focused)

            if (channelPos != -1) {
                val onHeader = isChannelHeader(focused)
                val onEpg = isEpgView(focused)

                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (onHeader) {
                            focusCurrentCategory()
                            return true
                        } else if (onEpg) {
                            val epgPos = getFocusedEpgPosition(focused)
                            if (epgPos <= 0) {
                                val holder = binding.recyclerChannels.findViewHolderForAdapterPosition(channelPos) as? ChannelAdapter.ViewHolder
                                holder?.header?.requestFocus()
                                return true
                            }
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (onHeader) {
                            val holder = binding.recyclerChannels.findViewHolderForAdapterPosition(channelPos) as? ChannelAdapter.ViewHolder
                            val epgView = holder?.recyclerPrograms?.findViewHolderForAdapterPosition(0)?.itemView
                            if (epgView != null) {
                                epgView.requestFocus()
                            } else {
                                holder?.recyclerPrograms?.requestFocus()
                            }
                            return true
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (channelPos < currentStreams.size - 1) {
                            val nextPos = channelPos + 1
                            binding.recyclerChannels.scrollToPosition(nextPos)
                            binding.recyclerChannels.post {
                                val holder = binding.recyclerChannels.findViewHolderForAdapterPosition(nextPos) as? ChannelAdapter.ViewHolder
                                if (onEpg) {
                                    val epgPos = getFocusedEpgPosition(focused).coerceAtLeast(0)
                                    holder?.recyclerPrograms?.findViewHolderForAdapterPosition(epgPos)?.itemView?.requestFocus()
                                        ?: holder?.header?.requestFocus()
                                } else {
                                    holder?.header?.requestFocus()
                                }
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
                                if (onEpg) {
                                    val epgPos = getFocusedEpgPosition(focused).coerceAtLeast(0)
                                    holder?.recyclerPrograms?.findViewHolderForAdapterPosition(epgPos)?.itemView?.requestFocus()
                                        ?: holder?.header?.requestFocus()
                                } else {
                                    holder?.header?.requestFocus()
                                }
                            }
                        }
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isChannelHeader(view: View?): Boolean {
        var cur = view
        while (cur != null && cur != binding.recyclerChannels) {
            if (cur.id == R.id.channelHeader) return true
            cur = cur.parent as? View
        }
        return false
    }

    private fun isEpgView(view: View?): Boolean {
        var cur = view
        while (cur != null && cur != binding.recyclerChannels) {
            if (cur.id == R.id.recyclerChannelPrograms) return true
            cur = cur.parent as? View
        }
        return false
    }

    private fun getFocusedEpgPosition(view: View?): Int {
        var cur = view
        var parentRv: RecyclerView? = null
        while (cur != null && cur != binding.recyclerChannels) {
            val p = cur.parent as? View
            if (p?.id == R.id.recyclerChannelPrograms && p is RecyclerView) {
                parentRv = p
                break
            }
            cur = p
        }
        if (parentRv != null && cur != null) {
            return parentRv.getChildAdapterPosition(cur)
        }
        return -1
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

            holder.itemView.setOnClickListener {
                onSelect(cat)
            }

            holder.itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            onSelect(cat)
                            return@setOnKeyListener true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            focusFirstChannel()
                            return@setOnKeyListener true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            return@setOnKeyListener true
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (position == 0) {
                                binding.btnOpenLiveSearch.requestFocus()
                                return@setOnKeyListener true
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (position == items.size - 1) {
                                return@setOnKeyListener true
                            }
                        }
                    }
                }
                false
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
