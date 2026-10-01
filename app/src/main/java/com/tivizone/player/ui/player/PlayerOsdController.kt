package com.tivizone.player.ui.player

import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.media3.exoplayer.ExoPlayer
import com.tivizone.player.data.MultiStreamChannel
import com.tivizone.player.data.XtreamClient
import com.tivizone.player.databinding.ActivityPlayerBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Locale

class PlayerOsdController(
    private val binding: ActivityPlayerBinding,
    private val client: XtreamClient,
    private val coroutineScope: CoroutineScope
) {
    var lastFocusedOsdButton: View? = null
    private val osdHandler = Handler(Looper.getMainLooper())
    private var epgJob: Job? = null

    val isOsdVisible: Boolean
        get() = binding.osdBottom.visibility == View.VISIBLE || binding.layoutLiveOsd.visibility == View.VISIBLE

    fun resetOsdInactivityTimer(isDialogShowing: () -> Boolean) {
        osdHandler.removeCallbacksAndMessages(null)
        osdHandler.postDelayed({
            if (!isDialogShowing()) {
                hideOsd()
            }
        }, 5000)
    }

    fun keepOsdOpen() {
        osdHandler.removeCallbacksAndMessages(null)
    }

    fun showOsd(
        title: String,
        isLive: Boolean,
        isDialogShowing: () -> Boolean,
        onFocusDefault: () -> Unit
    ) {
        if (isLive) {
            return
        }

        binding.layoutLiveOsd.visibility = View.GONE
        binding.txtPlayerTitle.text = title
        binding.osdTop.visibility = View.VISIBLE
        binding.osdBottom.visibility = View.VISIBLE

        resetOsdInactivityTimer(isDialogShowing)

        if (!isOsdFocused()) {
            binding.playerSeekBar.post {
                if (binding.osdBottom.visibility == View.VISIBLE && !isOsdButtonFocused()) {
                    onFocusDefault()
                }
            }
        }
    }

    fun showLiveOsd(
        channel: MultiStreamChannel?,
        currentStreamName: String,
        currentStreamId: Int,
        activeSourceIndex: Int,
        currentIndex: Int,
        player: ExoPlayer?,
        isDialogShowing: () -> Boolean
    ) {
        binding.osdTop.visibility = View.GONE
        binding.osdBottom.visibility = View.GONE
        binding.layoutLiveOsd.visibility = View.VISIBLE

        val chNum = if (currentIndex >= 0) "${currentIndex + 1}" else "🔴"
        binding.txtLiveOsdChannelNum.text = chNum
        binding.txtLiveOsdChannelName.text = channel?.cleanName ?: currentStreamName

        if (channel != null && channel.sources.isNotEmpty()) {
            val src = channel.sources.getOrNull(activeSourceIndex) ?: channel.sources[0]
            val count = channel.sources.size
            binding.txtLiveOsdSourceInfo.text = if (count > 1) {
                "⚡ Quelle ${activeSourceIndex + 1}/$count: ${src.label}"
            } else {
                "⚡ ${src.label}"
            }
            binding.txtLiveOsdSourceInfo.visibility = View.VISIBLE
        } else {
            binding.txtLiveOsdSourceInfo.visibility = View.GONE
        }

        val vf = player?.videoFormat
        val af = player?.audioFormat
        val res = if (vf != null && vf.width > 0 && vf.height > 0) "${vf.width}x${vf.height}" else "1080p"
        val fps = if (vf != null && vf.frameRate > 0) "${vf.frameRate.toInt()} fps" else "50 fps"
        val vCodec = vf?.sampleMimeType?.substringAfter("/")?.uppercase() ?: "H.264"
        val aCodec = af?.sampleMimeType?.substringAfter("/")?.uppercase() ?: "AAC"
        binding.txtLiveOsdTechSpecs.text = "$res | $fps | $vCodec | $aCodec"

        val epgTargetId = channel?.epgStreamId ?: currentStreamId
        if (epgTargetId > 0) {
            epgJob?.cancel()
            epgJob = coroutineScope.launch {
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

        resetOsdInactivityTimer(isDialogShowing)
    }

    fun hideOsd() {
        binding.osdTop.visibility = View.GONE
        binding.osdBottom.visibility = View.GONE
        binding.layoutLiveOsd.visibility = View.GONE
        binding.playerView.requestFocus()
    }

    fun isOsdFocused(): Boolean {
        return binding.btnAudioTracks.hasFocus() ||
                binding.btnSubtitles.hasFocus() ||
                binding.btnPrevEpisode.hasFocus() ||
                binding.btnNextEpisode.hasFocus() ||
                binding.btnDebugOverlay.hasFocus() ||
                binding.btnSleepTimer.hasFocus() ||
                binding.playerSeekBar.hasFocus()
    }

    fun isOsdButtonFocused(): Boolean {
        return binding.btnAudioTracks.hasFocus() ||
                binding.btnSubtitles.hasFocus() ||
                binding.btnPrevEpisode.hasFocus() ||
                binding.btnNextEpisode.hasFocus() ||
                binding.btnDebugOverlay.hasFocus() ||
                binding.btnSleepTimer.hasFocus()
    }

    fun focusOsdButtonRow() {
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

    fun onDestroy() {
        osdHandler.removeCallbacksAndMessages(null)
        epgJob?.cancel()
    }
}
