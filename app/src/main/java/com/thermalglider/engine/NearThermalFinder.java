package com.thermalglider.engine;

import com.thermalglider.data.NearThermal;
import com.thermalglider.util.GeoUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * NearThermalFinder — история встреченных термиков.
 * Сохраняет все завершённые спирали, ищет ближайший.
 *
 * Раздел 7.5 ТЗ.
 */
public class NearThermalFinder {

    private static final int MAX_THERMALS = 50;
    private static final long THERMAL_LIFETIME_MS = 30 * 60 * 1000; // 30 минут

    private final List<ThermalRecord> history = new ArrayList<>();

    private static class ThermalRecord {
        double lat, lon;
        float lift;
        long timeMs;
        ThermalRecord(double lat, double lon, float lift, long timeMs) {
            this.lat = lat; this.lon = lon;
            this.lift = lift; this.timeMs = timeMs;
        }
    }

    /** Добавление завершённой спирали */
    public void addThermal(double centerLat, double centerLon, float avgLift, long timeMs) {
        if (avgLift < 0.3f) return; // слабый — не сохраняем

        history.add(new ThermalRecord(centerLat, centerLon, avgLift, timeMs));

        // Очистка старых
        long cutoff = timeMs - THERMAL_LIFETIME_MS;
        history.removeIf(r -> r.timeMs < cutoff);

        // Ограничение по количеству
        while (history.size() > MAX_THERMALS) {
            history.remove(0);
        }
    }

    /** Поиск ближайшего */
    public NearThermal findNearest(double currentLat, double currentLon, float minLift) {
        if (history.isEmpty()) return null;

        NearThermal best = null;
        float bestDist = Float.MAX_VALUE;
        long now = System.currentTimeMillis();

        for (ThermalRecord r : history) {
            if (r.lift < minLift) continue;
            // Проверка срока жизни
            if (now - r.timeMs > THERMAL_LIFETIME_MS) continue;

            float distM = (float) GeoUtils.haversineM(currentLat, currentLon, r.lat, r.lon);
            if (distM < bestDist) {
                bestDist = distM;
                best = new NearThermal();
                best.lat = r.lat;
                best.lon = r.lon;
                best.lift = r.lift;
                best.distanceM = distM;
                best.bearingDeg = (float) GeoUtils.bearingDeg(currentLat, currentLon, r.lat, r.lon);
                best.dirText = com.thermalglider.util.Units.windDirText(best.bearingDeg);
                best.timeMs = r.timeMs;
            }
        }

        return best;
    }

    public int size() { return history.size(); }

    public void reset() { history.clear(); }
}
