package com.laksh.jarvismusic

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.ShapeAppearanceModel
import com.laksh.jarvismusic.api.ApiSong
import com.laksh.jarvismusic.api.RetrofitInstance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Spotify-style playlist page: big cover, title, green play button and the
 * song list. Used for Liked Songs, Downloads, user playlists, artists,
 * mixes and genres.
 */
class CollectionFragment : Fragment(R.layout.fragment_collection) {

    enum class Mode { LIKED, DOWNLOADS, PLAYLIST, ARTIST, MIX, GENRE }

    companion object {
        private const val ARG_MODE = "mode"
        private const val ARG_TITLE = "title"
        private const val ARG_QUERY = "query"
        private const val ARG_IMAGE = "image"
        private const val ARG_PLAYLIST_ID = "playlist_id"
        private const val ARG_COLOR = "color"

        private fun create(mode: Mode, title: String, vararg extras: Pair<String, Any?>) =
            CollectionFragment().apply {
                arguments = bundleOf(ARG_MODE to mode.name, ARG_TITLE to title, *extras)
            }

        fun liked() = create(Mode.LIKED, "Liked Songs")
        fun downloads() = create(Mode.DOWNLOADS, "Downloaded")
        fun playlist(id: Int, name: String) = create(Mode.PLAYLIST, name, ARG_PLAYLIST_ID to id)
        fun artist(name: String, imageUrl: String? = null) =
            create(Mode.ARTIST, name, ARG_QUERY to name, ARG_IMAGE to (imageUrl ?: ArtistCatalog.imageFor(name)))
        fun mix(title: String, query: String, imageUrl: String?) =
            create(Mode.MIX, title, ARG_QUERY to query, ARG_IMAGE to imageUrl)
        fun genre(name: String, query: String, color: Int) =
            create(Mode.GENRE, name, ARG_QUERY to query, ARG_COLOR to color)
    }

    private lateinit var mode: Mode
    private var title = ""
    private var query = ""
    private var imageUrl: String? = null
    private var playlistId = -1
    private var fixedColor: Int? = null

    private var songs: List<ApiSong> = emptyList()
    private var loaded = false
    private var loadFailed = false
    private var headerColor = ArtColors.DEFAULT
    private var loadJob: Job? = null

    private lateinit var recyclerView: RecyclerView
    private lateinit var toolbar: View
    private lateinit var toolbarTitle: TextView
    private lateinit var progress: ProgressBar
    private lateinit var headerAdapter: HeaderAdapter
    private lateinit var songAdapter: SongListAdapter

    private val sourceKey: String
        get() = "collection:${mode.name}:${if (mode == Mode.PLAYLIST) playlistId.toString() else query}"

