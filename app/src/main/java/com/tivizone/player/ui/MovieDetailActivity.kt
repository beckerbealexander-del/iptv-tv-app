package com.tivizone.player.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.tivizone.player.R
import com.tivizone.player.data.HistoryManager
import com.tivizone.player.data.VodDetailsInfo
import com.tivizone.player.data.VodStream
import com.tivizone.player.data.XtreamClient
import com.tivizone.player.databinding.ActivityMovieDetailBinding
import com.tivizone.player.util.TrailerUtils
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import kotlinx.coroutines.launch
import java.util.Locale

class MovieDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMovieDetailBinding
    private lateinit var client: XtreamClient
    private lateinit var historyManager: HistoryManager

    private var streamId: Int = -1
    private var streamName: String = ""
    private var posterUrl: String? = null
    private var containerExt: String = "mp4"
    private var movieDetails: VodDetailsInfo? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMovieDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        client = XtreamClient(this)
        historyManager = HistoryManager(this)

        @Suppress("DEPRECATION")
        val vodStream = intent.getSerializableExtra("VOD_STREAM") as? VodStream

        streamId = intent.getIntExtra("STREAM_ID", vodStream?.streamId ?: -1)
        streamName = intent.getStringExtra("STREAM_NAME") ?: vodStream?.name ?: ""
        posterUrl = intent.getStringExtra("POSTER_URL") ?: vodStream?.streamIcon
        containerExt = intent.getStringExtra("CONTAINER_EXT") ?: vodStream?.containerExtension ?: "mp4"

        if (streamId <= 0) {
            Toast.makeText(this, "Ungültiger Film", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        displayInitialInfo(vodStream)
        setupButtons()
        loadFullMovieDetails()
    }

    override fun onResume() {
        super.onResume()
        updatePlayButtonProgress()
        historyManager.syncWithCloud(client.username) {
            runOnUiThread { updatePlayButtonProgress() }
        }
    }

    private fun displayInitialInfo(vodStream: VodStream?) {
        binding.txtMovieTitle.text = streamName

        if (!posterUrl.isNullOrEmpty()) {
            Glide.with(this)
                .load(posterUrl)
                .override(320, 420)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(R.drawable.tv_banner)
                .into(binding.imgMovieCover)
        }

        if (vodStream != null && !vodStream.rating.isNullOrEmpty() && vodStream.rating != "0") {
            binding.txtMovieRating.text = "★ ${vodStream.rating}"
            binding.txtMovieRating.visibility = View.VISIBLE
        } else {
            binding.txtMovieRating.visibility = View.GONE
        }

        binding.txtMovieYear.visibility = View.GONE
        binding.txtMovieDuration.visibility = View.GONE
        binding.txtMovieGenre.visibility = View.GONE
    }

    private fun updatePlayButtonProgress() {
        val history = historyManager.getHistory()
        val item = history.firstOrNull { it.id == streamId.toString() || (it.streamId == streamId && it.type == "VOD") }
        if (item != null && item.progressPercent >= 90) {
            binding.btnPlayMovie.text = "▶ Film erneut abspielen"
        } else if (item != null && item.positionMs > 5000L) {
            binding.btnPlayMovie.text = "▶ Fortsetzen bei ${formatTime(item.positionMs)}"
        } else {
            binding.btnPlayMovie.text = "▶ Film abspielen"
        }
    }

    private fun setupButtons() {
        updatePlayButtonProgress()
        binding.btnPlayMovie.requestFocus()

        binding.btnPlayMovie.setOnClickListener {
            val intent = Intent(this, PlayerActivity::class.java).apply {
                putExtra("STREAM_URL", client.getVodStreamUrl(streamId, containerExt))
                putExtra("STREAM_NAME", streamName)
                putExtra("POSTER_URL", posterUrl)
                putExtra("STREAM_ID", streamId)
                putExtra("STREAM_TYPE", "VOD")
            }
            startActivity(intent)
        }

        binding.btnMovieTrailer.setOnClickListener {
            val trailer = movieDetails?.youtubeTrailer
            TrailerUtils.openTrailer(this, trailer, streamName)
        }

        binding.btnMovieBack.setOnClickListener {
            finish()
        }
    }

    private fun loadFullMovieDetails() {
        // 1. Aus Cache prüfen
        val cached = client.loadCachedVodInfo(streamId)
        if (cached?.info != null) {
            applyDetails(cached.info)
            return
        }

        binding.progressMovieDetail.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val resp = client.getVodInfo(streamId)
                binding.progressMovieDetail.visibility = View.GONE
                if (resp.info != null) {
                    applyDetails(resp.info)
                }
            } catch (e: Exception) {
                binding.progressMovieDetail.visibility = View.GONE
            }
        }
    }

    private fun applyDetails(info: VodDetailsInfo) {
        movieDetails = info

        if (!info.name.isNullOrEmpty()) {
            binding.txtMovieTitle.text = info.name
        }

        if (!info.rating.isNullOrEmpty() && info.rating != "0") {
            binding.txtMovieRating.text = "★ ${info.rating}"
            binding.txtMovieRating.visibility = View.VISIBLE
        }

        val year = info.releaseDate?.take(4)
        if (!year.isNullOrEmpty()) {
            binding.txtMovieYear.text = year
            binding.txtMovieYear.visibility = View.VISIBLE
        }

        val durationFormatted = when {
            !info.duration.isNullOrEmpty() -> info.duration
            info.durationSecs != null && info.durationSecs > 0 -> {
                val m = info.durationSecs / 60
                val h = m / 60
                val remM = m % 60
                if (h > 0) "${h}h ${remM}m" else "${remM}m"
            }
            else -> null
        }
        if (!durationFormatted.isNullOrEmpty()) {
            binding.txtMovieDuration.text = durationFormatted
            binding.txtMovieDuration.visibility = View.VISIBLE
        }

        if (!info.genre.isNullOrEmpty()) {
            binding.txtMovieGenre.text = info.genre
            binding.txtMovieGenre.visibility = View.VISIBLE
        }

        if (!info.plot.isNullOrEmpty()) {
            binding.txtMoviePlot.text = info.plot
        } else {
            binding.txtMoviePlot.text = "Keine Filmbeschreibung verfügbar."
        }

        if (!info.cast.isNullOrEmpty()) {
            binding.txtMovieCast.text = "Besetzung: ${info.cast}"
            binding.txtMovieCast.visibility = View.VISIBLE
        }

        if (!info.director.isNullOrEmpty()) {
            binding.txtMovieDirector.text = "Regie: ${info.director}"
            binding.txtMovieDirector.visibility = View.VISIBLE
        }

        if (!info.movieImage.isNullOrEmpty() && posterUrl.isNullOrEmpty()) {
            Glide.with(this)
                .load(info.movieImage)
                .override(320, 420)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(R.drawable.tv_banner)
                .into(binding.imgMovieCover)
        }
    }

    private fun formatTime(ms: Long): String {
        val totalSecs = (ms / 1000).coerceAtLeast(0)
        val hours = totalSecs / 3600
        val minutes = (totalSecs % 3600) / 60
        val seconds = totalSecs % 60
        return if (hours > 0) String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
        else String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }
}