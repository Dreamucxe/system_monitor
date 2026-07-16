package com.monitor.sysmon;

import android.app.ActivityManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.widget.RemoteViews;

/**
 * Compact 4-stat home-screen widget: CPU • RAM • Temp • Net(down).
 *
 * The widget cannot run a per-second loop cheaply, so it paints the snapshot
 * MonitorService last wrote to {@link WidgetState}. When the service is not
 * running it falls back to a one-shot read of the cheap metrics (RAM, temp)
 * plus the last stored CPU/net, refreshed on the system updatePeriodMillis
 * tick and whenever the user taps it.
 */
public class MonitorWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) render(context, mgr, id);
    }

    /** Called by MonitorService each cycle to refresh all live widgets. */
    public static void refresh(Context context) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        ComponentName cn = new ComponentName(context, MonitorWidget.class);
        int[] ids = mgr.getAppWidgetIds(cn);
        if (ids == null) return;
        MonitorWidget w = new MonitorWidget();
        for (int id : ids) w.render(context, mgr, id);
    }

    private void render(Context context, AppWidgetManager mgr, int id) {
        RemoteViews rv = new RemoteViews(context.getPackageName(), R.layout.widget_monitor);

        // Prefer the service snapshot; fall back to a cheap one-shot read.
        float ram = WidgetState.ram(context);
        float temp = WidgetState.temp(context);
        float cpu = WidgetState.cpu(context);
        float net = WidgetState.net(context);

        if (ram < 0) {
            try {
                ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                if (mi.totalMem > 0) ram = (float) (100.0 * (mi.totalMem - mi.availMem) / mi.totalMem);
            } catch (Exception ignored) {}
        }
        if (temp < 0) {
            try { temp = (float) SysInfo.readBattery(context).tempC; } catch (Exception ignored) {}
        }

        rv.setTextViewText(R.id.w_cpu,  cpu  >= 0 ? String.format("%.0f%%", cpu)  : "—");
        rv.setTextViewText(R.id.w_ram,  ram  >= 0 ? String.format("%.0f%%", ram)  : "—");
        rv.setTextViewText(R.id.w_temp, temp >= 0 ? String.format("%.1f°", temp)  : "—");
        rv.setTextViewText(R.id.w_net,  net  >= 0 ? NetStats.fmt(net)             : "—");

        // Tapping the widget opens the app.
        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(context, 0, open, flags);
        rv.setOnClickPendingIntent(R.id.w_root, pi);

        mgr.updateAppWidget(id, rv);
    }
}
