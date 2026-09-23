package com.architect.driveaggregator.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.architect.driveaggregator.security.SecureTokenVault
import com.architect.driveaggregator.ui.model.AccountStatus
import com.architect.driveaggregator.ui.model.DriveAccountInfo
import com.architect.driveaggregator.ui.model.PooledStorageOverview
import com.architect.driveaggregator.ui.viewmodel.LinkedAccountsViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class LinkedAccountsViewModelTest {

    private lateinit var context: Context
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var tokenVault: SecureTokenVault
    private lateinit var viewModel: LinkedAccountsViewModel

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        sharedPreferences = context.getSharedPreferences("test_vault_prefs", Context.MODE_PRIVATE)
        sharedPreferences.edit().clear().commit()
        tokenVault = SecureTokenVault(context, "test_vault_prefs", sharedPreferences)

        // Seed an account
        tokenVault.storeRefreshToken("test_account_123", "dummy_refresh")

        viewModel = LinkedAccountsViewModel(context.applicationContext as android.app.Application, null, tokenVault)
    }

    @Test
    fun loadsAccountsAndCalculatesPooledStorage() {
        val state = viewModel.uiState.value
        assertTrue(!state.isLoading)
        assertEquals(1, state.pooledStorage.accounts.size)
        assertEquals("test_account_123", state.pooledStorage.accounts[0].accountId)
        assertEquals(15L * 1024 * 1024 * 1024, state.pooledStorage.totalCapacityBytes)
    }

    @Test
    fun triggersLinkNewAccountSuccessfully() {
        viewModel.triggerLinkNewAccount()
        val accounts = tokenVault.getAllAccountIds()
        assertTrue(accounts.size >= 2)
    }

    @Test
    fun unlinksAccountCorrectly() {
        viewModel.unlinkAccount("test_account_123")
        val accounts = tokenVault.getAllAccountIds()
        assertTrue(accounts.isEmpty())
    }
}
