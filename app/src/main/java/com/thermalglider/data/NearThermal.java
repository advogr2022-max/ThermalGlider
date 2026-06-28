package com.thermalglider.data;

/**
 * NearThermal — ближайший сохранённый термик.
 */
public class NearThermal {
    public double lat, lon;
    public float lift;          // средний подъём (м/с)
    public float distanceM;     // дистанция (м)
    public float bearingDeg;    // направление (°)
    public String dirText;      // "NNE", "SSW" и т.д.
    public long timeMs;         // время обнаружения
}
