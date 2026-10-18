package com.architect.driveaggregator.ui

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.architect.driveaggregator.ai.AiSmartOrganizer
import com.architect.driveaggregator.data.SmartRecommendationDao
import com.architect.driveaggregator.data.SmartRecommendationEntity
import com.architect.driveaggregator.data.VirtualFileDao
import com.architect.driveaggregator.data.VirtualFileEntity
import com.architect.driveaggregator.ui.screens.AiSmartDashboardScreen
import com.architect.driveaggregator.ui.viewmodel.AiSmartDashboardViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@ExperimentalCoroutinesApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AiSmartDashboardScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var aiSmartOrganizer: AiSmartOrganizer
    private lateinit var virtualFileDao: VirtualFileDao
    private lateinit var smartRecommendationDao: SmartRecommendationDao
    private lateinit var viewModel: AiSmartDashboardViewModel

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        aiSmartOrganizer = mock(AiSmartOrganizer::class.java)
        virtualFileDao = mock(VirtualFileDao::class.java)
        smartRecommendationDao = mock(SmartRecommendationDao::class.java)

        run {
            kotlinx.coroutines.runBlocking {
                val file1 = VirtualFileEntity(
                    virtualFileId = "f1",
                    fileName = "Resume.pdf",
                    mimeType = "application/pdf",
                    fileSize = 2048576L,
                    sagaState = "COMMITTED",
                    createdAt = System.currentTimeMillis(),
                    aiCategory = AiSmartOrganizer.CATEGORY_DOCUMENTS,
                    sha256Hash = "hash1"
                )
                val file2 = VirtualFileEntity(
                    virtualFileId = "f2",
                    fileName = "Resume_dup.pdf",
                    mimeType = "application/pdf",
                    fileSize = 2048576L,
                    sagaState = "COMMITTED",
                    createdAt = System.currentTimeMillis(),
                    aiCategory = AiSmartOrganizer.CATEGORY_DOCUMENTS,
                    sha256Hash = "hash1"
                )
                whenever(virtualFileDao.getAllFiles()).thenReturn(listOf(file1, file2))
                whenever(smartRecommendationDao.getActiveRecommendations()).thenReturn(
                    flowOf(
                        listOf(
                            SmartRecommendationEntity(
                                id = 1L,
                                title = "Duplicate Files Detected",
                                description = "2 files share identical content hash. Reclaim 2.0 MB.",
                                category = "DUPLICATE",
                                potentialSavingsBytes = 2048576L,
                                relatedFileIdsJson = "[\"f1\", \"f2\"]",
                                isDismissed = false,
                                createdAt = System.currentTimeMillis()
                            )
                        )
                    )
                )
            }
        }

        viewModel = AiSmartDashboardViewModel(
            context.applicationContext as android.app.Application,
            aiSmartOrganizer,
            virtualFileDao,
            smartRecommendationDao
        )
    }

    @Test
    fun testAiSmartDashboard_displaysOverviewAndCategories() {
        composeTestRule.setContent {
            AiSmartDashboardScreen(
                viewModel = viewModel,
                onBack = {}
            )
        }

        // Verify title & summary cards
        composeTestRule.onNodeWithText("AI Smart Organization").assertIsDisplayed()
        composeTestRule.onNodeWithText("Total Managed Storage").assertIsDisplayed()
        composeTestRule.onNodeWithText("Storage by AI Category").assertIsDisplayed()
        composeTestRule.onNodeWithText(AiSmartOrganizer.CATEGORY_DOCUMENTS).assertIsDisplayed()

        // Switch to Duplicates tab
        composeTestRule.onNodeWithText("Duplicates").performClick()
        composeTestRule.onNodeWithText("Duplicate File Finder").assertIsDisplayed()
        composeTestRule.onNodeWithText("Duplicate Files Detected").assertIsDisplayed()

        // Switch to Categories tab
        composeTestRule.onNodeWithText("Categories").performClick()
        composeTestRule.onNodeWithText("Resume.pdf").assertIsDisplayed()
    }
}
