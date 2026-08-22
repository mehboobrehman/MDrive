package com.architect.driveaggregator.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity representing a virtual file being uploaded or managed across multiple Google Drive accounts.
 * Tracks saga state for outbox-pattern reliability and transaction consistency.
 */
@Entity(tableName = "virtual_files")
data class VirtualFileEntity(
    @PrimaryKey
    val virtualFileId: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val sagaState: String, // PENDING, SHARDING, COMMITTED, FAILED, ROLLBACK
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
