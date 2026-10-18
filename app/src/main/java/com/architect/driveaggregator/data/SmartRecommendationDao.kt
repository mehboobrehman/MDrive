package com.architect.driveaggregator.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SmartRecommendationDao {
    @Query("SELECT * FROM smart_recommendations WHERE isDismissed = 0 ORDER BY createdAt DESC")
    fun getActiveRecommendations(): Flow<List<SmartRecommendationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecommendation(recommendation: SmartRecommendationEntity)

    @Query("UPDATE smart_recommendations SET isDismissed = 1 WHERE id = :id")
    suspend fun dismissRecommendation(id: Long)

    @Query("DELETE FROM smart_recommendations")
    suspend fun clearAll()
}
