package com.architect.driveaggregator.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.architect.driveaggregator.ai.AiSmartOrganizer
import com.architect.driveaggregator.data.SmartRecommendationDao
import com.architect.driveaggregator.data.SmartRecommendationEntity
import com.architect.driveaggregator.data.VirtualFileDao
import com.architect.driveaggregator.data.VirtualFileEntity
import com.architect.driveaggregator.telemetry.AgentLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CategoryStorageInfo(
    val categoryName: String,
    val fileCount: Int,
    val totalBytes: Long
)

data class DuplicateGroupInfo(
    val recommendationId: Long,
    val title: String,
    val description: String,
    val potentialSavingsBytes: Long,
    val relatedFileIds: List<String>
)

data class AiSmartDashboardUiState(
    val isLoading: Boolean = false,
    val isScanning: Boolean = false,
    val totalStorageBytes: Long = 0L,
    val categoryBreakdown: List<CategoryStorageInfo> = emptyList(),
    val activeRecommendations: List<SmartRecommendationEntity> = emptyList(),
    val duplicateGroups: List<DuplicateGroupInfo> = emptyList(),
    val categorizedFiles: Map<String, List<VirtualFileEntity>> = emptyMap(),
    val selectedCategoryTab: String = AiSmartOrganizer.CATEGORY_DOCUMENTS,
    val errorMessage: String? = null,
    val successMessage: String? = null
)

@HiltViewModel
class AiSmartDashboardViewModel @Inject constructor(
    application: Application,
    private val aiSmartOrganizer: AiSmartOrganizer,
    private val virtualFileDao: VirtualFileDao,
    private val smartRecommendationDao: SmartRecommendationDao
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(AiSmartDashboardUiState())
    val uiState: StateFlow<AiSmartDashboardUiState> = _uiState.asStateFlow()

    init {
        loadDashboardData()
        observeRecommendations()
    }

    fun loadDashboardData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val allFiles = virtualFileDao.getAllFiles()
                
                // Calculate storage per category
                val categories = listOf(
                    AiSmartOrganizer.CATEGORY_DOCUMENTS,
                    AiSmartOrganizer.CATEGORY_MEDIA,
                    AiSmartOrganizer.CATEGORY_ARCHIVES,
                    AiSmartOrganizer.CATEGORY_CODE,
                    AiSmartOrganizer.CATEGORY_OTHER
                )
                
                val categorizedMap = allFiles.groupBy { it.aiCategory ?: AiSmartOrganizer.CATEGORY_OTHER }
                val breakdown = categories.map { cat ->
                    val filesInCat = categorizedMap[cat] ?: emptyList()
                    CategoryStorageInfo(
                        categoryName = cat,
                        fileCount = filesInCat.size,
                        totalBytes = filesInCat.sumOf { it.fileSize }
                    )
                }
                val totalBytes = allFiles.sumOf { it.fileSize }

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        totalStorageBytes = totalBytes,
                        categoryBreakdown = breakdown,
                        categorizedFiles = categorizedMap
                    )
                }
                AgentLogger.logEvent("INFO", "AI_DASHBOARD", "LOADED", "Loaded dashboard data: ${allFiles.size} files, total bytes: $totalBytes")
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = "Failed to load dashboard data: ${e.message}"
                    )
                }
                AgentLogger.logEvent("ERROR", "AI_DASHBOARD", "LOAD_FAIL", "Failed to load dashboard data: ${e.message}")
            }
        }
    }

    private fun observeRecommendations() {
        viewModelScope.launch {
            smartRecommendationDao.getActiveRecommendations().collect { recs ->
                // Extract duplicate groups or large files from recommendations
                val dupGroups = recs.filter { it.category == "DUPLICATE" }.map { rec ->
                    val fileIds = try {
                        com.google.gson.Gson().fromJson(rec.relatedFileIdsJson, Array<String>::class.java).toList()
                    } catch (e: Exception) {
                        emptyList()
                    }
                    DuplicateGroupInfo(
                        recommendationId = rec.id,
                        title = rec.title,
                        description = rec.description,
                        potentialSavingsBytes = rec.potentialSavingsBytes,
                        relatedFileIds = fileIds
                    )
                }

                _uiState.update {
                    it.copy(
                        activeRecommendations = recs,
                        duplicateGroups = dupGroups
                    )
                }
            }
        }
    }

    fun runAiOrganizationScan() {
        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true, errorMessage = null, successMessage = null) }
            try {
                val recs = aiSmartOrganizer.runSmartOrganizationAndCleanup()
                loadDashboardData()
                _uiState.update {
                    it.copy(
                        isScanning = false,
                        successMessage = "AI Smart Organization scan completed! Found ${recs.size} insights."
                    )
                }
                AgentLogger.logEvent("INFO", "AI_DASHBOARD", "SCAN_SUCCESS", "Scan completed with ${recs.size} recommendations")
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isScanning = false,
                        errorMessage = "AI scan failed: ${e.message}"
                    )
                }
                AgentLogger.logEvent("ERROR", "AI_DASHBOARD", "SCAN_FAIL", "Scan failed: ${e.message}")
            }
        }
    }

    fun dismissRecommendation(recommendationId: Long) {
        viewModelScope.launch {
            try {
                smartRecommendationDao.dismissRecommendation(recommendationId)
                _uiState.update { state ->
                    val updatedRecs = state.activeRecommendations.filter { it.id != recommendationId }
                    val updatedDups = state.duplicateGroups.filter { it.recommendationId != recommendationId }
                    state.copy(
                        activeRecommendations = updatedRecs,
                        duplicateGroups = updatedDups,
                        successMessage = "Recommendation dismissed."
                    )
                }
                AgentLogger.logEvent("INFO", "AI_DASHBOARD", "DISMISS_REC", "Dismissed recommendation $recommendationId")
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to dismiss recommendation: ${e.message}") }
            }
        }
    }

    fun cleanupDuplicateGroup(duplicateGroup: DuplicateGroupInfo) {
        viewModelScope.launch {
            try {
                // Delete duplicate files from database by fileId string
                for (fileId in duplicateGroup.relatedFileIds) {
                    virtualFileDao.deleteFile(fileId)
                }
                // Dismiss the recommendation
                smartRecommendationDao.dismissRecommendation(duplicateGroup.recommendationId)
                
                loadDashboardData()
                _uiState.update {
                    it.copy(
                        successMessage = "Successfully cleaned up duplicate files! Reclaimed ${formatBytes(duplicateGroup.potentialSavingsBytes)}."
                    )
                }
                AgentLogger.logEvent("INFO", "AI_DASHBOARD", "CLEANUP_DUP", "Cleaned up duplicate group ${duplicateGroup.recommendationId}")
            } catch (e: Exception) {
                _uiState.update { it.copy(errorMessage = "Failed to clean up duplicates: ${e.message}") }
            }
        }
    }

    fun selectCategoryTab(category: String) {
        _uiState.update { it.copy(selectedCategoryTab = category) }
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
}
