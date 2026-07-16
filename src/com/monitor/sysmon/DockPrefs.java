package com.monitor.sysmon;

import android.content.Context;
import android.content.SharedPreferences;

/** Stores floating-dock customization. */
public class DockPrefs {
    public static final String PREFS = "sysmon_dock";

    public static final int SHAPE_CIRCLE = 0;
    public static final int SHAPE_ROUNDED = 1;
    public static final int SHAPE_SQUARE = 2;

    public static final int METRIC_RAM = 0;
    public static final int METRIC_CPU = 1;
    public static final int METRIC_BOTH = 2;

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context c) { return p(c).getBoolean("enabled", false); }
    public static void setEnabled(Context c, boolean v) { p(c).edit().putBoolean("enabled", v).apply(); }

    public static int shape(Context c) { return p(c).getInt("shape", SHAPE_CIRCLE); }
    public static void setShape(Context c, int v) { p(c).edit().putInt("shape", v).apply(); }

    public static int metric(Context c) { return p(c).getInt("metric", METRIC_RAM); }
    public static void setMetric(Context c, int v) { p(c).edit().putInt("metric", v).apply(); }

    /** size in dp */
    public static int sizeDp(Context c) { return p(c).getInt("size", 72); }
    public static void setSizeDp(Context c, int v) { p(c).edit().putInt("size", v).apply(); }

    public static int color(Context c) { return p(c).getInt("color", 0xFF3DDC84); }
    public static void setColor(Context c, int v) { p(c).edit().putInt("color", v).apply(); }

    /** 0..255 */
    public static int opacity(Context c) { return p(c).getInt("opacity", 235); }
    public static void setOpacity(Context c, int v) { p(c).edit().putInt("opacity", v).apply(); }

    public static int x(Context c) { return p(c).getInt("x", 40); }
    public static int y(Context c) { return p(c).getInt("y", 240); }
    public static void setPos(Context c, int x, int y) {
        p(c).edit().putInt("x", x).putInt("y", y).apply();
    }
}
