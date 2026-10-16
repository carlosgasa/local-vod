package com.local.vod.presentation

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Fondo de manchas de luz al estilo "mesh gradient": varios focos de color neón
 * muy difuminados sobre un fondo casi negro. No es el degradado lineal de toda
 * la vida: son halos superpuestos, que es lo que da el aire moderno.
 */
private val Base = Color(0xFF0B0D12)
private val NeonBlue = Color(0xFF4DA3FF)
private val NeonViolet = Color(0xFF7C4DFF)
private val NeonPink = Color(0xFFFF2D95)
private val NeonCyan = Color(0xFF00E5FF)

/** @param intensity 0f-1f: en el detalle conviene bajarlo para no competir con la carátula. */
fun Modifier.neonBackground(intensity: Float = 1f): Modifier = drawBehind {
    drawRect(Base)

    fun glow(color: Color, center: Offset, radius: Float, alpha: Float) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = alpha * intensity), Color.Transparent),
                center = center,
                radius = radius,
            ),
            radius = radius,
            center = center,
        )
    }

    val w = size.width
    val h = size.height
    glow(NeonViolet, Offset(w * 0.08f, h * -0.05f), maxOf(w, h) * 0.55f, 0.30f)
    glow(NeonBlue, Offset(w * 0.75f, h * 0.10f), maxOf(w, h) * 0.45f, 0.18f)
    glow(NeonPink, Offset(w * 1.02f, h * 0.92f), maxOf(w, h) * 0.50f, 0.20f)
    glow(NeonCyan, Offset(w * 0.20f, h * 1.05f), maxOf(w, h) * 0.40f, 0.14f)
}
