package com.laksh.jarvismusic

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PlaylistDao {
    @Insert
    fun insertPlaylist(playlist: Playlist): Long

    @Query("SELECT * FROM playlists")
    fun getAllPlaylists(): List<Playlist>

    @Query("SELECT * FROM playlists WHERE playlistId = :id")
    fun getPlaylist(id: Int): Playlist?

    @Query("UPDATE playlists SET name = :name WHERE playlistId = :id")
    fun renamePlaylist(id: Int, name: String)

    @Query("DELETE FROM playlists WHERE playlistId = :id")
    fun deletePlaylist(id: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertSongToPlaylist(song: PlaylistSong)

    @Query("SELECT * FROM playlist_songs WHERE playlistId = :id")
    fun getSongsInPlaylist(id: Int): List<PlaylistSong>

    @Query("SELECT EXISTS(SELECT * FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId)")
    fun isSongInPlaylist(playlistId: Int, songId: String): Boolean

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    fun removeSongFromPlaylist(playlistId: Int, songId: String)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :id")
    fun clearPlaylist(id: Int)

    @Query("SELECT COUNT(*) FROM playlists")
    fun getPlaylistCount(): Int
}
