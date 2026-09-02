package com.alex.iptvplayer.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class ChannelWithEpg(
    val stream: LiveStream,
    var epgList: List<EpgProgram> = emptyList()
)

class LiveTvActivity : AppCompatActivity() {

    companion object {
        val categoryChannelMap = HashMap<String, List<ChannelWithEpg>>()
        var allLiveStreamsCache: List<LiveStream> = emptyList()
    }

    private lateinit var binding: ActivityLiveTvBinding
    private lateinit var client: XtreamClient
    private lateinit var historyManager: HistoryManager

    private var allCategories: List<Category> = emptyList()
    private var displayedCategories: List<Category> = emptyList()
    private var currentChannelItems: List<ChannelWithEpg> = emptyList()
    private var allLiveStreamsGlobal: List<LiveStream> = emptyList()

    // 1. Zwei-Stufen-Logik: Playing Category vs. Browsing Category
    private var playingCategoryId: String? = null
    private var browsingCategoryId: String? = null
    private var nowPlayingStreamId: Int? = null

    // Sperre gegen Fokus-Zwischensprung in die Suche
    private var isSwitchingCategories = false

    // Playlist-Kontext (z. B. Kategorie-Senderliste)
    private var activePlaylist: List<ChannelWithEpg> = emptyList()
    private var activePlaylistIndex: Int = 0

    private var categoryAdapter: CategoryAdapter? = null
    private var channelAdapter: ChannelAdapter? = null

    private var isInitialLoad = true
    private var isFullscreen = false

    // Gemeinsamer Player für Mini-PIP und Vollbild
    private var livePlayer: ExoPlayer? = null
    private var activeStream: LiveStream? = null
    private var activeStreamEpg: EpgProgram? = null

    // OSD Timer
    private val osdHandler = Handler(Looper.getMainLooper())
    private val hideOsdRunnable = Runnable {
        binding.layoutFullscreenOsd.visibility = View.GONE
    }

    // 3. SUCHE DIREKT IM SUCHFENSTER: Treffer starten den Sender sofort, KEINE temporäre Kategorie mehr!
    private val searchLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val streamId = result.data?.getIntExtra("SELECTED_STREAM_ID", -1) ?: -1
            val startFullscreen = result.data?.getBooleanExtra("START_FULLSCREEN", true) ?: true

            if (streamId != -1) {
                val targetStream = allLiveStreamsGlobal.firstOrNull { it.streamId == streamId }
                    ?: currentChannelItems.firstOrNull { it.stream.streamId == streamId }?.stream

                if (targetStream != null) {
                    val catId = targetStream.categoryId
                    if (!catId.isNullOrEmpty()) {
                        val cat = displayedCategories.firstOrNull { it.id == catId }
                        if (cat != null) {
                            loadChannels(cat, preselectedStreamId = targetStream.streamId)
                        }
                    }
                    playLiveStream(targetStream)
                    if (startFullscreen) {
                        setFullscreenMode()
                    }
                }
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
            clipChildren = false
            clipToPadding = false
        }

