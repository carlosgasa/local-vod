package com.local.vod.presentation.photos

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.local.vod.R
import com.local.vod.domain.model.PhotoAlbum
import com.local.vod.presentation.TvButton
import com.local.vod.presentation.TvCard
import com.local.vod.presentation.TvIconButton
import com.local.vod.presentation.neonBackground
import com.local.vod.presentation.screenPadding
import kotlinx.coroutines.launch

@Composable
fun PhotosScreen(onBack: () -> Unit, viewModel: PhotosViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selectedAlbumId by remember { mutableStateOf<String?>(null) }
    var selectedPhoto by remember { mutableStateOf<Int?>(null) }
    val album = state.albums.firstOrNull { it.id == selectedAlbumId }

    BackHandler(selectedPhoto != null || selectedAlbumId != null) {
        if (selectedPhoto != null) selectedPhoto = null else selectedAlbumId = null
    }

    if (album != null && selectedPhoto != null) {
        key(album.id) {
            PhotoViewer(album, selectedPhoto!!, onClose = { selectedPhoto = null })
        }
        return
    }

    Column(Modifier.fillMaxSize().neonBackground().screenPadding()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                album?.title ?: "Fotos",
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TvButton(onClick = { if (album != null) selectedAlbumId = null else onBack() }) {
                Text("Volver")
            }
        }
        Spacer(Modifier.height(20.dp))
        when {
            state.loading -> Text("Cargando fotos…")
            state.error != null -> Column {
                Text("No se pudieron cargar las fotos: ${state.error}")
                TvButton(onClick = viewModel::load) { Text("Reintentar") }
            }
            album != null -> LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(album.photos, key = { it.id }) { photo ->
                    val index = album.photos.indexOf(photo)
                    TvCard(onClick = { selectedPhoto = index }) {
                        AsyncImage(
                            model = photo.url,
                            contentDescription = photo.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                        )
                    }
                }
            }
            state.albums.isEmpty() -> Text("No hay álbumes de fotos.")
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(180.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(state.albums, key = { it.id }) { item ->
                    Column {
                        TvCard(onClick = { selectedAlbumId = item.id }) {
                            AsyncImage(
                                model = item.coverUrl,
                                contentDescription = item.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f),
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${item.count} fotos", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoViewer(album: PhotoAlbum, startIndex: Int, onClose: () -> Unit) {
    val pager = rememberPagerState(initialPage = startIndex, pageCount = { album.photos.size })
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val target = when (event.key) {
                    Key.DirectionLeft -> pager.currentPage - 1
                    Key.DirectionRight -> pager.currentPage + 1
                    else -> return@onPreviewKeyEvent false
                }
                if (target in album.photos.indices) scope.launch { pager.animateScrollToPage(target) }
                true
            }
            .focusRequester(focus)
            .focusable(),
    ) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { index ->
            AsyncImage(
                model = album.photos[index].url,
                contentDescription = album.photos[index].name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Row(
            Modifier.align(Alignment.TopEnd).screenPadding(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${pager.currentPage + 1} / ${album.photos.size}", color = Color.White)
            Spacer(Modifier.width(12.dp))
            TvIconButton(onClick = onClose, icon = R.drawable.ic_close, description = "Cerrar foto")
        }
    }
}
