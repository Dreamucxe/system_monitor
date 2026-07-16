package com.monitor.sysmon;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts the RAM logger after device reboot if the user had it enabled. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        if (action.equals(Intent.ACTION_BOOT_COMPLETED)
                || action.equals("android.intent.action.QUICKBOOT_POWERON")) {
            if (MonitorService.shouldRun(context)) {
                MonitorService.start(context);
            }
            if (DockPrefs.enabled(context)) {
                DockService.start(context);
            }
        }
    }
}
