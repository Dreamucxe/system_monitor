package com.monitor.sysmon;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/** Reads CPU, battery, storage and device info without needing root. */
public class SysInfo {

    // ---------------- CPU ----------------

    /** Result of a CPU sample. pct may be -1 if unknown. */
    public static class Cpu {
        public double pct;          // 0..100, or -1
        public String source;       // "proc/stat" or "cpufreq" or "n/a"
        public int cores;
        public int[] freqKhz;       // current per-core freq (kHz), may be empty
        public int minKhz, maxKhz;  // overall min/max scaling range
    }

    // /proc/stat baseline
    private long lastIdle = 0, lastTotal = 0;
    private boolean haveBaseline = false;

    public void seedCpu() {
        long[] v = readProcStat();
        if (v != null) { lastIdle = v[0]; lastTotal = v[1]; haveBaseline = true; }
    }

    public Cpu sampleCpu() {
        Cpu c = new Cpu();
        c.cores = Runtime.getRuntime().availableProcessors();
        c.freqKhz = readPerCoreFreq(c.cores);
        int[] range = readFreqRange(c.cores);
        c.minKhz = range[0]; c.maxKhz = range[1];

        // 1) Try /proc/stat delta
        long[] v = readProcStat();
        if (v != null && haveBaseline) {
            long dTotal = v[1] - lastTotal;
            long dIdle = v[0] - lastIdle;
            lastIdle = v[0]; lastTotal = v[1];
            if (dTotal > 0) {
                c.pct = 100.0 * (dTotal - dIdle) / dTotal;
                c.source = "proc/stat";
                return c;
            }
            // dTotal==0 -> frozen snapshot (SELinux). fall through.
        } else if (v != null) {
            lastIdle = v[0]; lastTotal = v[1]; haveBaseline = true;
        }

        // 2) Fallback: estimate load from cpufreq (avg current freq vs range)
        if (c.freqKhz.length > 0 && c.maxKhz > c.minKhz) {
            long sum = 0; int n = 0;
            for (int f : c.freqKhz) { if (f > 0) { sum += f; n++; } }
            if (n > 0) {
                double avg = (double) sum / n;
                double p = 100.0 * (avg - c.minKhz) / (c.maxKhz - c.minKhz);
                c.pct = Math.max(0, Math.min(100, p));
                c.source = "cpufreq";
                return c;
            }
        }

        c.pct = -1; c.source = "n/a";
        return c;
    }

    private long[] readProcStat() {
        try (RandomAccessFile r = new RandomAccessFile("/proc/stat", "r")) {
            String line = r.readLine();
            if (line == null || !line.startsWith("cpu")) return null;
            String[] p = line.trim().split("\\s+");
            long total = 0, idle = 0;
            for (int i = 1; i < p.length; i++) {
                long val = Long.parseLong(p[i]);
                total += val;
                if (i == 4 || i == 5) idle += val; // idle + iowait
            }
            return new long[]{ idle, total };
        } catch (Exception e) {
            return null;
        }
    }

    private int[] readPerCoreFreq(int cores) {
        List<Integer> f = new ArrayList<>();
        for (int i = 0; i < cores; i++) {
            int v = readIntFile("/sys/devices/system/cpu/cpu" + i +
                    "/cpufreq/scaling_cur_freq");
            f.add(v > 0 ? v : 0);
        }
        int[] out = new int[f.size()];
        for (int i = 0; i < out.length; i++) out[i] = f.get(i);
        return out;
    }

    private int[] readFreqRange(int cores) {
        int min = Integer.MAX_VALUE, max = 0;
        for (int i = 0; i < cores; i++) {
            int lo = readIntFile("/sys/devices/system/cpu/cpu" + i +
                    "/cpufreq/cpuinfo_min_freq");
            int hi = readIntFile("/sys/devices/system/cpu/cpu" + i +
                    "/cpufreq/cpuinfo_max_freq");
            if (lo > 0) min = Math.min(min, lo);
            if (hi > 0) max = Math.max(max, hi);
        }
        if (min == Integer.MAX_VALUE) min = 0;
        return new int[]{ min, max };
    }

