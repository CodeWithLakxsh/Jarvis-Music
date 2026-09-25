package com.laksh.jarvismusic

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.laksh.jarvismusic.api.ApiSong
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * App-wide observable state shared between the player (MainActivity) and the
 * screens, so rows can highlight the playing song and pages refresh when the
 * library changes.
 */
object MusicState {
    val nowPlayingKey = MutableStateFlow<String?>(null)
    val isPlaying = MutableStateFlow(false)

    /** Identifies the list playback started from (a playlist, a home row…). */
    val sourceKey = MutableStateFlow<String?>(null)

    /** Name shown under "PLAYING FROM" in the full player. */
    var sourceName: String = "Jarvis Music"

    /** Our own shuffle (reorders upcoming songs) so the queue always matches what plays next. */
    val shuffleOn = MutableStateFlow(false)

    /** Song key -> position in the list playback started from; restores order when shuffle is turned off. */
    var originalOrder: Map<String, Int> = emptyMap()

    /** Player index where the next "Add to queue" song goes (after earlier queued songs). */
    var manualQueueEnd = 0

    /** Bumped whenever likes, playlists or downloads change. */
    val libraryVersion = MutableStateFlow(0)

    fun notifyLibraryChanged() {
        libraryVersion.value = libraryVersion.value + 1
    }
}

data class ArtistInfo(val name: String, val imageUrl: String)

object ArtistCatalog {
    val popular = listOf(
        ArtistInfo("Arijit Singh", "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcRETraqkav99oDFbUFGsbCDZl0Mteg_X51A8QGEdAE7vwEtf5WxDLa__Nl6HP71zQTFI7scIFhdXe-HvmMlZTX9grvaQBvTHDmR8-dRxiCzGQ&s=10"),
        ArtistInfo("Sidhu Moose Wala", "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcQJdEcXaCXdx1sM2bPH-_uWj2UwEHkz3jtJHOobOxAu6vMQYQbSCDDWFessB633dJ7Hk-RoO0c_tRkSW7A6TX39DXeRxJ1_KaoiFiSFr6-v&s=10"),
        ArtistInfo("Karan Aujla", "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcT9Y18c9heMbV95Veq1ilfjGeVxROeSxUMS8sTjJCXxbhF8y4oudkwkMZfndm-J25QhQQuokNBKOQSD_kk9o1zrcNyLP8vJyGHczPLY8PzS&s=10"),
        ArtistInfo("Badshah", "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcT2tBCiyAppsoI55CUm96M4hWI2h-kLR0jKxTnFVdhspmMCan60c7K0leP3pyObxFKjtnAivpXAHWcwqmCwsqT35YsLHFXpcGUU6LCPc0lj&s=10"),
        ArtistInfo("Diljit Dosanjh", "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcRXfY1DhaCBMBuzZvxk-gDFNsU6ECeU25uZSIdJgXPCQ6VIp5ipoLztB6Nf4XlSMSd9lkwTMa4QCx94xuXpDwV7zbgkSAIEojiM_ByHv5a6&s=10"),
        ArtistInfo("Shubh", "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcSw6PLNP_f0QyZcsXrEpcXxHulnVgYu9PIt_s3E6scoX6U-NNV-20SNWTeXG69Am31Zh0wuoXvbYOq0Fqmi3T_j3jUi9FMgzV9DDRU5iB0A&s=10"),
        ArtistInfo("Masoom Sharma", "https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcRfAI3PVX5dpvihMDSVQTUVYgh8QuLBRFCIRpjhIJ7DmtsnfT6TGr_-d1vMJufZox9tmPeMSYLd_BjyD0hMgluvLzQrC4NoiGlVd-hQkjdh&s=10"),
        ArtistInfo("KD", "https://c.saavncdn.com/artists/KD_20191130133214_500x500.jpg")
    )

    fun imageFor(name: String): String? =
        popular.firstOrNull { it.name.equals(name, ignoreCase = true) }?.imageUrl
}

/** Small SharedPreferences-backed stores: recents, search history, favourite artists. */
object LocalStore {

    private const val APP_PREFS = "JarvisPrefs"
    private const val KEY_FIRST_TIME = "isFirstTime"
    private const val KEY_FAVORITE_ARTISTS = "favorite_artists"

    private const val HISTORY_PREFS = "JarvisSearchHistory"
    private const val HISTORY_KEY = "history_list"
    private const val HISTORY_SEPARATOR = "|||"

    private const val RECENTS_PREFS = "JarvisRecents"
    private const val RECENTS_KEY = "recent_songs"
    private const val MAX_RECENTS = 30

    private val gson = Gson()

    // --- First launch / onboarding ---

    fun isFirstTime(context: Context): Boolean =
        context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE).getBoolean(KEY_FIRST_TIME, true)

    fun completeOnboarding(context: Context, artists: List<String>) {
        context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_FIRST_TIME, false)
            .putString(KEY_FAVORITE_ARTISTS, artists.joinToString(HISTORY_SEPARATOR))
            .apply()
    }

    fun favoriteArtists(context: Context): List<String> {
        val raw = context.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_FAVORITE_ARTISTS, "") ?: ""
        return raw.split(HISTORY_SEPARATOR).filter { it.isNotBlank() }
    }

    // --- Search history (same format as v1.0 so old history is kept) ---

    fun searchHistory(context: Context): List<String> {
        val raw = context.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE)
            .getString(HISTORY_KEY, "") ?: ""
        return raw.split(HISTORY_SEPARATOR).filter { it.isNotBlank() }
    }

    private fun saveHistory(context: Context, list: List<String>) {
        context.getSharedPreferences(HISTORY_PREFS, Context.MODE_PRIVATE).edit()
            .putString(HISTORY_KEY, list.joinToString(HISTORY_SEPARATOR))
            .apply()
    }

    fun addSearch(context: Context, query: String) {
        val list = searchHistory(context).toMutableList()
        list.removeAll { it.equals(query, ignoreCase = true) }
        list.add(0, query)
        // removeAt(lastIndex) instead of removeLast(): the latter crashes on Android < 15
        while (list.size > 10) list.removeAt(list.lastIndex)
        saveHistory(context, list)
    }

    fun removeSearch(context: Context, query: String) {
        saveHistory(context, searchHistory(context).filter { it != query })
    }

    fun clearSearches(context: Context) = saveHistory(context, emptyList())

    // --- Recently played ---

    fun recentSongs(context: Context): List<ApiSong> {
        val json = context.getSharedPreferences(RECENTS_PREFS, Context.MODE_PRIVATE)
            .getString(RECENTS_KEY, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<ApiSong>>() {}.type
            gson.fromJson<List<ApiSong>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addRecent(context: Context, song: ApiSong) {
        // Local-only files are not worth showing on Home
        if (song.media_url?.startsWith("http") != true) return
        val list = recentSongs(context).toMutableList()
        list.removeAll { it.key == song.key }
        list.add(0, song)
        while (list.size > MAX_RECENTS) list.removeAt(list.lastIndex)
        context.getSharedPreferences(RECENTS_PREFS, Context.MODE_PRIVATE).edit()
            .putString(RECENTS_KEY, gson.toJson(list))
            .apply()
    }
}
