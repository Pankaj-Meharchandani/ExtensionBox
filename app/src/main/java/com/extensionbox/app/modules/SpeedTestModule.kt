package com.extensionbox.app.modules

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.extensionbox.app.Fmt
import com.extensionbox.app.Prefs
import com.extensionbox.app.R
import com.extensionbox.app.SystemAccess
import com.extensionbox.app.ui.components.SettingSlider
import com.extensionbox.app.ui.components.SettingSwitch
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

data class SpeedTestRecord(
    val timestamp: Long,
    val dateFormatted: String,
    val networkType: String,
    val pingMs: String,
    val downloadSpeed: String,
    val uploadSpeed: String
)

class SpeedTestModule : Module {

    private var ctx: Context? = null
    @Volatile private var running = false
    @Volatile private var testing = false
    @Volatile private var retryAfter = 0L

    // Owned by the module so an in-flight test is cancelled when the module is stopped
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    @Volatile private var downKbps = 0L
    @Volatile private var upKbps = 0L
    @Volatile private var pingMs = 0L
    @Volatile private var lastTestTime = 0L

    override fun key(): String = "speedtest"
    override fun name(): String = ctx?.getString(R.string.speed_test_module_name) ?: "Speed Test"
    override fun description(): String = ctx?.getString(R.string.speed_test_module_description) ?: "Periodic download and upload speed test"
    override fun defaultEnabled(): Boolean = false
    override fun alive(): Boolean = running
    override fun priority(): Int = 95
    override fun hasSettings(): Boolean = true

    override fun tickIntervalMs(): Int = ctx?.let { Prefs.getInt(it, "spd_interval", 60000) } ?: 60000

    override fun start(ctx: Context, sys: SystemAccess) {
        this.ctx = ctx
        running = true
    }

    override fun stop() {
        running = false
        scope.coroutineContext[Job]?.cancelChildren()
    }

    override fun tick() {
        val c = ctx ?: return
        if (testing || System.currentTimeMillis() < retryAfter) return

        val auto = Prefs.getBool(c, "spd_auto_test", true)
        if (!auto) return

        val now = System.currentTimeMillis()
        val freqMin = Prefs.getInt(c, "spd_test_freq", 60)
        if (now - lastTestTime < freqMin * 60 * 1000L) return

        // Daily limit check
        val testsToday = Prefs.getInt(c, "spd_tests_today", 0)
        val limit = Prefs.getInt(c, "spd_daily_limit", 10)
        if (testsToday >= limit) return

        // WiFi only check
        if (Prefs.getBool(c, "spd_wifi_only", true)) {
            if (!isWifiConnected(c)) return
        }

        runTest()
    }

    private companion object {
        const val DOWN_URL = "https://speed.cloudflare.com/__down?bytes="
        const val UP_URL = "https://speed.cloudflare.com/__up"
        const val DOWN_BYTES = 10_000_000
        const val UP_BYTES = 2_000_000
        const val PING_SAMPLES = 3
        const val PHASE_MAX_MS = 4_000L
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 8_000
        const val RETRY_BACKOFF_MS = 5 * 60 * 1000L
    }

