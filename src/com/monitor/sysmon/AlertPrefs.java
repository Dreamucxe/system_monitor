package com.monitor.sysmon;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Stores threshold-alert configuration: per metric an on/off flag and a
 * threshold value. Checked every 60s inside {@link MonitorService}.
 *
 * Direction of each alert:
 *   TEMP  – fire when battery temperature rises ABOVE threshold (°C)
 *   BATT  – fire when battery level drops BELOW threshold (%)
 *   CPU   – fire when CPU usage rises ABOVE threshold (%)
 *   RAM   – fire when RAM usage rises ABOVE threshold (%)
 */
public class AlertPrefs {
    public static final String PREFS = "sysmon_alerts";

    public static final int TEMP = 0;
    public static final int BATT = 1;
    public static final int CPU  = 2;
    public static final int RAM  = 3;
    public static final int COUNT = 4;

    // Aliases used by MonitorService for readability.
    public static final int M_TEMP = TEMP;
    public static final int M_BATT = BATT;
    public static final int M_CPU  = CPU;
    public static final int M_RAM  = RAM;

    /** Margin a value must recover past the threshold before the alert re-arms. */
    public static final double HYSTERESIS = 2.0;

    private static final String[] KEY_ON  = {"temp_on", "batt_on", "cpu_on", "ram_on"};
    private static final String[] KEY_VAL = {"temp_v", "batt_v", "cpu_v", "ram_v"};
    private static final int[] DEFAULT_VAL = {43, 15, 90, 90}; // °C, %, %, %

    public static final String[] NAMES  = {"Temperature", "Battery low", "CPU usage", "RAM usage"};
    public static final String[] UNITS  = {"°C", "%", "%", "%"};
    /** Preset choices shown as chips, per metric. */
    public static final int[][] PRESETS = {
            {40, 43, 46},   // temp
            {20, 15, 10},   // battery low
            {80, 90, 95},   // cpu
            {80, 90, 95},   // ram
    };

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context c, int metric) {
        return p(c).getBoolean(KEY_ON[metric], false);
    }
    public static void setEnabled(Context c, int metric, boolean on) {
        p(c).edit().putBoolean(KEY_ON[metric], on).apply();
    }

    public static int value(Context c, int metric) {
        return p(c).getInt(KEY_VAL[metric], DEFAULT_VAL[metric]);
    }
    public static void setValue(Context c, int metric, int v) {
        p(c).edit().putInt(KEY_VAL[metric], v).apply();
    }

    /** Alias for value(), reads more naturally at the alert-check site. */
    public static double threshold(Context c, int metric) {
        return value(c, metric);
    }

    /** True if any metric alert is enabled (service uses this to decide CPU sampling). */
    public static boolean anyEnabled(Context c) {
        for (int i = 0; i < COUNT; i++) if (enabled(c, i)) return true;
        return false;
    }

    // ---- per-metric "already fired" latch, so we alert once per crossing ----

    public static boolean isLatched(Context c, int metric) {
        return p(c).getBoolean("latch_" + metric, false);
    }
    public static void setLatched(Context c, int metric, boolean latched) {
        p(c).edit().putBoolean("latch_" + metric, latched).apply();
    }
}
