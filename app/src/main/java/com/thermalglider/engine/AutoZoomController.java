package com.thermalglider.engine;

import com.thermalglider.data.FlightState;

/**
 * AutoZoomController — автомасштаб карты по дальности планирования с ветром.
 *
 * Раздел 18.3 ТЗ.
 */
public class AutoZoomController {

    private static final float THERMAL_HEIGHT_LOSS = 200f; // м — в спирали
    private static final float CRUISE_AGL_MINIMUM = 150f;  // м
    private static final float AIRSPEED_MS = 9.0f;
    private static final float ZOOM_ALPHA = 0.03f;  // плавность

    private boolean autoZoomEnabled = true;
    private float targetKmPerPx = 0.02f;

    /** Вызов из FlightManager.tick() */
    public void update(FlightState state, float viewWidthPx, float viewHeightPx) {
        if (!autoZoomEnabled || !state.hasGpsFix) return;

        float heightAGL = state.altitudeAGL;
        float ld = state.glideRatio;

        if (heightAGL <= 0 || ld <= 0) {
            targetKmPerPx = 0.02f;
            state.targetKmPerPx = targetKmPerPx;
            return;
        }

        // 1. Базовая дальность
        float usableHeight;
        if (state.isCircling) {
            usableHeight = Math.min(THERMAL_HEIGHT_LOSS, heightAGL * 0.3f);
        } else {
            usableHeight = heightAGL - CRUISE_AGL_MINIMUM;
            if (usableHeight <= 0) usableHeight = 50;
        }

        float baseRangeKm = (usableHeight * ld) / 1000.0f;
        if (baseRangeKm <= 0.1f) baseRangeKm = 0.5f;

        // 2. Учёт ветра
        float halfW = baseRangeKm;
        float halfH = baseRangeKm;

        if (state.windConfidence >= 30 && state.windSpeed > 0.5f) {
            // Дальность в 4 направлениях
            float[] ranges = new float[4];
            for (int i = 0; i < 4; i++) {
                float dirDeg = i * 90;
                ranges[i] = rangeWithWind(baseRangeKm, state.windSpeed, state.windDirection, dirDeg);
            }

            float upwind = Math.min(Math.min(ranges[0], ranges[1]), Math.min(ranges[2], ranges[3]));
            float downwind = Math.max(Math.max(ranges[0], ranges[1]), Math.max(ranges[2], ranges[3]));
            float crosswind = (upwind + downwind) / 2.0f;

            halfW = Math.max(upwind, crosswind);
            halfH = Math.max(crosswind, upwind * 0.7f);
        }

        // 3. Масштаб по минимальной оси
        float halfWPx = viewWidthPx / 2;
        float halfHPx = viewHeightPx / 2;

        float kmPerPxW = (halfWPx > 0) ? halfW / halfWPx : 999;
        float kmPerPxH = (halfHPx > 0) ? halfH / halfHPx : 999;

        targetKmPerPx = Math.min(kmPerPxW, kmPerPxH);
        targetKmPerPx = Math.max(0.001f, Math.min(targetKmPerPx, 50.0f));

        state.targetKmPerPx = targetKmPerPx;

        // Плавное обновление
        state.currentKmPerPx += (targetKmPerPx - state.currentKmPerPx) * ZOOM_ALPHA;
    }

    private float rangeWithWind(float baseRangeKm, float windSpeed, float windDirDeg, float directionDeg) {
        float windTo = (windDirDeg + 180) % 360;
        float angle = (float) Math.toRadians(directionDeg - windTo);
        float windComp = windSpeed * (float) Math.cos(angle);
        float groundSpeed = AIRSPEED_MS + windComp;

        if (groundSpeed <= 0) return 0;

        float flightTimeSec = (baseRangeKm * 1000) / AIRSPEED_MS;
        return (groundSpeed * flightTimeSec) / 1000.0f;
    }

    public void setAutoZoomEnabled(boolean enabled) { this.autoZoomEnabled = enabled; }
    public boolean isAutoZoomEnabled() { return autoZoomEnabled; }
    public float getTargetKmPerPx() { return targetKmPerPx; }

    public void reset() {
        targetKmPerPx = 0.02f;
        autoZoomEnabled = true;
    }
}
