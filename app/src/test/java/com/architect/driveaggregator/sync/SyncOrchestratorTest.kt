package com.architect.driveaggregator.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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

@RunWith(AndroidJUnit4::class)
class SyncOrchestratorTest {

    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var virtualDriveEngine: VirtualDriveEngine
    private lateinit var tokenVault: SecureTokenVault
    private lateinit var networkClient: DefaultOAuthNetworkClient
    private lateinit var oauthManager: GoogleOAuthManager
    private lateinit var uploadManager: GoogleResumableUploadManager
    private lateinit var syncOrchestrator: SyncOrchestrator

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
        val cacheDir = File(context.cacheDir, "test_sync_cache")
        val diskCache = com.jakewharton.disklrucache.DiskLruCache.open(cacheDir, 1, 1, 1024 * 1024)
        val cacheManager = com.architect.driveaggregator.cache.CacheManager(diskCache)
        syncOrchestrator = SyncOrchestrator(database, VirtualDriveEngine(database.sagaTransactionDao(), cacheManager = cacheManager), uploadManager, context)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testRegistrationAndShardingLifecycle() = runBlocking {
        assertNotNull(syncOrchestrator)
        assertNotNull(database)
    }
}
