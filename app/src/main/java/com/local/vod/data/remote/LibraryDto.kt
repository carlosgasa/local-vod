package com.local.vod.data.remote

import com.local.vod.domain.model.Episode
import com.local.vod.domain.model.MusicAlbum
import com.local.vod.domain.model.Season
import com.local.vod.domain.model.Subtitle
import com.local.vod.domain.model.Title
import com.local.vod.domain.model.Track
import kotlinx.serialization.Serializable

@Serializable
data class LibraryResponse(val items: List<TitleDto> = emptyList())

@Serializable
data class TitleDto(
    val id: String,
    val title: String,
    val type: String,
    val poster: String? = null,
    val preview: String? = null,
    val mosaic: String? = null,
    val seasons: List<SeasonDto> = emptyList(),
    val addedAt: Long = 0,
)

@Serializable
data class SeasonDto(val number: Int, val episodes: List<EpisodeDto> = emptyList())

@Serializable
data class EpisodeDto(
    val id: String,
    val title: String,
    val season: Int,
    val number: Int? = null,
    val url: String,
    val subs: List<SubtitleDto> = emptyList(),
    val durationSeconds: Long? = null,
    val thumb: String? = null,
    val addedAt: Long = 0,
)

@Serializable
data class SubtitleDto(val lang: String, val url: String)

@Serializable
data class MusicResponse(val albums: List<MusicAlbumDto> = emptyList())

@Serializable
data class MusicAlbumDto(
    val id: String,
    val title: String,
    val cover: String? = null,
    val tracks: List<TrackDto> = emptyList(),
)

@Serializable
data class TrackDto(
    val id: String,
    val title: String,
    val url: String,
    val durationSeconds: Long? = null,
    val bytes: Long = 0,
)

/** Las rutas del servidor son relativas; un enlace remoto ya viene completo. */
internal fun absolute(url: String, baseUrl: String) =
    if (url.startsWith("http://") || url.startsWith("https://")) url else baseUrl + url

// El servidor manda rutas relativas ("/media/..."): aquí se vuelven absolutas.
fun TitleDto.toDomain(baseUrl: String) = Title(
    id = id,
    name = title,
    isMovie = type == "movie",
    posterUrl = poster?.let { absolute(it, baseUrl) },
    previewUrl = preview?.let { absolute(it, baseUrl) },
    mosaicUrl = mosaic?.let { absolute(it, baseUrl) },
    seasons = seasons.map { season ->
        Season(
            number = season.number,
            episodes = season.episodes.map { it.toDomain(id, baseUrl) },
        )
    },
    addedAt = addedAt,
)

fun EpisodeDto.toDomain(titleId: String, baseUrl: String) = Episode(
    id = id,
    titleId = titleId,
    name = title,
    season = season,
    number = number,
    url = absolute(url, baseUrl),
    subs = subs.map { Subtitle(it.lang, absolute(it.url, baseUrl)) },
    durationSeconds = durationSeconds,
    thumbUrl = thumb?.let { absolute(it, baseUrl) },
    addedAt = addedAt,
)

fun MusicAlbumDto.toDomain(baseUrl: String) = MusicAlbum(
    id = id,
    title = title,
    coverUrl = cover?.let { absolute(it, baseUrl) },
    tracks = tracks.map { Track(it.id, it.title, absolute(it.url, baseUrl), it.durationSeconds) },
)
