package com.local.vod.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.local.vod.domain.repository.ServerRepository
import com.local.vod.presentation.MainActivity
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ServerMonitorDependencies {
    fun serverRepository(): ServerRepository
}

/** Sólo avisa si este mismo servidor respondió en una revisión anterior. */
class ServerHealthWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val repository = EntryPointAccessors.fromApplication(
            context,
            ServerMonitorDependencies::class.java,
        ).serverRepository()
        val preferences = context.getSharedPreferences("estado_servidor", Context.MODE_PRIVATE)
        val url = repository.baseUrl.first()?.takeIf { it.isNotBlank() }

        if (url == null) {
            cancelNotification(context)
            preferences.edit().clear().apply()
            return Result.success()
        }

        if (preferences.getString("url", null) != url) {
            cancelNotification(context)
            preferences.edit().clear().putString("url", url).apply()
        }

        if (repository.isAlive()) {
            if (preferences.getBoolean("caido", false)) cancelNotification(context)
            preferences.edit()
                .putBoolean("respondia", true)
                .putBoolean("caido", false)
                .putBoolean("avisado", false)
                .apply()
        } else {
            val caido = preferences.getBoolean("caido", false) ||
                preferences.getBoolean("respondia", false)
            val avisado = preferences.getBoolean("avisado", false) ||
                (caido && showNotification(context))
            preferences.edit()
                .putBoolean("respondia", false)
                .putBoolean("caido", caido)
                .putBoolean("avisado", avisado)
                .apply()
        }
        return Result.success()
    }

    private fun showNotification(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false

        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Estado del servidor",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
            manager.createNotificationChannel(channel)
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("Servidor desconectado")
            .setContentText("El servidor de vídeos no contesta. Quizá se apagó la computadora.")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("El servidor de vídeos no contesta. Quizá se apagó la computadora."),
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(false)
            .build()
        return try {
            manager.notify(NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun cancelNotification(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private companion object {
        const val CHANNEL_ID = "estado_servidor"
        const val NOTIFICATION_ID = 7301
    }
}
