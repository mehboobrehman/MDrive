package com.architect.driveaggregator.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class DatabaseV4SchemaTest {

    private lateinit var database: AppDatabase
    private lateinit var sessionDao: PrimaryAccountSessionDao
    private lateinit var recommendationDao: SmartRecommendationDao
    private lateinit var sagaDao: SagaTransactionDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        sessionDao = database.primaryAccountSessionDao()
        recommendationDao = database.smartRecommendationDao()
        sagaDao = database.sagaTransactionDao()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        database.close()
    }

    @Test
    fun testVirtualFileV4Columns() = runBlocking {
        val virtualFile = VirtualFileEntity(
            virtualFileId = "vf_v4_1",
            fileName = "photo.jpg",
            fileSize = 2048000L,
            mimeType = "image/jpeg",
            sagaState = "COMMITTED",
            isOfflinePinned = true,
            aiCategory = "Photos",
            sha256Hash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            shareUrl = "https://mdrive.storage/share/xyz123",
            lastSyncedTimestamp = 1718000000000L
        )

        sagaDao.insertVirtualFile(virtualFile)

        val retrieved = sagaDao.getVirtualFile("vf_v4_1")
        assertNotNull(retrieved)
        assertTrue(retrieved!!.isOfflinePinned)
        assertEquals("Photos", retrieved.aiCategory)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", retrieved.sha256Hash)
        assertEquals("https://mdrive.storage/share/xyz123", retrieved.shareUrl)
        assertEquals(1718000000000L, retrieved.lastSyncedTimestamp)
    }

    @Test
    fun testPrimaryAccountSessionCrud() = runBlocking {
        val session = PrimaryAccountSessionEntity(
            accountId = "google_user_123",
            email = "user@gmail.com",
            displayName = "John Doe",
            photoUrl = "https://lh3.googleusercontent.com/photo",
            accessToken = "access_token_abc",
            refreshToken = "refresh_token_xyz",
            tokenExpiresAt = System.currentTimeMillis() + 3600000L,
            linkedProvidersJson = "[\"ONEDRIVE\",\"DROPBOX\"]"
        )

        sessionDao.insertOrUpdateSession(session)

        val retrieved = sessionDao.getPrimarySessionSync()
        assertNotNull(retrieved)
        assertEquals("google_user_123", retrieved!!.accountId)
        assertEquals("user@gmail.com", retrieved.email)
        assertEquals("John Doe", retrieved.displayName)
        assertEquals("[\"ONEDRIVE\",\"DROPBOX\"]", retrieved.linkedProvidersJson)

        sessionDao.clearSession()
        assertNull(sessionDao.getPrimarySessionSync())
    }

    @Test
    fun testSmartRecommendationsCrud() = runBlocking {
        val rec1 = SmartRecommendationEntity(
            title = "Duplicate Files Found",
            description = "You have 3 copies of 'report.pdf' across Google Drive and OneDrive.",
            category = "DUPLICATE",
            potentialSavingsBytes = 15000000L,
            relatedFileIdsJson = "[\"vf_1\",\"vf_2\",\"vf_3\"]",
            isDismissed = false
        )

        recommendationDao.insertRecommendation(rec1)

        val activeList = recommendationDao.getActiveRecommendations().first()
        assertEquals(1, activeList.size)
        assertEquals("Duplicate Files Found", activeList[0].title)

        recommendationDao.dismissRecommendation(activeList[0].id)
        val activeListAfterDismiss = recommendationDao.getActiveRecommendations().first()
        assertTrue(activeListAfterDismiss.isEmpty())

        recommendationDao.clearAll()
    }
}
