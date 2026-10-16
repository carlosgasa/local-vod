package com.local.vod.notifications

import android.app.Application
import android.content.Context
import androidx.startup.Initializer
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkManagerInitializer
import java.util.concurrent.TimeUnit

/** Programa la vigilancia al iniciar la app y registra la petición del permiso. */
class ServerMonitorInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        val work = PeriodicWorkRequestBuilder<ServerHealthWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "vigilar_servidor",
            ExistingPeriodicWorkPolicy.KEEP,
            work,
        )
        (context.applicationContext as Application)
            .registerActivityLifecycleCallbacks(NotificationPermission())
    }

    override fun dependencies(): List<Class<out Initializer<*>>> =
        listOf(WorkManagerInitializer::class.java)
}
