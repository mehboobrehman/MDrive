package com.architect.driveaggregator.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.architect.driveaggregator.telemetry.AgentLogger

/**
 * SecureTokenVault stores OAuth tokens (access and refresh tokens) for multiple accounts
 * using Android Keystore EncryptedSharedPreferences (AES-256 GCM).
 */
class SecureTokenVault @JvmOverloads constructor(
    context: Context,
    prefsFileName: String = DEFAULT_PREFS_FILE,
    overrideSharedPreferences: SharedPreferences? = null
) {
    private val prefs: SharedPreferences = runCatching {
        overrideSharedPreferences ?: createEncryptedPreferences(context, prefsFileName)
    }.getOrElse { initFailure ->
        // Never propagate a Keystore/prefs failure: fall back to degraded storage so no
        // thread (WorkManager worker, Hilt init) crashes during secure vault init.
        Log.e(TAG, "Secure storage init failed; using degraded fallback prefs", initFailure)
        createDegradedFallbackPreferences(context, prefsFileName, initFailure)
    }

    /**
     * True when the vault is running degraded: the Android Keystore was unavailable or
     * corrupt, so tokens are persisted in PLAIN (unencrypted) SharedPreferences. Accounts
     * must be re-authenticated to restore encrypted storage.
     */
    val degraded: Boolean = prefs.getBoolean(KEY_VAULT_DEGRADED, false)

    /**
     * Returns whether secure storage has degraded to unencrypted SharedPreferences.
     */
    fun isDegraded(): Boolean = degraded

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
     * Stores the email address for the specified account ID.
     */
    fun storeAccountEmail(accountId: String, email: String) {
        try {
            prefs.edit()
                .putString(getAccountEmailKey(accountId), email)
                .apply()
            addAccountId(accountId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to store email for account: $accountId", e)
        }
    }

    /**
     * Retrieves the stored email for the specified account ID.
     */
    fun getAccountEmail(accountId: String): String? {
        return try {
            prefs.getString(getAccountEmailKey(accountId), null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve email for account: $accountId", e)
            null
        }
    }

    /**
     * Stores the display name for the specified account ID.
     */
    fun storeAccountDisplayName(accountId: String, displayName: String) {
        try {
            prefs.edit()
                .putString(getAccountDisplayNameKey(accountId), displayName)
                .apply()
            addAccountId(accountId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to store display name for account: $accountId", e)
        }
    }

    /**
     * Retrieves the stored display name for the specified account ID.
     */
    fun getAccountDisplayName(accountId: String): String? {
        return try {
            prefs.getString(getAccountDisplayNameKey(accountId), null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve display name for account: $accountId", e)
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
                .remove(getAccountEmailKey(accountId))
                .remove(getAccountDisplayNameKey(accountId))
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
        const val KEY_VAULT_DEGRADED = "vault_degraded"
        private const val KEY_ACCOUNT_IDS = "vault_registered_account_ids"

        private const val PREFIX_REFRESH_TOKEN = "refresh_token_"
        private const val PREFIX_ACCESS_TOKEN = "access_token_"
        private const val PREFIX_ACCESS_TOKEN_EXPIRY = "access_token_expiry_"
        private const val PREFIX_PKCE_VERIFIER = "pkce_verifier_"
        private const val PREFIX_ACCOUNT_EMAIL = "account_email_"
        private const val PREFIX_ACCOUNT_DISPLAY_NAME = "account_display_name_"

        fun getRefreshTokenKey(accountId: String) = "$PREFIX_REFRESH_TOKEN$accountId"
        fun getAccessTokenKey(accountId: String) = "$PREFIX_ACCESS_TOKEN$accountId"
        fun getAccessTokenExpiryKey(accountId: String) = "$PREFIX_ACCESS_TOKEN_EXPIRY$accountId"
        fun getPkceVerifierKey(state: String) = "$PREFIX_PKCE_VERIFIER$state"
        fun getAccountEmailKey(accountId: String) = "$PREFIX_ACCOUNT_EMAIL$accountId"
        fun getAccountDisplayNameKey(accountId: String) = "$PREFIX_ACCOUNT_DISPLAY_NAME$accountId"

        /**
         * Creates an EncryptedSharedPreferences instance backed by Android Keystore MasterKey.
         *
         * Degradation strategy (keeps the vault usable and worker threads alive):
         * 1. Attempt to build encrypted prefs.
         * 2. On Keystore failure: delete the corrupt prefs file and retry once.
         * 3. On retry failure: delete the corrupt file again, fall back to PLAIN
         *    SharedPreferences flagged with vault_degraded=true, and emit a
         *    SECURITY:VAULT_DEGRADED telemetry event. Tokens keep working (get/put) but are
         *    NOT encrypted — the user must re-authenticate their Google accounts to restore
         *    encrypted storage.
         * Never throws: callers (Hilt init, WorkManager workers) must not crash on Keystore
         * corruption.
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
                    Log.e(TAG, "Unrecoverable Android Keystore corruption/failure. Degrading to plain SharedPreferences.", retryException)
                    // Clear invalid prefs to unblock future clean authentications
                    context.deleteSharedPreferences(fileName)
                    createDegradedFallbackPreferences(context, fileName, retryException)
                }
            }
        }

        /**
         * Creates a PLAIN (unencrypted) SharedPreferences fallback, marks it degraded, and
         * reports the security downgrade through telemetry (SECURITY:VAULT_DEGRADED).
         */
        private fun createDegradedFallbackPreferences(
            context: Context,
            fileName: String,
            cause: Throwable
        ): SharedPreferences {
            val plainPrefs = context.getSharedPreferences(fileName, Context.MODE_PRIVATE)
            runCatching {
                plainPrefs.edit().putBoolean(KEY_VAULT_DEGRADED, true).apply()
            }
            AgentLogger.logEvent(
                level = "ERROR",
                category = "SECURITY",
                event = "VAULT_DEGRADED",
                message = "Secure storage initialization failed due to Keystore corruption. " +
                    "Degraded to UNENCRYPTED SharedPreferences: tokens are not encrypted and " +
                    "accounts must be re-authenticated.",
                payload = mapOf(
                    "prefsFile" to fileName,
                    "exceptionClass" to cause.javaClass.name,
                    "exceptionMessage" to (cause.message ?: "")
                ),
                stack = cause.stackTraceToString()
            )
            return plainPrefs
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
