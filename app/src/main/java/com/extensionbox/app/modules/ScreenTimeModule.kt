package com.extensionbox.app.modules

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Process
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.extensionbox.app.Fmt
import com.extensionbox.app.Prefs
import com.extensionbox.app.R
import com.extensionbox.app.SystemAccess
import com.extensionbox.app.ui.components.SettingSlider
import java.util.*

data class AppScreenTimeInfo(
    val packageName: String,
    val totalTimeMs: Long,
    val openCount: Int,
    val avgSessionMs: Long
)

class ScreenTimeModule : Module {
    private var ctx: Context? = null
    private var running = false

    private var totalScreenTimeMs: Long = 0L
    private var totalUnlocksCount: Int = 0
    private var avgTimePerUnlockMs: Long = 0L
    private var usageList: List<AppScreenTimeInfo> = emptyList()

    override fun key(): String = "screen_time"
    override fun name(): String = ctx?.getString(R.string.screen_time_module_name) ?: "Screen Time"
    override fun description(): String = ctx?.getString(R.string.screen_time_module_description) ?: "App usage, open counts, session avg & unlock stats"
    override fun defaultEnabled(): Boolean = true
    override fun alive(): Boolean = running
    override fun priority(): Int = 20
    override fun hasSettings(): Boolean = true

    override fun tickIntervalMs(): Int = ctx?.let { Prefs.getInt(it, "screen_time_interval", 30000) } ?: 30000

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
        var keyguardDismissEvents = 0

