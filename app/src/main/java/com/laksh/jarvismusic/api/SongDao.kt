package com.laksh.jarvismusic

import androidx.room.*

@Dao
interface SongDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSong(song: LikedSong)

    @Delete
    suspend fun deleteSong(song: LikedSong)

    @Query("DELETE FROM liked_songs WHERE id = :songId")
    suspend fun deleteById(songId: String)

    @Query("SELECT * FROM liked_songs")
    suspend fun getAllLikedSongs(): List<LikedSong>

    // Most recently liked first, like Spotify's Liked Songs
    @Query("SELECT * FROM liked_songs ORDER BY rowid DESC")
    suspend fun getLikedSongsNewestFirst(): List<LikedSong>

    @Query("SELECT COUNT(*) FROM liked_songs")
    suspend fun getLikedCount(): Int

    @Query("SELECT EXISTS(SELECT * FROM liked_songs WHERE id = :songId)")
    suspend fun isLiked(songId: String): Boolean
}
