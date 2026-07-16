package com.monitor.sysmon;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Filled line-graph over the last 24h. Plots one of two series:
 *   MODE_RAM  – RAM used %, fixed 0..100 axis
 *   MODE_TEMP – battery temperature °C, auto-scaled axis
 * Series, line color and axis labels are configurable so the same view renders
 * both the RAM-history and the temperature cards.
 */
public class GraphView extends View {

    public static final int MODE_RAM = 0;
    public static final int MODE_TEMP = 1;

    private List<RamLog.Sample> data;
    private long nowMs;
    private int mode = MODE_RAM;
    private int lineColor = 0xFF4F9CF9;
    private int fillTop = 0x554F9CF9;
    private int fillBot = 0x084F9CF9;

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private static final int GRID = 0xFF2A303B;
    private static final int LABEL = 0xFF8A93A2;

    public GraphView(Context c) {
        super(c);
        float density = c.getResources().getDisplayMetrics().density;
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2.5f * density);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        gridPaint.setColor(GRID);
        gridPaint.setStrokeWidth(1f * density);
        textPaint.setColor(LABEL);
        textPaint.setTextSize(11f * density);
        linePaint.setColor(lineColor);
    }

    /** Configure which series and color this graph renders. */
    public void setMode(int mode, int lineColor) {
        this.mode = mode;
        this.lineColor = lineColor;
        this.fillTop = (lineColor & 0x00FFFFFF) | 0x55000000;
        this.fillBot = (lineColor & 0x00FFFFFF) | 0x08000000;
        linePaint.setColor(lineColor);
        invalidate();
    }

    public void setData(List<RamLog.Sample> data, long nowMs) {
        this.data = data;
        this.nowMs = nowMs;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas cv) {
        int w = getWidth();
        int h = getHeight();
        float padL = textPaint.getTextSize() * 3.0f;
        float padB = textPaint.getTextSize() * 1.8f;
        float gw = w - padL;
        float gh = h - padB;

        // y-axis range depends on the series
        double yMin, yMax;
        if (mode == MODE_TEMP) {
            double[] r = tempRange();
            yMin = r[0]; yMax = r[1];
        } else {
            yMin = 0; yMax = 100;
        }
        double span = (yMax - yMin) <= 0 ? 1 : (yMax - yMin);

        // horizontal grid + y labels (5 lines)
        for (int i = 0; i <= 4; i++) {
            float y = gh - (gh * i / 4f);
            cv.drawLine(padL, y, w, y, gridPaint);
            double val = yMin + span * i / 4.0;
            String lbl = (mode == MODE_TEMP)
                    ? String.format("%.0f°", val)
                    : ((int) Math.round(val)) + "%";
            cv.drawText(lbl, 0, y + textPaint.getTextSize() / 3f, textPaint);
        }
        // x labels: -24h ... now
        cv.drawText("-24h", padL, h - 2, textPaint);
        cv.drawText("now", w - textPaint.measureText("now"), h - 2, textPaint);

        List<RamLog.Sample> pts = usablePoints();
        if (pts == null || pts.size() < 2) {
            cv.drawText("Collecting data…", padL + gw / 3f, gh / 2f, textPaint);
            return;
        }

        long start = nowMs - RamLog.WINDOW_MS;
        Path line = new Path();
        Path fill = new Path();
        boolean first = true;
        float lastX = padL, lastY = gh;
        for (RamLog.Sample s : pts) {
            float x = padL + gw * (s.t - start) / (float) RamLog.WINDOW_MS;
            double v = (mode == MODE_TEMP) ? s.tempC() : Math.max(0, Math.min(100, s.pct()));
            float frac = (float) ((v - yMin) / span);
            frac = Math.max(0f, Math.min(1f, frac));
            float y = gh - gh * frac;
            if (first) {
                line.moveTo(x, y);
                fill.moveTo(x, gh);
                fill.lineTo(x, y);
                first = false;
            } else {
                line.lineTo(x, y);
                fill.lineTo(x, y);
            }
            lastX = x; lastY = y;
        }
        fill.lineTo(lastX, gh);
        fill.close();

        fillPaint.setShader(new LinearGradient(0, 0, 0, gh,
                fillTop, fillBot, Shader.TileMode.CLAMP));
        cv.drawPath(fill, fillPaint);
        cv.drawPath(line, linePaint);
    }

    /** In temp mode, only samples that carry a temperature are plotted. */
    private List<RamLog.Sample> usablePoints() {
        if (mode != MODE_TEMP) return data;
        if (data == null) return null;
        ArrayList<RamLog.Sample> out = new ArrayList<>();
        for (RamLog.Sample s : data) if (s.hasTemp()) out.add(s);
        return out;
    }

    /** Auto-scaled temperature axis with headroom, snapped to 5°. */
    private double[] tempRange() {
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        if (data != null) {
            for (RamLog.Sample s : data) {
                if (!s.hasTemp()) continue;
                double t = s.tempC();
                if (t < min) min = t;
                if (t > max) max = t;
            }
        }
        if (min == Double.MAX_VALUE) return new double[]{20, 45}; // no data yet
        double lo = Math.floor((min - 2) / 5.0) * 5.0;
        double hi = Math.ceil((max + 2) / 5.0) * 5.0;
        if (hi - lo < 10) hi = lo + 10;
        return new double[]{lo, hi};
    }
}
