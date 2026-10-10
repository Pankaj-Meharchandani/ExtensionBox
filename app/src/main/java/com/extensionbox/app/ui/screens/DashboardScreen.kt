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
        dockKeysStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }
    var showCustomizeDialog by remember { mutableStateOf(false) }

    val rippleScope = rememberCoroutineScope()
    val rippleScale = remember { Animatable(0f) }
    val rippleAlpha = remember { Animatable(0f) }
    val primaryColor = MaterialTheme.colorScheme.primary

    val widgetColors = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondary,
        MaterialTheme.colorScheme.tertiary,
        MaterialTheme.colorScheme.error,
        MaterialTheme.colorScheme.outline
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
        tonalElevation = 4.dp
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
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
                            color = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (isRunning) stringResource(id = R.string.all_systems_operational) else stringResource(id = R.string.engine_paused),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.width(8.dp))

                    IconButton(
                        onClick = { showCustomizeDialog = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = stringResource(id = R.string.customize_top_dock),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Box(contentAlignment = Alignment.Center) {
                    Canvas(modifier = Modifier.size(96.dp)) {
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
                        modifier = Modifier.size(48.dp).background(
                            if (isRunning) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            CircleShape
                        )
                    ) {
                        Icon(
                            imageVector = if (isRunning) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                dockKeys.forEachIndexed { idx, key ->
                    val info = extractDockWidgetData(key, dashData, context)
                    val color = widgetColors.getOrElse(idx) { widgetColors.first() }
                    PulseIndicator(
                        label = info.label,
                        value = info.value,
                        progress = info.progress,
                        color = color,
                        onClick = { onModuleClick(info.targetModuleKey) },
                        onLongClick = { showCustomizeDialog = true }
                    )
                }
            }
        }
    }

    if (showCustomizeDialog) {
        TopDockCustomizerDialog(
            context = context,
            currentKeys = dockKeys,
            onDismiss = { showCustomizeDialog = false },
            onSave = { updatedKeys ->
                showCustomizeDialog = false
                val joined = updatedKeys.joinToString(",")
                Prefs.setString(context, "top_dock_widgets", joined)
                dockKeysStr = joined
            }
        )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PulseIndicator(
    label: String,
    value: String,
    progress: Float,
    color: Color,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(8.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(60.dp)) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxSize().rotate(-90f),
                strokeWidth = 5.dp,
                color = color,
                trackColor = color.copy(alpha = 0.1f),
                strokeCap = StrokeCap.Round
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
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
    val activeKeys = remember { mutableStateListOf<String>().apply { addAll(currentKeys) } }
    var expandedDropdown by remember { mutableStateOf(false) }

    val availableOptions = remember(activeKeys.toList()) {
        ALL_DOCK_WIDGET_OPTIONS.filter { it.first !in activeKeys }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.customize_top_dock)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(id = R.string.customize_top_dock_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Text(
                    text = "Active Widgets (${activeKeys.size}/5):",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    activeKeys.forEachIndexed { idx, key ->
                        val name = ALL_DOCK_WIDGET_OPTIONS.find { it.first == key }?.second ?: key
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${idx + 1}. $name",
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )

                                Row {
                                    if (idx > 0) {
                                        IconButton(
                                            onClick = {
                                                val tmp = activeKeys[idx]
                                                activeKeys[idx] = activeKeys[idx - 1]
                                                activeKeys[idx - 1] = tmp
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Default.ArrowUpward, contentDescription = "Move Up", modifier = Modifier.size(16.dp))
                                        }
                                    }
                                    if (idx < activeKeys.size - 1) {
                                        IconButton(
                                            onClick = {
                                                val tmp = activeKeys[idx]
                                                activeKeys[idx] = activeKeys[idx + 1]
                                                activeKeys[idx + 1] = tmp
                                            },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Default.ArrowDownward, contentDescription = "Move Down", modifier = Modifier.size(16.dp))
                                        }
                                    }
                                    if (activeKeys.size > 1) {
                                        IconButton(
                                            onClick = { activeKeys.removeAt(idx) },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(Icons.Default.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (activeKeys.size < 5 && availableOptions.isNotEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Button(
                            onClick = { expandedDropdown = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(id = R.string.add_widget))
                        }

                        DropdownMenu(
                            expanded = expandedDropdown,
                            onDismissRequest = { expandedDropdown = false }
                        ) {
                            availableOptions.forEach { (optionKey, optionLabel) ->
                                DropdownMenuItem(
                                    text = { Text(optionLabel) },
                                    onClick = {
                                        activeKeys.add(optionKey)
                                        expandedDropdown = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(activeKeys.toList()) },
                enabled = activeKeys.isNotEmpty()
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