        try {
            val events = usm.queryEvents(startTime, now)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val type = event.eventType
                if (type == UsageEvents.Event.ACTIVITY_RESUMED) {
                    val pkg = event.packageName
                    if (!pkg.isNullOrEmpty()) {
                        launchCounts[pkg] = (launchCounts[pkg] ?: 0) + 1
                    }
                } else if (type == 18 || type == 15 || type == 26) {
                    if (type == 18) keyguardDismissEvents++
                }
            }
        } catch (ignored: Exception) {}

        val unlocksFromModule = Prefs.getInt(c, "ulk_today", 0)
        totalUnlocksCount = if (unlocksFromModule > 0) unlocksFromModule else maxOf(keyguardDismissEvents, 1)

        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, now)
        if (stats != null) {
            val rawTimeMap = mutableMapOf<String, Long>()
            var grandTotal = 0L

            for (s in stats) {
                if (s.totalTimeInForeground > 0) {
                    val pkg = s.packageName
                    val time = s.totalTimeInForeground
                    rawTimeMap[pkg] = (rawTimeMap[pkg] ?: 0L) + time
                }
            }

            val list = mutableListOf<AppScreenTimeInfo>()
            for ((pkg, time) in rawTimeMap) {
                grandTotal += time
                val opens = (launchCounts[pkg] ?: 1).coerceAtLeast(1)
                val avgSession = time / opens
                list.add(AppScreenTimeInfo(pkg, time, opens, avgSession))
            }

            totalScreenTimeMs = grandTotal
            avgTimePerUnlockMs = if (totalUnlocksCount > 0) totalScreenTimeMs / totalUnlocksCount else 0L
            usageList = list.sortedByDescending { it.totalTimeMs }
        }
    }

    override fun compact(): String {
        val c = ctx ?: return ""
        if (!checkPermission(c)) return "Grant Usage Access"
        return "${Fmt.duration(totalScreenTimeMs)} • $totalUnlocksCount unlocks"
    }

    override fun detail(): String {
        val c = ctx ?: return ""
        val sb = StringBuilder()
        if (!checkPermission(c)) {
            sb.append("Usage Access Permission is required to view Screen Time stats.")
            return sb.toString()
        }
        val pm = c.packageManager
        sb.append("Total Screen Time Today: ").append(Fmt.duration(totalScreenTimeMs)).append("\n")
        sb.append("Total Device Unlocks: ").append(totalUnlocksCount).append("\n")
        sb.append("Avg Time / Unlock: ").append(Fmt.duration(avgTimePerUnlockMs)).append("\n\n")
        sb.append("Apps Breakdown:\n")
        usageList.take(15).forEach { info ->
            val name = try { pm.getApplicationLabel(pm.getApplicationInfo(info.packageName, 0)).toString() } catch (e: Exception) { info.packageName.substringAfterLast('.') }
            sb.append("• $name: ").append(Fmt.duration(info.totalTimeMs))
                .append(" (${info.openCount} opens, avg ").append(Fmt.duration(info.avgSessionMs)).append("/open)\n")
        }
        return sb.toString()
    }

    override fun dataPoints(): LinkedHashMap<String, String> {
        val d = LinkedHashMap<String, String>()
        d["screen_time.total_screen_time"] = Fmt.duration(totalScreenTimeMs)
        d["screen_time.total_screen_time_ms"] = "$totalScreenTimeMs"
        d["screen_time.total_unlocks"] = "$totalUnlocksCount"
        d["screen_time.avg_time_per_unlock"] = Fmt.duration(avgTimePerUnlockMs)
        val top = usageList.firstOrNull()
        if (top != null) {
            val pm = ctx?.packageManager
            val name = try { pm?.getApplicationLabel(pm.getApplicationInfo(top.packageName, 0))?.toString() ?: top.packageName.substringAfterLast('.') } catch (e: Exception) { top.packageName.substringAfterLast('.') }
            d["screen_time.top_app"] = name
            d["screen_time.top_app_time"] = Fmt.duration(top.totalTimeMs)
        }
        return d
    }

    override fun checkAlerts(ctx: Context) {}

    @Composable
    override fun dashboardContent(ctx: Context, sys: SystemAccess) {
        val hasPermission = remember { mutableStateOf(checkPermission(ctx)) }
        if (!hasPermission.value) {
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
                        text = "Screen Time access requires permission.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Button(onClick = {
                        ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) {
                        Text("Grant Usage Access")
                    }
                }
            }
            return
        }

        val topApps = usageList.take(5)
        val maxAppTime = topApps.firstOrNull()?.totalTimeMs?.toFloat() ?: 1f
        val pm = ctx.packageManager

        Column(
            modifier = Modifier.padding(top = 4.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Summary Chips / Cards Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Screen Time",
                    value = Fmt.duration(totalScreenTimeMs),
                    color = MaterialTheme.colorScheme.primary
                )
                MetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Unlocks",
                    value = "$totalUnlocksCount",
                    color = MaterialTheme.colorScheme.secondary
                )
                MetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Avg / Unlock",
                    value = Fmt.duration(avgTimePerUnlockMs),
                    color = MaterialTheme.colorScheme.tertiary
                )
            }

            // App Screen Time List
            if (topApps.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    topApps.forEach { info ->
                        val appLabel = remember(info.packageName) {
                            try { pm.getApplicationLabel(pm.getApplicationInfo(info.packageName, 0)).toString() }
                            catch (e: Exception) { info.packageName.substringAfterLast('.') }
                        }
                        val appIcon = remember(info.packageName) {
                            try {
                                val d = pm.getApplicationIcon(info.packageName)
                                val bm = Bitmap.createBitmap(d.intrinsicWidth.coerceAtLeast(1), d.intrinsicHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                                val c = Canvas(bm)
                                d.setBounds(0, 0, c.width, c.height)
                                d.draw(c)
                                bm.asImageBitmap()
                            } catch (e: Exception) { null }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (appIcon != null) {
                                Image(
                                    bitmap = appIcon,
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
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = appLabel,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold
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
                                }

                                Spacer(Modifier.height(4.dp))

                                LinearProgressIndicator(
                                    progress = { (info.totalTimeMs.toFloat() / maxAppTime).coerceIn(0f, 1f) },
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
                }
            }
        }
    }

    @Composable
    override fun composableContent(ctx: Context, sys: SystemAccess) {
        dashboardContent(ctx, sys)
    }

    @Composable
    override fun settingsContent(ctx: Context, sys: SystemAccess) {
        var interval by remember { mutableStateOf(Prefs.getInt(ctx, "screen_time_interval", 30000).toFloat()) }
        SettingSlider(
            label = "Update Interval",
            value = interval,
            onValueChange = {
                interval = it
                Prefs.setInt(ctx, "screen_time_interval", it.toInt())
            },
            valueRange = 10000f..300000f,
            formatter = { if (it >= 60000f) "${it.toInt() / 60000}m" else "${it.toInt() / 1000}s" }
        )
    }

    private fun checkPermission(ctx: Context): Boolean {
        val appOps = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }
}

@Composable
private fun MetricCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    color: androidx.compose.ui.graphics.Color
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
