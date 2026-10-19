package com.local.vod.presentation.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import com.local.vod.R
import com.local.vod.presentation.appBackground
import com.local.vod.presentation.screenPadding

@Composable
fun SetupScreen(
    onSaved: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    val saveButton = remember { FocusRequester() }
    val searchButton = remember { FocusRequester() }
    // Con mando lo cómodo es entrar y pulsar OK para que busque solo; el teclado
    // solo aparece si eliges escribir la dirección a mano.
    LaunchedEffect(Unit) { searchButton.requestFocus() }

    Column(
        Modifier
            .fillMaxSize()
            .appBackground()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .screenPadding(),
    ) {
        val status = state.status
        if (status != null) {
            ServerStatusCard(status)
            Spacer(Modifier.height(24.dp))
        }

        Text("Servidor de la PC", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Puedo buscarlo solo en tu red, o escribe la dirección que aparece en el panel de la PC " +
                "(por ejemplo 192.168.1.100:8000).",
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
                icon = R.drawable.ic_check,
                text = if (state.testing) "Probando…" else "Probar y guardar",
                enabled = !state.testing && !state.searching,
                modifier = Modifier.focusRequester(saveButton),
            )
            TvButton(
                onClick = { viewModel.search(onSaved) },
                icon = R.drawable.ic_search,
                text = if (state.searching) "Buscando…" else "Buscar en mi red",
                enabled = !state.testing && !state.searching,
                modifier = Modifier.focusRequester(searchButton),
            )
        }

        Spacer(Modifier.height(24.dp))
        TvButton(onClick = viewModel::togglePreviews) {
            Text("Adelanto al enfocar: ${if (state.previewsEnabled) "sí" else "no"}")
        }

        if (state.error != null) {
            Spacer(Modifier.height(16.dp))
            Text(state.error!!, color = Color(0xFFF6C445))
        }
    }
}
