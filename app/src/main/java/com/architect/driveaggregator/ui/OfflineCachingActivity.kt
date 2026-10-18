package com.architect.driveaggregator.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.architect.driveaggregator.ui.screens.OfflineCachingScreen
import com.architect.driveaggregator.ui.viewmodel.OfflineCacheViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class OfflineCachingActivity : ComponentActivity() {

    private val viewModel: OfflineCacheViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            androidx.compose.material3.MaterialTheme {
                OfflineCachingScreen(
                    viewModel = viewModel,
                    onNavigateBack = { finish() }
                )
            }
        }
    }
}
