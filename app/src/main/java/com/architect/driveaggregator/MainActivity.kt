package com.architect.driveaggregator

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.architect.driveaggregator.auth.GoogleOAuthManager
import com.architect.driveaggregator.auth.OAuthNetworkClient
import com.architect.driveaggregator.auth.NetworkTokenResponse
import com.architect.driveaggregator.auth.NetworkRefreshResponse
import com.architect.driveaggregator.security.SecureTokenVault

class MainActivity : AppCompatActivity() {
    private lateinit var oauthManager: GoogleOAuthManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize dependencies (using a stub for network client for now)
        val vault = SecureTokenVault(this)
        val networkClient = object : OAuthNetworkClient {
            override suspend fun exchangeCodeForTokens(authCode: String, codeVerifier: String?): NetworkTokenResponse {
                // This is a placeholder for actual network request logic
                // In a real app, this would involve sending an HTTP POST request to Google's token endpoint
                // with authCode, codeVerifier, client_id, redirect_uri, and grant_type.
                // For demonstration, we'll simulate a successful response.
                return NetworkTokenResponse("test_account_id", "test_access_token", "test_refresh_token", 3600L)
            }
            override suspend fun refreshAccessToken(refreshToken: String): NetworkRefreshResponse {
                // Similar to exchangeCodeForTokens, this would be a real HTTP request.
                return NetworkRefreshResponse("new_test_access_token", 3600L)
            }
            override suspend fun revokeToken(token: String): Boolean = true
        }
        oauthManager = GoogleOAuthManager(vault, networkClient)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        val textView = TextView(this).apply {
            text = "Welcome to MDrive!"
            textSize = 24f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val loginButton = Button(this).apply {
            text = "Login with Google (drive.file scope)"
            setOnClickListener {
                val url = oauthManager.getAuthorizationUrl()
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(intent)
            }
        }

        layout.addView(textView)
        layout.addView(loginButton)
        setContentView(layout)
    }

    override fun onResume() {
        super.onResume()
        // Handle the redirect URI from Google OAuth
        intent?.data?.let { uri ->
            if (uri.toString().startsWith(GoogleOAuthManager.REDIRECT_URI)) {
                val code = uri.getQueryParameter("code")
                val state = uri.getQueryParameter("state")

                if (code != null && state != null) {
                    lifecycleScope.launch {
                        try {
                            val result = oauthManager.handleAuthorizationCode(code, state)
                            if (result.isSuccess) {
                                Toast.makeText(this@MainActivity, "Authentication successful!", Toast.LENGTH_LONG).show()
                                // You can now use oauthManager.getValidAccessToken() to get tokens for API calls
                            } else {
                                Toast.makeText(this@MainActivity, "Authentication failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                            }
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "Error during authentication: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                } else {
                    Toast.makeText(this@MainActivity, "OAuth redirect missing code or state.", Toast.LENGTH_LONG).show()
                }
                // Clear the intent data to prevent re-processing on subsequent onResume calls
                intent.data = null
            }
        }
    }
}
