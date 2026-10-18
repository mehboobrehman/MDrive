package com.architect.driveaggregator.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.architect.driveaggregator.contracts.CloudProviderType
import com.architect.driveaggregator.search.FederatedSearchManager
import com.architect.driveaggregator.search.FederatedSearchResult
import com.architect.driveaggregator.search.SearchFilter
import com.architect.driveaggregator.telemetry.AgentLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AdvancedSearchUiState(
    val query: String = "",
    val selectedMimeType: String = "",
    val selectedProviderType: CloudProviderType? = null,
    val minSize: Long = 0L,
    val maxSize: Long = 0L,
    val startTime: Long = 0L,
    val endTime: Long = 0L,
    val searchResults: List<FederatedSearchResult> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val searchSource: String = "ALL", // ALL, LOCAL, REMOTE
    val snackbarMessage: String? = null
)

@HiltViewModel
class AdvancedSearchViewModel @Inject constructor(
    application: Application,
    private val federatedSearchManager: FederatedSearchManager
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(AdvancedSearchUiState())
    val uiState: StateFlow<AdvancedSearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    init {
        // Initial search load
        executeSearch()
    }

    fun updateQuery(query: String) {
        _uiState.update { it.copy(query = query) }
        updateSuggestions(query)
        executeSearch()
    }

    fun setMimeTypeFilter(mimeType: String) {
        _uiState.update { it.copy(selectedMimeType = mimeType) }
        executeSearch()
    }

    fun setProviderFilter(providerType: CloudProviderType?) {
        _uiState.update { it.copy(selectedProviderType = providerType) }
        executeSearch()
    }

    fun setSizeRange(min: Long, max: Long) {
        _uiState.update { it.copy(minSize = min, maxSize = max) }
        executeSearch()
    }

    fun setDateRange(start: Long, end: Long) {
        _uiState.update { it.copy(startTime = start, endTime = end) }
        executeSearch()
    }

    fun clearFilters() {
        _uiState.update {
            it.copy(
                query = "",
                selectedMimeType = "",
                selectedProviderType = null,
                minSize = 0L,
                maxSize = 0L,
                startTime = 0L,
                endTime = 0L,
                suggestions = emptyList()
            )
        }
        executeSearch()
    }

    private fun updateSuggestions(query: String) {
        if (query.isBlank()) {
            _uiState.update { it.copy(suggestions = emptyList()) }
            return
        }
        // Generate real-time search suggestions based on common file keywords & categories
        val sampleSuggestions = listOf(
            "$query documents",
            "$query report",
            "$query archive",
            "$query spreadsheet",
            "pdf files matching $query",
            "media items for $query"
        ).filter { it.contains(query, ignoreCase = true) }

        _uiState.update { it.copy(suggestions = sampleSuggestions) }
    }

    fun executeSearch() {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            val currentState = _uiState.value
            _uiState.update { it.copy(isLoading = true) }

            val filter = SearchFilter(
                query = currentState.query,
                mimeType = currentState.selectedMimeType,
                providerType = currentState.selectedProviderType,
                minSize = currentState.minSize,
                maxSize = currentState.maxSize,
                startTime = currentState.startTime,
                endTime = currentState.endTime
            )

            try {
                AgentLogger.logEvent(
                    level = "INFO",
                    category = "SEARCH",
                    event = "ADVANCED_SEARCH_EXECUTE",
                    message = "Executing federated search with query: '${currentState.query}', mime: '${currentState.selectedMimeType}', provider: ${currentState.selectedProviderType}",
                    payload = mapOf(
                        "query" to currentState.query,
                        "mimeType" to currentState.selectedMimeType,
                        "providerType" to (currentState.selectedProviderType?.name ?: "ALL")
                    )
                )

                val results = federatedSearchManager.searchFederated(filter)
                _uiState.update {
                    it.copy(
                        searchResults = results,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                AgentLogger.logEvent(
                    level = "ERROR",
                    category = "SEARCH",
                    event = "ADVANCED_SEARCH_ERROR",
                    message = "Federated search failed: ${e.localizedMessage}",
                    payload = mapOf("exception" to e.javaClass.name)
                )
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        snackbarMessage = "Search error: ${e.localizedMessage}"
                    )
                }
            }
        }
    }

    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }
}
