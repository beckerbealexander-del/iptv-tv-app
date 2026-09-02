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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.alex.iptvplayer.R
import com.alex.iptvplayer.data.Category
import com.alex.iptvplayer.data.LangFilter
import com.alex.iptvplayer.data.VodStream
import com.alex.iptvplayer.data.XtreamClient
import com.alex.iptvplayer.databinding.ActivityVodBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class VodActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVodBinding
    private lateinit var client: XtreamClient
    private var allCategories: List<Category> = emptyList()
    private var displayedCategories: List<Category> = emptyList()
    private var currentFilter = LangFilter.AUTO_DE_RU_ADULT
    private var currentMovies: List<VodStream> = emptyList()
    private var rawCategoryMovies: List<VodStream> = emptyList()
    private var allMoviesGlobal: List<VodStream> = emptyList()
    private var selectedCategoryId: String? = null
    private val categoryCache = HashMap<String, List<VodStream>>()

    private var currentSortMode: String = "DEFAULT"

    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var heroJob: Job? = null

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

        binding.txtVodCategoryTitle.text = "👈 Wähle eine Kategorie oder nutze die Suche"

        setupFilterButtons()
        setupSortButtons()
        setupSearch()
        loadCategories()
        preloadGlobalCatalog()

        binding.recyclerVodCategories.post {
            binding.recyclerVodCategories.requestFocus()
        }
    }

    private fun preloadGlobalCatalog() {
        lifecycleScope.launch {
            try {
                allMoviesGlobal = client.getAllVodStreams()
            } catch (e: Exception) {
                // Silent
            }
        }
    }

    private fun setupSortButtons() {
        binding.btnSortDefault.setOnClickListener { applySorting("DEFAULT") }
        binding.btnSortRating.setOnClickListener { applySorting("RATING") }
        binding.btnSortYear.setOnClickListener { applySorting("YEAR") }
        binding.btnSortAlpha.setOnClickListener { applySorting("ALPHA") }
    }

    private fun applySorting(mode: String) {
        currentSortMode = mode
        if (rawCategoryMovies.isEmpty()) return

        val sorted = when (mode) {
            "RATING" -> rawCategoryMovies.sortedByDescending { it.rating?.toFloatOrNull() ?: 0f }
            "YEAR" -> rawCategoryMovies.sortedByDescending { 
                val year = Regex("\\b(19\\d\\d|20\\d\\d)\\b").find(it.name)?.value?.toIntOrNull() ?: 0
                year
            }
            "ALPHA" -> rawCategoryMovies.sortedBy { it.name.lowercase() }
            else -> rawCategoryMovies
        }
        currentMovies = sorted
        binding.recyclerVodGrid.adapter = MovieAdapter(sorted, { movie ->
            updateHeroBannerDebounced(movie)
        }, { movie ->
            playMovie(movie)
        })
        if (sorted.isNotEmpty()) {
            updateHeroBanner(sorted[0])
        }
    }

    private fun setupSearch() {
        binding.editVodSearch.isFocusable = true
        binding.editVodSearch.isFocusableInTouchMode = false
        binding.editVodSearch.setOnClickListener {
            binding.editVodSearch.isFocusableInTouchMode = true
            binding.editVodSearch.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.editVodSearch, InputMethodManager.SHOW_IMPLICIT)
        }

        binding.editVodSearch.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                hideKeyboard()
            }
        }

        binding.editVodSearch.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                hideKeyboard()
                binding.recyclerVodGrid.requestFocus()
                true
            } else false
        }

        // 1. Typen-isolierte globale Suche: Durchsucht ausnahmslos ALLE Filme über alle Kategorien hinweg mit contains()
        binding.editVodSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchJob?.cancel()
                val q = s?.toString()?.trim() ?: ""
                searchJob = lifecycleScope.launch {
                    delay(300)
                    if (q.isEmpty()) {
                        binding.txtVodCategoryTitle.text = "Filme"
                        rawCategoryMovies = currentMovies
                        applySorting(currentSortMode)
                    } else {
                        val pool = if (allMoviesGlobal.isNotEmpty()) allMoviesGlobal else currentMovies
                        val filtered = pool.filter { it.name.contains(q, ignoreCase = true) }
                        binding.txtVodCategoryTitle.text = "Suchergebnisse (${filtered.size})"
                        rawCategoryMovies = filtered
                        applySorting(currentSortMode)
                    }
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.editVodSearch.windowToken, 0)
        binding.editVodSearch.isFocusableInTouchMode = false
        binding.editVodSearch.clearFocus()
    }

    // 2. Fokus-Erhalt beim Klick auf Filter (DE / RU / ALLE bleibt auf dem Filter-Button)
    private fun setupFilterButtons() {
        binding.btnVodFilterDe.setOnClickListener {
            applyFilter(LangFilter.DE)
            binding.btnVodFilterDe.requestFocus()
        }
        binding.btnVodFilterRu.setOnClickListener {
            applyFilter(LangFilter.RU)
            binding.btnVodFilterRu.requestFocus()
        }
        binding.btnVodFilterAll.setOnClickListener {
            applyFilter(LangFilter.ALL)
            binding.btnVodFilterAll.requestFocus()
        }
    }

    private fun applyFilter(filter: LangFilter) {
        currentFilter = filter
        val filtered = client.filterCategories(allCategories, filter).toMutableList()
        if (filtered.none { it.id == "ALL_MOVIES" }) {
            filtered.add(0, Category(id = "ALL_MOVIES", name = "✨ Alle Filme"))
        }
        displayedCategories = filtered
        binding.recyclerVodCategories.adapter = VodCategoryAdapter(filtered) { cat ->
            loadMovies(cat)
        }
    }

    private fun loadCategories() {
        binding.progressVodCats.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                allCategories = client.getVodCategories()
                binding.progressVodCats.visibility = View.GONE
                applyFilter(currentFilter)
            } catch (e: Exception) {
                binding.progressVodCats.visibility = View.GONE
                Toast.makeText(this@VodActivity, "Fehler: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadMovies(category: Category) {
        if (binding.editVodSearch.text.isNotEmpty()) {
            binding.editVodSearch.setText("")
            hideKeyboard()
        }

        selectedCategoryId = category.id
        binding.recyclerVodCategories.adapter?.notifyDataSetChanged()
        binding.txtVodCategoryTitle.text = category.name

        val cached = categoryCache[category.id]
        if (cached != null) {
            rawCategoryMovies = cached
            binding.progressVod.visibility = View.GONE
            applySorting(currentSortMode)
            return
        }

        binding.progressVod.visibility = View.VISIBLE
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            try {
                val list = client.getVodStreams(category.id)
                categoryCache[category.id] = list
                rawCategoryMovies = list
                binding.progressVod.visibility = View.GONE
                applySorting(currentSortMode)
            } catch (e: Exception) {
                binding.progressVod.visibility = View.GONE
                Toast.makeText(this@VodActivity, "Fehler beim Laden: ${e.message}", Toast.LENGTH_SHORT).show()
            }
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
        binding.txtHeroRating.text = if (!movie.rating.isNullOrEmpty()) "⭐ ${movie.rating} | VOD" else "⭐ 8.0 | VOD"

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
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("STREAM_URL", client.getVodStreamUrl(movie.streamId, movie.containerExtension ?: "mp4"))
            putExtra("STREAM_NAME", movie.name)
            putExtra("POSTER_URL", movie.streamIcon)
            putExtra("STREAM_ID", movie.streamId)
            putExtra("STREAM_TYPE", "VOD")
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

    // --- Hard-Lock D-Pad Navigation in der Grid ---
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
                        // Strikter Hard-Lock: Niemals aus der Grid nach unten ausbrechen
                        val nextPos = gridPos + 5
                        if (nextPos < total) {
                            binding.recyclerVodGrid.scrollToPosition(nextPos)
                            binding.recyclerVodGrid.post {
                                binding.recyclerVodGrid.findViewHolderForAdapterPosition(nextPos)?.itemView?.requestFocus()
                            }
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        // Strikter Hard-Lock: Oben in die Sortierleiste, niemals horizontal ausbrechen
                        val prevPos = gridPos - 5
                        if (prevPos >= 0) {
                            binding.recyclerVodGrid.scrollToPosition(prevPos)
                            binding.recyclerVodGrid.post {
                                binding.recyclerVodGrid.findViewHolderForAdapterPosition(prevPos)?.itemView?.requestFocus()
                            }
                        } else {
                            binding.btnSortDefault.requestFocus()
                        }
                        return true
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
        private val items: List<Category>,
        private val onSelect: (Category) -> Unit
    ) : RecyclerView.Adapter<VodCategoryAdapter.ViewHolder>() {

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
                            binding.recyclerVodGrid.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
                                ?: binding.recyclerVodGrid.requestFocus()
                            return@setOnKeyListener true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            return@setOnKeyListener true
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (position == 0) {
                                binding.btnVodFilterDe.requestFocus()
                                return@setOnKeyListener true
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (position == items.size - 1) {
                                return@setOnKeyListener true // Hard-Lock unten
                            }
                        }
                    }
                }
                false
            }
        }

        override fun getItemCount() = items.size
    }

    inner class MovieAdapter(
        private val items: List<VodStream>,
        private val onFocus: (VodStream) -> Unit,
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
                if (hasFocus) onFocus(movie)
            }

            holder.itemView.setOnClickListener { onClick(movie) }
        }

        override fun getItemCount() = items.size
    }
}
