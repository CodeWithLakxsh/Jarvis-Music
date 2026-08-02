package com.laksh.jarvismusic

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.laksh.jarvismusic.api.ApiSong
import com.laksh.jarvismusic.api.RetrofitInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.File

class MainActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var bottomSheetBehavior: BottomSheetBehavior<FrameLayout>
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var miniPlayer: LinearLayout
    private lateinit var fullPlayerLayout: LinearLayout
    private lateinit var seekBar: SeekBar
    private lateinit var btnPlayPauseMini: ImageView
    private lateinit var btnPlayPauseFull: com.google.android.material.button.MaterialButton
    private lateinit var bottomNav: BottomNavigationView

    private lateinit var btnLike: ImageView
    private lateinit var btn_repeat: ImageView
    private lateinit var btn_shuffle: ImageView
    private lateinit var btnDownload: ImageView
    private lateinit var btnOptions: ImageView
    private lateinit var btnUpNext: ImageView

    private lateinit var db: AppDatabase
    private lateinit var songDao: SongDao
    private var currentApiSong: ApiSong? = null
    private var isCurrentSongLiked = false

    // --- QUEUE SYSTEM TRACKERS ---
    private var playbackQueue: MutableList<ApiSong> = mutableListOf()
    private var currentSongIndex: Int = -1
    private var isAutoGenerating = false

    // Universal Trackers
    private var currentDownloadUrl: String? = null
    private var currentDownloadTitle: String? = null
    private var currentArtistName: String? = null
    private var currentImageUrl: String? = null

    private val playDownloadedLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val filePath = result.data?.getStringExtra("file_path")
            val fileName = result.data?.getStringExtra("file_name")
            if (filePath != null) {
                playLocalFile(filePath, fileName ?: "Downloaded Song")
            }
        }
    }

    private val playDatabaseLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val audioUrl = result.data?.getStringExtra("audio_url") ?: return@registerForActivityResult
            val title = result.data?.getStringExtra("title") ?: "Unknown"
            val artist = result.data?.getStringExtra("artist") ?: "Unknown"
            val imageUrl = result.data?.getStringExtra("image_url") ?: ""
            val songId = result.data?.getStringExtra("song_id") ?: ""

            playDatabaseSong(songId, title, artist, imageUrl, audioUrl)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)

        setContentView(R.layout.activity_main)

        db = AppDatabase.getDatabase(this)
        songDao = db.songDao()

        bottomNav = findViewById(R.id.bottom_navigation)
        miniPlayer = findViewById(R.id.mini_player)
        fullPlayerLayout = findViewById(R.id.full_player_layout)
        seekBar = findViewById(R.id.player_seekbar)
        btnPlayPauseMini = findViewById(R.id.btn_play_pause)
        btnPlayPauseFull = findViewById(R.id.btn_full_play_pause)
        btnLike = findViewById(R.id.btn_like)
        btn_repeat = findViewById(R.id.btn_repeat)
        btn_shuffle = findViewById(R.id.btn_shuffle)
        btnDownload = findViewById(R.id.btn_download)
        btnOptions = findViewById(R.id.btn_options)
        btnUpNext = findViewById(R.id.btn_up_next)

        setupNavigation(savedInstanceState)
        setupMusicPlayer()
        setupEdgeToEdge()
        setupBackNavigation()

        showWelcomeDialog()
    }

    private fun showWelcomeDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_welcome, null)

        val builder = AlertDialog.Builder(this)
        builder.setView(dialogView)
        builder.setCancelable(false)

        val dialog = builder.create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val btnEnter = dialogView.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_enter_app)
        btnEnter.setOnClickListener {
            dialog.dismiss()

            val prefs = getSharedPreferences("JarvisPrefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean("isFirstTime", true)) {
                startActivity(Intent(this, OnboardingActivity::class.java))
            }
        }
        dialog.show()
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (bottomSheetBehavior.state == BottomSheetBehavior.STATE_EXPANDED) {
                    bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun setupMusicPlayer() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .build()

        val playerSheet = findViewById<FrameLayout>(R.id.player_sheet)
        bottomSheetBehavior = BottomSheetBehavior.from(playerSheet)

        bottomSheetBehavior.peekHeight = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 70f, resources.displayMetrics
        ).toInt()

        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN

        bottomSheetBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                miniPlayer.alpha = 1 - (slideOffset * 2).coerceIn(0f, 1f)
                fullPlayerLayout.alpha = slideOffset
                bottomNav.translationY = slideOffset * bottomNav.height

                if (slideOffset > 0.5f) {
                    miniPlayer.visibility = View.GONE
                    fullPlayerLayout.visibility = View.VISIBLE
                } else {
                    miniPlayer.visibility = View.VISIBLE
                    fullPlayerLayout.visibility = View.GONE
                }
            }

            override fun onStateChanged(bottomSheet: View, newState: Int) {
                if (newState == BottomSheetBehavior.STATE_EXPANDED) {
                    bottomNav.visibility = View.GONE
                } else if (newState == BottomSheetBehavior.STATE_COLLAPSED) {
                    bottomNav.visibility = View.VISIBLE
                    bottomNav.translationY = 0f
                }
            }
        })

        val toggleAction = View.OnClickListener {
            player?.let { if (it.isPlaying) it.pause() else it.play() }
        }
        btnPlayPauseMini.setOnClickListener(toggleAction)
        btnPlayPauseFull.setOnClickListener(toggleAction)

        findViewById<ImageView>(R.id.btn_next).setOnClickListener { playNextSong() }
        findViewById<ImageView>(R.id.btn_prev).setOnClickListener { playPreviousSong() }
        btnUpNext.setOnClickListener { showQueueBottomSheet() }

        btnOptions.setOnClickListener {
            showOptionsBottomSheet()
        }

        btn_shuffle.setOnClickListener {
            player?.let {
                it.shuffleModeEnabled = !it.shuffleModeEnabled
                val activeColor = if (it.shuffleModeEnabled) Color.parseColor("#1DB954") else Color.WHITE
                btn_shuffle.setColorFilter(activeColor)
            }
        }

        btn_repeat.setOnClickListener {
            player?.let {
                when (it.repeatMode) {
                    Player.REPEAT_MODE_OFF -> {
                        it.repeatMode = Player.REPEAT_MODE_ALL
                        btn_repeat.setImageResource(R.drawable.ic_repeat_all)
                        btn_repeat.setColorFilter(Color.parseColor("#1DB954"))
                    }
                    Player.REPEAT_MODE_ALL -> {
                        it.repeatMode = Player.REPEAT_MODE_ONE
                        btn_repeat.setImageResource(R.drawable.ic_repeat_one)
                        btn_repeat.setColorFilter(Color.parseColor("#1DB954"))
                    }
                    else -> {
                        it.repeatMode = Player.REPEAT_MODE_OFF
                        btn_repeat.setImageResource(R.drawable.ic_repeat_all)
                        btn_repeat.setColorFilter(Color.WHITE)
                    }
                }
            }
        }

        btnDownload.setOnClickListener {
            if (currentDownloadUrl != null && currentDownloadTitle != null) {
                downloadSongToPhone(currentDownloadUrl!!, currentDownloadTitle!!)
            } else {
                Toast.makeText(this, "Song already downloaded or unavailable", Toast.LENGTH_SHORT).show()
            }
        }

        btnLike.setOnClickListener {
            val songId = currentApiSong?.id ?: currentApiSong?.song ?: currentDownloadTitle ?: return@setOnClickListener
            val title = currentDownloadTitle ?: "Unknown"
            val artist = currentArtistName ?: "Unknown Artist"
            val img = currentImageUrl ?: ""
            val dlUrl = currentDownloadUrl ?: ""

            isCurrentSongLiked = !isCurrentSongLiked
            updateLikeUI()

            lifecycleScope.launch(Dispatchers.IO) {
                val likedSong = LikedSong(
                    id = songId, title = title, artist = artist, imageUrl = img, audioUrl = dlUrl
                )
                if (isCurrentSongLiked) songDao.insertSong(likedSong) else songDao.deleteSong(likedSong)
            }
        }

        miniPlayer.setOnClickListener { bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED }
        findViewById<ImageView>(R.id.btn_collapse).setOnClickListener { bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) player?.seekTo(p.toLong())
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        player?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val iconRes = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
                btnPlayPauseMini.setImageResource(iconRes)
                btnPlayPauseFull.setIconResource(iconRes)
                if (isPlaying) {
                    startSeekBarUpdate()
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) {
                    seekBar.max = player?.duration?.toInt() ?: 0

                    if (playbackQueue.size - currentSongIndex < 10) {
                        autoGenerateInfiniteSongs()
                    }
                }
                if (state == Player.STATE_ENDED) {
                    playNextSong()
                }
            }
        })
    }

    fun setQueueAndPlay(song: ApiSong, fullList: List<ApiSong>) {
        playbackQueue.clear()
        playbackQueue.addAll(fullList)
        currentSongIndex = playbackQueue.indexOf(song)
        playSong(song)
    }

    private fun playNextSong() {
        if (currentSongIndex < playbackQueue.size - 1) {
            currentSongIndex++
            playSong(playbackQueue[currentSongIndex])
        } else {
            autoGenerateInfiniteSongs(forcePlayNext = true)
        }
    }

    private fun playPreviousSong() {
        if (currentSongIndex > 0) {
            currentSongIndex--
            playSong(playbackQueue[currentSongIndex])
        }
    }

    private fun autoGenerateInfiniteSongs(forcePlayNext: Boolean = false) {
        // 1. Prevent multiple requests from running at once
        if (isAutoGenerating) return
        isAutoGenerating = true

        val radioMix = listOf("Trending", "Top 50", "Viral", "Hits", currentArtistName ?: "Latest")
        val randomSearch = radioMix.random()

        // 2. Use lifecycleScope to run the API call on the background thread
        lifecycleScope.launch {
            try {
                // 3. Call the API directly (No .enqueue needed for suspend functions)
                val newSongs = RetrofitInstance.api.searchSongs(randomSearch, 20)

                // 4. Handle the result
                if (!newSongs.isNullOrEmpty()) {
                    val uniqueSongs = newSongs.filter { newItem ->
                        playbackQueue.none { existing -> existing.id == newItem.id }
                    }.shuffled()

                    playbackQueue.addAll(uniqueSongs)

                    if (forcePlayNext && uniqueSongs.isNotEmpty()) {
                        currentSongIndex++
                        playSong(playbackQueue[currentSongIndex])
                    }
                }
            } catch (e: Exception) {
                // 5. Handle any network or parsing errors safely
                Log.e("API_ERROR", "Auto-gen failed: ${e.message}")
            } finally {
                // 6. 🔥 IMPORTANT: Always reset this flag, even if the request fails,
                // otherwise your app will never fetch new songs again.
                isAutoGenerating = false
            }
        }
    }

    private fun showQueueBottomSheet() {
        val bottomSheetDialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_queue_bottom_sheet, null)
        bottomSheetDialog.setContentView(view)

        val rvQueue = view.findViewById<RecyclerView>(R.id.rv_queue_list)
        rvQueue.layoutManager = LinearLayoutManager(this)

        val upcoming = if (currentSongIndex < playbackQueue.size - 1) {
            playbackQueue.subList(currentSongIndex + 1, playbackQueue.size).toMutableList()
        } else mutableListOf()

        rvQueue.adapter = QueueAdapter(upcoming) { positionToRemove ->
            val actualIndex = currentSongIndex + 1 + positionToRemove
            if (actualIndex < playbackQueue.size) {
                playbackQueue.removeAt(actualIndex)
                upcoming.removeAt(positionToRemove)
                rvQueue.adapter?.notifyItemRemoved(positionToRemove)
            }
        }
        bottomSheetDialog.show()
    }

    private fun showOptionsBottomSheet() {
        if (currentDownloadTitle == null) return

        val bottomSheetDialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_song_options, null)
        bottomSheetDialog.setContentView(view)

        val tvTitle = view.findViewById<TextView>(R.id.tv_option_title)
        val tvArtist = view.findViewById<TextView>(R.id.tv_option_artist)
        val imgArt = view.findViewById<ImageView>(R.id.img_option_art)
        val optionAddPlaylist = view.findViewById<LinearLayout>(R.id.option_add_playlist)
        val optionShare = view.findViewById<LinearLayout>(R.id.option_share)

        tvTitle.text = currentDownloadTitle
        tvArtist.text = currentArtistName ?: "Unknown Artist"
        Glide.with(this).load(currentImageUrl).placeholder(R.drawable.ic_download).into(imgArt)

        optionShare.setOnClickListener {
            bottomSheetDialog.dismiss()
            if (currentDownloadUrl != null) {
                val shareIntent = Intent(Intent.ACTION_SEND)
                shareIntent.type = "text/plain"
                val shareMessage = "Listen to ${currentDownloadTitle} on Jarvis Music!\n\n${currentDownloadUrl}"
                shareIntent.putExtra(Intent.EXTRA_TEXT, shareMessage)
                startActivity(Intent.createChooser(shareIntent, "Share Song via"))
            } else {
                Toast.makeText(this, "Cannot share local files", Toast.LENGTH_SHORT).show()
            }
        }

        optionAddPlaylist.setOnClickListener {
            bottomSheetDialog.dismiss()

            lifecycleScope.launch(Dispatchers.IO) {
                val playlists = db.playlistDao().getAllPlaylists()

                withContext(Dispatchers.Main) {
                    val builder = AlertDialog.Builder(this@MainActivity)
                    builder.setTitle("Add to Playlist")

                    if (playlists.isEmpty()) {
                        builder.setMessage("No playlists available yet.")
                    } else {
                        val playlistNames = playlists.map { it.name }.toTypedArray()
                        builder.setItems(playlistNames) { _, which ->
                            saveSongToPlaylist(playlists[which])
                        }
                    }

                    builder.setPositiveButton("+ Create New Playlist") { _, _ ->
                        createNewPlaylistFromPlayer()
                    }
                    builder.setNegativeButton("Cancel", null)

                    val dialog = builder.create()
                    dialog.setOnShowListener {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.parseColor("#1DB954"))
                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.WHITE)
                    }
                    dialog.show()
                }
            }
        }
        bottomSheetDialog.show()
    }

    private fun saveSongToPlaylist(playlist: Playlist) {
        lifecycleScope.launch(Dispatchers.IO) {
            val playlistSong = PlaylistSong(
                playlistId = playlist.playlistId,
                songId = currentApiSong?.id ?: currentDownloadTitle ?: "",
                title = currentDownloadTitle ?: "Unknown",
                artist = currentArtistName ?: "Unknown Artist",
                imageUrl = currentImageUrl ?: "",
                audioUrl = currentDownloadUrl ?: ""
            )
            db.playlistDao().insertSongToPlaylist(playlistSong)
            withContext(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Added to ${playlist.name}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun createNewPlaylistFromPlayer() {
        val input = EditText(this)
        input.hint = "Playlist Name"
        input.setTextColor(Color.WHITE)
        input.setHintTextColor(Color.GRAY)
        input.setPadding(60, 40, 60, 40)

        val dialog = AlertDialog.Builder(this)
            .setTitle("New Playlist")
            .setView(input)
            .setPositiveButton("Create & Add Song") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        db.playlistDao().insertPlaylist(Playlist(name = name))
                        val newPlaylist = db.playlistDao().getAllPlaylists().last()
                        saveSongToPlaylist(newPlaylist)
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.parseColor("#1DB954"))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.WHITE)
        }
        dialog.show()
    }

    fun playSong(song: ApiSong) {
        val url = song.media_url ?: return
        currentApiSong = song

        currentDownloadUrl = url
        currentDownloadTitle = song.song
        currentArtistName = song.singers
        currentImageUrl = song.image

        player?.stop()
        player?.clearMediaItems()

        findViewById<TextView>(R.id.mini_title).text = currentDownloadTitle
        findViewById<TextView>(R.id.mini_artist).text = currentArtistName
        findViewById<TextView>(R.id.full_title).text = currentDownloadTitle
        findViewById<TextView>(R.id.full_artist).text = currentArtistName

        Glide.with(this).load(currentImageUrl).into(findViewById(R.id.mini_album_art))
        Glide.with(this).load(currentImageUrl).into(findViewById(R.id.full_album_art))

        checkIfSongIsLiked(song)
        btnDownload.setColorFilter(Color.WHITE)

        val mediaItem = MediaItem.fromUri(url)
        player?.setMediaItem(mediaItem)
        player?.prepare()
        player?.play()

        val playerSheet = findViewById<FrameLayout>(R.id.player_sheet)
        playerSheet.visibility = View.VISIBLE
        if (bottomSheetBehavior.state == BottomSheetBehavior.STATE_HIDDEN) {
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }
    }

    private fun playLocalFile(path: String, name: String) {
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(this, "File not found", Toast.LENGTH_SHORT).show()
            return
        }

        player?.stop()
        player?.clearMediaItems()
        currentApiSong = null
        currentDownloadUrl = null
        currentDownloadTitle = name
        currentArtistName = "Offline • Local File"
        currentImageUrl = null

        findViewById<TextView>(R.id.mini_title).text = currentDownloadTitle
        findViewById<TextView>(R.id.mini_artist).text = currentArtistName
        findViewById<TextView>(R.id.full_title).text = currentDownloadTitle
        findViewById<TextView>(R.id.full_artist).text = currentArtistName

        btnLike.setImageResource(R.drawable.ic_heart_outline)
        btnLike.setColorFilter(Color.WHITE)
        btnDownload.setColorFilter(Color.parseColor("#1DB954"))

        findViewById<ImageView>(R.id.mini_album_art).setImageResource(R.drawable.ic_download)
        findViewById<ImageView>(R.id.full_album_art).setImageResource(R.drawable.ic_download)

        val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
        player?.setMediaItem(mediaItem)
        player?.prepare()
        player?.play()

        val playerSheet = findViewById<FrameLayout>(R.id.player_sheet)
        playerSheet.post {
            playerSheet.visibility = View.VISIBLE
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
            handler.postDelayed({ bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED }, 100)
        }
    }

    private fun playDatabaseSong(id: String, title: String, artist: String, imageUrl: String, audioUrl: String) {
        player?.stop()
        player?.clearMediaItems()
        currentApiSong = null
        currentDownloadUrl = audioUrl
        currentDownloadTitle = title
        currentArtistName = artist
        currentImageUrl = imageUrl

        findViewById<TextView>(R.id.mini_title).text = currentDownloadTitle
        findViewById<TextView>(R.id.mini_artist).text = currentArtistName
        findViewById<TextView>(R.id.full_title).text = currentDownloadTitle
        findViewById<TextView>(R.id.full_artist).text = currentArtistName

        Glide.with(this).load(currentImageUrl).into(findViewById(R.id.mini_album_art))
        Glide.with(this).load(currentImageUrl).into(findViewById(R.id.full_album_art))

        isCurrentSongLiked = true
        updateLikeUI()
        btnDownload.setColorFilter(Color.WHITE)

        val mediaItem = MediaItem.fromUri(Uri.parse(audioUrl))
        player?.setMediaItem(mediaItem)
        player?.prepare()
        player?.play()

        val playerSheet = findViewById<FrameLayout>(R.id.player_sheet)
        playerSheet.post {
            playerSheet.visibility = View.VISIBLE
            bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
            handler.postDelayed({ bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED }, 100)
        }
    }

    private fun checkIfSongIsLiked(song: ApiSong) {
        val songId = song.id ?: song.song ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            isCurrentSongLiked = songDao.isLiked(songId)
            withContext(Dispatchers.Main) { updateLikeUI() }
        }
    }

    private fun updateLikeUI() {
        if (isCurrentSongLiked) {
            btnLike.setImageResource(R.drawable.ic_heart_filled)
            btnLike.setColorFilter(Color.parseColor("#1DB954"))
        } else {
            btnLike.setImageResource(R.drawable.ic_heart_outline)
            btnLike.setColorFilter(Color.WHITE)
        }
    }

    private fun startSeekBarUpdate() {
        player?.let {
            if (it.isPlaying) {
                seekBar.progress = it.currentPosition.toInt()
                handler.postDelayed({ startSeekBarUpdate() }, 1000)
            }
        }
    }

    private fun downloadSongToPhone(url: String, title: String) {
        val cleanTitle = title.replace("[^a-zA-Z0-9.-]".toRegex(), "_")
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(title)
            .setDescription("Downloading via Jarvis Music")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_MUSIC, "$cleanTitle.mp3")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        downloadManager.enqueue(request)

        Toast.makeText(this, "Downloading $title...", Toast.LENGTH_SHORT).show()
        btnDownload.setColorFilter(Color.parseColor("#1DB954"))
    }

    fun openDownloads() {
        val intent = Intent(this, DownloadedSongsActivity::class.java)
        playDownloadedLauncher.launch(intent)
    }

    fun openLikedSongs() {
        val intent = Intent(this, LikedSongsActivity::class.java)
        playDatabaseLauncher.launch(intent)
    }

    fun openPlaylists() {
        val intent = Intent(this, PlaylistsActivity::class.java)
        playDatabaseLauncher.launch(intent)
    }

    private fun setupNavigation(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            loadFragment(HomeFragment())
            bottomNav.selectedItemId = R.id.nav_home
        }
        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> loadFragment(HomeFragment())
                R.id.nav_search -> loadFragment(SearchFragment())
                R.id.nav_library -> loadFragment(LibraryFragment())
            }
            true
        }
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction().replace(R.id.fragment_container, fragment).commit()
    }

    private fun setupEdgeToEdge() {
        val mainRoot = findViewById<View>(R.id.main_root)
        ViewCompat.setOnApplyWindowInsetsListener(mainRoot) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, 0)
            insets
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        handler.removeCallbacksAndMessages(null)
    }
}