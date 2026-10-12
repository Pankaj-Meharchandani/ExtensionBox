package com.extensionbox.app.modules

import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Drawable
import android.os.BatteryManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Power
import androidx.compose.material.icons.filled.RestartAlt
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
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.toBitmap
import com.extensionbox.app.Fmt
import com.extensionbox.app.Prefs
import com.extensionbox.app.R
import com.extensionbox.app.SystemAccess
import com.extensionbox.app.db.AppDatabase
import com.extensionbox.app.ui.components.SettingSlider
import com.extensionbox.app.ui.components.SettingSwitch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.LinkedHashMap
import java.util.Locale
import kotlin.math.abs

data class AppBatteryUsageInfo(
    val packageName: String,
    val appLabel: String,
    val foregroundTimeMs: Long,
    val batteryPct: Float
)

class BatteryModule : Module {

    private var ctx: Context? = null
    private var sys: SystemAccess? = null
    private var rcv: BroadcastReceiver? = null
    private var running = false

    private var level = 0
    private var temp = 0
    private var voltage = 0
    private var health = 0
    private var status = 0
    private var plugged = 0
    private var designCap = 4000
    private var currentMa = 0

    // Enhanced tier data
    private var actualCap = -1
    private var cycleCount = -1
    private var realHealthPct = -1
    private var technology: String? = null
    private var cpuTemp = Float.NaN

    // Screen tracking
    private var screenOn = true
    private var onAccMs: Long = 0
    private var offAccMs: Long = 0
    private var periodStart: Long = 0
    private var periodStartLevel: Int = 0
    private var onDrain = 0f
    private var offDrain = 0f

    override fun key(): String = "battery"
    override fun name(): String = ctx?.getString(R.string.battery_module_name) ?: "Battery & Power"
    override fun description(): String = ctx?.getString(R.string.battery_module_description) ?: "Battery health, power draw, time left, and app battery usage"
    override fun defaultEnabled(): Boolean = true
    override fun alive(): Boolean = running
    override fun hasSettings(): Boolean = true

