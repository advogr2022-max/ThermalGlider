package com.thermalglider.sensors;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * SensorController — управление всеми датчиками.
 * Барометр, акселерометр, гироскоп, магнитометр.
 *
 * 50 Гц для accel/gyro/mag, baro по событию.
 *
 * Раздел 22 ТЗ.
 */
public class SensorController implements SensorEventListener {

    private SensorManager sensorManager;
    private boolean hasBarometer = false;
    private boolean hasAccel = false;
    private boolean hasGyro = false;
    private boolean hasMag = false;
    private boolean isRegistered = false;

    // Текущие показания
    public float pressure = 0;          // hPa (барометр)
    public float temperature = 0;       // °C
    public float ax = 0, ay = 0, az = 0;// accel (m/s², with gravity)
    public float gx = 0, gy = 0, gz = 0;// gyro (rad/s)
    public float mx = 0, my = 0, mz = 0;// mag (µT)

    // Ориентация
    public float pitch = 0, roll = 0;
    public float heading = 0;           // из rotation vector

    // Callback для SensorLogger (50 Гц)
    public interface SensorSampleCallback {
        void onSample(long timestampMs);
    }
    private SensorSampleCallback sampleCallback;

    public SensorController() {}

    public void start(Context context) {
        sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager == null) return;

        Sensor barometer = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE);
        Sensor accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        Sensor gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        Sensor mag = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        Sensor rotVec = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);

        hasBarometer = barometer != null;
        hasAccel = accel != null;
        hasGyro = gyro != null;
        hasMag = mag != null;

        // Регистрируем
        if (hasBarometer) {
            sensorManager.registerListener(this, barometer, SensorManager.SENSOR_DELAY_NORMAL);
        }
        if (hasAccel) {
            sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_FASTEST); // 50 Гц
        }
        if (hasGyro) {
            sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_FASTEST);
        }
        if (hasMag) {
            sensorManager.registerListener(this, mag, SensorManager.SENSOR_DELAY_FASTEST);
        }
        if (rotVec != null) {
            sensorManager.registerListener(this, rotVec, SensorManager.SENSOR_DELAY_GAME);
        }

        isRegistered = true;
    }

    public void stop() {
        if (sensorManager != null && isRegistered) {
            sensorManager.unregisterListener(this);
        }
        isRegistered = false;
    }

    public void setSampleCallback(SensorSampleCallback cb) {
        this.sampleCallback = cb;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        long now = System.currentTimeMillis();

        switch (event.sensor.getType()) {
            case Sensor.TYPE_PRESSURE:
                pressure = event.values[0];
                temperature = event.values.length > 1 ? event.values[1] : 0;
                break;

            case Sensor.TYPE_ACCELEROMETER:
                ax = event.values[0];
                ay = event.values[1];
                az = event.values[2];
                break;

            case Sensor.TYPE_GYROSCOPE:
                gx = event.values[0];
                gy = event.values[1];
                gz = event.values[2];
                break;

            case Sensor.TYPE_MAGNETIC_FIELD:
                mx = event.values[0];
                my = event.values[1];
                mz = event.values[2];
                break;

            case Sensor.TYPE_ROTATION_VECTOR:
                float[] rotMatrix = new float[9];
                float[] orientation = new float[3];
                SensorManager.getRotationMatrixFromVector(rotMatrix, event.values);
                SensorManager.getOrientation(rotMatrix, orientation);
                heading = (float) Math.toDegrees(orientation[0]);
                if (heading < 0) heading += 360;
                pitch = (float) Math.toDegrees(orientation[1]);
                roll = (float) Math.toDegrees(orientation[2]);
                break;
        }

        // Callback для логгера (на accel/gyro/mag сэмплах)
        if (sampleCallback != null &&
            (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER ||
             event.sensor.getType() == Sensor.TYPE_GYROSCOPE ||
             event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD)) {
            sampleCallback.onSample(now);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    public boolean hasBarometer() { return hasBarometer; }
    public boolean isRegistered() { return isRegistered; }
}
