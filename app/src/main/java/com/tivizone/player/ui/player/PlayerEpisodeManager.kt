package com.tivizone.player.ui.player

import android.view.View
import android.widget.Button
import com.tivizone.player.data.EpisodeItem
import com.tivizone.player.data.XtreamClient

class PlayerEpisodeManager(
    private val client: XtreamClient,
    private val btnPrevEpisode: Button,
    private val btnNextEpisode: Button,
    private val onEpisodeSelected: (streamUrl: String, title: String, streamId: Int, season: Int, episodeNum: Int, posterUrl: String?) -> Unit,
    private val onShowOsd: () -> Unit
) {
    var episodeList: List<EpisodeItem> = emptyList()
        private set
    var currentEpisodeIndex: Int = -1
        private set
    var currentType: String = "VOD"
        private set
    private var baseSeriesTitle: String = ""
    private var fallbackPosterUrl: String? = null

    fun setup(
        type: String,
        episodes: List<EpisodeItem>,
        initialIndex: Int,
        streamName: String,
        posterUrl: String?
    ) {
        currentType = type
        episodeList = episodes
        currentEpisodeIndex = initialIndex
        baseSeriesTitle = streamName.substringBefore(" - S")
        fallbackPosterUrl = posterUrl

        if (currentType == "SERIES" && episodeList.isNotEmpty()) {
            updateEpisodeButtons()
            btnPrevEpisode.setOnClickListener { playPreviousEpisode() }
            btnNextEpisode.setOnClickListener { playNextEpisode() }
        } else {
            btnPrevEpisode.visibility = View.GONE
            btnNextEpisode.visibility = View.GONE
        }
    }

    fun updateEpisodeButtons() {
        if (currentType != "SERIES" || episodeList.isEmpty()) {
            btnPrevEpisode.visibility = View.GONE
            btnNextEpisode.visibility = View.GONE
            return
        }
        btnPrevEpisode.visibility = if (currentEpisodeIndex > 0) View.VISIBLE else View.GONE
        btnNextEpisode.visibility = if (currentEpisodeIndex < episodeList.size - 1) View.VISIBLE else View.GONE
    }

    fun playNextEpisode(): Boolean {
        if (currentEpisodeIndex < episodeList.size - 1) {
            playEpisodeAtIndex(currentEpisodeIndex + 1)
            return true
        }
        return false
    }

    fun playPreviousEpisode(): Boolean {
        if (currentEpisodeIndex > 0) {
            playEpisodeAtIndex(currentEpisodeIndex - 1)
            return true
        }
        return false
    }

    fun playEpisodeAtIndex(index: Int) {
        if (index < 0 || index >= episodeList.size) return
        currentEpisodeIndex = index
        val ep = episodeList[index]
        val title = "$baseSeriesTitle - S${ep.season}E${ep.episodeNum} ${ep.title}"
        val streamUrl = client.getSeriesStreamUrl(ep.id, ep.containerExtension ?: "mp4")
        val streamId = ep.id.toIntOrNull() ?: -1
        val poster = ep.info?.movieImage ?: fallbackPosterUrl

        updateEpisodeButtons()
        onShowOsd()
        onEpisodeSelected(streamUrl, title, streamId, ep.season, ep.episodeNum, poster)
    }

    fun hasNextEpisode(): Boolean {
        return currentType == "SERIES" && currentEpisodeIndex < episodeList.size - 1
    }
}
