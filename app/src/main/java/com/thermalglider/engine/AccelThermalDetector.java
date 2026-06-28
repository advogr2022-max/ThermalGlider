package com.thermalglider.engine;

/**
 * AccelThermalDetector — ЗАГЛУШКА.
 *
 * Блипы (микрораскачка) будут добавлены в последнюю очередь
 * на основе Tradar ThermalDetector.
 *
 * Чекбокс enable_micro_detection заблокирован — всегда false.
 */
public class AccelThermalDetector {

    private boolean enabled = false;

    /** Заглушка: всегда возвращает false */
    public boolean isEnabled() { return false; }

    /** Ничего не делает */
    public void processSample(float axWorldG, float ayWorldG, long timestampMs) {}

    /** Ничего не возвращает */
    public Object getCurrentBlip() { return null; }

    public void reset() {}
}
