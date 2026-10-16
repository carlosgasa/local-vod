package com.local.vod.presentation.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.local.vod.domain.model.ServerStatus
import com.local.vod.domain.repository.ServerRepository
import com.local.vod.domain.usecase.GetServerStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SetupUiState(
    val address: String = "192.168.1.100:8000",
    val testing: Boolean = false,
    val searching: Boolean = false,
    val error: String? = null,
    val status: ServerStatus? = null,
    val previewsEnabled: Boolean = true,
)

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val server: ServerRepository,
    private val getServerStatus: GetServerStatus,
) : ViewModel() {

    private val _state = MutableStateFlow(SetupUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            server.previewsEnabled.collect { enabled ->
                _state.update { it.copy(previewsEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            server.baseUrl.first()?.let { saved ->
                _state.update { it.copy(address = saved.removePrefix("http://")) }
                loadStatus()
            }
        }
    }

    fun togglePreviews() {
        viewModelScope.launch { server.setPreviewsEnabled(!_state.value.previewsEnabled) }
    }

    fun loadStatus() {
        viewModelScope.launch {
            runCatching { getServerStatus() }
                .onSuccess { status -> _state.update { it.copy(status = status) } }
                .onFailure { _state.update { it.copy(status = null) } }
        }
    }

    fun onAddressChange(value: String) {
        _state.update { it.copy(address = value, error = null) }
    }

    fun testAndSave(onSaved: () -> Unit) {
        val url = normalize(_state.value.address)
        viewModelScope.launch {
            _state.update { it.copy(testing = true, error = null) }
            if (server.probe(url)) {
                server.save(url)
                _state.update { it.copy(testing = false) }
                loadStatus()
                onSaved()
            } else {
                _state.update {
                    it.copy(testing = false, error = "No contesta en $url. Revisa que el servidor esté encendido en la PC.")
                }
            }
        }
    }

    /** Recorre la red local buscando el servidor: útil cuando el router le cambia la IP. */
    fun search(onSaved: () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(searching = true, error = null) }
            val found = runCatching { server.discover() }.getOrNull()
            if (found != null) {
                server.save(found)
                _state.update { it.copy(searching = false, address = found.removePrefix("http://")) }
                loadStatus()
                onSaved()
            } else {
                _state.update {
                    it.copy(
                        searching = false,
                        error = "No encontré ningún servidor en esta red. Comprueba que la PC esté encendida " +
                            "y en el mismo wifi.",
                    )
                }
            }
        }
    }

    private fun normalize(input: String): String {
        val trimmed = input.trim().trimEnd('/').removePrefix("http://").removePrefix("https://")
        return "http://" + if (trimmed.contains(':')) trimmed else "$trimmed:8000"
    }
}
