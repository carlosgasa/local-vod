package com.local.vod.domain.repository

import com.local.vod.domain.model.Episode
import com.local.vod.domain.model.PlaybackProgress
import com.local.vod.domain.model.Title
import kotlinx.coroutines.flow.Flow

interface ServerRepository {
    val baseUrl: Flow<String?>
    suspend fun save(baseUrl: String)
    /** true si en esa dirección responde el servidor de medios. */
    suspend fun probe(baseUrl: String): Boolean
}

interface LibraryRepository {
    /** Lo último cargado, o lo pide al servidor si aún no hay nada. */
    suspend fun titles(): List<Title>
    suspend fun refresh(): List<Title>
    suspend fun title(id: String): Title?
}

interface ProgressRepository {
    suspend fun save(episode: Episode, positionMs: Long, durationMs: Long)
    suspend fun get(episodeId: String): PlaybackProgress?
    suspend fun clear(episodeId: String)
    fun forTitle(titleId: String): Flow<Map<String, PlaybackProgress>>
    /** Episodios empezados y no terminados, el más reciente primero. */
    fun unfinished(): Flow<List<PlaybackProgress>>
}
