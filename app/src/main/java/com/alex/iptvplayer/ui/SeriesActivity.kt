package com.alex.iptvplayer.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
        binding.editSeriesSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                searchJob?.cancel()
                val q = s?.toString()?.trim() ?: ""
                searchJob = lifecycleScope.launch {
                    delay(350) // Debounce: Sauberes Tippen und Löschen einzelner Zeichen
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
        binding.recyclerSeriesCategories.adapter = SeriesCategoryAdapter(filtered, selectedCategoryId) { cat ->
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

    inner class SeriesCategoryAdapter(
        private val items: List<Category>,
        private var activeCatId: String?,
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
            holder.itemView.isSelected = (cat.id == activeCatId)

            // Kategorie wird AUSSCHLIESSLICH bei Klick mit OK gewechselt!
            holder.itemView.setOnClickListener {
                activeCatId = cat.id
                notifyDataSetChanged()
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
