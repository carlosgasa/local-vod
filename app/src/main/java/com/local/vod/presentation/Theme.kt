package com.local.vod.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme

// Negro de sala de cine y un solo color de acento: el que marca progreso y botón principal.
private val VodColors = darkColorScheme(
    primary = Color(0xFFE5243B),
    onPrimary = Color.White,
    background = Color(0xFF0B0B0F),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF18181D),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF24242B),
)

@Composable
fun VodTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = VodColors) {
        // tv-material3 solo fija el color de texto dentro de un Surface: fuera queda casi negro.
        CompositionLocalProvider(LocalContentColor provides VodColors.onBackground, content = content)
    }
}
