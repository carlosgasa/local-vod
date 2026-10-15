package com.local.vod.presentation.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.local.vod.di.AppScope
import com.local.vod.domain.model.Playlist
import com.local.vod.domain.usecase.GetPlaylist
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
    getPlaylist: GetPlaylist,
    private val saveProgress: SaveProgress,
    @param:AppScope private val appScope: CoroutineScope,
) : ViewModel() {

    val startMs: Long = savedState["startMs"] ?: 0L
    private val episodeId: String = checkNotNull(savedState["episodeId"])

    private val _playlist = MutableStateFlow<Playlist?>(null)
    val playlist = _playlist.asStateFlow()

    init {
        viewModelScope.launch { _playlist.value = getPlaylist(episodeId) }
    }

    /**
     * Guarda el progreso del episodio indicado (el reproductor puede haber avanzado solo al
     * siguiente). Usa un scope de app porque el ViewModel muere al navegar hacia atrás.
     */
    fun save(episodeId: String, positionMs: Long, durationMs: Long) {
        val episode = _playlist.value?.episodes?.firstOrNull { it.id == episodeId } ?: return
        appScope.launch { saveProgress(episode, positionMs, durationMs) }
    }
}
