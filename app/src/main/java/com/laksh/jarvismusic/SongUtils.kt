package com.laksh.jarvismusic

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.text.HtmlCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.laksh.jarvismusic.api.ApiSong

// ---------------------------------------------------------------------------
// Display helpers — JioSaavn strings arrive HTML-escaped ("&quot;", "&amp;"…)
// ---------------------------------------------------------------------------

fun String.cleanHtml(): String =
    HtmlCompat.fromHtml(this, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim()

val ApiSong.displayTitle: String
    get() = song?.cleanHtml()?.takeIf { it.isNotEmpty() } ?: "Unknown Track"

val ApiSong.displayArtist: String
    get() = singers?.cleanHtml()?.takeIf { it.isNotEmpty() } ?: "Unknown Artist"

/** First credited artist — used for "Go to artist" and autoplay radio. */
val ApiSong.primaryArtist: String?
    get() = singers?.cleanHtml()?.split(",", "&")?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }

/** Stable key used for likes, playlists and downloads. */
val ApiSong.key: String
    get() = id?.takeIf { it.isNotBlank() } ?: media_url ?: displayTitle

/** JioSaavn serves the same artwork in several sizes; ask for the sharpest one. */
val ApiSong.hiResImage: String?
    get() = image?.replace("150x150", "500x500")?.replace("50x50", "500x500")

val ApiSong.isPlayable: Boolean
    get() = !media_url.isNullOrBlank()

// ---------------------------------------------------------------------------
// Room entity <-> ApiSong
// ---------------------------------------------------------------------------

fun LikedSong.toApiSong() = ApiSong(
    id = id, song = title, singers = artist, image = imageUrl, media_url = audioUrl, album = null
)

fun PlaylistSong.toApiSong() = ApiSong(
    id = songId, song = title, singers = artist, image = imageUrl, media_url = audioUrl, album = null
)

fun ApiSong.toLikedSong() = LikedSong(
    id = key, title = displayTitle, artist = displayArtist, imageUrl = image ?: "", audioUrl = media_url ?: ""
)

fun ApiSong.toPlaylistSong(playlistId: Int) = PlaylistSong(
    playlistId = playlistId, songId = key, title = displayTitle, artist = displayArtist,
    imageUrl = image ?: "", audioUrl = media_url ?: ""
)

// ---------------------------------------------------------------------------
// ApiSong <-> Media3 MediaItem
// The full song travels in the metadata extras so it survives the trip
// through the MediaSession (local configuration and tags do not).
// ---------------------------------------------------------------------------

private const val EXTRA_ID = "jm_id"
private const val EXTRA_SONG = "jm_song"
private const val EXTRA_SINGERS = "jm_singers"
private const val EXTRA_IMAGE = "jm_image"
private const val EXTRA_MEDIA_URL = "jm_media_url"
private const val EXTRA_ALBUM = "jm_album"
private const val EXTRA_PERMA_URL = "jm_perma_url"

fun ApiSong.toMediaItem(context: Context): MediaItem {
    // Prefer the downloaded copy so downloaded songs play offline
    val uri = DownloadStore.localUri(context, this) ?: Uri.parse(media_url ?: "")

    val extras = Bundle().apply {
        putString(EXTRA_ID, id)
        putString(EXTRA_SONG, song)
        putString(EXTRA_SINGERS, singers)
        putString(EXTRA_IMAGE, image)
        putString(EXTRA_MEDIA_URL, media_url)
        putString(EXTRA_ALBUM, album)
        putString(EXTRA_PERMA_URL, perma_url)
    }

    val metadata = MediaMetadata.Builder()
        .setTitle(displayTitle)
        .setArtist(displayArtist)
        .setAlbumTitle(album?.cleanHtml())
        .setArtworkUri(hiResImage?.let { Uri.parse(it) })
        .setExtras(extras)
        .build()

    return MediaItem.Builder()
        .setMediaId(key)
        .setUri(uri)
        .setMediaMetadata(metadata)
        .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
        .build()
}

fun MediaItem.toApiSong(): ApiSong {
    val extras = mediaMetadata.extras
    return ApiSong(
        id = extras?.getString(EXTRA_ID) ?: mediaId,
        song = extras?.getString(EXTRA_SONG) ?: mediaMetadata.title?.toString(),
        singers = extras?.getString(EXTRA_SINGERS) ?: mediaMetadata.artist?.toString(),
        image = extras?.getString(EXTRA_IMAGE) ?: mediaMetadata.artworkUri?.toString(),
        media_url = extras?.getString(EXTRA_MEDIA_URL) ?: requestMetadata.mediaUri?.toString(),
        album = extras?.getString(EXTRA_ALBUM),
        perma_url = extras?.getString(EXTRA_PERMA_URL)
    )
}

fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}
