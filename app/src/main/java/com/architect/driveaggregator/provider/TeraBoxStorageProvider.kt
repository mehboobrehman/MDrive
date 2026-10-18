package com.architect.driveaggregator.provider

import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * TeraBoxStorageProvider integrates TeraBox cloud storage API for storage aggregation.
 * Endpoints:
 * - Quota: GET https://c-api.terabox.com/api/quota
 * - Initiate upload session: POST https://c-api.terabox.com/rest/2.0/pcs/file?method=upload
 * - Upload chunk: PUT chunk endpoint
 * - Delete file: POST https://c-api.terabox.com/rest/2.0/pcs/file?method=delete
 */
class TeraBoxStorageProvider(
    override val providerId: String,
    private val accessTokenProvider: suspend () -> String,
    private val apiBaseUrl: String = "https://c-api.terabox.com"
) : StorageProvider {

    override suspend fun getQuota(): StorageQuota = executeWithRetry {
        val token = accessTokenProvider()
        val url = URL("$apiBaseUrl/api/quota?access_token=$token")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
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
        val total = json.optLong("total", 1024L * 1024 * 1024 * 1024) // 1TB default TeraBox free/promotional tier
        val used = json.optLong("used", 0L)
        val free = (total - used).coerceAtLeast(0L)

        StorageQuota(
            totalBytes = total,
            usedBytes = used,
            freeBytes = free
        )
    }

    override suspend fun initiateUpload(fileName: String, sizeBytes: Long): ResumableUploadSession = executeWithRetry {
        val token = accessTokenProvider()
        val url = URL("$apiBaseUrl/rest/2.0/pcs/file?method=upload&access_token=$token&path=/apps/mdrive/$fileName")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 15000
            readTimeout = 15000
        }

        val requestBody = JSONObject().apply {
            put("size", sizeBytes)
            put("name", fileName)
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
        val uploadUrl = json.optString("upload_url", "$apiBaseUrl/rest/2.0/pcs/file?method=upload_chunk&access_token=$token")
        val remoteFileId = json.optString("path", "/apps/mdrive/$fileName")

        ResumableUploadSession(
            uploadUrl = uploadUrl,
            remoteFileId = remoteFileId,
            providerId = providerId
        )
    }

    override suspend fun uploadChunk(session: ResumableUploadSession, chunk: ChunkData): ChunkUploadResult = executeWithRetry {
        val token = accessTokenProvider()
        val url = URL("${session.uploadUrl}&part_seq=${chunk.chunkIndex}&offset=${chunk.offset}&access_token=$token")
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
        val url = URL("$apiBaseUrl/rest/2.0/pcs/file?method=delete&access_token=$token")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
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
