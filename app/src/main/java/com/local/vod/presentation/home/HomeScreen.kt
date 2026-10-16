package com.local.vod.presentation.home

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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

@Composable
fun HomeScreen(
    onTitleClick: (Title) -> Unit,
    onContinueClick: (ContinueItem) -> Unit,
    onSetupClick: () -> Unit,
    onProfileClick: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

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
        // Título a la izquierda y los controles pegados a la derecha, como en cualquier tele.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                "Local VOD",
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
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
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (state.continueItems.isNotEmpty()) {
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
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text("Biblioteca", style = MaterialTheme.typography.titleLarge)
                    }
                    items(state.titles, key = { it.id }) { title ->
                        TitleCard(title) { onTitleClick(title) }
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
