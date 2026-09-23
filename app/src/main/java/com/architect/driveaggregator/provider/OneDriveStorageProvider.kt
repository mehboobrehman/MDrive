package com.architect.driveaggregator.provider

import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * OneDriveStorageProvider integrates Microsoft Graph API for OneDrive storage aggregation.
 * Endpoints:
 * - Quota: GET https://graph.microsoft.com/v1.0/me/drive
 * - Initiate upload: POST https://graph.microsoft.com/v1.0/me/drive/root:/filename:/createUploadSession
 * - Upload chunk: PUT uploadUrl (Content-Range header)
 * - Delete file: DELETE https://graph.microsoft.com/v1.0/me/drive/items/{remoteFileId}
 */
class OneDriveStorageProvider(
    override val providerId: String,
    private val accessTokenProvider: suspend () -> String,
    private val baseUrl: String = "https://graph.microsoft.com/v1.0"
) : StorageProvider {

    override suspend fun getQuota(): StorageQuota = executeWithRetry {
        val token = accessTokenProvider()
        val url = URL("$baseUrl/me/drive")
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
        val quotaObj = json.optJSONObject("quota") ?: JSONObject()
        val total = quotaObj.optLong("total", 0L)
        val used = quotaObj.optLong("used", 0L)
        val remaining = quotaObj.optLong("remaining", (total - used).coerceAtLeast(0L))

        StorageQuota(
            totalBytes = total,
            usedBytes = used,
            freeBytes = remaining
        )
    }

    override suspend fun initiateUpload(fileName: String, sizeBytes: Long): ResumableUploadSession = executeWithRetry {
        val token = accessTokenProvider()
        val encodedFileName = java.net.URLEncoder.encode(fileName, "UTF-8")
        val url = URL("$baseUrl/me/drive/root:/$encodedFileName:/createUploadSession")
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
            put("item", JSONObject().apply {
                put("@microsoft.graph.conflictBehavior", "rename")
                put("name", fileName)
            })
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
        val uploadUrl = json.optString("uploadUrl", "")
        val remoteFileId = json.optString("id", fileName)

        ResumableUploadSession(
            uploadUrl = uploadUrl,
            remoteFileId = remoteFileId,
            providerId = providerId
        )
    }

    override suspend fun uploadChunk(session: ResumableUploadSession, chunk: ChunkData): ChunkUploadResult = executeWithRetry {
        val url = URL(session.uploadUrl)
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            setRequestProperty("Content-Length", chunk.data.size.toString())
            val rangeEnd = chunk.offset + chunk.data.size - 1
            setRequestProperty("Content-Range", "bytes ${chunk.offset}-$rangeEnd/${chunk.totalSize}")
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
        val url = URL("$baseUrl/me/drive/items/$remoteFileId")
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
