package com.local.vod.data.remote

import com.local.vod.domain.model.Photo
import com.local.vod.domain.model.PhotoAlbum
import kotlinx.serialization.Serializable

@Serializable
data class PhotosResponse(val albums: List<PhotoAlbumDto> = emptyList())

@Serializable
data class PhotoAlbumDto(
    val id: String,
    val title: String,
    val cover: String,
    val count: Int,
    val photos: List<PhotoDto>,
)

@Serializable
data class PhotoDto(val id: String, val name: String, val url: String, val bytes: Long)

fun PhotoAlbumDto.toDomain(baseUrl: String) = PhotoAlbum(
    id = id,
    title = title,
    coverUrl = absolute(cover, baseUrl),
    count = count,
    photos = photos.map { Photo(it.id, it.name, absolute(it.url, baseUrl)) },
)
