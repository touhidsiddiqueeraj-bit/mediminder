package com.mediminder.app.di

import com.mediminder.app.data.remote.RxNormApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/** Only used for the optional RxNorm name check; short timeouts, never blocking. */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun provideRxNormApi(json: Json): RxNormApi {
        val contentType = "application/json".toMediaType()
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl("https://rxnav.nlm.nih.gov/REST/")
            .client(client)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
            .create(RxNormApi::class.java)
    }
}
