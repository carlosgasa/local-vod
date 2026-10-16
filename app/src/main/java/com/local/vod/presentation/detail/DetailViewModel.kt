package com.local.vod.presentation.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.local.vod.domain.model.PlaybackProgress
import com.local.vod.domain.model.Episode
import com.local.vod.domain.model.Title
import com.local.vod.domain.usecase.ClearProgress
import com.local.vod.domain.usecase.GetTitle
import com.local.vod.domain.usecase.GetTitleProgress
import com.local.vod.domain.usecase.MarkWatched
import com.local.vod.domain.usecase.NextToWatch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DetailUiState(
    val title: Title? = null,
    val progress: Map<String, PlaybackProgress> = emptyMap(),
    val error: String? = null,
    /** Por dónde seguir la serie: episodio y desde qué milisegundo. */
    val next: Pair<Episode, Long>? = null,
)

@HiltViewModel
class DetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    getTitle: GetTitle,
    getTitleProgress: GetTitleProgress,
    private val nextToWatch: NextToWatch,
    private val clearProgress: ClearProgress,
    private val markWatched: MarkWatched,
) : ViewModel() {

    private val titleId: String = checkNotNull(savedState["titleId"])
    private val title = MutableStateFlow<Title?>(null)
    private val error = MutableStateFlow<String?>(null)

    val state = combine(title, getTitleProgress(titleId), error) { title, progress, error ->
        DetailUiState(title, progress, error, title?.let { nextToWatch(it, progress) })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailUiState())

    init {
        viewModelScope.launch {
            runCatching { getTitle(titleId) }
                .onSuccess { title.value = it }
                .onFailure { error.value = it.message ?: "No se pudo cargar" }
        }
    }

    /** "Empezar de nuevo": borra la posición guardada antes de reproducir. */
    fun restart(episodeId: String, onDone: () -> Unit) {
        viewModelScope.launch {
            clearProgress(episodeId)
            onDone()
        }
    }

    fun setWatched(episode: Episode, saved: PlaybackProgress?) {
        viewModelScope.launch {
            if ((saved?.fraction ?: 0f) >= 0.95f) clearProgress(episode.id)
            else markWatched(episode, saved)
        }
    }
}
