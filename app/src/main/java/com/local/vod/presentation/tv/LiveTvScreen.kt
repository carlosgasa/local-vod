package com.local.vod.presentation.tv

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.window.Dialog
import com.local.vod.domain.model.TitleList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.local.vod.R
import com.local.vod.domain.model.Channel
import com.local.vod.presentation.TvButton
import com.local.vod.presentation.TvCard
import com.local.vod.presentation.appBackground
import com.local.vod.presentation.screenPadding

@Composable
fun LiveTvScreen(
    onChannel: (Channel) -> Unit,
    onBack: () -> Unit,
    viewModel: LiveTvViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<Channel?>(null) }
    val onCard: (Channel) -> Unit = { if (state.editing) selected = it else onChannel(it) }
    Column(Modifier.fillMaxSize().appBackground().screenPadding()) {
        val title = @Composable { modifier: Modifier ->
            Text(
                "TV en vivo",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = modifier,
            )
        }
        val buttons = @Composable {
            TvButton(onClick = viewModel::toggleEditing, primary = state.editing) { Text(if (state.editing) "Listo" else "Editar") }
            Spacer(Modifier.width(8.dp))
            TvButton(onClick = viewModel::load, icon = R.drawable.ic_refresh, text = "Recargar")
            Spacer(Modifier.width(8.dp))
            TvButton(onClick = onBack) { Text("Volver") }
        }
        // Como en la pantalla principal: en el celular en vertical los botones van debajo del título.
        if (LocalConfiguration.current.screenWidthDp >= 600) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                title(Modifier.weight(1f))
                buttons()
            }
        } else {
            title(Modifier)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { buttons() }
        }
        if (state.editing) Text(
            "Elige un canal para marcarlo como favorito u ocultarlo. Los ocultos se ven en gris.",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(16.dp))
        when {
            state.loading && state.groups.isEmpty() -> Text("Cargando canales…")
            state.error != null -> Text("No se pudo conectar con el servidor:\n${state.error}")
            state.groups.isEmpty() -> Text(
                "Todavía no hay canales.\nAgrégalos en el panel del servidor, en la sección «TV en vivo».",
                style = MaterialTheme.typography.titleMedium,
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.fillMaxSize()) {
                val favorites = state.favorites
                if (favorites.isNotEmpty()) {
                    item(key = "favoritos") {
                        Column {
                            Text("♥ Mis favoritos", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.height(10.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(favorites, key = { it.id }) { channel -> ChannelCard(channel) { onCard(channel) } }
                            }
                        }
                    }
                }
                items(state.visibleGroups, key = { it.name }) { group ->
                    Column {
                        Text(
                            "${group.name} · ${group.channels.size}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(10.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(group.channels, key = { it.id }) { channel ->
                                ChannelCard(channel, dimmed = channel.id in state.hiddenIds) { onCard(channel) }
                            }
                        }
                    }
                }
            }
        }
    }
    selected?.let { channel ->
        val favorite = channel.id in state.favoriteIds
        val hidden = channel.id in state.hiddenIds
        ChannelOptionsDialog(
            channel = channel,
            favorite = favorite,
            hidden = hidden,
            onFavorite = { viewModel.set(TitleList.CHANNELS, channel, !favorite) },
            onHidden = { viewModel.set(TitleList.HIDDEN, channel, !hidden); selected = null },
            onDismiss = { selected = null },
        )
    }
}

@Composable
private fun ChannelOptionsDialog(
    channel: Channel,
    favorite: Boolean,
    hidden: Boolean,
    onFavorite: () -> Unit,
    onHidden: () -> Unit,
    onDismiss: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Dialog(onDismissRequest = onDismiss) {
        Surface(Modifier.widthIn(max = 640.dp)) {
            Column(Modifier.padding(24.dp)) {
                Text(channel.name, style = MaterialTheme.typography.titleLarge, maxLines = 2)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton(
                        onClick = onFavorite,
                        icon = if (favorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border,
                        text = if (favorite) "Quitar de favoritos" else "Favorito",
                        modifier = Modifier.focusRequester(focus),
                    )
                    TvButton(
                        onClick = onHidden,
                        icon = if (hidden) R.drawable.ic_check else R.drawable.ic_close,
                        text = if (hidden) "Mostrar" else "Ocultar",
                    )
                }
            }
        }
    }
}

@Composable
fun ChannelCard(channel: Channel, dimmed: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val width = if (LocalConfiguration.current.screenWidthDp >= 900) 200.dp else 150.dp
    Column(Modifier.width(width).alpha(if (dimmed) 0.35f else 1f)) {
        TvCard(onClick = onClick, modifier = modifier.width(width)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Brush.verticalGradient(listOf(Color(0xFF2A2A33), Color(0xFF141418)))),
                contentAlignment = Alignment.Center,
            ) {
                if (channel.logoUrl != null) {
                    // los logos vienen de mil tamaños: que quepan enteros, sin recortar
                    AsyncImage(
                        model = channel.logoUrl,
                        contentDescription = channel.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                    )
                } else {
                    Text(
                        channel.name,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Bold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(channel.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
