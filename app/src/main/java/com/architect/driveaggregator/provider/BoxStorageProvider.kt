package com.architect.driveaggregator.provider

import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * BoxStorageProvider integrates Box API for cloud storage aggregation.
 * Endpoints:
 * - Quota: GET https://api.box.com/2.0/users/me
 * - Initiate upload session: POST https://upload.box.com/api/2.0/files/upload_sessions
 * - Upload chunk: PUT https://upload.box.com/api/2.0/files/upload_sessions/{upload_id}/{part_id}
 * - Delete file: DELETE https://api.box.com/2.0/files/{remoteFileId}
 */
class BoxStorageProvider(
    override val providerId: String,
    private val accessTokenProvider: suspend () -> String,
    private val apiBaseUrl: String = "https://api.box.com/2.0",
    private val uploadBaseUrl: String = "https://upload.box.com/api/2.0"
) : StorageProvider {

    override suspend fun getQuota(): StorageQuota = executeWithRetry {
        val token = accessTokenProvider()
        val url = URL("$apiBaseUrl/users/me")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/json")
            connectTimeout = 15000
            readTimeout = 15000
        }

        val responseCode = connection.responseCode
        if (responseCode == 429) {
            val retryAfter = connection.getHeaderField("Retry-After")?.toLongOrNull() ?: 5L
            throw ProviderHttpException(429, "Rate limited", retryAfter)
        }
        if (responseCode !in 200..299) {
            val errorMsg = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
            throw ProviderHttpException(responseCode, errorMsg)
        }

        val responseText = connection.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(responseText)
        val spaceAmount = json.optLong("space_amount", 10L * 1024 * 1024 * 1024)
        val spaceUsed = json.optLong("space_used", 0L)
        val free = (spaceAmount - spaceUsed).coerceAtLeast(0L)

        StorageQuota(
            totalBytes = spaceAmount,
            usedBytes = spaceUsed,
            freeBytes = free
        )
    }

    override suspend fun initiateUpload(fileName: String, sizeBytes: Long): ResumableUploadSession = executeWithRetry {
        val token = accessTokenProvider()
        val url = URL("$uploadBaseUrl/files/upload_sessions")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            doOutput = true
            connectTimeout = 15000
            readTimeout = 15000
        }

        val requestBody = JSONObject().apply {
            put("folder_id", "0") // Root folder
            put("file_name", fileName)
            put("file_size", sizeBytes)
        }.toString()

        OutputStreamWriter(connection.outputStream).use { it.write(requestBody) }

        val responseCode = connection.responseCode
        if (responseCode == 429) {
            val retryAfter = connection.getHeaderField("Retry-After")?.toLongOrNull() ?: 5L
            throw ProviderHttpException(429, "Rate limited", retryAfter)
        }
        if (responseCode !in 200..299) {
            val errorMsg = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
            throw ProviderHttpException(responseCode, errorMsg)
        }

        val responseText = connection.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(responseText)
        val sessionId = json.optString("id", "")
        // partSize available if needed for chunking granularity

        ResumableUploadSession(
            uploadUrl = "$uploadBaseUrl/files/upload_sessions/$sessionId",
            remoteFileId = sessionId,
            providerId = providerId
        )
    }

    override suspend fun uploadChunk(session: ResumableUploadSession, chunk: ChunkData): ChunkUploadResult = executeWithRetry {
        val token = accessTokenProvider()
        val rangeEnd = chunk.offset + chunk.data.size - 1
        val contentRange = "bytes ${chunk.offset}-$rangeEnd/${chunk.totalSize}"
        val url = URL(session.uploadUrl)

        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty("Digest", "sha=${java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-1").digest(chunk.data))}")
            setRequestProperty("Content-Range", contentRange)
            doOutput = true
            connectTimeout = 30000
            readTimeout = 30000
        }

        connection.outputStream.use { it.write(chunk.data) }

        val responseCode = connection.responseCode
        if (responseCode == 429) {
            val retryAfter = connection.getHeaderField("Retry-After")?.toLongOrNull() ?: 5L
            throw ProviderHttpException(429, "Rate limited", retryAfter)
        }
        if (responseCode !in 200..299) {
            val errorMsg = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
            return@executeWithRetry ChunkUploadResult(
                chunkIndex = chunk.chunkIndex,
                isSuccess = false,
                bytesUploaded = 0L,
                errorMessage = "HTTP $responseCode: $errorMsg"
            )
        }

        ChunkUploadResult(
            chunkIndex = chunk.chunkIndex,
            isSuccess = true,
            bytesUploaded = chunk.data.size.toLong(),
            remoteChunkId = session.remoteFileId
        )
    }

    override suspend fun deleteFile(remoteFileId: String): Boolean = executeWithRetry {
        val token = accessTokenProvider()
        val url = URL("$apiBaseUrl/files/$remoteFileId")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 15000
            readTimeout = 15000
        }

        val responseCode = connection.responseCode
        if (responseCode == 429) {
            val retryAfter = connection.getHeaderField("Retry-After")?.toLongOrNull() ?: 5L
            throw ProviderHttpException(429, "Rate limited", retryAfter)
        }
        responseCode in 200..299 || responseCode == 404
    }
}
