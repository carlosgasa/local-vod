package com.local.vod.presentation.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.local.vod.presentation.TvButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text

@Composable
fun SetupScreen(
    onSaved: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    val saveButton = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(48.dp),
    ) {
        Text("Servidor de la PC", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Escribe la dirección que aparece en el dashboard (por ejemplo 192.168.1.100:8000).",
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(24.dp))

        BasicTextField(
            value = state.address,
            onValueChange = viewModel::onAddressChange,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 24.sp),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
            // En la tele se escribe con el teclado en pantalla: su tecla "listo" ya guarda.
            keyboardActions = KeyboardActions(onDone = { viewModel.testAndSave(onSaved) }),
            modifier = Modifier
                .width(420.dp)
                .focusRequester(focus)
                // el campo se come las flechas: sin esto el mando no sale hacia el botón
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                        saveButton.requestFocus()
                        true
                    } else {
                        false
                    }
                }
                .background(MaterialTheme.colorScheme.surface)
                .border(2.dp, MaterialTheme.colorScheme.primary)
                .padding(16.dp),
        )

        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(
                onClick = { viewModel.testAndSave(onSaved) },
                enabled = !state.testing,
                modifier = Modifier.focusRequester(saveButton),
            ) {
                Text(if (state.testing) "Probando…" else "Probar y guardar")
            }
        }

        if (state.error != null) {
            Spacer(Modifier.height(16.dp))
            Text(state.error!!, color = Color(0xFFF6C445))
        }
    }
}
