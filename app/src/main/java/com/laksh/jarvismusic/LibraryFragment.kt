package com.laksh.jarvismusic

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.CornerFamily
import com.google.android.material.shape.ShapeAppearanceModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class LibraryEntry {
    data class Liked(val count: Int) : LibraryEntry()
    data class Downloads(val count: Int) : LibraryEntry()
    data class PlaylistItem(val playlist: Playlist, val count: Int, val coverUrl: String?) : LibraryEntry()
    data class Artist(val info: ArtistInfo) : LibraryEntry()
    object Footer : LibraryEntry()
}

private fun songCount(count: Int) = if (count == 1) "1 song" else "$count songs"

/** Binds a Your Library style row (also used by the "Add to playlist" sheet). */
fun bindLibraryRow(view: View, entry: LibraryEntry, subtitleOverride: String? = null) {
    val context = view.context
    val art = view.findViewById<ShapeableImageView>(R.id.img_lib_art)
    val icon = view.findViewById<ImageView>(R.id.img_lib_icon)
    val title = view.findViewById<TextView>(R.id.tv_lib_title)
    val subtitle = view.findViewById<TextView>(R.id.tv_lib_subtitle)
    val badge = view.findViewById<ImageView>(R.id.img_lib_badge)

    val isArtist = entry is LibraryEntry.Artist
    val radius = if (isArtist) context.dp(32).toFloat() else context.dp(4).toFloat()
    art.shapeAppearanceModel = ShapeAppearanceModel.builder().setAllCorners(CornerFamily.ROUNDED, radius).build()

    // Clear any pending Glide load before showing a local cover
    Glide.with(art).clear(art)
    icon.visibility = View.GONE
    badge.visibility = View.GONE
    icon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.white))

    when (entry) {
        is LibraryEntry.Liked -> {
            art.setImageResource(R.drawable.bg_liked_cover)
            icon.setImageResource(R.drawable.ic_heart_filled)
            icon.visibility = View.VISIBLE
            badge.visibility = View.VISIBLE
            title.text = context.getString(R.string.liked_songs)
            subtitle.text = "Playlist • ${songCount(entry.count)}"
        }
        is LibraryEntry.Downloads -> {
            art.setImageResource(R.drawable.bg_downloads_cover)
            icon.setImageResource(R.drawable.ic_download)
            icon.visibility = View.VISIBLE
            badge.visibility = View.VISIBLE
            title.text = context.getString(R.string.downloaded)
            subtitle.text = "Playlist • ${songCount(entry.count)}"
        }
        is LibraryEntry.PlaylistItem -> {
            if (entry.coverUrl.isNullOrBlank()) {
                art.setImageResource(R.drawable.bg_playlist_cover)
                icon.setImageResource(R.drawable.ic_music_note)
                icon.imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.text_secondary))
                icon.visibility = View.VISIBLE
            } else {
                art.loadArt(entry.coverUrl)
            }
            title.text = entry.playlist.name
            subtitle.text = "Playlist • ${songCount(entry.count)}"
        }
        is LibraryEntry.Artist -> {
            art.loadArt(entry.info.imageUrl)
            title.text = entry.info.name
            subtitle.text = "Artist"
        }
        LibraryEntry.Footer -> Unit
    }
    subtitleOverride?.let { subtitle.text = it }
}

class LibraryFragment : Fragment(R.layout.fragment_library), ScrollToTop {

    private enum class Filter(val label: String) { PLAYLISTS("Playlists"), ARTISTS("Artists"), DOWNLOADED("Downloaded") }

