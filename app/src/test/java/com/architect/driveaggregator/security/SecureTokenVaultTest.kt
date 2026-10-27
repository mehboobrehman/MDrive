package com.architect.driveaggregator.security

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.kotlin.anyOrNull

class SecureTokenVaultTest {

    private lateinit var fakePrefs: FakeSharedPreferences
    private lateinit var mockContext: Context
    private lateinit var vault: SecureTokenVault

    @Before
    fun setUp() {
        fakePrefs = FakeSharedPreferences()
        mockContext = mock(Context::class.java)
        `when`(mockContext.getSharedPreferences(anyString(), anyInt())).thenReturn(fakePrefs)

        vault = SecureTokenVault(
            context = mockContext,
            overrideSharedPreferences = fakePrefs
        )
    }

    @Test
    fun storeAndGetRefreshToken_storesAndRetrievesTokenSuccessfully() {
        val accountId = "account_123"
        val refreshToken = "sample_refresh_token_abc"

        vault.storeRefreshToken(accountId, refreshToken)

        val retrievedToken = vault.getRefreshToken(accountId)
        assertEquals(refreshToken, retrievedToken)
        assertTrue(vault.getAllAccountIds().contains(accountId))
    }

    @Test
    fun getRefreshToken_returnsNullForUnknownAccount() {
        val retrievedToken = vault.getRefreshToken("non_existent_account")
        assertNull(retrievedToken)
    }

    @Test
    fun storeAndGetAccessToken_storesAndRetrievesAccessTokenAndExpiry() {
        val accountId = "account_456"
        val accessToken = "sample_access_token_xyz"
        val expiry = System.currentTimeMillis() + 3600000L

        vault.storeAccessToken(accountId, accessToken, expiry)

        assertEquals(accessToken, vault.getAccessToken(accountId))
        assertEquals(expiry, vault.getAccessTokenExpiry(accountId))
        assertTrue(vault.getAllAccountIds().contains(accountId))
    }

    @Test
    fun getAccessTokenAndExpiry_returnsNullForUnknownAccount() {
        assertNull(vault.getAccessToken("unknown"))
        assertNull(vault.getAccessTokenExpiry("unknown"))
    }

    @Test
    fun getAllAccountIds_returnsAllRegisteredAccounts() {
        vault.storeRefreshToken("acc_1", "rt_1")
        vault.storeAccessToken("acc_2", "at_2", 1000L)

        val accounts = vault.getAllAccountIds()
        assertEquals(2, accounts.size)
        assertTrue(accounts.contains("acc_1"))
        assertTrue(accounts.contains("acc_2"))
    }

    @Test
    fun removeAccount_deletesTokensAndRemovesAccountId() {
        val accountId = "acc_remove"
        vault.storeRefreshToken(accountId, "rt")
        vault.storeAccessToken(accountId, "at", 5000L)

        vault.removeAccount(accountId)

        assertNull(vault.getRefreshToken(accountId))
        assertNull(vault.getAccessToken(accountId))
        assertNull(vault.getAccessTokenExpiry(accountId))
        assertTrue(!vault.getAllAccountIds().contains(accountId))
    }

    @Test
    fun storeAndGetAccountEmailAndDisplayName_storesAndRetrievesSuccessfully() {
        val accountId = "google_123"
        val email = "user@gmail.com"
        val displayName = "User Name"

        vault.storeAccountEmail(accountId, email)
        vault.storeAccountDisplayName(accountId, displayName)

        assertEquals(email, vault.getAccountEmail(accountId))
        assertEquals(displayName, vault.getAccountDisplayName(accountId))
    }

    @Test
    fun removeAccount_deletesProfileInfo() {
        val accountId = "acc_remove"
        vault.storeAccountEmail(accountId, "email")
        vault.storeAccountDisplayName(accountId, "name")

        vault.removeAccount(accountId)

        assertNull(vault.getAccountEmail(accountId))
        assertNull(vault.getAccountDisplayName(accountId))
    }

