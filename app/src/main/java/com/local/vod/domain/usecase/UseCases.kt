package com.local.vod.domain.usecase

import com.local.vod.domain.model.ContinueItem
import com.local.vod.domain.model.Episode
import com.local.vod.domain.model.Playlist
import com.local.vod.domain.model.Profile
import com.local.vod.domain.model.ServerStatus
import com.local.vod.domain.model.PlaybackProgress
import com.local.vod.domain.model.Title
import com.local.vod.domain.repository.LibraryRepository
import com.local.vod.domain.repository.ProfileRepository
import com.local.vod.domain.repository.ServerRepository
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
        // Si todavía no hay servidor, esta vuelta sale vacía pero el flujo sigue vivo:
        // al sincronizar, Room emite otra vez y entonces sí se puede cruzar con la biblioteca.
        val titles = runCatching { library.titles() }.getOrElse { return@map emptyList() }
        rows.mapNotNull { row ->
            val title = titles.firstOrNull { it.id == row.titleId } ?: return@mapNotNull null
            val episode = title.episodes.firstOrNull { it.id == row.episodeId } ?: return@mapNotNull null
            ContinueItem(title, episode, row)
        }
            // una tarjeta por serie o película: la del episodio más reciente
            .distinctBy { it.title.id }
            .take(CONTINUE_LIMIT)
    }

    private companion object {
        const val CONTINUE_LIMIT = 5
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


/** Trae del servidor lo que el perfil haya visto en cualquier otro aparato. */
class SyncProgress @Inject constructor(private val progress: ProgressRepository) {
    suspend operator fun invoke() = progress.sync()
}

class GetProfiles @Inject constructor(private val profiles: ProfileRepository) {
    suspend operator fun invoke(): List<Profile> = profiles.available()
}

class CreateProfile @Inject constructor(private val profiles: ProfileRepository) {
    suspend operator fun invoke(name: String): Profile? = profiles.create(name)
}

class SelectProfile @Inject constructor(private val profiles: ProfileRepository) {
    suspend operator fun invoke(profile: Profile) = profiles.select(profile)
}

/**
 * Por dónde seguir una serie: el episodio a medias más reciente y, si no hay
 * ninguno, el primero sin terminar. Devuelve también desde qué milisegundo.
 */
class NextToWatch @Inject constructor() {
    operator fun invoke(title: Title, progress: Map<String, PlaybackProgress>): Pair<Episode, Long>? {
        val episodes = title.episodes
        if (episodes.isEmpty()) return null

        val started = episodes
            .mapNotNull { episode -> progress[episode.id]?.let { episode to it } }
            .filter { (_, p) -> p.positionMs > 10_000 && p.fraction < 0.95f }
            .maxByOrNull { (_, p) -> p.updatedAt }
        if (started != null) return started.first to started.second.positionMs

        val pending = episodes.firstOrNull { episode ->
            val seen = progress[episode.id]
            seen == null || seen.fraction < 0.95f
        }
        return pending?.let { it to 0L }
    }
}


class GetServerStatus @Inject constructor(private val server: ServerRepository) {
    suspend operator fun invoke(): ServerStatus = server.status()
}

/** Para el latido: ¿sigue ahí el servidor? */
class IsServerAlive @Inject constructor(private val server: ServerRepository) {
    suspend operator fun invoke(): Boolean = server.isAlive()
}

/** Guarda el episodio completo usando la duración conocida por el reproductor o el servidor. */
class MarkWatched @Inject constructor(private val progress: ProgressRepository) {
    suspend operator fun invoke(episode: Episode, saved: PlaybackProgress?) {
        val durationMs = durationMs(episode, saved) ?: return
        progress.save(episode, durationMs, durationMs)
    }

    companion object {
        fun durationMs(episode: Episode, saved: PlaybackProgress?): Long? =
            saved?.durationMs?.takeIf { it > 0 }
                ?: episode.durationSeconds
                    ?.takeIf { it in 1..Long.MAX_VALUE / 1_000 }
                    ?.times(1_000)
    }
}
