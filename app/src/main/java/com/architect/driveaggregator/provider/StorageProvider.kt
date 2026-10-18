package com.architect.driveaggregator.provider

import java.io.InputStream

/**
 * Storage quota information for a cloud provider account.
 */
data class StorageQuota(
    val totalBytes: Long,
    val usedBytes: Long,
    val freeBytes: Long
)

/**
 * Represents a resumable upload session with a remote cloud provider.
 */
data class ResumableUploadSession(
    val uploadUrl: String,
    val remoteFileId: String,
    val providerId: String,
    val expiresAt: Long = 0L
)

/**
 * Data payload representing a chunk/shard to be uploaded.
 */
data class ChunkData(
    val chunkIndex: Int,
    val data: ByteArray,
    val offset: Long,
    val totalSize: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ChunkData

        if (chunkIndex != other.chunkIndex) return false
        if (offset != other.offset) return false
        if (totalSize != other.totalSize) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = chunkIndex
        result = 31 * result + data.contentHashCode()
        result = 31 * result + offset.hashCode()
        result = 31 * result + totalSize.hashCode()
        return result
    }
}

/**
 * Result of uploading a chunk.
 */
data class ChunkUploadResult(
    val chunkIndex: Int,
    val isSuccess: Boolean,
    val bytesUploaded: Long,
    val remoteChunkId: String? = null,
    val errorMessage: String? = null
)

/**
 * Common StorageProvider interface for multi-cloud integration (Google Drive, OneDrive, Dropbox, Box).
 */
interface StorageProvider {
    val providerId: String

    /**
     * Retrieves current storage quota metrics (total, used, free bytes).
     */
    suspend fun getQuota(): StorageQuota

    /**
     * Initiates a resumable upload session for a file/shard of given size.
     */
    suspend fun initiateUpload(fileName: String, sizeBytes: Long): ResumableUploadSession

    /**
     * Uploads a chunk/shard within the resumable upload session.
     * Must handle rate limiting (429 Retry-After) robustly.
     */
    suspend fun uploadChunk(session: ResumableUploadSession, chunk: ChunkData): ChunkUploadResult

    /**
     * Deletes a remote file or shard by its remote file ID.
     */
    suspend fun deleteFile(remoteFileId: String): Boolean

    /**
     * Lists files from the provider repository.
     */
    suspend fun listFiles(folderId: String = ""): List<CloudFile> {
        return emptyList()
    }
}
