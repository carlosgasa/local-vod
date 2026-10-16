package com.local.vod.presentation.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.local.vod.R
import com.local.vod.domain.model.MusicAlbum
import com.local.vod.presentation.TvButton
import com.local.vod.presentation.TvCard
import com.local.vod.presentation.TvIconButton
import com.local.vod.presentation.formatTime
import com.local.vod.presentation.neonBackground
import com.local.vod.presentation.screenPadding

@Composable
fun MusicScreen(onBack: () -> Unit, viewModel: MusicViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selected = state.albums.firstOrNull { it.id == state.selectedAlbumId }
    val playing = state.albums.firstOrNull { it.id == state.playingAlbumId }
    val track = playing?.tracks?.getOrNull(state.trackIndex)

    Column(Modifier.fillMaxSize().neonBackground().screenPadding()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TvButton(onClick = { if (selected == null) onBack() else viewModel.select(null) }) {
                Text("Volver")
            }
            Spacer(Modifier.width(16.dp))
            Text(selected?.title ?: "Música", style = MaterialTheme.typography.headlineMedium)
        }
        Spacer(Modifier.height(20.dp))

        when {
            state.loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("Cargando música…")
            }
            state.error != null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.error ?: "Error")
                    Spacer(Modifier.height(12.dp))
                    TvButton(onClick = viewModel::load) { Text("Reintentar") }
                }
            }
            selected == null -> {
                if (state.albums.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("No hay álbumes de música.")
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(160.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        items(state.albums, key = { it.id }) { album ->
                            Column {
                                TvCard(onClick = { viewModel.select(album) }) {
                                    AlbumArt(album, Modifier.fillMaxWidth().aspectRatio(1f))
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(album.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            else -> LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                itemsIndexed(selected.tracks, key = { _, item -> item.id }) { index, item ->
                    TvCard(onClick = { viewModel.play(selected, index) }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${index + 1}.", modifier = Modifier.width(44.dp))
                            Text(item.title, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            item.durationSeconds?.let { Text(formatTime(it * 1000)) }
                        }
                    }
                }
            }
        }

        if (playing != null && track != null) {
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AlbumArt(playing, Modifier.size(56.dp))
                Column(Modifier.weight(1f)) {
                    Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${formatTime(state.positionMs)} / ${formatTime(state.durationMs)}",
                        style = MaterialTheme.typography.bodySmall)
                }
                TvIconButton(onClick = viewModel::previous, icon = R.drawable.ic_skip_previous, description = "Pista anterior")
                TvIconButton(
                    onClick = viewModel::togglePlay,
                    icon = if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                    description = if (state.isPlaying) "Pausar" else "Reproducir",
                )
                TvIconButton(onClick = viewModel::next, icon = R.drawable.ic_skip_next, description = "Pista siguiente")
            }
        }
    }
}

@Composable
private fun AlbumArt(album: MusicAlbum, modifier: Modifier = Modifier) {
    Box(modifier.background(Color(0xFF0F1116)), contentAlignment = Alignment.Center) {
        if (album.coverUrl != null) {
            AsyncImage(
                model = album.coverUrl,
                contentDescription = album.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(album.title, modifier = Modifier.padding(8.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
