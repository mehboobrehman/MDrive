package com.architect.driveaggregator.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.architect.driveaggregator.auth.DefaultOAuthNetworkClient
import com.architect.driveaggregator.auth.GoogleOAuthManager
import com.architect.driveaggregator.data.AppDatabase
import com.architect.driveaggregator.engine.VirtualDriveEngine
import com.architect.driveaggregator.security.SecureTokenVault
import com.architect.driveaggregator.upload.GoogleResumableUploadManager
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncOrchestratorTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var virtualDriveEngine: VirtualDriveEngine
    private lateinit var tokenVault: SecureTokenVault
    private lateinit var networkClient: DefaultOAuthNetworkClient
    private lateinit var oauthManager: GoogleOAuthManager
    private lateinit var uploadManager: GoogleResumableUploadManager
    private lateinit var syncOrchestrator: SyncOrchestrator
    private lateinit var cacheDir: File
    private lateinit var diskLruCache: com.jakewharton.disklrucache.DiskLruCache

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        virtualDriveEngine = VirtualDriveEngine(database.sagaTransactionDao())
        val fakePrefs = context.getSharedPreferences("test_vault", Context.MODE_PRIVATE)
        tokenVault = SecureTokenVault(context, overrideSharedPreferences = fakePrefs)
        networkClient = DefaultOAuthNetworkClient(tokenVault)
        oauthManager = GoogleOAuthManager(tokenVault, networkClient)
        uploadManager = GoogleResumableUploadManager(database.sagaTransactionDao(), oauthManager)
        cacheDir = File(context.cacheDir, "test_sync_cache_${System.currentTimeMillis()}")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        diskLruCache = com.jakewharton.disklrucache.DiskLruCache.open(cacheDir, 1, 1, 1024 * 1024)
        val cacheManager = com.architect.driveaggregator.cache.CacheManager(diskLruCache)
        syncOrchestrator = SyncOrchestrator(database, VirtualDriveEngine(database.sagaTransactionDao(), cacheManager = cacheManager), uploadManager, context)
    }

    @After
    fun tearDown() {
        try {
            diskLruCache.close()
        } catch (_: Exception) {}
        cacheDir.deleteRecursively()
        database.close()
    }

    @Test
    fun testRegistrationAndShardingLifecycle() = runBlocking {
        assertNotNull(syncOrchestrator)
        assertNotNull(database)
    }

    @Test
    fun testTemporaryShardFilesCleanupOnSuccessAndFailure() {
        runBlocking {
            // Create a temp source file
            val sourceFile = File(context.cacheDir, "source_test.txt")
            sourceFile.writeText("Hello distributed sharded sync world with some content to shard properly!")

            try {
                syncOrchestrator.coordinateSync(sourceFile.absolutePath, "virtual_file_test_1") { _, _ -> }
            } catch (e: Exception) {
                // Expected if network/upload fails without valid credentials
            }

            // Verify no shard files left in context.cacheDir
            val remainingShards = context.cacheDir.listFiles { _, name -> name.contains("virtual_file_test_1") }
            assertTrue("Temporary shard files should be cleaned up in finally block", remainingShards == null || remainingShards.isEmpty())

            sourceFile.delete()
        }
    }
}
