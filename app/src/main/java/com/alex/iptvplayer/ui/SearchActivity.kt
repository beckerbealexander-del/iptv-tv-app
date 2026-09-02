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
import com.alex.iptvplayer.data.XtreamClient
import com.alex.iptvplayer.databinding.ActivitySearchBinding
import com.bumptech.glide.Glide
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private const val TYPE_CHANNEL = 1
private const val TYPE_PROGRAM = 2

sealed class SearchResultItem {
    data class ChannelItem(val stream: LiveStream) : SearchResultItem()
    data class ProgramItem(val program: EpgProgram, val stream: LiveStream) : SearchResultItem()
}

class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private lateinit var historyManager: HistoryManager
    private lateinit var client: XtreamClient

    private var searchType: String = "LIVE"
    private var allLiveStreams: List<LiveStream> = emptyList()

    private var foundChannels: List<LiveStream> = emptyList()
    private var foundPrograms: List<Pair<EpgProgram, LiveStream>> = emptyList()

    private var activeFilterTab: String = "CHANNELS" // CHANNELS oder PROGRAMS ("Alle" entfernt)
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        client = XtreamClient(this)
        historyManager = HistoryManager(this)
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

        loadSearchHistory()
        setupTabs()

        if (searchType == "LIVE") {
            loadGlobalStreams()
        }

        // Live-Suche während der Eingabe (Debounce 300ms)
        binding.editSearchQuery.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                val query = s?.toString()?.trim() ?: ""
                if (query.isEmpty()) {
                    showHistoryView()
                } else if (searchType == "LIVE") {
                    searchJob?.cancel()
                    searchJob = lifecycleScope.launch {
                        delay(300)
                        performDualSearch(query)
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
                if (q.isNotEmpty()) {
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

        // 1. FOKUS-KORREKTUR: Aus der Suchleiste nach UNTEN springt ZWINGEND in den Filter-Tab (nicht direkt in die Liste!)
        binding.editSearchQuery.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (binding.layoutTabs.visibility == View.VISIBLE) {
                    if (activeFilterTab == "PROGRAMS") {
                        binding.btnTabPrograms.requestFocus()
                    } else {
                        binding.btnTabChannels.requestFocus()
                    }
                    return@setOnKeyListener true
                } else if (binding.layoutSearchHistory.visibility == View.VISIBLE) {
                    val holder = binding.recyclerSearchHistory.findViewHolderForAdapterPosition(0)
                    holder?.itemView?.requestFocus()
                    return@setOnKeyListener true
                }
            }
            false
        }

        binding.editSearchQuery.post {
            binding.editSearchQuery.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.editSearchQuery, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun setupTabs() {
        binding.btnTabChannels.setOnClickListener {
            activeFilterTab = "CHANNELS"
            updateTabStyles()
            renderSearchResults()
        }
        binding.btnTabPrograms.setOnClickListener {
            activeFilterTab = "PROGRAMS"
            updateTabStyles()
            renderSearchResults()
        }

        // DPAD-Navigation der Filter-Tabs:
        // UP -> zurück zur Suchleiste
        // DOWN -> in die Ergebnisliste
        // LEFT/RIGHT -> zwischen den beiden Tabs wechseln
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

    private fun loadGlobalStreams() {
        lifecycleScope.launch {
            allLiveStreams = if (LiveTvActivity.allLiveStreamsCache.isNotEmpty()) {
                LiveTvActivity.allLiveStreamsCache
            } else {
                try {
                    client.getAllLiveStreams()
                } catch (e: Exception) {
                    emptyList()
                }
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
        if (query.isEmpty()) return

        binding.layoutSearchHistory.visibility = View.GONE
        binding.progressSearch.visibility = View.VISIBLE
        binding.txtNoResults.visibility = View.GONE

        lifecycleScope.launch {
            // 1. Sender-Treffer
            val channelMatches = allLiveStreams.filter { it.name.contains(query, ignoreCase = true) }
            foundChannels = channelMatches

            // 2. Live-Programm Treffer (aktuell laufende Sendungen aus dem EPG)
            val programMatches = mutableListOf<Pair<EpgProgram, LiveStream>>()

            val cachedChannels = LiveTvActivity.categoryChannelMap.values.flatten()
            for (item in cachedChannels) {
                for (epg in item.epgList) {
                    if (isProgramNowPlaying(epg)) {
                        if (epg.title.contains(query, ignoreCase = true) || epg.description.contains(query, ignoreCase = true)) {
                            programMatches.add(Pair(epg, item.stream))
                        }
                    }
                }
            }

            val channelsToCheck = channelMatches.take(12)
            for (stream in channelsToCheck) {
                val isAlreadyChecked = cachedChannels.any { it.stream.streamId == stream.streamId }
                if (!isAlreadyChecked) {
                    try {
                        val epgList = client.getEpg(stream.streamId)
                        for (epg in epgList) {
                            if (isProgramNowPlaying(epg)) {
                                if (epg.title.contains(query, ignoreCase = true) || epg.description.contains(query, ignoreCase = true)) {
                                    if (programMatches.none { it.second.streamId == stream.streamId }) {
                                        programMatches.add(Pair(epg, stream))
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Silent
                    }
                }
            }

            foundPrograms = programMatches
            binding.progressSearch.visibility = View.GONE

            binding.btnTabChannels.text = "📺 Sender (${foundChannels.size})"
            binding.btnTabPrograms.text = "🔴 Live-Programm (${foundPrograms.size})"
            binding.layoutTabs.visibility = View.VISIBLE

            // Falls Sender leer, aber Programme da sind, wechsle auf Programme
            if (foundChannels.isEmpty() && foundPrograms.isNotEmpty()) {
                activeFilterTab = "PROGRAMS"
            } else if (foundChannels.isNotEmpty()) {
                activeFilterTab = "CHANNELS"
            }
            updateTabStyles()

            renderSearchResults()
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
            for (stream in foundChannels) {
                items.add(SearchResultItem.ChannelItem(stream))
            }
        } else {
            for ((prog, stream) in foundPrograms) {
                items.add(SearchResultItem.ProgramItem(prog, stream))
            }
        }

        if (items.isEmpty()) {
            binding.recyclerSearchResults.visibility = View.GONE
            binding.txtNoResults.visibility = View.VISIBLE
        } else {
            binding.txtNoResults.visibility = View.GONE
            binding.recyclerSearchResults.visibility = View.VISIBLE
            binding.recyclerSearchResults.adapter = SearchResultsAdapter(items) { stream ->
                selectAndPlayStream(stream)
            }
        }
    }

    private fun selectAndPlayStream(stream: LiveStream) {
        val query = binding.editSearchQuery.text.toString().trim()
        if (query.isNotEmpty()) {
            historyManager.addSearchQuery("LIVE", query)
        }
        val data = Intent().apply {
            putExtra("SELECTED_STREAM_ID", stream.streamId)
            putExtra("SELECTED_STREAM_NAME", stream.name)
            putExtra("START_FULLSCREEN", true)
        }
        setResult(RESULT_OK, data)
        finish()
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
                binding.editSearchQuery.setText(query)
                binding.editSearchQuery.setSelection(query.length)
                if (searchType == "LIVE") {
                    hideKeyboard()
                    performDualSearch(query)
                } else {
                    submitVodOrSeriesSearch(query)
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
        private val items: List<SearchResultItem>,
        private val onSelect: (LiveStream) -> Unit
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
                    vh.name.text = item.stream.name
                    vh.category.text = "Live TV"
                    vh.itemView.isFocusable = true
                    vh.itemView.isClickable = true

                    if (!item.stream.streamIcon.isNullOrEmpty()) {
                        Glide.with(vh.itemView).load(item.stream.streamIcon).override(36, 36).into(vh.logo)
                    } else {
                        vh.logo.setImageResource(R.drawable.tv_banner)
                    }

                    vh.itemView.setOnClickListener {
                        onSelect(item.stream)
                    }
                    vh.itemView.setOnKeyListener { _, keyCode, event ->
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                                onSelect(item.stream)
                                return@setOnKeyListener true
                            } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP && position == 0) {
                                // Springt zurück in den aktiven Tab!
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
                    vh.channel.text = item.stream.name
                    val timeStr = "${item.program.start} - ${item.program.end}"
                    val desc = if (item.program.description.isNotEmpty()) " • ${item.program.description}" else ""
                    vh.timeAndDesc.text = "$timeStr$desc"
                    vh.itemView.isFocusable = true
                    vh.itemView.isClickable = true

                    vh.itemView.setOnClickListener {
                        onSelect(item.stream)
                    }
                    vh.itemView.setOnKeyListener { _, keyCode, event ->
                        if (event.action == KeyEvent.ACTION_DOWN) {
                            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                                onSelect(item.stream)
                                return@setOnKeyListener true
                            } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP && position == 0) {
                                // Springt zurück in den aktiven Tab!
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
