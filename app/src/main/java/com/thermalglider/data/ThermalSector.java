package com.thermalglider.data;

/**
 * ThermalSector — сектор термика.
 * Содержит геометрию спирали + статистику по 4 квадрантам.
 *
 * Раздел 2.4 ТЗ.
 */
public class ThermalSector {

    // === Геометрия ===
    /** Геометрический центр спирали */
    public double centerLat, centerLon;
    /** Предполагаемый центр потока (смещён к лучшему квадранту) */
    public double thermalCoreLat, thermalCoreLon;
    /** Радиус термической зоны (м) */
    public float radius;

    // === 4 квадранта (0=E, 1=N, 2=W, 3=S) ===
    /** Центр каждого квадранта */
    public final double[] quadrantCenterLat = new double[4];
    public final double[] quadrantCenterLon = new double[4];
    /** Подъём в каждом квадранте (м/с) */
    public final float[] quadrantLift = new float[4];

    // === Статистика ===
    /** Средний подъём (м/с) */
    public float avgLift;
    /** Максимальный встреченный подъём (м/с) */
    public float maxLift;
    /** Энергия потока */
    public float energy;
    /** Количество точек в спирали */
    public int pointCount;
    /** Длительность спирали (мс) */
    public long durationMs;
    /** Время начала спирали (epoch ms) */
    public long startTimeMs;

    public ThermalSector() {
        reset();
    }

    public void reset() {
        centerLat = 0;
        centerLon = 0;
        thermalCoreLat = 0;
        thermalCoreLon = 0;
        radius = 0;
        for (int i = 0; i < 4; i++) {
            quadrantCenterLat[i] = 0;
            quadrantCenterLon[i] = 0;
            quadrantLift[i] = 0;
        }
        avgLift = 0;
        maxLift = 0;
        energy = 0;
        pointCount = 0;
        durationMs = 0;
        startTimeMs = 0;
    }

    /** Индекс лучшего квадранта (максимальный подъём) */
    public int bestQuadrantIndex() {
        int best = 0;
        for (int i = 1; i < 4; i++) {
            if (quadrantLift[i] > quadrantLift[best]) best = i;
        }
        return best;
    }

    /** Индекс худшего квадранта (минимальный подъём) */
    public int worstQuadrantIndex() {
        int worst = 0;
        for (int i = 1; i < 4; i++) {
            if (quadrantLift[i] < quadrantLift[worst]) worst = i;
        }
        return worst;
    }
}
