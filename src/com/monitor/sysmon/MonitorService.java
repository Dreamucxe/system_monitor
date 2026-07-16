package com.monitor.sysmon;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.app.ActivityManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

/**
 * Foreground service that samples the device every 60s: RAM + battery temp are
 * logged to RamLog (rolling 24h history), CPU/RAM/temp/net are published to
 * WidgetState and pushed to the home-screen widget, and each metric is checked
 * against the user's alert thresholds (AlertPrefs).
 *
 * Runs whenever logging OR alerts are enabled.
 */
public class MonitorService extends Service {

    public static final String PREFS = "sysmon_prefs";
    public static final String KEY_LOGGING = "logging_enabled";
    private static final String CHANNEL = "sysmon_monitor";
    private static final String ALERT_CHANNEL = "sysmon_alerts";
    private static final int NOTIF_ID = 1001;
    private static final int ALERT_BASE_ID = 1100;   // + metric index
    private static final long INTERVAL_MS = 60_000L; // 60 seconds

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running = false;

    private final SysInfo sys = new SysInfo();
    private final NetStats net = new NetStats();
    // per-metric latch: true while we've already fired and not yet recovered
    private final boolean[] alertLatched = new boolean[AlertPrefs.COUNT];

    @Override
    public void onCreate() {
        super.onCreate();
        sys.seedCpu();
        net.seed();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) {
            running = true;
            startForeground(NOTIF_ID, buildNotification("Monitoring…"));
            handler.post(sampler);
        }
        return START_STICKY;
    }

    private final Runnable sampler = new Runnable() {
        @Override public void run() {
            sampleOnce();
            handler.postDelayed(this, INTERVAL_MS);
        }
    };

    private void sampleOnce() {
        try {
            // ---- RAM ----
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            long total = mi.totalMem;
            long used = total - mi.availMem;
            double ramPct = total > 0 ? (100.0 * used / total) : 0;

            // ---- battery temp ----
            SysInfo.Battery bat = SysInfo.readBattery(this);
            double tempC = bat.tempC;
            int tempTenths = tempC > -100 ? (int) Math.round(tempC * 10) : RamLog.TEMP_ABSENT;

            // ---- CPU ----
            SysInfo.Cpu cpu = sys.sampleCpu();
            double cpuPct = cpu.pct; // may be -1

            // ---- network (download bytes/sec) ----
            NetStats.Net n = net.sampleNet(this);
            double netDown = n.valid ? n.rxTotal : 0;

            long now = System.currentTimeMillis();

            // log history only when logging is enabled
            if (isLogging(this)) {
                RamLog.append(this, now, used, total, tempTenths);
            }

            // publish widget snapshot + refresh widgets
            WidgetState.save(this, cpuPct, ramPct, tempC, netDown, now);
            MonitorWidget.refresh(this);

            // evaluate alerts
            checkAlerts(cpuPct, ramPct, tempC, bat.level);

            updateNotification(String.format("RAM %.0f%% • %.1f°C%s",
                    ramPct, tempC, isLogging(this) ? " • logging 24h" : ""));
        } catch (Exception ignored) {}
    }

    // ---------------- Alerts ----------------

    private void checkAlerts(double cpuPct, double ramPct, double tempC, int battLevel) {
        // CPU: alert when ABOVE threshold
        evalHigh(AlertPrefs.M_CPU, cpuPct, "CPU usage high",
                String.format("CPU at %.0f%%", cpuPct));
        // RAM: alert when ABOVE threshold
        evalHigh(AlertPrefs.M_RAM, ramPct, "RAM usage high",
                String.format("RAM at %.0f%%", ramPct));
        // Temp: alert when ABOVE threshold
        evalHigh(AlertPrefs.M_TEMP, tempC, "Temperature high",
                String.format("Battery at %.1f°C", tempC));
        // Battery: alert when BELOW threshold (low battery)
        evalLow(AlertPrefs.M_BATT, battLevel, "Battery low",
                String.format("Battery at %d%%", battLevel));
    }

    private void evalHigh(int metric, double value, String title, String text) {
        if (value < 0) return; // unknown reading (e.g. CPU -1)
        if (!AlertPrefs.enabled(this, metric)) { alertLatched[metric] = false; return; }
        double thr = AlertPrefs.threshold(this, metric);
        if (value >= thr) {
            if (!alertLatched[metric]) { fireAlert(metric, title, text); alertLatched[metric] = true; }
        } else if (value <= thr - AlertPrefs.HYSTERESIS) {
            alertLatched[metric] = false; // re-arm once safely back to normal
        }
    }

    private void evalLow(int metric, double value, String title, String text) {
        if (value < 0) return;
        if (!AlertPrefs.enabled(this, metric)) { alertLatched[metric] = false; return; }
        double thr = AlertPrefs.threshold(this, metric);
        if (value <= thr) {
            if (!alertLatched[metric]) { fireAlert(metric, title, text); alertLatched[metric] = true; }
        } else if (value >= thr + AlertPrefs.HYSTERESIS) {
            alertLatched[metric] = false;
        }
    }

    private void fireAlert(int metric, String title, String text) {
        ensureAlertChannel();
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, metric, open, piFlags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, ALERT_CHANNEL);
        else b = new Notification.Builder(this);
        b.setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setAutoCancel(true)
                .setContentIntent(pi);
        if (Build.VERSION.SDK_INT >= 21) b.setPriority(Notification.PRIORITY_HIGH);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(ALERT_BASE_ID + metric, b.build());
    }

    private void ensureAlertChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(ALERT_CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(ALERT_CHANNEL,
                        "Threshold Alerts", NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("Fires when a metric crosses your threshold");
                nm.createNotificationChannel(ch);
            }
        }
    }

    // ---------------- foreground notification ----------------

    private Notification buildNotification(String text) {
        ensureChannel();
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, piFlags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle("System Monitor")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .setContentIntent(pi);
        return b.build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL,
                        "Monitoring", NotificationManager.IMPORTANCE_LOW);
                ch.setDescription("Background 24h history + widget + alerts");
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        running = false;
        handler.removeCallbacks(sampler);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ---------------- static control ----------------

    public static void start(Context c) {
        Intent i = new Intent(c, MonitorService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, MonitorService.class));
    }

    public static boolean isLogging(Context c) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return p.getBoolean(KEY_LOGGING, false);
    }

    public static void setLogging(Context c, boolean on) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_LOGGING, on).apply();
    }

    /** The service should run if either logging or any alert is enabled. */
    public static boolean shouldRun(Context c) {
        return isLogging(c) || AlertPrefs.anyEnabled(c);
    }
}
