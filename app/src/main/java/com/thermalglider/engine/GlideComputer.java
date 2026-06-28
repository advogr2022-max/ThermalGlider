package com.thermalglider.engine;

import com.thermalglider.data.FlightState;
import com.thermalglider.util.GeoUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * GlideComputer — L/D, эллипс долёта с ветром.
 *
 * Разделы 9-10 ТЗ.
 */
public class GlideComputer {

    // L/D: 30s sliding window
    private static final int LD_WINDOW_SEC = 30;
    private static final int LD_BUF_MAX = 32;
    private static final float AIRSPEED_MS = 9.0f;

    // Буферы L/D (кольцевые)
    private final float[] ldLat = new float[LD_BUF_MAX];
    private final float[] ldLon = new float[LD_BUF_MAX];
    private final float[] ldAlt = new float[LD_BUF_MAX];
    private final long[] ldTime = new long[LD_BUF_MAX];
    private int ldHead = 0;
    private int ldCount = 0;

    // Текущие значения
    private float glideRatio;
    private float glideRangeKm;
    private float sinkRate;
    private List<double[]> glideEllipse;

    // Поляра параплана (типичная)
    private static final float POLAR_MIN_SPEED = 7.0f;       // м/с
    private static final float POLAR_MIN_SINK = 1.0f;        // м/с
    private static final float POLAR_OPT_SPEED = 10.6f;      // м/с
    private static final float POLAR_OPT_SINK = 1.1f;        // м/с
    private static final float POLAR_MAX_SPEED = 15.3f;      // м/с
    private static final float POLAR_MAX_SINK = 3.0f;        // м/с

    /** Вызов из FlightManager.tick() */
    public void update(FlightState state, long nowMs) {
        if (!state.hasGpsFix) return;

        // 1. Push в буфер L/D
        ldLat[ldHead] = (float) state.latitude;
        ldLon[ldHead] = (float) state.longitude;
        ldAlt[ldHead] = state.baroAltitude > 0 ? state.baroAltitude : state.gpsAltitude;
        ldTime[ldHead] = nowMs;
        ldHead = (ldHead + 1) % LD_BUF_MAX;
        if (ldCount < LD_BUF_MAX) ldCount++;

        // 2. L/D по треку (30s окно)
        glideRatio = computeLd(state, nowMs);

        // 3. L/D от поляры (fallback)
        if (glideRatio <= 0) {
            glideRatio = polarLd(state.speed);
        }

        // 4. Sink rate
        sinkRate = (glideRatio > 0 && state.speed > 0) ? state.speed / glideRatio : 0;

        // 5. Дальность планирования
        if (state.altitudeAGL > 0 && glideRatio > 0) {
            glideRangeKm = state.altitudeAGL * glideRatio / 1000.0f;
        }

        // 6. Эллипс долёта
        if (state.altitudeAGL > 10 && glideRatio > 1) {
            glideEllipse = computeGlideEllipse(state);
        } else {
            glideEllipse = null;
        }

        // Запись в state
        state.glideRatio = glideRatio;
        state.glideRangeKm = glideRangeKm;
        state.sinkRate = sinkRate;
        state.glideEllipse = glideEllipse;
    }

    /** L/D по отрезку трека (30s окно) */
    private float computeLd(FlightState state, long nowMs) {
        if (ldCount < 2) return 0;

        long cutoff = nowMs - LD_WINDOW_SEC * 1000;

        // Ищем точку, которая была window_sec назад
        int startIdx = -1;
        for (int i = 1; i < ldCount; i++) {
            int idx = (ldHead - 1 - i + LD_BUF_MAX) % LD_BUF_MAX;
            if (ldTime[idx] <= cutoff) {
                startIdx = idx;
                break;
            }
        }

        if (startIdx == -1) return 0;

        int newest = (ldHead - 1 + LD_BUF_MAX) % LD_BUF_MAX;

        double horDistM = GeoUtils.haversineM(
            ldLat[startIdx], ldLon[startIdx],
            ldLat[newest], ldLon[newest]);
        float vertDiff = ldAlt[newest] - ldAlt[startIdx];

        if (vertDiff <= 0) return 99.0f; // набираем
        if (horDistM < 100) return 0;    // слишком мало

        float ratio = (float) (horDistM / vertDiff);
        return Math.min(ratio, 99.0f);
    }

    /** L/D от поляры */
    private float polarLd(float speedMps) {
        float v = Math.max(POLAR_MIN_SPEED, Math.min(speedMps, POLAR_MAX_SPEED));
        // Параболическая аппроксимация
        float sink = 0.018f * v * v - 0.12f * v + 0.2f;
        if (sink <= 0) return 8.0f; // fallback
        return v / sink;
    }

    /** Эллипс долёта с ветром (36 точек) */
    private List<double[]> computeGlideEllipse(FlightState state) {
        float maxRangeM = state.altitudeAGL * glideRatio;
        if (maxRangeM <= 100) return null;

        boolean hasWind = state.windConfidence >= 30 && state.windSpeed > 0.5f;
        float windDirRad = (float) Math.toRadians(state.windDirection);
        float windToRad = (float) Math.toRadians((state.windDirection + 180) % 360);

        List<double[]> points = new ArrayList<>(36);

        for (int i = 0; i < 36; i++) {
            double angle = 2 * Math.PI * i / 36;

            if (hasWind) {
                // С учётом ветра
                float windComp = (float) (state.windSpeed * Math.cos(angle - windToRad));
                float groundSpeed = AIRSPEED_MS + windComp;

                if (groundSpeed <= 0) {
                    points.add(new double[]{state.latitude, state.longitude});
                    continue;
                }

                float flightTime = maxRangeM / AIRSPEED_MS;
                float effectiveRange = groundSpeed * flightTime;

                double[] pt = GeoUtils.offsetPoint(
                    state.latitude, state.longitude,
                    effectiveRange, Math.toDegrees(angle));
                points.add(pt);
            } else {
                // Без ветра — круг
                double[] pt = GeoUtils.offsetPoint(
                    state.latitude, state.longitude,
                    maxRangeM, Math.toDegrees(angle));
                points.add(pt);
            }
        }

        return points;
    }

    public float getGlideRatio() { return glideRatio; }
    public float getGlideRangeKm() { return glideRangeKm; }
    public float getSinkRate() { return sinkRate; }
    public List<double[]> getGlideEllipse() { return glideEllipse; }

    public void reset() {
        ldHead = 0;
        ldCount = 0;
        glideRatio = 0;
        glideRangeKm = 0;
        sinkRate = 0;
        glideEllipse = null;
    }
}
