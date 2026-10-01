package com.tivizone.player.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tivizone.player.R
import com.tivizone.player.data.Category
import com.tivizone.player.data.EpgProgram
import com.tivizone.player.data.HistoryManager
import com.tivizone.player.data.LiveTvCacheManager
import com.tivizone.player.data.LiveStream
import com.tivizone.player.data.MultiStreamChannel
import com.tivizone.player.data.MultiStreamManager
import com.tivizone.player.data.QualityPreferenceManager
import com.tivizone.player.data.StreamSource
import com.tivizone.player.data.XtreamClient
import com.tivizone.player.databinding.ActivityLiveTvBinding
import com.tivizone.player.util.AppLogger
import com.tivizone.player.util.PlayerUtils
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class ChannelWithEpg(
    val channel: MultiStreamChannel,
    var epgList: List<EpgProgram> = emptyList()
) {
    constructor(stream: LiveStream, epgList: List<EpgProgram> = emptyList()) : this(
        channel = MultiStreamChannel(
            cleanName = stream.name,
            originalName = stream.name,
            categoryId = stream.categoryId ?: "",
            categoryName = "",
            icon = stream.streamIcon,
            epgId = stream.epgChannelId,
            sources = listOf(
                StreamSource(
                    streamId = stream.streamId,
                    name = stream.name,
                    label = "Standard Stream",
                    score = 50,
                    subcategory = ""
                )
            )
        ),
        epgList = epgList
    )

    val stream: LiveStream
        get() = LiveStream(
            streamId = channel.primarySource?.streamId ?: 0,
            name = channel.cleanName,
            streamIcon = channel.icon,
            epgChannelId = channel.epgId,
            categoryId = channel.categoryId
        )
}

class LiveTvActivity : AppCompatActivity() {

    companion object {
        val categoryChannelMap = ConcurrentHashMap<String, List<ChannelWithEpg>>()
        var allLiveStreamsCache: List<LiveStream> = emptyList()
        val multiStreamCategoriesMap = ConcurrentHashMap<String, List<MultiStreamChannel>>()
        val epgGlobalCache = ConcurrentHashMap<String, List<EpgProgram>>()
        val rawCategoryStreamsCache = ConcurrentHashMap<String, List<LiveStream>>()
        var rawCategoriesCache: List<Category> = emptyList()
    }

    private lateinit var binding: ActivityLiveTvBinding
    private lateinit var client: XtreamClient
    private lateinit var historyManager: HistoryManager
    private lateinit var cacheManager: LiveTvCacheManager

    private var allCategories: List<Category> = emptyList()
    private var displayedCategories: List<Category> = emptyList()
    private var currentChannelItems: List<ChannelWithEpg> = emptyList()
    private var allLiveStreamsGlobal: List<LiveStream> = emptyList()

    // Zwei-Stufen-Logik: Playing Category vs. Browsing Category
    private var playingCategoryId: String? = null
    private var browsingCategoryId: String? = null
    private var nowPlayingStreamId: Int? = null

    // Multi-Stream State
    private var activeChannel: MultiStreamChannel? = null
    private var activeSourceIndex: Int = 0

    // Sperre gegen Fokus-Zwischensprung in die Suche
    private var isSwitchingCategories = false
    private var isPlayingJustStarted = false

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

    // Asynchrone Jobs für Zappen & EPG
    private var streamJob: Job? = null
    private var epgFetchJob: Job? = null

    // Fast Auto-Retry für Verbindungslimit (401/403/429/Timeout)
    private val retryHandler = Handler(Looper.getMainLooper())
    private var retryRunnable: Runnable? = null
    private var retryCount = 0
    private val resetRetryRunnable = Runnable { retryCount = 0 }
    private val MAX_STREAM_RETRIES = 6

