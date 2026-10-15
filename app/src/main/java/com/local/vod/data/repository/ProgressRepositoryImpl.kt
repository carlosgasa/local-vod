package com.local.vod.data.repository

import com.local.vod.data.local.ProgressDao
import com.local.vod.data.local.ProgressEntity
import com.local.vod.domain.model.Episode
import com.local.vod.domain.model.PlaybackProgress
import com.local.vod.domain.repository.ProgressRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProgressRepositoryImpl @Inject constructor(
    private val dao: ProgressDao,
) : ProgressRepository {

    override suspend fun save(episode: Episode, positionMs: Long, durationMs: Long) {
        // duration desconocida (C.TIME_UNSET) o posición absurda: no se guarda nada
        if (durationMs <= 0 || positionMs < 0) return
        dao.upsert(
            ProgressEntity(
                episodeId = episode.id,
                titleId = episode.titleId,
                positionMs = positionMs.coerceAtMost(durationMs),
                durationMs = durationMs,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun get(episodeId: String): PlaybackProgress? = dao.byEpisode(episodeId)?.toDomain()

    override suspend fun clear(episodeId: String) = dao.delete(episodeId)

    override fun forTitle(titleId: String): Flow<Map<String, PlaybackProgress>> =
        dao.byTitle(titleId).map { rows -> rows.associate { it.episodeId to it.toDomain() } }

    override fun unfinished(): Flow<List<PlaybackProgress>> =
        dao.unfinished().map { rows -> rows.map { it.toDomain() } }
}

private fun ProgressEntity.toDomain() = PlaybackProgress(episodeId, titleId, positionMs, durationMs)