        channelAdapter = ChannelAdapter(emptyList())
        binding.recyclerChannels.apply {
            layoutManager = LinearLayoutManager(this@LiveTvActivity)
            setHasFixedSize(true)
            setItemViewCacheSize(80)
            clipChildren = false
            clipToPadding = false
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

    private fun setupPipFocusVisuals() {
        binding.livePlayerContainer.setOnFocusChangeListener { _, hasFocus ->
            if (!isFullscreen) {
                binding.pipFocusBorder.visibility = if (hasFocus) View.VISIBLE else View.GONE
                if (hasFocus) {
                    binding.livePlayerContainer.animate().scaleX(1.05f).scaleY(1.05f).setDuration(150).start()
                    if (activeStream != null) {
                        updatePipProgramInfo(activeStream!!, activeStreamEpg)
                    }
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

    private fun setupSearchAndPipRouting() {
        // Klick auf Suche öffnet saubere Vollbild-Dual-Suche
        binding.btnOpenLiveSearch.setOnClickListener {
            val intent = Intent(this, SearchActivity::class.java).apply {
                putExtra("SEARCH_TYPE", "LIVE")
            }
            searchLauncher.launch(intent)
        }

        // Fokus-Schutz: Falls während eines Kategoriewechsels die Suche Fokus erhält, sofort auf Sender leiten
        binding.btnOpenLiveSearch.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && isSwitchingCategories) {
                focusTargetChannel(0)
            }
        }

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
                allLiveStreamsCache = allLiveStreamsGlobal
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
        if (isFinishing) {
            livePlayer?.pause()
        }
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing) {
            livePlayer?.pause()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        osdHandler.removeCallbacks(hideOsdRunnable)
        livePlayer?.release()
        livePlayer = null
    }

    // 1. Sender starten: Roter Punkt wandert sofort zum neuen Sender und zu dessen Kategorie mit!
    private fun playLiveStream(stream: LiveStream) {
        nowPlayingStreamId = stream.streamId

        // Ermittle Ursprungskategorie des Senders
        val targetCatId = stream.categoryId ?: browsingCategoryId
        playingCategoryId = targetCatId

        activeStream = stream
        historyManager.saveLiveChannel(stream)

        channelAdapter?.notifyDataSetChanged()
        categoryAdapter?.updateCategoryStates(playingId = playingCategoryId, newBrowsingId = browsingCategoryId)

        val url = client.getLiveStreamUrl(stream.streamId)
        val mediaItem = MediaItem.fromUri(url)
        livePlayer?.setMediaItem(mediaItem)
        livePlayer?.prepare()
        livePlayer?.playWhenReady = true

        lifecycleScope.launch {
            val cachedEpg = currentChannelItems.firstOrNull { it.stream.streamId == stream.streamId }?.epgList
            val epg = if (!cachedEpg.isNullOrEmpty()) cachedEpg else client.getEpg(stream.streamId)
            activeStreamEpg = epg.firstOrNull()
            updatePipProgramInfo(stream, activeStreamEpg)
            if (isFullscreen) {
                updateOsdContent(stream, activeStreamEpg)
            }
        }
    }

    private fun updatePipProgramInfo(stream: LiveStream, program: EpgProgram?) {
        if (program != null) {
            binding.txtPreviewTitle.text = "${stream.name} – ${program.title}"
            binding.txtPreviewTime.text = "${program.start} - ${program.end}"
            binding.txtPreviewDesc.text = if (program.description.isNotEmpty()) program.description else "Keine Programmbeschreibung vorhanden."

            val progress = calculateProgress(program.start, program.end)
            binding.progressCurrentProgram.progress = progress
            binding.progressCurrentProgram.visibility = View.VISIBLE
        } else {
            binding.txtPreviewTitle.text = stream.name
            binding.txtPreviewTime.text = "🔴 LIVE"
            binding.txtPreviewDesc.text = "Drücke OK auf der Fernbedienung, um den Sender im Vollbild zu starten."
            binding.progressCurrentProgram.visibility = View.GONE
        }
    }

    private fun calculateProgress(start: String, end: String): Int {
        try {
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            val cal = Calendar.getInstance()
            val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

            val sCal = Calendar.getInstance().apply { time = sdf.parse(start) ?: return 50 }
            val sMin = sCal.get(Calendar.HOUR_OF_DAY) * 60 + sCal.get(Calendar.MINUTE)

            val eCal = Calendar.getInstance().apply { time = sdf.parse(end) ?: return 50 }
            var eMin = eCal.get(Calendar.HOUR_OF_DAY) * 60 + eCal.get(Calendar.MINUTE)
            if (eMin <= sMin) eMin += 24 * 60

            var curMin = now
            if (curMin < sMin && eMin > 24 * 60) curMin += 24 * 60

            val total = eMin - sMin
            if (total <= 0) return 50
            val current = curMin - sMin
            return ((current.toFloat() / total.toFloat()) * 100).toInt().coerceIn(0, 100)
        } catch (e: Exception) {
            return 50
        }
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
        binding.livePlayerView.useController = false

        showOsd()
    }

    private fun setMiniPipMode() {
        isFullscreen = false
        binding.layoutFullscreenOsd.visibility = View.GONE
        osdHandler.removeCallbacks(hideOsdRunnable)
        updatePipPosition()
        focusTargetChannel(activePlaylistIndex)
    }

    private fun showOsd() {
        osdHandler.removeCallbacks(hideOsdRunnable)
        if (activeStream != null) {
            updateOsdContent(activeStream!!, activeStreamEpg)
        }
        binding.layoutFullscreenOsd.visibility = View.VISIBLE
        binding.layoutFullscreenOsd.bringToFront()
        osdHandler.postDelayed(hideOsdRunnable, 5000)
    }

    private fun toggleOsd() {
        if (binding.layoutFullscreenOsd.visibility == View.VISIBLE) {
            osdHandler.removeCallbacks(hideOsdRunnable)
            binding.layoutFullscreenOsd.visibility = View.GONE
        } else {
            showOsd()
        }
    }

    private fun updateOsdContent(stream: LiveStream, program: EpgProgram?) {
        binding.txtOsdChannelNum.text = "${activePlaylistIndex + 1}"
        binding.txtOsdChannelName.text = stream.name

        val videoFormat = livePlayer?.videoFormat
        val audioFormat = livePlayer?.audioFormat

        val res = if (videoFormat != null && videoFormat.width > 0 && videoFormat.height > 0) {
            "${videoFormat.width}x${videoFormat.height}"
        } else "1080p"

        val fps = if (videoFormat != null && videoFormat.frameRate > 0) {
            "${videoFormat.frameRate.toInt()} fps"
        } else "50 fps"

        val vCodec = videoFormat?.sampleMimeType?.substringAfter("/")?.uppercase() ?: "H.264"
        val aCodec = audioFormat?.sampleMimeType?.substringAfter("/")?.uppercase() ?: "AAC"

        binding.txtOsdTechSpecs.text = "$res | $fps | $vCodec | $aCodec"

        if (program != null) {
            binding.txtOsdProgramTitle.text = "🔴 JETZT: ${program.title}"
            binding.txtOsdProgramTime.text = "${program.start} - ${program.end}"
            binding.txtOsdProgramDesc.text = if (program.description.isNotEmpty()) program.description else "Keine Programmbeschreibung vorhanden."
            val prog = calculateProgress(program.start, program.end)
            binding.progressOsdProgram.progress = prog
            binding.progressOsdProgram.visibility = View.VISIBLE
        } else {
            binding.txtOsdProgramTitle.text = "🔴 LIVE TV"
            binding.txtOsdProgramTime.text = ""
            binding.txtOsdProgramDesc.text = "Live Stream aktiv"
            binding.progressOsdProgram.visibility = View.GONE
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun updateLiveTimeHeader() {
        val sdf = SimpleDateFormat("EEE, dd. MMM 'um' HH:mm", Locale.GERMANY)
        binding.txtCurrentLiveTime.text = "🔴 ${sdf.format(Date())}"
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
                        playingCategoryId = matchingCat.id
                        browsingCategoryId = matchingCat.id
                        categoryAdapter?.updateCategoryStates(playingCategoryId, browsingCategoryId)
                        loadChannels(matchingCat, preselectedStreamId = lastWatched.streamId)
                        return@launch
                    }
                }

                if (displayedCategories.isNotEmpty()) {
                    playingCategoryId = displayedCategories[0].id
                    browsingCategoryId = displayedCategories[0].id
                    categoryAdapter?.updateCategoryStates(playingCategoryId, browsingCategoryId)
                    loadChannels(displayedCategories[0])
                }
            } catch (e: Exception) {
                binding.progressCategories.visibility = View.GONE
                Toast.makeText(this@LiveTvActivity, "Fehler: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 1. & 2. KATEGORIEWECHSEL & SENDER-RESTORE
    private fun loadChannels(category: Category, preselectedStreamId: Int? = null) {
        if (browsingCategoryId == category.id && preselectedStreamId == null && currentChannelItems.isNotEmpty()) {
            focusTargetChannelForCategory(category)
            return
        }

        isSwitchingCategories = true
        val oldBrowsingId = browsingCategoryId
        browsingCategoryId = category.id

        // Aktualisiere Kategorie-States
        categoryAdapter?.updateCategoryStates(
            playingId = playingCategoryId,
            newBrowsingId = browsingCategoryId,
            oldBrowsingId = oldBrowsingId
        )

        // Cache-Check
        val cached = categoryChannelMap[category.id]
        if (cached != null && preselectedStreamId == null) {
            currentChannelItems = cached
            activePlaylist = cached
            binding.progressChannels.visibility = View.GONE
            channelAdapter?.updateItems(cached)
            if (cached.isNotEmpty()) {
                // 2. Sender-Restore: Wenn Rücksprung in die Playing Category -> Sender mit rotem Punkt fokussieren!
                val restoreIndex = if (category.id == playingCategoryId && nowPlayingStreamId != null) {
                    val idx = cached.indexOfFirst { it.stream.streamId == nowPlayingStreamId }
                    if (idx != -1) idx else 0
                } else 0

                showChannelPreview(cached[restoreIndex].stream, cached[restoreIndex].epgList.firstOrNull())
                focusTargetChannel(restoreIndex)
            }
            return
        }

        binding.progressChannels.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val streams = client.getLiveStreams(category.id)
                val channelList = streams.map { ChannelWithEpg(it) }
                categoryChannelMap[category.id] = channelList
                currentChannelItems = channelList
                activePlaylist = channelList
                binding.progressChannels.visibility = View.GONE
                channelAdapter?.updateItems(channelList)

                val targetItem = if (preselectedStreamId != null) {
                    currentChannelItems.firstOrNull { it.stream.streamId == preselectedStreamId } ?: currentChannelItems.firstOrNull()
                } else if (category.id == playingCategoryId && nowPlayingStreamId != null) {
                    currentChannelItems.firstOrNull { it.stream.streamId == nowPlayingStreamId } ?: currentChannelItems.firstOrNull()
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

    // 2. SENDER-RESTORE: Springt und scrollt exakt auf den Sender mit dem roten Punkt (mit Offset)
    private fun focusTargetChannel(position: Int) {
        activePlaylistIndex = position
        val lm = binding.recyclerChannels.layoutManager as? LinearLayoutManager
        lm?.scrollToPositionWithOffset(position, dpToPx(40)) ?: binding.recyclerChannels.scrollToPosition(position)

        binding.recyclerChannels.post {
            val holder = binding.recyclerChannels.findViewHolderForAdapterPosition(position) as? ChannelAdapter.ViewHolder
            if (holder != null) {
                holder.header.requestFocus()
                isSwitchingCategories = false
            } else {
                binding.recyclerChannels.postDelayed({
                    val retryHolder = binding.recyclerChannels.findViewHolderForAdapterPosition(position) as? ChannelAdapter.ViewHolder
                    retryHolder?.header?.requestFocus()
                    isSwitchingCategories = false
                }, 60)
            }
        }
    }

    private fun focusTargetChannelForCategory(cat: Category) {
        if (cat.id == playingCategoryId && nowPlayingStreamId != null) {
            val targetIndex = currentChannelItems.indexOfFirst { it.stream.streamId == nowPlayingStreamId }
            if (targetIndex != -1) {
                focusTargetChannel(targetIndex)
                return
            }
        }
        focusTargetChannel(0)
    }

    private fun focusCurrentCategory() {
        val catIndex = displayedCategories.indexOfFirst { it.id == browsingCategoryId }.coerceAtLeast(0)
        val lm = binding.recyclerCategories.layoutManager as? LinearLayoutManager
        lm?.scrollToPositionWithOffset(catIndex, dpToPx(40)) ?: binding.recyclerCategories.scrollToPosition(catIndex)

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
            val prog = calculateProgress(program.start, program.end)
            binding.progressCurrentProgram.progress = prog
            binding.progressCurrentProgram.visibility = View.VISIBLE
        } else {
            val cached = currentChannelItems.firstOrNull { it.stream.streamId == stream.streamId }?.epgList?.firstOrNull()
            if (cached != null) {
                binding.txtPreviewTitle.text = "${stream.name} – ${cached.title}"
                binding.txtPreviewTime.text = "${cached.start} - ${cached.end}"
                binding.txtPreviewDesc.text = if (cached.description.isNotEmpty()) cached.description else "Keine Programmbeschreibung vorhanden."
                val prog = calculateProgress(cached.start, cached.end)
                binding.progressCurrentProgram.progress = prog
                binding.progressCurrentProgram.visibility = View.VISIBLE
            } else {
                binding.txtPreviewTitle.text = stream.name
                binding.txtPreviewTime.text = "🔴 LIVE"
                binding.txtPreviewDesc.text = "Drücke OK auf der Fernbedienung, um den Sender im Vollbild zu starten."
                binding.progressCurrentProgram.visibility = View.GONE
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
            // Im Vollbildmodus
            if (isFullscreen) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_BACK -> {
                        setMiniPipMode()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        toggleOsd()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (activePlaylistIndex > 0) {
                            activePlaylistIndex--
                            val item = activePlaylist[activePlaylistIndex]
                            playLiveStream(item.stream)
                            showOsd()
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (activePlaylistIndex < activePlaylist.size - 1) {
                            activePlaylistIndex++
                            val item = activePlaylist[activePlaylistIndex]
                            playLiveStream(item.stream)
                            showOsd()
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
                            activePlaylistIndex = nextPos
                            (binding.recyclerChannels.layoutManager as? LinearLayoutManager)
                                ?.scrollToPositionWithOffset(nextPos, dpToPx(40))
                                ?: binding.recyclerChannels.scrollToPosition(nextPos)

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
                        if (channelPos == 0) {
                            binding.livePlayerContainer.requestFocus()
                            return true
                        } else {
                            val prevPos = channelPos - 1
                            activePlaylistIndex = prevPos
                            (binding.recyclerChannels.layoutManager as? LinearLayoutManager)
                                ?.scrollToPositionWithOffset(prevPos, dpToPx(40))
                                ?: binding.recyclerChannels.scrollToPosition(prevPos)

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

    // --- Adapter 1: Kategorien mit rotem Punkt & Browsing-Status ---
    inner class CategoryAdapter(
        private val items: List<Category>,
        private val onSelect: (Category) -> Unit
    ) : RecyclerView.Adapter<CategoryAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val dot: View = view.findViewById(R.id.dotIndicator)
            val txtName: TextView = view.findViewById(R.id.txtCategoryName)
        }

        fun updateCategoryStates(playingId: String?, newBrowsingId: String?, oldBrowsingId: String? = null) {
            playingCategoryId = playingId
            browsingCategoryId = newBrowsingId

            val toUpdate = mutableSetOf<Int>()
            items.indexOfFirst { it.id == oldBrowsingId }.takeIf { it != -1 }?.let { toUpdate.add(it) }
            items.indexOfFirst { it.id == newBrowsingId }.takeIf { it != -1 }?.let { toUpdate.add(it) }
            items.indexOfFirst { it.id == playingId }.takeIf { it != -1 }?.let { toUpdate.add(it) }

            if (toUpdate.isNotEmpty()) {
                for (pos in toUpdate) {
                    notifyItemChanged(pos)
                }
            } else {
                notifyDataSetChanged()
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val cat = items[position]
            applyCategoryStyle(holder, cat)

            holder.itemView.setOnFocusChangeListener { _, _ ->
                applyCategoryStyle(holder, cat)
                if (!holder.itemView.isFocused && activeStream != null) {
                    updatePipProgramInfo(activeStream!!, activeStreamEpg)
                }
            }

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
                            // 2. Sender-Restore: DPAD_RIGHT in die Senderliste springt direkt zum Sender mit dem roten Punkt!
                            focusTargetChannelForCategory(cat)
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

        // 1. OPTIK: Roter Indikator-Punkt für Playing Category, nur Browsing Category hat rote Tönung
        private fun applyCategoryStyle(holder: ViewHolder, cat: Category) {
            val isPlaying = (cat.id == playingCategoryId)
            val isBrowsing = (cat.id == browsingCategoryId)
            val isFocused = holder.itemView.isFocused

            // 1. DYNAMISCHER ROTER PUNKT: Signalisiert den laufenden Stream (NUR DER PUNKT, KEINE ROTE FÜLLUNG!)
            holder.dot.visibility = if (isPlaying) View.VISIBLE else View.GONE
            holder.txtName.text = cat.name
            holder.txtName.setTextColor(if (isFocused || isBrowsing) Color.parseColor("#FFFFFF") else Color.parseColor("#B0B0B0"))

            val drawable = GradientDrawable().apply {
                cornerRadius = dpToPx(6).toFloat()
                when {
                    // D-Pad Cursor: Reiner roter Fokusrahmen (Border), kein Vollflächen-Knallrot
                    isFocused -> {
                        if (isBrowsing) {
                            setColor(Color.parseColor("#701A22"))
                        } else {
                            setColor(Color.parseColor("#1C1C1C"))
                        }
                        setStroke(dpToPx(3), Color.parseColor("#E50914"))
                    }
                    // Browsing Category (die gerade durchsucht wird): Sichtbare matte rote Tönung
                    isBrowsing -> {
                        setColor(Color.parseColor("#701A22"))
                        setStroke(dpToPx(1.5f.toInt()), Color.parseColor("#90202A"))
                    }
                    // Playing Category & Normal: Neutral dunkel, KEINE zusätzliche rote Füllung! (Nur der rote Punkt!)
                    else -> {
                        setColor(Color.parseColor("#141414"))
                        setStroke(dpToPx(1), Color.parseColor("#222222"))
                    }
                }
            }
            holder.itemView.background = drawable

            if (isFocused) {
                holder.itemView.scaleX = 1.02f
                holder.itemView.scaleY = 1.02f
            } else {
                holder.itemView.scaleX = 1.0f
                holder.itemView.scaleY = 1.0f
            }
        }

        override fun getItemCount() = items.size
    }

    // --- Adapter 2: Senderzeilen mit dynamischem rotem Punkt ---
    inner class ChannelAdapter(
        private var items: List<ChannelWithEpg>
    ) : RecyclerView.Adapter<ChannelAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val header: View = view.findViewById(R.id.channelHeader)
            val txtNum: TextView = view.findViewById(R.id.txtChannelNum)
            val imgLogo: ImageView = view.findViewById(R.id.imgChannelLogo)
            val dot: View = view.findViewById(R.id.dotChannelIndicator)
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
            val isPlaying = (s.streamId == nowPlayingStreamId)

            holder.txtName.text = s.name

            // 1. DYNAMISCHER ROTER PUNKT: Signalisiert den laufenden Stream (NUR DER PUNKT, KEINE ROTE FÜLLUNG!)
            holder.dot.visibility = if (isPlaying) View.VISIBLE else View.GONE

            if (isPlaying) {
                holder.txtNum.text = "${position + 1}"
                holder.txtNum.setTextColor(Color.parseColor("#E50914"))
                holder.txtName.setTextColor(Color.parseColor("#FFFFFF"))
            } else {
                holder.txtNum.text = "${position + 1}"
                holder.txtNum.setTextColor(Color.parseColor("#888888"))
                holder.txtName.setTextColor(Color.parseColor("#D0D0D0"))
            }

            if (!s.streamIcon.isNullOrEmpty()) {
                Glide.with(holder.itemView).load(s.streamIcon).override(36, 36).into(holder.imgLogo)
            } else {
                holder.imgLogo.setImageResource(R.drawable.tv_banner)
            }

            applyChannelHeaderStyle(holder, isPlaying)

            holder.header.setOnFocusChangeListener { _, _ ->
                applyChannelHeaderStyle(holder, isPlaying)
                if (holder.header.isFocused) {
                    activePlaylistIndex = position
                    showChannelPreview(s, item.epgList.firstOrNull())
                } else if (activeStream != null) {
                    updatePipProgramInfo(activeStream!!, activeStreamEpg)
                }
            }

            holder.header.setOnClickListener {
                activePlaylist = currentChannelItems
                activePlaylistIndex = position
                playLiveStream(s)
                setFullscreenMode()
            }

            holder.recyclerPrograms.apply {
                layoutManager = LinearLayoutManager(holder.itemView.context, LinearLayoutManager.HORIZONTAL, false)
                setHasFixedSize(true)
            }

            // EPG-Cache
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

        private fun applyChannelHeaderStyle(holder: ViewHolder, isPlaying: Boolean) {
            val isFocused = holder.header.isFocused
            val drawable = GradientDrawable().apply {
                cornerRadius = dpToPx(6).toFloat()
                when {
                    // Cursor: Reiner roter Fokusrahmen
                    isFocused -> {
                        setColor(Color.parseColor("#1C1C1C"))
                        setStroke(dpToPx(3), Color.parseColor("#E50914"))
                    }
                    // Normal & Aktuell laufender Sender: Neutral dunkel, KEINE rote Füllung! (Nur der rote Punkt!)
                    else -> {
                        setColor(Color.parseColor("#141414"))
                        setStroke(dpToPx(1), Color.parseColor("#222222"))
                    }
                }
            }
            holder.header.background = drawable
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
                activePlaylist = currentChannelItems
                activePlaylistIndex = channelIndex
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
