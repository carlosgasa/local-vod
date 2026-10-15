package com.local.vod.presentation.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.local.vod.di.AppScope
import com.local.vod.domain.model.Episode
import com.local.vod.domain.usecase.GetEpisode
import com.local.vod.domain.usecase.SaveProgress
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    savedState: SavedStateHandle,
    getEpisode: GetEpisode,
    private val saveProgress: SaveProgress,
    @param:AppScope private val appScope: CoroutineScope,
) : ViewModel() {

    val startMs: Long = savedState["startMs"] ?: 0L
    private val episodeId: String = checkNotNull(savedState["episodeId"])

    private val _episode = MutableStateFlow<Episode?>(null)
    val episode = _episode.asStateFlow()

    init {
        viewModelScope.launch { _episode.value = getEpisode(episodeId) }
    }

    /** Se llama al salir del reproductor: usa un scope de app porque el ViewModel muere al navegar. */
    fun save(positionMs: Long, durationMs: Long) {
        val episode = _episode.value ?: return
        appScope.launch { saveProgress(episode, positionMs, durationMs) }
    }
}
