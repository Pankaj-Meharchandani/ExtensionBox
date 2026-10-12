package com.extensionbox.app.modules

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import com.extensionbox.app.Fmt
import com.extensionbox.app.Prefs
import com.extensionbox.app.R
import com.extensionbox.app.SystemAccess
import com.extensionbox.app.db.AppDatabase
import com.extensionbox.app.ui.components.SettingSlider
import com.extensionbox.app.ui.components.SettingSwitch
import com.extensionbox.app.ui.components.Sparkline
import com.extensionbox.app.ui.components.extractPoints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap
import java.util.Locale

class ThermalModule : Module {

    private var ctx: Context? = null
    private var sys: SystemAccess? = null
    private var rcv: BroadcastReceiver? = null
    private var running = false

    private var batteryTempC = Float.NaN
    private var cpuTempC = Float.NaN
    private var thermalZones = mapOf<String, Float>()

    override fun key(): String = "thermal"
    override fun name(): String = ctx?.getString(R.string.thermal_module_name) ?: "Thermals"
    override fun description(): String = ctx?.getString(R.string.thermal_module_description) ?: "Device & battery temperatures and thermal zones"
    override fun defaultEnabled(): Boolean = true
    override fun alive(): Boolean = running
    override fun hasSettings(): Boolean = true
    override fun priority(): Int = 12

    override fun tickIntervalMs(): Int = ctx?.let { Prefs.getInt(it, "thermal_interval", 5000) } ?: 5000

    override fun start(ctx: Context, sys: SystemAccess) {
        this.ctx = ctx
        this.sys = sys

        rcv = object : BroadcastReceiver() {
            override fun onReceive(context: Context, i: Intent) {
                if (i.action == Intent.ACTION_BATTERY_CHANGED) {
                    val rawTemp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
                    if (rawTemp > 0) {
                        batteryTempC = rawTemp / 10f
                    }
                }
            }
        }
        ctx.registerReceiver(rcv, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val sticky = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        sticky?.let { i ->
            val rawTemp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            if (rawTemp > 0) {
                batteryTempC = rawTemp / 10f
            }
        }

        tick()
        running = true
    }

    override fun stop() {
        if (rcv != null && ctx != null) {
            try {
                ctx?.unregisterReceiver(rcv!!)
            } catch (ignored: Exception) {}
        }
        rcv = null
        running = false
    }

    override fun tick() {
        sys?.let { s ->
            val cpuT = s.readCpuTemp()
            if (!cpuT.isNaN()) {
                cpuTempC = cpuT
            }
            val zones = s.getThermalData()
            if (zones.isNotEmpty()) {
                thermalZones = zones
            }
        }
    }

    override fun compact(): String {
        val batStr = if (!batteryTempC.isNaN()) Fmt.temp(batteryTempC) else "--"
        val cpuStr = if (!cpuTempC.isNaN()) Fmt.temp(cpuTempC) else "--"
        return "Bat: $batStr • CPU: $cpuStr"
    }

    override fun detail(): String {
        val sb = StringBuilder()
        sb.append("Battery Temp: ").append(if (!batteryTempC.isNaN()) Fmt.temp(batteryTempC) else "N/A").append("\n")
        sb.append("CPU Temp: ").append(if (!cpuTempC.isNaN()) Fmt.temp(cpuTempC) else "N/A").append("\n")

        if (thermalZones.isNotEmpty()) {
            sb.append("\nThermal Zones (${thermalZones.size}):\n")
            thermalZones.entries.take(8).forEach { (type, temp) ->
                sb.append("   • $type: ${Fmt.temp(temp)}\n")
            }
        }
        return sb.toString()
    }

    override fun dataPoints(): LinkedHashMap<String, String> {
        val d = LinkedHashMap<String, String>()
        d["thermal.battery_temp"] = if (!batteryTempC.isNaN()) Fmt.temp(batteryTempC) else "N/A"
        d["thermal.cpu_temp"] = if (!cpuTempC.isNaN()) Fmt.temp(cpuTempC) else "N/A"

        val maxTemp = maxOf(
            if (!batteryTempC.isNaN()) batteryTempC else 0f,
            if (!cpuTempC.isNaN()) cpuTempC else 0f,
            thermalZones.values.maxOrNull() ?: 0f
        )
        if (maxTemp > 0f) {
            d["thermal.max_temp"] = Fmt.temp(maxTemp)
            d["thermal.status"] = when {
                maxTemp >= 50f -> "Critical"
                maxTemp >= 42f -> "Warm"
                else -> "Normal"
            }
        }

        thermalZones.entries.take(10).forEach { (type, temp) ->
            val safeKey = type.lowercase(Locale.US).replace(Regex("[^a-z0-9_]"), "_")
            d["thermal.zone_$safeKey"] = Fmt.temp(temp)
        }
        return d
    }

    @Composable
    override fun composableContent(ctx: Context, sys: SystemAccess) {
        val maxTemp = maxOf(
            if (!batteryTempC.isNaN()) batteryTempC else 0f,
            if (!cpuTempC.isNaN()) cpuTempC else 0f,
            thermalZones.values.maxOrNull() ?: 0f
        )

        var historyPoints by remember { mutableStateOf<List<Float>>(emptyList()) }

        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                try {
                    val db = AppDatabase.getDatabase(ctx)
                    val oneHourAgo = System.currentTimeMillis() - (60 * 60 * 1000)
                    val history = db.moduleDataDao().getHistoryList("thermal", oneHourAgo)
                    historyPoints = extractPoints("thermal", history)
                } catch (_: Exception) {}
            }
        }

