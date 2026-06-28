package com.thermalglider.ui;

import android.graphics.Color;

import com.thermalglider.data.FlightState;

/**
 * InfoBox — все реализации виджетов (22 шт).
 */
public class InfoBox {

    public static final int PADDING = 6;

    public String title;
    protected String unit = "";
    protected String value = "--";
    protected int titleColor = Color.rgb(140, 140, 140);
    protected int valueColor = Color.WHITE;
    protected int bgColor = Color.rgb(10, 10, 15);
    protected int borderColor = Color.rgb(25, 25, 30);

    public android.graphics.RectF rect = new android.graphics.RectF();

    public InfoBox(String title, String unit) { this.title = title; this.unit = unit; }
    public InfoBox(String title) { this(title, ""); }

    public void update(FlightState state) { value = "--"; }

    public void draw(android.graphics.Canvas canvas, android.graphics.Paint titlePaint,
                     android.graphics.Paint valuePaint, android.graphics.Paint unitPaint) {
        android.graphics.Paint bg = new android.graphics.Paint();
        bg.setColor(bgColor);
        canvas.drawRect(rect, bg);

        android.graphics.Paint border = new android.graphics.Paint();
        border.setColor(borderColor);
        border.setStyle(android.graphics.Paint.Style.STROKE);
        border.setStrokeWidth(1);
        canvas.drawRect(rect, border);

        titlePaint.setColor(titleColor);
        titlePaint.setTextSize(rect.height() * 0.18f);
        titlePaint.setTypeface(android.graphics.Typeface.DEFAULT);
        titlePaint.setTextAlign(android.graphics.Paint.Align.LEFT);
        canvas.drawText(title, rect.left + PADDING, rect.top + rect.height() * 0.22f, titlePaint);

        valuePaint.setColor(valueColor);
        valuePaint.setTextSize(rect.height() * 0.55f);  // было 0.48
        valuePaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        valuePaint.setTextAlign(android.graphics.Paint.Align.CENTER);
        canvas.drawText(value, rect.centerX(), rect.bottom - rect.height() * 0.25f, valuePaint);
        valuePaint.setTypeface(android.graphics.Typeface.DEFAULT);

        if (!unit.isEmpty()) {
            unitPaint.setTextSize(rect.height() * 0.15f);
            unitPaint.setColor(android.graphics.Color.rgb(120, 120, 120));
            unitPaint.setTextAlign(android.graphics.Paint.Align.RIGHT);
            canvas.drawText(unit, rect.right - PADDING, rect.bottom - PADDING, unitPaint);
        }
        valuePaint.setTextAlign(android.graphics.Paint.Align.LEFT);
    }

    // ============================
    // === 22 реализации виджетов ===
    // ============================

    /** 0. Vario */
    public static class VarioBox extends InfoBox {
        public VarioBox() { super("Vario", "m/s"); }
        @Override
        public void update(FlightState s) {
            if (!s.hasGpsFix) { value = "--"; return; }
            value = String.format("%+.1f", s.varioFiltered);
            valueColor = s.varioFiltered > 0.5f ? Color.rgb(100, 255, 100)
                : s.varioFiltered < -0.5f ? Color.rgb(255, 80, 80) : Color.WHITE;
        }
    }

    /** 1. Altitude (MSL) */
    public static class AltBox extends InfoBox {
        public AltBox() { super("Alt", "m"); }
        @Override
        public void update(FlightState s) {
            if (!s.hasGpsFix) { value = "--"; return; }
            float a = s.baroAltitude > 0 ? s.baroAltitude : s.gpsAltitude;
            value = String.format("%.0f", a);
        }
    }

    /** 2. Speed */
    public static class SpeedBox extends InfoBox {
        public SpeedBox() { super("Speed", "km/h"); }
        @Override
        public void update(FlightState s) {
            if (!s.hasGpsFix) { value = "--"; return; }
            value = String.format("%.0f", s.speed * 3.6);
            valueColor = s.speed > 15 ? Color.rgb(255, 200, 100) : Color.WHITE;
        }
    }

