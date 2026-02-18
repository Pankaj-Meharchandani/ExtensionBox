package com.extensionbox.app.modules;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;

import com.extensionbox.app.Prefs;
import com.extensionbox.app.SystemAccess;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Locale;

public class SpeedTestModule implements Module {

    private Context ctx;
    private boolean running = false;
    private Handler handler;
    private Runnable testRunnable;

    private String result = "Waiting...";
    private String pingResult = "—";
    private long lastTestTime = 0;
    private int testsToday = 0;

    @Override public String key() { return "speedtest"; }
    @Override public String name() { return "Speed Test"; }
    @Override public String emoji() { return "🏎"; }
    @Override public String description() { return "Periodic download speed test"; }
    @Override public boolean defaultEnabled() { return false; }
    @Override public boolean alive() { return running; }

    @Override
    public int tickIntervalMs() {
        return ctx != null ? Prefs.getInt(ctx, "spd_interval", 30000) : 30000;
    }

    @Override
    public void start(Context c, SystemAccess sys) {
        ctx = c;
        running = true;
        handler = new Handler(Looper.getMainLooper());

        boolean autoTest = Prefs.getBool(c, "spd_auto_test", true);
        if (autoTest) {
            int freqMin = Prefs.getInt(c, "spd_test_freq", 60);
            testRunnable = new Runnable() {
                @Override
                public void run() {
                    if (!running) return;
                    runTest();
                    handler.postDelayed(this, freqMin * 60000L);
                }
            };
            handler.postDelayed(testRunnable, 10000);
        }
    }

    @Override
    public void stop() {
        running = false;
        if (handler != null) handler.removeCallbacksAndMessages(null);
    }

    @Override public void tick() { }

    public void runTestNow() { runTest(); }

    private void runTest() {
        if (ctx == null) return;

        boolean wifiOnly = Prefs.getBool(ctx, "spd_wifi_only", true);
        if (wifiOnly && !isOnWifi()) {
            result = "Skipped (not WiFi)";
            lastTestTime = System.currentTimeMillis();
            return;
        }

        int dailyLimit = Prefs.getInt(ctx, "spd_daily_limit", 10);
        if (dailyLimit > 0 && testsToday >= dailyLimit) {
            result = "Daily limit reached";
            lastTestTime = System.currentTimeMillis();
            return;
        }

        result = "Testing...";
        new Thread(() -> {
            try {
                String testUrl = Prefs.getString(ctx, "spd_test_url",
                        "http://speedtest.tele2.net/1MB.zip");

                if (Prefs.getBool(ctx, "spd_show_ping", true)) {
                    try {
                        URL u = new URL(testUrl);
                        long ps = System.currentTimeMillis();
                        Socket sock = new Socket(u.getHost(), u.getPort() == -1 ? 80 : u.getPort());
                        long pe = System.currentTimeMillis();
                        sock.close();
                        pingResult = (pe - ps) + "ms";
                    } catch (Exception e) {
                        pingResult = "—";
                    }
                }

                HttpURLConnection c = (HttpURLConnection) new URL(testUrl).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(20000);
                long start = System.currentTimeMillis();
                InputStream is = c.getInputStream();
                byte[] buf = new byte[16384];
                long total = 0;
                int r;
                while ((r = is.read(buf)) != -1) total += r;
                long ms = System.currentTimeMillis() - start;
                is.close();
                c.disconnect();
                if (ms > 0) {
                    double mbps = (total * 8.0) / (ms / 1000.0) / 1_000_000.0;
                    result = String.format(Locale.US, "%.1f Mbps", mbps);
                }
                testsToday++;
            } catch (Exception e) {
                result = "Failed";
            }
            lastTestTime = System.currentTimeMillis();
        }).start();
    }

    private boolean isOnWifi() {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network net = cm.getActiveNetwork();
            if (net == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(net);
            return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Exception e) { return false; }
    }

    @Override
    public String compact() {
        return "🏎" + result;
    }

    @Override
    public String detail() {
        long ago = lastTestTime > 0 ? (System.currentTimeMillis() - lastTestTime) / 60000 : -1;
        String agoStr = ago < 0 ? "" : ago < 1 ? " (just now)" : " (" + ago + "m ago)";
        String ping = Prefs.getBool(ctx, "spd_show_ping", true) ? " • Ping: " + pingResult : "";
        return "🏎 Speed: " + result + ping + agoStr +
                "\n   Tests today: " + testsToday;
    }

    @Override
    public LinkedHashMap<String, String> dataPoints() {
        LinkedHashMap<String, String> d = new LinkedHashMap<>();
        d.put("speedtest.result", result);
        d.put("speedtest.ping", pingResult);
        d.put("speedtest.tests_today", String.valueOf(testsToday));
        return d;
    }

    @Override public void checkAlerts(Context ctx) { }
}
