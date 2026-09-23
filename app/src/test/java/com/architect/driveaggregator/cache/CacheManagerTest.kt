package com.architect.driveaggregator.cache

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.jakewharton.disklrucache.DiskLruCache
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
        cacheManager = CacheManager(diskLruCache)
    }

    @After
    fun tearDown() {
        try {
            diskLruCache.close()
        } catch (_: Exception) {}
        cacheDir.deleteRecursively()
    }

    @Test
    fun testPutAndGetCache() {
        val key = "test_file_id"
        val data = "Hello, MDrive Cache!".toByteArray()
        val inputStream = ByteArrayInputStream(data)

        cacheManager.put(key, inputStream)

        assertTrue(cacheManager.contains(key))

        val cachedStream = cacheManager.get(key)
        assertNotNull(cachedStream)
        val readText = cachedStream?.bufferedReader()?.use { it.readText() }
        assertEquals("Hello, MDrive Cache!", readText)
    }

    @Test
    fun testCacheMiss() {
        assertFalse(cacheManager.contains("non_existent_key"))
        assertNull(cacheManager.get("non_existent_key"))
    }
}
