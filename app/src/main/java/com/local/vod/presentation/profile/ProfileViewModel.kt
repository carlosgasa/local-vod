package com.local.vod.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.local.vod.domain.model.Profile
import com.local.vod.domain.usecase.CreateProfile
import com.local.vod.domain.usecase.GetProfiles
import com.local.vod.domain.usecase.SelectProfile
import com.local.vod.domain.usecase.SyncProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileUiState(
    val loading: Boolean = true,
    val profiles: List<Profile> = emptyList(),
    val error: String? = null,
    val newName: String = "",
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val getProfiles: GetProfiles,
    private val createProfile: CreateProfile,
    private val selectProfile: SelectProfile,
    private val syncProgress: SyncProgress,
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileUiState())
    val state = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { getProfiles() }
                .onSuccess { list -> _state.update { it.copy(loading = false, profiles = list) } }
                .onFailure { e ->
                    _state.update { it.copy(loading = false, error = e.message ?: "No se pudo conectar") }
                }
        }
    }

    fun onNameChange(value: String) = _state.update { it.copy(newName = value) }

    fun create(onDone: () -> Unit) {
        val name = _state.value.newName.trim()
        if (name.isEmpty()) return
        viewModelScope.launch {
            runCatching { createProfile(name) }
                .onSuccess { profile ->
                    _state.update { it.copy(newName = "") }
                    if (profile != null) choose(profile, onDone) else load()
                }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    fun choose(profile: Profile, onDone: () -> Unit) {
        viewModelScope.launch {
            selectProfile(profile)
            runCatching { syncProgress() } // trae lo visto en los otros aparatos
            onDone()
        }
    }
}
