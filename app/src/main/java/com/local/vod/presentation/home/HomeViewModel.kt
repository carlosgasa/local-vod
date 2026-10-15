package com.local.vod.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.local.vod.domain.model.ContinueItem
import com.local.vod.domain.model.Title
import com.local.vod.domain.repository.ServerRepository
import com.local.vod.domain.usecase.GetContinueWatching
import com.local.vod.domain.usecase.GetLibrary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val loading: Boolean = true,
    val needsSetup: Boolean = false,
    val error: String? = null,
    val titles: List<Title> = emptyList(),
    val continueItems: List<ContinueItem> = emptyList(),
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val getLibrary: GetLibrary,
    getContinueWatching: GetContinueWatching,
    server: ServerRepository,
) : ViewModel() {

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
            getContinueWatching()
                .catch { /* sin servidor todavía: la fila sale vacía */ }
                .collect { items -> _state.update { it.copy(continueItems = items) } }
        }
    }

    fun load(refresh: Boolean = false) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { getLibrary(refresh) }
                .onSuccess { titles -> _state.update { it.copy(loading = false, titles = titles) } }
                .onFailure { e ->
                    _state.update { it.copy(loading = false, error = e.message ?: "No se pudo conectar") }
                }
        }
    }
}
