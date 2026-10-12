package com.extensionbox.app.modules

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Process
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
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
    val lastTimeUsedMs: Long
)

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
    override fun dashboardContent(ctx: Context, sys: SystemAccess) {
        // Details shown in detail screen via composableContent
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
        if (!hasPermission.value) {
            PermissionCard(ctx)
            return
        }

        var dataList by remember { mutableStateOf<List<AppUsageInfo>?>(null) }
        var searchQuery by remember { mutableStateOf("") }

        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                dataList = loadAppUsageData(ctx)
            }
        }

        val currentList = dataList ?: usageList
        val pm = ctx.packageManager
        val maxTime = currentList.firstOrNull()?.totalTimeMs ?: 1L
        val totalTimeMs = currentList.sumOf { it.totalTimeMs }
        val totalOpens = currentList.sumOf { it.openCount }
        val totalBattery = currentList.sumOf { it.batteryPctEstimate.toDouble() }.toFloat()

        val filteredApps = remember(currentList, searchQuery) {
            if (searchQuery.isBlank()) currentList
            else currentList.filter { info ->
                info.appLabel.contains(searchQuery, ignoreCase = true) ||
                        info.packageName.contains(searchQuery, ignoreCase = true)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "App Usage Breakdown",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Screen Time",
                    value = Fmt.duration(totalTimeMs),
                    color = MaterialTheme.colorScheme.primary
                )
                MetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Total Opens",
                    value = "$totalOpens",
                    color = MaterialTheme.colorScheme.secondary
                )
                MetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Est. Battery",
                    value = String.format(Locale.US, "%.1f%%", totalBattery),
                    color = MaterialTheme.colorScheme.tertiary
                )
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search app usage...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp)
            )

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
                    text = if (searchQuery.isBlank()) "No app usage detected today." else "No apps found matching '$searchQuery'",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    filteredApps.forEach { info ->
                        AppUsageRow(
                            info = info,
                            maxTimeMs = maxTime,
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
        val label = try {
            val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            pkg.substringAfterLast('.')
        }
        list.add(AppUsageInfo(pkg, label, time, opens, avg, bat, lastUsedMap[pkg] ?: 0L))
    }

    return list.sortedByDescending { it.totalTimeMs }
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
    color: Color
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
                color = color
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun AppUsageRow(
    info: AppUsageInfo,
    maxTimeMs: Long,
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

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                Text(
                    text = info.appLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Text(
                    text = Fmt.duration(info.totalTimeMs),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(2.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${info.openCount} opens • Avg ${Fmt.duration(info.avgSessionMs)} / open",
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

            Spacer(Modifier.height(4.dp))

            LinearProgressIndicator(
                progress = { (info.totalTimeMs.toFloat() / maxTimeMs.toFloat().coerceAtLeast(1f)).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}
