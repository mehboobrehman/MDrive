package com.architect.driveaggregator.sharing

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.architect.driveaggregator.data.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class SharingManagerTest {

    private lateinit var db: AppDatabase
    private lateinit var sharingLinkDao: SharingLinkDao
    private lateinit var sharingManager: SharingManager
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        sharingLinkDao = db.sharingLinkDao()
        sharingManager = SharingManager(context, sharingLinkDao) { providerId ->
            if (providerId == "fail") throw IOException("Token error")
            "mock_token_$providerId"
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testGenerateShareLinkSuccess() = runBlocking {
        val options = ShareOptions(permission = "VIEW", isPublic = true, expirationDurationMs = 3600000L)
        val result = sharingManager.generateShareLink("file_123", "googledrive", "gfile_456", options)
        
        assertTrue(result.isSuccess)
        val link = result.getOrNull()
        assertNotNull(link)
        assertEquals("file_123", link?.virtualFileId)
        assertEquals("googledrive", link?.providerId)
        assertEquals("VIEW", link?.permission)
        assertTrue(link?.isPublic == true)
        assertTrue(link?.expiresAt ?: 0L > System.currentTimeMillis())
        assertTrue(link?.shareUrl?.contains("gfile_456") == true)
    }

    @Test
    fun testRevokeShareLink() = runBlocking {
        val options = ShareOptions(permission = "EDIT", isPublic = false, expirationDurationMs = 0L)
        val genResult = sharingManager.generateShareLink("file_789", "onedrive", "ofile_999", options)
        assertTrue(genResult.isSuccess)
        val linkId = genResult.getOrNull()!!.linkId

        val revokeResult = sharingManager.revokeShareLink(linkId)
        assertTrue(revokeResult.isSuccess)

        val validateResult = sharingManager.validateShareLink(linkId)
        assertTrue(validateResult.isFailure)
    }

    @Test
    fun testValidateExpiredLink() = runBlocking {
        val options = ShareOptions(permission = "VIEW", isPublic = true, expirationDurationMs = -1000L) // Already expired
        val genResult = sharingManager.generateShareLink("file_exp", "dropbox", "dfile_111", options)
        assertTrue(genResult.isSuccess)
        val linkId = genResult.getOrNull()!!.linkId

        val validateResult = sharingManager.validateShareLink(linkId)
        assertTrue(validateResult.isFailure)
    }

    @Test
    fun testMultiProviderSupport() = runBlocking {
        val providers = listOf("googledrive", "onedrive", "dropbox", "box", "mega", "terabox")
        for (provider in providers) {
            val options = ShareOptions(permission = "DOWNLOAD", isPublic = true)
            val result = sharingManager.generateShareLink("file_$provider", provider, "pfile_$provider", options)
            assertTrue("Failed for provider $provider", result.isSuccess)
            assertNotNull(result.getOrNull()?.shareUrl)
        }
    }
}
