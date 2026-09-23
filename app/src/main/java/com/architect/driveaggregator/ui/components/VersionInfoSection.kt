package com.architect.driveaggregator.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.architect.driveaggregator.ui.viewmodel.AboutVersionViewModel

@Composable
fun VersionInfoSection(
    viewModel: AboutVersionViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Stale Client / Update Banner if newer build detected
        if (uiState.isStale && uiState.updateMessage != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SystemUpdate,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Column {
                            Text(
                                text = "New Build Available!",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Text(
                                text = uiState.latestVersion?.fullVersion ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }

                    Button(
                        onClick = { viewModel.setShowUpdateDialog(true) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary,
                            contentColor = MaterialTheme.colorScheme.onTertiary
                        )
                    ) {
                        Text("Update")
                    }
                }
            }
        }

        // Discrete Version String & About Dialog Trigger Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "MDrive ${uiState.runningVersion.fullVersion}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                TextButton(
                    onClick = { viewModel.setShowAboutDialog(true) }
                ) {
                    Text("About MDrive", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    // About MDrive Dialog
    if (uiState.showAboutDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.setShowAboutDialog(false) },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("About MDrive", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("MDrive aggregates storage across multiple Google Drive accounts with client-side erasure coding (Reed-Solomon shards).", style = MaterialTheme.typography.bodyMedium)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    VersionDetailRow("App Version", uiState.runningVersion.version)
                    VersionDetailRow("Full Version (SemVer)", uiState.runningVersion.fullVersion)
                    VersionDetailRow("Git Commit Hash", uiState.runningVersion.commitHash)
                    VersionDetailRow("Build Timestamp", uiState.runningVersion.buildTimestamp)
                    VersionDetailRow("Environment", uiState.runningVersion.environment)
                    VersionDetailRow("Version Code", uiState.runningVersion.versionCode.toString())
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.setShowAboutDialog(false) }) {
                    Text("Close")
                }
            }
        )
    }

    // Update Prompt Dialog when Stale Client is detected
    if (uiState.showUpdateDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.setShowUpdateDialog(false) },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                    Text("New Build Available", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(uiState.updateMessage ?: "A newer build is available for MDrive.")
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Running: ${uiState.runningVersion.fullVersion}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    Text("Latest: ${uiState.latestVersion?.fullVersion}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text("Built: ${uiState.latestVersion?.buildTimestamp}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        // Action to prompt download or install latest build
                        viewModel.setShowUpdateDialog(false)
                    }
                ) {
                    Text("Download Update")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        viewModel.dismissUpdateForCurrentVersion()
                    }
                ) {
                    Text("Dismiss")
                }
            }
        )
    }
}

@Composable
fun VersionDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        Text(text = value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}