    private lateinit var recyclerView: RecyclerView
    private val adapter = LibraryAdapter()
    private var allEntries: List<LibraryEntry> = emptyList()
    private var filter: Filter? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.library_root).applyStatusBarPadding()

        recyclerView = view.findViewById(R.id.rv_library)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        view.findViewById<View>(R.id.btn_library_add).setOnClickListener { mainActivity?.showCreatePlaylist() }
        setupChips(view.findViewById(R.id.library_chips))

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                MusicState.libraryVersion.collect { loadLibrary() }
            }
        }
    }

    override fun scrollToTop() {
        if (::recyclerView.isInitialized) recyclerView.smoothScrollToPosition(0)
    }

    private fun setupChips(group: ChipGroup) {
        val inflater = LayoutInflater.from(group.context)
        Filter.values().forEach { option ->
            val chip = inflater.inflate(R.layout.item_chip, group, false) as Chip
            chip.id = View.generateViewId()
            chip.text = option.label
            chip.setOnClickListener {
                // Tapping the active filter again clears it, like Spotify
                filter = if (filter == option) null else option
                chip.isChecked = filter == option
                applyFilter()
            }
            group.addView(chip)
        }
    }

    private suspend fun loadLibrary() {
        val context = requireContext().applicationContext
        allEntries = withContext(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(context)
            val likedCount = db.songDao().getLikedCount()
            val downloadCount = DownloadStore.downloadedSongs(context).size
            val playlists = db.playlistDao().getAllPlaylists().reversed().map { playlist ->
                val songs = db.playlistDao().getSongsInPlaylist(playlist.playlistId)
                LibraryEntry.PlaylistItem(playlist, songs.size, songs.firstOrNull()?.imageUrl)
            }
            val artists = LocalStore.favoriteArtists(context).map { name ->
                LibraryEntry.Artist(ArtistInfo(name, ArtistCatalog.imageFor(name) ?: ""))
            }
            listOf(LibraryEntry.Liked(likedCount), LibraryEntry.Downloads(downloadCount)) + playlists + artists
        }
        applyFilter()
    }

    private fun applyFilter() {
        val filtered = when (filter) {
            Filter.PLAYLISTS -> allEntries.filter { it !is LibraryEntry.Artist }
            Filter.ARTISTS -> allEntries.filterIsInstance<LibraryEntry.Artist>()
            Filter.DOWNLOADED -> allEntries.filterIsInstance<LibraryEntry.Downloads>()
            null -> allEntries
        }
        adapter.submit(filtered + LibraryEntry.Footer)
    }

    private fun onEntryClick(entry: LibraryEntry) {
        val main = mainActivity ?: return
        when (entry) {
            is LibraryEntry.Liked -> main.openPage(CollectionFragment.liked())
            is LibraryEntry.Downloads -> main.openPage(CollectionFragment.downloads())
            is LibraryEntry.PlaylistItem -> main.openPage(
                CollectionFragment.playlist(entry.playlist.playlistId, entry.playlist.name)
            )
            is LibraryEntry.Artist -> main.openPage(CollectionFragment.artist(entry.info.name, entry.info.imageUrl))
            LibraryEntry.Footer -> Unit
        }
    }

    private fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            mainActivity?.toast("No browser found")
        }
    }

    private inner class LibraryAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val entries = mutableListOf<LibraryEntry>()

        @SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<LibraryEntry>) {
            entries.clear()
            entries.addAll(list)
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int) = if (entries[position] is LibraryEntry.Footer) 1 else 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            if (viewType == 1) {
                val footer = inflater.inflate(R.layout.item_library_footer, parent, false)
                footer.findViewById<View>(R.id.btn_visit_website).setOnClickListener { openLink("https://codewithlaksh.in/") }
                footer.findViewById<View>(R.id.btn_follow_github).setOnClickListener { openLink("https://github.com/CodeWithLakxsh") }
                return object : RecyclerView.ViewHolder(footer) {}
            }
            val row = inflater.inflate(R.layout.item_library_row, parent, false)
            val holder = object : RecyclerView.ViewHolder(row) {}
            row.setOnClickListener {
                val position = holder.bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) onEntryClick(entries[position])
            }
            row.setOnLongClickListener {
                val position = holder.bindingAdapterPosition
                val entry = entries.getOrNull(position) as? LibraryEntry.PlaylistItem ?: return@setOnLongClickListener false
                mainActivity?.showPlaylistOptions(entry.playlist.playlistId, entry.playlist.name)
                true
            }
            return holder
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val entry = entries[position]
            if (entry !is LibraryEntry.Footer) bindLibraryRow(holder.itemView, entry)
        }

        override fun getItemCount() = entries.size
    }
}
