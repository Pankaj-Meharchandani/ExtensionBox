package com.extensionbox.app.modules

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.extensionbox.app.Fmt
import com.extensionbox.app.Prefs
import com.extensionbox.app.R
import com.extensionbox.app.SystemAccess
import com.extensionbox.app.ui.components.SettingSlider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*

data class AppUsageInfo(
    val packageName: String,
    val totalTimeMs: Long,
    val openCount: Int,
    val avgSessionMs: Long,
    val batteryPctEstimate: Float
)

class AppUsageModule : Module {
    private var ctx: Context? = null
    private var running = false
    private var usageMap = mutableMapOf<String, AppUsageInfo>()

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
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
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
        } catch (ignored: Exception) {}

        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, now)
        if (stats != null) {
            val rawForeground = mutableMapOf<String, Long>()
            var grandTotalMs = 0L
            for (s in stats) {
                if (s.totalTimeInForeground > 0) {
                    val pkg = s.packageName
                    rawForeground[pkg] = (rawForeground[pkg] ?: 0L) + s.totalTimeInForeground
                    grandTotalMs += s.totalTimeInForeground
                }
            }

            // Estimate battery discharge allocated per app
            val estimatedDischarge = 30f // average daily active discharge estimate

            val newMap = mutableMapOf<String, AppUsageInfo>()
            for ((pkg, time) in rawForeground) {
                val opens = (launchCounts[pkg] ?: 1).coerceAtLeast(1)
                val avg = time / opens
                val bat = if (grandTotalMs > 0) (time.toFloat() / grandTotalMs.toFloat()) * estimatedDischarge else 0f
                newMap[pkg] = AppUsageInfo(pkg, time, opens, avg, bat)
            }

            usageMap = newMap.toList().sortedByDescending { it.second.totalTimeMs }.take(10).toMap().toMutableMap()
        }
    }

    override fun compact(): String {
        val c = ctx ?: return ""
        val top = usageMap.maxByOrNull { it.value.totalTimeMs }
        val pm = ctx?.packageManager
        val name = top?.let { info -> 
            try { pm?.getApplicationLabel(pm.getApplicationInfo(info.key, 0))?.toString() } catch (e: Exception) { info.key.substringAfterLast('.') }
        }
        return if (name != null && top != null) c.getString(R.string.app_usage_module_compact_text, name, Fmt.duration(top.value.totalTimeMs)) else c.getString(R.string.app_usage_module_no_usage_data)
    }

    override fun detail(): String {
        val c = ctx ?: return ""
        val sb = StringBuilder()
        val pm = ctx?.packageManager
        sb.append(c.getString(R.string.app_usage_module_todays_app_usage)).append("\n")
        if (usageMap.isEmpty()) {
            sb.append(c.getString(R.string.app_usage_module_no_data_permission))
        } else {
            usageMap.toList().sortedByDescending { it.second.totalTimeMs }.forEach { (pkg, info) ->
                val name = try { pm?.getApplicationLabel(pm.getApplicationInfo(pkg, 0))?.toString() ?: pkg.substringAfterLast('.') } catch (e: Exception) { pkg.substringAfterLast('.') }
                val timeStr = Fmt.duration(info.totalTimeMs)
                val avgStr = Fmt.duration(info.avgSessionMs)
                sb.append("• $name: $timeStr (${info.openCount} opens, avg $avgStr, ~${String.format(Locale.US, "%.1f%%", info.batteryPctEstimate)} bat)\n")
            }
        }
        return sb.toString()
    }

    override fun dataPoints(): LinkedHashMap<String, String> {
        val d = LinkedHashMap<String, String>()
        val pm = ctx?.packageManager
        usageMap.forEach { (pkg, info) ->
            val name = try { pm?.getApplicationLabel(pm.getApplicationInfo(pkg, 0))?.toString() ?: pkg.substringAfterLast('.') } catch (e: Exception) { pkg.substringAfterLast('.') }
            d["usage.$name.screen_time"] = Fmt.duration(info.totalTimeMs)
            d["usage.$name.launches"] = "${info.openCount}"
            d["usage.$name.avg_session"] = Fmt.duration(info.avgSessionMs)
            d["usage.$name.battery"] = String.format(Locale.US, "%.1f%%", info.batteryPctEstimate)
        }
        return d
    }

    override fun checkAlerts(ctx: Context) {}

    @androidx.compose.runtime.Composable
    override fun dashboardContent(ctx: Context, sys: SystemAccess) {
        val hasPermission = remember { mutableStateOf(checkPermission(ctx)) }
        if (!hasPermission.value) return

        val sortedUsage = usageMap.toList().sortedByDescending { it.second.totalTimeMs }
        if (sortedUsage.isEmpty()) return

        val totalTime = sortedUsage.sumOf { it.second.totalTimeMs }
        val sortedTop = sortedUsage.take(5)
        val maxUsage = sortedTop.first().second.totalTimeMs.toFloat()
        val pm = ctx.packageManager

        Column(
            modifier = Modifier.padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Total Usage Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    Text("Total Screen Time Today", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(Fmt.duration(totalTime), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                }
                Text("${sortedUsage.size} Apps Used", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // Simple Multi-Bar Graph
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                val colors = listOf(
                    MaterialTheme.colorScheme.primary,
                    MaterialTheme.colorScheme.secondary,
                    MaterialTheme.colorScheme.tertiary,
                    MaterialTheme.colorScheme.error,
                    MaterialTheme.colorScheme.primaryContainer
                )
                sortedTop.forEachIndexed { index, (_, info) ->
                    val weight = info.totalTimeMs.toFloat() / totalTime.toFloat()
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

            // Rich App List
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                sortedTop.forEach { (pkg, info) ->
                    val appName = remember(pkg) {
                        try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
                        catch (e: Exception) { pkg.substringAfterLast('.') }
                    }
                    val icon = remember(pkg) {
                        try {
                            val drawable = pm.getApplicationIcon(pkg)
                            val bitmap = android.graphics.Bitmap.createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, android.graphics.Bitmap.Config.ARGB_8888)
                            val canvas = android.graphics.Canvas(bitmap)
                            drawable.setBounds(0, 0, canvas.width, canvas.height)
                            drawable.draw(canvas)
                            bitmap.asImageBitmap()
                        } catch (e: Exception) { null }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (icon != null) {
                            Image(
                                bitmap = icon,
                                contentDescription = null,
                                modifier = Modifier.size(32.dp).clip(CircleShape)
                            )
                        } else {
                            Box(
                                modifier = Modifier.size(32.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(appName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                Text(Fmt.duration(info.totalTimeMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
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
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { info.totalTimeMs.toFloat() / maxUsage },
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    override fun settingsContent(ctx: android.content.Context, sys: com.extensionbox.app.SystemAccess) {
        var interval by remember { mutableStateOf(Prefs.getInt(ctx, "usage_interval", 30000).toFloat()) }
        SettingSlider(
            label = ctx.getString(R.string.app_usage_module_update_interval),
            value = interval,
            onValueChange = {
                interval = it
                Prefs.setInt(ctx, "usage_interval", it.toInt())
            },
            valueRange = 10000f..300000f,
            formatter = { if (it >= 60000f) ctx.getString(R.string.app_usage_module_interval_formatter_minutes, it.toInt() / 60000) else ctx.getString(R.string.app_usage_module_interval_formatter_seconds, it.toInt() / 1000) }
        )
    }

    @androidx.compose.runtime.Composable
    override fun composableContent(ctx: Context, sys: SystemAccess) {
        val hasPermission = remember { mutableStateOf(checkPermission(ctx)) }
        var loaded by remember { mutableStateOf(false) }
        
        if (!hasPermission.value) {
            Button(
                onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(ctx.getString(R.string.app_usage_module_grant_usage_access))
            }
        } else {
            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) {
                    start(ctx, sys)
                }
                loaded = true
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (loaded) {
                    dashboardContent(ctx, sys)
                } else {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }
                }
            }
        }
    }

    private fun checkPermission(ctx: Context): Boolean {
        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = appOps.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.packageName)
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }
}
