package com.local.vod.presentation.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.local.vod.di.AppScope
import com.local.vod.domain.model.Episode
import com.local.vod.domain.model.Playlist
import com.local.vod.domain.model.TitleList
import com.local.vod.domain.repository.ListsRepository
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
    private val listsRepository: ListsRepository,
    @param:AppScope private val appScope: CoroutineScope,
) : ViewModel() {

    val startMs: Long = savedState["startMs"] ?: 0L
    private val episodeId: String? = savedState["episodeId"]
    // Un canal de TV en vivo llega con su dirección y su nombre, sin pasar por la biblioteca.
    val liveUrl: String? = savedState["liveUrl"]
    val isLive: Boolean get() = liveUrl != null

    val lists = listsRepository.lists

    private val _playlist = MutableStateFlow<Playlist?>(null)
    val playlist = _playlist.asStateFlow()

    init {
        val live = liveUrl
        if (live != null) {
            viewModelScope.launch { runCatching { listsRepository.refresh() } }
            val name = savedState.get<String>("liveName").orEmpty()
            val channel = Episode(id = live, titleId = "", name = name, season = 1, number = null, url = live, subs = emptyList())
            _playlist.value = Playlist(listOf(channel), 0, titleName = name, isMovie = true)
        } else {
            viewModelScope.launch { _playlist.value = getPlaylist(checkNotNull(episodeId)) }
        }
    }

    /**
     * Guarda el progreso del episodio indicado (el reproductor puede haber avanzado solo al
     * siguiente). Usa un scope de app porque el ViewModel muere al navegar hacia atrás.
     */
    /** El ♥ del canal en vivo: entra o sale de los canales favoritos del perfil. */
    fun toggleFavoriteChannel(isFavorite: Boolean) {
        val url = liveUrl ?: return
        viewModelScope.launch { runCatching { listsRepository.set(TitleList.CHANNELS, url, !isFavorite) } }
    }

    fun save(episodeId: String, positionMs: Long, durationMs: Long) {
        if (isLive) return // en vivo no hay "por dónde ibas"
        val episode = _playlist.value?.episodes?.firstOrNull { it.id == episodeId } ?: return
        appScope.launch { saveProgress(episode, positionMs, durationMs) }
    }
}
