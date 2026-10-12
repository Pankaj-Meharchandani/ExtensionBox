package com.extensionbox.app.ui.screens

import android.content.Context
import android.graphics.drawable.Drawable
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import com.extensionbox.app.SystemAccess
import com.extensionbox.app.ui.components.SingleOptionSelectionDialog
import com.extensionbox.app.ui.viewmodel.AppTypeFilter
import com.extensionbox.app.ui.viewmodel.PrivacyViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(
    sys: SystemAccess,
    viewModel: PrivacyViewModel = viewModel()
) {
    val context = LocalContext.current
    val events by viewModel.events.collectAsState()
    val apps by viewModel.apps.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val toastMessage by viewModel.toastMessage.collectAsState()

    val activeTab by viewModel.activeTab.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val appFilter by viewModel.appFilter.collectAsState()
    val matrixCategory by viewModel.matrixCategory.collectAsState()
    val selectedAppPackage by viewModel.selectedAppPackage.collectAsState()
    val selectedAppPermissions by viewModel.selectedAppPermissions.collectAsState()

    // Handle toast messages
    LaunchedEffect(toastMessage) {
        toastMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    // Initial load
    LaunchedEffect(Unit) {
        viewModel.loadData(context, sys)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // --- Privacy Hero Summary Dashboard ---
        PrivacyHeroHeader(
            events = events,
            sys = sys,
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.loadData(context, sys) }
        )

        // --- Navigation Tab Bar ---
        TabRow(
            selectedTabIndex = activeTab,
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
            contentColor = MaterialTheme.colorScheme.primary,
            divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)) }
        ) {
            Tab(
                selected = activeTab == 0,
                onClick = { viewModel.setActiveTab(0) },
                text = { Text("Timeline", fontWeight = FontWeight.Bold) },
                icon = { Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(18.dp)) }
            )
            Tab(
                selected = activeTab == 1,
                onClick = { viewModel.setActiveTab(1) },
                text = { Text("Apps", fontWeight = FontWeight.Bold) },
                icon = { Icon(Icons.Default.Apps, contentDescription = null, modifier = Modifier.size(18.dp)) }
            )
            Tab(
                selected = activeTab == 2,
                onClick = { viewModel.setActiveTab(2) },
                text = { Text("Matrix", fontWeight = FontWeight.Bold) },
                icon = { Icon(Icons.Default.GridOn, contentDescription = null, modifier = Modifier.size(18.dp)) }
            )
        }

        // --- Content Views ---
        Box(modifier = Modifier.weight(1f)) {
            when (activeTab) {
                0 -> PrivacyTimelineView(
                    events = events,
                    searchQuery = searchQuery,
                    onSearchChange = { viewModel.setSearchQuery(it) }
                )
                1 -> AppInspectorView(
                    apps = apps,
                    searchQuery = searchQuery,
                    onSearchChange = { viewModel.setSearchQuery(it) },
                    appFilter = appFilter,
                    onFilterChange = { viewModel.setAppFilter(it) },
                    selectedAppPackage = selectedAppPackage,
                    selectedPermissions = selectedAppPermissions,
                    onAppSelect = { pkg -> viewModel.selectApp(sys, pkg) },
                    onPermissionUpdate = { op, mode ->
                        selectedAppPackage?.let { pkg -> viewModel.updatePermission(sys, pkg, op, mode) }
                    }
                )
                2 -> PermissionMatrixView(
                    apps = apps,
                    selectedCategory = matrixCategory,
                    onCategorySelect = { viewModel.setMatrixCategory(it) },
                    sys = sys,
                    onPermissionUpdate = { pkg, op, mode ->
                        viewModel.updatePermission(sys, pkg, op, mode)
                    }
                )
            }
        }
    }
}

