package com.extensionbox.app

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.location.GnssStatus
import android.location.LocationManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.extensionbox.app.ui.screens.PrivacyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object PrivacyTracker {

    private const val PREF_KEY_RECORDED_EVENTS = "privacy_recorded_events_json"
    private const val MAX_EVENTS = 100
    private var isRegistered = false
    private val memoryEvents = ConcurrentHashMap.newKeySet<PrivacyEvent>()

    @SuppressLint("MissingPermission")
    fun init(context: Context) {
        val appCtx = context.applicationContext
        loadPersistedEvents(appCtx)

        if (isRegistered) return
        isRegistered = true

        val mainHandler = Handler(Looper.getMainLooper())

        // 1. Camera Hardware Listener
        try {
            val cameraManager = appCtx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            cameraManager?.registerAvailabilityCallback(
                object : CameraManager.AvailabilityCallback() {
                    override fun onCameraUnavailable(cameraId: String) {
                        super.onCameraUnavailable(cameraId)
                        val fgPkg = getForegroundPackage(appCtx)
                        val targetPkg = if (fgPkg.isNotBlank()) fgPkg else getDefaultCameraPackage(appCtx)
                        if (targetPkg.isNotBlank() && targetPkg != appCtx.packageName) {
                            recordOpEvent(appCtx, targetPkg, "CAMERA")
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

        // 3. Location GPS/GNSS Hardware Listener (Natively captures Location access)
        try {
            val locationManager = appCtx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (locationManager != null) {
                val gnssCallback = object : GnssStatus.Callback() {
                    override fun onStarted() {
                        super.onStarted()
                        val fgPkg = getForegroundPackage(appCtx)
                        if (fgPkg.isNotBlank() && fgPkg != appCtx.packageName) {
                            recordOpEvent(appCtx, fgPkg, "FINE_LOCATION")
                        }
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    locationManager.registerGnssStatusCallback(ContextCompat.getMainExecutor(appCtx), gnssCallback)
                } else {
                    @Suppress("DEPRECATION")
                    locationManager.registerGnssStatusCallback(gnssCallback, mainHandler)
                }
            }
        } catch (_: SecurityException) {
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 4. Clipboard Listener (Ensured on Main Looper)
        mainHandler.post {
            try {
                val clipboard = appCtx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.addPrimaryClipChangedListener {
                    val fgPkg = getForegroundPackage(appCtx)
                    if (fgPkg.isNotBlank() && fgPkg != appCtx.packageName) {
                        recordOpEvent(appCtx, fgPkg, "READ_CLIPBOARD")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 5. AppOps Mode Watcher for Location, Camera, Mic & Clipboard
        try {
            val appOps = appCtx.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            if (appOps != null) {
                val watchOps = arrayOf(
                    AppOpsManager.OPSTR_CAMERA,
                    AppOpsManager.OPSTR_RECORD_AUDIO,
                    AppOpsManager.OPSTR_FINE_LOCATION,
                    AppOpsManager.OPSTR_COARSE_LOCATION,
                    AppOpsManager.OPSTR_MONITOR_LOCATION,
                    AppOpsManager.OPSTR_MONITOR_HIGH_POWER_LOCATION,
                    "android:read_clipboard"
                )
                for (op in watchOps) {
                    try {
                        appOps.startWatchingMode(op, null) { changedOp, pkg ->
                            val fgPkg = if (!pkg.isNullOrBlank()) pkg else getForegroundPackage(appCtx)
                            if (fgPkg.isNotBlank() && fgPkg != appCtx.packageName) {
                                val opName = when {
                                    changedOp.contains("camera", ignoreCase = true) -> "CAMERA"
                                    changedOp.contains("audio", ignoreCase = true) || changedOp.contains("record", ignoreCase = true) -> "RECORD_AUDIO"
                                    changedOp.contains("location", ignoreCase = true) || changedOp.contains("gps", ignoreCase = true) || changedOp.contains("fine", ignoreCase = true) -> "FINE_LOCATION"
                                    changedOp.contains("clipboard", ignoreCase = true) -> "READ_CLIPBOARD"
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

        // 6. AppOps Active Watcher (API 30+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val appOps = appCtx.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                appOps?.startWatchingActive(
                    arrayOf(
                        AppOpsManager.OPSTR_CAMERA,
                        AppOpsManager.OPSTR_RECORD_AUDIO,
                        AppOpsManager.OPSTR_FINE_LOCATION,
                        AppOpsManager.OPSTR_COARSE_LOCATION,
                        AppOpsManager.OPSTR_MONITOR_LOCATION,
                        AppOpsManager.OPSTR_MONITOR_HIGH_POWER_LOCATION
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
                                op.contains("location", ignoreCase = true) || op.contains("fine", ignoreCase = true) -> "FINE_LOCATION"
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

    fun getDefaultCameraPackage(context: Context): String {
        try {
            val pm = context.packageManager
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            val resolveInfo = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?: pm.resolveActivity(intent, 0)
            if (resolveInfo != null && resolveInfo.activityInfo != null) {
                val pkg = resolveInfo.activityInfo.packageName
                if (!pkg.isNullOrBlank() && pkg != context.packageName) return pkg
            }
        } catch (_: Exception) {}

        val commonCameraPkgs = listOf(
            "com.google.android.GoogleCamera",
            "com.sec.android.app.camera",
            "com.android.camera",
            "com.android.camera2",
            "com.oneplus.camera",
            "com.miui.camera",
            "com.oppo.camera",
            "com.vivo.camera"
        )
        for (pkg in commonCameraPkgs) {
            try {
                context.packageManager.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: Exception) {}
        }
        return ""
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
                val events = usm.queryEvents(now - 15000L, now)
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
                val recent = stats?.filter { it.packageName != context.packageName }?.maxByOrNull { it.lastTimeUsed }
                if (recent != null) {
                    return recent.packageName
                }
            }
        } catch (_: Exception) {}

        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val processes = am?.runningAppProcesses
            val fgProc = processes?.find {
                it.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE &&
                it.processName != context.packageName &&
                !it.processName.contains("systemui", ignoreCase = true) &&
                !it.processName.contains("launcher", ignoreCase = true)
            }
            if (fgProc != null && fgProc.processName.contains(".")) return fgProc.processName
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
