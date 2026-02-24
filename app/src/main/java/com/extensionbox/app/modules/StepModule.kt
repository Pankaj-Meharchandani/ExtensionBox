package com.extensionbox.app.modules

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.extensionbox.app.Fmt
import com.extensionbox.app.Prefs
import com.extensionbox.app.R
import com.extensionbox.app.SystemAccess
import java.util.Calendar
import java.util.LinkedHashMap
import java.util.Locale
import kotlin.math.abs

class StepModule : Module, SensorEventListener {

    private var ctx: Context? = null
    private var sm: SensorManager? = null
    private var running = false
    private var lastRaw = -1f
    private var dailySteps = 0L
    private var sensorAvailable = true
    private var permissionDenied = false

    override fun key(): String = "steps"
    override fun name(): String = "Step Counter"
    override fun emoji(): String = "👣"
    override fun description(): String = "Steps and distance"
    override fun defaultEnabled(): Boolean = false
    override fun alive(): Boolean = running
    override fun priority(): Int = 70

    override fun tickIntervalMs(): Int = ctx?.let { Prefs.getInt(it, "stp_interval", 10000) } ?: 10000

    override fun start(ctx: Context, sys: SystemAccess) {
        this.ctx = ctx
        dailySteps = Prefs.getLong(ctx, "stp_today", 0)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
                permissionDenied = true
                sensorAvailable = false
                running = true
                return
            }
        }

        permissionDenied = false
        sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val s = sm?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (s != null) {
            sm?.registerListener(this, s, SensorManager.SENSOR_DELAY_UI)
            sensorAvailable = true
        } else {
            sensorAvailable = false
        }
        running = true
    }

    override fun stop() {
        if (sm != null) {
            try {
                sm?.unregisterListener(this)
            } catch (ignored: Exception) {
            }
        }
        sm = null
        running = false
    }

    override fun tick() {
        val c = ctx ?: return
        if (permissionDenied && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(c, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED) {
                permissionDenied = false
                sm = c.getSystemService(Context.SENSOR_SERVICE) as SensorManager
                val s = sm?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
                if (s != null) {
                    sm?.registerListener(this, s, SensorManager.SENSOR_DELAY_UI)
                    sensorAvailable = true
                } else {
                    sensorAvailable = false
                }
            }
        }

        val today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        val lastDay = Prefs.getInt(c, "stp_last_day", -1)
        if (lastDay != -1 && lastDay != today) {
            Prefs.setLong(c, "stp_yesterday", dailySteps)
            dailySteps = 0
            lastRaw = -1f
            Prefs.setLong(c, "stp_today", 0)
            Prefs.setBool(c, "stp_goal_fired", false)
        }
        Prefs.setInt(c, "stp_last_day", today)
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type != Sensor.TYPE_STEP_COUNTER) return
        val cur = e.values[0]
        if (lastRaw >= 0) {
            val delta = cur - lastRaw
            if (delta > 0 && delta < 5000) {
                dailySteps += delta.toLong()
                ctx?.let { Prefs.setLong(it, "stp_today", dailySteps) }
            }
        }
        lastRaw = cur
    }

    override fun onAccuracyChanged(s: Sensor, a: Int) {}

    override fun compact(): String = when {
        permissionDenied -> "👣Perm"
        !sensorAvailable -> "👣N/A"
        else -> "👣${Fmt.number(dailySteps)}"
    }

    override fun detail(): String {
        if (permissionDenied) return "👣 Permission needed (ACTIVITY_RECOGNITION)"
        if (!sensorAvailable) return "👣 Step sensor not available"

        val sb = StringBuilder()
        val c = ctx ?: return "👣 No data"
        val goal = Prefs.getInt(c, "stp_goal", 10000)
        val strideCm = Prefs.getInt(c, "stp_stride_cm", 75)
        val km = dailySteps * strideCm / 100000.0

        if (goal > 0 && Prefs.getBool(c, "stp_show_goal", true)) {
            val pct = dailySteps * 100f / goal
            sb.append("👣 Steps: ${Fmt.number(dailySteps)} / ${Fmt.number(goal.toLong())} (${String.format(Locale.US, "%.0f%%", pct)})\n")
        } else {
            sb.append("👣 Steps: ${Fmt.number(dailySteps)}\n")
        }

        if (Prefs.getBool(c, "stp_show_distance", true)) {
            sb.append("   Distance: ${String.format(Locale.US, "%.1f km", km)}")
        }

        if (Prefs.getBool(c, "stp_show_yesterday", true)) {
            val y = Prefs.getLong(c, "stp_yesterday", 0)
            if (y > 0) {
                val diff = dailySteps - y
                val cmp = if (diff <= 0) "↓${Fmt.number(abs(diff))}" else "↑${Fmt.number(diff)}"
                sb.append("\n   Yesterday: ${Fmt.number(y)} ($cmp)")
            }
        }
        return sb.toString()
    }

    override fun dataPoints(): LinkedHashMap<String, String> {
        val d = LinkedHashMap<String, String>()
        if (permissionDenied) {
            d["steps.status"] = "Permission needed"
            return d
        }
        if (!sensorAvailable) {
            d["steps.status"] = "Sensor not available"
            return d
        }
        val c = ctx ?: return d
        val strideCm = Prefs.getInt(c, "stp_stride_cm", 75)
        val km = dailySteps * strideCm / 100000.0
        d["steps.today"] = Fmt.number(dailySteps)
        d["steps.distance"] = String.format(Locale.US, "%.1f km", km)
        val goal = Prefs.getInt(c, "stp_goal", 10000)
        if (goal > 0) d["steps.goal"] = Fmt.number(goal.toLong())
        val y = Prefs.getLong(c, "stp_yesterday", 0)
        if (y > 0) {
            d["steps.yesterday"] = Fmt.number(y)
            val diff = dailySteps - y
            d["steps.vs_yesterday"] = if (diff <= 0) "↓${Fmt.number(abs(diff))}" else "↑${Fmt.number(diff)}"
        }
        return d
    }

    override fun checkAlerts(ctx: Context) {
        if (permissionDenied || !sensorAvailable) return
        val goal = Prefs.getInt(ctx, "stp_goal", 10000)
        if (goal <= 0) return
        val fired = Prefs.getBool(ctx, "stp_goal_fired", false)
        if (dailySteps >= goal.toLong() && !fired) {
            try {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(2008, NotificationCompat.Builder(ctx, "ebox_alerts")
                    .setSmallIcon(R.drawable.ic_notif)
                    .setContentTitle("🎉 Step Goal Reached!")
                    .setContentText("${Fmt.number(dailySteps)} steps today!")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true).build())
            } catch (ignored: Exception) {
            }
            vibrate(ctx, longArrayOf(0, 150, 50, 150, 50, 300)) // Triple celebratory pulse
            Prefs.setBool(ctx, "stp_goal_fired", true)
        }
    }
}
