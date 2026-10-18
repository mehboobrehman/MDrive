package com.architect.driveaggregator.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.architect.driveaggregator.cache.CacheManager
import com.architect.driveaggregator.data.VirtualFileDao
import com.architect.driveaggregator.data.VirtualFileEntity
import com.architect.driveaggregator.ui.screens.OfflineCachingScreen
import com.architect.driveaggregator.ui.viewmodel.OfflineCacheViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class OfflineCachingScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var cacheManager: CacheManager
    private lateinit var virtualFileDao: VirtualFileDao
    private lateinit var viewModel: OfflineCacheViewModel

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        cacheManager = mock(CacheManager::class.java)
        virtualFileDao = mock(VirtualFileDao::class.java)
    }

    @Test
    fun testOfflineCachingScreen_displaysPinnedFilesAndStorage() {
        val pinnedFile = VirtualFileEntity(
            virtualFileId = "pin1",
            fileName = "ImportantOfflineDoc.pdf",
            mimeType = "application/pdf",
            fileSize = 5242880L, // 5MB
            sagaState = "COMMITTED",
            isOfflinePinned = true,
            aiCategory = "Documents",
            createdAt = System.currentTimeMillis()
        )

        run {
            kotlinx.coroutines.runBlocking {
                whenever(virtualFileDao.getOfflinePinnedFiles()).thenReturn(listOf(pinnedFile))
                whenever(cacheManager.getCacheSize()).thenReturn(15 * 1024 * 1024L) // 15MB
                whenever(cacheManager.getMaxSize()).thenReturn(50 * 1024 * 1024L) // 50MB
            }
        }

        viewModel = OfflineCacheViewModel(
            context.applicationContext as android.app.Application,
            cacheManager,
            virtualFileDao
        )

        composeTestRule.setContent {
            OfflineCachingScreen(
                viewModel = viewModel,
                onNavigateBack = {}
            )
        }

        // Verify title & storage breakdown
        composeTestRule.onNodeWithText("Offline Caching & Download Management").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cache & Storage Breakdown").assertIsDisplayed()
        composeTestRule.onNodeWithText("Used: 15.00 MB / 50.00 MB (Max)").assertIsDisplayed()
        composeTestRule.onNodeWithText("LRU Eviction Active").assertIsDisplayed()

        // Verify pinned file card & status badges
        composeTestRule.onNodeWithText("Pinned Offline Files (1)").assertIsDisplayed()
        composeTestRule.onNodeWithText("ImportantOfflineDoc.pdf").assertIsDisplayed()
        composeTestRule.onNodeWithText("PINNED").assertIsDisplayed()
        composeTestRule.onNodeWithText("5.0 MB").assertIsDisplayed()
    }

    @Test
    fun testClearCacheAction() {
        run {
            kotlinx.coroutines.runBlocking {
                whenever(virtualFileDao.getOfflinePinnedFiles()).thenReturn(emptyList())
                whenever(cacheManager.getCacheSize()).thenReturn(0L)
                whenever(cacheManager.getMaxSize()).thenReturn(50 * 1024 * 1024L)
            }
        }

        viewModel = OfflineCacheViewModel(
            context.applicationContext as android.app.Application,
            cacheManager,
            virtualFileDao
        )

        composeTestRule.setContent {
            OfflineCachingScreen(
                viewModel = viewModel,
                onNavigateBack = {}
            )
        }

        composeTestRule.onNodeWithText("Clear Cache").assertIsDisplayed()
        composeTestRule.onNodeWithText("Clear Cache").performClick()
        composeTestRule.waitForIdle()
    }
}
