package com.local.vod.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.local.vod.data.remote.VodApi
import com.local.vod.domain.model.Profile
import com.local.vod.domain.repository.ProfileRepository
import com.local.vod.domain.repository.ServerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val api: VodApi,
    private val server: ServerRepository,
) : ProfileRepository {

    override val current: Flow<Profile?> = dataStore.data.map { prefs ->
        val id = prefs[ID] ?: return@map null
        Profile(id, prefs[NAME].orEmpty())
    }

    override suspend fun available(): List<Profile> = api.profiles(base())

    override suspend fun create(name: String): Profile? = api.createProfile(base(), name)

    override suspend fun select(profile: Profile) {
        dataStore.edit {
            it[ID] = profile.id
            it[NAME] = profile.name
        }
    }

    private suspend fun base(): String =
        server.baseUrl.first() ?: throw IllegalStateException("Servidor sin configurar")

    private companion object {
        val ID = stringPreferencesKey("profile_id")
        val NAME = stringPreferencesKey("profile_name")
    }
}