    @Test
    fun clearAll_removesAllAccountsAndTokens() {
        vault.storeRefreshToken("acc_1", "rt_1")
        vault.storeAccessToken("acc_2", "at_2", 1000L)

        vault.clearAll()

        assertNull(vault.getRefreshToken("acc_1"))
        assertNull(vault.getAccessToken("acc_2"))
        assertTrue(vault.getAllAccountIds().isEmpty())
    }

    @Test
    fun storeAndGetPkceVerifier_storesAndRetrievesVerifierSuccessfully() {
        val state = "state_123"
        val verifier = "verifier_abc"

        vault.storePkceVerifier(state, verifier)

        val retrievedVerifier = vault.getAndRemovePkceVerifier(state)
        assertEquals(verifier, retrievedVerifier)
        
        // Should be removed after retrieval
        assertNull(vault.getAndRemovePkceVerifier(state))
    }

    @Test
    fun isDegraded_reflectsDegradedFlagFromPrefs() {
        fakePrefs.edit().putBoolean(SecureTokenVault.KEY_VAULT_DEGRADED, true).apply()
        val degradedVault = SecureTokenVault(
            context = mockContext,
            overrideSharedPreferences = fakePrefs
        )
        assertTrue(degradedVault.isDegraded())
        assertTrue(degradedVault.degraded)
    }

    @Test
    fun notDegraded_whenDegradedFlagAbsent() {
        assertFalse(vault.isDegraded())
        assertFalse(vault.degraded)
    }

    @Test
    fun gracefullyHandlesPreferencesExceptionDuringOperations() {
        val faultyPrefs = mock(SharedPreferences::class.java)
        `when`(faultyPrefs.getString(anyOrNull(), anyOrNull())).thenThrow(RuntimeException("Storage corrupted"))
        `when`(faultyPrefs.contains(anyOrNull())).thenThrow(RuntimeException("Storage corrupted"))

        val faultyVault = SecureTokenVault(
            context = mockContext,
            overrideSharedPreferences = faultyPrefs
        )

        assertNull(faultyVault.getRefreshToken("acc"))
        assertNull(faultyVault.getAccessToken("acc"))
        assertNull(faultyVault.getAccessTokenExpiry("acc"))
        assertTrue(faultyVault.getAllAccountIds().isEmpty())
    }
}

class FakeSharedPreferences : SharedPreferences {
    private val map = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = HashMap(map)

    override fun getString(key: String?, defValue: String?): String? {
        return if (key != null && map.containsKey(key)) map[key] as? String ?: defValue else defValue
    }

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
        return if (key != null && map.containsKey(key)) {
            val set = map[key] as? Set<String>
            set?.toMutableSet() ?: defValues
        } else {
            defValues
        }
    }

    override fun getInt(key: String?, defValue: Int): Int {
        return if (key != null && map.containsKey(key)) map[key] as? Int ?: defValue else defValue
    }

    override fun getLong(key: String?, defValue: Long): Long {
        return if (key != null && map.containsKey(key)) map[key] as? Long ?: defValue else defValue
    }

    override fun getFloat(key: String?, defValue: Float): Float {
        return if (key != null && map.containsKey(key)) map[key] as? Float ?: defValue else defValue
    }

    override fun getBoolean(key: String?, defValue: Boolean): Boolean {
        return if (key != null && map.containsKey(key)) map[key] as? Boolean ?: defValue else defValue
    }

    override fun contains(key: String?): Boolean = key != null && map.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    inner class Editor : SharedPreferences.Editor {
        private val tempMap = HashMap<String, Any?>()
        private val removeKeys = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (key != null) tempMap[key] = value
            return this
        }

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
            if (key != null) tempMap[key] = values?.toSet()
            return this
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            if (key != null) tempMap[key] = value
            return this
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            if (key != null) tempMap[key] = value
            return this
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            if (key != null) tempMap[key] = value
            return this
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            if (key != null) tempMap[key] = value
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) removeKeys.add(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clearAll) {
                map.clear()
            }
            for (key in removeKeys) {
                map.remove(key)
            }
            for ((key, value) in tempMap) {
                map[key] = value
            }
        }
    }
}
