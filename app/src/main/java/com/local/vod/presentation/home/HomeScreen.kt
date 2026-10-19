package com.local.vod.presentation.home

import androidx.annotation.OptIn as AndroidXOptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import com.local.vod.presentation.formatDuration
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
import com.local.vod.presentation.appBackground
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
    onPlay: (episodeId: String, startMs: Long) -> Unit,
    onSetupClick: () -> Unit,
    onUploadClick: () -> Unit,
    onProfileClick: () -> Unit,
    onPhotosClick: () -> Unit,
    onMusicClick: () -> Unit,
    onLiveTvClick: () -> Unit,
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
    val wide = LocalConfiguration.current.screenWidthDp >= 900
    val posterWidth = if (wide) 160.dp else 120.dp
    val progressByTitle = state.continueItems.associate { it.title.id to it.progress.fraction }

    // "Reproducir" del destacado: sigue donde se quedó; una película desde el inicio;
    // una serie abre su ficha para elegir episodio.
    fun play(title: Title) {
        val pending = state.continueItems.firstOrNull { it.title.id == title.id }
        val movie = title.episodes.firstOrNull()
        when {
            pending != null -> onContinueClick(pending)
            title.isMovie && movie != null -> onPlay(movie.id, 0L)
            else -> onTitleClick(title)
        }
    }

    // Sin perfil elegido no hay nada que enseñar: se va directo a "¿quién está viendo?"
    LaunchedEffect(state.needsProfile, state.needsSetup) {
        if (state.needsProfile && !state.needsSetup) onProfileClick()
    }

    Column(
        Modifier
            .fillMaxSize()
            .appBackground()
            .screenPadding(),
    ) {
        val controls: @Composable () -> Unit = {
            if (state.titles.size > 1) {
                TvIconButton(
                    onClick = { onTitleClick(state.titles.random()) },
                    icon = R.drawable.ic_shuffle,
                    description = "Sorpréndeme",
                )
                Spacer(Modifier.width(4.dp))
            }
            TvIconButton(
                onClick = viewModel::toggleSearch,
                icon = R.drawable.ic_search,
                description = if (state.searchOpen) "Cerrar búsqueda" else "Buscar títulos",
            )
            Spacer(Modifier.width(8.dp))
            TvIconButton(
                onClick = onLiveTvClick,
                icon = R.drawable.ic_tv,
                description = "TV en vivo",
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
                onClick = onUploadClick,
                icon = R.drawable.ic_add,
                description = "Subir archivos",
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
        val brand: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier) {
                Text(
                    "LOCAL VOD",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
                state.profileName?.let { name ->
                    Text(
                        "${greeting()}, $name",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (LocalConfiguration.current.screenWidthDp >= 600) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                brand(Modifier.weight(1f))
                controls()
            }
        } else {
            brand(Modifier)
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
                        // sin texto no hay rejilla de resultados a la que bajar: las flechas siguen solas
                        if (event.type == KeyEventType.KeyDown && state.isSearching &&
                            event.key == Key.DirectionDown && visibleTitles.isNotEmpty()
                        ) {
                            scope.launch {
                                gridState.scrollToItem(1) // el 0 es el encabezado "Resultados"
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

            state.isSearching -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(posterWidth),
                    state = gridState,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text("Resultados", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                    if (visibleTitles.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) { Text("No se encontraron títulos.") }
                    }
                    itemsIndexed(visibleTitles, key = { _, title -> title.id }) { index, title ->
                        TitleCard(
                            title,
                            posterWidth,
                            progressByTitle[title.id],
                            previewsEnabled,
                            foreground,
                            activePreview,
                            { activePreview = it },
                            modifier = if (index == 0) Modifier.focusRequester(firstResultFocus) else Modifier,
                        ) { onTitleClick(title) }
                    }
                }
            }

            else -> {
                // Como en cualquier app de streaming: un destacado grande y filas que se deslizan de lado.
                LazyColumn(verticalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.fillMaxSize()) {
                    if (state.heroTitles.isNotEmpty()) {
                        item(key = "hero") {
                            Hero(
                                titles = state.heroTitles,
                                continueItems = state.continueItems,
                                onPlay = { title -> play(title) },
                                onInfo = onTitleClick,
                            )
                        }
                    }
                    if (state.continueItems.isNotEmpty()) {
                        item(key = "continue") {
                            Column {
                                RowHeader("Seguir viendo")
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    items(state.continueItems, key = { it.episode.id }) { item ->
                                        ContinueCard(item) { onContinueClick(item) }
                                    }
                                }
                            }
                        }
                    }
                    fun titleRow(label: String, titles: List<Title>) {
                        if (titles.isEmpty()) return
                        item(key = label) {
                            Column {
                                RowHeader(label)
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    items(titles, key = { it.id }) { title ->
                                        TitleCard(title, posterWidth, progressByTitle[title.id], previewsEnabled,
                                            foreground, activePreview, { activePreview = it }) { onTitleClick(title) }
                                    }
                                }
                            }
                        }
                    }
                    titleRow("Ver después", state.laterTitles)
                    titleRow("Favoritos", state.favoriteTitles)
                    titleRow("Añadido recientemente", recentTitles)
                    titleRow("Películas", state.titles.filter { it.isMovie })
                    titleRow("Series", state.titles.filter { !it.isMovie })
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
/**
 * La carátula, y si no hay: la imagen de [fallbackUrl] (el mosaico de fotogramas) con un
 * difuminado encima. Sin ninguna imagen, un degradado con el título para saber qué es.
 */
fun Poster(url: String?, name: String, modifier: Modifier = Modifier, fallbackUrl: String? = null) {
    Box(
        modifier.background(Brush.verticalGradient(listOf(Color(0xFF2A2A33), Color(0xFF141418)))),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            if (fallbackUrl != null) {
                AsyncImage(
                    model = fallbackUrl,
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f)))),
            )
            if (fallbackUrl == null) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }
    }
}

@Composable
private fun TitleCard(
    title: Title,
    width: Dp,
    progress: Float?,
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
    // Sin texto debajo, como en las apps de streaming: el título ya va en la carátula.
    // Sin carátula, el mosaico no dice qué es: entonces sí lleva el nombre debajo.
    Column(modifier.width(width)) {
        TvCard(onClick = onClick, modifier = Modifier.width(width), interactionSource = source) {
            Box(Modifier.width(width).aspectRatio(2f / 3f)) {
                val showPreview = ready && eligible && activePreview === owner
                var videoReady by remember { mutableStateOf(false) }
                if (showPreview) {
                    PreviewVideo(title.previewUrl, onReady = { videoReady = it })
                }
                val posterAlpha by animateFloatAsState(if (showPreview && videoReady) 0f else 1f, label = "Adelanto")
                Poster(
                    title.posterUrl,
                    title.name,
                    Modifier.fillMaxSize().alpha(posterAlpha),
                    fallbackUrl = title.mosaicUrl ?: title.thumbUrl,
                )
                if (progress != null) ProgressBar(progress, Modifier.align(Alignment.BottomCenter))
            }
        }
        if (title.posterUrl == null) {
            Spacer(Modifier.height(6.dp))
            Text(
                title.name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
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
    val width = if (LocalConfiguration.current.screenWidthDp >= 900) 280.dp else 220.dp
    TvCard(onClick = onClick, modifier = Modifier.width(width)) {
        Box(Modifier.width(width).aspectRatio(16f / 9f)) {
            // el fotograma del episodio dice más que una carátula recortada a lo ancho
            Poster(
                item.episode.thumbUrl ?: item.title.thumbUrl,
                item.title.name,
                Modifier.fillMaxSize(),
                fallbackUrl = item.title.posterUrl,
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))),
            )
            Icon(
                painter = painterResource(R.drawable.ic_play),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(44.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50))
                    .padding(8.dp),
            )
            Column(Modifier.align(Alignment.BottomStart).padding(start = 10.dp, end = 10.dp, bottom = 12.dp)) {
                Text(
                    if (item.title.isMovie) item.title.name else "${item.title.name} · ${item.episode.name}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Quedan ${formatTime(item.progress.durationMs - item.progress.positionMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
            ProgressBar(item.progress.fraction, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.25f))) {
        Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(MaterialTheme.colorScheme.primary))
    }
}

@Composable
private fun RowHeader(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(10.dp))
}

