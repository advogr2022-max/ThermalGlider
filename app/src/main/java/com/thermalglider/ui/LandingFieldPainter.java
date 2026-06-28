package com.thermalglider.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

import com.thermalglider.data.LandingField;
import com.thermalglider.util.Units;

import java.util.List;

/**
 * LandingFieldPainter — отрисовка посадочных площадок на карте.
 *
 * Раздел 11.4 ТЗ.
 */
public class LandingFieldPainter {

    private final Paint strokePaint = new Paint();
    private final Paint fillPaint = new Paint();
    private final Paint textPaint = new Paint();
    private final Paint reachableFill = new Paint();

    public LandingFieldPainter() {
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(2);
        strokePaint.setAntiAlias(true);

        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setAntiAlias(true);

        reachableFill.setStyle(Paint.Style.FILL);
        reachableFill.setColor(Color.argb(40, 0, 200, 0));
        reachableFill.setAntiAlias(true);

        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(24);
        textPaint.setAntiAlias(true);
    }

    /** Отрисовка всех площадок */
    public void draw(Canvas canvas, MapEngine world, List<LandingField> fields,
                     LandingField selectedField) {
        if (fields == null) return;

        for (LandingField field : fields) {
            android.graphics.PointF center = world.geoToScreen(field.centerLat, field.centerLon);
            float radiusPx = field.radiusM * world.metersPerPixel();

            // Цвет по типу
            int color = LandingField.colorByType(field.type);
            strokePaint.setColor(color);

            // Достижимые — зелёная заливка
            if (field.isReachable) {
                canvas.drawCircle(center.x, center.y, Math.max(radiusPx, 5), reachableFill);
            } else {
                fillPaint.setColor(Color.argb(20, 100, 100, 100));
                canvas.drawCircle(center.x, center.y, Math.max(radiusPx, 5), fillPaint);
            }

            // Контур
            canvas.drawCircle(center.x, center.y, Math.max(radiusPx, 5), strokePaint);

            // Подпись (если достаточно приближено)
            if (world.getKmPerPx() < 0.1) {
                String label = field.name;
                // Обрезаем длинные имена
                if (label.length() > 15) label = label.substring(0, 14) + "…";
                canvas.drawText(label, center.x + radiusPx + 5, center.y, textPaint);

                if (field.isReachable) {
                    String info = String.format("%.1fкм ↑%.0fм", field.distanceKm, field.heightAboveM);
                    canvas.drawText(info, center.x + radiusPx + 5, center.y + 18, textPaint);
                }
            }

            // Подсветка выбранной
            if (selectedField != null && field.name.equals(selectedField.name)) {
                Paint hl = new Paint();
                hl.setStyle(Paint.Style.STROKE);
                hl.setColor(Color.argb(255, 255, 255, 255));
                hl.setStrokeWidth(4);
                canvas.drawCircle(center.x, center.y, Math.max(radiusPx, 5) + 5, hl);
            }
        }
    }
}
