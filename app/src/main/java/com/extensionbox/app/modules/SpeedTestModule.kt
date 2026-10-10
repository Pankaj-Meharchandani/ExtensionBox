package com.extensionbox.app.modules

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.extensionbox.app.Fmt
import com.extensionbox.app.Prefs
import com.extensionbox.app.R
import com.extensionbox.app.SystemAccess
import com.extensionbox.app.ui.components.SettingSlider
import com.extensionbox.app.ui.components.SettingSwitch
import kotlinx.coroutines.*
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap
import kotlin.math.abs

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
        // Cloudflare's public speed-test endpoints: __down streams N random bytes, __up discards a POST body
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
                // Upload is best-effort: a failed upload shouldn't discard a good ping/download result
                val upBps = try { measureUpload() } catch (e: IOException) { 0L }

                pingMs = ping
                // Stored as kbit/s in 128-byte units so the existing `* 128` display code yields bytes/s
                downKbps = downBps / 128
                upKbps = upBps / 128
                lastTestTime = System.currentTimeMillis()
                ctx?.let { 
                    val count = Prefs.getInt(it, "spd_tests_today", 0)
                    Prefs.setInt(it, "spd_tests_today", count + 1)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Failed test: keep the previous results and back off instead of retrying every tick
                retryAfter = System.currentTimeMillis() + RETRY_BACKOFF_MS
            } finally {
                testing = false
            }
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

    /**
     * Round-trip time in ms. The first request pays for DNS + TLS and only warms the connection;
     * the reported value is the best of the following requests, which reuse it.
     */
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

    /** Throughput in bytes/s, timed from the first received chunk so connection setup isn't counted. */
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
                        startNs = System.nanoTime() // the first chunk only starts the clock
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

    /** Upload throughput in bytes/s. Timed until the server has answered, i.e. consumed the whole body. */
    private fun measureUpload(): Long {
        val payload = ByteArray(UP_BYTES).also { java.util.Random().nextBytes(it) }
        val conn = openConnection(UP_URL)
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(payload.size)
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.connect() // keep TCP + TLS setup out of the timing
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