    private fun runTest() {
        testing = true
        scope.launch {
            try {
                val ping = measurePing()
                val downBps = measureDownload()
                val upBps = try { measureUpload() } catch (e: IOException) { 0L }

                pingMs = ping
                downKbps = downBps / 128
                upKbps = upBps / 128
                lastTestTime = System.currentTimeMillis()
                ctx?.let { 
                    val count = Prefs.getInt(it, "spd_tests_today", 0)
                    Prefs.setInt(it, "spd_tests_today", count + 1)
                    saveSpeedTestRecord(it, pingMs, downKbps, upKbps)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                retryAfter = System.currentTimeMillis() + RETRY_BACKOFF_MS
            } finally {
                testing = false
            }
        }
    }

    private fun runOnDemandTest(
        context: Context,
        onProgress: (stage: String, ping: Long, downKbps: Long, upKbps: Long) -> Unit
    ) {
        var p = 0L
        var d = 0L
        var u = 0L

        try {
            onProgress("Ping", p, d, u)
            p = measurePing()
            onProgress("Download", p, d, u)

            val downBps = measureDownload()
            d = downBps / 128
            onProgress("Upload", p, d, u)

            val upBps = try { measureUpload() } catch (e: Exception) { 0L }
            u = upBps / 128

            pingMs = p
            downKbps = d
            upKbps = u
            lastTestTime = System.currentTimeMillis()

            saveSpeedTestRecord(context, p, d, u)
            onProgress("Completed", p, d, u)
        } catch (e: Exception) {
            onProgress("Error", p, d, u)
        }
    }

    private fun saveSpeedTestRecord(context: Context, ping: Long, down: Long, up: Long) {
        try {
            val historyJson = Prefs.getString(context, "speedtest_results_history", "[]") ?: "[]"
            val array = JSONArray(historyJson)
            val now = System.currentTimeMillis()
            val sdf = SimpleDateFormat("MMM dd, yyyy, hh:mm a", Locale.US)

            val obj = JSONObject()
            obj.put("timestamp", now)
            obj.put("dateFormatted", sdf.format(Date(now)))
            obj.put("networkType", getNetworkName(context))
            obj.put("pingMs", "${ping}ms")
            obj.put("downloadSpeed", Fmt.speed(down * 128))
            obj.put("uploadSpeed", Fmt.speed(up * 128))

            val newArray = JSONArray()
            newArray.put(obj)
            for (i in 0 until array.length().coerceAtMost(25)) {
                newArray.put(array.get(i))
            }
            Prefs.setString(context, "speedtest_results_history", newArray.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun parseSpeedTestHistory(json: String): List<SpeedTestRecord> {
        val list = mutableListOf<SpeedTestRecord>()
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    SpeedTestRecord(
                        timestamp = obj.optLong("timestamp", 0L),
                        dateFormatted = obj.optString("dateFormatted", "Unknown Date"),
                        networkType = obj.optString("networkType", "Network"),
                        pingMs = obj.optString("pingMs", "--"),
                        downloadSpeed = obj.optString("downloadSpeed", "--"),
                        uploadSpeed = obj.optString("uploadSpeed", "--")
                    )
                )
            }
        } catch (e: Exception) {}
        return list
    }

    private fun getNetworkName(c: Context): String {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return "Offline"
        val caps = cm.getNetworkCapabilities(net) ?: return "Unknown"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi Network"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular (Mobile Data)"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Active Connection"
        }
    }

    private fun openConnection(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.useCaches = false
        conn.setRequestProperty("Cache-Control", "no-cache")
        return conn
    }

    private fun measurePing(): Long {
        var best = Long.MAX_VALUE
        repeat(PING_SAMPLES + 1) { i ->
            val conn = openConnection(DOWN_URL + "0")
            val t0 = System.nanoTime()
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            conn.inputStream.use { it.readBytes() }
            val ms = (System.nanoTime() - t0) / 1_000_000L
            if (i > 0 && ms < best) best = ms
        }
        return best
    }

