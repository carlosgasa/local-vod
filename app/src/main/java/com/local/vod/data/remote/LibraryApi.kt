package com.local.vod.data.remote

import com.local.vod.domain.model.Title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibraryApi @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
) {
    suspend fun fetch(baseUrl: String): List<Title> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("${baseUrl.trimEnd('/')}/api/library").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            json.decodeFromString<LibraryResponse>(response.body.string())
                .items.map { it.toDomain(baseUrl.trimEnd('/')) }
        }
    }
}
