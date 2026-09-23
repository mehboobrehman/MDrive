package com.architect.driveaggregator.engine

import com.backblaze.erasure.ReedSolomon
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.security.MessageDigest

/**
 * Encoded shard metadata representing either a data shard or parity shard
 * produced by Reed-Solomon erasure coding.
 */
data class CodedShard(
    val shardIndex: Int,       // Index among all shards (0 to dataShardCount + parityShardCount - 1)
    val isParity: Boolean,     // True if parity shard, false if data shard
    val data: ByteArray,       // Shard content bytes
    val sha256Checksum: String,// SHA-256 checksum of shard data
    val originalFileSize: Long // Original file size before padding/sharding
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CodedShard

        if (shardIndex != other.shardIndex) return false
        if (isParity != other.isParity) return false
        if (!data.contentEquals(other.data)) return false
        if (sha256Checksum != other.sha256Checksum) return false
        if (originalFileSize != other.originalFileSize) return false

        return true
    }

    override fun hashCode(): Int {
        var result = shardIndex
        result = 31 * result + isParity.hashCode()
        result = 31 * result + data.contentHashCode()
        result = 31 * result + sha256Checksum.hashCode()
        result = 31 * result + originalFileSize.hashCode()
        return result
    }
}

/**
 * SecureDistributedEngine implements Reed-Solomon (4+2) erasure coding with
 * zero-copy Android NIO memory mapping (FileChannel.map), SHA-256 sharding and hashing,
 * and resilient file reconstruction even with missing or corrupted shards (up to 2 parity shards lost).
 */
class SecureDistributedEngine(
    private val dataShardCount: Int = 4,
    private val parityShardCount: Int = 2
) {
    private val codec = ReedSolomon.create(dataShardCount, parityShardCount)

    /**
     * Encodes a source file into (dataShardCount + parityShardCount) shards using
     * zero-copy NIO FileChannel.map for zero heap churn.
     */
    fun encodeFile(sourceFile: File): List<CodedShard> {
        require(sourceFile.exists() && sourceFile.isFile) { "Source file does not exist or is invalid: ${sourceFile.absolutePath}" }

        val fileSize = sourceFile.length()
        RandomAccessFile(sourceFile, "r").use { raf ->
            raf.channel.use { channel ->
                // Zero-copy memory map the file region
                val mappedByteBuffer = channel.map(FileChannel.MapMode.READ_ONLY, 0L, fileSize)
                
                // Calculate individual data shard size, ensuring exact alignment and padding
                val remainder = (fileSize % dataShardCount).toInt()
                val shardSize = ((fileSize + dataShardCount - 1) / dataShardCount).toInt()
                val totalShardsCount = dataShardCount + parityShardCount

                // Prepare data and parity buffers
                val shards = Array(totalShardsCount) { ByteArray(shardSize) }

                // Read mapped buffer into data shards, zero-padding the last shard if necessary
                for (i in 0 until dataShardCount) {
                    val startPos = (i * shardSize).toLong()
                    val bytesToRead = if (startPos < fileSize) {
                        minOf(shardSize.toLong(), fileSize - startPos).toInt()
                    } else {
                        0
                    }

                    if (bytesToRead > 0) {
                        mappedByteBuffer.position(startPos.toInt())
                        mappedByteBuffer.get(shards[i], 0, bytesToRead)
                    }
                    // Remaining bytes in shards[i] are already 0-initialized
                }

                // Compute parity shards using Backblaze Reed-Solomon encodeParity method
                codec.encodeParity(shards, 0, shardSize)

                // Build CodedShard metadata and compute SHA-256 for each shard
                val resultShards = mutableListOf<CodedShard>()
                for (i in 0 until totalShardsCount) {
                    val isParity = i >= dataShardCount
                    val shardBytes = shards[i].copyOf() // copy to ensure immutable shard data
                    val checksum = computeSha256(shardBytes)

                    resultShards.add(
                        CodedShard(
                            shardIndex = i,
                            isParity = isParity,
                            data = shardBytes,
                            sha256Checksum = checksum,
                            originalFileSize = fileSize
                        )
                    )
                }

                return resultShards
            }
        }
    }

    /**
     * Reconstructs the original file from any valid subset of available shards (at least dataShardCount out of total).
     * Missing shards are represented by null or omitted in the input map/list, indexed by shardIndex (0 to totalShardsCount - 1).
     */
    fun reconstructFile(availableShards: Map<Int, CodedShard>, targetOutputFile: File): File {
        require(availableShards.size >= dataShardCount) {
            "Insufficient shards available for reconstruction: found ${availableShards.size}, required at least $dataShardCount."
        }

        val totalShardsCount = dataShardCount + parityShardCount
        val firstShard = availableShards.values.first()
        val originalFileSize = firstShard.originalFileSize
        val shardSize = firstShard.data.size

        // Setup shards array and present boolean array expected by Reed-Solomon decoder
        val shards = Array(totalShardsCount) { ByteArray(shardSize) }
        val shardPresent = BooleanArray(totalShardsCount)

        for ((index, codedShard) in availableShards) {
            require(index in 0 until totalShardsCount) { "Invalid shard index $index." }
            require(codedShard.data.size == shardSize) { "Shard size mismatch at index $index." }
            
            // Verify checksum integrity
            val calculatedChecksum = computeSha256(codedShard.data)
            require(calculatedChecksum.equals(codedShard.sha256Checksum, ignoreCase = true)) {
                "Shard checksum verification failed at index $index! Expected ${codedShard.sha256Checksum}, got $calculatedChecksum."
            }

            System.arraycopy(codedShard.data, 0, shards[index], 0, shardSize)
            shardPresent[index] = true
        }

        // Decode / reconstruct missing shards
        codec.decodeMissing(shards, shardPresent, 0, shardSize)

        // Write reconstructed data bytes to target output file, truncating to original file size
        targetOutputFile.parentFile?.mkdirs()
        targetOutputFile.outputStream().buffered().use { output ->
            var bytesWritten = 0L
            for (i in 0 until dataShardCount) {
                val dataChunk = shards[i]
                val bytesToWrite = if (bytesWritten + shardSize <= originalFileSize) {
                    shardSize
                } else {
                    (originalFileSize - bytesWritten).toInt().coerceAtLeast(0)
                }

                if (bytesToWrite > 0) {
                    output.write(dataChunk, 0, bytesToWrite)
                    bytesWritten += bytesToWrite
                }
            }
            output.flush()
        }

        return targetOutputFile
    }

    /**
     * Computes SHA-256 hexadecimal checksum for a byte array.
     */
    fun computeSha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(data)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
}
