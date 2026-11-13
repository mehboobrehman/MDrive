package com.architect.driveaggregator.update

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.architect.driveaggregator.BuildConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class AppUpdateUiState(
    val runningVersion: String = BuildConfig.APP_VERSION,
    val fullVersion: String = BuildConfig.FULL_VERSION_STRING,
    val updateState: UpdateState = UpdateState.Idle,
    val availableRelease: GitHubRelease? = null,
    val availableApkAsset: ReleaseAsset? = null,
    val availableChecksumAsset: ReleaseAsset? = null,
    val showUpdateDialog: Boolean = false,
    val showProgressDialog: Boolean = false,
    val showVerifiedDialog: Boolean = false,
    val showBanner: Boolean = false,
    val errorMessage: String? = null
)

@HiltViewModel
class UpdateViewModel @Inject constructor(
    application: Application,
    private val updateRepository: UpdateRepository
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(AppUpdateUiState())
    val uiState: StateFlow<AppUpdateUiState> = _uiState.asStateFlow()

    init {
        checkForUpdates(silent = true)
    }

    fun checkForUpdates(silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) {
                _uiState.update { it.copy(updateState = UpdateState.Checking) }
            }

            val result = updateRepository.fetchLatestRelease()
            result.fold(
                onSuccess = { release ->
                    val validatedAssets = updateRepository.validateAssets(release)
                    if (validatedAssets != null) {
                        val (apkAsset, checksumAsset) = validatedAssets
                        val isNewer = SemVerComparator.isGreater(release.tagName, BuildConfig.APP_VERSION)
                        if (isNewer) {
                            _uiState.update {
                                it.copy(
                                    updateState = UpdateState.Available(release, apkAsset, checksumAsset),
                                    availableRelease = release,
                                    availableApkAsset = apkAsset,
                                    availableChecksumAsset = checksumAsset,
                                    showBanner = true,
                                    showUpdateDialog = !silent,
                                    errorMessage = null
                                )
                            }
                        } else {
                            _uiState.update {
                                it.copy(
                                    updateState = UpdateState.Idle,
                                    showBanner = false,
                                    errorMessage = null
                                )
                            }
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                updateState = UpdateState.Error(UpdateError.NO_PUBLISHED_RELEASE, "Release assets missing signed APK or SHA256SUMS"),
                                errorMessage = "Release assets missing signed APK or SHA256SUMS"
                            )
                        }
                    }
                },
                onFailure = { e ->
                    val updateError = (e as? UpdateRepoException)?.error ?: UpdateError.UNKNOWN
                    _uiState.update {
                        it.copy(
                            updateState = UpdateState.Error(updateError, e.localizedMessage ?: "Failed to check updates"),
                            errorMessage = e.localizedMessage
                        )
                    }
                }
            )
        }
    }

    fun startDownload() {
        val release = _uiState.value.availableRelease
        val apkAsset = _uiState.value.availableApkAsset
        val checksumAsset = _uiState.value.availableChecksumAsset

        if (release == null || apkAsset == null || checksumAsset == null) return

        viewModelScope.launch {
            _uiState.update { it.copy(showUpdateDialog = false, showProgressDialog = true) }
            updateRepository.downloadAndVerify(apkAsset, checksumAsset, release).collect { downloadState ->
                _uiState.update { it.copy(updateState = downloadState) }
                when (downloadState) {
                    is UpdateState.Verified -> {
                        _uiState.update { it.copy(showProgressDialog = false, showVerifiedDialog = true) }
                    }
                    is UpdateState.Error -> {
                        _uiState.update { it.copy(showProgressDialog = false) }
                    }
                    else -> {}
                }
            }
        }
    }

    fun cancelDownload() {
        updateRepository.cancelDownload()
        _uiState.update {
            it.copy(
                updateState = UpdateState.Idle,
                showProgressDialog = false
            )
        }
    }

    fun retryDownload() {
        startDownload()
    }

    fun installUpdate(context: Context, apkFile: File) {
        try {
            val apkUri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
            } else {
                _uiState.update {
                    it.copy(
                        updateState = UpdateState.Error(UpdateError.INSTALLER_UNAVAILABLE, "No package installer available"),
                        errorMessage = "No package installer available"
                    )
                }
            }
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    updateState = UpdateState.Error(UpdateError.INSTALLER_UNAVAILABLE, "Failed to launch installer: ${e.localizedMessage}"),
                    errorMessage = e.localizedMessage
                )
            }
        }
    }

    fun setShowUpdateDialog(show: Boolean) {
        _uiState.update { it.copy(showUpdateDialog = show) }
    }

    fun setShowProgressDialog(show: Boolean) {
        _uiState.update { it.copy(showProgressDialog = show) }
    }

    fun setShowVerifiedDialog(show: Boolean) {
        _uiState.update { it.copy(showVerifiedDialog = show) }
    }

    fun dismissBanner() {
        _uiState.update { it.copy(showBanner = false) }
    }
}
