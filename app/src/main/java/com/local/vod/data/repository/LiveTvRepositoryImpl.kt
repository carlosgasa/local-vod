package com.local.vod.data.repository

import com.local.vod.data.remote.VodApi
import com.local.vod.domain.model.ChannelGroup
import com.local.vod.domain.repository.LiveTvRepository
import com.local.vod.domain.repository.ServerRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class LiveTvRepositoryImpl @Inject constructor(
    private val api: VodApi,
    private val server: ServerRepository,
) : LiveTvRepository {
    override suspend fun groups(): List<ChannelGroup> {
        val base = server.baseUrl.first() ?: throw IllegalStateException("Servidor sin configurar")
        return api.liveTv(base)
    }
}
