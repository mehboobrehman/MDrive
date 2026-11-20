package com.architect.driveaggregator.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Data Access Object for accessing immutable entity history and audit records.
 */
@Dao
interface EntityHistoryDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertHistory(history: EntityHistoryEntity)

    @Query("SELECT * FROM entity_histories WHERE entity_id = :entityId ORDER BY version DESC")
    suspend fun getHistoryForEntity(entityId: String): List<EntityHistoryEntity>

    @Query("SELECT * FROM entity_histories WHERE entity_id = :entityId AND version = :version LIMIT 1")
    suspend fun getHistoryAtVersion(entityId: String, version: Int): EntityHistoryEntity?

    @Query("SELECT MAX(version) FROM entity_histories WHERE entity_id = :entityId")
    suspend fun getLatestVersion(entityId: String): Int?
}
