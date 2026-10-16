package com.local.vod.data.repository

import com.local.vod.data.remote.VodApi
import com.local.vod.domain.model.PhotoAlbum
import com.local.vod.domain.repository.PhotoRepository
import com.local.vod.domain.repository.ServerRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class PhotoRepositoryImpl @Inject constructor(
    private val api: VodApi,
    private val server: ServerRepository,
) : PhotoRepository {
    override suspend fun albums(): List<PhotoAlbum> {
        val base = server.baseUrl.first() ?: throw IllegalStateException("Servidor sin configurar")
        return api.photos(base)
    }
}
