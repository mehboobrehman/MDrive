package com.architect.driveaggregator.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.architect.driveaggregator.ai.AiSmartOrganizer
import com.architect.driveaggregator.data.SmartRecommendationEntity
import com.architect.driveaggregator.data.VirtualFileEntity
import com.architect.driveaggregator.ui.viewmodel.AiSmartDashboardViewModel
import com.architect.driveaggregator.ui.viewmodel.CategoryStorageInfo
import com.architect.driveaggregator.ui.viewmodel.DuplicateGroupInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSmartDashboardScreen(
    viewModel: AiSmartDashboardViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedMainTab by remember { mutableStateOf(0) } // 0: Overview, 1: Duplicates & Cleanup, 2: Categories

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AI Smart Organization") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.runAiOrganizationScan() },
                        enabled = !uiState.isScanning
                    ) {
                        if (uiState.isScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Run AI Scan")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    titleContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Dashboard, contentDescription = null) },
                    label = { Text("Overview") },
                    selected = selectedMainTab == 0,
                    onClick = { selectedMainTab = 0 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.FileCopy, contentDescription = null) },
                    label = { Text("Duplicates") },
                    selected = selectedMainTab == 1,
                    onClick = { selectedMainTab = 1 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Folder, contentDescription = null) },
                    label = { Text("Categories") },
                    selected = selectedMainTab == 2,
                    onClick = { selectedMainTab = 2 }
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (uiState.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    // Status / Error / Success Banners
                    uiState.errorMessage?.let { error ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(error, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }

                    uiState.successMessage?.let { success ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(success, color = MaterialTheme.colorScheme.onPrimaryContainer, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }

                    when (selectedMainTab) {
                        0 -> OverviewTabContent(
                            uiState = uiState,
                            onRunScan = { viewModel.runAiOrganizationScan() }
                        )
                        1 -> DuplicatesTabContent(
                            uiState = uiState,
                            onCleanupDuplicates = { group -> viewModel.cleanupDuplicateGroup(group) },
                            onDismissRecommendation = { id -> viewModel.dismissRecommendation(id) }
                        )
                        2 -> CategoriesTabContent(
                            uiState = uiState,
                            onSelectCategoryTab = { cat -> viewModel.selectCategoryTab(cat) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun OverviewTabContent(
    uiState: com.architect.driveaggregator.ui.viewmodel.AiSmartDashboardUiState,
    onRunScan: () -> Unit
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // Storage Summary Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Total Managed Storage",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = formatBytes(uiState.totalStorageBytes),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onRunScan
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Run AI Organization Scan")
                    }
                }
            }
        }

        // Category Breakdown Section
        item {
            Text(
                text = "Storage by AI Category",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        }

        items(uiState.categoryBreakdown, key = { it.categoryName }) { categoryInfo ->
            CategoryBreakdownItem(categoryInfo, uiState.totalStorageBytes)
        }

        // Active AI Recommendations Section
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "AI Insights & Recommendations (${uiState.activeRecommendations.size})",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (uiState.activeRecommendations.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(24.dp)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.CheckCircleOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Storage is fully optimized!", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "No duplicate files or pending cleanup suggestions.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(uiState.activeRecommendations, key = { it.id }) { recommendation ->
                RecommendationCard(recommendation, onDismiss = { })
            }
        }
    }
}

@Composable
fun CategoryBreakdownItem(info: CategoryStorageInfo, totalBytes: Long) {
    val percentage = if (totalBytes > 0) (info.totalBytes.toFloat() / totalBytes.toFloat()) else 0f
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = getCategoryIcon(info.categoryName),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = info.categoryName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
                Text(
                    text = "${info.fileCount} files (${formatBytes(info.totalBytes)})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { percentage.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}

@Composable
fun DuplicatesTabContent(
    uiState: com.architect.driveaggregator.ui.viewmodel.AiSmartDashboardUiState,
    onCleanupDuplicates: (DuplicateGroupInfo) -> Unit,
    onDismissRecommendation: (Long) -> Unit
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Text(
                text = "Duplicate File Finder",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Review duplicate files identified across your connected cloud storage accounts and reclaim valuable space.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (uiState.duplicateGroups.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(32.dp)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.FileCopy,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("No Duplicate Files Found", style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Run an AI Organization Scan from the Overview tab to detect duplicate items.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(uiState.duplicateGroups, key = { it.recommendationId }) { group ->
                DuplicateGroupCard(
                    group = group,
                    onCleanup = { onCleanupDuplicates(group) },
                    onDismiss = { onDismissRecommendation(group.recommendationId) }
                )
            }
        }
    }
}

@Composable
fun DuplicateGroupCard(
    group: DuplicateGroupInfo,
    onCleanup: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = group.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
                Badge(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                ) {
                    Text("Save ${formatBytes(group.potentialSavingsBytes)}")
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = group.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(
                    onClick = onDismiss
                ) {
                    Text("Dismiss")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onCleanup,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Clean Up Duplicates")
                }
            }
        }
    }
}

@Composable
fun CategoriesTabContent(
    uiState: com.architect.driveaggregator.ui.viewmodel.AiSmartDashboardUiState,
    onSelectCategoryTab: (String) -> Unit
) {
    val categories = listOf(
        AiSmartOrganizer.CATEGORY_DOCUMENTS,
        AiSmartOrganizer.CATEGORY_MEDIA,
        AiSmartOrganizer.CATEGORY_ARCHIVES,
        AiSmartOrganizer.CATEGORY_CODE,
        AiSmartOrganizer.CATEGORY_OTHER
    )

    Column(modifier = Modifier.fillMaxSize()) {
        ScrollableTabRow(
            selectedTabIndex = categories.indexOf(uiState.selectedCategoryTab).coerceAtLeast(0),
            edgePadding = 0.dp
        ) {
            categories.forEach { category ->
                Tab(
                    selected = uiState.selectedCategoryTab == category,
                    onClick = { onSelectCategoryTab(category) },
                    text = { Text(category) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        val files = uiState.categorizedFiles[uiState.selectedCategoryTab] ?: emptyList()

        if (files.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = getCategoryIcon(uiState.selectedCategoryTab),
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "No files in ${uiState.selectedCategoryTab}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(files, key = { it.virtualFileId }) { file ->
                    FileItemRow(file)
                }
            }
        }
    }
}

@Composable
fun FileItemRow(file: VirtualFileEntity) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = getCategoryIcon(file.aiCategory),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.fileName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "ID: ${file.virtualFileId} • Size: ${formatBytes(file.fileSize)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun RecommendationCard(
    recommendation: SmartRecommendationEntity,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (recommendation.category == "DUPLICATE") Icons.Default.FileCopy else Icons.Default.Storage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = recommendation.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = recommendation.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

fun getCategoryIcon(category: String?): ImageVector {
    return when (category) {
        AiSmartOrganizer.CATEGORY_DOCUMENTS -> Icons.Default.Description
        AiSmartOrganizer.CATEGORY_MEDIA -> Icons.Default.PermMedia
        AiSmartOrganizer.CATEGORY_ARCHIVES -> Icons.Default.Archive
        AiSmartOrganizer.CATEGORY_CODE -> Icons.Default.Code
        else -> Icons.Default.InsertDriveFile
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    val gb = mb / 1024.0
    return "%.2f GB".format(gb)
}
