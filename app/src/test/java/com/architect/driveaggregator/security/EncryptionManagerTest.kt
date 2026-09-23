package com.architect.driveaggregator.security

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class EncryptionManagerTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var mockContext: Context
    private lateinit var secureTokenVault: SecureTokenVault
    private lateinit var encryptionManager: EncryptionManager

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        mockContext = mock(Context::class.java)
        `when`(mockContext.getSharedPreferences(anyString(), anyInt())).thenReturn(fakePrefs)

        secureTokenVault = SecureTokenVault(
            context = mockContext,
            overrideSharedPreferences = fakePrefs
        )
        encryptionManager = EncryptionManager(mockContext, secureTokenVault)
    }

    @Test
    fun testSaltGenerationAndPersistence() {
        val salt1 = encryptionManager.getOrCreateSalt()
        assertNotNull(salt1)
        assertEquals(16, salt1.size)

        // Calling again should return the exact same salt (persisted in vault)
        val salt2 = encryptionManager.getOrCreateSalt()
        assertArrayEquals(salt1, salt2)
    }

    @Test
    fun testKeyDerivation() {
        val passphrase = "SecureUserPassphrase123!".toCharArray()
        val salt = encryptionManager.getOrCreateSalt()

        val key1 = encryptionManager.deriveKeyFromPassphrase(passphrase, salt)
        assertNotNull(key1)
        assertEquals(32, key1.size) // 256 bits

        // Same passphrase and salt must derive identical key
        val key2 = encryptionManager.deriveKeyFromPassphrase(passphrase, salt)
        assertArrayEquals(key1, key2)
    }

    @Test
    fun testRoundTripEncryptionDecryption() {
        val passphrase = "MySecretPassphrase".toCharArray()
        val masterKey = encryptionManager.getOrCreateMasterKey(passphrase)

        val plaintext = "Hello Zero-Knowledge Encryption World! MDrive E2EE test data.".toByteArray(Charsets.UTF_8)
        val encrypted = encryptionManager.encryptBytes(plaintext, masterKey)

        assertNotNull(encrypted)
        assertTrue(encrypted.size > plaintext.size) // Contains 12-byte IV + 16-byte GCM tag

        val decrypted = encryptionManager.decryptBytes(encrypted, masterKey)
        assertArrayEquals(plaintext, decrypted)
        assertEquals("Hello Zero-Knowledge Encryption World! MDrive E2EE test data.", String(decrypted, Charsets.UTF_8))
    }

    @Test(expected = Exception::class)
    fun testTamperResistanceGCM() {
        val passphrase = "MySecretPassphrase".toCharArray()
        val masterKey = encryptionManager.getOrCreateMasterKey(passphrase)

        val plaintext = "Sensitive data shard content".toByteArray(Charsets.UTF_8)
        val encrypted = encryptionManager.encryptBytes(plaintext, masterKey)

        // Tamper with ciphertext bytes (flip a bit in the encrypted payload)
        encrypted[encrypted.size - 1] = (encrypted[encrypted.size - 1].toInt() xor 0x01).toByte()

        // Decryption should throw AEADBadTagException / GeneralSecurityException due to tag verification failure
        encryptionManager.decryptBytes(encrypted, masterKey)
    }

    @Test
    fun testStreamEncryptionDecryptionTransformer() {
        val passphrase = "StreamPassphrase".toCharArray()
        val masterKey = encryptionManager.getOrCreateMasterKey(passphrase)

        val originalData = "Streaming AES-256-GCM test content across shards.".toByteArray(Charsets.UTF_8)
        
        val outputStream = java.io.ByteArrayOutputStream()
        val encryptedOut = encryptionManager.wrapEncryptedOutputStream(outputStream, masterKey)
        encryptedOut.write(originalData)
        encryptedOut.close()

        val encryptedBytes = outputStream.toByteArray()

        val inputStream = java.io.ByteArrayInputStream(encryptedBytes)
        val decryptedIn = encryptionManager.wrapEncryptedInputStream(inputStream, masterKey)
        val decryptedBytes = decryptedIn.readBytes()

        assertArrayEquals(originalData, decryptedBytes)
    }
}
