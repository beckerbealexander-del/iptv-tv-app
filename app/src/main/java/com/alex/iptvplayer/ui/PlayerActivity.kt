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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.alex.iptvplayer.R
import com.alex.iptvplayer.data.EpisodeItem
import com.alex.iptvplayer.data.HistoryManager
import com.alex.iptvplayer.data.LiveStream
import com.alex.iptvplayer.data.MultiStreamChannel
import com.alex.iptvplayer.data.MultiStreamManager
import com.alex.iptvplayer.data.QualityPreferenceManager
import com.alex.iptvplayer.data.StreamSource
import com.alex.iptvplayer.data.XtreamClient
import com.alex.iptvplayer.databinding.ActivityPlayerBinding
import com.alex.iptvplayer.util.AppLogger
import com.alex.iptvplayer.util.PlayerUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var exoPlayer: ExoPlayer? = null
    private lateinit var client: XtreamClient
    private lateinit var historyManager: HistoryManager

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

    private val osdHandler = Handler(Looper.getMainLooper())
    private val progressHandler = Handler(Looper.getMainLooper())
    private val scrubHandler = Handler(Looper.getMainLooper())
    private val retryHandler = Handler(Looper.getMainLooper())
    private var epgJob: Job? = null

    // Automatischer Reconnect & Retry
    private var retryCount = 0
    private val maxRetries = 5
    private val resetRetryRunnable = Runnable { retryCount = 0 }
    private var lastKnownPosition: Long = 0L
    private var activeDialog: AlertDialog? = null

    // Netflix-Style Spulen Variablen
    private var isScrubbing = false
    private var targetSeekPosition: Long = -1L
    private var scrubSessionStartTime = 0L
    private var lastScrubTime = 0L
    private var lastKnownDuration: Long = 0L
    private var lastFocusedOsdButton: View? = null

    private val hideOsdRunnable = Runnable { hideOsd() }

    // Standby-Erkennung (HDMI / Display Off)
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                AppLogger.logLifecycle("PlayerActivity", "ACTION_SCREEN_OFF -> Releasing stream connections immediately")
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

        // Bildschirmschoner / Standby auf TV während Wiedergabe verhindern
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.playerView.keepScreenOn = true

        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        registerReceiver(screenOffReceiver, filter)

        client = XtreamClient(this)
        historyManager = HistoryManager(this)

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

        // Vor dem Start den neuesten Stand synchronisieren
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
            if (!isLive && binding.osdBottom.visibility == View.VISIBLE && !isOsdFocused()) {
                binding.playerSeekBar.requestFocus()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !isLive && binding.osdBottom.visibility == View.VISIBLE && !isOsdFocused()) {
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

        binding.btnAudioTracks.setOnClickListener { showAudioTrackDialog() }
        binding.btnSubtitles.setOnClickListener { showSubtitleDialog() }
        binding.btnDebugOverlay.setOnClickListener { toggleDebugOverlay() }

        val buttonFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                lastFocusedOsdButton = v
                resetOsdInactivityTimer()
            }
        }
        binding.btnAudioTracks.onFocusChangeListener = buttonFocusChangeListener
        binding.btnSubtitles.onFocusChangeListener = buttonFocusChangeListener
        binding.btnPrevEpisode.onFocusChangeListener = buttonFocusChangeListener
        binding.btnNextEpisode.onFocusChangeListener = buttonFocusChangeListener
        binding.btnDebugOverlay.onFocusChangeListener = buttonFocusChangeListener

        val buttonKeyHandler = View.OnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                resetOsdInactivityTimer()
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        binding.playerSeekBar.requestFocus()
                        return@OnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        return@OnKeyListener true
                    }
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
            resetOsdInactivityTimer()
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        hideOsd()
                    }
                    return@setOnKeyListener true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        focusOsdButtonRow()
                    }
                    return@setOnKeyListener true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        performNetflixScrub(false)
                    }
                    return@setOnKeyListener true // Consumes ACTION_DOWN AND ACTION_UP so AbsSeekBar doesn't touch progress
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        performNetflixScrub(true)
                    }
                    return@setOnKeyListener true // Consumes ACTION_DOWN AND ACTION_UP so AbsSeekBar doesn't touch progress
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        if (isScrubbing && targetSeekPosition >= 0) {
                            commitScrubSeek()
                            return@setOnKeyListener true
                        }
                    }
                }
            }
            false
        }

        binding.playerSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                // Nur wenn der Nutzer aktiv per Touch/Maus schiebt und KEIN D-Pad-Scrubbing aktiv ist
                if (fromUser && !isScrubbing && exoPlayer != null) {
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
        showOsd(currentStreamName, currentStreamId)

        val mediaItem = MediaItem.fromUri(currentStreamUrl)
        exoPlayer?.setMediaItem(mediaItem)
        exoPlayer?.prepare()
        exoPlayer?.playWhenReady = true
    }

    private fun setupPlayer(url: String, name: String, streamId: Int) {
        showOsd(name, streamId)
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

                    // Automatisch nächste Folge abspielen
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
                        // Erst nach 3 Sekunden stabiler Wiedergabe den Retry-Zähler zurücksetzen
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
                        retryHandler.postDelayed({
                            reconnectStream(pos)
                        }, 2500)
                        return
                    }

                    if (retryCount < maxRetries) {
                        retryCount++
                        Toast.makeText(this@PlayerActivity, "⚠️ Verbindungsversuch (${retryCount}/${maxRetries})...", Toast.LENGTH_SHORT).show()
                        binding.playerLoading.visibility = View.VISIBLE

                        // Container-Fallback nach 2 Fehlversuchen (z.B. .mp4 <-> .mkv <-> .ts)
                        if (retryCount == 3) {
                            if (currentStreamUrl.endsWith(".mp4")) {
                                currentStreamUrl = currentStreamUrl.replace(".mp4", ".mkv")
                            } else if (currentStreamUrl.endsWith(".mkv")) {
                                currentStreamUrl = currentStreamUrl.replace(".mkv", ".ts")
                            }
                        }

                        retryHandler.removeCallbacksAndMessages(null)
                        retryHandler.postDelayed({
                            reconnectStream(pos)
                        }, 1500)
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

            // Fortsetzen / Resume
            if (!isLive) {
                val resumePos = historyManager.getResumePosition(url, currentStreamId)
                if (resumePos > 15_000) {
                    lastKnownPosition = resumePos
                    seekTo(resumePos)
                    Toast.makeText(this@PlayerActivity, "Fortgesetzt bei ${formatTime(resumePos)}", Toast.LENGTH_SHORT).show()
                }
            }

            // Untertitel-Stil: Weiß auf 90% durchsichtigem Schwarz, 2 Nummern kleiner
            binding.playerView.subtitleView?.apply {
                val captionStyle = androidx.media3.ui.CaptionStyleCompat(
                    /* foregroundColor = */ android.graphics.Color.WHITE,
                    /* backgroundColor = */ android.graphics.Color.argb(26, 0, 0, 0), // 90% transparentes Schwarz
                    /* windowColor = */ android.graphics.Color.TRANSPARENT,
                    /* edgeType = */ androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                    /* edgeColor = */ android.graphics.Color.argb(180, 0, 0, 0),
                    /* typeface = */ null
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
                if (player != null && !isLive && player.duration > 0 && !isScrubbing && targetSeekPosition < 0) {
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
                if (binding.layoutDebugOverlay.visibility == View.VISIBLE) {
                    updateDebugOverlayStats()
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
            showOsd(binding.txtPlayerTitle.text.toString(), currentStreamId)
        } else {
            player.play()
            showOsd(binding.txtPlayerTitle.text.toString(), currentStreamId)
        }
    }

    private fun resetOsdInactivityTimer() {
        osdHandler.removeCallbacksAndMessages(null)
        osdHandler.postDelayed({
            if (activeDialog == null || !activeDialog!!.isShowing) {
                hideOsd()
            }
        }, 5000)
    }

    private fun showOsd(name: String, streamId: Int) {
        if (isLive) {
            showLiveOsd()
            return
        }

        binding.layoutLiveOsd.visibility = View.GONE
        binding.txtPlayerTitle.text = name
        binding.osdTop.visibility = View.VISIBLE
        binding.osdBottom.visibility = View.VISIBLE

        resetOsdInactivityTimer()

        if (!isOsdFocused()) {
            binding.playerSeekBar.post {
                if (binding.osdBottom.visibility == View.VISIBLE && !isOsdButtonFocused()) {
                    binding.playerSeekBar.requestFocus()
                }
            }
        }
    }

    private fun showLiveOsd() {
        binding.osdTop.visibility = View.GONE
        binding.osdBottom.visibility = View.GONE
        binding.layoutLiveOsd.visibility = View.VISIBLE

        val ch = activeChannel
        val chNum = if (currentIndex >= 0) "${currentIndex + 1}" else "🔴"
        binding.txtLiveOsdChannelNum.text = chNum
        binding.txtLiveOsdChannelName.text = ch?.cleanName ?: currentStreamName

        if (ch != null && ch.sources.isNotEmpty()) {
            val src = ch.sources.getOrNull(activeSourceIndex) ?: ch.sources[0]
            val count = ch.sources.size
            binding.txtLiveOsdSourceInfo.text = if (count > 1) {
                "⚡ Quelle ${activeSourceIndex + 1}/$count: ${src.label}"
            } else {
                "⚡ ${src.label}"
            }
            binding.txtLiveOsdSourceInfo.visibility = View.VISIBLE
        } else {
            binding.txtLiveOsdSourceInfo.visibility = View.GONE
        }

        val vf = exoPlayer?.videoFormat
        val af = exoPlayer?.audioFormat
        val res = if (vf != null && vf.width > 0 && vf.height > 0) "${vf.width}x${vf.height}" else "1080p"
        val fps = if (vf != null && vf.frameRate > 0) "${vf.frameRate.toInt()} fps" else "50 fps"
        val vCodec = vf?.sampleMimeType?.substringAfter("/")?.uppercase() ?: "H.264"
        val aCodec = af?.sampleMimeType?.substringAfter("/")?.uppercase() ?: "AAC"
        binding.txtLiveOsdTechSpecs.text = "$res | $fps | $vCodec | $aCodec"

        val epgTargetId = ch?.epgStreamId ?: currentStreamId
        if (epgTargetId > 0) {
            epgJob?.cancel()
            epgJob = lifecycleScope.launch {
                try {
                    val list = client.getEpg(epgTargetId)
                    val cur = list.firstOrNull { it.isNowPlaying } ?: list.firstOrNull()
                    if (cur != null) {
                        binding.txtLiveOsdProgramTitle.text = "🔴 JETZT: ${cur.title}"
                        binding.txtLiveOsdProgramTime.text = "${cur.start} - ${cur.end}"
                        binding.txtLiveOsdProgramDesc.text = if (cur.description.isNotEmpty()) cur.description else "Keine Programmbeschreibung vorhanden."
                        val prog = calculateProgress(cur.start, cur.end)
                        binding.progressLiveOsdProgram.progress = prog
                        binding.progressLiveOsdProgram.visibility = View.VISIBLE
                    } else {
                        binding.txtLiveOsdProgramTitle.text = "🔴 LIVE TV"
                        binding.txtLiveOsdProgramTime.text = ""
                        binding.txtLiveOsdProgramDesc.text = ""
                        binding.progressLiveOsdProgram.visibility = View.GONE
                    }
                } catch (e: Exception) {
                    binding.txtLiveOsdProgramTitle.text = "🔴 LIVE TV"
                    binding.txtLiveOsdProgramTime.text = ""
                    binding.txtLiveOsdProgramDesc.text = ""
                    binding.progressLiveOsdProgram.visibility = View.GONE
                }
            }
        }

        resetOsdInactivityTimer()
    }

    private fun hideOsd() {
        binding.osdTop.visibility = View.GONE
        binding.osdBottom.visibility = View.GONE
        binding.layoutLiveOsd.visibility = View.GONE
        binding.playerView.requestFocus()
    }

    private fun calculateProgress(start: String, end: String): Int {
        return try {
            val sdf = java.text.SimpleDateFormat("HH:mm", Locale.getDefault())
            val cal = java.util.Calendar.getInstance()
            val nowMin = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)

            val sCal = java.util.Calendar.getInstance().apply { time = sdf.parse(start) ?: return 50 }
            val sMin = sCal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + sCal.get(java.util.Calendar.MINUTE)

            val eCal = java.util.Calendar.getInstance().apply { time = sdf.parse(end) ?: return 50 }
            var eMin = eCal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + eCal.get(java.util.Calendar.MINUTE)
            if (eMin <= sMin) eMin += 24 * 60

            var cur = nowMin
            if (cur < sMin && eMin > 24 * 60) cur += 24 * 60

            val total = eMin - sMin
            if (total <= 0) return 50
            val current = cur - sMin
            ((current.toFloat() / total.toFloat()) * 100).toInt().coerceIn(0, 100)
        } catch (e: Exception) {
            50
        }
    }

    private var hasTestedSourcesCount = 0

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
        showLiveOsd()
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

    private fun isOsdFocused(): Boolean {
        return binding.btnAudioTracks.hasFocus() ||
                binding.btnSubtitles.hasFocus() ||
                binding.btnPrevEpisode.hasFocus() ||
                binding.btnNextEpisode.hasFocus() ||
                binding.btnDebugOverlay.hasFocus() ||
                binding.playerSeekBar.hasFocus()
    }

    private fun isOsdButtonFocused(): Boolean {
        return binding.btnAudioTracks.hasFocus() ||
                binding.btnSubtitles.hasFocus() ||
                binding.btnPrevEpisode.hasFocus() ||
                binding.btnNextEpisode.hasFocus() ||
                binding.btnDebugOverlay.hasFocus()
    }

    private fun toggleDebugOverlay() {
        if (binding.layoutDebugOverlay.visibility == View.VISIBLE) {
            binding.layoutDebugOverlay.visibility = View.GONE
        } else {
            binding.layoutDebugOverlay.visibility = View.VISIBLE
            updateDebugOverlayStats()
        }
    }

    private fun updateDebugOverlayStats() {
        val player = exoPlayer ?: return
        val stateStr = when (player.playbackState) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> if (player.isPlaying) "PLAYING" else "PAUSED"
            Player.STATE_ENDED -> "ENDED"
            else -> "UNKNOWN"
        }
        val bufferSec = ((player.bufferedPosition - player.currentPosition).coerceAtLeast(0) / 1000.0)
        binding.txtDebugState.text = "State: $stateStr | Buffer: ${String.format(Locale.US, "%.1fs", bufferSec)}"

        val vf = player.videoFormat
        if (vf != null) {
            val w = vf.width
            val h = vf.height
            val fps = if (vf.frameRate > 0) "${vf.frameRate.toInt()}fps" else "?fps"
            val codec = vf.sampleMimeType?.substringAfter("/")?.uppercase() ?: "UNKNOWN"
            binding.txtDebugVideo.text = "Video: ${w}x${h} @ $fps ($codec)"
        } else {
            binding.txtDebugVideo.text = "Video: N/A"
        }

        val af = player.audioFormat
        if (af != null) {
            val channels = when (af.channelCount) {
                1 -> "Mono"
                2 -> "Stereo"
                6 -> "5.1"
                8 -> "7.1"
                else -> "${af.channelCount}ch"
            }
            val aCodec = af.sampleMimeType?.substringAfter("/")?.uppercase() ?: "AUDIO"
            val lang = af.language ?: "und"
            binding.txtDebugAudio.text = "Audio: $aCodec $channels ($lang)"
        } else {
            binding.txtDebugAudio.text = "Audio: N/A"
        }

        val cur = player.currentPosition
        val dur = if (player.duration > 0) player.duration else lastKnownDuration
        val pct = if (dur > 0) (cur * 100 / dur).toInt() else 0
        binding.txtDebugPos.text = "Pos: ${formatTime(cur)} / ${formatTime(dur)} ($pct%)"

        val epInfo = if (currentType == "SERIES") " | S${seasonNum}E${episodeNum}" else ""
        binding.txtDebugStreamInfo.text = "ID: $currentStreamId | Type: $currentType$epInfo"
    }

    private fun focusOsdButtonRow() {
        val lastBtn = lastFocusedOsdButton
        if (lastBtn != null && lastBtn.visibility == View.VISIBLE) {
            lastBtn.requestFocus()
        } else if (binding.btnPrevEpisode.visibility == View.VISIBLE) {
            binding.btnPrevEpisode.requestFocus()
        } else if (binding.btnNextEpisode.visibility == View.VISIBLE) {
            binding.btnNextEpisode.requestFocus()
        } else {
            binding.btnAudioTracks.requestFocus()
        }
    }

    // --- Netflix-Style Scrubbing (erste 3 Sek im 15s-Takt, 3-7s im 30s-Takt, danach 1m) ---
    private fun performNetflixScrub(forward: Boolean) {
        val player = exoPlayer ?: return
        val totalDuration = if (player.duration > 0) player.duration else lastKnownDuration
        if (totalDuration <= 0) return

        val now = System.currentTimeMillis()

        // Key-repeat Drosselung: wenn D-Pad gehalten wird, feuert Android ca. alle 50ms.
        // Drosseln auf min. 140ms pro Schritt für präzises, ruhiges Spulen ohne Durchrauschen
        if (isScrubbing && now - lastScrubTime < 140L) {
            return
        }

        if (!isScrubbing || targetSeekPosition < 0) {
            isScrubbing = true
            scrubSessionStartTime = now
            val cur = player.currentPosition
            targetSeekPosition = if (cur in 0..totalDuration) cur else lastKnownPosition.coerceIn(0, totalDuration)
        }

        lastScrubTime = now

        val sessionDuration = now - scrubSessionStartTime
        val stepMs = when {
            sessionDuration < 3000L -> 15_000L
            sessionDuration < 7000L -> 30_000L
            else -> 60_000L
        }

        if (forward) {
            targetSeekPosition = (targetSeekPosition + stepMs).coerceAtMost(totalDuration)
        } else {
            targetSeekPosition = (targetSeekPosition - stepMs).coerceAtLeast(0L)
        }

        val icon = if (forward) "⏩ +" else "⏪ -"
        val stepSec = stepMs / 1000
        val stepText = if (stepSec >= 60) "${stepSec / 60}m" else "${stepSec}s"
        binding.txtScrubSpeed.text = "$icon$stepText"
        binding.txtScrubTargetTime.text = "${formatTime(targetSeekPosition)} / ${formatTime(totalDuration)}"
        binding.osdScrubBubble.visibility = View.VISIBLE

        showOsd(binding.txtPlayerTitle.text.toString(), currentStreamId)
        binding.txtTimeCurrent.text = formatTime(targetSeekPosition)
        binding.txtTimeTotal.text = formatTime(totalDuration)
        binding.playerSeekBar.progress = ((targetSeekPosition * 1000) / totalDuration).toInt()

        // OSD während des Spulens geöffnet halten
        osdHandler.removeCallbacksAndMessages(null)

        // Nach 800ms ohne weiteren Tastendruck automatisch ausführen
        scrubHandler.removeCallbacksAndMessages(null)
        scrubHandler.postDelayed({
            commitScrubSeek()
        }, 800)
    }

    private fun commitScrubSeek() {
        scrubHandler.removeCallbacksAndMessages(null)
        val pos = targetSeekPosition
        val wasScrubbing = isScrubbing
        isScrubbing = false
        targetSeekPosition = -1L
        binding.osdScrubBubble.visibility = View.GONE

        if (wasScrubbing && pos >= 0) {
            val player = exoPlayer ?: return
            val totalDuration = if (player.duration > 0) player.duration else lastKnownDuration
            val finalPos = if (totalDuration > 0) pos.coerceIn(0L, totalDuration) else pos.coerceAtLeast(0L)
            lastKnownPosition = finalPos
            player.seekTo(finalPos)
            binding.txtTimeCurrent.text = formatTime(finalPos)
            if (totalDuration > 0) {
                binding.playerSeekBar.progress = ((finalPos * 1000) / totalDuration).toInt()
            }
            resetOsdInactivityTimer()
        }
    }

    // --- Audio-Spuren Dialog ---
    private fun showAudioTrackDialog() {
        val player = exoPlayer ?: return
        val tracks = player.currentTracks
        val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }

        if (audioGroups.isEmpty()) {
            Toast.makeText(this, "Keine alternativen Tonspuren verfügbar", Toast.LENGTH_SHORT).show()
            return
        }

        val names = mutableListOf<String>()
        var selectedIdx = 0
        audioGroups.forEachIndexed { idx, g ->
            val f = g.getTrackFormat(0)
            val lang = f.language ?: "Spur ${idx + 1}"
            val channels = if (f.channelCount > 2) "${f.channelCount}.1" else "Stereo"
            val label = f.label ?: ""
            names.add("$lang $label ($channels)".trim())
            if (g.isSelected) selectedIdx = idx
        }

        val dialog = AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("Tonspur auswählen")
            .setSingleChoiceItems(names.toTypedArray(), selectedIdx) { d, which ->
                val group = audioGroups[which]
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                    .build()
                d.dismiss()
                updateQualityAndAudioBadges()
                Toast.makeText(this, "Tonspur gewählt: ${names[which]}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Abbrechen", null)
            .create()

        activeDialog = dialog
        dialog.setOnDismissListener {
            activeDialog = null
            binding.btnAudioTracks.requestFocus()
            resetOsdInactivityTimer()
        }
        dialog.show()
    }

    // --- Untertitel Dialog ---
    private fun showSubtitleDialog() {
        val player = exoPlayer ?: return
        val tracks = player.currentTracks
        val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }

        val names = mutableListOf("Aus (Deaktiviert)")
        var selectedIdx = 0
        textGroups.forEachIndexed { idx, g ->
            val f = g.getTrackFormat(0)
            val lang = f.language ?: "Untertitel ${idx + 1}"
            names.add(lang)
            if (g.isSelected) selectedIdx = idx + 1
        }

        val dialog = AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("Untertitel auswählen")
            .setSingleChoiceItems(names.toTypedArray(), selectedIdx) { d, which ->
                if (which == 0) {
                    player.trackSelectionParameters = player.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
                } else {
                    val group = textGroups[which - 1]
                    player.trackSelectionParameters = player.trackSelectionParameters
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                        .build()
                }
                d.dismiss()
            }
            .setNegativeButton("Abbrechen", null)
            .create()

        activeDialog = dialog
        dialog.setOnDismissListener {
            activeDialog = null
            binding.btnSubtitles.requestFocus()
            resetOsdInactivityTimer()
        }
        dialog.show()
    }

    // --- Fernbedienungssteuerung (D-Pad) ---
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        resetOsdInactivityTimer()
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (activeDialog != null && activeDialog!!.isShowing) {
                    activeDialog?.dismiss()
                    return true
                }
                if (isScrubbing) {
                    scrubHandler.removeCallbacksAndMessages(null)
                    isScrubbing = false
                    targetSeekPosition = -1L
                    binding.osdScrubBubble.visibility = View.GONE
                    hideOsd()
                    return true
                }
                if (isLive && binding.layoutLiveOsd.visibility == View.VISIBLE) {
                    hideOsd()
                    return true
                }
                if (binding.osdBottom.visibility == View.VISIBLE) {
                    hideOsd()
                    return true
                }
                finish()
                return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_M, KeyEvent.KEYCODE_I -> {
                toggleDebugOverlay()
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
                } else if (isOsdButtonFocused()) {
                    return false // Erlaubt D-Pad Navigation zwischen den Buttons
                } else {
                    performNetflixScrub(true)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (!isLive) {
                    if (isOsdButtonFocused()) {
                        return false // Erlaubt D-Pad Navigation zwischen den Buttons
                    } else {
                        performNetflixScrub(false)
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
                        showOsd(binding.txtPlayerTitle.text.toString(), currentStreamId)
                        binding.playerSeekBar.requestFocus()
                    } else if (binding.playerSeekBar.hasFocus()) {
                        focusOsdButtonRow()
                    } else if (isOsdButtonFocused()) {
                        // Bereits auf den Buttons, Fokus behalten
                    } else {
                        // OSD ist bereits sichtbar, aber weder SeekBar noch Buttons waren fokussiert:
                        focusOsdButtonRow()
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
                        showOsd(binding.txtPlayerTitle.text.toString(), currentStreamId)
                        binding.playerSeekBar.requestFocus()
                    } else if (isOsdButtonFocused()) {
                        // Von der Buttonleiste hoch zur SeekBar
                        binding.playerSeekBar.requestFocus()
                    } else if (binding.playerSeekBar.hasFocus()) {
                        // Von der SeekBar hoch: OSD schließen
                        hideOsd()
                    } else {
                        // OSD sichtbar, Fokus war nicht im OSD: zur SeekBar
                        binding.playerSeekBar.requestFocus()
                    }
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (isLive) {
                    if (binding.layoutLiveOsd.visibility == View.VISIBLE) {
                        hideOsd()
                    } else {
                        showLiveOsd()
                    }
                    return true
                } else {
                    if (isScrubbing && targetSeekPosition >= 0) {
                        commitScrubSeek()
                        return true
                    }
                    if (binding.btnAudioTracks.hasFocus()) {
                        showAudioTrackDialog()
                        return true
                    } else if (binding.btnSubtitles.hasFocus()) {
                        showSubtitleDialog()
                        return true
                    } else if (binding.btnPrevEpisode.hasFocus()) {
                        playPreviousEpisode()
                        return true
                    } else if (binding.btnNextEpisode.hasFocus()) {
                        playNextEpisode()
                        return true
                    } else if (binding.btnDebugOverlay.hasFocus()) {
                        toggleDebugOverlay()
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

        // Vor neuem Stream sofort vorherige Verbindung stoppen
        exoPlayer?.stop()
        exoPlayer?.clearMediaItems()
        PlayerUtils.cancelPendingMediaRequests()

        historyManager.saveLiveChannel(stream)
        showOsd(stream.name, stream.streamId)
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
        osdHandler.removeCallbacksAndMessages(null)
        progressHandler.removeCallbacksAndMessages(null)
        scrubHandler.removeCallbacksAndMessages(null)
        retryHandler.removeCallbacksAndMessages(null)
        PlayerUtils.releaseStreamConnections(exoPlayer)
        exoPlayer?.release()
        exoPlayer = null
    }
}
