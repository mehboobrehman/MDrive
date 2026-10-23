package com.architect.driveaggregator.provider

import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * GoogleDriveStorageProvider integrates Google Drive REST API v3.
 * Endpoints:
 * - Quota: GET https://www.googleapis.com/drive/v3/about?fields=storageQuota
 * - List files: GET https://www.googleapis.com/drive/v3/files
 * - Download file: GET https://www.googleapis.com/drive/v3/files/{fileId}?alt=media
 * - Upload: POST https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable
 * - Delete file: DELETE https://www.googleapis.com/drive/v3/files/{fileId}
 */
class GoogleDriveStorageProvider(
    override val providerId: String,
    private val accessTokenProvider: suspend () -> String,
    private val apiBaseUrl: String = "https://www.googleapis.com/drive/v3",
    private val uploadBaseUrl: String = "https://www.googleapis.com/upload/drive/v3"
) : StorageProvider {

    override suspend fun getQuota(): StorageQuota = executeWithRetry {
        val token = accessTokenProvider()
        val url = URL("$apiBaseUrl/about?fields=storageQuota")
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
        val json = JSONObject(responseText).getJSONObject("storageQuota")
        val total = json.optLong("limit", 0L)
        val used = json.optLong("usage", 0L)
        val free = (total - used).coerceAtLeast(0L)

        StorageQuota(
            totalBytes = total,
            usedBytes = used,
            freeBytes = free
        )
    }

    override suspend fun listFiles(folderId: String): List<CloudFile> = executeWithRetry {
        val token = accessTokenProvider()
        val qParam = if (folderId.isNotBlank() && folderId != "root") {
            "q='$folderId' in parents and trashed=false"
        } else {
            "q='root' in parents and trashed=false"
        }
        val url = URL("$apiBaseUrl/files?$qParam&pageSize=100&fields=files(id,name,size,mimeType,createdTime)")
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
        val files = json.optJSONArray("files") ?: org.json.JSONArray()
        val result = mutableListOf<CloudFile>()
        for (i in 0 until files.length()) {
            val file = files.getJSONObject(i)
            val mimeType = file.optString("mimeType", "application/octet-stream")
            val isFolder = mimeType == "application/vnd.google-apps.folder"
            result.add(CloudFile(
                id = file.optString("id"),
                name = file.optString("name"),
                sizeBytes = file.optLong("size", 0L),
                mimeType = mimeType,
                createdAt = 0L,
                downloadUrl = null,
                isFolder = isFolder
            ))
        }
        result
    }

    suspend fun downloadFile(remoteFileId: String): InputStream = executeWithRetry {
        val token = accessTokenProvider()
        val url = URL("$apiBaseUrl/files/$remoteFileId?alt=media")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 15000
            readTimeout = 30000
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

        connection.inputStream
    }

    suspend fun downloadChunk(remoteFileId: String, offset: Long, length: Long): InputStream = executeWithRetry {
        val token = accessTokenProvider()
        val url = URL("$apiBaseUrl/files/$remoteFileId?alt=media")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("Authorization", "Bearer $token")
            val rangeEnd = offset + length - 1
            setRequestProperty("Range", "bytes=$offset-$rangeEnd")
            connectTimeout = 15000
            readTimeout = 30000
        }

        val responseCode = connection.responseCode
        if (responseCode == 429) {
            val retryAfter = connection.getHeaderField("Retry-After")?.toLongOrNull() ?: 5L
            throw ProviderHttpException(429, "Rate limited", retryAfter)
        }
        if (responseCode !in 200..299 && responseCode != 206) {
            val errorMsg = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
            throw ProviderHttpException(responseCode, errorMsg)
        }

        connection.inputStream
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

    override suspend fun initiateUpload(fileName: String, sizeBytes: Long): ResumableUploadSession = executeWithRetry {
        val token = accessTokenProvider()
        val url = URL("$uploadBaseUrl/files?uploadType=resumable")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("X-Upload-Content-Length", sizeBytes.toString())
            doOutput = true
            connectTimeout = 15000
            readTimeout = 15000
        }

        val metadata = JSONObject().apply {
            put("name", fileName)
        }.toString()

        OutputStreamWriter(connection.outputStream).use { it.write(metadata) }

        val responseCode = connection.responseCode
        if (responseCode == 429) {
            val retryAfter = connection.getHeaderField("Retry-After")?.toLongOrNull() ?: 5L
            throw ProviderHttpException(429, "Rate limited", retryAfter)
        }
        if (responseCode !in 200..299) {
            val errorMsg = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown error"
            throw ProviderHttpException(responseCode, errorMsg)
        }

        val uploadUrl = connection.getHeaderField("Location") ?: throw ProviderHttpException(500, "Missing Location header")

        ResumableUploadSession(
            uploadUrl = uploadUrl,
            remoteFileId = fileName,
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
            setRequestProperty("Content-Length", chunk.data.size.toString())
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
        if (responseCode !in 200..308) { // 308 Resume Incomplete is standard Google Drive chunk success
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
}
