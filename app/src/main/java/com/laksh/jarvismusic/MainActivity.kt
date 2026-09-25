package com.laksh.jarvismusic

import android.animation.ValueAnimator
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.TextViewCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.common.util.concurrent.ListenableFuture
import com.laksh.jarvismusic.api.ApiSong
import com.laksh.jarvismusic.api.RetrofitInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min

/** Implemented by tab roots so re-tapping a tab scrolls it back to the top. */
interface ScrollToTop {
    fun scrollToTop()
}

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "JarvisMusic"
        private const val KEY_TAB = "current_tab"
        private const val MINI_PLAYER_HEIGHT_DP = 60
        private val TABS = listOf(R.id.nav_home, R.id.nav_search, R.id.nav_library)
    }

    // --- Player connection (the ExoPlayer lives in PlaybackService) ---
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var player: MediaController? = null
    private val pendingPlayerActions = mutableListOf<(MediaController) -> Unit>()

    private lateinit var db: AppDatabase
    private val handler = Handler(Looper.getMainLooper())

    // --- Views ---
    private lateinit var bottomNav: BottomNavigationView
    private lateinit var playerSheet: FrameLayout
    private lateinit var sheetBehavior: BottomSheetBehavior<FrameLayout>

    private lateinit var miniPlayer: View
    private lateinit var miniCard: View
    private lateinit var miniInfo: View
    private lateinit var miniArt: ImageView
    private lateinit var miniTitle: TextView
    private lateinit var miniArtist: TextView
    private lateinit var miniLike: ImageView
    private lateinit var miniPlayPause: ImageView
    private lateinit var miniProgress: ProgressBar

    private lateinit var fullPlayer: View
    private lateinit var fullArt: ImageView
    private lateinit var fullTitle: TextView
    private lateinit var fullArtist: TextView
    private lateinit var tvPlayingFrom: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var tvElapsed: TextView
    private lateinit var tvRemaining: TextView
    private lateinit var btnPlayPauseFull: ImageButton
    private lateinit var btnShuffle: ImageView
    private lateinit var btnRepeat: ImageView
    private lateinit var btnLike: ImageView
    private lateinit var btnDownload: ImageView

    private val fullBackground = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0, 0))

    // --- State ---
    private var currentTab = R.id.nav_home
    private var currentSong: ApiSong? = null
    private var isCurrentLiked = false
    private var currentArtColor = ArtColors.DEFAULT
    private var colorAnimator: ValueAnimator? = null
    private var isUserSeeking = false
    private var isAutoGenerating = false
    private var consecutiveErrors = 0
    private var miniSwiped = false
    private var queueDialog: BottomSheetDialog? = null

    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    private val downloadReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { DownloadStore.refresh(applicationContext) }
                MusicState.notifyLibraryChanged()
                updateDownloadButton()
            }
        }
    }

    // =====================================================================
    // Lifecycle
    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        db = AppDatabase.getDatabase(this)

        bindViews()
        setupInsets()
        setupTabs(savedInstanceState)
        setupPlayerSheet()
        setupPlayerControls()
        setupBackNavigation()
        observeLibrary()

        ContextCompat.registerReceiver(
            this,
            downloadReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { DownloadStore.refresh(applicationContext) }
            MusicState.notifyLibraryChanged()
        }

        if (savedInstanceState == null && LocalStore.isFirstTime(this)) {
            showWelcomeDialog()
        }
    }

    override fun onStart() {
        super.onStart()
        connectToPlayer()
    }

    override fun onStop() {
        handler.removeCallbacks(progressRunnable)
        queueDialog?.dismiss()
        player?.removeListener(playerListener)
        player = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, currentTab)
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(downloadReceiver)
        } catch (_: IllegalArgumentException) {
        }
        colorAnimator?.cancel()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun connectToPlayer() {
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            if (controllerFuture !== future) return@addListener
            val controller = try {
                future.get()
            } catch (e: Exception) {
                Log.e(TAG, "Could not connect to the playback service", e)
                return@addListener
            }
            player = controller
            controller.addListener(playerListener)
            syncUiFromPlayer()
            val actions = pendingPlayerActions.toList()
            pendingPlayerActions.clear()
            actions.forEach { it(controller) }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Runs [action] now if the player is connected, or as soon as it connects. */
    private fun withPlayer(action: (MediaController) -> Unit) {
        val p = player
        if (p != null) action(p) else pendingPlayerActions.add(action)
    }

    private fun bindViews() {
        bottomNav = findViewById(R.id.bottom_navigation)
        playerSheet = findViewById(R.id.player_sheet)

        miniPlayer = findViewById(R.id.mini_player)
        miniCard = findViewById(R.id.mini_card)
        miniInfo = findViewById(R.id.mini_info)
        miniArt = findViewById(R.id.mini_album_art)
        miniTitle = findViewById(R.id.mini_title)
        miniArtist = findViewById(R.id.mini_artist)
        miniLike = findViewById(R.id.btn_mini_like)
        miniPlayPause = findViewById(R.id.btn_play_pause)
        miniProgress = findViewById(R.id.mini_progress)

        fullPlayer = findViewById(R.id.full_player_layout)
        fullArt = findViewById(R.id.full_album_art)
        fullTitle = findViewById(R.id.full_title)
        fullArtist = findViewById(R.id.full_artist)
        tvPlayingFrom = findViewById(R.id.tv_playing_from)
        seekBar = findViewById(R.id.player_seekbar)
        tvElapsed = findViewById(R.id.tv_elapsed)
        tvRemaining = findViewById(R.id.tv_remaining)
        btnPlayPauseFull = findViewById(R.id.btn_full_play_pause)
        btnShuffle = findViewById(R.id.btn_shuffle)
        btnRepeat = findViewById(R.id.btn_repeat)
        btnLike = findViewById(R.id.btn_like)
        btnDownload = findViewById(R.id.btn_download)

        seekBar.max = 1000
        fullPlayer.background = fullBackground
        applyArtColor(ArtColors.DEFAULT)
        miniCard.clipToOutline = true
    }

    private fun setupInsets() {
        // The bottom nav pads itself for the navigation bar; the full player needs both bars.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main_root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            fullPlayer.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        bottomNav.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) updatePeekHeight()
        }
    }

    private fun updatePeekHeight() {
        sheetBehavior.peekHeight = dp(MINI_PLAYER_HEIGHT_DP) + bottomNav.height
    }

    // =====================================================================
    // Tabs & pages
    // =====================================================================

    private fun tagFor(tabId: Int) = "tab_$tabId"

    private fun createTab(tabId: Int): Fragment = when (tabId) {
        R.id.nav_search -> SearchFragment()
        R.id.nav_library -> LibraryFragment()
        else -> HomeFragment()
    }

    private fun setupTabs(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.fragment_container, HomeFragment(), tagFor(R.id.nav_home))
                .commitNow()
        } else {
            currentTab = savedInstanceState.getInt(KEY_TAB, R.id.nav_home)
        }

        bottomNav.setOnItemSelectedListener { item ->
            switchTab(item.itemId)
            true
        }
        bottomNav.setOnItemReselectedListener { item ->
            val fm = supportFragmentManager
            if (fm.backStackEntryCount > 0) {
                fm.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
            } else {
                (fm.findFragmentByTag(tagFor(item.itemId)) as? ScrollToTop)?.scrollToTop()
            }
        }
    }

    private fun switchTab(tabId: Int) {
        val fm = supportFragmentManager
        // Pages opened from the previous tab (playlists, artists…) are closed
        if (fm.backStackEntryCount > 0) fm.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)

        val tx = fm.beginTransaction().setReorderingAllowed(true)
        TABS.forEach { id ->
            val fragment = fm.findFragmentByTag(tagFor(id)) ?: return@forEach
            if (id == tabId) tx.show(fragment) else tx.hide(fragment)
        }
        if (fm.findFragmentByTag(tagFor(tabId)) == null) {
            tx.add(R.id.fragment_container, createTab(tabId), tagFor(tabId))
        }
        tx.commit()
        currentTab = tabId
    }

    fun goToTab(tabId: Int) {
        if (bottomNav.selectedItemId != tabId) bottomNav.selectedItemId = tabId
        else if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }
    }

    /** Opens a playlist/artist/collection page on top of the current tab. */
    fun openPage(fragment: Fragment) {
        if (sheetBehavior.state != BottomSheetBehavior.STATE_COLLAPSED) {
            sheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }
        supportFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .setCustomAnimations(R.anim.page_enter, R.anim.page_exit, R.anim.page_pop_enter, R.anim.page_pop_exit)
            .add(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }

    fun closePage() {
        if (supportFragmentManager.backStackEntryCount > 0) supportFragmentManager.popBackStack()
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    playerSheet.visibility == View.VISIBLE &&
                        sheetBehavior.state != BottomSheetBehavior.STATE_COLLAPSED ->
                        sheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED

                    supportFragmentManager.backStackEntryCount > 0 ->
                        supportFragmentManager.popBackStack()

                    currentTab != R.id.nav_home ->
                        bottomNav.selectedItemId = R.id.nav_home

                    // Like Spotify: leaving the app keeps the music playing
                    MusicState.isPlaying.value -> moveTaskToBack(true)

                    else -> finish()
                }
            }
        })
    }

    private fun observeLibrary() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                MusicState.libraryVersion.collect {
                    currentSong?.let { refreshLikeState(it) }
                    updateDownloadButton()
                }
            }
        }
    }

    // =====================================================================
    // Welcome / onboarding
    // =====================================================================

    private fun showWelcomeDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_welcome, null)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        dialogView.findViewById<View>(R.id.btn_enter_app).setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
        dialog.show()
    }

    // =====================================================================
    // Player sheet (mini player <-> full player)
    // =====================================================================

    private fun setupPlayerSheet() {
        sheetBehavior = BottomSheetBehavior.from(playerSheet)
        sheetBehavior.isHideable = false
        updatePeekHeight()

        sheetBehavior.addBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {
                when (newState) {
                    BottomSheetBehavior.STATE_EXPANDED -> applySlide(1f)
                    BottomSheetBehavior.STATE_COLLAPSED -> applySlide(0f)
                }
            }

            override fun onSlide(bottomSheet: View, slideOffset: Float) = applySlide(slideOffset)
        })

        miniPlayer.setOnClickListener {
            if (miniSwiped) {
                miniSwiped = false
            } else {
                sheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
            }
        }
        findViewById<View>(R.id.btn_collapse).setOnClickListener {
            sheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        }

        setupMiniSwipe()
        setupArtSwipe()
    }

    private fun applySlide(offset: Float) {
        val o = offset.coerceIn(0f, 1f)
        miniPlayer.alpha = (1f - o * 4f).coerceIn(0f, 1f)
        miniPlayer.visibility = if (o >= 0.25f) View.INVISIBLE else View.VISIBLE
        fullPlayer.alpha = o
        fullPlayer.visibility = if (o > 0f) View.VISIBLE else View.INVISIBLE
        bottomNav.translationY = o * bottomNav.height
    }

    private fun showPlayerSheet() {
        if (playerSheet.visibility == View.VISIBLE) return
        playerSheet.visibility = View.VISIBLE
        updatePeekHeight()
        sheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
        applySlide(0f)
    }

    /** Swipe the mini player left/right to change songs, like Spotify. */
    private fun setupMiniSwipe() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val start = e1 ?: return false
                val dx = e2.x - start.x
                val dy = e2.y - start.y
                if (abs(dx) > abs(dy) && abs(dx) > dp(40) && abs(velocityX) > 300) {
                    miniSwiped = true
                    if (dx < 0) {
                        animateMiniSwipe(direction = 1) { skipToNext() }
                    } else {
                        animateMiniSwipe(direction = -1) { skipToPrevious() }
                    }
                    return true
                }
                return false
            }
        })
        miniPlayer.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) miniSwiped = false
            detector.onTouchEvent(event)
            false // let the click and the sheet drag work as usual
        }
    }

    private fun animateMiniSwipe(direction: Int, action: () -> Unit) {
        val distance = miniInfo.width.toFloat().coerceAtLeast(1f)
        miniInfo.animate()
            .translationX(-direction * distance)
            .alpha(0f)
            .setDuration(120)
            .withEndAction {
                action()
                miniInfo.translationX = direction * distance
                miniInfo.animate().translationX(0f).alpha(1f).setDuration(180).start()
            }
            .start()
    }

    /** Drag the album art sideways in the full player to change songs. */
    private fun setupArtSwipe() {
        var dragging = false
        var flingDirection = 0

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                val start = e1 ?: return false
                val dx = e2.x - start.x
                if (!dragging && abs(dx) > abs(e2.y - start.y) && abs(dx) > dp(12)) {
                    dragging = true
                    fullArt.parent.requestDisallowInterceptTouchEvent(true)
                }
                if (dragging) {
                    fullArt.translationX = dx
                    fullArt.alpha = 1f - min(0.6f, abs(dx) / fullArt.width.coerceAtLeast(1))
                }
                return dragging
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (dragging && abs(velocityX) > 600 && abs(velocityX) > abs(velocityY)) {
                    flingDirection = if (velocityX < 0) 1 else -1
                }
                return dragging
            }
        })

        fullArt.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (dragging) {
                    val threshold = fullArt.width / 4f
                    val direction = when {
                        flingDirection != 0 -> flingDirection
                        fullArt.translationX < -threshold -> 1
                        fullArt.translationX > threshold -> -1
                        else -> 0
                    }
                    if (direction == 0 || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                        fullArt.animate().translationX(0f).alpha(1f).setDuration(180).start()
                    } else {
                        animateArtOut(direction)
                    }
                }
                dragging = false
                flingDirection = 0
            }
            true
        }
    }

    private fun animateArtOut(direction: Int) {
        val distance = fullArt.width.toFloat()
        fullArt.animate()
            .translationX(-direction * distance)
            .alpha(0f)
            .setDuration(150)
            .withEndAction {
                if (direction > 0) skipToNext() else skipToPrevious()
                fullArt.translationX = direction * distance
                fullArt.animate().translationX(0f).alpha(1f).setDuration(220).start()
            }
            .start()
    }

    // =====================================================================
    // Player controls
    // =====================================================================

    private fun setupPlayerControls() {
        miniPlayPause.setOnClickListener { togglePlayPause() }
        btnPlayPauseFull.setOnClickListener { togglePlayPause() }
        findViewById<View>(R.id.btn_next).setOnClickListener { skipToNext() }
        findViewById<View>(R.id.btn_prev).setOnClickListener { skipToPrevious() }
        btnShuffle.setOnClickListener { toggleShuffle() }
        btnRepeat.setOnClickListener { cycleRepeat() }
        findViewById<View>(R.id.btn_up_next).setOnClickListener { showQueueSheet() }
        findViewById<View>(R.id.btn_share).setOnClickListener { currentSong?.let { shareSong(it) } }
        findViewById<View>(R.id.btn_options).setOnClickListener {
            currentSong?.let { showSongOptions(it, fromPlayer = true) }
        }

        val likeClick = View.OnClickListener {
            val song = currentSong ?: return@OnClickListener
            isCurrentLiked = !isCurrentLiked
            updateLikeUI()
            toggleLike(song)
        }
        btnLike.setOnClickListener(likeClick)
        miniLike.setOnClickListener(likeClick)

        fullArtist.setOnClickListener {
            currentSong?.primaryArtist?.let { openPage(CollectionFragment.artist(it)) }
        }

        btnDownload.setOnClickListener {
            val song = currentSong ?: return@setOnClickListener
            when {
                DownloadStore.isDownloaded(song) -> confirmRemoveDownload(song)
                DownloadStore.isDownloading(this, song) -> toast("Downloading…")
                else -> downloadSong(song)
            }
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val duration = currentDuration()
                val position = duration * progress / 1000
                tvElapsed.text = formatTime(position)
                tvRemaining.text = "-${formatTime(duration - position)}"
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                isUserSeeking = true
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                isUserSeeking = false
                val duration = currentDuration()
                if (duration > 0) player?.seekTo(duration * bar.progress / 1000)
            }
        })
    }

    private fun currentDuration(): Long {
        val duration = player?.duration ?: 0L
        return if (duration > 0) duration else 0L
    }

    private fun shouldShowPause(p: Player): Boolean =
        p.playWhenReady && p.playbackState != Player.STATE_ENDED && p.playbackState != Player.STATE_IDLE

    fun togglePlayPause() = withPlayer { p ->
        if (shouldShowPause(p)) {
            p.pause()
        } else {
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            if (p.playbackState == Player.STATE_ENDED) p.seekToDefaultPosition()
            p.play()
        }
    }

    private fun skipToNext() = withPlayer { p ->
        if (p.hasNextMediaItem()) {
            p.seekToNextMediaItem()
            p.play()
        } else {
            extendQueue(playWhenAdded = true)
        }
    }

    // Restarts the song if it has played for more than 3 seconds, like Spotify
    private fun skipToPrevious() = withPlayer { p -> p.seekToPrevious() }

    private fun cycleRepeat() = withPlayer { p ->
        p.repeatMode = when (p.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        updateModes()
    }

    /**
     * Shuffle reorders the upcoming songs instead of using the player's hidden
     * shuffle order, so the queue screen always shows what really plays next.
     */
    private fun toggleShuffle() = withPlayer { p ->
        val enable = !MusicState.shuffleOn.value
        MusicState.shuffleOn.value = enable
        val current = p.currentMediaItemIndex
        val count = p.mediaItemCount
        if (current >= 0 && count - current > 2) {
            val upcoming = (current + 1 until count).map { p.getMediaItemAt(it) }
            val reordered = if (enable) {
                upcoming.shuffled()
            } else {
                val order = MusicState.originalOrder
                upcoming.withIndex()
                    .sortedWith(compareBy({ order[it.value.mediaId] ?: Int.MAX_VALUE }, { it.index }))
                    .map { it.value }
            }
            p.removeMediaItems(current + 1, count)
            p.addMediaItems(reordered)
        }
        MusicState.manualQueueEnd = 0
        updateModes()
        toast(if (enable) "Shuffle on" else "Shuffle off")
    }

    // =====================================================================
    // Public playback API used by the screens
    // =====================================================================

    /**
     * Plays [songs] starting at [startIndex]. [shuffle] = true is "Shuffle play",
     * false is "Play in order", null keeps the current shuffle setting.
     */
    fun playSongs(
        songs: List<ApiSong>,
        startIndex: Int,
        sourceName: String,
        sourceKey: String? = null,
        shuffle: Boolean? = null
    ) {
        val clicked = songs.getOrNull(startIndex)
        if (clicked != null && !clicked.isPlayable && shuffle == null) {
            toast("This song isn't available right now")
            return
        }
        val playable = songs.filter { it.isPlayable }
        if (playable.isEmpty()) {
            toast("Nothing to play here yet")
            return
        }

        shuffle?.let { MusicState.shuffleOn.value = it }
        val shuffleOn = MusicState.shuffleOn.value
        var start = if (clicked == null) 0 else playable.indexOfFirst { it === clicked }.coerceAtLeast(0)

        val ordered = if (shuffleOn) {
            val first = if (shuffle == true) playable.random() else playable[start]
            start = 0
            listOf(first) + (playable - first).shuffled()
        } else {
            playable
        }

        MusicState.originalOrder = playable.mapIndexed { index, song -> song.key to index }.toMap()
        MusicState.sourceName = sourceName
        MusicState.sourceKey.value = sourceKey
        MusicState.manualQueueEnd = 0
        consecutiveErrors = 0

        val items = ordered.map { it.toMediaItem(this) }
        val startAt = start
        withPlayer { p ->
            p.setMediaItems(items, startAt, 0L)
            p.prepare()
            p.play()
        }
        showPlayerSheet()
        updatePlayingFrom()
        updateModes()
    }

    fun playNext(song: ApiSong) {
        if (!song.isPlayable) {
            toast("This song isn't available right now")
            return
        }
        withPlayer { p ->
            if (p.mediaItemCount == 0) {
                playSongs(listOf(song), 0, "Your queue")
                return@withPlayer
            }
            val current = p.currentMediaItemIndex
            p.addMediaItem(current + 1, song.toMediaItem(this))
            MusicState.manualQueueEnd =
                if (MusicState.manualQueueEnd > current) MusicState.manualQueueEnd + 1 else current + 2
            toast("Playing next")
        }
    }

    fun addToQueue(song: ApiSong) {
        if (!song.isPlayable) {
            toast("This song isn't available right now")
            return
        }
        withPlayer { p ->
            if (p.mediaItemCount == 0) {
                playSongs(listOf(song), 0, "Your queue")
                return@withPlayer
            }
            val insertAt = MusicState.manualQueueEnd.coerceIn(p.currentMediaItemIndex + 1, p.mediaItemCount)
            p.addMediaItem(insertAt, song.toMediaItem(this))
            MusicState.manualQueueEnd = insertAt + 1
            toast("Added to queue")
        }
    }

    /** Autoplay: keep the music going with similar songs when the queue runs out. */
    private fun extendQueue(playWhenAdded: Boolean) {
        if (isAutoGenerating) return
        isAutoGenerating = true

        val seeds = mutableListOf<String>()
        currentSong?.primaryArtist?.let { seeds += it; seeds += it }
        LocalStore.favoriteArtists(this).randomOrNull()?.let { seeds += it }
        seeds += listOf("Trending", "Top Hits")
        val query = seeds.random()

        lifecycleScope.launch {
            try {
                val results = RetrofitInstance.api.searchSongs(query, 20)
                val p = player ?: return@launch
                val existing = (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId }.toSet()
                val fresh = results.filter { it.isPlayable && it.key !in existing }.shuffled().take(10)
                if (fresh.isNotEmpty()) {
                    p.addMediaItems(fresh.map { it.toMediaItem(this@MainActivity) })
                    if (playWhenAdded) {
                        p.seekToNextMediaItem()
                        p.prepare()
                        p.play()
                    }
                } else if (playWhenAdded) {
                    toast("No more songs to play")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Autoplay failed: ${e.message}")
                if (playWhenAdded) toast("Couldn't load more songs")
            } finally {
                isAutoGenerating = false
            }
        }
    }

    private fun maybeExtendQueue() {
        val p = player ?: return
        if (p.repeatMode != Player.REPEAT_MODE_OFF || p.mediaItemCount == 0) return
        val remaining = p.mediaItemCount - 1 - p.currentMediaItemIndex
        if (remaining < 3) extendQueue(playWhenAdded = false)
    }

    // =====================================================================
    // Player events -> UI
    // =====================================================================

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_MEDIA_METADATA_CHANGED,
                    Player.EVENT_TIMELINE_CHANGED
                )
            ) {
                updateNowPlaying()
            }
            if (events.containsAny(
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                    Player.EVENT_PLAY_WHEN_READY_CHANGED
                )
            ) {
                updatePlayPause()
            }
            if (events.containsAny(Player.EVENT_REPEAT_MODE_CHANGED)) updateModes()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val p = player ?: return
            if (MusicState.manualQueueEnd <= p.currentMediaItemIndex) {
                MusicState.manualQueueEnd = p.currentMediaItemIndex + 1
            }
            mediaItem?.let { item ->
                val song = item.toApiSong()
                lifecycleScope.launch(Dispatchers.IO) { LocalStore.addRecent(applicationContext, song) }
            }
            maybeExtendQueue()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> consecutiveErrors = 0
                Player.STATE_ENDED -> {
                    val p = player
                    if (p != null && p.repeatMode == Player.REPEAT_MODE_OFF) extendQueue(playWhenAdded = true)
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Playback error", error)
            consecutiveErrors++
            val p = player ?: return
            if (consecutiveErrors > 3) {
                toast("Playback failed. Check your connection.")
                return
            }
            toast("Couldn't play this song. Skipping…")
            if (p.hasNextMediaItem()) {
                p.seekToNextMediaItem()
                p.prepare()
                p.play()
            } else {
                extendQueue(playWhenAdded = true)
            }
        }
    }

    private fun syncUiFromPlayer() {
        val p = player ?: return
        if (p.mediaItemCount > 0) updateNowPlaying()
        updatePlayPause()
        updateModes()
        updatePlayingFrom()
        handler.removeCallbacks(progressRunnable)
        handler.post(progressRunnable)
    }

    private fun updateNowPlaying() {
        val p = player ?: return
        val item = p.currentMediaItem
        if (item == null) {
            currentSong = null
            MusicState.nowPlayingKey.value = null
            return
        }
        val song = item.toApiSong()
        val changed = currentSong?.key != song.key
        currentSong = song
        MusicState.nowPlayingKey.value = song.key
        showPlayerSheet()

        miniTitle.text = song.displayTitle
        miniArtist.text = song.displayArtist
        fullTitle.text = song.displayTitle
        fullTitle.isSelected = true // start the marquee for long titles
        fullArtist.text = song.displayArtist

        if (changed) {
            miniArt.loadArt(song.image)
            fullArt.loadArt(song.hiResImage)
            ArtColors.extract(this, song.image) { color ->
                if (currentSong?.key == song.key) animateArtColor(color)
            }
            refreshLikeState(song)
            updateDownloadButton()
        }
        updatePlayingFrom()
    }

    private fun updatePlayPause() {
        val p = player ?: return
        val showPause = shouldShowPause(p)
        MusicState.isPlaying.value = showPause
        val icon = if (showPause) R.drawable.ic_pause else R.drawable.ic_play
        miniPlayPause.setImageResource(icon)
        btnPlayPauseFull.setImageResource(icon)
    }

    private fun updateModes() {
        val green = ContextCompat.getColor(this, R.color.spotify_green)
        val white = Color.WHITE
        btnShuffle.imageTintList = ColorStateList.valueOf(if (MusicState.shuffleOn.value) green else white)

        when (player?.repeatMode ?: Player.REPEAT_MODE_OFF) {
            Player.REPEAT_MODE_ALL -> {
                btnRepeat.setImageResource(R.drawable.ic_repeat_all)
                btnRepeat.imageTintList = ColorStateList.valueOf(green)
            }
            Player.REPEAT_MODE_ONE -> {
                btnRepeat.setImageResource(R.drawable.ic_repeat_one)
                btnRepeat.imageTintList = ColorStateList.valueOf(green)
            }
            else -> {
                btnRepeat.setImageResource(R.drawable.ic_repeat_all)
                btnRepeat.imageTintList = ColorStateList.valueOf(white)
            }
        }
    }

    private fun updatePlayingFrom() {
        tvPlayingFrom.text = MusicState.sourceName
    }

    private fun updateProgress() {
        val p = player ?: return
        val duration = currentDuration()
        val position = p.currentPosition.coerceIn(0L, if (duration > 0) duration else Long.MAX_VALUE)
        val fraction = if (duration > 0) (position * 1000 / duration).toInt() else 0
        miniProgress.progress = fraction
        if (!isUserSeeking) {
            seekBar.progress = fraction
            seekBar.secondaryProgress =
                if (duration > 0) (p.bufferedPosition.coerceAtMost(duration) * 1000 / duration).toInt() else 0
            tvElapsed.text = formatTime(position)
            tvRemaining.text = "-${formatTime(duration - position)}"
        }
    }

    private fun animateArtColor(target: Int) {
        colorAnimator?.cancel()
        colorAnimator = ValueAnimator.ofArgb(currentArtColor, target).apply {
            duration = 450
            addUpdateListener { applyArtColor(it.animatedValue as Int) }
            start()
        }
    }

    private fun applyArtColor(color: Int) {
        currentArtColor = color
        val base = ContextCompat.getColor(this, R.color.bg_base)
        miniCard.backgroundTintList = ColorStateList.valueOf(ColorUtils.blendARGB(color, Color.BLACK, 0.15f))
        fullBackground.colors = intArrayOf(color, ColorUtils.blendARGB(color, base, 0.55f), base)
    }

    // =====================================================================
    // Likes
    // =====================================================================

    private fun refreshLikeState(song: ApiSong) {
        lifecycleScope.launch {
            val liked = withContext(Dispatchers.IO) { db.songDao().isLiked(song.key) }
            if (currentSong?.key == song.key) {
                isCurrentLiked = liked
                updateLikeUI()
            }
        }
    }

    private fun updateLikeUI() {
        val icon = if (isCurrentLiked) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline
        val tint = ColorStateList.valueOf(
            if (isCurrentLiked) ContextCompat.getColor(this, R.color.spotify_green) else Color.WHITE
        )
        listOf(btnLike, miniLike).forEach {
            it.setImageResource(icon)
            it.imageTintList = tint
        }
    }

    fun toggleLike(song: ApiSong) {
        lifecycleScope.launch {
            val nowLiked = withContext(Dispatchers.IO) {
                val dao = db.songDao()
                if (dao.isLiked(song.key)) {
                    dao.deleteById(song.key)
                    false
                } else {
                    dao.insertSong(song.toLikedSong())
                    true
                }
            }
            toast(if (nowLiked) "Added to Liked Songs" else "Removed from Liked Songs")
            MusicState.notifyLibraryChanged()
        }
    }

    // =====================================================================
    // Downloads
    // =====================================================================

    private fun updateDownloadButton() {
        val song = currentSong ?: return
        val green = ContextCompat.getColor(this, R.color.spotify_green)
        when {
            DownloadStore.isDownloaded(song) -> {
                btnDownload.setImageResource(R.drawable.ic_downloaded)
                btnDownload.imageTintList = ColorStateList.valueOf(green)
                btnDownload.alpha = 1f
            }
            DownloadStore.isDownloading(this, song) -> {
                btnDownload.setImageResource(R.drawable.ic_download_circle)
                btnDownload.imageTintList = ColorStateList.valueOf(green)
                btnDownload.alpha = 0.6f
            }
            else -> {
                btnDownload.setImageResource(R.drawable.ic_download_circle)
                btnDownload.imageTintList = ColorStateList.valueOf(Color.WHITE)
                btnDownload.alpha = if (song.media_url?.startsWith("http") == true) 1f else 0.4f
            }
        }
    }

    fun downloadSong(song: ApiSong) {
        if (DownloadStore.isDownloaded(song)) {
            toast("Already downloaded")
            return
        }
        if (song.media_url?.startsWith("http") != true) {
            toast("This song can't be downloaded")
            return
        }
        lifecycleScope.launch {
            val started = withContext(Dispatchers.IO) {
                try {
                    DownloadStore.enqueue(applicationContext, song)
                } catch (e: Exception) {
                    Log.e(TAG, "Download failed", e)
                    false
                }
            }
            toast(if (started) "Downloading ${song.displayTitle}…" else "Download failed")
            updateDownloadButton()
        }
    }

    fun confirmRemoveDownload(song: ApiSong) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Remove from downloads?")
            .setMessage("You won't be able to play \"${song.displayTitle}\" offline.")
            .setPositiveButton("Remove") { _, _ -> removeDownload(song) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun removeDownload(song: ApiSong) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { DownloadStore.delete(applicationContext, song) }
            toast("Removed from downloads")
            MusicState.notifyLibraryChanged()
            updateDownloadButton()
        }
    }

    // =====================================================================
    // Playlists
    // =====================================================================

    fun promptPlaylistName(
        title: String,
        initial: String = "",
        positive: String = "Create",
        onName: (String) -> Unit
    ) {
        val view = layoutInflater.inflate(R.layout.dialog_playlist_name, null)
        val inputLayout = view.findViewById<TextInputLayout>(R.id.til_playlist_name)
        val input = view.findViewById<TextInputEditText>(R.id.et_playlist_name)
        input.setText(initial)
        input.setSelection(initial.length)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(view)
            .setPositiveButton(positive, null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    inputLayout.error = "Give your playlist a name"
                } else {
                    dialog.dismiss()
                    onName(name)
                }
            }
            input.requestFocus()
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
    }

    fun createPlaylist(name: String, songToAdd: ApiSong? = null, openAfter: Boolean = false) {
        lifecycleScope.launch {
            val playlistId = withContext(Dispatchers.IO) {
                val dao = db.playlistDao()
                val id = dao.insertPlaylist(Playlist(name = name)).toInt()
                songToAdd?.let { dao.insertSongToPlaylist(it.toPlaylistSong(id)) }
                id
            }
            MusicState.notifyLibraryChanged()
            toast(if (songToAdd != null) "Added to $name" else "Created $name")
            if (openAfter) openPage(CollectionFragment.playlist(playlistId, name))
        }
    }

    fun showCreatePlaylist() {
        promptPlaylistName("Give your playlist a name") { name -> createPlaylist(name, openAfter = true) }
    }

    fun showAddToPlaylist(song: ApiSong) {
        lifecycleScope.launch {
            val playlists = withContext(Dispatchers.IO) {
                val dao = db.playlistDao()
                dao.getAllPlaylists().reversed().map { it to dao.getSongsInPlaylist(it.playlistId) }
            }
            val dialog = BottomSheetDialog(this@MainActivity)
            val view = layoutInflater.inflate(R.layout.layout_add_to_playlist, null)
            dialog.setContentView(view)

            view.findViewById<View>(R.id.btn_new_playlist).setOnClickListener {
                dialog.dismiss()
                promptPlaylistName("Give your playlist a name") { name -> createPlaylist(name, songToAdd = song) }
            }

            val rows = view.findViewById<LinearLayout>(R.id.playlist_rows)
            playlists.forEach { (playlist, songs) ->
                val row = layoutInflater.inflate(R.layout.item_library_row, rows, false)
                val alreadyAdded = songs.any { it.songId == song.key }
                bindLibraryRow(
                    row,
                    LibraryEntry.PlaylistItem(playlist, songs.size, songs.firstOrNull()?.imageUrl),
                    subtitleOverride = if (alreadyAdded) "Already added" else null
                )
                row.setOnClickListener {
                    dialog.dismiss()
                    addSongToPlaylist(song, playlist)
                }
                rows.addView(row)
            }
            dialog.show()
        }
    }

    private fun addSongToPlaylist(song: ApiSong, playlist: Playlist) {
        lifecycleScope.launch {
            val added = withContext(Dispatchers.IO) {
                val dao = db.playlistDao()
                if (dao.isSongInPlaylist(playlist.playlistId, song.key)) {
                    false
                } else {
                    dao.insertSongToPlaylist(song.toPlaylistSong(playlist.playlistId))
                    true
                }
            }
            toast(if (added) "Added to ${playlist.name}" else "Already in ${playlist.name}")
            if (added) MusicState.notifyLibraryChanged()
        }
    }

    private fun removeFromPlaylist(song: ApiSong, playlistId: Int) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { db.playlistDao().removeSongFromPlaylist(playlistId, song.key) }
            toast("Removed from playlist")
            MusicState.notifyLibraryChanged()
        }
    }

    fun showPlaylistOptions(playlistId: Int, name: String) {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_song_options, null)
        dialog.setContentView(view)
        view.findViewById<TextView>(R.id.tv_option_title).text = name
        view.findViewById<TextView>(R.id.tv_option_artist).text = "Playlist"
        view.findViewById<ImageView>(R.id.img_option_art).setImageResource(R.drawable.bg_playlist_cover)

        val container = view.findViewById<LinearLayout>(R.id.options_container)
        addSheetOption(container, dialog, R.drawable.ic_add, "Add songs") { goToTab(R.id.nav_search) }
        addSheetOption(container, dialog, R.drawable.ic_edit, "Rename playlist") {
            promptPlaylistName("Rename playlist", name, "Save") { newName ->
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) { db.playlistDao().renamePlaylist(playlistId, newName) }
                    MusicState.notifyLibraryChanged()
                }
            }
        }
        addSheetOption(container, dialog, R.drawable.ic_delete, "Delete playlist", R.color.danger) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Delete playlist?")
                .setMessage("\"$name\" will be deleted from Your Library.")
                .setPositiveButton("Delete") { _, _ ->
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) {
                            db.playlistDao().clearPlaylist(playlistId)
                            db.playlistDao().deletePlaylist(playlistId)
                        }
                        toast("Playlist deleted")
                        MusicState.notifyLibraryChanged()
                        closePage()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        dialog.show()
    }

    // =====================================================================
    // Song "⋮" menu
    // =====================================================================

    private fun addSheetOption(
        container: LinearLayout,
        dialog: BottomSheetDialog,
        icon: Int,
        label: String,
        tint: Int = R.color.text_secondary,
        action: () -> Unit
    ) {
        val row = layoutInflater.inflate(R.layout.item_sheet_option, container, false)
        row.findViewById<ImageView>(R.id.img_option_icon).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, tint))
        }
        row.findViewById<TextView>(R.id.tv_option_label).text = label
        row.setOnClickListener {
            dialog.dismiss()
            action()
        }
        container.addView(row)
    }

    /**
     * Shows the Spotify-style song menu. [playlistId] adds "Remove from this
     * playlist"; [fromPlayer] tailors it for the now-playing screen.
     */
    fun showSongOptions(song: ApiSong, playlistId: Int? = null, fromPlayer: Boolean = false) {
        lifecycleScope.launch {
            val liked = withContext(Dispatchers.IO) { db.songDao().isLiked(song.key) }

            val dialog = BottomSheetDialog(this@MainActivity)
            val view = layoutInflater.inflate(R.layout.layout_song_options, null)
            dialog.setContentView(view)
            view.findViewById<TextView>(R.id.tv_option_title).text = song.displayTitle
            view.findViewById<TextView>(R.id.tv_option_artist).text = song.displayArtist
            view.findViewById<ImageView>(R.id.img_option_art).loadArt(song.image)

            val container = view.findViewById<LinearLayout>(R.id.options_container)
            if (liked) {
                addSheetOption(container, dialog, R.drawable.ic_heart_filled, "Remove from Liked Songs", R.color.spotify_green) {
                    toggleLike(song)
                }
            } else {
                addSheetOption(container, dialog, R.drawable.ic_heart_outline, "Add to Liked Songs") { toggleLike(song) }
            }
            addSheetOption(container, dialog, R.drawable.ic_playlist_add, "Add to playlist") { showAddToPlaylist(song) }
            if (playlistId != null) {
                addSheetOption(container, dialog, R.drawable.ic_remove_circle, "Remove from this playlist") {
                    removeFromPlaylist(song, playlistId)
                }
            }
            if (!fromPlayer) {
                addSheetOption(container, dialog, R.drawable.ic_play_next, "Play next") { playNext(song) }
                addSheetOption(container, dialog, R.drawable.ic_add_to_queue, "Add to queue") { addToQueue(song) }
            } else {
                addSheetOption(container, dialog, R.drawable.ic_queue, "Go to queue") { showQueueSheet() }
            }
            when {
                DownloadStore.isDownloaded(song) ->
                    addSheetOption(container, dialog, R.drawable.ic_downloaded, "Remove download", R.color.spotify_green) {
                        confirmRemoveDownload(song)
                    }
                song.media_url?.startsWith("http") == true ->
                    addSheetOption(container, dialog, R.drawable.ic_download_circle, "Download") { downloadSong(song) }
            }
            song.primaryArtist?.let { artist ->
                addSheetOption(container, dialog, R.drawable.ic_person, "Go to artist") {
                    openPage(CollectionFragment.artist(artist))
                }
            }
            addSheetOption(container, dialog, R.drawable.ic_share, "Share") { shareSong(song) }
            dialog.show()
        }
    }

    fun shareSong(song: ApiSong) {
        val link = song.perma_url ?: song.media_url?.takeIf { it.startsWith("http") }
        val text = buildString {
            append("${song.displayTitle} by ${song.displayArtist} — listen on Jarvis Music")
            if (link != null) append("\n\n$link")
        }
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(intent, "Share song"))
    }

    // =====================================================================
    // Queue sheet: tap to jump, drag to reorder, swipe to remove
    // =====================================================================

    fun showQueueSheet() {
        val p = player ?: return
        if (p.mediaItemCount == 0) return

        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.layout_queue_bottom_sheet, null)
        dialog.setContentView(
            view,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (resources.displayMetrics.heightPixels * 0.9).toInt())
        )
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true

        val nowArt = view.findViewById<ImageView>(R.id.img_queue_now)
        val nowTitle = view.findViewById<TextView>(R.id.tv_queue_now_title)
        val nowArtist = view.findViewById<TextView>(R.id.tv_queue_now_artist)
        val nextLabel = view.findViewById<TextView>(R.id.tv_queue_next_label)
        val emptyText = view.findViewById<TextView>(R.id.tv_queue_empty)
        val shuffleButton = view.findViewById<TextView>(R.id.btn_queue_shuffle)
        val repeatButton = view.findViewById<TextView>(R.id.btn_queue_repeat)
        val rv = view.findViewById<RecyclerView>(R.id.rv_queue_list)
        rv.layoutManager = LinearLayoutManager(this)

        lateinit var touchHelper: ItemTouchHelper
        val adapter = QueueAdapter(
            onSongClick = { entry ->
                withPlayer {
                    it.seekTo(entry.playerIndex, 0L)
                    it.play()
                }
            },
            onRemoveClick = { entry -> withPlayer { it.removeMediaItem(entry.playerIndex) } },
            onStartDrag = { holder -> touchHelper.startDrag(holder) }
        )
        rv.adapter = adapter

        var dragFrom = -1
        var dragTo = -1
        touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                if (dragFrom == -1) dragFrom = from
                dragTo = to
                adapter.moveItem(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                if (position == RecyclerView.NO_POSITION) return
                val entry = adapter.entries[position]
                withPlayer { it.removeMediaItem(entry.playerIndex) }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                if (dragFrom != -1 && dragTo != -1 && dragFrom != dragTo) {
                    val from = dragFrom
                    val to = dragTo
                    withPlayer {
                        val base = it.currentMediaItemIndex + 1
                        it.moveMediaItem(base + from, base + to)
                    }
                }
                dragFrom = -1
                dragTo = -1
            }
        })
        touchHelper.attachToRecyclerView(rv)

        fun refresh() {
            val controller = player ?: return
            val now = controller.currentMediaItem?.toApiSong()
            if (now == null) {
                dialog.dismiss()
                return
            }
            nowTitle.text = now.displayTitle
            nowArtist.text = now.displayArtist
            nowArt.loadArt(now.image)
            nextLabel.text = "Next from: ${MusicState.sourceName}"

            val current = controller.currentMediaItemIndex
            val entries = (current + 1 until controller.mediaItemCount).map {
                QueueEntry(it, controller.getMediaItemAt(it).toApiSong())
            }
            adapter.submit(entries, canDrag = true)
            emptyText.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE

            val green = ContextCompat.getColor(this, R.color.spotify_green)
            val white = Color.WHITE
            val shuffleColor = if (MusicState.shuffleOn.value) green else white
            shuffleButton.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_shuffle, 0, 0, 0)
            TextViewCompat.setCompoundDrawableTintList(shuffleButton, ColorStateList.valueOf(shuffleColor))
            shuffleButton.setTextColor(shuffleColor)

            val (repeatIcon, repeatText, repeatColor) = when (controller.repeatMode) {
                Player.REPEAT_MODE_ALL -> Triple(R.drawable.ic_repeat_all, "Repeat all", green)
                Player.REPEAT_MODE_ONE -> Triple(R.drawable.ic_repeat_one, "Repeat one", green)
                else -> Triple(R.drawable.ic_repeat_all, "Repeat", white)
            }
            repeatButton.text = repeatText
            repeatButton.setCompoundDrawablesRelativeWithIntrinsicBounds(repeatIcon, 0, 0, 0)
            TextViewCompat.setCompoundDrawableTintList(repeatButton, ColorStateList.valueOf(repeatColor))
            repeatButton.setTextColor(repeatColor)
        }

        shuffleButton.setOnClickListener { toggleShuffle() }
        repeatButton.setOnClickListener { cycleRepeat() }

        val queueListener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(
                        Player.EVENT_TIMELINE_CHANGED,
                        Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_REPEAT_MODE_CHANGED
                    )
                ) {
                    refresh()
                }
            }
        }
        p.addListener(queueListener)
        dialog.setOnDismissListener {
            p.removeListener(queueListener)
            if (queueDialog === dialog) queueDialog = null
        }

        refresh()
        queueDialog = dialog
        dialog.show()
    }

    // =====================================================================

    fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
