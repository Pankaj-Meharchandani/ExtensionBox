package com.extensionbox.app.ui.screens

import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.extensionbox.app.MonitorService
import com.extensionbox.app.Prefs
import com.extensionbox.app.R
import com.extensionbox.app.db.ModuleDataEntity
import com.extensionbox.app.ui.ModuleRegistry
import com.extensionbox.app.ui.components.Sparkline
import com.extensionbox.app.ui.components.extractPoints
import com.extensionbox.app.ui.viewmodel.DashboardViewModel
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.util.Locale

data class DockWidgetInfo(
    val key: String,
    val label: String,
    val value: String,
    val progress: Float,
    val targetModuleKey: String
)

private val ALL_DOCK_WIDGET_OPTIONS = listOf(
    "battery" to "Battery Level",
    "cpu" to "CPU Load",
    "ram" to "RAM Usage",
    "thermal" to "Battery Temp",
    "storage" to "Storage Usage",
    "data" to "Data Usage Today",
    "steps" to "Step Counter",
    "sleep" to "Deep Sleep %",
    "unlock" to "Unlock Counter",
    "network" to "Download Speed",
    "uptime" to "Device Uptime"
)

fun getModuleColor(key: String): Color {
    return when (key) {
        "battery" -> Color(0xFF00E676)   // Lime/Green
        "cpu" -> Color(0xFF00B0FF)       // Electric Cyan
        "ram" -> Color(0xFFAB47BC)       // Violet/Purple
        "thermal" -> Color(0xFFFF6D00)   // Deep Orange
        "storage" -> Color(0xFFFFD600)   // Gold/Yellow
        "data" -> Color(0xFF1DE9B6)      // Teal/Turquoise
        "steps" -> Color(0xFF2979FF)     // Electric Blue
        "sleep" -> Color(0xFF7E57C2)     // Deep Purple/Indigo
        "unlock" -> Color(0xFFFF4081)    // Bright Pink
        "network" -> Color(0xFF00E5FF)   // Light Cyan
        "uptime" -> Color(0xFF3F51B5)    // Indigo
        "speedtest" -> Color(0xFFFF9100) // Orange
        "privacy" -> Color(0xFF3D5AFF)   // Shield Blue
        else -> Color(0xFF00B0FF)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DashboardScreen(viewModel: DashboardViewModel = viewModel(), onModuleClick: (String) -> Unit = {}) {
    val context = LocalContext.current
    val isRunning by viewModel.isRunning.collectAsState()
    val activeCount by viewModel.activeCount.collectAsState()
    val dashData by viewModel.dashData.collectAsState()
    val historyData by viewModel.historyData.collectAsState()
    val visibleModules by viewModel.visibleModules.collectAsState()
    val sysAccess by viewModel.sysAccess.collectAsState()

    val lazyListState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        viewModel.updateOrder(from.index, to.index)
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 120.dp, top = 16.dp, start = 16.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "status_header") {
                SystemPulseHero(
                    isRunning = isRunning,
                    activeCount = activeCount,
                    dashData = dashData,
                    context = context,
                    onModuleClick = onModuleClick
                )
            }

            if (!isRunning) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(id = R.string.engine_offline),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                items(visibleModules, key = { it.key() }) { module ->
                    val key = module.key()
                    val data = dashData[key]
                    if (data != null) {
                        ReorderableItem(reorderableState, key = key) { isDragging ->
                            val elevation by animateDpAsState(if (isDragging) 12.dp else 0.dp, label = stringResource(id = R.string.elevation))
                            
                            KernelCard(
                                key = key,
                                data = data,
                                history = historyData[key] ?: emptyList(),
                                sysAccess = sysAccess,
                                onClick = { onModuleClick(key) },
                                reorderableItemScope = this,
                                modifier = Modifier
                                    .graphicsLayer {
                                        this.shadowElevation = elevation.toPx()
                                    }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SystemPulseHero(
    isRunning: Boolean,
    activeCount: Int,
    dashData: Map<String, Map<String, String>>,
    context: android.content.Context,
    onModuleClick: (String) -> Unit
) {
    var dockKeysStr by remember {
        mutableStateOf(Prefs.getString(context, "top_dock_widgets", "battery,cpu,thermal") ?: "battery,cpu,thermal")
    }
    val dockKeys = remember(dockKeysStr) {
        val parsed = dockKeysStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (parsed.size >= 3) parsed.take(3) else (parsed + listOf("battery", "cpu", "thermal")).distinct().take(3)
    }
    var showCustomizeDialog by remember { mutableStateOf(false) }

    val rippleScope = rememberCoroutineScope()
    val rippleScale = remember { Animatable(0f) }
    val rippleAlpha = remember { Animatable(0f) }
    val primaryColor = MaterialTheme.colorScheme.primary

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = Color(0xFF13171F),
        tonalElevation = 4.dp
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(20.dp)) {
                // Header with Engine Toggle & Edit Pencil Button
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text(
                                text = if (isRunning) stringResource(id = R.string.system_pulse) else stringResource(id = R.string.system_idle),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isRunning) Color(0xFF00A3FF) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (isRunning) stringResource(id = R.string.all_systems_operational) else stringResource(id = R.string.engine_paused),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Start/Stop engine button
                        Box(contentAlignment = Alignment.Center) {
                            Canvas(modifier = Modifier.size(64.dp)) {
                                val radius = size.minDimension / 2 * rippleScale.value
                                drawCircle(
                                    color = primaryColor.copy(alpha = rippleAlpha.value),
                                    radius = radius,
                                    style = Stroke(width = 3.dp.toPx())
                                )
                            }
                            IconButton(
                                onClick = {
                                    if (isRunning) {
                                        val intent = Intent(context, MonitorService::class.java).setAction(MonitorService.ACTION_STOP)
                                        context.startService(intent)
                                    } else {
                                        val intent = Intent(context, MonitorService::class.java)
                                        ContextCompat.startForegroundService(context, intent)
                                    }
                                    rippleScope.launch {
                                        rippleScale.snapTo(0f)
                                        rippleAlpha.snapTo(0.8f)
                                        launch { rippleScale.animateTo(1.8f, tween(600, easing = FastOutSlowInEasing)) }
                                        rippleAlpha.animateTo(0f, tween(600, easing = FastOutSlowInEasing))
                                    }
                                },
                                modifier = Modifier.size(38.dp).background(
                                    if (isRunning) Color(0xFF00A3FF).copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
                                    CircleShape
                                )
                            ) {
                                Icon(
                                    imageVector = if (isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = if (isRunning) Color(0xFF00A3FF) else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        Spacer(Modifier.width(8.dp))

                        // Edit Pencil Icon Button
                        IconButton(
                            onClick = { showCustomizeDialog = true },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = stringResource(id = R.string.customize_top_dock),
                                tint = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 3 Circle Dock Layout (1st in order is CENTER BIG CIRCLE, 2nd is LEFT SMALL CIRCLE, 3rd is RIGHT SMALL CIRCLE)
                val dockWidgetInfos = remember(dockKeys, dashData) {
                    dockKeys.map { extractDockWidgetData(it, dashData, context) }
                }

                ThreeCircleDock(
                    dockWidgets = dockWidgetInfos,
                    onModuleClick = onModuleClick,
                    onEditClick = { showCustomizeDialog = true }
                )
            }

            // Bottom subtle reflection gradient line
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                Color.Transparent,
                                Color(0xFF00A3FF).copy(alpha = 0.4f),
                                Color.Transparent
                            )
                        )
                    )
            )
        }
    }

    if (showCustomizeDialog) {
        TopDockCustomizerDialog(
            context = context,
            currentKeys = dockKeys,
            onDismiss = { showCustomizeDialog = false },
            onSave = { updatedKeys ->
                showCustomizeDialog = false
                val joined = updatedKeys.take(3).joinToString(",")
                Prefs.setString(context, "top_dock_widgets", joined)
                dockKeysStr = joined
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ThreeCircleDock(
    dockWidgets: List<DockWidgetInfo>,
    onModuleClick: (String) -> Unit,
    onEditClick: () -> Unit
) {
    if (dockWidgets.isEmpty()) return

    // 1st in order is Center Widget, 2nd is Left, 3rd is Right
    val centerWidget = dockWidgets.getOrNull(0) ?: return
    val leftWidget = dockWidgets.getOrNull(1)
    val rightWidget = dockWidgets.getOrNull(2)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // --- Left Small Circle Widget (2nd in order) ---
        if (leftWidget != null) {
            val leftColor = getModuleColor(leftWidget.key)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .combinedClickable(
                        onClick = { onModuleClick(leftWidget.targetModuleKey) },
                        onLongClick = onEditClick
                    )
                    .padding(4.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(78.dp)) {
                    CircularProgressIndicator(
                        progress = { leftWidget.progress.coerceIn(0.05f, 1f) },
                        modifier = Modifier.fillMaxSize().rotate(-90f),
                        strokeWidth = 6.dp,
                        color = leftColor,
                        trackColor = leftColor.copy(alpha = 0.15f),
                        strokeCap = StrokeCap.Round
                    )
                    Text(
                        text = leftWidget.value,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = leftColor,
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = leftWidget.label.uppercase(Locale.US),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }

        // --- Center Big Circle Widget (1st in order) ---
        val centerColor = getModuleColor(centerWidget.key)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .combinedClickable(
                    onClick = { onModuleClick(centerWidget.targetModuleKey) },
                    onLongClick = onEditClick
                )
                .padding(4.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(135.dp)) {
                CircularProgressIndicator(
                    progress = { centerWidget.progress.coerceIn(0.05f, 1f) },
                    modifier = Modifier.fillMaxSize().rotate(-90f),
                    strokeWidth = 10.dp,
                    color = centerColor,
                    trackColor = centerColor.copy(alpha = 0.15f),
                    strokeCap = StrokeCap.Round
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = centerWidget.value,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Black,
                        color = centerColor,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = centerWidget.label.uppercase(Locale.US),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        // --- Right Small Circle Widget (3rd in order) ---
        if (rightWidget != null) {
            val rightColor = getModuleColor(rightWidget.key)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .combinedClickable(
                        onClick = { onModuleClick(rightWidget.targetModuleKey) },
                        onLongClick = onEditClick
                    )
                    .padding(4.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(78.dp)) {
                    CircularProgressIndicator(
                        progress = { rightWidget.progress.coerceIn(0.05f, 1f) },
                        modifier = Modifier.fillMaxSize().rotate(-90f),
                        strokeWidth = 6.dp,
                        color = rightColor,
                        trackColor = rightColor.copy(alpha = 0.15f),
                        strokeCap = StrokeCap.Round
                    )
                    Text(
                        text = rightWidget.value,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = rightColor,
                        textAlign = TextAlign.Center
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = rightWidget.label.uppercase(Locale.US),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

private fun extractDockWidgetData(
    key: String,
    dashData: Map<String, Map<String, String>>,
    context: android.content.Context
): DockWidgetInfo {
    return when (key) {
        "battery" -> {
            val lvl = dashData["battery"]?.get("battery.level")?.removeSuffix("%")?.toFloatOrNull()?.toInt() ?: 0
            DockWidgetInfo("battery", context.getString(R.string.battery), "$lvl%", (lvl / 100f).coerceIn(0f, 1f), "battery")
        }
        "cpu" -> {
            val usage = dashData["cpu"]?.get("cpu.usage")?.removeSuffix("%")?.toFloatOrNull()?.toInt() ?: 0
            DockWidgetInfo("cpu", context.getString(R.string.cpu_load), "$usage%", (usage / 100f).coerceIn(0f, 1f), "cpu")
        }
        "ram" -> {
            val usage = dashData["ram"]?.get("ram.percentage")?.removeSuffix("%")?.toFloatOrNull()?.toInt() ?: 0
            DockWidgetInfo("ram", "RAM", "$usage%", (usage / 100f).coerceIn(0f, 1f), "ram")
        }
        "storage" -> {
            val usage = dashData["storage"]?.get("storage.percentage")?.removeSuffix("%")?.toFloatOrNull()?.toInt() ?: 0
            DockWidgetInfo("storage", "Storage", "$usage%", (usage / 100f).coerceIn(0f, 1f), "storage")
        }
        "data" -> {
            val usageStr = dashData["data"]?.get("data.today_total") ?: "--"
            DockWidgetInfo("data", "Data", usageStr, 0.5f, "data")
        }
        "steps" -> {
            val text = dashData["steps"]?.get("steps.today") ?: "--"
            val count = text.replace(",", "").toFloatOrNull() ?: 0f
            DockWidgetInfo("steps", "Steps", text, (count / 10000f).coerceIn(0f, 1f), "steps")
        }
        "sleep" -> {
            val text = dashData["sleep"]?.get("sleep.deep_percentage") ?: "--"
            val pct = text.removeSuffix("%").toFloatOrNull() ?: 0f
            DockWidgetInfo("sleep", "Sleep", text, (pct / 100f).coerceIn(0f, 1f), "sleep")
        }
        "unlock" -> {
            val text = dashData["unlock"]?.get("unlock.today") ?: "--"
            val count = text.toFloatOrNull() ?: 0f
            DockWidgetInfo("unlock", "Unlocks", text, (count / 50f).coerceIn(0f, 1f), "unlock")
        }
        "network" -> {
            val speed = dashData["network"]?.get("net.download") ?: "--"
            DockWidgetInfo("network", "Speed", speed, 0.5f, "network")
        }
        "uptime" -> {
            val uptimeStr = dashData["uptime"]?.get("uptime.duration") ?: "--"
            DockWidgetInfo("uptime", "Uptime", uptimeStr, 1.0f, "uptime")
        }
        "thermal" -> {
            val temp = dashData["battery"]?.get("battery.temp") ?: "--"
            val tVal = temp.removeSuffix("°C").removeSuffix("°F").toFloatOrNull() ?: 0f
            DockWidgetInfo("thermal", context.getString(R.string.thermal), temp, (tVal / 50f).coerceIn(0f, 1f), "battery")
        }
        else -> {
            DockWidgetInfo(key, key.replaceFirstChar { it.uppercase() }, "--", 0f, key)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopDockCustomizerDialog(
    context: android.content.Context,
    currentKeys: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit
) {
    val activeKeys = remember {
        mutableStateListOf<String>().apply {
            addAll(currentKeys.take(3))
            while (size < 3) {
                val candidate = ALL_DOCK_WIDGET_OPTIONS.firstOrNull { it.first !in this }?.first ?: "battery"
                add(candidate)
            }
        }
    }
    var expandedDropdownIndex by remember { mutableIntStateOf(-1) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.customize_top_dock)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Top Dock slots (Slot 1 is Center Big Circle, Slots 2 & 3 are Left & Right Small Circles):",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    activeKeys.forEachIndexed { idx, key ->
                        val slotLabel = when (idx) {
                            0 -> "Slot 1 (Center Big Circle)"
                            1 -> "Slot 2 (Left Small Circle)"
                            else -> "Slot 3 (Right Small Circle)"
                        }
                        val name = ALL_DOCK_WIDGET_OPTIONS.find { it.first == key }?.second ?: key

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = slotLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f)
                                    )

                                    Box {
                                        Button(
                                            onClick = { expandedDropdownIndex = idx },
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                        ) {
                                            Text("Change", style = MaterialTheme.typography.labelSmall)
                                        }

                                        DropdownMenu(
                                            expanded = expandedDropdownIndex == idx,
                                            onDismissRequest = { expandedDropdownIndex = -1 }
                                        ) {
                                            ALL_DOCK_WIDGET_OPTIONS.forEach { (optKey, optLabel) ->
                                                DropdownMenuItem(
                                                    text = { Text(optLabel) },
                                                    onClick = {
                                                        activeKeys[idx] = optKey
                                                        expandedDropdownIndex = -1
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(activeKeys.toList()) }
            ) {
                Text(stringResource(id = R.string.dialog_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = R.string.dialog_cancel))
            }
        }
    )
}

@Composable
fun KernelCard(
    key: String, 
    data: Map<String, String>, 
    history: List<ModuleDataEntity>,
    sysAccess: com.extensionbox.app.SystemAccess?,
    onClick: () -> Unit,
    reorderableItemScope: ReorderableCollectionItemScope,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val module = ModuleRegistry.getModule(key)
    val icon = ModuleRegistry.iconFor(key)
    val name = ModuleRegistry.nameFor(key)
    val primaryValue = data.values.firstOrNull() ?: ""

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                with(reorderableItemScope) {
                    Icon(
                        imageVector = Icons.Default.DragHandle,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp).draggableHandle(),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                    )
                }
                Spacer(Modifier.width(12.dp))
                
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
                
                Spacer(Modifier.width(16.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(text = primaryValue, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                }

                if (history.isNotEmpty()) {
                    Box(modifier = Modifier.width(60.dp).height(30.dp)) {
                        Sparkline(
                            points = extractPoints(key, history),
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                            strokeWidth = 1.5.dp
                        )
                    }
                }

                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = stringResource(id = R.string.details),
                    modifier = Modifier.padding(start = 8.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (module != null && sysAccess != null) {
                Spacer(Modifier.height(8.dp))
                module.dashboardContent(context, sysAccess)
            }
        }
    }
}
