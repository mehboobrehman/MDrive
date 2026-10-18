package com.architect.driveaggregator.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity representing a virtual file being uploaded or managed across multiple cloud providers.
 * Tracks saga state, offline pinning, AI categorization, sharing URLs, and duplicate hashes.
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
    val updatedAt: Long = System.currentTimeMillis(),
    
    // v4 Schema Additions
    @ColumnInfo(name = "is_offline_pinned", defaultValue = "0")
    val isOfflinePinned: Boolean = false,
    
    @ColumnInfo(name = "ai_category")
    val aiCategory: String? = null,
    
    @ColumnInfo(name = "sha256_hash")
    val sha256Hash: String? = null,
    
    @ColumnInfo(name = "share_url")
    val shareUrl: String? = null,
    
    @ColumnInfo(name = "last_synced_timestamp", defaultValue = "0")
    val lastSyncedTimestamp: Long = 0L
)
