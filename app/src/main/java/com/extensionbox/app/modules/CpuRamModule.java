package com.extensionbox.app.modules;

import android.app.ActivityManager;
import android.app.NotificationManager;
import android.content.Context;

import androidx.core.app.NotificationCompat;

import com.extensionbox.app.Fmt;
import com.extensionbox.app.Prefs;
import com.extensionbox.app.R;
import com.extensionbox.app.SystemAccess;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.LinkedHashMap;
import java.util.Locale;

public class CpuRamModule implements Module {
    private Context ctx;
    private boolean running = false;

    private long[] prevCpuTimes;
    private float cpuUsage = 0;
    private long ramUsed, ramTotal, ramAvail;

    @Override public String key() { return "cpu_ram"; }
    @Override public String name() { return "CPU & RAM"; }
    @Override public String emoji() { return "🧠"; }
    @Override public String description() { return "CPU usage, memory status"; }
    @Override public boolean defaultEnabled() { return true; }
    @Override public boolean alive() { return running; }

    @Override
    public int tickIntervalMs() {
        return ctx != null ? Prefs.getInt(ctx, "cpu_interval", 5000) : 5000;
    }

    @Override
    public void start(Context c, SystemAccess sys) {
        ctx = c;
        prevCpuTimes = readCpuTimes();
        running = true;
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public void tick() {
        long[] current = readCpuTimes();
        if (prevCpuTimes != null && current != null) {
            long prevIdle = prevCpuTimes[3] + prevCpuTimes[4];
            long currIdle = current[3] + current[4];
            long prevTotal = 0, currTotal = 0;
            for (long v : prevCpuTimes) prevTotal += v;
            for (long v : current) currTotal += v;
            long totalDiff = currTotal - prevTotal;
            long idleDiff = currIdle - prevIdle;
            if (totalDiff > 0) {
                cpuUsage = (totalDiff - idleDiff) * 100f / totalDiff;
            }
        }
        prevCpuTimes = current;

        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            ramTotal = mi.totalMem;
            ramAvail = mi.availMem;
            ramUsed = ramTotal - ramAvail;
        } catch (Exception ignored) {}
    }

    private long[] readCpuTimes() {
        try {
            BufferedReader br = new BufferedReader(new FileReader("/proc/stat"));
            String line = br.readLine();
            br.close();
            if (line != null && line.startsWith("cpu ")) {
                String[] parts = line.substring(4).trim().split("\\s+");
                long[] times = new long[Math.min(parts.length, 7)];
                for (int i = 0; i < times.length; i++) {
                    times[i] = Long.parseLong(parts[i]);
                }
                return times;
            }
        } catch (Exception ignored) {}
        return null;
    }

    @Override
    public String compact() {
        float ramPct = ramTotal > 0 ? ramUsed * 100f / ramTotal : 0;
        return String.format(Locale.US, "CPU:%.0f%% RAM:%.0f%%", cpuUsage, ramPct);
    }

    @Override
    public String detail() {
        float ramPct = ramTotal > 0 ? ramUsed * 100f / ramTotal : 0;
        return String.format(Locale.US,
                "🧠 CPU: %.1f%%\n   RAM: %s / %s (%.0f%%)\n   Available: %s",
                cpuUsage, Fmt.bytes(ramUsed), Fmt.bytes(ramTotal), ramPct, Fmt.bytes(ramAvail));
    }

    @Override
    public LinkedHashMap<String, String> dataPoints() {
        LinkedHashMap<String, String> d = new LinkedHashMap<>();
        float ramPct = ramTotal > 0 ? ramUsed * 100f / ramTotal : 0;
        d.put("cpu.usage", String.format(Locale.US, "%.1f%%", cpuUsage));
        d.put("ram.used", Fmt.bytes(ramUsed));
        d.put("ram.total", Fmt.bytes(ramTotal));
        d.put("ram.available", Fmt.bytes(ramAvail));
        d.put("ram.pct", String.format(Locale.US, "%.0f%%", ramPct));
        return d;
    }

    @Override
    public void checkAlerts(Context c) {
        boolean alertOn = Prefs.getBool(c, "cpu_ram_alert", false);
        int thresh = Prefs.getInt(c, "cpu_ram_thresh", 90);
        boolean fired = Prefs.getBool(c, "cpu_ram_alert_fired", false);
        float ramPct = ramTotal > 0 ? ramUsed * 100f / ramTotal : 0;

        if (alertOn && ramPct >= thresh && !fired) {
            try {
                NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
                nm.notify(2003, new NotificationCompat.Builder(c, "ebox_alerts")
                        .setSmallIcon(R.drawable.ic_notif)
                        .setContentTitle("🔴 High RAM Usage")
                        .setContentText("RAM at " + String.format(Locale.US, "%.0f%%", ramPct))
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true).build());
            } catch (Exception ignored) {}
            Prefs.setBool(c, "cpu_ram_alert_fired", true);
        }
        if (fired && ramPct < thresh - 5) {
            Prefs.setBool(c, "cpu_ram_alert_fired", false);
        }
    }
}
