package com.architect.driveaggregator.upload

import com.architect.driveaggregator.auth.GoogleOAuthManager
import com.architect.driveaggregator.data.FileShardEntity
import com.architect.driveaggregator.data.SagaTransactionDao
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

/**
 * Implements the Google Drive Resumable Upload protocol (uploadType=resumable)
 * with exponential backoff, jitter, session persistence, and saga orchestrator integration.
 */
class GoogleResumableUploadManager(
    private val sagaTransactionDao: SagaTransactionDao,
    private val oauthManager: GoogleOAuthManager,
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
) {

    companion object {
        private const val RESUMABLE_INIT_URL = "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable"
        private const val DRIVE_FILES_API_BASE = "https://www.googleapis.com/drive/v3/files/"
        const val DEFAULT_CHUNK_SIZE = 256 * 1024L // 256 KB Google recommended minimum chunk multiple
        const val MAX_RETRIES = 5
        const val INITIAL_BACKOFF_MS = 1000L
    }

    /**
     * Initiates a resumable upload session on Google Drive for a given file shard.
     * Returns the session URI (Location header).
     */
    suspend fun initiateResumableSession(
        accountId: String,
        fileName: String,
        mimeType: String,
        fileSize: Long,
        parentFolderId: String? = null
    ): String {
        if (accountId.startsWith("mock_acc_")) {
            return "https://mock.googleapis.com/upload/drive/v3/files?session=mock_${UUID.randomUUID()}"
        }

        val accessToken = oauthManager.getValidAccessToken(accountId)
            ?: throw IllegalStateException("No valid access token found for account: $accountId")

        val metadata = JSONObject().apply {
            put("name", fileName)
            if (!parentFolderId.isNullOrBlank()) {
                put("parents", org.json.JSONArray().put(parentFolderId))
            }
        }

        val requestBody = metadata.toString().toRequestBody("application/json; charset=UTF-8".toMediaType())

        val request = Request.Builder()
            .url(RESUMABLE_INIT_URL)
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Type", "application/json; charset=UTF-8")
            .header("X-Upload-Content-Type", mimeType)
            .header("X-Upload-Content-Length", fileSize.toString())
            .post(requestBody)
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 200 && response.code != 201) {
                val errorBody = response.body?.string() ?: "Unknown error"
                throw RuntimeException("Failed to initiate resumable session: HTTP ${response.code} - $errorBody")
            }

            val sessionUri = response.header("Location")
                ?: throw IllegalStateException("Google Drive did not return a Location header for resumable upload session.")
            return sessionUri
        }
    }

    /**
     * Queries Google Drive for the current uploaded offset of an active resumable session.
     * Used when resuming interrupted transfers.
     */
    suspend fun queryUploadStatus(
        accountId: String,
        sessionUri: String,
        totalSize: Long
    ): Long {
        if (accountId.startsWith("mock_acc_")) {
            return sagaTransactionDao.getShardBySessionUri(sessionUri)?.lastKnownUploadedOffset ?: 0L
        }

        val accessToken = oauthManager.getValidAccessToken(accountId)
            ?: throw IllegalStateException("No valid access token found for account: $accountId")

        val request = Request.Builder()
            .url(sessionUri)
            .header("Authorization", "Bearer $accessToken")
            .header("Content-Range", "bytes */$totalSize")
            .put(ByteArray(0).toRequestBody(null))
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (response.code == 308) {
                // Resume Incomplete status code
                val rangeHeader = response.header("Range")
                if (rangeHeader != null) {
                    // Range format: bytes=0-49999
                    val parts = rangeHeader.split("-")
                    if (parts.size == 2) {
                        val lastByteIndex = parts[1].toLongOrNull() ?: -1L
                        if (lastByteIndex >= 0) {
                            return lastByteIndex + 1 // Next byte offset to upload
                        }
                    }
                }
                return 0L
            } else if (response.isSuccessful || response.code == 201) {
                // Already fully uploaded
                return totalSize
            } else {
                val errorBody = response.body?.string() ?: "Unknown error"
                throw RuntimeException("Failed to query upload status: HTTP ${response.code} - $errorBody")
            }
        }
    }

    /**
     * Uploads a shard data file or stream using the resumable upload protocol with exponential backoff,
     * jitter, and Room DAO persistence updates.
     */
    suspend fun uploadShard(
        shard: FileShardEntity,
        dataSourceFile: File,
        chunkSize: Long = DEFAULT_CHUNK_SIZE
    ): String {
        // 1. Update state to UPLOADING
        sagaTransactionDao.updateShardState(shard.shardId, "UPLOADING")

        var sessionUri = shard.resumableSessionUri
        val accountId = shard.targetAccountId
        val totalSize = shard.shardSize

        // 2. If no session URI exists or if we need to verify/get one
        if (sessionUri.isNullOrBlank()) {
            try {
                sessionUri = initiateResumableSession(
                    accountId = accountId,
                    fileName = "shard_${shard.shardIndex}_${shard.virtualFileId}",
                    mimeType = "application/octet-stream",
                    fileSize = totalSize
                )
                sagaTransactionDao.updateShardProgress(
                    shardId = shard.shardId,
                    remoteFileId = shard.remoteFileId,
                    sessionUri = sessionUri,
                    offset = shard.lastKnownUploadedOffset
                )
            } catch (e: Exception) {
                sagaTransactionDao.updateShardState(shard.shardId, "FAILED")
                throw e
            }
        }

        // 3. Query current uploaded offset (for resume support)
        var uploadedOffset = 0L
        try {
            uploadedOffset = queryUploadStatus(accountId, sessionUri, totalSize)
            if (uploadedOffset >= totalSize) {
                sagaTransactionDao.updateShardState(shard.shardId, "COMPLETED")
                return shard.remoteFileId ?: "resumed_completed"
            }
            sagaTransactionDao.updateShardOffset(shard.shardId, uploadedOffset)
        } catch (e: Exception) {
            // If session expired or invalid, re-initiate
            try {
                sessionUri = initiateResumableSession(
                    accountId = accountId,
                    fileName = "shard_${shard.shardIndex}_${shard.virtualFileId}",
                    mimeType = "application/octet-stream",
                    fileSize = totalSize
                )
                uploadedOffset = 0L
                sagaTransactionDao.updateShardProgress(
                    shardId = shard.shardId,
                    remoteFileId = shard.remoteFileId,
                    sessionUri = sessionUri,
                    offset = 0L
                )
            } catch (initEx: Exception) {
                sagaTransactionDao.updateShardState(shard.shardId, "FAILED")
                throw initEx
            }
        }

        // 4. Stream and upload remaining chunks with exponential backoff & jitter
        if (accountId.startsWith("mock_acc_")) {
            var currentOffset = uploadedOffset
            while (currentOffset < totalSize) {
                val step = min(chunkSize, totalSize - currentOffset)
                delay(30)
                currentOffset += step
                sagaTransactionDao.updateShardOffset(shard.shardId, currentOffset)
                android.util.Log.d("MockUpload", "Shard ${shard.shardId} progress: $currentOffset/$totalSize")
            }
            val mockRemoteId = "mock_file_${UUID.randomUUID()}"
            sagaTransactionDao.updateShardProgress(
                shardId = shard.shardId,
                remoteFileId = mockRemoteId,
                sessionUri = sessionUri,
                offset = totalSize
            )
            sagaTransactionDao.updateShardState(shard.shardId, "COMPLETED")
            return mockRemoteId
        }

        var finalRemoteId = shard.remoteFileId
        RandomAccessFile(dataSourceFile, "r").use { raf ->
            raf.seek(uploadedOffset)
            val buffer = ByteArray(chunkSize.toInt())

            while (uploadedOffset < totalSize) {
                val bytesToRead = min(chunkSize, totalSize - uploadedOffset).toInt()
                raf.readFully(buffer, 0, bytesToRead)

                var attempt = 0
                var chunkSuccess = false

                while (attempt < MAX_RETRIES && !chunkSuccess) {
                    try {
                        val accessToken = oauthManager.getValidAccessToken(accountId)
                            ?: throw IllegalStateException("Access token unavailable for account: $accountId")

                        val endByte = uploadedOffset + bytesToRead - 1
                        val contentRange = "bytes $uploadedOffset-$endByte/$totalSize"

                        val requestBody = buffer.copyOfRange(0, bytesToRead)
                            .toRequestBody("application/octet-stream".toMediaType())

                        val request = Request.Builder()
                            .url(sessionUri)
                            .header("Authorization", "Bearer $accessToken")
                            .header("Content-Range", contentRange)
                            .header("Content-Length", bytesToRead.toString())
                            .put(requestBody)
                            .build()

                        okHttpClient.newCall(request).execute().use { response ->
                            if (response.code == 308) {
                                // Incomplete, chunk accepted
                                uploadedOffset += bytesToRead
                                sagaTransactionDao.updateShardOffset(shard.shardId, uploadedOffset)
                                chunkSuccess = true
                            } else if (response.isSuccessful || response.code == 201) {
                                // Upload complete!
                                uploadedOffset = totalSize
                                val responseBodyStr = response.body?.string()
                                if (!responseBodyStr.isNullOrBlank()) {
                                    val json = JSONObject(responseBodyStr)
                                    finalRemoteId = json.optString("id", finalRemoteId ?: "")
                                }
                                sagaTransactionDao.updateShardProgress(
                                    shardId = shard.shardId,
                                    remoteFileId = finalRemoteId,
                                    sessionUri = sessionUri,
                                    offset = uploadedOffset
                                )
                                sagaTransactionDao.updateShardState(shard.shardId, "COMPLETED")
                                chunkSuccess = true
                            } else if (response.code in 500..599 || response.code == 429 || response.code == 408) {
                                // Server error or rate limit, retry with backoff
                                throw IOException("Transient server error: HTTP ${response.code}")
                            } else {
                                // Client error or unrecoverable error
                                val errBody = response.body?.string() ?: ""
                                throw RuntimeException("Unrecoverable upload error: HTTP ${response.code} - $errBody")
                            }
                        }
                    } catch (e: Exception) {
                        attempt++
                        if (attempt >= MAX_RETRIES) {
                            sagaTransactionDao.updateShardState(shard.shardId, "FAILED")
                            throw RuntimeException("Failed chunk upload after $MAX_RETRIES retries: ${e.message}", e)
                        }
                        // Exponential backoff with jitter: 2^attempt * 1000ms + random jitter
                        val baseDelay = INITIAL_BACKOFF_MS * (2.0.pow(attempt.toDouble())).toLong()
                        val jitter = (Math.random() * 500).toLong()
                        val delayTime = min(baseDelay + jitter, 30000L)
                        delay(delayTime)
                    }
                }
            }
        }

        return finalRemoteId ?: "completed"
    }

    /**
     * Executes the saga transaction for uploading a virtual file across its shards.
     * Handles rollbacks / compensation if any shard fails.
     */
    suspend fun executeVirtualFileSaga(
        virtualFileId: String,
        shardFiles: Map<String, File> // map of shardId -> File containing shard data
    ): Boolean {
        // Mark virtual file as SHARDING/UPLOADING
        sagaTransactionDao.updateSagaState(virtualFileId, "UPLOADING")
        val shards = sagaTransactionDao.getShardsForVirtualFile(virtualFileId)

        try {
            for (shard in shards) {
                val file = shardFiles[shard.shardId]
                    ?: throw IllegalArgumentException("Data file not provided for shard: ${shard.shardId}")
                uploadShard(shard, file)
            }

            // All shards completed successfully
            sagaTransactionDao.updateSagaState(virtualFileId, "COMMITTED")
            return true
        } catch (e: Exception) {
            // Trigger Compensation / Rollback
            compensateSaga(virtualFileId, shards)
            return false
        }
    }

    /**
     * Compensation logic for rolling back failed saga transactions.
     * Marks shards and virtual file as ROLLBACK / FAILED and cleans up remote files if needed.
     */
    private suspend fun compensateSaga(virtualFileId: String, shards: List<FileShardEntity>) {
        sagaTransactionDao.updateSagaState(virtualFileId, "ROLLBACK")
        for (shard in shards) {
            if (shard.uploadState == "COMPLETED" && !shard.remoteFileId.isNullOrBlank()) {
                // Optionally delete uploaded remote file to maintain atomicity across drives
                try {
                    deleteRemoteFile(shard.targetAccountId, shard.remoteFileId!!)
                } catch (_: Exception) {
                    // Ignore cleanup errors during compensation
                }
            }
            sagaTransactionDao.updateShardState(shard.shardId, "FAILED")
        }
        sagaTransactionDao.updateSagaState(virtualFileId, "FAILED")
    }

    private suspend fun deleteRemoteFile(accountId: String, remoteFileId: String) {
        val accessToken = oauthManager.getValidAccessToken(accountId) ?: return
        val request = Request.Builder()
            .url("$DRIVE_FILES_API_BASE$remoteFileId")
            .header("Authorization", "Bearer $accessToken")
            .delete()
            .build()
        okHttpClient.newCall(request).execute().close()
    }
}

private class IOException(message: String) : Exception(message)
