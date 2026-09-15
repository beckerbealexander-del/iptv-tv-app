package com.alex.iptvplayer.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.alex.iptvplayer.R
import com.alex.iptvplayer.data.EpgProgram
import com.alex.iptvplayer.data.HistoryManager
import com.alex.iptvplayer.data.LiveStream
import com.alex.iptvplayer.data.LiveTvCacheManager
import com.alex.iptvplayer.data.MultiStreamChannel
import com.alex.iptvplayer.data.MultiStreamManager
import com.alex.iptvplayer.data.StreamSource
import com.alex.iptvplayer.data.XtreamClient
import com.alex.iptvplayer.databinding.ActivitySearchBinding
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private const val TYPE_CHANNEL = 1
private const val TYPE_PROGRAM = 2

sealed class SearchResultItem {
    data class ChannelItem(val channel: MultiStreamChannel) : SearchResultItem()
    data class ProgramItem(val program: EpgProgram, val channel: MultiStreamChannel) : SearchResultItem()
}

class SearchActivity : AppCompatActivity() {

    companion object {
        var cachedQuery: String = ""
        var cachedChannels: List<MultiStreamChannel> = emptyList()
        var cachedPrograms: List<Pair<EpgProgram, MultiStreamChannel>> = emptyList()
        var cachedTab: String = "CHANNELS"
        var lastSelectedStreamId: Int? = null
        var isReturningFromPlayer: Boolean = false
        var globalLiveStreamsCache: List<LiveStream> = emptyList()
    }

    private lateinit var binding: ActivitySearchBinding
    private lateinit var historyManager: HistoryManager
    private lateinit var client: XtreamClient
    private lateinit var cacheManager: LiveTvCacheManager

    private var searchType: String = "LIVE"
    private var allCachedChannels: List<MultiStreamChannel> = emptyList()

    private var foundChannels: List<MultiStreamChannel> = emptyList()
    private var foundPrograms: List<Pair<EpgProgram, MultiStreamChannel>> = emptyList()

    private var activeFilterTab: String = "CHANNELS"
    private var searchJob: Job? = null
    private var progressiveEpgJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        client = XtreamClient(this)
        historyManager = HistoryManager(this)
        cacheManager = LiveTvCacheManager(this)
        searchType = intent.getStringExtra("SEARCH_TYPE") ?: "LIVE"

        val title = when (searchType) {
            "VOD" -> "🎬 Filme durchsuchen"
            "SERIES" -> "🍿 Serien durchsuchen"
            else -> "🔍 Live TV Suche (Sender & Live-EPG)"
        }
        binding.txtSearchTitle.text = title

