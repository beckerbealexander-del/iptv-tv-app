package com.alex.iptvplayer.ui

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
import androidx.media3.exoplayer.ExoPlayer
import com.alex.iptvplayer.R
import com.alex.iptvplayer.data.HistoryManager
import com.alex.iptvplayer.data.LiveStream
import com.alex.iptvplayer.data.XtreamClient
import com.alex.iptvplayer.databinding.ActivityPlayerBinding
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
    private var currentIndex: Int = -1
    private var currentStreamId: Int = -1
    private var currentStreamUrl: String = ""
    private var currentStreamName: String = ""
    private var currentPosterUrl: String? = null
    private var currentType: String = "VOD"
    private var seasonNum: Int = 1
    private var episodeNum: Int = 1

    private val osdHandler = Handler(Looper.getMainLooper())
    private val progressHandler = Handler(Looper.getMainLooper())
    private val scrubHandler = Handler(Looper.getMainLooper())
    private var epgJob: Job? = null

    // Netflix-Style Spulen Variablen
    private var targetSeekPosition: Long = -1
    private var scrubStepIndex = 0
    private val scrubSteps = longArrayOf(10_000, 30_000, 60_000, 150_000, 300_000)
    private var lastScrubTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        client = XtreamClient(this)
        historyManager = HistoryManager(this)

        currentStreamUrl = intent.getStringExtra("STREAM_URL") ?: ""
        currentStreamName = intent.getStringExtra("STREAM_NAME") ?: "Stream"
        currentPosterUrl = intent.getStringExtra("POSTER_URL")
        currentStreamId = intent.getIntExtra("STREAM_ID", -1)
        currentIndex = intent.getIntExtra("CURRENT_INDEX", -1)
        currentType = intent.getStringExtra("STREAM_TYPE") ?: if (intent.hasExtra("STREAM_LIST")) "LIVE" else "VOD"
        seasonNum = intent.getIntExtra("SEASON_NUM", 1)
        episodeNum = intent.getIntExtra("EPISODE_NUM", 1)

        @Suppress("DEPRECATION")
        streamList = (intent.getSerializableExtra("STREAM_LIST") as? ArrayList<LiveStream>) ?: emptyList()
        isLive = streamList.isNotEmpty() || currentType == "LIVE"

        setupUI()
        setupPlayer(currentStreamUrl, currentStreamName, currentStreamId)
    }

    private fun setupUI() {
        if (isLive) {
            binding.layoutTimeline.visibility = View.GONE
            binding.txtHintControls.text = "▲ / ▼ Umschalten | OK Info"
        } else {
            binding.layoutTimeline.visibility = View.VISIBLE
            binding.txtHintControls.text = "◀ / ▶ Spulen | ▲ Zurück zum Film"
        }

        binding.btnPlayPause.setOnClickListener { togglePlayPause() }
        binding.btnAudioTracks.setOnClickListener { showAudioTrackDialog() }
        binding.btnSubtitles.setOnClickListener { showSubtitleDialog() }

        // Wenn auf den Buttons DPAD_UP gedrückt wird: OSD verlassen und Fokus zurück zum Video!
        val buttonKeyDispatcher = View.OnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                hideOsd()
                true
            } else {
                false
            }
        }
        binding.btnPlayPause.setOnKeyListener(buttonKeyDispatcher)
        binding.btnAudioTracks.setOnKeyListener(buttonKeyDispatcher)
        binding.btnSubtitles.setOnKeyListener(buttonKeyDispatcher)
        binding.playerSeekBar.setOnKeyListener(buttonKeyDispatcher)

        binding.playerSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && exoPlayer != null) {
                    val duration = exoPlayer!!.duration
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

    private fun setupPlayer(url: String, name: String, streamId: Int) {
        showOsd(name, streamId)

        exoPlayer = PlayerUtils.createExoPlayer(this).apply {
            binding.playerView.player = this

            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    binding.playerLoading.visibility =
                        if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE

                    if (state == Player.STATE_READY) {
                        updateQualityAndAudioBadges()
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    binding.btnPlayPause.text = if (isPlaying) "⏸ Pause" else "▶ Play"
                }

                override fun onTracksChanged(tracks: Tracks) {
                    updateQualityAndAudioBadges()
                }

                override fun onPlayerError(error: PlaybackException) {
                    Toast.makeText(this@PlayerActivity, "Wiedergabefehler: ${error.message}", Toast.LENGTH_SHORT).show()
                }
            })

            val mediaItem = MediaItem.fromUri(url)
            setMediaItem(mediaItem)
            prepare()

            // Weiterschauen / Resume Position prüfen
            if (!isLive) {
                val resumePos = historyManager.getResumePosition(url)
                if (resumePos > 15_000) {
                    seekTo(resumePos)
                    Toast.makeText(this@PlayerActivity, "Fortgesetzt bei ${formatTime(resumePos)}", Toast.LENGTH_SHORT).show()
                }
            }

            playWhenReady = true
        }

        startProgressUpdater()
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
                if (player != null && !isLive && player.duration > 0 && targetSeekPosition < 0) {
                    val current = player.currentPosition
                    val total = player.duration
                    binding.txtTimeCurrent.text = formatTime(current)
                    binding.txtTimeTotal.text = formatTime(total)
                    binding.playerSeekBar.progress = ((current * 1000) / total).toInt()

                    // Fortschritt alle 5s im Verlauf speichern
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
                            episodeNum = episodeNum
                        )
                    }
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
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    private fun togglePlayPause() {
        val player = exoPlayer ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            player.play()
        }
        showOsd(binding.txtPlayerTitle.text.toString(), currentStreamId)
    }

    private fun showOsd(name: String, streamId: Int) {
        binding.txtPlayerTitle.text = name
        binding.osdTop.visibility = View.VISIBLE
        binding.osdBottom.visibility = View.VISIBLE

        if (isLive && streamId > 0) {
            epgJob?.cancel()
            epgJob = lifecycleScope.launch {
                val list = client.getEpg(streamId)
                if (list.isNotEmpty()) {
                    val cur = list.firstOrNull { it.isNowPlaying } ?: list[0]
                    binding.txtPlayerSubtitle.text = "🔴 ${cur.title} (${cur.start} - ${cur.end})"
                }
            }
        }

        osdHandler.removeCallbacksAndMessages(null)
        osdHandler.postDelayed({
            if (exoPlayer?.isPlaying == true && !isOsdFocused()) {
                hideOsd()
            }
        }, 4000)
    }

    private fun hideOsd() {
        binding.osdTop.visibility = View.GONE
        binding.osdBottom.visibility = View.GONE
        binding.playerView.requestFocus()
    }

    private fun isOsdFocused(): Boolean {
        return binding.btnPlayPause.hasFocus() ||
                binding.btnAudioTracks.hasFocus() ||
                binding.btnSubtitles.hasFocus() ||
                binding.playerSeekBar.hasFocus()
    }

    // --- Netflix-Style Scrubbing ---
    private fun performNetflixScrub(forward: Boolean) {
        val player = exoPlayer ?: return
        val now = System.currentTimeMillis()

        if (now - lastScrubTime < 600) {
            scrubStepIndex = (scrubStepIndex + 1).coerceAtMost(scrubSteps.size - 1)
        } else {
            scrubStepIndex = 0
            targetSeekPosition = player.currentPosition
        }
        lastScrubTime = now

        val stepMs = scrubSteps[scrubStepIndex]
        if (forward) {
            targetSeekPosition = (targetSeekPosition + stepMs).coerceAtMost(player.duration)
        } else {
            targetSeekPosition = (targetSeekPosition - stepMs).coerceAtLeast(0)
        }

        val icon = if (forward) "⏩ +" else "⏪ -"
        val stepSec = stepMs / 1000
        val stepText = if (stepSec >= 60) "${stepSec / 60}m" else "${stepSec}s"
        binding.txtScrubSpeed.text = "$icon$stepText"
        binding.txtScrubTargetTime.text = "${formatTime(targetSeekPosition)} / ${formatTime(player.duration)}"
        binding.osdScrubBubble.visibility = View.VISIBLE

        showOsd(binding.txtPlayerTitle.text.toString(), currentStreamId)
        binding.txtTimeCurrent.text = formatTime(targetSeekPosition)
        if (player.duration > 0) {
            binding.playerSeekBar.progress = ((targetSeekPosition * 1000) / player.duration).toInt()
        }

        scrubHandler.removeCallbacksAndMessages(null)
        scrubHandler.postDelayed({
            if (targetSeekPosition >= 0) {
                player.seekTo(targetSeekPosition)
                targetSeekPosition = -1
                binding.osdScrubBubble.visibility = View.GONE
            }
        }, 600)
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

        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("Tonspur auswählen")
            .setSingleChoiceItems(names.toTypedArray(), selectedIdx) { dialog, which ->
                val group = audioGroups[which]
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                    .build()
                dialog.dismiss()
                updateQualityAndAudioBadges()
                Toast.makeText(this, "Tonspur gewählt: ${names[which]}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
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

        AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Dialog_Alert)
            .setTitle("Untertitel auswählen")
            .setSingleChoiceItems(names.toTypedArray(), selectedIdx) { dialog, which ->
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
                dialog.dismiss()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    // --- Fernbedienungssteuerung (D-Pad) ---
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                // Wenn OSD offen ist, erst OSD schließen statt App zu beenden
                if (binding.osdBottom.visibility == View.VISIBLE) {
                    hideOsd()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (!isLive && !isOsdFocused()) {
                    performNetflixScrub(true)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (!isLive && !isOsdFocused()) {
                    performNetflixScrub(false)
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (isLive) {
                    zapPreviousChannel()
                    return true
                } else {
                    if (binding.osdBottom.visibility != View.VISIBLE) {
                        showOsd(binding.txtPlayerTitle.text.toString(), currentStreamId)
                    }
                    binding.btnPlayPause.requestFocus()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (isLive) {
                    zapNextChannel()
                    return true
                } else {
                    if (isOsdFocused() || binding.osdBottom.visibility == View.VISIBLE) {
                        hideOsd()
                    } else {
                        showOsd(binding.txtPlayerTitle.text.toString(), currentStreamId)
                        binding.btnPlayPause.requestFocus()
                    }
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (!isLive) {
                    if (!isOsdFocused()) {
                        togglePlayPause()
                        return true
                    }
                } else {
                    if (binding.osdBottom.visibility == View.VISIBLE) {
                        hideOsd()
                    } else {
                        showOsd(binding.txtPlayerTitle.text.toString(), currentStreamId)
                    }
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun zapNextChannel() {
        if (streamList.isNotEmpty() && currentIndex >= 0) {
            currentIndex = (currentIndex + 1) % streamList.size
            val stream = streamList[currentIndex]
            switchStream(stream)
        }
    }

    private fun zapPreviousChannel() {
        if (streamList.isNotEmpty() && currentIndex >= 0) {
            currentIndex = if (currentIndex - 1 < 0) streamList.size - 1 else currentIndex - 1
            val stream = streamList[currentIndex]
            switchStream(stream)
        }
    }

    private fun switchStream(stream: LiveStream) {
        currentStreamId = stream.streamId
        currentStreamName = stream.name
        currentStreamUrl = client.getLiveStreamUrl(stream.streamId)
        showOsd(stream.name, stream.streamId)
        val mediaItem = MediaItem.fromUri(currentStreamUrl)
        exoPlayer?.setMediaItem(mediaItem)
        exoPlayer?.prepare()
        exoPlayer?.playWhenReady = true
    }

    private fun saveCurrentState() {
        val player = exoPlayer ?: return
        if (!isLive && player.duration > 0 && player.currentPosition > 5000) {
            historyManager.saveProgress(
                id = if (currentStreamId > 0) currentStreamId.toString() else currentStreamUrl,
                title = currentStreamName,
                streamUrl = currentStreamUrl,
                posterUrl = currentPosterUrl,
                type = currentType,
                streamId = currentStreamId,
                positionMs = player.currentPosition,
                durationMs = player.duration,
                season = seasonNum,
                episodeNum = episodeNum
            )
        }
    }

    override fun onPause() {
        super.onPause()
        saveCurrentState()
    }

    override fun onDestroy() {
        super.onDestroy()
        saveCurrentState()
        osdHandler.removeCallbacksAndMessages(null)
        progressHandler.removeCallbacksAndMessages(null)
        scrubHandler.removeCallbacksAndMessages(null)
        exoPlayer?.release()
        exoPlayer = null
    }
}
