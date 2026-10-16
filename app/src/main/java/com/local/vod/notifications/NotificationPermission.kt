package com.local.vod.notifications

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import com.local.vod.presentation.MainActivity

/** Pide el permiso una vez al abrir la app; en televisores no abre un diálogo inútil. */
internal class NotificationPermission : Application.ActivityLifecycleCallbacks {
    override fun onActivityResumed(activity: Activity) {
        if (activity !is MainActivity || Build.VERSION.SDK_INT < 33) return
        if (activity.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) return
        if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return

        val preferences = activity.getSharedPreferences("aviso_permiso", Context.MODE_PRIVATE)
        if (preferences.getBoolean("solicitado", false)) return
        preferences.edit().putBoolean("solicitado", true).apply()
        activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7301)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
