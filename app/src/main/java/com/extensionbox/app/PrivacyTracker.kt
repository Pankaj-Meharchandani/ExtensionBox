package com.extensionbox.app

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.extensionbox.app.ui.screens.PrivacyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object PrivacyTracker {

    private const val PREF_KEY_RECORDED_EVENTS = "privacy_recorded_events_json"
    private const val MAX_EVENTS = 100
    private var isRegistered = false
    private val memoryEvents = ConcurrentHashMap.newKeySet<PrivacyEvent>()

    fun init(context: Context) {
        val appCtx = context.applicationContext
        loadPersistedEvents(appCtx)

        if (isRegistered) return
        isRegistered = true

        val mainHandler = Handler(Looper.getMainLooper())

        // 1. Camera Hardware Listener (Works 100% natively on stock Android without Root/Shizuku)
        try {
            val cameraManager = appCtx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            cameraManager?.registerAvailabilityCallback(
                object : CameraManager.AvailabilityCallback() {
                    override fun onCameraUnavailable(cameraId: String) {
                        super.onCameraUnavailable(cameraId)
                        val fgPkg = getForegroundPackage(appCtx)
                        if (fgPkg.isNotBlank() && fgPkg != appCtx.packageName) {
                            recordOpEvent(appCtx, fgPkg, "CAMERA")
                        }
                    }
                },
                mainHandler
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Audio/Microphone Recording Listener
        try {
            val audioManager = appCtx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.registerAudioRecordingCallback(
                object : AudioManager.AudioRecordingCallback() {
                    override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>?) {
                        super.onRecordingConfigChanged(configs)
                        if (!configs.isNullOrEmpty()) {
                            val fgPkg = getForegroundPackage(appCtx)
                            if (fgPkg.isNotBlank() && fgPkg != appCtx.packageName) {
                                recordOpEvent(appCtx, fgPkg, "RECORD_AUDIO")
                            }
                        }
                    }
                },
                mainHandler
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 3. AppOps Mode Watcher
        try {
            val appOps = appCtx.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            if (appOps != null) {
                val watchOps = arrayOf(
                    AppOpsManager.OPSTR_CAMERA,
                    AppOpsManager.OPSTR_RECORD_AUDIO,
                    AppOpsManager.OPSTR_FINE_LOCATION,
                    AppOpsManager.OPSTR_COARSE_LOCATION
                )
                for (op in watchOps) {
                    try {
                        appOps.startWatchingMode(op, null) { changedOp, pkg ->
                            val fgPkg = if (!pkg.isNullOrBlank()) pkg else getForegroundPackage(appCtx)
                            if (fgPkg.isNotBlank() && fgPkg != appCtx.packageName) {
                                val opName = when {
                                    changedOp.contains("camera", ignoreCase = true) -> "CAMERA"
                                    changedOp.contains("audio", ignoreCase = true) || changedOp.contains("record", ignoreCase = true) -> "RECORD_AUDIO"
                                    changedOp.contains("location", ignoreCase = true) -> "FINE_LOCATION"
                                    else -> "PRIVACY_ACCESS"
                                }
                                recordOpEvent(appCtx, fgPkg, opName)
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 4. AppOps Active Watcher (API 30+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val appOps = appCtx.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                appOps?.startWatchingActive(
                    arrayOf(
                        AppOpsManager.OPSTR_CAMERA,
                        AppOpsManager.OPSTR_RECORD_AUDIO,
                        AppOpsManager.OPSTR_FINE_LOCATION,
                        AppOpsManager.OPSTR_COARSE_LOCATION
                    ),
                    appCtx.mainExecutor
                ) { op, uid, pkgName, active ->
                    if (active) {
                        val resolvedPkg = if (!pkgName.isNullOrBlank()) pkgName else {
                            appCtx.packageManager.getPackagesForUid(uid)?.firstOrNull() ?: ""
                        }
                        val fgPkg = if (resolvedPkg.isNotBlank()) resolvedPkg else getForegroundPackage(appCtx)
                        if (fgPkg.isNotBlank() && fgPkg != appCtx.packageName) {
                            val opName = when {
                                op.contains("camera", ignoreCase = true) -> "CAMERA"
                                op.contains("audio", ignoreCase = true) || op.contains("record", ignoreCase = true) -> "RECORD_AUDIO"
                                op.contains("location", ignoreCase = true) -> "FINE_LOCATION"
                                else -> "PRIVACY_ACCESS"
                            }
                            recordOpEvent(appCtx, fgPkg, opName)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun recordOpEvent(context: Context, packageName: String, opName: String) {
        val pm = context.packageManager
        val label = try {
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            packageName.substringAfterLast('.')
        }

        recordEvent(
            context,
            PrivacyEvent(
                packageName = packageName,
                appLabel = label,
                opName = opName,
                lastAccessTime = System.currentTimeMillis()
            )
        )
    }

    @Synchronized
    fun recordEvent(context: Context, event: PrivacyEvent) {
        val duplicate = memoryEvents.find {
            it.packageName == event.packageName &&
            it.opName == event.opName &&
            kotlin.math.abs(it.lastAccessTime - event.lastAccessTime) < 2000L
        }
        if (duplicate != null) return

        memoryEvents.add(event)
        persistEvents(context)
    }

    fun getEvents(context: Context): List<PrivacyEvent> {
        if (memoryEvents.isEmpty()) {
            loadPersistedEvents(context)
        }
        return memoryEvents.toList().sortedByDescending { it.lastAccessTime }.take(MAX_EVENTS)
    }

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun getForegroundPackage(context: Context): String {
        try {
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            if (usm != null) {
                val now = System.currentTimeMillis()
                val events = usm.queryEvents(now - 10000L, now)
                val event = UsageEvents.Event()
                var lastResumedPkg = ""
                var maxTime = 0L

                while (events.hasNextEvent()) {
                    events.getNextEvent(event)
                    @Suppress("DEPRECATION")
                    if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                        event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                        if (event.timeStamp > maxTime && event.packageName != context.packageName) {
                            maxTime = event.timeStamp
                            lastResumedPkg = event.packageName
                        }
                    }
                }
                if (lastResumedPkg.isNotBlank()) return lastResumedPkg

                val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 60000L, now)
                val recent = stats?.maxByOrNull { it.lastTimeUsed }
                if (recent != null && recent.packageName != context.packageName) {
                    return recent.packageName
                }
            }
        } catch (_: Exception) {}

        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val processes = am?.runningAppProcesses
            val fgProc = processes?.find {
                it.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                it.processName != context.packageName
            }
            if (fgProc != null) return fgProc.processName
        } catch (_: Exception) {}

        return ""
    }

    private fun loadPersistedEvents(context: Context) {
        try {
            val jsonStr = Prefs.getString(context, PREF_KEY_RECORDED_EVENTS, null) ?: return
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                memoryEvents.add(
                    PrivacyEvent(
                        packageName = obj.optString("packageName"),
                        appLabel = obj.optString("appLabel"),
                        opName = obj.optString("opName"),
                        lastAccessTime = obj.optLong("lastAccessTime")
                    )
                )
            }
        } catch (_: Exception) {}
    }

    private fun persistEvents(context: Context) {
        try {
            val array = JSONArray()
            memoryEvents.sortedByDescending { it.lastAccessTime }.take(MAX_EVENTS).forEach { ev ->
                val obj = JSONObject().apply {
                    put("packageName", ev.packageName)
                    put("appLabel", ev.appLabel)
                    put("opName", ev.opName)
                    put("lastAccessTime", ev.lastAccessTime)
                }
                array.put(obj)
            }
            Prefs.setString(context, PREF_KEY_RECORDED_EVENTS, array.toString())
        } catch (_: Exception) {}
    }
}
