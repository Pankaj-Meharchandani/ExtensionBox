package com.extensionbox.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;

import androidx.core.app.NotificationCompat;

import com.extensionbox.app.modules.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MonitorService extends Service {

    public static final String ACTION_STOP = "com.extensionbox.STOP";
    private static final String MONITOR_CH = "ebox_monitor";
    private static final String ALERT_CH = "ebox_alerts";
    private static final int NOTIF_ID = 1001;

    private SystemAccess sysAccess;
    private List<Module> modules;
    private Map<String, Long> lastTickTime;
    private Handler handler;
    private Runnable tickRunnable;

    // Static data holder for Dashboard (Sprint 3)
    private static final Map<String, LinkedHashMap<String, String>> moduleData = new HashMap<>();

    public static LinkedHashMap<String, String> getModuleData(String key) {
        return moduleData.get(key);
    }

    public static List<String> getAliveModuleKeys() {
        return new ArrayList<>(moduleData.keySet());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();

        // Must call startForeground quickly to avoid ANR
        startForeground(NOTIF_ID, buildNotification());

        sysAccess = new SystemAccess(this);
        initModules();
        lastTickTime = new HashMap<>();
        syncModules();

        handler = new Handler(Looper.getMainLooper());
        tickRunnable = () -> {
            doTickCycle();
            scheduleNextTick();
        };
        scheduleNextTick();

        Prefs.setRunning(this, true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopAll();
            Prefs.setRunning(this, false);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopAll();
        if (handler != null) handler.removeCallbacksAndMessages(null);
        Prefs.setRunning(this, false);
        moduleData.clear();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    // ── Module lifecycle ──

    private void initModules() {
        modules = new ArrayList<>();
        modules.add(new BatteryModule());
        modules.add(new NetworkModule());
        modules.add(new UnlockModule());
    }

    private void syncModules() {
        for (Module m : modules) {
            boolean shouldRun = Prefs.isModuleEnabled(this, m.key(), m.defaultEnabled());
            if (shouldRun && !m.alive()) {
                m.start(this, sysAccess);
                lastTickTime.put(m.key(), 0L);
            } else if (!shouldRun && m.alive()) {
                m.stop();
                moduleData.remove(m.key());
                lastTickTime.remove(m.key());
            }
        }
    }

    private void doTickCycle() {
        syncModules();

        long now = SystemClock.elapsedRealtime();
        boolean changed = false;

        for (Module m : modules) {
            if (!m.alive()) continue;

            Long last = lastTickTime.get(m.key());
            if (last == null) last = 0L;

            if (now - last >= m.tickIntervalMs()) {
                m.tick();
                m.checkAlerts(this);
                lastTickTime.put(m.key(), now);
                moduleData.put(m.key(), m.dataPoints());
                changed = true;
            }
        }

        if (changed) {
            updateNotification();
        }
    }

    private void scheduleNextTick() {
        long now = SystemClock.elapsedRealtime();
        long minDelay = Long.MAX_VALUE;

        for (Module m : modules) {
            if (!m.alive()) continue;
            Long last = lastTickTime.get(m.key());
            if (last == null) last = 0L;
            long nextDue = last + m.tickIntervalMs();
            long delay = nextDue - now;
            if (delay < minDelay) minDelay = delay;
        }

        // Clamp between 1s and 60s
        if (minDelay < 1000) minDelay = 1000;
        if (minDelay > 60000) minDelay = 60000;

        // If no modules alive, check every 5s for module changes
        if (minDelay == Long.MAX_VALUE) minDelay = 5000;

        handler.postDelayed(tickRunnable, minDelay);
    }

    private void stopAll() {
        if (modules == null) return;
        for (Module m : modules) {
            if (m.alive()) m.stop();
        }
        moduleData.clear();
    }

    // ── Notification ──

    private void createChannels() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        NotificationChannel monCh = new NotificationChannel(
                MONITOR_CH, "Extension Box Monitor", NotificationManager.IMPORTANCE_LOW);
        monCh.setDescription("Continuous system monitoring");
        monCh.setShowBadge(false);
        monCh.enableVibration(false);
        monCh.setSound(null, null);
        nm.createNotificationChannel(monCh);

        NotificationChannel alertCh = new NotificationChannel(
                ALERT_CH, "Extension Box Alerts", NotificationManager.IMPORTANCE_HIGH);
        alertCh.setDescription("Important alerts: battery low, limits reached");
        nm.createNotificationChannel(alertCh);
    }

    private Notification buildNotification() {
        String title = buildTitle();
        String compact = buildCompact();
        String expanded = buildExpanded();

        Intent openI = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, openI,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopI = new Intent(this, MonitorService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stopI,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, MONITOR_CH)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(title)
                .setContentText(compact)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(expanded))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setContentIntent(openPi)
                .addAction(0, "■ Stop", stopPi)
                .setShowWhen(false)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
    }

    private String buildTitle() {
        for (Module m : modules) {
            if (m.key().equals("battery") && m.alive()) {
                if (m instanceof BatteryModule) {
                    return "Extension Box • " + ((BatteryModule) m).getLevel() + "%";
                }
            }
        }
        return "Extension Box";
    }

    private String buildCompact() {
        List<String> parts = new ArrayList<>();
        for (Module m : modules) {
            if (!m.alive()) continue;
            String c = m.compact();
            if (c != null && !c.isEmpty()) parts.add(c);
            if (parts.size() >= 4) break;
        }
        return parts.isEmpty() ? "All extensions disabled" : TextUtils.join(" • ", parts);
    }

    private String buildExpanded() {
        List<String> lines = new ArrayList<>();
        for (Module m : modules) {
            if (!m.alive()) continue;
            String d = m.detail();
            if (d != null && !d.isEmpty()) lines.add(d);
        }
        return lines.isEmpty()
                ? "Enable extensions from the app"
                : TextUtils.join("\n", lines);
    }

    private void updateNotification() {
        try {
            NotificationManager nm = (NotificationManager)
                    getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, buildNotification());
        } catch (Exception ignored) {}
    }
}