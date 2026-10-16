package com.local.vod.presentation.player

import androidx.annotation.OptIn as AndroidXOptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import android.app.Activity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.local.vod.presentation.TvButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.local.vod.R
import com.local.vod.domain.model.Episode
import kotlinx.coroutines.delay

/** A falta de este tiempo para terminar aparece el aviso de "siguiente episodio". */
private const val NEXT_PROMPT_MS = 25_000L

/** Lo último que se vio del reproductor: para la cuenta atrás y para guardar el progreso. */
private data class Tick(val mediaId: String? = null, val positionMs: Long = 0L, val durationMs: Long = 0L)

@AndroidXOptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val playlist by viewModel.playlist.collectAsStateWithLifecycle()
    val current = playlist

    if (current == null) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text("Cargando…", color = Color.White)
        }
        return
    }

    val context = LocalContext.current
    // La cola son los episodios del título: así el botón de siguiente y el salto automático
    // al final los hace Media3 por su cuenta.
    val player = remember(current) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItems(
                current.episodes.map { it.toMediaItem() },
                current.startIndex,
                if (viewModel.startMs > 0) viewModel.startMs else C.TIME_UNSET,
            )
            // Que un archivo traiga subtítulos marcados como "por defecto" no significa que
            // se quieran siempre: se ignora esa marca y quedan a un clic en el botón CC.
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build()
            playWhenReady = true
            prepare()
        }
    }

    var tick by remember { mutableStateOf(Tick()) }
    var cancelled by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(player) {
        while (true) {
            player.currentMediaItem?.mediaId?.let { id ->
                tick = Tick(id, player.currentPosition, player.duration)
            }
            delay(500)
        }
    }

    // Pantalla completa de verdad: en el móvil las barras tapaban los controles del reproductor.
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        val bars = window?.let { WindowCompat.getInsetsController(it, view) }
        bars?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { bars?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                failure = error.friendlyMessage()
            }

            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                failure = null
                // Salto (manual o automático): el anterior queda como visto.
                val previous = tick
                if (previous.mediaId != null && previous.durationMs > 0) {
                    viewModel.save(previous.mediaId, previous.durationMs, previous.durationMs)
                }
                cancelled = false
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    val id = player.currentMediaItem?.mediaId
                    if (id != null && player.duration > 0) viewModel.save(id, player.duration, player.duration)
                    onBack()
                }
            }
        }
        player.addListener(listener)

        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                player.pause()
                player.currentMediaItem?.mediaId?.let { viewModel.save(it, player.currentPosition, player.duration) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            player.currentMediaItem?.mediaId?.let { viewModel.save(it, player.currentPosition, player.duration) }
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.removeListener(listener)
            player.release()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // PlayerView trae el manejo del mando, los botones de anterior/siguiente episodio
        // y el menú de pistas de audio y subtítulos.
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    setShowSubtitleButton(true)
                    setShowNextButton(true)
                    setShowPreviousButton(true)
                    controllerShowTimeoutMs = 8_000
                    requestFocus()
                }
            },
        )

        val index = current.episodes.indexOfFirst { it.id == tick.mediaId }
        val next = current.episodes.getOrNull(index + 1)
        val remainingMs = if (tick.durationMs > 0) tick.durationMs - tick.positionMs else Long.MAX_VALUE

        if (failure != null) {
            PlaybackErrorPanel(
                message = failure!!,
                episodeName = current.episodes.getOrNull(index)?.name.orEmpty(),
                next = next,
                onRetry = { failure = null; player.prepare() },
                onNext = { failure = null; player.seekToNextMediaItem(); player.prepare() },
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
        }

        if (failure == null && next != null && !cancelled && remainingMs in 0..NEXT_PROMPT_MS) {
            NextEpisodePrompt(
                next = next,
                secondsLeft = (remainingMs / 1000).toInt(),
                onPlayNow = { player.seekToNextMediaItem() },
                onCancel = {
                    cancelled = true
                    // Sin cola detrás, al terminar este episodio no salta a ningún sitio.
                    player.removeMediaItems(player.currentMediaItemIndex + 1, player.mediaItemCount)
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(40.dp),
            )
        }
    }
}

@Composable
private fun PlaybackErrorPanel(
    message: String,
    episodeName: String,
    next: Episode?,
    onRetry: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Surface(modifier.width(560.dp)) {
        Column(Modifier.padding(24.dp)) {
            Text("No se pudo reproducir", style = MaterialTheme.typography.titleLarge, color = Color(0xFFF6C445))
            Spacer(Modifier.height(4.dp))
            Text(episodeName, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))
            Text(message, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                "Con el botón ATRÁS del mando vuelves a la lista.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton(
                    onClick = onRetry,
                    icon = R.drawable.ic_refresh,
                    text = "Reintentar",
                    modifier = Modifier.focusRequester(focus),
                )
                if (next != null) {
                    TvButton(onClick = onNext, icon = R.drawable.ic_skip_next, text = "Siguiente episodio")
                }
            }
        }
    }
}

/** Traduce el código de Media3 a algo que se entienda desde el sofá. */
private fun PlaybackException.friendlyMessage(): String = when (errorCode) {
    in 2000..2999 -> "No se pudo leer el archivo desde la PC. Comprueba que el servidor siga encendido."
    in 3000..3999 -> "El archivo está dañado o tiene un formato que no se puede leer."
    in 4000..4999 -> "Este aparato no puede decodificar el vídeo o el audio de este archivo."
    else -> "Fallo de reproducción (código $errorCode)."
}

@Composable
private fun NextEpisodePrompt(
    next: Episode,
    secondsLeft: Int,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Surface(modifier.width(420.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text("Siguiente episodio", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text(next.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton(
                    onClick = onPlayNow,
                    icon = R.drawable.ic_skip_next,
                    text = if (secondsLeft > 0) "Ver ahora ($secondsLeft)" else "Ver ahora",
                    modifier = Modifier.focusRequester(focus),
                )
                TvButton(onClick = onCancel, icon = R.drawable.ic_close, text = "Cancelar")
            }
        }
    }
}

private fun Episode.toMediaItem(): MediaItem = MediaItem.Builder()
    .setUri(url)
    .setMediaId(id)
    .setSubtitleConfigurations(
        subs.map { sub ->
            MediaItem.SubtitleConfiguration.Builder(sub.url.toUri())
                .setMimeType(subtitleMimeType(sub.url))
                .setLanguage(sub.lang)
                .setLabel("${sub.lang} (archivo)")
                .build()
        },
    )
    .build()

private fun subtitleMimeType(url: String): String = when {
    url.endsWith(".vtt", ignoreCase = true) -> MimeTypes.TEXT_VTT
    url.endsWith(".ass", ignoreCase = true) || url.endsWith(".ssa", ignoreCase = true) -> MimeTypes.TEXT_SSA
    else -> MimeTypes.APPLICATION_SUBRIP
}
