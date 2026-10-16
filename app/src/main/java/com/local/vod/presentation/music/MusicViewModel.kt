package com.local.vod.presentation.music

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.local.vod.domain.model.MusicAlbum
import com.local.vod.domain.usecase.GetMusicAlbums
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MusicUiState(
    val albums: List<MusicAlbum> = emptyList(),
    val selectedAlbumId: String? = null,
    val playingAlbumId: String? = null,
    val trackIndex: Int = -1,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val loading: Boolean = true,
    val error: String? = null,
)

@HiltViewModel
class MusicViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val getMusicAlbums: GetMusicAlbums,
) : ViewModel() {
    private val player = ExoPlayer.Builder(context).build()
    private val _state = MutableStateFlow(MusicUiState())
    val state = _state.asStateFlow()

    init {
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = updatePlayback()
        })
        load()
        viewModelScope.launch {
            while (true) {
                updatePlayback()
                delay(500)
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                _state.value = _state.value.copy(albums = getMusicAlbums(), loading = false)
            } catch (error: Exception) {
                _state.value = _state.value.copy(loading = false, error = error.message ?: "No se pudo cargar la música")
            }
        }
    }

    fun select(album: MusicAlbum?) {
        _state.value = _state.value.copy(selectedAlbumId = album?.id)
    }

    fun play(album: MusicAlbum, index: Int) {
        _state.value = _state.value.copy(playingAlbumId = album.id)
        player.setMediaItems(album.tracks.map { MediaItem.Builder().setMediaId(it.id).setUri(it.url).build() }, index, 0L)
        player.prepare()
        player.play()
        updatePlayback()
    }

    fun togglePlay() {
        if (player.isPlaying) player.pause() else player.play()
        updatePlayback()
    }

    fun previous() {
        if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem() else player.seekTo(0)
        updatePlayback()
    }

    fun next() {
        if (player.hasNextMediaItem()) player.seekToNextMediaItem()
        updatePlayback()
    }

    private fun updatePlayback() {
        val current = _state.value
        _state.value = current.copy(
            trackIndex = player.currentMediaItemIndex.takeIf { player.mediaItemCount > 0 } ?: -1,
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.coerceAtLeast(0),
        )
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }
}
