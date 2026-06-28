package com.thermalglider.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;

import com.thermalglider.data.FlightState;
import com.thermalglider.data.ThermalSector;

/**
 * ThermalPainter — отрисовка термиков на карте.
 * 3D кольца, ядро, квадрантные стрелки.
 *
 * Раздел 18.1-18.2 ТЗ.
 */
public class ThermalPainter {

    private final Paint circlePaint = new Paint();
    private final Paint fillPaint = new Paint();
    private final Paint trackPaint = new Paint();
    private final Paint textPaint = new Paint();
    private final Paint arrowPaint = new Paint();

    public ThermalPainter() {
        circlePaint.setStyle(Paint.Style.STROKE);
        circlePaint.setAntiAlias(true);
        circlePaint.setColor(Color.argb(180, 255, 255, 255));

        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setAntiAlias(true);

        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setColor(Color.argb(64, 200, 200, 200));
        trackPaint.setStrokeWidth(3);
        trackPaint.setStrokeJoin(Paint.Join.ROUND);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(32);
        textPaint.setAntiAlias(true);

        arrowPaint.setStyle(Paint.Style.FILL);
        arrowPaint.setAntiAlias(true);
    }

    /** Главная отрисовка */
    public void draw(Canvas canvas, MapEngine world, FlightState state,
                     float[] trailLat, float[] trailLon, int trailCount) {
        if (!state.isCircling || state.thermalSector == null) return;

        ThermalSector t = state.thermalSector;
        android.graphics.PointF center = world.geoToScreen(t.centerLat, t.centerLon);
        android.graphics.PointF core = world.geoToScreen(t.thermalCoreLat, t.thermalCoreLon);
        float radiusPx = t.radius * world.metersPerPixel();

        // 1. След трека
        drawTrail(canvas, world, trailLat, trailLon, trailCount);

        // 2. 3D кольца
        draw3dRings(canvas, center, core, radiusPx);

        // 3. Оранжевое ядро
        circlePaint.setColor(Color.argb(200, 255, 128, 0));
        circlePaint.setStrokeWidth(3);
        canvas.drawCircle(core.x, core.y, Math.max(radiusPx * 0.3f, 10), circlePaint);

        // 4. Квадрантные стрелки
        drawQuadHelpers(canvas, world, t, core);

        // 5. Текст с подъёмом
        String liftText = String.format("%+.1f", t.avgLift);
        canvas.drawText(liftText, core.x - 20, core.y - Math.max(radiusPx, 40) - 10, textPaint);
    }

    private void drawTrail(Canvas canvas, MapEngine world,
                           float[] trailLat, float[] trailLon, int count) {
        if (count < 2) return;
        Path path = new Path();
        boolean first = true;
        for (int i = 0; i < count; i++) {
            int idx = i; // от старых к новым
            android.graphics.PointF pt = world.geoToScreen(trailLat[idx], trailLon[idx]);
            if (first) {
                path.moveTo(pt.x, pt.y);
                first = false;
            } else {
                path.lineTo(pt.x, pt.y);
            }
        }
        canvas.drawPath(path, trackPaint);
    }

    private void draw3dRings(Canvas canvas, android.graphics.PointF centerFrom,
                              android.graphics.PointF centerTo, float radius) {
        int nRings = 7;
        circlePaint.setColor(Color.argb(180, 200, 200, 200));
        for (int i = 0; i < nRings; i++) {
            float t = (i + 1) / (float) nRings;
            float cx = centerFrom.x + (centerTo.x - centerFrom.x) * t;
            float cy = centerFrom.y + (centerTo.y - centerFrom.y) * t;
            float r = radius * (1.0f - 0.3f * t);
            int alpha = 250 - i * 31;
            circlePaint.setAlpha(Math.max(alpha, 30));
            canvas.drawCircle(cx, cy, Math.max(r, 5), circlePaint);
        }
        circlePaint.setAlpha(255);
    }

    private void drawQuadHelpers(Canvas canvas, MapEngine world,
                                  ThermalSector t, android.graphics.PointF core) {
        // 4 направления: E, N, W, S
        float[] dx = {1, 0, -1, 0};
        float[] dy = {0, -1, 0, 1};

        for (int i = 0; i < 4; i++) {
            float lift = t.quadrantLift[i];
            if (Math.abs(lift) < 0.1f) continue;

            float length = Math.min(Math.abs(lift) * 50, 100);

            if (lift > 0) {
                arrowPaint.setColor(Color.argb(180, 0, 100, 255)); // синий = подъём
            } else {
                arrowPaint.setColor(Color.argb(180, 255, 0, 0));   // красный = снижение
            }

            float x1 = core.x + dx[i] * length;
            float y1 = core.y + dy[i] * length;
            canvas.drawLine(core.x, core.y, x1, y1, arrowPaint);
            canvas.drawCircle(x1, y1, Math.max(5, length * 0.15f), arrowPaint);
        }
    }
}
