package com.local.vod

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class VodApplication : Application(), SingletonImageLoader.Factory {
    // Coil 3 no trae fetcher de red por defecto: se registra el de OkHttp para las carátulas.
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory()) }
            .build()
}
