package com.architect.driveaggregator.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PrimaryAccountSessionDao {
    @Query("SELECT * FROM primary_account_session LIMIT 1")
    fun getPrimarySession(): Flow<PrimaryAccountSessionEntity?>

    @Query("SELECT * FROM primary_account_session LIMIT 1")
    suspend fun getPrimarySessionSync(): PrimaryAccountSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateSession(session: PrimaryAccountSessionEntity)

    @Query("DELETE FROM primary_account_session")
    suspend fun clearSession()
}
