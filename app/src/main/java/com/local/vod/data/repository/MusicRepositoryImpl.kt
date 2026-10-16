package com.local.vod.data.repository

import com.local.vod.data.remote.VodApi
import com.local.vod.domain.model.MusicAlbum
import com.local.vod.domain.repository.MusicRepository
import com.local.vod.domain.repository.ServerRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class MusicRepositoryImpl @Inject constructor(
    private val api: VodApi,
    private val server: ServerRepository,
) : MusicRepository {
    override suspend fun albums(): List<MusicAlbum> {
        val base = server.baseUrl.first() ?: throw IllegalStateException("Servidor sin configurar")
        return api.music(base)
    }
}
