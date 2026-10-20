package com.local.vod.presentation.player

import androidx.annotation.OptIn as AndroidXOptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import com.local.vod.domain.model.Channel
import com.local.vod.domain.model.ChannelGroup
import com.local.vod.presentation.tv.ChannelCard
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.windowInsetsPadding
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
import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.window.Dialog
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import com.local.vod.presentation.TvIconButton
import com.local.vod.presentation.formatTime
import java.util.Locale
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.local.vod.presentation.TvButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.local.vod.R
import com.local.vod.domain.model.Episode
import com.local.vod.domain.model.TitleList
import com.local.vod.domain.model.TitleLists
import kotlinx.coroutines.delay

/** A falta de este tiempo para terminar aparece el aviso de "siguiente episodio". */
private const val NEXT_PROMPT_MS = 25_000L
// Tras tantos episodios seguidos sin tocar nada, se pregunta si sigue alguien mirando.
private const val STILL_WATCHING_AFTER = 3
private const val SEEK_STEP_MS = 10_000L

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

    val lists by viewModel.lists.collectAsStateWithLifecycle(initialValue = TitleLists())
    val favoriteChannel = viewModel.liveUrl?.let { lists.contains(TitleList.CHANNELS, it) }
    val channelGroups by viewModel.channels.collectAsStateWithLifecycle()
    // el mosaico de canales: sigue abierto al cambiar de canal, para ir probando
    var channelsOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // La cola son los episodios del título: así el botón de siguiente y el salto automático
    // al final los hace Media3 por su cuenta.
    val player = remember(current) {
        // Si el aparato no sabe decodificar un audio (el Dolby de muchas películas en un
        // celular), entra FFmpeg por software en lugar de quedarse mudo.
        val renderers = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        ExoPlayer.Builder(context, renderers).build().apply {
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
    // Los datos de lo que se ve salen y se esconden con los controles, para no tapar la imagen.
    var controlsVisible by remember { mutableStateOf(true) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    var autoAdvances by remember { mutableIntStateOf(0) }
    var askStillWatching by remember { mutableStateOf(false) }
    // el aviso de "+10 s" del doble toque: lado (-1 o 1) y un contador para reiniciar la animación
    var seekHint by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var isPlaying by remember { mutableStateOf(true) }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    // qué lista de pistas está abierta: audio o subtítulos
    var trackDialog by remember { mutableStateOf<Int?>(null) }
    // cada pulsación lo sube: los controles se esconden solos a los 5 s sin tocar nada
    var interaction by remember { mutableIntStateOf(0) }
    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }

    fun showControls() {
        controlsVisible = true
        interaction++
    }

    fun seekBy(deltaMs: Long) {
        if (!player.isCurrentMediaItemSeekable) return
        val end = if (player.duration > 0) player.duration else Long.MAX_VALUE
        player.seekTo((player.currentPosition + deltaMs).coerceIn(0L, end))
        showControls()
    }

    LaunchedEffect(controlsVisible, interaction, isPlaying, trackDialog) {
        if (controlsVisible && isPlaying && trackDialog == null) {
            delay(5_000)
            controlsVisible = false
        }
    }
    // Con controles: el foco al botón de pausa. Sin ellos: a la pantalla, que recoge las flechas.
    LaunchedEffect(controlsVisible, channelsOpen) {
        if (channelsOpen) return@LaunchedEffect // el panel se queda con el foco
        runCatching { if (controlsVisible) playFocus.requestFocus() else rootFocus.requestFocus() }
    }

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

    // En el celular: girar a lo ancho o a lo alto con un botón, y la ventanita flotante (PiP)
    // solo cuando se pide; al minimizar la app el video se pausa como siempre.
    val activity = view.context as? ComponentActivity
    val isPhone = remember { !context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) }
    val canPip = remember {
        isPhone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var inPip by remember { mutableStateOf(false) }
    DisposableEffect(activity) {
        val listener = Consumer<PictureInPictureModeChangedInfo> { inPip = it.isInPictureInPictureMode }
        activity?.addOnPictureInPictureModeChangedListener(listener)
        onDispose {
            activity?.removeOnPictureInPictureModeChangedListener(listener)
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    LaunchedEffect(inPip) { if (inPip) controlsVisible = false }

    fun toggleOrientation() {
        activity?.requestedOrientation =
            if (landscape) ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        interaction++
    }

    fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity == null) return
        val size = player.videoSize
        val ratio = if (size.width > 0 && size.height > 0) Rational(size.width, size.height) else Rational(16, 9)
        // Android rechaza proporciones más extremas que 2.39:1; entonces, la ventana por defecto.
        runCatching { activity.enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(ratio).build()) }
            .recoverCatching { activity.enterPictureInPictureMode(PictureInPictureParams.Builder().build()) }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onTracksChanged(newTracks: Tracks) {
                tracks = newTracks
            }

            override fun onPlayerError(error: PlaybackException) {
                failure = error.friendlyMessage()
            }

            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                failure = null
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    autoAdvances += 1
                    if (autoAdvances >= STILL_WATCHING_AFTER) {
                        player.pause()
                        askStillWatching = true
                    }
                } else {
                    autoAdvances = 0
                }
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
            // En la ventanita flotante la app queda en pausa pero el video sigue; se detiene
            // cuando se cierra la ventanita (ON_STOP).
            val pip = activity?.isInPictureInPictureMode == true
            if ((event == Lifecycle.Event.ON_PAUSE && !pip) || event == Lifecycle.Event.ON_STOP) {
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

    val index = current.episodes.indexOfFirst { it.id == tick.mediaId }
    val next = current.episodes.getOrNull(index + 1)
    val remainingMs = if (tick.durationMs > 0) tick.durationMs - tick.positionMs else Long.MAX_VALUE
    val nextPromptVisible = failure == null && next != null && !cancelled && remainingMs in 0..NEXT_PROMPT_MS
    // con un aviso en pantalla, sus botones mandan: las flechas no se tocan
    val panelOpen = askStillWatching || failure != null || nextPromptVisible || trackDialog != null || channelsOpen

    BackHandler(enabled = controlsVisible && !panelOpen) { controlsVisible = false }
    BackHandler(enabled = channelsOpen) { channelsOpen = false }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            // El mando: los botones multimedia siempre; con los controles escondidos, izquierda
            // y derecha saltan 10 s y cualquier otra tecla los saca.
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                interaction++
                when (event.key) {
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                        if (player.isPlaying) player.pause() else player.play()
                        showControls()
                        true
                    }
                    Key.MediaFastForward -> { seekBy(SEEK_STEP_MS); true }
                    Key.MediaRewind -> { seekBy(-SEEK_STEP_MS); true }
                    Key.MediaNext -> { if (player.hasNextMediaItem()) player.seekToNextMediaItem(); true }
                    Key.MediaPrevious -> { player.seekToPrevious(); true }
                    else -> when {
                        controlsVisible || panelOpen -> false
                        event.key == Key.DirectionLeft -> { seekBy(-SEEK_STEP_MS); true }
                        event.key == Key.DirectionRight -> { seekBy(SEEK_STEP_MS); true }
                        event.key == Key.Back -> false
                        else -> { showControls(); true }
                    }
                }
            },
    ) {
        // PlayerView solo pinta el video y los subtítulos: los controles son nuestros,
        // pensados para el mando (todo enfocable) y para el dedo.
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    isFocusable = false
                    playerView = this
                }
            },
            // Mientras corre el video, ni el Fire TV ni el celular apagan la pantalla;
            // en pausa sí se deja, para que no se quede encendida toda la noche.
            // al cambiar de canal hay otro reproductor: la vista tiene que usar el nuevo
            update = {
                it.player = player
                it.keepScreenOn = isPlaying
            },
        )
        // en la ventanita flotante, solo el video
        if (inPip) return@Box

        // Con los controles escondidos, en el celular: doble toque a un lado salta 10 s,
        // un toque saca los controles como siempre. Con los controles fuera no se tapa nada.
        if (!controlsVisible && !askStillWatching) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(player) {
                        detectTapGestures(
                            onTap = { showControls() },
                            onDoubleTap = { offset ->
                                val side = if (offset.x < size.width / 2) -1 else 1
                                val target = player.currentPosition + side * SEEK_STEP_MS
                                player.seekTo(target.coerceIn(0L, maxOf(0L, player.duration)))
                                seekHint = side to ((seekHint?.second ?: 0) + 1)
                            },
                        )
                    },
            )
        }
        seekHint?.let { (side, count) ->
            LaunchedEffect(count) {
                delay(700)
                seekHint = null
            }
            Text(
                if (side < 0) "−10 s" else "+10 s",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                modifier = Modifier
                    .align(if (side < 0) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 56.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
        }

        val playing = current.episodes.getOrNull(index)
        if (controlsVisible && !panelOpen) {
            // tocar fuera de los botones los esconde
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
                    .pointerInput(Unit) { detectTapGestures(onTap = { controlsVisible = false }) },
            )
            PlayerControls(
                positionMs = tick.positionMs,
                durationMs = tick.durationMs,
                isPlaying = isPlaying,
                hasPrevious = index > 0,
                hasNext = next != null,
                hasAudioChoice = tracks.choices(C.TRACK_TYPE_AUDIO).size > 1,
                hasSubtitles = tracks.choices(C.TRACK_TYPE_TEXT).isNotEmpty(),
                favorite = favoriteChannel,
                onFavorite = { favoriteChannel?.let(viewModel::toggleFavoriteChannel); interaction++ },
                playFocus = playFocus,
                onPlayPause = { if (player.isPlaying) player.pause() else player.play(); interaction++ },
                onSeekBy = ::seekBy,
                onSeekTo = { player.seekTo(it); interaction++ },
                onPrevious = { player.seekToPreviousMediaItem() },
                onNext = { player.seekToNextMediaItem() },
                onAudio = { trackDialog = C.TRACK_TYPE_AUDIO },
                onSubtitles = { trackDialog = C.TRACK_TYPE_TEXT },
                onChannels = if (channelGroups.isNotEmpty()) ({ channelsOpen = true }) else null,
                landscape = landscape,
                onOrientation = if (isPhone) ::toggleOrientation else null,
                onPip = if (canPip) ::enterPip else null,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        if (controlsVisible && playing != null) {
            Column(
                Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.displayCutout)
                    .padding(horizontal = if (isPhone) 16.dp else 40.dp, vertical = 28.dp),
            ) {
                Text(
                    current.titleName.ifEmpty { playing.name },
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!current.isMovie) {
                    Text(
                        "T${playing.season}E${playing.number ?: 1} · ${playing.name}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

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

        if (askStillWatching) {
            StillWatchingPanel(
                onContinue = {
                    autoAdvances = 0
                    askStillWatching = false
                    player.play()
                },
                onExit = onBack,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        trackDialog?.let { type ->
            TrackDialog(
                type = type,
                choices = tracks.choices(type),
                subtitlesOff = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT),
                onPick = { choice ->
                    val params = player.trackSelectionParameters.buildUpon()
                    player.trackSelectionParameters = if (choice == null) {
                        params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                    } else {
                        params.setTrackTypeDisabled(type, false)
                            .setOverrideForType(TrackSelectionOverride(choice.group, choice.index))
                            .build()
                    }
                    trackDialog = null
                },
                onDismiss = { trackDialog = null },
            )
        }

        if (channelsOpen) {
            ChannelPanel(
                groups = channelGroups,
                favoriteIds = lists.channels,
                hiddenIds = lists.hidden.toSet(),
                currentUrl = viewModel.liveUrl,
                onPick = { failure = null; viewModel.switchChannel(it) },
                onClose = { channelsOpen = false },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }

        if (nextPromptVisible && next != null) {
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

/** Una pista que se puede elegir: su grupo, su posición en él y cómo llamarla. */
private data class TrackChoice(val group: TrackGroup, val index: Int, val label: String, val selected: Boolean)

@AndroidXOptIn(UnstableApi::class)
private fun Tracks.choices(type: Int): List<TrackChoice> = groups
    .filter { it.type == type }
    .flatMap { group ->
        (0 until group.length).filter { group.isTrackSupported(it) }.map { i ->
            TrackChoice(group.mediaTrackGroup, i, trackLabel(group.getTrackFormat(i)), group.isTrackSelected(i))
        }
    }

/** "Español · 5.1" a partir del idioma y los canales; si el archivo trae nombre, ese. */
private fun trackLabel(format: Format): String {
    val language = format.language?.let { code ->
        Locale.forLanguageTag(code).getDisplayLanguage(Locale("es")).replaceFirstChar { it.uppercase() }
            .takeIf { it.isNotBlank() && it != code }
    }
    val channels = when (format.channelCount) {
        1 -> "mono"
        2 -> "estéreo"
        6 -> "5.1"
        8 -> "7.1"
        else -> null
    }
    val base = format.label ?: language ?: "Pista"
    return listOfNotNull(base, channels?.takeIf { format.label == null }).joinToString(" · ")
}

@Composable
private fun PlayerControls(
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    hasAudioChoice: Boolean,
    hasSubtitles: Boolean,
    // solo en canales en vivo: null en películas y series
    favorite: Boolean?,
    onFavorite: () -> Unit,
    playFocus: FocusRequester,
    onPlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onAudio: () -> Unit,
    onSubtitles: () -> Unit,
    // solo en la TV en vivo, cuando ya llegó la lista de canales
    onChannels: (() -> Unit)?,
    landscape: Boolean,
    // solo en el celular: null en la tele
    onOrientation: (() -> Unit)?,
    onPip: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val live = durationMs <= 0
    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(horizontal = if (onOrientation != null) 16.dp else 40.dp, vertical = 24.dp),
    ) {
        if (live) {
            Text(
                "● EN VIVO",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleMedium,
            )
        } else {
            SeekBar(positionMs, durationMs, onSeekBy, onSeekTo)
            Spacer(Modifier.height(6.dp))
            Text(
                "${formatTime(positionMs)} / ${formatTime(durationMs)}",
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(12.dp))
        // Si no cabe todo en una línea (celular en vertical), los botones de la derecha bajan a otra.
        FlowRow(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasPrevious) TvIconButton(onClick = onPrevious, icon = R.drawable.ic_skip_previous, description = "Episodio anterior")
                if (!live) TvIconButton(onClick = { onSeekBy(-SEEK_STEP_MS) }, icon = R.drawable.ic_rewind, description = "Atrás 10 segundos")
                TvIconButton(
                    onClick = onPlayPause,
                    icon = if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                    description = if (isPlaying) "Pausa" else "Reproducir",
                    modifier = Modifier.focusRequester(playFocus),
                )
                if (!live) TvIconButton(onClick = { onSeekBy(SEEK_STEP_MS) }, icon = R.drawable.ic_forward, description = "Adelante 10 segundos")
                if (hasNext) TvIconButton(onClick = onNext, icon = R.drawable.ic_skip_next, description = "Siguiente episodio")
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onChannels != null) {
                    if (onOrientation != null) TvIconButton(onClick = onChannels, icon = R.drawable.ic_tv, description = "Canales")
                    else TvButton(onClick = onChannels, icon = R.drawable.ic_tv, text = "Canales")
                }
                if (favorite != null) {
                    val icon = if (favorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border
                    val label = if (favorite) "En favoritos" else "Favorito"
                    // en el celular solo el corazón: el texto no deja sitio a los demás botones
                    if (onOrientation != null) TvIconButton(onClick = onFavorite, icon = icon, description = label)
                    else TvButton(onClick = onFavorite, icon = icon, text = label)
                }
                if (hasAudioChoice) TvButton(onClick = onAudio) { Text("Audio") }
                if (hasSubtitles) TvButton(onClick = onSubtitles) { Text("Subtítulos") }
                if (onPip != null) TvIconButton(onClick = onPip, icon = R.drawable.ic_pip, description = "Ventana flotante")
                if (onOrientation != null) TvIconButton(
                    onClick = onOrientation,
                    icon = if (landscape) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen,
                    description = if (landscape) "Contraer" else "Expandir",
                )
            }
        }
    }
}

/** Barra de avance: con el mando, izquierda y derecha saltan 10 s; con el dedo, se toca o se arrastra. */
@Composable
private fun SeekBar(positionMs: Long, durationMs: Long, onSeekBy: (Long) -> Unit, onSeekTo: (Long) -> Unit) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val fraction = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .height(24.dp)
            .focusable(interactionSource = source)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { onSeekBy(-SEEK_STEP_MS); true }
                    Key.DirectionRight -> { onSeekBy(SEEK_STEP_MS); true }
                    else -> false
                }
            }
            .pointerInput(durationMs) {
                detectTapGestures { offset -> onSeekTo((offset.x / size.width * durationMs).toLong()) }
            }
            .pointerInput(durationMs) {
                detectHorizontalDragGestures { change, _ ->
                    onSeekTo((change.position.x / size.width).coerceIn(0f, 1f).times(durationMs).toLong())
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val barHeight = if (focused) 8.dp else 4.dp
        Box(Modifier.fillMaxWidth().height(barHeight).background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(4.dp)))
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(barHeight)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)),
        )
        // el punto que marca dónde vas: crece cuando la barra tiene el foco
        Box(Modifier.fillMaxWidth(fraction), contentAlignment = Alignment.CenterEnd) {
            Box(
                Modifier
                    .size(if (focused) 20.dp else 12.dp)
                    .background(Color.White, RoundedCornerShape(50)),
            )
        }
    }
}

/**
 * Mosaico de canales sobre el video, que sigue sonando detrás: favoritos primero y luego
 * cada categoría en su fila. Al abrirlo el foco cae en el canal que se está viendo.
 */
@Composable
private fun ChannelPanel(
    groups: List<ChannelGroup>,
    favoriteIds: List<String>,
    hiddenIds: Set<String>,
    currentUrl: String?,
    onPick: (Channel) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows = remember(groups, favoriteIds, hiddenIds) {
        val visible = groups.map { g -> g.copy(channels = g.channels.filter { it.id !in hiddenIds }) }.filter { it.channels.isNotEmpty() }
        val byId = groups.flatMap { it.channels }.associateBy { it.id }
        val favorites = favoriteIds.mapNotNull { byId[it] }
        listOfNotNull(favorites.takeIf { it.isNotEmpty() }?.let { ChannelGroup("♥ Mis favoritos", it) }) + visible
    }
    // la primera fila donde aparece el canal actual; si no está, la primera tarjeta
    val startRow = rows.indexOfFirst { g -> g.channels.any { it.url == currentUrl } }.coerceAtLeast(0)
    val startColumn = rows.getOrNull(startRow)?.channels?.indexOfFirst { it.url == currentUrl }?.coerceAtLeast(0) ?: 0
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val wide = LocalConfiguration.current.screenWidthDp >= 900
    Column(
        modifier
            .fillMaxWidth()
            .fillMaxHeight(if (wide) 0.6f else 0.75f)
            .background(Color.Black.copy(alpha = 0.88f))
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(horizontal = if (wide) 40.dp else 16.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Canales", style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.weight(1f))
            TvIconButton(onClick = onClose, icon = R.drawable.ic_close, description = "Cerrar")
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            state = rememberLazyListState(initialFirstVisibleItemIndex = startRow),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(rows, key = { _, g -> g.name }) { r, group ->
                Column {
                    Text(group.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    LazyRow(
                        state = rememberLazyListState(initialFirstVisibleItemIndex = if (r == startRow) startColumn else 0),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        itemsIndexed(group.channels, key = { _, c -> c.id }) { c, channel ->
                            ChannelCard(
                                channel,
                                modifier = if (r == startRow && c == startColumn) Modifier.focusRequester(focus) else Modifier,
                            ) { onPick(channel) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackDialog(
    type: Int,
    choices: List<TrackChoice>,
    subtitlesOff: Boolean,
    onPick: (TrackChoice?) -> Unit,
    onDismiss: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val subtitles = type == C.TRACK_TYPE_TEXT
    // la opción marcada es la que tiene el foco al abrir
    val selectedIndex = when {
        subtitles && (subtitlesOff || choices.none { it.selected }) -> -1
        else -> choices.indexOfFirst { it.selected }.coerceAtLeast(0)
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(24.dp).widthIn(min = 280.dp).verticalScroll(rememberScrollState())) {
                Text(if (subtitles) "Subtítulos" else "Audio", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))
                if (subtitles) {
                    TvButton(
                        onClick = { onPick(null) },
                        modifier = Modifier.fillMaxWidth().let { if (selectedIndex == -1) it.focusRequester(focus) else it },
                    ) { Text(if (selectedIndex == -1) "✓  Desactivados" else "Desactivados") }
                    Spacer(Modifier.height(8.dp))
                }
                choices.forEachIndexed { i, choice ->
                    TvButton(
                        onClick = { onPick(choice) },
                        modifier = Modifier.fillMaxWidth().let { if (i == selectedIndex) it.focusRequester(focus) else it },
                    ) { Text(if (i == selectedIndex) "✓  ${choice.label}" else choice.label) }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun StillWatchingPanel(onContinue: () -> Unit, onExit: () -> Unit, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(
        modifier
            .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("¿Sigues ahí?", style = MaterialTheme.typography.headlineSmall, color = Color.White)
        Spacer(Modifier.height(6.dp))
        Text(
            "Llevas varios episodios seguidos. Lo dejamos en pausa por si te quedaste dormido.",
            color = Color.White.copy(alpha = 0.75f),
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(
                onClick = onContinue,
                icon = R.drawable.ic_play,
                text = "Seguir viendo",
                primary = true,
                modifier = Modifier.focusRequester(focus),
            )
            TvButton(onClick = onExit) { Text("Salir") }
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
    // muchos canales en vivo son HLS aunque la dirección no acabe en .m3u8
    .apply { if (url.contains(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8) }
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
