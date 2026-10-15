package com.local.vod.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

private val VodColors = darkColorScheme(
    primary = Color(0xFF4DA3FF),
    onPrimary = Color(0xFF07121F),
    background = Color(0xFF13151A),
    onBackground = Color(0xFFE8EAED),
    surface = Color(0xFF1C1F26),
    onSurface = Color(0xFFE8EAED),
)

@Composable
fun VodTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = VodColors, content = content)
}
