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
// más el resalte de foco de arriba.

@Composable
fun TvButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = modifier.focusHighlight(24),
        enabled = enabled,
        content = content,
    )
}

@Composable
fun TvCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    androidx.compose.material3.Card(
        onClick = onClick,
        modifier = modifier.focusHighlight(12),
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
) {
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = modifier.focusHighlight(24),
        enabled = enabled,
    ) {
        androidx.compose.material3.Icon(
            painter = androidx.compose.ui.res.painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        androidx.compose.foundation.layout.Spacer(Modifier.size(8.dp))
        androidx.compose.material3.Text(text, maxLines = 1)
    }
}

/** Solo icono, para la barra de arriba. Lleva descripción para el lector de pantalla. */
@Composable
fun TvIconButton(
    onClick: () -> Unit,
    icon: Int,
    description: String,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.FilledIconButton(
        onClick = onClick,
        modifier = modifier.focusHighlight(28),
    ) {
        androidx.compose.material3.Icon(
            painter = androidx.compose.ui.res.painterResource(icon),
            contentDescription = description,
            modifier = Modifier.size(22.dp),
        )
    }
}
