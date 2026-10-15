package com.local.vod.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.local.vod.data.remote.LibraryApi
import com.local.vod.domain.repository.ServerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val api: LibraryApi,
) : ServerRepository {

    override val baseUrl: Flow<String?> = dataStore.data.map { it[KEY] }

    override suspend fun save(baseUrl: String) {
        dataStore.edit { it[KEY] = baseUrl }
    }

    override suspend fun probe(baseUrl: String): Boolean =
        runCatching { api.fetch(baseUrl) }.isSuccess

    private companion object {
        val KEY = stringPreferencesKey("base_url")
    }
}
