package com.architect.driveaggregator.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.architect.driveaggregator.ui.screens.FileExplorerScreen
import com.architect.driveaggregator.ui.viewmodel.FileExplorerViewModel
import com.architect.driveaggregator.telemetry.AgentLogger
import com.architect.driveaggregator.util.ActivityTouchInterceptor
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class FileExplorerActivity : ComponentActivity() {

    private val touchInterceptor = ActivityTouchInterceptor("FileExplorerActivity")
    private lateinit var viewModelRef: FileExplorerViewModel

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { selectedUri ->
            // Grant persistable read permission if needed, and query file details
            try {
                contentResolver.takePersistableUriPermission(selectedUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}

            var fileName = "Uploaded_File"
            var fileSize = 0L
            var mimeType = contentResolver.getType(selectedUri) ?: "application/octet-stream"

            contentResolver.query(selectedUri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) {
                        val n = cursor.getString(nameIndex)
                        if (!n.isNullOrBlank()) fileName = n
                    }
                    if (sizeIndex != -1) {
                        fileSize = cursor.getLong(sizeIndex)
                    }
                }
            }

            AgentLogger.logEvent("INFO", "ACTION", "FILE_PICKED", "Selected file from device storage: $fileName", mapOf("uri" to selectedUri.toString(), "size" to fileSize, "mimeType" to mimeType))
            if (::viewModelRef.isInitialized) {
                viewModelRef.initiateUpload(fileName, fileSize, mimeType, selectedUri.toString())
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AgentLogger.logEvent("INFO", "NAVIGATION", "PAGE_MOUNT", "FileExplorerActivity created", mapOf("activity" to "FileExplorerActivity"))
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val viewModel: FileExplorerViewModel = viewModel()
                    viewModelRef = viewModel
                    FileExplorerScreen(
                        viewModel = viewModel,
                        onNavigateBack = { finish() },
                        onLaunchFilePicker = {
                            AgentLogger.logEvent("INFO", "ACTION", "BUTTON_CLICK", "Launching system file picker (ACTION_OPEN_DOCUMENT)")
                            filePickerLauncher.launch(arrayOf("*/*"))
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AgentLogger.logEvent("INFO", "NAVIGATION", "PAGE_RESUME", "FileExplorerActivity resumed", mapOf("activity" to "FileExplorerActivity"))
    }

    override fun onDestroy() {
        AgentLogger.logEvent("INFO", "NAVIGATION", "PAGE_UNMOUNT", "FileExplorerActivity destroyed", mapOf("activity" to "FileExplorerActivity"))
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        touchInterceptor.handleTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }
}