    // Standby-Erkennung (HDMI / Display Off)
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                AppLogger.logLifecycle("LiveTvActivity", "ACTION_SCREEN_OFF: Standby erkannt -> Beende alle Stream-Verbindungen sofort")
                watchdogHandler.removeCallbacks(bufferWatchdogRunnable)
                retryRunnable?.let { retryHandler.removeCallbacks(it) }
                streamJob?.cancel()
                epgFetchJob?.cancel()
                PlayerUtils.releaseStreamConnections(livePlayer)
            }
        }
    }

    // Watchdog für hängenden Puffer (Auto-Failover nach 6 Sekunden Buffering)
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private val bufferWatchdogRunnable = Runnable {
        if (livePlayer != null && livePlayer?.playbackState == Player.STATE_BUFFERING) {
            triggerAutoFailover("Lade-Timeout")
        }
    }

    // OSD Timer
    private val osdHandler = Handler(Looper.getMainLooper())
    private val hideOsdRunnable = Runnable {
        binding.layoutFullscreenOsd.visibility = View.GONE
    }

    // SUCHE DIREKT IM SUCHFENSTER: Treffer starten den Sender sofort!
    private val searchLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val streamId = result.data?.getIntExtra("SELECTED_STREAM_ID", -1) ?: -1
            val startFullscreen = result.data?.getBooleanExtra("START_FULLSCREEN", true) ?: true

            if (streamId != -1) {
                playTargetStreamById(streamId, startFullscreen)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val targetStreamId = intent.getIntExtra("TARGET_STREAM_ID", -1).takeIf { it != -1 }
        val startFullscreen = intent.getBooleanExtra("START_FULLSCREEN", false)
        if (targetStreamId != null) {
            playTargetStreamById(targetStreamId, startFullscreen)
        }
    }

    private fun playTargetStreamById(streamId: Int, startFullscreen: Boolean) {
        val matchingChannel = multiStreamCategoriesMap.values.flatten()
            .firstOrNull { ch -> ch.sources.any { it.streamId == streamId } || ch.cleanName.equals(currentChannelItems.firstOrNull { it.stream.streamId == streamId }?.channel?.cleanName, ignoreCase = true) }

        if (matchingChannel != null) {
            val cat = displayedCategories.firstOrNull { it.id == matchingChannel.categoryId }
            if (cat != null) {
                browsingCategoryId = cat.id
                playingCategoryId = cat.id
                categoryAdapter?.updateCategoryStates(playingCategoryId, browsingCategoryId)
                val catPos = displayedCategories.indexOf(cat)
                if (catPos >= 0) {
                    binding.recyclerCategories.scrollToPosition(catPos)
                }
                loadChannels(cat, preselectedStreamId = streamId)
            }
            val srcIdx = matchingChannel.sources.indexOfFirst { it.streamId == streamId }.coerceAtLeast(0)
            playMultiStreamChannel(matchingChannel, srcIdx)
        } else {
            val targetStream = allLiveStreamsGlobal.firstOrNull { it.streamId == streamId }
                ?: allLiveStreamsCache.firstOrNull { it.streamId == streamId }
                ?: currentChannelItems.firstOrNull { it.stream.streamId == streamId }?.stream

            val targetCatId = if (targetStream != null) {
                MultiStreamManager.resolveMainCategoryIdForStream(targetStream, rawCategoriesCache)
            } else {
                "MAIN_FREETV"
            }
            val cat = displayedCategories.firstOrNull { it.id == targetCatId }
            if (cat != null) {
                browsingCategoryId = cat.id
                playingCategoryId = cat.id
                categoryAdapter?.updateCategoryStates(playingCategoryId, browsingCategoryId)
                val catPos = displayedCategories.indexOf(cat)
                if (catPos >= 0) {
                    binding.recyclerCategories.scrollToPosition(catPos)
                }
                loadChannels(cat, preselectedStreamId = streamId)
            }
            if (targetStream != null) {
                playLiveStream(targetStream)
            }
        }

        if (startFullscreen) {
            setFullscreenMode()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.init(this)
        AppLogger.logLifecycle("LiveTvActivity", "onCreate")

        // Bildschirmschoner / Standby auf TV während LiveTV verhindern
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityLiveTvBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.livePlayerView.keepScreenOn = true

        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        registerReceiver(screenOffReceiver, filter)

        client = XtreamClient(this)
        historyManager = HistoryManager(this)
        cacheManager = LiveTvCacheManager(this)

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
        setupLivePlayerInteractions()

        binding.root.post {
            updatePipPosition()
        }

        loadCategories()
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

        // Fokus-Schutz: Falls während eines Senderspielens oder Kategoriewechsels die Suche Fokus erhält, sofort auf Sender leiten
        binding.btnOpenLiveSearch.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                if (isSwitchingCategories) {
                    focusTargetChannel(0)
                } else if (isPlayingJustStarted && activePlaylistIndex in currentChannelItems.indices) {
                    focusTargetChannel(activePlaylistIndex)
                }
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
    }

    private fun setupLivePlayerInteractions() {
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
                    KeyEvent.KEYCODE_PROG_YELLOW, KeyEvent.KEYCODE_BUTTON_Y -> {
                        cycleToNextSourceManually()
                        return@setOnKeyListener true
                    }
                }
            }
            false
        }
    }

    private fun setupLivePlayer() {
        livePlayer = PlayerUtils.createExoPlayer(this, isLive = true).apply {
            binding.livePlayerView.player = this
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_BUFFERING -> {
                            watchdogHandler.removeCallbacks(bufferWatchdogRunnable)
                            watchdogHandler.postDelayed(bufferWatchdogRunnable, 10000)
                            AppLogger.logPlayerState("LiveTv", "BUFFERING")
                        }
                        Player.STATE_READY -> {
                            watchdogHandler.removeCallbacks(bufferWatchdogRunnable)
                            retryRunnable?.let { retryHandler.removeCallbacks(it) }
                            hideRetryBanner()
                            updateOsdSourceBadge()
                            AppLogger.logPlayerState("LiveTv", "READY (Playing stream $nowPlayingStreamId)")
                        }
                        Player.STATE_ENDED -> {
                            watchdogHandler.removeCallbacks(bufferWatchdogRunnable)
                            retryHandler.removeCallbacks(resetRetryRunnable)
                            AppLogger.logPlayerState("LiveTv", "ENDED")
                        }
                        Player.STATE_IDLE -> {
                            AppLogger.logPlayerState("LiveTv", "IDLE")
                        }
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) {
                        retryHandler.removeCallbacks(resetRetryRunnable)
                        retryHandler.postDelayed(resetRetryRunnable, 3000)
                    } else {
                        retryHandler.removeCallbacks(resetRetryRunnable)
                    }
                }

                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    AppLogger.logFormatChange("LiveTv", livePlayer?.videoFormat, livePlayer?.audioFormat)
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    checkAndCacheQuality(videoSize.width, videoSize.height)
                }

                override fun onPlayerError(error: PlaybackException) {
                    watchdogHandler.removeCallbacks(bufferWatchdogRunnable)
                    retryHandler.removeCallbacks(resetRetryRunnable)
                    AppLogger.logError("LiveTvPlayer", "Player error: ${error.errorCodeName} (${error.errorCode})", error)

                    if (error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
                        error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) {
                        hideRetryBanner()
                        Toast.makeText(this@LiveTvActivity, "Audio-/Decoder-Fehler: Format wird vom Gerät nicht unterstützt", Toast.LENGTH_LONG).show()
                        return
                    }

                    val cause = error.cause
                    val isHttpAuthOrRateLimit = when (cause) {
                        is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException -> {
                            cause.responseCode in listOf(401, 403, 408, 429, 503)
                        }
                        else -> error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                    }
                    val isTimeoutOrConnFailed = error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                            cause is java.net.SocketTimeoutException ||
                            cause is java.net.ConnectException

                    if ((isHttpAuthOrRateLimit || isTimeoutOrConnFailed) && retryCount < MAX_STREAM_RETRIES) {
                        retryCount++
                        val msg = "⏳ Warte auf Stream-Freigabe... ($retryCount/$MAX_STREAM_RETRIES)"
                        showRetryBanner(msg)
                        AppLogger.logNetwork("Auto-Retry #$retryCount in 2.5s waiting for stream socket ($nowPlayingStreamId)")
                        retryRunnable?.let { retryHandler.removeCallbacks(it) }
                        retryRunnable = Runnable {
                            activeChannel?.let { ch ->
                                playStreamSource(ch, activeSourceIndex)
                            }
                        }
                        retryHandler.postDelayed(retryRunnable!!, 2500)
                        return
                    }

                    hideRetryBanner()
                    val errorMsg = error.cause?.message ?: error.message ?: "Wiedergabefehler"
                    triggerAutoFailover(errorMsg)
                }
            })
        }
    }

    private fun showRetryBanner(text: String) {
        binding.txtPlayerRetryBanner.text = text
        binding.txtPlayerRetryBanner.visibility = View.VISIBLE
    }

    private fun hideRetryBanner() {
        binding.txtPlayerRetryBanner.visibility = View.GONE
    }

    private var hasTestedSourcesCount = 0

    private fun checkAndCacheQuality(width: Int, height: Int) {
        val ch = activeChannel ?: return
        if (ch.sources.size <= 1) return
        if (QualityPreferenceManager.hasPreference(this, ch.cleanName)) return

        val currentSource = ch.sources.getOrNull(activeSourceIndex) ?: return

        if (width >= 1920 && height >= 1080) {
            QualityPreferenceManager.savePreferredStream(
                this,
                ch.cleanName,
                currentSource.streamId,
                "${width}x${height}"
            )
        } else if (width > 0 && width < 1920 && hasTestedSourcesCount < 2 && activeSourceIndex + 1 < ch.sources.size) {
            hasTestedSourcesCount++
            activeSourceIndex++
            playStreamSource(ch, activeSourceIndex)
        } else if (width > 0) {
            QualityPreferenceManager.savePreferredStream(
                this,
                ch.cleanName,
                currentSource.streamId,
                "${width}x${height}"
            )
        }
    }

    // Automatisches Failover auf die nächste verfügbare Quelle
    private fun triggerAutoFailover(reason: String) {
        val ch = activeChannel ?: return
        if (ch.sources.size <= 1) {
            Toast.makeText(this, "⚠️ Keine alternativen Quellen für ${ch.cleanName}", Toast.LENGTH_SHORT).show()
            return
        }

        if (activeSourceIndex + 1 < ch.sources.size) {
            activeSourceIndex++
            val nextSource = ch.sources[activeSourceIndex]
            Toast.makeText(
                this,
                "🔄 Failover ($reason):\nWechsle zu Quelle ${activeSourceIndex + 1}/${ch.sources.size}: ${nextSource.label}",
                Toast.LENGTH_LONG
            ).show()
            playStreamSource(ch, activeSourceIndex)
        } else {
            Toast.makeText(
                this,
                "❌ Alle ${ch.sources.size} Quellen für ${ch.cleanName} sind derzeit nicht erreichbar.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Manuelles Durchwechseln der Quellen (Gelbe Taste auf TV-Fernbedienung oder DPAD_RIGHT im OSD)
    private fun cycleToNextSourceManually() {
        val ch = activeChannel ?: return
        if (ch.sources.size <= 1) return
        activeSourceIndex = (activeSourceIndex + 1) % ch.sources.size
        val nextSource = ch.sources[activeSourceIndex]
        QualityPreferenceManager.savePreferredStream(
            this,
            ch.cleanName,
            nextSource.streamId,
            nextSource.label
        )
        playStreamSource(ch, activeSourceIndex)
        showOsd()
    }

    private fun cycleToPreviousSourceManually() {
        val ch = activeChannel ?: return
        if (ch.sources.size <= 1) return
        activeSourceIndex = if (activeSourceIndex - 1 < 0) ch.sources.size - 1 else activeSourceIndex - 1
        val prevSource = ch.sources[activeSourceIndex]
        QualityPreferenceManager.savePreferredStream(
            this,
            ch.cleanName,
            prevSource.streamId,
            prevSource.label
        )
        playStreamSource(ch, activeSourceIndex)
        showOsd()
    }

    override fun onResume() {
        super.onResume()
        val lastWatched = historyManager.getRecentLiveChannels().firstOrNull()
        if (lastWatched != null) {
            val matchingChannel = multiStreamCategoriesMap.values.flatten()
                .firstOrNull { ch -> ch.sources.any { it.streamId == lastWatched.streamId } || ch.cleanName.equals(lastWatched.name, ignoreCase = true) }

            val targetCatId = if (matchingChannel != null && matchingChannel.categoryId.isNotEmpty()) {
                matchingChannel.categoryId
            } else if (!lastWatched.categoryId.isNullOrEmpty() && displayedCategories.any { it.id == lastWatched.categoryId }) {
                lastWatched.categoryId
            } else {
                val rawStream = allLiveStreamsGlobal.firstOrNull { it.streamId == lastWatched.streamId }
                    ?: allLiveStreamsCache.firstOrNull { it.streamId == lastWatched.streamId }
                if (rawStream != null) {
                    MultiStreamManager.resolveMainCategoryIdForStream(rawStream, rawCategoriesCache)
                } else {
                    MultiStreamManager.findMainCategoryByChannelName(lastWatched.name)
                }
            }

            val targetCat = displayedCategories.firstOrNull { it.id == targetCatId }

            if (lastWatched.streamId != nowPlayingStreamId) {
                if (targetCat != null) {
                    browsingCategoryId = targetCat.id
                    playingCategoryId = targetCat.id
                    categoryAdapter?.updateCategoryStates(playingCategoryId, browsingCategoryId)
                    val catPos = displayedCategories.indexOf(targetCat)
                    if (catPos >= 0) {
                        binding.recyclerCategories.scrollToPosition(catPos)
                    }
                    loadChannels(targetCat, preselectedStreamId = lastWatched.streamId)
                }
                if (matchingChannel != null) {
                    val srcIdx = matchingChannel.sources.indexOfFirst { it.streamId == lastWatched.streamId }.coerceAtLeast(0)
                    playMultiStreamChannel(matchingChannel, srcIdx)
                } else {
                    playLiveStream(lastWatched)
                }
            } else {
                if (targetCat != null && browsingCategoryId != targetCat.id) {
                    browsingCategoryId = targetCat.id
                    playingCategoryId = targetCat.id
                    categoryAdapter?.updateCategoryStates(playingCategoryId, browsingCategoryId)
                    val catPos = displayedCategories.indexOf(targetCat)
                    if (catPos >= 0) {
                        binding.recyclerCategories.scrollToPosition(catPos)
                    }
                    loadChannels(targetCat, preselectedStreamId = lastWatched.streamId)
                }
                if (livePlayer != null && activeStream != null && !livePlayer!!.isPlaying) {
                    livePlayer?.play()
                }
            }
        } else if (livePlayer != null && activeStream != null && !livePlayer!!.isPlaying) {
            livePlayer?.play()
        }
        binding.root.post {
            updatePipPosition()
        }
    }

    override fun onPause() {
        super.onPause()
        AppLogger.logLifecycle("LiveTvActivity", "onPause -> Releasing stream connections")
        watchdogHandler.removeCallbacks(bufferWatchdogRunnable)
        retryRunnable?.let { retryHandler.removeCallbacks(it) }
        streamJob?.cancel()
        epgFetchJob?.cancel()
        PlayerUtils.releaseStreamConnections(livePlayer)
    }

    override fun onStop() {
        super.onStop()
        AppLogger.logLifecycle("LiveTvActivity", "onStop -> Releasing stream connections")
        watchdogHandler.removeCallbacks(bufferWatchdogRunnable)
        retryRunnable?.let { retryHandler.removeCallbacks(it) }
        streamJob?.cancel()
        epgFetchJob?.cancel()
        PlayerUtils.releaseStreamConnections(livePlayer)
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLogger.logLifecycle("LiveTvActivity", "onDestroy")
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (e: Exception) {}
        watchdogHandler.removeCallbacks(bufferWatchdogRunnable)
        osdHandler.removeCallbacks(hideOsdRunnable)
        retryRunnable?.let { retryHandler.removeCallbacks(it) }
        streamJob?.cancel()
        epgFetchJob?.cancel()
        PlayerUtils.releaseStreamConnections(livePlayer)
        livePlayer?.release()
        livePlayer = null
    }

    // Startet einen Kanal mit automatischer Priorisierung
    fun playMultiStreamChannel(channel: MultiStreamChannel, sourceIndex: Int = 0) {
        // Zappen State-Collision-Fix: Alle vorherigen Lade-Jobs & Timeouts hart abbrechen
        streamJob?.cancel()
        epgFetchJob?.cancel()
        watchdogHandler.removeCallbacks(bufferWatchdogRunnable)
        retryRunnable?.let { retryHandler.removeCallbacks(it) }
        retryCount = 0
        hideRetryBanner()

        // Vor dem Laden stets stop() & clearMediaItems()
        livePlayer?.stop()
        livePlayer?.clearMediaItems()
        PlayerUtils.cancelPendingMediaRequests()

        val prevIdx = currentChannelItems.indexOfFirst {
            it.channel.cleanName == activeChannel?.cleanName || it.channel.sources.any { s -> s.streamId == nowPlayingStreamId }
        }

        val ch = MultiStreamManager.applyPreferredSources(this, channel)
        activeChannel = ch
        activeSourceIndex = sourceIndex.coerceIn(0, (ch.sources.size - 1).coerceAtLeast(0))
        val currentSource = ch.sources.getOrNull(activeSourceIndex)
        val streamId = currentSource?.streamId ?: 0

        nowPlayingStreamId = streamId
        playingCategoryId = ch.categoryId

        val stream = LiveStream(
            streamId = streamId,
            name = ch.cleanName,
            streamIcon = ch.icon,
            epgChannelId = ch.epgId,
            categoryId = ch.categoryId
        )
        activeStream = stream
        historyManager.saveLiveChannel(stream)

        val newIdx = currentChannelItems.indexOfFirst {
            it.channel.cleanName == ch.cleanName || it.channel.sources.any { s -> s.streamId == streamId }
        }
        if (prevIdx != -1 && prevIdx != newIdx) {
            channelAdapter?.notifyItemChanged(prevIdx)
        }
        if (newIdx != -1) {
            channelAdapter?.notifyItemChanged(newIdx)
        }
        categoryAdapter?.updateCategoryStates(playingId = playingCategoryId, newBrowsingId = browsingCategoryId)

        isPlayingJustStarted = true
        binding.recyclerChannels.postDelayed({
            isPlayingJustStarted = false
        }, 300)

        // Wichtig: activeStreamEpg sofort zurücksetzen, damit niemals der EPG des vorherigen Senders gezeigt wird!
        activeStreamEpg = null

        playStreamSource(ch, activeSourceIndex)

        val cachedEpg = epgGlobalCache[channel.cleanName] ?: currentChannelItems.firstOrNull { it.channel.cleanName == channel.cleanName }?.epgList?.filter { it.title != "Lade EPG..." }
        if (!cachedEpg.isNullOrEmpty()) {
            activeStreamEpg = cachedEpg.firstOrNull()
            updatePipProgramInfo(stream, activeStreamEpg)
            if (isFullscreen) {
                updateOsdContent(stream, activeStreamEpg)
            }
        } else {
            // Kein EPG vorhanden: Sofort saubere OSD- und PIP-Ansicht ohne alten EPG anzeigen
            updatePipProgramInfo(stream, null)
            if (isFullscreen) {
                updateOsdContent(stream, null)
            }

            epgFetchJob = lifecycleScope.launch {
                val currentPlayingStreamId = nowPlayingStreamId
                var fetched: List<EpgProgram> = emptyList()
                val candidates = mutableListOf<Int>()
                channel.epgStreamId?.let { candidates.add(it) }
                channel.sources.forEach { if (!candidates.contains(it.streamId)) candidates.add(it.streamId) }
                for (sid in candidates) {
                    val res = client.getEpg(sid)
                    if (res.isNotEmpty()) {
                        fetched = res
                        break
                    }
                }
                if (fetched.isNotEmpty() && nowPlayingStreamId == currentPlayingStreamId) {
                    epgGlobalCache[channel.cleanName] = fetched
                    activeStreamEpg = fetched.firstOrNull()
                    updatePipProgramInfo(stream, activeStreamEpg)
                    if (isFullscreen) {
                        updateOsdContent(stream, activeStreamEpg)
                    }
                }
            }
        }
    }

    private fun playStreamSource(channel: MultiStreamChannel, sourceIndex: Int) {
        val source = channel.sources.getOrNull(sourceIndex) ?: return
        watchdogHandler.removeCallbacks(bufferWatchdogRunnable)
        retryRunnable?.let { retryHandler.removeCallbacks(it) }

        // Vorherigen Stream-Ladevorgang hart abbrechen
        streamJob?.cancel()

        nowPlayingStreamId = source.streamId
        AppLogger.logPlayerState("LiveTv", "Zapping to stream ${source.streamId} (${source.label}) for channel ${channel.cleanName}")

        streamJob = lifecycleScope.launch {
            val url = client.getLiveStreamUrl(source.streamId)
            val mediaItem = MediaItem.fromUri(url)

            livePlayer?.stop()
            livePlayer?.clearMediaItems()
            livePlayer?.setMediaItem(mediaItem)
            livePlayer?.prepare()
            livePlayer?.playWhenReady = true

            // 10s Puffer-Watchdog starten
            watchdogHandler.postDelayed(bufferWatchdogRunnable, 10000)

            updateOsdSourceBadge()
            if (activeStream != null) {
                updatePipProgramInfo(activeStream!!, activeStreamEpg)
            }
        }
    }

    // Rückwärtskompatible Methode
    private fun playLiveStream(stream: LiveStream) {
        val matchingChannel = multiStreamCategoriesMap.values.flatten()
            .firstOrNull { ch -> ch.sources.any { it.streamId == stream.streamId } || ch.cleanName.equals(stream.name, ignoreCase = true) }

        if (matchingChannel != null) {
            val srcIndex = matchingChannel.sources.indexOfFirst { it.streamId == stream.streamId }.coerceAtLeast(0)
            playMultiStreamChannel(matchingChannel, srcIndex)
        } else {
            val synthChannel = MultiStreamChannel(
                cleanName = stream.name,
                originalName = stream.name,
                categoryId = stream.categoryId ?: "",
                categoryName = "",
                icon = stream.streamIcon,
                epgId = stream.epgChannelId,
                sources = listOf(
                    StreamSource(
                        streamId = stream.streamId,
                        name = stream.name,
                        label = "Standard Stream",
                        score = 50,
                        subcategory = ""
                    )
                )
            )
            playMultiStreamChannel(synthChannel, 0)
        }
    }

    private fun updatePipProgramInfo(stream: LiveStream, program: EpgProgram?) {
        val sourceInfo = if (activeChannel != null && activeChannel!!.sources.size > 1) {
            activeChannel?.sources?.getOrNull(activeSourceIndex)?.name ?: ""
        } else ""
        val displayName = activeChannel?.cleanName ?: stream.name
        val titleText = if (sourceInfo.isNotEmpty()) "$displayName [$sourceInfo]" else displayName

        if (program != null) {
            binding.txtPreviewTitle.text = "$titleText – ${program.title}"
            binding.txtPreviewTime.text = "${program.start} - ${program.end}"
            binding.txtPreviewDesc.text = if (program.description.isNotEmpty()) program.description else "Keine Programmbeschreibung vorhanden."

            val progress = calculateProgress(program.start, program.end)
            binding.progressCurrentProgram.progress = progress
            binding.progressCurrentProgram.visibility = View.VISIBLE
        } else {
            binding.txtPreviewTitle.text = titleText
            binding.txtPreviewTime.text = "🔴 LIVE"
            binding.txtPreviewDesc.text = "OK = Vollbild. Gelbe Taste = Backup-Quelle wechseln (${activeChannel?.sources?.size ?: 1} verf.)."
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

        // Focus lock: Block DPAD focus on background elements
        binding.layoutOverview.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        binding.layoutOverview.isFocusable = false

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

        // Restore focusability on background elements
        binding.layoutOverview.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        binding.layoutOverview.isFocusable = false

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

    private fun updateOsdSourceBadge() {
        val ch = activeChannel ?: return
        val src = ch.sources.getOrNull(activeSourceIndex) ?: return
        val count = ch.sources.size
        val badge = if (count > 1) {
            "⚡ Quelle ${activeSourceIndex + 1}/$count: ${src.label}"
        } else {
            "⚡ ${src.label}"
        }
        binding.txtOsdSourceInfo.text = badge
        binding.txtOsdSourceInfo.visibility = View.VISIBLE
    }

    private fun updateOsdContent(stream: LiveStream, program: EpgProgram?) {
        binding.txtOsdChannelNum.text = "${activePlaylistIndex + 1}"
        val displayName = activeChannel?.cleanName ?: stream.name
        binding.txtOsdChannelName.text = displayName

        updateOsdSourceBadge()

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

        if (program != null && program.title != "Lade EPG...") {
            binding.txtOsdProgramTitle.text = "🔴 JETZT: ${program.title}"
            binding.txtOsdProgramTime.text = "${program.start} - ${program.end}"
            binding.txtOsdProgramDesc.text = if (program.description.isNotEmpty()) program.description else "Keine Programmbeschreibung vorhanden."
            val prog = calculateProgress(program.start, program.end)
            binding.progressOsdProgram.progress = prog
            binding.progressOsdProgram.visibility = View.VISIBLE
        } else {
            binding.txtOsdProgramTitle.text = "🔴 LIVE TV"
            binding.txtOsdProgramTime.text = ""
            binding.txtOsdProgramDesc.text = ""
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
        binding.progressCategories.visibility = View.GONE
        displayedCategories = MultiStreamManager.MAIN_CATEGORIES
        allCategories = displayedCategories

        categoryAdapter = CategoryAdapter(displayedCategories) { category ->
            loadChannels(category)
        }
        binding.recyclerCategories.adapter = categoryAdapter

        // 1. OFFLINE-FIRST: Sofort aus lokalem Disk-Cache laden (Ladezeit ~50 ms)
        val diskBundled = cacheManager.loadBundledChannels()
        val diskRawCats = cacheManager.loadRawCategories()
        if (!diskRawCats.isNullOrEmpty()) {
            rawCategoriesCache = diskRawCats
        }
        if (!diskBundled.isNullOrEmpty()) {
            diskBundled.forEach { (catId, list) ->
                multiStreamCategoriesMap[catId] = list
                categoryChannelMap[catId] = list.map { ChannelWithEpg(channel = it) }
            }
        }

        val targetStreamId = intent.getIntExtra("TARGET_STREAM_ID", -1).takeIf { it != -1 }
        val startFullscreen = intent.getBooleanExtra("START_FULLSCREEN", false)

        val lastWatched = if (targetStreamId != null) {
            historyManager.getRecentLiveChannels().firstOrNull { it.streamId == targetStreamId }
                ?: allLiveStreamsGlobal.firstOrNull { it.streamId == targetStreamId }
                ?: LiveStream(name = "", streamId = targetStreamId)
        } else {
            historyManager.getRecentLiveChannels().firstOrNull()
        }

        val targetCatId = if (lastWatched != null) {
            if (!lastWatched.categoryId.isNullOrEmpty() && displayedCategories.any { it.id == lastWatched.categoryId }) {
                lastWatched.categoryId
            } else {
                val rawStream = allLiveStreamsGlobal.firstOrNull { it.streamId == lastWatched.streamId }
                    ?: allLiveStreamsCache.firstOrNull { it.streamId == lastWatched.streamId }
                if (rawStream != null) {
                    MultiStreamManager.resolveMainCategoryIdForStream(rawStream, rawCategoriesCache)
                } else {
                    MultiStreamManager.findMainCategoryByChannelName(lastWatched.name)
                }
            }
        } else {
            "MAIN_FREETV"
        }

        val targetCategory = displayedCategories.firstOrNull { it.id == targetCatId } ?: displayedCategories[0]

        playingCategoryId = targetCategory.id
        browsingCategoryId = targetCategory.id
        categoryAdapter?.updateCategoryStates(playingCategoryId, browsingCategoryId)
        val catPos = displayedCategories.indexOf(targetCategory)
        if (catPos >= 0) {
            binding.recyclerCategories.scrollToPosition(catPos)
        }
        loadChannels(targetCategory, preselectedStreamId = lastWatched?.streamId)

        if (startFullscreen) {
            setFullscreenMode()
        }

        // 2. STILLES HINTERGRUND-UPDATE: Falls Cache fehlt oder älter als 12 Stunden ist
        if (!cacheManager.isCacheValid(maxAgeHours = 12)) {
            syncLiveTvInBackground(forceRefresh = false)
        }
    }

    private var isSyncingLiveTv = false

    private fun syncLiveTvInBackground(forceRefresh: Boolean = false) {
        if (isSyncingLiveTv) return
        isSyncingLiveTv = true

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val freshCats = client.getLiveCategories()
                if (freshCats.isNotEmpty()) {
                    rawCategoriesCache = freshCats
                    cacheManager.saveRawCategories(freshCats)
                }

                val allRawIdsToFetch = MultiStreamManager.MAIN_CATEGORIES.flatMap { mainCat ->
                    MultiStreamManager.getRawCategoryIdsForMain(mainCat.id, rawCategoriesCache)
                }.distinct()

                val allStreams = coroutineScope {
                    allRawIdsToFetch.map { cid ->
                        async {
                            try {
                                val sList = client.getLiveStreams(cid)
                                rawCategoryStreamsCache[cid] = sList
                                sList
                            } catch (e: Exception) {
                                emptyList()
                            }
                        }
                    }.awaitAll().flatten()
                }

                if (allStreams.isNotEmpty()) {
                    val bundledMap = MultiStreamManager.buildMultiStreamCategories(allStreams, rawCategoriesCache, this@LiveTvActivity)
                    cacheManager.saveBundledChannels(bundledMap)

                    withContext(Dispatchers.Main) {
                        bundledMap.forEach { (catId, list) ->
                            multiStreamCategoriesMap[catId] = list
                            categoryChannelMap[catId] = list.map { ChannelWithEpg(channel = it) }
                        }
                        allLiveStreamsGlobal = rawCategoryStreamsCache.values.flatten().distinctBy { it.streamId }
                        allLiveStreamsCache = allLiveStreamsGlobal

                        val currentBrowsing = browsingCategoryId
                        if (currentBrowsing != null) {
                            val updated = categoryChannelMap[currentBrowsing] ?: emptyList()
                            if (updated.isNotEmpty() && currentChannelItems.isEmpty()) {
                                currentChannelItems = updated
                                activePlaylist = updated
                                binding.progressChannels.visibility = View.GONE
                                channelAdapter?.updateItems(updated)
                            }
                        }
                        if (forceRefresh) {
                            Toast.makeText(this@LiveTvActivity, "✅ Senderliste erfolgreich aktualisiert!", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                if (forceRefresh) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@LiveTvActivity, "Aktualisierung fehlgeschlagen: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                isSyncingLiveTv = false
            }
        }
    }

    private fun loadChannels(category: Category, preselectedStreamId: Int? = null) {
        if (browsingCategoryId == category.id && preselectedStreamId == null && currentChannelItems.isNotEmpty()) {
            focusTargetChannelForCategory(category)
            return
        }

        isSwitchingCategories = true
        val oldBrowsingId = browsingCategoryId
        browsingCategoryId = category.id

        categoryAdapter?.updateCategoryStates(
            playingId = playingCategoryId,
            newBrowsingId = browsingCategoryId,
            oldBrowsingId = oldBrowsingId
        )

        val cachedChannels = categoryChannelMap[category.id]
        if (!cachedChannels.isNullOrEmpty()) {
            currentChannelItems = cachedChannels
            activePlaylist = cachedChannels
            binding.progressChannels.visibility = View.GONE
            channelAdapter?.updateItems(cachedChannels)
            selectTargetChannelInList(category, preselectedStreamId)
            return
        }

        binding.progressChannels.visibility = View.VISIBLE
        channelAdapter?.updateItems(emptyList())

        lifecycleScope.launch {
            try {
                if (rawCategoriesCache.isEmpty()) {
                    rawCategoriesCache = withContext(Dispatchers.IO) { client.getLiveCategories() }
                }

                val rawIds = MultiStreamManager.getRawCategoryIdsForMain(category.id, rawCategoriesCache)
                val streams = withContext(Dispatchers.IO) {
                    coroutineScope {
                        rawIds.map { cid ->
                            async {
                                rawCategoryStreamsCache.getOrPut(cid) {
                                    try {
                                        client.getLiveStreams(cid)
                                    } catch (e: Exception) {
                                        emptyList()
                                    }
                                }
                            }
                        }.awaitAll().flatten()
                    }
                }

                val bundledMap = MultiStreamManager.buildMultiStreamCategories(streams, rawCategoriesCache, this@LiveTvActivity)
                val bundledList = bundledMap[category.id] ?: emptyList()
                multiStreamCategoriesMap[category.id] = bundledList
                val channelList = bundledList.map { ChannelWithEpg(channel = it) }
                categoryChannelMap[category.id] = channelList

                allLiveStreamsGlobal = rawCategoryStreamsCache.values.flatten().distinctBy { it.streamId }
                allLiveStreamsCache = allLiveStreamsGlobal

                if (browsingCategoryId == category.id) {
                    binding.progressChannels.visibility = View.GONE
                    currentChannelItems = channelList
                    activePlaylist = channelList
                    channelAdapter?.updateItems(channelList)
                    selectTargetChannelInList(category, preselectedStreamId)
                }
            } catch (e: Exception) {
                binding.progressChannels.visibility = View.GONE
                Toast.makeText(this@LiveTvActivity, "Fehler beim Laden: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun selectTargetChannelInList(category: Category, preselectedStreamId: Int?) {
        val targetItem = if (preselectedStreamId != null) {
            currentChannelItems.firstOrNull { item ->
                item.channel.sources.any { it.streamId == preselectedStreamId } || item.channel.cleanName.equals(activeStream?.name, ignoreCase = true)
            } ?: currentChannelItems.firstOrNull()
        } else if (category.id == playingCategoryId && activeChannel != null) {
            currentChannelItems.firstOrNull { it.channel.cleanName == activeChannel?.cleanName } ?: currentChannelItems.firstOrNull()
        } else {
            currentChannelItems.firstOrNull()
        }

        if (targetItem != null) {
            showChannelPreview(targetItem.stream, null)
            if (isInitialLoad) {
                isInitialLoad = false
                playMultiStreamChannel(targetItem.channel, 0)
            }

            val targetPos = currentChannelItems.indexOf(targetItem).coerceAtLeast(0)
            focusTargetChannel(targetPos)
        }
    }

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
        if (cat.id != browsingCategoryId) {
            val browsingIndex = displayedCategories.indexOfFirst { it.id == browsingCategoryId }
            if (browsingIndex >= 0) {
                val lm = binding.recyclerCategories.layoutManager as? LinearLayoutManager
                lm?.scrollToPositionWithOffset(browsingIndex, dpToPx(40)) ?: binding.recyclerCategories.scrollToPosition(browsingIndex)
                categoryAdapter?.updateCategoryStates(playingCategoryId, browsingCategoryId)
            }
            if (activePlaylistIndex in currentChannelItems.indices) {
                focusTargetChannel(activePlaylistIndex)
            } else {
                focusTargetChannel(0)
            }
            return
        }

        if (cat.id == playingCategoryId && activeChannel != null) {
            val targetIndex = currentChannelItems.indexOfFirst { it.channel.cleanName == activeChannel?.cleanName }
            if (targetIndex != -1) {
                focusTargetChannel(targetIndex)
                return
            }
        }
        if (activePlaylistIndex in currentChannelItems.indices) {
            focusTargetChannel(activePlaylistIndex)
        } else {
            focusTargetChannel(0)
        }
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
        val ch = currentChannelItems.firstOrNull { it.channel.cleanName == stream.name }?.channel
        val sourceInfo = ch?.primarySource?.label ?: ""
        val displayName = ch?.cleanName ?: stream.name
        val titleText = if (sourceInfo.isNotEmpty()) "$displayName [$sourceInfo]" else displayName

        if (program != null && program.title != "Lade EPG...") {
            binding.txtPreviewTitle.text = "$titleText – ${program.title}"
            binding.txtPreviewTime.text = "${program.start} - ${program.end}"
            binding.txtPreviewDesc.text = if (program.description.isNotEmpty()) program.description else "Keine Programmbeschreibung vorhanden."
            val prog = calculateProgress(program.start, program.end)
            binding.progressCurrentProgram.progress = prog
            binding.progressCurrentProgram.visibility = View.VISIBLE
        } else {
            val cached = epgGlobalCache[displayName]?.firstOrNull()
                ?: currentChannelItems.firstOrNull { it.stream.streamId == stream.streamId }?.epgList?.firstOrNull { it.title != "Lade EPG..." }
            if (cached != null && cached.title != "Lade EPG...") {
                binding.txtPreviewTitle.text = "$titleText – ${cached.title}"
                binding.txtPreviewTime.text = "${cached.start} - ${cached.end}"
                binding.txtPreviewDesc.text = if (cached.description.isNotEmpty()) cached.description else "Keine Programmbeschreibung vorhanden."
                val prog = calculateProgress(cached.start, cached.end)
                binding.progressCurrentProgram.progress = prog
                binding.progressCurrentProgram.visibility = View.VISIBLE
            } else {
                binding.txtPreviewTitle.text = titleText
                binding.txtPreviewTime.text = "🔴 LIVE"
                val count = ch?.sources?.size ?: 1
                binding.txtPreviewDesc.text = "Drücke OK für Vollbild. $count Quelle(n) gebündelt mit Auto-Failover."
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
            val viewName = try {
                currentFocus?.let { resources.getResourceEntryName(it.id) } ?: "null"
            } catch (e: Exception) {
                currentFocus?.javaClass?.simpleName ?: "unknown"
            }
            AppLogger.logFocus(event.keyCode, KeyEvent.keyCodeToString(event.keyCode), viewName)

            // Taste Suche auf Fernbedienung öffnet Suche
            if (event.keyCode == KeyEvent.KEYCODE_SEARCH) {
                val intent = Intent(this, SearchActivity::class.java).apply {
                    putExtra("SEARCH_TYPE", "LIVE")
                }
                searchLauncher.launch(intent)
                return true
            }

            // Farbtaste Gelb (Remote Key) wechselt manuell die Quelle
            if (event.keyCode == KeyEvent.KEYCODE_PROG_YELLOW || event.keyCode == KeyEvent.KEYCODE_BUTTON_Y) {
                cycleToNextSourceManually()
                return true
            }
            // Farbtaste Blau (Remote Key) aktualisiert die Senderliste
            if (event.keyCode == KeyEvent.KEYCODE_PROG_BLUE || event.keyCode == KeyEvent.KEYCODE_BUTTON_B) {
                Toast.makeText(this, "🔄 Senderliste wird im Hintergrund aktualisiert...", Toast.LENGTH_SHORT).show()
                syncLiveTvInBackground(forceRefresh = true)
                return true
            }

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
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (binding.layoutFullscreenOsd.visibility == View.VISIBLE) {
                            cycleToNextSourceManually()
                            return true
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (binding.layoutFullscreenOsd.visibility == View.VISIBLE) {
                            cycleToPreviousSourceManually()
                            return true
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (activePlaylistIndex > 0) {
                            activePlaylistIndex--
                            val item = activePlaylist[activePlaylistIndex]
                            playMultiStreamChannel(item.channel, 0)
                            showOsd()
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (activePlaylistIndex < activePlaylist.size - 1) {
                            activePlaylistIndex++
                            val item = activePlaylist[activePlaylistIndex]
                            playMultiStreamChannel(item.channel, 0)
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

                            val lm = binding.recyclerChannels.layoutManager as? LinearLayoutManager
                            val lastCompletelyVisible = lm?.findLastCompletelyVisibleItemPosition() ?: -1

                            if (nextPos > lastCompletelyVisible) {
                                // Erst wenn der letzte sichtbare Sender erreicht ist, rutscht die Liste nach unten
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
                            } else {
                                // Nächster Sender ist BEREITS VOLLSTÄNDIG SICHTBAR: Kein Scrollen, nur Highlight wandert!
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

                            val lm = binding.recyclerChannels.layoutManager as? LinearLayoutManager
                            val firstCompletelyVisible = lm?.findFirstCompletelyVisibleItemPosition() ?: -1

                            if (prevPos < firstCompletelyVisible) {
                                // Erst wenn der oberste sichtbare Sender erreicht ist, rutscht die Liste nach oben
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
                            } else {
                                // Vorheriger Sender ist BEREITS VOLLSTÄNDIG SICHTBAR: Kein Scrollen, nur Highlight wandert!
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
            notifyDataSetChanged()
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

        private fun applyCategoryStyle(holder: ViewHolder, cat: Category) {
            val isPlaying = (cat.id == playingCategoryId)
            val isBrowsing = (cat.id == browsingCategoryId)
            val isFocused = holder.itemView.isFocused

            // Nur die aktuell spielende Kategorie zeigt den roten Punkt!
            holder.dot.visibility = if (isPlaying) View.VISIBLE else View.GONE
            holder.txtName.text = cat.name
            holder.txtName.setTextColor(
                if (isFocused || isBrowsing) Color.parseColor("#FFFFFF") else Color.parseColor("#B0B0B0")
            )

            val drawable = GradientDrawable().apply {
                cornerRadius = dpToPx(6).toFloat()
                when {
                    isFocused -> {
                        if (isBrowsing) {
                            setColor(Color.parseColor("#3E271E"))
                        } else {
                            setColor(Color.parseColor("#2A2B32"))
                        }
                        setStroke(dpToPx(3), Color.parseColor("#C5866D"))
                    }
                    isBrowsing -> {
                        setColor(Color.parseColor("#352219"))
                        setStroke(dpToPx(1.5f.toInt()), Color.parseColor("#6B3F2E"))
                    }
                    else -> {
                        setColor(Color.parseColor("#17181C"))
                        setStroke(dpToPx(1), Color.parseColor("#23242A"))
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

    // --- Adapter 2: Senderzeilen mit dynamischem rotem Punkt & Multi-Stream Badge ---
    inner class ChannelAdapter(
        private var items: List<ChannelWithEpg>
    ) : RecyclerView.Adapter<ChannelAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val header: View = view.findViewById(R.id.channelHeader)
            val txtNum: TextView = view.findViewById(R.id.txtChannelNum)
            val imgLogo: ImageView = view.findViewById(R.id.imgChannelLogo)
            val dot: View = view.findViewById(R.id.dotChannelIndicator)
            val txtName: TextView = view.findViewById(R.id.txtChannelName)
            val txtSourceBadge: TextView = view.findViewById(R.id.txtSourceBadge)
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
            val ch = item.channel
            val s = item.stream
            val isPlaying = (activeChannel?.cleanName == ch.cleanName || ch.sources.any { it.streamId == nowPlayingStreamId })

            holder.txtName.text = ch.cleanName

            // Multi-Stream Badge (z. B. "3 Q.")
            if (ch.sources.size > 1) {
                holder.txtSourceBadge.text = "${ch.sources.size} Q."
                holder.txtSourceBadge.visibility = View.VISIBLE
            } else {
                holder.txtSourceBadge.visibility = View.GONE
            }

            // Dynamischer roter Punkt für aktuell laufenden Sender
            holder.dot.visibility = if (isPlaying) View.VISIBLE else View.GONE

            if (isPlaying) {
                holder.txtNum.text = "${position + 1}"
                holder.txtNum.setTextColor(Color.parseColor("#C5866D"))
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

            applyChannelHeaderStyle(holder)

            holder.header.setOnFocusChangeListener { _, _ ->
                applyChannelHeaderStyle(holder)
                if (holder.header.isFocused) {
                    holder.txtName.isSelected = true
                    activePlaylistIndex = position
                    showChannelPreview(s, item.epgList.firstOrNull())
                } else {
                    holder.txtName.isSelected = false
                    if (activeStream != null) {
                        updatePipProgramInfo(activeStream!!, activeStreamEpg)
                    }
                }
            }

            holder.header.setOnClickListener {
                val isCurrentlyPlaying = (activeChannel?.cleanName.equals(ch.cleanName, ignoreCase = true) ||
                        ch.sources.any { it.streamId == nowPlayingStreamId }) && livePlayer != null

                if (isCurrentlyPlaying) {
                    setFullscreenMode()
                } else {
                    activePlaylist = currentChannelItems
                    activePlaylistIndex = position
                    playMultiStreamChannel(ch, 0)
                    holder.header.post {
                        holder.header.requestFocus()
                    }
                }
            }

            holder.recyclerPrograms.apply {
                layoutManager = LinearLayoutManager(holder.itemView.context, LinearLayoutManager.HORIZONTAL, false)
                setHasFixedSize(true)
            }

            // EPG Laden: Erst aus globalem Cache oder Quellenauswahl mit vorhandenem EPG
            val cachedPrograms = epgGlobalCache[ch.cleanName] ?: item.epgList
            if (cachedPrograms.isNotEmpty()) {
                item.epgList = cachedPrograms
                holder.recyclerPrograms.adapter = ProgramTimelineAdapter(ch, position, cachedPrograms, holder)
            } else {
                val fallback = listOf(EpgProgram("Lade EPG...", "", "Jetzt", "", true))
                holder.recyclerPrograms.adapter = ProgramTimelineAdapter(ch, position, fallback, holder)

                lifecycleScope.launch {
                    var fetchedEpg: List<EpgProgram> = emptyList()
                    val candidates = mutableListOf<Int>()
                    ch.epgStreamId?.let { candidates.add(it) }
                    ch.sources.forEach { if (!candidates.contains(it.streamId)) candidates.add(it.streamId) }

                    for (candidateId in candidates) {
                        val list = client.getEpg(candidateId)
                        if (list.isNotEmpty()) {
                            fetchedEpg = list
                            break
                        }
                    }

                    if (fetchedEpg.isNotEmpty()) {
                        epgGlobalCache[ch.cleanName] = fetchedEpg
                        item.epgList = fetchedEpg
                        holder.recyclerPrograms.adapter = ProgramTimelineAdapter(ch, position, fetchedEpg, holder)
                        if (activeChannel?.cleanName == ch.cleanName && activeStreamEpg == null) {
                            activeStreamEpg = fetchedEpg.firstOrNull()
                            activeStream?.let { updatePipProgramInfo(it, activeStreamEpg) }
                        }
                    }
                }
            }
        }

        private fun applyChannelHeaderStyle(holder: ViewHolder) {
            val isFocused = holder.header.isFocused
            holder.txtName.isSelected = isFocused
            val drawable = GradientDrawable().apply {
                cornerRadius = dpToPx(6).toFloat()
                when {
                    isFocused -> {
                        setColor(Color.parseColor("#2A2B32"))
                        setStroke(dpToPx(3), Color.parseColor("#C5866D"))
                    }
                    else -> {
                        setColor(Color.parseColor("#17181C"))
                        setStroke(dpToPx(1), Color.parseColor("#23242A"))
                    }
                }
            }
            holder.header.background = drawable
        }

        override fun getItemCount() = items.size
    }

    // --- Adapter 3: Horizontale EPG-Sendungsblöcke (Timeline) ---
    inner class ProgramTimelineAdapter(
        private val channel: MultiStreamChannel,
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
                channelHolder.txtName.isSelected = hasFocus
                holder.txtTitle.isSelected = hasFocus
                if (hasFocus) {
                    val s = LiveStream(
                        streamId = channel.primarySource?.streamId ?: 0,
                        name = channel.cleanName,
                        streamIcon = channel.icon,
                        epgChannelId = channel.epgId,
                        categoryId = channel.categoryId
                    )
                    showChannelPreview(s, p)
                }
            }

            holder.itemView.setOnClickListener {
                val isCurrentlyPlaying = (activeChannel?.cleanName.equals(channel.cleanName, ignoreCase = true) ||
                        channel.sources.any { it.streamId == nowPlayingStreamId }) && livePlayer != null

                if (isCurrentlyPlaying) {
                    setFullscreenMode()
                } else {
                    activePlaylist = currentChannelItems
                    activePlaylistIndex = channelIndex
                    playMultiStreamChannel(channel, 0)
                    holder.itemView.post {
                        holder.itemView.requestFocus()
                    }
                }
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
