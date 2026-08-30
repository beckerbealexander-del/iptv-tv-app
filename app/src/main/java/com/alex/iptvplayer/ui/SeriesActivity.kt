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
    private var currentFilter = LangFilter.AUTO_DE_RU_ADULT
    private var currentSeries: List<SeriesItem> = emptyList()
    private var allSeriesGlobal: List<SeriesItem> = emptyList()
    private var selectedCategoryId: String? = null
    private val categoryCache = HashMap<String, List<SeriesItem>>()

    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var heroJob: Job? = null

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
            setItemViewCacheSize(60)
        }

        setupFilterButtons()
        setupSearch()
        loadCategories()
        preloadGlobalCatalog()

        // Tastatur beim Start NICHT öffnen
        binding.recyclerSeriesCategories.post {
            binding.recyclerSeriesCategories.requestFocus()
        }
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

    private fun setupSearch() {
        // Tastatur erst öffnen wenn aktiv auf die Suche geklickt wird
        binding.editSeriesSearch.isFocusableInTouchMode = false
        binding.editSeriesSearch.setOnClickListener {
            binding.editSeriesSearch.isFocusableInTouchMode = true
            binding.editSeriesSearch.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.editSeriesSearch, InputMethodManager.SHOW_IMPLICIT)
        }

        // Tastatur schließen bei Bestätigung
        binding.editSeriesSearch.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                hideKeyboard()
                binding.recyclerSeriesGrid.requestFocus()
                true
            } else false
        }

        binding.editSeriesSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchJob?.cancel()
                val q = s?.toString()?.trim() ?: ""
                searchJob = lifecycleScope.launch {
                    delay(350)
                    if (q.isEmpty()) {
                        binding.txtSeriesCategoryTitle.text = "Serien"
                        binding.recyclerSeriesGrid.adapter = SeriesAdapter(currentSeries, { series ->
                            updateHeroBannerDebounced(series)
                        }, { series ->
                            openSeriesDetail(series)
                        })
                    } else {
                        val pool = if (allSeriesGlobal.isNotEmpty()) allSeriesGlobal else currentSeries
                        val filtered = pool.filter { it.name.contains(q, ignoreCase = true) }
                        binding.txtSeriesCategoryTitle.text = "Suchergebnisse (${filtered.size})"
                        binding.recyclerSeriesGrid.adapter = SeriesAdapter(filtered, { series ->
                            updateHeroBannerDebounced(series)
                        }, { series ->
                            openSeriesDetail(series)
                        })
                    }
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.editSeriesSearch.windowToken, 0)
        binding.editSeriesSearch.isFocusableInTouchMode = false
        binding.editSeriesSearch.clearFocus()
    }

    private fun setupFilterButtons() {
        binding.btnSeriesFilterDe.setOnClickListener { applyFilter(LangFilter.DE) }
        binding.btnSeriesFilterRu.setOnClickListener { applyFilter(LangFilter.RU) }
        binding.btnSeriesFilterAll.setOnClickListener { applyFilter(LangFilter.ALL) }
    }

    private fun applyFilter(filter: LangFilter) {
        currentFilter = filter
        val filtered = client.filterCategories(allCategories, filter).toMutableList()
        if (filtered.none { it.id == "ALL_SERIES" }) {
            filtered.add(0, Category(id = "ALL_SERIES", name = "✨ Alle Serien"))
        }
        binding.recyclerSeriesCategories.adapter = SeriesCategoryAdapter(filtered) { cat ->
            loadSeries(cat)
        }
        if (filtered.isNotEmpty() && selectedCategoryId == null) {
            loadSeries(filtered[0])
        }
    }

    private fun loadCategories() {
        binding.progressSeriesCats.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                allCategories = client.getSeriesCategories()
                binding.progressSeriesCats.visibility = View.GONE
                applyFilter(currentFilter)
            } catch (e: Exception) {
                binding.progressSeriesCats.visibility = View.GONE
                Toast.makeText(this@SeriesActivity, "Fehler: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadSeries(category: Category) {
        // Suche bei Kategorie-Klick sofort beenden
        if (binding.editSeriesSearch.text.isNotEmpty()) {
            binding.editSeriesSearch.setText("")
            hideKeyboard()
        }

        if (selectedCategoryId == category.id && currentSeries.isNotEmpty()) return
        selectedCategoryId = category.id

        binding.txtSeriesCategoryTitle.text = category.name

        // Blitzschnelles Umschalten über Cache (0ms Wartezeit)
        val cached = categoryCache[category.id]
        if (cached != null) {
            currentSeries = cached
            binding.progressSeries.visibility = View.GONE
            binding.recyclerSeriesGrid.adapter = SeriesAdapter(cached, { series ->
                updateHeroBannerDebounced(series)
            }, { series ->
                openSeriesDetail(series)
            })
            if (cached.isNotEmpty()) {
                updateHeroBanner(cached[0])
            }
            return
        }

        binding.progressSeries.visibility = View.VISIBLE
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            try {
                val list = client.getSeries(category.id)
                categoryCache[category.id] = list
                currentSeries = list
                binding.progressSeries.visibility = View.GONE
                binding.recyclerSeriesGrid.adapter = SeriesAdapter(list, { series ->
                    updateHeroBannerDebounced(series)
                }, { series ->
                    openSeriesDetail(series)
                })

                if (list.isNotEmpty()) {
                    updateHeroBanner(list[0])
                }
            } catch (e: Exception) {
                binding.progressSeries.visibility = View.GONE
                Toast.makeText(this@SeriesActivity, "Fehler beim Laden: ${e.message}", Toast.LENGTH_SHORT).show()
            }
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

    // --- Absolute Fokus-Verriegelung: In der Titelauswahl bleibt der Cursor 100% in der Grid ---
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val focused = currentFocus
            val isGrid = isViewInView(focused, binding.recyclerSeriesGrid)

            if (isGrid) {
                val gridPos = getFocusedGridPosition(focused)
                val total = (binding.recyclerSeriesGrid.adapter?.itemCount ?: 0)

                when (event.keyCode) {
                    KeyEvent.KEYCODE_BACK -> {
                        // Wechsel zurück zur Kategorie-Auswahl
                        val catHolder = binding.recyclerSeriesCategories.findViewHolderForAdapterPosition(0)
                        catHolder?.itemView?.requestFocus() ?: binding.recyclerSeriesCategories.requestFocus()
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (gridPos % 5 == 0) return true // Blockiert Ausbrechen nach links
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (gridPos % 5 == 4 || gridPos == total - 1) return true // Blockiert Ausbrechen nach rechts
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        if (gridPos < 5) return true // Blockiert Ausbrechen nach oben
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (gridPos >= total - 5) return true // Blockiert Ausbrechen nach unten
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
                selectedCategoryId = cat.id
                holder.itemView.requestFocus()
                onSelect(cat)
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
