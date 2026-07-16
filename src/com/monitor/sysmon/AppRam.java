package com.monitor.sysmon;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Debug;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Ranks apps by RAM. Uses root `dumpsys meminfo` when available (all apps),
 *  otherwise falls back to the OS-visible own processes. */
public class AppRam {

    public static class Entry {
        public String label;
        public long pssKB;
        public boolean fromRoot;
        public Entry(String l, long p, boolean r) { label = l; pssKB = p; fromRoot = r; }
    }

    public static class Result {
        public List<Entry> entries = new ArrayList<>();
        public boolean rooted;
        public String note;
    }

    public static Result scan(Context c) {
        Result res = new Result();
        if (RootHelper.isRootAvailable()) {
            List<Entry> e = scanViaDumpsys(c);
            if (e != null && !e.isEmpty()) {
                res.entries = e;
                res.rooted = true;
                res.note = "Root: real per-app RAM (PSS) for all running apps.";
                return res;
            }
        }
        res.rooted = false;
        res.entries = scanOwnProcesses(c);
        res.note = "No root: Android only exposes this app's own processes. "
                + "Root a device to rank every app's RAM.";
        return res;
    }

    // dumpsys meminfo output has a "Total PSS by process:" table:
    //   123456 kB: com.some.app (pid 987 / activities)
    private static final Pattern LINE =
            Pattern.compile("^\\s*([\\d,]+)\\s*kB:\\s*([^\\s(]+)");

    private static List<Entry> scanViaDumpsys(Context c) {
        String out = RootHelper.runAsRoot("dumpsys meminfo");
        if (out == null) return null;
        PackageManager pm = c.getPackageManager();
        List<Entry> list = new ArrayList<>();
        boolean inSection = false;
        for (String line : out.split("\n")) {
            if (line.contains("Total PSS by process")) { inSection = true; continue; }
            if (inSection) {
                if (line.trim().isEmpty() || line.contains("Total PSS by OOM")
                        || line.contains("by category")) break;
                Matcher m = LINE.matcher(line);
                if (m.find()) {
                    long kb = Long.parseLong(m.group(1).replace(",", ""));
                    String pkg = m.group(2).trim();
                    list.add(new Entry(prettyLabel(pm, pkg), kb, true));
                }
            }
        }
        Collections.sort(list, new Comparator<Entry>() {
            @Override public int compare(Entry a, Entry b) { return Long.compare(b.pssKB, a.pssKB); }
        });
        return list;
    }

    private static List<Entry> scanOwnProcesses(Context c) {
        List<Entry> out = new ArrayList<>();
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            PackageManager pm = c.getPackageManager();
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs == null) return out;
            for (ActivityManager.RunningAppProcessInfo p : procs) {
                long pss = 0;
                try {
                    Debug.MemoryInfo[] mis = am.getProcessMemoryInfo(new int[]{p.pid});
                    if (mis != null && mis.length > 0) pss = mis[0].getTotalPss();
                } catch (Exception ignored) {}
                out.add(new Entry(prettyLabel(pm, p.processName), pss, false));
            }
            Collections.sort(out, new Comparator<Entry>() {
                @Override public int compare(Entry a, Entry b) { return Long.compare(b.pssKB, a.pssKB); }
            });
        } catch (Exception ignored) {}
        return out;
    }

    private static String prettyLabel(PackageManager pm, String pkg) {
        // strip process suffix like :remote
        String base = pkg.contains(":") ? pkg.substring(0, pkg.indexOf(':')) : pkg;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(base, 0);
            String lbl = pm.getApplicationLabel(ai).toString();
            if (!base.equals(pkg)) lbl += pkg.substring(pkg.indexOf(':'));
            return lbl;
        } catch (Exception ignored) {}
        return pkg;
    }
}
