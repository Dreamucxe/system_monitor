package com.monitor.sysmon;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Rolling 24h store of device samples, persisted as a small CSV file in the
 * app's private files dir. Each line: epochMillis,usedBytes,totalBytes,tempTenthsC
 *
 * The 4th column (battery temperature in tenths of °C) was added in v1.4.
 * Older 3-column rows still parse — their temp reads back as absent (< -100).
 */
public class RamLog {

    public static final long WINDOW_MS = 24L * 60 * 60 * 1000; // 24 hours
    private static final String FILE = "ram_log.csv";
    /** Sentinel meaning "no temperature recorded for this sample". */
    public static final int TEMP_ABSENT = -1000;

    public static class Sample {
        public long t;      // epoch millis
        public long used;   // bytes
        public long total;  // bytes
        public int tempT;   // battery temp in tenths of °C, or TEMP_ABSENT

        public Sample(long t, long used, long total) {
            this(t, used, total, TEMP_ABSENT);
        }
        public Sample(long t, long used, long total, int tempT) {
            this.t = t; this.used = used; this.total = total; this.tempT = tempT;
        }
        public double pct() { return total > 0 ? (100.0 * used / total) : 0; }
        public boolean hasTemp() { return tempT > TEMP_ABSENT + 1; }
        public double tempC() { return tempT / 10.0; }
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), FILE);
    }

    /** Append one sample (with temperature) and prune anything older than 24h. */
    public static synchronized void append(Context c, long now, long used, long total, int tempTenthsC) {
        List<Sample> all = load(c);
        all.add(new Sample(now, used, total, tempTenthsC));
        long cutoff = now - WINDOW_MS;
        List<Sample> keep = new ArrayList<>();
        for (Sample s : all) if (s.t >= cutoff) keep.add(s);
        writeAll(c, keep);
    }

    /** Back-compatible overload for callers that have no temperature. */
    public static synchronized void append(Context c, long now, long used, long total) {
        append(c, now, used, total, TEMP_ABSENT);
    }

    public static synchronized List<Sample> load(Context c) {
        List<Sample> out = new ArrayList<>();
        File f = file(c);
        if (!f.exists()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split(",");
                if (p.length >= 3) {
                    try {
                        int temp = TEMP_ABSENT;
                        if (p.length >= 4) {
                            try { temp = Integer.parseInt(p[3].trim()); }
                            catch (NumberFormatException ignored) {}
                        }
                        out.add(new Sample(Long.parseLong(p[0].trim()),
                                Long.parseLong(p[1].trim()),
                                Long.parseLong(p[2].trim()), temp));
                    } catch (NumberFormatException ignored) {}
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** Load already pruned to the last 24h relative to now. */
    public static synchronized List<Sample> loadWindow(Context c, long now) {
        List<Sample> all = load(c);
        long cutoff = now - WINDOW_MS;
        List<Sample> keep = new ArrayList<>();
        for (Sample s : all) if (s.t >= cutoff) keep.add(s);
        return keep;
    }

    private static void writeAll(Context c, List<Sample> samples) {
        try (FileWriter w = new FileWriter(file(c), false)) {
            StringBuilder sb = new StringBuilder();
            for (Sample s : samples) {
                sb.append(s.t).append(',').append(s.used).append(',')
                  .append(s.total).append(',').append(s.tempT).append('\n');
            }
            w.write(sb.toString());
        } catch (Exception ignored) {}
    }

    public static synchronized void clear(Context c) {
        File f = file(c);
        if (f.exists()) f.delete();
    }

    /** min / avg / max used-% over the given samples. */
    public static double[] stats(List<Sample> s) {
        if (s.isEmpty()) return new double[]{0, 0, 0};
        double min = 100, max = 0, sum = 0;
        for (Sample x : s) {
            double p = x.pct();
            if (p < min) min = p;
            if (p > max) max = p;
            sum += p;
        }
        return new double[]{min, sum / s.size(), max};
    }

    /** min / avg / max temperature (°C) over samples that have a temp reading.
     *  Returns null if no sample carries temperature. */
    public static double[] tempStats(List<Sample> s) {
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE, sum = 0;
        int n = 0;
        for (Sample x : s) {
            if (!x.hasTemp()) continue;
            double t = x.tempC();
            if (t < min) min = t;
            if (t > max) max = t;
            sum += t; n++;
        }
        if (n == 0) return null;
        return new double[]{min, sum / n, max};
    }
}
