package com.extensionbox.app.ui.screens

import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.extensionbox.app.MonitorService
import com.extensionbox.app.Prefs
import com.extensionbox.app.R
import com.extensionbox.app.db.HistoryCsvExporter
import com.extensionbox.app.db.ModuleDataEntity
import com.extensionbox.app.ui.ModuleRegistry
import com.extensionbox.app.ui.components.*
import com.extensionbox.app.ui.viewmodel.DashboardViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val DAY_MS = 24L * 60 * 60 * 1000
private const val CHART_TARGET_POINTS = 180

/** Time windows offered on the detail chart. Longer ranges are only shown if data retention can fill them. */
enum class HistoryRange(val label: String, val durationMs: Long) {
    M15("15m", 15L * 60 * 1000),
    H1("1h", 60L * 60 * 1000),
    D1("24h", DAY_MS),
    D7("7d", 7 * DAY_MS),
    D30("30d", 30 * DAY_MS);

    /** One sample is kept per bucket, so every range draws roughly the same number of points. */
    val bucketMs: Long get() = maxOf(durationMs / CHART_TARGET_POINTS, 1_000L)

    val refreshMs: Long get() = if (this == M15) 5_000L else 30_000L
}

@Composable
fun ModuleDetailScreen(
    moduleKey: String,
    viewModel: DashboardViewModel
) {
    val context = LocalContext.current
    val sysAccess = viewModel.sysAccess.collectAsState().value

    val module = MonitorService.getLiveModule(moduleKey) ?: com.extensionbox.app.ui.ModuleRegistry.getModule(moduleKey)
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()

    // --- History chart state ---
    val chartable = hasChart(moduleKey)
    val retentionDays = remember { Prefs.getDataRetentionDays(context) }
    val ranges = remember(retentionDays) {
        HistoryRange.entries.filter { it.durationMs <= retentionDays * DAY_MS }
            .ifEmpty { listOf(HistoryRange.M15) }
    }
    var rangeIndex by rememberSaveable { mutableIntStateOf(0) }
    val range = ranges.getOrElse(rangeIndex) { ranges.first() }
    var rangeHistory by remember { mutableStateOf<List<ModuleDataEntity>>(emptyList()) }

    LaunchedEffect(moduleKey, range) {
        if (!chartable) return@LaunchedEffect
        while (true) {
            try {
                rangeHistory = viewModel.loadHistory(moduleKey, range.durationMs, range.bucketMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                rangeHistory = emptyList()
            }
            delay(range.refreshMs)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            val after = System.currentTimeMillis() - range.durationMs
            scope.launch {
                try {
                    val rows = HistoryCsvExporter.export(context, uri, moduleKey, after)
                    Toast.makeText(context, context.getString(R.string.history_exported, rows), Toast.LENGTH_SHORT).show()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Toast.makeText(context, R.string.history_export_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (chartable) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Timeline, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(id = R.string.history_title),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { exportLauncher.launch("extensionbox_${moduleKey}_${range.label}.csv") }) {
                            Icon(
                                Icons.Default.FileDownload,
                                contentDescription = stringResource(id = R.string.history_export_csv)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ranges.forEachIndexed { index, r ->
                            FilterChip(
                                selected = r == range,
                                onClick = { rangeIndex = index },
                                label = { Text(r.label) }
                            )
                        }
                    }

                    val points = remember(rangeHistory) { extractPoints(moduleKey, rangeHistory) }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (points.size >= 2) {
                            Sparkline(
                                points = points,
                                modifier = Modifier.fillMaxSize(),
                                color = MaterialTheme.colorScheme.primary,
                                fillGradient = true,
                                animate = true
                            )
                        } else {
                            Text(
                                text = stringResource(id = R.string.history_no_data),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (rangeHistory.size >= 2) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = formatTimestamp(context, rangeHistory.first().timestamp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = formatTimestamp(context, rangeHistory.last().timestamp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        if (module != null && sysAccess != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    module.composableContent(context, sysAccess)
                }
            }
        }

        val hasSettings = module?.hasSettings() == true

        if (module != null && sysAccess != null && hasSettings) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Settings, null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(id = R.string.extension_settings),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    
                    module.settingsContent(context, sysAccess)
                }
            }
        }
        
        Spacer(modifier = Modifier.height(100.dp))
    }
}

private fun formatTimestamp(context: Context, timestamp: Long): String =
    DateUtils.formatDateTime(
        context,
        timestamp,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL
    )
