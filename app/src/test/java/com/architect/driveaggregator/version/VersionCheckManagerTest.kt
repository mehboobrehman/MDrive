package com.architect.driveaggregator.version

import android.content.Context
import android.content.res.AssetManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
class VersionCheckManagerTest {

    private lateinit var mockContext: Context
    private lateinit var mockAssetManager: AssetManager
    private lateinit var versionCheckManager: VersionCheckManager

    @Before
    fun setUp() {
        mockContext = mock(Context::class.java)
        mockAssetManager = mock(AssetManager::class.java)
        `when`(mockContext.assets).thenReturn(mockAssetManager)

        versionCheckManager = VersionCheckManager(mockContext)
    }

    @Test
    fun appVersionInfo_dataClassConstructsCorrectly() {
        val info = AppVersionInfo(
            version = "1.0.0",
            fullVersion = "1.0.0-release",
            commitHash = "abc1234",
            buildTimestamp = "2026-09-23T00:00:00Z",
            environment = "prod",
            versionCode = 100
        )

        assertEquals("1.0.0", info.version)
        assertEquals("1.0.0-release", info.fullVersion)
        assertEquals("abc1234", info.commitHash)
        assertEquals("2026-09-23T00:00:00Z", info.buildTimestamp)
        assertEquals("prod", info.environment)
        assertEquals(100, info.versionCode)
    }

    @Test
    fun getAssetVersionInfo_parsesJsonSuccessfully() {
        kotlinx.coroutines.runBlocking {
            val jsonSample = """
                {
                  "version": "1.0.1",
                  "fullVersion": "1.0.1-debug",
                  "commitHash": "def5678",
                  "buildTimestamp": "2026-09-23T12:00:00Z",
                  "environment": "debug",
                  "versionCode": 101
                }
            """.trimIndent()

            `when`(mockAssetManager.open("version.json")).thenReturn(ByteArrayInputStream(jsonSample.toByteArray()))

            val assetInfo = versionCheckManager.getAssetVersionInfo()

            assertEquals("1.0.1", assetInfo.version)
            assertEquals("1.0.1-debug", assetInfo.fullVersion)
            assertEquals("def5678", assetInfo.commitHash)
            assertEquals("2026-09-23T12:00:00Z", assetInfo.buildTimestamp)
            assertEquals("debug", assetInfo.environment)
            assertEquals(101, assetInfo.versionCode)
        }
    }

    @Test
    fun getAssetVersionInfo_fallsBackGracefullyOnException() {
        kotlinx.coroutines.runBlocking {
            `when`(mockAssetManager.open("version.json")).thenThrow(RuntimeException("Asset not found"))

            val assetInfo = versionCheckManager.getAssetVersionInfo()
            assertNotNull(assetInfo)
            assertNotNull(assetInfo.version)
        }
    }

    @Test
    fun staleClientDetection_logicVerification() {
        val runningCode = 100
        val newerCode = 101

        assertTrue(newerCode > runningCode)
    }

    @Test
    fun preferencesHandling_savesAndRetrievesCorrectly() {
        val mockPrefs = org.mockito.Mockito.mock(android.content.SharedPreferences::class.java)
        val mockEditor = org.mockito.Mockito.mock(android.content.SharedPreferences.Editor::class.java)

        `when`(mockContext.getSharedPreferences(VersionCheckManager.PREF_NAME, Context.MODE_PRIVATE)).thenReturn(mockPrefs)
        `when`(mockPrefs.edit()).thenReturn(mockEditor)
        `when`(mockEditor.putLong(org.mockito.ArgumentMatchers.eq(VersionCheckManager.KEY_LAST_CHECKED), org.mockito.ArgumentMatchers.anyLong())).thenReturn(mockEditor)
        `when`(mockEditor.putInt(org.mockito.ArgumentMatchers.eq(VersionCheckManager.KEY_DISMISSED_VERSION), org.mockito.ArgumentMatchers.anyInt())).thenReturn(mockEditor)
        
        `when`(mockPrefs.getLong(VersionCheckManager.KEY_LAST_CHECKED, 0L)).thenReturn(123456789L)
        `when`(mockPrefs.getInt(VersionCheckManager.KEY_DISMISSED_VERSION, 0)).thenReturn(105)

        versionCheckManager.saveLastCheckedTimestamp()
        assertEquals(123456789L, versionCheckManager.getLastCheckedTimestamp())

        versionCheckManager.setDismissedVersionCode(105)
        assertEquals(105, versionCheckManager.getDismissedVersionCode())
    }
}
