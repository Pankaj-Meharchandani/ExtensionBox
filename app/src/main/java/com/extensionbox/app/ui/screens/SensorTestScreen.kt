package com.extensionbox.app.ui.screens

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.util.Locale

data class SensorTestDef(
    val id: String,
    val name: String,
    val sensorType: Int,
    val icon: ImageVector
)

@Suppress("DEPRECATION")
private val FEATURED_SENSORS = listOf(
    SensorTestDef("accel", "Accelerometer", Sensor.TYPE_ACCELEROMETER, Icons.Default.ScreenRotation),
    SensorTestDef("light", "Light", Sensor.TYPE_LIGHT, Icons.Default.LightMode),
    SensorTestDef("proximity", "Proximity", Sensor.TYPE_PROXIMITY, Icons.Default.Sensors),
    SensorTestDef("mag", "Magnetometer", Sensor.TYPE_MAGNETIC_FIELD, Icons.Default.Explore),
    SensorTestDef("compass", "Compass", Sensor.TYPE_ORIENTATION, Icons.Default.Navigation),
    SensorTestDef("gyro", "Gyroscope", Sensor.TYPE_GYROSCOPE, Icons.Default.RotateRight),
    SensorTestDef("baro", "Barometer", Sensor.TYPE_PRESSURE, Icons.Default.Compress),
    SensorTestDef("gravity", "Gravity", Sensor.TYPE_GRAVITY, Icons.Default.VerticalAlignBottom),
    SensorTestDef("linear_accel", "Linear Acceleration", Sensor.TYPE_LINEAR_ACCELERATION, Icons.Default.Speed),
    SensorTestDef("rotation", "Rotation Vector", Sensor.TYPE_ROTATION_VECTOR, Icons.Default.FlipCameraAndroid),
    SensorTestDef("steps", "Step Counter", Sensor.TYPE_STEP_COUNTER, Icons.AutoMirrored.Filled.DirectionsWalk)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensorTestScreen() {
    val context = LocalContext.current
    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }

    val allSensors = remember { sensorManager.getSensorList(Sensor.TYPE_ALL) }
    val totalCount = allSensors.size

    var testSensorDef by remember { mutableStateOf<SensorTestDef?>(null) }
    var infoSensorObj by remember { mutableStateOf<Sensor?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    val filteredAllSensors = remember(allSensors, searchQuery) {
        if (searchQuery.isBlank()) allSensors
        else allSensors.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
                    it.vendor.contains(searchQuery, ignoreCase = true)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // --- 1. Total Sensors Banner ---
        item {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            modifier = Modifier.size(44.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Sensors,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }

                        Column {
                            Text(
                                text = "Sensor Diagnostic Test",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Live hardware testing & specs inspection",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF00E676).copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = "$totalCount Sensors",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFF00E676),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        // --- 2. Featured Hardware Sensors List ---
        item {
            Text(
                text = "Primary Hardware Sensors",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }

        items(FEATURED_SENSORS, key = { it.id }) { def ->
            val defaultSensor = remember(def) { sensorManager.getDefaultSensor(def.sensorType) }
            val isAvailable = defaultSensor != null

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Status Dot (Green = Present, Red = Missing)
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isAvailable) Color(0xFF00E676) else Color(0xFFFF1744))
                    )

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = def.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = defaultSensor?.vendor ?: if (isAvailable) "Available" else "Not Present",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = { testSensorDef = def },
                            enabled = isAvailable,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("TEST", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = { infoSensorObj = defaultSensor },
                            enabled = isAvailable,
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("INFO", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // --- 3. All Sensors Catalog Header & Search ---
        item {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "All Device Sensors Catalog ($totalCount)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 4.dp)
            )

            Spacer(Modifier.height(6.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search sensor name or vendor...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = null)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )
        }

        // All sensors catalog rows
        items(filteredAllSensors, key = { "${it.type}_${it.name}_${it.vendor}" }) { sensor ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                modifier = Modifier.fillMaxWidth().clickable { infoSensorObj = sensor }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF00E676))
                    )

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = sensor.name,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Vendor: ${sensor.vendor} • Power: ${sensor.power} mA",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        item { Spacer(Modifier.height(80.dp)) }
    }

    // --- Dialogs ---
    testSensorDef?.let { def ->
        LiveSensorTestDialog(
            sensorDef = def,
            sensorManager = sensorManager,
            onDismiss = { testSensorDef = null }
        )
    }

    infoSensorObj?.let { sensor ->
        SensorInfoDialog(
            sensor = sensor,
            onDismiss = { infoSensorObj = null }
        )
    }
}

@Composable
private fun LiveSensorTestDialog(
    sensorDef: SensorTestDef,
    sensorManager: SensorManager,
    onDismiss: () -> Unit
) {
    var sensorValues by remember { mutableStateOf(floatArrayOf(0f, 0f, 0f)) }

    DisposableEffect(sensorDef) {
        val sensor = sensorManager.getDefaultSensor(sensorDef.sensorType)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event != null) {
                    sensorValues = event.values.clone()
                }
            }

            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }

        if (sensor != null) {
            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        }

        onDispose {
            sensorManager.unregisterListener(listener)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(sensorDef.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                        Text(sensorDef.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, null)
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                when (sensorDef.sensorType) {
                    Sensor.TYPE_LIGHT -> {
                        val lux = sensorValues.getOrNull(0) ?: 0f
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = String.format(Locale.US, "%.1f Lux", lux),
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = when {
                                    lux < 10 -> "Dark / Night"
                                    lux < 100 -> "Dim Light"
                                    lux < 1000 -> "Normal Indoor"
                                    else -> "Bright Daylight"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    Sensor.TYPE_PROXIMITY -> {
                        val dist = sensorValues.getOrNull(0) ?: 0f
                        val isNear = dist < 2f
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Surface(
                                shape = CircleShape,
                                color = if (isNear) Color(0xFFFF1744).copy(alpha = 0.2f) else Color(0xFF00E676).copy(alpha = 0.2f),
                                modifier = Modifier.size(72.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = if (isNear) "NEAR" else "FAR",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Black,
                                        color = if (isNear) Color(0xFFFF1744) else Color(0xFF00E676)
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("Distance: $dist cm", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                    Sensor.TYPE_PRESSURE -> {
                        val hpa = sensorValues.getOrNull(0) ?: 0f
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = String.format(Locale.US, "%.2f hPa", hpa),
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text("Barometric Air Pressure", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Sensor.TYPE_STEP_COUNTER -> {
                        val steps = sensorValues.getOrNull(0)?.toInt() ?: 0
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "$steps",
                                style = MaterialTheme.typography.displayMedium,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text("Hardware Steps Since Reboot", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    else -> {
                        // Multi-axis sensors (Accel, Gyro, Mag, Gravity, etc.)
                        val x = sensorValues.getOrNull(0) ?: 0f
                        val y = sensorValues.getOrNull(1) ?: 0f
                        val z = sensorValues.getOrNull(2) ?: 0f

                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            AxisMeter(label = "X-Axis", value = x, color = Color(0xFFFF1744))
                            AxisMeter(label = "Y-Axis", value = y, color = Color(0xFF00E676))
                            AxisMeter(label = "Z-Axis", value = z, color = Color(0xFF2979FF))
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Close Test")
                }
            }
        }
    }
}

@Composable
private fun AxisMeter(label: String, value: Float, color: Color) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = color.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = color)
            Text(
                text = String.format(Locale.US, "%+.3f", value),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Black,
                color = color
            )
        }
    }
}

@Composable
private fun SensorInfoDialog(
    sensor: Sensor,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Info, null, tint = MaterialTheme.colorScheme.primary)
                        Text(sensor.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, null)
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                val specs = listOf(
                    "Vendor" to sensor.vendor,
                    "Sensor Type ID" to "${sensor.type}",
                    "Version" to "${sensor.version}",
                    "Power Consumption" to "${sensor.power} mA",
                    "Max Range" to "${sensor.maximumRange}",
                    "Resolution" to "${sensor.resolution}",
                    "Min Delay" to "${sensor.minDelay} μs",
                    "Reporting Mode" to when (sensor.reportingMode) {
                        Sensor.REPORTING_MODE_CONTINUOUS -> "Continuous"
                        Sensor.REPORTING_MODE_ON_CHANGE -> "On Change"
                        Sensor.REPORTING_MODE_ONE_SHOT -> "One Shot"
                        Sensor.REPORTING_MODE_SPECIAL_TRIGGER -> "Special Trigger"
                        else -> "Standard"
                    }
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    specs.forEach { (key, value) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(key, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Close Specs")
                }
            }
        }
    }
}
