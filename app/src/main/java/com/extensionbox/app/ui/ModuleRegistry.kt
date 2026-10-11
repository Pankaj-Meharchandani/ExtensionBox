package com.extensionbox.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.graphics.vector.ImageVector

data class ModuleDef(
    val key: String,
    val icon: ImageVector,
    val name: String,
    val description: String,
    val defaultEnabled: Boolean
)

object ModuleRegistry {

    private val ALL_MODULES = listOf(
        ModuleDef("battery", Icons.Rounded.BatteryChargingFull, "Battery & Screen", "Battery health, power and screen usage", true),
        ModuleDef("screen_time", Icons.Rounded.Schedule, "Screen Time", "App usage, open counts & unlock analytics", true),
        ModuleDef("app_usage", Icons.Rounded.Apps, "App Usage", "Time spent in each application", false),
        ModuleDef("cpu", Icons.Rounded.Memory, "CPU", "CPU usage, frequency and temperature", true),
        ModuleDef("ram", Icons.Rounded.Memory, "RAM", "Memory status and running processes", true),
        ModuleDef("sleep", Icons.Rounded.Bedtime, "Device Sleep", "CPU sleep vs awake ratio", true),
        ModuleDef("network", Icons.Rounded.NetworkCheck, "Network Speed", "Real-time download/upload speed", true),
        ModuleDef("data", Icons.Rounded.DataUsage, "Data Usage", "Daily & monthly, WiFi & mobile", true),
        ModuleDef("unlock", Icons.Rounded.LockOpen, "Unlock Counter", "Daily unlocks, detox tracking", true),
        ModuleDef("storage", Icons.Rounded.Storage, "Storage", "Internal storage usage", false),
        ModuleDef("connection", Icons.Rounded.SettingsInputAntenna, "Connection Info", "WiFi, cellular, VPN status", false),
        ModuleDef("uptime", Icons.Rounded.History, "Uptime", "Device uptime since boot", false),
        ModuleDef("steps", Icons.AutoMirrored.Rounded.DirectionsWalk, "Step Counter", "Steps and distance", false),
        ModuleDef("speedtest", Icons.Rounded.Speed, "Speed Test", "Periodic download/upload speed test", false),
        ModuleDef("privacy", Icons.Rounded.Shield, "Privacy Dashboard", "Permission usage and force-revoke tools", false)
    )

    private val HIDDEN_KEYS = setOf("speedtest", "app_usage", "privacy")
    private val MODULES = ALL_MODULES.filter { it.key !in HIDDEN_KEYS }

    fun keyAt(i: Int): String = MODULES[i].key
    fun iconAt(i: Int): ImageVector = MODULES[i].icon
    fun nameAt(i: Int): String = MODULES[i].name
    fun descAt(i: Int): String = MODULES[i].description
    fun defAt(i: Int): Boolean = MODULES[i].defaultEnabled
    fun count(): Int = MODULES.size

    fun iconFor(key: String): ImageVector {
        return ALL_MODULES.find { it.key == key }?.icon ?: Icons.Rounded.Extension
    }

    fun nameFor(key: String): String {
        return ALL_MODULES.find { it.key == key }?.name ?: key
    }

    fun getModules(): List<com.extensionbox.app.modules.Module> = MODULES.mapNotNull { getModule(it.key) }

    fun getModule(key: String): com.extensionbox.app.modules.Module? {
        return when (key) {
            "battery" -> com.extensionbox.app.modules.BatteryModule()
            "screen_time" -> com.extensionbox.app.modules.ScreenTimeModule()
            "cpu" -> com.extensionbox.app.modules.CpuModule()
            "ram" -> com.extensionbox.app.modules.RamModule()
            "app_usage" -> com.extensionbox.app.modules.AppUsageModule()
            "sleep" -> com.extensionbox.app.modules.SleepModule()
            "network" -> com.extensionbox.app.modules.NetworkModule()
            "data" -> com.extensionbox.app.modules.DataUsageModule()
            "unlock" -> com.extensionbox.app.modules.UnlockModule()
            "storage" -> com.extensionbox.app.modules.StorageModule()
            "connection" -> com.extensionbox.app.modules.ConnectionModule()
            "uptime" -> com.extensionbox.app.modules.UptimeModule()
            "steps" -> com.extensionbox.app.modules.StepModule()
            "speedtest" -> com.extensionbox.app.modules.SpeedTestModule()
            "privacy" -> com.extensionbox.app.modules.PrivacyModule()
            else -> null
        }
    }
}
