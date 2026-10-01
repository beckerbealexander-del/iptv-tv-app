package com.tivizone.player.ui.player

import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.media3.exoplayer.ExoPlayer
import com.tivizone.player.databinding.ActivityPlayerBinding

class PlayerScrubberHelper(
    private val binding: ActivityPlayerBinding,
    private val timeFormatter: (Long) -> String
) {
    var isScrubbing = false
        private set

    var targetSeekPosition = -1L
        private set

    private var scrubSessionStartTime = 0L
    private var lastScrubTime = 0L
    private val scrubHandler = Handler(Looper.getMainLooper())

    fun performNetflixScrub(
        forward: Boolean,
        player: ExoPlayer?,
        lastKnownDuration: Long,
        lastKnownPosition: Long,
        onOsdKeepOpen: () -> Unit,
        onCommitSeek: (Long) -> Unit
    ) {
        val p = player ?: return
        val totalDuration = if (p.duration > 0) p.duration else lastKnownDuration
        if (totalDuration <= 0) return

        val now = System.currentTimeMillis()

        // Key-repeat Drosselung: mindestens 140ms pro Schritt
        if (isScrubbing && now - lastScrubTime < 140L) {
            return
        }

        if (!isScrubbing || targetSeekPosition < 0) {
            isScrubbing = true
            scrubSessionStartTime = now
            val cur = p.currentPosition
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
        binding.txtScrubTargetTime.text = "${timeFormatter(targetSeekPosition)} / ${timeFormatter(totalDuration)}"
        binding.osdScrubBubble.visibility = View.VISIBLE

        onOsdKeepOpen()
        binding.txtTimeCurrent.text = timeFormatter(targetSeekPosition)
        binding.txtTimeTotal.text = timeFormatter(totalDuration)
        binding.playerSeekBar.progress = ((targetSeekPosition * 1000) / totalDuration).toInt()

        // Nach 800ms ohne weiteren Tastendruck automatisch ausführen
        scrubHandler.removeCallbacksAndMessages(null)
        scrubHandler.postDelayed({
            commitScrubSeek(player, lastKnownDuration, onCommitSeek)
        }, 800)
    }

    fun commitScrubSeek(
        player: ExoPlayer?,
        lastKnownDuration: Long,
        onCommitSeek: (Long) -> Unit
    ) {
        scrubHandler.removeCallbacksAndMessages(null)
        val pos = targetSeekPosition
        val wasScrubbing = isScrubbing
        isScrubbing = false
        targetSeekPosition = -1L
        binding.osdScrubBubble.visibility = View.GONE

        if (wasScrubbing && pos >= 0) {
            val p = player ?: return
            val totalDuration = if (p.duration > 0) p.duration else lastKnownDuration
            val finalPos = if (totalDuration > 0) pos.coerceIn(0L, totalDuration) else pos.coerceAtLeast(0L)
            p.seekTo(finalPos)
            binding.txtTimeCurrent.text = timeFormatter(finalPos)
            if (totalDuration > 0) {
                binding.playerSeekBar.progress = ((finalPos * 1000) / totalDuration).toInt()
            }
            onCommitSeek(finalPos)
        }
    }

    fun cancelScrub() {
        scrubHandler.removeCallbacksAndMessages(null)
        isScrubbing = false
        targetSeekPosition = -1L
        binding.osdScrubBubble.visibility = View.GONE
    }
}
