package com.extensionbox.app.modules

import android.content.Context
import android.os.SystemClock
import com.extensionbox.app.Fmt
import com.extensionbox.app.Prefs
import com.extensionbox.app.SystemAccess
import java.util.LinkedHashMap
import java.util.Locale
import kotlin.math.max

class SleepModule : Module {

    private var ctx: Context? = null
    private var running = false
    private var elapsedStart: Long = 0
    private var uptimeStart: Long = 0

    override fun key(): String = "sleep"
    override fun name(): String = "Deep Sleep"
    override fun emoji(): String = "😴"
    override fun description(): String = "CPU sleep vs awake ratio"
    override fun defaultEnabled(): Boolean = true
    override fun alive(): Boolean = running
    override fun priority(): Int = 30

    override fun tickIntervalMs(): Int = ctx?.let { Prefs.getInt(it, "slp_interval", 30000) } ?: 30000

    override fun start(ctx: Context, sys: SystemAccess) {
        this.ctx = ctx
        elapsedStart = SystemClock.elapsedRealtime()
        uptimeStart = SystemClock.uptimeMillis()
        running = true
    }

    override fun stop() {
        running = false
    }

    override fun tick() {}

    private fun deepPct(): Float {
        val el = SystemClock.elapsedRealtime() - elapsedStart
        val up = SystemClock.uptimeMillis() - uptimeStart
        val ds = max(0, el - up)
        return if (el > 0) ds * 100f / el else 0f
    }

    override fun compact(): String = "Sleep:${deepPct().toInt()}%"

    override fun detail(): String {
        val el = SystemClock.elapsedRealtime() - elapsedStart
        val up = SystemClock.uptimeMillis() - uptimeStart
        val ds = max(0, el - up)
        val pct = deepPct()
        return "😴 Deep Sleep: ${Fmt.duration(ds)} (${String.format(Locale.US, "%.1f%%", pct)})\n" +
               "   Awake: ${Fmt.duration(up)} (${String.format(Locale.US, "%.1f%%", 100 - pct)})"
    }

    override fun dataPoints(): LinkedHashMap<String, String> {
        val el = SystemClock.elapsedRealtime() - elapsedStart
        val up = SystemClock.uptimeMillis() - uptimeStart
        val ds = max(0, el - up)
        val pct = deepPct()
        val d = LinkedHashMap<String, String>()
        d["sleep.deep_time"] = Fmt.duration(ds)
        d["sleep.deep_percentage"] = Fmt.percentage(pct)
        d["sleep.awake_time"] = Fmt.duration(up)
        d["sleep.awake_percentage"] = Fmt.percentage(100 - pct)
        return d
    }

    override fun checkAlerts(ctx: Context) {}
}
