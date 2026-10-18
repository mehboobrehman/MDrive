package com.architect.driveaggregator.search

import com.architect.driveaggregator.contracts.CloudProviderType
import com.architect.driveaggregator.data.VirtualFileDao
import com.architect.driveaggregator.data.VirtualFileEntity
import com.architect.driveaggregator.provider.CloudFile
import com.architect.driveaggregator.provider.StorageProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

@ExperimentalCoroutinesApi
class FederatedSearchManagerTest {

    private lateinit var virtualFileDao: VirtualFileDao
    private lateinit var storageProvider: StorageProvider
    private lateinit var storageProviders: Map<CloudProviderType, StorageProvider>
    private lateinit var federatedSearchManager: FederatedSearchManager

    @Before
    fun setUp() {
        virtualFileDao = mock(VirtualFileDao::class.java)
        storageProvider = mock(StorageProvider::class.java)
        whenever(storageProvider.providerId).thenReturn("GOOGLE_DRIVE")
        storageProviders = mapOf(CloudProviderType.GOOGLE_DRIVE to storageProvider)
        federatedSearchManager = FederatedSearchManager(virtualFileDao, storageProviders)
    }

    @Test
    fun testSearchLocal_returnsMappedResults() = runTest {
        val entity = VirtualFileEntity(
            virtualFileId = "file1",
            fileName = "TestDocument.pdf",
            mimeType = "application/pdf",
            fileSize = 1024L,
            sagaState = "COMMITTED",
            shareUrl = "https://example.com/file1",
            createdAt = 1000L,
            aiCategory = "Documents"
        )
        whenever(virtualFileDao.searchFilesAdvanced(any(), any(), any(), any(), any(), any()))
            .thenReturn(listOf(entity))

        val results = federatedSearchManager.searchLocal(SearchFilter(query = "Test"))

        assertEquals(1, results.size)
        assertEquals("file1", results[0].fileId)
        assertEquals("TestDocument.pdf", results[0].name)
        assertEquals(FederatedSearchResult.SearchSource.LOCAL_ROOM, results[0].source)
    }

    @Test
    fun testSearchRemoteConcurrently_filtersAndReturnsResults() = runTest {
        val cloudFile = CloudFile(
            id = "remote1",
            name = "Report.docx",
            sizeBytes = 2048L,
            mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            createdAt = 2000L,
            downloadUrl = "https://example.com/remote1",
            isFolder = false
        )
        whenever(storageProvider.listFiles("")).thenReturn(listOf(cloudFile))

        val results = federatedSearchManager.searchRemoteConcurrently(
            SearchFilter(query = "Report", mimeType = "wordprocessingml", minSize = 1000L)
        )

        assertEquals(1, results.size)
        assertEquals("remote1", results[0].fileId)
        assertEquals("Report.docx", results[0].name)
        assertEquals(FederatedSearchResult.SearchSource.REMOTE_PROVIDER, results[0].source)
    }

    @Test
    fun testSearchFederated_combinesLocalAndRemote() = runTest {
        val entity = VirtualFileEntity(
            virtualFileId = "local1",
            fileName = "Sheet.xlsx",
            mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            fileSize = 500L,
            sagaState = "COMMITTED",
            shareUrl = null,
            createdAt = 500L,
            aiCategory = "Documents"
        )
        whenever(virtualFileDao.searchFilesAdvanced(any(), any(), any(), any(), any(), any()))
            .thenReturn(listOf(entity))

        val cloudFile = CloudFile(
            id = "remote1",
            name = "Sheet.xlsx",
            sizeBytes = 500L,
            mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            createdAt = 500L,
            downloadUrl = "https://example.com/remote1",
            isFolder = false
        )
        whenever(storageProvider.listFiles("")).thenReturn(listOf(cloudFile))

        val results = federatedSearchManager.searchFederated(SearchFilter(query = "Sheet"))

        assertTrue(results.size >= 1)
    }
}