    private val isLocal: Boolean
        get() = mode == Mode.LIKED || mode == Mode.DOWNLOADS || mode == Mode.PLAYLIST

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val args = requireArguments()
        mode = Mode.valueOf(args.getString(ARG_MODE) ?: Mode.MIX.name)
        title = args.getString(ARG_TITLE).orEmpty()
        query = args.getString(ARG_QUERY).orEmpty()
        imageUrl = args.getString(ARG_IMAGE)
        playlistId = args.getInt(ARG_PLAYLIST_ID, -1)
        fixedColor = when (mode) {
            Mode.LIKED -> Color.parseColor("#5038A0")
            Mode.DOWNLOADS -> Color.parseColor("#1E6B45")
            Mode.GENRE -> ArtColors.toBackground(args.getInt(ARG_COLOR, ArtColors.DEFAULT))
            else -> null
        }
        headerColor = fixedColor ?: ArtColors.DEFAULT
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.rv_collection)
        toolbar = view.findViewById(R.id.collection_toolbar)
        toolbarTitle = view.findViewById(R.id.tv_collection_toolbar_title)
        progress = view.findViewById(R.id.collection_progress)

        toolbar.applyStatusBarPadding()
        toolbarTitle.text = title
        view.findViewById<View>(R.id.btn_collection_back).setOnClickListener { mainActivity?.closePage() }

        headerAdapter = HeaderAdapter()
        songAdapter = SongListAdapter(
            onSongClick = { index -> mainActivity?.playSongs(songs, index, title, sourceKey) },
            onMoreClick = { song ->
                mainActivity?.showSongOptions(song, playlistId = if (mode == Mode.PLAYLIST) playlistId else null)
            }
        )
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = ConcatAdapter(headerAdapter, songAdapter)
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = updateToolbar()
        })
        updateToolbar()

        // Header top padding = status bar + toolbar, so the cover sits below the back button
        ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            v.post { headerAdapter.topInset = top } // never notify the adapter mid-layout
            insets
        }
        recyclerView.requestApplyInsetsWhenAttached()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                MusicState.nowPlayingKey.collect { songAdapter.setNowPlaying(it) }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(MusicState.isPlaying, MusicState.sourceKey) { playing, key -> playing && key == sourceKey }
                    .collect { headerAdapter.setPlaying(it) }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                MusicState.libraryVersion.collect {
                    if (isLocal || !loaded) {
                        load()
                    } else {
                        // Refresh the "downloaded" markers
                        songAdapter.notifyItemRangeChanged(0, songAdapter.itemCount)
                    }
                }
            }
        }
    }

    private fun load() {
        loadJob?.cancel()
        if (!loaded) progress.visibility = View.VISIBLE
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val context = requireContext().applicationContext
            val db = AppDatabase.getDatabase(context)
            loadFailed = false
            val result: List<ApiSong> = try {
                when (mode) {
                    Mode.LIKED -> withContext(Dispatchers.IO) {
                        db.songDao().getLikedSongsNewestFirst().map { it.toApiSong() }
                    }
                    Mode.DOWNLOADS -> withContext(Dispatchers.IO) { DownloadStore.downloadedSongs(context) }
                    Mode.PLAYLIST -> {
                        val playlist = withContext(Dispatchers.IO) { db.playlistDao().getPlaylist(playlistId) }
                        if (playlist == null) {
                            // The playlist was deleted
                            mainActivity?.closePage()
                            return@launch
                        }
                        title = playlist.name
                        toolbarTitle.text = title
                        withContext(Dispatchers.IO) {
                            db.playlistDao().getSongsInPlaylist(playlistId).map { it.toApiSong() }
                        }
                    }
                    Mode.ARTIST, Mode.MIX, Mode.GENRE ->
                        RetrofitInstance.api.searchSongs(query, 30).filter { it.isPlayable }.distinctBy { it.key }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loadFailed = true
                emptyList()
            }

            songs = result
            loaded = true
            progress.visibility = View.GONE
            songAdapter.submit(songs)
            headerAdapter.refresh()
            extractHeaderColor()
        }
    }

    private fun coverUrl(): String? = when (mode) {
        Mode.ARTIST, Mode.MIX -> imageUrl ?: songs.firstOrNull()?.hiResImage
        Mode.PLAYLIST -> songs.firstOrNull()?.hiResImage
        else -> null
    }

    private fun extractHeaderColor() {
        if (fixedColor != null) return
        ArtColors.extract(requireContext(), coverUrl()) { color ->
            if (view == null) return@extract
            headerColor = color
            headerAdapter.refresh()
            updateToolbar()
        }
    }

    private fun updateToolbar() {
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
        val header = layoutManager.findViewByPosition(0)
        val fraction = if (recyclerView.childCount == 0) {
            0f // not laid out yet
        } else if (header == null) {
            1f
        } else {
            val range = (header.height * 0.45f).coerceAtLeast(1f)
            (-header.top / range).coerceIn(0f, 1f)
        }
        val barColor = ColorUtils.blendARGB(headerColor, Color.BLACK, 0.35f)
        toolbar.setBackgroundColor(ColorUtils.setAlphaComponent(barColor, (fraction * 255).toInt()))
        toolbarTitle.alpha = fraction
    }

    private fun play(shuffle: Boolean) {
        val main = mainActivity ?: return
        if (!shuffle && MusicState.sourceKey.value == sourceKey && MusicState.nowPlayingKey.value != null) {
            main.togglePlayPause()
        } else {
            main.playSongs(songs, 0, title, sourceKey, shuffle = shuffle)
        }
    }

    // ---------------------------------------------------------------------

    private inner class HeaderAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        var topInset = 0
            set(value) {
                if (field != value) {
                    field = value
                    refresh()
                }
            }
        private var isPlaying = false

        fun setPlaying(playing: Boolean) {
            if (isPlaying != playing) {
                isPlaying = playing
                refresh()
            }
        }

        fun refresh() = notifyItemChanged(0)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_collection_header, parent, false)
            view.findViewById<View>(R.id.btn_header_play).setOnClickListener { play(shuffle = false) }
            view.findViewById<View>(R.id.btn_header_shuffle).setOnClickListener { play(shuffle = true) }
            view.findViewById<View>(R.id.btn_header_more).setOnClickListener {
                mainActivity?.showPlaylistOptions(playlistId, title)
            }
            return object : RecyclerView.ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val view = holder.itemView
            val context = view.context
            val base = ContextCompat.getColor(context, R.color.bg_base)

            view.background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(headerColor, ColorUtils.blendARGB(headerColor, base, 0.7f), base)
            )
            view.updatePadding(top = topInset + context.dp(56))

            bindCover(
                view.findViewById(R.id.img_header_art),
                view.findViewById(R.id.img_header_icon)
            )

            view.findViewById<TextView>(R.id.tv_header_title).text = title
            view.findViewById<TextView>(R.id.tv_header_owner).text = when (mode) {
                Mode.ARTIST -> "Artist"
                Mode.MIX, Mode.GENRE -> "Made for you"
                else -> "Jarvis Music"
            }
            view.findViewById<TextView>(R.id.tv_header_subtitle).text = subtitle()

            val hasSongs = songs.any { it.isPlayable }
            val play = view.findViewById<ImageView>(R.id.btn_header_play)
            play.visibility = if (hasSongs) View.VISIBLE else View.INVISIBLE
            play.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
            view.findViewById<View>(R.id.btn_header_shuffle).visibility = if (hasSongs) View.VISIBLE else View.INVISIBLE
            view.findViewById<View>(R.id.btn_header_more).visibility =
                if (mode == Mode.PLAYLIST) View.VISIBLE else View.INVISIBLE

            bindEmptyState(view)
        }

        override fun getItemCount() = 1
    }

    private fun subtitle(): String {
        if (!loaded) return ""
        val count = songs.size
        val countText = if (count == 1) "1 song" else "$count songs"
        val totalSeconds = songs.sumOf { it.duration?.toLongOrNull() ?: 0L }
        return if (totalSeconds > 0) {
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val length = if (hours > 0) "${hours} hr ${minutes} min" else "${minutes} min"
            "$countText • $length"
        } else {
            countText
        }
    }

    private fun bindCover(art: ShapeableImageView, icon: ImageView) {
        val context = art.context
        val radius = if (mode == Mode.ARTIST) context.dp(110).toFloat() else context.dp(4).toFloat()
        art.shapeAppearanceModel = ShapeAppearanceModel.builder().setAllCorners(CornerFamily.ROUNDED, radius).build()
        Glide.with(art).clear(art)
        icon.visibility = View.GONE

        when (mode) {
            Mode.LIKED -> {
                art.setImageResource(R.drawable.bg_liked_cover)
                icon.setImageResource(R.drawable.ic_heart_filled)
                icon.visibility = View.VISIBLE
            }
            Mode.DOWNLOADS -> {
                art.setImageResource(R.drawable.bg_downloads_cover)
                icon.setImageResource(R.drawable.ic_download)
                icon.visibility = View.VISIBLE
            }
            Mode.GENRE -> {
                art.setImageDrawable(ColorDrawable(requireArguments().getInt(ARG_COLOR, ArtColors.DEFAULT)))
                icon.setImageResource(R.drawable.ic_music_note)
                icon.visibility = View.VISIBLE
            }
            else -> {
                val url = coverUrl()
                if (url.isNullOrBlank()) {
                    art.setImageResource(R.drawable.bg_playlist_cover)
                    icon.setImageResource(if (mode == Mode.ARTIST) R.drawable.ic_person else R.drawable.ic_music_note)
                    icon.visibility = View.VISIBLE
                } else {
                    art.loadArt(url)
                }
            }
        }
    }

    private fun bindEmptyState(view: View) {
        val empty = view.findViewById<View>(R.id.header_empty)
        if (!loaded || songs.isNotEmpty()) {
            empty.visibility = View.GONE
            return
        }
        empty.visibility = View.VISIBLE
        val titleView = view.findViewById<TextView>(R.id.tv_empty_title)
        val message = view.findViewById<TextView>(R.id.tv_empty_message)
        val action = view.findViewById<MaterialButton>(R.id.btn_empty_action)
        val findSongs = View.OnClickListener { mainActivity?.goToTab(R.id.nav_search) }

        when {
            loadFailed -> {
                titleView.text = "Couldn't load songs"
                message.text = "Check your internet connection and try again."
                action.text = "Try again"
                action.setOnClickListener { load() }
            }
            mode == Mode.LIKED -> {
                titleView.text = "Songs you like will appear here"
                message.text = "Save songs by tapping the heart icon."
                action.text = "Find songs"
                action.setOnClickListener(findSongs)
            }
            mode == Mode.DOWNLOADS -> {
                titleView.text = "No downloads yet"
                message.text = "Download songs to listen without an internet connection."
                action.text = "Find songs"
                action.setOnClickListener(findSongs)
            }
            mode == Mode.PLAYLIST -> {
                titleView.text = "Let's find something for your playlist"
                message.text = "Use the ⋮ menu on any song to add it here."
                action.text = "Find songs"
                action.setOnClickListener(findSongs)
            }
            else -> {
                titleView.text = "No songs found"
                message.text = "Try again in a moment."
                action.text = "Try again"
                action.setOnClickListener { load() }
            }
        }
    }
}
