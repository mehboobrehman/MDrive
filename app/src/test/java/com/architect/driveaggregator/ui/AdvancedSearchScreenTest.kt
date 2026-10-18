package com.architect.driveaggregator.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.architect.driveaggregator.contracts.CloudProviderType
import com.architect.driveaggregator.data.VirtualFileDao
import com.architect.driveaggregator.data.VirtualFileEntity
import com.architect.driveaggregator.provider.CloudFile
import com.architect.driveaggregator.provider.StorageProvider
import com.architect.driveaggregator.search.FederatedSearchManager
import com.architect.driveaggregator.ui.screens.AdvancedSearchScreen
import com.architect.driveaggregator.ui.viewmodel.AdvancedSearchViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AdvancedSearchScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var virtualFileDao: VirtualFileDao
    private lateinit var storageProvider: StorageProvider
    private lateinit var federatedSearchManager: FederatedSearchManager
    private lateinit var viewModel: AdvancedSearchViewModel

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        virtualFileDao = mock(VirtualFileDao::class.java)
        storageProvider = mock(StorageProvider::class.java)
        whenever(storageProvider.providerId).thenReturn("GOOGLE_DRIVE")

        val storageProviders = mapOf(CloudProviderType.GOOGLE_DRIVE to storageProvider)
        federatedSearchManager = FederatedSearchManager(virtualFileDao, storageProviders)

        viewModel = AdvancedSearchViewModel(
            context.applicationContext as android.app.Application,
            federatedSearchManager
        )
    }

    @Test
    fun testAdvancedSearchScreen_displaysComponentsAndResults() {
        val entity = VirtualFileEntity(
            virtualFileId = "f1",
            fileName = "ProjectSpec.pdf",
            mimeType = "application/pdf",
            fileSize = 1048576L,
            sagaState = "COMMITTED",
            shareUrl = "https://example.com/spec",
            createdAt = System.currentTimeMillis(),
            aiCategory = "Documents"
        )
        run {
            kotlinx.coroutines.runBlocking {
                whenever(virtualFileDao.searchFilesAdvanced(any(), any(), any(), any(), any(), any()))
                    .thenReturn(listOf(entity))
                val cloudFile = CloudFile(
                    id = "c1",
                    name = "RemoteReport.docx",
                    sizeBytes = 204800L,
                    mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                    createdAt = System.currentTimeMillis(),
                    downloadUrl = "https://example.com/report",
                    isFolder = false
                )
                whenever(storageProvider.listFiles("")).thenReturn(listOf(cloudFile))
            }
        }

        viewModel.executeSearch()

        composeTestRule.setContent {
            AdvancedSearchScreen(viewModel = viewModel)
        }

        // Verify title & search bar exist
        composeTestRule.onNodeWithText("MDrive Advanced Search").assertIsDisplayed()
        composeTestRule.onNodeWithText("Search across all cloud accounts...").assertIsDisplayed()

        // Verify search results rendered with rich metadata and provider badges
        composeTestRule.onNodeWithText("ProjectSpec.pdf").assertIsDisplayed()
        composeTestRule.onNodeWithText("GOOGLE DRIVE").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cached").assertIsDisplayed()

        composeTestRule.onNodeWithText("RemoteReport.docx").assertIsDisplayed()
    }

    @Test
    fun testFilterChips_andQueryInput() {
        composeTestRule.setContent {
            AdvancedSearchScreen(viewModel = viewModel)
        }

        // Click a filter chip
        composeTestRule.onNodeWithText("📄 Documents").assertIsDisplayed()
        composeTestRule.onNodeWithText("📄 Documents").performClick()

        // Type query
        composeTestRule.onNodeWithText("Search across all cloud accounts...").performTextInput("Report")
        composeTestRule.waitForIdle()
    }
}