        binding.recyclerSearchHistory.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        binding.recyclerSearchResults.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false)

        setupTabs()
        setupClearButton()

        if (searchType == "LIVE") {
            loadGlobalChannels()
        }

        // Falls Cache existiert (nach Rückkehr oder Recreation): Treffer sofort wiederherstellen!
        if (cachedQuery.isNotEmpty() && (cachedChannels.isNotEmpty() || cachedPrograms.isNotEmpty())) {
            foundChannels = cachedChannels
            foundPrograms = cachedPrograms
            activeFilterTab = cachedTab
            binding.editSearchQuery.setText(cachedQuery)
            binding.editSearchQuery.setSelection(cachedQuery.length)
            binding.btnClearSearch.visibility = View.VISIBLE
            updateTabStyles()
            renderSearchResults()
            binding.editSearchQuery.post {
                hideKeyboard()
            }
            if (isReturningFromPlayer) {
                isReturningFromPlayer = false
                focusSearchResultItem(lastSelectedStreamId)
            } else {
                binding.editSearchQuery.requestFocus()
            }
        } else {
            binding.btnClearSearch.visibility = View.GONE
            loadSearchHistory()
            binding.editSearchQuery.post {
                binding.editSearchQuery.requestFocus()
                hideKeyboard()
            }
        }

        // Tastatur erst auf expliziten Klick auf das Suchfeld öffnen
        binding.editSearchQuery.setOnClickListener {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.editSearchQuery, InputMethodManager.SHOW_IMPLICIT)
        }

        // Live-Suche während der Eingabe (Debounce 250ms)
        binding.editSearchQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                if (isReturningFromPlayer) return
                val query = s?.toString()?.trim() ?: ""
                if (query.isEmpty()) {
                    binding.btnClearSearch.visibility = View.GONE
                    searchJob?.cancel()
                    progressiveEpgJob?.cancel()
                    cachedQuery = ""
                    cachedChannels = emptyList()
                    cachedPrograms = emptyList()
                    foundChannels = emptyList()
                    foundPrograms = emptyList()
                    showHistoryView()
                } else {
                    binding.btnClearSearch.visibility = View.VISIBLE
                    if (query.length < 2) {
                        // Sobald weniger als 2 Zeichen: Suche abbrechen und Verlauf anzeigen!
                        searchJob?.cancel()
                        progressiveEpgJob?.cancel()
                        showHistoryView()
                    } else if (searchType == "LIVE") {
                        if (query == cachedQuery && (foundChannels.isNotEmpty() || foundPrograms.isNotEmpty())) {
                            return
                        }
                        searchJob?.cancel()
                        searchJob = lifecycleScope.launch {
                            delay(250)
                            performDualSearch(query)
                        }
                    }
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        // Tastatur-Enter löst sofortige Suche aus
        binding.editSearchQuery.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                val q = binding.editSearchQuery.text.toString().trim()
                if (q.length >= 2) {
                    if (searchType == "LIVE") {
                        hideKeyboard()
                        performDualSearch(q)
                    } else {
                        submitVodOrSeriesSearch(q)
                    }
                }
                true
            } else false
        }

        // Navigation aus der Suchleiste
        binding.editSearchQuery.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.showSoftInput(binding.editSearchQuery, InputMethodManager.SHOW_IMPLICIT)
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (binding.btnClearSearch.visibility == View.VISIBLE) {
                            binding.btnClearSearch.requestFocus()
                            return@setOnKeyListener true
                        }
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        hideKeyboard()
                        if (binding.layoutTabs.visibility == View.VISIBLE) {
                            if (activeFilterTab == "PROGRAMS") {
                                binding.btnTabPrograms.requestFocus()
                            } else {
                                binding.btnTabChannels.requestFocus()
                            }
                            return@setOnKeyListener true
                        } else if (binding.layoutSearchHistory.visibility == View.VISIBLE) {
                            val holder = binding.recyclerSearchHistory.findViewHolderForAdapterPosition(0)
                            holder?.itemView?.requestFocus() ?: binding.recyclerSearchHistory.requestFocus()
                            return@setOnKeyListener true
                        }
                    }
                }
            }
            false
        }
    }

    override fun onResume() {
        super.onResume()

        historyManager.syncWithCloud(client.username) {
            runOnUiThread {
                if (cachedQuery.isEmpty()) {
                    loadSearchHistory()
                }
            }
        }

        // Wenn aus dem Player zurückgekehrt wird: Suchergebnisse erhalten und Fokus auf das Ergebnis setzen!
        if (isReturningFromPlayer) {
            isReturningFromPlayer = false
            hideKeyboard()

            // Falls im Player gezappt wurde, den zuletzt gespielten Sender ermitteln
            val recent = historyManager.getRecentLiveChannels().firstOrNull()
            if (recent != null) {
                lastSelectedStreamId = recent.streamId
            }

            if (cachedChannels.isNotEmpty() || cachedPrograms.isNotEmpty()) {
                foundChannels = cachedChannels
                foundPrograms = cachedPrograms
                activeFilterTab = cachedTab
                binding.btnClearSearch.visibility = View.VISIBLE
                updateTabStyles()
                renderSearchResults()
                focusSearchResultItem(lastSelectedStreamId)
            }
        }
    }

    private fun setupClearButton() {
        binding.btnClearSearch.setOnClickListener {
            binding.editSearchQuery.setText("")
            searchJob?.cancel()
            progressiveEpgJob?.cancel()
            binding.btnClearSearch.visibility = View.GONE
            cachedQuery = ""
            cachedChannels = emptyList()
            cachedPrograms = emptyList()
            foundChannels = emptyList()
            foundPrograms = emptyList()
            showHistoryView()
            hideKeyboard()
            binding.editSearchQuery.requestFocus()
        }

        binding.btnClearSearch.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        binding.btnClearSearch.performClick()
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        binding.editSearchQuery.requestFocus()
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (binding.layoutTabs.visibility == View.VISIBLE) {
                            if (activeFilterTab == "PROGRAMS") {
                                binding.btnTabPrograms.requestFocus()
                            } else {
                                binding.btnTabChannels.requestFocus()
                            }
                            return@setOnKeyListener true
                        } else if (binding.layoutSearchHistory.visibility == View.VISIBLE) {
                            val holder = binding.recyclerSearchHistory.findViewHolderForAdapterPosition(0)
                            holder?.itemView?.requestFocus() ?: binding.recyclerSearchHistory.requestFocus()
                            return@setOnKeyListener true
                        }
                    }
                }
            }
            false
        }
    }

    // Fokus direkt in die Ergebnisliste auf den entsprechenden Sender/das Programm setzen
    private fun focusSearchResultItem(streamId: Int?) {
        val adapter = binding.recyclerSearchResults.adapter as? SearchResultsAdapter ?: return
        val items = adapter.items
        if (items.isEmpty()) return

        val targetPos = if (streamId != null) {
            val idx = items.indexOfFirst {
                when (it) {
                    is SearchResultItem.ChannelItem -> it.channel.sources.any { s -> s.streamId == streamId } || it.channel.cleanName.equals(cachedQuery, ignoreCase = true)
                    is SearchResultItem.ProgramItem -> it.channel.sources.any { s -> s.streamId == streamId }
                }
            }
            if (idx != -1) idx else 0
        } else 0

        binding.recyclerSearchResults.post {
            (binding.recyclerSearchResults.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(targetPos, 40)
                ?: binding.recyclerSearchResults.scrollToPosition(targetPos)

            binding.recyclerSearchResults.postDelayed({
                val vh = binding.recyclerSearchResults.findViewHolderForAdapterPosition(targetPos)
                vh?.itemView?.requestFocus() ?: binding.recyclerSearchResults.requestFocus()
            }, 80)
        }
    }

    private fun setupTabs() {
        binding.btnTabChannels.setOnClickListener {
            activeFilterTab = "CHANNELS"
            cachedTab = "CHANNELS"
            updateTabStyles()
            renderSearchResults()
        }
        binding.btnTabPrograms.setOnClickListener {
            activeFilterTab = "PROGRAMS"
            cachedTab = "PROGRAMS"
            updateTabStyles()
            renderSearchResults()
        }

        binding.btnTabChannels.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        binding.editSearchQuery.requestFocus()
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        focusFirstSearchResult()
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        binding.btnTabPrograms.requestFocus()
                        return@setOnKeyListener true
                    }
                }
            }
            false
        }

        binding.btnTabPrograms.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        binding.editSearchQuery.requestFocus()
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        focusFirstSearchResult()
                        return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        binding.btnTabChannels.requestFocus()
                        return@setOnKeyListener true
                    }
                }
            }
            false
        }
    }

    private fun updateTabStyles() {
        binding.btnTabChannels.text = "📺 Sender (${foundChannels.size})"
        binding.btnTabPrograms.text = "🔴 Live-Programm (${foundPrograms.size})"
        binding.layoutTabs.visibility = View.VISIBLE
        binding.btnTabChannels.setBackgroundResource(if (activeFilterTab == "CHANNELS") R.drawable.card_focus_border_red else R.drawable.card_focus_selector)
        binding.btnTabPrograms.setBackgroundResource(if (activeFilterTab == "PROGRAMS") R.drawable.card_focus_border_red else R.drawable.card_focus_selector)
    }

    private fun focusFirstSearchResult() {
        if (binding.recyclerSearchResults.visibility == View.VISIBLE) {
            val count = binding.recyclerSearchResults.adapter?.itemCount ?: 0
            if (count > 0) {
                binding.recyclerSearchResults.scrollToPosition(0)
                binding.recyclerSearchResults.post {
                    val holder = binding.recyclerSearchResults.findViewHolderForAdapterPosition(0)
                    holder?.itemView?.requestFocus() ?: binding.recyclerSearchResults.requestFocus()
                }
            }
        }
    }

    private fun loadGlobalChannels() {
        if (allCachedChannels.isNotEmpty()) return

        // 1. In-Memory aus LiveTvActivity
        if (LiveTvActivity.multiStreamCategoriesMap.isNotEmpty()) {
            allCachedChannels = LiveTvActivity.multiStreamCategoriesMap.values.flatten().distinctBy { it.cleanName }
            return
        }

        // 2. Aus Disk-Cache (LiveTvCacheManager)
        lifecycleScope.launch(Dispatchers.IO) {
            val diskBundled = cacheManager.loadBundledChannels()
            if (!diskBundled.isNullOrEmpty()) {
                val list = diskBundled.values.flatten().distinctBy { it.cleanName }
                withContext(Dispatchers.Main) {
                    allCachedChannels = list
                    val currentQ = binding.editSearchQuery.text.toString().trim()
                    if (currentQ.length >= 2 && foundChannels.isEmpty()) {
                        performDualSearch(currentQ)
                    }
                }
                return@launch
            }

            // 3. Fallback: categoryChannelMap
            if (LiveTvActivity.categoryChannelMap.isNotEmpty()) {
                val list = LiveTvActivity.categoryChannelMap.values.flatten().map { it.channel }.distinctBy { it.cleanName }
                withContext(Dispatchers.Main) {
                    allCachedChannels = list
                    val currentQ = binding.editSearchQuery.text.toString().trim()
                    if (currentQ.length >= 2 && foundChannels.isEmpty()) {
                        performDualSearch(currentQ)
                    }
                }
                return@launch
            }

            // 4. Einmaliger Kaltstart-Fallback
            try {
                val rawCats = client.getLiveCategories()
                val streams = client.getAllLiveStreams()
                if (streams.isNotEmpty()) {
                    val bundledMap = MultiStreamManager.buildMultiStreamCategories(streams, rawCats, this@SearchActivity)
                    val list = bundledMap.values.flatten().distinctBy { it.cleanName }
                    withContext(Dispatchers.Main) {
                        allCachedChannels = list
                        val currentQ = binding.editSearchQuery.text.toString().trim()
                        if (currentQ.length >= 2 && foundChannels.isEmpty()) {
                            performDualSearch(currentQ)
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore fallback network error
            }
        }
    }

    private fun showHistoryView() {
        binding.layoutTabs.visibility = View.GONE
        binding.recyclerSearchResults.visibility = View.GONE
        binding.txtNoResults.visibility = View.GONE
        binding.progressSearch.visibility = View.GONE
        loadSearchHistory()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.editSearchQuery.windowToken, 0)
    }

    private fun performDualSearch(query: String) {
        val cleanQuery = query.trim()
        if (cleanQuery.length < 2) {
            searchJob?.cancel()
            progressiveEpgJob?.cancel()
            showHistoryView()
            return
        }

        binding.layoutSearchHistory.visibility = View.GONE
        binding.progressSearch.visibility = View.GONE
        binding.txtNoResults.visibility = View.GONE

        // 1. Sendersuche: Blitzschnell aus lokalem In-Memory Cache sortiert nach Relevanz & natürlicher Zahlenordnung
        val qLower = cleanQuery.lowercase()
        val channelMatches = allCachedChannels.filter { ch ->
            ch.cleanName.lowercase().contains(qLower) ||
            ch.originalName.lowercase().contains(qLower) ||
            ch.sources.any { it.name.lowercase().contains(qLower) }
        }.sortedWith(
            compareBy<MultiStreamChannel> { !it.cleanName.equals(cleanQuery, ignoreCase = true) }
                .thenBy { !it.cleanName.startsWith(cleanQuery, ignoreCase = true) }
                .thenBy(MultiStreamManager.NaturalOrderComparator) { it.cleanName }
        )

        foundChannels = channelMatches

        // 2. Sofortige Programmsuche aus dem lokalen EPG-Cache
        val programMatches = mutableListOf<Pair<EpgProgram, MultiStreamChannel>>()
        val seenProgramKeys = mutableSetOf<String>()

        fun checkAndAddProgram(epg: EpgProgram, channel: MultiStreamChannel) {
            if (isProgramNowPlaying(epg)) {
                if (epg.title.contains(cleanQuery, ignoreCase = true) || epg.description.contains(cleanQuery, ignoreCase = true)) {
                    val key = "${channel.cleanName}|${epg.title}|${epg.start}"
                    if (seenProgramKeys.add(key)) {
                        programMatches.add(Pair(epg, channel))
                    }
                }
            }
        }

        for (item in LiveTvActivity.categoryChannelMap.values.flatten()) {
            for (epg in item.epgList) {
                checkAndAddProgram(epg, item.channel)
            }
        }

        for ((chName, epgList) in LiveTvActivity.epgGlobalCache) {
            val ch = allCachedChannels.firstOrNull { it.cleanName.equals(chName, ignoreCase = true) }
            if (ch != null) {
                for (epg in epgList) {
                    checkAndAddProgram(epg, ch)
                }
            }
        }

        foundPrograms = programMatches

        // Falls Sender leer, aber Programme da sind, wechsle auf Programme
        if (foundChannels.isEmpty() && foundPrograms.isNotEmpty()) {
            activeFilterTab = "PROGRAMS"
        } else {
            activeFilterTab = "CHANNELS"
        }

        // Cache sichern
        cachedQuery = cleanQuery
        cachedChannels = foundChannels
        cachedPrograms = foundPrograms
        cachedTab = activeFilterTab

        updateTabStyles()
        renderSearchResults()

        // 3. Aufbauende ("progressive") EPG-Suche im Hintergrund für noch nicht geladene relevante Sender
        progressiveEpgJob?.cancel()
        progressiveEpgJob = lifecycleScope.launch {
            val candidateChannels = allCachedChannels.filter { ch ->
                !LiveTvActivity.epgGlobalCache.containsKey(ch.cleanName) &&
                (ch.cleanName.lowercase().contains(qLower) || ch.categoryId in listOf("MAIN_FREETV", "MAIN_SKY", "MAIN_SPORT"))
            }.take(25)

            for (ch in candidateChannels) {
                if (!isActive) break
                val sid = ch.epgStreamId ?: ch.sources.firstOrNull()?.streamId ?: continue
                val fetched = try {
                    withContext(Dispatchers.IO) { client.getEpg(sid) }
                } catch (e: Exception) {
                    emptyList()
                }
                if (fetched.isNotEmpty() && isActive) {
                    LiveTvActivity.epgGlobalCache[ch.cleanName] = fetched
                    var newFound = false
                    for (epg in fetched) {
                        if (isProgramNowPlaying(epg)) {
                            if (epg.title.contains(cleanQuery, ignoreCase = true) || epg.description.contains(cleanQuery, ignoreCase = true)) {
                                val key = "${ch.cleanName}|${epg.title}|${epg.start}"
                                if (seenProgramKeys.add(key)) {
                                    programMatches.add(Pair(epg, ch))
                                    newFound = true
                                }
                            }
                        }
                    }
                    if (newFound && isActive) {
                        foundPrograms = ArrayList(programMatches)
                        cachedPrograms = foundPrograms
                        updateTabStyles()
                        if (activeFilterTab == "PROGRAMS") {
                            renderSearchResults()
                        }
                    }
                }
            }
        }
    }

    private fun isProgramNowPlaying(epg: EpgProgram): Boolean {
        if (epg.isNowPlaying) return true
        if (epg.start.isEmpty() || epg.end.isEmpty()) return false
        return try {
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            val cal = Calendar.getInstance()
            val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

            val sCal = Calendar.getInstance().apply { time = sdf.parse(epg.start) ?: return false }
            val sMin = sCal.get(Calendar.HOUR_OF_DAY) * 60 + sCal.get(Calendar.MINUTE)

            val eCal = Calendar.getInstance().apply { time = sdf.parse(epg.end) ?: return false }
            var eMin = eCal.get(Calendar.HOUR_OF_DAY) * 60 + eCal.get(Calendar.MINUTE)
            if (eMin <= sMin) eMin += 24 * 60

            var cur = nowMin
            if (cur < sMin && eMin > 24 * 60) cur += 24 * 60

            cur in sMin..eMin
        } catch (e: Exception) {
            false
        }
    }

    private fun renderSearchResults() {
        val items = mutableListOf<SearchResultItem>()

        if (activeFilterTab == "CHANNELS") {
            for (channel in foundChannels) {
                items.add(SearchResultItem.ChannelItem(channel))
            }
        } else {
            for ((prog, channel) in foundPrograms) {
                items.add(SearchResultItem.ProgramItem(prog, channel))
            }
        }

        if (items.isEmpty()) {
            binding.recyclerSearchResults.visibility = View.GONE
            binding.txtNoResults.visibility = View.VISIBLE
        } else {
            binding.txtNoResults.visibility = View.GONE
            binding.recyclerSearchResults.visibility = View.VISIBLE
            binding.recyclerSearchResults.adapter = SearchResultsAdapter(items) { channel ->
                selectAndPlayChannel(channel)
            }
        }
    }

    private fun selectAndPlayChannel(channel: MultiStreamChannel) {
        val query = binding.editSearchQuery.text.toString().trim()
        if (query.length >= 2) {
            historyManager.addSearchQuery("LIVE", query)
        }

        val bestStreamId = channel.primarySource?.streamId ?: channel.sources.firstOrNull()?.streamId ?: -1

        // Cache sichern vor Player-Start
        cachedQuery = query
        cachedChannels = foundChannels
        cachedPrograms = foundPrograms
        cachedTab = activeFilterTab
        lastSelectedStreamId = bestStreamId
        isReturningFromPlayer = true

        val playlist: ArrayList<MultiStreamChannel> = if (activeFilterTab == "CHANNELS") {
            ArrayList(foundChannels)
        } else {
            ArrayList(foundPrograms.map { it.second }.distinctBy { it.cleanName })
        }
        val index = playlist.indexOfFirst { it.cleanName.equals(channel.cleanName, ignoreCase = true) }.coerceAtLeast(0)

        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("STREAM_URL", client.getLiveStreamUrl(bestStreamId))
            putExtra("STREAM_NAME", channel.cleanName)
            putExtra("STREAM_ID", bestStreamId)
            putExtra("STREAM_TYPE", "LIVE")
            putExtra("MULTI_STREAM_CHANNEL", channel)
            putExtra("MULTI_STREAM_LIST", playlist)
            putExtra("CURRENT_INDEX", index)
            putExtra("POSTER_URL", channel.icon)
        }
        startActivity(intent)
    }

    override fun finish() {
        if (searchType == "LIVE" && lastSelectedStreamId != null) {
            val data = Intent().apply {
                putExtra("SELECTED_STREAM_ID", lastSelectedStreamId)
                putExtra("START_FULLSCREEN", false)
            }
            setResult(RESULT_OK, data)
        }
        super.finish()
    }

    private fun submitVodOrSeriesSearch(query: String) {
        hideKeyboard()
        historyManager.addSearchQuery(searchType, query)
        val data = Intent().apply {
            putExtra("SEARCH_QUERY", query)
            putExtra("SEARCH_TYPE", searchType)
        }
        setResult(RESULT_OK, data)
        finish()
    }

    private fun loadSearchHistory() {
        val history = historyManager.getSearchHistory(searchType)
        if (history.isEmpty()) {
            binding.layoutSearchHistory.visibility = View.GONE
        } else {
            binding.layoutSearchHistory.visibility = View.VISIBLE
            binding.recyclerSearchHistory.adapter = HistoryChipAdapter(history) { query ->
                hideKeyboard()
                binding.editSearchQuery.setText(query)
                binding.editSearchQuery.setSelection(query.length)
                binding.btnClearSearch.visibility = View.VISIBLE
                binding.editSearchQuery.post { hideKeyboard() }
                if (query.trim().length >= 2) {
                    if (searchType == "LIVE") {
                        performDualSearch(query)
                    } else {
                        submitVodOrSeriesSearch(query)
                    }
                }
            }
        }
    }

    inner class HistoryChipAdapter(
        private val items: List<String>,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<HistoryChipAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val txt: TextView = view.findViewById(R.id.txtHistoryQuery)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_search_history, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val q = items[position]
            holder.txt.text = "🔍 $q"
            holder.itemView.isFocusable = true
            holder.itemView.isClickable = true

            holder.itemView.setOnClickListener {
                onClick(q)
            }
            holder.itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                        onClick(q)
                        return@setOnKeyListener true
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                        binding.editSearchQuery.requestFocus()
                        return@setOnKeyListener true
                    }
                }
                false
            }
        }

        override fun getItemCount() = items.size
    }

    inner class SearchResultsAdapter(
        val items: List<SearchResultItem>,
        private val onSelect: (MultiStreamChannel) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        inner class ChannelViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val logo: ImageView = view.findViewById(R.id.imgSearchChannelLogo)
            val name: TextView = view.findViewById(R.id.txtSearchChannelName)
            val category: TextView = view.findViewById(R.id.txtSearchChannelCategory)
        }

        inner class ProgramViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.txtSearchProgramTitle)
            val channel: TextView = view.findViewById(R.id.txtSearchProgramChannel)
            val timeAndDesc: TextView = view.findViewById(R.id.txtSearchProgramTimeAndDesc)
        }

        override fun getItemViewType(position: Int): Int {
            return when (items[position]) {
                is SearchResultItem.ChannelItem -> TYPE_CHANNEL
                is SearchResultItem.ProgramItem -> TYPE_PROGRAM
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_CHANNEL -> ChannelViewHolder(inflater.inflate(R.layout.item_search_channel, parent, false))
                else -> ProgramViewHolder(inflater.inflate(R.layout.item_search_program, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is SearchResultItem.ChannelItem -> {
                    val vh = holder as ChannelViewHolder
                    vh.name.text = item.channel.cleanName
                    val srcCount = item.channel.sources.size
                    val topSource = item.channel.primarySource?.label ?: "Standard"
                    vh.category.text = if (srcCount > 1) "⚡ $srcCount Quellen • $topSource" else "Live TV • $topSource"
                    vh.itemView.isFocusable = true
                    vh.itemView.isClickable = true

                    if (!item.channel.icon.isNullOrEmpty()) {
                        Glide.with(vh.itemView).load(item.channel.icon).override(36, 36).into(vh.logo)
                    } else {
                        vh.logo.setImageResource(R.drawable.tv_banner)
                    }

                    vh.itemView.setOnClickListener {
                        onSelect(item.channel)
                    }
                    vh.itemView.setOnKeyListener { _, keyCode, event ->
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                                onSelect(item.channel)
                                return@setOnKeyListener true
                            } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP && position == 0) {
                                if (activeFilterTab == "PROGRAMS") binding.btnTabPrograms.requestFocus()
                                else binding.btnTabChannels.requestFocus()
                                return@setOnKeyListener true
                            }
                        }
                        false
                    }
                }
                is SearchResultItem.ProgramItem -> {
                    val vh = holder as ProgramViewHolder
                    vh.title.text = item.program.title
                    vh.channel.text = item.channel.cleanName
                    val timeStr = "${item.program.start} - ${item.program.end}"
                    val desc = if (item.program.description.isNotEmpty()) " • ${item.program.description}" else ""
                    vh.timeAndDesc.text = "$timeStr$desc"
                    vh.itemView.isFocusable = true
                    vh.itemView.isClickable = true

                    vh.itemView.setOnClickListener {
                        onSelect(item.channel)
                    }
                    vh.itemView.setOnKeyListener { _, keyCode, event ->
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                                onSelect(item.channel)
                                return@setOnKeyListener true
                            } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP && position == 0) {
                                if (activeFilterTab == "PROGRAMS") binding.btnTabPrograms.requestFocus()
                                else binding.btnTabChannels.requestFocus()
                                return@setOnKeyListener true
                            }
                        }
                        false
                    }
                }
            }
        }

        override fun getItemCount() = items.size
    }
}
