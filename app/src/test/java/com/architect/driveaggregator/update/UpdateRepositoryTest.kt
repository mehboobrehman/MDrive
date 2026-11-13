package com.architect.driveaggregator.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class UpdateRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: UpdateRepository
    private lateinit var client: OkHttpClient
    private lateinit var context: Context

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder().build()
        context = ApplicationProvider.getApplicationContext()
        repository = UpdateRepository(context, client)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun testFetchLatestReleaseSuccess() = kotlinx.coroutines.test.runTest {
        val json = """
            {
                "tag_name": "v1.0.24",
                "name": "Release v1.0.24",
                "published_at": "2026-11-13T00:00:00Z",
                "body": "Bug fixes",
                "html_url": "${server.url("/release")}",
                "assets": [
                    {
                        "name": "MDrive-v1.0.24.apk",
                        "browser_download_url": "${server.url("/MDrive-v1.0.24.apk")}",
                        "size": 1024,
                        "content_type": "application/vnd.android.package-archive"
                    },
                    {
                        "name": "SHA256SUMS",
                        "browser_download_url": "${server.url("/SHA256SUMS")}",
                        "size": 100,
                        "content_type": "text/plain"
                    }
                ]
            }
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(json))

        val result = repository.fetchLatestRelease(server.url("/releases/latest").toString())
        assertTrue(result.isSuccess)
        val release = result.getOrNull()
        assertNotNull(release)
        assertEquals("v1.0.24", release?.tagName)
        assertEquals(2, release?.assets?.size)

        val validated = repository.validateAssets(release!!)
        assertNotNull(validated)
        assertEquals("MDrive-v1.0.24.apk", validated?.first?.name)
        assertEquals("SHA256SUMS", validated?.second?.name)
    }

    @Test
    fun testFetchLatestReleaseRateLimit() = kotlinx.coroutines.test.runTest {
        server.enqueue(MockResponse().setResponseCode(403).setHeader("X-RateLimit-Remaining", "0"))

        val result = repository.fetchLatestRelease(server.url("/releases/latest").toString())
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull() as? UpdateRepoException
        assertEquals(UpdateError.GITHUB_RATE_LIMIT, exception?.error)
    }

    @Test
    fun testFetchLatestReleaseNotFound() = kotlinx.coroutines.test.runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = repository.fetchLatestRelease(server.url("/releases/latest").toString())
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull() as? UpdateRepoException
        assertEquals(UpdateError.NO_PUBLISHED_RELEASE, exception?.error)
    }
}
