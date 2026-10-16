package com.local.vod.data.remote

import com.local.vod.domain.model.PlaybackProgress
import com.local.vod.domain.model.Profile
import com.local.vod.domain.model.Title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class ProfilesResponse(val profiles: List<ProfileDto> = emptyList())

@Serializable
data class ProfileDto(val id: String = "", val name: String = "", val error: String? = null)

@Serializable
data class ProgressResponse(val items: List<ProgressDto> = emptyList())

@Serializable
data class ProgressDto(
    val episodeId: String,
    val titleId: String = "",
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val updatedAt: Long = 0,
)

@Serializable
private data class SaveProgressBody(
    val profile: String,
    val episodeId: String,
    val titleId: String,
    val positionMs: Long,
    val durationMs: Long,
)

@Serializable
private data class NameBody(val name: String)

@Singleton
class VodApi @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
) {
    suspend fun library(baseUrl: String): List<Title> = get(baseUrl, "/api/library") { body ->
        json.decodeFromString<LibraryResponse>(body).items.map { it.toDomain(baseUrl.trimEnd('/')) }
    }

    suspend fun profiles(baseUrl: String): List<Profile> = get(baseUrl, "/api/profiles") { body ->
        json.decodeFromString<ProfilesResponse>(body).profiles.map { Profile(it.id, it.name) }
    }

    suspend fun createProfile(baseUrl: String, name: String): Profile? =
        post(baseUrl, "/api/profiles", json.encodeToString(NameBody(name))) { body ->
            json.decodeFromString<ProfileDto>(body).takeIf { it.id.isNotEmpty() }?.let { Profile(it.id, it.name) }
        }

    suspend fun progress(baseUrl: String, profileId: String): List<PlaybackProgress> =
        get(baseUrl, "/api/progress?profile=$profileId") { body ->
            json.decodeFromString<ProgressResponse>(body).items.map {
                PlaybackProgress(it.episodeId, it.titleId, it.positionMs, it.durationMs, it.updatedAt)
            }
        }

    suspend fun saveProgress(
        baseUrl: String,
        profileId: String,
        episodeId: String,
        titleId: String,
        positionMs: Long,
        durationMs: Long,
    ) {
        val payload = json.encodeToString(
            SaveProgressBody(profileId, episodeId, titleId, positionMs, durationMs),
        )
        post(baseUrl, "/api/progress", payload) { }
    }

    private suspend fun <T> get(baseUrl: String, path: String, parse: (String) -> T): T =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(baseUrl.trimEnd('/') + path).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                parse(response.body.string())
            }
        }

    private suspend fun <T> post(baseUrl: String, path: String, payload: String, parse: (String) -> T): T =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + path)
                .post(payload.toRequestBody(JSON_TYPE))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                parse(response.body.string())
            }
        }

    private companion object {
        val JSON_TYPE = "application/json".toMediaType()
    }
}
