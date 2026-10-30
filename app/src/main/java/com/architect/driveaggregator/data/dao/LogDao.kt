package com.architect.driveaggregator.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.architect.driveaggregator.data.entities.LogEntity

/**
 * DAO for managing LogEntity records in Room DB.
 * Supports offline logging persistence and telemetry sync.
 */
@Dao
interface LogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: LogEntity)

    @Query("SELECT * FROM logs ORDER BY timestamp DESC")
    suspend fun getAll(): List<LogEntity>

    @Query("SELECT * FROM logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<LogEntity>

    @Query("DELETE FROM logs")
    suspend fun clearAll()

    @Query("SELECT * FROM logs WHERE isSynced = 0 ORDER BY timestamp ASC")
    suspend fun getUnsyncedLogs(): List<LogEntity>

    @Query("UPDATE logs SET isSynced = 1 WHERE id IN (:ids)")
    suspend fun markAsSynced(ids: List<Long>)

    @Query("DELETE FROM logs WHERE isSynced = 1 AND timestamp < :olderThan")
    suspend fun deleteSyncedLogs(olderThan: Long)
}
