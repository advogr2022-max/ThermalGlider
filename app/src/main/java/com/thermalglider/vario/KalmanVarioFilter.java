package com.thermalglider.vario;

/**
 * KalmanVarioFilter — 3-го порядка.
 * Состояние: [position, velocity, acceleration].
 *
 * Раздел 6.3 ТЗ.
 */
public class KalmanVarioFilter {

    private final float processNoise;
    private final float measNoiseFactor;

    // Ковариация 3x3 (flat array)
    private final float[] P = new float[9]; // row-major

    // Состояние
    private float xPos;  // высота
    private float xVel;  // варьо (м/с)
    private float xAcc;  // ускорение (м/с²)

    private boolean initialized = false;

    public KalmanVarioFilter(float processNoise, float measNoiseFactor) {
        this.processNoise = processNoise;
        this.measNoiseFactor = measNoiseFactor;
    }

    public KalmanVarioFilter() {
        this(0.1f, 1.5f);
    }

    /** Обновление фильтра */
    public float update(float varioMeas, float dtSec) {
        if (!initialized || dtSec <= 0) {
            xVel = varioMeas;
            initialized = true;
            return varioMeas;
        }

        float dt = dtSec;
        float dt2 = dt * dt / 2.0f;

        // === PREDICT ===
        xPos += xVel * dt + xAcc * dt2;
        xVel += xAcc * dt;
        // xAcc остаётся (инерциальная модель)

        // Прогноз ковариации
        float Q = processNoise;
        float P11 = P[0] + dt * P[3] + dt2 * P[6] +
                    P[1] * dt + dt * dt * P[4] + dt * dt2 * P[7] +
                    P[2] * dt2 + dt * dt2 * P[5] + dt2 * dt2 * P[8] + Q * dt * dt;
        float P12 = P[1] + dt * P[4] + dt2 * P[7] +
                    P[2] * dt + dt * dt * P[5] + dt2 * dt * P[8];
        float P13 = P[2] + dt * P[5] + dt2 * P[8];
        float P22 = P[4] + 2 * dt * P[7] + dt * dt * P[8] + Q;
        float P23 = P[7] + dt * P[8];
        float P33 = P[8] + Q;

        P[0] = P11; P[1] = P12; P[2] = P13;
        P[3] = P12; P[4] = P22; P[5] = P23;
        P[6] = P13; P[7] = P23; P[8] = P33;

        // === UPDATE ===
        // Адаптивный шум: чем сильнее сигнал, тем больше шум
        float R = Math.abs(varioMeas) * measNoiseFactor + 0.1f;
        float R2 = R * R;

        // Инновация по скорости
        float S = P[4] + R2;
        float K_vel = (S > 0) ? P[4] / S : 0;

        float innovation = varioMeas - xVel;

        xVel += K_vel * innovation;
        xAcc += 0.1f * K_vel * innovation / Math.max(dt, 0.1f);

        // Обновление ковариации
        P[4] = (1 - K_vel) * P[4];
        P[7] = (1 - K_vel) * P[7];
        P[5] = P[7];

        return xVel;
    }

    public void reset() {
        xPos = 0;
        xVel = 0;
        xAcc = 0;
        for (int i = 0; i < 9; i++) P[i] = 0;
        initialized = false;
    }

    public float getVelocity() { return xVel; }
    public boolean isInitialized() { return initialized; }
}
