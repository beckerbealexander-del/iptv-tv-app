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

    private var isLive: Boolean = false
    private var streamList: List<LiveStream> = emptyList()
    private var currentIndex: Int = -1
    private var currentStreamId: Int = -1

    private val osdHandler = Handler(Looper.getMainLooper())
    private val progressHandler = Handler(Looper.getMainLooper())
    private var epgJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        client = XtreamClient(this)

        val streamUrl = intent.getStringExtra("STREAM_URL") ?: ""
        val streamName = intent.getStringExtra("STREAM_NAME") ?: "Stream"
        currentStreamId = intent.getIntExtra("STREAM_ID", -1)
        currentIndex = intent.getIntExtra("CURRENT_INDEX", -1)

        @Suppress("UNCHECKED_CAST")
        streamList = (intent.getSerializableExtra("STREAM_LIST") as? ArrayList<LiveStream>) ?: emptyList()
        isLive = streamList.isNotEmpty()

        setupUI()
        setupPlayer(streamUrl, streamName, currentStreamId)
    }

    private fun setupUI() {
        if (isLive) {
            binding.layoutTimeline.visibility = View.GONE
            binding.txtHintControls.text = "▲ / ▼ Umschalten | OK Info"
        } else {
            binding.layoutTimeline.visibility = View.VISIBLE
            binding.txtHintControls.text = "◀ / ▶ 10s spulen | OK Pause"
        }

        binding.btnPlayPause.setOnClickListener { togglePlayPause() }
        binding.btnAudioTracks.setOnClickListener { showAudioTrackDialog() }
        binding.btnSubtitles.setOnClickListener { showSubtitleDialog() }

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
                    binding.btnPlayPause.text = if (isPlaying) "⏸ Pause (OK)" else "▶ Play (OK)"
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
                if (player != null && !isLive && player.duration > 0) {
                    val current = player.currentPosition
                    val total = player.duration
                    binding.txtTimeCurrent.text = formatTime(current)
                    binding.txtTimeTotal.text = formatTime(total)
                    binding.playerSeekBar.progress = ((current * 1000) / total).toInt()
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
            if (exoPlayer?.isPlaying == true) {
                binding.osdTop.visibility = View.GONE
                binding.osdBottom.visibility = View.GONE
            }
        }, 5000)
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
            // Spulen vorwärts (10s)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                if (!isLive && exoPlayer != null) {
                    val newPos = (exoPlayer!!.currentPosition + 10_000).coerceAtMost(exoPlayer!!.duration)
                    exoPlayer!!.seekTo(newPos)
                    showOsd(binding.txtPlayerTitle.text.toString(), currentStreamId)
                    return true
                }
            }
            // Spulen rückwärts (10s)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (!isLive && exoPlayer != null) {
                    val newPos = (exoPlayer!!.currentPosition - 10_000).coerceAtLeast(0)
                    exoPlayer!!.seekTo(newPos)
                    showOsd(binding.txtPlayerTitle.text.toString(), currentStreamId)
                    return true
                }
            }
            // Play / Pause oder OSD öffnen
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (!isLive) {
                    togglePlayPause()
                } else {
                    if (binding.osdBottom.visibility == View.VISIBLE) {
                        binding.osdTop.visibility = View.GONE
                        binding.osdBottom.visibility = View.GONE
                    } else {
                        showOsd(binding.txtPlayerTitle.text.toString(), currentStreamId)
                    }
                }
                return true
            }
            // Zapping im Live TV / Menü öffnen in VOD
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (isLive) {
                    zapNextChannel()
                } else {
                    showAudioTrackDialog()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (isLive) {
                    zapPreviousChannel()
                } else {
                    showOsd(binding.txtPlayerTitle.text.toString(), currentStreamId)
                }
                return true
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
        showOsd(stream.name, stream.streamId)
        val url = client.getLiveStreamUrl(stream.streamId)
        val mediaItem = MediaItem.fromUri(url)
        exoPlayer?.setMediaItem(mediaItem)
        exoPlayer?.prepare()
        exoPlayer?.playWhenReady = true
    }

    override fun onDestroy() {
        super.onDestroy()
        osdHandler.removeCallbacksAndMessages(null)
        progressHandler.removeCallbacksAndMessages(null)
        exoPlayer?.release()
        exoPlayer = null
    }
}
