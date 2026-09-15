package com.alex.iptvplayer.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.alex.iptvplayer.data.EpisodeItem
import com.alex.iptvplayer.data.HistoryManager
import com.alex.iptvplayer.data.LiveStream
import com.alex.iptvplayer.data.MultiStreamChannel
import com.alex.iptvplayer.data.MultiStreamManager
import com.alex.iptvplayer.data.QualityPreferenceManager
import com.alex.iptvplayer.data.XtreamClient
import com.alex.iptvplayer.databinding.ActivityPlayerBinding
import com.alex.iptvplayer.ui.player.PlayerOsdController
import com.alex.iptvplayer.ui.player.PlayerScrubberHelper
import com.alex.iptvplayer.ui.player.PlayerStatsOverlayHelper
import com.alex.iptvplayer.ui.player.PlayerTrackDialogHelper
import com.alex.iptvplayer.util.AppLogger
import com.alex.iptvplayer.util.PlayerUtils
import java.util.Locale

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var exoPlayer: ExoPlayer? = null
    private lateinit var client: XtreamClient
    private lateinit var historyManager: HistoryManager

    // Modulare Helfer
    private lateinit var statsOverlayHelper: PlayerStatsOverlayHelper
    private lateinit var scrubberHelper: PlayerScrubberHelper
    private lateinit var trackDialogHelper: PlayerTrackDialogHelper
    private lateinit var osdController: PlayerOsdController

    private var isLive: Boolean = false
    private var streamList: List<LiveStream> = emptyList()
    private var channelList: List<MultiStreamChannel> = emptyList()
    private var activeChannel: MultiStreamChannel? = null
    private var activeSourceIndex: Int = 0
    private var episodeList: List<EpisodeItem> = emptyList()
    private var currentIndex: Int = -1
    private var currentEpisodeIndex: Int = -1
    private var currentStreamId: Int = -1
    private var currentStreamUrl: String = ""
    private var currentStreamName: String = ""
    private var currentPosterUrl: String? = null
    private var currentType: String = "VOD"
    private var seasonNum: Int = 1
    private var episodeNum: Int = 1
    private var seriesId: Int = -1

    private val progressHandler = Handler(Looper.getMainLooper())
    private val retryHandler = Handler(Looper.getMainLooper())

    private var retryCount = 0
    private val maxRetries = 5
    private val resetRetryRunnable = Runnable { retryCount = 0 }
    private var lastKnownPosition: Long = 0L
    private var lastKnownDuration: Long = 0L
    private var hasTestedSourcesCount = 0

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                AppLogger.logLifecycle("PlayerActivity", "ACTION_SCREEN_OFF -> Releasing stream connections")
                saveCurrentState()
                retryHandler.removeCallbacksAndMessages(null)
                PlayerUtils.releaseStreamConnections(exoPlayer)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogger.init(this)
        AppLogger.logLifecycle("PlayerActivity", "onCreate")

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.playerView.keepScreenOn = true

        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        registerReceiver(screenOffReceiver, filter)

        client = XtreamClient(this)
        historyManager = HistoryManager(this)

        // Helfer initialisieren
        statsOverlayHelper = PlayerStatsOverlayHelper(binding, ::formatTime)
        scrubberHelper = PlayerScrubberHelper(binding, ::formatTime)
        trackDialogHelper = PlayerTrackDialogHelper()
        osdController = PlayerOsdController(binding, client, lifecycleScope)

        currentStreamUrl = intent.getStringExtra("STREAM_URL") ?: ""
        currentStreamName = intent.getStringExtra("STREAM_NAME") ?: "Stream"
        currentPosterUrl = intent.getStringExtra("POSTER_URL")
        currentStreamId = intent.getIntExtra("STREAM_ID", -1)
        currentIndex = intent.getIntExtra("CURRENT_INDEX", -1)
        currentEpisodeIndex = intent.getIntExtra("EPISODE_INDEX", -1)
        currentType = intent.getStringExtra("STREAM_TYPE") ?: if (intent.hasExtra("STREAM_LIST") || intent.hasExtra("MULTI_STREAM_CHANNEL")) "LIVE" else "VOD"
        seasonNum = intent.getIntExtra("SEASON_NUM", 1)
        episodeNum = intent.getIntExtra("EPISODE_NUM", 1)
        seriesId = intent.getIntExtra("SERIES_ID", -1)

        @Suppress("DEPRECATION")
        streamList = (intent.getSerializableExtra("STREAM_LIST") as? ArrayList<LiveStream>) ?: emptyList()
        @Suppress("DEPRECATION")
        channelList = (intent.getSerializableExtra("MULTI_STREAM_LIST") as? ArrayList<MultiStreamChannel>) ?: emptyList()
        @Suppress("DEPRECATION")
        activeChannel = intent.getSerializableExtra("MULTI_STREAM_CHANNEL") as? MultiStreamChannel
        @Suppress("DEPRECATION")
        episodeList = (intent.getSerializableExtra("EPISODE_LIST") as? ArrayList<EpisodeItem>) ?: emptyList()

        if (activeChannel != null) {
            activeChannel = MultiStreamManager.applyPreferredSources(this, activeChannel!!)
            isLive = true
            currentType = "LIVE"
            currentStreamName = activeChannel!!.cleanName
            activeSourceIndex = 0
            val bestSource = activeChannel!!.sources.firstOrNull()
            if (bestSource != null) {
                currentStreamId = bestSource.streamId
                currentStreamUrl = client.getLiveStreamUrl(bestSource.streamId)
            }
        } else {
            isLive = streamList.isNotEmpty() || currentType == "LIVE"
        }

        historyManager.syncWithCloud(client.username)

        if (isLive && currentStreamId > 0) {
            val s = if (activeChannel != null) {
                LiveStream(
                    name = activeChannel!!.cleanName,
                    streamId = currentStreamId,
                    epgChannelId = activeChannel!!.epgId,
                    streamIcon = activeChannel!!.icon,
                    categoryId = activeChannel!!.categoryId
                )
            } else {
                streamList.getOrNull(currentIndex) ?: LiveStream(name = currentStreamName, streamId = currentStreamId)
            }
            historyManager.saveLiveChannel(s)
        }

        setupUI()
        setupPlayer(currentStreamUrl, currentStreamName, currentStreamId)

        binding.root.post {
            if (!isLive && binding.osdBottom.visibility == View.VISIBLE && !osdController.isOsdFocused()) {
                binding.playerSeekBar.requestFocus()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !isLive && binding.osdBottom.visibility == View.VISIBLE && !osdController.isOsdFocused()) {
            binding.playerSeekBar.requestFocus()
        }
    }

    private fun setupUI() {
        if (isLive) {
            binding.layoutTimeline.visibility = View.GONE
            binding.btnPrevEpisode.visibility = View.GONE
            binding.btnNextEpisode.visibility = View.GONE
            binding.osdTop.visibility = View.GONE
            binding.osdBottom.visibility = View.GONE
        } else {
            binding.layoutLiveOsd.visibility = View.GONE
            binding.layoutTimeline.visibility = View.VISIBLE
            binding.txtHintControls.text = "OK Pause | ◀ / ▶ Spulen | ▲/▼ OSD"

            if (currentType == "SERIES" && episodeList.isNotEmpty()) {
                updateEpisodeButtons()
                binding.btnPrevEpisode.setOnClickListener { playPreviousEpisode() }
                binding.btnNextEpisode.setOnClickListener { playNextEpisode() }
            } else {
                binding.btnPrevEpisode.visibility = View.GONE
                binding.btnNextEpisode.visibility = View.GONE
            }
        }

        binding.btnAudioTracks.setOnClickListener {
            trackDialogHelper.showAudioTrackDialog(this, exoPlayer, ::updateQualityAndAudioBadges) {
                binding.btnAudioTracks.requestFocus()
                osdController.resetOsdInactivityTimer(trackDialogHelper::isDialogShowing)
            }
        }
        binding.btnSubtitles.setOnClickListener {
            trackDialogHelper.showSubtitleDialog(this, exoPlayer, ::updateQualityAndAudioBadges) {
                binding.btnSubtitles.requestFocus()
                osdController.resetOsdInactivityTimer(trackDialogHelper::isDialogShowing)
            }
        }
        binding.btnDebugOverlay.setOnClickListener {
            statsOverlayHelper.toggle(exoPlayer, currentStreamId, currentType, seasonNum, episodeNum, lastKnownDuration)
        }

        val buttonFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                osdController.lastFocusedOsdButton = v
                osdController.resetOsdInactivityTimer(trackDialogHelper::isDialogShowing)
            }
        }
        binding.btnAudioTracks.onFocusChangeListener = buttonFocusChangeListener
        binding.btnSubtitles.onFocusChangeListener = buttonFocusChangeListener
        binding.btnPrevEpisode.onFocusChangeListener = buttonFocusChangeListener
        binding.btnNextEpisode.onFocusChangeListener = buttonFocusChangeListener
        binding.btnDebugOverlay.onFocusChangeListener = buttonFocusChangeListener

        val buttonKeyHandler = View.OnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                osdController.resetOsdInactivityTimer(trackDialogHelper::isDialogShowing)
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        binding.playerSeekBar.requestFocus()
                        return@OnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> return@OnKeyListener true
                }
            }
            false
        }
        binding.btnAudioTracks.setOnKeyListener(buttonKeyHandler)
        binding.btnSubtitles.setOnKeyListener(buttonKeyHandler)
        binding.btnPrevEpisode.setOnKeyListener(buttonKeyHandler)
        binding.btnNextEpisode.setOnKeyListener(buttonKeyHandler)
        binding.btnDebugOverlay.setOnKeyListener(buttonKeyHandler)

        binding.playerSeekBar.setOnKeyListener { _, keyCode, event ->
            osdController.resetOsdInactivityTimer(trackDialogHelper::isDialogShowing)
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        osdController.hideOsd()
                    }
                    return@setOnKeyListener true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        osdController.focusOsdButtonRow()
                    }
                    return@setOnKeyListener true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        performScrub(false)
                    }
                    return@setOnKeyListener true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        performScrub(true)
                    }
                    return@setOnKeyListener true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (event.action == KeyEvent.ACTION_DOWN && scrubberHelper.isScrubbing && scrubberHelper.targetSeekPosition >= 0) {
                        commitScrub()
                        return@setOnKeyListener true
                    }
                }
            }
            false
        }

        binding.playerSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && !scrubberHelper.isScrubbing && exoPlayer != null) {
                    val duration = if (exoPlayer!!.duration > 0) exoPlayer!!.duration else lastKnownDuration
                    if (duration > 0) {
                        val seekPos = (duration * progress) / 1000
                        exoPlayer!!.seekTo(seekPos)
                    }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun updateEpisodeButtons() {
        if (currentType != "SERIES" || episodeList.isEmpty()) {
            binding.btnPrevEpisode.visibility = View.GONE
            binding.btnNextEpisode.visibility = View.GONE
            return
        }
        binding.btnPrevEpisode.visibility = if (currentEpisodeIndex > 0) View.VISIBLE else View.GONE
        binding.btnNextEpisode.visibility = if (currentEpisodeIndex < episodeList.size - 1) View.VISIBLE else View.GONE
    }

    private fun playNextEpisode() {
        if (currentEpisodeIndex < episodeList.size - 1) {
            playEpisodeAtIndex(currentEpisodeIndex + 1)
        }
    }

    private fun playPreviousEpisode() {
        if (currentEpisodeIndex > 0) {
            playEpisodeAtIndex(currentEpisodeIndex - 1)
        }
    }

    private fun playEpisodeAtIndex(index: Int) {
        if (index < 0 || index >= episodeList.size) return
        currentEpisodeIndex = index
        val ep = episodeList[index]
        val seriesTitle = currentStreamName.substringBefore(" - S")
        currentStreamName = "$seriesTitle - S${ep.season}E${ep.episodeNum} ${ep.title}"
        currentStreamUrl = client.getSeriesStreamUrl(ep.id, ep.containerExtension ?: "mp4")
        currentStreamId = ep.id.toIntOrNull() ?: -1
        seasonNum = ep.season
        episodeNum = ep.episodeNum
        currentPosterUrl = ep.info?.movieImage ?: currentPosterUrl
        retryCount = 0

        updateEpisodeButtons()
        showOsdWrapper()

        val mediaItem = MediaItem.fromUri(currentStreamUrl)
        exoPlayer?.setMediaItem(mediaItem)
        exoPlayer?.prepare()
        exoPlayer?.playWhenReady = true
    }

    private fun setupPlayer(url: String, name: String, streamId: Int) {
        showOsdWrapper()
        retryCount = 0

        exoPlayer = PlayerUtils.createExoPlayer(this, isLive = isLive).apply {
            binding.playerView.player = this

            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    binding.playerLoading.visibility =
                        if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE

                    if (state == Player.STATE_READY) {
                        hideRetryBanner()
                        updateQualityAndAudioBadges()
                        AppLogger.logPlayerState("PlayerActivity", "READY (Stream: $currentStreamId, Type: $currentType)")
                    } else if (state == Player.STATE_BUFFERING) {
                        AppLogger.logPlayerState("PlayerActivity", "BUFFERING")
                    } else if (state == Player.STATE_ENDED) {
                        retryHandler.removeCallbacks(resetRetryRunnable)
                        AppLogger.logPlayerState("PlayerActivity", "ENDED")
                        val dur = if (exoPlayer?.duration != null && exoPlayer!!.duration > 0) exoPlayer!!.duration else lastKnownDuration
                        if (dur > 0 && !isLive) {
                            historyManager.saveProgress(
                                id = if (currentStreamId > 0) currentStreamId.toString() else currentStreamUrl,
                                title = currentStreamName,
                                streamUrl = currentStreamUrl,
                                posterUrl = currentPosterUrl,
                                type = currentType,
                                streamId = currentStreamId,
                                positionMs = dur,
                                durationMs = dur,
                                season = seasonNum,
                                episodeNum = episodeNum,
                                seriesId = if (currentType == "SERIES") seriesId else 0
                            )
                        }
                    }

                    updateCenterPauseVisibility(exoPlayer?.isPlaying == true)

                    if (state == Player.STATE_ENDED && currentType == "SERIES") {
                        if (currentEpisodeIndex < episodeList.size - 1) {
                            Toast.makeText(this@PlayerActivity, "Nächste Folge startet...", Toast.LENGTH_SHORT).show()
                            playNextEpisode()
                        }
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updateCenterPauseVisibility(isPlaying)
                    if (isPlaying) {
                        retryHandler.removeCallbacks(resetRetryRunnable)
                        retryHandler.postDelayed(resetRetryRunnable, 3000)
                    } else {
                        retryHandler.removeCallbacks(resetRetryRunnable)
                    }
                }

                override fun onTracksChanged(tracks: Tracks) {
                    AppLogger.logFormatChange("PlayerActivity", exoPlayer?.videoFormat, exoPlayer?.audioFormat)
                    updateQualityAndAudioBadges()
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    checkAndCacheQuality(videoSize.width, videoSize.height)
                }

                override fun onPlayerError(error: PlaybackException) {
                    retryHandler.removeCallbacks(resetRetryRunnable)
                    AppLogger.logError("PlayerActivity", "Player error: ${error.errorCodeName} (${error.errorCode})", error)

                    if (error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
                        error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) {
                        binding.playerLoading.visibility = View.GONE
                        hideRetryBanner()
                        Toast.makeText(this@PlayerActivity, "Audio-/Decoder-Fehler: Format wird vom Gerät nicht unterstützt", Toast.LENGTH_LONG).show()
                        return
                    }

                    if (isLive && activeChannel != null && activeChannel!!.sources.size > 1) {
                        if (activeSourceIndex + 1 < activeChannel!!.sources.size) {
                            activeSourceIndex++
                            val nextSource = activeChannel!!.sources[activeSourceIndex]
                            Toast.makeText(
                                this@PlayerActivity,
                                "🔄 Auto-Failover: Wechsle zu Quelle ${activeSourceIndex + 1}/${activeChannel!!.sources.size}: ${nextSource.label}",
                                Toast.LENGTH_SHORT
                            ).show()
                            playCurrentLiveSource()
                            return
                        }
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

                    val pos = if (currentPosition > 0) currentPosition else lastKnownPosition

                    if ((isHttpAuthOrRateLimit || isTimeoutOrConnFailed) && retryCount < maxRetries) {
                        retryCount++
                        val msg = "⏳ Warte auf Stream-Freigabe... ($retryCount/$maxRetries)"
                        showRetryBanner(msg)
                        AppLogger.logNetwork("Auto-Retry #$retryCount in 2.5s waiting for stream socket ($currentStreamUrl)")
                        retryHandler.removeCallbacksAndMessages(null)
                        retryHandler.postDelayed({ reconnectStream(pos) }, 2500)
                        return
                    }

                    if (retryCount < maxRetries) {
                        retryCount++
                        Toast.makeText(this@PlayerActivity, "⚠️ Verbindungsversuch (${retryCount}/${maxRetries})...", Toast.LENGTH_SHORT).show()
                        binding.playerLoading.visibility = View.VISIBLE

                        if (retryCount == 3) {
                            if (currentStreamUrl.endsWith(".mp4")) {
                                currentStreamUrl = currentStreamUrl.replace(".mp4", ".mkv")
                            } else if (currentStreamUrl.endsWith(".mkv")) {
                                currentStreamUrl = currentStreamUrl.replace(".mkv", ".ts")
                            }
                        }

                        retryHandler.removeCallbacksAndMessages(null)
                        retryHandler.postDelayed({ reconnectStream(pos) }, 1500)
                    } else {
                        hideRetryBanner()
                        Toast.makeText(this@PlayerActivity, "Wiedergabefehler: ${error.message} (Server antwortet nicht)", Toast.LENGTH_LONG).show()
                        binding.playerLoading.visibility = View.GONE
                    }
                }
            })

            val mediaItem = MediaItem.fromUri(url)
            setMediaItem(mediaItem)
            prepare()

            if (!isLive) {
                val resumePos = historyManager.getResumePosition(url, currentStreamId)
                if (resumePos > 15_000) {
                    lastKnownPosition = resumePos
                    seekTo(resumePos)
                    Toast.makeText(this@PlayerActivity, "Fortgesetzt bei ${formatTime(resumePos)}", Toast.LENGTH_SHORT).show()
                }
            }

            binding.playerView.subtitleView?.apply {
                val captionStyle = androidx.media3.ui.CaptionStyleCompat(
                    android.graphics.Color.WHITE,
                    android.graphics.Color.argb(26, 0, 0, 0),
                    android.graphics.Color.TRANSPARENT,
                    androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                    android.graphics.Color.argb(180, 0, 0, 0),
                    null
                )
                setStyle(captionStyle)
                setFractionalTextSize(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 0.72f)
            }

            playWhenReady = true
        }

        startProgressUpdater()
    }

    private fun updateCenterPauseVisibility(isPlaying: Boolean) {
        if (isLive) {
            binding.layoutCenterPause.visibility = View.GONE
            return
        }
        val isReady = (exoPlayer?.playbackState == Player.STATE_READY)
        binding.layoutCenterPause.visibility = if (!isPlaying && isReady) View.VISIBLE else View.GONE
    }

    private fun showRetryBanner(msg: String) {
        binding.txtPlayerRetryBanner.text = msg
        binding.txtPlayerRetryBanner.visibility = View.VISIBLE
    }

    private fun hideRetryBanner() {
        binding.txtPlayerRetryBanner.visibility = View.GONE
    }

    private fun reconnectStream(seekPos: Long) {
        val player = exoPlayer ?: return
        AppLogger.logNetwork("reconnectStream at pos $seekPos (url: $currentStreamUrl)")
        PlayerUtils.releaseStreamConnections(player)
        val mediaItem = MediaItem.fromUri(currentStreamUrl)
        player.setMediaItem(mediaItem)
        player.prepare()
        if (seekPos > 10_000 && !isLive) {
            player.seekTo(seekPos)
        }
        player.playWhenReady = true
    }

    private fun updateQualityAndAudioBadges() {
        val player = exoPlayer ?: return
        val format = player.videoFormat
        if (format != null) {
            val h = format.height
            binding.badgeQuality.text = when {
                h >= 2160 -> "4K UHD"
                h >= 1080 -> "1080p FHD"
                h >= 720 -> "720p HD"
                h > 0 -> "${h}p"
                else -> "HD"
            }
            binding.badgeQuality.visibility = View.VISIBLE
        }

        val tracks = player.currentTracks
        var audioName = "Audio"
        for (g in tracks.groups) {
            if (g.type == C.TRACK_TYPE_AUDIO && g.isSelected) {
                val f = g.getTrackFormat(0)
                val lang = f.language?.uppercase() ?: ""
                val channels = if (f.channelCount > 2) "${f.channelCount}.1" else "Stereo"
                audioName = if (lang.isNotEmpty()) "$lang ($channels)" else channels
                break
            }
        }
        binding.badgeAudio.text = audioName
    }

    private fun startProgressUpdater() {
        progressHandler.post(object : Runnable {
            override fun run() {
                val player = exoPlayer
                if (player != null && !isLive && player.duration > 0 && !scrubberHelper.isScrubbing && scrubberHelper.targetSeekPosition < 0) {
                    val current = player.currentPosition
                    val total = player.duration
                    if (current > 0) lastKnownPosition = current
                    if (total > 0) lastKnownDuration = total
                    binding.txtTimeCurrent.text = formatTime(current)
                    binding.txtTimeTotal.text = formatTime(total)
                    binding.playerSeekBar.progress = ((current * 1000) / total).toInt()

                    if (current > 5000) {
                        historyManager.saveProgress(
                            id = if (currentStreamId > 0) currentStreamId.toString() else currentStreamUrl,
                            title = currentStreamName,
                            streamUrl = currentStreamUrl,
                            posterUrl = currentPosterUrl,
                            type = currentType,
                            streamId = currentStreamId,
                            positionMs = current,
                            durationMs = total,
                            season = seasonNum,
                            episodeNum = episodeNum,
                            seriesId = if (currentType == "SERIES") seriesId else 0
                        )
                    }
                }
                if (statsOverlayHelper.isVisible) {
                    statsOverlayHelper.update(player, currentStreamId, currentType, seasonNum, episodeNum, lastKnownDuration)
                }
                progressHandler.postDelayed(this, 1000)
            }
        })
    }

    private fun formatTime(ms: Long): String {
        val totalSecs = (ms / 1000).coerceAtLeast(0)
        val hours = totalSecs / 3600
        val minutes = (totalSecs % 3600) / 60
        val seconds = totalSecs % 60
        return if (hours > 0) String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
        else String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    private fun togglePlayPause() {
        val player = exoPlayer ?: return
        if (player.isPlaying) {
            player.pause()
            showOsdWrapper()
        } else {
            player.play()
            showOsdWrapper()
        }
    }

    private fun showOsdWrapper() {
        if (isLive) {
            osdController.showLiveOsd(
                activeChannel,
                currentStreamName,
                currentStreamId,
                activeSourceIndex,
                currentIndex,
                exoPlayer,
                trackDialogHelper::isDialogShowing
            )
        } else {
            osdController.showOsd(
                currentStreamName,
                isLive,
                trackDialogHelper::isDialogShowing
            ) {
                binding.playerSeekBar.requestFocus()
            }
        }
    }

    private fun performScrub(forward: Boolean) {
        showOsdWrapper()
        scrubberHelper.performNetflixScrub(
            forward,
            exoPlayer,
            lastKnownDuration,
            lastKnownPosition,
            onOsdKeepOpen = { osdController.keepOsdOpen() },
            onCommitSeek = { finalPos ->
                lastKnownPosition = finalPos
                osdController.resetOsdInactivityTimer(trackDialogHelper::isDialogShowing)
            }
        )
    }

    private fun commitScrub() {
        scrubberHelper.commitScrubSeek(exoPlayer, lastKnownDuration) { finalPos ->
            lastKnownPosition = finalPos
            osdController.resetOsdInactivityTimer(trackDialogHelper::isDialogShowing)
        }
    }

    private fun playCurrentLiveSource() {
        val ch = activeChannel ?: return
        val src = ch.sources.getOrNull(activeSourceIndex) ?: return
        currentStreamId = src.streamId
        currentStreamName = ch.cleanName
        currentStreamUrl = client.getLiveStreamUrl(src.streamId)
        retryCount = 0
        val s = LiveStream(
            name = ch.cleanName,
            streamId = src.streamId,
            epgChannelId = ch.epgId,
            streamIcon = ch.icon,
            categoryId = ch.categoryId
        )
        historyManager.saveLiveChannel(s)
        showOsdWrapper()
        val mediaItem = MediaItem.fromUri(currentStreamUrl)
        exoPlayer?.setMediaItem(mediaItem)
        exoPlayer?.prepare()
        exoPlayer?.playWhenReady = true
    }

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
        playCurrentLiveSource()
    }

    private fun checkAndCacheQuality(width: Int, height: Int) {
        if (!isLive || activeChannel == null) return
        val ch = activeChannel ?: return
        if (ch.sources.size <= 1) return

        if (QualityPreferenceManager.hasPreference(this, ch.cleanName)) {
            return
        }

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
            playCurrentLiveSource()
        } else if (width > 0) {
            QualityPreferenceManager.savePreferredStream(
                this,
                ch.cleanName,
                currentSource.streamId,
                "${width}x${height}"
            )
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        osdController.resetOsdInactivityTimer(trackDialogHelper::isDialogShowing)
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (trackDialogHelper.dismissActiveDialog()) {
                    return true
                }
                if (scrubberHelper.isScrubbing) {
                    scrubberHelper.cancelScrub()
                    osdController.hideOsd()
                    return true
                }
                if (isLive && binding.layoutLiveOsd.visibility == View.VISIBLE) {
                    osdController.hideOsd()
                    return true
                }
                if (binding.osdBottom.visibility == View.VISIBLE) {
                    osdController.hideOsd()
                    return true
                }
                finish()
                return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_M, KeyEvent.KEYCODE_I -> {
                statsOverlayHelper.toggle(exoPlayer, currentStreamId, currentType, seasonNum, episodeNum, lastKnownDuration)
                return true
            }
            KeyEvent.KEYCODE_PROG_YELLOW, KeyEvent.KEYCODE_BUTTON_Y -> {
                if (isLive) {
                    cycleToNextSourceManually()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (isLive) {
                    if (binding.layoutLiveOsd.visibility == View.VISIBLE) {
                        cycleToNextSourceManually()
                        return true
                    }
                } else if (osdController.isOsdButtonFocused()) {
                    return false
                } else {
                    performScrub(true)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (!isLive) {
                    if (osdController.isOsdButtonFocused()) {
                        return false
                    } else {
                        performScrub(false)
                        return true
                    }
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (isLive) {
                    zapPreviousChannel()
                    return true
                } else {
                    if (binding.osdBottom.visibility != View.VISIBLE) {
                        showOsdWrapper()
                        binding.playerSeekBar.requestFocus()
                    } else if (binding.playerSeekBar.hasFocus()) {
                        osdController.focusOsdButtonRow()
                    } else if (osdController.isOsdButtonFocused()) {
                        // Fokus behalten
                    } else {
                        osdController.focusOsdButtonRow()
                    }
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (isLive) {
                    zapNextChannel()
                    return true
                } else {
                    if (binding.osdBottom.visibility != View.VISIBLE) {
                        showOsdWrapper()
                        binding.playerSeekBar.requestFocus()
                    } else if (osdController.isOsdButtonFocused()) {
                        binding.playerSeekBar.requestFocus()
                    } else if (binding.playerSeekBar.hasFocus()) {
                        osdController.hideOsd()
                    } else {
                        binding.playerSeekBar.requestFocus()
                    }
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (isLive) {
                    if (binding.layoutLiveOsd.visibility == View.VISIBLE) {
                        osdController.hideOsd()
                    } else {
                        showOsdWrapper()
                    }
                    return true
                } else {
                    if (scrubberHelper.isScrubbing && scrubberHelper.targetSeekPosition >= 0) {
                        commitScrub()
                        return true
                    }
                    if (binding.btnAudioTracks.hasFocus()) {
                        trackDialogHelper.showAudioTrackDialog(this, exoPlayer, ::updateQualityAndAudioBadges) {
                            binding.btnAudioTracks.requestFocus()
                            osdController.resetOsdInactivityTimer(trackDialogHelper::isDialogShowing)
                        }
                        return true
                    } else if (binding.btnSubtitles.hasFocus()) {
                        trackDialogHelper.showSubtitleDialog(this, exoPlayer, ::updateQualityAndAudioBadges) {
                            binding.btnSubtitles.requestFocus()
                            osdController.resetOsdInactivityTimer(trackDialogHelper::isDialogShowing)
                        }
                        return true
                    } else if (binding.btnPrevEpisode.hasFocus()) {
                        playPreviousEpisode()
                        return true
                    } else if (binding.btnNextEpisode.hasFocus()) {
                        playNextEpisode()
                        return true
                    } else if (binding.btnDebugOverlay.hasFocus()) {
                        statsOverlayHelper.toggle(exoPlayer, currentStreamId, currentType, seasonNum, episodeNum, lastKnownDuration)
                        return true
                    } else {
                        togglePlayPause()
                    }
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun zapNextChannel() {
        if (channelList.isNotEmpty() && currentIndex >= 0) {
            currentIndex = (currentIndex + 1) % channelList.size
            activeChannel = MultiStreamManager.applyPreferredSources(this, channelList[currentIndex])
            activeSourceIndex = 0
            hasTestedSourcesCount = 0
            playCurrentLiveSource()
        } else if (streamList.isNotEmpty() && currentIndex >= 0) {
            currentIndex = (currentIndex + 1) % streamList.size
            val stream = streamList[currentIndex]
            switchStream(stream)
        }
    }

    private fun zapPreviousChannel() {
        if (channelList.isNotEmpty() && currentIndex >= 0) {
            currentIndex = if (currentIndex - 1 < 0) channelList.size - 1 else currentIndex - 1
            activeChannel = MultiStreamManager.applyPreferredSources(this, channelList[currentIndex])
            activeSourceIndex = 0
            hasTestedSourcesCount = 0
            playCurrentLiveSource()
        } else if (streamList.isNotEmpty() && currentIndex >= 0) {
            currentIndex = if (currentIndex - 1 < 0) streamList.size - 1 else currentIndex - 1
            val stream = streamList[currentIndex]
            switchStream(stream)
        }
    }

    private fun switchStream(stream: LiveStream) {
        currentStreamId = stream.streamId
        currentStreamName = stream.name
        currentStreamUrl = client.getLiveStreamUrl(stream.streamId)
        retryCount = 0
        hideRetryBanner()

        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
        PlayerUtils.cancelPendingMediaRequests()

        historyManager.saveLiveChannel(stream)
        showOsdWrapper()
        val mediaItem = MediaItem.fromUri(currentStreamUrl)
        exoPlayer?.setMediaItem(mediaItem)
        exoPlayer?.prepare()
        exoPlayer?.playWhenReady = true
    }

    private fun saveCurrentState() {
        val player = exoPlayer ?: return
        if (!isLive && player.duration > 0 && player.currentPosition > 5000) {
            val isCompleted = player.playbackState == Player.STATE_ENDED ||
                    (player.duration > 0 && (player.currentPosition.toFloat() / player.duration.toFloat()) >= 0.90f)
            val pos = if (isCompleted) player.duration else player.currentPosition
            historyManager.saveProgress(
                id = if (currentStreamId > 0) currentStreamId.toString() else currentStreamUrl,
                title = currentStreamName,
                streamUrl = currentStreamUrl,
                posterUrl = currentPosterUrl,
                type = currentType,
                streamId = currentStreamId,
                positionMs = pos,
                durationMs = player.duration,
                season = seasonNum,
                episodeNum = episodeNum,
                seriesId = if (currentType == "SERIES") seriesId else 0
            )
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val viewName = try {
                currentFocus?.let { resources.getResourceEntryName(it.id) } ?: "null"
            } catch (e: Exception) {
                currentFocus?.javaClass?.simpleName ?: "unknown"
            }
            AppLogger.logFocus(event.keyCode, KeyEvent.keyCodeToString(event.keyCode), viewName)
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onPause() {
        super.onPause()
        AppLogger.logLifecycle("PlayerActivity", "onPause -> Releasing stream connections")
        saveCurrentState()
        retryHandler.removeCallbacksAndMessages(null)
        PlayerUtils.releaseStreamConnections(exoPlayer)
    }

    override fun onStop() {
        super.onStop()
        AppLogger.logLifecycle("PlayerActivity", "onStop -> Releasing stream connections")
        saveCurrentState()
        retryHandler.removeCallbacksAndMessages(null)
        PlayerUtils.releaseStreamConnections(exoPlayer)
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLogger.logLifecycle("PlayerActivity", "onDestroy")
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (e: Exception) {}
        saveCurrentState()
        osdController.onDestroy()
        progressHandler.removeCallbacksAndMessages(null)
        retryHandler.removeCallbacksAndMessages(null)
        PlayerUtils.releaseStreamConnections(exoPlayer)
        exoPlayer?.release()
        exoPlayer = null
    }
}
