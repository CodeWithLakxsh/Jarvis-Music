package com.laksh.jarvismusic

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.laksh.jarvismusic.api.ApiSong
import com.laksh.jarvismusic.api.RetrofitInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

class HomeFragment : Fragment(R.layout.fragment_home), ScrollToTop {

    private data class Filter(val label: String, val washColor: Int)

    private sealed class Section(val title: String) {
        class Songs(title: String, val query: String) : Section(title)
        class Recent(title: String, val songs: List<ApiSong>) : Section(title)
        class Mixes(title: String, val mixes: List<Mix>) : Section(title)
        class Artists(title: String, val artists: List<ArtistInfo>) : Section(title)
    }

    private val filters = listOf(
        Filter("All", Color.parseColor("#3B2A6B")),
        Filter("Hindi", Color.parseColor("#6B2A3A")),
        Filter("Punjabi", Color.parseColor("#2A5B6B")),
        Filter("English", Color.parseColor("#2A6B3F")),
        Filter("Haryanvi", Color.parseColor("#6B532A"))
    )
    private var selectedFilter = 0

    private lateinit var scrollView: NestedScrollView
    private lateinit var refreshLayout: SwipeRefreshLayout
    private lateinit var sectionsContainer: LinearLayout
    private lateinit var errorView: View
    private lateinit var progressBar: ProgressBar
    private lateinit var topWash: View
    private lateinit var quickGrid: RecyclerView

    private lateinit var quickAdapter: QuickGridAdapter
    private var loadJob: Job? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        scrollView = view.findViewById(R.id.home_scroll)
        refreshLayout = view.findViewById(R.id.home_refresh)
        sectionsContainer = view.findViewById(R.id.home_sections)
        errorView = view.findViewById(R.id.home_error)
        progressBar = view.findViewById(R.id.home_progress_bar)
        topWash = view.findViewById(R.id.home_top_wash)
        quickGrid = view.findViewById(R.id.rv_quick_grid)

        view.findViewById<View>(R.id.home_content).applyStatusBarPadding()
        view.findViewById<TextView>(R.id.tv_greeting).text = greeting()

        quickAdapter = QuickGridAdapter { index ->
            mainActivity?.playSongs(quickAdapter.songs, index, "Recently played", "home:quick")
        }
        quickGrid.layoutManager = GridLayoutManager(requireContext(), 2)
        quickGrid.adapter = quickAdapter

        refreshLayout.setColorSchemeResources(R.color.spotify_green)
        refreshLayout.setProgressBackgroundColorSchemeResource(R.color.bg_highlight)
        refreshLayout.setProgressViewOffset(false, requireContext().dp(24), requireContext().dp(96))
        refreshLayout.setOnRefreshListener { loadHome(fromRefresh = true) }
        view.findViewById<View>(R.id.btn_home_retry).setOnClickListener { loadHome() }

