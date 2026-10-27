package com.architect.driveaggregator.sharing

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity representing a secure sharing link for a file or folder across cloud providers.
 */
@Entity(tableName = "sharing_links")
data class SharingLinkEntity(
    @PrimaryKey
    val linkId: String,
    val virtualFileId: String,
    val providerId: String,
    val providerFileId: String,
    val shareUrl: String,
    val permission: String, // "VIEW", "EDIT", "DOWNLOAD"
    val isPublic: Boolean,
    val expiresAt: Long, // 0 if never expires
    val createdAt: Long = System.currentTimeMillis(),
    val isRevoked: Boolean = false,
    val accessCount: Int = 0
)
