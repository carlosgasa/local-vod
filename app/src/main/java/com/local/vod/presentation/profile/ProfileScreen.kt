package com.local.vod.presentation.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.local.vod.R
import com.local.vod.domain.model.Profile
import com.local.vod.presentation.TvButton
import com.local.vod.presentation.neonBackground
import com.local.vod.presentation.screenPadding
import com.local.vod.presentation.focusHighlight

@Composable
fun ProfileScreen(
    onChosen: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .neonBackground()
            .screenPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Text("¿Quién está viendo?", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(32.dp))

        when {
            state.error != null -> Text("No se pudo conectar: ${state.error}", textAlign = TextAlign.Center)
            state.loading -> Text("Cargando perfiles…")
            else -> LazyRow(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                items(state.profiles, key = { it.id }) { profile ->
                    ProfileAvatar(profile) { viewModel.choose(profile, onChosen) }
                }
            }
        }

        Spacer(Modifier.height(40.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = state.newName,
                onValueChange = viewModel::onNameChange,
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 20.sp),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { viewModel.create(onChosen) }),
                decorationBox = { field ->
                    Box(Modifier.padding(14.dp)) {
                        if (state.newName.isEmpty()) {
                            Text("Nombre nuevo", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                        }
                        field()
                    }
                },
                modifier = Modifier
                    .width(260.dp)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(2.dp, MaterialTheme.colorScheme.primary),
            )
            Spacer(Modifier.width(12.dp))
            TvButton(
                onClick = { viewModel.create(onChosen) },
                icon = R.drawable.ic_add,
                text = "Crear perfil",
            )
        }
    }
}

@Composable
private fun ProfileAvatar(profile: Profile, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(120.dp)
                .focusHighlight(60)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .let { base ->
                    base.then(
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                profile.name.take(1).uppercase(),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(12.dp))
        TvButton(onClick = onClick) { Text(profile.name, maxLines = 1) }
    }
}
