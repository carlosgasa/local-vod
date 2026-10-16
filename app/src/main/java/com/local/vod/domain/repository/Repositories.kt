package com.local.vod.domain.repository

import com.local.vod.domain.model.Episode
import com.local.vod.domain.model.PlaybackProgress
import com.local.vod.domain.model.Profile
import com.local.vod.domain.model.Title
import kotlinx.coroutines.flow.Flow

interface ServerRepository {
    val baseUrl: Flow<String?>
    suspend fun save(baseUrl: String)
    /** true si en esa dirección responde el servidor de medios. */
    suspend fun probe(baseUrl: String): Boolean
    /** Busca el servidor por la red local cuando la dirección guardada ya no vale. */
    suspend fun discover(): String?
}

interface LibraryRepository {
    /** Lo último cargado, o lo pide al servidor si aún no hay nada. */
    suspend fun titles(): List<Title>
    suspend fun refresh(): List<Title>
    suspend fun title(id: String): Title?
}

interface ProfileRepository {
    /** El perfil elegido en este aparato. */
    val current: Flow<Profile?>
    suspend fun available(): List<Profile>
    suspend fun create(name: String): Profile?
    suspend fun select(profile: Profile)
}

interface ProgressRepository {
    suspend fun save(episode: Episode, positionMs: Long, durationMs: Long)
    suspend fun clear(episodeId: String)
    fun forTitle(titleId: String): Flow<Map<String, PlaybackProgress>>
    /** Episodios empezados y no terminados, el más reciente primero. */
    fun unfinished(): Flow<List<PlaybackProgress>>
    /** Trae del servidor lo visto por el perfil actual (en otras pantallas). */
    suspend fun sync()
}
