package com.local.vod.domain.usecase

import com.local.vod.domain.model.ContinueItem
import com.local.vod.domain.model.Episode
import com.local.vod.domain.model.Playlist
import com.local.vod.domain.model.PlaybackProgress
import com.local.vod.domain.model.Title
import com.local.vod.domain.repository.LibraryRepository
import com.local.vod.domain.repository.ProgressRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class GetLibrary @Inject constructor(private val library: LibraryRepository) {
    suspend operator fun invoke(refresh: Boolean = false): List<Title> =
        if (refresh) library.refresh() else library.titles()
}

class GetTitle @Inject constructor(private val library: LibraryRepository) {
    suspend operator fun invoke(id: String): Title? = library.title(id)
}

/** El episodio pedido y los que le siguen, para que el reproductor tenga "siguiente episodio". */
class GetPlaylist @Inject constructor(private val library: LibraryRepository) {
    suspend operator fun invoke(episodeId: String): Playlist? {
        val title = library.titles().firstOrNull { t -> t.episodes.any { it.id == episodeId } } ?: return null
        val episodes = title.episodes // ya vienen ordenados por temporada y número
        val index = episodes.indexOfFirst { it.id == episodeId }
        return if (index < 0) null else Playlist(episodes, index)
    }
}

class GetContinueWatching @Inject constructor(
    private val library: LibraryRepository,
    private val progress: ProgressRepository,
) {
    operator fun invoke(): Flow<List<ContinueItem>> = progress.unfinished().map { rows ->
        val titles = library.titles()
        rows.mapNotNull { row ->
            val title = titles.firstOrNull { it.id == row.titleId } ?: return@mapNotNull null
            val episode = title.episodes.firstOrNull { it.id == row.episodeId } ?: return@mapNotNull null
            ContinueItem(title, episode, row)
        }
    }
}

class GetTitleProgress @Inject constructor(private val progress: ProgressRepository) {
    operator fun invoke(titleId: String): Flow<Map<String, PlaybackProgress>> = progress.forTitle(titleId)
}

class SaveProgress @Inject constructor(private val progress: ProgressRepository) {
    suspend operator fun invoke(episode: Episode, positionMs: Long, durationMs: Long) =
        progress.save(episode, positionMs, durationMs)
}

class ClearProgress @Inject constructor(private val progress: ProgressRepository) {
    suspend operator fun invoke(episodeId: String) = progress.clear(episodeId)
}
