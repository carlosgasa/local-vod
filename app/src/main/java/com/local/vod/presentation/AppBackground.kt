package com.local.vod.presentation

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

private val Base = Color(0xFF0B0B0F)
private val Accent = Color(0xFFE5243B)

/**
 * Fondo casi negro, como en las apps de streaming: lo que tiene que brillar son
 * las carátulas. Solo un resplandor muy tenue del acento arriba a la izquierda.
 *
 * @param intensity 0f-1f del resplandor; en el detalle se baja para no competir con la imagen.
 */
fun Modifier.appBackground(intensity: Float = 1f): Modifier = drawBehind {
    drawRect(Base)
    val radius = maxOf(size.width, size.height) * 0.6f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(Accent.copy(alpha = 0.10f * intensity), Color.Transparent),
            center = Offset(0f, 0f),
            radius = radius,
        ),
        radius = radius,
        center = Offset(0f, 0f),
    )
}
