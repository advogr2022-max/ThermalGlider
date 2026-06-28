package com.thermalglider;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.thermalglider.data.FlightState;
import com.thermalglider.data.LandingFieldDB;
import com.thermalglider.engine.FlightManager;
import com.thermalglider.gps.GpsManager;
import com.thermalglider.igc.IgcLogger;
import com.thermalglider.power.GroundProximityAlert;
import com.thermalglider.sensors.SensorController;
import com.thermalglider.sensors.SensorLogger;
import com.thermalglider.ui.VarioSoundGenerator;
import com.thermalglider.vario.VarioEngine;

/**
 * BackgroundService — foreground service с 100ms тиком.
 * Интеграция: GpsManager + SensorController + VarioEngine + WindStore.
 */
public class BackgroundService extends Service {

    private static final String TAG = "ThermalGlider";
    private static final String CHANNEL_ID = "thermalglider_flight";
    private static final int NOTIFICATION_ID = 1;
    private static final long TICK_INTERVAL_MS = 100;

    private HandlerThread handlerThread;
    private Handler tickHandler;
    private volatile boolean running = false;

    // Компоненты
    private final FlightState flightState = AppState.getInstance().flightState;
    private final FlightManager flightManager = new FlightManager();
    private GpsManager gpsManager;
    private SensorController sensorController;
    private VarioEngine varioEngine;
    private SensorLogger sensorLogger;
    private LandingFieldDB fieldDB;
    private VarioSoundGenerator soundGenerator;
    private GroundProximityAlert groundAlert;
    private IgcLogger igcLogger;

    // QNH
    private float qnh = 1013.25f;
    private float fieldElevation = 0;

    // Трекинг высоты
    private float lastBaroAlt = 0;
    private long lastGpsTime = 0;
    private double prevLat, prevLon;
    private float launchAltitude = 0;
    private boolean hasLaunchAlt = false;

    private String basePath;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            createNotificationChannel();
            startForeground(NOTIFICATION_ID, buildNotification("Initializing..."));

            basePath = getExternalFilesDir(null) != null
                ? getExternalFilesDir(null).getAbsolutePath() + "/ThermalGlider"
                : Environment.getExternalStorageDirectory().getAbsolutePath() + "/ThermalGlider";

            // Инициализация компонентов
            sensorController = new SensorController();
            sensorController.start(this);

            gpsManager = new GpsManager(flightState);
            gpsManager.start(this);

            varioEngine = new VarioEngine();
            sensorLogger = new SensorLogger();

            // Инициализация БД площадок
            fieldDB = new LandingFieldDB();
            fieldDB.init(basePath);
            flightManager.setFieldDB(fieldDB);

            // Звук + земля
            soundGenerator = new VarioSoundGenerator();
            soundGenerator.start();
            groundAlert = new GroundProximityAlert(this);
            groundAlert.setSoundCallback((freq, vol) -> {
                if (soundGenerator != null) soundGenerator.update(-5.0f, System.currentTimeMillis());
            });

            // IGC логгер
            igcLogger = new IgcLogger();
            igcLogger.setBasePath(basePath);

            handlerThread = new HandlerThread("bg-tick");
            handlerThread.start();
            tickHandler = new Handler(handlerThread.getLooper());
            running = true;
            tickHandler.post(this::tick);

