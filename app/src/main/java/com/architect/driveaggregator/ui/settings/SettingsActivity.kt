package com.architect.driveaggregator.ui.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.architect.driveaggregator.telemetry.AgentLogger
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AgentLogger.logEvent("INFO", "NAVIGATION", "PAGE_MOUNT", "SettingsActivity created")
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SettingsScreen(onNavigateBack = { finish() })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AgentLogger.logEvent("INFO", "NAVIGATION", "PAGE_RESUME", "SettingsActivity resumed")
    }

    override fun onDestroy() {
        AgentLogger.logEvent("INFO", "NAVIGATION", "PAGE_UNMOUNT", "SettingsActivity destroyed")
        super.onDestroy()
    }
}
