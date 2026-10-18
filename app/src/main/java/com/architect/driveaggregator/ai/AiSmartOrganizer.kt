package com.architect.driveaggregator.ai

import android.content.Context
import android.util.Log
import com.architect.driveaggregator.data.SmartRecommendationDao
import com.architect.driveaggregator.data.SmartRecommendationEntity
import com.architect.driveaggregator.data.VirtualFileDao
import com.architect.driveaggregator.data.VirtualFileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * AiSmartOrganizer engine for on-device file categorization, SHA-256 duplicate detection,
 * and storage cleanup recommendations.
 */
class AiSmartOrganizer(
    private val context: Context,
    private val virtualFileDao: VirtualFileDao,
    private val smartRecommendationDao: SmartRecommendationDao
) {

    companion object {
        private const val TAG = "AiSmartOrganizer"
        
        // Category constants
        const val CATEGORY_DOCUMENTS = "Documents"
        const val CATEGORY_MEDIA = "Media"
        const val CATEGORY_ARCHIVES = "Archives"
        const val CATEGORY_CODE = "Code"
        const val CATEGORY_OTHER = "Other"
    }

    /**
     * Categorizes a file based on MIME type and file extension heuristics.
     */
    fun categorizeFile(mimeType: String?, fileName: String?): String {
        val mime = mimeType?.lowercase() ?: ""
        val name = fileName?.lowercase() ?: ""

        // 1. Check MIME type first
        if (mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/")) {
            return CATEGORY_MEDIA
        }
        if (mime.contains("pdf") || mime.contains("msword") || 
            mime.contains("officedocument") || mime.contains("text/plain") ||
            mime.contains("rtf") || mime.contains("epub+zip")) {
            return CATEGORY_DOCUMENTS
        }
        if (mime.contains("zip") || mime.contains("tar") || mime.contains("compressed") ||
            mime.contains("rar") || mime.contains("7z") || mime.contains("gzip")) {
            return CATEGORY_ARCHIVES
        }
        if (mime.contains("json") || mime.contains("xml") || mime.contains("javascript") ||
            mime.contains("x-python") || mime.contains("java") || mime.contains("x-sh") ||
            mime.contains("html") || mime.contains("css") || mime.contains("sql")) {
            return CATEGORY_CODE
        }

        // 2. Fallback to file extension heuristics
        val extension = name.substringAfterLast('.', "")
        return when (extension) {
            in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "mp4", "mkv", "avi", "mov", "mp3", "wav", "flac", "m4a", "aac") -> CATEGORY_MEDIA
            in listOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "odt", "ods", "odp", "epub") -> CATEGORY_DOCUMENTS
            in listOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz") -> CATEGORY_ARCHIVES
            in listOf("kt", "java", "py", "js", "ts", "html", "css", "json", "xml", "c", "cpp", "h", "rs", "go", "sql", "sh", "bat") -> CATEGORY_CODE
            else -> CATEGORY_OTHER
        }
    }

    /**
     * Computes the cryptographic SHA-256 hash of an InputStream.
     */
    suspend fun computeSha256(inputStream: InputStream): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var bytesRead: Int
        try {
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                if (bytesRead > 0) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
        } finally {
            try {
                inputStream.close()
            } catch (e: Exception) {
                // Ignore close exception
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Computes the SHA-256 hash of a local File.
     */
    suspend fun computeFileSha256(file: File): String = withContext(Dispatchers.IO) {
        file.inputStream().use { computeSha256(it) }
    }

    /**
     * Scans indexed virtual files, updates their AI categories and SHA-256 hashes,
     * detects duplicates across providers, and generates storage cleanup recommendations.
     */
    suspend fun runSmartOrganizationAndCleanup(): List<SmartRecommendationEntity> = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting Smart Organization and Cleanup analysis...")
        
        // 1. Get all virtual files from Room DB
        val allFiles = virtualFileDao.getAllFiles()

        // 2. Categorize and update files if category is missing or Other
        val updatedFiles = mutableListOf<VirtualFileEntity>()
        for (file in allFiles) {
            val category = if (file.aiCategory.isNullOrBlank() || file.aiCategory == CATEGORY_OTHER) {
                categorizeFile(file.mimeType, file.fileName)
            } else {
                file.aiCategory!!
            }
            
            val updated = file.copy(aiCategory = category)
            updatedFiles.add(updated)
            virtualFileDao.insertFile(updated)
        }

        val recommendations = mutableListOf<SmartRecommendationEntity>()
        val currentTime = System.currentTimeMillis()

        // 3. Duplicate detection via SHA-256 hash grouping
        val filesWithHash = updatedFiles.filter { !it.sha256Hash.isNullOrBlank() }
        val hashGroups = filesWithHash.groupBy { it.sha256Hash }

        for ((hash, duplicates) in hashGroups) {
            if (duplicates.size > 1) {
                val sorted = duplicates.sortedByDescending { it.updatedAt }
                val keepFile = sorted.first()
                val duplicateFiles = sorted.drop(1)
                val wastedBytes = duplicateFiles.sumOf { it.fileSize }

                val rec = SmartRecommendationEntity(
                    title = "Duplicate Files Detected",
                    description = "Found ${duplicateFiles.size} duplicate copies of '${keepFile.fileName}'. Reclaim ${formatBytes(wastedBytes)}.",
                    category = "DUPLICATE",
                    potentialSavingsBytes = wastedBytes,
                    relatedFileIdsJson = com.google.gson.Gson().toJson(duplicateFiles.map { it.virtualFileId }),
                    isDismissed = false,
                    createdAt = currentTime
                )
                recommendations.add(rec)
                smartRecommendationDao.insertRecommendation(rec)
                Log.d(TAG, "Detected duplicate group for hash $hash: ${duplicateFiles.size} duplicates, wasting $wastedBytes bytes.")
            }
        }

        // 4. Large unused files recommendation (> 50 MB, updated > 90 days ago)
        val ninetyDaysAgo = currentTime - (90L * 24 * 60 * 60 * 1000)
        val largeOldFiles = updatedFiles.filter { it.fileSize > 50L * 1024 * 1024 && it.updatedAt < ninetyDaysAgo }
        if (largeOldFiles.isNotEmpty()) {
            val totalLargeBytes = largeOldFiles.sumOf { it.fileSize }
            val rec = SmartRecommendationEntity(
                title = "Large Inactive Files",
                description = "Found ${largeOldFiles.size} large files (>50MB) untouched for over 90 days, occupying ${formatBytes(totalLargeBytes)}.",
                category = "LARGE_FILE",
                potentialSavingsBytes = totalLargeBytes,
                relatedFileIdsJson = com.google.gson.Gson().toJson(largeOldFiles.map { it.virtualFileId }),
                isDismissed = false,
                createdAt = currentTime
            )
            recommendations.add(rec)
            smartRecommendationDao.insertRecommendation(rec)
        }

        Log.d(TAG, "Smart organization completed. Generated ${recommendations.size} recommendations.")
        recommendations
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        val gb = mb / 1024.0
        return "%.2f GB".format(gb)
    }
}
