package com.monitor.sysmon;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Draggable floating overlay bubble showing live CPU/RAM.
 *  Shape/size/color/opacity/metric are read from DockPrefs. */
public class DockService extends Service {

    private static final String CHANNEL = "sysmon_dock";
    private static final int NOTIF_ID = 2002;

    private WindowManager wm;
    private View dock;
    private TextView topLabel, topValue, bottomLabel, bottomValue;
    private WindowManager.LayoutParams lp;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SysInfo sys = new SysInfo();

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        sys.seedCpu();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIF_ID, buildNotification());
        if (dock == null) addDock();
        else applyStyle();
        handler.post(ticker);
        return START_STICKY;
    }

    private int type() {
        if (Build.VERSION.SDK_INT >= 26)
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        return WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void addDock() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);

        topLabel = mkText(11, 0xFF0E1116, true);
        topValue = mkText(20, 0xFF0E1116, true);
        bottomLabel = mkText(11, 0xFF0E1116, true);
        bottomValue = mkText(20, 0xFF0E1116, true);
        box.addView(topLabel);
        box.addView(topValue);
        box.addView(bottomLabel);
        box.addView(bottomValue);
        dock = box;

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = DockPrefs.x(this);
        lp.y = DockPrefs.y(this);

        applyStyle();
        attachDrag(box);
        try { wm.addView(dock, lp); } catch (Exception ignored) {}
    }

    private TextView mkText(int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private void applyStyle() {
        int shape = DockPrefs.shape(this);
        int sizePx = dp(DockPrefs.sizeDp(this));
        int base = DockPrefs.color(this);
        int op = DockPrefs.opacity(this);
        int col = (base & 0x00FFFFFF) | (op << 24);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(col);
        switch (shape) {
            case DockPrefs.SHAPE_CIRCLE:  bg.setShape(GradientDrawable.OVAL); break;
            case DockPrefs.SHAPE_ROUNDED: bg.setShape(GradientDrawable.RECTANGLE); bg.setCornerRadius(dp(18)); break;
            default:                      bg.setShape(GradientDrawable.RECTANGLE); bg.setCornerRadius(dp(4)); break;
        }
        bg.setStroke(dp(2), 0x33000000);
        dock.setBackground(bg);
        dock.setMinimumWidth(sizePx);
        dock.setMinimumHeight(sizePx);
        int padH = shape == DockPrefs.SHAPE_CIRCLE ? dp(4) : dp(10);
        dock.setPadding(dp(10), padH, dp(10), padH);

        // pick contrasting text color for readability
        int textColor = luminance(base) > 0.6 ? 0xFF0E1116 : 0xFFFFFFFF;
        topLabel.setTextColor(textColor);
        topValue.setTextColor(textColor);
        bottomLabel.setTextColor(textColor);
        bottomValue.setTextColor(textColor);

        int metric = DockPrefs.metric(this);
        boolean showTop = metric == DockPrefs.METRIC_CPU || metric == DockPrefs.METRIC_BOTH;
        boolean showBottom = metric == DockPrefs.METRIC_RAM || metric == DockPrefs.METRIC_BOTH;
        topLabel.setText("CPU"); bottomLabel.setText("RAM");
        topLabel.setVisibility(showTop ? View.VISIBLE : View.GONE);
        topValue.setVisibility(showTop ? View.VISIBLE : View.GONE);
        bottomLabel.setVisibility(showBottom ? View.VISIBLE : View.GONE);
        bottomValue.setVisibility(showBottom ? View.VISIBLE : View.GONE);
    }

    private double luminance(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        return (0.299 * r + 0.587 * g + 0.114 * b) / 255.0;
    }

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            updateValues();
            handler.postDelayed(this, 1000);
        }
    };

    private void updateValues() {
        if (dock == null) return;
        SysInfo.Cpu cpu = sys.sampleCpu();
        if (cpu.pct >= 0) topValue.setText(String.format("%.0f%%", cpu.pct));
        else topValue.setText("—");

        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        double ram = mi.totalMem > 0 ? (100.0 * (mi.totalMem - mi.availMem) / mi.totalMem) : 0;
        bottomValue.setText(String.format("%.0f%%", ram));
    }

    private float touchX, touchY; private int startX, startY; private boolean moved;

    private void attachDrag(View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View view, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        touchX = e.getRawX(); touchY = e.getRawY();
                        startX = lp.x; startY = lp.y; moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int nx = startX + (int) (e.getRawX() - touchX);
                        int ny = startY + (int) (e.getRawY() - touchY);
                        if (Math.abs(nx - startX) > 8 || Math.abs(ny - startY) > 8) moved = true;
                        lp.x = nx; lp.y = ny;
                        try { wm.updateViewLayout(dock, lp); } catch (Exception ignored) {}
                        return true;
                    case MotionEvent.ACTION_UP:
                        DockPrefs.setPos(DockService.this, lp.x, lp.y);
                        if (!moved) {
                            // tap opens the app
                            Intent i = new Intent(DockService.this, MainActivity.class);
                            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(i);
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL,
                        "Floating Dock", NotificationManager.IMPORTANCE_MIN);
                nm.createNotificationChannel(ch);
            }
        }
        Intent open = new Intent(this, MainActivity.class);
        int pf = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) pf |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, pf);
        Notification.Builder b = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setContentTitle("System Monitor dock")
                .setContentText("Floating usage dock is active")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .setContentIntent(pi);
        return b.build();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(ticker);
        if (dock != null) { try { wm.removeView(dock); } catch (Exception ignored) {} dock = null; }
        DockPrefs.setEnabled(this, false);
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    // ---- control helpers ----
    public static void start(Context c) {
        DockPrefs.setEnabled(c, true);
        Intent i = new Intent(c, DockService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }
    public static void restyle(Context c) {
        if (!DockPrefs.enabled(c)) return;
        Intent i = new Intent(c, DockService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }
    public static void stop(Context c) {
        DockPrefs.setEnabled(c, false);
        c.stopService(new Intent(c, DockService.class));
    }
}
