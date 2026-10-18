package com.architect.driveaggregator.ai

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.architect.driveaggregator.data.AppDatabase
import com.architect.driveaggregator.data.VirtualFileEntity
import com.architect.driveaggregator.contracts.CloudProviderType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AiSmartOrganizerTest {

    private lateinit.AppDatabase database
    private lateinit var aiSmartOrganizer: AiSmartOrganizer
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        
        aiSmartOrganizer = AiSmartOrganizer(
            context = context,
            virtualFileDao = database.virtualFileDao(),
            smartRecommendationDao = database.smartRecommendationDao()
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun testFileCategorization() {
        // Documents
        assertEquals(AiSmartOrganizer.CATEGORY_DOCUMENTS, aiSmartOrganizer.categorizeFile("application/pdf", "report.pdf"))
        assertEquals(AiSmartOrganizer.CATEGORY_DOCUMENTS, aiSmartOrganizer.categorizeFile("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "doc.docx"))
        assertEquals(AiSmartOrganizer.CATEGORY_DOCUMENTS, aiSmartOrganizer.categorizeFile(null, "notes.txt"))

        // Media
        assertEquals(AiSmartOrganizer.CATEGORY_MEDIA, aiSmartOrganizer.categorizeFile("image/png", "photo.png"))
        assertEquals(AiSmartOrganizer.CATEGORY_MEDIA, aiSmartOrganizer.categorizeFile("video/mp4", "video.mp4"))
        assertEquals(AiSmartOrganizer.CATEGORY_MEDIA, aiSmartOrganizer.categorizeFile(null, "song.mp3"))

        // Archives
        assertEquals(AiSmartOrganizer.CATEGORY_ARCHIVES, aiSmartOrganizer.categorizeFile("application/zip", "backup.zip"))
        assertEquals(AiSmartOrganizer.CATEGORY_ARCHIVES, aiSmartOrganizer.categorizeFile(null, "archive.tar.gz"))

        // Code
        assertEquals(AiSmartOrganizer.CATEGORY_CODE, aiSmartOrganizer.categorizeFile("text/x-python", "script.py"))
        assertEquals(AiSmartOrganizer.CATEGORY_CODE, aiSmartOrganizer.categorizeFile(null, "Main.kt"))

        // Other
        assertEquals(AiSmartOrganizer.CATEGORY_OTHER, aiSmartOrganizer.categorizeFile("application/octet-stream", "binary.bin"))
    }

    @Test
    fun testSha256Hashing() = runBlocking {
        val tempFile = File.createTempFile("test_hash", ".txt", context.cacheDir)
        tempFile.writeText("Hello MDrive AI Smart Organizer")

        val hash = aiSmartOrganizer.computeFileSha256(tempFile)
        assertNotNull(hash)
        assertEquals(64, hash.length) // SHA-256 hex string is 64 chars

        tempFile.delete()
    }

    @Test
    fun testDuplicateDetectionAndCleanupRecommendations() = runBlocking {
        val sharedHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        val file1 = VirtualFileEntity(
            fileId = "file_1",
            providerId = "provider_1",
            providerType = CloudProviderType.GOOGLE_DRIVE,
            name = "document.pdf",
            path = "/docs/document.pdf",
            sizeBytes = 1024L * 1024L * 10, // 10 MB
            mimeType = "application/pdf",
            lastModifiedTimestamp = System.currentTimeMillis(),
            aiCategory = AiSmartOrganizer.CATEGORY_DOCUMENTS,
            sha256Hash = sharedHash
        )

        val file2 = VirtualFileEntity(
            fileId = "file_2",
            providerId = "provider_2",
            providerType = CloudProviderType.ONEDRIVE,
            name = "document_copy.pdf",
            path = "/backup/document_copy.pdf",
            sizeBytes = 1024L * 1024L * 10, // 10 MB
            mimeType = "application/pdf",
            lastModifiedTimestamp = System.currentTimeMillis() - 1000L,
            aiCategory = AiSmartOrganizer.CATEGORY_DOCUMENTS,
            sha256Hash = sharedHash
        )

        database.virtualFileDao().insertFile(file1)
        database.virtualFileDao().insertFile(file2)

        val recommendations = aiSmartOrganizer.runSmartOrganizationAndCleanup()
        assertTrue(recommendations.isNotEmpty())

        val duplicateRec = recommendations.find { it.category == "DUPLICATE_CLEANUP" }
        assertNotNull(duplicateRec)
        assertEquals("Duplicate Files Detected", duplicateRec?.title)
        assertTrue(duplicateRec?.potentialSavingsBytes == 1024L * 1024L * 10)
    }
}
