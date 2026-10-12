package com.extensionbox.app.modules

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Process
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.extensionbox.app.Fmt
import com.extensionbox.app.Prefs
import com.extensionbox.app.R
import com.extensionbox.app.SystemAccess
import com.extensionbox.app.ui.components.SettingSlider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.LinkedHashMap
import java.util.Locale

data class AppUsageInfo(
    val packageName: String,
    val appLabel: String,
    val totalTimeMs: Long,
    val openCount: Int,
    val avgSessionMs: Long,
    val batteryPctEstimate: Float,
    val lastTimeUsedMs: Long,
    val isSystemApp: Boolean
)

data class TimeOfDayUsage(
    val morningMs: Long,
    val afternoonMs: Long,
    val nightMs: Long
)

enum class AppUsageSortOrder {
    TIME, LAUNCHES, BATTERY, SESSION
}

enum class AppCategoryFilter {
    ALL, USER, SYSTEM, UNUSED
}

class AppUsageModule : Module {
    private var ctx: Context? = null
    private var running = false
    private var usageList: List<AppUsageInfo> = emptyList()

    override fun key(): String = "app_usage"
    override fun name(): String = ctx?.getString(R.string.app_usage_module_name) ?: "App Usage"
    override fun description(): String = ctx?.getString(R.string.app_usage_module_description) ?: "Screen time, launches, average session & battery impact"
    override fun defaultEnabled(): Boolean = false
    override fun alive(): Boolean = running
    override fun priority(): Int = 22
    override fun hasSettings(): Boolean = true

    override fun tickIntervalMs(): Int = ctx?.let { Prefs.getInt(it, "usage_interval", 30000) } ?: 30000

    override fun start(ctx: Context, sys: SystemAccess) {
        this.ctx = ctx
        running = true
        tick()
    }

    override fun stop() {
        running = false
    }

    override fun tick() {
        val c = ctx ?: return
        if (!checkPermission(c)) return
        usageList = loadAppUsageData(c)
    }

    override fun compact(): String {
        val c = ctx ?: return ""
        if (!checkPermission(c)) return "Grant Usage Access"
        val top = usageList.firstOrNull()
        return if (top != null) {
            c.getString(R.string.app_usage_module_compact_text, top.appLabel, Fmt.duration(top.totalTimeMs))
        } else {
            c.getString(R.string.app_usage_module_no_usage_data)
        }
    }

    override fun detail(): String {
        val c = ctx ?: return ""
        val sb = StringBuilder()
        if (!checkPermission(c)) {
            sb.append("Usage Access Permission is required to view App Usage stats.")
            return sb.toString()
        }
        sb.append(c.getString(R.string.app_usage_module_todays_app_usage)).append("\n")
        if (usageList.isEmpty()) {
            sb.append(c.getString(R.string.app_usage_module_no_data_permission))
        } else {
            usageList.take(15).forEach { info ->
                val timeStr = Fmt.duration(info.totalTimeMs)
                val avgStr = Fmt.duration(info.avgSessionMs)
                sb.append("• ${info.appLabel}: $timeStr (${info.openCount} opens, avg $avgStr, ~${String.format(Locale.US, "%.1f%%", info.batteryPctEstimate)} bat)\n")
            }
        }
        return sb.toString()
    }

    override fun dataPoints(): LinkedHashMap<String, String> {
        val d = LinkedHashMap<String, String>()
        usageList.take(15).forEach { info ->
            val name = info.appLabel
            d["usage.$name.screen_time"] = Fmt.duration(info.totalTimeMs)
            d["usage.$name.launches"] = "${info.openCount}"
            d["usage.$name.avg_session"] = Fmt.duration(info.avgSessionMs)
            d["usage.$name.battery"] = String.format(Locale.US, "%.1f%%", info.batteryPctEstimate)
        }
        return d
    }

    override fun checkAlerts(ctx: Context) {}

    @Composable
    override fun dashboardContent(ctx: Context, sys: SystemAccess) {}

