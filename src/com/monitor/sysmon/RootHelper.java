package com.monitor.sysmon;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.InputStreamReader;

/** Minimal helper to detect root and run a command via su (best-effort). */
public class RootHelper {

    private static Boolean cached = null;

    public static boolean isRootAvailable() {
        if (cached != null) return cached;
        boolean found = false;
        String[] paths = {
                "/system/bin/su", "/system/xbin/su", "/sbin/su",
                "/su/bin/su", "/system/app/Superuser.apk", "/data/local/xbin/su",
                "/data/local/bin/su", "/system/sd/xbin/su", "/vendor/bin/su"
        };
        for (String p : paths) {
            if (new File(p).exists()) { found = true; break; }
        }
        cached = found;
        return found;
    }

    /** Runs `cmd` as root, returns combined stdout (or null on failure). */
    public static String runAsRoot(String cmd) {
        Process proc = null;
        try {
            proc = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(proc.getOutputStream());
            os.writeBytes(cmd + "\n");
            os.writeBytes("exit\n");
            os.flush();

            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            proc.waitFor();
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (proc != null) try { proc.destroy(); } catch (Exception ignored) {}
        }
    }
}