    private fun measureDownload(): Long {
        val conn = openConnection(DOWN_URL + DOWN_BYTES)
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            val buf = ByteArray(32 * 1024)
            var total = 0L
            var startNs = 0L
            conn.inputStream.use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (startNs == 0L) {
                        startNs = System.nanoTime()
                    } else {
                        total += n
                        if (System.nanoTime() - startNs >= PHASE_MAX_MS * 1_000_000L) break
                    }
                }
            }
            val elapsedNs = System.nanoTime() - startNs
            if (startNs == 0L || total <= 0L || elapsedNs <= 0L) throw IOException("No data received")
            return total * 1_000_000_000L / elapsedNs
        } finally {
            conn.disconnect()
        }
    }

    private fun measureUpload(): Long {
        val payload = ByteArray(UP_BYTES).also { Random().nextBytes(it) }
        val conn = openConnection(UP_URL)
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(payload.size)
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.connect()
            val t0 = System.nanoTime()
            conn.outputStream.use { it.write(payload) }
            val code = conn.responseCode
            val elapsedNs = System.nanoTime() - t0
            if (code !in 200..299) throw IOException("HTTP $code")
            if (elapsedNs <= 0L) throw IOException("Invalid timing")
            return payload.size * 1_000_000_000L / elapsedNs
        } finally {
            conn.disconnect()
        }
    }

    private fun isWifiConnected(c: Context): Boolean {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(net)
        return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    override fun compact(): String = if (testing) ctx?.getString(R.string.speed_test_module_testing) ?: "" else ctx?.getString(R.string.speed_test_module_compact_text, Fmt.speed(downKbps * 128)) ?: ""

    override fun detail(): String {
        val c = ctx ?: return ""
        val sb = StringBuilder()
        sb.append(c.getString(R.string.speed_test_module_name))
        if (testing) sb.append(c.getString(R.string.speed_test_module_running))
        sb.append(c.getString(R.string.speed_test_module_download, Fmt.speed(downKbps * 128)))
        if (upKbps > 0) sb.append(c.getString(R.string.speed_test_module_upload, Fmt.speed(upKbps * 128)))
        
        if (c != null && Prefs.getBool(c, "spd_show_ping", true)) {
            sb.append(c.getString(R.string.speed_test_module_latency, pingMs))
        }
        
        if (lastTestTime > 0) {
            val ago = (System.currentTimeMillis() - lastTestTime) / 60000
            sb.append(c.getString(R.string.speed_test_module_last_test, ago))
        }
        
        return sb.toString()
    }

    override fun dataPoints(): LinkedHashMap<String, String> {
        val d = LinkedHashMap<String, String>()
        d["speedtest.download"] = Fmt.speed(downKbps * 128)
        if (upKbps > 0) d["speedtest.upload"] = Fmt.speed(upKbps * 128)
        d["speedtest.ping"] = "${pingMs}ms"
        return d
    }

    @androidx.compose.runtime.Composable
    override fun dashboardContent(ctx: Context, sys: SystemAccess) {
        composableContent(ctx, sys)
    }

    @androidx.compose.runtime.Composable
    override fun composableContent(ctx: Context, sys: SystemAccess) {
        val scope = rememberCoroutineScope()
        var isTestRunning by remember { mutableStateOf(false) }
        var currentPing by remember { mutableLongStateOf(pingMs) }
        var currentDown by remember { mutableLongStateOf(downKbps) }
        var currentUp by remember { mutableLongStateOf(upKbps) }
        var testStage by remember { mutableStateOf("Idle") }

        var historyJson by remember {
            mutableStateOf(Prefs.getString(ctx, "speedtest_results_history", "[]") ?: "[]")
        }
        val historyList = remember(historyJson, isTestRunning) {
            parseSpeedTestHistory(historyJson)
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // --- Main Gauge & Test Button ---
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = if (isTestRunning) "Stage: $testStage..." else "Speed Test",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )

                    // Live Gauge values
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        MetricGaugeItem("PING", if (currentPing > 0) "${currentPing}ms" else "--", MaterialTheme.colorScheme.tertiary)
                        MetricGaugeItem("DOWNLOAD", Fmt.speed(currentDown * 128), MaterialTheme.colorScheme.primary)
                        MetricGaugeItem("UPLOAD", Fmt.speed(currentUp * 128), MaterialTheme.colorScheme.secondary)
                    }

                    Button(
                        onClick = {
                            if (!isTestRunning) {
                                isTestRunning = true
                                testStage = "Measuring Ping"
                                scope.launch(Dispatchers.IO) {
                                    runOnDemandTest(ctx) { stage, p, d, u ->
                                        testStage = stage
                                        currentPing = p
                                        currentDown = d
                                        currentUp = u
                                    }
                                    isTestRunning = false
                                    historyJson = Prefs.getString(ctx, "speedtest_results_history", "[]") ?: "[]"
                                }
                            }
                        },
                        enabled = !isTestRunning,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        if (isTestRunning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.width(12.dp))
                            Text("TESTING SPEED...")
                        } else {
                            Icon(Icons.Default.Speed, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("START SPEED TEST")
                        }
                    }
                }
            }

            // --- Previous Test Results History ---
            if (historyList.isNotEmpty()) {
                Text(
                    text = "Previous Test Results",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    historyList.forEach { record ->
                        var expanded by remember { mutableStateOf(false) }

                        Surface(
                            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = record.dateFormatted,
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = record.networkType,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    Text(
                                        text = record.downloadSpeed,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )

                                    Icon(
                                        imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = "Expand",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 8.dp)
                                    )
                                }

                                if (expanded) {
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        DetailColumn("Ping Latency", record.pingMs)
                                        DetailColumn("Download", record.downloadSpeed)
                                        DetailColumn("Upload", record.uploadSpeed)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun MetricGaugeItem(label: String, value: String, color: Color) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = color)
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @androidx.compose.runtime.Composable
    private fun DetailColumn(label: String, value: String) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
    }

    @androidx.compose.runtime.Composable
    override fun settingsContent(ctx: android.content.Context, sys: com.extensionbox.app.SystemAccess) {
        var interval by remember { mutableStateOf(Prefs.getInt(ctx, "spd_interval", 60000).toFloat()) }
        
        Column {
            SettingSlider(
                label = ctx.getString(R.string.speed_test_module_update_interval),
                value = interval,
                onValueChange = {
                    interval = it
                    Prefs.setInt(ctx, "spd_interval", it.toInt())
                },
                valueRange = 10000f..300000f,
                formatter = { ctx.getString(R.string.speed_test_module_interval_formatter, it.toInt() / 1000) }
            )

            androidx.compose.material3.HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            var autoTest by remember { mutableStateOf(Prefs.getBool(ctx, "spd_auto_test", true)) }
            SettingSwitch(
                label = ctx.getString(R.string.speed_test_module_auto_test),
                checked = autoTest,
                onCheckedChange = {
                    autoTest = it
                    Prefs.setBool(ctx, "spd_auto_test", it)
                }
            )
            if (autoTest) {
                var freq by remember { mutableStateOf(Prefs.getInt(ctx, "spd_test_freq", 60).toFloat()) }
                SettingSlider(
                    label = ctx.getString(R.string.speed_test_module_test_frequency),
                    value = freq,
                    valueRange = 15f..240f,
                    steps = 15,
                    onValueChange = {
                        freq = it
                        Prefs.setInt(ctx, "spd_test_freq", it.toInt())
                    },
                    formatter = { ctx.getString(R.string.speed_test_module_frequency_formatter, it.toInt()) }
                )
            }
            var dailyLimit by remember { mutableStateOf(Prefs.getInt(ctx, "spd_daily_limit", 10).toFloat()) }
            SettingSlider(
                label = ctx.getString(R.string.speed_test_module_daily_test_limit),
                value = dailyLimit,
                valueRange = 1f..100f,
                onValueChange = {
                    dailyLimit = it
                    Prefs.setInt(ctx, "spd_daily_limit", it.toInt())
                },
                formatter = { ctx.getString(R.string.speed_test_module_limit_formatter, it.toInt()) }
            )
            var wifiOnly by remember { mutableStateOf(Prefs.getBool(ctx, "spd_wifi_only", true)) }
            SettingSwitch(
                label = ctx.getString(R.string.speed_test_module_wifi_only),
                checked = wifiOnly,
                onCheckedChange = {
                    wifiOnly = it
                    Prefs.setBool(ctx, "spd_wifi_only", it)
                }
            )
            var showPing by remember { mutableStateOf(Prefs.getBool(ctx, "spd_show_ping", true)) }
            SettingSwitch(
                label = ctx.getString(R.string.speed_test_module_show_ping),
                checked = showPing,
                onCheckedChange = {
                    showPing = it
                    Prefs.setBool(ctx, "spd_show_ping", it)
                }
            )
        }
    }

    override fun checkAlerts(ctx: Context) {}

    override fun reset() {
        ctx?.let { Prefs.setInt(it, "spd_tests_today", 0) }
    }
}
