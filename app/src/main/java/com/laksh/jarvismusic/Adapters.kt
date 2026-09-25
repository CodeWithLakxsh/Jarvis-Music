package com.laksh.jarvismusic

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.laksh.jarvismusic.api.ApiSong

private const val PAYLOAD_NOW_PLAYING = "now_playing"

private fun RecyclerView.ViewHolder.withPosition(action: (Int) -> Unit) {
    val position = bindingAdapterPosition
    if (position != RecyclerView.NO_POSITION) action(position)
}

/** Vertical song rows (search results, playlists, liked songs…). */
class SongListAdapter(
    private val onSongClick: (Int) -> Unit,
    private val onMoreClick: (ApiSong) -> Unit
) : RecyclerView.Adapter<SongListAdapter.ViewHolder>() {

    var songs: List<ApiSong> = emptyList()
        private set

    private var nowPlayingKey: String? = null

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<ApiSong>) {
        songs = list
        notifyDataSetChanged()
    }

    fun setNowPlaying(key: String?) {
        if (key == nowPlayingKey) return
        nowPlayingKey = key
        notifyItemRangeChanged(0, itemCount, PAYLOAD_NOW_PLAYING)
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val art: ImageView = view.findViewById(R.id.iv_album_art)
        val title: TextView = view.findViewById(R.id.tv_song_title)
        val artist: TextView = view.findViewById(R.id.tv_song_artist)
        val downloaded: ImageView = view.findViewById(R.id.iv_downloaded)
        val more: ImageView = view.findViewById(R.id.btn_song_more)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false)
        val holder = ViewHolder(view)
        view.setOnClickListener { holder.withPosition(onSongClick) }
        holder.more.setOnClickListener { holder.withPosition { onMoreClick(songs[it]) } }
        return holder
    }

    private fun bindTitleColor(holder: ViewHolder, song: ApiSong) {
        val color = if (song.key == nowPlayingKey) R.color.spotify_green else R.color.text_primary
        holder.title.setTextColor(ContextCompat.getColor(holder.itemView.context, color))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_NOW_PLAYING)) {
            bindTitleColor(holder, songs[position])
        } else {
            super.onBindViewHolder(holder, position, payloads)
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val song = songs[position]
        holder.title.text = song.displayTitle
        holder.artist.text = song.displayArtist
        holder.downloaded.visibility = if (DownloadStore.isDownloaded(song)) View.VISIBLE else View.GONE
        holder.itemView.alpha = if (song.isPlayable) 1f else 0.4f
        holder.art.loadArt(song.image)
        bindTitleColor(holder, song)
    }

    override fun getItemCount() = songs.size
}

/** Square song cards for Home carousels. */
class SongCardAdapter(
    private val songs: List<ApiSong>,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<SongCardAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val art: ImageView = view.findViewById(R.id.img_card)
        val title: TextView = view.findViewById(R.id.tv_card_title)
        val subtitle: TextView = view.findViewById(R.id.tv_card_subtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_card, parent, false)
        return ViewHolder(view).also { holder -> view.setOnClickListener { holder.withPosition(onClick) } }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val song = songs[position]
        holder.title.text = song.displayTitle
        holder.subtitle.text = song.displayArtist
        holder.art.loadArt(song.hiResImage)
    }

    override fun getItemCount() = songs.size
}

/** A "mix" opens a playlist-style page for an artist or a mood. */
data class Mix(val title: String, val subtitle: String, val query: String, val imageUrl: String?, val isArtist: Boolean)

class MixCardAdapter(
    private val mixes: List<Mix>,
    private val onClick: (Mix) -> Unit
) : RecyclerView.Adapter<MixCardAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val art: ImageView = view.findViewById(R.id.img_card)
        val badge: TextView = view.findViewById(R.id.tv_card_badge)
        val title: TextView = view.findViewById(R.id.tv_card_title)
        val subtitle: TextView = view.findViewById(R.id.tv_card_subtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_card, parent, false)
        return ViewHolder(view).also { holder ->
            view.setOnClickListener { holder.withPosition { onClick(mixes[it]) } }
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val mix = mixes[position]
        holder.badge.visibility = View.VISIBLE
        holder.badge.text = mix.title
        holder.title.text = mix.title
        holder.subtitle.text = mix.subtitle
        holder.art.loadArt(mix.imageUrl)
    }

    override fun getItemCount() = mixes.size
}

/** Round artist bubbles. */
class ArtistCircleAdapter(
    private val artists: List<ArtistInfo>,
    private val onClick: (ArtistInfo) -> Unit
) : RecyclerView.Adapter<ArtistCircleAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.img_artist)
        val name: TextView = view.findViewById(R.id.tv_artist_name)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_artist_circle, parent, false)
        return ViewHolder(view).also { holder ->
            view.setOnClickListener { holder.withPosition { onClick(artists[it]) } }
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val artist = artists[position]
        holder.name.text = artist.name
        holder.image.loadArt(artist.imageUrl)
    }

    override fun getItemCount() = artists.size
}

/** Compact 2-column tiles at the top of Home. */
class QuickGridAdapter(
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<QuickGridAdapter.ViewHolder>() {

    var songs: List<ApiSong> = emptyList()
        private set
    private var nowPlayingKey: String? = null

    @SuppressLint("NotifyDataSetChanged")
    fun submit(list: List<ApiSong>) {
        songs = list
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setNowPlaying(key: String?) {
        if (key == nowPlayingKey) return
        nowPlayingKey = key
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val art: ImageView = view.findViewById(R.id.img_grid)
        val title: TextView = view.findViewById(R.id.tv_grid_title)
        val playing: ImageView = view.findViewById(R.id.img_grid_playing)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_quick_grid, parent, false)
        return ViewHolder(view).also { holder -> view.setOnClickListener { holder.withPosition(onClick) } }
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val song = songs[position]
        val isPlaying = song.key == nowPlayingKey
        holder.title.text = song.displayTitle
        holder.title.setTextColor(
            ContextCompat.getColor(holder.itemView.context, if (isPlaying) R.color.spotify_green else R.color.text_primary)
        )
        holder.playing.visibility = if (isPlaying) View.VISIBLE else View.GONE
        holder.art.loadArt(song.image)
    }

    override fun getItemCount() = songs.size
}
