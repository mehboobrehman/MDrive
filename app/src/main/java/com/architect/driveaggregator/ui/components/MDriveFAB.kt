package com.architect.driveaggregator.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

@Composable
fun MDriveFloatingActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    text: String? = null
) {
    if (text != null) {
        ExtendedFloatingActionButton(
            onClick = onClick,
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text(text) },
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = modifier.semantics { contentDescription = text }
        )
    } else {
        FloatingActionButton(
            onClick = onClick,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = modifier.semantics { contentDescription = "Add file or folder" }
        ) {
            Icon(
                imageVector = Icons.Default.CloudUpload,
                contentDescription = null
            )
        }
    }
}
