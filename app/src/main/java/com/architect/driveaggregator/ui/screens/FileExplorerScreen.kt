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
import com.architect.driveaggregator.ui.model.VirtualFileItem
import com.architect.driveaggregator.ui.viewmodel.FileExplorerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileExplorerScreen(
    viewModel: FileExplorerViewModel,
    onNavigateBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    var showUploadDialog by remember { mutableStateOf(false) }

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
                title = { Text("MDrive File Explorer", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = {
                        AgentLogger.logEvent("INFO", "ACTION", "BUTTON_CLICK", "Clicked back arrow on File Explorer")
                        onNavigateBack()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        AgentLogger.logEvent("INFO", "ACTION", "BUTTON_CLICK", "Clicked refresh button on File Explorer")
                        viewModel.loadVirtualFiles()
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
                    AgentLogger.logEvent("INFO", "ACTION", "BUTTON_CLICK", "Clicked Upload Virtual File FAB")
                    showUploadDialog = true
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
                    text = { Text("All Files (${uiState.files.size})") }
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
                    text = { Text("Completed (${uiState.files.count { it.sagaState == "COMPLETED" }})") }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Main Content based on selected tab
            when (uiState.selectedFilterTab) {
                0 -> {
                    // All Files List with active Progress Tracker overlay header if transfers active
                    FileListContent(
                        files = uiState.files.filter { it.fileName.contains(uiState.searchQuery, ignoreCase = true) },
                        transfers = uiState.transfers,
                        isLoading = uiState.isLoading,
                        onDownload = { viewModel.initiateDownload(it) },
                        onDelete = { viewModel.deleteVirtualFile(it.virtualFileId) },
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
                        files = uiState.files.filter { it.sagaState == "COMPLETED" && it.fileName.contains(uiState.searchQuery, ignoreCase = true) },
                        transfers = emptyList(),
                        isLoading = uiState.isLoading,
                        onDownload = { viewModel.initiateDownload(it) },
                        onDelete = { viewModel.deleteVirtualFile(it.virtualFileId) },
                        onPauseTransfer = {},
                        onResumeTransfer = {},
                        onCancelTransfer = {}
                    )
                }
            }
        }
    }

    if (showUploadDialog) {
        UploadVirtualFileDialog(
            onDismiss = { showUploadDialog = false },
            onUpload = { name, size, mime ->
                viewModel.initiateUpload(name, size, mime)
                showUploadDialog = false
            }
        )
    }
}

@Composable
fun FileListContent(
    files: List<VirtualFileItem>,
    transfers: List<TransferProgressItem>,
    isLoading: Boolean,
    onDownload: (VirtualFileItem) -> Unit,
    onDelete: (VirtualFileItem) -> Unit,
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
                    text = "Virtual Files Library",
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
        } else if (files.isEmpty()) {
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
                            text = "No virtual files found",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        } else {
            items(files, key = { it.virtualFileId }) { fileItem ->
                VirtualFileCard(
                    fileItem = fileItem,
                    onDownload = { onDownload(fileItem) },
                    onDelete = { onDelete(fileItem) }
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(80.dp)) // Padding for FAB
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

@Composable
fun UploadVirtualFileDialog(
    onDismiss: () -> Unit,
    onUpload: (String, Long, String) -> Unit
) {
    var fileName by remember { mutableStateOf("Document_Archive.zip") }
    var fileSizeMb by remember { mutableStateOf("25") }
    var mimeType by remember { mutableStateOf("application/zip") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Upload Virtual File (Chunked)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text("File Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = fileSizeMb,
                    onValueChange = { fileSizeMb = it },
                    label = { Text("Size (MB)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = mimeType,
                    onValueChange = { mimeType = it },
                    label = { Text("MIME Type") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    AgentLogger.logEvent("INFO", "ACTION", "BUTTON_CLICK", "Clicked Start Upload & Sharding: $fileName", mapOf("name" to fileName, "sizeMb" to fileSizeMb, "mimeType" to mimeType))
                    val sizeBytes = (fileSizeMb.toLongOrNull() ?: 10L) * 1024 * 1024
                    onUpload(fileName, sizeBytes, mimeType)
                }
            ) {
                Text("Start Upload & Sharding")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
