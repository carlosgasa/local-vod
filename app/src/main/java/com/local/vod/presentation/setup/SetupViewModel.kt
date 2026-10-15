package com.local.vod.presentation.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.local.vod.domain.repository.ServerRepository
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
    val error: String? = null,
)

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val server: ServerRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SetupUiState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            server.baseUrl.first()?.let { saved ->
                _state.update { it.copy(address = saved.removePrefix("http://")) }
            }
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
                onSaved()
            } else {
                _state.update {
                    it.copy(testing = false, error = "No contesta en $url. Revisa que el servidor esté encendido en la PC.")
                }
            }
        }
    }

    private fun normalize(input: String): String {
        val trimmed = input.trim().trimEnd('/').removePrefix("http://").removePrefix("https://")
        return "http://" + if (trimmed.contains(':')) trimmed else "$trimmed:8000"
    }
}
