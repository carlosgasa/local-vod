package com.local.vod.presentation.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.local.vod.domain.model.ServerStatus
import com.local.vod.presentation.formatBytes
import com.local.vod.presentation.formatDuration

@Composable
fun ServerStatusCard(status: ServerStatus, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(20.dp),
    ) {
        Text(
            "${status.hostName} · ${status.ip}:${status.port}",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            status.system,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
        Spacer(Modifier.height(16.dp))

        val pending = if (status.pendingDurations > 0) " (midiendo ${status.pendingDurations})" else ""
        StatusRow(
            "Biblioteca",
            "${status.titles} títulos · ${status.videos} videos · ${formatBytes(status.libraryBytes)} · " +
                formatDuration(status.librarySeconds) + pending,
        )

        val freePercent = ((1f - status.usedFraction) * 100).toInt()
        StatusRow(
            "Espacio",
            "${formatBytes(status.freeBytes)} libres de ${formatBytes(status.totalBytes)} · $freePercent % libre",
        )
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0xFF2B3039)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(status.usedFraction.coerceIn(0f, 1f))
                    .height(6.dp)
                    .background(
                        if (status.usedFraction > 0.9f) Color(0xFFF6C445) else MaterialTheme.colorScheme.primary,
                    ),
            )
        }

        val battery = status.battery
        if (battery != null) {
            Spacer(Modifier.height(12.dp))
            val charge = battery.percent?.let { "$it %" } ?: "—"
            val state = if (battery.charging) "cargando" else "con batería"
            val temp = battery.temperature?.let { " · %.0f °C".format(it) } ?: ""
            StatusRow(
                "Batería",
                "$charge $state$temp",
                warn = !battery.charging || (battery.percent ?: 100) < 20,
            )
        }

        if (status.cpuTemperature != null) {
            StatusRow("Temperatura", "%.0f °C".format(status.cpuTemperature))
        }
        if (status.uptimeSeconds != null) {
            StatusRow("Encendido desde hace", formatDuration(status.uptimeSeconds))
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, warn: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.width(170.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (warn) Color(0xFFF6C445) else MaterialTheme.colorScheme.onSurface,
        )
    }
}
