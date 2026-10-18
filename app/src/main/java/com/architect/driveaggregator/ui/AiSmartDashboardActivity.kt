package com.architect.driveaggregator.ui

import android.os.Bundle
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.architect.driveaggregator.telemetry.AgentLogger
import com.architect.driveaggregator.ui.screens.AiSmartDashboardScreen
import com.architect.driveaggregator.ui.viewmodel.AiSmartDashboardViewModel
import com.architect.driveaggregator.util.ActivityTouchInterceptor
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class AiSmartDashboardActivity : ComponentActivity() {

    private val viewModel: AiSmartDashboardViewModel by viewModels()
    private val touchInterceptor = ActivityTouchInterceptor("AiSmartDashboardActivity")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AgentLogger.logEvent("INFO", "NAVIGATION", "PAGE_MOUNT", "AiSmartDashboardActivity created")

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AiSmartDashboardScreen(
                        viewModel = viewModel,
                        onBack = { finish() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AgentLogger.logEvent("INFO", "NAVIGATION", "PAGE_RESUME", "AiSmartDashboardActivity resumed")
    }

    override fun onDestroy() {
        AgentLogger.logEvent("INFO", "NAVIGATION", "PAGE_UNMOUNT", "AiSmartDashboardActivity destroyed")
        super.onDestroy()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        touchInterceptor.handleTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }
}
