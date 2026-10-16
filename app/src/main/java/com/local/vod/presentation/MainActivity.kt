package com.local.vod.presentation

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dagger.hilt.android.AndroidEntryPoint

data class ShareSelection(val id: Int = 0, val uris: List<Uri> = emptyList())

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var sharedFiles by mutableStateOf(ShareSelection())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        receiveShare(intent)
        setContent {
            VodTheme {
                VodNavHost(sharedFiles) {
                    sharedFiles = sharedFiles.copy(uris = emptyList())
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveShare(intent)
    }

    @Suppress("DEPRECATION")
    private fun receiveShare(intent: Intent?) {
        val sharedIntent = intent ?: return
        if (sharedIntent.action != Intent.ACTION_SEND && sharedIntent.action != Intent.ACTION_SEND_MULTIPLE) return
        val streams = when (sharedIntent.action) {
            Intent.ACTION_SEND -> listOfNotNull(sharedIntent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> sharedIntent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> emptyList()
        }
        val uris = streams.ifEmpty {
            sharedIntent.clipData?.let { clip ->
                (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
            }.orEmpty()
        }
        if (uris.isNotEmpty()) sharedFiles = ShareSelection(sharedFiles.id + 1, uris)
    }
}
