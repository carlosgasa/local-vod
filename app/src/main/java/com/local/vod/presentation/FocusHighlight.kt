package com.local.vod.presentation

import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * Con mando no hay puntero: lo enfocado tiene que cantar. Material 3 marca el foco de forma muy
 * sutil, así que aquí se le añade borde y un pelín de escala. Con el dedo no estorba porque
 * tocar no da foco.
 */
fun Modifier.focusHighlight(cornerRadius: Int = 12): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    this
        .onFocusChanged { focused = it.isFocused }
        .scale(if (focused) 1.04f else 1f)
        .border(
            width = if (focused) 3.dp else 0.dp,
            color = if (focused) Color.White else Color.Transparent,
            shape = RoundedCornerShape(cornerRadius.dp),
        )
}

/** Para componentes que ya exponen su interactionSource (botones). */
@Composable
fun rememberFocusInteractionSource(): MutableInteractionSource = remember { MutableInteractionSource() }

@Composable
fun MutableInteractionSource.isFocused(): Boolean = collectIsFocusedAsState().value

// Envoltorios que usan todas las pantallas: Material 3 (responde al dedo y al mando)
// con el estilo de las apps de streaming: lo enfocado se vuelve blanco y crece un poco.

private val ButtonShape = RoundedCornerShape(8.dp)

@Composable
private fun tvButtonColors(focused: Boolean, primary: Boolean) = androidx.compose.material3.ButtonDefaults.buttonColors(
    containerColor = when {
        focused -> Color.White
        primary -> androidx.compose.material3.MaterialTheme.colorScheme.primary
        else -> Color.White.copy(alpha = 0.14f)
    },
    contentColor = if (focused) Color.Black else Color.White,
    disabledContainerColor = Color.White.copy(alpha = 0.06f),
    disabledContentColor = Color.White.copy(alpha = 0.38f),
)

/** @param primary el botón principal de la pantalla (reproducir): va en el color de acento. */
@Composable
fun TvButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val source = rememberFocusInteractionSource()
    val focused = source.isFocused()
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = modifier.scale(if (focused) 1.05f else 1f),
        enabled = enabled,
        shape = ButtonShape,
        colors = tvButtonColors(focused, primary),
        interactionSource = source,
        content = content,
    )
}

@Composable
fun TvCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    androidx.compose.material3.Card(
        onClick = onClick,
        modifier = modifier.focusHighlight(8),
        shape = RoundedCornerShape(8.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface,
        ),
        interactionSource = interactionSource,
        content = content,
    )
}


/** Botón con icono y texto: el icono manda, el texto acompaña en las acciones grandes. */
@Composable
fun TvButton(
    onClick: () -> Unit,
    icon: Int,
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
) {
    TvButton(onClick = onClick, modifier = modifier, enabled = enabled, primary = primary) {
        androidx.compose.material3.Icon(
            painter = androidx.compose.ui.res.painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
        androidx.compose.material3.Text(text, maxLines = 1)
    }
}

/** Solo icono, para la barra de arriba: transparente hasta que se enfoca. */
@Composable
fun TvIconButton(
    onClick: () -> Unit,
    icon: Int,
    description: String,
    modifier: Modifier = Modifier,
) {
    val source = rememberFocusInteractionSource()
    val focused = source.isFocused()
    androidx.compose.material3.FilledIconButton(
        onClick = onClick,
        modifier = modifier.scale(if (focused) 1.1f else 1f),
        colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(
            containerColor = if (focused) Color.White else Color.Transparent,
            contentColor = if (focused) Color.Black else Color.White,
        ),
        interactionSource = source,
    ) {
        androidx.compose.material3.Icon(
            painter = androidx.compose.ui.res.painterResource(icon),
            contentDescription = description,
            modifier = Modifier.size(24.dp),
        )
    }
}
