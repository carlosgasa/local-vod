package com.local.vod.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.local.vod.data.remote.VodApi
import com.local.vod.domain.repository.ServerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val api: VodApi,
) : ServerRepository {

    override val baseUrl: Flow<String?> = dataStore.data.map { it[KEY] }

    override suspend fun save(baseUrl: String) {
        dataStore.edit { it[KEY] = baseUrl }
    }

    override suspend fun probe(baseUrl: String): Boolean =
        runCatching { api.profiles(baseUrl) }.isSuccess

    /**
     * Si el router le cambia la IP a la PC, nadie quiere reconfigurar tres aparatos:
     * se recorre la propia subred /24 buscando quién responde la API.
     */
    override suspend fun discover(): String? = withContext(Dispatchers.IO) {
        val prefix = localPrefix() ?: return@withContext null
        val port = baseUrl.first()?.substringAfterLast(':')?.toIntOrNull() ?: DEFAULT_PORT
        coroutineScope {
            for (block in (1..254).chunked(64)) {
                // Primero un toque al puerto (barato y rápido); solo a quien conteste
                // se le pregunta por HTTP si de verdad es este servidor.
                val candidates = block
                    .map { host -> async { "$prefix.$host".takeIf { portOpen(it, port) } } }
                    .awaitAll()
                    .filterNotNull()
                for (host in candidates) {
                    val url = "http://$host:$port"
                    if (runCatching { api.profiles(url) }.isSuccess) return@coroutineScope url
                }
            }
            null
        }
    }

    private fun portOpen(host: String, port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS)
            true
        }
    }.getOrDefault(false)

    /** "192.168.1" a partir de la IP de este aparato en la red local. */
    private fun localPrefix(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
            ?.substringBeforeLast('.')
    }.getOrNull()

    private companion object {
        val KEY = stringPreferencesKey("base_url")
        const val DEFAULT_PORT = 8000
        const val PROBE_TIMEOUT_MS = 350
    }
}