            Log.d(TAG, "BackgroundService started, tick=100ms");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start BackgroundService", e);
        }
    }

    private void tick() {
        if (!running) return;

        try {
            long nowMs = System.currentTimeMillis();

            // 1. GPS: speed из дистанции/время (вычисленный)
            if (flightState.hasGpsFix) {
                if (prevLat != 0 && prevLon != 0 && lastGpsTime > 0) {
                    double dt = (nowMs - lastGpsTime) / 1000.0;
                    if (dt > 0 && dt < 5) {
                        double distM = com.thermalglider.util.GeoUtils.haversineM(
                            prevLat, prevLon, flightState.latitude, flightState.longitude);
                        float calcSpeed = (float) (distM / dt);
                        if (flightState.speed == 0 && calcSpeed > 0) {
                            flightState.speed = calcSpeed;
                        }
                    }
                }
                prevLat = flightState.latitude;
                prevLon = flightState.longitude;
                lastGpsTime = nowMs;
            }

            // 2. Высота: baro или GPS
            float baroAlt = 0;
            if (sensorController.hasBarometer() && sensorController.pressure > 0) {
                baroAlt = pressureToAlt(sensorController.pressure, qnh);
                flightState.barometerAvailable = true;
                flightState.barometerCalibrated = (qnh > 0);
            }
            float gpsAlt = flightState.gpsAltitude;

            // Авто-QNH при старте
            if (!flightState.barometerCalibrated && baroAlt > 0 && flightState.hasGpsFix
                && !flightState.isFlying && flightState.speed < 2) {
                if (Math.abs(baroAlt - gpsAlt) < 50) {
                    qnh = calibrateQnh(sensorController.pressure, fieldElevation);
                    flightState.barometerCalibrated = true;
                    Log.d(TAG, "QNH calibrated: " + qnh);
                }
            }

            // 3. Vario
            if (flightState.hasGpsFix || baroAlt > 0) {
                varioEngine.calculate(baroAlt, gpsAlt, flightState.speed,
                    sensorController.pressure, qnh, sensorController,
                    nowMs, flightState);
                flightState.varioFiltered = varioEngine.getDisplayVario();
                flightState.varioRaw = varioEngine.getVarioFiltered();
            }

            // 4. Высота AGL
            float displayAlt = baroAlt > 0 ? baroAlt : gpsAlt;
            flightState.baroAltitude = baroAlt;
            flightState.altitudeAGL = Math.max(0, displayAlt - fieldElevation);

            // Launch altitude
            if (displayAlt > 0 && !hasLaunchAlt && flightState.speed > 3) {
                launchAltitude = displayAlt;
                hasLaunchAlt = true;
                flightState.launchAltitude = launchAltitude;
            }

            // 5. Сессия полёта
            if (flightState.speed > 3 && flightState.altitudeAGL > 50) {
                if (!flightState.isFlying) {
                    flightState.flightStartTimeMs = nowMs;
                    flightState.isFlying = true;
                    // Старт IGC
                    if (igcLogger != null && !igcLogger.isRecording()) {
                        igcLogger.startRecording(nowMs);
                        flightState.isRecording = true;
                    }
                    Log.d(TAG, "Flight started");
                }
            } else if (flightState.isFlying && flightState.speed < 1 && flightState.altitudeAGL < 20) {
                if (nowMs - flightState.flightStartTimeMs > 60000) {
                    flightState.isFlying = false;
                    // Стоп IGC
                    if (igcLogger != null && igcLogger.isRecording()) {
                        igcLogger.stopRecording();
                        flightState.isRecording = false;
                    }
                    Log.d(TAG, "Flight ended");
                }
            }

            // 5b. IGC запись точек
            if (igcLogger != null && igcLogger.isRecording() && flightState.hasGpsFix) {
                igcLogger.addFix(flightState.latitude, flightState.longitude,
                    flightState.baroAltitude, flightState.gpsAltitude,
                    flightState.speed, flightState.bearing, nowMs);
            }

            // 6. Дистанция
            if (flightState.isFlying && prevLat != 0 && prevLon != 0) {
                double segKm = com.thermalglider.util.GeoUtils.haversineKm(
                    prevLat, prevLon, flightState.latitude, flightState.longitude);
                flightState.totalDistanceKm += (float) segKm;
            }

            // 7. FlightManager — термики, ветер, L/D, эллипс, автомасштаб
            int viewW = 1080; // TODO: получать из MapView
            int viewH = 1920;
            flightManager.tick(flightState, nowMs, viewW, viewH);

            // 8. Обновление времени + батарея
            flightState.updateSession(nowMs);
            flightState.batteryPercent = getBatteryLevel();

            // 9. Звук варио
            if (soundGenerator != null) {
                soundGenerator.update(flightState.varioFiltered, nowMs);
            }

            // 10. Ground proximity alert
            if (groundAlert != null) {
                groundAlert.evaluate(flightState.altitudeAGL, flightState.varioFiltered, nowMs);
            }

            // 11. Broadcast to MainActivity
            Intent intent = new Intent(MainActivity.ACTION_FLIGHT_UPDATE);
            sendBroadcast(intent);

        } catch (Exception e) {
            Log.e(TAG, "Tick error", e);
        }

        tickHandler.postDelayed(this::tick, TICK_INTERVAL_MS);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        updateNotification("Running");
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (handlerThread != null) handlerThread.quitSafely();
        if (gpsManager != null) gpsManager.stop();
        if (sensorController != null) sensorController.stop();
        if (sensorLogger != null) sensorLogger.stop();
        if (soundGenerator != null) soundGenerator.release();
        if (igcLogger != null && igcLogger.isRecording()) igcLogger.stopRecording();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // === Baro utilities ===

    /** ISA: давление → высота */
    private float pressureToAlt(float pressure, float qnh) {
        if (pressure <= 0 || qnh <= 0) return 0;
        return 44330.0f * (1.0f - (float) Math.pow(pressure / qnh, 0.1903f));
    }

    /** Калибровка QNH по известной высоте поля */
    private float calibrateQnh(float pressure, float fieldElevM) {
        if (pressure <= 0) return 1013.25f;
        return (float) (pressure / Math.pow(1.0 - fieldElevM / 44330.0, 5.255));
    }

    // === Battery ===
    private int getBatteryLevel() {
        try {
            android.content.IntentFilter ifilter = new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            android.content.Intent batteryStatus = registerReceiver(null, ifilter);
            if (batteryStatus != null) {
                int level = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
                if (level >= 0 && scale > 0) {
                    return (int) (level * 100.0f / scale);
                }
            }
        } catch (Exception ignored) {}
        return 50;
    }

    // === Notification ===

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "ThermalGlider Flight", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Flight service notification");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ThermalGlider")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }
}
