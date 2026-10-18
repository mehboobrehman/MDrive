package com.architect.driveaggregator.provider

import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * MegaStorageProvider integrates MEGA cloud storage API for storage aggregation.
 * Endpoints:
 * - Quota: POST / Quota check endpoint
 * - Initiate upload session: POST upload endpoint
 * - Upload chunk: PUT chunk endpoint
 * - Delete file: DELETE file endpoint
 */
class MegaStorageProvider(
    override val providerId: String,
    private val accessTokenProvider: suspend () -> String,
    private val apiBaseUrl: String = "https://g.api.mega.co.nz",
    private val contentBaseUrl: String = "https://chunk.mega.co.nz"
) : StorageProvider {

    override suspend fun getQuota(): StorageQuota = executeWithRetry {
        val token = accessTokenProvider()
        val url = URL("$apiBaseUrl/cs?id=0&ak=$token")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 15000
            readTimeout = 15000
        }

        val requestBody = "[{\"a\": \"uq\", \"s\": 1}]"
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
        // MEGA returns array or JSON object, let's parse robustly
        var total = 50L * 1024 * 1024 * 1024 // 50GB default free tier
        var used = 0L
        try {
            if (responseText.startsWith("[")) {
                val jsonArr = org.json.JSONArray(responseText)
                if (jsonArr.length() > 0) {
                    val obj = jsonArr.optJSONObject(0)
                    if (obj != null) {
                        total = obj.optLong("space_total", total)
                        used = obj.optLong("space_used", used)
                    }
                }
            } else if (responseText.startsWith("{")) {
                val json = JSONObject(responseText)
                total = json.optLong("space_total", total)
                used = json.optLong("space_used", used)
            }
        } catch (_: Exception) {}

        val free = (total - used).coerceAtLeast(0L)
        StorageQuota(
            totalBytes = total,
            usedBytes = used,
            freeBytes = free
        )
    }

    override suspend fun initiateUpload(fileName: String, sizeBytes: Long): ResumableUploadSession = executeWithRetry {
        val token = accessTokenProvider()
        val url = URL("$apiBaseUrl/cs?id=0&ak=$token")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 15000
            readTimeout = 15000
        }

        val requestBody = "[{\"a\": \"u0\", \"s\": $sizeBytes, \"n\": \"$fileName\"}]"
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
        var uploadUrl = "$contentBaseUrl/upload"
        var remoteId = "mega_${System.currentTimeMillis()}"
        try {
            if (responseText.startsWith("[")) {
                val jsonArr = org.json.JSONArray(responseText)
                val obj = jsonArr.optJSONObject(0)
                if (obj != null) {
                    uploadUrl = obj.optString("p", uploadUrl)
                    remoteId = obj.optString("h", remoteId)
                }
            } else if (responseText.startsWith("{")) {
                val json = JSONObject(responseText)
                uploadUrl = json.optString("p", uploadUrl)
                remoteId = json.optString("h", remoteId)
            }
        } catch (_: Exception) {}

        ResumableUploadSession(
            uploadUrl = uploadUrl,
            remoteFileId = remoteId,
            providerId = providerId
        )
    }

    override suspend fun uploadChunk(session: ResumableUploadSession, chunk: ChunkData): ChunkUploadResult = executeWithRetry {
        val url = URL("${session.uploadUrl}/${chunk.offset}")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            setRequestProperty("Content-Type", "application/octet-stream")
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
        val url = URL("$apiBaseUrl/cs?id=0&ak=$token")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 15000
            readTimeout = 15000
        }

        val requestBody = "[{\"a\": \"d\", \"n\": \"$remoteFileId\"}]"
        OutputStreamWriter(connection.outputStream).use { it.write(requestBody) }

        val responseCode = connection.responseCode
        if (responseCode == 429) {
            val retryAfter = connection.getHeaderField("Retry-After")?.toLongOrNull() ?: 5L
            throw ProviderHttpException(429, "Rate limited", retryAfter)
        }
        responseCode in 200..299
    }
}
