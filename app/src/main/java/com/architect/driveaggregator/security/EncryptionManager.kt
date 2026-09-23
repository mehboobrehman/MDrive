package com.architect.driveaggregator.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * EncryptionManager handles Client-Side Zero-Knowledge Encryption (E2EE) for MDrive.
 * 
 * Requirements:
 * 1. Passphrase & Key Derivation: PBKDF2WithHmacSHA256 taking user passphrase and salt
 *    (stored securely in SecureTokenVault), deriving a 256-bit Master Encryption Key.
 * 2. Stream Cipher Transformer: AES-256-GCM stream encryption/decryption transformers (CipherInputStream / CipherOutputStream)
 *    intercepting data shards during sharding/sync stage before disk write or remote dispatch.
 * 3. Zero-Knowledge Principle: Keys never leave the device, and unencrypted shards are never transmitted to cloud providers.
 */
class EncryptionManager(
    private val context: Context,
    private val secureTokenVault: SecureTokenVault,
    private val overrideSharedPreferences: SharedPreferences? = null
) {
    companion object {
        private const val KEY_LENGTH_BITS = 256
        private const val ITERATION_COUNT = 65536 // PBKDF2 iterations for robust derivation
        private const val SALT_LENGTH_BYTES = 16
        private const val GCM_IV_LENGTH_BYTES = 12 // 96 bits recommended for GCM
        private const val GCM_TAG_LENGTH_BITS = 128 // 128 bit authentication tag
        private const val ALGORITHM = "AES"
        private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PBKDF_ALGORITHM = "PBKDF2WithHmacSHA256"

        const val PREF_SALT_KEY = "e2ee_salt_b64"
    }

    private var cachedMasterKey: SecretKey? = null

    /**
     * Initializes or retrieves the 256-bit Master Encryption Key derived from the user passphrase and salt.
     */
    @Synchronized
    fun getOrCreateMasterKey(passphrase: CharArray): SecretKey {
        cachedMasterKey?.let { return it }

        val salt = getOrCreateSalt()
        val keyBytes = deriveKeyFromPassphrase(passphrase, salt)
        val secretKey = SecretKeySpec(keyBytes, ALGORITHM)
        cachedMasterKey = secretKey
        return secretKey
    }

    /**
     * Clears sensitive keys from memory.
     */
    @Synchronized
    fun clearKeyCache() {
        cachedMasterKey = null
    }

    /**
     * Generates or retrieves the 16-byte cryptographically secure salt stored in SecureTokenVault.
     */
    fun getOrCreateSalt(): ByteArray {
        val existingSaltB64 = secureTokenVault.getRefreshToken(PREF_SALT_KEY)
        if (!existingSaltB64.isNullOrBlank()) {
            return Base64.decode(existingSaltB64, Base64.DEFAULT)
        }

        val salt = ByteArray(SALT_LENGTH_BYTES)
        SecureRandom().nextBytes(salt)
        val saltB64 = Base64.encodeToString(salt, Base64.DEFAULT)
        secureTokenVault.storeRefreshToken(PREF_SALT_KEY, saltB64)
        return salt
    }

    /**
     * Derives a 256-bit (32 byte) key using PBKDF2WithHmacSHA256.
     */
    fun deriveKeyFromPassphrase(passphrase: CharArray, salt: ByteArray): ByteArray {
        require(passphrase.isNotEmpty()) { "Passphrase cannot be empty." }
        require(salt.isNotEmpty()) { "Salt cannot be empty." }

        val factory = javax.crypto.SecretKeyFactory.getInstance(PBKDF_ALGORITHM)
        val spec = javax.crypto.spec.PBEKeySpec(passphrase, salt, ITERATION_COUNT, KEY_LENGTH_BITS)
        val tmp = factory.generateSecret(spec)
        val encoded = tmp.encoded
        spec.clearPassword()
        return encoded
    }

    /**
     * Encrypts a raw byte array using AES-256-GCM.
     */
    fun encryptBytes(plainBytes: ByteArray, secretKey: SecretKey): ByteArray {
        val iv = ByteArray(GCM_IV_LENGTH_BYTES)
        SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)

        val ciphertext = cipher.doFinal(plainBytes)
        val result = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, result, 0, iv.size)
        System.arraycopy(ciphertext, 0, result, iv.size, ciphertext.size)
        return result
    }

    /**
     * Decrypts an AES-256-GCM encrypted byte array.
     */
    fun decryptBytes(encryptedBytes: ByteArray, secretKey: SecretKey): ByteArray {
        require(encryptedBytes.size > GCM_IV_LENGTH_BYTES) { "Encrypted data is too short to contain IV and ciphertext." }

        val iv = ByteArray(GCM_IV_LENGTH_BYTES)
        System.arraycopy(encryptedBytes, 0, iv, 0, GCM_IV_LENGTH_BYTES)

        val ciphertextLength = encryptedBytes.size - GCM_IV_LENGTH_BYTES
        val ciphertext = ByteArray(ciphertextLength)
        System.arraycopy(encryptedBytes, GCM_IV_LENGTH_BYTES, ciphertext, 0, ciphertextLength)

        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

        return cipher.doFinal(ciphertext)
    }

    /**
     * Wraps an OutputStream with AES-256-GCM encryption transformer (`CipherOutputStream`).
     */
    fun wrapEncryptedOutputStream(outputStream: java.io.OutputStream, secretKey: SecretKey): java.io.OutputStream {
        val iv = ByteArray(GCM_IV_LENGTH_BYTES)
        SecureRandom().nextBytes(iv)
        outputStream.write(iv)

        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)

        return CipherOutputStream(outputStream, cipher)
    }

    /**
     * Wraps an InputStream with AES-256-GCM decryption transformer (`CipherInputStream`).
     */
    fun wrapEncryptedInputStream(inputStream: java.io.InputStream, secretKey: SecretKey): java.io.InputStream {
        val iv = ByteArray(GCM_IV_LENGTH_BYTES)
        val bytesRead = inputStream.read(iv)
        require(bytesRead == GCM_IV_LENGTH_BYTES) { "Could not read 12-byte IV from encrypted stream." }

        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

        return CipherInputStream(inputStream, cipher)
    }
}
