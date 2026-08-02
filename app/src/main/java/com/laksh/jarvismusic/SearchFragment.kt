package com.laksh.jarvismusic

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.laksh.jarvismusic.api.RetrofitInstance
import kotlinx.coroutines.launch

class SearchFragment : Fragment() {

    private lateinit var etSearch: EditText
    private lateinit var layoutHistory: LinearLayout
    private lateinit var listHistory: ListView
    private lateinit var rvResults: RecyclerView

    private val PREFS_NAME = "JarvisSearchHistory"
    private val HISTORY_KEY = "history_list"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_search, container, false)

        etSearch = view.findViewById(R.id.et_search_query)
        layoutHistory = view.findViewById(R.id.layout_history)
        listHistory = view.findViewById(R.id.list_history)
        rvResults = view.findViewById(R.id.rv_search_results)

        loadSearchHistory()

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {}
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (s.isNullOrEmpty()) {
                    layoutHistory.visibility = View.VISIBLE
                    rvResults.visibility = View.GONE
                }
            }
        })

        etSearch.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = v.text.toString().trim()
                if (query.isNotEmpty()) {
                    saveSearchQuery(query)
                    layoutHistory.visibility = View.GONE
                    rvResults.visibility = View.VISIBLE
                    performSearch(query)
                }
                true
            } else {
                false
            }
        }

        return view
    }

    private fun performSearch(query: String) {
        rvResults.layoutManager = LinearLayoutManager(requireContext())

        // 🔥 FIX: Coroutine instead of .enqueue()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Call the API directly
                val songs = RetrofitInstance.api.searchSongs(query, 20)

                if (isAdded) {
                    if (!songs.isNullOrEmpty()) {
                        rvResults.adapter = SongAdapter(songs) { clickedSong ->
                            (activity as? MainActivity)?.setQueueAndPlay(clickedSong, songs)
                        }
                    } else {
                        Toast.makeText(requireContext(), "No results found", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                if (isAdded) {
                    Toast.makeText(requireContext(), "Network Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun saveSearchQuery(query: String) {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val historyString = prefs.getString(HISTORY_KEY, "") ?: ""

        val historyList = if (historyString.isEmpty()) mutableListOf() else historyString.split("|||").toMutableList()

        historyList.remove(query)
        historyList.add(0, query)

        if (historyList.size > 10) {
            historyList.removeLast()
        }

        prefs.edit().putString(HISTORY_KEY, historyList.joinToString("|||")).apply()
        loadSearchHistory()
    }

    private fun loadSearchHistory() {
        val prefs = try { requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) } catch (e: Exception) { null }
        val historyString = prefs?.getString(HISTORY_KEY, "") ?: ""

        val historyList = if (historyString.isEmpty()) emptyList() else historyString.split("|||")

        val adapter = object : ArrayAdapter<String>(requireContext(), android.R.layout.simple_list_item_1, historyList) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val textView = view.findViewById<TextView>(android.R.id.text1)
                textView.setTextColor(Color.WHITE)
                return view
            }
        }

        listHistory.adapter = adapter

        listHistory.setOnItemClickListener { _, _, position, _ ->
            val clickedQuery = historyList[position]
            etSearch.setText(clickedQuery)
            etSearch.setSelection(clickedQuery.length)

            layoutHistory.visibility = View.GONE
            rvResults.visibility = View.VISIBLE
            performSearch(clickedQuery)
        }
    }
}