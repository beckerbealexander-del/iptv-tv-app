package com.alex.iptvplayer.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.alex.iptvplayer.R
import com.alex.iptvplayer.data.HistoryManager
import com.alex.iptvplayer.databinding.ActivitySearchBinding

class SearchActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySearchBinding
    private lateinit var historyManager: HistoryManager
    private var searchType: String = "LIVE"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        historyManager = HistoryManager(this)
        searchType = intent.getStringExtra("SEARCH_TYPE") ?: "LIVE"

        val title = when (searchType) {
            "VOD" -> "🎬 Filme durchsuchen"
            "SERIES" -> "🍿 Serien durchsuchen"
            else -> "📺 Live TV Sender durchsuchen"
        }
        binding.txtSearchTitle.text = title

        binding.recyclerSearchHistory.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)

        loadSearchHistory()

        binding.btnSearchClear.setOnClickListener {
            binding.editSearchQuery.setText("")
        }

        binding.btnSearchSubmit.setOnClickListener {
            val q = binding.editSearchQuery.text.toString().trim()
            if (q.isNotEmpty()) {
                submitSearch(q)
            }
        }

        binding.editSearchQuery.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO ||
                (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)) {
                val q = binding.editSearchQuery.text.toString().trim()
                if (q.isNotEmpty()) {
                    submitSearch(q)
                }
                true
            } else false
        }

        binding.editSearchQuery.post {
            binding.editSearchQuery.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.editSearchQuery, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun loadSearchHistory() {
        val history = historyManager.getSearchHistory(searchType)
        if (history.isEmpty()) {
            binding.layoutSearchHistory.visibility = View.GONE
        } else {
            binding.layoutSearchHistory.visibility = View.VISIBLE
            binding.recyclerSearchHistory.adapter = HistoryChipAdapter(history) { query ->
                submitSearch(query)
            }
        }
    }

    private fun submitSearch(query: String) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.editSearchQuery.windowToken, 0)

        historyManager.addSearchQuery(searchType, query)
        val data = Intent().apply {
            putExtra("SEARCH_QUERY", query)
            putExtra("SEARCH_TYPE", searchType)
        }
        setResult(RESULT_OK, data)
        finish()
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
            holder.itemView.setOnClickListener {
                onClick(q)
            }
            holder.itemView.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
                    onClick(q)
                    return@setOnKeyListener true
                }
                false
            }
        }

        override fun getItemCount() = items.size
    }
}