        val statusColor = when {
            maxTemp >= 48f -> Color(0xFFFF1744)
            maxTemp >= 42f -> Color(0xFFFF9100)
            else -> Color(0xFF00E676)
        }

        val statusText = when {
            maxTemp >= 48f -> "HIGH HEAT / THROTTLING"
            maxTemp >= 42f -> "WARM - ELEVATED TEMP"
            maxTemp > 0f -> "OPTIMAL THERMAL STATE"
            else -> "MONITORING SENSORS"
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Thermal & Temperatures Breakdown",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            // 3 Metric Cards Row (matching ScreenTimeModule)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Battery Temp",
                    value = if (!batteryTempC.isNaN()) Fmt.temp(batteryTempC) else "N/A",
                    color = if (!batteryTempC.isNaN() && batteryTempC >= 42f) Color(0xFFFF9100) else Color(0xFF00E676)
                )
                MetricCard(
                    modifier = Modifier.weight(1f),
                    title = "CPU / SoC",
                    value = if (!cpuTempC.isNaN()) Fmt.temp(cpuTempC) else "N/A",
                    color = if (!cpuTempC.isNaN() && cpuTempC >= 45f) Color(0xFFFF9100) else MaterialTheme.colorScheme.primary
                )
                MetricCard(
                    modifier = Modifier.weight(1f),
                    title = "Peak Zone",
                    value = if (maxTemp > 0f) Fmt.temp(maxTemp) else "N/A",
                    color = statusColor
                )
            }

            // Status Badge Banner
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = statusColor.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, statusColor.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "• ",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Black,
                        color = statusColor
                    )
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = statusColor
                    )
                }
            }

            // Thermal History Trend Chart Card
            if (historyPoints.size >= 2) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Thermal History Trend",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(8.dp))
                        Box(
                            modifier = Modifier.fillMaxWidth().height(90.dp)
                        ) {
                            Sparkline(
                                points = historyPoints,
                                modifier = Modifier.fillMaxSize(),
                                color = statusColor,
                                strokeWidth = 2.dp,
                                fillGradient = true,
                                animate = true
                            )
                        }
                    }
                }
            }

            // Hardware Thermal Zones List
            if (thermalZones.isNotEmpty()) {
                Text(
                    text = "Hardware Thermal Sensors (${thermalZones.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                val zoneList = remember(thermalZones) {
                    thermalZones.entries.sortedByDescending { it.value }
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    zoneList.take(14).forEach { (type, temp) ->
                        val barColor = when {
                            temp >= 48f -> Color(0xFFFF1744)
                            temp >= 42f -> Color(0xFFFF9100)
                            else -> MaterialTheme.colorScheme.primary
                        }

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = type,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = Fmt.temp(temp),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = barColor
                                    )
                                }

                                Spacer(Modifier.height(6.dp))

                                LinearProgressIndicator(
                                    progress = { (temp / 80f).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                    color = barColor,
                                    trackColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                                )
                            }
                        }
                    }
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
    override fun settingsContent(ctx: Context, sys: SystemAccess) {
        var interval by remember { mutableStateOf(Prefs.getInt(ctx, "thermal_interval", 5000).toFloat()) }

        Column {
            SettingSlider(
                label = ctx.getString(R.string.thermal_module_update_interval),
                value = interval,
                valueRange = 1000f..30000f,
                steps = 28,
                onValueChange = {
                    interval = it
                    Prefs.setInt(ctx, "thermal_interval", it.toInt())
                },
                formatter = { "${it.toInt() / 1000}s" }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            var tempAlert by remember { mutableStateOf(Prefs.getBool(ctx, "thermal_temp_alert", Prefs.getBool(ctx, "bat_temp_alert", true))) }
            SettingSwitch(
                label = ctx.getString(R.string.thermal_module_high_temp_alert),
                checked = tempAlert,
                onCheckedChange = {
                    tempAlert = it
                    Prefs.setBool(ctx, "thermal_temp_alert", it)
                    Prefs.setBool(ctx, "bat_temp_alert", it)
                }
            )

            if (tempAlert) {
                var tempThresh by remember { mutableStateOf(Prefs.getInt(ctx, "thermal_temp_thresh", Prefs.getInt(ctx, "bat_temp_thresh", 42)).toFloat()) }
                SettingSlider(
                    label = ctx.getString(R.string.thermal_module_temp_threshold),
                    value = tempThresh,
                    valueRange = 30f..60f,
                    onValueChange = {
                        tempThresh = it
                        Prefs.setInt(ctx, "thermal_temp_thresh", it.toInt())
                        Prefs.setInt(ctx, "bat_temp_thresh", it.toInt())
                    },
                    formatter = { ctx.getString(R.string.thermal_module_temp_formatter, it.toInt()) }
                )
            }
        }
    }

    override fun checkAlerts(ctx: Context) {
        val tempEnabled = Prefs.getBool(ctx, "thermal_temp_alert", Prefs.getBool(ctx, "bat_temp_alert", true))
        val tempThresh = Prefs.getInt(ctx, "thermal_temp_thresh", Prefs.getInt(ctx, "bat_temp_thresh", 42))
        val tempFired = Prefs.getBool(ctx, "thermal_temp_fired", false)

        val activeTemp = maxOf(
            if (!batteryTempC.isNaN()) batteryTempC else 0f,
            if (!cpuTempC.isNaN()) cpuTempC else 0f
        )

        if (tempEnabled && activeTemp >= tempThresh.toFloat() && !tempFired) {
            fireAlert(ctx, 2002, ctx.getString(R.string.thermal_module_high_temp_alert_title), ctx.getString(R.string.thermal_module_high_temp_alert_content, Fmt.temp(activeTemp)))
            vibrate(ctx, longArrayOf(0, 500, 200, 500))
            Prefs.setBool(ctx, "thermal_temp_fired", true)
        }
        if (tempFired && activeTemp < tempThresh - 3) {
            Prefs.setBool(ctx, "thermal_temp_fired", false)
        }
    }

    fun getBatteryTemp(): Float = batteryTempC
    fun getCpuTemp(): Float = cpuTempC

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
        } catch (ignored: Exception) {}
    }
}
