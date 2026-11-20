package com.architect.driveaggregator

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.architect.driveaggregator.auth.GoogleOAuthManager
import com.architect.driveaggregator.security.SecureTokenVault
import com.architect.driveaggregator.telemetry.AgentLogger
import com.architect.driveaggregator.ui.FileExplorerActivity
import com.architect.driveaggregator.ui.LinkedAccountsActivity
import com.architect.driveaggregator.ui.components.MDriveNavigationDrawer
import com.architect.driveaggregator.ui.components.MDriveTopAppBar
import com.architect.driveaggregator.ui.theme.MDriveTheme
import com.architect.driveaggregator.util.ActivityTouchInterceptor
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var oauthManager: GoogleOAuthManager

    @Inject
    lateinit var tokenVault: SecureTokenVault

    private val touchInterceptor = ActivityTouchInterceptor("MainActivity")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AgentLogger.logEvent("INFO", "NAVIGATION", "PAGE_MOUNT", "MainActivity created", mapOf("activity" to "MainActivity"))

        setContent {
            MDriveTheme {
                MainScreen(
                    tokenVault = tokenVault,
                    onNavigateToLinkedAccounts = {
                        AgentLogger.logEvent("INFO", "ACTION", "BUTTON_CLICK", "Clicked Manage Linked Accounts button")
                        startActivity(Intent(this, LinkedAccountsActivity::class.java))
                    },
                    onNavigateToFileExplorer = {
                        AgentLogger.logEvent("INFO", "ACTION", "BUTTON_CLICK", "Clicked Open File Explorer button")
                        startActivity(Intent(this, FileExplorerActivity::class.java))
                    },
                    onNavigateToAdvancedSearch = {
                        AgentLogger.logEvent("INFO", "ACTION", "BUTTON_CLICK", "Clicked Advanced Federated Search button")
                        startActivity(Intent(this, com.architect.driveaggregator.ui.AdvancedSearchActivity::class.java))
                    },
                    onNavigateToAiDashboard = {
                        AgentLogger.logEvent("INFO", "ACTION", "BUTTON_CLICK", "Clicked AI Smart Organization Dashboard button")
                        startActivity(Intent(this, com.architect.driveaggregator.ui.AiSmartDashboardActivity::class.java))
                    },
                    onNavigateToSettings = {
                        AgentLogger.logEvent("INFO", "ACTION", "BUTTON_CLICK", "Clicked Settings button")
                        startActivity(Intent(this, com.architect.driveaggregator.ui.settings.SettingsActivity::class.java))
                    },
                    onLoginClick = {
                        initiateGoogleLogin()
                    }
                )
            }
        }

        handleIntent(intent)
    }

    private fun initiateGoogleLogin() {
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
                this,
                e.message ?: GoogleOAuthManager.OAUTH_MISCONFIGURED_MESSAGE,
                Toast.LENGTH_LONG
            ).show()
        }
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
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return

        // 1. Google OAuth redirect handling
        if (uri.toString().startsWith(GoogleOAuthManager.REDIRECT_URI)) {
            val error = uri.getQueryParameter("error")
            val errorDescription = uri.getQueryParameter("error_description")
            val code = uri.getQueryParameter("code")
            val state = uri.getQueryParameter("state")

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

        // 2. HTTP Telemetry Config URL
        if (uri.scheme?.equals("http", ignoreCase = true) == true && uri.path?.contains("/api/config") == true) {
            val host = uri.host
            val port = if (uri.port != -1) uri.port else 8080
            if (!host.isNullOrBlank()) {
                AgentLogger.setServerAddress(host, port, this)
                Toast.makeText(this, "📡 Telemetry connected to $host:$port", Toast.LENGTH_LONG).show()
                intent.data = null
                return
            }
        }

        // 3. MDrive Custom Scheme
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
                intent.data = null
                return
            }
        }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    tokenVault: SecureTokenVault,
    onNavigateToLinkedAccounts: () -> Unit,
    onNavigateToFileExplorer: () -> Unit,
    onNavigateToAdvancedSearch: () -> Unit,
    onNavigateToAiDashboard: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onLoginClick: () -> Unit
) {
    val context = LocalContext.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    
    var accountsCount by remember { mutableIntStateOf(tokenVault.getAllAccountIds().size) }
    var isVaultDegraded by remember { mutableStateOf(tokenVault.isDegraded()) }
    var telemetryUrl by remember { mutableStateOf(AgentLogger.getServerUrl()) }
    var telemetryIp by remember { mutableStateOf(AgentLogger.getServerIp(context)) }
    var showTelemetryDialog by remember { mutableStateOf(false) }

    // Periodic refresh of status
    LaunchedEffect(Unit) {
        while(true) {
            accountsCount = tokenVault.getAllAccountIds().size
            isVaultDegraded = tokenVault.isDegraded()
            telemetryUrl = AgentLogger.getServerUrl()
            telemetryIp = AgentLogger.getServerIp(context)
            delay(2000)
        }
    }

    if (showTelemetryDialog) {
        TelemetryConfigDialog(
            currentIp = telemetryIp ?: "192.168.105.79",
            onDismiss = { showTelemetryDialog = false },
            onConnect = { ip, port ->
                AgentLogger.setServerAddress(ip, port, context)
                telemetryUrl = AgentLogger.getServerUrl()
                telemetryIp = AgentLogger.getServerIp(context)
                showTelemetryDialog = false
            }
        )
    }

    MDriveNavigationDrawer(
        drawerState = drawerState,
        currentRoute = "dashboard",
        accountsCount = accountsCount,
        onItemSelected = { route ->
            scope.launch { drawerState.close() }
            when (route) {
                "accounts" -> onNavigateToLinkedAccounts()
                "settings" -> onNavigateToSettings()
                // Handle other routes if needed
            }
        }
    ) {
        Scaffold(
            topBar = {
                MDriveTopAppBar(
                    title = "MDrive Dashboard",
                    onMenuClick = { scope.launch { drawerState.open() } },
                    onSearchClick = onNavigateToAdvancedSearch,
                    onMoreClick = { /* More options */ }
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                TelemetryBanner(
                    serverUrl = telemetryUrl,
                    savedIp = telemetryIp,
                    onClick = { showTelemetryDialog = true }
                )

                if (isVaultDegraded) {
                    VaultDegradedWarning()
                }

                DashboardContent(
                    onLoginClick = onLoginClick,
                    onManageAccountsClick = onNavigateToLinkedAccounts,
                    onFileExplorerClick = onNavigateToFileExplorer,
                    onAdvancedSearchClick = onNavigateToAdvancedSearch,
                    onAiDashboardClick = onNavigateToAiDashboard,
                    onSettingsClick = onNavigateToSettings
                )
            }
        }
    }
}

@Composable
fun TelemetryBanner(
    serverUrl: String?,
    savedIp: String?,
    onClick: () -> Unit
) {
    val backgroundColor = when {
        serverUrl != null -> Color(0xFF15803D) // Green
        savedIp != null -> Color(0xFFC2410C) // Orange
        else -> Color(0xFF334155) // Slate
    }
    val text = when {
        serverUrl != null -> "📡 Telemetry: ${serverUrl.removePrefix("http://")} (Connected)"
        savedIp != null -> "📡 Telemetry: $savedIp:8080 (Tap to Reconnect)"
        else -> "📡 Telemetry: Not Connected (Tap to Configure)"
    }

    Surface(
        color = backgroundColor,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Text(
            text = text,
            color = Color.White,
            modifier = Modifier.padding(12.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@Composable
fun VaultDegradedWarning() {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "⚠️ Secure storage degraded: tokens are not encrypted. Please re-authenticate your Google accounts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
fun DashboardContent(
    onLoginClick: () -> Unit,
    onManageAccountsClick: () -> Unit,
    onFileExplorerClick: () -> Unit,
    onAdvancedSearchClick: () -> Unit,
    onAiDashboardClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val items = listOf(
        DashboardItemData("Google Login", Icons.AutoMirrored.Filled.Login, "Connect drive.file", onLoginClick),
        DashboardItemData("Accounts", Icons.Default.Cloud, "Manage linked accounts", onManageAccountsClick),
        DashboardItemData("Explorer", Icons.Default.Folder, "Browse your files", onFileExplorerClick),
        DashboardItemData("Search", Icons.Default.Search, "Federated cross-cloud search", onAdvancedSearchClick),
        DashboardItemData("AI Smart", Icons.Default.AutoAwesome, "AI organization", onAiDashboardClick),
        DashboardItemData("Settings", Icons.Default.Settings, "App configuration", onSettingsClick)
    )

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(items) { item ->
            DashboardCard(item)
        }
    }
}

data class DashboardItemData(
    val title: String,
    val icon: ImageVector,
    val subtitle: String,
    val onClick: () -> Unit
)

@Composable
fun DashboardCard(item: DashboardItemData) {
    ElevatedCard(
        onClick = item.onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Text(
                text = item.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun TelemetryConfigDialog(
    currentIp: String,
    onDismiss: () -> Unit,
    onConnect: (String, Int) -> Unit
) {
    var ipText by remember { mutableStateOf(currentIp) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("📡 Telemetry Configuration") },
        text = {
            Column {
                Text("Enter the host IP of your development machine running log-server.js:")
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = ipText,
                    onValueChange = { ipText = it },
                    label = { Text("Host IP") },
                    placeholder = { Text("e.g. 192.168.105.79") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val entered = ipText.trim()
                    if (entered.isNotEmpty()) {
                        val cleanHost = entered.removePrefix("http://").removePrefix("https://").substringBefore(":")
                        val port = if (entered.contains(":") && entered.substringAfter(":").toIntOrNull() != null) {
                            entered.substringAfter(":").toInt()
                        } else {
                            8080
                        }
                        onConnect(cleanHost, port)
                    }
                }
            ) {
                Text("Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
