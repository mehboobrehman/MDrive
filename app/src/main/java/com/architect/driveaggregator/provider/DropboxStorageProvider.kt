package com.architect.driveaggregator.provider

import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * DropboxStorageProvider integrates Dropbox API v2 for cloud storage aggregation.
 * Endpoints:
 * - Quota: POST https://api.dropboxapi.com/2/users/get_space_usage
 * - Initiate upload session (start): POST https://content.dropboxapi.com/2/files/upload_session/start
 * - Upload chunk (append): POST https://content.dropboxapi.com/2/files/upload_session/append_v2
 * - Finish upload session: POST https://content.dropboxapi.com/2/files/upload_session/finish
 * - Delete file: POST https://api.dropboxapi.com/2/files/delete_v2
 */
class DropboxStorageProvider(
    override val providerId: String,
    private val accessTokenProvider: suspend () -> String,
    private val apiBaseUrl: String = "https://api.dropboxapi.com/2",
    private val contentBaseUrl: String = "https://content.dropboxapi.com/2"
) : StorageProvider {

    override suspend fun getQuota(): StorageQuota = executeWithRetry {
        val token = accessTokenProvider()
        val url = URL("$apiBaseUrl/users/get_space_usage")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 15000
            readTimeout = 15000
        }

        // Empty body or {} for get_space_usage
        OutputStreamWriter(connection.outputStream).use { it.write("{}") }

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
        val used = json.optLong("used", 0L)
        val allocationObj = json.optJSONObject("allocation") ?: JSONObject()
        val allocated = allocationObj.optLong("allocated", used + 10L * 1024 * 1024 * 1024) // fallback 10GB if unallocated
        val free = (allocated - used).coerceAtLeast(0L)

        StorageQuota(
            totalBytes = allocated,
            usedBytes = used,
            freeBytes = free
        )
    }

    override suspend fun initiateUpload(fileName: String, sizeBytes: Long): ResumableUploadSession = executeWithRetry {
        val token = accessTokenProvider()
        val url = URL("$contentBaseUrl/files/upload_session/start")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/octet-stream")
            val argsHeader = JSONObject().apply { put("close", false) }.toString()
            setRequestProperty("Dropbox-API-Arg", argsHeader)
            doOutput = true
            connectTimeout = 15000
            readTimeout = 15000
        }

        // Send empty start or initial 0 bytes chunk session
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
        val sessionId = json.optString("session_id", "ds_${System.currentTimeMillis()}")
        val remotePath = "/$fileName"

        ResumableUploadSession(
            uploadUrl = sessionId,
            remoteFileId = remotePath,
            providerId = providerId
        )
    }

    override suspend fun uploadChunk(session: ResumableUploadSession, chunk: ChunkData): ChunkUploadResult = executeWithRetry {
        val token = accessTokenProvider()
        val sessionId = session.uploadUrl
        val isFinal = (chunk.offset + chunk.data.size) >= chunk.totalSize

        val endpoint = if (isFinal) {
            "$contentBaseUrl/files/upload_session/finish"
        } else {
            "$contentBaseUrl/files/upload_session/append_v2"
        }

        val url = URL(endpoint)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/octet-stream")

            val apiArg = if (isFinal) {
                JSONObject().apply {
                    put("cursor", JSONObject().apply {
                        put("session_id", sessionId)
                        put("offset", chunk.offset)
                    })
                    put("commit", JSONObject().apply {
                        put("path", session.remoteFileId)
                        put("mode", "add")
                        put("autorename", true)
                        put("mute", false)
                    })
                }.toString()
            } else {
                JSONObject().apply {
                    put("cursor", JSONObject().apply {
                        put("session_id", sessionId)
                        put("offset", chunk.offset)
                    })
                    put("close", false)
                }.toString()
            }

            setRequestProperty("Dropbox-API-Arg", apiArg)
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
        val url = URL("$apiBaseUrl/files/delete_v2")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 15000
            readTimeout = 15000
        }

        val requestBody = JSONObject().apply {
            put("path", remoteFileId)
        }.toString()

        OutputStreamWriter(connection.outputStream).use { it.write(requestBody) }

        val responseCode = connection.responseCode
        if (responseCode == 429) {
            val retryAfter = connection.getHeaderField("Retry-After")?.toLongOrNull() ?: 5L
            throw ProviderHttpException(429, "Rate limited", retryAfter)
        }
        responseCode in 200..299
    }
}
