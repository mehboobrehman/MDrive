package com.architect.driveaggregator.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity representing a shard of a virtual file assigned to a specific target Google Drive account.
 * Tracks upload progress, remote file identification, resumable session URIs, and shard state.
 */
@Entity(
    tableName = "file_shards",
    foreignKeys = [
        ForeignKey(
            entity = VirtualFileEntity::class,
            parentColumns = ["virtualFileId"],
            childColumns = ["virtualFileId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["virtualFileId"]),
        Index(value = ["targetAccountId"])
    ]
)
data class FileShardEntity(
    @PrimaryKey
    val shardId: String,
    val virtualFileId: String,
    val shardIndex: Int,
    val targetAccountId: String,
    val shardSize: Long,
    val remoteFileId: String? = null,
    val resumableSessionUri: String? = null,
    val lastKnownUploadedOffset: Long = 0L,
    val uploadState: String, // PENDING, UPLOADING, COMPLETED, FAILED
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
