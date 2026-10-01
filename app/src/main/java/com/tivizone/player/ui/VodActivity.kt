package com.tivizone.player.ui

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
import com.tivizone.player.R
import com.tivizone.player.data.Category
import com.tivizone.player.data.LangFilter
import com.tivizone.player.data.VodStream
import com.tivizone.player.data.XtreamClient
import com.tivizone.player.databinding.ActivityVodBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
data class VodCategoryItem(
    val id: String,
    val title: String,
    val isProviderHeader: Boolean = false,
    val providerKey: String? = null,
    val genreKey: String? = null,
    val isSubItem: Boolean = false
)

class VodActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVodBinding
    private lateinit var client: XtreamClient
    private var displayedCategories: List<VodCategoryItem> = emptyList()
    private var currentMovies: List<VodStream> = emptyList()
    private var rawCategoryMovies: List<VodStream> = emptyList()
    private var allMoviesGlobal: List<VodStream> = emptyList()
    private var expandedProviderKey: String? = "netflix"
    private var selectedCategoryId: String = "netflix_TOP"
    private val categoryCache get() = com.tivizone.player.data.TmdbProviderCatalogManager.movieCategoryCache
    private val categoryCounts get() = com.tivizone.player.data.TmdbProviderCatalogManager.movieCategoryCounts

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
        binding = ActivityVodBinding.inflate(layoutInflater)
        setContentView(binding.root)

        client = XtreamClient(this)

        binding.recyclerVodCategories.apply {
            layoutManager = LinearLayoutManager(this@VodActivity)
            setHasFixedSize(true)
            setItemViewCacheSize(60)
        }

        binding.recyclerVodGrid.apply {
            layoutManager = GridLayoutManager(this@VodActivity, 5)
            setHasFixedSize(true)
            setItemViewCacheSize(80)
        }

        binding.btnOpenVodSearch.isFocusable = false
        binding.btnOpenVodSearch.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                binding.btnOpenVodSearch.isFocusable = false
            }
        }
        binding.btnOpenVodSearch.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                focusSelectedCategory()
                return@setOnKeyListener true
            }
            false
        }

        binding.btnOpenVodSearch.setOnClickListener {
            val intent = Intent(this, SearchActivity::class.java).apply {
                putExtra("SEARCH_TYPE", "VOD")
            }
            searchLauncher.launch(intent)
        }

        // 1. Sofort aus lokalem Disk-Cache laden (Ladezeit < 10ms)
        allMoviesGlobal = cachedAllMoviesGlobal ?: client.loadCachedVodCatalog().also { cachedAllMoviesGlobal = it }
        loadCategories()
        // 2. Im Hintergrund synchronisieren falls noetig
        preloadGlobalCatalog()
        preloadCategoryCounts()
    }

    companion object {
        var cachedAllMoviesGlobal: List<VodStream>? = null

        val PROVIDERS = listOf(
            Pair("netflix", "Netflix"),
            Pair("prime", "Amazon Prime"),
            Pair("disney", "Disney+"),
            Pair("apple", "Apple TV+"),
            Pair("paramount", "Paramount+"),
            Pair("other", "Weitere Filme")
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
            Pair("ADULT_MOVIES", "Privat"),
            Pair("ALL_MOVIES", "Alle Filme")
        )
    }

    private fun preloadGlobalCatalog() {
        lifecycleScope.launch {
            try {
                val fresh = client.getGermanVodStreamsStreamed()
                if (fresh.isNotEmpty()) {
                    allMoviesGlobal = fresh
                    cachedAllMoviesGlobal = fresh
                    categoryCounts["ALL_MOVIES"] = fresh.size
                    categoryCounts["GENRE_RUSSIAN"] = fresh.count { client.isRussianMedia(it.name, it.categoryId) }
                    binding.recyclerVodCategories.adapter?.notifyDataSetChanged()
                    preloadCategoryCounts()
                }
            } catch (e: Exception) {
                // Silent
            }
        }
    }

    private fun preloadCategoryCounts() {
        lifecycleScope.launch(Dispatchers.Default) {
            val pool = if (allMoviesGlobal.isNotEmpty()) allMoviesGlobal else client.loadCachedVodCatalog()
            val germanPool = pool.filter { client.isGermanMedia(it.name) }
            if (germanPool.isEmpty()) return@launch

            val provKey = expandedProviderKey ?: "netflix"
            for (genre in GENRE_ITEMS) {
                val cacheKey = "${provKey}_${genre.first}"
                if (!categoryCounts.containsKey(cacheKey)) {
                    com.tivizone.player.data.TmdbProviderCatalogManager.filterMoviesByProviderAndGenre(
                        context = this@VodActivity,
                        providerKey = provKey,
                        genreKey = genre.first,
                        germanMovies = germanPool
                    )
                }
            }
            withContext(Dispatchers.Main) {
                binding.recyclerVodCategories.adapter?.notifyDataSetChanged()
            }
        }
    }

    private fun applySorting() {
        if (rawCategoryMovies.isEmpty()) {
            binding.txtVodCategoryCounter.text = ""
            return
        }
        currentMovies = rawCategoryMovies
        binding.txtVodCategoryCounter.text = "1 / ${currentMovies.size}"
        binding.recyclerVodGrid.adapter = MovieAdapter(currentMovies, { movie, position ->
            binding.txtVodCategoryCounter.text = "${position + 1} / ${currentMovies.size}"
            updateHeroBannerDebounced(movie)
        }, { movie ->
            playMovie(movie)
        })
        if (currentMovies.isNotEmpty()) {
            updateHeroBanner(currentMovies[0])
        }
    }

    // 5. SUCHE: DYNAMISCHE KATEGORIE "Aktuelle Suche" AN INDEX 0
    private fun applySearchQuery(query: String) {
        currentSearchQuery = query
        selectedCategoryId = "CURRENT_SEARCH"
        expandedProviderKey = null
        displayedCategories = buildCategoryList()
        (binding.recyclerVodCategories.adapter as? VodCategoryAdapter)?.updateItems(displayedCategories)

        val pool = if (allMoviesGlobal.isNotEmpty()) allMoviesGlobal else currentMovies
        val filtered = pool.filter { it.name.contains(query, ignoreCase = true) }
        categoryCounts["CURRENT_SEARCH"] = filtered.size
        binding.txtVodCategoryTitle.text = "Suchergebnisse: „$query“"
        binding.txtVodCategoryCounter.text = if (filtered.isNotEmpty()) "1 / ${filtered.size}" else ""
        rawCategoryMovies = filtered
        binding.recyclerVodCategories.adapter?.notifyDataSetChanged()
        applySorting()

        // Fokus sofort auf das erste Suchergebnis
        focusFirstMovie()
    }

    private fun buildCategoryList(): List<VodCategoryItem> {
        val list = mutableListOf<VodCategoryItem>()
        if (currentSearchQuery != null) {
            list.add(VodCategoryItem(id = "CURRENT_SEARCH", title = "Aktuelle Suche"))
        }

        for (prov in PROVIDERS) {
            val isExpanded = (expandedProviderKey == prov.first)
            list.add(
                VodCategoryItem(
                    id = "prov_${prov.first}",
                    title = prov.second,
                    isProviderHeader = true,
                    providerKey = prov.first
                )
            )
            if (isExpanded) {
                for (genre in GENRE_ITEMS) {
                    list.add(
                        VodCategoryItem(
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
                VodCategoryItem(
                    id = staticCat.first,
                    title = staticCat.second,
                    isProviderHeader = false
                )
            )
        }

        return list
    }

    private fun loadCategories() {
        binding.progressVodCats.visibility = View.GONE
        if (allMoviesGlobal.isNotEmpty()) {
            categoryCounts["ALL_MOVIES"] = allMoviesGlobal.size
            categoryCounts["GENRE_RUSSIAN"] = allMoviesGlobal.count { client.isRussianMedia(it.name, it.categoryId) }
        }

        displayedCategories = buildCategoryList()
        binding.recyclerVodCategories.adapter = VodCategoryAdapter(displayedCategories) { cat ->
            onCategoryClicked(cat)
        }

        // Beim Start immer Netflix Top vorauswählen und laden!
        val initialItem = displayedCategories.firstOrNull { it.id == "netflix_TOP" }
            ?: displayedCategories.firstOrNull()
        if (initialItem != null) {
            loadMovies(initialItem, autoFocusGrid = false)
        }

        // Fokus sofort und ruhig auf die ausgewählte Kategorie setzen (ohne Suchen-Feld-Sprung)
        binding.recyclerVodCategories.post {
            focusSelectedCategory()
        }
    }

    private fun onCategoryClicked(item: VodCategoryItem) {
        if (item.isProviderHeader) {
            if (expandedProviderKey == item.providerKey) {
                // Bei Klick auf bereits geöffneten Anbieter -> Zuklappen
                expandedProviderKey = null
                displayedCategories = buildCategoryList()
                (binding.recyclerVodCategories.adapter as? VodCategoryAdapter)?.updateItems(displayedCategories)
            } else {
                // Anbieter aufklappen und automatisch dessen "Top" Titel laden
                expandedProviderKey = item.providerKey
                val topGenreId = "${item.providerKey}_TOP"
                selectedCategoryId = topGenreId
                displayedCategories = buildCategoryList()
                (binding.recyclerVodCategories.adapter as? VodCategoryAdapter)?.updateItems(displayedCategories)

                val targetItem = displayedCategories.firstOrNull { it.id == topGenreId } ?: item
                loadMovies(targetItem, autoFocusGrid = false)
                preloadCategoryCounts()
            }
        } else {
            selectedCategoryId = item.id
            if (!item.isSubItem && item.id != "CURRENT_SEARCH") {
                // Bei Klick auf Russian, Privat oder Alle Filme -> Provider zuklappen
                expandedProviderKey = null
                displayedCategories = buildCategoryList()
                (binding.recyclerVodCategories.adapter as? VodCategoryAdapter)?.updateItems(displayedCategories)
            } else {
                binding.recyclerVodCategories.adapter?.notifyDataSetChanged()
            }
            loadMovies(item, autoFocusGrid = false)
        }
    }

    // 6. KEIN SUCH-SPRUNG: Fokus springt bei Bedarf auf das erste Media-Item der Kategorie
    private fun loadMovies(category: VodCategoryItem, autoFocusGrid: Boolean = true) {
        if (category.id == "CURRENT_SEARCH" && currentSearchQuery != null) {
            applySearchQuery(currentSearchQuery!!)
            return
        }

        selectedCategoryId = category.id
        binding.recyclerVodCategories.adapter?.notifyDataSetChanged()

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
            rawCategoryMovies = cached
            categoryCounts[category.id] = cached.size
            binding.txtVodCategoryTitle.text = headerTitle
            binding.txtVodCategoryCounter.text = if (cached.isNotEmpty()) "1 / ${cached.size}" else ""
            binding.recyclerVodCategories.adapter?.notifyDataSetChanged()
            binding.progressVod.visibility = View.GONE
            applySorting()
            if (autoFocusGrid) {
                focusFirstMovie()
            } else {
                focusSelectedCategory()
            }
            return
        }

        binding.txtVodCategoryTitle.text = headerTitle
        binding.txtVodCategoryCounter.text = ""
        binding.progressVod.visibility = View.VISIBLE
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            try {
                val list = if (category.id == "ADULT_MOVIES") {
                    client.getVodStreams("382")
                } else {
                    val allGlobal = if (allMoviesGlobal.isNotEmpty()) allMoviesGlobal else {
                        val streamed = client.getGermanVodStreamsStreamed()
                        allMoviesGlobal = streamed
                        cachedAllMoviesGlobal = streamed
                        streamed
                    }
                    val germanPool = allGlobal.filter { client.isGermanMedia(it.name) }

                    when {
                        category.providerKey != null -> {
                            val gKey = category.genreKey ?: "TOP"
                            com.tivizone.player.data.TmdbProviderCatalogManager.filterMoviesByProviderAndGenre(
                                context = this@VodActivity,
                                providerKey = category.providerKey,
                                genreKey = gKey,
                                germanMovies = germanPool
                            )
                        }
                        category.id == "GENRE_RUSSIAN" -> {
                            val ruList = allGlobal.filter { client.isRussianMedia(it.name, it.categoryId) }
                            if (ruList.isNotEmpty()) ruList else {
                                val cat81 = client.getVodStreams("81")
                                cat81
                            }
                        }
                        category.id == "ALL_MOVIES" -> allGlobal
                        else -> allGlobal
                    }
                }

                categoryCache[category.id] = list
                categoryCounts[category.id] = list.size
                rawCategoryMovies = list
                binding.txtVodCategoryTitle.text = headerTitle
                binding.txtVodCategoryCounter.text = if (list.isNotEmpty()) "1 / ${list.size}" else ""
                binding.recyclerVodCategories.adapter?.notifyDataSetChanged()
                binding.progressVod.visibility = View.GONE
                applySorting()
                if (autoFocusGrid) {
                    focusFirstMovie()
                } else {
                    focusSelectedCategory()
                }
            } catch (e: Exception) {
                binding.progressVod.visibility = View.GONE
                Toast.makeText(this@VodActivity, "Fehler beim Laden: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun matchesKeywords(name: String, keywords: List<String>): Boolean {
        val lower = name.lowercase()
        return keywords.any { lower.contains(it) }
    }

    private fun focusFirstMovie() {
        binding.recyclerVodGrid.scrollToPosition(0)
        binding.recyclerVodGrid.post {
            binding.recyclerVodGrid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    private fun updateHeroBannerDebounced(movie: VodStream) {
        heroJob?.cancel()
        heroJob = lifecycleScope.launch {
            delay(150)
            updateHeroBanner(movie)
        }
    }

    private fun updateHeroBanner(movie: VodStream) {
        binding.txtHeroTitle.text = movie.name
        binding.txtHeroRating.text = if (!movie.rating.isNullOrEmpty()) "★ ${movie.rating} | VOD" else "★ 8.0 | VOD"

        if (!movie.streamIcon.isNullOrEmpty()) {
            Glide.with(this)
                .load(movie.streamIcon)
                .override(140, 190)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .placeholder(R.drawable.tv_banner)
                .into(binding.imgHeroPoster)
        } else {
            binding.imgHeroPoster.setImageResource(R.drawable.tv_banner)
        }
    }

    private fun playMovie(movie: VodStream) {
        val intent = Intent(this, MovieDetailActivity::class.java).apply {
            putExtra("VOD_STREAM", movie)
            putExtra("STREAM_ID", movie.streamId)
            putExtra("STREAM_NAME", movie.name)
            putExtra("POSTER_URL", movie.streamIcon)
            putExtra("CONTAINER_EXT", movie.containerExtension ?: "mp4")
        }
        startActivity(intent)
    }

    private fun focusSelectedCategory() {
        val catIdx = displayedCategories.indexOfFirst { it.id == selectedCategoryId }.coerceAtLeast(0)
        binding.recyclerVodCategories.scrollToPosition(catIdx)
        binding.recyclerVodCategories.post {
            val holder = binding.recyclerVodCategories.findViewHolderForAdapterPosition(catIdx)
            holder?.itemView?.requestFocus() ?: binding.recyclerVodCategories.requestFocus()
        }
    }

    // --- Hard-Lock D-Pad Navigation in der Grid & Sortierleiste ---
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val focused = currentFocus
            val isGrid = isViewInView(focused, binding.recyclerVodGrid)

            if (isGrid) {
                val gridPos = getFocusedGridPosition(focused)
                val total = (binding.recyclerVodGrid.adapter?.itemCount ?: 0)

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
                            binding.recyclerVodGrid.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus()
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (gridPos % 5 < 4 && gridPos < total - 1) {
                            val target = gridPos + 1
                            binding.recyclerVodGrid.findViewHolderForAdapterPosition(target)?.itemView?.requestFocus()
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        val nextPos = gridPos + 5
                        if (nextPos < total) {
                            binding.recyclerVodGrid.scrollToPosition(nextPos)
                            binding.recyclerVodGrid.post {
                                binding.recyclerVodGrid.findViewHolderForAdapterPosition(nextPos)?.itemView?.requestFocus()
                            }
                        }
                        return true // Hard-Lock unten
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        val prevPos = gridPos - 5
                        if (prevPos >= 0) {
                            binding.recyclerVodGrid.scrollToPosition(prevPos)
                            binding.recyclerVodGrid.post {
                                binding.recyclerVodGrid.findViewHolderForAdapterPosition(prevPos)?.itemView?.requestFocus()
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
        while (cur != null && cur != binding.recyclerVodGrid) {
            val p = cur.parent
            if (p == binding.recyclerVodGrid) {
                return binding.recyclerVodGrid.getChildAdapterPosition(cur)
            }
            cur = p as? View
        }
        return -1
    }

    inner class VodCategoryAdapter(
        private var items: List<VodCategoryItem>,
        private val onSelect: (VodCategoryItem) -> Unit
    ) : RecyclerView.Adapter<VodCategoryAdapter.ViewHolder>() {

        fun updateItems(newItems: List<VodCategoryItem>) {
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
                            focusFirstMovie()
                            return@setOnKeyListener true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            return@setOnKeyListener true
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (position == 0) {
                                binding.btnOpenVodSearch.isFocusable = true
                                binding.btnOpenVodSearch.requestFocus()
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

        private fun applyCategoryStyle(holder: ViewHolder, cat: VodCategoryItem) {
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

    inner class MovieAdapter(
        private val items: List<VodStream>,
        private val onFocus: (VodStream, Int) -> Unit,
        private val onClick: (VodStream) -> Unit
    ) : RecyclerView.Adapter<MovieAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val txtTitle: TextView = view.findViewById(R.id.txtPosterTitle)
            val imgPoster: ImageView = view.findViewById(R.id.imgPoster)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_poster, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val movie = items[position]
            holder.txtTitle.text = movie.name

            if (!movie.streamIcon.isNullOrEmpty()) {
                Glide.with(holder.itemView)
                    .load(movie.streamIcon)
                    .override(130, 180)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .placeholder(R.drawable.tv_banner)
                    .into(holder.imgPoster)
            } else {
                holder.imgPoster.setImageResource(R.drawable.tv_banner)
            }

            holder.itemView.setOnFocusChangeListener { _, hasFocus ->
                holder.txtTitle.isSelected = hasFocus
                if (hasFocus) onFocus(movie, position)
            }

            holder.itemView.setOnClickListener { onClick(movie) }
        }

        override fun getItemCount() = items.size
    }
}
