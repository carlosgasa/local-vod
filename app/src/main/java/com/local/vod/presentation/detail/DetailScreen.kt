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
import androidx.compose.foundation.layout.widthIn
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
import com.local.vod.R
import com.local.vod.domain.model.Episode
import com.local.vod.domain.model.PlaybackProgress
import com.local.vod.domain.usecase.MarkWatched
import com.local.vod.presentation.formatDuration
import com.local.vod.presentation.formatTime
import com.local.vod.presentation.neonBackground
import com.local.vod.presentation.screenPadding
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

    // En pantallas estrechas queda más espacio para los botones con una carátula pequeña.
    val compact = LocalConfiguration.current.screenWidthDp < 900
    val posterWidth = if (compact) 110.dp else 220.dp

    Box(
        Modifier
            .fillMaxSize()
            .neonBackground(intensity = 0.7f)
            .screenPadding(),
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
                        if (movie != null) MovieActions(movie, state.progress[movie.id], viewModel, onPlay, compact)
                    } else {
                        val next = state.next
                        if (next != null) {
                            val (episode, startMs) = next
                            val label = if (startMs > 0) {
                                "Continuar T${episode.season}E${episode.number ?: 1} · ${formatTime(startMs)}"
                            } else {
                                "Ver T${episode.season}E${episode.number ?: 1}"
                            }
                            TvButton(
                                onClick = { onPlay(episode.id, startMs) },
                                icon = R.drawable.ic_play,
                                text = label,
                            )
                            Spacer(Modifier.height(16.dp))
                        }
                        SeasonEpisodes(title.seasons, state.progress, compact, ::open, viewModel::setWatched)
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
    compact: Boolean,
) {
    if (compact) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MoviePlayButtons(movie, progress, viewModel, onPlay)
            WatchedButton(movie, progress) { viewModel.setWatched(movie, progress) }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            MoviePlayButtons(movie, progress, viewModel, onPlay)
            WatchedButton(movie, progress) { viewModel.setWatched(movie, progress) }
        }
    }
}

@Composable
private fun MoviePlayButtons(
    movie: Episode,
    progress: PlaybackProgress?,
    viewModel: DetailViewModel,
    onPlay: (String, Long) -> Unit,
) {
    if (progress != null && progress.positionMs > 10_000 && progress.fraction < 0.95f) {
        TvButton(
            onClick = { onPlay(movie.id, progress.positionMs) },
            icon = R.drawable.ic_play,
            text = "Reanudar ${formatTime(progress.positionMs)}",
        )
        TvButton(
            onClick = { viewModel.restart(movie.id) { onPlay(movie.id, 0L) } },
            icon = R.drawable.ic_replay,
            text = "Empezar de nuevo",
        )
    } else {
        TvButton(onClick = { onPlay(movie.id, 0L) }, icon = R.drawable.ic_play, text = "Reproducir")
    }
}

@Composable
private fun SeasonEpisodes(
    seasons: List<com.local.vod.domain.model.Season>,
    progress: Map<String, PlaybackProgress>,
    compact: Boolean,
    onEpisode: (Episode) -> Unit,
    onToggleWatched: (Episode, PlaybackProgress?) -> Unit,
) {
    var selected by remember { mutableStateOf(seasons.firstOrNull()?.number ?: 1) }
    var hideWatched by remember { mutableStateOf(false) }
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
    TvButton(onClick = { hideWatched = !hideWatched }) {
        Text(if (hideWatched) "Ver todos" else "Ocultar vistos")
    }
    Spacer(Modifier.height(8.dp))
    val episodes = seasons.firstOrNull { it.number == selected }?.episodes.orEmpty()
        .filter { !hideWatched || (progress[it.id]?.fraction ?: 0f) < 0.95f }
    if (episodes.isEmpty() && hideWatched) {
        Text("No hay episodios pendientes")
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(episodes, key = { it.id }) { episode ->
            val saved = progress[episode.id]
            EpisodeRow(episode, saved, compact, { onEpisode(episode) }) { onToggleWatched(episode, saved) }
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: Episode,
    progress: PlaybackProgress?,
    compact: Boolean,
    onClick: () -> Unit,
    onToggleWatched: () -> Unit,
) {
    if (compact) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            EpisodeCard(episode, progress, Modifier.fillMaxWidth(), onClick)
            WatchedButton(episode, progress, onToggleWatched)
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            EpisodeCard(episode, progress, Modifier.weight(1f), onClick)
            WatchedButton(episode, progress, onToggleWatched)
        }
    }
}

@Composable
private fun WatchedButton(episode: Episode, progress: PlaybackProgress?, onClick: () -> Unit) {
    val watched = (progress?.fraction ?: 0f) >= 0.95f
    val canMark = MarkWatched.durationMs(episode, progress) != null
    TvButton(onClick = onClick, enabled = watched || canMark) {
        Text(
            when {
                watched -> "Marcar no visto"
                canMark -> "Marcar visto"
                else -> "Sin duración para marcar visto"
            },
            maxLines = 2,
        )
    }
}

@Composable
private fun EpisodeCard(episode: Episode, progress: PlaybackProgress?, modifier: Modifier, onClick: () -> Unit) {
    TvCard(onClick = onClick, modifier = modifier) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            // Los enlaces remotos no tienen miniatura: sin ella la fila sigue siendo solo texto.
            if (episode.thumbUrl != null) {
                Poster(episode.thumbUrl, episode.name, Modifier.width(96.dp).aspectRatio(16f / 9f))
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        episode.number?.let { "%02d".format(it) } ?: "\u2013",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        episode.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
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
                if (episode.durationSeconds != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        formatDuration(episode.durationSeconds),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
                // menos de 10 s no es "visto": no se pinta barra ni texto
                if (progress != null && progress.positionMs > 10_000) {
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
                        if (progress.fraction >= 0.95f) "\u2713 Visto" else "Visto hasta ${formatTime(progress.positionMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
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
        Surface(Modifier.widthIn(max = 640.dp)) {
            Column(Modifier.padding(24.dp)) {
                Text(episodeName, style = MaterialTheme.typography.titleLarge, maxLines = 2)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton(
                        onClick = onResume,
                        icon = R.drawable.ic_play,
                        text = "Reanudar ${formatTime(positionMs)}",
                        modifier = Modifier.focusRequester(focus),
                    )
                    TvButton(onClick = onRestart, icon = R.drawable.ic_replay, text = "Desde el inicio")
                }
            }
        }
    }
}
