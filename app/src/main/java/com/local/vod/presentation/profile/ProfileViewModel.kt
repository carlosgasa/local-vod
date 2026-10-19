package com.local.vod.presentation.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.local.vod.domain.model.Profile
import com.local.vod.domain.repository.ServerRepository
import com.local.vod.domain.usecase.CreateProfile
import com.local.vod.domain.usecase.DeleteProfile
import com.local.vod.domain.usecase.RenameProfile
import com.local.vod.domain.usecase.GetProfiles
import com.local.vod.domain.usecase.SelectProfile
import com.local.vod.domain.usecase.SyncProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileUiState(
    val loading: Boolean = true,
    val profiles: List<Profile> = emptyList(),
    val error: String? = null,
    val newName: String = "",
    val editing: Boolean = false,
    // el perfil cuyo nombre está en la caja de texto, o null si la caja crea uno nuevo
    val renaming: Profile? = null,
    // borrar pide pulsar dos veces: con el mando es fácil darle sin querer
    val confirmDelete: String? = null,
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val getProfiles: GetProfiles,
    private val createProfile: CreateProfile,
    private val selectProfile: SelectProfile,
    private val syncProgress: SyncProgress,
    private val server: ServerRepository,
    private val renameProfile: RenameProfile,
    private val deleteProfile: DeleteProfile,
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileUiState())
    val state = _state.asStateFlow()

    // Se recarga cada vez que cambia el servidor (por ejemplo, al volver de ajustes).
    init { viewModelScope.launch { server.baseUrl.distinctUntilChanged().collect { load() } } }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { getProfiles() }
                .recoverCatching { error ->
                    // El servidor guardado no contesta: puede que cambiara de IP o de
                    // máquina. Se busca en la red antes de dejar a nadie atascado aquí.
                    val found = server.discover() ?: throw error
                    server.save(found)
                    getProfiles()
                }
                .onSuccess { list -> _state.update { it.copy(loading = false, profiles = list) } }
                .onFailure { e ->
                    _state.update { it.copy(loading = false, error = e.message ?: "No se pudo conectar") }
                }
        }
    }

    fun onNameChange(value: String) = _state.update { it.copy(newName = value) }

    fun toggleEditing() = _state.update {
        it.copy(editing = !it.editing, renaming = null, confirmDelete = null, newName = "")
    }

    fun startRename(profile: Profile) = _state.update {
        it.copy(renaming = profile, newName = profile.name, confirmDelete = null)
    }

    fun delete(profile: Profile) {
        if (_state.value.confirmDelete != profile.id) {
            _state.update { it.copy(confirmDelete = profile.id, renaming = null, newName = "") }
            return
        }
        viewModelScope.launch {
            runCatching { deleteProfile(profile) }
                .onSuccess { _state.update { it.copy(confirmDelete = null) }; load() }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

    /** El botón de la caja de texto: crea un perfil, o guarda el nombre si se está renombrando. */
    fun submit(onDone: () -> Unit) {
        val renaming = _state.value.renaming ?: return create(onDone)
        val name = _state.value.newName.trim()
        if (name.isEmpty()) return
        viewModelScope.launch {
            runCatching { renameProfile(renaming, name) }
                .onSuccess { _state.update { it.copy(renaming = null, newName = "") }; load() }
                .onFailure { e -> _state.update { it.copy(error = e.message) } }
        }
    }

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
