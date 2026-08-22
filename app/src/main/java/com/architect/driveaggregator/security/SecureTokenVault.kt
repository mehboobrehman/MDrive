package com.architect.driveaggregator.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * SecureTokenVault stores OAuth tokens (access and refresh tokens) for multiple accounts
 * using Android Keystore EncryptedSharedPreferences (AES-256 GCM).
 */
class SecureTokenVault @JvmOverloads constructor(
    context: Context,
    prefsFileName: String = DEFAULT_PREFS_FILE,
    overrideSharedPreferences: SharedPreferences? = null
) {
    private val prefs: SharedPreferences = overrideSharedPreferences
        ?: createEncryptedPreferences(context, prefsFileName)

    /**
     * Stores a refresh token for the specified account ID.
     */
    fun storeRefreshToken(accountId: String, token: String) {
        try {
            prefs.edit()
                .putString(getRefreshTokenKey(accountId), token)
                .apply()
            addAccountId(accountId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to store refresh token for account: $accountId", e)
        }
    }

    /**
     * Retrieves the stored refresh token for the specified account ID.
     */
    fun getRefreshToken(accountId: String): String? {
        return try {
            prefs.getString(getRefreshTokenKey(accountId), null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve refresh token for account: $accountId", e)
            null
        }
    }

    /**
     * Stores an access token and its expiration timestamp for the specified account ID.
     */
    fun storeAccessToken(accountId: String, token: String, expiryTimeMs: Long) {
        try {
            prefs.edit()
                .putString(getAccessTokenKey(accountId), token)
                .putLong(getAccessTokenExpiryKey(accountId), expiryTimeMs)
                .apply()
            addAccountId(accountId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to store access token for account: $accountId", e)
        }
    }

    /**
     * Retrieves the stored access token for the specified account ID.
     */
    fun getAccessToken(accountId: String): String? {
        return try {
            prefs.getString(getAccessTokenKey(accountId), null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve access token for account: $accountId", e)
            null
        }
    }

    /**
     * Retrieves the stored access token expiry timestamp for the specified account ID.
     * Returns null if no expiry timestamp is stored.
     */
    fun getAccessTokenExpiry(accountId: String): Long? {
        return try {
            val key = getAccessTokenExpiryKey(accountId)
            if (prefs.contains(key)) {
                prefs.getLong(key, -1L)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve access token expiry for account: $accountId", e)
            null
        }
    }

    /**
     * Returns the set of all account IDs stored in the vault.
     */
    fun getAllAccountIds(): Set<String> {
        return try {
            val accountIds = prefs.getStringSet(KEY_ACCOUNT_IDS, emptySet()) ?: emptySet()
            accountIds.toSet()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve account IDs", e)
            emptySet()
        }
    }

    /**
     * Removes all stored tokens and metadata for the specified account ID.
     */
    fun removeAccount(accountId: String) {
        try {
            prefs.edit()
                .remove(getRefreshTokenKey(accountId))
                .remove(getAccessTokenKey(accountId))
                .remove(getAccessTokenExpiryKey(accountId))
                .apply()

            val accountIds = prefs.getStringSet(KEY_ACCOUNT_IDS, emptySet()) ?: emptySet()
            if (accountIds.contains(accountId)) {
                val updatedIds = accountIds.toMutableSet()
                updatedIds.remove(accountId)
                prefs.edit().putStringSet(KEY_ACCOUNT_IDS, updatedIds).apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove account: $accountId", e)
        }
    }

    /**
     * Clears all stored tokens and metadata from the vault.
     */
    fun clearAll() {
        try {
            prefs.edit().clear().apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear vault", e)
        }
    }

    /**
     * Stores a PKCE verifier associated with a state.
     */
    fun storePkceVerifier(state: String, verifier: String) {
        prefs.edit().putString(getPkceVerifierKey(state), verifier).apply()
    }

    /**
     * Retrieves and removes a PKCE verifier associated with a state.
     */
    fun getAndRemovePkceVerifier(state: String): String? {
        val key = getPkceVerifierKey(state)
        val verifier = prefs.getString(key, null)
        if (verifier != null) {
            prefs.edit().remove(key).apply()
        }
        return verifier
    }

    private fun addAccountId(accountId: String) {
        try {
            val accountIds = prefs.getStringSet(KEY_ACCOUNT_IDS, emptySet()) ?: emptySet()
            if (!accountIds.contains(accountId)) {
                val updatedIds = accountIds.toMutableSet()
                updatedIds.add(accountId)
                prefs.edit().putStringSet(KEY_ACCOUNT_IDS, updatedIds).apply()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update account IDs set for: $accountId", e)
        }
    }

    companion object {
        private const val TAG = "SecureTokenVault"
        const val DEFAULT_PREFS_FILE = "secure_oauth_tokens"
        private const val KEY_ACCOUNT_IDS = "vault_registered_account_ids"

        private const val PREFIX_REFRESH_TOKEN = "refresh_token_"
        private const val PREFIX_ACCESS_TOKEN = "access_token_"
        private const val PREFIX_ACCESS_TOKEN_EXPIRY = "access_token_expiry_"
        private const val PREFIX_PKCE_VERIFIER = "pkce_verifier_"

        fun getRefreshTokenKey(accountId: String) = "$PREFIX_REFRESH_TOKEN$accountId"
        fun getAccessTokenKey(accountId: String) = "$PREFIX_ACCESS_TOKEN$accountId"
        fun getAccessTokenExpiryKey(accountId: String) = "$PREFIX_ACCESS_TOKEN_EXPIRY$accountId"
        fun getPkceVerifierKey(state: String) = "$PREFIX_PKCE_VERIFIER$state"

        /**
         * Creates an EncryptedSharedPreferences instance backed by Android Keystore MasterKey.
         * Handles Keystore exceptions gracefully with cleanup, retry, and fallback.
         */
        private fun createEncryptedPreferences(context: Context, fileName: String): SharedPreferences {
            return try {
                buildEncryptedSharedPreferences(context, fileName)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create EncryptedSharedPreferences. Attempting reset/retry.", e)
                try {
                    context.deleteSharedPreferences(fileName)
                    buildEncryptedSharedPreferences(context, fileName)
                } catch (retryException: Exception) {
                    Log.e(
                        TAG,
                        "Retry creating EncryptedSharedPreferences failed. Falling back to standard SharedPreferences.",
                        retryException
                    )
                    context.getSharedPreferences("${fileName}_uncrypted_fallback", Context.MODE_PRIVATE)
                }
            }
        }

        private fun buildEncryptedSharedPreferences(context: Context, fileName: String): SharedPreferences {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            return EncryptedSharedPreferences.create(
                context,
                fileName,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }
}
