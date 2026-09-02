package com.alex.iptvplayer.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
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

// Datenhalter für Sender inklusive gecachter EPG-Daten
data class ChannelWithEpg(
    val stream: LiveStream,
    var epgList: List<EpgProgram> = emptyList()
)

class LiveTvActivity : AppCompatActivity() {

    companion object {
        // 3. In-Memory-Cache für Sender und EPG: Sobald geladen, bleibt alles persistent im Speicher!
        val categoryChannelMap = HashMap<String, List<ChannelWithEpg>>()
    }

    private lateinit var binding: ActivityLiveTvBinding
    private lateinit var client: XtreamClient
    private lateinit var historyManager: HistoryManager

    private var allCategories: List<Category> = emptyList()
    private var displayedCategories: List<Category> = emptyList()
    private var currentChannelItems: List<ChannelWithEpg> = emptyList()
    private var allLiveStreamsGlobal: List<LiveStream> = emptyList()
    private var selectedCategoryId: String? = null
    private var currentChannelIndex: Int = 0

    private var categoryAdapter: CategoryAdapter? = null
    private var channelAdapter: ChannelAdapter? = null

    private var isInitialLoad = true
    private var isFullscreen = false
    private var currentSearchQuery: String? = null

    // Gemeinsamer Player für Mini-PIP und Vollbild (Nahtloser Übergang ohne Rebuffering)
    private var livePlayer: ExoPlayer? = null
    private var activeStream: LiveStream? = null

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

        setupLivePlayer()
        updateLiveTimeHeader()

        binding.recyclerCategories.apply {
            layoutManager = LinearLayoutManager(this@LiveTvActivity)
            setHasFixedSize(true)
            setItemViewCacheSize(60)
        }

        channelAdapter = ChannelAdapter(emptyList())
        binding.recyclerChannels.apply {
            layoutManager = LinearLayoutManager(this@LiveTvActivity)
            setHasFixedSize(true)
            setItemViewCacheSize(80)
            adapter = channelAdapter
        }

        setupSearchAndPipRouting()
        setupPipFocusVisuals()

        binding.root.post {
            updatePipPosition()
        }

