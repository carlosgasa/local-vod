package com.local.vod.data.repository

import com.local.vod.data.remote.VodApi
import com.local.vod.domain.model.TitleList
import com.local.vod.domain.model.TitleLists
import com.local.vod.domain.repository.ListsRepository
import com.local.vod.domain.repository.ProfileRepository
import com.local.vod.domain.repository.ServerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** Las listas viven en el servidor; aquí solo la copia que pintan las pantallas. */
@Singleton
class ListsRepositoryImpl @Inject constructor(
    private val api: VodApi,
    private val profiles: ProfileRepository,
    private val server: ServerRepository,
) : ListsRepository {

    private val _lists = MutableStateFlow(TitleLists())
    override val lists = _lists.asStateFlow()

    override suspend fun refresh() {
        val profile = profiles.current.first() ?: return run { _lists.value = TitleLists() }
        _lists.value = api.lists(base(), profile.id)
    }

    override suspend fun set(list: TitleList, titleId: String, on: Boolean) {
        val profile = profiles.current.first() ?: return
        // se pinta al momento y luego se confirma con lo que diga el servidor
        _lists.update { current ->
            val ids = current.ids(list).filter { it != titleId }.let { if (on) listOf(titleId) + it else it }
            when (list) {
                TitleList.LATER -> current.copy(later = ids)
                TitleList.FAVORITES -> current.copy(favorites = ids)
                TitleList.CHANNELS -> current.copy(channels = ids)
                TitleList.HIDDEN -> current.copy(hidden = ids)
            }
        }
        _lists.value = api.setInList(base(), profile.id, list.key, titleId, on)
    }

    private suspend fun base(): String =
        server.baseUrl.first() ?: throw IllegalStateException("Servidor sin configurar")
}
