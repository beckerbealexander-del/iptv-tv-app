package com.tivizone.player.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tivizone.player.R
import com.tivizone.player.data.EpisodeItem
import com.tivizone.player.data.HistoryItem
import com.tivizone.player.data.HistoryManager
import com.tivizone.player.data.SeasonItem
import com.tivizone.player.data.SeriesInfoResponse
import com.tivizone.player.data.SeriesItem
import com.tivizone.player.data.XtreamClient
import com.tivizone.player.databinding.ActivitySeriesDetailBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import kotlinx.coroutines.launch

class SeriesDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySeriesDetailBinding
    private lateinit var client: XtreamClient
    private lateinit var historyManager: HistoryManager

    private var seriesItem: SeriesItem? = null
    private var seriesInfo: SeriesInfoResponse? = null
    private var seriesId: Int = -1
    private var targetSeason: Int = -1
    private var targetEpisode: Int = -1
    private var episodeStreamId: Int = -1
    private var extraTitle: String? = null
    private var autoPlay: Boolean = false
    private var hasAutoPlayed: Boolean = false
    private var currentSeasonIndex: Int = 0
    private var currentSeasonNum: Int = 1
    private var historyList: List<HistoryItem> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySeriesDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        client = XtreamClient(this)
        historyManager = HistoryManager(this)

        @Suppress("DEPRECATION")
        seriesItem = intent.getSerializableExtra("SERIES_ITEM") as? SeriesItem
        seriesId = intent.getIntExtra("SERIES_ID", seriesItem?.seriesId ?: -1)
        targetSeason = intent.getIntExtra("TARGET_SEASON", -1)
        targetEpisode = intent.getIntExtra("TARGET_EPISODE", -1)
        episodeStreamId = intent.getIntExtra("EPISODE_STREAM_ID", -1)
        autoPlay = intent.getBooleanExtra("AUTO_PLAY", false)

        extraTitle = intent.getStringExtra("SERIES_NAME")
        val extraPoster = intent.getStringExtra("SERIES_POSTER")

        if (!extraPoster.isNullOrEmpty()) {
            Glide.with(this)
                .load(extraPoster)
                .override(320, 420)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(R.drawable.tv_banner)
                .into(binding.imgDetailSeriesCover)
        }

        binding.recyclerSeasons.apply {
            layoutManager = LinearLayoutManager(this@SeriesDetailActivity, LinearLayoutManager.HORIZONTAL, false)
            setHasFixedSize(true)
        }

        binding.recyclerEpisodes.apply {
            layoutManager = LinearLayoutManager(this@SeriesDetailActivity)
            setHasFixedSize(true)
            setItemViewCacheSize(40)
        }

        binding.btnDetailSeriesTrailer.setOnClickListener {
            val trailer = seriesInfo?.info?.youtubeTrailer
            val title = seriesItem?.name ?: seriesInfo?.info?.name ?: extraTitle ?: ""
            com.tivizone.player.util.TrailerUtils.openTrailer(this, trailer, title)
        }

        if (seriesItem != null) {
            displayInitialInfo()
            loadFullSeriesInfo()
        } else if (seriesId > 0) {
            if (!extraTitle.isNullOrEmpty()) binding.txtDetailSeriesTitle.text = extraTitle
            loadFullSeriesInfo()
        } else if (!extraTitle.isNullOrEmpty()) {
            binding.txtDetailSeriesTitle.text = extraTitle
            resolveSeriesByNameAndLoad(extraTitle!!)
        } else {
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        historyList = historyManager.getHistory()
        binding.recyclerEpisodes.adapter?.notifyDataSetChanged()
        updateFocusToLastWatchedEpisode()

        historyManager.syncWithCloud(client.username) {
            runOnUiThread {
                historyList = historyManager.getHistory()
                binding.recyclerEpisodes.adapter?.notifyDataSetChanged()
                updateFocusToLastWatchedEpisode()
            }
        }
    }

    private fun updateFocusToLastWatchedEpisode() {
        val sName = seriesItem?.name ?: seriesInfo?.info?.name ?: ""
        val cleanName = com.tivizone.player.util.SeriesUtils.cleanSeriesTitle(sName).lowercase()
        val effectiveSeriesId = seriesItem?.seriesId ?: seriesId

        val match = historyList.firstOrNull { 
            (effectiveSeriesId > 0 && it.seriesId == effectiveSeriesId) || 
            (cleanName.isNotEmpty() && com.tivizone.player.util.SeriesUtils.cleanSeriesTitle(it.title).lowercase() == cleanName)
        }
        if (match != null && seriesInfo != null) {
            // Wenn die gefundene Folge in einer anderen Staffel liegt, Staffel wechseln!
            if (match.season > 0 && match.season != currentSeasonNum) {
                val epMap = seriesInfo?.episodes ?: emptyMap()
                val validSeasonNums = epMap.filter { it.value.isNotEmpty() }.keys.mapNotNull { it.toIntOrNull() }.sorted()
                val sIdx = validSeasonNums.indexOf(match.season)
                if (sIdx >= 0) {
                    updateSeasonTabSelection(sIdx)
                    loadEpisodesForSeason(match.season, requestFocusOnEpisode = true)
                    return
                }
            }

            val epList = seriesInfo?.episodes?.get(currentSeasonNum.toString()) ?: emptyList()
            val epIdx = epList.indexOfFirst { it.season == match.season && it.episodeNum == match.episodeNum }
            if (epIdx >= 0) {
                binding.recyclerEpisodes.scrollToPosition(epIdx)
                binding.recyclerEpisodes.post {
                    val holder = binding.recyclerEpisodes.findViewHolderForAdapterPosition(epIdx)
                    holder?.itemView?.requestFocus()
                }
            }
        }
    }

    private fun resolveSeriesByNameAndLoad(title: String) {
        binding.progressEpisodes.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val all = client.getAllSeries()
                val match = com.tivizone.player.util.SeriesUtils.findMatchingSeries(title, all)

                if (match != null) {
                    seriesItem = match
                    seriesId = match.seriesId
                    displayInitialInfo()
                    loadFullSeriesInfo()
                } else {
                    binding.progressEpisodes.visibility = View.GONE
                    Toast.makeText(this@SeriesDetailActivity, "Serie nicht gefunden: $title", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                binding.progressEpisodes.visibility = View.GONE
                Toast.makeText(this@SeriesDetailActivity, "Fehler: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun displayInitialInfo() {
        val s = seriesItem ?: return
        binding.txtDetailSeriesTitle.text = s.name
        if (!s.plot.isNullOrEmpty()) {
            binding.txtDetailSeriesPlot.text = s.plot
        }
        if (!s.rating.isNullOrEmpty()) {
            binding.txtDetailSeriesRating.text = "★ ${s.rating}"
        }

        if (!s.cover.isNullOrEmpty()) {
            Glide.with(this)
                .load(s.cover)
                .override(320, 420)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(R.drawable.tv_banner)
                .into(binding.imgDetailSeriesCover)
        }
    }

    private fun loadFullSeriesInfo() {
        val targetId = if (seriesItem != null) seriesItem!!.seriesId else seriesId
        if (targetId <= 0) {
            val titleToResolve = extraTitle ?: binding.txtDetailSeriesTitle.text?.toString() ?: ""
            if (titleToResolve.isNotBlank() && seriesItem == null) {
                resolveSeriesByNameAndLoad(titleToResolve)
            }
            return
        }

        // 1. Sofort aus lokalem Cache laden falls vorhanden (<10ms)
        val cached = client.loadCachedSeriesInfo(targetId)
        val hasCachedData = cached != null && (!cached.seasons.isNullOrEmpty() || !cached.episodes.isNullOrEmpty())
        if (hasCachedData) {
            seriesInfo = cached
            applySeriesInfoToUi(cached!!, requestFocusOnEpisodes = false)
        } else {
            binding.progressEpisodes.visibility = View.VISIBLE
        }

        lifecycleScope.launch {
            try {
                historyList = historyManager.getHistory()
                val info = client.getSeriesInfo(targetId)
                binding.progressEpisodes.visibility = View.GONE

                val hasData = !info.seasons.isNullOrEmpty() || !info.episodes.isNullOrEmpty()
                if (!hasData) {
                    // Falls targetId ungültig war (z.B. alte Historie mit Episoden-ID statt Serien-ID)
                    val titleToResolve = extraTitle ?: binding.txtDetailSeriesTitle.text?.toString() ?: ""
                    if (titleToResolve.isNotBlank() && seriesItem == null) {
                        resolveSeriesByNameAndLoad(titleToResolve)
                        return@launch
                    }
                }

                val currentInfo = seriesInfo
                val needsUiUpdate = (currentInfo == null ||
                        binding.recyclerSeasons.adapter == null ||
                        currentInfo.episodes?.size != info.episodes?.size)

                seriesInfo = info
                if (needsUiUpdate && hasData) {
                    applySeriesInfoToUi(info, requestFocusOnEpisodes = !hasCachedData)
                }
            } catch (e: Exception) {
                binding.progressEpisodes.visibility = View.GONE
                val titleToResolve = extraTitle ?: binding.txtDetailSeriesTitle.text?.toString() ?: ""
                if (titleToResolve.isNotBlank() && seriesItem == null) {
                    resolveSeriesByNameAndLoad(titleToResolve)
                    return@launch
                }
                if (seriesInfo == null) {
                    Toast.makeText(this@SeriesDetailActivity, "Fehler: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateSeasonTabSelection(selectedIdx: Int) {
        currentSeasonIndex = selectedIdx
        for (i in 0 until binding.recyclerSeasons.childCount) {
            val child = binding.recyclerSeasons.getChildAt(i)
            val pos = binding.recyclerSeasons.getChildAdapterPosition(child)
            if (pos != RecyclerView.NO_POSITION) {
                child.isSelected = (pos == selectedIdx)
            }
        }
    }

    private fun applySeriesInfoToUi(info: SeriesInfoResponse, requestFocusOnEpisodes: Boolean) {
        if (info.info != null) {
            if (!info.info.name.isNullOrEmpty()) binding.txtDetailSeriesTitle.text = info.info.name
            if (!info.info.plot.isNullOrEmpty()) binding.txtDetailSeriesPlot.text = info.info.plot
            if (!info.info.genre.isNullOrEmpty()) binding.txtDetailSeriesGenre.text = info.info.genre
            if (!info.info.rating.isNullOrEmpty()) binding.txtDetailSeriesRating.text = "★ ${info.info.rating}"

            if (!info.info.cover.isNullOrEmpty()) {
                Glide.with(this@SeriesDetailActivity)
                    .load(info.info.cover)
                    .override(320, 420)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(R.drawable.tv_banner)
                    .into(binding.imgDetailSeriesCover)
            }
        }

        // Alle Staffeln bestimmen, die tatsächlich Episoden enthalten (behebt leere seasons-Arrays wie bei Shameless)
        val epMap = info.episodes ?: emptyMap()
        val validSeasonNums = epMap.filter { it.value.isNotEmpty() }
            .keys
            .mapNotNull { it.toIntOrNull() }
            .sorted()

        val serverSeasonsMap = (info.seasons ?: emptyList()).associateBy { it.seasonNumber }

        val seasons: List<SeasonItem> = if (validSeasonNums.isNotEmpty()) {
            validSeasonNums
                .filter { sNum ->
                    // Staffel 0 nur anzeigen, wenn sie nicht "Extras" mit 0 Folgen ist
                    if (sNum == 0) {
                        val s0Name = serverSeasonsMap[0]?.name?.trim() ?: ""
                        !s0Name.equals("Extras", ignoreCase = true) || epMap["0"]?.isNotEmpty() == true
                    } else true
                }
                .map { sNum ->
                    val existing = serverSeasonsMap[sNum]
                    val rawName = existing?.name?.trim()
                    val displayName = when {
                        rawName.isNullOrEmpty() || rawName.equals("null", ignoreCase = true) -> "Staffel $sNum"
                        sNum == 0 && rawName.isBlank() -> "Specials"
                        else -> rawName
                    }
                    SeasonItem(
                        seasonNumber = sNum,
                        name = displayName,
                        episodeCount = epMap[sNum.toString()]?.size?.toString() ?: existing?.episodeCount
                    )
                }
        } else {
            (info.seasons ?: emptyList()).filter { s ->
                val epCount = epMap[s.seasonNumber.toString()]?.size ?: 0
                epCount > 0
            }
        }

        val initialSeasonNum = if (targetSeason > 0 && seasons.any { it.seasonNumber == targetSeason }) {
            targetSeason
        } else {
            val effectiveSeriesId = seriesItem?.seriesId ?: seriesId
            val sName = seriesItem?.name ?: info.info?.name ?: ""
            val cleanName = com.tivizone.player.util.SeriesUtils.cleanSeriesTitle(sName).lowercase()
            val lastWatched = historyList.firstOrNull { 
                (effectiveSeriesId > 0 && it.seriesId == effectiveSeriesId) || 
                (cleanName.isNotEmpty() && com.tivizone.player.util.SeriesUtils.cleanSeriesTitle(it.title).lowercase() == cleanName)
            }
            if (lastWatched != null && lastWatched.season > 0 && seasons.any { it.seasonNumber == lastWatched.season }) {
                lastWatched.season
            } else {
                seasons.firstOrNull()?.seasonNumber ?: 1
            }
        }
        currentSeasonNum = initialSeasonNum
        currentSeasonIndex = seasons.indexOfFirst { it.seasonNumber == initialSeasonNum }.coerceAtLeast(0)

        if (seasons.isNotEmpty()) {
            val seasonAdapter = SeasonAdapter(seasons) { season, idx ->
                if (idx == currentSeasonIndex && binding.recyclerEpisodes.adapter != null && currentSeasonNum == season.seasonNumber) {
                    return@SeasonAdapter
                }
                updateSeasonTabSelection(idx)
                loadEpisodesForSeason(season.seasonNumber, requestFocusOnEpisode = false)
            }
            binding.recyclerSeasons.adapter = seasonAdapter
            updateSeasonTabSelection(currentSeasonIndex)
            loadEpisodesForSeason(initialSeasonNum, requestFocusOnEpisode = requestFocusOnEpisodes)
        } else {
            val all = mutableListOf<EpisodeItem>()
            epMap.values.forEach { all.addAll(it) }
            displayEpisodes(all, requestFocusOnEpisode = requestFocusOnEpisodes)
        }
    }

    private fun loadEpisodesForSeason(seasonNum: Int, requestFocusOnEpisode: Boolean) {
        currentSeasonNum = seasonNum
        val epList = seriesInfo?.episodes?.get(seasonNum.toString()) ?: emptyList()
        displayEpisodes(epList, requestFocusOnEpisode)
    }

    private fun displayEpisodes(epList: List<EpisodeItem>, requestFocusOnEpisode: Boolean) {
        binding.recyclerEpisodes.adapter = EpisodeAdapter(epList)

        var targetIdx = if (targetEpisode > 0) {
            epList.indexOfFirst { it.episodeNum == targetEpisode }
        } else {
            val effectiveSeriesId = seriesItem?.seriesId ?: seriesId
            val sName = seriesItem?.name ?: seriesInfo?.info?.name ?: ""
            val cleanName = com.tivizone.player.util.SeriesUtils.cleanSeriesTitle(sName).lowercase()
            val lastWatched = historyList.firstOrNull { 
                (effectiveSeriesId > 0 && it.seriesId == effectiveSeriesId) || 
                (cleanName.isNotEmpty() && com.tivizone.player.util.SeriesUtils.cleanSeriesTitle(it.title).lowercase() == cleanName)
            }
            if (lastWatched != null && lastWatched.season == currentSeasonNum) {
                epList.indexOfFirst { it.episodeNum == lastWatched.episodeNum }
            } else -1
        }

        if (targetIdx < 0 && episodeStreamId > 0) {
            targetIdx = epList.indexOfFirst { it.id == episodeStreamId.toString() }
        }
        if (targetIdx < 0) targetIdx = 0

        if (epList.isNotEmpty()) {
            binding.recyclerEpisodes.scrollToPosition(targetIdx)

            if (requestFocusOnEpisode) {
                if (targetEpisode > 0 || episodeStreamId > 0) {
                    binding.recyclerEpisodes.post {
                        val holder = binding.recyclerEpisodes.findViewHolderForAdapterPosition(targetIdx)
                        holder?.itemView?.requestFocus()
                    }
                } else {
                    focusCurrentSeasonTab()
                }
            }

            if (autoPlay && !hasAutoPlayed) {
                hasAutoPlayed = true
                playEpisode(epList[targetIdx], targetIdx, epList)
            }
        }
    }

    private fun playEpisode(ep: EpisodeItem, index: Int, list: List<EpisodeItem>) {
        val sName = seriesItem?.name ?: seriesInfo?.info?.name ?: "Serie"
        val effectiveSeriesId = seriesItem?.seriesId ?: seriesId
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("STREAM_URL", client.getSeriesStreamUrl(ep.id, ep.containerExtension ?: "mp4"))
            putExtra("STREAM_NAME", "$sName - S${ep.season}E${ep.episodeNum} ${ep.title}")
            putExtra("POSTER_URL", ep.info?.movieImage ?: seriesItem?.cover ?: seriesInfo?.info?.cover)
            putExtra("STREAM_TYPE", "SERIES")
            putExtra("STREAM_ID", ep.id.toIntOrNull() ?: -1)
            putExtra("SERIES_ID", effectiveSeriesId)
            putExtra("SEASON_NUM", ep.season)
            putExtra("EPISODE_NUM", ep.episodeNum)
            putExtra("EPISODE_INDEX", index)
            putExtra("EPISODE_LIST", ArrayList(list))
        }
        startActivity(intent)
    }

    private fun focusCurrentSeasonTab() {
        binding.recyclerSeasons.scrollToPosition(currentSeasonIndex)
        binding.recyclerSeasons.post {
            val seasonHolder = binding.recyclerSeasons.findViewHolderForAdapterPosition(currentSeasonIndex)
            seasonHolder?.itemView?.requestFocus() ?: binding.recyclerSeasons.requestFocus()
        }
    }

    // --- Fokus-Navigation: D-Pad Links öffnet die Beschreibung zum Durchscrollen, D-Pad Rechts kehrt zurück ---
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val focused = currentFocus
            val isPlot = (focused == binding.scrollDetailSeriesPlot || focused == binding.txtDetailSeriesPlot)
            val isEpisode = isViewInRecyclerView(focused, binding.recyclerEpisodes)
            val isSeason = isViewInRecyclerView(focused, binding.recyclerSeasons)

            if (isPlot) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        binding.scrollDetailSeriesPlot.smoothScrollBy(0, -120)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        binding.scrollDetailSeriesPlot.smoothScrollBy(0, 120)
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        focusCurrentSeasonTab()
                        return true
                    }
                }
            } else if (isEpisode) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        binding.scrollDetailSeriesPlot.requestFocus()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        val epPos = getFocusedAdapterPosition(focused, binding.recyclerEpisodes)
                        if (epPos == 0) {
                            focusCurrentSeasonTab()
                            return true
                        }
                    }
                }
            } else if (isSeason) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        val seasonPos = getFocusedAdapterPosition(focused, binding.recyclerSeasons)
                        if (seasonPos == 0) {
                            binding.scrollDetailSeriesPlot.requestFocus()
                            return true
                        }
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isViewInRecyclerView(view: View?, rv: RecyclerView): Boolean {
        var cur = view
        while (cur != null) {
            if (cur == rv) return true
            val p = cur.parent
            cur = p as? View
        }
        return false
    }

    private fun getFocusedAdapterPosition(view: View?, rv: RecyclerView): Int {
        var cur = view
        while (cur != null && cur != rv) {
            val p = cur.parent
            if (p == rv) return rv.getChildAdapterPosition(cur)
            cur = p as? View
        }
        return -1
    }

    inner class SeasonAdapter(
        private val seasons: List<SeasonItem>,
        private val onSelect: (SeasonItem, Int) -> Unit
    ) : RecyclerView.Adapter<SeasonAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val txtName: TextView = view.findViewById(R.id.txtSeasonName)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_season_tab, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val season = seasons[position]
            holder.txtName.text = season.name ?: "Staffel ${season.seasonNumber}"
            holder.itemView.isSelected = (position == currentSeasonIndex)

            holder.itemView.setOnClickListener {
                if (position != currentSeasonIndex) {
                    onSelect(season, position)
                }
            }
            holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus && position != currentSeasonIndex) {
                    onSelect(season, position)
                }
            }

            holder.itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && position == seasons.size - 1) {
                        return@setOnKeyListener true
                    }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && position == 0) {
                        return@setOnKeyListener true
                    }
                    if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        val firstEpHolder = binding.recyclerEpisodes.findViewHolderForAdapterPosition(0)
                        firstEpHolder?.itemView?.requestFocus() ?: binding.recyclerEpisodes.requestFocus()
                        return@setOnKeyListener true
                    }
                }
                false
            }
        }

        override fun getItemCount() = seasons.size
    }

    inner class EpisodeAdapter(
        private val episodes: List<EpisodeItem>
    ) : RecyclerView.Adapter<EpisodeAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val txtTitle: TextView = view.findViewById(R.id.txtEpisodeNumAndTitle)
            val txtDuration: TextView = view.findViewById(R.id.txtEpisodeDuration)
            val txtPlayIcon: TextView = view.findViewById(R.id.txtEpisodePlayIcon)
            val imgThumb: ImageView = view.findViewById(R.id.imgEpisodeThumb)
            val progressBar: ProgressBar = view.findViewById(R.id.progressEpisodeWatched)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_episode_card, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val ep = episodes[position]
            holder.txtTitle.text = "Folge ${ep.episodeNum}: ${ep.title}"
            holder.txtDuration.text = ep.info?.duration ?: ""

            // Gesehen-Fortschrittsbalken & Indikator
            val epIdInt = ep.id.toIntOrNull() ?: -1
            val targetSeriesId = seriesItem?.seriesId ?: seriesId
            val historyEntry = historyList.firstOrNull { 
                (epIdInt > 0 && it.streamId == epIdInt) || 
                (ep.id.isNotEmpty() && it.id == ep.id) ||
                (it.season == ep.season && it.episodeNum == ep.episodeNum && (
                    (targetSeriesId > 0 && it.seriesId == targetSeriesId) ||
                    it.streamUrl.contains("/${ep.id}.") || 
                    it.title.contains("S${ep.season}E${ep.episodeNum}")
                ))
            }

            if (historyEntry != null && historyEntry.progressPercent > 0) {
                holder.progressBar.visibility = View.VISIBLE
                val pct = if (historyEntry.progressPercent >= 85) 100 else historyEntry.progressPercent
                holder.progressBar.progress = pct
                if (pct == 100) {
                    holder.txtPlayIcon.text = "✓"
                    holder.txtPlayIcon.setTextColor(resources.getColor(R.color.accent_gold, null))
                } else {
                    holder.txtPlayIcon.text = "▶"
                    holder.txtPlayIcon.setTextColor(resources.getColor(R.color.netflix_red, null))
                }
            } else {
                holder.progressBar.visibility = View.GONE
                holder.txtPlayIcon.text = "▶"
                holder.txtPlayIcon.setTextColor(resources.getColor(R.color.netflix_red, null))
            }

            val imgUrl = ep.info?.movieImage ?: seriesInfo?.info?.cover ?: seriesItem?.cover
            if (!imgUrl.isNullOrEmpty()) {
                Glide.with(holder.itemView)
                    .load(imgUrl)
                    .override(110, 70)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(R.drawable.tv_banner)
                    .into(holder.imgThumb)
            } else {
                holder.imgThumb.setImageResource(R.drawable.tv_banner)
            }

            holder.itemView.setOnClickListener { playEpisode(ep, position, episodes) }

            holder.itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_UP && position == 0) {
                        focusCurrentSeasonTab()
                        return@setOnKeyListener true
                    }
                }
                false
            }
        }

        override fun getItemCount() = episodes.size
    }
}
