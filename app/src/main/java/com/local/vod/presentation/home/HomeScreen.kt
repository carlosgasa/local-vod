package com.local.vod.presentation.home

import androidx.annotation.OptIn as AndroidXOptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.local.vod.presentation.TvButton
import com.local.vod.presentation.neonBackground
import com.local.vod.presentation.screenPadding
import com.local.vod.presentation.TvIconButton
import com.local.vod.presentation.TvCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import coil3.compose.AsyncImage
import com.local.vod.R
import com.local.vod.domain.model.ContinueItem
import com.local.vod.domain.model.Title
import com.local.vod.presentation.formatTime
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    onTitleClick: (Title) -> Unit,
    onContinueClick: (ContinueItem) -> Unit,
    onSetupClick: () -> Unit,
    onProfileClick: () -> Unit,
    onPhotosClick: () -> Unit,
    onMusicClick: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val previewsEnabled by viewModel.previewsEnabled.collectAsStateWithLifecycle(initialValue = true)
    val lifecycleOwner = LocalLifecycleOwner.current
    var foreground by remember { mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    var activePreview by remember { mutableStateOf<Any?>(null) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) foreground = true
            if (event == Lifecycle.Event.ON_STOP) {
                foreground = false
                activePreview = null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val visibleTitles = state.visibleTitles
    val recentTitles = state.recentTitles
    val searchFieldFocus = remember { FocusRequester() }
    val firstResultFocus = remember { FocusRequester() }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    // Sin perfil elegido no hay nada que enseñar: se va directo a "¿quién está viendo?"
    LaunchedEffect(state.needsProfile, state.needsSetup) {
        if (state.needsProfile && !state.needsSetup) onProfileClick()
    }

    Column(
        Modifier
            .fillMaxSize()
            .neonBackground()
            .screenPadding(),
    ) {
        val controls: @Composable () -> Unit = {
            TvIconButton(
                onClick = viewModel::toggleSearch,
                icon = R.drawable.ic_search,
                description = if (state.searchOpen) "Cerrar búsqueda" else "Buscar títulos",
            )
            Spacer(Modifier.width(8.dp))
            TvIconButton(
                onClick = onPhotosClick,
                icon = R.drawable.ic_photos,
                description = "Galería de fotos",
            )
            Spacer(Modifier.width(8.dp))
            TvIconButton(
                onClick = onMusicClick,
                icon = R.drawable.ic_music,
                description = "Música",
            )
            Spacer(Modifier.width(8.dp))
            TvIconButton(
                onClick = { viewModel.load(refresh = true) },
                icon = R.drawable.ic_refresh,
                description = "Volver a cargar la biblioteca",
            )
            Spacer(Modifier.width(8.dp))
            TvIconButton(
                onClick = onSetupClick,
                icon = R.drawable.ic_server,
                description = "Servidor",
            )
            Spacer(Modifier.width(8.dp))
            TvIconButton(
                onClick = onProfileClick,
                icon = R.drawable.ic_person,
                description = state.profileName?.let { "Perfil: $it" } ?: "Elegir perfil",
            )
        }
        // Título a la izquierda y los controles pegados a la derecha, como en cualquier tele.
        // En un celular no caben seis botones al lado del nombre: bajan a su propia fila.
        if (LocalConfiguration.current.screenWidthDp >= 600) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Local VOD",
                    style = MaterialTheme.typography.headlineMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                controls()
            }
        } else {
            Text("Local VOD", style = MaterialTheme.typography.headlineMedium, maxLines = 1)
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth(),
            ) { controls() }
        }
        if (state.searchOpen) {
            Spacer(Modifier.height(12.dp))
            LaunchedEffect(Unit) { searchFieldFocus.requestFocus() }
            BasicTextField(
                value = state.searchQuery,
                onValueChange = viewModel::onSearchQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 20.sp),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { innerTextField ->
                    Box {
                        if (state.searchQuery.isEmpty()) {
                            Text("Buscar por nombre", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                        innerTextField()
                    }
                },
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth()
                    .focusRequester(searchFieldFocus)
                    // El campo consume las flechas del mando; bajar entra en los resultados.
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown &&
                            event.key == Key.DirectionDown && visibleTitles.isNotEmpty()
                        ) {
                            scope.launch {
                                val firstResultIndex = 1 + if (state.isSearching) 0 else {
                                    (if (state.continueItems.isNotEmpty()) 1 else 0) +
                                        (if (recentTitles.isNotEmpty()) 1 else 0)
                                }
                                gridState.scrollToItem(firstResultIndex)
                                firstResultFocus.requestFocus()
                            }
                            true
                        } else {
                            false
                        }
                    }
                    .background(MaterialTheme.colorScheme.surface)
                    .border(2.dp, MaterialTheme.colorScheme.primary)
                    .padding(16.dp),
            )
        }
        if (state.serverDown) {
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF3A2E12), RoundedCornerShape(10.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "El servidor dejó de responder. Lo que ya está en pantalla sigue, pero no se podrá reproducir.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFF6C445),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                TvButton(onClick = { viewModel.load(refresh = true) }, icon = R.drawable.ic_refresh, text = "Reintentar")
            }
        }

        Spacer(Modifier.height(16.dp))

        when {
            state.needsSetup -> Message(
                "Falta configurar el servidor.\nPulsa «Servidor» y escribe la dirección que muestra el dashboard de la PC.",
            )

            state.error != null -> Message(
                "No se pudo conectar con el servidor:\n${state.error}",
                actionLabel = "Reintentar",
                actionIcon = R.drawable.ic_refresh,
                onAction = { viewModel.load(refresh = true) },
            )

            state.loading && state.titles.isEmpty() -> Message("Cargando biblioteca…")

            state.titles.isEmpty() -> Message(
                "La biblioteca está vacía.\nCopia carpetas con videos en la carpeta media de la PC.",
            )

            else -> {
                // Todo en una sola rejilla: así "Seguir viendo" y la biblioteca
                // se desplazan juntos y nada se queda fuera de pantalla.
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(160.dp),
                    state = gridState,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (!state.isSearching && state.continueItems.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column {
                                Text("Seguir viendo", style = MaterialTheme.typography.titleLarge)
                                Spacer(Modifier.height(8.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    items(state.continueItems, key = { it.episode.id }) { item ->
                                        ContinueCard(item) { onContinueClick(item) }
                                    }
                                }
                            }
                        }
                    }
                    if (!state.isSearching && recentTitles.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column {
                                Text("Añadido recientemente", style = MaterialTheme.typography.titleLarge)
                                Spacer(Modifier.height(8.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    items(recentTitles, key = { it.id }) { title ->
                                        TitleCard(title, previewsEnabled, foreground, activePreview,
                                            { activePreview = it }) { onTitleClick(title) }
                                    }
                                }
                            }
                        }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            if (state.isSearching) "Resultados" else "Biblioteca",
                            style = MaterialTheme.typography.titleLarge,
                        )
                    }
                    if (visibleTitles.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) { Text("No se encontraron títulos.") }
                    }
                    itemsIndexed(visibleTitles, key = { _, title -> title.id }) { index, title ->
                        TitleCard(
                            title,
                            previewsEnabled,
                            foreground,
                            activePreview,
                            { activePreview = it },
                            modifier = if (index == 0) Modifier.focusRequester(firstResultFocus) else Modifier,
                        ) { onTitleClick(title) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(
    text: String,
    actionLabel: String? = null,
    actionIcon: Int? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(20.dp))
                if (actionIcon != null) {
                    TvButton(onClick = onAction, icon = actionIcon, text = actionLabel)
                } else {
                    TvButton(onClick = onAction) { Text(actionLabel) }
                }
            }
        }
    }
}

