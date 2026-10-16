package com.local.vod.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Caché local de lo que el servidor sabe: así la tele enseña "seguir viendo"
 * al instante y aguanta un rato si el servidor no contesta.
 */
@Entity(tableName = "progress", primaryKeys = ["profileId", "episodeId"])
data class ProgressEntity(
    val profileId: String,
    val episodeId: String,
    val titleId: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

@Dao
interface ProgressDao {
    @Upsert
    suspend fun upsert(row: ProgressEntity)

    @Upsert
    suspend fun upsertAll(rows: List<ProgressEntity>)

    @Query("SELECT * FROM progress WHERE profileId = :profileId AND titleId = :titleId")
    fun byTitle(profileId: String, titleId: String): Flow<List<ProgressEntity>>

    /** Empezado (>10 s) y sin terminar (<95 %), lo más reciente primero. */
    @Query(
        """SELECT * FROM progress
           WHERE profileId = :profileId AND durationMs > 0 AND positionMs > 10000
             AND positionMs * 100 < durationMs * 95
           ORDER BY updatedAt DESC LIMIT 60""",
    )
    fun unfinished(profileId: String): Flow<List<ProgressEntity>>

    @Query("DELETE FROM progress WHERE profileId = :profileId AND episodeId = :episodeId")
    suspend fun delete(profileId: String, episodeId: String)

    @Query("DELETE FROM progress WHERE profileId = :profileId")
    suspend fun clearProfile(profileId: String)
}

@Database(entities = [ProgressEntity::class], version = 2)
abstract class VodDatabase : RoomDatabase() {
    abstract fun progressDao(): ProgressDao
}
