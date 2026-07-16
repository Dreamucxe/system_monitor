package com.monitor.sysmon;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Small snapshot of the latest live metrics, written by MonitorService each
 * cycle and read by MonitorWidget when it redraws. Decouples the widget from
 * the sampling loop: the widget just paints whatever was last stored.
 */
public class WidgetState {
    private static final String PREFS = "sysmon_widget";

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Store the latest values. cpu/ram in %, temp in °C, netBps download bytes/sec. */
    public static void save(Context c, double cpuPct, double ramPct,
                            double tempC, double netDownBps, long whenMs) {
        p(c).edit()
                .putFloat("cpu", (float) cpuPct)
                .putFloat("ram", (float) ramPct)
                .putFloat("temp", (float) tempC)
                .putFloat("net", (float) netDownBps)
                .putLong("when", whenMs)
                .apply();
    }

    public static float cpu(Context c)  { return p(c).getFloat("cpu", -1f); }
    public static float ram(Context c)  { return p(c).getFloat("ram", -1f); }
    public static float temp(Context c) { return p(c).getFloat("temp", -1f); }
    public static float net(Context c)  { return p(c).getFloat("net", -1f); }
    public static long  when(Context c) { return p(c).getLong("when", 0L); }
}
