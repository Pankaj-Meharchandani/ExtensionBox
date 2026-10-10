package com.extensionbox.app.ui.screens

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.extensionbox.app.Fmt
import com.extensionbox.app.Prefs
import com.extensionbox.app.SystemAccess
import com.extensionbox.app.ui.components.AppCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

data class SpeedRecord(
    val timestamp: Long,
    val dateFormatted: String,
    val networkType: String,
    val pingMs: String,
    val downloadSpeed: String,
    val uploadSpeed: String
)

@Composable
fun SpeedTestScreen(sys: SystemAccess) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var testing by remember { mutableStateOf(false) }
    var testStage by remember { mutableStateOf("Ready") } // "Measuring Ping", "Testing Download", "Testing Upload", "Completed", "Ready"
    var pingVal by remember { mutableLongStateOf(0L) }
    var downVal by remember { mutableLongStateOf(0L) }
    var upVal by remember { mutableLongStateOf(0L) }

    var historyJson by remember {
        mutableStateOf(Prefs.getString(context, "speedtest_results_history", "[]") ?: "[]")
    }
    val historyList = remember(historyJson, testing) {
        parseHistory(historyJson)
    }

    val animatedProgress by animateFloatAsState(
        targetValue = when (testStage) {
            "Measuring Ping" -> 0.2f
            "Testing Download" -> (downVal / 1000f).coerceIn(0.2f, 0.7f)
            "Testing Upload" -> (upVal / 1000f).coerceIn(0.7f, 0.95f)
            "Completed" -> 1f
            else -> 0f
        },
        animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing),
        label = "SpeedGaugeProgress"
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- Main Ookla Speedometer Header Card ---
        item {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
                tonalElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    // Header & Stage Badge
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Speedometer",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Surface(
                            shape = CircleShape,
                            color = if (testing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = testStage,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (testing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }

                    // Main Speedometer Gauge
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(200.dp)
                    ) {
                        val activeColor = MaterialTheme.colorScheme.primary
                        val trackColor = MaterialTheme.colorScheme.surfaceVariant

                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val strokeWidth = 14.dp.toPx()
                            val diameter = size.minDimension - strokeWidth
                            val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)
                            val arcSize = Size(diameter, diameter)

                            // Track Arc (240 degrees)
                            drawArc(
                                color = trackColor,
                                startAngle = 150f,
                                sweepAngle = 240f,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                            )

                            // Progress Arc
                            drawArc(
                                color = activeColor,
                                startAngle = 150f,
                                sweepAngle = 240f * animatedProgress,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                            )
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val displaySpeed = if (testStage == "Testing Upload") Fmt.speed(upVal * 128) else Fmt.speed(downVal * 128)
                            val num = displaySpeed.substringBefore(" ")
                            val unit = displaySpeed.substringAfter(" ", "")

                            Text(
                                text = num,
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Black,
                                fontSize = 38.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            if (unit.isNotEmpty()) {
                                Text(
                                    text = unit,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    // Live Metrics Bar (Ping, Download, Upload)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        MetricItem("PING", if (pingVal > 0) "${pingVal}ms" else "--", MaterialTheme.colorScheme.tertiary)
                        MetricItem("DOWNLOAD", Fmt.speed(downVal * 128), MaterialTheme.colorScheme.primary)
                        MetricItem("UPLOAD", Fmt.speed(upVal * 128), MaterialTheme.colorScheme.secondary)
                    }

                    // START SPEED TEST Button
                    Button(
                        onClick = {
                            if (!testing) {
                                testing = true
                                testStage = "Measuring Ping"
                                scope.launch(Dispatchers.IO) {
                                    executeSpeedTest(context) { stage, p, d, u ->
                                        testStage = stage
                                        pingVal = p
                                        downVal = d
                                        upVal = u
                                    }
                                    testing = false
                                    testStage = "Completed"
                                    historyJson = Prefs.getString(context, "speedtest_results_history", "[]") ?: "[]"
                                }
                            }
                        },
                        enabled = !testing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        if (testing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.5.dp
                            )
                            Spacer(Modifier.width(12.dp))
                            Text("TESTING SPEED...", fontWeight = FontWeight.Bold)
                        } else {
                            Icon(Icons.Default.Speed, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("START SPEED TEST", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // --- Previous Test Results History Section ---
        item {
            Text(
                text = "Previous Test Results",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        if (historyList.isEmpty()) {
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
                ) {
                    Box(
                        modifier = Modifier.padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No previous tests found. Tap 'START SPEED TEST' above.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else {
            items(historyList, key = { it.timestamp }) { record ->
                SpeedHistoryCard(record)
            }
        }

        item { Spacer(modifier = Modifier.height(100.dp)) }
    }
}

@Composable
fun SpeedHistoryCard(record: SpeedRecord) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(20.dp),
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
                        style = MaterialTheme.typography.bodySmall,
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

            AnimatedVisibility(visible = expanded) {
                Column {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 12.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        MetricColumn("Ping Latency", record.pingMs)
                        MetricColumn("Download", record.downloadSpeed)
                        MetricColumn("Upload", record.uploadSpeed)
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = color)
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MetricColumn(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
}

private suspend fun executeSpeedTest(
    context: Context,
    onProgress: (stage: String, ping: Long, downKbps: Long, upKbps: Long) -> Unit
) {
    var p = 0L
    var d = 0L
    var u = 0L

    try {
        onProgress("Measuring Ping", p, d, u)
        p = measurePing()

        onProgress("Testing Download", p, d, u)
        val downBps = measureDownload()
        d = downBps / 128

        onProgress("Testing Upload", p, d, u)
        val upBps = try { measureUpload() } catch (e: Exception) { 0L }
        u = upBps / 128

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
        val sdf = SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.US)

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

private fun parseHistory(json: String): List<SpeedRecord> {
    val list = mutableListOf<SpeedRecord>()
    try {
        val array = JSONArray(json)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            list.add(
                SpeedRecord(
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
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular Data"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        else -> "Active Network"
    }
}

private fun openConnection(url: String): HttpURLConnection {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 5000
    conn.readTimeout = 8000
    conn.useCaches = false
    conn.setRequestProperty("Cache-Control", "no-cache")
    return conn
}

private fun measurePing(): Long {
    var best = Long.MAX_VALUE
    repeat(4) { i ->
        val conn = openConnection("https://speed.cloudflare.com/__down?bytes=0")
        val t0 = System.nanoTime()
        if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
        conn.inputStream.use { it.readBytes() }
        val ms = (System.nanoTime() - t0) / 1_000_000L
        if (i > 0 && ms < best) best = ms
    }
    return best
}

private fun measureDownload(): Long {
    val conn = openConnection("https://speed.cloudflare.com/__down?bytes=10000000")
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
                    if (System.nanoTime() - startNs >= 4000L * 1_000_000L) break
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
    val payload = ByteArray(2_000_000).also { Random().nextBytes(it) }
    val conn = openConnection("https://speed.cloudflare.com/__up")
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
