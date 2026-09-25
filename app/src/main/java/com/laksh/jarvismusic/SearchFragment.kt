package com.laksh.jarvismusic

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.laksh.jarvismusic.api.ApiSong
import com.laksh.jarvismusic.api.RetrofitInstance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SearchFragment : Fragment(R.layout.fragment_search), ScrollToTop {

    private data class Genre(val name: String, val query: String, val color: Int)

    private val genres = listOf(
        Genre("Bollywood", "Bollywood hits", Color.parseColor("#E13300")),
        Genre("Punjabi", "Punjabi hits", Color.parseColor("#1E3264")),
        Genre("Hip-Hop", "Hindi hip hop", Color.parseColor("#BC5900")),
        Genre("Romance", "romantic songs", Color.parseColor("#E8115B")),
        Genre("Party", "party songs", Color.parseColor("#8D67AB")),
        Genre("Chill", "lofi chill", Color.parseColor("#477D95")),
        Genre("Workout", "workout songs", Color.parseColor("#E91429")),
        Genre("Devotional", "bhakti songs", Color.parseColor("#DC148C")),
        Genre("Indie", "indie hindi", Color.parseColor("#608108")),
        Genre("Pop", "pop hits", Color.parseColor("#148A08")),
        Genre("Retro", "old hindi songs", Color.parseColor("#BA5D07")),
        Genre("Sad", "sad songs", Color.parseColor("#503750")),
        Genre("Haryanvi", "haryanvi songs", Color.parseColor("#27856A")),
        Genre("English", "english pop", Color.parseColor("#0D73EC")),
        Genre("Ghazal", "ghazal", Color.parseColor("#8C1932")),
        Genre("Sufi", "sufi songs", Color.parseColor("#A56752"))
    )

    private enum class Mode { BROWSE, HISTORY, LOADING, RESULTS, MESSAGE }

    private lateinit var etSearch: EditText
    private lateinit var btnClear: ImageView
    private lateinit var rvBrowse: RecyclerView
    private lateinit var rvHistory: RecyclerView
    private lateinit var rvResults: RecyclerView
    private lateinit var tvMessage: TextView
    private lateinit var progress: ProgressBar

    private lateinit var songAdapter: SongListAdapter
    private lateinit var topResultAdapter: TopResultAdapter
    private lateinit var historyAdapter: HistoryAdapter

    private var searchJob: Job? = null
    private var lastQuery: String = ""
    private var mode = Mode.BROWSE

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<View>(R.id.search_root).applyStatusBarPadding()
        etSearch = view.findViewById(R.id.et_search_query)
        btnClear = view.findViewById(R.id.btn_clear_search)
        rvBrowse = view.findViewById(R.id.rv_browse)
        rvHistory = view.findViewById(R.id.rv_history)
        rvResults = view.findViewById(R.id.rv_search_results)
        tvMessage = view.findViewById(R.id.tv_search_message)
        progress = view.findViewById(R.id.search_progress)

        setupBrowse()
        setupHistory()
        setupResults()
        setupSearchBox()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                MusicState.nowPlayingKey.collect { songAdapter.setNowPlaying(it) }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Refresh the "downloaded" markers
                MusicState.libraryVersion.collect { songAdapter.notifyItemRangeChanged(0, songAdapter.itemCount) }
            }
        }

        showMode(Mode.BROWSE)
    }

    override fun scrollToTop() {
        if (!::rvBrowse.isInitialized) return
        rvBrowse.smoothScrollToPosition(0)
        rvResults.smoothScrollToPosition(0)
    }

    // --- Search box ---

    private fun setupSearchBox() {
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim().orEmpty()
                btnClear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                if (query.isEmpty()) {
                    searchJob?.cancel()
                    lastQuery = ""
                    showIdleMode()
                } else if (query != lastQuery) {
                    // Search as you type, after a short pause
                    searchJob?.cancel()
                    searchJob = viewLifecycleOwner.lifecycleScope.launch {
                        delay(400)
                        search(query)
                    }
                }
            }
        })

        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = etSearch.text.toString().trim()
                if (query.isNotEmpty()) {
                    LocalStore.addSearch(requireContext(), query)
                    searchJob?.cancel()
                    lastQuery = ""
                    searchJob = viewLifecycleOwner.lifecycleScope.launch { search(query) }
                    hideKeyboard()
                }
                true
            } else {
                false
            }
        }

        etSearch.setOnFocusChangeListener { _, _ ->
            if (etSearch.text.isNullOrBlank()) showIdleMode()
        }

        btnClear.setOnClickListener {
            etSearch.setText("")
            etSearch.requestFocus()
            showKeyboard()
        }
    }

    private fun showIdleMode() {
        val history = LocalStore.searchHistory(requireContext())
        if (etSearch.hasFocus() && history.isNotEmpty()) {
            historyAdapter.submit(history)
            showMode(Mode.HISTORY)
        } else {
            showMode(Mode.BROWSE)
        }
    }

    private suspend fun search(query: String) {
        lastQuery = query
        // Keep old results on screen while typing instead of flashing a spinner
        if (mode != Mode.RESULTS) showMode(Mode.LOADING)
        try {
            val songs = RetrofitInstance.api.searchSongs(query, 30).distinctBy { it.key }
            if (songs.isEmpty()) {
                showMessage("Couldn't find \"$query\"\n\nTry searching again using a different spelling or keyword.")
            } else {
                topResultAdapter.song = songs.first()
                songAdapter.submit(songs)
                showMode(Mode.RESULTS)
                rvResults.scrollToPosition(0)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            showMessage("Something went wrong\n\nCheck your internet connection and try again.")
        }
    }

    private fun showMessage(text: String) {
        tvMessage.text = text
        showMode(Mode.MESSAGE)
    }

    private fun showMode(mode: Mode) {
        this.mode = mode
        rvBrowse.visibility = if (mode == Mode.BROWSE) View.VISIBLE else View.GONE
        rvHistory.visibility = if (mode == Mode.HISTORY) View.VISIBLE else View.GONE
        rvResults.visibility = if (mode == Mode.RESULTS) View.VISIBLE else View.GONE
        progress.visibility = if (mode == Mode.LOADING) View.VISIBLE else View.GONE
        tvMessage.visibility = if (mode == Mode.MESSAGE) View.VISIBLE else View.GONE
    }

    private fun hideKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etSearch.windowToken, 0)
        etSearch.clearFocus()
    }

    private fun showKeyboard() {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun rememberQuery() {
        val query = etSearch.text.toString().trim()
        if (query.isNotEmpty()) LocalStore.addSearch(requireContext(), query)
    }

    // --- Browse all ---

    private fun setupBrowse() {
        val layoutManager = GridLayoutManager(requireContext(), 2)
        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (position == 0) 2 else 1
        }
        rvBrowse.layoutManager = layoutManager
        rvBrowse.adapter = BrowseAdapter()
    }

    private inner class BrowseAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemViewType(position: Int) = if (position == 0) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == 0) {
                val header = inflater.inflate(R.layout.item_browse_header, parent, false) as TextView
                header.text = "Browse all"
                object : RecyclerView.ViewHolder(header) {}
            } else {
                val tile = inflater.inflate(R.layout.item_genre_tile, parent, false)
                val holder = object : RecyclerView.ViewHolder(tile) {}
                tile.setOnClickListener {
                    val position = holder.bindingAdapterPosition
                    if (position == RecyclerView.NO_POSITION) return@setOnClickListener
                    val genre = genres[position - 1]
                    hideKeyboard()
                    mainActivity?.openPage(CollectionFragment.genre(genre.name, genre.query, genre.color))
                }
                holder
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (position == 0) return
            val genre = genres[position - 1]
            holder.itemView.backgroundTintList = android.content.res.ColorStateList.valueOf(genre.color)
            holder.itemView.findViewById<TextView>(R.id.tv_genre_name).text = genre.name
        }

        override fun getItemCount() = genres.size + 1
    }

    // --- Recent searches ---

    private fun setupHistory() {
        historyAdapter = HistoryAdapter()
        rvHistory.layoutManager = LinearLayoutManager(requireContext())
        rvHistory.adapter = historyAdapter
    }

    private inner class HistoryAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val items = mutableListOf<String>()

        @SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<String>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int) = when (position) {
            0 -> 0
            items.size + 1 -> 2
            else -> 1
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                0 -> {
                    val header = inflater.inflate(R.layout.item_browse_header, parent, false) as TextView
                    header.text = "Recent searches"
                    header.setPadding(parent.context.dp(16), parent.context.dp(8), parent.context.dp(16), parent.context.dp(8))
                    object : RecyclerView.ViewHolder(header) {}
                }
                2 -> {
                    val footer = inflater.inflate(R.layout.item_clear_history, parent, false)
                    footer.findViewById<View>(R.id.btn_clear_history).setOnClickListener {
                        LocalStore.clearSearches(requireContext())
                        showMode(Mode.BROWSE)
                    }
                    object : RecyclerView.ViewHolder(footer) {}
                }
                else -> {
                    val row = inflater.inflate(R.layout.item_recent_search, parent, false)
                    val holder = object : RecyclerView.ViewHolder(row) {}
                    row.setOnClickListener {
                        val position = holder.bindingAdapterPosition
                        if (position == RecyclerView.NO_POSITION) return@setOnClickListener
                        val query = items[position - 1]
                        etSearch.setText(query)
                        etSearch.setSelection(query.length)
                        LocalStore.addSearch(requireContext(), query)
                        hideKeyboard()
                    }
                    row.findViewById<View>(R.id.btn_remove_recent).setOnClickListener {
                        val position = holder.bindingAdapterPosition
                        if (position == RecyclerView.NO_POSITION) return@setOnClickListener
                        LocalStore.removeSearch(requireContext(), items[position - 1])
                        showIdleMode()
                    }
                    holder
                }
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (getItemViewType(position) != 1) return
            holder.itemView.findViewById<TextView>(R.id.tv_recent_query).text = items[position - 1]
        }

        override fun getItemCount() = if (items.isEmpty()) 0 else items.size + 2
    }

    // --- Results ---

    private fun setupResults() {
        songAdapter = SongListAdapter(
            onSongClick = { index ->
                rememberQuery()
                hideKeyboard()
                mainActivity?.playSongs(songAdapter.songs, index, "Search: $lastQuery", "search:$lastQuery")
            },
            onMoreClick = { song -> mainActivity?.showSongOptions(song) }
        )
        topResultAdapter = TopResultAdapter {
            rememberQuery()
            hideKeyboard()
            mainActivity?.playSongs(songAdapter.songs, 0, "Search: $lastQuery", "search:$lastQuery")
        }
        rvResults.layoutManager = LinearLayoutManager(requireContext())
        rvResults.adapter = ConcatAdapter(topResultAdapter, songAdapter)
        rvResults.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) hideKeyboard()
            }
        })
    }

    private class TopResultAdapter(
        private val onPlay: () -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        var song: ApiSong? = null
            @SuppressLint("NotifyDataSetChanged")
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_top_result, parent, false)
            view.findViewById<View>(R.id.top_result_card).setOnClickListener { onPlay() }
            view.findViewById<View>(R.id.btn_top_play).setOnClickListener { onPlay() }
            return object : RecyclerView.ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val current = song ?: return
            holder.itemView.findViewById<TextView>(R.id.tv_top_title).text = current.displayTitle
            holder.itemView.findViewById<TextView>(R.id.tv_top_subtitle).text = "Song • ${current.displayArtist}"
            holder.itemView.findViewById<ImageView>(R.id.img_top_result).loadArt(current.hiResImage)
        }

        override fun getItemCount() = if (song == null) 0 else 1
    }
}
