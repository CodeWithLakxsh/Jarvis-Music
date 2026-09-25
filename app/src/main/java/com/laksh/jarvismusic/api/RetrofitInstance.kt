package com.laksh.jarvismusic.api

import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitInstance {
    // YOUR PRIVATE MUMBAI SERVER: Dedicated to Jarvis Music to prevent 429 rate limiting
    private const val BASE_URL = "https://jio-saavn-api-mauve.vercel.app/"

    // The Shield: Lenient Gson handles messy API responses without crashing
    private val gson = GsonBuilder()
        .setLenient()
        .create()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    val api: JioSaavnApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(JioSaavnApi::class.java)
    }
}
