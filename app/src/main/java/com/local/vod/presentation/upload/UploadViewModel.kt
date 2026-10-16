package com.local.vod.presentation.upload

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.local.vod.data.remote.VodApi
import com.local.vod.domain.repository.ServerRepository
import com.local.vod.domain.usecase.GetLibrary
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UploadFile(
    val id: Long,
    val uri: Uri,
    val name: String,
    val size: Long,
    val progress: Int = 0,
    val result: String = "Pendiente",
)

@HiltViewModel
class UploadViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val server: ServerRepository,
    private val api: VodApi,
    private val getLibrary: GetLibrary,
) : ViewModel() {
    private val _files = MutableStateFlow<List<UploadFile>>(emptyList())
    val files = _files.asStateFlow()
    private val _baseUrl = MutableStateFlow<String?>(null)
    val baseUrl = _baseUrl.asStateFlow()
    private val _uploading = MutableStateFlow(false)
    val uploading = _uploading.asStateFlow()
    private var nextId = 0L

    init {
        viewModelScope.launch { server.baseUrl.collect { _baseUrl.value = it } }
    }

    fun addFiles(uris: List<Uri>) {
        val resolver = context.contentResolver
        val added = uris.map { uri ->
            var name = ""
            var size = -1L
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            name = cursor.getString(0) ?: ""
                            if (!cursor.isNull(1)) size = cursor.getLong(1)
                        }
                    }
            }
            if (size <= 0) {
                size = runCatching { resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L }
                    .getOrDefault(-1L)
            }
            UploadFile(
                id = ++nextId,
                uri = uri,
                name = name,
                size = size,
                result = if (name.isBlank() || size <= 0) "Error: nombre o tamaño no disponible" else "Pendiente",
            )
        }
        _files.update { it + added }
    }

    fun upload(folder: String) {
        if (_uploading.value) return
        viewModelScope.launch {
            val base = server.baseUrl.first() ?: return@launch
            _uploading.value = true
            try {
                val prefix = folder.trim().trim('/')
                for (file in _files.value.filter { it.result == "Pendiente" }) {
                    val path = if (prefix.isEmpty()) file.name else "$prefix/${file.name}"
                    update(file.id) { it.copy(result = "Subiendo…") }
                    runCatching {
                        api.upload(base, context.contentResolver, file.uri, path, file.size) { percent ->
                            update(file.id) { it.copy(progress = percent) }
                        }
                    }.onSuccess {
                        update(file.id) { it.copy(progress = 100, result = "Listo") }
                        runCatching { getLibrary(refresh = true) }
                    }.onFailure { error ->
                        update(file.id) { it.copy(result = "Error: ${error.message ?: "No se pudo subir"}") }
                    }
                }
            } finally {
                _uploading.value = false
            }
        }
    }

    private fun update(id: Long, change: (UploadFile) -> UploadFile) {
        _files.update { files -> files.map { if (it.id == id) change(it) else it } }
    }
}
