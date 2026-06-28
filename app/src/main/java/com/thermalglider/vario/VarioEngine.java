package com.thermalglider.vario;

import com.thermalglider.data.FlightState;
import com.thermalglider.sensors.SensorController;

/**
 * VarioEngine — расчёт вертикальной скорости.
 * Три источника: барометр, GPS, энергетически компенсированный.
 * Фильтры: Deadband + Kalman + Median.
 *
 * Раздел 5 ТЗ.
 */
public class VarioEngine {

    // Константы
    private static final float MIN_SPEED_FOR_VALID = 2.0f;  // м/с
    private static final float DEADBAND_THRESHOLD = 0.05f;   // м/с
    private static final float TE_COMP_ALPHA = 0.3f;
    private static final float G = 9.81f;

    // Фильтры
    private final KalmanVarioFilter kalman = new KalmanVarioFilter();
    private final MedianSmoother median = new MedianSmoother(3000);

    // Состояние для TE-компенсации
    private float lastSpeed = 0;
    private float lastBaroAlt = 0;
    private float lastGpsAlt = 0;
    private long lastTime = 0;

    // Выходные значения
    private float varioRaw;
    private float varioFiltered;
    private float varioEnergy;

    // Альфа-фильтры для разных каналов
    private float soundVario;   // α=0.6
    private float displayVario; // α=0.1
    private float avgVario30;   // α=0.05

    // 30s avg vario buffer
    private final float[] varioBuf = new float[30];
    private int varioIdx = 0;
    private int varioCount = 0;

    public VarioEngine() {}

    /**
     * Основной расчёт.
     * Вызывается из FlightManager.tick() каждые 100ms.
     */
    public void calculate(float baroAlt, float gpsAlt, float speed,
                          float pressure, float qnh, SensorController sensors,
                          long nowMs, FlightState state) {
        float dt = (nowMs - lastTime) / 1000.0f;
        if (dt <= 0 || dt > 2.0f) {
            // Сброс при перерыве >2с
            lastBaroAlt = baroAlt > 0 ? baroAlt : gpsAlt;
            lastGpsAlt = gpsAlt;
            lastTime = nowMs;
            lastSpeed = speed;
            return;
        }

        // 1. Сырое варьо (барометр)
        float rawBaro = 0;
        if (baroAlt > 0 && lastBaroAlt > 0) {
            rawBaro = (baroAlt - lastBaroAlt) / dt;
        }

        // 2. Сырое варьо (GPS)
        float rawGps = 0;
        if (gpsAlt > 0 && lastGpsAlt > 0) {
            rawGps = (gpsAlt - lastGpsAlt) / dt;
        }

        // 3. Выбор источника: барометр приоритет
        if (baroAlt > 0 && state.barometerAvailable && state.barometerCalibrated) {
            varioRaw = rawBaro;
        } else {
            varioRaw = rawGps;
        }

        // 4. Анти-алиасинг (на земле не считаем)
        if (speed < MIN_SPEED_FOR_VALID) {
            varioRaw = 0;
        }

        // 5. Энергетическая компенсация
        varioEnergy = energyCompensate(varioRaw, speed, lastSpeed, dt);

        // 6. Deadband
        if (Math.abs(varioEnergy) < DEADBAND_THRESHOLD) {
            varioFiltered = 0;
            varioEnergy = 0;
        } else {
            // 7. Калман
            varioFiltered = kalman.update(varioEnergy, dt);
        }

        // 8. Медианный фильтр
        varioFiltered = median.update(varioFiltered, nowMs);

        // 9. Три канала варио
        soundVario = alphaFilter(varioFiltered, soundVario, 0.6f);
        displayVario = alphaFilter(varioFiltered, displayVario, 0.1f);
        avgVario30 = alphaFilter(varioFiltered, avgVario30, 0.05f);

        // 10. 30s avg via buffer
        varioBuf[varioIdx] = varioFiltered;
        varioIdx = (varioIdx + 1) % 30;
        if (varioCount < 30) varioCount++;

        // Запись в state
        state.varioRaw = varioRaw;
        state.varioFiltered = displayVario;
        state.varioEnergy = varioEnergy;

        // Обновление last
        lastBaroAlt = baroAlt > 0 ? baroAlt : lastBaroAlt;
        lastGpsAlt = gpsAlt > 0 ? gpsAlt : lastGpsAlt;
        lastSpeed = speed;
        lastTime = nowMs;
    }

    /** Энергетическая компенсация: dE = (v2² - v1²) / (2*g) */
    private float energyCompensate(float rawVario, float speed, float lastSpeed, float dt) {
        if (dt <= 0) return rawVario;
        float dKe = (speed * speed - lastSpeed * lastSpeed) / (2 * G);
        return rawVario + dKe / dt;
    }

    /** Alpha-фильтр */
    private float alphaFilter(float newVal, float prev, float alpha) {
        return alpha * newVal + (1 - alpha) * prev;
    }

    /** Среднее за 30с */
    public float getAvgVario30() {
        if (varioCount == 0) return 0;
        float sum = 0;
        int n = Math.min(varioCount, 30);
        for (int i = 0; i < n; i++) {
            sum += varioBuf[(varioIdx - i - 1 + 30) % 30];
        }
        return sum / n;
    }

    /** Среднее звукового варио за 30с */
    public float getAvgVario30Sound() {
        return avgVario30;
    }

    public float getSoundVario() { return soundVario; }
    public float getDisplayVario() { return displayVario; }
    public float getVarioFiltered() { return varioFiltered; }

    public void reset() {
        kalman.reset();
        median.reset();
        varioRaw = 0;
        varioFiltered = 0;
        varioEnergy = 0;
        soundVario = 0;
        displayVario = 0;
        avgVario30 = 0;
        lastBaroAlt = 0;
        lastGpsAlt = 0;
        lastTime = 0;
        lastSpeed = 0;
        varioIdx = 0;
        varioCount = 0;
    }
}
