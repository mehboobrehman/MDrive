package com.architect.driveaggregator.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.architect.driveaggregator.contracts.CloudProviderType
import com.architect.driveaggregator.search.FederatedSearchResult
import com.architect.driveaggregator.telemetry.AgentLogger
import com.architect.driveaggregator.ui.viewmodel.AdvancedSearchViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSearchScreen(
    viewModel: AdvancedSearchViewModel,
    onNavigateBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    var isSearchActive by remember { mutableStateOf(false) }
    var showFilterDialog by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.snackbarMessage) {
        uiState.snackbarMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearSnackbar()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MDrive Advanced Search", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = {
                        AgentLogger.logEvent("INFO", "ACTION", "BUTTON_CLICK", "Clicked Back on Advanced Search")
                        onNavigateBack()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        AgentLogger.logEvent("INFO", "ACTION", "BUTTON_CLICK", "Opened Advanced Filter Sheet")
                        showFilterDialog = true
                    }) {
                        Icon(Icons.Default.Tune, contentDescription = "Advanced Filters")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Material 3 SearchView / SearchBar Component
            SearchBar(
                query = uiState.query,
                onQueryChange = { query ->
                    viewModel.updateQuery(query)
                },
                onSearch = { query ->
                    isSearchActive = false
                    viewModel.executeSearch()
                },
                active = isSearchActive,
                onActiveChange = { active ->
                    isSearchActive = active
                },
                placeholder = { Text("Search across all cloud accounts...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search Icon") },
                trailingIcon = {
                    Row {
                        if (uiState.query.isNotEmpty()) {
                            IconButton(onClick = { viewModel.updateQuery("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear search")
                            }
                        }
                        IconButton(onClick = { showFilterDialog = true }) {
                            Icon(Icons.Default.Tune, contentDescription = "Filter")
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // Real-time Suggestion Dropdown
                if (uiState.suggestions.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(uiState.suggestions) { suggestion ->
                            ListItem(
                                headlineContent = { Text(suggestion) },
                                leadingContent = { Icon(Icons.Default.History, contentDescription = null) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.updateQuery(suggestion)
                                        isSearchActive = false
                                    }
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Type to search virtual files across Google Drive, OneDrive, Dropbox, MEGA, Box, and TeraBox...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Quick Filter Chips Bar
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        selected = uiState.selectedMimeType.isEmpty(),
                        onClick = { viewModel.setMimeTypeFilter("") },
                        label = { Text("All Types") },
                        leadingIcon = if (uiState.selectedMimeType.isEmpty()) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                        } else null
                    )
                }
                item {
                    FilterChip(
                        selected = uiState.selectedMimeType == "pdf" || uiState.selectedMimeType == "document",
                        onClick = { viewModel.setMimeTypeFilter("pdf") },
                        label = { Text("📄 Documents") }
                    )
                }
                item {
                    FilterChip(
                        selected = uiState.selectedMimeType == "image" || uiState.selectedMimeType == "video",
                        onClick = { viewModel.setMimeTypeFilter("image") },
                        label = { Text("🖼️ Media") }
                    )
                }
                item {
                    FilterChip(
                        selected = uiState.selectedMimeType == "zip" || uiState.selectedMimeType == "archive",
                        onClick = { viewModel.setMimeTypeFilter("zip") },
                        label = { Text("📦 Archives") }
                    )
                }
                item {
                    FilterChip(
                        selected = uiState.selectedProviderType == CloudProviderType.GOOGLE_DRIVE,
                        onClick = {
                            val next = if (uiState.selectedProviderType == CloudProviderType.GOOGLE_DRIVE) null else CloudProviderType.GOOGLE_DRIVE
                            viewModel.setProviderFilter(next)
                        },
                        label = { Text("Google Drive") }
                    )
                }
                item {
                    FilterChip(
                        selected = uiState.selectedProviderType == CloudProviderType.ONEDRIVE,
                        onClick = {
                            val next = if (uiState.selectedProviderType == CloudProviderType.ONEDRIVE) null else CloudProviderType.ONEDRIVE
                            viewModel.setProviderFilter(next)
                        },
                        label = { Text("OneDrive") }
                    )
                }
                item {
                    FilterChip(
                        selected = uiState.selectedProviderType == CloudProviderType.DROPBOX,
                        onClick = {
                            val next = if (uiState.selectedProviderType == CloudProviderType.DROPBOX) null else CloudProviderType.DROPBOX
                            viewModel.setProviderFilter(next)
                        },
                        label = { Text("Dropbox") }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Results count and loading indicator
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Results (${uiState.searchResults.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                if (uiState.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }

            // Search Results List
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
            ) {
                if (uiState.searchResults.isEmpty() && !uiState.isLoading) {
                    // MD3 Empty State
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SearchOff,
                            contentDescription = "No results",
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No results found",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Try adjusting your search query, mime-type filters, or cloud providers.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(uiState.searchResults, key = { it.providerId + "_" + it.fileId }) { result ->
                            SearchResultItemCard(result = result)
                        }
                    }
                }
            }
        }
    }

    // Advanced Filter Bottom Sheet / Dialog
    if (showFilterDialog) {
        AlertDialog(
            onDismissRequest = { showFilterDialog = false },
            title = { Text("Advanced Search Filters") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Filter by Cloud Provider:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { viewModel.setProviderFilter(null) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (uiState.selectedProviderType == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("All", fontSize = 12.sp)
                        }
                        Button(
                            onClick = { viewModel.setProviderFilter(CloudProviderType.GOOGLE_DRIVE) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (uiState.selectedProviderType == CloudProviderType.GOOGLE_DRIVE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Google", fontSize = 12.sp)
                        }
                        Button(
                            onClick = { viewModel.setProviderFilter(CloudProviderType.ONEDRIVE) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (uiState.selectedProviderType == CloudProviderType.ONEDRIVE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("OneDrive", fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Clear All Filters:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    OutlinedButton(
                        onClick = {
                            viewModel.clearFilters()
                            showFilterDialog = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Reset Filters")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showFilterDialog = false }) {
                    Text("Apply")
                }
            }
        )
    }
}

@Composable
fun SearchResultItemCard(result: FederatedSearchResult) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    val formattedDate = remember(result.createdAt) {
        if (result.createdAt > 0L) dateFormat.format(Date(result.createdAt)) else "Unknown Date"
    }

    val formattedSize = remember(result.sizeBytes) {
        val kb = result.sizeBytes / 1024.0
        val mb = kb / 1024.0
        if (mb >= 1.0) String.format(Locale.US, "%.1f MB", mb)
        else if (kb >= 1.0) String.format(Locale.US, "%.1f KB", kb)
        else "${result.sizeBytes} B"
    }

    // Provider badge color and icon
    val (providerColor, providerIcon) = when (result.providerType) {
        CloudProviderType.GOOGLE_DRIVE -> Color(0xFF1967D2) to Icons.Default.Cloud
        CloudProviderType.ONEDRIVE -> Color(0xFF0078D4) to Icons.Default.CloudQueue
        CloudProviderType.DROPBOX -> Color(0xFF0061FE) to Icons.Default.FolderShared
        CloudProviderType.MEGA -> Color(0xFFD9272E) to Icons.Default.Lock
        CloudProviderType.BOX -> Color(0xFF0061D5) to Icons.Default.Storage
        CloudProviderType.TERABOX -> Color(0xFF00A4EF) to Icons.Default.CloudSync
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // File Icon
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when {
                        result.mimeType.contains("pdf", true) -> Icons.Default.PictureAsPdf
                        result.mimeType.contains("image", true) -> Icons.Default.Image
                        result.mimeType.contains("video", true) -> Icons.Default.VideoFile
                        result.mimeType.contains("zip", true) -> Icons.Default.FolderZip
                        else -> Icons.Default.InsertDriveFile
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // File Metadata & Rich badges
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = result.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Provider Badge
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = providerColor.copy(alpha = 0.15f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = providerIcon,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = providerColor
                            )
                            Text(
                                text = result.providerType.name.replace("_", " "),
                                style = MaterialTheme.typography.labelSmall,
                                color = providerColor,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Source Badge
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Text(
                            text = if (result.source == FederatedSearchResult.SearchSource.LOCAL_ROOM) "Cached" else "Live",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = formattedSize,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formattedDate,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (!result.aiCategory.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "AI Category: ${result.aiCategory}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
    }
}
