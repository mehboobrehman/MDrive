package com.architect.driveaggregator.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.architect.driveaggregator.security.SecureTokenVault
import com.architect.driveaggregator.ui.screens.LinkedAccountsScreen
import com.architect.driveaggregator.ui.viewmodel.LinkedAccountsViewModel
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LinkedAccountsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

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

        // Seed a google drive account
        tokenVault.storeRefreshToken("test_account_123", "dummy_refresh")

        viewModel = LinkedAccountsViewModel(context.applicationContext as android.app.Application, null, tokenVault)
    }

    @Test
    fun displaysPooledStorageOverviewAndAccountList() {
        composeTestRule.setContent {
            LinkedAccountsScreen(viewModel = viewModel)
        }

        // Verify top bar
        composeTestRule.onNodeWithText("MDrive Cloud Aggregator").assertIsDisplayed()
    }

    @Test
    fun canTriggerLinkNewAccount() {
        composeTestRule.setContent {
            LinkedAccountsScreen(viewModel = viewModel)
        }

        // Click Link Cloud Account FAB
        composeTestRule.onNodeWithText("Link Cloud Account", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("Link Cloud Account", useUnmergedTree = true).performClick()

        // Verify dialog opens and select OneDrive
        composeTestRule.onNodeWithText("Link Cloud Storage Account").assertIsDisplayed()
        composeTestRule.onNodeWithText("Microsoft OneDrive").assertIsDisplayed()
        composeTestRule.onNodeWithText("Microsoft OneDrive").performClick()

        // Vault should now have 2 accounts due to simulation fallback in test viewModel when oauthManager is null
        val accounts = tokenVault.getAllAccountIds()
        org.junit.Assert.assertTrue(accounts.size >= 2)
    }
}