    private int readIntFile(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String s = r.readLine();
            if (s != null) return Integer.parseInt(s.trim());
        } catch (Exception ignored) {}
        return -1;
    }

    // ---------------- Battery ----------------

    public static class Battery {
        public int level;          // %
        public String status;
        public String plugged;
        public String health;
        public String tech;
        public double tempC;
        public double voltageV;
        public long currentUa;     // instantaneous current (may be 0)
    }

    public static Battery readBattery(Context c) {
        Battery b = new Battery();
        IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent i = c.registerReceiver(null, f);
        if (i != null) {
            int lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            b.level = (lvl >= 0 && scale > 0) ? Math.round(lvl * 100f / scale) : -1;
            b.tempC = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0;
            b.voltageV = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) / 1000.0;
            b.tech = i.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);
            b.status = statusStr(i.getIntExtra(BatteryManager.EXTRA_STATUS, -1));
            b.plugged = pluggedStr(i.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1));
            b.health = healthStr(i.getIntExtra(BatteryManager.EXTRA_HEALTH, -1));
        }
        try {
            BatteryManager bm = (BatteryManager) c.getSystemService(Context.BATTERY_SERVICE);
            if (bm != null) {
                long cur = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                b.currentUa = cur;
            }
        } catch (Exception ignored) {}
        return b;
    }

    private static String statusStr(int s) {
        switch (s) {
            case BatteryManager.BATTERY_STATUS_CHARGING: return "Charging";
            case BatteryManager.BATTERY_STATUS_DISCHARGING: return "Discharging";
            case BatteryManager.BATTERY_STATUS_FULL: return "Full";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING: return "Not charging";
            default: return "Unknown";
        }
    }
    private static String pluggedStr(int p) {
        switch (p) {
            case BatteryManager.BATTERY_PLUGGED_AC: return "AC";
            case BatteryManager.BATTERY_PLUGGED_USB: return "USB";
            case BatteryManager.BATTERY_PLUGGED_WIRELESS: return "Wireless";
            case 0: return "Unplugged";
            default: return "—";
        }
    }
    private static String healthStr(int h) {
        switch (h) {
            case BatteryManager.BATTERY_HEALTH_GOOD: return "Good";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT: return "Overheat";
            case BatteryManager.BATTERY_HEALTH_DEAD: return "Dead";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: return "Over voltage";
            case BatteryManager.BATTERY_HEALTH_COLD: return "Cold";
            default: return "Unknown";
        }
    }

    // ---------------- Storage ----------------

    public static class Vol {
        public String name;
        public long total, avail;
        public long used() { return total - avail; }
        public double pct() { return total > 0 ? (100.0 * used() / total) : 0; }
    }

    public static List<Vol> readStorage() {
        List<Vol> out = new ArrayList<>();
        out.add(vol("Internal storage", Environment.getDataDirectory()));
        File ext = Environment.getExternalStorageDirectory();
        if (ext != null) {
            Vol e = vol("Shared / SD storage", ext);
            // only add if meaningfully different from internal
            if (out.isEmpty() || e.total != out.get(0).total) out.add(e);
        }
        return out;
    }

    private static Vol vol(String name, File path) {
        Vol v = new Vol();
        v.name = name;
        try {
            StatFs s = new StatFs(path.getAbsolutePath());
            long bs = s.getBlockSizeLong();
            v.total = s.getBlockCountLong() * bs;
            v.avail = s.getAvailableBlocksLong() * bs;
        } catch (Exception ignored) {}
        return v;
    }

    // ---------------- Device info ----------------

    public static List<String[]> deviceInfo(Context c) {
        List<String[]> kv = new ArrayList<>();
        kv.add(new String[]{"Device", Build.MANUFACTURER + " " + Build.MODEL});
        kv.add(new String[]{"Brand / Product", Build.BRAND + " / " + Build.PRODUCT});
        kv.add(new String[]{"Board / Hardware", nz(Build.BOARD) + " / " + nz(Build.HARDWARE)});
        // SoC on API 31+
        if (Build.VERSION.SDK_INT >= 31) {
            kv.add(new String[]{"SoC", nz(Build.SOC_MANUFACTURER) + " " + nz(Build.SOC_MODEL)});
        }
        kv.add(new String[]{"CPU cores", String.valueOf(Runtime.getRuntime().availableProcessors())});
        kv.add(new String[]{"CPU ABI", abis()});
        kv.add(new String[]{"CPU hardware", cpuHardware()});
        kv.add(new String[]{"Android version", Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"});
        kv.add(new String[]{"Security patch", Build.VERSION.SDK_INT >= 23 ? nz(Build.VERSION.SECURITY_PATCH) : "—"});
        kv.add(new String[]{"Build", Build.DISPLAY});
        kv.add(new String[]{"Kernel", System.getProperty("os.version")});
        kv.add(new String[]{"Bootloader", nz(Build.BOOTLOADER)});
        return kv;
    }

    public static String ramSummary(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        double gb = mi.totalMem / (1024.0 * 1024 * 1024);
        return String.format("%.1f GB total", gb);
    }

    public static String displaySummary(Context c) {
        try {
            WindowManager wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
            Display d = wm.getDefaultDisplay();
            DisplayMetrics m = new DisplayMetrics();
            d.getRealMetrics(m);
            return m.widthPixels + " x " + m.heightPixels + " px, "
                    + m.densityDpi + " dpi, " + Math.round(d.getRefreshRate()) + " Hz";
        } catch (Exception e) {
            return "—";
        }
    }

    private static String abis() {
        if (Build.VERSION.SDK_INT >= 21 && Build.SUPPORTED_ABIS != null
                && Build.SUPPORTED_ABIS.length > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Build.SUPPORTED_ABIS.length; i++) {
                sb.append(Build.SUPPORTED_ABIS[i]);
                if (i < Build.SUPPORTED_ABIS.length - 1) sb.append(", ");
            }
            return sb.toString();
        }
        return nz(Build.CPU_ABI);
    }

    private static String cpuHardware() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/cpuinfo"))) {
            String line; String hw = null; String proc = null;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("Hardware")) hw = after(line);
                else if (line.startsWith("model name")) proc = after(line);
                else if (proc == null && line.startsWith("Processor")) proc = after(line);
            }
            if (hw != null) return hw;
            if (proc != null) return proc;
        } catch (Exception ignored) {}
        return "—";
    }

    private static String after(String line) {
        int i = line.indexOf(':');
        return i >= 0 ? line.substring(i + 1).trim() : line.trim();
    }

    private static String nz(String s) { return (s == null || s.isEmpty()) ? "—" : s; }
}