        loadCategories()
        preloadGlobalChannels()
    }

    // 1. PIP-Fokus Highlight-Rahmen & Skalierung
    private fun setupPipFocusVisuals() {
        binding.livePlayerContainer.setOnFocusChangeListener { _, hasFocus ->
            if (!isFullscreen) {
                binding.pipFocusBorder.visibility = if (hasFocus) View.VISIBLE else View.GONE
                if (hasFocus) {
                    binding.livePlayerContainer.animate().scaleX(1.05f).scaleY(1.05f).setDuration(150).start()
                } else {
                    binding.livePlayerContainer.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start()
                }
            } else {
                binding.pipFocusBorder.visibility = View.GONE
                binding.livePlayerContainer.scaleX = 1.0f
                binding.livePlayerContainer.scaleY = 1.0f
            }
        }
    }

    // 2. Definiertes Fokus-Routing zwischen Suche, PiP und Listen
    private fun setupSearchAndPipRouting() {
        binding.btnOpenLiveSearch.setOnClickListener {
            val intent = Intent(this, SearchActivity::class.java).apply {
                putExtra("SEARCH_TYPE", "LIVE")
            }
            searchLauncher.launch(intent)
        }

        // Vom Suchfeld:
        // - DPAD_RIGHT -> direkt in den PiP
        // - DPAD_DOWN -> in die Kategorienliste
        binding.btnOpenLiveSearch.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        binding.livePlayerContainer.requestFocus()
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        val holder = binding.recyclerCategories.findViewHolderForAdapterPosition(0)
                        holder?.itemView?.requestFocus() ?: binding.recyclerCategories.requestFocus()
                        return@setOnKeyListener true
                    }
                }
            }
            false
        }

        // Vom PiP:
        // - DPAD_DOWN -> zurück auf das erste Element der Senderliste
        // - DPAD_LEFT -> zurück auf das Suchfeld
        // - DPAD_CENTER / ENTER -> Vollbildmodus
        binding.livePlayerContainer.setOnClickListener {
            if (!isFullscreen) setFullscreenMode()
        }

        binding.livePlayerContainer.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && !isFullscreen) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        setFullscreenMode()
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        focusTargetChannel(0)
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        binding.btnOpenLiveSearch.requestFocus()
                        return@setOnKeyListener true
                    }
                }
            }
            false
        }
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

    private fun setupLivePlayer() {
        livePlayer = PlayerUtils.createExoPlayer(this).apply {
            binding.livePlayerView.player = this
            playWhenReady = true
        }
    }

    override fun onResume() {
        super.onResume()
        if (livePlayer != null && activeStream != null && !livePlayer!!.isPlaying) {
            livePlayer?.play()
        }
        binding.root.post {
            updatePipPosition()
        }
    }

    override fun onPause() {
        super.onPause()
        livePlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        livePlayer?.release()
        livePlayer = null
    }

    private fun playLiveStream(stream: LiveStream) {
        if (activeStream?.streamId == stream.streamId && livePlayer?.isPlaying == true) return
        activeStream = stream
        historyManager.saveLiveChannel(stream)

        val url = client.getLiveStreamUrl(stream.streamId)
        val mediaItem = MediaItem.fromUri(url)
        livePlayer?.setMediaItem(mediaItem)
        livePlayer?.prepare()
        livePlayer?.playWhenReady = true
    }

    private fun updatePipPosition() {
        if (isFullscreen) return
        val anchor = binding.pipAnchor
        val root = binding.rootLiveTv
        val anchorLoc = IntArray(2)
        val rootLoc = IntArray(2)
        anchor.getLocationOnScreen(anchorLoc)
        root.getLocationOnScreen(rootLoc)

        val x = anchorLoc[0] - rootLoc[0]
        val y = anchorLoc[1] - rootLoc[1]

        val w = if (anchor.width > 0) anchor.width else dpToPx(190)
        val h = if (anchor.height > 0) anchor.height else dpToPx(103)

        val lp = FrameLayout.LayoutParams(w, h).apply {
            leftMargin = x
            topMargin = y
        }
        binding.livePlayerContainer.layoutParams = lp
        binding.livePlayerContainer.isFocusable = true
        binding.livePlayerContainer.isClickable = true
        binding.livePlayerView.useController = false
    }

    private fun setFullscreenMode() {
        isFullscreen = true
        binding.pipFocusBorder.visibility = View.GONE
        binding.livePlayerContainer.scaleX = 1.0f
        binding.livePlayerContainer.scaleY = 1.0f

        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ).apply {
            leftMargin = 0
            topMargin = 0
        }
        binding.livePlayerContainer.layoutParams = lp
        binding.livePlayerContainer.bringToFront()
        binding.livePlayerContainer.requestFocus()
        binding.livePlayerView.useController = true
    }

    private fun setMiniPipMode() {
        isFullscreen = false
        updatePipPosition()
        focusCurrentChannel()
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun updateLiveTimeHeader() {
        val sdf = SimpleDateFormat("EEE, dd. MMM 'um' HH:mm", Locale.GERMANY)
        binding.txtCurrentLiveTime.text = "🔴 ${sdf.format(Date())}"
    }

    private fun applySearchQuery(query: String) {
        currentSearchQuery = query
        val searchCategory = Category(id = "CURRENT_SEARCH", name = "🔍 Aktuelle Suche")
        val newCategories = mutableListOf(searchCategory)
        newCategories.addAll(allCategories)
        displayedCategories = newCategories
        selectedCategoryId = "CURRENT_SEARCH"

        categoryAdapter = CategoryAdapter(newCategories) { cat ->
            loadChannels(cat)
        }
        binding.recyclerCategories.adapter = categoryAdapter

        val pool = if (allLiveStreamsGlobal.isNotEmpty()) allLiveStreamsGlobal else currentChannelItems.map { it.stream }
        val filtered = pool.filter { it.name.contains(query, ignoreCase = true) }
        val channelListWithEpg = filtered.map { ChannelWithEpg(it) }
        currentChannelItems = channelListWithEpg
        channelAdapter?.updateItems(channelListWithEpg)

        if (channelListWithEpg.isNotEmpty()) {
            showChannelPreview(channelListWithEpg[0].stream, null)
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
                categoryAdapter = CategoryAdapter(displayedCategories) { category ->
                    loadChannels(category)
                }
                binding.recyclerCategories.adapter = categoryAdapter

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

    // 3. KATEGORIEWECHSEL & EPG-IN-MEMORY-CACHE:
    // Kein Ladekreis, kein Flackern, sofortige Anzeige aus dem Cache!
    private fun loadChannels(category: Category, preselectedStreamId: Int? = null) {
        if (category.id == "CURRENT_SEARCH" && currentSearchQuery != null) {
            applySearchQuery(currentSearchQuery!!)
            return
        }

        if (selectedCategoryId == category.id && preselectedStreamId == null && currentChannelItems.isNotEmpty()) {
            focusFirstChannel()
            return
        }

        selectedCategoryId = category.id
        categoryAdapter?.setSelectedCategoryId(category.id)

        // Cache-Check: Wurde diese Kategorie bereits geladen?
        val cached = categoryChannelMap[category.id]
        if (cached != null && preselectedStreamId == null) {
            currentChannelItems = cached
            binding.progressChannels.visibility = View.GONE
            channelAdapter?.updateItems(cached)
            if (cached.isNotEmpty()) {
                showChannelPreview(cached[0].stream, cached[0].epgList.firstOrNull())
                focusTargetChannel(0)
            }
            return
        }

        // Nur wenn noch nie geladen: Ladeindikator zeigen und Daten holen
        binding.progressChannels.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val streams = client.getLiveStreams(category.id)
                val channelList = streams.map { ChannelWithEpg(it) }
                categoryChannelMap[category.id] = channelList
                currentChannelItems = channelList
                binding.progressChannels.visibility = View.GONE
                channelAdapter?.updateItems(channelList)

                val targetItem = if (preselectedStreamId != null) {
                    currentChannelItems.firstOrNull { it.stream.streamId == preselectedStreamId } ?: currentChannelItems.firstOrNull()
                } else {
                    currentChannelItems.firstOrNull()
                }

                if (targetItem != null) {
                    showChannelPreview(targetItem.stream, null)
                    if (isInitialLoad) {
                        isInitialLoad = false
                        playLiveStream(targetItem.stream)
                    }

                    val targetPos = currentChannelItems.indexOf(targetItem).coerceAtLeast(0)
                    focusTargetChannel(targetPos)
                }
            } catch (e: Exception) {
                binding.progressChannels.visibility = View.GONE
                Toast.makeText(this@LiveTvActivity, "Fehler beim Laden: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun focusTargetChannel(position: Int) {
        currentChannelIndex = position
        binding.recyclerChannels.scrollToPosition(position)
        binding.recyclerChannels.post {
            val holder = binding.recyclerChannels.findViewHolderForAdapterPosition(position) as? ChannelAdapter.ViewHolder
            holder?.header?.requestFocus()
        }
    }

    private fun focusCurrentChannel() {
        focusTargetChannel(currentChannelIndex)
    }

    private fun focusFirstChannel() {
        focusTargetChannel(0)
    }

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
            val cached = currentChannelItems.firstOrNull { it.stream.streamId == stream.streamId }?.epgList?.firstOrNull()
            if (cached != null) {
                binding.txtPreviewTitle.text = "${stream.name} – ${cached.title}"
                binding.txtPreviewTime.text = "${cached.start} - ${cached.end}"
                binding.txtPreviewDesc.text = if (cached.description.isNotEmpty()) cached.description else "Keine Programmbeschreibung vorhanden."
            } else {
                binding.txtPreviewTitle.text = stream.name
                binding.txtPreviewTime.text = "🔴 LIVE"
                binding.txtPreviewDesc.text = "Drücke OK auf der Fernbedienung, um den Sender im Vollbild zu starten."
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (isFullscreen) {
                setMiniPipMode()
                return true
            }

            val focused = currentFocus
            if (isViewInRecyclerView(focused, binding.recyclerChannels)) {
                focusCurrentCategory()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (isFullscreen) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_BACK -> {
                        setMiniPipMode()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (currentChannelIndex > 0) {
                            currentChannelIndex--
                            val item = currentChannelItems[currentChannelIndex]
                            playLiveStream(item.stream)
                            Toast.makeText(this, "${currentChannelIndex + 1}. ${item.stream.name}", Toast.LENGTH_SHORT).show()
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (currentChannelIndex < currentChannelItems.size - 1) {
                            currentChannelIndex++
                            val item = currentChannelItems[currentChannelIndex]
                            playLiveStream(item.stream)
                            Toast.makeText(this, "${currentChannelIndex + 1}. ${item.stream.name}", Toast.LENGTH_SHORT).show()
                        }
                        return true
                    }
                }
                return super.dispatchKeyEvent(event)
            }

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
                        if (channelPos < currentChannelItems.size - 1) {
                            val nextPos = channelPos + 1
                            currentChannelIndex = nextPos
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
                        // 2. Weg in den PiP: Vom obersten Element (Index 0) der Senderliste mit DPAD_UP direkt in den PiP!
                        if (channelPos == 0) {
                            binding.livePlayerContainer.requestFocus()
                            return true
                        } else {
                            val prevPos = channelPos - 1
                            currentChannelIndex = prevPos
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
                            return true
                        }
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

        fun setSelectedCategoryId(id: String?) {
            val prevId = selectedCategoryId
            selectedCategoryId = id
            val prevPos = items.indexOfFirst { it.id == prevId }
            val newPos = items.indexOfFirst { it.id == id }
            if (prevPos != -1) notifyItemChanged(prevPos)
            if (newPos != -1) notifyItemChanged(newPos)
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
                focusTargetChannel(0)
            }

            holder.itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            onSelect(cat)
                            focusTargetChannel(0)
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
                            // 2. Fix: Aus der obersten Kategorie MUSS DPAD_UP zwingend auf das Suchfeld springen!
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
        private var items: List<ChannelWithEpg>
    ) : RecyclerView.Adapter<ChannelAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val header: View = view.findViewById(R.id.channelHeader)
            val txtNum: TextView = view.findViewById(R.id.txtChannelNum)
            val imgLogo: ImageView = view.findViewById(R.id.imgChannelLogo)
            val txtName: TextView = view.findViewById(R.id.txtChannelName)
            val recyclerPrograms: RecyclerView = view.findViewById(R.id.recyclerChannelPrograms)
        }

        fun updateItems(newList: List<ChannelWithEpg>) {
            this.items = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_epg_channel_row, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            val s = item.stream
            holder.txtNum.text = "${position + 1}"
            holder.txtName.text = s.name

            if (!s.streamIcon.isNullOrEmpty()) {
                Glide.with(holder.itemView).load(s.streamIcon).override(36, 36).into(holder.imgLogo)
            } else {
                holder.imgLogo.setImageResource(R.drawable.tv_banner)
            }

            holder.header.setOnClickListener {
                currentChannelIndex = position
                playLiveStream(s)
                setFullscreenMode()
            }

            holder.header.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    currentChannelIndex = position
                    showChannelPreview(s, item.epgList.firstOrNull())
                }
            }

            holder.recyclerPrograms.apply {
                layoutManager = LinearLayoutManager(holder.itemView.context, LinearLayoutManager.HORIZONTAL, false)
                setHasFixedSize(true)
            }

            // 3. EPG-Cache: Wenn bereits im ChannelWithEpg gecached, sofort und ohne Delay anzeigen!
            if (item.epgList.isNotEmpty()) {
                holder.recyclerPrograms.adapter = ProgramTimelineAdapter(s, position, item.epgList, holder)
            } else {
                val fallback = listOf(EpgProgram("Lade EPG...", "", "Jetzt", "", true))
                holder.recyclerPrograms.adapter = ProgramTimelineAdapter(s, position, fallback, holder)

                lifecycleScope.launch {
                    val epgList = client.getEpg(s.streamId)
                    if (epgList.isNotEmpty()) {
                        item.epgList = epgList
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
                currentChannelIndex = channelIndex
                playLiveStream(stream)
                setFullscreenMode()
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
