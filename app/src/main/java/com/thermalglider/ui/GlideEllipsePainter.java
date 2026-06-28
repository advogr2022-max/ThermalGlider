package com.thermalglider.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;

import java.util.List;

/**
 * GlideEllipsePainter — эллипс долёта с ветром.
 * Полупрозрачная жёлтая заливка + граница.
 *
 * Раздел 10.3 ТЗ.
 */
public class GlideEllipsePainter {

    private final Paint fillPaint = new Paint();
    private final Paint strokePaint = new Paint();

    public GlideEllipsePainter() {
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(Color.argb(30, 255, 255, 0));
        fillPaint.setAntiAlias(true);

        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setColor(Color.argb(80, 255, 255, 0));
        strokePaint.setStrokeWidth(2);
        strokePaint.setAntiAlias(true);
    }

    /** Отрисовка эллипса */
    public void draw(Canvas canvas, MapEngine world, List<double[]> ellipsePoints) {
        if (ellipsePoints == null || ellipsePoints.size() < 3) return;

        Path path = new Path();
        boolean first = true;

        for (double[] pt : ellipsePoints) {
            android.graphics.PointF screen = world.geoToScreen(pt[0], pt[1]);
            if (first) {
                path.moveTo(screen.x, screen.y);
                first = false;
            } else {
                path.lineTo(screen.x, screen.y);
            }
        }
        path.close();

        // Заливка
        canvas.drawPath(path, fillPaint);
        // Граница
        canvas.drawPath(path, strokePaint);
    }
}
