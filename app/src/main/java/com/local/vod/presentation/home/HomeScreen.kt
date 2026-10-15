package com.local.vod.presentation.home

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.local.vod.presentation.TvButton
import com.local.vod.presentation.TvCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import coil3.compose.AsyncImage
import com.local.vod.domain.model.ContinueItem
import com.local.vod.domain.model.Title
import com.local.vod.presentation.formatTime

@Composable
fun HomeScreen(
    onTitleClick: (Title) -> Unit,
    onContinueClick: (ContinueItem) -> Unit,
    onSetupClick: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 32.dp, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Local VOD", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(24.dp))
            TvButton(onClick = { viewModel.load(refresh = true) }) { Text("Recargar") }
            Spacer(Modifier.width(12.dp))
            TvButton(onClick = onSetupClick) { Text("Servidor") }
        }
        Spacer(Modifier.height(16.dp))

        when {
            state.needsSetup -> Message(
                "Falta configurar el servidor.\nPulsa «Servidor» y escribe la dirección que muestra el dashboard de la PC.",
            )

            state.error != null -> Message("No se pudo conectar:\n${state.error}")

            state.loading && state.titles.isEmpty() -> Message("Cargando biblioteca…")

            state.titles.isEmpty() -> Message(
                "La biblioteca está vacía.\nCopia carpetas con videos en la carpeta media de la PC.",
            )

            else -> {
                if (state.continueItems.isNotEmpty()) {
                    Text("Seguir viendo", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(state.continueItems, key = { it.episode.id }) { item ->
                            ContinueCard(item) { onContinueClick(item) }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
                Text("Biblioteca", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(160.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.titles, key = { it.id }) { title ->
                        TitleCard(title) { onTitleClick(title) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
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
private fun TitleCard(title: Title, onClick: () -> Unit) {
    Column {
        TvCard(onClick = onClick, modifier = Modifier.width(160.dp)) {
            Poster(title.posterUrl, title.name, Modifier.width(160.dp).aspectRatio(2f / 3f))
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