    @Composable
    override fun composableContent(ctx: Context, sys: SystemAccess) {
        val isCharge = status == BatteryManager.BATTERY_STATUS_CHARGING
        val absMa = abs(currentMa)
        val watts = absMa * voltage / 1_000_000f
        val tempC = temp / 10f

        var appBatteryUsage by remember { mutableStateOf<List<AppBatteryUsageInfo>>(emptyList()) }

        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                appBatteryUsage = getTopAppBatteryUsage(ctx)
            }
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            // --- 1. Battery Health & Live Metrics Bento Grid ---
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "$level%",
                                style = MaterialTheme.typography.displayMedium,
                                fontWeight = FontWeight.Black,
                                color = if (isCharge) Color(0xFF00E676) else MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = if (isCharge) "Charging (${pluggedType()})" else "Discharging • ${timeLeft()}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isCharge) Color(0xFF00E676) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Surface(
                            shape = CircleShape,
                            color = if (isCharge) Color(0xFF00E676).copy(alpha = 0.15f) else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            modifier = Modifier.size(52.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = healthStr(),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isCharge) Color(0xFF00E676) else MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // 4 Stat Items Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        BatteryStatBox(
                            label = if (isCharge) "Charge Rate" else "Discharge Rate",
                            value = "$absMa mA",
                            subValue = String.format(Locale.US, "%.1f W", watts),
                            color = if (isCharge) Color(0xFF00E676) else Color(0xFFFF5252),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        BatteryStatBox(
                            label = "Temperature",
                            value = Fmt.temp(tempC),
                            subValue = if (!cpuTemp.isNaN()) "CPU ${Fmt.temp(cpuTemp)}" else "Battery",
                            color = Color(0xFFFF9100),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        BatteryStatBox(
                            label = "Voltage",
                            value = String.format(Locale.US, "%.2f V", voltage / 1000f),
                            subValue = technology ?: "Li-ion",
                            color = Color(0xFF2979FF),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    if (sys.isEnhanced() && realHealthPct > 0) {
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        Spacer(Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Health & Cycles",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "$realHealthPct% Health • ${if (cycleCount >= 0) "$cycleCount cycles" else "Design $designCap mAh"}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // --- 2. App Battery Drain Breakdown ("Which app used most battery") ---
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "App Battery Usage Today",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Estimated battery drain based on active foreground screen time",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(14.dp))

                    if (appBatteryUsage.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Grant Usage Access to view per-app battery drain stats",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        val maxPct = appBatteryUsage.first().batteryPct.coerceAtLeast(0.1f)

                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            appBatteryUsage.forEach { info ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    AppIconBox(packageName = info.packageName)

                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
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
                                                text = String.format(Locale.US, "%.1f%%", info.batteryPct),
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }

                                        Spacer(Modifier.height(2.dp))

                                        Text(
                                            text = "Screen: ${Fmt.duration(info.foregroundTimeMs)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )

                                        Spacer(Modifier.height(4.dp))

                                        LinearProgressIndicator(
                                            progress = { (info.batteryPct / maxPct).coerceIn(0f, 1f) },
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
            }
        }
    }

    @Composable
    private fun BatteryStatBox(
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
    private fun AppIconBox(packageName: String) {
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
                modifier = Modifier.size(32.dp).clip(CircleShape)
            )
        } else {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Android,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }

    @Composable
    override fun settingsContent(ctx: Context, sys: SystemAccess) {
        var interval by remember { mutableStateOf(Prefs.getInt(ctx, "bat_interval", 10000).toFloat()) }

        Column {
            SettingSlider(
                label = ctx.getString(R.string.battery_module_update_interval),
                value = interval,
                valueRange = 1000f..60000f,
                onValueChange = {
                    interval = it
                    Prefs.setInt(ctx, "bat_interval", it.toInt())
                },
                formatter = { ctx.getString(R.string.battery_module_interval_formatter, it.toInt() / 1000) }
            )

            // --- Battery Reset Options ---
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            var resetFull by remember { mutableStateOf(Prefs.getBool(ctx, "scr_reset_full", true)) }
            SettingSwitch(
                label = ctx.getString(R.string.reset_on_full_charge),
                checked = resetFull,
                onCheckedChange = {
                    resetFull = it
                    Prefs.setBool(ctx, "scr_reset_full", it)
                }
            )

            var resetPlugged by remember { mutableStateOf(Prefs.getBool(ctx, "scr_reset_plugged", false)) }
            SettingSwitch(
                label = "Reset on Plugged",
                checked = resetPlugged,
                onCheckedChange = {
                    resetPlugged = it
                    Prefs.setBool(ctx, "scr_reset_plugged", it)
                }
            )

            var resetBoot by remember { mutableStateOf(Prefs.getBool(ctx, "scr_reset_boot", true)) }
            SettingSwitch(
                label = ctx.getString(R.string.reset_on_reboot),
                checked = resetBoot,
                onCheckedChange = {
                    resetBoot = it
                    Prefs.setBool(ctx, "scr_reset_boot", it)
                }
            )

            if (sys.rootProvider != SystemAccess.RootProvider.NONE) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                var limitEn by remember {
                    mutableStateOf(Prefs.getBool(ctx, "bat_charge_limit_en", false))
                }
                var limitVal by remember {
                    mutableStateOf(Prefs.getInt(ctx, "bat_charge_limit_val", 80).toFloat())
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            ctx.getString(R.string.battery_module_charge_limiter),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            ctx.getString(R.string.battery_module_stop_charging_at, limitVal.toInt()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = limitEn,
                        onCheckedChange = {
                            limitEn = it
                            Prefs.setBool(ctx, "bat_charge_limit_en", it)
                            if (!it) {
                                CoroutineScope(Dispatchers.IO).launch {
                                    sys.setChargingEnabled(true)
                                }
                            }
                        }
                    )
                }

                if (limitEn) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Slider(
                        value = limitVal,
                        onValueChange = {
                            limitVal = it
                            Prefs.setInt(ctx, "bat_charge_limit_val", it.toInt())
                        },
                        valueRange = 50f..100f,
                        steps = 49
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            var lowAlert by remember { mutableStateOf(Prefs.getBool(ctx, "bat_low_alert", true)) }
            SettingSwitch(
                label = ctx.getString(R.string.battery_module_low_battery_alert),
                checked = lowAlert,
                onCheckedChange = {
                    lowAlert = it
                    Prefs.setBool(ctx, "bat_low_alert", it)
                }
            )
            if (lowAlert) {
                var lowThresh by remember { mutableStateOf(Prefs.getInt(ctx, "bat_low_thresh", 15).toFloat()) }
                SettingSlider(
                    label = ctx.getString(R.string.battery_module_low_alert_threshold),
                    value = lowThresh,
                    valueRange = 5f..50f,
                    onValueChange = {
                        lowThresh = it
                        Prefs.setInt(ctx, "bat_low_thresh", it.toInt())
                    },
                    formatter = { "${it.toInt()}%" }
                )
            }
            var tempAlert by remember { mutableStateOf(Prefs.getBool(ctx, "bat_temp_alert", true)) }
            SettingSwitch(
                label = ctx.getString(R.string.battery_module_high_temp_alert),
                checked = tempAlert,
                onCheckedChange = {
                    tempAlert = it
                    Prefs.setBool(ctx, "bat_temp_alert", it)
                }
            )
            if (tempAlert) {
                var tempThresh by remember { mutableStateOf(Prefs.getInt(ctx, "bat_temp_thresh", 42).toFloat()) }
                SettingSlider(
                    label = ctx.getString(R.string.battery_module_temp_threshold),
                    value = tempThresh,
                    valueRange = 30f..60f,
                    onValueChange = {
                        tempThresh = it
                        Prefs.setInt(ctx, "bat_temp_thresh", it.toInt())
                    },
                    formatter = { ctx.getString(R.string.battery_module_temp_formatter, it.toInt()) }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            var showDrain by remember { mutableStateOf(Prefs.getBool(ctx, "scr_show_drain", true)) }
            SettingSwitch(
                label = ctx.getString(R.string.battery_module_show_screen_drain),
                checked = showDrain,
                onCheckedChange = {
                    showDrain = it
                    Prefs.setBool(ctx, "scr_show_drain", it)
                }
            )
            var useUsage by remember { mutableStateOf(Prefs.getBool(ctx, "m_app_usage_enabled", true)) }
            SettingSwitch(
                label = ctx.getString(R.string.battery_module_show_app_usage),
                checked = useUsage,
                onCheckedChange = {
                    useUsage = it
                    Prefs.setBool(ctx, "m_app_usage_enabled", it)
                }
            )
            var timeLimit by remember { mutableStateOf(Prefs.getInt(ctx, "scr_time_limit", 0).toFloat()) }
            SettingSlider(
                label = ctx.getString(R.string.battery_module_screen_time_limit),
                value = timeLimit,
                valueRange = 0f..600f,
                steps = 12,
                onValueChange = {
                    timeLimit = it
                    Prefs.setInt(ctx, "scr_time_limit", it.toInt())
                },
                formatter = { if (it == 0f) ctx.getString(R.string.battery_module_disabled) else ctx.getString(R.string.battery_module_time_limit_formatter, it.toInt()) }
            )
        }
    }

    private fun getTopAppBatteryUsage(context: Context): List<AppBatteryUsageInfo> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return emptyList()
        val now = System.currentTimeMillis()
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
        }
        val startTime = calendar.timeInMillis

        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, now) ?: return emptyList()
        var totalForegroundMs = 0L
        val appTimeMap = mutableMapOf<String, Long>()

        for (s in stats) {
            if (s.totalTimeInForeground > 0 && s.packageName != context.packageName) {
                appTimeMap[s.packageName] = (appTimeMap[s.packageName] ?: 0L) + s.totalTimeInForeground
                totalForegroundMs += s.totalTimeInForeground
            }
        }

        if (totalForegroundMs <= 0) return emptyList()

        val pm = context.packageManager
        val result = mutableListOf<AppBatteryUsageInfo>()

        for ((pkg, timeMs) in appTimeMap) {
            val pct = (timeMs.toFloat() / totalForegroundMs.toFloat()) * 35f
            if (pct >= 0.1f) {
                val label = try {
                    val info = pm.getApplicationInfo(pkg, 0)
                    pm.getApplicationLabel(info).toString()
                } catch (_: Exception) {
                    pkg.substringAfterLast('.')
                }
                result.add(AppBatteryUsageInfo(pkg, label, timeMs, pct))
            }
        }

        return result.sortedByDescending { it.batteryPct }.take(10)
    }

    override fun priority(): Int = 10

    override fun tickIntervalMs(): Int = ctx?.let { Prefs.getInt(it, "bat_interval", 10000) } ?: 10000

    override fun start(ctx: Context, sys: SystemAccess) {
        this.ctx = ctx
        this.sys = sys
        designCap = sys.readDesignCapacity(ctx)

        // Initialize screen tracking
        onAccMs = Prefs.getLong(ctx, "scr_on_acc", 0)
        offAccMs = 0
        onDrain = 0f
        offDrain = 0f
        periodStart = android.os.SystemClock.elapsedRealtime()
        periodStartLevel = level

        rcv = object : BroadcastReceiver() {
            override fun onReceive(context: Context, i: Intent) {
                when(i.action) {
                    Intent.ACTION_BATTERY_CHANGED -> {
                        level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
                        temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
                        voltage = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
                        health = i.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)
                        status = i.getIntExtra(BatteryManager.EXTRA_STATUS, 0)
                        plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        val now = android.os.SystemClock.elapsedRealtime()
                        val dt = now - periodStart
                        val drain = abs(periodStartLevel - level).toFloat()
                        onAccMs += dt
                        onDrain += drain
                        screenOn = false
                        periodStart = now
                        periodStartLevel = level
                        Prefs.setLong(ctx, "scr_on_acc", onAccMs)
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        val now = android.os.SystemClock.elapsedRealtime()
                        val dt = now - periodStart
                        val drain = abs(periodStartLevel - level).toFloat()
                        offAccMs += dt
                        offDrain += drain
                        screenOn = true
                        periodStart = now
                        periodStartLevel = level
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ctx.registerReceiver(rcv, filter)

        val sticky = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        sticky?.let { i ->
            level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
            temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
            voltage = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
            health = i.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)
            status = i.getIntExtra(BatteryManager.EXTRA_STATUS, 0)
            plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        }
        periodStartLevel = level

        running = true
    }

    override fun stop() {
        if (rcv != null && ctx != null) {
            try {
                ctx?.unregisterReceiver(rcv!!)
            } catch (ignored: Exception) {
            }
        }
        rcv = null
        running = false
    }

    override fun tick() {
        sys?.let { s ->
            ctx?.let { c ->
                currentMa = s.readBatteryCurrentMa(c)
                actualCap = s.readActualCapacity()
                cycleCount = s.readCycleCount()
                realHealthPct = s.readRealHealthPercentage(c)
                technology = s.readBatteryTechnology()
                cpuTemp = s.readCpuTemp()

                if (actualCap > 0) {
                    designCap = actualCap
                }

                // --- Screen tracking tick logic ---
                val today = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
                val lastDay = Prefs.getInt(c, "scr_last_day", -1)
                if (lastDay != -1 && lastDay != today) {
                    Prefs.setLong(c, "scr_yesterday_on", onAccMs)
                    onAccMs = 0
                    offAccMs = 0
                    onDrain = 0f
                    offDrain = 0f
                    periodStart = android.os.SystemClock.elapsedRealtime()
                    periodStartLevel = level
                    Prefs.setLong(c, "scr_on_acc", 0)
                    Prefs.setBool(c, "scr_alert_fired", false)
                }
                Prefs.setInt(c, "scr_last_day", today)

                // --- Charge Limiter Logic ---
                if (s.rootProvider != SystemAccess.RootProvider.NONE) {
                    val limitEnabled = Prefs.getBool(c, "bat_charge_limit_en", false)
                    val limitVal = Prefs.getInt(c, "bat_charge_limit_val", 80)
                    if (limitEnabled) {
                        if (level >= limitVal && isCharging()) {
                            s.setChargingEnabled(false)
                        } else if (level < limitVal - 5 && !isCharging() && plugged > 0) {
                            s.setChargingEnabled(true)
                        }
                    }
                }
            }
        }
    }

    override fun compact(): String = ctx?.getString(R.string.battery_module_compact_text, level, timeLeft(), Fmt.duration(getTotalOn())) ?: ""

    override fun detail(): String {
        val c = ctx ?: return ""
        val ma = abs(currentMa)
        val w = ma * voltage / 1_000_000f
        val t = temp / 10f

        val sb = StringBuilder()
        sb.append(c.getString(R.string.battery_module_detail_line_1, level, ma, w, Fmt.temp(t)))

        if (sys?.isEnhanced() == true && realHealthPct > 0 && cycleCount >= 0) {
            val design = sys?.readDesignCapacity(ctx!!) ?: 0
            sb.append(c.getString(R.string.battery_module_health_enhanced, realHealthPct, if (actualCap > 0) actualCap.toString() else c.getString(R.string.battery_module_not_available), design, cycleCount))
        } else {
            sb.append(c.getString(R.string.battery_module_health_basic, healthStr(), voltage / 1000f, statusStr()))
        }

        if (sys?.isEnhanced() == true && technology != null) {
            sb.append(c.getString(R.string.battery_module_tech_info, voltage / 1000f, technology, statusStr()))
        }

        sb.append("   ").append(timeLeft())
        val remMah = sys?.readRemainingCapacity(c) ?: -1
        if (remMah > 0) {
            sb.append(" • ").append(remMah).append(" mAh")
        }
        if (isCharging()) {
            sb.append(c.getString(R.string.battery_module_time_left_separator)).append(chargeType())
        }
        sb.append("\n")

        // Screen Time Info
        val on = getTotalOn()
        val off = getTotalOff()
        sb.append(c.getString(R.string.battery_module_screen_on_label, Fmt.duration(on)))

        if (c != null && Prefs.getBool(c, "scr_show_drain", true)) {
            val curDrain = if (screenOn) abs(periodStartLevel - level).toFloat() else 0f
            val totalOnDrain = onDrain + curDrain
            val onDrainStr = String.format(Locale.US, "%.1f", totalOnDrain)
            val offDrainStr = String.format(Locale.US, "%.1f", offDrain)

            sb.append(c.getString(R.string.battery_module_screen_on_drain, onDrainStr))
            sb.append(c.getString(R.string.battery_module_screen_off_label, Fmt.duration(off), offDrainStr))

            if (on > 60000) {
                val rateOn = totalOnDrain / (on / 3600000f)
                val rateStr = String.format(Locale.US, "%.1f", rateOn)
                sb.append(c.getString(R.string.battery_module_active_drain, rateStr))
            }
        } else {
            sb.append(c.getString(R.string.battery_module_screen_off_basic, Fmt.duration(off)))
        }

        if (c != null && Prefs.getBool(c, "scr_show_yesterday", true)) {
            val yOn = Prefs.getLong(c, "scr_yesterday_on", 0)
            if (yOn > 0) {
                val diff = on - yOn
                val pct = (diff * 100 / yOn).toInt()
                val cmp = if (pct <= 0) c.getString(R.string.battery_module_yesterday_comparison_good, abs(pct)) else c.getString(R.string.battery_module_yesterday_comparison_bad, pct)
                sb.append(c.getString(R.string.battery_module_yesterday_label, Fmt.duration(yOn), cmp))
            }
        }

        return sb.toString()
    }

    override fun dataPoints(): LinkedHashMap<String, String> {
        val d = LinkedHashMap<String, String>()
        val c = ctx ?: return d
        val ma = abs(currentMa)
        val w = ma * voltage / 1_000_000f
        val t = temp / 10f

        d["battery.level"] = "$level%"

        if (isCharging()) {
            d["battery.charge_current"] = "$ma mA"
            d["battery.charger_type"] = pluggedType()
            d["battery.charge_speed"] = chargeType()
        } else {
            d["battery.discharge_rate"] = "$ma mA"
        }

        d["battery.power"] = String.format(Locale.US, "%.1f W", w)
        d["battery.temp"] = Fmt.temp(t)
        d["battery.voltage"] = String.format(Locale.US, "%.2fV", voltage / 1000f)

        val remMah = sys?.readRemainingCapacity(c) ?: -1
        if (remMah > 0) {
            d["battery.remaining_capacity"] = "$remMah mAh"
        }

        d["battery.health"] = healthStr()
        d["battery.status"] = statusStr()
        d["battery.time_left"] = timeLeft()

        d["battery.design_capacity"] = if (sys != null && ctx != null) "${sys?.readDesignCapacity(ctx!!)} mAh" else "$designCap mAh"
        d["battery.technology"] = technology ?: c.getString(R.string.battery_module_not_available)
        d["battery.cycle_count"] = if (cycleCount >= 0) cycleCount.toString() else c.getString(R.string.battery_module_not_available)
        d["battery.real_health_percentage"] = if (realHealthPct > 0) "$realHealthPct%" else c.getString(R.string.battery_module_not_available)
        d["battery.actual_capacity"] = if (actualCap > 0) "$actualCap mAh" else c.getString(R.string.battery_module_not_available)

        d["screen.on_time"] = Fmt.duration(getTotalOn())
        d["screen.off_time"] = Fmt.duration(getTotalOff())

        return d
    }

    override fun checkAlerts(ctx: Context) {
        val lowEnabled = Prefs.getBool(ctx, "bat_low_alert", true)
        val lowThresh = Prefs.getInt(ctx, "bat_low_thresh", 15)
        val lowFired = Prefs.getBool(ctx, "bat_low_fired", false)

        if (lowEnabled && level <= lowThresh && !lowFired && !isCharging()) {
            fireAlert(ctx, 2001, ctx.getString(R.string.battery_module_low_alert_title), ctx.getString(R.string.battery_module_low_alert_content, level))
            vibrate(ctx, longArrayOf(0, 300, 100, 300))
            Prefs.setBool(ctx, "bat_low_fired", true)
        }
        if (lowFired && level > lowThresh + 5) {
            Prefs.setBool(ctx, "bat_low_fired", false)
        }

        val tempEnabled = Prefs.getBool(ctx, "bat_temp_alert", true)
        val tempThresh = Prefs.getInt(ctx, "bat_temp_thresh", 42)
        val tempFired = Prefs.getBool(ctx, "bat_temp_fired", false)
        val currentTemp = temp / 10f

        if (tempEnabled && currentTemp >= tempThresh.toFloat() && !tempFired) {
            fireAlert(ctx, 2002, ctx.getString(R.string.battery_module_high_temp_alert_title), ctx.getString(R.string.battery_module_high_temp_alert_content, Fmt.temp(currentTemp)))
            vibrate(ctx, longArrayOf(0, 500, 200, 500))
            Prefs.setBool(ctx, "bat_temp_fired", true)
        }
        if (tempFired && currentTemp < tempThresh - 3) {
            Prefs.setBool(ctx, "bat_temp_fired", false)
        }

        val limitMin = Prefs.getInt(ctx, "scr_time_limit", 0)
        if (limitMin > 0) {
            val fired = Prefs.getBool(ctx, "scr_alert_fired", false)
            val onMs = getTotalOn()
            val limitMs = limitMin * 60000L

            if (onMs >= limitMs && !fired) {
                fireAlert(ctx, 2004, ctx.getString(R.string.battery_module_screen_time_limit_title), ctx.getString(R.string.battery_module_screen_time_limit_content, Fmt.duration(onMs)))
                Prefs.setBool(ctx, "scr_alert_fired", true)
            }
        }

        if (Prefs.getBool(ctx, "bat_smart_alerts", true) && !isCharging()) {
            val db = AppDatabase.getDatabase(ctx)
            val scope = CoroutineScope(Dispatchers.IO)
            scope.launch {
                val fifteenMinsAgo = System.currentTimeMillis() - (15 * 60 * 1000)
                val history = db.moduleDataDao().getHistoryList(key(), fifteenMinsAgo)
                if (history.size > 2) {
                    val firstPoint = history.first().data["battery.level"]?.removeSuffix("%")?.toIntOrNull() ?: return@launch
                    val currentLevel = level
                    val drop = firstPoint - currentLevel
                    val lastFired = Prefs.getLong(ctx, "bat_abnormal_last_fired", 0L)
                    val now = System.currentTimeMillis()

                    if (drop >= 5 && (now - lastFired) > 30 * 60 * 1000) {
                        fireAlert(ctx, 2003, ctx.getString(R.string.battery_module_abnormal_discharge_title), ctx.getString(R.string.battery_module_abnormal_discharge_content, drop))
                        Prefs.setLong(ctx, "bat_abnormal_last_fired", now)
                    }
                }
            }
        }
    }

    fun getLevel(): Int = level

    fun isFull(): Boolean = status == BatteryManager.BATTERY_STATUS_FULL || level >= 100

    fun isCharging(): Boolean = status == BatteryManager.BATTERY_STATUS_CHARGING

    fun getCurrentMa(): Int = currentMa

    fun getVoltage(): Int = voltage

    fun getTemp(): Int = temp

    fun getTimeLeft(): String = timeLeft()

    fun getOnDrain(): Float = onDrain

    fun getOffDrain(): Float = offDrain

    fun getOnAccMs(): Long = onAccMs

    fun getOffAccMs(): Long = offAccMs

    fun getPeriodStartLevel(): Int = periodStartLevel

    fun getPeriodStart(): Long = periodStart

    fun isScreenOn(): Boolean = screenOn

    fun getTotalOn(): Long {
        val now = android.os.SystemClock.elapsedRealtime()
        return onAccMs + if (screenOn) (now - periodStart) else 0
    }

    fun getTotalOff(): Long {
        val now = android.os.SystemClock.elapsedRealtime()
        return offAccMs + if (!screenOn) (now - periodStart) else 0
    }

    private fun timeLeft(): String {
        val c = ctx ?: return ctx?.getString(R.string.battery_module_not_available) ?: ""
        val ma = abs(currentMa)
        if (ma < 5) return c.getString(R.string.battery_module_not_available)
        val capacity = if (actualCap > 0) actualCap else designCap
        return if (isCharging()) {
            val neededMah = (100 - level) / 100f * capacity
            val hrs = neededMah / ma
            c.getString(R.string.battery_module_full_in, formatHours(hrs))
        } else {
            val remMah = level / 100f * capacity
            val hrs = remMah / ma
            c.getString(R.string.battery_module_time_left, formatHours(hrs))
        }
    }

    private fun formatHours(hrs: Float): String {
        val c = ctx ?: return ""
        val m_hrs = if (hrs < 0) 0f else hrs
        val d = (m_hrs / 24).toInt()
        val h = (m_hrs % 24).toInt()
        val m = ((m_hrs * 60) % 60).toInt()
        return when {
            d > 0 -> String.format(Locale.US, c.getString(R.string.battery_module_hours_format_days), d, h)
            h > 0 -> String.format(Locale.US, c.getString(R.string.battery_module_hours_format_hours), h, m)
            else -> String.format(Locale.US, c.getString(R.string.battery_module_hours_format_minutes), m)
        }
    }

    private fun pluggedType(): String {
        val c = ctx ?: return ""
        return when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> c.getString(R.string.battery_module_plugged_ac)
            BatteryManager.BATTERY_PLUGGED_USB -> c.getString(R.string.battery_module_plugged_usb)
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> c.getString(R.string.battery_module_plugged_wireless)
            else -> c.getString(R.string.battery_module_not_available)
        }
    }

    private fun chargeType(): String {
        val c = ctx ?: return ""
        val ma = abs(currentMa)
        return when {
            ma > 3000 -> c.getString(R.string.battery_charge_type_rapid)
            ma > 1500 -> c.getString(R.string.battery_charge_type_fast)
            ma > 500 -> c.getString(R.string.battery_charge_type_normal)
            else -> c.getString(R.string.battery_charge_type_slow)
        }
    }

    private fun healthStr(): String {
        val c = ctx ?: return ""
        return when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> c.getString(R.string.battery_health_good)
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> c.getString(R.string.battery_health_overheat)
            BatteryManager.BATTERY_HEALTH_DEAD -> c.getString(R.string.battery_health_dead)
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> c.getString(R.string.battery_health_over_voltage)
            BatteryManager.BATTERY_HEALTH_COLD -> c.getString(R.string.battery_health_cold)
            else -> c.getString(R.string.battery_health_unknown)
        }
    }

    private fun statusStr(): String {
        val c = ctx ?: return ""
        return when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> c.getString(R.string.battery_status_charging)
            BatteryManager.BATTERY_STATUS_DISCHARGING -> c.getString(R.string.battery_status_discharging)
            BatteryManager.BATTERY_STATUS_FULL -> c.getString(R.string.battery_status_full)
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> c.getString(R.string.battery_status_not_charging)
            else -> c.getString(R.string.battery_status_unknown)
        }
    }

    private fun fireAlert(c: Context, id: Int, title: String, body: String) {
        try {
            val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val b = NotificationCompat.Builder(c, "ebox_alerts")
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
            nm.notify(id, b.build())
        } catch (ignored: Exception) {
        }
    }
}
