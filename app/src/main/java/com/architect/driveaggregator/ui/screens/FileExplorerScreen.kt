package com.architect.driveaggregator.ui.screens

import com.architect.driveaggregator.telemetry.AgentLogger
import androidx.compose.animation.AnimatedVisibility
import com.architect.driveaggregator.util.FileUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.HorizontalDivider
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
import com.architect.driveaggregator.ui.model.TransferProgressItem
import com.architect.driveaggregator.ui.model.TransferStatus
import com.architect.driveaggregator.ui.model.TransferType
import com.architect.driveaggregator.ui.model.ExplorerItem
import com.architect.driveaggregator.contracts.CloudProviderType
import com.architect.driveaggregator.provider.CloudFile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileExplorerScreen(
    viewModel: FileExplorerViewModel,
    onNavigateBack: () -> Unit = {},
    onLaunchFilePicker: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.snackbarMessage) {
        uiState.snackbarMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearSnackbar()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("MDrive File Explorer", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        if (uiState.currentFolderId != "root") {
                            Text(
                                text = if (uiState.currentProviderType != null) "Cloud: ${uiState.currentProviderType}" else "Virtual Root",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (uiState.folderHistory.isNotEmpty()) {
                        IconButton(onClick = {
                            AgentLogger.logEvent("INFO", "ACTION", "BUTTON_CLICK", "Clicked back arrow on File Explorer")
                            viewModel.navigateBack()
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Navigate Up")
                        }
                    } else {
                        IconButton(onClick = {
                            AgentLogger.logEvent("INFO", "ACTION", "BUTTON_CLICK", "Clicked drawer/back on File Explorer")
                            onNavigateBack()
                        }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = {
                        AgentLogger.logEvent("INFO", "ACTION", "BUTTON_CLICK", "Clicked refresh button on File Explorer")
                        viewModel.loadExplorerItems()
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    AgentLogger.logEvent("INFO", "ACTION", "BUTTON_CLICK", "Clicked Upload Virtual File FAB - Launching File Picker")
                    onLaunchFilePicker()
                },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Upload Virtual File")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Search Bar & Filters
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    placeholder = { Text("Search virtual files across accounts...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                    trailingIcon = {
                        if (uiState.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear search")
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent
                    )
                )
            }

            // Filter Tabs: All Files, Active Transfers, Completed
            ScrollableTabRow(
                selectedTabIndex = uiState.selectedFilterTab,
                modifier = Modifier.fillMaxWidth(),
                edgePadding = 16.dp
            ) {
                Tab(
                    selected = uiState.selectedFilterTab == 0,
                    onClick = {
                        AgentLogger.logEvent("INFO", "ACTION", "BUTTON_CLICK", "Selected All Files tab")
                        viewModel.setSelectedFilterTab(0)
                    },
                    text = { Text("All Items (${uiState.explorerItems.size})") }
                )
                Tab(
                    selected = uiState.selectedFilterTab == 1,
                    onClick = {
                        AgentLogger.logEvent("INFO", "ACTION", "BUTTON_CLICK", "Selected Active Transfers tab")
                        viewModel.setSelectedFilterTab(1)
                    },
                    text = { Text("Active Transfers (${uiState.transfers.count { it.status == TransferStatus.IN_PROGRESS || it.status == TransferStatus.PAUSED }})") }
                )
                Tab(
                    selected = uiState.selectedFilterTab == 2,
                    onClick = {
                        AgentLogger.logEvent("INFO", "ACTION", "BUTTON_CLICK", "Selected Completed tab")
                        viewModel.setSelectedFilterTab(2)
                    },
                    text = { Text("Completed (${uiState.explorerItems.count { it is ExplorerItem.VirtualFile && it.virtualFile.sagaState == "COMPLETED" }})") }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Main Content based on selected tab
            when (uiState.selectedFilterTab) {
                0 -> {
                    // All Files List with active Progress Tracker overlay header if transfers active
                    FileListContent(
                        explorerItems = uiState.explorerItems.filter { 
                            when (it) {
                                is ExplorerItem.VirtualFile -> it.virtualFile.fileName.contains(uiState.searchQuery, ignoreCase = true)
                                is ExplorerItem.CloudFile -> it.cloudFile.name.contains(uiState.searchQuery, ignoreCase = true)
                            }
                        },
                        transfers = uiState.transfers,
                        isLoading = uiState.isLoading,
                        onVirtualDownload = { viewModel.initiateDownload(it) },
                        onVirtualDelete = { viewModel.deleteVirtualFile(it.virtualFileId) },
                        onCloudClick = { cloudFile, providerType ->
                            if (cloudFile.isFolder) {
                                viewModel.navigateToFolder(cloudFile.id, providerType)
                            } else {
                                // Handle cloud file click (e.g. download or preview)
                                AgentLogger.logEvent("INFO", "UI", "CLOUD_FILE_CLICK", "Clicked cloud file ${cloudFile.name}")
                            }
                        },
                        onPauseTransfer = { viewModel.pauseTransfer(it) },
                        onResumeTransfer = { viewModel.resumeTransfer(it) },
                        onCancelTransfer = { viewModel.cancelTransfer(it) }
                    )
                }
                1 -> {
                    // Active Transfers Tracker view
                    ActiveTransfersView(
                        transfers = uiState.transfers,
                        onPause = { viewModel.pauseTransfer(it) },
                        onResume = { viewModel.resumeTransfer(it) },
                        onCancel = { viewModel.cancelTransfer(it) }
                    )
                }
                2 -> {
                    // Completed files
                    FileListContent(
                        explorerItems = uiState.explorerItems.filter { 
                            it is ExplorerItem.VirtualFile && it.virtualFile.sagaState == "COMPLETED" && 
                            it.virtualFile.fileName.contains(uiState.searchQuery, ignoreCase = true) 
                        },
                        transfers = emptyList(),
                        isLoading = uiState.isLoading,
                        onVirtualDownload = { viewModel.initiateDownload(it) },
                        onVirtualDelete = { viewModel.deleteVirtualFile(it.virtualFileId) },
                        onCloudClick = { _, _ -> },
                        onPauseTransfer = {},
                        onResumeTransfer = {},
                        onCancelTransfer = {}
                    )
                }
            }
        }
    }


}

@Composable
fun FileListContent(
    explorerItems: List<ExplorerItem>,
    transfers: List<TransferProgressItem>,
    isLoading: Boolean,
    onVirtualDownload: (VirtualFileItem) -> Unit,
    onVirtualDelete: (VirtualFileItem) -> Unit,
    onCloudClick: (CloudFile, CloudProviderType) -> Unit,
    onPauseTransfer: (String) -> Unit,
    onResumeTransfer: (String) -> Unit,
    onCancelTransfer: (String) -> Unit
) {
    val activeTransfers = transfers.filter { it.status == TransferStatus.IN_PROGRESS || it.status == TransferStatus.PAUSED }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Active Progress Tracker Banner Section if any active
        if (activeTransfers.isNotEmpty()) {
            item {
                Text(
                    text = "Active Transfer Progress Tracker",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
            items(activeTransfers, key = { "transfer_" + it.transferId }) { transfer ->
                ProgressTrackerCard(
                    transfer = transfer,
                    onPause = { onPauseTransfer(transfer.transferId) },
                    onResume = { onResumeTransfer(transfer.transferId) },
                    onCancel = { onCancelTransfer(transfer.transferId) }
                )
            }
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = "File Explorer",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        if (isLoading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
        } else if (explorerItems.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(64.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No items found",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        } else {
            items(explorerItems, key = { 
                when (it) {
                    is ExplorerItem.VirtualFile -> "virtual_" + it.virtualFile.virtualFileId
                    is ExplorerItem.CloudFile -> "cloud_" + it.cloudFile.id
                }
            }) { item ->
                when (item) {
                    is ExplorerItem.VirtualFile -> {
                        VirtualFileCard(
                            fileItem = item.virtualFile,
                            onDownload = { onVirtualDownload(item.virtualFile) },
                            onDelete = { onVirtualDelete(item.virtualFile) }
                        )
                    }
                    is ExplorerItem.CloudFile -> {
                        CloudFileCard(
                            cloudFile = item.cloudFile,
                            providerType = item.providerType,
                            onClick = { onCloudClick(item.cloudFile, item.providerType) }
                        )
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(80.dp)) // Padding for FAB
        }
    }
}

@Composable
fun CloudFileCard(
    cloudFile: CloudFile,
    providerType: CloudProviderType,
    onClick: (CloudFile, CloudProviderType) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick(cloudFile, providerType) },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.tertiaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (cloudFile.isFolder) Icons.Default.Folder else Icons.Default.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = cloudFile.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (cloudFile.isFolder) "Folder" else "${FileUtils.formatFileSize(cloudFile.size)} • ${providerType.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            if (!cloudFile.isFolder) {
                IconButton(onClick = { /* TODO: Implement cloud file download/options */ }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Options")
                }
            }
        }
    }
}

@Composable
fun VirtualFileCard(
    fileItem: VirtualFileItem,
    onDownload: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
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
                            imageVector = when {
                                fileItem.mimeType.contains("pdf") -> Icons.Default.Description
                                fileItem.mimeType.contains("zip") || fileItem.mimeType.contains("tar") -> Icons.Default.Archive
                                else -> Icons.Default.InsertDriveFile
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = fileItem.fileName,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${FileUtils.formatFileSize(fileItem.fileSize)} • ${fileItem.totalShardsCount} Chunks across multi-Drive",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                // Status Badge
                Badge(
                    containerColor = when (fileItem.sagaState) {
                        "COMPLETED" -> MaterialTheme.colorScheme.primary
                        "UPLOADING", "DOWNLOADING", "SHARDING" -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.error
                    }
                ) {
                    Text(
                        text = fileItem.sagaState,
                        color = when (fileItem.sagaState) {
                            "COMPLETED" -> MaterialTheme.colorScheme.onPrimary
                            "UPLOADING", "DOWNLOADING", "SHARDING" -> MaterialTheme.colorScheme.onTertiary
                            else -> MaterialTheme.colorScheme.onError
                        },
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Shard progress indicator
            LinearProgressIndicator(
                progress = { if (fileItem.totalShardsCount > 0) fileItem.uploadedShardsCount.toFloat() / fileItem.totalShardsCount.toFloat() else 0f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Shards: ${fileItem.uploadedShardsCount}/${fileItem.totalShardsCount} stored",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )

                Row {
                    OutlinedButton(
                        onClick = {
                            AgentLogger.logEvent("INFO", "ACTION", "BUTTON_CLICK", "Clicked Reconstruct & Download file: ${fileItem.fileName}", mapOf("fileId" to fileItem.virtualFileId))
                            onDownload()
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Reconstruct & Download", fontSize = 12.sp)
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = {
                            AgentLogger.logEvent("INFO", "ACTION", "BUTTON_CLICK", "Clicked Delete file: ${fileItem.fileName}", mapOf("fileId" to fileItem.virtualFileId))
                            onDelete()
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun ActiveTransfersView(
    transfers: List<TransferProgressItem>,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit
) {
    val activeList = transfers.filter { it.status == TransferStatus.IN_PROGRESS || it.status == TransferStatus.PAUSED }

    if (activeList.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Sync,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "No active upload/download tasks",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(activeList, key = { it.transferId }) { transfer ->
                ProgressTrackerCard(
                    transfer = transfer,
                    onPause = { onPause(transfer.transferId) },
                    onResume = { onResume(transfer.transferId) },
                    onCancel = { onCancel(transfer.transferId) }
                )
            }
        }
    }
}

@Composable
fun ProgressTrackerCard(
    transfer: TransferProgressItem,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = when (transfer.transferType) {
                            TransferType.UPLOAD -> Icons.Default.CloudUpload
                            TransferType.DOWNLOAD -> Icons.Default.CloudDownload
                            TransferType.SYNC -> Icons.Default.Sync
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = "${transfer.transferType.name}: ${transfer.fileName}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${FileUtils.formatFileSize(transfer.bytesTransferred)} / ${FileUtils.formatFileSize(transfer.totalBytes)} (${(transfer.progressPercent * 100).toInt()}%)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                // Controls: Pause / Resume / Cancel
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (transfer.status) {
                        TransferStatus.IN_PROGRESS -> {
                            IconButton(onClick = onPause, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Pause, contentDescription = "Pause", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        TransferStatus.PAUSED -> {
                            IconButton(onClick = onResume, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Resume", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                        else -> {}
                    }
                    IconButton(onClick = onCancel, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            LinearProgressIndicator(
                progress = { transfer.progressPercent },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surface,
            )

            if (transfer.totalShards != null && transfer.currentShardIndex != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Processing Chunk ${transfer.currentShardIndex + 1} of ${transfer.totalShards} across multi-Drive shards",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}


