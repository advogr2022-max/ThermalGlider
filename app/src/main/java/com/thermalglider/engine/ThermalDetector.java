package com.thermalglider.engine;

import com.thermalglider.data.FlightState;
import com.thermalglider.data.ThermalSector;
import com.thermalglider.util.GeoUtils;

/**
 * ThermalDetector — обнаружение спиралей и накопление данных квадрантов.
 *
 * Раздел 7.1-7.3 ТЗ.
 */
public class ThermalDetector {

    // Пороги из ТЗ
    private static final float MIN_CIRCLING_RATE = 30f;     // °/с
    private static final int MIN_CIRCLING_POINTS = 6;       // точек подряд
    private static final long MAX_CIRCLING_GAP_MS = 3000;   // макс пауза между точками

    private boolean wasCircling = false;
    private int consecutivePoints = 0;
    private float lastBearing = 0;
    private long lastTurningTime = 0;
    private ThermalSector currentThermal;

    // Кэш трека спирали для кольцевых точек
    private static final int TRAIL_MAX = 500;
    private final float[] trailLat = new float[TRAIL_MAX];
    private final float[] trailLon = new float[TRAIL_MAX];
    private int trailHead = 0;
    private int trailCount = 0;

    public ThermalDetector() {}

    /** Главный вызов из FlightManager.tick() */
    public void update(FlightState state, long nowMs) {
        if (!state.hasGpsFix || state.speed < 4 || state.speed > 15) {
            if (wasCircling) finalizeCircling(state);
            return;
        }

        // Вычисляем скорость поворота
        float bearing = state.bearing;
        float turnRate = Math.abs(bearing - lastBearing);
        // Нормализация для перехода через 0/360
        if (turnRate > 180) turnRate = 360 - turnRate;

        float dt = (nowMs - lastTurningTime) / 1000f;
        if (dt > 0.3f) {
            turnRate /= dt;
        }

        state.turnRate = turnRate;

        if (turnRate >= MIN_CIRCLING_RATE) {
            // В спирали
            if (!wasCircling) {
                // Начало спирали
                wasCircling = true;
                consecutivePoints = 1;
                currentThermal = new ThermalSector();
                currentThermal.startTimeMs = nowMs;
                currentThermal.centerLat = state.latitude;
                currentThermal.centerLon = state.longitude;
            } else {
                consecutivePoints++;
            }

            // Накопление данных квадрантов
            if (currentThermal != null) {
                accumulateThermalData(state, nowMs);
            }

            state.isCircling = true;
            state.circlingPointCount = consecutivePoints;
            state.thermalSector = currentThermal;
            lastTurningTime = nowMs;

        } else if (wasCircling) {
            // Вышли из спирали
            if (consecutivePoints >= MIN_CIRCLING_POINTS) {
                finalizeCircling(state);
            } else {
                // Слишком короткая спираль — сброс
                wasCircling = false;
                consecutivePoints = 0;
                currentThermal = null;
                state.isCircling = false;
                state.thermalSector = null;
            }
        }

        lastBearing = bearing;
    }

    private void accumulateThermalData(FlightState state, long nowMs) {
        ThermalSector t = currentThermal;
        double lat = state.latitude;
        double lon = state.longitude;

        // Обновление геометрического центра (EMA)
        int n = consecutivePoints;
        float alpha = 1.0f / Math.min(n + 1, 15);
        t.centerLat += alpha * (lat - t.centerLat);
        t.centerLon += alpha * (lon - t.centerLon);

        // Относительное положение
        double dx = (lon - t.centerLon) * Math.cos(Math.toRadians(t.centerLat));
        double dy = lat - t.centerLat;

        // Квадрант (от центра спирали)
        double angle = Math.atan2(dy, dx);
        int quad;
        if (angle >= -Math.PI / 4 && angle < Math.PI / 4) {
            quad = 0; // E
        } else if (angle >= Math.PI / 4 && angle < 3 * Math.PI / 4) {
            quad = 1; // N
        } else if (angle >= 3 * Math.PI / 4 || angle < -3 * Math.PI / 4) {
            quad = 2; // W
        } else {
            quad = 3; // S
        }

        // Обновление подъёма в квадранте
        float alphaQ = 0.3f;
        t.quadrantLift[quad] = (1 - alphaQ) * t.quadrantLift[quad] + alphaQ * state.varioFiltered;
        t.quadrantCenterLat[quad] = lat;
        t.quadrantCenterLon[quad] = lon;

        // Средний подъём
        t.avgLift = (t.quadrantLift[0] + t.quadrantLift[1] + t.quadrantLift[2] + t.quadrantLift[3]) / 4.0f;
        t.energy = t.avgLift;

        // Максимальный подъём
        if (state.varioFiltered > t.maxLift) {
            t.maxLift = state.varioFiltered;
        }

        // Радиус спирали
        float distM = (float) GeoUtils.haversineM(t.centerLat, t.centerLon, lat, lon);
        if (distM > t.radius) t.radius = distM;

        // Кэш трека
        trailLat[trailHead] = (float) lat;
        trailLon[trailHead] = (float) lon;
        trailHead = (trailHead + 1) % TRAIL_MAX;
        if (trailCount < TRAIL_MAX) trailCount++;

        // Thermal core
        updateThermalCore(t);

        t.pointCount = consecutivePoints;
        t.durationMs = nowMs - t.startTimeMs;
    }

    private void updateThermalCore(ThermalSector t) {
        int best = t.bestQuadrantIndex();
        int worst = t.worstQuadrantIndex();
        float bestLift = t.quadrantLift[best];
        float worstLift = t.quadrantLift[worst];

        if (bestLift <= 0) {
            t.thermalCoreLat = t.centerLat;
            t.thermalCoreLon = t.centerLon;
            return;
        }

        // Сдвиг в сторону лучшего квадранта
        float liftDiff = bestLift - worstLift;
        float maxShift = t.radius * 0.5f;
        float shift = Math.min(liftDiff / Math.max(Math.abs(bestLift), 0.1f) * maxShift, maxShift);

        // Направление на лучший квадрант: E=0, N=π/2, W=π, S=-π/2
        double[] qAngles = {0, Math.PI / 2, Math.PI, -Math.PI / 2};
        double targetAngle = qAngles[best];

        double dlat = shift * Math.cos(targetAngle) / 111320.0;
        double dlon = shift * Math.sin(targetAngle) / (111320.0 * Math.cos(Math.toRadians(t.centerLat)));

        t.thermalCoreLat = t.centerLat + dlat;
        t.thermalCoreLon = t.centerLon + dlon;
    }

    private void finalizeCircling(FlightState state) {
        wasCircling = false;
        consecutivePoints = 0;
        state.isCircling = false;
        // thermalSector остаётся для рендеринга до следующей спирали
    }

    public boolean isCircling() { return wasCircling; }
    public ThermalSector getCurrentThermal() { return currentThermal; }

    public float[] getTrailLat() { return trailLat; }
    public float[] getTrailLon() { return trailLon; }
    public int getTrailCount() { return trailCount; }

    public void reset() {
        wasCircling = false;
        consecutivePoints = 0;
        currentThermal = null;
        trailCount = 0;
        trailHead = 0;
    }
}