    /** 3. L/D */
    public static class LdBox extends InfoBox {
        public LdBox() { super("L/D", ""); }
        @Override
        public void update(FlightState s) {
            if (!s.hasGpsFix || s.glideRatio <= 0) { value = "--"; return; }
            if (s.glideRatio >= 99) { value = "\u2191"; valueColor = Color.rgb(0, 255, 255); return; }
            value = String.format("%.1f", s.glideRatio);
            valueColor = s.glideRatio > 5 ? Color.rgb(0, 255, 255) : Color.rgb(255, 200, 100);
        }
    }

    /** 4. Wind */
    public static class WindBox extends InfoBox {
        public WindBox() { super("Wind", "m/s"); }
        @Override
        public void update(FlightState s) {
            if (!s.hasGpsFix || s.windConfidence < 10) { value = "--"; return; }
            value = String.format("%.0f\u00B0 %.1f", s.windDirection, s.windSpeed);
            valueColor = s.windSpeed > 12 ? Color.rgb(255, 80, 80) : Color.WHITE;
        }
    }

    /** 5. AGL */
    public static class AglBox extends InfoBox {
        public AglBox() { super("AGL", "m"); }
        @Override
        public void update(FlightState s) {
            if (!s.hasGpsFix) { value = "--"; return; }
            value = String.format("%.0f", s.altitudeAGL);
            valueColor = s.altitudeAGL < 100 ? Color.rgb(255, 80, 80)
                : s.altitudeAGL < 300 ? Color.rgb(255, 200, 50) : Color.WHITE;
        }
    }

    /** 6. Distance (total) */
    public static class DistBox extends InfoBox {
        public DistBox() { super("Dist", "km"); }
        @Override
        public void update(FlightState s) {
            if (!s.hasGpsFix) { value = "--"; return; }
            value = String.format("%.1f", s.totalDistanceKm);
        }
    }

    /** 7. Time */
    public static class TimeBox extends InfoBox {
        public TimeBox() { super("Time", ""); }
        @Override
        public void update(FlightState s) {
            if (!s.hasGpsFix) { value = "--"; return; }
            long t = s.flightDurationSec;
            value = String.format("%02d:%02d", t / 60, t % 60);
        }
    }

    /** 8. Max Alt */
    public static class MaxAltBox extends InfoBox {
        public MaxAltBox() { super("Max", "m"); }
        @Override
        public void update(FlightState s) {
            if (!s.hasGpsFix) { value = "--"; return; }
            value = String.format("%.0f", s.maxAltitude);
        }
    }

    /** 9. Total Climb */
    public static class TotalClimbBox extends InfoBox {
        public TotalClimbBox() { super("Climb", "m"); }
        @Override
        public void update(FlightState s) {
            if (!s.hasGpsFix) { value = "--"; return; }
            value = String.format("%.0f", s.totalClimb);
        }
    }

    /** 10. Avg L/D */
    public static class AvgLdBox extends InfoBox {
        public AvgLdBox() { super("Avg L/D", ""); }
        @Override
        public void update(FlightState s) {
            if (!s.hasGpsFix || s.glideRatio <= 0) { value = "--"; return; }
            value = String.format("%.1f", s.glideRatio);
        }
    }

    /** 11. Avg Climb */
    public static class AvgClimbBox extends InfoBox {
        public AvgClimbBox() { super("Avg\u2191", "m/s"); }
        @Override
        public void update(FlightState s) {
            if (!s.hasGpsFix) { value = "--"; return; }
            // Используем среднее варио за 30с
            float avg = s.varioFiltered; // пока так, потом 30s avg
            value = String.format("%+.1f", avg);
            valueColor = avg > 0.3f ? Color.rgb(100, 255, 100) : Color.WHITE;
        }
    }

