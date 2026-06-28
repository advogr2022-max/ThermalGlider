package com.thermalglider.data;

/**
 * LandingField — безопасная посадочная площадка.
 *
 * Раздел 2.6 ТЗ.
 */
public class LandingField {
    public String name;
    public double centerLat, centerLon;
    public float radiusM;          // радиус площадки (м)
    public int type;               // 0=поле, 1=аэродром, 2=посадочная зона
    public boolean hasObstacles;   // есть препятствия?

    // Вычисляемые поля
    public float distanceKm;       // расстояние до площадки
    public float bearingDeg;       // направление
    public float heightAboveM;     // превышение над площадкой (м)
    public boolean isReachable;    // достижима?

    public LandingField() {}

    public LandingField(String name, double lat, double lon, float radiusM, int type, boolean hasObstacles) {
        this.name = name;
        this.centerLat = lat;
        this.centerLon = lon;
        this.radiusM = radiusM;
        this.type = type;
        this.hasObstacles = hasObstacles;
    }

    public static int colorByType(int type) {
        switch (type) {
            case 0: return 0xFF4CAF50;  // поле — зелёный
            case 1: return 0xFF2196F3;  // аэродром — синий
            case 2: return 0xFFFF9800;  // посадочная зона — оранжевый
            default: return 0xFFFFFFFF;
        }
    }
}
