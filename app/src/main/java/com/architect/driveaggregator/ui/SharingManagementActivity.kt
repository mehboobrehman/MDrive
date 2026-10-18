package com.architect.driveaggregator.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.architect.driveaggregator.ui.screens.SharingManagementScreen
import com.architect.driveaggregator.ui.viewmodel.SharingViewModel
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class SharingManagementActivity : ComponentActivity() {

    private val viewModel: SharingViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SharingManagementScreen(
                        viewModel = viewModel,
                        onNavigateBack = { finish() }
                    )
                }
            }
        }
    }
}
