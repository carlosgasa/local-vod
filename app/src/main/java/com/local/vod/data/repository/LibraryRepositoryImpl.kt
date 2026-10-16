package com.local.vod.data.repository

import com.local.vod.data.remote.VodApi
import com.local.vod.domain.model.Title
import com.local.vod.domain.repository.LibraryRepository
import com.local.vod.domain.repository.ServerRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** La biblioteca es pequeña: se cachea en memoria y se recarga a mano. */
@Singleton
class LibraryRepositoryImpl @Inject constructor(
    private val api: VodApi,
    private val server: ServerRepository,
) : LibraryRepository {

    private val mutex = Mutex()
    private var cache: List<Title>? = null

    override suspend fun titles(): List<Title> = cache ?: refresh()

    override suspend fun refresh(): List<Title> = mutex.withLock {
        val base = server.baseUrl.first() ?: throw IllegalStateException("Servidor sin configurar")
        api.library(base).also { cache = it }
    }

    override suspend fun title(id: String): Title? = titles().firstOrNull { it.id == id }
}
