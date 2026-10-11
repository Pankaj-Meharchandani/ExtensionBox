package com.extensionbox.app.ui.screens

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import android.widget.Toast
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewmodel.compose.viewModel
import com.extensionbox.app.ui.viewmodel.DebloatViewModel
import com.extensionbox.app.ui.viewmodel.UninstallResult
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebloatScreen(
    viewModel: DebloatViewModel = viewModel()
) {
    val context = LocalContext.current
    val packageManager = context.packageManager
    val scope = rememberCoroutineScope()

    val apps by viewModel.apps.collectAsState()
    val isRooted by viewModel.isRooted.collectAsState()
    val hasShizuku by viewModel.hasShizuku.collectAsState()
    val showSystemApps by viewModel.showSystemApps.collectAsState()
    var query by remember { mutableStateOf("") }
    var pendingUninstall by remember { mutableStateOf<AppInfo?>(null) }

    val filteredApps = remember(apps, showSystemApps, query) {
        val base = if (showSystemApps) {
            apps
        } else {
            apps.filter { !it.isSystem || packageManager.getLaunchIntentForPackage(it.packageName) != null }
        }
        if (query.isBlank()) base else base.filter { it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
    }

    

    LaunchedEffect(Unit) {
        viewModel.loadApps(packageManager)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ) {
                Text(
                    text = when {
                        hasShizuku -> "Mode: Shizuku"
                        isRooted -> "Mode: Root"
                        else -> "Mode: Limited functionality (user apps only)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    placeholder = { Text("Search name or package (e.g. chrome, com.google)") },
                    singleLine = true
                )
            }

            

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Show system apps",
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = showSystemApps,
                        onCheckedChange = { viewModel.setShowSystemApps(it) }
                    )
                }
            }

            items(filteredApps, key = { it.packageName }) { app ->
                AppItem(
                    app = app,
                    onUninstall = {
                        if (app.isSystem && !(hasShizuku || isRooted)) {
                            Toast.makeText(context, "Cannot uninstall system app without root/Shizuku", Toast.LENGTH_SHORT).show()
                        } else {
                            pendingUninstall = app
                        }
                    },

                    canUninstallSystem = hasShizuku || isRooted
                )
            }
        }

    pendingUninstall?.let { app ->
        AlertDialog(
            onDismissRequest = { pendingUninstall = null },
            title = { Text("Uninstall ${app.label}?") },
            text = {
                Text(
                    if (app.isSystem) {
                        "This is a system package (${app.packageName}). Removing it may break device features. Continue only if you know what it does."
                    } else {
                        "This will start uninstall for ${app.packageName}."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingUninstall = null
                        Toast.makeText(context, "Uninstalling ${app.label}", Toast.LENGTH_SHORT).show()
                        scope.launch {
                            when (viewModel.uninstallApp(context, app)) {
                                UninstallResult.REMOVED -> {
                                    viewModel.removeApp(app.packageName)
                                    context.showUninstallNotification(app.label)
                                }
                                UninstallResult.STARTED -> {
                                    Toast.makeText(context, "Uninstall prompt opened for ${app.label}", Toast.LENGTH_SHORT).show()
                                }
                                UninstallResult.FAILED -> {
                                    Toast.makeText(context, "Could not uninstall ${app.label}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                ) {
                    Text("Uninstall", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUninstall = null }) {
                    Text("Cancel")
                }
            }
        )
    }
    }


@Composable
fun AppItem(
    app: AppInfo,
    onUninstall: () -> Unit,
    canUninstallSystem: Boolean
) {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (app.category) {
                AppCategory.SAFE -> Color.Green.copy(alpha = 0.1f)
                AppCategory.CAUTION -> Color.Yellow.copy(alpha = 0.1f)
                AppCategory.EXTREME -> Color.Red.copy(alpha = 0.1f)
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (app.description.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = app.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = app.category.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = when (app.category) {
                        AppCategory.SAFE -> Color.Green
                        AppCategory.CAUTION -> Color(0xFFFF9800)
                        AppCategory.EXTREME -> Color.Red
                    }
                )
            }

            
            Column(modifier = Modifier.wrapContentWidth(), horizontalAlignment = Alignment.End) {
                // uninstall button always shown; if app is system without permissions it's disabled
                IconButton(onClick = onUninstall) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Uninstall",
                        tint = if (app.isSystem && !canUninstallSystem) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.error
                    )
                }
                if (app.isSystem && !canUninstallSystem) {
                    Text(
                        text = "requires root/Shizuku",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
