package com.architect.driveaggregator.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entity representing the primary Google ID OAuth session and linked provider associations.
 */
@Entity(tableName = "primary_account_session")
data class PrimaryAccountSessionEntity(
    @PrimaryKey
    val accountId: String, // Google user ID or email as primary key
    val email: String,
    val displayName: String?,
    val photoUrl: String?,
    val accessToken: String?,
    val refreshToken: String?,
    val tokenExpiresAt: Long,
    val linkedProvidersJson: String?, // JSON or comma-separated list of linked providers (OneDrive, Dropbox, etc.)
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
