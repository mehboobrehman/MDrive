package com.architect.driveaggregator.update

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChecksumVerifierTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testExtractChecksum() {
        val checksums = """
            e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855  MDrive-v1.0.23.apk
            a591a6d40bf420404a011733cfb7b190d62c65bf0bcda32b57b277d9ad9f146e *MDrive-v1.0.24.apk
        """.trimIndent()

        val hash23 = ChecksumVerifier.extractChecksum(checksums, "MDrive-v1.0.23.apk")
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash23)

        val hash24 = ChecksumVerifier.extractChecksum(checksums, "MDrive-v1.0.24.apk")
        assertEquals("a591a6d40bf420404a011733cfb7b190d62c65bf0bcda32b57b277d9ad9f146e", hash24)

        assertNull(ChecksumVerifier.extractChecksum(checksums, "nonexistent.apk"))
    }

    @Test
    fun testVerifyFileChecksum() {
        val file = tempFolder.newFile("test.apk")
        file.writeText("hello mdrive")
        val actualHash = ChecksumVerifier.computeSha256(file)

        val checksums = "$actualHash  test.apk"
        assertTrue(ChecksumVerifier.verify(file, checksums, "test.apk"))

        val invalidChecksums = "0000000000000000000000000000000000000000000000000000000000000000  test.apk"
        assertFalse(ChecksumVerifier.verify(file, invalidChecksums, "test.apk"))
    }
}
