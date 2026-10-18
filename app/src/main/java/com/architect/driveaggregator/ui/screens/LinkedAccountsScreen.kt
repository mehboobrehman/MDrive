package com.architect.driveaggregator.ui.screens

import com.architect.driveaggregator.telemetry.AgentLogger
import com.architect.driveaggregator.util.FileUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.architect.driveaggregator.ui.model.AccountStatus
import com.architect.driveaggregator.ui.model.CloudProviderType
import com.architect.driveaggregator.ui.model.DriveAccountInfo
import com.architect.driveaggregator.ui.model.PooledStorageOverview
import com.architect.driveaggregator.ui.viewmodel.LinkedAccountsUiState
import com.architect.driveaggregator.ui.viewmodel.LinkedAccountsViewModel
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkedAccountsScreen(
    viewModel: LinkedAccountsViewModel,
    onLaunchAuthUrl: (String) -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    var showAddProviderDialog by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.authUrlToLaunch) {
        uiState.authUrlToLaunch?.let { url ->
            onLaunchAuthUrl(url)
            viewModel.onAuthUrlConsumed()
        }
    }

    if (showAddProviderDialog) {
        AddProviderSelectionDialog(
            onDismiss = { showAddProviderDialog = false },
            onSelectProvider = { providerType ->
                showAddProviderDialog = false
                viewModel.triggerLinkNewAccount(providerType)
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MDrive Cloud Aggregator", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = {
                        AgentLogger.logEvent("INFO", "ACTION", "BUTTON_CLICK", "Clicked Refresh on Linked Accounts screen")
                        viewModel.loadAccounts()
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh Accounts")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    AgentLogger.logEvent("INFO", "ACTION", "BUTTON_CLICK", "Clicked Link Cloud Storage FAB")
                    showAddProviderDialog = true
                },
                icon = { Icon(Icons.Default.Add, contentDescription = "Link Cloud") },
                text = { Text("Link Cloud Account") }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (uiState.isLoading && uiState.pooledStorage.accounts.isEmpty()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp)
                ) {
                    // Primary Account Master Banner (Google ID OAuth Anchor)
                    item {
                        PrimaryAccountMasterCard(
                            primaryAccount = uiState.pooledStorage.accounts.firstOrNull { it.providerType == CloudProviderType.GOOGLE_DRIVE },
                            onLinkGoogleMaster = { viewModel.triggerLinkNewAccount(CloudProviderType.GOOGLE_DRIVE) }
                        )
                    }

                    // Pooled Storage Overview Card
                    item {
                        PooledStorageCard(overview = uiState.pooledStorage)
                    }

                    // Section Header for Linked Secondary Accounts
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Linked Cloud Accounts (${uiState.pooledStorage.accounts.size})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Error Banner if any
                    uiState.errorMessage?.let { error ->
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = error,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(16.dp)
                                )
                            }
                        }
                    }

                    // Accounts List
                    if (uiState.pooledStorage.accounts.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No cloud storage accounts linked yet.\nTap 'Link Cloud Account' to connect Google Drive, OneDrive, Dropbox, MEGA, Box, or TeraBox.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                            }
                        }
                    } else {
                        items(uiState.pooledStorage.accounts, key = { it.accountId }) { account ->
                            AccountItemCard(
                                account = account,
                                onUnlink = { viewModel.unlinkAccount(account.accountId) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PrimaryAccountMasterCard(
    primaryAccount: DriveAccountInfo?,
    onLinkGoogleMaster: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Primary Master Login (Google ID OAuth)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (primaryAccount != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = if (primaryAccount != null) "Active Master" else "Not Signed In",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = if (primaryAccount != null) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (primaryAccount != null) {
                Text(
                    text = "Authenticated as ${primaryAccount.email}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    text = "Governing secure storage federation and multi-provider token vault.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            } else {
                Text(
                    text = "Authenticate with your Google ID OAuth credential to establish the master session and aggregate secondary platforms.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Button(
                    onClick = onLinkGoogleMaster,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("Sign in with Google ID")
                }
            }
        }
    }
}

@Composable
fun AddProviderSelectionDialog(
    onDismiss: () -> Unit,
    onSelectProvider: (CloudProviderType) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link Cloud Storage Account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Select cloud platform to bind under your Google ID primary master session:")
                Spacer(modifier = Modifier.height(4.dp))
                
                CloudProviderType.values().forEach { provider ->
                    OutlinedButton(
                        onClick = { onSelectProvider(provider) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(provider.displayName, fontWeight = FontWeight.Bold)
                            Text("Default: ${FileUtils.formatFileSize(provider.defaultQuotaBytes)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun PooledStorageCard(overview: PooledStorageOverview) {
    val usedFormatted = FileUtils.formatFileSize(overview.totalUsedBytes)
    val totalFormatted = FileUtils.formatFileSize(overview.totalCapacityBytes)
    val freeFormatted = FileUtils.formatFileSize(overview.totalFreeBytes)

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Aggregate Pooled Storage",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            LinearProgressIndicator(
                progress = { overview.overallUsagePercentage },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surface
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Used: $usedFormatted",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Free: $freeFormatted",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Total: $totalFormatted",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Text(
                text = "${overview.accounts.size} cloud account(s) pooled across Google Drive, OneDrive, Dropbox, MEGA, Box & TeraBox",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun AccountItemCard(
    account: DriveAccountInfo,
    onUnlink: () -> Unit
) {
    var showUnlinkDialog by remember { mutableStateOf(false) }

    if (showUnlinkDialog) {
        AlertDialog(
            onDismissRequest = { showUnlinkDialog = false },
            title = { Text("Unlink Account") },
            text = { Text("Are you sure you want to unlink ${account.email}? Pooled storage capacity will be reduced accordingly.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUnlinkDialog = false
                        onUnlink()
                    }
                ) {
                    Text("Unlink", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnlinkDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cloud,
                            contentDescription = account.providerType.displayName,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = account.providerType.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = account.email,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                IconButton(onClick = {
                    AgentLogger.logEvent("INFO", "ACTION", "BUTTON_CLICK", "Clicked Unlink account button", mapOf("email" to account.email))
                    showUnlinkDialog = true
                }) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Unlink Account",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            LinearProgressIndicator(
                progress = { account.usagePercentage },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = when (account.status) {
                    AccountStatus.ACTIVE -> MaterialTheme.colorScheme.primary
                    AccountStatus.SYNCING -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.error
                },
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${FileUtils.formatFileSize(account.usedBytes)} used of ${FileUtils.formatFileSize(account.totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                StatusBadge(status = account.status)
            }
        }
    }
}

@Composable
fun StatusBadge(status: AccountStatus) {
    val (text, color, containerColor) = when (status) {
        AccountStatus.ACTIVE -> Triple("Active", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer)
        AccountStatus.SYNCING -> Triple("Syncing", MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.tertiaryContainer)
        AccountStatus.ERROR -> Triple("Error", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer)
        AccountStatus.EXPIRED_TOKEN -> Triple("Re-auth", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.errorContainer)
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = containerColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (status != AccountStatus.ACTIVE && status != AccountStatus.SYNCING) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = color
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
