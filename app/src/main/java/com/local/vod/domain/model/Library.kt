package com.local.vod.domain.model

data class Title(
    val id: String,
    val name: String,
    val isMovie: Boolean,
    val posterUrl: String?,
    val seasons: List<Season>,
) {
    val episodes: List<Episode> get() = seasons.flatMap { it.episodes }
}

data class Season(
    val number: Int,
    val episodes: List<Episode>,
)

data class Episode(
    val id: String,
    val titleId: String,
    val name: String,
    val season: Int,
    val number: Int?,
    val url: String,
    val subs: List<Subtitle>,
)

data class Subtitle(
    val lang: String,
    val url: String,
)

data class PlaybackProgress(
    val episodeId: String,
    val titleId: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long = 0L,
) {
    val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/** Lo que se le pasa al reproductor: los episodios del título y por cuál empezar. */
data class Playlist(
    val episodes: List<Episode>,
    val startIndex: Int,
)

/** Una fila de "Seguir viendo": el título, el episodio a medias y dónde se quedó. */
data class ContinueItem(
    val title: Title,
    val episode: Episode,
    val progress: PlaybackProgress,
)
