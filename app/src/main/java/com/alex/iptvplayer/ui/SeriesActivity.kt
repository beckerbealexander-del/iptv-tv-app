package com.alex.iptvplayer.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.alex.iptvplayer.R
import com.alex.iptvplayer.data.Category
import com.alex.iptvplayer.data.LangFilter
import com.alex.iptvplayer.data.SeriesItem
import com.alex.iptvplayer.data.XtreamClient
import com.alex.iptvplayer.databinding.ActivitySeriesBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SeriesActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySeriesBinding
    private lateinit var client: XtreamClient
    private var allCategories: List<Category> = emptyList()
    private var displayedCategories: List<Category> = emptyList()
    private var currentSeries: List<SeriesItem> = emptyList()
    private var rawCategorySeries: List<SeriesItem> = emptyList()
    private var allSeriesGlobal: List<SeriesItem> = emptyList()
    private var selectedCategoryId: String? = null
    private val categoryCache = HashMap<String, List<SeriesItem>>()

    private var currentSortMode: String = "DEFAULT"
    private var currentSearchQuery: String? = null

    private var loadJob: Job? = null
    private var heroJob: Job? = null

    // Vollbild-Suche Launcher
    private val searchLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val query = result.data?.getStringExtra("SEARCH_QUERY")?.trim() ?: ""
            if (query.isNotEmpty()) {
                applySearchQuery(query)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySeriesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        client = XtreamClient(this)

        binding.recyclerSeriesCategories.apply {
            layoutManager = LinearLayoutManager(this@SeriesActivity)
            setHasFixedSize(true)
            setItemViewCacheSize(60)
        }

        binding.recyclerSeriesGrid.apply {
            layoutManager = GridLayoutManager(this@SeriesActivity, 5)
            setHasFixedSize(true)
            setItemViewCacheSize(80)
        }

        binding.btnOpenSeriesSearch.setOnClickListener {
            val intent = Intent(this, SearchActivity::class.java).apply {
                putExtra("SEARCH_TYPE", "SERIES")
            }
            searchLauncher.launch(intent)
        }

        setupSortButtons()
        loadCategories()
        preloadGlobalCatalog()
    }

    private fun preloadGlobalCatalog() {
        lifecycleScope.launch {
            try {
                allSeriesGlobal = client.getAllSeries()
            } catch (e: Exception) {
                // Silent
            }
        }
    }

    private fun setupSortButtons() {
        binding.btnSeriesSortDefault.setOnClickListener { applySorting("DEFAULT") }
        binding.btnSeriesSortRating.setOnClickListener { applySorting("RATING") }
        binding.btnSeriesSortYear.setOnClickListener { applySorting("YEAR") }
        binding.btnSeriesSortAlpha.setOnClickListener { applySorting("ALPHA") }
    }

    private fun applySorting(mode: String) {
        currentSortMode = mode
        if (rawCategorySeries.isEmpty()) return

        val sorted = when (mode) {
            "RATING" -> rawCategorySeries.sortedByDescending { it.rating?.toFloatOrNull() ?: 0f }
            "YEAR" -> rawCategorySeries.sortedByDescending { 
                val year = Regex("\\b(19\\d\\d|20\\d\\d)\\b").find(it.name)?.value?.toIntOrNull() ?: 0
                year
            }
            "ALPHA" -> rawCategorySeries.sortedBy { it.name.lowercase() }
            else -> rawCategorySeries
        }
        currentSeries = sorted
        binding.recyclerSeriesGrid.adapter = SeriesAdapter(sorted, { series ->
            updateHeroBannerDebounced(series)
        }, { series ->
            openSeriesDetail(series)
        })
        if (sorted.isNotEmpty()) {
            updateHeroBanner(sorted[0])
        }
    }

    // 5. SUCHE: DYNAMISCHE KATEGORIE "🔍 Aktuelle Suche" AN INDEX 0
    private fun applySearchQuery(query: String) {
        currentSearchQuery = query
        val searchCategory = Category(id = "CURRENT_SEARCH", name = "🔍 Aktuelle Suche")
        val newCategories = mutableListOf(searchCategory)
        newCategories.addAll(allCategories)
        displayedCategories = newCategories
        selectedCategoryId = "CURRENT_SEARCH"

        binding.recyclerSeriesCategories.adapter = SeriesCategoryAdapter(newCategories) { cat ->
            loadSeries(cat)
        }

        val pool = if (allSeriesGlobal.isNotEmpty()) allSeriesGlobal else currentSeries
        val filtered = pool.filter { it.name.contains(query, ignoreCase = true) }
        binding.txtSeriesCategoryTitle.text = "Suchergebnisse: „$query“ (${filtered.size})"
        rawCategorySeries = filtered
        applySorting(currentSortMode)

        // Fokus sofort auf das erste Suchergebnis
        focusFirstSeries()
    }

    private fun loadCategories() {
        binding.progressSeriesCats.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val raw = client.getSeriesCategories()
                val filtered = client.filterCategories(raw, LangFilter.AUTO_DE_RU_ADULT).toMutableList()
                if (filtered.none { it.id == "ALL_SERIES" }) {
                    filtered.add(0, Category(id = "ALL_SERIES", name = "✨ Alle Serien"))
                }
                allCategories = filtered
                displayedCategories = filtered
                binding.progressSeriesCats.visibility = View.GONE
                binding.recyclerSeriesCategories.adapter = SeriesCategoryAdapter(displayedCategories) { cat ->
                    loadSeries(cat)
                }

                // 6. STANDARD-KATEGORIE: Beim Start immer "Alle Serien" vorauswählen und laden!
                if (displayedCategories.isNotEmpty()) {
                    loadSeries(displayedCategories[0])
                }
            } catch (e: Exception) {
                binding.progressSeriesCats.visibility = View.GONE
                Toast.makeText(this@SeriesActivity, "Fehler: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 6. KEIN SUCH-SPRUNG: Fokus springt immer auf das erste Media-Item der Kategorie (niemals Suchfeld)
    private fun loadSeries(category: Category) {
        if (category.id == "CURRENT_SEARCH" && currentSearchQuery != null) {
            applySearchQuery(currentSearchQuery!!)
            return
        }

        selectedCategoryId = category.id
        binding.recyclerSeriesCategories.adapter?.notifyDataSetChanged()
        binding.txtSeriesCategoryTitle.text = category.name

        val cached = categoryCache[category.id]
        if (cached != null) {
            rawCategorySeries = cached
            binding.progressSeries.visibility = View.GONE
            applySorting(currentSortMode)
            focusFirstSeries()
            return
        }

        binding.progressSeries.visibility = View.VISIBLE
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            try {
                val list = client.getSeries(category.id)
                categoryCache[category.id] = list
                rawCategorySeries = list
                binding.progressSeries.visibility = View.GONE
                applySorting(currentSortMode)
                focusFirstSeries()
            } catch (e: Exception) {
                binding.progressSeries.visibility = View.GONE
                Toast.makeText(this@SeriesActivity, "Fehler beim Laden: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun focusFirstSeries() {
        binding.recyclerSeriesGrid.scrollToPosition(0)
        binding.recyclerSeriesGrid.post {
            binding.recyclerSeriesGrid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    private fun updateHeroBannerDebounced(series: SeriesItem) {
        heroJob?.cancel()
        heroJob = lifecycleScope.launch {
            delay(150)
            updateHeroBanner(series)
        }
    }

    private fun updateHeroBanner(series: SeriesItem) {
        binding.txtHeroSeriesTitle.text = series.name
        binding.txtHeroSeriesRating.text = if (!series.rating.isNullOrEmpty()) "⭐ ${series.rating} | Staffeln & Folgen" else "⭐ 8.5 | Staffeln & Folgen"

        if (!series.cover.isNullOrEmpty()) {
            Glide.with(this)
                .load(series.cover)
                .override(140, 190)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(R.drawable.tv_banner)
                .into(binding.imgHeroSeriesCover)
        } else {
            binding.imgHeroSeriesCover.setImageResource(R.drawable.tv_banner)
        }
    }

    private fun openSeriesDetail(series: SeriesItem) {
        val intent = Intent(this, SeriesDetailActivity::class.java).apply {
            putExtra("SERIES_ITEM", series)
        }
        startActivity(intent)
    }

    private fun focusSelectedCategory() {
        val catIdx = displayedCategories.indexOfFirst { it.id == selectedCategoryId }.coerceAtLeast(0)
        binding.recyclerSeriesCategories.scrollToPosition(catIdx)
        binding.recyclerSeriesCategories.post {
            val holder = binding.recyclerSeriesCategories.findViewHolderForAdapterPosition(catIdx)
            holder?.itemView?.requestFocus() ?: binding.recyclerSeriesCategories.requestFocus()
        }
    }

    // --- Hard-Lock D-Pad Navigation in der Grid & Sortierleiste ---
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val focused = currentFocus
            val isGrid = isViewInView(focused, binding.recyclerSeriesGrid)

            if (isGrid) {
                val gridPos = getFocusedGridPosition(focused)
                val total = (binding.recyclerSeriesGrid.adapter?.itemCount ?: 0)

                when (event.keyCode) {
                    KeyEvent.KEYCODE_BACK -> {
                        focusSelectedCategory()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (gridPos % 5 == 0) {
                            // Ganz links in der Grid -> springt exakt auf die ausgewählte Kategorie
                            focusSelectedCategory()
                        } else {
                            val target = gridPos - 1
                            binding.recyclerSeriesGrid.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus()
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (gridPos % 5 < 4 && gridPos < total - 1) {
                            val target = gridPos + 1
                            binding.recyclerSeriesGrid.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus()
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        val nextPos = gridPos + 5
                        if (nextPos < total) {
                            binding.recyclerSeriesGrid.scrollToPosition(nextPos)
                            binding.recyclerSeriesGrid.post {
                                binding.recyclerSeriesGrid.findViewHolderForAdapterPosition(nextPos)?.itemView?.requestFocus()
                            }
                        }
                        return true // Hard-Lock unten
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        val prevPos = gridPos - 5
                        if (prevPos >= 0) {
                            binding.recyclerSeriesGrid.scrollToPosition(prevPos)
                            binding.recyclerSeriesGrid.post {
                                binding.recyclerSeriesGrid.findViewHolderForAdapterPosition(prevPos)?.itemView?.requestFocus()
                            }
                        } else {
                            // Aus der ersten Reihe nach oben in die integrierte Sortierleiste
                            binding.btnSeriesSortDefault.requestFocus()
                        }
                        return true
                    }
                }
            } else if (isViewInView(focused, binding.layoutSeriesSortBar)) {
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    focusFirstSeries()
                    return true
                } else if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT && focused == binding.btnSeriesSortDefault) {
                    focusSelectedCategory()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isViewInView(view: View?, target: View): Boolean {
        var cur = view
        while (cur != null) {
            if (cur == target) return true
            val p = cur.parent
            cur = p as? View
        }
        return false
    }

    private fun getFocusedGridPosition(view: View?): Int {
        var cur = view
        while (cur != null && cur != binding.recyclerSeriesGrid) {
            val p = cur.parent
            if (p == binding.recyclerSeriesGrid) {
                return binding.recyclerSeriesGrid.getChildAdapterPosition(cur)
            }
            cur = p as? View
        }
        return -1
    }

    inner class SeriesCategoryAdapter(
        private val items: List<Category>,
        private val onSelect: (Category) -> Unit
    ) : RecyclerView.Adapter<SeriesCategoryAdapter.ViewHolder>() {

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
            holder.itemView.isSelected = (cat.id == selectedCategoryId)

            holder.itemView.setOnClickListener {
                onSelect(cat)
            }

            holder.itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            onSelect(cat)
                            return@setOnKeyListener true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            focusFirstSeries()
                            return@setOnKeyListener true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            return@setOnKeyListener true
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (position == 0) {
                                binding.btnOpenSeriesSearch.requestFocus()
                                return@setOnKeyListener true
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (position == items.size - 1) {
                                return@setOnKeyListener true
                            }
                        }
                    }
                }
                false
            }
        }

        override fun getItemCount() = items.size
    }

    inner class SeriesAdapter(
        private val items: List<SeriesItem>,
        private val onFocus: (SeriesItem) -> Unit,
        private val onClick: (SeriesItem) -> Unit
    ) : RecyclerView.Adapter<SeriesAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val txtTitle: TextView = view.findViewById(R.id.txtPosterTitle)
            val imgPoster: ImageView = view.findViewById(R.id.imgPoster)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_poster, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val s = items[position]
            holder.txtTitle.text = s.name

            if (!s.cover.isNullOrEmpty()) {
                Glide.with(holder.itemView)
                    .load(s.cover)
                    .override(130, 180)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(R.drawable.tv_banner)
                    .into(holder.imgPoster)
            } else {
                holder.imgPoster.setImageResource(R.drawable.tv_banner)
            }

            holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) onFocus(s)
            }

            holder.itemView.setOnClickListener { onClick(s) }
        }

        override fun getItemCount() = items.size
    }
}
