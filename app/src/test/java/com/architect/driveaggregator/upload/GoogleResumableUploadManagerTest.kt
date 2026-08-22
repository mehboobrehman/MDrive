package com.architect.driveaggregator.upload

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.architect.driveaggregator.auth.GoogleOAuthManager
import com.architect.driveaggregator.data.AppDatabase
import com.architect.driveaggregator.data.FileShardEntity
import com.architect.driveaggregator.data.VirtualFileEntity
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class GoogleResumableUploadManagerTest {

    private lateinit var database: AppDatabase
    private lateinit var context: Context
    private lateinit var oauthManager: GoogleOAuthManager
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var tempFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        oauthManager = mock()
        
        // Create temp test file with 1024 bytes of data
        tempFile = File.createTempFile("test_shard", ".bin", context.cacheDir)
        tempFile.writeBytes(ByteArray(1024) { it.toByte() })
    }

    @After
    fun tearDown() {
        database.close()
        if (tempFile.exists()) tempFile.delete()
    }

    @Test
    fun testInitiateResumableSession_success() = runBlocking {
        val mockCall = mock<Call>()
        val mockResponse = Response.Builder()
            .request(Request.Builder().url("https://www.googleapis.com/upload/drive/v3/files").build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Location", "https://www.googleapis.com/upload/drive/v3/files?upload_id=test_upload_123")
            .body("{".toResponseBody("application/json".toMediaType()))
            .build()

        whenever(oauthManager.getValidAccessToken("acc_1")).thenReturn("mock_token")
        
        val mockClient = mock<OkHttpClient>()
        whenever(mockClient.newCall(any())).thenReturn(mockCall)
        whenever(mockCall.execute()).thenReturn(mockResponse)

        val manager = GoogleResumableUploadManager(database.sagaTransactionDao(), oauthManager, mockClient)
        val sessionUri = manager.initiateResumableSession("acc_1", "test.bin", "application/octet-stream", 1024L)

        assertEquals("https://www.googleapis.com/upload/drive/v3/files?upload_id=test_upload_123", sessionUri)
    }

    @Test
    fun testQueryUploadStatus_incomplete() = runBlocking {
        val mockCall = mock<Call>()
        val mockResponse = Response.Builder()
            .request(Request.Builder().url("https://session_uri").build())
            .protocol(Protocol.HTTP_1_1)
            .code(308) // Resume Incomplete
            .message("Resume Incomplete")
            .header("Range", "bytes=0-511")
            .body("".toResponseBody(null))
            .build()

        whenever(oauthManager.getValidAccessToken("acc_1")).thenReturn("mock_token")

        val mockClient = mock<OkHttpClient>()
        whenever(mockClient.newCall(any())).thenReturn(mockCall)
        whenever(mockCall.execute()).thenReturn(mockResponse)

        val manager = GoogleResumableUploadManager(database.sagaTransactionDao(), oauthManager, mockClient)
        val nextOffset = manager.queryUploadStatus("acc_1", "https://session_uri", 1024L)

        assertEquals(512L, nextOffset)
    }

    @Test
    fun testUploadShard_successFlow() = runBlocking {
        val virtualFileId = "vf_1"
        val shardId = "shard_1"
        database.sagaTransactionDao().insertVirtualFile(
            VirtualFileEntity(virtualFileId, "test.bin", 1024L, "application/octet-stream", "PENDING")
        )
        database.sagaTransactionDao().insertFileShard(
            FileShardEntity(
                shardId = shardId,
                virtualFileId = virtualFileId,
                shardIndex = 0,
                targetAccountId = "acc_1",
                shardSize = 1024L,
                uploadState = "PENDING"
            )
        )

        val shard = database.sagaTransactionDao().getShardsForVirtualFile(virtualFileId)[0]

        val initResponse = Response.Builder()
            .request(Request.Builder().url("https://init").build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .header("Location", "https://session_uri")
            .body("{}".toResponseBody("application/json".toMediaType()))
            .build()

        val statusResponse = Response.Builder()
            .request(Request.Builder().url("https://session_uri").build())
            .protocol(Protocol.HTTP_1_1).code(308).message("Resume Incomplete")
            // No Range header to simulate 0 bytes uploaded
            .body("".toResponseBody(null))
            .build()

        val uploadResponse = Response.Builder()
            .request(Request.Builder().url("https://session_uri").build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body("{\"id\": \"remote_file_123\"}".toResponseBody("application/json".toMediaType()))
            .build()

        whenever(oauthManager.getValidAccessToken("acc_1")).thenReturn("mock_token")

        val mockCall = mock<Call>()
        whenever(mockCall.execute()).thenReturn(initResponse, statusResponse, uploadResponse)

        val mockClient = mock<OkHttpClient>()
        whenever(mockClient.newCall(any())).thenReturn(mockCall)

        val manager = GoogleResumableUploadManager(database.sagaTransactionDao(), oauthManager, mockClient)
        val remoteId = manager.uploadShard(shard, tempFile, chunkSize = 1024L)

        assertEquals("remote_file_123", remoteId)

        val updatedShard = database.sagaTransactionDao().getShardsForVirtualFile(virtualFileId)[0]
        assertEquals("COMPLETED", updatedShard.uploadState)
        assertEquals(1024L, updatedShard.lastKnownUploadedOffset)
    }

    @Test
    fun testSagaRollbackOnFailure() = runBlocking {
        val virtualFileId = "vf_saga_fail"
        database.sagaTransactionDao().insertVirtualFile(
            VirtualFileEntity(virtualFileId, "doc.pdf", 500L, "application/pdf", "PENDING")
        )
        database.sagaTransactionDao().insertFileShards(
            listOf(
                FileShardEntity("s1", virtualFileId, 0, "acc_1", 250L, uploadState = "PENDING"),
                FileShardEntity("s2", virtualFileId, 1, "acc_2", 250L, uploadState = "PENDING")
            )
        )

        val mockCall = mock<Call>()
        val errorResponse = Response.Builder()
            .request(Request.Builder().url("https://init").build())
            .protocol(Protocol.HTTP_1_1).code(403).message("Forbidden")
            .body("Quota exceeded".toResponseBody("text/plain".toMediaType()))
            .build()

        whenever(oauthManager.getValidAccessToken("acc_1")).thenReturn("mock_token")
        val mockClient = mock<OkHttpClient>()
        whenever(mockClient.newCall(any())).thenReturn(mockCall)
        whenever(mockCall.execute()).thenReturn(errorResponse)

        val manager = GoogleResumableUploadManager(database.sagaTransactionDao(), oauthManager, mockClient)

        val shardFiles = mapOf("s1" to tempFile, "s2" to tempFile)
        val success = manager.executeVirtualFileSaga(virtualFileId, shardFiles)

        assertFalse(success)

        val virtualFile = database.sagaTransactionDao().getVirtualFile(virtualFileId)
        assertEquals("FAILED", virtualFile?.sagaState)

        val shards = database.sagaTransactionDao().getShardsForVirtualFile(virtualFileId)
        assertEquals("FAILED", shards[0].uploadState)
        assertEquals("FAILED", shards[1].uploadState)
    }
}
