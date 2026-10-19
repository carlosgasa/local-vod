package com.local.vod.data.remote

import android.content.ContentResolver
import android.net.Uri
import com.local.vod.domain.model.PlaybackProgress
import com.local.vod.domain.model.Battery
import com.local.vod.domain.model.Profile
import com.local.vod.domain.model.ServerStatus
import com.local.vod.domain.model.Title
import com.local.vod.domain.model.TitleLists
import com.local.vod.domain.model.PhotoAlbum
import com.local.vod.domain.model.MusicAlbum
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType
import okio.BufferedSink
import org.json.JSONObject
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

@Serializable
data class ListsResponse(val later: List<String> = emptyList(), val favorites: List<String> = emptyList())

@Serializable
private data class SetInListBody(val profile: String, val list: String, val titleId: String, val on: Boolean)

@Serializable
private data class ProfileBody(val id: String, val name: String = "")

@Serializable
data class StatusResponse(
    val host: HostDto = HostDto(),
    val network: NetworkDto = NetworkDto(),
    val storage: StorageDto = StorageDto(),
    val library: LibraryStatsDto = LibraryStatsDto(),
    val battery: BatteryDto? = null,
)

@Serializable
data class HostDto(
    val name: String = "",
    val system: String = "",
    val python: String = "",
    val uptimeSeconds: Long? = null,
    val temperature: Double? = null,
)

@Serializable
data class NetworkDto(val ip: String = "", val port: Int = 0)

@Serializable
data class StorageDto(
    val path: String = "",
    val totalBytes: Long = 0,
    val freeBytes: Long = 0,
    val libraryBytes: Long = 0,
)

@Serializable
data class LibraryStatsDto(
    val titles: Int = 0,
    val videos: Int = 0,
    val seconds: Long = 0,
    val pendingDurations: Int = 0,
)

@Serializable
data class BatteryDto(
    val percent: Int? = null,
    val charging: Boolean = false,
    val temperature: Double? = null,
)

@Singleton
class VodApi @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
) {
    suspend fun upload(
        baseUrl: String,
        resolver: ContentResolver,
        uri: Uri,
        path: String,
        size: Long,
        onProgress: (Int) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val body = object : RequestBody() {
            override fun contentType(): MediaType = "application/octet-stream".toMediaType()
            override fun contentLength(): Long = size
            override fun isOneShot(): Boolean = true

            override fun writeTo(sink: BufferedSink) {
                val input = resolver.openInputStream(uri) ?: throw IOException("No se pudo abrir el archivo")
                input.use {
                    val buffer = ByteArray(64 * 1024)
                    var sent = 0L
                    var lastPercent = -1
                    while (true) {
                        val count = it.read(buffer)
                        if (count < 0) break
                        sink.write(buffer, 0, count)
                        sent += count
                        val percent = (sent * 100 / size).toInt().coerceAtMost(100)
                        if (percent != lastPercent) {
                            onProgress(percent)
                            lastPercent = percent
                        }
                    }
                    if (sent != size) throw IOException("El tamaño del archivo cambió durante la subida")
                }
            }
        }
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/api/upload/" + path.split('/').joinToString("/") { Uri.encode(it) })
            .put(body)
            .build()
        // Los videos grandes pueden tardar mucho más que el límite normal de escritura.
        client.newBuilder().writeTimeout(0, java.util.concurrent.TimeUnit.SECONDS).build()
            .newCall(request).execute().use { response ->
                val payload = response.body.string()
                if (!response.isSuccessful) {
                    val message = runCatching { JSONObject(payload).optString("error") }.getOrNull()
                    throw IOException(message?.takeIf { it.isNotBlank() } ?: "HTTP ${response.code}")
                }
            }
    }
    suspend fun library(baseUrl: String): List<Title> = get(baseUrl, "/api/library") { body ->
        json.decodeFromString<LibraryResponse>(body).items.map { it.toDomain(baseUrl.trimEnd('/')) }
    }

    suspend fun photos(baseUrl: String): List<PhotoAlbum> = get(baseUrl, "/api/photos") { body ->
        json.decodeFromString<PhotosResponse>(body).albums.map { it.toDomain(baseUrl.trimEnd('/')) }
    }

    suspend fun music(baseUrl: String): List<MusicAlbum> = get(baseUrl, "/api/music") { body ->
        json.decodeFromString<MusicResponse>(body).albums.map { it.toDomain(baseUrl.trimEnd('/')) }
    }

    suspend fun profiles(baseUrl: String): List<Profile> = get(baseUrl, "/api/profiles") { body ->
        json.decodeFromString<ProfilesResponse>(body).profiles.map { Profile(it.id, it.name) }
    }

    suspend fun createProfile(baseUrl: String, name: String): Profile? =
        post(baseUrl, "/api/profiles", json.encodeToString(NameBody(name))) { body ->
            json.decodeFromString<ProfileDto>(body).takeIf { it.id.isNotEmpty() }?.let { Profile(it.id, it.name) }
        }

    suspend fun renameProfile(baseUrl: String, id: String, name: String) {
        post(baseUrl, "/api/profiles/rename", json.encodeToString(ProfileBody(id, name))) { }
    }

    suspend fun deleteProfile(baseUrl: String, id: String) {
        post(baseUrl, "/api/profiles/delete", json.encodeToString(ProfileBody(id))) { }
    }

    suspend fun lists(baseUrl: String, profileId: String): TitleLists =
        get(baseUrl, "/api/lists?profile=$profileId") { body ->
            json.decodeFromString<ListsResponse>(body).let { TitleLists(it.later, it.favorites) }
        }

    suspend fun setInList(baseUrl: String, profileId: String, list: String, titleId: String, on: Boolean): TitleLists =
        post(baseUrl, "/api/lists", json.encodeToString(SetInListBody(profileId, list, titleId, on))) { body ->
            json.decodeFromString<ListsResponse>(body).let { TitleLists(it.later, it.favorites) }
        }

    suspend fun status(baseUrl: String): ServerStatus = get(baseUrl, "/api/status") { body ->
        val dto = json.decodeFromString<StatusResponse>(body)
        ServerStatus(
            hostName = dto.host.name,
            system = dto.host.system,
            uptimeSeconds = dto.host.uptimeSeconds,
            cpuTemperature = dto.host.temperature,
            ip = dto.network.ip,
            port = dto.network.port,
            totalBytes = dto.storage.totalBytes,
            freeBytes = dto.storage.freeBytes,
            libraryBytes = dto.storage.libraryBytes,
            titles = dto.library.titles,
            videos = dto.library.videos,
            librarySeconds = dto.library.seconds,
            pendingDurations = dto.library.pendingDurations,
            battery = dto.battery?.let { Battery(it.percent, it.charging, it.temperature) },
        )
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
