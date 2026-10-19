package com.local.vod.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.local.vod.data.local.ProgressDao
import com.local.vod.data.local.VodDatabase
import com.local.vod.data.repository.LibraryRepositoryImpl
import com.local.vod.data.repository.ListsRepositoryImpl
import com.local.vod.data.repository.LiveTvRepositoryImpl
import com.local.vod.data.repository.PhotoRepositoryImpl
import com.local.vod.data.repository.MusicRepositoryImpl
import com.local.vod.data.repository.ProfileRepositoryImpl
import com.local.vod.data.repository.ProgressRepositoryImpl
import com.local.vod.data.repository.ServerRepositoryImpl
import com.local.vod.domain.repository.LibraryRepository
import com.local.vod.domain.repository.ListsRepository
import com.local.vod.domain.repository.LiveTvRepository
import com.local.vod.domain.repository.PhotoRepository
import com.local.vod.domain.repository.MusicRepository
import com.local.vod.domain.repository.ProfileRepository
import com.local.vod.domain.repository.ProgressRepository
import com.local.vod.domain.repository.ServerRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** Scope que sobrevive a los ViewModel: para guardar el progreso al salir del reproductor. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        // El wifi de un móvil o de la tele recién despertados tarda: 5 s se quedaban cortos.
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Provides
    @Singleton
    fun json(): Json = Json { ignoreUnknownKeys = true }

    @Provides
    @Singleton
    fun dataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("vod_settings") }

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): VodDatabase =
        Room.databaseBuilder(context, VodDatabase::class.java, "vod.db")
            // es caché del servidor: si cambia el esquema se tira y se vuelve a bajar
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun progressDao(db: VodDatabase): ProgressDao = db.progressDao()

    @Provides
    @Singleton
    @AppScope
    fun appScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    abstract fun server(impl: ServerRepositoryImpl): ServerRepository

    @Binds
    abstract fun library(impl: LibraryRepositoryImpl): LibraryRepository

    @Binds
    abstract fun photos(impl: PhotoRepositoryImpl): PhotoRepository

    @Binds
    abstract fun music(impl: MusicRepositoryImpl): MusicRepository

    @Binds
    abstract fun progress(impl: ProgressRepositoryImpl): ProgressRepository

    @Binds
    abstract fun profiles(impl: ProfileRepositoryImpl): ProfileRepository

    @Binds
    abstract fun lists(impl: ListsRepositoryImpl): ListsRepository

    @Binds
    abstract fun liveTv(impl: LiveTvRepositoryImpl): LiveTvRepository
}