    @Composable
    override fun settingsContent(ctx: Context, sys: SystemAccess) {
        var interval by remember { mutableStateOf(Prefs.getInt(ctx, "usage_interval", 30000).toFloat()) }
        SettingSlider(
            label = ctx.getString(R.string.app_usage_module_update_interval),
            value = interval,
            onValueChange = {
                interval = it
                Prefs.setInt(ctx, "usage_interval", it.toInt())
            },
            valueRange = 10000f..300000f,
            formatter = {
                if (it >= 60000f) ctx.getString(R.string.app_usage_module_interval_formatter_minutes, it.toInt() / 60000)
                else ctx.getString(R.string.app_usage_module_interval_formatter_seconds, it.toInt() / 1000)
            }
        )
    }

    @Composable
    override fun composableContent(ctx: Context, sys: SystemAccess) {
        val hasPermission = remember { mutableStateOf(checkPermission(ctx)) }
        if (!hasPermission.value) {
            PermissionCard(ctx)
            return
        }

        var dataList by remember { mutableStateOf<List<AppUsageInfo>?>(null) }
        var timeOfDayData by remember { mutableStateOf<TimeOfDayUsage?>(null) }
        var searchQuery by remember { mutableStateOf("") }
        var sortOrder by remember { mutableStateOf(AppUsageSortOrder.TIME) }
        var categoryFilter by remember { mutableStateOf(AppCategoryFilter.ALL) }

        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                val list = loadAppUsageData(ctx)
                val tod = loadTimeOfDayDistribution(ctx)
                dataList = list
                timeOfDayData = tod
            }
        }

        val currentList = dataList ?: usageList
        val pm = ctx.packageManager

        val categoryFiltered = remember(currentList, categoryFilter) {
            when (categoryFilter) {
                AppCategoryFilter.ALL -> currentList
                AppCategoryFilter.USER -> currentList.filter { !it.isSystemApp }
                AppCategoryFilter.SYSTEM -> currentList.filter { it.isSystemApp }
                AppCategoryFilter.UNUSED -> currentList.filter { it.totalTimeMs == 0L || (System.currentTimeMillis() - it.lastTimeUsedMs) > (7 * 24 * 60 * 60 * 1000L) }
            }
        }

        val filteredApps = remember(categoryFiltered, searchQuery, sortOrder) {
            val searched = if (searchQuery.isBlank()) {
                categoryFiltered
            } else {
                categoryFiltered.filter {
                    it.appLabel.contains(searchQuery, ignoreCase = true) ||
                            it.packageName.contains(searchQuery, ignoreCase = true)
                }
            }
            when (sortOrder) {
                AppUsageSortOrder.TIME -> searched.sortedByDescending { it.totalTimeMs }
                AppUsageSortOrder.LAUNCHES -> searched.sortedByDescending { it.openCount }
                AppUsageSortOrder.BATTERY -> searched.sortedByDescending { it.batteryPctEstimate }
                AppUsageSortOrder.SESSION -> searched.sortedByDescending { it.avgSessionMs }
            }
        }

        val totalTimeMs = currentList.sumOf { it.totalTimeMs }
        val totalOpens = currentList.sumOf { it.openCount }
        val totalBattery = currentList.sumOf { it.batteryPctEstimate.toDouble() }.toFloat()

        val maxValInFiltered = remember(filteredApps, sortOrder) {
            when (sortOrder) {
                AppUsageSortOrder.TIME -> filteredApps.firstOrNull()?.totalTimeMs?.toFloat()?.coerceAtLeast(1f) ?: 1f
                AppUsageSortOrder.LAUNCHES -> filteredApps.firstOrNull()?.openCount?.toFloat()?.coerceAtLeast(1f) ?: 1f
                AppUsageSortOrder.BATTERY -> filteredApps.firstOrNull()?.batteryPctEstimate?.coerceAtLeast(0.1f) ?: 1f
                AppUsageSortOrder.SESSION -> filteredApps.firstOrNull()?.avgSessionMs?.toFloat()?.coerceAtLeast(1f) ?: 1f
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "App Usage Analytics",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            // 3 Top Metric Cards Row (Reduced side paddings, clickable & interchange focus)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                MetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Screen Time",
                    value = Fmt.duration(totalTimeMs),
                    color = MaterialTheme.colorScheme.primary,
                    selected = sortOrder == AppUsageSortOrder.TIME,
                    onClick = { sortOrder = AppUsageSortOrder.TIME }
                )
                MetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Total Opens",
                    value = "$totalOpens",
                    color = MaterialTheme.colorScheme.secondary,
                    selected = sortOrder == AppUsageSortOrder.LAUNCHES,
                    onClick = { sortOrder = AppUsageSortOrder.LAUNCHES }
                )
                MetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Est. Battery",
                    value = String.format(Locale.US, "%.1f%%", totalBattery),
                    color = Color(0xFFFF9100),
                    selected = sortOrder == AppUsageSortOrder.BATTERY,
                    onClick = { sortOrder = AppUsageSortOrder.BATTERY }
                )
            }

            // Time-of-Day Peak Usage Breakdown Card (No emojis)
            timeOfDayData?.let { tod ->
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(
                            text = "Peak Time-of-Day Distribution",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(Modifier.height(6.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            TimeOfDayPill(
                                label = "Morning",
                                durationStr = Fmt.duration(tod.morningMs),
                                color = Color(0xFFFFB74D),
                                modifier = Modifier.weight(1f)
                            )
                            TimeOfDayPill(
                                label = "Afternoon",
                                durationStr = Fmt.duration(tod.afternoonMs),
                                color = Color(0xFF2979FF),
                                modifier = Modifier.weight(1f)
                            )
                            TimeOfDayPill(
                                label = "Evening / Night",
                                durationStr = Fmt.duration(tod.nightMs),
                                color = Color(0xFFAB47BC),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // Category Filters & Search Controls
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search app usage...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilterChip(
                        selected = categoryFilter == AppCategoryFilter.ALL,
                        onClick = { categoryFilter = AppCategoryFilter.ALL },
                        label = { Text("All (${currentList.size})") }
                    )
                    FilterChip(
                        selected = categoryFilter == AppCategoryFilter.USER,
                        onClick = { categoryFilter = AppCategoryFilter.USER },
                        label = { Text("User Apps") },
                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(14.dp)) }
                    )
                    FilterChip(
                        selected = categoryFilter == AppCategoryFilter.SYSTEM,
                        onClick = { categoryFilter = AppCategoryFilter.SYSTEM },
                        label = { Text("System") },
                        leadingIcon = { Icon(Icons.Default.Android, contentDescription = null, modifier = Modifier.size(14.dp)) }
                    )
                    FilterChip(
                        selected = categoryFilter == AppCategoryFilter.UNUSED,
                        onClick = { categoryFilter = AppCategoryFilter.UNUSED },
                        label = { Text("Unused (7d+)") },
                        leadingIcon = { Icon(Icons.Default.HourglassDisabled, contentDescription = null, modifier = Modifier.size(14.dp)) }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilterChip(
                        selected = sortOrder == AppUsageSortOrder.TIME,
                        onClick = { sortOrder = AppUsageSortOrder.TIME },
                        label = { Text("Sort Time") },
                        leadingIcon = { Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(14.dp)) }
                    )
                    FilterChip(
                        selected = sortOrder == AppUsageSortOrder.LAUNCHES,
                        onClick = { sortOrder = AppUsageSortOrder.LAUNCHES },
                        label = { Text("Sort Opens") },
                        leadingIcon = { Icon(Icons.Default.TouchApp, contentDescription = null, modifier = Modifier.size(14.dp)) }
                    )
                    FilterChip(
                        selected = sortOrder == AppUsageSortOrder.BATTERY,
                        onClick = { sortOrder = AppUsageSortOrder.BATTERY },
                        label = { Text("Sort Battery") },
                        leadingIcon = { Icon(Icons.Default.BatteryChargingFull, contentDescription = null, modifier = Modifier.size(14.dp)) }
                    )
                }
            }

            // Clean 1-frame App Usage List
            if (currentList.isEmpty() && dataList == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
            } else if (filteredApps.isEmpty()) {
                Text(
                    text = if (searchQuery.isBlank()) "No apps in this category." else "No apps found matching '$searchQuery'",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp)
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    filteredApps.forEach { info ->
                        AppUsageRow(
                            info = info,
                            maxVal = maxValInFiltered,
                            sortOrder = sortOrder,
                            pm = pm,
                            onClick = { openAppSettings(ctx, info.packageName) }
                        )
                    }
                }
            }
        }
    }

    private fun checkPermission(ctx: Context): Boolean {
        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }
}