        setupChips(view.findViewById(R.id.home_chips))

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                MusicState.nowPlayingKey.collect { quickAdapter.setNowPlaying(it) }
            }
        }

        loadHome()
    }

    override fun scrollToTop() {
        if (::scrollView.isInitialized) scrollView.smoothScrollTo(0, 0)
    }

    private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        else -> "Good evening"
    }

    private fun setupChips(group: ChipGroup) {
        val inflater = LayoutInflater.from(group.context)
        filters.forEachIndexed { index, filter ->
            val chip = inflater.inflate(R.layout.item_chip, group, false) as Chip
            chip.id = View.generateViewId()
            chip.text = filter.label
            chip.isChecked = index == selectedFilter
            chip.setOnClickListener {
                if (selectedFilter != index) {
                    selectedFilter = index
                    loadHome()
                }
            }
            group.addView(chip)
        }
    }

    private fun applyWash(color: Int) {
        topWash.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(color, Color.TRANSPARENT)
        )
    }

    private fun buildSections(filter: Filter, recents: List<ApiSong>): List<Section> {
        val context = requireContext()
        if (filter.label != "All") {
            val lang = filter.label
            return listOf(
                Section.Songs("Trending $lang", "$lang trending"),
                Section.Songs("$lang hits", "$lang hits"),
                Section.Songs("New $lang releases", "new $lang songs"),
                Section.Songs("$lang romance", "$lang romantic"),
                Section.Songs("$lang party", "$lang party")
            )
        }

        val favorites = LocalStore.favoriteArtists(context)
        val mixArtists = favorites.ifEmpty { ArtistCatalog.popular.take(4).map { it.name } }
        val mixes = mixArtists.map { name ->
            Mix(
                title = "$name Mix",
                subtitle = "$name and more",
                query = name,
                imageUrl = ArtistCatalog.imageFor(name),
                isArtist = true
            )
        }

        val sections = mutableListOf<Section>()
        if (recents.size >= 3) sections += Section.Recent("Jump back in", recents.take(15))
        sections += Section.Mixes("Your top mixes", mixes)
        sections += Section.Songs("Trending now", "Trending")
        favorites.randomOrNull()?.let { sections += Section.Songs("More like $it", "$it hits") }
        sections += Section.Artists("Popular artists", ArtistCatalog.popular)
        sections += Section.Songs("Today's biggest hits", "Top hits")
        sections += Section.Songs("Romantic", "romantic")
        sections += Section.Songs("Party anthems", "party")
        sections += Section.Songs("Chill vibes", "lofi")
        return sections
    }

    private fun loadHome(fromRefresh: Boolean = false) {
        loadJob?.cancel()
        val filter = filters[selectedFilter]
        applyWash(filter.washColor)

        sectionsContainer.removeAllViews()
        errorView.visibility = View.GONE
        progressBar.visibility = if (fromRefresh) View.GONE else View.VISIBLE

        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val appContext = requireContext().applicationContext
            val recents = withContext(Dispatchers.IO) { LocalStore.recentSongs(appContext) }
            val sections = buildSections(filter, recents)

            // Inflate every section up front (hidden) so they keep their order
            val sectionViews = sections.map { section -> inflateSection(section) }

            // Quick-access grid: recently played, topped up with trending songs
            var quickSongs = recents.take(8)
            quickAdapter.submit(quickSongs)

            val results = coroutineScope {
                sections.mapIndexed { index, section ->
                    async {
                        val view = sectionViews[index]
                        when (section) {
                            is Section.Songs -> {
                                val songs = try {
                                    RetrofitInstance.api.searchSongs(section.query, 20).filter { it.isPlayable }
                                } catch (e: Exception) {
                                    Log.e("HomeFragment", "Section '${section.title}' failed: ${e.message}")
                                    null
                                }
                                if (!songs.isNullOrEmpty()) {
                                    bindSongs(view, section.title, songs.distinctBy { it.key })
                                    progressBar.visibility = View.GONE
                                }
                                songs
                            }
                            is Section.Recent -> {
                                bindSongs(view, section.title, section.songs)
                                section.songs
                            }
                            is Section.Mixes -> {
                                bindMixes(view, section.mixes)
                                emptyList<ApiSong>()
                            }
                            is Section.Artists -> {
                                bindArtists(view, section.artists)
                                emptyList<ApiSong>()
                            }
                        }
                    }
                }.awaitAll()
            }

            val loadedSongSections = sections.indices.filter { sections[it] is Section.Songs && !results[it].isNullOrEmpty() }
            if (quickSongs.size < 8) {
                val filler = loadedSongSections.firstOrNull()?.let { results[it] }.orEmpty()
                quickSongs = (quickSongs + filler).distinctBy { it.key }.take(8)
                quickAdapter.submit(quickSongs)
            }

            progressBar.visibility = View.GONE
            refreshLayout.isRefreshing = false
            if (loadedSongSections.isEmpty() && recents.isEmpty()) {
                sectionsContainer.removeAllViews()
                errorView.visibility = View.VISIBLE
            }
        }
    }

    private fun inflateSection(section: Section): View {
        val view = layoutInflater.inflate(R.layout.item_home_section, sectionsContainer, false)
        view.findViewById<TextView>(R.id.tv_section_title).text = section.title
        view.findViewById<RecyclerView>(R.id.rv_section).layoutManager =
            LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        sectionsContainer.addView(view)
        return view
    }

    private fun bindSongs(view: View, title: String, songs: List<ApiSong>) {
        view.findViewById<RecyclerView>(R.id.rv_section).adapter = SongCardAdapter(songs) { index ->
            mainActivity?.playSongs(songs, index, title, "home:$title")
        }
        view.visibility = View.VISIBLE
    }

    private fun bindMixes(view: View, mixes: List<Mix>) {
        view.findViewById<RecyclerView>(R.id.rv_section).adapter = MixCardAdapter(mixes) { mix ->
            mainActivity?.openPage(CollectionFragment.mix(mix.title, mix.query, mix.imageUrl))
        }
        view.visibility = View.VISIBLE
    }

    private fun bindArtists(view: View, artists: List<ArtistInfo>) {
        view.findViewById<RecyclerView>(R.id.rv_section).adapter = ArtistCircleAdapter(artists) { artist ->
            mainActivity?.openPage(CollectionFragment.artist(artist.name, artist.imageUrl))
        }
        view.visibility = View.VISIBLE
    }
}
