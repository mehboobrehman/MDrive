package com.architect.driveaggregator.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity representing a log entry for local persistence before syncing to telemetry server.
 */
@Entity(tableName = "logs")
data class LogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val timestamp: Long,
    val level: String,
    val tag: String,
    val message: String,
    val metadata: String, // JSON string for extra data
    val isSynced: Boolean = false
)
