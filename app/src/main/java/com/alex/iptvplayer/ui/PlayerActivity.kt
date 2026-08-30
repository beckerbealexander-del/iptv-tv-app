package com.alex.iptvplayer.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.alex.iptvplayer.data.LiveStream
import com.alex.iptvplayer.data.XtreamClient
import com.alex.iptvplayer.databinding.ActivityPlayerBinding
import com.alex.iptvplayer.util.PlayerUtils

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var exoPlayer: ExoPlayer? = null
    private lateinit var client: XtreamClient

    private var streamList: List<LiveStream> = emptyList()
    private var currentIndex: Int = -1
    private val hideHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        client = XtreamClient(this)

        val streamUrl = intent.getStringExtra("STREAM_URL") ?: ""
        val streamName = intent.getStringExtra("STREAM_NAME") ?: "Stream"
        currentIndex = intent.getIntExtra("CURRENT_INDEX", -1)

        @Suppress("UNCHECKED_CAST")
        streamList = (intent.getSerializableExtra("STREAM_LIST") as? ArrayList<LiveStream>) ?: emptyList()

        setupPlayer(streamUrl, streamName)
    }

    private fun setupPlayer(url: String, name: String) {
        showChannelInfo(name)

        exoPlayer = PlayerUtils.createExoPlayer(this).apply {
            binding.playerView.player = this

            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    binding.playerLoading.visibility =
                        if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
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
    }

    private fun showChannelInfo(name: String) {
        binding.txtPlayerChannelName.text = name
        binding.overlayChannelInfo.visibility = View.VISIBLE

        hideHandler.removeCallbacksAndMessages(null)
        hideHandler.postDelayed({
            binding.overlayChannelInfo.visibility = View.GONE
        }, 4000)
    }

    // --- D-Pad Steuerung: Zapping (Hoch/Runter) und Pause/Play (OK) ---
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                zapNextChannel()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                zapPreviousChannel()
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (binding.overlayChannelInfo.visibility == View.VISIBLE) {
                    binding.overlayChannelInfo.visibility = View.GONE
                } else {
                    showChannelInfo(binding.txtPlayerChannelName.text.toString())
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
        showChannelInfo(stream.name)
        val url = client.getLiveStreamUrl(stream.streamId)
        val mediaItem = MediaItem.fromUri(url)
        exoPlayer?.setMediaItem(mediaItem)
        exoPlayer?.prepare()
        exoPlayer?.playWhenReady = true
    }

    override fun onDestroy() {
        super.onDestroy()
        hideHandler.removeCallbacksAndMessages(null)
        exoPlayer?.release()
        exoPlayer = null
    }
}
