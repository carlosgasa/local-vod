package com.local.vod.presentation.detail

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.local.vod.presentation.TvButton
import com.local.vod.presentation.TvCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.local.vod.domain.model.Episode
import com.local.vod.domain.model.PlaybackProgress
import com.local.vod.presentation.formatTime
import com.local.vod.presentation.home.Poster
import androidx.compose.runtime.LaunchedEffect

@Composable
fun DetailScreen(
    onPlay: (episodeId: String, startMs: Long) -> Unit,
    onBack: () -> Unit,
    viewModel: DetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var asking by remember { mutableStateOf<Episode?>(null) }
    val title = state.title

    fun open(episode: Episode) {
        val saved = state.progress[episode.id]
        // Terminado (>=95 %) no se "reanuda": empieza de cero como cualquier otro.
        val resumable = saved != null && saved.positionMs > 10_000 && saved.fraction < 0.95f
        if (resumable) asking = episode else onPlay(episode.id, 0L)
    }

    // En un móvil en vertical no sobra ancho: carátula pequeña y menos margen.
    val compact = LocalConfiguration.current.screenWidthDp < 600
    val posterWidth = if (compact) 110.dp else 220.dp

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(if (compact) 16.dp else 32.dp),
    ) {
        when {
            state.error != null -> Text("Error: ${state.error}")
            title == null -> Text("Cargando…")
            else -> Row {
                Column(Modifier.width(posterWidth)) {
                    Poster(title.posterUrl, title.name, Modifier.width(posterWidth).aspectRatio(2f / 3f))
                }
                Spacer(Modifier.width(if (compact) 16.dp else 32.dp))
                Column(Modifier.fillMaxSize()) {
                    Text(title.name, style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (title.isMovie) "Película" else "Serie · ${title.seasons.size} temporada(s)",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.height(20.dp))

                    if (title.isMovie) {
                        val movie = title.episodes.firstOrNull()
                        if (movie != null) MovieActions(movie, state.progress[movie.id], viewModel, onPlay)
                    } else {
                        SeasonEpisodes(title.seasons, state.progress, ::open)
                    }
                }
            }
        }
    }

    val episode = asking
    if (episode != null) {
        val saved = state.progress[episode.id]
        ResumeDialog(
            episodeName = episode.name,
            positionMs = saved?.positionMs ?: 0L,
            onResume = { asking = null; onPlay(episode.id, saved?.positionMs ?: 0L) },
            onRestart = { asking = null; viewModel.restart(episode.id) { onPlay(episode.id, 0L) } },
            onDismiss = { asking = null },
        )
    }
}

@Composable
private fun MovieActions(
    movie: Episode,
    progress: PlaybackProgress?,
    viewModel: DetailViewModel,
    onPlay: (String, Long) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (progress != null && progress.positionMs > 10_000 && progress.fraction < 0.95f) {
            TvButton(onClick = { onPlay(movie.id, progress.positionMs) }) {
                Text("Reanudar ${formatTime(progress.positionMs)}")
            }
            TvButton(onClick = { viewModel.restart(movie.id) { onPlay(movie.id, 0L) } }) {
                Text("Empezar de nuevo")
            }
        } else {
            TvButton(onClick = { onPlay(movie.id, 0L) }) { Text("Reproducir") }
        }
    }
}

@Composable
private fun SeasonEpisodes(
    seasons: List<com.local.vod.domain.model.Season>,
    progress: Map<String, PlaybackProgress>,
    onEpisode: (Episode) -> Unit,
) {
    var selected by remember { mutableStateOf(seasons.firstOrNull()?.number ?: 1) }
    if (seasons.size > 1) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(seasons, key = { it.number }) { season ->
                TvButton(onClick = { selected = season.number }) {
                    Text(if (season.number == selected) "▍Temporada ${season.number}" else "Temporada ${season.number}")
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
    val episodes = seasons.firstOrNull { it.number == selected }?.episodes ?: emptyList()
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(episodes, key = { it.id }) { episode ->
            EpisodeRow(episode, progress[episode.id]) { onEpisode(episode) }
        }
    }
}

@Composable
private fun EpisodeRow(episode: Episode, progress: PlaybackProgress?, onClick: () -> Unit) {
    TvCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    episode.number?.let { "%02d".format(it) } ?: "–",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(16.dp))
                Text(
                    episode.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (episode.subs.isNotEmpty()) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "SUB " + episode.subs.joinToString("/") { it.lang },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
            }
            if (progress != null && progress.fraction > 0f) {
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color(0xFF2B3039)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(progress.fraction)
                            .height(4.dp)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "visto hasta ${formatTime(progress.positionMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
private fun ResumeDialog(
    episodeName: String,
    positionMs: Long,
    onResume: () -> Unit,
    onRestart: () -> Unit,
    onDismiss: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Dialog(onDismissRequest = onDismiss) {
        Surface(Modifier.width(520.dp)) {
            Column(Modifier.padding(24.dp)) {
                Text(episodeName, style = MaterialTheme.typography.titleLarge, maxLines = 2)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton(onClick = onResume, modifier = Modifier.focusRequester(focus)) {
                        Text("Reanudar ${formatTime(positionMs)}")
                    }
                    TvButton(onClick = onRestart) { Text("Empezar de nuevo") }
                }
            }
        }
    }
}
