package com.laksh.jarvismusic

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.laksh.jarvismusic.api.ApiSong
import java.io.File

/**
 * Keeps track of songs downloaded through DownloadManager, together with their
 * metadata (title, artist, artwork) so the Downloads page looks like a real
 * playlist instead of a list of file names.
 */
object DownloadStore {

    private data class Entry(val fileName: String, val downloadId: Long, val song: ApiSong)

    private const val PREFS = "JarvisDownloads"
    private const val KEY_ENTRIES = "entries"
    private val gson = Gson()

    // Song keys whose file is fully downloaded (refreshed off the main thread)
    @Volatile
    private var completedKeys: Set<String> = emptySet()

    fun musicDir(context: Context): File? = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)

    private fun readEntries(context: Context): MutableList<Entry> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ENTRIES, null)
            ?: return mutableListOf()
        return try {
            val type = object : TypeToken<List<Entry>>() {}.type
            gson.fromJson<List<Entry>>(json, type)?.toMutableList() ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun writeEntries(context: Context, entries: List<Entry>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ENTRIES, gson.toJson(entries))
            .apply()
    }

    private fun fileFor(context: Context, entry: Entry): File? = musicDir(context)?.let { File(it, entry.fileName) }

    private fun isComplete(context: Context, entry: Entry): Boolean {
        val file = fileFor(context, entry) ?: return false
        if (!file.exists() || file.length() == 0L) return false
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return true
        return try {
            dm.query(DownloadManager.Query().setFilterById(entry.downloadId)).use { cursor ->
                if (cursor != null && cursor.moveToFirst()) {
                    val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    status == DownloadManager.STATUS_SUCCESSFUL
                } else {
                    true // DownloadManager forgot about it, but the file is there
                }
            }
        } catch (e: Exception) {
            true
        }
    }

    /** Re-reads download states. Call from a background thread. */
    fun refresh(context: Context) {
        completedKeys = readEntries(context)
            .filter { isComplete(context, it) }
            .map { it.song.key }
            .toSet()
    }

    fun isDownloaded(song: ApiSong): Boolean =
        song.media_url?.startsWith("file:") == true || completedKeys.contains(song.key)

    fun isDownloading(context: Context, song: ApiSong): Boolean =
        !isDownloaded(song) && readEntries(context).any { it.song.key == song.key }

    /** Local file for a downloaded song, or null when it must be streamed. */
    fun localUri(context: Context, song: ApiSong): Uri? {
        if (song.media_url?.startsWith("file:") == true) return Uri.parse(song.media_url)
        if (!completedKeys.contains(song.key)) return null
        val entry = readEntries(context).firstOrNull { it.song.key == song.key } ?: return null
        val file = fileFor(context, entry) ?: return null
        return if (file.exists()) Uri.fromFile(file) else null
    }

    /** Starts a download. Returns false if the song can't be downloaded. */
    fun enqueue(context: Context, song: ApiSong): Boolean {
        val url = song.media_url ?: return false
        if (!url.startsWith("http")) return false
        val entries = readEntries(context)
        if (entries.any { it.song.key == song.key }) return true

        val safeName = song.displayTitle.replace("[^a-zA-Z0-9.-]".toRegex(), "_").take(60)
        val fileName = "${safeName}_${song.key.hashCode().toUInt()}.mp3"

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(song.displayTitle)
            .setDescription("Downloading via Jarvis Music")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_MUSIC, fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val id = dm.enqueue(request)
        entries.add(Entry(fileName, id, song))
        writeEntries(context, entries)
        return true
    }

    /** Deletes a downloaded (or legacy local) song. Call from a background thread. */
    fun delete(context: Context, song: ApiSong): Boolean {
        val entries = readEntries(context)
        val entry = entries.firstOrNull { it.song.key == song.key }
        var deleted = false
        if (entry != null) {
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            try { dm?.remove(entry.downloadId) } catch (_: Exception) { }
            fileFor(context, entry)?.let { if (it.exists()) it.delete() }
            entries.remove(entry)
            writeEntries(context, entries)
            deleted = true
        } else if (song.media_url?.startsWith("file:") == true) {
            Uri.parse(song.media_url).path?.let { deleted = File(it).delete() }
        }
        refresh(context)
        return deleted
    }

    /** Every playable downloaded song, newest first. Call from a background thread. */
    fun downloadedSongs(context: Context): List<ApiSong> {
        refresh(context)
        val entries = readEntries(context)
        val known = entries.map { it.fileName }.toSet()

        val tracked = entries
            .filter { completedKeys.contains(it.song.key) }
            .reversed()
            .map { it.song }

        // Files downloaded by older versions of the app have no metadata
        val legacy = musicDir(context)
            ?.listFiles { file -> file.extension == "mp3" && file.name !in known }
            ?.sortedByDescending { it.lastModified() }
            ?.map { file ->
                ApiSong(
                    id = "local:${file.name}",
                    song = file.nameWithoutExtension.replace("_", " "),
                    singers = "Downloaded",
                    image = null,
                    media_url = Uri.fromFile(file).toString(),
                    album = null
                )
            } ?: emptyList()

        return tracked + legacy
    }
}
