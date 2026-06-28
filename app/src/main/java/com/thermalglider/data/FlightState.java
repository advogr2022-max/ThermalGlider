package com.thermalglider.data;

import java.util.List;

/**
 * FlightState — полное состояние полёта.
 * Заполняется в BackgroundService.tick() каждые 100ms.
 * Читается в MapView.onDraw() — только для рендеринга.
 *
 * Раздел 2.3 ТЗ.
 */
public class FlightState {

    // === Позиция ===
    /** Текущая широта (градусы) */
    public double latitude;
    /** Текущая долгота (градусы) */
    public double longitude;
    /** Высота MSL от GPS (м) */
    public float gpsAltitude;
    /** Точность GPS (м), <100 = valid */
    public float gpsAccuracy;
    /** Курс от GPS (° от N) */
    public float bearing;
    /** Скорость путевая (м/с) */
    public float speed;

    // === Высота ===
    /** Высота по барометру, QNH скорректированная (м) */
    public float baroAltitude;
    /** Высота над землёй AGL (м) */
    public float altitudeAGL;
    /** Высота старта (м) — первая точка в воздухе */
    public float launchAltitude;

    // === Vario ===
    /** Сырое варьо (м/с) */
    public float varioRaw;
    /** Отфильтрованное варьо (м/с) — отображается */
    public float varioFiltered;
    /** Энергетически компенсированное варьо (м/с) */
    public float varioEnergy;
    /** Среднее варьо за 30с (м/с) */
    public float avgVario30;

    // === Движение ===
    /** true = в спирали */
    public boolean isCircling;
    /** Угловая скорость поворота (°/с) */
    public float turnRate;
    /** Количество точек в текущей спирали */
    public int circlingPointCount;

    // === Термик ===
    /** Текущий термический сектор (если в спирали) */
    public ThermalSector thermalSector;
    /** Ближайший термик из истории */
    public NearThermal nearestThermal;

    // === Ветер ===
    /** Скорость ветра (м/с) */
    public float windSpeed;
    /** Направление ветра — откуда дует (°) */
    public float windDirection;
    /** Уверенность 0-100% */
    public int windConfidence;

    // === L/D ===
    /** Аэродинамическое качество */
    public float glideRatio;
    /** Скорость снижения (м/с) */
    public float sinkRate;
    /** Дальность планирования (км) */
    public float glideRangeKm;
    /** Достижимые площадки */
    public List<LandingField> reachableFields;

    // === Эллипс долёта ===
    /** Полигон эллипса долёта (координаты) */
    public List<double[]> glideEllipse;

    // === Сессия ===
    /** Время старта полёта (epoch ms) */
    public long flightStartTimeMs;
    /** Текущее время полёта (сек) */
    public long flightDurationSec;
    /** Максимальная высота (м) */
    public float maxAltitude;
    /** Пройдено за полёт (км) */
    public float totalDistanceKm;
    /** Суммарный набор (м) */
    public float totalClimb;
    /** В воздухе? */
    public boolean isFlying;
    /** Запись IGC идёт? */
    public boolean isRecording;
    /** Выбранная площадка (касанием на карте) */
    public LandingField selectedField;
    /** Режим реплея */
    public boolean isReplayMode;
    /** Скорость реплея */
    public float replaySpeed;

    // === Карта ===
    /** Целевой масштаб от AutoZoom (км/пиксель) */
    public float targetKmPerPx;
    /** Текущий масштаб карты (км/пиксель) */
    public float currentKmPerPx;
    /** В спутниковом режиме? */
    public boolean satelliteMode;
    /** Режим карты: 0=track-up, 1=north-up, 2=auto-center */
    public int mapMode;

    // === Спутники ===
    public int satelliteCount;
    public boolean hasGpsFix;

    // === Батарея ===
    public int batteryPercent;
    public boolean batteryCharging;

    // === Таймстамп ===
    /** Когда был последний раз обновлён (epoch ms) */
    public long lastUpdateMs;

    // === Статусы ===
    public boolean barometerAvailable;
    public boolean barometerCalibrated;
    public boolean sensorLoggingEnabled;

    public FlightState() {
        reset();
    }

    /** Сброс в начальное состояние */
    public void reset() {
        latitude = 0;
        longitude = 0;
        gpsAltitude = 0;
        gpsAccuracy = 999;
        bearing = 0;
        speed = 0;
        baroAltitude = 0;
        altitudeAGL = 0;
        launchAltitude = 0;
        varioRaw = 0;
        varioFiltered = 0;
        varioEnergy = 0;
        avgVario30 = 0;
        isCircling = false;
        turnRate = 0;
        circlingPointCount = 0;
        thermalSector = null;
        nearestThermal = null;
        windSpeed = 0;
        windDirection = 0;
        windConfidence = 0;
        glideRatio = 0;
        sinkRate = 0;
        glideRangeKm = 0;
        reachableFields = null;
        glideEllipse = null;
        flightStartTimeMs = 0;
        flightDurationSec = 0;
        maxAltitude = 0;
        totalDistanceKm = 0;
        totalClimb = 0;
        isFlying = false;
        isRecording = false;
        selectedField = null;
        isReplayMode = false;
        replaySpeed = 1.0f;
        targetKmPerPx = 0.02f;
        currentKmPerPx = 0.02f;
        satelliteMode = false;
        mapMode = 0;
        satelliteCount = 0;
        hasGpsFix = false;
        batteryPercent = 100;
        batteryCharging = false;
        lastUpdateMs = 0;
        barometerAvailable = false;
        barometerCalibrated = false;
        sensorLoggingEnabled = false;
    }

    /** Обновление long-only полей сессии */
    public void updateSession(long nowMs) {
        if (isFlying && flightStartTimeMs > 0) {
            flightDurationSec = (nowMs - flightStartTimeMs) / 1000;
        }
        if (baroAltitude > maxAltitude) {
            maxAltitude = baroAltitude;
        }
        lastUpdateMs = nowMs;
    }
}
