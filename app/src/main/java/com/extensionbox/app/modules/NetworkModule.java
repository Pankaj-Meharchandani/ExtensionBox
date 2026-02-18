package com.extensionbox.app.modules;

import android.content.Context;
import android.net.TrafficStats;

import com.extensionbox.app.Fmt;
import com.extensionbox.app.Prefs;
import com.extensionbox.app.SystemAccess;

import java.util.LinkedHashMap;
import java.util.Locale;

public class NetworkModule implements Module {

    private Context ctx;
    private boolean running = false;
    private long prevRx, prevTx, prevTime;
    private long dlSpeed, ulSpeed;

    @Override public String key() { return "network"; }
    @Override public String name() { return "Network Speed"; }
    @Override public String emoji() { return "📶"; }
    @Override public String description() { return "Real-time download and upload speed"; }
    @Override public boolean defaultEnabled() { return true; }
    @Override public boolean alive() { return running; }

    @Override
    public int tickIntervalMs() {
        return ctx != null ? Prefs.getInt(ctx, "net_interval", 3000) : 3000;
    }

    @Override
    public void start(Context c, SystemAccess sys) {
        ctx = c;
        prevRx = TrafficStats.getTotalRxBytes();
        prevTx = TrafficStats.getTotalTxBytes();
        prevTime = System.currentTimeMillis();
        dlSpeed = 0;
        ulSpeed = 0;
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        dlSpeed = 0;
        ulSpeed = 0;
    }

    @Override
    public void tick() {
        long rx = TrafficStats.getTotalRxBytes();
        long tx = TrafficStats.getTotalTxBytes();

        if (rx == TrafficStats.UNSUPPORTED || tx == TrafficStats.UNSUPPORTED) {
            dlSpeed = 0;
            ulSpeed = 0;
            return;
        }

        long now = System.currentTimeMillis();
        long dt = now - prevTime;

        if (dt > 0) {
            dlSpeed = Math.max(0, (rx - prevRx) * 1000 / dt);
            ulSpeed = Math.max(0, (tx - prevTx) * 1000 / dt);
        }

        prevRx = rx;
        prevTx = tx;
        prevTime = now;
    }

    @Override
    public String compact() {
        return "↓" + Fmt.speed(dlSpeed) + " ↑" + Fmt.speed(ulSpeed);
    }

    @Override
    public String detail() {
        return String.format(Locale.US,
                "📶 Download: %s\n   Upload: %s",
                Fmt.speed(dlSpeed), Fmt.speed(ulSpeed));
    }

    @Override
    public LinkedHashMap<String, String> dataPoints() {
        LinkedHashMap<String, String> d = new LinkedHashMap<>();
        d.put("net.download", Fmt.speed(dlSpeed));
        d.put("net.upload", Fmt.speed(ulSpeed));
        return d;
    }

    @Override
    public void checkAlerts(Context ctx) {
        // No alerts for network speed
    }
}