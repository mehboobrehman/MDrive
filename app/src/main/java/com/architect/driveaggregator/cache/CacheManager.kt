package com.architect.driveaggregator.cache

import com.jakewharton.disklrucache.DiskLruCache
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CacheManager @Inject constructor(
    private val diskLruCache: DiskLruCache
) {
    fun put(key: String, inputStream: InputStream) {
        var editor: DiskLruCache.Editor? = null
        try {
            editor = diskLruCache.edit(sanitizeKey(key))
            if (editor != null) {
                val outputStream: OutputStream = editor.newOutputStream(0)
                inputStream.copyTo(outputStream)
                outputStream.close()
                editor.commit()
            }
        } catch (e: Exception) {
            editor?.abort()
        }
    }

    fun get(key: String): InputStream? {
        return try {
            val snapshot = diskLruCache.get(sanitizeKey(key))
            snapshot?.getInputStream(0)
        } catch (e: Exception) {
            null
        }
    }

    fun contains(key: String): Boolean {
        return try {
            val snapshot = diskLruCache.get(sanitizeKey(key))
            snapshot?.use { true } ?: false
        } catch (e: Exception) {
            false
        }
    }

    fun remove(key: String) {
        try {
            diskLruCache.remove(sanitizeKey(key))
        } catch (e: Exception) {
            // Ignore removal errors
        }
    }

    fun clearCache() {
        try {
            diskLruCache.delete()
        } catch (e: Exception) {
            // Ignore clear errors
        }
    }

    fun getCacheSize(): Long {
        return try {
            diskLruCache.size()
        } catch (e: Exception) {
            0L
        }
    }

    fun getMaxSize(): Long {
        return 50 * 1024 * 1024L // Default 50MB max size as configured in CacheModule
    }

    private fun sanitizeKey(key: String): String {
        return key.lowercase().replace(Regex("[^a-z0-9_-]"), "_").take(64)
    }
}