private fun loadAppUsageData(context: Context): List<AppUsageInfo> {
    val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        ?: return emptyList()

    val now = System.currentTimeMillis()
    val calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val startTime = calendar.timeInMillis.coerceAtMost(now - 1000)

    val launchCounts = mutableMapOf<String, Int>()
    try {
        val events = usm.queryEvents(startTime, now)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                val pkg = event.packageName
                if (!pkg.isNullOrEmpty()) {
                    launchCounts[pkg] = (launchCounts[pkg] ?: 0) + 1
                }
            }
        }
    } catch (_: Exception) {}

    var stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, startTime, now)
    if (stats.isNullOrEmpty() || stats.all { it.totalTimeInForeground <= 0 }) {
        stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, now)
    }
    if (stats.isNullOrEmpty() || stats.all { it.totalTimeInForeground <= 0 }) {
        stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - (24 * 60 * 60 * 1000L), now)
    }

    val rawForeground = mutableMapOf<String, Long>()
    val lastUsedMap = mutableMapOf<String, Long>()
    var grandTotalMs = 0L

    if (stats != null) {
        for (s in stats) {
            if (s.totalTimeInForeground > 0) {
                val pkg = s.packageName
                rawForeground[pkg] = (rawForeground[pkg] ?: 0L) + s.totalTimeInForeground
                lastUsedMap[pkg] = maxOf(lastUsedMap[pkg] ?: 0L, s.lastTimeUsed)
                grandTotalMs += s.totalTimeInForeground
            }
        }
    }

    val pm = context.packageManager
    val estimatedDischarge = 35f

    val list = mutableListOf<AppUsageInfo>()
    for ((pkg, time) in rawForeground) {
        val opens = (launchCounts[pkg] ?: 1).coerceAtLeast(1)
        val avg = time / opens
        val bat = if (grandTotalMs > 0) (time.toFloat() / grandTotalMs.toFloat()) * estimatedDischarge else 0f
        val (label, isSys) = try {
            val info = pm.getApplicationInfo(pkg, 0)
            val l = pm.getApplicationLabel(info).toString()
            val sys = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            Pair(l, sys)
        } catch (_: Exception) {
            Pair(pkg.substringAfterLast('.'), false)
        }
        list.add(AppUsageInfo(pkg, label, time, opens, avg, bat, lastUsedMap[pkg] ?: 0L, isSys))
    }

    return list.sortedByDescending { it.totalTimeMs }
}

