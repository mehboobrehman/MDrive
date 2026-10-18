package com.architect.driveaggregator.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.architect.driveaggregator.data.AppDatabase
import com.architect.driveaggregator.sharing.ShareOptions
import com.architect.driveaggregator.sharing.SharingLinkDao
import com.architect.driveaggregator.sharing.SharingManager
import com.architect.driveaggregator.ui.screens.SharingBottomSheet
import com.architect.driveaggregator.ui.screens.SharingManagementScreen
import com.architect.driveaggregator.ui.viewmodel.SharingViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SharingUiRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var db: AppDatabase
    private lateinit var sharingLinkDao: SharingLinkDao
    private lateinit var sharingManager: SharingManager
    private lateinit var sharingViewModel: SharingViewModel
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        sharingLinkDao = db.sharingLinkDao()
        sharingManager = SharingManager(context, sharingLinkDao) { "mock_token" }
        sharingViewModel = SharingViewModel(context as android.app.Application, sharingManager)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testSharingBottomSheetDisplaysAndGeneratesLink() {
        sharingViewModel.openSharingSheet(
            virtualFileId = "file_test_1",
            providerId = "googledrive",
            providerFileId = "g_123",
            fileName = "ProjectReport.pdf"
        )

        composeTestRule.setContent {
            SharingBottomSheet(
                viewModel = sharingViewModel,
                onDismiss = {}
            )
        }

        composeTestRule.onNodeWithText("Share & Collaborate").assertIsDisplayed()
        composeTestRule.onNodeWithText("ProjectReport.pdf").assertIsDisplayed()

        composeTestRule.onNodeWithText("EDIT").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithText("DOWNLOAD").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithText("VIEW").assertIsDisplayed().performClick()

        composeTestRule.onNodeWithText("Generate Secure Link").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()
    }

    @Test
    fun testSharingManagementScreenDisplaysActiveLinks() {
        runBlocking {
            sharingManager.generateShareLink(
                virtualFileId = "file_mgmt_1",
                providerId = "onedrive",
                providerFileId = "o_456",
                options = ShareOptions(permission = "VIEW", isPublic = true, expirationDurationMs = 3600000L)
            )

            sharingViewModel.loadAllActiveLinks()
        }

        composeTestRule.setContent {
            SharingManagementScreen(
                viewModel = sharingViewModel,
                onNavigateBack = {}
            )
        }

        composeTestRule.onNodeWithText("Sharing & Link Management").assertIsDisplayed()
        composeTestRule.onNodeWithText("Active Collaboration Links (1)").assertIsDisplayed()
    }
}
