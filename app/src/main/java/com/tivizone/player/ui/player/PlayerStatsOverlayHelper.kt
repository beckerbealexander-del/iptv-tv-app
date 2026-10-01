package com.tivizone.player.ui.player

import android.view.View
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.Player
import com.tivizone.player.databinding.ActivityPlayerBinding
import java.util.Locale

class PlayerStatsOverlayHelper(
    private val binding: ActivityPlayerBinding,
    private val timeFormatter: (Long) -> String
) {
    val isVisible: Boolean
        get() = binding.layoutDebugOverlay.visibility == View.VISIBLE

    fun toggle(
        player: ExoPlayer?,
        streamId: Int,
        type: String,
        season: Int,
        episode: Int,
        lastKnownDuration: Long
    ) {
        if (isVisible) {
            binding.layoutDebugOverlay.visibility = View.GONE
        } else {
            binding.layoutDebugOverlay.visibility = View.VISIBLE
            update(player, streamId, type, season, episode, lastKnownDuration)
        }
    }

    fun update(
        player: ExoPlayer?,
        streamId: Int,
        type: String,
        season: Int,
        episode: Int,
        lastKnownDuration: Long
    ) {
        if (!isVisible) return
        val p = player ?: return

        val stateStr = when (p.playbackState) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> if (p.isPlaying) "PLAYING" else "PAUSED"
            Player.STATE_ENDED -> "ENDED"
            else -> "UNKNOWN"
        }
        val bufferSec = ((p.bufferedPosition - p.currentPosition).coerceAtLeast(0) / 1000.0)
        binding.txtDebugState.text = "State: $stateStr | Buffer: ${String.format(Locale.US, "%.1fs", bufferSec)}"

        val vf = p.videoFormat
        if (vf != null) {
            val w = vf.width
            val h = vf.height
            val fps = if (vf.frameRate > 0) "${vf.frameRate.toInt()}fps" else "?fps"
            val codec = vf.sampleMimeType?.substringAfter("/")?.uppercase() ?: "UNKNOWN"
            binding.txtDebugVideo.text = "Video: ${w}x${h} @ $fps ($codec)"
        } else {
            binding.txtDebugVideo.text = "Video: N/A"
        }

        val af = p.audioFormat
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

        val cur = p.currentPosition
        val dur = if (p.duration > 0) p.duration else lastKnownDuration
        val pct = if (dur > 0) (cur * 100 / dur).toInt() else 0
        binding.txtDebugPos.text = "Pos: ${timeFormatter(cur)} / ${timeFormatter(dur)} ($pct%)"

        val epInfo = if (type == "SERIES") " | S${season}E${episode}" else ""
        binding.txtDebugStreamInfo.text = "ID: $streamId | Type: $type$epInfo"
    }
}
