package com.architect.driveaggregator.cache

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.architect.driveaggregator.data.AppDatabase
import com.architect.driveaggregator.data.VirtualFileDao
import com.architect.driveaggregator.data.VirtualFileEntity
import com.architect.driveaggregator.security.EncryptionManager
import com.architect.driveaggregator.security.SecureTokenVault
import com.jakewharton.disklrucache.DiskLruCache
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class CacheManagerTest {

    private lateinit var context: Context
    private lateinit var diskLruCache: DiskLruCache
    private lateinit var encryptionManager: EncryptionManager
    private lateinit var secureTokenVault: SecureTokenVault
    private lateinit var database: AppDatabase
    private lateinit var virtualFileDao: VirtualFileDao
    private lateinit var cacheManager: CacheManager
    private lateinit var cacheDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        cacheDir = File(context.cacheDir, "test_cache_${System.currentTimeMillis()}")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        diskLruCache = DiskLruCache.open(cacheDir, 1, 1, 10L * 1024 * 1024)
        
        val sharedPrefs = context.getSharedPreferences("test_vault", Context.MODE_PRIVATE)
        secureTokenVault = SecureTokenVault(context, sharedPrefs)
        encryptionManager = EncryptionManager(context, secureTokenVault)
        
        database = AppDatabase.getInstance(context)
        virtualFileDao = database.virtualFileDao()

        cacheManager = CacheManager(
            diskLruCache = diskLruCache,
            encryptionManager = encryptionManager,
            virtualFileDao = virtualFileDao,
            secureTokenVault = secureTokenVault,
            context = context
        )
    }

    @After
    fun tearDown() {
        try {
            diskLruCache.close()
        } catch (_: Exception) {}
        cacheDir.deleteRecursively()
        try {
            database.close()
        } catch (_: Exception) {}
    }

    @Test
    fun testEncryptedPutAndGet() = runBlocking {
        val key = "secure_test_file_id"
        val originalText = "Top-secret encrypted LRU cache payload for MDrive!"
        val inputStream = ByteArrayInputStream(originalText.toByteArray())

        val success = cacheManager.putEncrypted(key, inputStream)
        assertTrue(success)
        assertTrue(cacheManager.contains(key))

        val retrievedStream = cacheManager.getEncrypted(key)
        assertNotNull(retrievedStream)
        val decryptedText = retrievedStream?.bufferedReader()?.use { it.readText() }
        assertEquals(originalText, decryptedText)
    }

    @Test
    fun testOfflinePinManagement() = runBlocking {
        val fileId = "offline_pinned_file_123"
        val originalText = "Offline pinned file content protected with E2EE."
        val inputStream = ByteArrayInputStream(originalText.toByteArray())

        // Insert virtual file entity first
        val entity = VirtualFileEntity(
            virtualFileId = fileId,
            fileName = "Document.pdf",
            fileSize = originalText.length.toLong(),
            mimeType = "application/pdf",
            sagaState = "COMMITTED"
        )
        virtualFileDao.insertFile(entity)

        val pinned = cacheManager.pinFileOffline(fileId, inputStream)
        assertTrue(pinned)
        assertTrue(cacheManager.isOfflinePinned(fileId))
        assertTrue(cacheManager.contains(fileId))

        val offlineStream = cacheManager.getOfflinePinnedFile(fileId)
        assertNotNull(offlineStream)
        val content = offlineStream?.bufferedReader()?.use { it.readText() }
        assertEquals(originalText, content)

        // Verify Room entity updated
        val updatedEntity = virtualFileDao.getAllFiles().find { it.virtualFileId == fileId }
        assertNotNull(updatedEntity)
        assertTrue(updatedEntity!!.isOfflinePinned)

        // Test unpin
        val unpinned = cacheManager.unpinFileOffline(fileId)
        assertTrue(unpinned)
        assertFalse(cacheManager.isOfflinePinned(fileId))
    }

    @Test
    fun testBandwidthAwarePrefetch() = runBlocking {
        val streams = mapOf(
            "file1" to ByteArrayInputStream("Data 1".toByteArray()),
            "file2" to ByteArrayInputStream("Data 2".toByteArray())
        )

        val prefetchedCount = cacheManager.prefetchFiles(streams, pinToOffline = false)
        assertEquals(2, prefetchedCount)
        assertTrue(cacheManager.contains("file1"))
        assertTrue(cacheManager.contains("file2"))
    }

    @Test
    fun testCacheMiss() = runBlocking {
        assertFalse(cacheManager.contains("non_existent_key"))
        assertNull(cacheManager.getEncrypted("non_existent_key"))
        assertNull(cacheManager.getOfflinePinnedFile("non_existent_key"))
    }
}
