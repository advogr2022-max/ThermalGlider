package com.thermalglider.engine;

import com.thermalglider.data.FlightState;
import com.thermalglider.util.GeoUtils;

/**
 * WindEstimator — оценка ветра из дрейфа спиралей + crab angle.
 *
 * Раздел 8 ТЗ.
 */
public class WindEstimator {

    // Для спирального дрейфа
    private static final long ESTIMATE_WINDOW_MS = 30000;
    private static final float MIN_CIRCLING_FOR_WIND = 1.5f; // минимум оборотов

    private final float[] circleLat = new float[50];
    private final float[] circleLon = new float[50];
    private final long[] circleTime = new long[50];
    private int circleCount = 0;

    // Выходные значения
    private float windSpeed;
    private float windDirection;
    private int confidence;

    // Сглаживание
    private float smoothSpeed, smoothDir;
    private boolean hasSmooth = false;

    /** Вызов из FlightManager.tick() */
    public void update(FlightState state, long nowMs) {
        if (!state.hasGpsFix) return;

        // Метод 1: из спирального дрейфа
        if (state.isCircling && state.thermalSector != null) {
            onCircleCenter(state.thermalSector.centerLat, state.thermalSector.centerLon, nowMs);
        }

        // Метод 2: из crab angle (прямой полёт, малый vario)
        if (!state.isCircling && Math.abs(state.varioFiltered) < 1.3f
            && state.speed > 0.5f && state.windConfidence < 80) {
            estimateFromCrab(state);
        }

        // Запись в state
        state.windSpeed = windSpeed;
        state.windDirection = windDirection;
        state.windConfidence = confidence;
    }

    /** Дрейф центров спиралей */
    private void onCircleCenter(double lat, double lon, long nowMs) {
        if (circleCount >= 50) {
            // Сдвиг
            System.arraycopy(circleLat, 1, circleLat, 0, circleCount - 1);
            System.arraycopy(circleLon, 1, circleLon, 0, circleCount - 1);
            System.arraycopy(circleTime, 1, circleTime, 0, circleCount - 1);
            circleCount--;
        }
        circleLat[circleCount] = (float) lat;
        circleLon[circleCount] = (float) lon;
        circleTime[circleCount] = nowMs;
        circleCount++;

        if (circleCount < 2) return;

        // Очистка старых
        long cutoff = nowMs - ESTIMATE_WINDOW_MS;
        int oldest = 0;
        while (oldest < circleCount && circleTime[oldest] < cutoff) oldest++;
        if (oldest > 0) {
            int newCount = circleCount - oldest;
            System.arraycopy(circleLat, oldest, circleLat, 0, newCount);
            System.arraycopy(circleLon, oldest, circleLon, 0, newCount);
            System.arraycopy(circleTime, oldest, circleTime, 0, newCount);
            circleCount = newCount;
        }

        if (circleCount < 2) return;

        double firstLat = circleLat[0], firstLon = circleLon[0];
        double lastLat = circleLat[circleCount - 1], lastLon = circleLon[circleCount - 1];
        double dt = (circleTime[circleCount - 1] - circleTime[0]) / 1000.0;

        if (dt < 5) return;

        double distM = GeoUtils.haversineM(firstLat, firstLon, lastLat, lastLon);
        double driftSpeed = distM / dt;

        if (driftSpeed > 0.3f && driftSpeed < 30) {
            double driftBearing = GeoUtils.bearingDeg(firstLat, firstLon, lastLat, lastLon);
            float newDir = (float) ((driftBearing + 180) % 360); // meteo: откуда
            float newSpeed = (float) driftSpeed;
            int newConf = Math.min(circleCount * 10, 100);

            smoothWind(newSpeed, newDir, newConf);
        }
    }

    /** Crab angle: разница heading и track */
    private void estimateFromCrab(FlightState state) {
        // Упрощённо: heading ≈ bearing из GPS
        float airspeed = 9.0f; // типичная скорость параплана
        float crab = state.bearing - state.bearing; // будет 0 без компаса
        // Без внешнего компаса crab не определить
        // TODO: при интеграции компаса из rotation vector
    }

    /** Экспоненциальное сглаживание с учётом уверенности */
    private void smoothWind(float newSpeed, float newDir, int newConf) {
        if (!hasSmooth) {
            smoothSpeed = newSpeed;
            smoothDir = newDir;
            confidence = newConf;
            hasSmooth = true;
        } else {
            float alpha = 0.1f + confidence * 0.005f;
            alpha = Math.min(alpha, 0.6f);

            float[] avg = GeoUtils.vectorAverage(
                smoothSpeed, smoothDir, newSpeed, newDir, alpha);
            smoothSpeed = avg[0];
            smoothDir = avg[1];
            confidence = Math.min(confidence + 5, 100);
        }

        // Порог: ветер <3 м/с не обновляем (шум)
        if (smoothSpeed >= 0.5f) {
            windSpeed = smoothSpeed;
            windDirection = smoothDir;
        }
    }

    public void reset() {
        circleCount = 0;
        windSpeed = 0;
        windDirection = 0;
        confidence = 0;
        hasSmooth = false;
        smoothSpeed = 0;
        smoothDir = 0;
    }
}
