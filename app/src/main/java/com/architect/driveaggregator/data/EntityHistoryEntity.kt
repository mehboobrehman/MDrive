package com.architect.driveaggregator.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity representing an immutable point-in-time snapshot of an entity's state in version history.
 */
@Entity(
    tableName = "entity_histories",
    indices = [
        Index(value = ["entity_id"]),
        Index(value = ["entity_id", "version"], unique = true)
    ]
)
data class EntityHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    
    @ColumnInfo(name = "entity_id")
    val entityId: String,
    
    @ColumnInfo(name = "version")
    val version: Int,
    
    @ColumnInfo(name = "snapshot_json")
    val snapshotJson: String,
    
    @ColumnInfo(name = "changed_by")
    val changedBy: String,
    
    @ColumnInfo(name = "change_reason")
    val changeReason: String,
    
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)