private fun loadTimeOfDayDistribution(context: Context): TimeOfDayUsage {
    val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        ?: return TimeOfDayUsage(0L, 0L, 0L)

    val now = System.currentTimeMillis()
    val cal = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val startTime = cal.timeInMillis

    var morning = 0L
    var afternoon = 0L
    var night = 0L

    try {
        val events = usm.queryEvents(startTime, now)
        val event = UsageEvents.Event()
        var lastResumedTime = 0L
        val hourCal = Calendar.getInstance()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val type = event.eventType
            if (type == UsageEvents.Event.ACTIVITY_RESUMED) {
                lastResumedTime = event.timeStamp
            } else if ((type == UsageEvents.Event.ACTIVITY_PAUSED || type == UsageEvents.Event.ACTIVITY_STOPPED) && lastResumedTime > 0) {
                val duration = (event.timeStamp - lastResumedTime).coerceAtLeast(0)
                hourCal.timeInMillis = lastResumedTime
                when (hourCal.get(Calendar.HOUR_OF_DAY)) {
                    in 6..11 -> morning += duration
                    in 12..17 -> afternoon += duration
                    else -> night += duration
                }
                lastResumedTime = 0L
            }
        }
    } catch (_: Exception) {}

    return TimeOfDayUsage(morning, afternoon, night)
}