@Composable
fun Poster(url: String?, name: String, modifier: Modifier = Modifier) {
    Box(modifier.background(Color(0xFF0F1116)), contentAlignment = Alignment.Center) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}

@Composable
private fun TitleCard(
    title: Title,
    previewsEnabled: Boolean,
    foreground: Boolean,
    activePreview: Any?,
    setActivePreview: (Any?) -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    val owner = remember { Any() }
    val currentActive by rememberUpdatedState(activePreview)
    val eligible = (focused || hovered || pressed) && previewsEnabled && foreground && title.previewUrl != null
    var ready by remember { mutableStateOf(false) }
    DisposableEffect(eligible) {
        if (eligible) setActivePreview(owner)
        onDispose { if (currentActive === owner) setActivePreview(null) }
    }
    LaunchedEffect(eligible, title.previewUrl) {
        ready = false
        if (eligible) {
            delay(5_000)
            ready = true
        }
    }
    Column {
        TvCard(onClick = onClick, modifier = modifier.width(160.dp), interactionSource = source) {
            Box(Modifier.width(160.dp).aspectRatio(2f / 3f)) {
                val showPreview = ready && eligible && activePreview === owner
                var videoReady by remember { mutableStateOf(false) }
                if (showPreview) {
                    PreviewVideo(title.previewUrl, onReady = { videoReady = it })
                }
                val posterAlpha by animateFloatAsState(if (showPreview && videoReady) 0f else 1f, label = "Adelanto")
                Poster(title.posterUrl, title.name, Modifier.fillMaxSize().alpha(posterAlpha))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            title.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(160.dp),
        )
    }
}

@Composable
@AndroidXOptIn(UnstableApi::class)
private fun PreviewVideo(url: String, onReady: (Boolean) -> Unit) {
    val context = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            setMediaItem(MediaItem.fromUri(url))
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) onReady(true)
            }
        }
        player.addListener(listener)
        if (player.playbackState == Player.STATE_READY) onReady(true)
        onDispose {
            onReady(false)
            player.removeListener(listener)
            player.release()
        }
    }
    AndroidView(
        factory = {
            PlayerView(it).apply {
                useController = false
                isFocusable = false
                isClickable = false
                this.player = player
                // El adelanto es 16:9 y la carátula 2:3: recortar es mejor que dejar franjas negras.
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun ContinueCard(item: ContinueItem, onClick: () -> Unit) {
    Column {
        TvCard(onClick = onClick, modifier = Modifier.width(260.dp)) {
            Column {
                Poster(item.title.posterUrl, item.title.name, Modifier.width(260.dp).height(146.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color(0xFF2B3039)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(item.progress.fraction)
                            .height(4.dp)
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (item.title.isMovie) item.title.name else "${item.title.name} · ${item.episode.name}",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(260.dp),
        )
        Text(
            "vas por ${formatTime(item.progress.positionMs)} de ${formatTime(item.progress.durationMs)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}
