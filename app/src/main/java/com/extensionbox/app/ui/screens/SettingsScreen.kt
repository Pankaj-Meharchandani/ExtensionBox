package com.extensionbox.app.ui.screens

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.extensionbox.app.MonitorService
import com.extensionbox.app.Prefs
import com.extensionbox.app.R
import com.extensionbox.app.SystemAccess
import com.extensionbox.app.ThemeHelper
import com.extensionbox.app.db.AppDatabase
import com.extensionbox.app.ui.components.SingleOptionSelectionDialog
import com.extensionbox.app.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = viewModel(),
    onAboutClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val lifecycleOwner = LocalLifecycleOwner.current

    val themeIndex by viewModel.themeIndex.collectAsState()
    val isShizukuRunning by viewModel.isShizukuRunning.collectAsState()
    val shizukuPermissionGranted by viewModel.shizukuPermissionGranted.collectAsState()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshShizukuState()
                if (isShizukuRunning && !shizukuPermissionGranted) {
                    try {
                        Shizuku.requestPermission(1001)
                    } catch (_: Exception) {}
                }
            }
        }

        val permissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            viewModel.setShizukuPermission(grantResult == PackageManager.PERMISSION_GRANTED)
        }

        val binderListener = object : Shizuku.OnBinderReceivedListener {
            override fun onBinderReceived() {
                viewModel.refreshShizukuState()
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListener(binderListener)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            Shizuku.removeRequestPermissionResultListener(permissionListener)
            Shizuku.removeBinderReceivedListener(binderListener)
        }
    }

    var sysAccess by remember { mutableStateOf<SystemAccess?>(null) }
    val scope = rememberCoroutineScope()
    var showResetDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val newAccess = withContext(Dispatchers.IO) { SystemAccess(context) }
        sysAccess = newAccess
    }

    DisposableEffect(Unit) {
        onDispose { sysAccess?.onDestroy() }
    }

    fun refreshAccess() {
        sysAccess?.onDestroy()
        sysAccess = null
        scope.launch {
            val newAccess = withContext(Dispatchers.IO) { SystemAccess(context) }
            sysAccess = newAccess
            viewModel.refreshShizukuState()
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let {
            try {
                val jsonObject = JSONObject()
                Prefs.getAll(context).forEach { (k, v) -> jsonObject.put(k, v) }
                context.contentResolver.openOutputStream(it)?.use { os ->
                    os.write(jsonObject.toString(4).toByteArray())
                }
                Toast.makeText(context, R.string.settings_exported, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(context, R.string.export_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            try {
                context.contentResolver.openInputStream(it)?.use { `is` ->
                    val json = BufferedReader(InputStreamReader(`is`)).readText()
                    val applied = Prefs.importJson(context, json)
                    Toast.makeText(
                        context,
                        if (applied >= 0) R.string.settings_imported else R.string.import_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, R.string.import_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // --- Header Status Banner ---
        val currentAccess = sysAccess
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    modifier = Modifier.size(52.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Settings & Access",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (currentAccess != null) "Tier: ${currentAccess.tier}" else "Inspecting system privileges...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (currentAccess != null) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (currentAccess.isEnhanced()) Color(0xFF00E676).copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Text(
                            text = if (currentAccess.isEnhanced()) "Enhanced" else "Standard",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (currentAccess.isEnhanced()) Color(0xFF00E676) else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }
        }

        // --- 1. Permissions & Access Section ---
        SettingsGroupCard(
            title = stringResource(id = R.string.permissions),
            icon = Icons.Default.Security,
            accentColor = Color(0xFF00E676)
        ) {
            if (currentAccess == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(id = R.string.checking_system_access),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                SettingsItemRow(
                    title = stringResource(id = R.string.system_access),
                    summary = stringResource(id = R.string.system_access_summary, currentAccess.tier),
                    icon = if (currentAccess.isEnhanced()) Icons.Default.Verified else Icons.Default.AdminPanelSettings,
                    iconColor = if (currentAccess.isEnhanced()) Color(0xFF00E676) else MaterialTheme.colorScheme.primary,
                    trailing = {
                        IconButton(onClick = { refreshAccess() }) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(id = R.string.refresh))
                        }
                    },
                    onClick = { refreshAccess() }
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            SettingsItemRow(
                title = stringResource(id = R.string.shizuku_service),
                summary = if (isShizukuRunning) stringResource(id = R.string.service_is_active) else stringResource(id = R.string.service_not_found),
                icon = Icons.Default.Terminal,
                iconColor = if (isShizukuRunning) Color(0xFF2979FF) else MaterialTheme.colorScheme.onSurfaceVariant,
                badge = if (isShizukuRunning) "Active" else null,
                badgeColor = Color(0xFF2979FF),
                onClick = {
                    try {
                        val intent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                        if (intent != null) context.startActivity(intent)
                        else Toast.makeText(context, R.string.shizuku_not_found, Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {}
                }
            )

            if (isShizukuRunning && !shizukuPermissionGranted) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                SettingsItemRow(
                    title = stringResource(id = R.string.grant_permission),
                    summary = stringResource(id = R.string.allow_system_file_access_via_shizuku),
                    icon = Icons.Default.VpnKey,
                    iconColor = Color(0xFFFF9100),
                    onClick = { Shizuku.requestPermission(1001) }
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            SettingsItemRow(
                title = stringResource(id = R.string.battery_optimization),
                summary = stringResource(id = R.string.allow_background_monitoring),
                icon = Icons.Default.BatterySaver,
                iconColor = Color(0xFFFF9100),
                onClick = {
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    } catch (_: Exception) {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }
            )
        }

        // --- 2. Appearance & Customization Section ---
        SettingsGroupCard(
            title = stringResource(id = R.string.appearance),
            icon = Icons.Default.Palette,
            accentColor = Color(0xFFAB47BC)
        ) {
            var showThemeDialog by remember { mutableStateOf(false) }

            SettingsItemRow(
                title = stringResource(id = R.string.app_theme),
                summary = ThemeHelper.NAMES[themeIndex.coerceIn(0, ThemeHelper.NAMES.size - 1)],
                icon = Icons.Default.ColorLens,
                iconColor = Color(0xFFAB47BC),
                onClick = { showThemeDialog = true }
            )

            if (showThemeDialog) {
                SingleOptionSelectionDialog(
                    title = stringResource(id = R.string.app_theme),
                    options = ThemeHelper.NAMES.toList(),
                    selectedOption = ThemeHelper.NAMES[themeIndex.coerceIn(0, ThemeHelper.NAMES.size - 1)],
                    onOptionSelected = { selectedName ->
                        val idx = ThemeHelper.NAMES.indexOf(selectedName)
                        if (idx >= 0) viewModel.updateTheme(idx)
                    },
                    onDismissRequest = { showThemeDialog = false }
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            var showLayoutDialog by remember { mutableStateOf(false) }
            val layoutStyleRaw by Prefs.getStringFlow(context, "dash_layout_style", "LIST")
                .collectAsState(initial = Prefs.getString(context, "dash_layout_style", "LIST") ?: "LIST")
            val layoutStyle = layoutStyleRaw ?: "LIST"

            SettingsItemRow(
                title = "Dashboard Layout",
                summary = "Current: $layoutStyle",
                icon = Icons.Default.Dashboard,
                iconColor = Color(0xFF00B0FF),
                onClick = { showLayoutDialog = true }
            )

            if (showLayoutDialog) {
                SingleOptionSelectionDialog(
                    title = "Dashboard Layout Style",
                    options = listOf("PUZZLE", "LIST", "GRID"),
                    selectedOption = layoutStyle,
                    onOptionSelected = { style ->
                        Prefs.setString(context, "dash_layout_style", style)
                    },
                    onDismissRequest = { showLayoutDialog = false }
                )
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
                (themeIndex == ThemeHelper.MONET || themeIndex == ThemeHelper.AMOLED)) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                var dynamicColor by remember { mutableStateOf(Prefs.getBool(context, "dynamic_color", true)) }
                SettingsSwitchRow(
                    title = stringResource(id = R.string.dynamic_color),
                    summary = stringResource(id = R.string.use_system_wallpaper_colors),
                    icon = Icons.Default.InvertColors,
                    iconColor = Color(0xFF26A69A),
                    checked = dynamicColor,
                    onCheckedChange = {
                        dynamicColor = it
                        Prefs.setBool(context, "dynamic_color", it)
                    }
                )
            }
        }

        // --- 3. Notification Section ---
        SettingsGroupCard(
            title = stringResource(id = R.string.notification),
            icon = Icons.Default.Notifications,
            accentColor = Color(0xFF2979FF)
        ) {
            var refreshRate by remember { mutableStateOf(Prefs.getLong(context, "notif_refresh_ms", 10000L).toFloat()) }

            SettingsSliderRow(
                title = stringResource(id = R.string.refresh_rate),
                valueStr = stringResource(id = R.string.refresh_rate_value, (refreshRate.toInt() / 1000)),
                value = refreshRate,
                valueRange = 1000f..60000f,
                steps = 58,
                onValueChange = {
                    refreshRate = it
                    Prefs.setLong(context, "notif_refresh_ms", it.toLong())
                }
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            var dismissible by remember { mutableStateOf(Prefs.getBool(context, "notif_dismissible", false)) }
            SettingsSwitchRow(
                title = stringResource(id = R.string.dismissible),
                summary = stringResource(id = R.string.allow_swiping_away_to_stop_service),
                icon = Icons.Default.Swipe,
                iconColor = Color(0xFF2979FF),
                checked = dismissible,
                onCheckedChange = {
                    dismissible = it
                    Prefs.setBool(context, "notif_dismissible", it)
                }
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            var contextAware by remember { mutableStateOf(Prefs.getBool(context, "notif_context_aware", true)) }
            SettingsSwitchRow(
                title = stringResource(id = R.string.context_aware),
                summary = stringResource(id = R.string.dynamic_titles_based_on_battery),
                icon = Icons.Default.NotificationAdd,
                iconColor = Color(0xFF1DE9B6),
                checked = contextAware,
                onCheckedChange = {
                    contextAware = it
                    Prefs.setBool(context, "notif_context_aware", it)
                }
            )
        }

        // --- 4. Data & Storage Section ---
        SettingsGroupCard(
            title = stringResource(id = R.string.data_backup),
            icon = Icons.Default.Storage,
            accentColor = Color(0xFFFF9100)
        ) {
            var retentionDays by remember { mutableStateOf(Prefs.getDataRetentionDays(context).toFloat()) }

            SettingsSliderRow(
                title = stringResource(id = R.string.data_retention),
                valueStr = stringResource(id = R.string.data_retention_days, retentionDays.toInt()),
                value = retentionDays,
                valueRange = 1f..30f,
                steps = 29,
                subText = stringResource(id = R.string.history_older_than_this_will_be_deleted_daily),
                onValueChange = {
                    retentionDays = it
                    Prefs.setDataRetentionDays(context, it.toInt())
                }
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            SettingsItemRow(
                title = stringResource(id = R.string.export_settings),
                summary = stringResource(id = R.string.save_config_to_json),
                icon = Icons.Default.UploadFile,
                iconColor = Color(0xFFFF9100),
                onClick = { exportLauncher.launch("extensionbox_config.json") }
            )

            SettingsItemRow(
                title = stringResource(id = R.string.import_settings),
                summary = stringResource(id = R.string.restore_from_json),
                icon = Icons.Default.FileDownload,
                iconColor = Color(0xFFFFD600),
                onClick = { importLauncher.launch(arrayOf("application/json")) }
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            SettingsItemRow(
                title = stringResource(id = R.string.reset_all_data),
                summary = stringResource(id = R.string.clear_all_stats_and_preferences),
                icon = Icons.Default.DeleteSweep,
                iconColor = MaterialTheme.colorScheme.error,
                onClick = { showResetDialog = true }
            )
        }

        // --- 5. About & Guide Section ---
        SettingsGroupCard(
            title = stringResource(id = R.string.about),
            icon = Icons.Default.Info,
            accentColor = Color(0xFF00B0FF)
        ) {
            SettingsItemRow(
                title = "Welcome Guide",
                summary = "Replay intro & system setup guide",
                icon = Icons.Default.Explore,
                iconColor = Color(0xFF00B0FF),
                onClick = { Prefs.setBool(context, "onboarding_completed", false) }
            )

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

            SettingsItemRow(
                title = stringResource(id = R.string.about),
                summary = stringResource(id = R.string.about_description),
                icon = Icons.Default.Info,
                iconColor = MaterialTheme.colorScheme.primary,
                onClick = onAboutClick
            )
        }

        if (showResetDialog) {
            AlertDialog(
                onDismissRequest = { showResetDialog = false },
                title = { Text(stringResource(id = R.string.reset_all_data)) },
                text = { Text(stringResource(id = R.string.reset_all_data_confirm)) },
                confirmButton = {
                    TextButton(onClick = {
                        showResetDialog = false
                        scope.launch { resetAllData(context) }
                    }) {
                        Text(
                            stringResource(id = R.string.reset_all_data_action),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showResetDialog = false }) {
                        Text(stringResource(id = R.string.dialog_cancel))
                    }
                }
            )
        }

        Spacer(modifier = Modifier.height(80.dp))
    }
}

private suspend fun resetAllData(context: Context) {
    val wasRunning = Prefs.isRunning(context)
    withContext(Dispatchers.IO) {
        AppDatabase.getDatabase(context).clearAllTables()
        Prefs.clearAll(context)
        Prefs.setRunning(context, wasRunning)
    }
    if (wasRunning) {
        context.startService(
            Intent(context, MonitorService::class.java).setAction(MonitorService.ACTION_RESET)
        )
    }
    Toast.makeText(context, R.string.data_cleared, Toast.LENGTH_SHORT).show()
}

@Composable
fun SettingsGroupCard(
    title: String,
    icon: ImageVector,
    accentColor: Color,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(1.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = accentColor.copy(alpha = 0.12f),
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            content()
        }
    }
}

@Composable
fun SettingsItemRow(
    title: String,
    summary: String,
    icon: ImageVector,
    iconColor: Color = MaterialTheme.colorScheme.primary,
    badge: String? = null,
    badgeColor: Color = MaterialTheme.colorScheme.primary,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = CircleShape,
            color = iconColor.copy(alpha = 0.12f),
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, modifier = Modifier.size(20.dp), tint = iconColor)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (badge != null) {
                    Spacer(Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = badgeColor.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = badge,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = badgeColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
fun SettingsSwitchRow(
    title: String,
    summary: String,
    icon: ImageVector,
    iconColor: Color = MaterialTheme.colorScheme.primary,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    SettingsItemRow(
        title = title,
        summary = summary,
        icon = icon,
        iconColor = iconColor,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary
                )
            )
        },
        onClick = { onCheckedChange(!checked) }
    )
}

@Composable
fun SettingsSliderRow(
    title: String,
    valueStr: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    subText: String? = null,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            ) {
                Text(
                    text = valueStr,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary
            )
        )
        if (subText != null) {
            Text(
                text = subText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
