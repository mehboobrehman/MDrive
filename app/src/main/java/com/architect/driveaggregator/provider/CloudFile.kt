package com.architect.driveaggregator.provider

import java.io.InputStream

/**
 * Represents a file or folder item in a cloud storage provider.
 */
data class CloudFile(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val mimeType: String,
    val createdAt: Long,
    val downloadUrl: String?,
    val isFolder: Boolean
)
