package com.tivizone.player.ui

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
import com.tivizone.player.data.EpisodeItem
import com.tivizone.player.data.HistoryManager
import com.tivizone.player.data.LiveStream
import com.tivizone.player.data.MultiStreamChannel
import com.tivizone.player.data.MultiStreamManager
import com.tivizone.player.data.QualityPreferenceManager
import com.tivizone.player.data.XtreamClient
import com.tivizone.player.databinding.ActivityPlayerBinding
import com.tivizone.player.ui.player.PlayerEpisodeManager
import com.tivizone.player.ui.player.PlayerOsdController
import com.tivizone.player.ui.player.PlayerRemoteHandler
import com.tivizone.player.ui.player.PlayerRetryManager
import com.tivizone.player.ui.player.PlayerScrubberHelper
import com.tivizone.player.ui.player.PlayerSleepTimer
import com.tivizone.player.ui.player.PlayerStatsOverlayHelper
import com.tivizone.player.ui.player.PlayerTrackDialogHelper
import com.tivizone.player.util.AppLogger
import com.tivizone.player.util.PlayerUtils
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
    private lateinit var episodeManager: PlayerEpisodeManager
    private lateinit var sleepTimer: PlayerSleepTimer
    private lateinit var retryManager: PlayerRetryManager
    private lateinit var remoteHandler: PlayerRemoteHandler

    private var isLive: Boolean = false
    private var streamList: List<LiveStream> = emptyList()
    private var channelList: List<MultiStreamChannel> = emptyList()
    private var activeChannel: MultiStreamChannel? = null
    private var activeSourceIndex: Int = 0
    private var currentIndex: Int = -1
    private var currentStreamId: Int = -1
    private var currentStreamUrl: String = ""
    private var currentStreamName: String = ""
    private var currentPosterUrl: String? = null
    private var currentType: String = "VOD"
    private var seasonNum: Int = 1
    private var episodeNum: Int = 1
    private var seriesId: Int = -1

    private val progressHandler = Handler(Looper.getMainLooper())
    private var lastKnownPosition: Long = 0L
    private var lastKnownDuration: Long = 0L
    private var lastPeriodicCloudSync: Long = 0L

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                AppLogger.logLifecycle("PlayerActivity", "ACTION_SCREEN_OFF -> Releasing stream connections")
                saveCurrentState()
                retryManager.cancelAll()
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
        sleepTimer = PlayerSleepTimer(this, lifecycleScope, listOf(binding.btnSleepTimer, binding.btnLivePlayerSleepTimer)) { exoPlayer }
        retryManager = PlayerRetryManager(this, binding.txtPlayerRetryBanner, binding.playerLoading) { seekPos ->
            reconnectStream(seekPos)
        }

        episodeManager = PlayerEpisodeManager(
            client = client,
            btnPrevEpisode = binding.btnPrevEpisode,
            btnNextEpisode = binding.btnNextEpisode,
            onEpisodeSelected = { streamUrl, title, streamId, season, episode, poster ->
                currentStreamUrl = streamUrl
                currentStreamName = title
                currentStreamId = streamId
                seasonNum = season
                episodeNum = episode
                currentPosterUrl = poster
                retryManager.resetRetryCount()

                val mediaItem = MediaItem.fromUri(currentStreamUrl)
                exoPlayer?.setMediaItem(mediaItem)
                exoPlayer?.prepare()
                exoPlayer?.playWhenReady = true
            },
            onShowOsd = { showOsdWrapper() }
        )

        remoteHandler = PlayerRemoteHandler(
            binding = binding,
            osdController = osdController,
            scrubberHelper = scrubberHelper,
            isAnyDialogShowing = { isAnyDialogShowing() },
            dismissAnyDialog = { dismissAnyDialog() },
            isLive = { isLive },
            onPlayPause = { togglePlayPause() },
            onPerformScrub = { forward -> performScrub(forward) },
            onCommitScrub = { commitScrub() },
            onZapNext = { zapNextChannel() },
            onZapPrev = { zapPreviousChannel() },
            onCycleSourceNext = { cycleToNextSourceManually() },
            onCycleSourcePrev = { cycleToPreviousSourceManually() },
            onToggleStats = { statsOverlayHelper.toggle(exoPlayer, currentStreamId, currentType, seasonNum, episodeNum, lastKnownDuration) },
            onOpenSleepTimerMenu = { showSleepTimerDialog() },
            onPlayPrevEpisode = { episodeManager.playPreviousEpisode() },
            onPlayNextEpisode = { episodeManager.playNextEpisode() },
            onShowAudioTrackDialog = {
                trackDialogHelper.showAudioTrackDialog(this, exoPlayer, ::updateQualityAndAudioBadges) {
                    binding.btnAudioTracks.requestFocus()
                    osdController.resetOsdInactivityTimer { isAnyDialogShowing() }
                }
            },
            onShowSubtitleDialog = {
                trackDialogHelper.showSubtitleDialog(this, exoPlayer, ::updateQualityAndAudioBadges) {
                    binding.btnSubtitles.requestFocus()
                    osdController.resetOsdInactivityTimer { isAnyDialogShowing() }
                }
            },
            onShowOsd = { showOsdWrapper() },
            onFinish = { finish() }
        )

        currentStreamUrl = intent.getStringExtra("STREAM_URL") ?: ""
        currentStreamName = intent.getStringExtra("STREAM_NAME") ?: "Stream"
        currentPosterUrl = intent.getStringExtra("POSTER_URL")
        currentStreamId = intent.getIntExtra("STREAM_ID", -1)
        currentIndex = intent.getIntExtra("CURRENT_INDEX", -1)
        val initialEpisodeIndex = intent.getIntExtra("EPISODE_INDEX", -1)
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
        val episodes = (intent.getSerializableExtra("EPISODE_LIST") as? ArrayList<EpisodeItem>) ?: emptyList()

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

        setupUI(episodes, initialEpisodeIndex)
        setupPlayer(currentStreamUrl)

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

    private fun setupUI(episodes: List<EpisodeItem>, initialEpisodeIndex: Int) {
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

            episodeManager.setup(currentType, episodes, initialEpisodeIndex, currentStreamName, currentPosterUrl)
        }

        binding.btnAudioTracks.setOnClickListener {
            trackDialogHelper.showAudioTrackDialog(this, exoPlayer, ::updateQualityAndAudioBadges) {
                binding.btnAudioTracks.requestFocus()
                osdController.resetOsdInactivityTimer { isAnyDialogShowing() }
            }
        }
        binding.btnSubtitles.setOnClickListener {
            trackDialogHelper.showSubtitleDialog(this, exoPlayer, ::updateQualityAndAudioBadges) {
                binding.btnSubtitles.requestFocus()
                osdController.resetOsdInactivityTimer { isAnyDialogShowing() }
            }
        }
        binding.btnDebugOverlay.setOnClickListener {
            statsOverlayHelper.toggle(exoPlayer, currentStreamId, currentType, seasonNum, episodeNum, lastKnownDuration)
        }
        binding.btnSleepTimer.setOnClickListener {
            showSleepTimerDialog()
        }
        binding.btnLivePlayerSleepTimer.setOnClickListener {
            showSleepTimerDialog()
        }

        remoteHandler.setupKeyListeners()

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

    private fun setupPlayer(url: String) {
        showOsdWrapper()
        retryManager.resetRetryCount()

        exoPlayer = PlayerUtils.createExoPlayer(this, isLive = isLive).apply {
            binding.playerView.player = this

            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    binding.playerLoading.visibility =
                        if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE

                    if (state == Player.STATE_READY) {
                        retryManager.hideRetryBanner()
                        updateQualityAndAudioBadges()
                        AppLogger.logPlayerState("PlayerActivity", "READY (Stream: $currentStreamId, Type: $currentType)")
                    } else if (state == Player.STATE_BUFFERING) {
                        AppLogger.logPlayerState("PlayerActivity", "BUFFERING")
                    } else if (state == Player.STATE_ENDED) {
                        retryManager.onPlaybackStateEnded()
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
                                seriesId = if (currentType == "SERIES") seriesId else 0,
                                forceCloudUpload = true
                            )
                        }
                    }

                    updateCenterPauseVisibility(exoPlayer?.isPlaying == true)

                    if (state == Player.STATE_ENDED && currentType == "SERIES") {
                        if (episodeManager.hasNextEpisode()) {
                            Toast.makeText(this@PlayerActivity, "Nächste Folge startet...", Toast.LENGTH_SHORT).show()
                            episodeManager.playNextEpisode()
                        }
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    updateCenterPauseVisibility(isPlaying)
                    retryManager.onIsPlayingChanged(isPlaying)
                }

                override fun onTracksChanged(tracks: Tracks) {
                    AppLogger.logFormatChange("PlayerActivity", exoPlayer?.videoFormat, exoPlayer?.audioFormat)
                    updateQualityAndAudioBadges()
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {}

                override fun onPlayerError(error: PlaybackException) {
                    val currentPos = if (currentPosition > 0) currentPosition else lastKnownPosition
                    retryManager.handlePlayerError(
                        error = error,
                        currentPos = currentPos,
                        lastKnownPos = lastKnownPosition,
                        currentStreamUrl = currentStreamUrl,
                        onUrlAdjusted = { adjustedUrl -> currentStreamUrl = adjustedUrl }
                    )
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

                        // Periodischer Cloud-Sync alle 60s während des Schauens
                        if (System.currentTimeMillis() - lastPeriodicCloudSync > 60_000L) {
                            lastPeriodicCloudSync = System.currentTimeMillis()
                            saveCurrentState()
                        }
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
            saveCurrentState()
        } else {
            player.play()
            showOsdWrapper()
        }
    }

    private fun isAnyDialogShowing(): Boolean =
        trackDialogHelper.isDialogShowing() || sleepTimer.isDialogShowing()

    private fun dismissAnyDialog(): Boolean {
        if (trackDialogHelper.dismissActiveDialog()) return true
        if (sleepTimer.dismissActiveDialog()) return true
        return false
    }

    private fun showSleepTimerDialog() {
        sleepTimer.showSelectionDialog(
            onResetInactivity = { osdController.resetOsdInactivityTimer { isAnyDialogShowing() } },
            onDismissed = {
                binding.btnSleepTimer.requestFocus()
                osdController.resetOsdInactivityTimer { isAnyDialogShowing() }
            }
        )
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
                { isAnyDialogShowing() }
            )
        } else {
            osdController.showOsd(
                currentStreamName,
                isLive,
                { isAnyDialogShowing() }
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
        retryManager.resetRetryCount()
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
        playCurrentLiveSource()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (remoteHandler.handleKeyDown(keyCode, event)) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun zapNextChannel() {
        if (channelList.isNotEmpty() && currentIndex >= 0) {
            currentIndex = (currentIndex + 1) % channelList.size
            activeChannel = MultiStreamManager.applyPreferredSources(this, channelList[currentIndex])
            activeSourceIndex = 0
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
        retryManager.resetRetryCount()

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
                seriesId = if (currentType == "SERIES") seriesId else 0,
                forceCloudUpload = true
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
        retryManager.cancelAll()
        PlayerUtils.releaseStreamConnections(exoPlayer)
    }

    override fun onStop() {
        super.onStop()
        AppLogger.logLifecycle("PlayerActivity", "onStop -> Releasing stream connections")
        saveCurrentState()
        retryManager.cancelAll()
        PlayerUtils.releaseStreamConnections(exoPlayer)
    }

    override fun onDestroy() {
        super.onDestroy()
        AppLogger.logLifecycle("PlayerActivity", "onDestroy")
        try {
            unregisterReceiver(screenOffReceiver)
        } catch (e: Exception) {}
        saveCurrentState()
        sleepTimer.cancel()
        osdController.onDestroy()
        progressHandler.removeCallbacksAndMessages(null)
        retryManager.cancelAll()
        PlayerUtils.releaseStreamConnections(exoPlayer)
        exoPlayer?.release()
        exoPlayer = null
    }
}
