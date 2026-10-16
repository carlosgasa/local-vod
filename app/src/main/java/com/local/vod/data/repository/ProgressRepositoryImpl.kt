package com.local.vod.data.repository

import com.local.vod.data.local.ProgressDao
import com.local.vod.data.local.ProgressEntity
import com.local.vod.domain.model.Episode
import com.local.vod.domain.model.PlaybackProgress
import com.local.vod.domain.repository.ProfileRepository
import com.local.vod.data.remote.VodApi
import com.local.vod.domain.repository.ProgressRepository
import com.local.vod.domain.repository.ServerRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * El servidor manda: es lo que hace que empieces un capítulo en el móvil y lo
 * sigas en la tele. Room queda como caché para pintar al instante y para que
 * la app no se quede muda si el servidor no contesta.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class ProgressRepositoryImpl @Inject constructor(
    private val dao: ProgressDao,
    private val api: VodApi,
    private val profiles: ProfileRepository,
    private val server: ServerRepository,
) : ProgressRepository {

    override suspend fun save(episode: Episode, positionMs: Long, durationMs: Long) {
        // duration desconocida (C.TIME_UNSET) o posición absurda: no se guarda nada
        if (durationMs <= 0 || positionMs < 0) return
        val profile = profiles.current.first() ?: return
        val clamped = positionMs.coerceAtMost(durationMs)
        dao.upsert(
            ProgressEntity(
                profileId = profile.id,
                episodeId = episode.id,
                titleId = episode.titleId,
                positionMs = clamped,
                durationMs = durationMs,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        // Si el servidor no está, se queda en la caché y se reenvía en el siguiente guardado.
        runCatching {
            api.saveProgress(base(), profile.id, episode.id, episode.titleId, clamped, durationMs)
        }
    }

    override suspend fun clear(episodeId: String) {
        val profile = profiles.current.first() ?: return
        dao.delete(profile.id, episodeId)
        // En el servidor "empezar de nuevo" es simplemente volver a cero.
        runCatching { api.saveProgress(base(), profile.id, episodeId, "", 0, 1) }
    }

    override fun forTitle(titleId: String): Flow<Map<String, PlaybackProgress>> =
        profiles.current.flatMapLatest { profile ->
            if (profile == null) flowOf(emptyMap())
            else dao.byTitle(profile.id, titleId).map { rows -> rows.associate { it.episodeId to it.toDomain() } }
        }

    override fun unfinished(): Flow<List<PlaybackProgress>> =
        profiles.current.flatMapLatest { profile ->
            if (profile == null) flowOf(emptyList())
            else dao.unfinished(profile.id).map { rows -> rows.map { it.toDomain() } }
        }

    override suspend fun sync() {
        val profile = profiles.current.first() ?: return
        val rows = api.progress(base(), profile.id)
        dao.clearProfile(profile.id)
        dao.upsertAll(
            rows.map {
                ProgressEntity(
                    profileId = profile.id,
                    episodeId = it.episodeId,
                    titleId = it.titleId,
                    positionMs = it.positionMs,
                    durationMs = it.durationMs,
                    updatedAt = it.updatedAt,
                )
            },
        )
    }

    private suspend fun base(): String =
        server.baseUrl.first() ?: throw IllegalStateException("Servidor sin configurar")
}

private fun ProgressEntity.toDomain() =
    PlaybackProgress(episodeId, titleId, positionMs, durationMs, updatedAt)