private fun greeting(): String = when (java.time.LocalTime.now().hour) {
    in 5..11 -> "Buenos días"
    in 12..19 -> "Buenas tardes"
    else -> "Buenas noches"
}

/**
 * El destacado de arriba: va rotando entre lo último que entró, con la imagen
 * acercándose despacio. Mientras un botón suyo tiene el foco se queda quieto,
 * para que con el mando no cambie la película justo antes de pulsar.
 */
@Composable
private fun Hero(
    titles: List<Title>,
    continueItems: List<ContinueItem>,
    onPlay: (Title) -> Unit,
    onInfo: (Title) -> Unit,
) {
    var index by remember { mutableStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    LaunchedEffect(titles, paused) {
        while (!paused && titles.size > 1) {
            delay(9_000)
            index = (index + 1) % titles.size
        }
    }
    val title = titles[index % titles.size]
    val wide = LocalConfiguration.current.screenWidthDp >= 900
    val zoom by rememberInfiniteTransition(label = "Destacado").animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(20_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "Acercamiento",
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(if (wide) 380.dp else 240.dp)
            .clip(RoundedCornerShape(12.dp))
            .onFocusChanged { paused = it.hasFocus },
    ) {
        Crossfade(title, animationSpec = tween(900), label = "Cambio de destacado") { shown ->
            Poster(
                shown.thumbUrl ?: shown.posterUrl,
                shown.name,
                Modifier.fillMaxSize().graphicsLayer { scaleX = zoom; scaleY = zoom },
                fallbackUrl = shown.posterUrl,
            )
        }
        // degradados para que el texto se lea sobre cualquier imagen
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.85f), Color.Transparent))),
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))),
        )
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(if (wide) 32.dp else 16.dp)
                .widthIn(max = 560.dp),
        ) {
            Text(
                title.name,
                style = if (wide) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                titleMeta(title),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.75f),
            )
            Spacer(Modifier.height(if (wide) 16.dp else 10.dp))
            val resume = continueItems.any { it.title.id == title.id }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton(
                    onClick = { onPlay(title) },
                    icon = R.drawable.ic_play,
                    text = when {
                        resume -> "Continuar"
                        title.isMovie -> "Reproducir"
                        else -> "Ver episodios"
                    },
                    primary = true,
                )
                if (wide || title.isMovie) {
                    TvButton(onClick = { onInfo(title) }) { Text("Más información") }
                }
            }
        }
        if (titles.size > 1) {
            Row(
                Modifier.align(Alignment.BottomEnd).padding(if (wide) 32.dp else 16.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                titles.indices.forEach { i ->
                    Box(
                        Modifier
                            .size(if (i == index % titles.size) 18.dp else 6.dp, 6.dp)
                            .background(
                                Color.White.copy(alpha = if (i == index % titles.size) 0.9f else 0.4f),
                                RoundedCornerShape(3.dp),
                            ),
                    )
                }
            }
        }
    }
}

/** "Película · 1 h 52 min" o "Serie · 2 temporadas · 16 episodios". */
fun titleMeta(title: Title): String = if (title.isMovie) {
    listOfNotNull("Película", title.episodes.firstOrNull()?.durationSeconds?.let { formatDuration(it) })
        .joinToString(" · ")
} else {
    val seasons = title.seasons.size
    val episodes = title.episodes.size
    "Serie · $seasons temporada${if (seasons > 1) "s" else ""} · $episodes episodio${if (episodes > 1) "s" else ""}"
}
