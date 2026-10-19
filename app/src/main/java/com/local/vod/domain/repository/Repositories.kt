package com.local.vod.domain.repository

import com.local.vod.domain.model.Episode
import com.local.vod.domain.model.PlaybackProgress
import com.local.vod.domain.model.Profile
import com.local.vod.domain.model.ServerStatus
import com.local.vod.domain.model.Title
import com.local.vod.domain.model.TitleList
import com.local.vod.domain.model.TitleLists
import com.local.vod.domain.model.PhotoAlbum
import com.local.vod.domain.model.MusicAlbum
import kotlinx.coroutines.flow.Flow

interface ServerRepository {
    val baseUrl: Flow<String?>
    val previewsEnabled: Flow<Boolean>
    suspend fun setPreviewsEnabled(enabled: Boolean)
    suspend fun save(baseUrl: String)
    /** true si en esa dirección responde el servidor de medios. */
    suspend fun probe(baseUrl: String): Boolean
    /** Busca el servidor por la red local cuando la dirección guardada ya no vale. */
    suspend fun discover(): String?
    /** Datos del servidor: espacio, biblioteca, batería del aparato que lo hospeda. */
    suspend fun status(): ServerStatus
    /** Un toque rápido para saber si sigue vivo. */
    suspend fun isAlive(): Boolean
}

interface LibraryRepository {
    /** Lo último cargado, o lo pide al servidor si aún no hay nada. */
    suspend fun titles(): List<Title>
    suspend fun refresh(): List<Title>
    suspend fun title(id: String): Title?
}

interface PhotoRepository {
    suspend fun albums(): List<PhotoAlbum>
}

interface MusicRepository {
    suspend fun albums(): List<MusicAlbum>
}

interface ProfileRepository {
    /** El perfil elegido en este aparato. */
    val current: Flow<Profile?>
    suspend fun available(): List<Profile>
    suspend fun create(name: String): Profile?
    suspend fun select(profile: Profile)
    suspend fun rename(profile: Profile, name: String)
    suspend fun delete(profile: Profile)
}

interface ListsRepository {
    /** "Ver después" y "Favoritos" del perfil actual. */
    val lists: Flow<TitleLists>
    suspend fun refresh()
    suspend fun set(list: TitleList, titleId: String, on: Boolean)
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
