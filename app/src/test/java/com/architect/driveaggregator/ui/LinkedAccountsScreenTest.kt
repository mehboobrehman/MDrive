package com.architect.driveaggregator.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

        // Seed an account
        tokenVault.storeRefreshToken("test_account_123", "dummy_refresh")

        viewModel = LinkedAccountsViewModel(context.applicationContext as android.app.Application, null, tokenVault)
    }

    @Test
    fun displaysPooledStorageOverviewAndAccountList() {
        composeTestRule.setContent {
            LinkedAccountsScreen(viewModel = viewModel)
        }

        // Verify title & pooled storage overview
        composeTestRule.onNodeWithText("MDrive Pooled Storage").assertIsDisplayed()
        composeTestRule.onNodeWithText("Aggregate Pooled Storage").assertIsDisplayed()
        composeTestRule.onNodeWithText("Linked Accounts (1)").assertIsDisplayed()

        // Account card sits below the fold on the default Robolectric window
        // (320x470dp), so scroll it into view before asserting.
        val emailNode = composeTestRule.onNodeWithText("test_account_123@gmail.com")
        emailNode.performScrollTo()

        // Verify account details rendered
        composeTestRule.onNodeWithText("Google Account test_account_123").assertIsDisplayed()
        emailNode.assertIsDisplayed()
    }

    @Test
    fun canTriggerLinkNewAccount() {
        composeTestRule.setContent {
            LinkedAccountsScreen(viewModel = viewModel)
        }

        // Click Link Google Drive FAB
        composeTestRule.onNodeWithText("Link Google Drive", useUnmergedTree = true).assertExists()
        composeTestRule.onNodeWithText("Link Google Drive", useUnmergedTree = true).performClick()

        // Vault should now have 2 accounts due to simulation fallback in test viewModel when oauthManager is null
        val accounts = tokenVault.getAllAccountIds()
        org.junit.Assert.assertTrue(accounts.size >= 2)
    }
}
