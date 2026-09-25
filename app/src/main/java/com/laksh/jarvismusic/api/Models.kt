package com.laksh.jarvismusic.api

import com.google.gson.annotations.SerializedName

// The API returns a direct List [{}, {}, {}] of songs.
data class ApiSong(
    @SerializedName("id")
    val id: String?,

    @SerializedName("song")
    val song: String?,

    @SerializedName("singers")
    val singers: String?,

    @SerializedName("image")
    val image: String?,

    @SerializedName("media_url")
    val media_url: String?,

    @SerializedName("album")
    val album: String?,

    @SerializedName("perma_url")
    val perma_url: String? = null,

    @SerializedName("duration")
    val duration: String? = null
)
