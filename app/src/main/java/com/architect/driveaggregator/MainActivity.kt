package com.architect.driveaggregator

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.architect.driveaggregator.auth.GoogleOAuthManager
import com.architect.driveaggregator.auth.OAuthNetworkClient
import com.architect.driveaggregator.auth.NetworkTokenResponse
import com.architect.driveaggregator.auth.NetworkRefreshResponse
import com.architect.driveaggregator.security.SecureTokenVault
import com.architect.driveaggregator.telemetry.AgentLogger
import com.architect.driveaggregator.util.ActivityTouchInterceptor

import com.architect.driveaggregator.ui.FileExplorerActivity
import com.architect.driveaggregator.ui.LinkedAccountsActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject
    lateinit var oauthManager: GoogleOAuthManager

    @Inject
    lateinit var tokenVault: SecureTokenVault

    private val touchInterceptor = ActivityTouchInterceptor("MainActivity")
    private lateinit var telemetryStatusButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AgentLogger.logEvent("INFO", "NAVIGATION", "PAGE_MOUNT", "MainActivity created", mapOf("activity" to "MainActivity"))

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(40, 40, 40, 40)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Telemetry Status Bar / Button at the top
        telemetryStatusButton = Button(this).apply {
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(24, 20, 24, 20)
            val btnParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 40
            }
            layoutParams = btnParams
            setOnClickListener {
                showTelemetryConfigDialog()
            }
        }
        layout.addView(telemetryStatusButton)

        val textView = TextView(this).apply {
            text = "Welcome to MDrive!"
            textSize = 24f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 30
            }
        }

        val loginButton = Button(this).apply {
            text = "Login with Google (drive.file scope)"
            setOnClickListener {
                AgentLogger.logEvent("INFO", "ACTION", "BUTTON_CLICK", "Clicked Google Login button")
                AgentLogger.logEvent(
                    "INFO", "OAUTH", "OAUTH_INIT",
                    "OAuth sign-in flow initiated from MainActivity",
                    mapOf("client_id" to "[REDACTED]")
                )
                try {
                    val url = oauthManager.getAuthorizationUrl()
                    AgentLogger.logEvent(
                        "INFO", "OAUTH", "OAUTH_URL_OPENED",
                        "Opening Google authorization URL",
                        mapOf("client_id" to "[REDACTED]")
                    )
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                } catch (e: IllegalStateException) {
                    Toast.makeText(
                        this@MainActivity,
                        e.message ?: GoogleOAuthManager.OAUTH_MISCONFIGURED_MESSAGE,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        val linkedAccountsButton = Button(this).apply {
            text = "Manage Linked Accounts"
            setOnClickListener {
                AgentLogger.logEvent("INFO", "ACTION", "BUTTON_CLICK", "Clicked Manage Linked Accounts button")
                val intent = Intent(this@MainActivity, LinkedAccountsActivity::class.java)
                startActivity(intent)
            }
        }

        val fileExplorerButton = Button(this).apply {
            text = "Open File Explorer"
            setOnClickListener {
                AgentLogger.logEvent("INFO", "ACTION", "BUTTON_CLICK", "Clicked Open File Explorer button")
                val intent = Intent(this@MainActivity, FileExplorerActivity::class.java)
                startActivity(intent)
            }
        }

        val advancedSearchButton = Button(this).apply {
            text = "Advanced Federated Search"
            setOnClickListener {
                AgentLogger.logEvent("INFO", "ACTION", "BUTTON_CLICK", "Clicked Advanced Federated Search button")
                val intent = Intent(this@MainActivity, com.architect.driveaggregator.ui.AdvancedSearchActivity::class.java)
                startActivity(intent)
            }
        }

        val aiSmartDashboardButton = Button(this).apply {
            text = "AI Smart Organization Dashboard"
            setOnClickListener {
                AgentLogger.logEvent("INFO", "ACTION", "BUTTON_CLICK", "Clicked AI Smart Organization Dashboard button")
                val intent = Intent(this@MainActivity, com.architect.driveaggregator.ui.AiSmartDashboardActivity::class.java)
                startActivity(intent)
            }
        }

        layout.addView(textView)
        layout.addView(loginButton)
        layout.addView(linkedAccountsButton)
        layout.addView(fileExplorerButton)
        layout.addView(advancedSearchButton)
        layout.addView(aiSmartDashboardButton)
        setContentView(layout)

        // Surface degraded secure storage so the user knows tokens are unencrypted
        // and must re-authenticate their Google accounts.
        if (tokenVault.isDegraded()) {
            Toast.makeText(
                this,
                "⚠️ Secure storage degraded: tokens are not encrypted. Please re-authenticate your Google accounts.",
                Toast.LENGTH_LONG
            ).show()
        }

        handleIntent(intent)
        updateTelemetryStatus()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        AgentLogger.logEvent("INFO", "NAVIGATION", "PAGE_RESUME", "MainActivity resumed", mapOf("activity" to "MainActivity"))
        handleIntent(intent)
        updateTelemetryStatus()
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return

        // 1. Google OAuth redirect handling
        if (uri.toString().startsWith(GoogleOAuthManager.REDIRECT_URI)) {
            val error = uri.getQueryParameter("error")
            val errorDescription = uri.getQueryParameter("error_description")
            val code = uri.getQueryParameter("code")
            val state = uri.getQueryParameter("state")

            // Google refused the request at the authorization endpoint (e.g. invalid_client)
            if (!error.isNullOrBlank()) {
                AgentLogger.logEvent(
                    level = "ERROR",
                    category = "OAUTH",
                    event = "OAUTH_REDIRECT_RECEIVED",
                    message = "OAuth redirect received with error: $error",
                    payload = mapOf(
                        "error" to error,
                        "error_description" to (errorDescription ?: ""),
                        "client_id" to "[REDACTED]"
                    )
                )
                Toast.makeText(
                    this,
                    "Google sign-in failed: $error — verify GOOGLE_CLIENT_ID in local.properties and the OAuth client in Google Cloud Console",
                    Toast.LENGTH_LONG
                ).show()
                intent.data = null
                return
            }

            AgentLogger.logEvent(
                level = "INFO",
                category = "OAUTH",
                event = "OAUTH_REDIRECT_RECEIVED",
                message = "OAuth redirect received",
                payload = mapOf(
                    "has_code" to (code != null),
                    "has_state" to (state != null),
                    "client_id" to "[REDACTED]"
                )
            )

            if (code != null && state != null) {
                lifecycleScope.launch {
                    try {
                        val result = oauthManager.handleAuthorizationCode(code, state)
                        if (result.isSuccess) {
                            Toast.makeText(this@MainActivity, "Authentication successful!", Toast.LENGTH_LONG).show()
                        } else {
                            val exception = result.exceptionOrNull()
                            val failureMessage = exception?.message ?: exception?.javaClass?.simpleName ?: "Unknown error"
                            val friendlyMessage = if (failureMessage.contains("invalid_client", ignoreCase = true)) {
                                "Google sign-in failed: invalid_client — verify GOOGLE_CLIENT_ID in local.properties and the OAuth client in Google Cloud Console"
                            } else {
                                "Authentication failed: $failureMessage"
                            }
                            Toast.makeText(this@MainActivity, friendlyMessage, Toast.LENGTH_LONG).show()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "Error during authentication: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            } else {
                Toast.makeText(this@MainActivity, "OAuth redirect missing code or state.", Toast.LENGTH_LONG).show()
            }
            intent.data = null
            return
        }

        // 2. HTTP Telemetry Config URL (e.g., http://192.168.105.79:8080/api/config)
        if (uri.scheme?.equals("http", ignoreCase = true) == true && uri.path?.contains("/api/config") == true) {
            val host = uri.host
            val port = if (uri.port != -1) uri.port else 8080
            if (!host.isNullOrBlank()) {
                AgentLogger.setServerAddress(host, port, this)
                Toast.makeText(this, "📡 Telemetry connected to $host:$port", Toast.LENGTH_LONG).show()
                updateTelemetryStatus()
                intent.data = null
                return
            }
        }

        // 3. MDrive Custom Scheme (e.g., mdrive://telemetry?ip=192.168.105.79&port=8080 or mdrive://config?ip=192.168.105.79)
        if (uri.scheme?.equals("mdrive", ignoreCase = true) == true) {
            val queryIp = uri.getQueryParameter("ip")
            val host = when {
                !queryIp.isNullOrBlank() -> queryIp
                uri.host != null && uri.host != "telemetry" && uri.host != "config" -> uri.host
                else -> null
            }
            val portStr = uri.getQueryParameter("port")
            val port = portStr?.toIntOrNull() ?: if (uri.port != -1) uri.port else 8080
            if (!host.isNullOrBlank()) {
                AgentLogger.setServerAddress(host, port, this)
                Toast.makeText(this, "📡 Telemetry connected to $host:$port", Toast.LENGTH_LONG).show()
                updateTelemetryStatus()
                intent.data = null
                return
            }
        }
    }

    private fun updateTelemetryStatus() {
        if (!::telemetryStatusButton.isInitialized) return
        val serverUrl = AgentLogger.getServerUrl()
        if (serverUrl != null) {
            val display = serverUrl.removePrefix("http://")
            telemetryStatusButton.text = "📡 Telemetry: $display (Connected)"
            telemetryStatusButton.setBackgroundColor(Color.parseColor("#15803D")) // Green
        } else {
            val savedIp = AgentLogger.getServerIp(this)
            if (savedIp != null) {
                telemetryStatusButton.text = "📡 Telemetry: $savedIp:8080 (Tap to Reconnect)"
                telemetryStatusButton.setBackgroundColor(Color.parseColor("#C2410C")) // Orange
            } else {
                telemetryStatusButton.text = "📡 Telemetry: Not Connected (Tap to Configure)"
                telemetryStatusButton.setBackgroundColor(Color.parseColor("#334155")) // Slate
            }
        }
    }

    private fun showTelemetryConfigDialog() {
        val currentIp = AgentLogger.getServerIp(this) ?: "192.168.105.79"
        val input = EditText(this).apply {
            hint = "e.g. 192.168.105.79"
            setText(currentIp)
            setSelection(text.length)
        }
        val container = FrameLayout(this).apply {
            setPadding(60, 20, 60, 10)
            addView(input)
        }

        AlertDialog.Builder(this)
            .setTitle("📡 Telemetry Configuration")
            .setMessage("Enter the host IP of your development machine running log-server.js:")
            .setView(container)
            .setPositiveButton("Connect") { _, _ ->
                val entered = input.text.toString().trim()
                if (entered.isNotEmpty()) {
                    val cleanHost = entered.removePrefix("http://").removePrefix("https://").substringBefore(":")
                    val port = if (entered.contains(":") && entered.substringAfter(":").toIntOrNull() != null) {
                        entered.substringAfter(":").toInt()
                    } else {
                        8080
                    }
                    AgentLogger.setServerAddress(cleanHost, port, this@MainActivity)
                    Toast.makeText(this@MainActivity, "📡 Telemetry connected to $cleanHost:$port", Toast.LENGTH_SHORT).show()
                    updateTelemetryStatus()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        AgentLogger.logEvent("INFO", "NAVIGATION", "PAGE_UNMOUNT", "MainActivity destroyed", mapOf("activity" to "MainActivity"))
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        touchInterceptor.handleTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }
}