package com.laksh.jarvismusic.api

import retrofit2.http.GET
import retrofit2.http.Query

interface JioSaavnApi {
    @GET("result/")
    suspend fun searchSongs(
        @Query("query") query: String,
        @Query("n") n: Int = 20
    ): List<ApiSong>
}