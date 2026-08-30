package com.alex.iptvplayer.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.alex.iptvplayer.R
import com.alex.iptvplayer.data.Category
import com.alex.iptvplayer.data.EpgProgram
import com.alex.iptvplayer.data.HistoryManager
import com.alex.iptvplayer.data.LangFilter
import com.alex.iptvplayer.data.LiveStream
import com.alex.iptvplayer.data.XtreamClient
import com.alex.iptvplayer.databinding.ActivityLiveTvBinding
import com.bumptech.glide.Glide
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LiveTvActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLiveTvBinding
    private lateinit var client: XtreamClient
    private lateinit var historyManager: HistoryManager

    private var allCategories: List<Category> = emptyList()
    private var currentFilter = LangFilter.AUTO_DE_RU_ADULT
    private var currentStreams: List<LiveStream> = emptyList()
    private var selectedCategoryId: String? = null
    private val epgCache = HashMap<Int, List<EpgProgram>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLiveTvBinding.inflate(layoutInflater)
        setContentView(binding.root)

        client = XtreamClient(this)
        historyManager = HistoryManager(this)

        updateLiveTimeHeader()

        binding.recyclerCategories.apply {
            layoutManager = LinearLayoutManager(this@LiveTvActivity)
            setHasFixedSize(true)
            setItemViewCacheSize(50)
        }

        binding.recyclerChannels.apply {
            layoutManager = LinearLayoutManager(this@LiveTvActivity)
            setHasFixedSize(true)
            setItemViewCacheSize(60)
        }

        setupFilterButtons()
        setupSearch()
        loadCategories()
    }

    private fun updateLiveTimeHeader() {
        val sdf = SimpleDateFormat("EEE, dd. MMM 'um' HH:mm", Locale.GERMANY)
        binding.txtCurrentLiveTime.text = "🔴 ${sdf.format(Date())}"
    }

    private fun setupSearch() {
        binding.editLiveSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString()?.trim()?.lowercase() ?: ""
                val filtered = if (q.isEmpty()) currentStreams else currentStreams.filter { it.name.lowercase().contains(q) }
                binding.recyclerChannels.adapter = ChannelAdapter(filtered)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupFilterButtons() {
        binding.btnLiveFilterDe.setOnClickListener { applyFilter(LangFilter.DE) }
        binding.btnLiveFilterRu.setOnClickListener { applyFilter(LangFilter.RU) }
        binding.btnLiveFilterAdult.setOnClickListener { applyFilter(LangFilter.ADULT) }
        binding.btnLiveFilterAll.setOnClickListener { applyFilter(LangFilter.ALL) }
    }

    private fun applyFilter(filter: LangFilter) {
        currentFilter = filter
        val filtered = client.filterCategories(allCategories, filter)
        binding.recyclerCategories.adapter = CategoryAdapter(filtered) { category ->
            loadChannels(category)
        }
        if (filtered.isNotEmpty()) {
            loadChannels(filtered[0])
        }
    }

    private fun loadCategories() {
        binding.progressCategories.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                allCategories = client.getLiveCategories()
                binding.progressCategories.visibility = View.GONE
                applyFilter(currentFilter)
            } catch (e: Exception) {
                binding.progressCategories.visibility = View.GONE
                Toast.makeText(this@LiveTvActivity, "Fehler: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadChannels(category: Category) {
        if (selectedCategoryId == category.id) return
        selectedCategoryId = category.id

        binding.progressChannels.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                currentStreams = client.getLiveStreams(category.id)
                binding.progressChannels.visibility = View.GONE
                binding.recyclerChannels.adapter = ChannelAdapter(currentStreams)

                if (currentStreams.isNotEmpty()) {
                    showChannelPreview(currentStreams[0], null)
                }
            } catch (e: Exception) {
                binding.progressChannels.visibility = View.GONE
                Toast.makeText(this@LiveTvActivity, "Fehler beim Laden: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showChannelPreview(stream: LiveStream, program: EpgProgram?) {
        if (program != null) {
            binding.txtPreviewTitle.text = "${stream.name} – ${program.title}"
            binding.txtPreviewTime.text = "${program.start} - ${program.end}"
            binding.txtPreviewDesc.text = if (program.description.isNotEmpty()) program.description else "Keine Programmbeschreibung vorhanden."
        } else {
            val cached = epgCache[stream.streamId]?.firstOrNull { it.isNowPlaying } ?: epgCache[stream.streamId]?.firstOrNull()
            if (cached != null) {
                binding.txtPreviewTitle.text = "${stream.name} – ${cached.title}"
                binding.txtPreviewTime.text = "${cached.start} - ${cached.end}"
                binding.txtPreviewDesc.text = if (cached.description.isNotEmpty()) cached.description else "Keine Programmbeschreibung vorhanden."
            } else {
                binding.txtPreviewTitle.text = stream.name
                binding.txtPreviewTime.text = "🔴 LIVE"
                binding.txtPreviewDesc.text = "Drücke OK auf der Fernbedienung, um den Sender direkt zu starten."
            }
        }
    }

    private fun openFullscreenPlayer(stream: LiveStream, position: Int) {
        historyManager.saveLiveChannel(stream)
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("STREAM_URL", client.getLiveStreamUrl(stream.streamId))
            putExtra("STREAM_NAME", stream.name)
            putExtra("POSTER_URL", stream.streamIcon)
            putExtra("STREAM_ID", stream.streamId)
            putExtra("STREAM_TYPE", "LIVE")
            putExtra("STREAM_LIST", ArrayList(currentStreams))
            putExtra("CURRENT_INDEX", position)
        }
        startActivity(intent)
    }

    // --- Adapter 1: Kategorien ---
    inner class CategoryAdapter(
        private val items: List<Category>,
        private val onSelect: (Category) -> Unit
    ) : RecyclerView.Adapter<CategoryAdapter.ViewHolder>() {

        private var focusJob: Job? = null

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val txtName: TextView = view.findViewById(R.id.txtCategoryName)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val cat = items[position]
            holder.txtName.text = cat.name
            holder.itemView.setOnClickListener { onSelect(cat) }
            holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    focusJob?.cancel()
                    focusJob = lifecycleScope.launch {
                        delay(350)
                        onSelect(cat)
                    }
                }
            }

            // Strikter Tastatur-Handler: Rechts wechselt gezielt zu den Sendern
            holder.itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    val firstHolder = binding.recyclerChannels.findViewHolderForAdapterPosition(0) as? ChannelAdapter.ViewHolder
                    firstHolder?.header?.requestFocus() ?: binding.recyclerChannels.requestFocus()
                    true
                } else false
            }
        }

        override fun getItemCount() = items.size
    }

    // --- Adapter 2: Senderzeilen mit horizontalem EPG Timeline Grid ---
    inner class ChannelAdapter(
        private val items: List<LiveStream>
    ) : RecyclerView.Adapter<ChannelAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val header: View = view.findViewById(R.id.channelHeader)
            val txtNum: TextView = view.findViewById(R.id.txtChannelNum)
            val imgLogo: ImageView = view.findViewById(R.id.imgChannelLogo)
            val txtName: TextView = view.findViewById(R.id.txtChannelName)
            val recyclerPrograms: RecyclerView = view.findViewById(R.id.recyclerChannelPrograms)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_epg_channel_row, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val s = items[position]
            holder.txtNum.text = "${position + 1}"
            holder.txtName.text = s.name

            if (!s.streamIcon.isNullOrEmpty()) {
                Glide.with(holder.itemView).load(s.streamIcon).override(45, 45).into(holder.imgLogo)
            } else {
                holder.imgLogo.setImageResource(R.drawable.tv_banner)
            }

            holder.header.setOnClickListener {
                openFullscreenPlayer(s, position)
            }

            holder.header.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    showChannelPreview(s, null)
                }
            }

            // Fokus-Sperre: Verhindert jegliches Abdriften zu den Kategorien beim Scrollen
            holder.header.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (position < items.size - 1) {
                                binding.recyclerChannels.smoothScrollToPosition(position + 1)
                                val nextHolder = binding.recyclerChannels.findViewHolderForAdapterPosition(position + 1) as? ChannelAdapter.ViewHolder
                                nextHolder?.header?.requestFocus()
                                return@setOnKeyListener true
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (position > 0) {
                                binding.recyclerChannels.smoothScrollToPosition(position - 1)
                                val prevHolder = binding.recyclerChannels.findViewHolderForAdapterPosition(position - 1) as? ChannelAdapter.ViewHolder
                                prevHolder?.header?.requestFocus()
                                return@setOnKeyListener true
                            } else {
                                binding.editLiveSearch.requestFocus()
                                return@setOnKeyListener true
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            binding.recyclerCategories.requestFocus()
                            return@setOnKeyListener true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            holder.recyclerPrograms.requestFocus()
                            return@setOnKeyListener true
                        }
                    }
                }
                false
            }

            holder.recyclerPrograms.apply {
                layoutManager = LinearLayoutManager(holder.itemView.context, LinearLayoutManager.HORIZONTAL, false)
                setHasFixedSize(true)
            }

            // Gecachte EPG-Daten sofort anzeigen
            val cached = epgCache[s.streamId]
            if (cached != null) {
                holder.recyclerPrograms.adapter = ProgramTimelineAdapter(s, position, cached, holder)
            } else {
                val fallback = listOf(EpgProgram("Lade EPG...", "", "16:00", "23:59", true))
                holder.recyclerPrograms.adapter = ProgramTimelineAdapter(s, position, fallback, holder)

                lifecycleScope.launch {
                    val epgList = client.getEpg(s.streamId)
                    if (epgList.isNotEmpty()) {
                        epgCache[s.streamId] = epgList
                        holder.recyclerPrograms.adapter = ProgramTimelineAdapter(s, position, epgList, holder)
                    }
                }
            }
        }

        override fun getItemCount() = items.size
    }

    // --- Adapter 3: Horizontale EPG-Sendungsblöcke (Timeline) ---
    inner class ProgramTimelineAdapter(
        private val stream: LiveStream,
        private val channelIndex: Int,
        private val programs: List<EpgProgram>,
        private val channelHolder: ChannelAdapter.ViewHolder
    ) : RecyclerView.Adapter<ProgramTimelineAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val txtTitle: TextView = view.findViewById(R.id.txtEpgProgramTitle)
            val txtTime: TextView = view.findViewById(R.id.txtEpgProgramTime)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_epg_program_block, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val p = programs[position]
            holder.txtTitle.text = p.title
            holder.txtTime.text = "${p.start} - ${p.end}"

            if (p.isNowPlaying) {
                holder.txtTitle.setTextColor(resources.getColor(R.color.netflix_red, null))
            } else {
                holder.txtTitle.setTextColor(resources.getColor(R.color.text_primary, null))
            }

            holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    showChannelPreview(stream, p)
                }
            }

            holder.itemView.setOnClickListener {
                openFullscreenPlayer(stream, channelIndex)
            }

            // Bei Links von der ersten Sendung zurück zum Senderkopf
            holder.itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && position == 0) {
                        channelHolder.header.requestFocus()
                        return@setOnKeyListener true
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_UP && channelIndex > 0) {
                        binding.recyclerChannels.smoothScrollToPosition(channelIndex - 1)
                        val prevHolder = binding.recyclerChannels.findViewHolderForAdapterPosition(channelIndex - 1) as? ChannelAdapter.ViewHolder
                        prevHolder?.header?.requestFocus()
                        return@setOnKeyListener true
                    } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && channelIndex < currentStreams.size - 1) {
                        binding.recyclerChannels.smoothScrollToPosition(channelIndex + 1)
                        val nextHolder = binding.recyclerChannels.findViewHolderForAdapterPosition(channelIndex + 1) as? ChannelAdapter.ViewHolder
                        nextHolder?.header?.requestFocus()
                        return@setOnKeyListener true
                    }
                }
                false
            }
        }

        override fun getItemCount() = programs.size
    }
}
