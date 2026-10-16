package com.local.vod.presentation.photos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.local.vod.domain.model.PhotoAlbum
import com.local.vod.domain.usecase.GetPhotoAlbums
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PhotosUiState(
    val loading: Boolean = true,
    val albums: List<PhotoAlbum> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class PhotosViewModel @Inject constructor(private val getPhotoAlbums: GetPhotoAlbums) : ViewModel() {
    private val _state = MutableStateFlow(PhotosUiState())
    val state = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            runCatching { getPhotoAlbums() }
                .onSuccess { albums -> _state.update { it.copy(loading = false, albums = albums) } }
                .onFailure { error ->
                    _state.update { it.copy(loading = false, error = error.message ?: "No se pudo conectar") }
                }
        }
    }
}
