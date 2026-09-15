package com.alex.iptvplayer.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SeriesCategoryItem(
    val id: String,
    val title: String,
    val isProviderHeader: Boolean = false,
    val providerKey: String? = null,
    val genreKey: String? = null,
    val isSubItem: Boolean = false
)

class SeriesActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySeriesBinding
    private lateinit var client: XtreamClient
    private var displayedCategories: List<SeriesCategoryItem> = emptyList()
    private var currentSeries: List<SeriesItem> = emptyList()
    private var rawCategorySeries: List<SeriesItem> = emptyList()
    private var allSeriesGlobal: List<SeriesItem> = emptyList()
    private var expandedProviderKey: String? = "netflix"
    private var selectedCategoryId: String = "netflix_TOP"
    private val categoryCache get() = com.alex.iptvplayer.data.TmdbProviderCatalogManager.seriesCategoryCache
    private val categoryCounts get() = com.alex.iptvplayer.data.TmdbProviderCatalogManager.seriesCategoryCounts

    private var currentSearchQuery: String? = null

    private var loadJob: Job? = null
    private var heroJob: Job? = null

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

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

        binding.btnOpenSeriesSearch.isFocusable = false
        binding.btnOpenSeriesSearch.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                binding.btnOpenSeriesSearch.isFocusable = false
            }
        }
        binding.btnOpenSeriesSearch.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                focusSelectedCategory()
                return@setOnKeyListener true
            }
            false
        }

        binding.btnOpenSeriesSearch.setOnClickListener {
            val intent = Intent(this, SearchActivity::class.java).apply {
                putExtra("SEARCH_TYPE", "SERIES")
            }
            searchLauncher.launch(intent)
        }

        // 1. Sofort aus lokalem Disk-Cache laden (Ladezeit < 10ms)
        allSeriesGlobal = cachedAllSeriesGlobal ?: client.loadCachedSeriesCatalog().also { cachedAllSeriesGlobal = it }
        loadCategories()
        // 2. Im Hintergrund synchronisieren falls noetig
        preloadGlobalCatalog()
        preloadCategoryCounts()
    }

    companion object {
        var cachedAllSeriesGlobal: List<SeriesItem>? = null

        val PROVIDERS = listOf(
            Pair("netflix", "Netflix"),
            Pair("prime", "Amazon Prime"),
            Pair("disney", "Disney+"),
            Pair("apple", "Apple TV+"),
            Pair("paramount", "Paramount+"),
            Pair("other", "Weitere Serien")
        )

        val GENRE_ITEMS = listOf(
            Pair("ALL", "Alle"),
            Pair("TOP", "Top"),
            Pair("ACTION", "Action & Abenteuer"),
            Pair("COMEDY", "Komödie"),
            Pair("THRILLER", "Thriller & Krimi"),
            Pair("HORROR", "Horror & Mystery"),
            Pair("SCIFI", "Sci-Fi & Fantasy"),
            Pair("DOCU", "Dokumentation"),
            Pair("KIDS", "Kinder & Familie")
        )

        val STATIC_ITEMS = listOf(
            Pair("GENRE_RUSSIAN", "Russian"),
            Pair("ALL_SERIES", "Alle Serien")
        )
    }

    private fun preloadGlobalCatalog() {
        lifecycleScope.launch {
            try {
                val fresh = client.getGermanSeriesStreamed()
                if (fresh.isNotEmpty()) {
                    allSeriesGlobal = fresh
                    cachedAllSeriesGlobal = fresh
                    categoryCounts["ALL_SERIES"] = fresh.size
                    categoryCounts["GENRE_RUSSIAN"] = fresh.count { client.isRussianMedia(it.name, it.categoryId) }
                    binding.recyclerSeriesCategories.adapter?.notifyDataSetChanged()
                    preloadCategoryCounts()
                }
            } catch (e: Exception) {
                // Silent
            }
        }
    }

    private fun preloadCategoryCounts() {
        lifecycleScope.launch(Dispatchers.Default) {
            val pool = if (allSeriesGlobal.isNotEmpty()) allSeriesGlobal else client.loadCachedSeriesCatalog()
            val germanPool = pool.filter { client.isGermanMedia(it.name) }
            if (germanPool.isEmpty()) return@launch

            val provKey = expandedProviderKey ?: "netflix"
            for (genre in GENRE_ITEMS) {
                val cacheKey = "${provKey}_${genre.first}"
                if (!categoryCounts.containsKey(cacheKey)) {
                    com.alex.iptvplayer.data.TmdbProviderCatalogManager.filterSeriesByProviderAndGenre(
                        context = this@SeriesActivity,
                        providerKey = provKey,
                        genreKey = genre.first,
                        germanSeries = germanPool
                    )
                }
            }
            withContext(Dispatchers.Main) {
                binding.recyclerSeriesCategories.adapter?.notifyDataSetChanged()
            }
        }
    }

    private fun applySorting() {
        if (rawCategorySeries.isEmpty()) {
            binding.txtSeriesCategoryCounter.text = ""
            return
        }
        currentSeries = rawCategorySeries
        binding.txtSeriesCategoryCounter.text = "1 / ${currentSeries.size}"
        binding.recyclerSeriesGrid.adapter = SeriesAdapter(currentSeries, { series, position ->
            binding.txtSeriesCategoryCounter.text = "${position + 1} / ${currentSeries.size}"
            updateHeroBannerDebounced(series)
        }, { series ->
            openSeriesDetail(series)
        })
        if (currentSeries.isNotEmpty()) {
            updateHeroBanner(currentSeries[0])
        }
    }

    // 5. SUCHE: DYNAMISCHE KATEGORIE "Aktuelle Suche" AN INDEX 0
    private fun applySearchQuery(query: String) {
        currentSearchQuery = query
        selectedCategoryId = "CURRENT_SEARCH"
        expandedProviderKey = null
        displayedCategories = buildCategoryList()
        (binding.recyclerSeriesCategories.adapter as? SeriesCategoryAdapter)?.updateItems(displayedCategories)

        val pool = if (allSeriesGlobal.isNotEmpty()) allSeriesGlobal else currentSeries
        val filtered = pool.filter { it.name.contains(query, ignoreCase = true) }
        categoryCounts["CURRENT_SEARCH"] = filtered.size
        binding.txtSeriesCategoryTitle.text = "Suchergebnisse: „$query“"
        binding.txtSeriesCategoryCounter.text = if (filtered.isNotEmpty()) "1 / ${filtered.size}" else ""
        rawCategorySeries = filtered
        binding.recyclerSeriesCategories.adapter?.notifyDataSetChanged()
        applySorting()

        // Fokus sofort auf das erste Suchergebnis
        focusFirstSeries()
    }

    private fun buildCategoryList(): List<SeriesCategoryItem> {
        val list = mutableListOf<SeriesCategoryItem>()
        if (currentSearchQuery != null) {
            list.add(SeriesCategoryItem(id = "CURRENT_SEARCH", title = "Aktuelle Suche"))
        }

        for (prov in PROVIDERS) {
            val isExpanded = (expandedProviderKey == prov.first)
            list.add(
                SeriesCategoryItem(
                    id = "prov_${prov.first}",
                    title = prov.second,
                    isProviderHeader = true,
                    providerKey = prov.first
                )
            )
            if (isExpanded) {
                for (genre in GENRE_ITEMS) {
                    list.add(
                        SeriesCategoryItem(
                            id = "${prov.first}_${genre.first}",
                            title = genre.second,
                            isProviderHeader = false,
                            providerKey = prov.first,
                            genreKey = genre.first,
                            isSubItem = true
                        )
                    )
                }
            }
        }

        for (staticCat in STATIC_ITEMS) {
            list.add(
                SeriesCategoryItem(
                    id = staticCat.first,
                    title = staticCat.second,
                    isProviderHeader = false
                )
            )
        }

        return list
    }

    private fun loadCategories() {
        binding.progressSeriesCats.visibility = View.GONE
        if (allSeriesGlobal.isNotEmpty()) {
            categoryCounts["ALL_SERIES"] = allSeriesGlobal.size
            categoryCounts["GENRE_RUSSIAN"] = allSeriesGlobal.count { client.isRussianMedia(it.name, it.categoryId) }
        }

        displayedCategories = buildCategoryList()
        binding.recyclerSeriesCategories.adapter = SeriesCategoryAdapter(displayedCategories) { cat ->
            onCategoryClicked(cat)
        }

        // Beim Start immer Netflix Top vorauswählen und laden!
        val initialItem = displayedCategories.firstOrNull { it.id == "netflix_TOP" }
            ?: displayedCategories.firstOrNull()
        if (initialItem != null) {
            loadSeries(initialItem, autoFocusGrid = false)
        }

        // Fokus sofort und ruhig auf die ausgewählte Kategorie setzen (ohne Suchen-Feld-Sprung)
        binding.recyclerSeriesCategories.post {
            focusSelectedCategory()
        }
    }

    private fun onCategoryClicked(item: SeriesCategoryItem) {
        if (item.isProviderHeader) {
            if (expandedProviderKey == item.providerKey) {
                // Bei Klick auf bereits geöffneten Anbieter -> Zuklappen
                expandedProviderKey = null
                displayedCategories = buildCategoryList()
                (binding.recyclerSeriesCategories.adapter as? SeriesCategoryAdapter)?.updateItems(displayedCategories)
            } else {
                // Anbieter aufklappen und automatisch dessen "Top" Titel laden
                expandedProviderKey = item.providerKey
                val topGenreId = "${item.providerKey}_TOP"
                selectedCategoryId = topGenreId
                displayedCategories = buildCategoryList()
                (binding.recyclerSeriesCategories.adapter as? SeriesCategoryAdapter)?.updateItems(displayedCategories)

                val targetItem = displayedCategories.firstOrNull { it.id == topGenreId } ?: item
                loadSeries(targetItem, autoFocusGrid = false)
                preloadCategoryCounts()
            }
        } else {
            selectedCategoryId = item.id
            if (!item.isSubItem && item.id != "CURRENT_SEARCH") {
                // Bei Klick auf Russian oder Alle Serien -> Provider zuklappen
                expandedProviderKey = null
                displayedCategories = buildCategoryList()
                (binding.recyclerSeriesCategories.adapter as? SeriesCategoryAdapter)?.updateItems(displayedCategories)
            } else {
                binding.recyclerSeriesCategories.adapter?.notifyDataSetChanged()
            }
            loadSeries(item, autoFocusGrid = false)
        }
    }

    // 6. KEIN SUCH-SPRUNG: Fokus springt bei Bedarf auf das erste Media-Item der Kategorie
    private fun loadSeries(category: SeriesCategoryItem, autoFocusGrid: Boolean = true) {
        if (category.id == "CURRENT_SEARCH" && currentSearchQuery != null) {
            applySearchQuery(currentSearchQuery!!)
            return
        }

        selectedCategoryId = category.id
        binding.recyclerSeriesCategories.adapter?.notifyDataSetChanged()

        val headerTitle = when {
            category.isProviderHeader -> {
                val provName = PROVIDERS.firstOrNull { it.first == category.providerKey }?.second ?: category.title
                "$provName › Top"
            }
            category.isSubItem -> {
                val provName = PROVIDERS.firstOrNull { it.first == category.providerKey }?.second ?: ""
                val genreName = GENRE_ITEMS.firstOrNull { it.first == category.genreKey }?.second ?: ""
                "$provName › $genreName"
            }
            else -> category.title
        }

        val cached = categoryCache[category.id]
        if (cached != null) {
            rawCategorySeries = cached
            categoryCounts[category.id] = cached.size
            binding.txtSeriesCategoryTitle.text = headerTitle
            binding.txtSeriesCategoryCounter.text = if (cached.isNotEmpty()) "1 / ${cached.size}" else ""
            binding.recyclerSeriesCategories.adapter?.notifyDataSetChanged()
            binding.progressSeries.visibility = View.GONE
            applySorting()
            if (autoFocusGrid) {
                focusFirstSeries()
            } else {
                focusSelectedCategory()
            }
            return
        }

        binding.txtSeriesCategoryTitle.text = headerTitle
        binding.txtSeriesCategoryCounter.text = ""
        binding.progressSeries.visibility = View.VISIBLE
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            try {
                val allGlobal = if (allSeriesGlobal.isNotEmpty()) allSeriesGlobal else {
                    val list = client.getGermanSeriesStreamed()
                    allSeriesGlobal = list
                    cachedAllSeriesGlobal = list
                    list
                }
                val germanPool = allGlobal.filter { client.isGermanMedia(it.name) }

                val filtered = when {
                    category.providerKey != null -> {
                        val gKey = category.genreKey ?: "TOP"
                        com.alex.iptvplayer.data.TmdbProviderCatalogManager.filterSeriesByProviderAndGenre(
                            context = this@SeriesActivity,
                            providerKey = category.providerKey,
                            genreKey = gKey,
                            germanSeries = germanPool
                        )
                    }
                    category.id == "GENRE_RUSSIAN" -> {
                        val ruList = allGlobal.filter { client.isRussianMedia(it.name, it.categoryId) }
                        if (ruList.isNotEmpty()) ruList else client.getSeries("1046")
                    }
                    category.id == "ALL_SERIES" -> allGlobal
                    else -> allGlobal
                }

                categoryCache[category.id] = filtered
                categoryCounts[category.id] = filtered.size
                rawCategorySeries = filtered
                binding.txtSeriesCategoryTitle.text = headerTitle
                binding.txtSeriesCategoryCounter.text = if (filtered.isNotEmpty()) "1 / ${filtered.size}" else ""
                binding.recyclerSeriesCategories.adapter?.notifyDataSetChanged()
                binding.progressSeries.visibility = View.GONE
                applySorting()
                if (autoFocusGrid) {
                    focusFirstSeries()
                } else {
                    focusSelectedCategory()
                }
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
        binding.txtHeroSeriesRating.text = if (!series.rating.isNullOrEmpty()) "★ ${series.rating} | Staffeln & Folgen" else "★ 8.5 | Staffeln & Folgen"

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
                        }
                        return true // Fix-Lock oben: Erste Reihe verlässt die Grid nicht!
                    }
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
        private var items: List<SeriesCategoryItem>,
        private val onSelect: (SeriesCategoryItem) -> Unit
    ) : RecyclerView.Adapter<SeriesCategoryAdapter.ViewHolder>() {

        fun updateItems(newItems: List<SeriesCategoryItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val txtName: TextView = view.findViewById(R.id.txtCategoryName)
            val txtCount: TextView = view.findViewById(R.id.txtCategoryCount)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val cat = items[position]
            applyCategoryStyle(holder, cat)

            holder.itemView.setOnFocusChangeListener { _, _ ->
                applyCategoryStyle(holder, cat)
            }

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
                                binding.btnOpenSeriesSearch.isFocusable = true
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

        private fun applyCategoryStyle(holder: ViewHolder, cat: SeriesCategoryItem) {
            val isSelected = (cat.id == selectedCategoryId)
            val isFocused = holder.itemView.isFocused

            if (cat.isProviderHeader) {
                val isExpanded = (expandedProviderKey == cat.providerKey)
                val arrow = if (isExpanded) "▾  " else "▸  "
                holder.txtName.text = arrow + cat.title
                holder.txtName.textSize = 12.5f
                holder.txtName.setPadding(dpToPx(4), 0, 0, 0)
            } else if (cat.isSubItem) {
                holder.txtName.text = cat.title
                holder.txtName.textSize = 11.5f
                holder.txtName.setPadding(dpToPx(16), 0, 0, 0)
            } else {
                holder.txtName.text = cat.title
                holder.txtName.textSize = 12f
                holder.txtName.setPadding(dpToPx(4), 0, 0, 0)
            }

            val count = if (cat.isProviderHeader && cat.providerKey != null) {
                categoryCounts["${cat.providerKey}_TOP"] ?: categoryCounts[cat.id]
            } else {
                categoryCounts[cat.id]
            }

            if (count != null && count > 0) {
                holder.txtCount.visibility = View.VISIBLE
                holder.txtCount.text = count.toString()
                holder.txtCount.setTextColor(
                    if (isFocused || isSelected) Color.parseColor("#FFFFFF") else Color.parseColor("#8E9297")
                )
            } else {
                holder.txtCount.visibility = View.GONE
            }

            holder.txtName.setTextColor(
                if (isFocused || isSelected) Color.parseColor("#FFFFFF") else Color.parseColor("#B0B0B0")
            )

            val drawable = GradientDrawable().apply {
                cornerRadius = dpToPx(6).toFloat()
                when {
                    isFocused -> {
                        if (isSelected) {
                            setColor(Color.parseColor("#3E271E"))
                        } else {
                            setColor(Color.parseColor("#2A2B32"))
                        }
                        setStroke(dpToPx(3), Color.parseColor("#C5866D"))
                    }
                    isSelected -> {
                        setColor(Color.parseColor("#352219"))
                        setStroke(dpToPx(1.5f.toInt()), Color.parseColor("#6B3F2E"))
                    }
                    cat.isProviderHeader -> {
                        setColor(Color.parseColor("#1B1C22"))
                        setStroke(dpToPx(1), Color.parseColor("#292A32"))
                    }
                    else -> {
                        setColor(Color.parseColor("#17181C"))
                        setStroke(dpToPx(1), Color.parseColor("#23242A"))
                    }
                }
            }
            holder.itemView.background = drawable
        }

        override fun getItemCount() = items.size
    }

    inner class SeriesAdapter(
        private val items: List<SeriesItem>,
        private val onFocus: (SeriesItem, Int) -> Unit,
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
                holder.txtTitle.isSelected = hasFocus
                if (hasFocus) onFocus(s, position)
            }

            holder.itemView.setOnClickListener { onClick(s) }
        }

        override fun getItemCount() = items.size
    }
}