// --- Privacy Summary Hero ---
@Composable
private fun PrivacyHeroHeader(
    events: List<PrivacyEvent>,
    sys: SystemAccess,
    isRefreshing: Boolean,
    onRefresh: () -> Unit
) {
    val cameraCount = events.count { it.opName == "CAMERA" }
    val micCount = events.count { it.opName == "RECORD_AUDIO" }
    val locationCount = events.count { it.opName in setOf("FINE_LOCATION", "COARSE_LOCATION") }
    val clipboardCount = events.count { it.opName == "READ_CLIPBOARD" }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp),
        tonalElevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = if (sys.isEnhanced()) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Box(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                                Text(
                                    text = sys.tier,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (sys.isEnhanced()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Privacy Shield",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (isRefreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    IconButton(
                        onClick = onRefresh,
                        modifier = Modifier
                            .size(36.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh Privacy Data",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 4 Stat Metric Cards
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricChip(
                    icon = Icons.Default.Videocam,
                    label = "Camera",
                    count = cameraCount,
                    color = Color(0xFFFF5252),
                    modifier = Modifier.weight(1f)
                )
                MetricChip(
                    icon = Icons.Default.Mic,
                    label = "Mic",
                    count = micCount,
                    color = Color(0xFFFF9100),
                    modifier = Modifier.weight(1f)
                )
                MetricChip(
                    icon = Icons.Default.LocationOn,
                    label = "Location",
                    count = locationCount,
                    color = Color(0xFF2979FF),
                    modifier = Modifier.weight(1f)
                )
                MetricChip(
                    icon = Icons.Default.ContentPaste,
                    label = "Clipboard",
                    count = clipboardCount,
                    color = Color(0xFFAB47BC),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun MetricChip(
    icon: ImageVector,
    label: String,
    count: Int,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.25f))
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = 10.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.height(4.dp))
            Text(
                text = "$count",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = color
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// --- 1. Privacy Timeline View ---
@Composable
private fun PrivacyTimelineView(
    events: List<PrivacyEvent>,
    searchQuery: String,
    onSearchChange: (String) -> Unit
) {
    val filteredEvents = remember(events, searchQuery) {
        if (searchQuery.isBlank()) events
        else events.filter {
            it.appLabel.contains(searchQuery, ignoreCase = true) ||
            it.packageName.contains(searchQuery, ignoreCase = true) ||
            it.opName.contains(searchQuery, ignoreCase = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search logs...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = if (searchQuery.isNotEmpty()) {
                {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                    }
                }
            } else null,
            shape = RoundedCornerShape(16.dp),
            singleLine = true
        )

        Spacer(Modifier.height(12.dp))

        if (filteredEvents.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Outlined.Shield,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = if (events.isEmpty()) "No recent privacy access logs found" else "No matching logs",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            val sdf = remember { SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault()) }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                items(filteredEvents, key = { "${it.packageName}_${it.opName}_${it.lastAccessTime}" }) { event ->
                    TimelineCard(event = event, sdf = sdf)
                }
            }
        }
    }
}

@Composable
private fun TimelineCard(
    event: PrivacyEvent,
    sdf: SimpleDateFormat
) {
    val (icon, color) = when (event.opName) {
        "CAMERA" -> Pair(Icons.Default.Videocam, Color(0xFFFF5252))
        "RECORD_AUDIO" -> Pair(Icons.Default.Mic, Color(0xFFFF9100))
        "FINE_LOCATION", "COARSE_LOCATION" -> Pair(Icons.Default.LocationOn, Color(0xFF2979FF))
        "READ_CLIPBOARD" -> Pair(Icons.Default.ContentPaste, Color(0xFFAB47BC))
        else -> Pair(Icons.Default.Shield, MaterialTheme.colorScheme.primary)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIconImage(packageName = event.packageName, modifier = Modifier.size(42.dp))

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.appLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
                    Text(
                        text = event.opName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = color
                    )
                }
                Text(
                    text = sdf.format(Date(event.lastAccessTime)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// --- 2. App Inspector View ---
@Composable
private fun AppInspectorView(
    apps: List<AppInfo>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    appFilter: AppTypeFilter,
    onFilterChange: (AppTypeFilter) -> Unit,
    selectedAppPackage: String?,
    selectedPermissions: List<PermissionInfo>,
    onAppSelect: (String?) -> Unit,
    onPermissionUpdate: (String, String) -> Unit
) {
    val filteredApps = remember(apps, searchQuery, appFilter) {
        apps.filter { app ->
            val matchesQuery = searchQuery.isBlank() ||
                app.label.contains(searchQuery, ignoreCase = true) ||
                app.packageName.contains(searchQuery, ignoreCase = true)

            val matchesFilter = when (appFilter) {
                AppTypeFilter.ALL -> true
                AppTypeFilter.USER -> !app.isSystem
                AppTypeFilter.SYSTEM -> app.isSystem
                AppTypeFilter.HIGH_RISK -> app.category != AppCategory.SAFE
            }
            matchesQuery && matchesFilter
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search installed apps...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = if (searchQuery.isNotEmpty()) {
                {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                    }
                }
            } else null,
            shape = RoundedCornerShape(16.dp),
            singleLine = true
        )

        Spacer(Modifier.height(8.dp))

        // Filter Chips
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(AppTypeFilter.entries.toTypedArray()) { filter ->
                val label = when (filter) {
                    AppTypeFilter.ALL -> "All (${apps.size})"
                    AppTypeFilter.USER -> "User Apps"
                    AppTypeFilter.SYSTEM -> "System Apps"
                    AppTypeFilter.HIGH_RISK -> "High Risk"
                }
                FilterChip(
                    selected = appFilter == filter,
                    onClick = { onFilterChange(filter) },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            items(filteredApps, key = { it.packageName }) { app ->
                val isExpanded = selectedAppPackage == app.packageName
                AppInspectorCard(
                    app = app,
                    isExpanded = isExpanded,
                    onClick = { onAppSelect(if (isExpanded) null else app.packageName) },
                    permissions = if (isExpanded) selectedPermissions else emptyList(),
                    onUpdate = onPermissionUpdate
                )
            }
        }
    }
}

@Composable
private fun AppInspectorCard(
    app: AppInfo,
    isExpanded: Boolean,
    onClick: () -> Unit,
    permissions: List<PermissionInfo>,
    onUpdate: (String, String) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
        border = BorderStroke(
            1.dp,
            if (isExpanded) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIconImage(packageName = app.packageName, modifier = Modifier.size(40.dp))

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (app.isSystem) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        Text(
                            text = "SYSTEM",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }

                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(Modifier.height(12.dp))

                    if (permissions.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    } else {
                        val relevantOps = setOf(
                            "CAMERA", "RECORD_AUDIO", "FINE_LOCATION", "COARSE_LOCATION",
                            "READ_CLIPBOARD", "SYSTEM_ALERT_WINDOW", "WRITE_SETTINGS"
                        )
                        val displayPerms = permissions.filter { it.opName in relevantOps }

                        if (displayPerms.isEmpty()) {
                            Text(
                                text = "No sensitive AppOps overrides found for this app",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            displayPerms.forEach { perm ->
                                PermissionControlRow(perm = perm, onUpdate = onUpdate)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionControlRow(
    perm: PermissionInfo,
    onUpdate: (String, String) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }

    val (modeColor, modeText) = when (perm.mode) {
        0 -> Pair(Color(0xFF00E676), "ALLOW")
        1 -> Pair(Color(0xFFFF9100), "IGNORE (FAKE)")
        2 -> Pair(Color(0xFFFF5252), "DENY")
        else -> Pair(MaterialTheme.colorScheme.onSurfaceVariant, "DEFAULT")
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val icon = when (perm.opName) {
                "CAMERA" -> Icons.Default.Videocam
                "RECORD_AUDIO" -> Icons.Default.Mic
                "FINE_LOCATION", "COARSE_LOCATION" -> Icons.Default.LocationOn
                "READ_CLIPBOARD" -> Icons.Default.ContentPaste
                "SYSTEM_ALERT_WINDOW" -> Icons.Default.Layers
                else -> Icons.Default.Tune
            }
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            Text(
                text = perm.opName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = modeColor.copy(alpha = 0.15f),
            border = BorderStroke(1.dp, modeColor.copy(alpha = 0.3f)),
            modifier = Modifier.clickable { showDialog = true }
        ) {
            Text(
                text = modeText,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = modeColor,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
    }

    if (showDialog) {
        SingleOptionSelectionDialog(
            title = "Permission Mode: ${perm.opName}",
            options = listOf("allow", "ignore", "deny", "default"),
            selectedOption = perm.modeName.lowercase(),
            optionLabel = { opt ->
                when (opt) {
                    "allow" -> "Allow"
                    "ignore" -> "Ignore (Fake Success)"
                    "deny" -> "Deny"
                    else -> "System Default"
                }
            },
            onOptionSelected = { mode -> onUpdate(perm.opName, mode) },
            onDismissRequest = { showDialog = false }
        )
    }
}

// --- 3. Permission Matrix View ---
@Composable
private fun PermissionMatrixView(
    apps: List<AppInfo>,
    selectedCategory: String,
    onCategorySelect: (String) -> Unit,
    sys: SystemAccess,
    onPermissionUpdate: (String, String, String) -> Unit
) {
    val categories = listOf(
        "CAMERA" to "Camera",
        "RECORD_AUDIO" to "Microphone",
        "FINE_LOCATION" to "Location",
        "READ_CLIPBOARD" to "Clipboard",
        "SYSTEM_ALERT_WINDOW" to "System Overlay"
    )

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(8.dp))

        // Category Selector Chips
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(categories) { (catKey, catLabel) ->
                FilterChip(
                    selected = selectedCategory == catKey,
                    onClick = { onCategorySelect(catKey) },
                    label = { Text(catLabel, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) }
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // List of user apps for direct AppOps toggle
        val userApps = remember(apps) { apps.filter { !it.isSystem }.take(40) }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            items(userApps, key = { it.packageName }) { app ->
                MatrixAppCard(
                    app = app,
                    opName = selectedCategory,
                    sys = sys,
                    onUpdate = { mode -> onPermissionUpdate(app.packageName, selectedCategory, mode) }
                )
            }
        }
    }
}

@Composable
private fun MatrixAppCard(
    app: AppInfo,
    opName: String,
    sys: SystemAccess,
    onUpdate: (String) -> Unit
) {
    var currentMode by remember(app.packageName, opName) { mutableStateOf<String?>(null) }

    LaunchedEffect(app.packageName, opName) {
        withContext(Dispatchers.IO) {
            val perms = sys.getAppPermissions(app.packageName)
            val match = perms.find { it.opName == opName }
            currentMode = match?.modeName ?: "default"
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                AppIconImage(packageName = app.packageName, modifier = Modifier.size(36.dp))

                Spacer(Modifier.width(12.dp))

                Column {
                    Text(
                        text = app.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = app.packageName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            val mode = currentMode ?: "..."
            var showDialog by remember { mutableStateOf(false) }

            Button(
                onClick = { showDialog = true },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(mode.uppercase(), style = MaterialTheme.typography.labelSmall)
            }

            if (showDialog) {
                SingleOptionSelectionDialog(
                    title = "Set $opName for ${app.label}",
                    options = listOf("allow", "ignore", "deny", "default"),
                    selectedOption = mode.lowercase(),
                    optionLabel = { opt ->
                        when (opt) {
                            "allow" -> "Allow"
                            "ignore" -> "Ignore (Fake Success)"
                            "deny" -> "Deny"
                            else -> "Default"
                        }
                    },
                    onOptionSelected = { m ->
                        currentMode = m
                        onUpdate(m)
                    },
                    onDismissRequest = { showDialog = false }
                )
            }
        }
    }
}

// --- App Icon Loader Component ---
@Composable
private fun AppIconImage(
    packageName: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var drawable by remember(packageName) { mutableStateOf<Drawable?>(null) }

    LaunchedEffect(packageName) {
        withContext(Dispatchers.IO) {
            try {
                drawable = context.packageManager.getApplicationIcon(packageName)
            } catch (_: Exception) {}
        }
    }

    val d = drawable
    if (d != null) {
        val bitmap = remember(d) { d.toBitmap(width = 96, height = 96).asImageBitmap() }
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = modifier.clip(RoundedCornerShape(10.dp))
        )
    } else {
        Surface(
            modifier = modifier,
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Android,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
