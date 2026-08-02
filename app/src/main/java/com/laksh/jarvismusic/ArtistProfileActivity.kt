package com.laksh.jarvismusic

import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope // Required for Coroutines
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.laksh.jarvismusic.api.RetrofitInstance
import kotlinx.coroutines.launch // Required for Coroutines

class ArtistProfileActivity : AppCompatActivity() {

    private lateinit var rvSongs: RecyclerView
    private lateinit var tvName: TextView
    private lateinit var imgHeader: ImageView
    private lateinit var fabPlay: FloatingActionButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_artist_profile)

        rvSongs = findViewById(R.id.rv_artist_songs)
        tvName = findViewById(R.id.tv_profile_name)
        imgHeader = findViewById(R.id.img_profile_header)
        fabPlay = findViewById(R.id.fab_play_all)

        rvSongs.layoutManager = LinearLayoutManager(this)

        val artistName = intent.getStringExtra("ARTIST_NAME") ?: "Unknown Artist"
        tvName.text = artistName

        fetchArtistDataAutomatically(artistName)
    }

    private fun fetchArtistDataAutomatically(artistName: String) {
        // 🔥 Use lifecycleScope.launch to call a suspend function
        lifecycleScope.launch {
            try {
                // Retrofit now returns the list directly, no .enqueue needed
                val songs = RetrofitInstance.api.searchSongs(artistName, 20)

                if (!songs.isNullOrEmpty()) {
                    val autoFetchedImageUrl = songs[0].image

                    Glide.with(this@ArtistProfileActivity)
                        .load(autoFetchedImageUrl)
                        .centerCrop()
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .into(imgHeader)

                    rvSongs.adapter = SongAdapter(songs) { clickedSong ->
                        // NOTE: Ensure your MainActivity has this method
                        (this@ArtistProfileActivity as? MainActivity)?.setQueueAndPlay(clickedSong, songs)
                    }

                    fabPlay.setOnClickListener {
                        Toast.makeText(this@ArtistProfileActivity, "Playing all songs by $artistName!", Toast.LENGTH_SHORT).show()
                        (this@ArtistProfileActivity as? MainActivity)?.setQueueAndPlay(songs[0], songs)
                    }
                } else {
                    Toast.makeText(this@ArtistProfileActivity, "No songs found for $artistName", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                // This catches network errors, parsing errors, etc.
                Toast.makeText(this@ArtistProfileActivity, "Error: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}