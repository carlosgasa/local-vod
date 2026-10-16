package com.local.vod.domain.model

data class PhotoAlbum(
    val id: String,
    val title: String,
    val coverUrl: String,
    val count: Int,
    val photos: List<Photo>,
)

data class Photo(val id: String, val name: String, val url: String)
