package com.extensionbox.app.modules

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import com.extensionbox.app.Fmt
import com.extensionbox.app.Prefs
import com.extensionbox.app.R
import com.extensionbox.app.SystemAccess
import java.util.LinkedHashMap

class RamModule : Module {
    private var ctx: Context? = null
    private var sys: SystemAccess? = null
    private var running = false

    private var ramUsed: Long = 0
    private var ramTotal: Long = 0
    private var ramAvail: Long = 0
    private var topProcs = listOf<Triple<String, String, String>>()

    override fun key(): String = "ram"
    override fun name(): String = "RAM"
    override fun emoji(): String = "🧠"
    override fun description(): String = "Memory status and running processes"
    override fun defaultEnabled(): Boolean = true
    override fun alive(): Boolean = running
    override fun priority(): Int = 16

    override fun tickIntervalMs(): Int = ctx?.let { Prefs.getInt(it, "ram_interval", 5000) } ?: 5000

    override fun start(ctx: Context, sys: SystemAccess) {
        this.ctx = ctx
        this.sys = sys
        running = true
        tick()
    }

    override fun stop() {
        running = false
        sys = null
    }

    override fun tick() {
        try {
            val am = ctx?.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            ramTotal = mi.totalMem
            ramAvail = mi.availMem
            ramUsed = ramTotal - ramAvail
            
            sys?.let { s ->
                topProcs = s.getRunningProcesses()
            }
        } catch (ignored: Exception) {
        }
    }

    override fun compact(): String {
        val ramPct = if (ramTotal > 0) ramUsed * 100f / ramTotal else 0f
        return "RAM: ${ramPct.toInt()}% (${Fmt.bytes(ramUsed)})"
    }

    override fun detail(): String {
        val ramPct = if (ramTotal > 0) ramUsed * 100f / ramTotal else 0f
        val sb = StringBuilder()
        sb.append("🧠 RAM Usage: ${ramPct.toInt()}% (${Fmt.bytes(ramUsed)} / ${Fmt.bytes(ramTotal)})\n")
        sb.append("   Available: ${Fmt.bytes(ramAvail)}\n")
        
        if (topProcs.isNotEmpty()) {
            sb.append("\n🔝 Top Processes (CPU / RAM):\n")
            topProcs.forEach { (name, cpu, mem) ->
                sb.append("   • ${name.take(15).padEnd(16)} $cpu / $mem\n")
            }
        }
        
        return sb.toString()
    }

    override fun dataPoints(): LinkedHashMap<String, String> {
        val d = LinkedHashMap<String, String>()
        val ramPct = if (ramTotal > 0) ramUsed * 100f / ramTotal else 0f
        d["ram.used"] = Fmt.bytes(ramUsed)
        d["ram.total"] = Fmt.bytes(ramTotal)
        d["ram.available"] = Fmt.bytes(ramAvail)
        d["ram.percentage"] = "${ramPct.toInt()}%"
        return d
    }

    @androidx.compose.runtime.Composable
    override fun composableContent(ctx: android.content.Context, sys: com.extensionbox.app.SystemAccess) {
        androidx.compose.material3.Text(
            "Top 5 Processes",
            style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
            color = androidx.compose.material3.MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            topProcs.take(5).forEach { (name, cpu, mem) ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    androidx.compose.material3.Text(name.take(15), style = androidx.compose.material3.MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    androidx.compose.material3.Text(cpu, style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = androidx.compose.material3.MaterialTheme.colorScheme.secondary)
                    Spacer(modifier = Modifier.width(8.dp))
                    androidx.compose.material3.Text(mem, style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = androidx.compose.material3.MaterialTheme.colorScheme.tertiary)
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    override fun settingsContent(ctx: android.content.Context, sys: com.extensionbox.app.SystemAccess) {
        var interval by remember { 
            mutableStateOf(Prefs.getInt(ctx, "ram_interval", 5000).toFloat()) 
        }
        var alertOn by remember { 
            mutableStateOf(Prefs.getBool(ctx, "cpu_ram_alert", false)) 
        }

        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Refresh Interval", style = MaterialTheme.typography.bodyMedium)
                Text("${interval.toInt()}ms", color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value = interval,
                onValueChange = { 
                    interval = it
                    Prefs.setInt(ctx, "ram_interval", it.toInt())
                },
                valueRange = 1000f..10000f,
                steps = 8
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("RAM Usage Alert", style = MaterialTheme.typography.bodyMedium)
                    Text("Notify when usage is high", style = MaterialTheme.typography.labelSmall)
                }
                Switch(
                    checked = alertOn,
                    onCheckedChange = {
                        alertOn = it
                        Prefs.setBool(ctx, "cpu_ram_alert", it)
                    }
                )
            }
        }
    }

    override fun checkAlerts(ctx: Context) {
        val alertOn = Prefs.getBool(ctx, "cpu_ram_alert", false)
        val thresh = Prefs.getInt(ctx, "cpu_ram_thresh", 90)
        val fired = Prefs.getBool(ctx, "cpu_ram_alert_fired", false)
        val ramPct = if (ramTotal > 0) ramUsed * 100f / ramTotal else 0f

        if (alertOn && ramPct >= thresh && !fired) {
            try {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(2003, NotificationCompat.Builder(ctx, "ebox_alerts")
                    .setSmallIcon(R.drawable.ic_notif)
                    .setContentTitle("🔴 High RAM Usage")
                    .setContentText("RAM at ${ramPct.toInt()}%")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true).build())
            } catch (ignored: Exception) {
            }
            Prefs.setBool(ctx, "cpu_ram_alert_fired", true)
        }
        if (fired && ramPct < thresh - 5) {
            Prefs.setBool(ctx, "cpu_ram_alert_fired", false)
        }
    }
}
