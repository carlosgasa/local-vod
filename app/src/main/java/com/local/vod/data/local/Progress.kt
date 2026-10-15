package com.local.vod.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "progress")
data class ProgressEntity(
    @PrimaryKey val episodeId: String,
    val titleId: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

@Dao
interface ProgressDao {
    @Upsert
    suspend fun upsert(row: ProgressEntity)

    @Query("SELECT * FROM progress WHERE episodeId = :episodeId")
    suspend fun byEpisode(episodeId: String): ProgressEntity?

    @Query("SELECT * FROM progress WHERE titleId = :titleId")
    fun byTitle(titleId: String): Flow<List<ProgressEntity>>

    /** Empezado (>10 s) y sin terminar (<95 %), lo más reciente primero. */
    @Query(
        """SELECT * FROM progress
           WHERE durationMs > 0 AND positionMs > 10000 AND positionMs * 100 < durationMs * 95
           ORDER BY updatedAt DESC LIMIT 20""",
    )
    fun unfinished(): Flow<List<ProgressEntity>>

    @Query("DELETE FROM progress WHERE episodeId = :episodeId")
    suspend fun delete(episodeId: String)
}

@Database(entities = [ProgressEntity::class], version = 1)
abstract class VodDatabase : RoomDatabase() {
    abstract fun progressDao(): ProgressDao
}