    /** 12. Heading */
    public static class HeadingBox extends InfoBox {
        public HeadingBox() { super("Head", "\u00B0"); }
        @Override
        public void update(FlightState s) {
            if (!s.hasGpsFix) { value = "--"; return; }
            value = String.format("%.0f", s.bearing);
        }
    }

    /** 13. Glide To Goal */
    public static class GlideToGoalBox extends InfoBox {
        public GlideToGoalBox() { super("Glide", ""); }
        @Override
        public void update(FlightState s) {
            if (s.selectedField != null && s.altitudeAGL > 0) {
                float ld = (float) (s.selectedField.distanceKm * 1000 / s.altitudeAGL);
                value = String.format("%.1f", ld);
            } else { value = "\u2014"; }
        }
    }

    /** 14. Goal Distance */
    public static class GoalDistBox extends InfoBox {
        public GoalDistBox() { super("To Goal", "km"); }
        @Override
        public void update(FlightState s) {
            if (s.selectedField != null) {
                value = String.format("%.1f", s.selectedField.distanceKm);
            } else { value = "\u2014"; }
        }
    }

    /** 15. Goal Time */
    public static class GoalTimeBox extends InfoBox {
        public GoalTimeBox() { super("ETA", ""); }
        @Override
        public void update(FlightState s) {
            if (s.selectedField != null && s.speed > 0) {
                double hours = s.selectedField.distanceKm / (s.speed * 3.6);
                int m = (int) (hours * 60);
                int sec = (int) ((hours * 3600) % 60);
                value = String.format("%d:%02d", m, sec);
            } else { value = "\u2014"; }
        }
    }

    /** 16. Ground Elevation */
    public static class GroundElevBox extends InfoBox {
        public GroundElevBox() { super("Terrain", "m"); }
        @Override
        public void update(FlightState s) {
            if (!s.hasGpsFix) { value = "--"; return; }
            float elev = s.gpsAltitude - s.altitudeAGL;
            value = String.format("%.0f", Math.max(0, elev));
        }
    }

    /** 17. Near Thermal */
    public static class NearThermalBox extends InfoBox {
        public NearThermalBox() { super("Thermal", ""); }
        @Override
        public void update(FlightState s) {
            if (s.nearestThermal != null) {
                value = String.format("%s %.0f\u2191%.1f",
                    s.nearestThermal.dirText, s.nearestThermal.distanceM, s.nearestThermal.lift);
                valueColor = Color.rgb(255, 193, 7);
            } else { value = "\u2014"; valueColor = Color.rgb(100, 100, 100); }
        }
    }

    /** 18. Speed Circle */
    public static class SpeedCircleBox extends InfoBox {
        public SpeedCircleBox() { super("Circle", "km/h"); }
        @Override
        public void update(FlightState s) {
            if (s.isCircling) {
                value = String.format("%.0f", s.speed * 3.6);
                valueColor = Color.rgb(255, 193, 7);
            } else { value = "\u2014"; }
        }
    }

    /** 19. Airspace */
    public static class AirspaceBox extends InfoBox {
        public AirspaceBox() { super("AS", ""); }
        @Override
        public void update(FlightState s) { value = "\u2014"; }
    }

    /** 20. Airspace Info */
    public static class AirspaceInfoBox extends InfoBox {
        public AirspaceInfoBox() { super("AS Det", ""); }
        @Override
        public void update(FlightState s) { value = "\u2014"; }
    }

    /** 21. BoxSet — группа из нескольких значений */
    public static class BoxSet extends InfoBox {
        public BoxSet() { super("Set", ""); }
        @Override
        public void update(FlightState s) {
            if (!s.hasGpsFix) { value = "--"; return; }
            value = String.format("%+.1f %s %.0f",
                s.varioFiltered,
                s.isCircling ? "CIR" : "CRS",
                s.baroAltitude > 0 ? s.baroAltitude : s.gpsAltitude);
        }
    }
}
