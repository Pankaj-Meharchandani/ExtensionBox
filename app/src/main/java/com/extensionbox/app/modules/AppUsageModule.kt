package com.extensionbox.app.modules

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
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
    val lastTimeUsedMs: Long
)

enum class AppUsageSortOrder {
    TIME, LAUNCHES, BATTERY, SESSION
}

class AppUsageModule : Module {
    private var ctx: Context? = null
    private var running = false
    private var usageList = listOf<AppUsageInfo>()

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
        val usm = c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startTime = calendar.timeInMillis

        val launchCounts = mutableMapOf<String, Int>()
        try {
            val events = usm.queryEvents(startTime, now)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                    event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    val pkg = event.packageName
                    launchCounts[pkg] = (launchCounts[pkg] ?: 0) + 1
                }
            }
        } catch (_: Exception) {}

        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, now)
        if (stats != null) {
            val rawForeground = mutableMapOf<String, Long>()
            val lastUsedMap = mutableMapOf<String, Long>()
            var grandTotalMs = 0L

            for (s in stats) {
                if (s.totalTimeInForeground > 0) {
                    val pkg = s.packageName
                    rawForeground[pkg] = (rawForeground[pkg] ?: 0L) + s.totalTimeInForeground
                    lastUsedMap[pkg] = maxOf(lastUsedMap[pkg] ?: 0L, s.lastTimeUsed)
                    grandTotalMs += s.totalTimeInForeground
                }
            }

            val pm = c.packageManager
            val estimatedDischarge = 35f

            val newList = mutableListOf<AppUsageInfo>()
            for ((pkg, time) in rawForeground) {
                val opens = (launchCounts[pkg] ?: 1).coerceAtLeast(1)
                val avg = time / opens
                val bat = if (grandTotalMs > 0) (time.toFloat() / grandTotalMs.toFloat()) * estimatedDischarge else 0f
                val label = try {
                    val info = pm.getApplicationInfo(pkg, 0)
                    pm.getApplicationLabel(info).toString()
                } catch (_: Exception) {
                    pkg.substringAfterLast('.')
                }
                newList.add(AppUsageInfo(pkg, label, time, opens, avg, bat, lastUsedMap[pkg] ?: 0L))
            }

            usageList = newList.sortedByDescending { it.totalTimeMs }
        }
    }

    override fun compact(): String {
        val c = ctx ?: return ""
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
        sb.append(c.getString(R.string.app_usage_module_todays_app_usage)).append("\n")
        if (usageList.isEmpty()) {
            sb.append(c.getString(R.string.app_usage_module_no_data_permission))
        } else {
            usageList.take(12).forEach { info ->
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
    override fun dashboardContent(ctx: Context, sys: SystemAccess) {
        if (usageList.isEmpty()) return
        val totalTime = usageList.sumOf { it.totalTimeMs }
        val topList = usageList.take(5)
        val maxUsage = topList.first().totalTimeMs.toFloat().coerceAtLeast(1f)

        Column(
            modifier = Modifier.padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    Text(
                        text = "Total Screen Time Today",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = Fmt.duration(totalTime),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = "${usageList.size} Apps Active",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Proportion bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                val colors = listOf(
                    MaterialTheme.colorScheme.primary,
                    MaterialTheme.colorScheme.secondary,
                    MaterialTheme.colorScheme.tertiary,
                    Color(0xFFFF9100),
                    Color(0xFF2979FF)
                )
                topList.forEachIndexed { index, info ->
                    val weight = info.totalTimeMs.toFloat() / totalTime.toFloat().coerceAtLeast(1f)
                    if (weight > 0.01f) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .weight(weight)
                                .background(colors[index % colors.size])
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                topList.forEach { info ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        AppIconBox(packageName = info.packageName, size = 32)

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = info.appLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Text(
                                    text = Fmt.duration(info.totalTimeMs),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }

                            Spacer(modifier = Modifier.height(2.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${info.openCount} opens • Avg ${Fmt.duration(info.avgSessionMs)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "~${String.format(Locale.US, "%.1f%%", info.batteryPctEstimate)} bat",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            LinearProgressIndicator(
                                progress = { (info.totalTimeMs.toFloat() / maxUsage).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

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
        var loaded by remember { mutableStateOf(false) }

        if (!hasPermission.value) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        modifier = Modifier.size(56.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.LockClock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    Text(
                        text = "Usage Access Required",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(Modifier.height(6.dp))

                    Text(
                        text = "Extension Box requires Usage Access permission to track active screen time, app launches, and battery drain per application.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )

                    Spacer(Modifier.height(18.dp))

                    Button(
                        onClick = {
                            ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(ctx.getString(R.string.app_usage_module_grant_usage_access))
                    }
                }
            }
        } else {
            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) {
                    tick()
                }
                loaded = true
            }

            if (!loaded) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                }
            } else {
                var searchQuery by remember { mutableStateOf("") }
                var sortOrder by remember { mutableStateOf(AppUsageSortOrder.TIME) }

                val filteredList = remember(usageList, searchQuery, sortOrder) {
                    val filtered = if (searchQuery.isBlank()) {
                        usageList
                    } else {
                        usageList.filter {
                            it.appLabel.contains(searchQuery, ignoreCase = true) ||
                                    it.packageName.contains(searchQuery, ignoreCase = true)
                        }
                    }
                    when (sortOrder) {
                        AppUsageSortOrder.TIME -> filtered.sortedByDescending { it.totalTimeMs }
                        AppUsageSortOrder.LAUNCHES -> filtered.sortedByDescending { it.openCount }
                        AppUsageSortOrder.BATTERY -> filtered.sortedByDescending { it.batteryPctEstimate }
                        AppUsageSortOrder.SESSION -> filtered.sortedByDescending { it.avgSessionMs }
                    }
                }

                val totalTimeMs = remember(usageList) { usageList.sumOf { it.totalTimeMs } }
                val totalOpens = remember(usageList) { usageList.sumOf { it.openCount } }
                val totalBattery = remember(usageList) { usageList.sumOf { it.batteryPctEstimate.toDouble() }.toFloat() }
                val avgSessionOverall = if (totalOpens > 0) totalTimeMs / totalOpens else 0L

                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // --- 1. Total Screen Time Hero Header ---
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = Fmt.duration(totalTimeMs),
                                        style = MaterialTheme.typography.displayMedium,
                                        fontWeight = FontWeight.Black,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "Total Screen Time Today • ${usageList.size} Apps Used",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                    modifier = Modifier.size(52.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Smartphone,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(26.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(16.dp))

                            // Proportions bar
                            val topFive = usageList.take(5)
                            if (totalTimeMs > 0) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(10.dp)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    val barColors = listOf(
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.colorScheme.secondary,
                                        MaterialTheme.colorScheme.tertiary,
                                        Color(0xFF00E676),
                                        Color(0xFFFF9100)
                                    )
                                    topFive.forEachIndexed { idx, info ->
                                        val weight = info.totalTimeMs.toFloat() / totalTimeMs.toFloat().coerceAtLeast(1f)
                                        if (weight > 0.01f) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxHeight()
                                                    .weight(weight)
                                                    .background(barColors[idx % barColors.size])
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(16.dp))
                            }

                            // 3 Summary Stats Row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                AppStatBox(
                                    label = "Total Opens",
                                    value = "$totalOpens",
                                    subValue = "Launches",
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(8.dp))
                                AppStatBox(
                                    label = "Avg Session",
                                    value = Fmt.duration(avgSessionOverall),
                                    subValue = "Per Launch",
                                    color = Color(0xFF2979FF),
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(8.dp))
                                AppStatBox(
                                    label = "Est. Battery",
                                    value = String.format(Locale.US, "%.1f%%", totalBattery),
                                    subValue = "Active Drain",
                                    color = Color(0xFFFF9100),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    // --- 2. Top 3 Apps Podium Section ---
                    if (usageList.size >= 3) {
                        Surface(
                            shape = RoundedCornerShape(22.dp),
                            color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "Top Used Applications",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.height(14.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    val podiumColors = listOf(Color(0xFFFFD700), Color(0xFFC0C0C0), Color(0xFFCD7F32))
                                    usageList.take(3).forEachIndexed { rank, info ->
                                        PodiumCard(
                                            rank = rank + 1,
                                            info = info,
                                            accentColor = podiumColors[rank],
                                            modifier = Modifier.weight(1f),
                                            onClick = { openAppSettings(ctx, info.packageName) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // --- 3. Search & Sort Controls ---
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            // Search bar
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text("Search app name or package...") },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                trailingIcon = {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { searchQuery = "" }) {
                                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                                        }
                                    }
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Spacer(Modifier.height(12.dp))

                            // Sort Order Filter Chips
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                FilterChip(
                                    selected = sortOrder == AppUsageSortOrder.TIME,
                                    onClick = { sortOrder = AppUsageSortOrder.TIME },
                                    label = { Text("Time") },
                                    leadingIcon = { Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(14.dp)) }
                                )
                                FilterChip(
                                    selected = sortOrder == AppUsageSortOrder.LAUNCHES,
                                    onClick = { sortOrder = AppUsageSortOrder.LAUNCHES },
                                    label = { Text("Opens") },
                                    leadingIcon = { Icon(Icons.Default.TouchApp, contentDescription = null, modifier = Modifier.size(14.dp)) }
                                )
                                FilterChip(
                                    selected = sortOrder == AppUsageSortOrder.BATTERY,
                                    onClick = { sortOrder = AppUsageSortOrder.BATTERY },
                                    label = { Text("Battery") },
                                    leadingIcon = { Icon(Icons.Default.BatteryChargingFull, contentDescription = null, modifier = Modifier.size(14.dp)) }
                                )
                            }
                        }
                    }

                    // --- 4. Detailed App Usage List ---
                    if (filteredList.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (searchQuery.isNotEmpty()) "No apps found matching '$searchQuery'" else "No app usage data available today.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        val maxTimeInFiltered = filteredList.first().totalTimeMs.toFloat().coerceAtLeast(1f)

                        Surface(
                            shape = RoundedCornerShape(22.dp),
                            color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Text(
                                    text = "App Usage Breakdown (${filteredList.size})",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )

                                filteredList.forEach { info ->
                                    AppUsageItemRow(
                                        info = info,
                                        maxTime = maxTimeInFiltered,
                                        onClick = { openAppSettings(ctx, info.packageName) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun PodiumCard(
        rank: Int,
        info: AppUsageInfo,
        accentColor: Color,
        modifier: Modifier = Modifier,
        onClick: () -> Unit
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = accentColor.copy(alpha = 0.1f),
            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.3f)),
            modifier = modifier.clickable { onClick() }
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = CircleShape,
                    color = accentColor,
                    modifier = Modifier.size(22.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "#$rank",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Black,
                            color = Color.Black
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                AppIconBox(packageName = info.packageName, size = 38)

                Spacer(Modifier.height(6.dp))

                Text(
                    text = info.appLabel,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(2.dp))

                Text(
                    text = Fmt.duration(info.totalTimeMs),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = "${info.openCount} opens",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    @Composable
    private fun AppUsageItemRow(
        info: AppUsageInfo,
        maxTime: Float,
        onClick: () -> Unit
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            modifier = Modifier.fillMaxWidth().clickable { onClick() }
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AppIconBox(packageName = info.packageName, size = 40)

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = info.appLabel,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )

                        Text(
                            text = Fmt.duration(info.totalTimeMs),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Black,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${info.openCount} opens • Avg ${Fmt.duration(info.avgSessionMs)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "~${String.format(Locale.US, "%.1f%%", info.batteryPctEstimate)} bat",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }

                    Spacer(Modifier.height(6.dp))

                    LinearProgressIndicator(
                        progress = { (info.totalTimeMs.toFloat() / maxTime).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }
        }
    }

    @Composable
    private fun AppStatBox(
        label: String,
        value: String,
        subValue: String,
        color: Color,
        modifier: Modifier = Modifier
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = color.copy(alpha = 0.12f),
            border = BorderStroke(1.dp, color.copy(alpha = 0.25f)),
            modifier = modifier
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = color,
                    maxLines = 1
                )
                Text(
                    text = subValue,
                    style = MaterialTheme.typography.labelSmall,
                    color = color.copy(alpha = 0.85f),
                    maxLines = 1
                )
            }
        }
    }

    @Composable
    private fun AppIconBox(packageName: String, size: Int = 36) {
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
            val bitmap = remember(d) { d.toBitmap(width = 80, height = 80).asImageBitmap() }
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.size(size.dp).clip(CircleShape)
            )
        } else {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(size.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Android,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size((size * 0.55f).dp)
                    )
                }
            }
        }
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

    private fun checkPermission(ctx: Context): Boolean {
        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = appOps.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.packageName)
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }
}
