package com.architect.driveaggregator.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

data class DrawerItem(
    val title: String,
    val icon: ImageVector,
    val badge: String? = null,
    val onClick: () -> Unit
)

@Composable
fun MDriveNavigationDrawer(
    drawerState: DrawerState,
    currentRoute: String,
    onItemSelected: (String) -> Unit,
    accountsCount: Int = 0,
    content: @Composable () -> Unit
) {
    val items = listOf(
        Triple("my_drive", "My Drive", Icons.Default.Folder),
        Triple("starred", "Starred", Icons.Default.Star),
        Triple("offline", "Offline Files", Icons.Default.Download),
        Triple("sharing", "Shared Links", Icons.Default.Share),
        Triple("trash", "Trash", Icons.Default.Delete),
        Triple("settings", "Settings", Icons.Default.Settings)
    )

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.width(320.dp)
            ) {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "MDrive Storage",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "$accountsCount Linked Cloud Accounts",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 2.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Divider(
                    modifier = Modifier.padding(vertical = 16.dp, horizontal = 28.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                items.forEach { (route, title, icon) ->
                    NavigationDrawerItem(
                        icon = { Icon(icon, contentDescription = title) },
                        label = { Text(title) },
                        selected = currentRoute == route,
                        onClick = { onItemSelected(route) },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                Divider(
                    modifier = Modifier.padding(vertical = 16.dp, horizontal = 28.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )

                NavigationDrawerItem(
                    icon = { Icon(Icons.Default.Cloud, contentDescription = "Manage Accounts") },
                    label = { Text("Linked Accounts") },
                    selected = currentRoute == "accounts",
                    onClick = { onItemSelected("accounts") },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        },
        content = content
    )
}
