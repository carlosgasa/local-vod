package com.local.vod.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.local.vod.domain.model.ContinueItem
import com.local.vod.domain.model.Title
import com.local.vod.domain.model.TitleLists
import com.local.vod.domain.model.Channel
import com.local.vod.domain.usecase.GetChannelGroups
import com.local.vod.domain.repository.ListsRepository
import com.local.vod.domain.repository.ProfileRepository
import com.local.vod.domain.repository.ServerRepository
import com.local.vod.domain.usecase.GetContinueWatching
import com.local.vod.domain.usecase.GetLibrary
import com.local.vod.domain.usecase.IsServerAlive
import com.local.vod.domain.usecase.SyncProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject

data class HomeUiState(
    val loading: Boolean = true,
    val needsSetup: Boolean = false,
    val needsProfile: Boolean = false,
    /** El latido dejó de contestar: el servidor se apagó o se cayó la red. */
    val serverDown: Boolean = false,
    val profileName: String? = null,
    val error: String? = null,
    val titles: List<Title> = emptyList(),
    val continueItems: List<ContinueItem> = emptyList(),
    val lists: TitleLists = TitleLists(),
    val favoriteChannels: List<Channel> = emptyList(),
    val searchOpen: Boolean = false,
    val searchQuery: String = "",
) {
    val isSearching: Boolean get() = searchOpen && searchQuery.isNotBlank()

    val visibleTitles: List<Title> get() {
        if (!isSearching) return titles
        val query = searchQuery.trim().normalizeForSearch()
        return titles.filter { query in it.name.normalizeForSearch() }
    }

    // un id que ya no está en la biblioteca (se borró la película) simplemente no sale
    val laterTitles: List<Title> get() = lists.later.mapNotNull { id -> titles.firstOrNull { it.id == id } }
    val favoriteTitles: List<Title> get() = lists.favorites.mapNotNull { id -> titles.firstOrNull { it.id == id } }

    /** Para el destacado: lo último que entró, prefiriendo lo que tiene imagen que enseñar. */
    val heroTitles: List<Title> get() = titles
        .sortedWith(compareByDescending<Title> { it.thumbUrl != null || it.posterUrl != null }.thenByDescending { it.addedAt })
        .take(5)

    val recentTitles: List<Title> get() =
        if (titles.size > 6 && titles.any { it.addedAt > 0L }) {
            titles.sortedByDescending { it.addedAt }.take(10)
        } else {
            emptyList()
        }
}

private fun String.normalizeForSearch(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val getLibrary: GetLibrary,
    private val syncProgress: SyncProgress,
    private val isServerAlive: IsServerAlive,
    getContinueWatching: GetContinueWatching,
    server: ServerRepository,
    profiles: ProfileRepository,
    private val lists: ListsRepository,
    private val getChannelGroups: GetChannelGroups,
) : ViewModel() {

    val previewsEnabled = server.previewsEnabled

    private val _state = MutableStateFlow(HomeUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            server.baseUrl.collect { url ->
                if (url == null) {
                    _state.update { it.copy(loading = false, needsSetup = true) }
                } else {
                    _state.update { it.copy(needsSetup = false) }
                    load(refresh = true)
                }
            }
        }
        viewModelScope.launch {
            profiles.current.collect { profile ->
                _state.update { it.copy(needsProfile = profile == null, profileName = profile?.name) }
                if (profile != null) load(refresh = true)
            }
        }
        // Latido: cada minuto se comprueba que el servidor siga en pie.
        viewModelScope.launch {
            while (true) {
                delay(60_000)
                if (_state.value.needsSetup) continue
                val alive = isServerAlive()
                val wasDown = _state.value.serverDown
                _state.update { it.copy(serverDown = !alive) }
                if (alive && wasDown) load(refresh = true) // volvió: se recarga solo
            }
        }
        viewModelScope.launch {
            lists.lists.collect { value ->
                _state.update { it.copy(lists = value) }
                loadFavoriteChannels(value.channels)
            }
        }
        viewModelScope.launch {
            getContinueWatching()
                .catch { /* sin servidor todavía: la fila sale vacía */ }
                .collect { items -> _state.update { it.copy(continueItems = items) } }
        }
    }

    fun load(refresh: Boolean = false) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            // lo visto en el móvil tiene que aparecer en la tele: primero se sincroniza
            runCatching { syncProgress() }
            runCatching { lists.refresh() }
            runCatching { getLibrary(refresh) }
                .onSuccess { titles -> _state.update { it.copy(loading = false, titles = titles) } }
                .onFailure { e ->
                    _state.update { it.copy(loading = false, error = e.message ?: "No se pudo conectar") }
                }
        }
    }

    /** Los canales favoritos se guardan por dirección: sus nombres y logos salen de las listas. */
    private suspend fun loadFavoriteChannels(ids: List<String>) {
        if (ids.isEmpty()) return _state.update { it.copy(favoriteChannels = emptyList()) }
        if (_state.value.favoriteChannels.map { it.id } == ids) return
        val byId = runCatching { getChannelGroups() }.getOrNull()
            ?.flatMap { it.channels }?.associateBy { it.id } ?: return
        _state.update { it.copy(favoriteChannels = ids.mapNotNull { id -> byId[id] }) }
    }

    fun toggleSearch() {
        _state.update { it.copy(searchOpen = !it.searchOpen, searchQuery = "") }
    }

    fun onSearchQueryChange(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }
}
