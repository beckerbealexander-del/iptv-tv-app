package com.tivizone.player.ui.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource
import com.tivizone.player.util.AppLogger
import java.net.ConnectException
import java.net.SocketTimeoutException

class PlayerRetryManager(
    private val context: Context,
    private val txtRetryBanner: TextView,
    private val loadingIndicator: View,
    private val onReconnect: (seekPos: Long) -> Unit
) {
    private val retryHandler = Handler(Looper.getMainLooper())
    var retryCount = 0
        private set
    private val maxRetries = 5
    private val resetRetryRunnable = Runnable { retryCount = 0 }

    fun resetRetryCount() {
        retryCount = 0
        retryHandler.removeCallbacks(resetRetryRunnable)
        hideRetryBanner()
    }

    fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            retryHandler.removeCallbacks(resetRetryRunnable)
            retryHandler.postDelayed(resetRetryRunnable, 3000)
        } else {
            retryHandler.removeCallbacks(resetRetryRunnable)
        }
    }

    fun onPlaybackStateEnded() {
        retryHandler.removeCallbacks(resetRetryRunnable)
    }

    fun showRetryBanner(msg: String) {
        txtRetryBanner.text = msg
        txtRetryBanner.visibility = View.VISIBLE
    }

    fun hideRetryBanner() {
        txtRetryBanner.visibility = View.GONE
    }

    fun handlePlayerError(
        error: PlaybackException,
        currentPos: Long,
        lastKnownPos: Long,
        currentStreamUrl: String,
        onUrlAdjusted: (String) -> Unit
    ) {
        retryHandler.removeCallbacks(resetRetryRunnable)
        AppLogger.logError("PlayerActivity", "Player error: ${error.errorCodeName} (${error.errorCode})", error)

        if (error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) {
            loadingIndicator.visibility = View.GONE
            hideRetryBanner()
            Toast.makeText(context, "Audio-/Decoder-Fehler: Format wird vom Gerät nicht unterstützt", Toast.LENGTH_LONG).show()
            return
        }

        val cause = error.cause
        val isHttpAuthOrRateLimit = when (cause) {
            is HttpDataSource.InvalidResponseCodeException -> cause.responseCode in listOf(401, 403, 408, 429, 503)
            else -> error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        }
        val isTimeoutOrConnFailed = error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
                cause is SocketTimeoutException ||
                cause is ConnectException

        val pos = if (currentPos > 0) currentPos else lastKnownPos

        if ((isHttpAuthOrRateLimit || isTimeoutOrConnFailed) && retryCount < maxRetries) {
            retryCount++
            val msg = "⏳ Warte auf Stream-Freigabe... ($retryCount/$maxRetries)"
            showRetryBanner(msg)
            AppLogger.logNetwork("Auto-Retry #$retryCount in 2.5s waiting for stream socket ($currentStreamUrl)")
            retryHandler.removeCallbacksAndMessages(null)
            retryHandler.postDelayed({ onReconnect(pos) }, 2500)
            return
        }

        if (retryCount < maxRetries) {
            retryCount++
            Toast.makeText(context, "⚠️ Verbindungsversuch (${retryCount}/${maxRetries})...", Toast.LENGTH_SHORT).show()
            loadingIndicator.visibility = View.VISIBLE

            if (retryCount == 3) {
                var modifiedUrl = currentStreamUrl
                if (currentStreamUrl.endsWith(".mp4")) {
                    modifiedUrl = currentStreamUrl.replace(".mp4", ".mkv")
                } else if (currentStreamUrl.endsWith(".mkv")) {
                    modifiedUrl = currentStreamUrl.replace(".mkv", ".ts")
                }
                if (modifiedUrl != currentStreamUrl) {
                    onUrlAdjusted(modifiedUrl)
                }
            }

            retryHandler.removeCallbacksAndMessages(null)
            retryHandler.postDelayed({ onReconnect(pos) }, 1500)
        } else {
            hideRetryBanner()
            Toast.makeText(context, "Wiedergabefehler: ${error.message} (Server antwortet nicht)", Toast.LENGTH_LONG).show()
            loadingIndicator.visibility = View.GONE
        }
    }

    fun cancelAll() {
        retryHandler.removeCallbacksAndMessages(null)
        hideRetryBanner()
    }
}
