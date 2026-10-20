package com.local.vod.presentation.tv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.local.vod.domain.model.Channel
import com.local.vod.domain.model.ChannelGroup
import com.local.vod.domain.model.TitleList
import com.local.vod.domain.repository.ListsRepository
import com.local.vod.domain.usecase.GetChannelGroups
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LiveTvUiState(
    val loading: Boolean = true,
    val groups: List<ChannelGroup> = emptyList(),
    val error: String? = null,
    val favoriteIds: List<String> = emptyList(),
    val hiddenIds: Set<String> = emptySet(),
    // En modo editar se ven también los ocultos y tocar un canal abre sus opciones.
    val editing: Boolean = false,
) {
    /** Los grupos tal como se muestran: sin los ocultos, salvo al editar. */
    val visibleGroups: List<ChannelGroup> get() = if (editing) groups else
        groups.map { g -> g.copy(channels = g.channels.filter { it.id !in hiddenIds }) }.filter { it.channels.isNotEmpty() }

    val favorites: List<Channel> get() {
        val byId = groups.flatMap { it.channels }.associateBy { it.id }
        return favoriteIds.mapNotNull { byId[it] }
    }
}

@HiltViewModel
class LiveTvViewModel @Inject constructor(
    private val getChannelGroups: GetChannelGroups,
    private val lists: ListsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(LiveTvUiState())
    val state = _state.asStateFlow()

    init {
        load()
        viewModelScope.launch { lists.lists.collect { l -> _state.update { it.copy(favoriteIds = l.channels, hiddenIds = l.hidden.toSet()) } } }
        viewModelScope.launch { runCatching { lists.refresh() } }
    }

    fun toggleEditing() = _state.update { it.copy(editing = !it.editing) }

    fun set(list: TitleList, channel: Channel, on: Boolean) {
        viewModelScope.launch { runCatching { lists.set(list, channel.id, on) } }
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { getChannelGroups() }
                .onSuccess { groups -> _state.update { it.copy(loading = false, groups = groups) } }
                .onFailure { error ->
                    _state.update { it.copy(loading = false, error = error.message ?: "No se pudo conectar") }
                }
        }
    }
}
