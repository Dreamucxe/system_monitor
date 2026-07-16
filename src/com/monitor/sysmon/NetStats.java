package com.monitor.sysmon;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.TrafficStats;
import android.os.Build;
import android.os.SystemClock;

/**
 * Samples device network throughput from {@link TrafficStats} cumulative
 * byte counters. Speed is computed as delta-bytes over delta-time between
 * two consecutive samples, mirroring the /proc/stat CPU delta pattern.
 *
 * WiFi throughput is derived as (total - mobile), since TrafficStats exposes
 * total and mobile counters but not a WiFi-only counter.
 */
public class NetStats {

    /** Result of a network sample. Speeds are in bytes/sec. */
    public static class Net {
        public double rxTotal, txTotal;   // whole device
        public double rxMobile, txMobile; // cellular only
        public double rxWifi, txWifi;     // total - mobile (never negative)
        public String link;               // "WiFi", "Mobile data", "Offline"
        public boolean valid;             // false on the very first sample
    }

    private long lastRxTotal = -1, lastTxTotal = -1;
    private long lastRxMobile = -1, lastTxMobile = -1;
    private long lastUptime = -1;

    /** Seed the counters so the first sampleNet() has a baseline. */
    public void seed() {
        lastRxTotal = safe(TrafficStats.getTotalRxBytes());
        lastTxTotal = safe(TrafficStats.getTotalTxBytes());
        lastRxMobile = safe(TrafficStats.getMobileRxBytes());
        lastTxMobile = safe(TrafficStats.getMobileTxBytes());
        lastUptime = SystemClock.elapsedRealtime();
    }

    public Net sampleNet(Context ctx) {
        Net n = new Net();
        long rxT = safe(TrafficStats.getTotalRxBytes());
        long txT = safe(TrafficStats.getTotalTxBytes());
        long rxM = safe(TrafficStats.getMobileRxBytes());
        long txM = safe(TrafficStats.getMobileTxBytes());
        long up = SystemClock.elapsedRealtime();

        n.link = activeLink(ctx);

        if (lastUptime > 0 && up > lastUptime) {
            double dt = (up - lastUptime) / 1000.0; // seconds
            n.rxTotal = rate(rxT, lastRxTotal, dt);
            n.txTotal = rate(txT, lastTxTotal, dt);
            n.rxMobile = rate(rxM, lastRxMobile, dt);
            n.txMobile = rate(txM, lastTxMobile, dt);
            n.rxWifi = Math.max(0, n.rxTotal - n.rxMobile);
            n.txWifi = Math.max(0, n.txTotal - n.txMobile);
            n.valid = true;
        }

        lastRxTotal = rxT; lastTxTotal = txT;
        lastRxMobile = rxM; lastTxMobile = txM;
        lastUptime = up;
        return n;
    }

    private static double rate(long now, long last, double dt) {
        if (last < 0 || now < last || dt <= 0) return 0;
        return (now - last) / dt;
    }

    private static long safe(long v) {
        // TrafficStats returns UNSUPPORTED (-1) on some devices/counters.
        return v == TrafficStats.UNSUPPORTED ? 0 : Math.max(0, v);
    }

    /** Human label for the currently active transport. */
    @SuppressWarnings("deprecation")
    private static String activeLink(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return "Offline";
            if (Build.VERSION.SDK_INT >= 23) {
                android.net.Network net = cm.getActiveNetwork();
                if (net == null) return "Offline";
                NetworkCapabilities cap = cm.getNetworkCapabilities(net);
                if (cap == null) return "Offline";
                if (cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "WiFi";
                if (cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "Mobile data";
                if (cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "Ethernet";
                return "Other";
            } else {
                NetworkInfo ni = cm.getActiveNetworkInfo();
                if (ni == null || !ni.isConnected()) return "Offline";
                if (ni.getType() == ConnectivityManager.TYPE_WIFI) return "WiFi";
                if (ni.getType() == ConnectivityManager.TYPE_MOBILE) return "Mobile data";
                return "Other";
            }
        } catch (Exception e) {
            return "—";
        }
    }

    /** Format a bytes/sec value as a compact human string. */
    public static String fmt(double bps) {
        if (bps < 1024) return String.format("%.0f B/s", bps);
        double kb = bps / 1024.0;
        if (kb < 1024) return String.format("%.1f KB/s", kb);
        double mb = kb / 1024.0;
        return String.format("%.2f MB/s", mb);
    }
}
