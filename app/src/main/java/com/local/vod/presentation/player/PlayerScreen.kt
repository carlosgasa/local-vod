package com.local.vod.presentation.player

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import com.local.vod.domain.model.Episode

@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val episode by viewModel.episode.collectAsStateWithLifecycle()
    val current = episode

    if (current == null) {
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text("Cargando…", color = Color.White)
        }
        return
    }

    val context = LocalContext.current
    val player = remember(current.id) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(current.toMediaItem())
            if (viewModel.startMs > 0) seekTo(viewModel.startMs)
            playWhenReady = true
            prepare()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    viewModel.save(player.duration, player.duration) // marcado como visto
                    onBack()
                }
            }
        }
        player.addListener(listener)

        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                player.pause()
                viewModel.save(player.currentPosition, player.duration)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            viewModel.save(player.currentPosition, player.duration)
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.removeListener(listener)
            player.release()
        }
    }

    // PlayerView ya trae el manejo del mando y el menú de pistas de audio/subtítulos.
    AndroidView(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                setShowSubtitleButton(true)
                setShowNextButton(false)
                setShowPreviousButton(false)
                controllerShowTimeoutMs = 4_000
                requestFocus()
            }
        },
    )
}

private fun Episode.toMediaItem(): MediaItem = MediaItem.Builder()
    .setUri(url)
    .setMediaId(id)
    .setSubtitleConfigurations(
        subs.map { sub ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
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
