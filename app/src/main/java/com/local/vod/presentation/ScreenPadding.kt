package com.local.vod.presentation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

/**
 * Margen de pantalla común. En la tele hace falta más aire (los bordes se comen
 * parte de la imagen) y en el móvil hay que esquivar la barra de gestos y el
 * notch: de eso se encarga safeDrawing.
 */
fun Modifier.screenPadding(): Modifier = composed {
    val width = LocalConfiguration.current.screenWidthDp
    val horizontal = if (width >= 900) 48.dp else 20.dp
    val vertical = if (width >= 900) 28.dp else 16.dp
    this
        .windowInsetsPadding(WindowInsets.safeDrawing)
        .padding(horizontal = horizontal, vertical = vertical)
}

/** Para listas que tienen que llegar al borde al desplazarse, pero con aire a los lados. */
@androidx.compose.runtime.Composable
fun screenContentPadding(): PaddingValues {
    val width = LocalConfiguration.current.screenWidthDp
    return PaddingValues(bottom = if (width >= 900) 28.dp else 16.dp)
}
