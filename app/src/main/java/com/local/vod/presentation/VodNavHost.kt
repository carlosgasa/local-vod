package com.local.vod.presentation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.local.vod.presentation.detail.DetailScreen
import com.local.vod.presentation.home.HomeScreen
import com.local.vod.presentation.music.MusicScreen
import com.local.vod.presentation.player.PlayerScreen
import com.local.vod.presentation.photos.PhotosScreen
import com.local.vod.presentation.profile.ProfileScreen
import com.local.vod.presentation.setup.SetupScreen
import com.local.vod.presentation.upload.UploadScreen

// Los ids son rutas de archivo (con barras y espacios): van como query, no como path.
private fun detailRoute(titleId: String) = "detail?titleId=${Uri.encode(titleId)}"
private fun playerRoute(episodeId: String, startMs: Long) =
    "player?episodeId=${Uri.encode(episodeId)}&startMs=$startMs"

@Composable
fun VodNavHost(sharedFiles: ShareSelection, onShareConsumed: () -> Unit) {
    val nav = rememberNavController()
    LaunchedEffect(sharedFiles.id) {
        if (sharedFiles.uris.isNotEmpty()) nav.navigate("upload") { launchSingleTop = true }
    }
    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onTitleClick = { nav.navigate(detailRoute(it.id)) },
                onContinueClick = { item -> nav.navigate(playerRoute(item.episode.id, item.progress.positionMs)) },
                onSetupClick = { nav.navigate("setup") },
                onUploadClick = { nav.navigate("upload") },
                onProfileClick = { nav.navigate("profiles") },
                onPhotosClick = { nav.navigate("photos") },
                onMusicClick = { nav.navigate("music") },
            )
        }
        composable("photos") {
            PhotosScreen(onBack = { nav.popBackStack() })
        }
        composable("profiles") {
            ProfileScreen(onChosen = { nav.popBackStack() }, onSetup = { nav.navigate("setup") })
        }
        composable("music") {
            MusicScreen(onBack = { nav.popBackStack() })
        }
        composable("setup") {
            SetupScreen(onSaved = { nav.popBackStack() })
        }
        composable("upload") {
            UploadScreen(
                sharedFiles = sharedFiles.uris,
                shareId = sharedFiles.id,
                onShareConsumed = onShareConsumed,
                onBack = { nav.popBackStack() },
                onSetup = { nav.navigate("setup") },
            )
        }
        composable(
            route = "detail?titleId={titleId}",
            arguments = listOf(navArgument("titleId") { type = NavType.StringType }),
        ) {
            DetailScreen(
                onPlay = { episodeId, startMs -> nav.navigate(playerRoute(episodeId, startMs)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            route = "player?episodeId={episodeId}&startMs={startMs}",
            arguments = listOf(
                navArgument("episodeId") { type = NavType.StringType },
                navArgument("startMs") { type = NavType.LongType; defaultValue = 0L },
            ),
        ) {
            PlayerScreen(onBack = { nav.popBackStack() })
        }
    }
}
