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
import androidx.compose.runtime.getValue
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
    Column(Modifier.fillMaxSize().appBackground().screenPadding()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "TV en vivo",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TvButton(onClick = viewModel::load, icon = R.drawable.ic_refresh, text = "Recargar")
            Spacer(Modifier.width(8.dp))
            TvButton(onClick = onBack) { Text("Volver") }
        }
        Spacer(Modifier.height(16.dp))
        when {
            state.loading && state.groups.isEmpty() -> Text("Cargando canales…")
            state.error != null -> Text("No se pudo conectar con el servidor:\n${state.error}")
            state.groups.isEmpty() -> Text(
                "Todavía no hay canales.\nAgrégalos en el panel del servidor, en la sección «TV en vivo».",
                style = MaterialTheme.typography.titleMedium,
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.fillMaxSize()) {
                items(state.groups, key = { it.name }) { group ->
                    Column {
                        Text(
                            "${group.name} · ${group.channels.size}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(10.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(group.channels, key = { it.id }) { channel ->
                                ChannelCard(channel) { onChannel(channel) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelCard(channel: Channel, onClick: () -> Unit) {
    val width = if (LocalConfiguration.current.screenWidthDp >= 900) 200.dp else 150.dp
    Column(Modifier.width(width)) {
        TvCard(onClick = onClick, modifier = Modifier.width(width)) {
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
