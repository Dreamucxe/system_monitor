package com.monitor.sysmon;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final int INTERVAL_MS = 1000;
    private int tick = 0;

    private final SysInfo sys = new SysInfo();
    private final NetStats netStats = new NetStats();

    // live UI refs
    private TextView cpuValue, cpuDetail;
    private View cpuBar;
    private TextView ramValue, ramDetail;
    private View ramBar;
    private TextView refreshValue, refreshDetail;
    private TextView uptimeValue;

    // network
    private TextView netValue, netDetail;

    // battery
    private TextView battValue, battDetail;
    private View battBar;

    // temperature
    private TextView tempValue, tempDetail, tempStats;
    private GraphView tempGraph;

    // storage
    private LinearLayout storageContainer;

    // history
    private GraphView graphView;
    private TextView logState, logStats;
    private Button logToggle;

    // alerts
    private LinearLayout alertContainer;

    // per-app
    private LinearLayout appListContainer;
    private TextView appNote;

    // device info
    private LinearLayout deviceContainer;
    private TextView gpuLine;

    // floating dock
    private TextView dockState;
    private Button dockToggle;
    private static final int REQ_OVERLAY = 77;

    // colors
    private static final int BG      = 0xFF0E1116;
    private static final int CARD    = 0xFF1A1F27;
    private static final int ACCENT  = 0xFF3DDC84; // green
    private static final int ACCENT2 = 0xFF4F9CF9; // blue
    private static final int ACCENT3 = 0xFFFFB454; // amber
    private static final int ACCENT4 = 0xFFB98AFF; // purple
    private static final int TXT     = 0xFFE6EAF0;
    private static final int SUBTXT  = 0xFF8A93A2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= 33) {
            try {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
            } catch (Exception ignored) {}
        }

        netStats.seed();

        int pad = dp(16);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(28), pad, pad);

        TextView title = new TextView(this);
        title.setText("System Monitor");
        title.setTextColor(TXT);
        title.setTextSize(28);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Live stats • battery • storage • device info");
        subtitle.setTextColor(SUBTXT);
        subtitle.setTextSize(14);
        subtitle.setPadding(0, dp(2), 0, dp(20));
        root.addView(subtitle);

        // CPU
        LinearLayout cpuCard = card(root, "CPU USAGE", ACCENT);
        cpuValue = bigValue(cpuCard, "…");
        cpuBar = progressBar(cpuCard, ACCENT);
        cpuDetail = detail(cpuCard, "Reading CPU…");

        // RAM
        LinearLayout ramCard = card(root, "RAM USAGE", ACCENT2);
        ramValue = bigValue(ramCard, "0%");
        ramBar = progressBar(ramCard, ACCENT2);
        ramDetail = detail(ramCard, "Reading memory…");

        // Network
        LinearLayout netCard = card(root, "NETWORK", ACCENT4);
        netValue = bigValue(netCard, "↓ … / ↑ …");
        netValue.setTextSize(26);
        netDetail = detail(netCard, "Measuring throughput…");

        // Battery
        LinearLayout battCard = card(root, "BATTERY", ACCENT);
        battValue = bigValue(battCard, "…");
        battBar = progressBar(battCard, ACCENT);
        battDetail = detail(battCard, "Reading battery…");

        // Temperature (with 24h graph)
        LinearLayout tempCard = card(root, "TEMPERATURE", ACCENT3);
        tempValue = bigValue(tempCard, "…°C");
        tempDetail = detail(tempCard, "Battery sensor");
        tempGraph = new GraphView(this);
        tempGraph.setMode(GraphView.MODE_TEMP, ACCENT3);
        LinearLayout.LayoutParams tglp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(150));
        tglp.topMargin = dp(10);
        tglp.bottomMargin = dp(6);
        tempGraph.setLayoutParams(tglp);
        tempCard.addView(tempGraph);
        tempStats = detail(tempCard, "24h graph fills while logging is on (RAM history card).");

        // Storage
        LinearLayout storeCard = card(root, "STORAGE", ACCENT3);
        storageContainer = new LinearLayout(this);
        storageContainer.setOrientation(LinearLayout.VERTICAL);
        storeCard.addView(storageContainer);

        // Refresh rate
        LinearLayout refCard = card(root, "DISPLAY REFRESH RATE", ACCENT3);
        refreshValue = bigValue(refCard, "0 Hz");
        refreshDetail = detail(refCard, "Reading display…");

        // Uptime
        LinearLayout upCard = card(root, "DEVICE UPTIME", TXT);
        uptimeValue = bigValue(upCard, "—");

        // 24h RAM history
        LinearLayout histCard = card(root, "24-HOUR RAM HISTORY", ACCENT2);
        logState = detail(histCard, "Logging: off");
        graphView = new GraphView(this);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(150));
        glp.topMargin = dp(10);
        glp.bottomMargin = dp(10);
        graphView.setLayoutParams(glp);
        histCard.addView(graphView);
        logStats = detail(histCard, "No samples yet");

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(10), 0, 0);
        logToggle = flatButton("START LOGGING", ACCENT);
        logToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleLogging(); }
        });
        Button clearBtn = flatButton("CLEAR", 0xFF3A4150);
        clearBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { RamLog.clear(MainActivity.this); refreshHistory(); }
        });
        LinearLayout.LayoutParams b1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f);
        LinearLayout.LayoutParams b2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        b1.rightMargin = dp(8);
        btnRow.addView(logToggle, b1);
        btnRow.addView(clearBtn, b2);
        histCard.addView(btnRow);

        // Apps using RAM (root-aware ranked list)
        LinearLayout appCard = card(root, "APPS USING RAM", ACCENT);
        appNote = detail(appCard, "Tap scan to rank apps by memory usage.");
        appListContainer = new LinearLayout(this);
        appListContainer.setOrientation(LinearLayout.VERTICAL);
        appListContainer.setPadding(0, dp(6), 0, 0);
        appCard.addView(appListContainer);
        Button refreshApps = flatButton("SCAN APPS USING RAM", ACCENT);
        refreshApps.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { refreshApps(); }
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(10);
        refreshApps.setLayoutParams(rlp);
        appCard.addView(refreshApps);

        // Threshold alerts
        buildAlertsCard(root);

        // Floating dock (customizable overlay bubble)
        buildDockCard(root);

        // Device info
        LinearLayout devCard = card(root, "DEVICE INFO", ACCENT4);
        deviceContainer = new LinearLayout(this);
        deviceContainer.setOrientation(LinearLayout.VERTICAL);
        deviceContainer.setPadding(0, dp(6), 0, 0);
        devCard.addView(deviceContainer);

        TextView footer = new TextView(this);
        footer.setText("Live stats update every second • RAM history sampled every 60s");
        footer.setTextColor(SUBTXT);
        footer.setTextSize(12);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(18), 0, dp(8));
        root.addView(footer);

        scroll.addView(root);
        setContentView(scroll);

        populateDeviceInfo();
        populateStorage();
    }

    // ---------- UI builders ----------

    private LinearLayout card(LinearLayout parent, String heading, int accent) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(16));
        c.setBackground(bg);
        int p = dp(18);
        c.setPadding(p, p, p, p);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(14);
        c.setLayoutParams(lp);

        TextView h = new TextView(this);
        h.setText(heading);
        h.setTextColor(accent);
        h.setTextSize(13);
        h.setLetterSpacing(0.08f);
        h.setTypeface(h.getTypeface(), Typeface.BOLD);
        c.addView(h);

        parent.addView(c);
        return c;
    }

    private TextView bigValue(LinearLayout card, String initial) {
        TextView t = new TextView(this);
        t.setText(initial);
        t.setTextColor(TXT);
        t.setTextSize(40);
        t.setTypeface(t.getTypeface(), Typeface.BOLD);
        t.setPadding(0, dp(6), 0, dp(6));
        card.addView(t);
        return t;
    }

    private View progressBar(LinearLayout card, final int accent) {
        final LinearLayout track = new LinearLayout(this);
        GradientDrawable tbg = new GradientDrawable();
        tbg.setColor(0xFF2A303B);
        tbg.setCornerRadius(dp(6));
        track.setBackground(tbg);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(12));
        tlp.topMargin = dp(4);
        tlp.bottomMargin = dp(4);
        track.setLayoutParams(tlp);

        final View fill = new View(this);
        GradientDrawable fbg = new GradientDrawable();
        fbg.setColor(accent);
        fbg.setCornerRadius(dp(6));
        fill.setBackground(fbg);
        track.addView(fill, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT));

        card.addView(track);
        return fill;
    }

    private void setBar(View fill, double pct) {
        if (fill == null) return;
        View track = (View) fill.getParent();
        int w = (track != null) ? track.getWidth() : 0;
        if (w <= 0) return;
        int fw = (int) Math.round(w * Math.max(0, Math.min(100, pct)) / 100.0);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) fill.getLayoutParams();
        lp.width = fw;
        fill.setLayoutParams(lp);
    }

    private void setBarColor(View fill, int color) {
        if (fill == null) return;
        GradientDrawable fbg = new GradientDrawable();
        fbg.setColor(color);
        fbg.setCornerRadius(dp(6));
        fill.setBackground(fbg);
    }

    private TextView detail(LinearLayout card, String initial) {
        TextView t = new TextView(this);
        t.setText(initial);
        t.setTextColor(SUBTXT);
        t.setTextSize(13);
        t.setPadding(0, dp(4), 0, 0);
        card.addView(t);
        return t;
    }

    private Button flatButton(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(0xFF0E1116);
        b.setTypeface(b.getTypeface(), Typeface.BOLD);
        b.setAllCaps(true);
        b.setTextSize(13);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(10));
        b.setBackground(bg);
        b.setPadding(dp(12), dp(10), dp(12), dp(10));
        return b;
    }

    private void kvRow(LinearLayout container, String k, String v) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(5), 0, dp(5));
        TextView kt = new TextView(this);
        kt.setText(k);
        kt.setTextColor(SUBTXT);
        kt.setTextSize(13);
        TextView vt = new TextView(this);
        vt.setText(v == null ? "—" : v);
        vt.setTextColor(TXT);
        vt.setTextSize(13);
        vt.setGravity(Gravity.END);
        vt.setTypeface(vt.getTypeface(), Typeface.BOLD);
        row.addView(kt, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.1f));
        row.addView(vt, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f));
        container.addView(row);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ---------- lifecycle ----------

    @Override protected void onResume() {
        super.onResume();
        sys.seedCpu();
        syncLogState();
        syncDockState();
        handler.post(ticker);
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            updateStats();
            handler.postDelayed(this, INTERVAL_MS);
        }
    };

    private void updateStats() {
        updateCpu();
        updateRam();
        updateNetwork();
        updateBattery();
        updateTemperature();
        updateRefresh();
        updateUptime();
        if (tick % 10 == 0) populateStorage();      // storage changes slowly
        if (tick % 5 == 0) refreshHistory();
        tick++;
    }

    // ---------- CPU (fixed: hybrid /proc/stat + cpufreq) ----------

    private void updateCpu() {
        SysInfo.Cpu c = sys.sampleCpu();
        if (c.pct < 0) {
            cpuValue.setText("n/a");
            cpuDetail.setText("CPU load not readable on this device (restricted)");
            return;
        }
        cpuValue.setText(String.format("%.0f%%", c.pct));
        setBar(cpuBar, c.pct);

        StringBuilder sb = new StringBuilder();
        sb.append(c.cores).append(" cores");
        if (c.maxKhz > 0) {
            sb.append(" • ").append(mhz(c.minKhz)).append("–").append(mhz(c.maxKhz));
        }
        // per-core current freq (compact)
        if (c.freqKhz != null && c.freqKhz.length > 0) {
            StringBuilder f = new StringBuilder();
            int shown = 0;
            for (int khz : c.freqKhz) {
                if (shown++ > 0) f.append(" ");
                f.append(khz > 0 ? (khz / 1000) : 0);
            }
            sb.append("\nNow: ").append(f).append(" MHz");
        }
        sb.append("  (via ").append(c.source).append(")");
        cpuDetail.setText(sb.toString());
    }

    private String mhz(int khz) {
        double ghz = khz / 1_000_000.0;
        if (ghz >= 1) return String.format("%.1f GHz", ghz);
        return (khz / 1000) + " MHz";
    }

    // ---------- Network ----------

    private void updateNetwork() {
        NetStats.Net n = netStats.sampleNet(this);
        if (!n.valid) {
            netValue.setText("↓ … / ↑ …");
            netDetail.setText("Measuring throughput…");
            return;
        }
        netValue.setText("↓ " + NetStats.fmt(n.rxTotal) + "   ↑ " + NetStats.fmt(n.txTotal));
        StringBuilder sb = new StringBuilder();
        sb.append("Active: ").append(n.link);
        sb.append("\nWiFi: ↓ ").append(NetStats.fmt(n.rxWifi)).append(" / ↑ ").append(NetStats.fmt(n.txWifi));
        sb.append("\nMobile: ↓ ").append(NetStats.fmt(n.rxMobile)).append(" / ↑ ").append(NetStats.fmt(n.txMobile));
        netDetail.setText(sb.toString());
    }

    // ---------- Temperature ----------

    private void updateTemperature() {
        SysInfo.Battery b = SysInfo.readBattery(this);
        if (b.tempC <= -100) {
            tempValue.setText("n/a");
            tempDetail.setText("Temperature not readable on this device");
            return;
        }
        tempValue.setText(String.format("%.1f°C", b.tempC));
        int col = b.tempC >= 45 ? 0xFFFF6B6B : (b.tempC >= 40 ? ACCENT3 : ACCENT);
        tempValue.setTextColor(col);
        double f = b.tempC * 9 / 5 + 32;
        tempDetail.setText(String.format("Battery sensor • %.1f°F", f));
    }

    // ---------- RAM ----------

    private void updateRam() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long totalB = mi.totalMem;
        long availB = mi.availMem;
        long usedB = totalB - availB;
        double pct = (totalB > 0) ? (100.0 * usedB / totalB) : 0;
        ramValue.setText(String.format("%.0f%%", pct));
        setBar(ramBar, pct);
        ramDetail.setText(fmtGB(usedB) + " used / " + fmtGB(totalB)
                + "  •  " + fmtGB(availB) + " free" + (mi.lowMemory ? "  • LOW" : ""));
    }

    // ---------- Battery ----------

    private void updateBattery() {
        SysInfo.Battery b = SysInfo.readBattery(this);
        if (b.level < 0) { battValue.setText("n/a"); return; }
        battValue.setText(b.level + "%");
        setBar(battBar, b.level);
        boolean charging = "Charging".equals(b.status) || "Full".equals(b.status);
        setBarColor(battBar, charging ? ACCENT : (b.level <= 15 ? 0xFFFF6B6B : ACCENT3));

        StringBuilder sb = new StringBuilder();
        sb.append(b.status);
        if (!"Unplugged".equals(b.plugged) && !"—".equals(b.plugged)) sb.append(" (").append(b.plugged).append(")");
        sb.append("  •  ").append(String.format("%.1f°C", b.tempC));
        sb.append("  •  ").append(String.format("%.2fV", b.voltageV));
        if (b.currentUa != 0 && b.currentUa != Long.MIN_VALUE) {
            sb.append("  •  ").append(String.format("%.0f mA", b.currentUa / 1000.0));
        }
        sb.append("\nHealth: ").append(b.health);
        if (b.tech != null) sb.append("  •  ").append(b.tech);
        battDetail.setText(sb.toString());
    }

    // ---------- Storage ----------

    private void populateStorage() {
        storageContainer.removeAllViews();
        List<SysInfo.Vol> vols = SysInfo.readStorage();
        for (SysInfo.Vol v : vols) {
            TextView name = new TextView(this);
            name.setText(v.name);
            name.setTextColor(TXT);
            name.setTextSize(15);
            name.setTypeface(name.getTypeface(), Typeface.BOLD);
            name.setPadding(0, dp(8), 0, dp(2));
            storageContainer.addView(name);

            LinearLayout barCard = new LinearLayout(this);
            barCard.setOrientation(LinearLayout.VERTICAL);
            View bar = progressBar(barCard, ACCENT3);
            storageContainer.addView(barCard);
            // set width after layout
            final View fbar = bar; final double pct = v.pct();
            bar.post(new Runnable() { @Override public void run() { setBar(fbar, pct); } });

            TextView d = new TextView(this);
            d.setText(String.format("%s used / %s  •  %s free  (%.0f%%)",
                    fmtGB(v.used()), fmtGB(v.total), fmtGB(v.avail), v.pct()));
            d.setTextColor(SUBTXT);
            d.setTextSize(13);
            d.setPadding(0, dp(2), 0, dp(4));
            storageContainer.addView(d);
        }
    }

    // ---------- Refresh / uptime ----------

    private void updateRefresh() {
        float hz = 60f;
        try {
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            hz = wm.getDefaultDisplay().getRefreshRate();
        } catch (Exception ignored) {}
        refreshValue.setText(String.format("%.0f Hz", hz));
        String extra;
        try {
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            float[] modes = wm.getDefaultDisplay().getSupportedRefreshRates();
            StringBuilder sb = new StringBuilder("Supported: ");
            for (int i = 0; i < modes.length; i++) {
                sb.append(String.format("%.0f", modes[i]));
                if (i < modes.length - 1) sb.append(", ");
            }
            sb.append(" Hz");
            extra = sb.toString();
        } catch (Exception e) {
            extra = "frame time ≈ " + String.format("%.2f", 1000f / hz) + " ms";
        }
        refreshDetail.setText(extra);
    }

    private void updateUptime() {
        long s = SystemClock.elapsedRealtime() / 1000;
        long h = s / 3600; long m = (s % 3600) / 60; long sec = s % 60;
        uptimeValue.setText(String.format("%dh %02dm %02ds", h, m, sec));
    }

    // ---------- 24h history ----------

    private void toggleLogging() {
        boolean turningOn = !MonitorService.isLogging(this);
        MonitorService.setLogging(this, turningOn);
        // Service must run if logging OR any alert is enabled.
        if (MonitorService.shouldRun(this)) MonitorService.start(this);
        else MonitorService.stop(this);
        syncLogState();
    }

    private void syncLogState() {
        boolean on = MonitorService.isLogging(this);
        if (logToggle != null) {
            logToggle.setText(on ? "STOP LOGGING" : "START LOGGING");
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(on ? 0xFFFF6B6B : ACCENT);
            bg.setCornerRadius(dp(10));
            logToggle.setBackground(bg);
        }
        if (logState != null) {
            logState.setText(on
                    ? "Logging: ON — sampling every 60s in the background"
                    : "Logging: off — tap Start to record 24h history");
        }
    }

    private void refreshHistory() {
        long now = System.currentTimeMillis();
        List<RamLog.Sample> data = RamLog.loadWindow(this, now);
        graphView.setData(data, now);
        // feed the same window to the temperature graph
        if (tempGraph != null) {
            tempGraph.setData(data, now);
            double[] ts = RamLog.tempStats(data);
            if (ts == null) {
                tempStats.setText("No temperature samples yet" +
                        (MonitorService.isLogging(this) ? " • first point arrives within 60s" : " • start logging to record"));
            } else {
                tempStats.setText(String.format("min %.1f°C / avg %.1f°C / max %.1f°C over history",
                        ts[0], ts[1], ts[2]));
            }
        }
        if (data.isEmpty()) {
            logStats.setText("No samples yet" +
                    (MonitorService.isLogging(this) ? " • first point arrives within 60s" : ""));
            return;
        }
        double[] st = RamLog.stats(data);
        long spanMs = data.get(data.size() - 1).t - data.get(0).t;
        logStats.setText(String.format("%d samples • span %s • used min %.0f%% / avg %.0f%% / max %.0f%%",
                data.size(), fmtDur(spanMs), st[0], st[1], st[2]));
    }

    // ---------- apps using RAM (root-aware) ----------

    private void refreshApps() {
        appNote.setText("Scanning… (root prompt may appear)");
        appListContainer.removeAllViews();
        new Thread(new Runnable() {
            @Override public void run() {
                final AppRam.Result res = AppRam.scan(MainActivity.this);
                runOnUiThread(new Runnable() {
                    @Override public void run() { showAppResult(res); }
                });
            }
        }).start();
    }

    private void showAppResult(AppRam.Result res) {
        appListContainer.removeAllViews();
        if (res.entries.isEmpty()) {
            appNote.setText("No app memory visible on this device.");
        } else {
            appNote.setText((res.rooted ? "ROOT • " : "")
                    + res.entries.size() + " apps • PSS = actual RAM footprint");
            int shown = 0;
            for (AppRam.Entry e : res.entries) {
                if (shown++ >= 25) break;
                addAppRow(e.label, fmtMB(e.pssKB));
            }
        }
        TextView note = new TextView(this);
        note.setText(res.note);
        note.setTextColor(SUBTXT);
        note.setTextSize(12);
        note.setPadding(0, dp(10), 0, 0);
        appListContainer.addView(note);
    }

    private void addAppRow(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(5), 0, dp(5));
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(TXT);
        l.setTextSize(14);
        l.setMaxLines(1);
        TextView r = new TextView(this);
        r.setText(value);
        r.setTextColor(ACCENT);
        r.setTextSize(14);
        r.setTypeface(r.getTypeface(), Typeface.BOLD);
        row.addView(l, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(r);
        appListContainer.addView(row);
    }

    // ---------- threshold alerts ----------

    private void buildAlertsCard(LinearLayout root) {
        LinearLayout c = card(root, "THRESHOLD ALERTS", 0xFFFF6B6B);
        detail(c, "Get a notification when a metric crosses your limit. "
                + "Checked every 60s in the background.");

        for (int m = 0; m < AlertPrefs.COUNT; m++) {
            final int metric = m;

            // metric title + on/off
            LinearLayout head = new LinearLayout(this);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setPadding(0, dp(12), 0, 0);
            TextView name = new TextView(this);
            name.setText(AlertPrefs.NAMES[metric]);
            name.setTextColor(TXT);
            name.setTextSize(15);
            name.setTypeface(name.getTypeface(), Typeface.BOLD);
            head.addView(name, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            final Button onBtn = new Button(this);
            onBtn.setAllCaps(false);
            onBtn.setTextSize(12);
            onBtn.setPadding(dp(12), dp(4), dp(12), dp(4));
            styleAlertToggle(onBtn, AlertPrefs.enabled(this, metric));
            onBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    boolean now = !AlertPrefs.enabled(MainActivity.this, metric);
                    AlertPrefs.setEnabled(MainActivity.this, metric, now);
                    styleAlertToggle(onBtn, now);
                    applyAlertServiceState();
                }
            });
            head.addView(onBtn);
            c.addView(head);

            // threshold preset chips
            final int[] presets = AlertPrefs.PRESETS[metric];
            final String unit = AlertPrefs.UNITS[metric];
            int cur = AlertPrefs.value(this, metric);
            int sel = 0;
            for (int i = 0; i < presets.length; i++) if (presets[i] == cur) sel = i;
            String[] labels = new String[presets.length];
            for (int i = 0; i < presets.length; i++) labels[i] = presets[i] + unit;
            addChipRow(c, "Threshold", labels, sel, new ChipPick() {
                @Override public void pick(int i) {
                    AlertPrefs.setValue(MainActivity.this, metric, presets[i]);
                }
            });
        }
    }

    private void styleAlertToggle(Button b, boolean on) {
        b.setText(on ? "ON" : "OFF");
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(on ? ACCENT : 0xFF2A303B);
        bg.setCornerRadius(dp(9));
        b.setBackground(bg);
        b.setTextColor(on ? 0xFF0E1116 : SUBTXT);
        b.setTypeface(b.getTypeface(), Typeface.BOLD);
    }

    /** Start/stop the monitor service to match logging + alert settings. */
    private void applyAlertServiceState() {
        if (MonitorService.shouldRun(this)) MonitorService.start(this);
        else MonitorService.stop(this);
    }

    // ---------- floating dock ----------

    private void buildDockCard(LinearLayout root) {
        LinearLayout c = card(root, "FLOATING USAGE DOCK", ACCENT2);
        dockState = detail(c, "");
        TextView hint = detail(c, "A draggable bubble that floats over other apps. "
                + "Tap it to open this app; drag to move.");

        // Shape row
        addChipRow(c, "Shape", new String[]{"● Circle", "▢ Rounded", "■ Square"},
                DockPrefs.shape(this), new ChipPick() {
                    @Override public void pick(int i) { DockPrefs.setShape(MainActivity.this, i); DockService.restyle(MainActivity.this); syncDockState(); }
                });
        // Metric row
        addChipRow(c, "Show", new String[]{"RAM", "CPU", "Both"},
                DockPrefs.metric(this), new ChipPick() {
                    @Override public void pick(int i) { DockPrefs.setMetric(MainActivity.this, i); DockService.restyle(MainActivity.this); syncDockState(); }
                });
        // Size row
        final int[] sizes = {56, 72, 92};
        int curSize = DockPrefs.sizeDp(this);
        int sizeIdx = curSize <= 60 ? 0 : (curSize <= 80 ? 1 : 2);
        addChipRow(c, "Size", new String[]{"Small", "Medium", "Large"},
                sizeIdx, new ChipPick() {
                    @Override public void pick(int i) { DockPrefs.setSizeDp(MainActivity.this, sizes[i]); DockService.restyle(MainActivity.this); syncDockState(); }
                });
        // Opacity row
        final int[] ops = {150, 210, 255};
        int curOp = DockPrefs.opacity(this);
        int opIdx = curOp <= 170 ? 0 : (curOp <= 230 ? 1 : 2);
        addChipRow(c, "Opacity", new String[]{"40%", "70%", "100%"},
                opIdx, new ChipPick() {
                    @Override public void pick(int i) { DockPrefs.setOpacity(MainActivity.this, ops[i]); DockService.restyle(MainActivity.this); syncDockState(); }
                });
        // Color swatches
        addColorRow(c);

        dockToggle = flatButton("ENABLE DOCK", ACCENT2);
        dockToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleDock(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        dockToggle.setLayoutParams(lp);
        c.addView(dockToggle);
        syncDockState();
    }

    private interface ChipPick { void pick(int index); }

    private void addChipRow(LinearLayout parent, String label, String[] options,
                            int selected, final ChipPick cb) {
        TextView lbl = new TextView(this);
        lbl.setText(label);
        lbl.setTextColor(SUBTXT);
        lbl.setTextSize(12);
        lbl.setPadding(0, dp(10), 0, dp(4));
        parent.addView(lbl);

        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final Button[] chips = new Button[options.length];
        for (int i = 0; i < options.length; i++) {
            final int idx = i;
            Button chip = new Button(this);
            chip.setText(options[i]);
            chip.setAllCaps(false);
            chip.setTextSize(13);
            chip.setPadding(dp(6), dp(6), dp(6), dp(6));
            styleChip(chip, i == selected);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    for (int j = 0; j < chips.length; j++) styleChip(chips[j], j == idx);
                    cb.pick(idx);
                }
            });
            chips[i] = chip;
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            clp.rightMargin = dp(6);
            row.addView(chip, clp);
        }
        parent.addView(row);
    }

    private void styleChip(Button b, boolean on) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(on ? ACCENT2 : 0xFF2A303B);
        bg.setCornerRadius(dp(9));
        b.setBackground(bg);
        b.setTextColor(on ? 0xFF0E1116 : TXT);
        b.setTypeface(b.getTypeface(), on ? Typeface.BOLD : Typeface.NORMAL);
    }

    private void addColorRow(LinearLayout parent) {
        TextView lbl = new TextView(this);
        lbl.setText("Color");
        lbl.setTextColor(SUBTXT);
        lbl.setTextSize(12);
        lbl.setPadding(0, dp(10), 0, dp(4));
        parent.addView(lbl);

        final int[] colors = {0xFF3DDC84, 0xFF4F9CF9, 0xFFFFB454, 0xFFB98AFF, 0xFFFF6B6B, 0xFFE6EAF0};
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (final int col : colors) {
            View sw = new View(this);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(col);
            bg.setShape(GradientDrawable.OVAL);
            bg.setStroke(dp(2), 0xFF2A303B);
            sw.setBackground(bg);
            sw.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    DockPrefs.setColor(MainActivity.this, col);
                    DockService.restyle(MainActivity.this);
                    syncDockState();
                }
            });
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(34), dp(34));
            slp.rightMargin = dp(10);
            row.addView(sw, slp);
        }
        parent.addView(row);
    }

    private void toggleDock() {
        if (DockPrefs.enabled(this)) {
            DockService.stop(this);
            syncDockState();
            return;
        }
        if (!canOverlay()) {
            Toast.makeText(this, "Grant \"Display over other apps\", then tap Enable again",
                    Toast.LENGTH_LONG).show();
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivityForResult(i, REQ_OVERLAY);
            } catch (Exception ignored) {}
            return;
        }
        DockService.start(this);
        syncDockState();
    }

    private boolean canOverlay() {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_OVERLAY && canOverlay()) {
            DockService.start(this);
            syncDockState();
        }
    }

    private void syncDockState() {
        if (dockState == null) return;
        boolean on = DockPrefs.enabled(this);
        if (dockToggle != null) {
            dockToggle.setText(on ? "DISABLE DOCK" : "ENABLE DOCK");
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(on ? 0xFFFF6B6B : ACCENT2);
            bg.setCornerRadius(dp(10));
            dockToggle.setBackground(bg);
        }
        String shape = new String[]{"circle", "rounded", "square"}[DockPrefs.shape(this)];
        String metric = new String[]{"RAM", "CPU", "RAM+CPU"}[DockPrefs.metric(this)];
        dockState.setText((on ? "Dock: ON" : "Dock: off")
                + " • " + shape + " • " + metric
                + (canOverlay() ? "" : " • needs overlay permission"));
    }


    private void populateDeviceInfo() {
        deviceContainer.removeAllViews();
        kvRow(deviceContainer, "RAM", SysInfo.ramSummary(this));
        kvRow(deviceContainer, "Display", SysInfo.displaySummary(this));
        gpuLine = new TextView(this); // placeholder replaced by kvRow style
        for (String[] kv : SysInfo.deviceInfo(this)) {
            kvRow(deviceContainer, kv[0], kv[1]);
        }
        // GPU row (added now, filled async)
        final LinearLayout gpuRowHolder = new LinearLayout(this);
        gpuRowHolder.setOrientation(LinearLayout.VERTICAL);
        deviceContainer.addView(gpuRowHolder);
        kvRowInto(gpuRowHolder, "GPU", "probing…");

        new Thread(new Runnable() {
            @Override public void run() {
                final GpuProbe.Gpu g = GpuProbe.read();
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        gpuRowHolder.removeAllViews();
                        kvRowInto(gpuRowHolder, "GPU vendor", g.vendor);
                        kvRowInto(gpuRowHolder, "GPU renderer", g.renderer);
                        kvRowInto(gpuRowHolder, "OpenGL ES", g.version);
                    }
                });
            }
        }).start();
    }

    private void kvRowInto(LinearLayout container, String k, String v) { kvRow(container, k, v); }

    // ---------- formatting ----------

    private String fmtGB(long bytes) {
        double gb = bytes / (1024.0 * 1024.0 * 1024.0);
        if (gb >= 1) return String.format("%.2f GB", gb);
        double mb = bytes / (1024.0 * 1024.0);
        return String.format("%.0f MB", mb);
    }

    private String fmtMB(long kb) {
        double mb = kb / 1024.0;
        if (mb >= 1024) return String.format("%.2f GB", mb / 1024.0);
        return String.format("%.0f MB", mb);
    }

    private String fmtDur(long ms) {
        long s = ms / 1000;
        long h = s / 3600; long m = (s % 3600) / 60;
        if (h > 0) return h + "h " + m + "m";
        return m + "m";
    }
}
