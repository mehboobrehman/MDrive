package com.architect.driveaggregator.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity representing smart AI-powered recommendations for storage cleanup, duplicate insights, and optimization.
 */
@Entity(tableName = "smart_recommendations")
data class SmartRecommendationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val description: String,
    val category: String, // e.g. "DUPLICATE", "LARGE_FILE", "INACTIVE", "CLEANUP"
    val potentialSavingsBytes: Long,
    val relatedFileIdsJson: String?, // JSON array of file IDs involved
    val isDismissed: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
