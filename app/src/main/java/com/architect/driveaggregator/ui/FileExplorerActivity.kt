package com.architect.driveaggregator.ui

import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
                    FileExplorerScreen(
                        viewModel = viewModel,
                        onNavigateBack = { finish() }
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