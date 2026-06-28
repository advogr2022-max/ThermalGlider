package com.thermalglider.engine;

import com.thermalglider.data.FlightState;
import com.thermalglider.util.GeoUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * WindEstimator — оценка ветра из дрейфа спиралей.
 *
 * Алгоритм Reichmann:
 * 1. Накопить точки одного полного круга (|cumulativeTurn| ≥ 360°)
 * 2. Найти центр масс точек круга
 * 3. Сравнить с центром предыдущего круга → ветер = смещение / время между кругами
 *
 * Раздел 8 ТЗ.
 */
public class WindEstimator {

    // Точки текущего круга
    private final List<double[]> currentCircle = new ArrayList<>(64);
    private long currentCircleStartMs = 0;

    // Центр и время предыдущего завершённого круга
    private double[] prevCircleCenter = null;
    private long prevCircleEndMs = 0;

    // Кумулятивный поворот для детекции полного круга
    private float cumulativeTurn = 0;
    private float lastBearing = -1;

    // Выходные значения
    private float windSpeed;
    private float windDirection;
    private int confidence;

    // Сглаживание
    private float smoothSpeed, smoothDir;
    private boolean hasSmooth = false;

    private static final float MIN_CIRCLING_SPEED = 4f;
    private static final float MAX_CIRCLING_SPEED = 15f;

    /** Вызов из FlightManager.tick() */
    public void update(FlightState state, long nowMs) {
        if (!state.hasGpsFix) return;

        if (state.isCircling) {
            accumulateCirclePoint(state, nowMs);
            checkCircleComplete(state, nowMs);
        } else {
            // Выход из спирали — сбрасываем накопление
            if (!currentCircle.isEmpty()) {
                currentCircle.clear();
                cumulativeTurn = 0;
                lastBearing = -1;
            }
        }

        // Crab angle — если есть компас
        if (!state.isCircling && Math.abs(state.varioFiltered) < 1.3f
            && state.speed > 0.5f && state.windConfidence < 80) {
            // estimateFromCrab — требует SensorController.heading, заглушка
            // TODO: при интеграции компаса
        }

        // Запись в state
        state.windSpeed = windSpeed;
        state.windDirection = windDirection;
        state.windConfidence = confidence;
    }

    private void accumulateCirclePoint(FlightState state, long nowMs) {
        if (currentCircle.isEmpty()) {
            currentCircleStartMs = nowMs;
            cumulativeTurn = 0;
            lastBearing = state.bearing;
        } else {
            // Накапливаем угол поворота (кратчайший путь)
            float delta = state.bearing - lastBearing;
            if (delta > 180) delta -= 360;
            if (delta < -180) delta += 360;
            cumulativeTurn += delta;
            lastBearing = state.bearing;
        }
        currentCircle.add(new double[]{state.latitude, state.longitude, nowMs});
    }

    private void checkCircleComplete(FlightState state, long nowMs) {
        // Полный круг = |cumulativeTurn| >= 360°
        if (Math.abs(cumulativeTurn) < 360f) return;

        // Центр масс точек круга
        double[] center = computeCentroid(currentCircle);
        long circleDurationMs = nowMs - currentCircleStartMs;

        if (prevCircleCenter != null && circleDurationMs > 5000) {
            // Ветер = смещение центра / время между кругами
            double driftM = GeoUtils.haversineM(
                prevCircleCenter[0], prevCircleCenter[1], center[0], center[1]);
            double dtSec = (nowMs - prevCircleEndMs) / 1000.0;

            if (driftM > 2 && driftM < 200 && dtSec > 5) {
                double driftSpeed = driftM / dtSec;
                double driftBearing = GeoUtils.bearingDeg(
                    prevCircleCenter[0], prevCircleCenter[1], center[0], center[1]);
                // meteo "откуда" = driftBearing + 180
                float newDir = (float) ((driftBearing + 180) % 360);
                float newSpeed = (float) driftSpeed;
                int newConf = Math.min(confidence + 25, 100);

                smoothWind(newSpeed, newDir, newConf);
            }
        }

        // Сохраняем как предыдущий круг
        prevCircleCenter = center;
        prevCircleEndMs = nowMs;

        // Начинаем новый круг
        currentCircle.clear();
        currentCircleStartMs = nowMs;
        cumulativeTurn = 0;
        lastBearing = state.bearing;
        // Сохраняем текущую точку как первую нового круга
        currentCircle.add(new double[]{state.latitude, state.longitude, nowMs});
    }

    /** Центр масс набора точек */
    private static double[] computeCentroid(List<double[]> pts) {
        double lat = 0, lon = 0;
        for (double[] p : pts) {
            lat += p[0];
            lon += p[1];
        }
        int n = pts.size();
        return new double[]{lat / n, lon / n};
    }

    /** Экспоненциальное сглаживание */
    private void smoothWind(float newSpeed, float newDir, int newConf) {
        if (!hasSmooth) {
            smoothSpeed = newSpeed;
            smoothDir = newDir;
            confidence = newConf;
            hasSmooth = true;
        } else {
            float alpha = 0.3f;
            float[] avg = GeoUtils.vectorAverage(
                smoothSpeed, smoothDir, newSpeed, newDir, alpha);
            smoothSpeed = avg[0];
            smoothDir = avg[1];
            confidence = Math.min(confidence + 5, 100);
        }

        if (smoothSpeed >= 0.5f) {
            windSpeed = smoothSpeed;
            windDirection = smoothDir;
        }
    }

    public void reset() {
        currentCircle.clear();
        prevCircleCenter = null;
        cumulativeTurn = 0;
        lastBearing = -1;
        windSpeed = 0;
        windDirection = 0;
        confidence = 0;
        hasSmooth = false;
        smoothSpeed = 0;
        smoothDir = 0;
    }
}