private fun openAppSettings(c: Context, packageName: String) {
    try {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        c.startActivity(intent)
    } catch (_: Exception) {}
}

@Composable
private fun PermissionCard(ctx: Context) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "App Usage access requires permission.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Button(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) {
                Text(ctx.getString(R.string.app_usage_module_grant_usage_access))
            }
        }
    }
}

@Composable
private fun MetricCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    color: Color,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    Surface(
        modifier = modifier.then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) color.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = if (selected) BorderStroke(1.5.dp, color) else null
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
                color = if (selected) color else MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) color else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun TimeOfDayPill(
    label: String,
    durationStr: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.25f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = durationStr,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = color,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun AppUsageRow(
    info: AppUsageInfo,
    maxVal: Float,
    sortOrder: AppUsageSortOrder,
    pm: PackageManager,
    onClick: () -> Unit
) {
    var appIcon by remember(info.packageName) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(info.packageName) {
        withContext(Dispatchers.IO) {
            try {
                val d = pm.getApplicationIcon(info.packageName)
                val bm = Bitmap.createBitmap(d.intrinsicWidth.coerceAtLeast(1), d.intrinsicHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                val c = Canvas(bm)
                d.setBounds(0, 0, c.width, c.height)
                d.draw(c)
                appIcon = bm.asImageBitmap()
            } catch (_: Exception) {}
        }
    }

    val (topRightVal, currentVal, progressColor) = when (sortOrder) {
        AppUsageSortOrder.TIME -> Triple(
            Fmt.duration(info.totalTimeMs),
            info.totalTimeMs.toFloat(),
            MaterialTheme.colorScheme.primary
        )
        AppUsageSortOrder.LAUNCHES -> Triple(
            "${info.openCount} opens",
            info.openCount.toFloat(),
            MaterialTheme.colorScheme.secondary
        )
        AppUsageSortOrder.BATTERY -> Triple(
            String.format(Locale.US, "~%.1f%% bat", info.batteryPctEstimate),
            info.batteryPctEstimate,
            Color(0xFFFF9100)
        )
        AppUsageSortOrder.SESSION -> Triple(
            "Avg ${Fmt.duration(info.avgSessionMs)}",
            info.avgSessionMs.toFloat(),
            Color(0xFF2979FF)
        )
    }

    val subtitle = when (sortOrder) {
        AppUsageSortOrder.TIME -> "${info.openCount} opens • Avg ${Fmt.duration(info.avgSessionMs)} / open"
        AppUsageSortOrder.LAUNCHES -> "${Fmt.duration(info.totalTimeMs)} time • Avg ${Fmt.duration(info.avgSessionMs)} / open"
        AppUsageSortOrder.BATTERY -> "${Fmt.duration(info.totalTimeMs)} time • ${info.openCount} opens"
        AppUsageSortOrder.SESSION -> "${Fmt.duration(info.totalTimeMs)} time • ${info.openCount} opens"
    }

    val sessionBadge = when {
        info.openCount >= 10 && info.avgSessionMs < 120_000L -> "Quick Bursts"
        info.avgSessionMs >= 600_000L -> "Deep Session"
        else -> null
    }

    val powerBadge = when {
        info.batteryPctEstimate >= 10f -> "High Drain"
        info.batteryPctEstimate <= 2f -> "Efficient"
        else -> null
    }

    val isOveruseWarning = info.totalTimeMs >= 3_600_000L

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        val icon = appIcon
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier.size(36.dp).clip(CircleShape)
            )
        } else {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Text(
                        text = info.appLabel,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isOveruseWarning) {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "1h+ Limit",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFF9100)
                        )
                    }
                }
                Text(
                    text = topRightVal,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = progressColor
                )
            }

            Spacer(Modifier.height(2.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (sessionBadge != null || powerBadge != null) {
                    Text(
                        text = sessionBadge ?: powerBadge!!,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            LinearProgressIndicator(
                progress = { (currentVal / maxVal).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = progressColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}
