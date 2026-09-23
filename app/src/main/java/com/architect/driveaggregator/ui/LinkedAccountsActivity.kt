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
import com.architect.driveaggregator.ui.screens.LinkedAccountsScreen
import com.architect.driveaggregator.ui.viewmodel.LinkedAccountsViewModel
import com.architect.driveaggregator.telemetry.AgentLogger
import com.architect.driveaggregator.util.ActivityTouchInterceptor
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class LinkedAccountsActivity : ComponentActivity() {

    private val touchInterceptor = ActivityTouchInterceptor("LinkedAccountsActivity")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AgentLogger.logEvent("INFO", "NAVIGATION", "PAGE_MOUNT", "LinkedAccountsActivity created", mapOf("activity" to "LinkedAccountsActivity"))
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val viewModel: LinkedAccountsViewModel = viewModel()
                    LinkedAccountsScreen(
                        viewModel = viewModel,
                        onLaunchAuthUrl = { url ->
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                            startActivity(intent)
                        }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AgentLogger.logEvent("INFO", "NAVIGATION", "PAGE_RESUME", "LinkedAccountsActivity resumed", mapOf("activity" to "LinkedAccountsActivity"))
    }

    override fun onDestroy() {
        AgentLogger.logEvent("INFO", "NAVIGATION", "PAGE_UNMOUNT", "LinkedAccountsActivity destroyed", mapOf("activity" to "LinkedAccountsActivity"))
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        touchInterceptor.handleTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }
}