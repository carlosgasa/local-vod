package com.local.vod.presentation.upload

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.local.vod.R
import com.local.vod.presentation.TvButton
import com.local.vod.presentation.focusHighlight
import com.local.vod.presentation.formatBytes
import com.local.vod.presentation.neonBackground
import com.local.vod.presentation.screenPadding

@Composable
fun UploadScreen(
    sharedFiles: List<Uri>,
    shareId: Int,
    onShareConsumed: () -> Unit,
    onBack: () -> Unit,
    onSetup: () -> Unit,
    viewModel: UploadViewModel = hiltViewModel(),
) {
    val files by viewModel.files.collectAsStateWithLifecycle()
    val baseUrl by viewModel.baseUrl.collectAsStateWithLifecycle()
    val uploading by viewModel.uploading.collectAsStateWithLifecycle()
    var folder by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        viewModel.addFiles(it)
    }
    LaunchedEffect(shareId) {
        if (sharedFiles.isNotEmpty()) {
            viewModel.addFiles(sharedFiles)
            onShareConsumed()
        }
    }

    Column(Modifier.fillMaxSize().neonBackground().screenPadding()) {
        // Igual que el resto de pantallas: título a la izquierda y el botón a la derecha.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Subir", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            TvButton(onClick = onBack) { Text("Volver") }
        }
        Spacer(Modifier.height(16.dp))
        if (baseUrl == null) {
            Text("Falta configurar el servidor.", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            TvButton(onClick = onSetup, icon = R.drawable.ic_server, text = "Ir a ajustes")
            return@Column
        }
        TvButton(
            onClick = { picker.launch(arrayOf("video/*", "audio/*", "image/*", "text/*")) },
            icon = R.drawable.ic_add,
            text = "Elegir archivos",
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = folder,
            onValueChange = { folder = it },
            label = { Text("Carpeta destino (opcional)") },
            placeholder = { Text("Breaking Bad/Temporada 01") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().focusHighlight(),
        )
        Spacer(Modifier.height(12.dp))
        TvButton(
            onClick = { viewModel.upload(folder) },
            text = "Subir archivos",
            icon = R.drawable.ic_add,
            enabled = !uploading && files.any { it.result == "Pendiente" },
        )
        if (files.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            TvButton(onClick = viewModel::clear, enabled = !uploading) { Text("Limpiar lista") }
        }
        Spacer(Modifier.height(16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(files, key = { it.id }) { file ->
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(file.name, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (file.size > 0) formatBytes(file.size) else "—")
                    }
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { file.progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${file.progress} % · ${file.result}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
