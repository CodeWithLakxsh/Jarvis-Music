package com.laksh.jarvismusic

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope // Required for Coroutines
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.laksh.jarvismusic.api.RetrofitInstance
import kotlinx.coroutines.launch // Required for Coroutines

class HomeFragment : Fragment() {

    private lateinit var rvGrid: RecyclerView
    private lateinit var rvSquares: RecyclerView
    private lateinit var rvPlaylists: RecyclerView
    private lateinit var progressBar: ProgressBar

    private var activeRequests = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_home, container, false)
        rvGrid = view.findViewById(R.id.rv_home_grid)
        rvSquares = view.findViewById(R.id.rv_home_squares)
        rvPlaylists = view.findViewById(R.id.rv_home_playlists)
        progressBar = view.findViewById(R.id.home_progress_bar)

        rvGrid.layoutManager = GridLayoutManager(requireContext(), 2)
        rvSquares.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        rvPlaylists.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)

        progressBar.visibility = View.VISIBLE
        loadDashboardInParallel()
        return view
    }

    private fun loadDashboardInParallel() {
        val seeds = listOf("Hindi", "Punjabi", "Arijit", "HipHop", "Trending", "Pop").shuffled()
        activeRequests = 3

        fetchSection(seeds[0], rvGrid, isGrid = true)
        fetchSection(seeds[1], rvSquares, isGrid = false)
        fetchSection(seeds[2], rvPlaylists, isGrid = false)
    }

    private fun fetchSection(query: String, recyclerView: RecyclerView, isGrid: Boolean) {
        // 🔥 FIX: Use Coroutines instead of .enqueue()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Execute network call on background thread automatically
                val songs = RetrofitInstance.api.searchSongs(query, 20)

                if (isAdded && !songs.isNullOrEmpty()) {
                    val shuffledSongs = songs.shuffled()

                    recyclerView.adapter = if (isGrid) {
                        SongAdapter(shuffledSongs) { clickedSong ->
                            (activity as? MainActivity)?.setQueueAndPlay(clickedSong, shuffledSongs)
                        }
                    } else {
                        HomeRowAdapter(shuffledSongs) { clickedSong ->
                            (activity as? MainActivity)?.setQueueAndPlay(clickedSong, shuffledSongs)
                        }
                    }
                }
            } catch (e: Exception) {
                // Handle network failure here
                Log.e("API_DEBUG", "Network failure for '$query': ${e.message}")
            } finally {
                // Ensure the progress bar logic runs even if the request fails
                if (isAdded) {
                    decrementRequestCount()
                }
            }
        }
    }

    private fun decrementRequestCount() {
        activeRequests--
        if (activeRequests <= 0 && isAdded) {
            progressBar.visibility = View.GONE
        }
    }
}