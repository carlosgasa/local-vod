package com.local.vod.domain.model

data class MusicAlbum(
    val id: String,
    val title: String,
    val coverUrl: String?,
    val tracks: List<Track>,
)

data class Track(
    val id: String,
    val title: String,
    val url: String,
    val durationSeconds: Long?,
)
