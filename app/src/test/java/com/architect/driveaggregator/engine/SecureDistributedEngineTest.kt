package com.architect.driveaggregator.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SecureDistributedEngineTest {

    private lateinit var context: Context
    private lateinit var engine: SecureDistributedEngine
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        engine = SecureDistributedEngine(dataShardCount = 4, parityShardCount = 2)
        tempDir = File(context.cacheDir, "rs_test_${System.currentTimeMillis()}")
        tempDir.mkdirs()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testEncodeAndReconstruct_perfectRecovery() {
        // Create a test file with some distinct binary and text content (e.g. 100 bytes)
        val sourceFile = File(tempDir, "test_source.bin")
        val originalBytes = ByteArray(100) { (it * 7).toByte() }
        sourceFile.writeBytes(originalBytes)

        // Encode file into 6 shards (4 data + 2 parity)
        val shards = engine.encodeFile(sourceFile)
        assertEquals(6, shards.size)

        // Verify shard metadata
        for (shard in shards) {
            assertEquals(100L, shard.originalFileSize)
            assertNotNull(shard.sha256Checksum)
            assertEquals(64, shard.sha256Checksum.length)
            assertEquals(shard.data.size, shards[0].data.size) // all shards equal size
        }

        // Collect all shards into a map
        val shardMap = shards.associateBy { it.shardIndex }

        // Reconstruct file using all available shards
        val restoredFile = File(tempDir, "restored_perfect.bin")
        engine.reconstructFile(shardMap, restoredFile)

        assertTrue(restoredFile.exists())
        val restoredBytes = restoredFile.readBytes()
        assertArrayEquals(originalBytes, restoredBytes)
    }

    @Test
    fun testReconstruct_withMissingShardsUpToParityCount() {
        val sourceFile = File(tempDir, "test_missing.bin")
        val originalBytes = "MDrive Secure Distributed Engine Reed-Solomon Erasure Coding Test Payload 2026!".toByteArray()
        sourceFile.writeBytes(originalBytes)

        val shards = engine.encodeFile(sourceFile)
        val shardMap = shards.associateBy { it.shardIndex }.toMutableMap()

        // Remove 2 shards (e.g. Data shard 1 and Parity shard 4) - we still have 4 out of 6 shards
        shardMap.remove(1)
        shardMap.remove(5)
        assertEquals(4, shardMap.size)

        val restoredFile = File(tempDir, "restored_missing.bin")
        engine.reconstructFile(shardMap, restoredFile)

        assertTrue(restoredFile.exists())
        assertArrayEquals(originalBytes, restoredFile.readBytes())
    }

    @Test(expected = IllegalArgumentException::class)
    fun testReconstruct_failsWhenTooManyShardsMissing() {
        val sourceFile = File(tempDir, "test_toomany.bin")
        sourceFile.writeBytes(ByteArray(50) { it.toByte() })

        val shards = engine.encodeFile(sourceFile)
        val shardMap = shards.associateBy { it.shardIndex }.toMutableMap()

        // Remove 3 shards (leaving only 3 data/parity shards, but need at least 4)
        shardMap.remove(0)
        shardMap.remove(1)
        shardMap.remove(4)
        assertEquals(3, shardMap.size)

        val restoredFile = File(tempDir, "restored_fail.bin")
        engine.reconstructFile(shardMap, restoredFile)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testReconstruct_failsOnCorruptedShardChecksum() {
        val sourceFile = File(tempDir, "test_corrupt.bin")
        sourceFile.writeBytes(ByteArray(64) { it.toByte() })

        val shards = engine.encodeFile(sourceFile)
        val shardMap = shards.associateBy { it.shardIndex }.toMutableMap()

        // Corrupt data inside shard 0 by modifying bytes without updating checksum
        val corruptedShard = shardMap[0]!!
        val tamperedData = corruptedShard.data.clone()
        tamperedData[0] = (tamperedData[0] + 1).toByte()
        
        shardMap[0] = corruptedShard.copy(data = tamperedData)

        val restoredFile = File(tempDir, "restored_corrupt.bin")
        engine.reconstructFile(shardMap, restoredFile)
    }
}
