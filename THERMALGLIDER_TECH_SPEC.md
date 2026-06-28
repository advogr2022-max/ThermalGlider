# ThermalGlider — Техническое задание

## Концепция

Упрощённый парапланерный прибор для **пилота выходного дня**:
- Не летает соревнования, не гоняется за OLC
- Задача: **максимально долго держаться в воздухе**, летать как можно дальше и безопасно садиться
- Плохо крутит термики — нужен **максимально точный термический ассистент**
- Нужна база **безопасных посадочных полей** с эллипсом долета по ветру
- Минимум экранов, всё в одном view

---

## Содержание
1. [Архитектура](#1-архитектура)
2. [Модель данных](#2-модель-данных)
3. [GPS-движок](#3-gps-движок)
4. [Барометрическая высота](#4-барометрическая-высота)
5. [Вертикальная скорость (Vario)](#5-vario)
6. [Фильтры варьо](#6-фильтры)
7. [Термический ассистент](#7-термический-ассистент)
8. [Ветер](#8-ветер)
9. [Аэродинамическое качество L/D](#9-ld)
10. [Эллипс долета](#10-эллипс-долета)
11. [База безопасных площадок](#11-безопасные-площадки)
12. [IGC-логгер](#12-igc)
13. [Проигрывание IGC (реплей)](#13-реплей)
14. [Картографический движок](#14-карты)
15. [Загрузка карт](#15-загрузка-карт)
16. [Основной экран](#16-основной-экран)
17. [InfoBox-панели (22 виджета)](#17-infobox)
18. [Термик-отрисовка на карте](#18-термик-отрисовка)
19. [Airspace](#19-airspace)
20. [Система настроек](#20-настройки)
21. [Звук](#21-звук)
22. [Внешние датчики](#22-внешние-датчики)
23. [Хранение данных](#23-хранение)

---

## 1. Архитектура

### 1.1 Общая схема

```
┌──────────────────────────────────────────────────────┐
│  MainActivity (один экран)                             │
│  ┌──────────────┐  ┌────────────────────────────────┐ │
│  │ Боковая      │  │  Карта (основная область)       │ │
│  │ панель       │  │  ┌─────────┐  ┌──────────────┐ │ │
│  │ InfoBox[1..N] │  │  │ VMP    │  │ ThermalHelper│ │ │
│  │ + кнопки     │  │  │ карта  │  │ 3D circles   │ │ │
│  └──────────────┘  │  │ трек   │  │ helpers      │ │ │
│                     │  │ площ-ки│  └──────────────┘ │ │
│                     │  └─────────┘                    │ │
│                     └────────────────────────────────┘ │
├──────────────────────────────────────────────────────┤
│  BackgroundService (foreground, 100ms тик)            │
│  ┌────────────┐ ┌──────┐ ┌───────┐ ┌───────────────┐│
│  │ GPS Manager│ │Vario │ │Thermal│ │ IGC Logger    ││
│  │ (Location)│ │Engine│ │Detect │ │               ││
│  └────────────┘ └──────┘ └───────┘ └───────────────┘│
└──────────────────────────────────────────────────────┘
```

### 1.2 Жизненный цикл

```
App.onCreate()
  └→ SharedPreferences.init()
     └→ Base path: sdcard/thermalglider/

MainActivity.onCreate()
  └→ requestPermissions([LOCATION, STORAGE])
     └→ copyDefaultFiles()
        └→ startService(BackgroundService)

BackgroundService.onStartCommand()
  └→ create Handler loop (100ms)
     └→ каждые 100ms: tick()

tick():
  1. GPS fix (from LocationManager или последний кэш)
  2. Если новый fix → FlightManager.onGpsFix(lat, lon, alt, speed, bearing, time, accuracy)
  3. FlightManager.calculate():
     a. Vario (барометр/GPS производная + фильтры)
     b. Определение спирали (thermal detect)
     c. Ветер
     d. L/D
     e. Эллипс долета
     f. ICG log (если скорость > порога движения)
  4. sendBroadcast(FLIGHT_UPDATE) → MainActivity.onResume()
  5. invalidate() → View перерисовка
  6. schedule next tick in 100ms

MainActivity.onPause()
  └→ pause view rendering (но сервис продолжает)
```

### 1.3 Главные модули

| Модуль | Описание |
|---|---|
| `FlightManager` | Главный контроллер всех расчётов |
| `GpsManager` | Android LocationManager + фильтрация |
| `VarioEngine` | Vario расчёт (барометр/GPS + фильтры) |
| `ThermalDetector` | Обнаружение и анализ термических спиралей |
| `WindEstimator` | Вычисление ветра |
| `GlideComputer` | L/D, эллипс долета, до ближайшей площадки |
| `LandingFieldDB` | База безопасных площадок |
| `IgcLogger` | Запись IGC-файлов |
| `IgcReplay` | Проигрывание IGC (отладка) |
| `SoundEngine` | Тональный звук варьо |
| `MapEngine` | VMP карты (векторные тайлы) |
| `ThermalPainter` | Визуализация термиков на карте |

---

## 2. Модель данных

### 2.1 GpsFix
```
GpsFix {
    double lat;         // широта (градусы)
    double lon;         // долгота (градусы)
    float  altitude;    // высота MSL (м) — от GPS
    float  speed;       // скорость (м/с) — от GPS
    float  bearing;     // курс (градусы от N)
    long   time;        // timestamp (epoch ms)
    float  accuracy;    // точность (м), <100 = valid
    int    satellites;  // кол-во спутников
}
```

### 2.2 BarometricSample
```
BarometricSample {
    float pressure;     // давление (hPa)
    float temperature;  // температура (°C)
    long   time;        // timestamp (ms)
}
```

### 2.3 FlightState (главное состояние)
```
FlightState {
    // Позиция
    GpsFix position;            // текущая позиция
    
    // Высота
    float baroAltitude;         // высота по барометру (QNH скорр.)
    float gpsAltitude;          // высота MSL от GPS
    float altitudeAGL;          // высота над землёй (м)
    
    // Vario
    float varioRaw;             // сырое варьо (м/с)
    float varioFiltered;        // отфильтрованное варьо (м/с)
    float varioEnergy;          // энергетически компенсированное
    
    // Движение
    float speed;                // скорость (м/с)
    float bearing;              // курс (°)
    boolean isCircling;         // в спирали?
    float circlingAngle;        // угол поворота за сек (°/с)
    float circlingCenterLat;    // центр спирали
    float circlingCenterLon;
    
    // Ветер
    float windSpeed;            // м/с
    float windDirection;        // откуда дует (°)
    int windConfidence;         // 0-100% уверенность
    
    // L/D
    float glideRatio;           // L/D
    float sinkRate;             // снижение (м/с)
    
    // Эллипс долета
    List<GeoPoint> glideEllipse; // полигон достижимой зоны
    
    // Безопасные площадки
    List<LandingField> reachableFields;  // достижимые поля
    
    // Сессия
    long   flightStartTime;     // время старта
    float  maxAltitude;         // макс высота (м)
    float  totalDistance;       // пройдено (км)
    float  totalClimb;          // суммарный набор (м)
    long   flightDuration;      // длительность (сек)
    boolean isFlying;           // в воздухе?
    int    igcRecordCount;      // записано точек в IGC
}
```

### 2.4 ThermalSector
```
ThermalSector {
    // Геометрия
    Coord center;           // геометрический центр спирали
    Coord thermalCore;      // предполагаемый центр потока
    float radius;           // радиус термической зоны (м)
    
    // 4 квадранта (N, E, S, W)
    Coord[] quadrantCenter; // центр каждого квадранта
    float[] quadrantLift;   // подъём в каждом квадранте (м/с)
    
    // Статистика
    float avgLift;          // средний подъём (м/с)
    float maxLift;          // максимальный встреченный подъём (м/с)
    float energy;           // энергия потока
    int    pointCount;      // кол-во точек в спирали
    long   duration;        // длительность спирали (мс)
    float[] trailLat;       // история трека в спирали
    float[] trailLon;
}
```

### 2.5 Coord / GeoPoint
```
Coord {
    double lat;
    double lon;
}

GeoPoint {
    double lat;
    double lon;
}
```

### 2.6 LandingField (безопасная площадка)
```
LandingField {
    String name;            // название
    Coord  center;          // координата центра
    float  radius;          // радиус площадки (м)
    int    type;            // 0=поле, 1=аэродром, 2=посад.зона
    boolean isObstacles;    // есть препятствия?
    // Вычисляемые поля
    float  distanceKm;      // расстояние до площадки
    float  bearingDeg;      // направление
    float  heightAboveM;    // превышение над площадкой (м)
}
```

### 2.7 RingBuffer (кольцевой буфер трека)
```
RingBuffer<TrackPoint> {
    TrackPoint[] buffer;    // [0..capacity-1]
    int head;               // следующая позиция записи
    int count;              // сколько записано
    
    void add(TrackPoint p)
    TrackPoint get(int i)   // 0 = самая новая
    TrackPoint getOldest()
    TrackPoint get(int index) // index от head вглубь
    int size()
    void clear()
}
```

### 2.8 Units (система единиц)
```
Units {
    bool useMetric;         // true = метры/км/кмч
    // Альтиметр
    float altToM;           // коэф. пересчёта в метры
    String altLabel;        // "m" или "ft"
    // Скорость
    float speedToMps;       // коэф. в м/с
    String speedLabel;      // "km/h" / "kt" / "mph"
    // Расстояние
    float distToKm;         // коэф. в км
    String distLabel;       // "km" / "mi" / "nm"
    // Vario
    float varioToMps;       // коэф. в м/с
    String varioLabel;      // "m/s" / "ft/min"
    bool varioIsInteger;    // ft/min = true
}
```

### 2.9 TrackPoint (для кольцевого буфера)
```
TrackPoint {
    float lat;
    float lon;
    short  fixQuality;     // 0=none, 2=2D, 3=3D
    long   time;           // epoch ms
    short  varioEncoded;   // vario * 2
    short  altitudeEncoded;// alt_msl + ALT_OFFSET (закодировано)
}
```

---

## 3. GPS-движок

### 3.1 Провайдер
Используем Android `LocationManager` с провайдером `LocationManager.GPS_PROVIDER`.

### 3.2 Запрос обновлений
```python
locationManager.requestLocationUpdates(
    provider="gps",
    minTimeMs=1000,       # 1 сек в полёте
    minDistanceM=0,       # каждое изменение
    listener=gpsListener
)
```

### 3.3 LocationListener
```python
def onLocationChanged(loc):
    # Проверка точности
    if not loc.hasAccuracy() or loc.getAccuracy() > 100:
        return  # брак
    
    fix = GpsFix(
        lat=loc.getLatitude(),
        lon=loc.getLongitude(),
        altitude=loc.getAltitude(),  # MSL
        speed=loc.getSpeed(),        # м/с
        bearing=loc.getBearing(),
        time=loc.getTime(),
        accuracy=loc.getAccuracy(),
        satellites=0  # заполняется из GpsStatus
    )
    
    flightManager.onGpsFix(fix)
```

### 3.4 GPS статус
```python
def onGpsStatusChanged(event):
    if event == GpsStatus.GPS_EVENT_FIRST_FIX:
        gpsState.isListening = True
    elif event == GpsStatus.GPS_EVENT_SATELLITE_STATUS:
        # Обновляем количество спутников и SNR
        pass
    elif event == GpsStatus.GPS_EVENT_STOPPED:
        gpsState.isListening = False
```

### 3.5 Навигация из GPS
- `speed` из GPS при `useGpsSpeed = true`
- `speed` вычисляется как `haversine(pos_prev, pos_now) / dt` при `useGpsSpeed = false`
- `bearing` из GPS (если скорость > 2 м/с) иначе вычисляется

### 3.6 Последняя известная позиция
```python
def getLastKnownPosition():
    providers = locationManager.getProviders(True)
    for p in reversed(providers):
        loc = locationManager.getLastKnownLocation(p)
        if loc:
            return loc
    return None
```

---

## 4. Барометрическая высота

### 4.1 ISA формула
```python
# Международная стандартная атмосфера
T0 = 288.15    # K (15°C)
L = 0.0065     # K/m (градиент температуры)
P0 = 1013.25   # hPa (давление на уровне моря)
R = 287.058    # J/(kg·K) — газовая постоянная воздуха
g = 9.80665    # m/s²

def pressure_to_altitude(pressure_hPa, qnh_hPa=P0):
    """
    Преобразование давления в высоту по ISA.
    pressure_hPa: текущее атмосферное давление
    qnh_hPa: давление на уровне моря (QNH), настраивается
    """
    ratio = pressure_hPa / qnh_hPa
    return 44330.0 * (1.0 - ratio ** 0.1903)

def altitude_to_pressure(alt_m, qnh_hPa=P0):
    """Обратное преобразование: высота → давление"""
    return qnh_hPa * (1.0 - alt_m / 44330.0) ** (1.0 / 0.1903)
```

### 4.2 QNH коррекция
При старте (на земле, известна высота поля):
```python
def calibrate_qnh(field_elevation_m, current_pressure_hPa):
    """
    Калибровка QNH по известной высоте площадки старта.
    """
    # Сначала вычисляем QNH: каким должно быть давление на уровне моря,
    # чтобы на высоте field_elevation_m было current_pressure_hPa
    qnh = current_pressure_hPa / (1.0 - field_elevation_m / 44330.0) ** (1.0 / 0.1903)
    return qnh

qnh = calibrate_qnh(start_field_altitude, sensor_pressure)
baroAltitude = pressure_to_altitude(sensor_pressure, qnh)
```

### 4.5 Автоматическая калибровка QNH в полёте (Auto QNH)

В полёте пилот может пересечь зону другого атмосферного давления. Если известна абсолютная высота пролетаемой точки (из карты/рельефа), QNH корректируется автоматически.

```python
class AutoQnhCalibrator:
    """
    Автоматическая калибровка QNH по известным точкам высоты.
    
    Принцип: если пилот пролетает над точкой с известной высотой
    (из SRTM/DEM или waypoint с заданной высотой), и GPS высота MSL
    совпадает с высотой рельефа в пределах 50м — корректируем QNH
    так, чтобы барометрическая высота совпала с известной.
    """
    CALIBRATION_COOLDOWN_MS = 120000  # не чаще 2 минут
    ALT_THRESHOLD_M = 50              # макс. расхождение для коррекции
    
    def __init__(self):
        self.last_calibration_time = 0
        self.qnh = 1013.25  # hPa, стартовое значение
        
    def try_calibrate(self, gps_alt_msl, baro_pressure_hpa, known_ground_alt_m, timestamp_ms):
        """
        Попытка калибровки.
        
        gps_alt_msl: высота MSL от GPS (м)
        baro_pressure_hpa: текущее давление с барометра (hPa)
        known_ground_alt_m: известная высота рельефа в этой точке (м)
        timestamp_ms: текущее время
        """
        if timestamp_ms - self.last_calibration_time < CALIBRATION_COOLDOWN_MS:
            return self.qnh  # слишком часто
        
        # Разница между GPS MSL и известной высотой рельефа
        agl = gps_alt_msl - known_ground_alt_m
        
        # Если пилот явно в воздухе (AGL > 50м) — калибровать рано
        if agl < 20 or agl > 200:
            return self.qnh  # на земле или слишком высоко
        
        # Расхождение: GPS говорит MSL, барометр говорит другое
        # Вычисляем QNH: P / (1 - known_alt/44330.0)^5.255
        ratio = baro_pressure_hpa / (1.0 - known_ground_alt_m / 44330.0) ** 5.255
        
        if abs(ratio - self.qnh) < 0.5:
            return self.qnh  # изменение незначительное
        
        # Проверка: расхождение с настройкой не более 30 hPa
        if abs(ratio - 1013.25) > 30:
            return self.qnh  # выброс, игнорируем
        
        self.qnh = ratio
        self.last_calibration_time = timestamp_ms
        
        # Логируем калибровку
        logger.info(f"Auto QNH: {ratio:.2f} hPa (было {self.qnh:.2f})")
        
        return self.qnh
```
```python
def get_altitude():
    if barometer_available and barometer_calibrated:
        return baroAltitude
    else:
        return gpsAltitude  # MSL напрямую
```

### 4.4 Высота над землёй (AGL)
```python
def get_agl(lat, lon, msl_altitude):
    # Высота рельефа из SRTM/DEM (встроенная или из карт)
    terrain_height = get_terrain_elevation(lat, lon)
    return msl_altitude - terrain_height
```

---

## 5. Vario (вертикальная скорость)

### 5.1 Три источника
1. **Барометрическое варьо** — производная барометрической высоты
2. **GPS-варьо** — производная высоты MSL
3. **Энергетически компенсированное** (для поворотов)

### 5.2 Расчёт
```python
def calculate_vario(now_time):
    dt = (now_time - last_time) / 1000.0  # секунды
    if dt <= 0:
        return last_vario
    
    # 1. Сырое варьо (барометр)
    if barometer_available:
        raw_baro = (current_baro_alt - last_baro_alt) / dt
    else:
        raw_baro = 0
    
    # 2. Сырое варьо (GPS)
    raw_gps = (current_gps_alt - last_gps_alt) / dt if dt > 0 else 0
    
    # 3. Выбор источника
    if barometer_available and barometer_calibrated:
        vario_raw = raw_baro
    else:
        vario_raw = raw_gps
    
    # 4. Энергетическая компенсация (если в повороте или меняется скорость)
    vario_energy = energy_compensate(vario_raw, current_speed, last_speed, dt)
    
    # 5. Фильтрация
    vario_filtered = apply_vario_filters(vario_energy, current_altitude, now_time)
    
    return vario_filtered
```

### 5.3 Энергетическая компенсация (Energy Vario)
При изменении скорости часть энергии переходит между кинетической и потенциальной. Парапланерист отдаёт ручку — скорость растёт, варьо показывает снижение, хотя параплан летит горизонтально. Компенсация:

```python
def energy_compensate(raw_vario, speed_mps, last_speed_mps, dt):
    """
    Energy compensation (компенсация по скорости):
    dE = (v2² - v1²) / (2*g)
    compensated_vario = raw_vario + dE/dt
    """
    if dt <= 0:
        return raw_vario
    
    v1 = last_speed_mps
    v2 = speed_mps
    g = 9.81
    
    # Изменение кинетической высоты
    d_ke = (v2*v2 - v1*v1) / (2 * g)
    
    # Прибавляем к варьо (если скорость растёт, варьо занижено)
    compensated = raw_vario + d_ke / dt
    
    return compensated
```

### 5.4 Анти-алиасинг (подавление шума GPS)
```python
MIN_SPEED_FOR_VALID_VARIO = 2.0  # м/с — на земле не считаем
if speed < MIN_SPEED_FOR_VALID_VARIO:
    vario_raw = 0
```

---

## 6. Фильтры варьо (каскад)

### 6.1 Схема фильтрации
```
Vario Raw → [Deadband] → [Kalman] → [Smoothing] → Vario Output
```

### 6.2 Deadband (зона нечувствительности)
```python
def deadband_filter(vario, threshold_ms=0.05):
    """
    Если варьо очень близко к нулю — обнуляем.
    threshold: минимальное варьо для отображения (м/с)
    """
    if abs(vario) < threshold_ms:
        return 0.0
    return vario
```

### 6.3 Калмановский фильтр (3-го порядка)
```python
class KalmanVarioFilter:
    """
    Фильтр Калмана 3-го порядка для варьо.
    Состояние: [position (высота), velocity (скорость), acceleration (ускорение)]
    """
    def __init__(self, process_noise=0.1, meas_noise_factor=1.5):
        # Матрица ковариации (3x3)
        self.P = [[0, 0, 0], [0, 0, 0], [0, 0, 0]]
        # Состояние
        self.x_pos = 0      # высота
        self.x_vel = 0      # варьо (м/с)
        self.x_acc = 0      # ускорение (м/с²)
        self.Q = process_noise      # шум процесса
        self.R_factor = meas_noise_factor
        self.last_time = 0
        self.initialized = False
    
    def update(self, vario_meas, dt_sec):
        if not self.initialized:
            self.x_vel = vario_meas
            self.last_time = now
            self.initialized = True
            return vario_meas
        
        # === PREDICT ===
        dt = dt_sec
        dt2 = dt * dt / 2.0
        
        # Прогноз состояния (модель движения с ускорением)
        self.x_pos += self.x_vel * dt + self.x_acc * dt2
        self.x_vel += self.x_acc * dt
        # x_acc остаётся (инерциальная модель)
        
        # Прогноз ковариации
        Q_factor = self.Q
        P11 = self.P[0][0] + dt*self.P[1][0] + dt2*self.P[2][0] + \
              self.P[0][1]*dt + dt*dt*self.P[1][1] + dt*dt2*self.P[2][1] + \
              self.P[0][2]*dt2 + dt*dt2*self.P[1][2] + dt2*dt2*self.P[2][2] + Q_factor*dt*dt
        
        P12 = self.P[0][1] + dt*self.P[1][1] + dt2*self.P[2][1] + \
              self.P[0][2]*dt + dt*dt*self.P[1][2] + dt2*dt*self.P[2][2]
        
        P13 = self.P[0][2] + dt*self.P[1][2] + dt2*self.P[2][2]
        P22 = self.P[1][1] + 2*dt*self.P[1][2] + dt*dt*self.P[2][2] + Q_factor
        P23 = self.P[1][2] + dt*self.P[2][2]
        P33 = self.P[2][2] + Q_factor
        
        self.P = [[P11, P12, P13],
                  [P12, P22, P23],
                  [P13, P23, P33]]
        
        # === UPDATE ===
        # Адаптивный шум измерения: зависит от |vario| (чем сильнее сигнал, тем больше шум)
        R = abs(vario_meas) * self.R_factor + 0.1
        R2 = R * R
        
        # Калмановский коэффициент (по позиции — наблюдение невязки варьо)
        S = self.P[1][1] + R2  # невязка по скорости
        if S == 0:
            K_vel = 0
        else:
            K_vel = self.P[1][1] / S
        
        # Инновация (разница с измерением)
        innovation = vario_meas - self.x_vel
        
        # Обновление состояния
        self.x_vel += K_vel * innovation
        # Слабая коррекция ускорения через инновацию
        self.x_acc += 0.1 * K_vel * innovation / max(dt, 0.1)
        
        # Обновление ковариации
        self.P[1][1] = (1 - K_vel) * self.P[1][1]
        self.P[1][2] = (1 - K_vel) * self.P[1][2]
        self.P[2][1] = self.P[1][2]
        
        self.last_time = now
        return self.x_vel
    
    def reset(self):
        self.x_pos = 0
        self.x_vel = 0
        self.x_acc = 0
        self.P = [[0,0,0],[0,0,0],[0,0,0]]
        self.initialized = False
```

### 6.4 Медианный фильтр (подавление выбросов)
```python
class MedianSmoother:
    """Проходит окном по последним N значениям, возвращает медиану"""
    def __init__(self, window_ms=3000):
        self.window_ms = window_ms
        self.buffer = []  # [(value, time)]
    
    def update(self, value, time):
        self.buffer.append((value, time))
        # Удаляем старые
        cutoff = time - self.window_ms
        self.buffer = [(v, t) for v, t in self.buffer if t >= cutoff]
        
        if len(self.buffer) < 2:
            return value
        
        values = sorted([v for v, t in self.buffer])
        return values[len(values) // 2]  # медиана
```

### 6.5 Alpha-фильтр (экспоненциальное сглаживание)
```python
def alpha_filter(vario_new, vario_prev, alpha=0.3):
    """
    alpha = 0.3 (сильное сглаживание)
    alpha = 0.7 (слабое сглаживание)
    """
    return alpha * vario_new + (1 - alpha) * vario_prev
```

---

## 7. Термический ассистент

### 7.1 Обнаружение спирали
```python
class ThermalDetector:
    MIN_CIRCLING_RATE = 30      # °/сек — минимальная скорость поворота для спирали
    MIN_CIRCLING_POINTS = 6     # точек подряд для подтверждения
    MAX_CIRCLING_GAP_MS = 3000  # макс пауза между точками в спирали
    
    def __init__(self):
        self.buffer = RingBuffer(capacity=500)
        self.is_circling = False
        self.circle_start_time = 0
        self.circle_points = 0
        self.thermal = None  # ThermalSector
    
    def is_flying_slow_cruise(self, speed_mps):
        """Проверка что параплан летит достаточно медленно для спирали"""
        return 4.0 <= speed_mps <= 15.0  # ~15-54 km/h
    
    def on_gps_update(self, fix):
        self.buffer.add(TrackPoint(fix.lat, fix.lon, fix.time, fix.altitude))
        
        if self.buffer.size() < 2:
            return
        
        # Вычисляем скорость поворота
        p0 = self.buffer.get(0)   # последняя
        p1 = self.buffer.get(1)   # предпоследняя
        dt = (p0.time - p1.time) / 1000.0  # сек
        
        if dt < 0.3:  # слишком часто
            return
        
        bearing = bearing_deg(p1.lat, p1.lon, p0.lat, p0.lon)
        turn_rate = abs(bearing - self.last_bearing) / dt
        
        if (turn_rate >= MIN_CIRCLING_RATE and
            self.is_flying_slow_cruise(fix.speed)):
            
            if not self.is_circling:
                # Начало спирали
                self.is_circling = True
                self.circle_start_time = fix.time
                self.circle_points = 1
                self.thermal = ThermalSector()
                self.thermal.center = Coord(fix.lat, fix.lon)
                self.thermal.quadrantLift = [0, 0, 0, 0]
            
            # Накопление
            self.circle_points += 1
            self._accumulate_thermal_data(fix)
            
        else:
            if self.is_circling and self.circle_points >= MIN_CIRCLING_POINTS:
                # Финализация термика
                self._finalize_thermal()
            self.is_circling = False
            self.thermal = None
        
        self.last_bearing = bearing
```

### 7.2 Алгоритм квадрантов (анализ подъёма)
```python
def _accumulate_thermal_data(self, fix):
    t = self.thermal
    
    # Обновление геометрического центра (экспоненциальное сглаживание)
    n = len(self._get_circle_trail())
    alpha = 1.0 / min(n + 1, 15)  # чем больше точек, тем меньше вес
    t.center.lat += alpha * (fix.lat - t.center.lat)
    t.center.lon += alpha * (fix.lon - t.center.lon)
    
    # Относительное положение
    dx = (fix.lon - t.center.lon) * cos(radians(t.center.lat))
    dy = fix.lat - t.center.lat
    
    # Квадрант (от центра спирали)
    angle = atan2(dy, dx)  # -π .. +π
    if -PI/4 <= angle < PI/4:
        quad = 0  # E
    elif PI/4 <= angle < 3*PI/4:
        quad = 1  # N
    elif 3*PI/4 <= angle or angle < -3*PI/4:
        quad = 2  # W
    else:
        quad = 3  # S
    
    # Обновление подъёма в квадранте (сглаживание)
    alpha_q = 0.3
    t.quadrantLift[quad] = (1-alpha_q) * t.quadrantLift[quad] + alpha_q * flightState.varioFiltered
    t.quadrantCenter[quad].lat = fix.lat
    t.quadrantCenter[quad].lon = fix.lon
    
    # Средний подъём
    t.avgLift = sum(t.quadrantLift) / 4.0
    t.energy = t.avgLift
    
    # Максимальный подъём
    if flightState.varioFiltered > t.maxLift:
        t.maxLift = flightState.varioFiltered
    
    # Радиус спирали (через расстояние от центра)
    dist = haversine_km(t.center.lat, t.center.lon, fix.lat, fix.lon) * 1000
    if dist > t.radius:
        t.radius = dist
    
    # Кэш трека спирали
    self._add_to_trail(fix.lat, fix.lon)
```

### 7.3 Вычисление термического ядра (core)
```python
def _update_thermal_core(self):
    """
    Предполагаемый центр термического потока смещён
    относительно геометрического центра спирали.
    """
    t = self.thermal
    
    if not t:
        return
    
    # Лучший и худший квадрант
    best_quad = argmax(t.quadrantLift)  # индекс с макс подъёмом
    worst_quad = argmin(t.quadrantLift)  # индекс с мин подъёмом
    
    best_lift = t.quadrantLift[best_quad]
    worst_lift = t.quadrantLift[worst_quad]
    
    if best_lift <= 0:
        # Везде снижение — центр не смещаем
        t.thermalCore = Coord(t.center.lat, t.center.lon)
        return
    
    # Сдвиг центра в сторону лучшего квадранта
    # Сила сдвига пропорциональна разнице подъёмов
    lift_diff = best_lift - worst_lift
    max_shift = t.radius * 0.5  # макс 50% радиуса
    shift = min(lift_diff / max(abs(best_lift), 0.1) * max_shift, max_shift)
    
    # Направление на лучший квадрант
    q_angles = [0, PI/2, PI, -PI/2]  # E=0, N=π/2, W=π, S=-π/2
    target_angle = q_angles[best_quad]
    
    # Смещение в метрах
    dlat = shift * cos(target_angle) / 111320.0
    dlon = shift * sin(target_angle) / (111320.0 * cos(radians(t.center.lat)))
    
    t.thermalCore = Coord(
        t.center.lat + dlat,
        t.center.lon + dlon
    )
```

### 7.4 Визуализация термического помощника
На карте рисуется:

1. **3D-кольца спирали** — 7 концентрических кругов от предпоследней точки до текущей, с убывающей прозрачностью (250→31)
2. **Ядро** — оранжевый круг в предполагаемом центре потока
3. **Стрелки 4 квадрантов** — длина стрелки пропорциональна подъёму, синий = подъём, красный = снижение
4. **След трека** — полупрозрачная линия за последние 500 точек

```python
# Размер стрелки (пиксели)
arrow_length_px = abs(lift) * PX_PER_MS  # конфиг
if lift > 0:
    color = BLUE  # подъём
else:
    color = RED   # снижение

# Радиус кружка на конце стрелки
circle_radius_px = min(arrow_length_px * 0.3, MAX_RADIUS_PX)
```

### 7.5 Поиск ближайшего термика (NearThermal)

```python
class NearThermalFinder:
    """
    Сохраняет все встреченные термики (центры спиралей)
    и возвращает ближайший для виджета NearThermal.
    """
    MAX_THERMALS = 50
    THERMAL_LIFETIME_MS = 30 * 60 * 1000  # 30 минут
    
    def __init__(self):
        self.thermal_history = []  # [(lat, lon, avg_lift, time)]
    
    def add_thermal(self, center_lat, center_lon, avg_lift, time_ms):
        """Добавляет завершённый термический сектор"""
        self.thermal_history.append({
            'lat': center_lat,
            'lon': center_lon,
            'lift': avg_lift,
            'time': time_ms
        })
        # Удаление старых
        cutoff = time_ms - THERMAL_LIFETIME_MS
        self.thermal_history = [t for t in self.thermal_history if t['time'] >= cutoff]
        # Удаление лишних (оставить лучшие по времени)
        if len(self.thermal_history) > MAX_THERMALS:
            self.thermal_history.sort(key=lambda t: t['time'], reverse=True)
            self.thermal_history = self.thermal_history[:MAX_THERMALS]
    
    def find_nearest(self, current_lat, current_lon, min_lift=0.3):
        """
        Ищет ближайший термик с подъёмом > min_lift.
        Возвращает: {dir_text, dist_m, lift} или None
        """
        best = None
        best_dist = float('inf')
        
        for t in self.thermal_history:
            if t['lift'] < min_lift:
                continue
            dist_m = haversine_km(current_lat, current_lon, t['lat'], t['lon']) * 1000
            if dist_m < best_dist:
                best_dist = dist_m
                best = t
        
        if not best:
            return None
        
        brg = bearing_deg(current_lat, current_lon, best['lat'], best['lon'])
        return {
            'dir_text': wind_dir_text(brg),
            'dist_m': best_dist,
            'lift': best['lift']
        }
```

---

## 8. Ветер

### 8.1 Измерение в спирали
Во время спирали центр круга дрейфует по ветру. Скорость дрейфа = скорость ветра.

```python
class WindEstimator:
    ESTIMATE_WINDOW_MS = 30000  # окно оценки (30 сек)
    MIN_CIRCLING_FOR_WIND = 1.5  # минимум оборотов для оценки
    
    def __init__(self):
        self.circle_centers = []  # история центров спиралей
        self.wind_speed = 0
        self.wind_direction = 0
        self.confidence = 0
    
    def on_circle_center(self, center_lat, center_lon, time_ms):
        """
        Вызывается каждый раз при обновлении центра спирали.
        """
        self.circle_centers.append({
            'lat': center_lat,
            'lon': center_lon,
            'time': time_ms
        })
        
        # Очистка старых
        cutoff = time_ms - ESTIMATE_WINDOW_MS
        self.circle_centers = [c for c in self.circle_centers if c['time'] >= cutoff]
        
        if len(self.circle_centers) < 2:
            return
        
        # Дрейф центра = ветер
        first = self.circle_centers[0]
        last = self.circle_centers[-1]
        dt = (last['time'] - first['time']) / 1000.0  # секунды
        
        if dt < 5:  # слишком мало времени
            return
        
        dist_m = haversine_km(first['lat'], first['lon'], last['lat'], last['lon']) * 1000
        drift_speed_ms = dist_m / dt  # м/с
        
        drift_bearing = bearing_deg(first['lat'], first['lon'], last['lat'], last['lon'])
        
        # Ветер дует В ту же сторону, что и дрейф
        # (т.е. ветер = направление дрейфа)
        self.wind_speed = drift_speed_ms
        self.wind_direction = drift_bearing  # откуда? куда?
        # Коррекция: метео-направление (откуда дует)
        self.wind_direction = (drift_bearing + 180) % 360
        self.confidence = min(len(self.circle_centers) * 10, 100)
```

### 8.2 Измерение в прямом полёте (альтернатива)
```python
def estimate_wind_from_crab(course, track, speed_mps):
    """
    Если курс (heading) и путевой угол (track) различаются,
    значит есть ветровой снос.
    course: курс головы (по компасу/GPS heading)
    track: путевой угол (по треку между точками)
    speed_mps: скорость полёта
    """
    crab_angle = track - course  # угол сноса
    # Боковая составляющая = speed * sin(crab_angle)
    # Продольная = speed * (1 - cos(crab_angle))
    # Но это требует знания воздушной скорости (не путевой)
    # Для параплана: воздушная ≈ 8-10 м/с (поляра)
    airspeed = 9.0  # типичная скорость параплана (м/с)
    crosswind = airspeed * sin(radians(crab_angle))
    return crosswind
```

### 8.3 Фильтрация ветра
```python
def smooth_wind(new_speed, new_dir, confidence):
    """Экспоненциальное сглаживание с учётом уверенности"""
    alpha = 0.1 + confidence * 0.005  # 0.1..0.6
    alpha = min(alpha, 0.6)
    
    wind_speed = (1-alpha) * wind_speed + alpha * new_speed
    # Для направления — через векторное усреднение
    wind_dir = vector_average(wind_speed, wind_direction, new_speed, new_dir, alpha)
    
    return wind_speed, wind_dir
```

---

## 9. Аэродинамическое качество (L/D)

### 9.1 Мгновенное L/D
```python
def calculate_ld(speed_mps, vario_ms):
    """
    L/D = Горизонтальная скорость / Вертикальная скорость
    L/D = distance_forward / height_lost
    """
    if abs(vario_ms) < 0.05:  # почти ровный полёт
        return 0  # нельзя оценить
    
    # Если снижаемся: L/D = speed / sink
    # Если набираем: L/D = ∞ (термик), показываем "↑"
    if vario_ms > 0:
        return INF  # специальное значение
    else:
        return speed_mps / abs(vario_ms)
```

### 9.2 L/D по треку (скользящее окно)
```python
def track_ld(buffer, window_sec=30):
    """
    L/D по отрезку трека:
    - Берём последние N секунд
    - Сумма горизонтального расстояния / потерю высоты
    """
    if buffer.size() < 2:
        return 0
    
    cutoff_time = buffer.get(0).time - window_sec * 1000
    
    # Ищем точку, которая была window_sec назад
    start_idx = -1
    for i in range(1, buffer.size()):
        if buffer.get(i).time <= cutoff_time:
            start_idx = i
            break
    
    if start_idx == -1 or start_idx == 0:
        return 0
    
    p0 = buffer.get(0)
    p1 = buffer.get(start_idx)
    
    hor_dist_m = haversine_km(p0.lat, p0.lon, p1.lat, p1.lon) * 1000
    vert_diff_m = p0.altitude - p1.altitude
    
    if vert_diff_m <= 0:
        return INF  # набираем
    if hor_dist_m < 100:  # слишком мало
        return 0
    
    return hor_dist_m / vert_diff_m
```

### 9.3 L/D от поляры (теоретическое)
```python
def polar_ld(speed_mps):
    """
    Поляра параплана задаётся тремя точками:
    [мин_скорость, снижение_мин] = [7 м/с, 1.0 м/с]
    [оптим_скорость, снижение_опт] = [10.6 м/с, 1.1 м/с]
    [макс_скорость, снижение_макс] = [15.3 м/с, 3.0 м/с]
    
    Аппроксимация параболой: sink = a*v² + b*v + c
    """
    v = speed_mps
    if v < 7: v = 7
    if v > 15.3: v = 15.3
    
    # Параболическая аппроксимация поляры
    # sink = 0.018*v² - 0.12*v + 0.2  (для типичного параплана)
    sink = 0.018 * v * v - 0.12 * v + 0.2  # м/с
    if sink <= 0:
        return 0
    
    return v / sink
```

---

## 10. Эллипс долета

### 10.1 Базовая достижимая дальность
```python
def max_glide_distance(height_agl_m, ld_ratio):
    """Максимальная дальность планирования в безветрии"""
    return height_agl_m * ld_ratio / 1000.0  # км
```

### 10.2 Эллипс долета с учётом ветра
```python
def compute_glide_ellipse(position, height_agl_m, ld_ratio, wind_speed_ms, wind_dir_deg, n_points=36):
    """
    Строит эллипс достижимости с учётом ветра.
    В безветрии — круг радиусом H*L/D.
    С ветром — эллипс, смещённый по ветру.
    
    Аргументы:
        position: Coord (lat, lon) — текущая позиция
        height_agl_m: высота над землёй (м)
        ld_ratio: текущее L/D
        wind_speed_ms: скорость ветра (м/с)
        wind_dir_deg: направление ветра (откуда, °)
        n_points: число точек полигона
    
    Возвращает: List[Coord] — полигон достижимости
    """
    max_range_m = height_agl_m * ld_ratio  # макс. дальность (м)
    
    if max_range_m <= 100:  # слишком низко
        return []
    
    # Встречный ветер сокращает дальность, попутный — увеличивает
    wind_dir_rad = radians(wind_dir_deg)
    
    points = []
    for i in range(n_points):
        angle = 2 * PI * i / n_points
        
        # Базовая дальность в этом направлении
        range_at_angle = max_range_m  # метров
        
        # Встречная/попутная составляющая ветра в этом направлении
        # wind_dir_deg — откуда дует, т.е. в направлении (wind_dir_deg+180)
        wind_to_deg = (wind_dir_deg + 180) % 360
        wind_to_rad = radians(wind_to_deg)
        
        # Проекция ветра на направление angle
        wind_component = wind_speed_ms * cos(angle - wind_to_rad)
        
        # Коррекция дальности: встречный -> меньше, попутный -> больше
        # airspeed — скорость параплана (путевая)
        airspeed = 9.0  # м/с, типичная
        ground_speed = airspeed + wind_component
        
        if ground_speed <= 0:
            # Встречный ветер сильнее скорости параплана — не летим
            effective_range = 0
        else:
            # Время полёта = max_range_m / airspeed  (в системе отсчёта воздуха)
            flight_time = max_range_m / airspeed
            # Дальность по земле = ground_speed * flight_time
            effective_range = ground_speed * flight_time
        
        # Преобразование в координаты
        if effective_range <= 0:
            # Ставим точку на позицию (никуда не летим)
            points.append(Coord(position.lat, position.lon))
        else:
            dlat = effective_range * cos(angle) / 111320.0
            dlon = effective_range * sin(angle) / (111320.0 * cos(radians(position.lat)))
            points.append(Coord(position.lat + dlat, position.lon + dlon))
    
    return points
```

### 10.3 Отрисовка эллипса
```python
def draw_glide_ellipse(canvas, world, ellipse_points):
    if not ellipse_points or len(ellipse_points) < 3:
        return
    
    paint = Paint()
    paint.setStyle(FILL)
    paint.setColor(Color.argb(30, 255, 255, 0))  # полупрозрачный жёлтый
    paint.setAlpha(30)
    
    path = Path()
    first = True
    for pt in ellipse_points:
        screen_xy = world.geo_to_screen(pt.lat, pt.lon)
        if first:
            path.moveTo(screen_xy.x, screen_xy.y)
            first = False
        else:
            path.lineTo(screen_xy.x, screen_xy.y)
    path.close()
    
    canvas.drawPath(path, paint)
    
    # Граница эллипса
    paint.setStyle(STROKE)
    paint.setColor(Color.argb(80, 255, 255, 0))
    paint.setStrokeWidth(2)
    canvas.drawPath(path, paint)
```

---

## 11. Безопасные площадки

### 11.1 Формат базы
```
# Формат: имя,широта,долгота,радиус_м,тип,препятствия
# тип: 0=поле, 1=аэродром, 2=посадочная зона
# препятствия: 0=нет, 1=провода/деревья
Большое поле,55.1234,37.5678,200,0,0
Аэродром Бобровка,55.2345,37.6789,500,1,0
Поляна у реки,55.3456,37.7890,150,2,0
Деревня Полянка,55.4567,37.8901,300,0,1
```

### 11.2 Загрузка базы
```python
def load_landing_fields(filepath):
    fields = []
    with open(filepath) as f:
        for line in f:
            line = line.strip()
            if line.startswith('#') or not line:
                continue
            parts = line.split(',')
            if len(parts) >= 5:
                f = LandingField(
                    name=parts[0],
                    center=Coord(float(parts[1]), float(parts[2])),
                    radius=float(parts[3]),
                    type=int(parts[4]),
                    isObstacles=bool(int(parts[5])) if len(parts) > 5 else False
                )
                fields.append(f)
    return fields
```

### 11.3 Расчёт достижимых площадок
```python
def compute_reachable_fields(fields, position, height_agl, ld_ratio, 
                              wind_speed, wind_direction):
    """
    Возвращает список площадок, до которых можно долететь
    с учётом ветра, отсортированных по расстоянию.
    """
    results = []
    
    max_range_m = height_agl * ld_ratio * 1.2  # с запасом 20%
    
    for field in fields:
        dist_m = haversine_km(position.lat, position.lon, 
                              field.center.lat, field.center.lon) * 1000
        bearing = bearing_deg(position.lat, position.lon,
                              field.center.lat, field.center.lon)
        
        # Учёт ветра: встречный/попутный
        wind_dir_to = (wind_direction + 180) % 360
        wind_angle = radians(bearing - wind_dir_to)
        wind_comp = wind_speed * cos(wind_angle)
        
        gnd_speed = 9.0 + wind_comp  # путевая скорость к площадке
        if gnd_speed <= 0:
            continue  # не долететь
        
        glide_time = dist_m / gnd_speed  # время до площадки
        height_loss = glide_time * abs(flightState.varioFiltered)  # потеря высоты
        
        altitude_at_field = height_agl - height_loss
        safe_height = altitude_at_field > max(height_agl * 0.1, 50)  # запас 10% или 50м
        
        if safe_height:
            field.distanceKm = dist_m / 1000.0
            field.bearingDeg = bearing
            field.heightAboveM = altitude_at_field
            results.append(field)
    
    # Сортировка: сначала самые близкие, потом те что с большим запасом
    results.sort(key=lambda f: f.distanceKm)
    
    return results[:10]  # топ-10
```

### 11.4 Отрисовка площадок на карте
```python
def draw_landing_fields(canvas, world, fields, reachable_only=True):
    for field in fields:
        screen_center = world.geo_to_screen(field.center.lat, field.center.lon)
        
        if reachable_only and not field.isReachable:
            # Недостижимые — бледные
            paint.setAlpha(30)
        else:
            paint.setAlpha(180)
        
        # Круг площадки
        radius_px = field.radius * world.meters_per_pixel()
        paint.setColor(color_by_type(field.type))
        paint.setStyle(STROKE)
        canvas.drawCircle(screen_center.x, screen_center.y, radius_px, paint)
        
        # Заливка (полупрозрачная)
        fill_paint.setStyle(FILL)
        if field.isReachable:
            fill_paint.setColor(Color.argb(40, 0, 200, 0))  # зелёный
        else:
            fill_paint.setColor(Color.argb(20, 100, 100, 100))
        canvas.drawCircle(screen_center.x, screen_center.y, radius_px, fill_paint)
        
        # Подпись
        if world.km_per_px() < 1:  # достаточно приближено
            draw_text(canvas, field.name, screen_center.x + radius_px + 5, screen_center.y)
            if field.isReachable:
                draw_text(canvas, f"{field.distanceKm:.1f}км ↑{field.heightAboveM:.0f}м",
                         screen_center.x + radius_px + 5, screen_center.y + 15)
    
    # Если площадка выбрана — дополнительная подсветка
    # и информация в статус-баре
```

---

## 12. IGC-логгер

### 12.1 Формат IGC
```
AFLYME                    — производитель
HFDTE010101              — дата: 1 Jan 2001 (DDMMYY)
HFFXA100                  — точность фиксации (100м)
HFPLTJohn Doe             — пилот
HFGTYGliderType           — тип параплана
HFGIDGLIDER-ID           — ID параплана
HFDTM100                  — датум WGS84
HFRFWVersion 1.0         — версия прошивки
HFRHZ100                  — частота обновления GPS (0.1Гц = 10сек)
I013638TDS                — расшифровка дополнительных данных
Bhhmmssssdddmm.mmNdddmm.mmEApppAAGGGG   — B-запись
...
G4B3B280A                 — CRC записи
```

### 12.2 B-запись (фикс)
```
B{hhmmss}{ss} = время (часы/мин/сек/сотые)
{dddmm.mmN} = широта: 45°30.50'N → 4530500N
{dddmm.mmE} = долгота: 008°25.30'E → 0082530E
{A} = fix: A=GPS, V=valid, 2=2D
{ppppp} = давление (высота по давлению) в футах
{aaaaa} = высота GNSS (GPS) в футах
```
Форматы широты/долготы:
```
lat_igc = DDMMmmm (где DD = градусы, MM = минуты, mmm = тысячные минуты)
lon_igc = DDDMMmmm
```

### 12.3 Создание IGC-файла
```python
class IgcLogger:
    IGC_DIR = "ThermalGlider/igc/"
    LOG_INTERVAL_MS = 4000  # 4 секунды
    
    def __init__(self, pilot_name, glider_type, glider_id):
        self.pilot = pilot_name
        self.glider = glider_type
        self.glider_id = glider_id
        self.filename = None
        self.writer = None
        self.fix_count = 0
        self.last_fix_time = 0
        self.is_recording = False
        
    def start_recording(self, base_path, start_time):
        """Создаёт новый IGC-файл при обнаружении полёта"""
        date_str = format_date(start_time)  # DDMMYY
        filepath = f"{base_path}/{date_str}_{start_time}.igc"
        self.filename = filepath
        
        writer = open(filepath, 'w')
        
        # Заголовок
        writer.write("AFLYME\n")
        writer.write(f"HFDTE{date_str}\n")
        writer.write("HFFXA100\n")
        writer.write(f"HFPLT{pilot}\n")
        writer.write(f"HFGTY{glider}\n")
        writer.write(f"HFGID{glider_id}\n")
        writer.write("HFDTM100\n")  # WGS84
        writer.write("HFFRFWVersion 1.0\n")
        writer.write("HFRHZ100\n")  # 0.1Hz = 10s
        
        # Дополнительные данные (опционально)
        writer.write("I013638TDS\n")
        # T=track (°), D=GPS altitude difference, S=speed (km/h)
        
        self.writer = writer
        self.fix_count = 0
        self.is_recording = True
    
    def add_fix(self, fix):
        """Добавляет B-запись каждые LOG_INTERVAL_MS"""
        if not self.is_recording:
            return
        
        if fix.time - self.last_fix_time < LOG_INTERVAL_MS:
            return
        
        time_str = format_igc_time(fix.time)  # hhmmss
        lat_str = format_igc_lat(fix.lat)     # DDMMmmmN/S
        lon_str = format_igc_lon(fix.lon)     # DDDMMmmmE/W
        fix_char = 'A'  # 3D fix
        alt_press_ft = meters_to_feet(fix.baro_altitude)
        alt_gps_ft = meters_to_feet(fix.gps_altitude)
        
        # Доп. данные
        enl = estimate_noise(fix)  # ENL (Engine Noise Level) — всегда 000 для параплана
        track = int(fix.bearing) % 360
        speed_kmh = fix.speed * 3.6
        
        b_record = (f"B{time_str}{lat_str}{fix_char}"
                    f"{alt_press_ft:05d}{alt_gps_ft:05d}\n")
        
        # K-запись (доп. данные)
        k_record = f"K{time_str}{track:03d}00{speed_kmh:03d}000\n"
        
        self.writer.write(b_record)
        self.writer.write(k_record)
        self.fix_count += 1
        self.last_fix_time = fix.time

    def stop_recording(self):
        """Завершение: запись G-записи (CRC)"""
        if not self.is_recording:
            return
        
        # G-запись: CRC-CCITT всех записанных байт
        crc = compute_igc_crc32(self.writer)
        self.writer.write(f"G{crc:08X}\n")
        self.writer.close()
        self.is_recording = False
    
    def stop_recording_crc(self, writer):
        """Alternative: CRC-16 CCITT"""
        # Пересчитываем CRC по всем данным
        crc = CRC16_CCITT()
        self.writer.write(f"G{crc.value:04X}\n")
        self.writer.close()
        self.is_recording = False
```

### 12.4 Форматирование координат IGC
```python
def format_igc_lat(lat):
    """55.1234 → 5507390N"""
    hemisphere = 'N' if lat >= 0 else 'S'
    lat = abs(lat)
    degrees = int(lat)
    minutes = (lat - degrees) * 60
    minutes_int = int(minutes)
    minutes_frac = int((minutes - minutes_int) * 1000)
    return f"{degrees:02d}{minutes_int:02d}{minutes_frac:03d}{hemisphere}"

def format_igc_lon(lon):
    """37.5678 → 0373404E"""
    hemisphere = 'E' if lon >= 0 else 'W'
    lon = abs(lon)
    degrees = int(lon)
    minutes = (lon - degrees) * 60
    minutes_int = int(minutes)
    minutes_frac = int((minutes - minutes_int) * 1000)
    return f"{degrees:03d}{minutes_int:02d}{minutes_frac:03d}{hemisphere}"

def format_igc_time(epoch_ms):
    from datetime import datetime
    dt = datetime.utcfromtimestamp(epoch_ms / 1000.0)
    return dt.strftime("%H%M%S")
```

### 12.5 CRC-CCITT для IGC
```python
def compute_igc_crc16(filepath):
    """
    IGC Spec: CRC-CCITT (0x1021), XOR всех байт между B и концом
    """
    crc = 0xFFFF  # инициализация
    polynomial = 0x1021
    
    with open(filepath, 'rb') as f:
        start_collecting = False
        while True:
            byte = f.read(1)
            if not byte:
                break
            b = byte[0]
            
            # Начинаем сбор данных с первого B-символа
            if b == ord('B') and not start_collecting:
                start_collecting = True
            
            if start_collecting:
                crc ^= (b << 8)
                for _ in range(8):
                    if crc & 0x8000:
                        crc = (crc << 1) ^ polynomial
                    else:
                        crc = (crc << 1)
                    crc &= 0xFFFF
    
    return crc
```

### 12.6 Детекция старта/остановки записи
```python
def should_start_logging(fix, state):
    """Начинаем запись когда параплан в воздухе >5 сек"""
    if not state.isFlying:
        if fix.altitudeAGL > 50 and fix.speed > 3.0:
            state.flightStartTime = fix.time
            state.isFlying = True
            return True
    return state.isFlying

def should_stop_logging(fix, state):
    """Останавливаем когда на земле >30 сек"""
    if state.isFlying and fix.altitudeAGL < 20 and fix.speed < 2.0:
        if not hasattr(state, 'landingStart'):
            state.landingStart = fix.time
        elif fix.time - state.landingStart > 30000:
            state.isFlying = False
            return True
    else:
        state.landingStart = None
    return False
```

### 12.7 Открытая файловая система IGC

**Треки должны сохраняться в доступное место, читаемое с компьютера по USB.** Никаких Scoped Storage и скрытых папок.

```python
# Путь для IGC-файлов (полный доступ):
IGC_DIR = Environment.getExternalStorageDirectory() + "/ThermalGlider/igc/"

# Формат имени:
# ThermalGlider/igc/YYYY-MM-DD_HHMMSS.igc
# Пример: ThermalGlider/igc/2026-06-27_143022.igc

def save_igc(filepath):
    """
    Файл пишется напрямую на SD-карту (external storage).
    При подключении телефона к компьютеру по USB —
    папка ThermalGlider/igc/ видна как обычная папка.
    """
    pass

def list_igc_files():
    """Возвращает список всех IGC-файлов, отсортированных по дате"""
    import os
    igc_dir = IGC_DIR
    if not os.path.exists(igc_dir):
        os.makedirs(igc_dir, exist_ok=True)
        return []
    
    files = []
    for f in os.listdir(igc_dir):
        if f.endswith('.igc'):
            path = os.path.join(igc_dir, f)
            files.append({
                'name': f,
                'path': path,
                'size': os.path.getsize(path),
                'modified': os.path.getmtime(path)
            })
    
    files.sort(key=lambda x: x['modified'], reverse=True)
    return files

def delete_igc(filename):
    """Удаление IGC-файла"""
    path = os.path.join(IGC_DIR, filename)
    if os.path.exists(path):
        os.remove(path)

def export_igc_to_usb(filename, usb_path):
    """Копирование IGC на USB-накопитель (OTG)"""
    import shutil
    src = os.path.join(IGC_DIR, filename)
    dst = os.path.join(usb_path, filename)
    shutil.copy2(src, dst)
```

**Правила:**
- Все треки лежат в `ThermalGlider/igc/` на **внешнем хранилище** (SD-карта)
- Права: `WRITE_EXTERNAL_STORAGE` + `READ_EXTERNAL_STORAGE` (Android < 11) или `MANAGE_EXTERNAL_STORAGE` (Android 11+)
- Никаких `getExternalFilesDir()` — только `Environment.getExternalStorageDirectory()`
- Название файла: `2026-06-27_143022.igc` (дата_время старта UTC)
- При подключении к ПК по USB — папка видна в проводнике, файлы можно копировать/удалять
- Дополнительно: копирование IGC на OTG-флешку через меню

---

## 13. Реплей IGC

### 13.1 Парсер IGC
```python
class IgcParser:
    def __init__(self, filepath):
        self.filepath = filepath
        self.b_records = []
        self.k_records = []
        
    def parse(self):
        """Читает IGC, извлекает B-записи (фиксы)"""
        with open(self.filepath) as f:
            for line in f:
                line = line.strip()
                if line.startswith('B') and len(line) >= 35:
                    rec = self.parse_b_record(line)
                    if rec:
                        self.b_records.append(rec)
                elif line.startswith('K') and len(line) >= 20:
                    rec = self.parse_k_record(line)
                    if rec:
                        self.k_records.append(rec)
        return self
    
    def parse_b_record(self, line):
        """B0930204650200N03625110EA0009001200"""
        time = line[1:7]    # hhmmss
        lat_raw = line[7:15]  # DDMMmmm + N/S
        lon_raw = line[15:24] # DDDMMmmm + E/W
        fix_type = line[24:25]
        alt_press_ft = int(line[25:30])
        alt_gps_ft = int(line[30:35])
        
        lat = self.parse_igc_lat(lat_raw)
        lon = self.parse_igc_lon(lon_raw)
        
        return IgcFix(
            time=self.parse_time(time),
            lat=lat, lon=lon,
            altitude_press=feet_to_meters(alt_press_ft),
            altitude_gps=feet_to_meters(alt_gps_ft),
            fix_type=fix_type
        )
    
    def parse_k_record(self, line):
        """K093020045000000000"""
        time = line[1:7]
        track = int(line[7:10]) if len(line) > 7 else 0
        speed = int(line[13:16]) if len(line) > 13 else 0  # km/h
        return {
            'time': self.parse_time(time),
            'track': track,
            'speed': speed / 3.6  # м/с
        }
    
    def parse_igc_lat(self, s):
        hemisphere = s[-1]
        deg = int(s[0:2])
        min = int(s[2:4]) + int(s[4:7]) / 1000.0
        lat = deg + min / 60.0
        return -lat if hemisphere == 'S' else lat
    
    def parse_igc_lon(self, s):
        hemisphere = s[-1]
        deg = int(s[0:3])
        min = int(s[3:5]) + int(s[5:8]) / 1000.0
        lon = deg + min / 60.0
        return -lon if hemisphere == 'W' else lon
    
    def parse_time(self, s):
        h, m, sec = int(s[0:2]), int(s[2:4]), int(s[4:6])
        return h * 3600 + m * 60 + sec  # секунд от начала дня
```

### 13.2 Запуск реплея
```python
class IgcReplay:
    def __init__(self, filepath):
        self.parser = IgcParser(filepath).parse()
        self.records = self.parser.b_records
        self.index = 0
        self.start_realtime = 0
        self.rec_start_time = 0
    
    def start(self):
        """Запуск реплея в фоновом потоке"""
        if not self.records:
            return
        
        self.index = 0
        self.start_realtime = current_time_ms()
        self.rec_start_time = self._to_epoch(self.records[0].time, datetime.now())
        
        # Запускаем поток с нужной скоростью
        thread = Thread(target=self._run)
        thread.start()
    
    def _run(self):
        """Цикл реплея"""
        while self.index < len(self.records):
            if self._is_stopped():
                break
            
            rec = self.records[self.index]
            
            # Время между записями в оригинале
            if self.index > 0:
                prev = self.records[self.index - 1]
                dt = rec.time - prev.time  # секунд
            else:
                dt = 0
            
            sleep(dt * 1000)  # ожидание в мс
            
            # Эмуляция GPS фикса
            fix = GpsFix(
                lat=rec.lat,
                lon=rec.lon,
                altitude=rec.altitude_gps,
                speed=0,  # будет вычислено из дистанции/dt
                bearing=0,
                time=current_time_ms(),
                accuracy=10
            )
            
            # Подача в пайплайн
            flightManager.onGpsFix(fix)
            self.index += 1
        
        logger.info("Replay finished")
```

### 13.3 Управление реплеем
```python
# Скорость реплея (1.0 = реальное время, 2.0 = ускорено, 0.5 = замедлено)
replay_speed = 1.0

# Режимы:
# START — начать с первой записи
# PAUSE — приостановить
# RESUME — продолжить
# STOP — завершить, вернуться к реальному GPS
# SPEED — изменить скорость
```

---

## 14. Картографический движок

### 14.1 Формат VMP
VMP — простой тайловый формат. Тайл — квадратный фрагмент карты (256×256 px), содержит:
- Базовую карту (дороги, реки, города)
- Контуры рельефа (изолинии)
- Высотные отметки

### 14.2 Иерархия
```
maps/
  base/              # базовые карты (1:200k)
    0/               # zoom level 0
      0_0.vmp        # x_y.vmp
      1_0.vmp
      ...
    1/               # zoom level 1 (×2 детализации)
    ...
```

### 14.3 VmpWorld (мировое окно)
```python
class MapWorld:
    # Проекция — Plate Carrée (Equirectangular)
    
    def __init__(self):
        self.center_lat = 0.0
        self.center_lon = 0.0
        self.zoom_level = 1.0
        self.km_per_px = 0.0  # вычисляется
    
    def geo_to_screen(self, lat, lon):
        """Географические координаты → экранные пиксели"""
        # Проекция: x = λ*cos(φ₀), y = φ
        # где φ₀ — центральная широта
        scale = self._get_scale()
        dx = (lon - self.center_lon) * cos(radians(self.center_lat)) * scale
        dy = -(lat - self.center_lat) * scale
        screen_x = VIEWPORT_WIDTH / 2 + dx
        screen_y = VIEWPORT_HEIGHT / 2 + dy
        return (screen_x, screen_y)
    
    def screen_to_geo(self, sx, sy):
        """Обратное преобразование"""
        scale = self._get_scale()
        dx = sx - VIEWPORT_WIDTH / 2
        dy = sy - VIEWPORT_HEIGHT / 2
        lon = self.center_lon + dx / (scale * cos(radians(self.center_lat)))
        lat = self.center_lat - dy / scale
        return (lat, lon)
    
    def _get_scale(self):
        """Масштаб: пикселей на градус широты"""
        return VIEWPORT_HEIGHT / (2 * self.km_per_degree / (self.km_per_px * 1000))
    
    # Альтернативно:
    def meters_per_pixel(self):
        """Метров в пикселе на текущем зуме"""
        return self.km_per_px * 1000
    
    def move_by(self, dx_px, dy_px):
        """Прокрутка карты на dx, dy пикселей"""
        scale = self._get_scale()
        dlat = -dy_px / scale
        dlon = dx_px / (scale * cos(radians(self.center_lat)))
        self.center_lat += dlat
        self.center_lon += dlon
    
    def zoom_in(self, factor=1.5):
        self.km_per_px /= factor
    
    def zoom_out(self, factor=1.5):
        self.km_per_px *= factor
        self.km_per_px = min(self.km_per_px, 50.0)  # не дальше 50 км/пиксель
```

### 14.4 Рендеринг тайлов
```python
def get_visible_tiles(self):
    """Возвращает список (zoom, x, y) тайлов, видимых на экране"""
    # Координаты углов экрана
    lat0, lon0 = self.screen_to_geo(0, 0)
    lat1, lon1 = self.screen_to_geo(VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
    
    # Определяем zoom-level для тайлов (из km_per_px)
    zoom = self._km_to_tile_zoom(self.km_per_px)
    
    # Вычисляем диапазон тайлов
    tile_size_deg = 360.0 / (2 ** zoom)
    
    x0 = int(math.floor((lon0 + 180) / tile_size_deg))
    x1 = int(math.ceil((lon1 + 180) / tile_size_deg))
    y0 = int(math.floor((lat0 + 90) / tile_size_deg))
    y1 = int(math.ceil((lat1 + 90) / tile_size_deg))
    
    tiles = []
    for x in range(x0, x1):
        for y in range(y0, y1):
            tiles.append((zoom, x, y))
    return tiles
```

---

## 15. Загрузка карт

### 15.1 Локальный кэш
```python
TILE_CACHE_DIR = "ThermalGlider/maps/"

def get_tile_path(zoom, x, y):
    """Путь к локальному файлу тайла"""
    return f"{TILE_CACHE_DIR}/{zoom}/{x}_{y}.vmp"

def get_tile_url(zoom, x, y):
    """URL для скачивания (если нет локально)"""
    return f"https://tiles.xcglobe.com/mapsv1/{zoom}/{x}/{y}.vmp"
```

### 15.2 Асинхронная загрузка
```python
class TileLoader:
    MAX_CONCURRENT = 4
    
    def __init__(self):
        self.pending = Queue()
        self.active_count = 0
        self.cache_tiles = {}
    
    def request_tile(self, zoom, x, y):
        self.pending.put((zoom, x, y))
        self._process_queue()
    
    def _process_queue(self):
        while not self.pending.empty() and self.active_count < MAX_CONCURRENT:
            zoom, x, y = self.pending.get()
            self.active_count += 1
            Thread(target=self._load_tile, args=(zoom, x, y)).start()
    
    def _load_tile(self, zoom, x, y):
        # 1. Проверка кэша в памяти
        # 2. Проверка локального файла
        # 3. HTTP-загрузка
        # 4. Декодирование -> Bitmap
        # 5. Сохранение в кэш
        
        local_path = get_tile_path(zoom, x, y)
        if os.path.exists(local_path):
            bitmap = decode_vmp(local_path)
        else:
            url = f"https://tiles.xcglobe.com/maps/{zoom}/{x}/{y}.vmp"
            bitmap = download_and_decode(url)
            if bitmap:
                os.makedirs(os.path.dirname(local_path), exist_ok=True)
                save_vmp(bitmap, local_path)
        
        if bitmap:
            cache_tiles[(zoom, x, y)] = bitmap
        
        # UI callback (post to UI thread)
        handler.post(lambda: redraw())
        
        self.active_count -= 1
        self._process_queue()
```

### 15.3 Формат VMP файла (упрощённый)
```python
def decode_vmp(filepath):
    """
    VMP — нестандартный формат.
    Для упрощения прибора используем стандартные тайлы (MBTiles или PNG).
    
    Альтернатива: готовые тайлы OpenStreetMap (OSM):
    https://tile.openstreetmap.org/{zoom}/{x}/{y}.png
    """
    # Если VMP формат — парсинг:
    with open(filepath, 'rb') as f:
        header = f.read(8)
        if header[:4] == b'VMP ':
            version = struct.unpack('>I', header[4:8])[0]
            # Распаковка данных тайла
            ...
```

### 14.5 Спутниковая подложка (Google/OSM Satellite)

В режиме термической помощи карта автоматически переключается на **спутниковую/аэрофото подложку** (Google Satellite / Bing Aerial / OSM Satellite). Это критически важно — на ней видны:
- Лесные массивы (тёмные) vs поля (светлые)
- Водоёмы, реки
- Линии электропередач
- Дороги, населённые пункты
- Овраги, холмы (по теням)

```python
class MapTileProvider:
    """
    Два источника тайлов:
    1. Карта (OSM/vector) — обычный режим
    2. Аэрофото (спутник) — когда isCircling=True (автопереключение)
    """
    TILE_SOURCES = {
        "OSM": "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
        "SATELLITE": "https://mt1.google.com/vt/lyrs=s&x={x}&y={y}&z={z}",
        # Альтернатива (без ключа):
        "SATELLITE_FREE": "https://tiles.openaerialmap.org/...",  
        # Или локальные VMP-тайлы с аэрофото
    }
    
    def __init__(self):
        self.current_source = "OSM"
        self.tile_cache = {}
    
    def get_tile(self, zoom, x, y, satellite_mode=False):
        source = "SATELLITE" if satellite_mode else "OSM"
        url_template = self.TILE_SOURCES[source]
        url = url_template.replace("{z}", str(zoom)).replace("{x}", str(x)).replace("{y}", str(y))
        return self._load_tile(url, zoom, x, y, source)
    
    def set_thermal_mode(self, active: bool):
        """Автоматическое переключение на спутник при входе в спираль"""
        if active and self.current_source != "SATELLITE":
            self.current_source = "SATELLITE"
            self.reload_visible_tiles()
        elif not active and self.current_source != "OSM":
            self.current_source = "OSM"
            self.reload_visible_tiles()
```

### 14.6 Кэш тайлов

```python
TILE_CACHE_SIZE_MAX = 200  # макс. тайлов в памяти
# На диске: ThermalGlider/maps/cache/{source}/{zoom}/{x}_{y}.png
# Срок жизни кэша: 30 дней, после — перезагрузка

def clean_tile_cache():
    """Удаление тайлов старше 30 дней"""
    for root, dirs, files in os.walk(TILE_CACHE_DIR):
        for f in files:
            path = os.path.join(root, f)
            age_days = (time.time() - os.path.getmtime(path)) / 86400
            if age_days > 30:
                os.remove(path)
```
### 14.7 Предзагрузка карт (Offline)

```python
def preload_area(lat0, lon0, lat1, lon1, zoom_min=5, zoom_max=10):
    """
    Предзагрузка тайлов для области.
    """
    for zoom in range(zoom_min, zoom_max + 1):
        tile_size_deg = 360.0 / (2 ** zoom)
        x0 = int(math.floor((lon0 + 180) / tile_size_deg))
        x1 = int(math.ceil((lon1 + 180) / tile_size_deg))
        y0 = int(math.floor((lat0 + 90) / tile_size_deg))
        y1 = int(math.ceil((lat1 + 90) / tile_size_deg))
        
        for x in range(x0, x1 + 1):
            for y in range(y0, y1 + 1):
                tile_url = f"https://tile.openstreetmap.org/{zoom}/{x}/{y}.png"
                local_path = get_tile_path(zoom, x, y)
                if not os.path.exists(local_path):
                    download_file(tile_url, local_path)
```

---

## 16. Основной экран

### 16.1 Компоновка экрана (фиксированная, без свайпа)

**Один экран на весь полёт.** Никаких смахиваний, переключений режимов и скрытых панелей. Всё, что нужно пилоту — перед глазами.

```
┌────────────────────────────────────────────────────────┐
│ Статус-бар: [🛰 7] [🔴 REC] [↑ 1234m] [00:23:45]     │
├────────────────────────────────────────────────────────┤
│                                                        │
│   ┌──────────────────────────────────────────────┐     │
│   │                                              │     │
│   │               КАРТА (60% экрана)              │     │
│   │                                              │     │
│   │   ┌─── спутниковая подложка ───────────┐     │     │
│   │   │ [Термик 3D] [Трек] [Площадки]      │     │     │
│   │   │ [Эллипс] [Стрелки-помощник]        │     │     │
│   │   │              ╱╲                     │     │     │
│   │   │     □ пл     ╲╱  ⬆︎  □ пл         │     │     │
│   │   │              планер                │     │     │
│   │   └────────────────────────────────────┘     │     │
│   │                                        ╱╲   │     │
│   │   эллипс долёта ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓  ╱    ╲  │     │
│   │                              ╱          ╲ │     │
│   └────────────────────────────────────────────┘     │
│                                                        │
├────────────────────────────────────────────────────────┤
│ Панель виджетов (9 шт, 3 строки × 3 столбца)          │
│ ┌──────────┬──────────┬──────────┐                     │
│ │ Vario    │ Altitude │ Speed    │                     │
│ │ +2.3 м/с │ 1234 м   │ 36 км/ч  │                     │
│ ├──────────┼──────────┼──────────┤                     │
│ │ L/D      │ Wind     │ AGL      │                     │
│ │ 9.5      │ NW 12км/ч│ 1150 м   │                     │
│ ├──────────┼──────────┼──────────┤                     │
│ │ Heading  │ NearTherm│ Distance │                     │
│ │ 235° SSW │ 200м ↑1.2│ 15.3 км  │                     │
│ └──────────┴──────────┴──────────┘                     │
├────────────────────────────────────────────────────────┤
│ [Режим карты ▾] [Center] [Zoom ▸▸] [Выбор площадки ▾]│
└────────────────────────────────────────────────────────┘
```

### 16.2 Принципы UI

1. **Никаких смахиваний экрана в полёте.** Всё на одном экране.
2. **Карта = всегда 60% экрана** сверху, под ней панель виджетов.

3. **Длительный тап (long press, 500ms) по пустому месту на карте** → всплывающий диалог (аналог FlyMe `g/r`):

   В диалоге:
   - **3 большие кнопки:**
     - «Треки» — переход в ActivityFlights (список IGC-файлов, просмотр/реплей/удаление)
     - «Настройки» — открытие экрана настроек
     - «Выход» — завершение полёта (с подтверждением)
   - **Регулировка зума** — ViewSwipeButton (слайдер +/-)
   - **Размер InfoBox** — ViewSwipeButton с 5 уровнями (1-5)
   - **Кнопка REC/STOP** — старт/остановка записи IGC
   
   Реализация (аналог FlyMe VmpEventManager + g/r):
   ```python
   class MapTouchHandler:
       LONG_PRESS_TIMEOUT_MS = 500  # как в FlyMe
       
       def __init__(self, view, activity):
           self.view = view
           self.activity = activity
           self.handler = Handler()
           self.down_x = 0
           self.down_y = 0
           self.is_dragging = False
           self.longpress_runnable = None
       
       def on_touch(self, event):
           if event.action == ACTION_DOWN:
               self.down_x = event.x
               self.down_y = event.y
               self.is_dragging = False
               # Запускаем таймер long-press (500ms) — как FlyMe startLongPress()
               self.longpress_runnable = self._on_long_press
               self.handler.postDelayed(self.longpress_runnable, LONG_PRESS_TIMEOUT_MS)
               return True
           
           elif event.action == ACTION_MOVE:
               dx = abs(event.x - self.down_x)
               dy = abs(event.y - self.down_y)
               if dx > 10 or dy > 10:
                   self.handler.removeCallbacks(self.longpress_runnable)
                   self.is_dragging = True
                   # перетаскивание карты...
           
           elif event.action == ACTION_UP:
               self.handler.removeCallbacks(self.longpress_runnable)
               if not self.is_dragging:
                   # короткое касание — как FlyMe touchEmptySpace → toggle
                   display.vmap.ViewVmp.setFlag(16)  # FLAG_TOGGLE
                   self.view.performClick()
           
           return True
       
       def _on_long_press(self):
           # Как FlyMe longClick() → ActivityMain.b(23) → g.r.a(activity, view)
           show_settings_dialog(self.activity, self.view)
   
   def show_settings_dialog(activity, view):
       """Аналог FlyMe g/r.b(view) — диалог быстрых настроек"""
       dialog = Dialog(activity)
       dialog.setContentView(R.layout.settings_popup)
       dialog.setCanceledOnTouchOutside(True)
       
       # Кнопка "Треки" — переход к списку IGC
       dialog.findViewById(R.id.btn_tracks).setOnClickListener(
           lambda: activity.startActivity(ActivityFlights))
       
       # Кнопка "Настройки" — экран конфигурации
       dialog.findViewById(R.id.btn_settings).setOnClickListener(
           lambda: activity.startActivity(SettingsActivity))
       
       # Кнопка "Выход" — завершение с подтверждением
       dialog.findViewById(R.id.btn_exit).setOnClickListener(
           lambda: confirm_exit(activity))
       
       # REC/STOP кнопка
       rec_btn = dialog.findViewById(R.id.btn_rec)
       if is_recording():
           rec_btn.setText("■ STOP")
           rec_btn.setOnClickListener(lambda: stop_recording())
       else:
           rec_btn.setText("● REC")
           rec_btn.setOnClickListener(lambda: start_recording())
       
       dialog.show()
   ```

4. **Длительный тап (long press, 500ms) по виджету** → меню выбора виджета (аналог FlyMe VmpEditor.editBox):
   - Показывается AlertDialog с чекбоксами всех 22 виджетов
   - Можно выбрать один (замена) или несколько (BoxSet — группа)
   - Выбранные виджеты встают на это место
   - Настройка запоминается в SharedPreferences (ключ `boxes`)
   
   Реализация (аналог FlyMe VmpEditor):
   ```python
   def on_widget_long_press(infoBox):
       # Список всех доступных виджетов
       all_widgets = [
           "BoxVario", "BoxAltitude", "BoxSpeed", "BoxHeading",
           "BoxFinesse", "BoxWind", "BoxAgl", "BoxDistance",
           "BoxTime", "BoxMaxAlt", "BoxTotalClimb", "BoxAvgLD",
           "BoxAvgClimb", "BoxGlideToGoal", "BoxGoalDist",
           "BoxGoalTime", "BoxGroundElev", "BoxNearThermal",
           "BoxSpeedCircle", "BoxAirspace", "BoxAirspaceInfo",
           "BoxSet"  # групповая панель
       ]
       
       # Определяем, какие уже выбраны
       current = infoBox.getClass().getSimpleName()
       
       # Создаём AlertDialog с мультивыбором
       dialog = AlertDialog.Builder(context)
           .setTitle("Выбор виджета")
           .setMultiChoiceItems(all_widgets, selected, callback)
           .setPositiveButton("OK", lambda d, w: changeBox(infoBox, selected_names))
           .setNegativeButton("Отмена", None)
           .show()
   
   def changeBox(oldBox, new_names):
       """Замена виджета (аналог FlyMe VmpEditor.changeBox)"""
       idx = view.boxes.indexOf(oldBox)
       
       if len(new_names) == 0:
           # Удалить виджет
           view.boxes.remove(oldBox)
       elif len(new_names) == 1:
           # Заменить на один
           newBox = create_box(new_names[0])
           view.boxes.set(idx, newBox)
       else:
           # Создать BoxSet (группу)
           group = BoxSet()
           for name in new_names:
               group.add(create_box(name))
           view.boxes.set(idx, group)
       
       # Сохранить раскладку
       save_layout(view.boxes)
       view.initDisplay()
   ```

5. **Касание по площадке на карте** → выделение + в виджетах Goal/GlideToGoal показывается дистанция и потребное L/D до неё.

6. **Одиночный тап по центру карты** (по планеру) → центрирование / переключение Track-Up / North-Up.

### 16.3 Режимы карты
```
MODE_TRACK_UP:    карта вращается по курсу (по умолчанию)
MODE_NORTH_UP:    север вверх
MODE_CENTER:      автоцентрирование по позиции
MODE_FREE:        свободная прокрутка (касание)
MODE_THERMAL:     увеличенная карта, фокус на термиках
```

### 16.3 Жесты
```
- Одиночное касание:              показать InfoBox выбранного объекта
- Двойное касание:                зум +/-
- Свайп:                          прокрутка карты
- Pinch:                          зум (двумя пальцами)
- Долгое нажатие:                 добавить точку/площадку
```

### 16.4 Статус-бар
```
[🛰 7]  [🔴 REC]  [↑ 1234m]  [00:23:45]  [+2.3 ↑]
   │        │         │           │          │
спутники  запись   высота    время      среднее варьо
          IGC       MSL      полёта     за спираль
```

---

## 17. InfoBox-панели (22 виджета)

### 17.1 Список InfoBox (все 22)

Теперь общее число виджетов = 22 (было 19 в FlyMe минус 2 соревновательных + 5 новых полезных = 22)

| Бокс | Содержание | Формула |
|---|---|---|
| **Vario** | Текущее варьо (м/с) | `flightState.varioFiltered` |
| **Altitude** | Высота MSL и AGL | `baroAlt / gpsAlt` |
| **Speed** | Путевая скорость | `flightState.speed * 3.6 → км/ч` |
| **L/D** | Аэродинамическое качество | `speed / sink_rate` |
| **Wind** | Ветер: напр.+скорость | `windDir → "NW" + windSpeed` |
| **AGL** | Высота над землёй | `baroAlt - terrainElev` |
| **Distance** | Пройдено за полёт | `totalDistance (км)` |
| **Time** | Длительность полёта | `(now - flightStartTime)` |
| **Max Alt** | Максимальная высота | `maxAltitude (м)` |
| **Total Climb** | Суммарный набор | `totalClimb (м)` |
| **Avg L/D** | Среднее L/D за полёт | `скользящее среднее` |
| **AvgClimb** | Средний подъём в спирали | `thermal.avgLift (м/с)` |
| **Heading** | Курс планера | `bearing → "235° SSW"` |
| **GlideToGoal** | Потребное L/D до цели | `dist_m / height_agl_m` (если выбрана площадка) |
| **GoalDist** | Дистанция до выбранной площадки | `haversine_km(pos, target)` |
| **GoalTime** | Время до площадки | `dist_km / speed_kmh * 3600 → "12:34"` |
| **GroundElev** | Высота рельефа под планером | `get_terrain_elevation(lat, lon)` |
| **NearThermal** | Ближайший сохранённый термик | `direction + distance + lift` |
| **SpeedCircle** | Скорость в спирали | `speed_mps → км/ч` (показывается только когда isCircling) |
| **Airspace** | Статус ВП | `"CTR 1200m MSL"` или `"—"` если вне зон |
| **AirspaceInfo** | Детальная информация о ВП | `имя, класс, верх/низ, расстояние до границы` |

### 17.2 Реализация InfoBox
```python
class InfoBox:
    PADDING = 4
    TITLE_SIZE_RATIO = 0.35  # заголовок = 35% высоты бокса
    
    def __init__(self, box_type, title, unit=""):
        self.type = box_type
        self.title = title
        self.unit = unit
        self.rect = Rect()
        self.value = "-"
        self.stale_timeout = 5000  # мс, после чего показывать "---"
    
    def update(self, flightState):
        if current_time_ms() - flightState.last_update > self.stale_timeout:
            self.value = "---"
            return
        
        self.value = self.compute_value(flightState)
    
    def compute_value(self, state):
        if self.type == 'VARIO':
            v = state.varioFiltered
            return f"{v:+.1f}"
        elif self.type == 'ALT':
            return f"{state.baroAltitude:.0f}"
        elif self.type == 'SPEED':
            return f"{state.speed * 3.6:.0f}"
        elif self.type == 'LD':
            if state.glideRatio == INF:
                return "↑"
            return f"{state.glideRatio:.1f}"
        elif self.type == 'WIND':
            return f"{wind_dir_text(state.windDirection)} {state.windSpeed * 3.6:.0f}"
        elif self.type == 'HEADING':
            deg = state.bearing
            return f"{deg:.0f}° {wind_dir_text(deg)}"
        elif self.type == 'GLIDE_TO_GOAL':
            if state.selectedField:
                dist_m = state.selectedField.distanceKm * 1000
                if dist_m <= 0 or state.altitudeAGL <= 0:
                    return "---"
                ld_needed = dist_m / state.altitudeAGL
                return f"{ld_needed:.1f}"
            return "—"
        elif self.type == 'GOAL_DIST':
            if state.selectedField:
                return f"{state.selectedField.distanceKm:.1f}"
            return "—"
        elif self.type == 'GOAL_TIME':
            if state.selectedField and state.speed > 0:
                hours = state.selectedField.distanceKm / (state.speed * 3.6)
                m = int(hours * 60)
                s = int((hours * 3600) % 60)
                return f"{m}:{s:02d}"
            return "—"
        elif self.type == 'GROUND_ELEV':
            elev = get_terrain_elevation(state.position.lat, state.position.lon)
            return f"{elev:.0f}"
        elif self.type == 'NEAR_THERMAL':
            th = state.nearestThermal
            if th:
                return f"{th.dir_text} {th.dist_m:.0f}м ↑{th.lift:+.1f}"
            return "—"
        elif self.type == 'SPEED_CIRCLE':
            if state.isCircling:
                return f"{state.speed * 3.6:.0f}"
            return "—"
        elif self.type == 'AIRSPACE':
            if state.currentAirspace:
                return f"{state.currentAirspace.name} ({state.currentAirspace.airspace_class})"
            return "—"
        elif self.type == 'AIRSPACE_INFO':
            if state.currentAirspace:
                a = state.currentAirspace
                return f"{a.alt_top:.0f}м MSL / {a.name}"
            return "—"
        # ...
    
    def draw(self, canvas, paint):
        # Фон
        canvas.drawRect(self.rect, bg_paint)
        
        # Заголовок
        canvas.drawText(self.title, self.rect.left + PADDING, 
                       self.rect.top + self.rect.height() * TITLE_SIZE_RATIO, title_paint)
        
        # Значение
        val_paint.color = self._value_color()
        canvas.drawText(self.value, self.rect.left + PADDING,
                       self.rect.bottom - PADDING, val_paint)
        
        # Единицы
        if self.unit:
            canvas.drawText(self.unit, self.rect.right - PADDING,
                          self.rect.bottom - PADDING, unit_paint)
    
    def _value_color(self):
        if 'VARIO' in self.type:
            if self.value > 0.5: return Color.GREEN
            if self.value < -0.5: return Color.RED
            return Color.WHITE
        return Color.LIGHT_GRAY
```

---

## 18. Термик-отрисовка на карте

### 18.1 Компоненты термик-отрисовки

```python
class ThermalRenderer:
    def __init__(self):
        self.circle_paint = Paint()
        self.circle_paint.setStyle(STROKE)
        self.circle_paint.setAntiAlias(True)
        
        self.track_paint = Paint()
        self.track_paint.setStyle(STROKE)
        self.track_paint.setColor(Color.argb(64, 200, 200, 200))
        self.track_paint.setStrokeWidth(3)
        self.track_paint.setStrokeJoin(ROUND)
        
        self.helper_paint = Paint()
        self.helper_paint.setStyle(FILL)
        self.helper_paint.setAntiAlias(True)
    
    def draw(self, canvas, world, state):
        if not state.isCircling or not state.thermal:
            return
        
        t = state.thermal
        center_screen = world.geo_to_screen(t.center.lat, t.center.lon)
        core_screen = world.geo_to_screen(t.thermalCore.lat, t.thermalCore.lon)
        radius_px = t.radius * world.meters_per_pixel()
        
        # 1. След трека (последние 500 точек)
        self._draw_track(canvas, world, state.trackBuffer)
        
        # 2. 3D кольца (эффект "бублика")
        self._draw_3d_rings(canvas, center_screen, core_screen, radius_px)
        
        # 3. Оранжевое ядро
        self.circle_paint.setColor(Color.argb(200, 255, 128, 0))
        self.circle_paint.setStrokeWidth(3)
        canvas.drawCircle(core_screen.x, core_screen.y, radius_px * 0.3, self.circle_paint)
        
        # 4. Квадрантные стрелки-помощники
        self._draw_quad_helpers(canvas, world, t, core_screen)
        
        # 5. Текст с подъёмом
        lift_text = f"{t.avgLift:+.1f}"
        canvas.drawText(lift_text, core_screen.x, core_screen.y - radius_px - 10, text_paint)
    
    def _draw_3d_rings(self, canvas, center_from, center_to, radius):
        """7 концентрических кругов от предпоследней позиции к текущей"""
        n_rings = 7
        for i in range(n_rings):
            t = (i + 1) / n_rings
            cx = center_from.x + (center_to.x - center_from.x) * t
            cy = center_from.y + (center_to.y - center_from.y) * t
            r = radius * (1.0 - 0.3 * t)
            alpha = 250 - i * 31
            self.circle_paint.setAlpha(max(alpha, 30))
            canvas.drawCircle(cx, cy, r, self.circle_paint)
        self.circle_paint.setAlpha(255)
    
    def _draw_quad_helpers(self, canvas, world, thermal, core_screen):
        """4 стрелки по квадрантам, длина = сила подъёма"""
        directions = [
            (0, 1),     # N
            (1, 0),     # E
            (0, -1),    # S
            (-1, 0),    # W
        ]
        
        for i, (dx, dy) in enumerate(directions):
            lift = thermal.quadrantLift[i]
            if abs(lift) < 0.1:
                continue
            
            # Длина стрелки (пиксели)
            length = min(abs(lift) * 50, 100)  # 50 px на 1 м/с, макс 100
            
            # Цвет: синий = подъём, красный = снижение
            if lift > 0:
                self.helper_paint.setColor(Color.argb(180, 0, 100, 255))
            else:
                self.helper_paint.setColor(Color.argb(180, 255, 0, 0))
            
            x0 = core_screen.x
            y0 = core_screen.y
            x1 = x0 + dx * length
            y1 = y0 + dy * length
            
            # Стрелка
            canvas.drawLine(x0, y0, x1, y1, self.helper_paint)
            
            # Кружок на конце
            circle_r = max(5, length * 0.15)
            canvas.drawCircle(x1, y1, circle_r, self.helper_paint)
```

### 18.2 Рисование трека
```python
def _draw_track(self, canvas, world, buffer):
    """Полупрозрачный след трека"""
    path = Path()
    first = True
    
    n = buffer.size()
    for i in range(n - 1, -1, -1):  # от старых к новым
        pt = buffer.get(i)
        screen = world.geo_to_screen(pt.lat, pt.lon)
        
        if first:
            path.moveTo(screen.x, screen.y)
            first = False
        else:
            path.lineTo(screen.x, screen.y)
    
    canvas.drawPath(path, self.track_paint)
```

### 18.3 Автоматическая подстройка зума по дальности планирования

**Принцип:** пилот должен видеть на карте ровно ту область, куда он может долететь с безопасным запасом. **Ветер учитывается** — дальность по ветру и против ветра разная, автомасштаб подстраивается под **наихудшее направление** (встречный ветер), чтобы эллипс долёта полностью помещался на экране.

```python
class AutoZoomController:
    """
    Автомасштаб карты на основе дальности планирования с учётом ветра.
    
    В спирали:  край карты = дальность потери 200м высоты
    По прямой:  край карты = дальность до 150м AGL
    
    Ветер: масштаб подстраивается под встречное направление (самое короткое),
    чтобы эллипс долёта был полностью виден на экране.
    
    Это даёт пилоту ответ на главный вопрос:
    «Куда я могу безопасно улететь отсюда?» — видно сразу на карте.
    """
    
    THERMAL_HEIGHT_LOSS = 200    # метров — потеря в спирали
    CRUISE_AGL_MINIMUM = 150     # метров AGL — безопасный минимум по прямой
    
    def range_with_wind(self, base_range_km, wind_speed_ms, wind_dir_deg, 
                        direction_deg, airspeed_ms=9.0):
        """
        Коррекция дальности с учётом ветра.
        
        base_range_km: дальность в безветрии (H * L/D)
        wind_speed_ms: скорость ветра (м/с)
        wind_dir_deg: направление ветра (откуда, °)
        direction_deg: направление полёта (°)
        airspeed_ms: воздушная скорость параплана (м/с)
        """
        if wind_speed_ms < 0.5 or airspeed_ms <= 0:
            return base_range_km  # ветра нет
        
        # Ветер дует В направлении (wind_dir_deg + 180)°
        wind_to = (wind_dir_deg + 180) % 360
        
        # Угол между направлением полёта и направлением ветра
        angle = radians(direction_deg - wind_to)
        wind_component = wind_speed_ms * cos(angle)
        
        # ground_speed = airspeed + wind_component
        ground_speed = airspeed_ms + wind_component
        
        if ground_speed <= 0:
            return 0  # встречный ветер сильнее скорости — никуда не летим
        
        # Время полёта в системе воздуха = base_range_km * 1000 / airspeed
        flight_time_sec = (base_range_km * 1000) / airspeed_ms
        
        # Дальность по земле = ground_speed * flight_time_sec
        return (ground_speed * flight_time_sec) / 1000.0
    
    def compute_target_km_per_px(self, state, viewport_width_px, viewport_height_px):
        """
        Вычисляет масштаб так, чтобы эллипс долёта полностью помещался на экране.
        Если ветра нет — используется круговая симметричная дальность.
        """
        height_agl = state.altitudeAGL
        ld = state.glideRatio
        
        if height_agl <= 0 or ld <= 0:
            return None
        
        # 1. Базовая дальность (в безветрии)
        if state.isCircling:
            usable_height = min(self.THERMAL_HEIGHT_LOSS, height_agl * 0.3)
            base_range_km = (usable_height * ld) / 1000.0
        else:
            usable_height = height_agl - self.CRUISE_AGL_MINIMUM
            if usable_height <= 0:
                return 0.005  # мин. зум ~5м/px
            base_range_km = (usable_height * ld) / 1000.0
        
        if base_range_km <= 0.1:
            return 0.005
        
        # 2. Если есть ветер — считаем дальность в 4 ключевых направлениях
        if state.windConfidence >= 30:  # ветер надёжен
            directions = [0, 90, 180, 270]  # N, E, S, W
            ranges = []
            for d in directions:
                r = self.range_with_wind(
                    base_range_km, state.windSpeed, state.windDirection,
                    d, airspeed_ms=9.0
                )
                ranges.append(r)
            
            # Берём максимальный разброс (от самой короткой до самой длинной)
            # Масштаб по ширине экрана: max(range_downwind, range_upwind) 
            # должно уместиться в половину экрана
            
            # НО: нам нужно чтобы САМАЯ КОРОТКАЯ дальность (встречный ветер)
            # была видна от центра до края, а САМАЯ ДЛИННАЯ (попутный)
            # будет дальше — её часть обрежется, но эллипс покажет куда
            
            # Используем СРЕДНЮЮ дальность как компромисс
            avg_range = sum(ranges) / len(ranges)
            # Но не меньше встречной
            upwind_range = min(ranges)  # встречный ветер — самая короткая
            downwind_range = max(ranges)  # попутный — самая длинная
            
            # Масштаб по ширине: чтобы встречная дальность была видна
            # (от центра до края = upwind_range)
            # Масштаб по высоте: аналогично, но с учётом aspect ratio
            
            # Recalculate: мы хотим чтобы ВЕСЬ эллипс помещался
            # Самый простой способ: half_viewport = max(upwind, crosswind)
            # crosswind ≈ среднее между upwind и downwind
            
            crosswind_range = (upwind_range + downwind_range) / 2.0
            
            half_w = max(upwind_range, crosswind_range)  # по ширине
            half_h = max(crosswind_range, upwind_range * 0.7)  # по высоте (эллипс вытянут по ветру)
            
        else:
            # Без ветра — симметрично
            half_w = base_range_km
            half_h = base_range_km
        
        # 3. Пересчёт в км/пиксель по минимальной оси
        half_w_px = viewport_width_px / 2
        half_h_px = viewport_height_px / 2
        
        # Масштаб должен быть достаточным чтобы ПОЛНЫЙ эллипс влез
        km_per_px_w = half_w / half_w_px
        km_per_px_h = half_h / half_h_px
        
        # Берём МИНИМАЛЬНЫЙ масштаб (чтобы всё влезло по обеим осям)
        target_km_per_px = min(km_per_px_w, km_per_px_h)
        
        # Ограничения
        target_km_per_px = max(0.001, min(target_km_per_px, 50.0))
        
        return target_km_per_px
    
    def update(self, state, world, viewport_width_px, viewport_height_px):
        """Плавное обновление зума каждый тик"""
        target = self.compute_target_km_per_px(state, viewport_width_px, viewport_height_px)
        if target is None:
            return
        
        alpha = 0.03  # ~3 сек на полное изменение
        world.km_per_px += (target - world.km_per_px) * alpha
```

**Примеры работы автомасштаба с ветром:**

| Режим | H | L/D | Ветер | Встр.дальн | Поп.дальн | Масштаб | Что на экране |
|---|---|---|---|---|---|---|---|
| Спираль | 1000м | 9 | штиль | 1.8 км | 1.8 км | 0.0033 | Круг 1.8 км |
| Спираль | 1000м | 9 | 5 м/с NW | 1.1 км | 3.8 км | 0.0038 | Эллипс вытянут на NW-SE |
| Прямая | 1200м | 10 | штиль | 10.5 км | 10.5 км | 0.019 | Круг 10.5 км |
| Прямая | 1200м | 10 | 6 м/с W | 5.6 км | 26 км | 0.019 | Эллипс W→E, 5.6 км на запад |
| Прямая | 600м | 8 | 4 м/с S | 2.1 км | 5.6 км | 0.0067 | Эллипс, 2.1 км на юг |

> **Как читать:** при ветре 4 м/с с юга на 600м пилот видит на карте 2.1 км на юг (встречный — минимум) и эллипс показывает, что на север он улетит на 5.6 км. Решение о маршруте принимается с учётом реальной достижимости.

*Условие расчёта: half_viewport_px = 540 (1080px ширина / 2), airspeed = 9.0 м/с*

### 18.4 Ручной зум (оверрайд)

Автомасштаб автоматически отключается если пилот:
1. Сделал pinch-to-zoom вручную
2. Удерживает палец на карте > 2 секунд
3. Выбрал площадку касанием

Возврат к автомасштабу через кнопку [Center] или двойной тап по планеру.

---

## 19. Airspace (воздушное пространство)

### 19.1 Формат OpenAir
```
AC D                          — класс D
AN СТРОГОЕ ВП                — имя
AH 1500ft MSL                 — верхняя граница
AL GND                        — нижняя граница
DP 55:45:00 N 37:30:00 E      — точка полигона
DP 55:45:00 N 37:45:00 E
...
```

### 19.2 Парсинг OpenAir
```python
class OpenAirParser:
    def parse(self, filepath):
        zones = []
        current = None
        
        with open(filepath) as f:
            for line in f:
                line = line.strip()
                if not line:
                    continue
                
                code = line[:2]
                data = line[2:].strip()
                
                if code == 'AC':
                    current = AirspaceZone()
                    current.airspace_class = data
                    zones.append(current)
                elif code == 'AN':
                    current.name = data
                elif code == 'AH':
                    current.alt_top = self._parse_alt(data)
                elif code == 'AL':
                    current.alt_bottom = self._parse_alt(data)
                elif code == 'DP':
                    lat, lon = self._parse_coord(data)
                    current.points.append((lat, lon))
        
        return zones
    
    def _parse_alt(self, data):
        """'1500ft MSL' → 457.2m  или '2500m MSL' → 2500m"""
        parts = data.split()
        value = float(parts[0])
        if 'ft' in data:
            value *= 0.3048
        return value  # метры
    
    def _parse_coord(self, data):
        """'55:45:00 N 37:30:00 E' → (55.75, 37.5)"""
        # DMS -> DD
        parts = data.split()
        lat_dms = parts[0]
        lat_hem = parts[1]
        lon_dms = parts[2]
        lon_hem = parts[3]
        
        lat = dms_to_dd(lat_dms) * (1 if lat_hem == 'N' else -1)
        lon = dms_to_dd(lon_dms) * (1 if lon_hem == 'E' else -1)
        return lat, lon

def dms_to_dd(dms):
    """'55:45:30' → 55.7583"""
    parts = dms.split(':')
    d = float(parts[0])
    m = float(parts[1]) / 60.0
    s = float(parts[2]) / 3600.0 if len(parts) > 2 else 0
    return d + m + s
```

### 19.3 Проверка пересечения с ВП
```python
def point_in_polygon(lat, lon, polygon):
    """Ray casting: внутри полигона?"""
    inside = False
    n = len(polygon)
    j = n - 1
    for i in range(n):
        yi, xi = polygon[i]  # (lat, lon)
        yj, xj = polygon[j]
        if ((yi > lat) != (yj > lat)) and \
           (lon < (xj - xi) * (lat - yi) / (yj - yi) + xi):
            inside = not inside
        j = i
    return inside

def check_airspace_alerts(lat, lon, alt_msl, zones):
    """Проверка всех ВП на пересечение с текущей позицией"""
    alerts = []
    for zone in zones:
        if alt_msl < zone.alt_bottom or alt_msl > zone.alt_top:
            continue
        if point_in_polygon(lat, lon, zone.points):
            alerts.append(zone)
    return alerts
```

---

## 20. Система настроек

### 20.1 Экран настроек (один экран, категоризированный)

```
┌──────────────────────────────────────────────┐
│  ⚙ НАСТРОЙКИ                                  │
├──────────────────────────────────────────────┤
│ ▶ ДИСПЛЕЙ                                     │
│  ☐ Полноэкранный режим                        │
│  Ориентация: [Авто] [Портрет] [Ландшафт]      │
│  ☐ Показывать варьо-помощник (стрелки)        │
│  ☐ Показывать эллипс долёта                   │
│  Размер InfoBox: [Маленький] [Средний] [Большой]│
│  Цвет карты: [День] [Ночь]                    │
│                                               │
│ ▶ ВАРЬО                                       │
│  ☐ Калмановский фильтр                        │
│  ☐ Стабилизация (Deadband)                    │
│  ☐ Энергетическая компенсация                 │
│  Порог звука снижения: [-3.0 м/с]             │
│  Громкость: [████████░░] 80%                  │
│                                               │
│ ▶ ПАРАПЛАН                                    │
│  Мин. скорость: [22 км/ч]                     │
│  Снижение на мин.: [0.9 м/с]                  │
│  Оптим.скорость: [36 км/ч]                    │
│  Снижение на оптим.: [1.0 м/с]                │
│  Макс.скорость: [50 км/ч]                     │
│  Снижение на макс.: [2.8 м/с]                 │
│                                               │
│ ▶ ВЫСОТОМЕР                                   │
│  QNH: [1013.25 hPa] (ручная калибровка)       │
│  Высота поля старта: [200 м]                  │
│  ☐ Калибровать при старте                     │
│                                               │
│ ▶ ЗАПИСЬ                                      │
│  Пилот: [Иван Петров]                         │
│  Тип параплана: [BGD Base 2]                  │
│  ID параплана: [BGD-12345]                    │
│  Интервал записи IGC: [4 сек]                 │
│                                               │
│ ▶ КАРТЫ                                       │
│  ☐ Автозагрузка тайлов (WiFi)                 │
│  Управление кэшем карт: [▼ 128MB]             │
│  Очистить кэш карт: [ОЧИСТИТЬ]               │
│                                               │
│ ▶ СИСТЕМА                                     │
│  Единицы: [Метрические] [Имперские]           │
│  Язык: [Русский] [English]                    │
│  ☐ Держать экран включённым                   │
│  Об авторе                                    │
│  Версия: 1.0.0                                │
└──────────────────────────────────────────────┘
```

### 20.2 Параметры (SharedPreferences)

| Ключ | Тип | Дефолт | Описание |
|---|---|---|---|
| `full_screen` | bool | false | Полный экран |
| `screen_orientation` | int | 0 | 0=auto, 1=portrait, 2=landscape |
| `show_vario_helpers` | bool | true | Стрелки квадрантов |
| `show_glide_ellipse` | bool | true | Эллипс долёта |
| `infobox_size` | int | 2 | 1=small, 2=medium, 3=large |
| `map_color_scheme` | int | 0 | 0=auto, 1=day, 2=night |
| `kalman_filter` | bool | true | Калман на варьо |
| `vario_stabilize` | bool | true | Deadband |
| `energy_compensation` | bool | true | Energy vario |
| `sink_tone` | float | -3.0 | Порог звука снижения |
| `vario_volume` | int | 80 | Громкость 0-100 |
| `glider_min_speed` | float | 22 | км/ч |
| `glider_min_sink` | float | 0.9 | м/с |
| `glider_opt_speed` | float | 36 | км/ч |
| `glider_opt_sink` | float | 1.0 | м/с |
| `glider_max_speed` | float | 50 | км/ч |
| `glider_max_sink` | float | 2.8 | м/с |
| `qnh` | float | 1013.25 | hPa |
| `field_elevation` | float | 0 | м |
| `pilot_name` | string | "" | |
| `glider_type` | string | "" | |
| `glider_id` | string | "" | |
| `igc_interval` | int | 4 | сек |
| `auto_load_tiles` | bool | true | |
| `units` | int | 0 | 0=metric, 1=imperial |
| `keep_screen_on` | bool | true | |
| `last_run_version` | string | "" | Для миграции |

### 20.3 Настройка микрораскачки
| Ключ | Тип | Дефолт | Описание |
|---|---|---|---|
| `enable_micro_detection` | bool | false | Вкл/выкл детекцию по микрораскачке |
| `micro_bpf_low_hz` | float | 0.25 | Нижняя граница полосового фильтра |
| `micro_bpf_high_hz` | float | 2.5 | Верхняя граница полосового фильтра |
| `language` | string | "ru" | Код языка: "ru", "en" |
| `units` | int | 0 | 0=metric, 1=imperial |
---

## 24. Микрораскачка (Accel-детекция термиков) + логирование датчиков

Включается чекбоксом в настройках: `enable_micro_detection`.  
При включении — дополнительно к основному термическому детектору (по спиралям):
1. Рисуются **жёлтые блипы** внутри круга термического помощника на карте
2. На диск пишется **ZIP-файл со всеми данными датчиков** (50 Гц)

### 24.1 Принцип

Микрораскачка — это турбулентность воздуха на границе термического потока.  
Параплан, влетая в край термика, начинает мелко вибрировать (0.25-2.5 Гц).  
Эти вибрации улавливаются акселерометром смартфона задолго до того, как варьо покажет подъём.

**Отношение к штатному термическому помощнику (раздел 18):**

| Режим | Штатный помощник | Микрораскачка |
|---|---|---|
| Чекбокс **OFF** | Работает (спирали, 3D кольца, стрелки квадрантов) | **Выключена** |
| Чекбокс **ON** | Работает (спирали, 3D кольца, стрелки квадрантов) | **Дополнительно** рисует жёлтые блипы |

То есть при включённом чекбоксе **поверх** стандартной отрисовки термического помощника (7 колец, ядро, стрелки квадрантов) рисуются жёлтые блипы из 24.5. При выключенном — только штатный алгоритм.

### 24.2 Accel канал — алгоритм микрораскачки

```python
class AccelThermalDetector:
    """
    Детекция термиков по акселерометру.
    Сигнал: accel X/Y в world coordinates (без гравитации).
    """
    # Пороги детекции (в g, где 1g = 9.81 м/с²)
    TH_SUSPECT = 0.015   # 0.015g — подозрение на термик
    TH_THERMAL = 0.020   # 0.020g — уверенный термик  
    TH_INSIDE  = 0.080   # 0.080g — внутри потока
    
    # Полосовой фильтр (окна термической турбулентности)
    BPF_LOW_HZ  = 0.25   # нижняя граница
    BPF_HIGH_HZ = 2.5    # верхняя граница
    
    def __init__(self):
        self.bpf_x = ButterworthBPF(order=2, low_hz=BPF_LOW_HZ, high_hz=BPF_HIGH_HZ, sample_hz=50)
        self.bpf_y = ButterworthBPF(order=2, low_hz=BPF_LOW_HZ, high_hz=BPF_HIGH_HZ, sample_hz=50)
        
        self.rms_window = []  # скользящее окно RMS (1.3 сек = 65 сэмплов)
        self.history_x = []
        self.history_y = []
        
        self.blips = []       # список активных блипов
        self.status = 'IDLE'  # IDLE | SUSPECT | THERMAL | INSIDE
        
        self.direction_buffer = []  # для atan2 усреднения
    
    def process_sample(self, ax_world_g, ay_world_g, timestamp_ms):
        """
        Подача линейного ускорения в world coordinates.
        ax_world_g: горизонтальное ускорение X (в g, без гравитации)
        ay_world_g: горизонтальное ускорение Y (в g, без гравитации)
        """
        # 1. Полосовая фильтрация
        filtered_x = self.bpf_x.filter(ax_world_g)
        filtered_y = self.bpf_y.filter(ay_world_g)
        
        # 2. RMS за 1.3 сек
        rms = math.sqrt(filtered_x**2 + filtered_y**2)
        self.rms_window.append(rms)
        if len(self.rms_window) > 65:  # 50Гц * 1.3с
            self.rms_window.pop(0)
        
        avg_rms = sum(self.rms_window) / len(self.rms_window)
        
        # 3. Определение статуса
        if avg_rms >= TH_INSIDE:
            self.status = 'INSIDE'
        elif avg_rms >= TH_THERMAL:
            self.status = 'THERMAL'
        elif avg_rms >= TH_SUSPECT:
            self.status = 'SUSPECT'
        else:
            self.status = 'IDLE'
        
        # 4. Направление на термик
        self.history_x.append(filtered_x)
        self.history_y.append(filtered_y)
        if len(self.history_x) > 65:  # 1.3с окно
            self.history_x.pop(0)
            self.history_y.pop(0)
        
        if self.status != 'IDLE':
            mean_x = sum(self.history_x) / len(self.history_x)
            mean_y = sum(self.history_y) / len(self.history_y)
            direction_deg = degrees(atan2(mean_y, mean_x))  # направление турбулентности
        else:
            direction_deg = 0
        
        # 5. Верификация: сравнение уровня за 2 периода
        # Если уровень РАСТЁТ (ratio ≥ 1.2) → CONFIRMED (летим к термику)
        # Если ПАДАЕТ (ratio ≤ 0.8) → REJECTED (улетаем)
        if len(self.rms_window) >= 30:
            first_half = sum(self.rms_window[:15]) / 15
            second_half = sum(self.rms_window[15:30]) / 15
            
            if second_half > 0 and first_half > 0:
                growth_ratio = second_half / first_half
                
                is_confirmed = growth_ratio >= 1.2
                is_rejected = growth_ratio <= 0.8
            else:
                is_confirmed = False
                is_rejected = False
        else:
            is_confirmed = False
            is_rejected = False
        
        # 6. Создание/обновление блипа
        if is_confirmed and self.status in ['THERMAL', 'INSIDE']:
            distance_m = self._estimate_distance(avg_rms)
            blip = ThermalBlip(
                direction=direction_deg,
                distance=distance_m,
                strength=min(avg_rms / TH_THERMAL, 8.0),  # 1.0 = порог, 8.0 = макс
                status=self.status,
                timestamp=timestamp_ms,
                is_confirmed=True
            )
            self.blips.append(blip)
        
        # 7. Очистка старых блипов
        self._cleanup_old_blips(timestamp_ms)
    
    def _estimate_distance(self, rms):
        """
        Эмпирическая формула: чем сильнее сигнал, тем ближе термик.
        d = 150 * sqrt(0.05 / rms)
        d ∈ [10, 150] метров
        """
        if rms <= 0:
            return 150.0
        d = 150.0 * math.sqrt(0.05 / rms)
        return max(10.0, min(150.0, d))
    
    def _cleanup_old_blips(self, now_ms):
        """Удаление блипов старше времени жизни"""
        self.blips = [b for b in self.blips if now_ms - b.timestamp < b.life_ms]
    
    def get_current_blip(self):
        """Блип для отображения (самый сильный или ближайший)"""
        if not self.blips:
            return None
        return max(self.blips, key=lambda b: b.strength)
```

### 24.3 ThermalBlip

```python
class ThermalBlip:
    """
    Блип — визуальная отметка термика на экране.
    """
    # Адаптивное время жизни
    LIFE_PROBE_MS = 3000       # пробный (не подтверждён)
    LIFE_CONFIRMED_WEAK = 8000 # подтверждённый, слабый (strength ≤ 3)
    LIFE_CONFIRMED_STRONG = 12000 # подтверждённый, сильный (strength > 3)
    LIFE_INSIDE = 15000        # внутри потока
    
    def __init__(self, direction, distance, strength, status, timestamp, is_confirmed):
        self.direction = direction    # направление от пилота (°)
        self.distance = distance      # дистанция (м)
        self.strength = strength      # сила сигнала (1.0-8.0)
        self.status = status          # SUSPECT / THERMAL / INSIDE
        self.timestamp = timestamp
        self.is_confirmed = is_confirmed
        
        # Адаптивное время жизни
        if status == 'INSIDE':
            self.life_ms = LIFE_INSIDE
        elif is_confirmed and strength > 3:
            self.life_ms = LIFE_CONFIRMED_STRONG
        elif is_confirmed:
            self.life_ms = LIFE_CONFIRMED_WEAK
        else:
            self.life_ms = LIFE_PROBE_MS
    
    def get_brightness(self, now_ms):
        """Яркость блипа в зависимости от возраста: 0.0 (угас) ... 1.0 (новый)"""
        age = now_ms - self.timestamp
        if age < self.life_ms * 0.3:
            return 1.0  # полная яркость первую треть жизни
        elif age < self.life_ms * 0.6:
            # линейный спад 1.0 → 0.3
            return 1.0 - 0.7 * (age - self.life_ms * 0.3) / (self.life_ms * 0.3)
        else:
            # линейный спад 0.3 → 0.0
            return 0.3 * (1.0 - (age - self.life_ms * 0.6) / (self.life_ms * 0.4))
    
    def get_size_px(self, base_size=20):
        """Размер блипа в пикселях: зависит от силы сигнала и расстояния"""
        # Дальние блипы мельче
        dist_factor = max(0.3, 1.0 - self.distance / 150.0)
        # Сильные блипы крупнее
        strength_factor = 0.5 + 0.5 * self.strength / 8.0
        return base_size * dist_factor * strength_factor
```

### 24.4 Butterworth BPF 2-го порядка

```python
class ButterworthBPF:
    """
    Полосовой фильтр Баттерворта 2-го порядка.
    Пропускает частоты [low_hz, high_hz].
    """
    def __init__(self, order=2, low_hz=0.25, high_hz=2.5, sample_hz=50):
        self.order = order
        self.low = low_hz
        self.high = high_hz
        self.fs = sample_hz
        
        # Инициализация состояний (для IIR)
        self.x1 = self.x2 = 0.0  # входные задержки
        self.y1 = self.y2 = 0.0  # выходные задержки
        
        # Расчёт коэффициентов (Bilinear Transform + Pre-warping)
        # Для полосового 2-го порядка:
        w0 = 2 * PI * math.sqrt(low_hz * high_hz) / sample_hz
        bw = 2 * PI * (high_hz - low_hz) / sample_hz
        
        Q = w0 / bw  # добротность
        alpha = math.sin(w0) / (2 * Q)
        
        # Коэффициенты биквада
        self.b0 = alpha
        self.b1 = 0
        self.b2 = -alpha
        self.a0 = 1 + alpha
        self.a1 = -2 * math.cos(w0)
        self.a2 = 1 - alpha
        
        # Нормализация
        self.b0 /= self.a0
        self.b1 /= self.a0
        self.b2 /= self.a0
        self.a1 /= self.a0
        self.a2 /= self.a0
    
    def filter(self, x):
        """Фильтрация одного сэмпла (прямая форма II)"""
        y = self.b0 * x + self.b1 * self.x1 + self.b2 * self.x2 \
            - self.a1 * self.y1 - self.a2 * self.y2
        
        self.x2 = self.x1
        self.x1 = x
        self.y2 = self.y1
        self.y1 = y
        
        return y
```

### 24.5 Визуализация блипов на карте

```python
def draw_micro_blips(canvas, world, pilot_lat, pilot_lon, pilot_heading, blips, now_ms):
    """
    Рисует жёлтые блипы внутри круга термического помощника.
    """
    if not blips:
        return
    
    paint = Paint()
    paint.setStyle(FILL)
    paint.setAntiAlias(True)
    
    for blip in blips:
        if now_ms - blip.timestamp > blip.life_ms:
            continue
        
        # Позиция блипа относительно пилота
        blip_angle_rad = radians(pilot_heading + blip.direction)
        blip_lat = pilot_lat + (blip.distance / 111320.0) * cos(blip_angle_rad)
        blip_lon = pilot_lon + (blip.distance / (111320.0 * cos(radians(pilot_lat)))) * sin(blip_angle_rad)
        
        # Экранные координаты
        sx, sy = world.geo_to_screen(blip_lat, blip_lon)
        
        # Яркость
        brightness = blip.get_brightness(now_ms)
        if brightness <= 0:
            continue
        
        # Цвет: янтарно-жёлтый с учётом яркости
        alpha = int(255 * brightness)
        paint.setColor(Color.argb(alpha, 255, 193, 7))  # янтарный
        
        # Размер
        size_px = blip.get_size_px()
        
        # Основной круг
        canvas.drawCircle(sx, sy, size_px, paint)
        
        # Внешнее свечение (glow) — чуть больше, полупрозрачное
        glow_paint = Paint(paint)
        glow_paint.setAlpha(alpha // 3)
        glow_paint.setStyle(STROKE)
        glow_paint.setStrokeWidth(2)
        canvas.drawCircle(sx, sy, size_px * 2, glow_paint)
        
        # Штриховая обводка для confirmed блипов
        if blip.is_confirmed:
            dash_paint = Paint(paint)
            dash_paint.setStyle(STROKE)
            dash_paint.setPathEffect(DashPathEffect([6, 4], 0))
            dash_paint.setStrokeWidth(1)
            canvas.drawCircle(sx, sy, size_px * 1.3, dash_paint)
        
        # Подпись силы
        label = f"{blip.distance:.0f}м"
        canvas.drawText(label, sx + size_px + 3, sy, label_paint)
```

### 24.6 Логирование датчиков (Companion ZIP)

При включённом чекбоксе параллельно с IGC пишется ZIP-файл со всеми данными датчиков — **50 Гц**.

```python
class SensorLogger:
    """
    Пишет CSV внутри ZIP: все сенсоры, 50 Гц (каждый сэмпл).
    Файл: ThermalGlider/logs/Flight_YYYYMMDD_HHmmss.zip → Flight_YYYYMMDD_HHmmss.csv
    """
    CSV_HEADER = (
        "dtMs,gpsSpeed,gpsHeading,gpsLat,gpsLon,gpsAlt,gpsFixAge,gpsAccuracy,"
        "vario,ax,ay,az,gx,gy,gz,mx,my,mz,pressure,pitch,roll,heading,"
        "thermalAngle,thermalStrength,thermalDist,thermalSource,snr,noiseFloor,detectStatus"
    )
    
    def __init__(self, base_dir):
        self.base_dir = base_dir
        self.zip_path = None
        self.csv_writer = None
        self.buffer = []
        self.flush_interval = 5000  # сброс на диск каждые 5с
        self.last_flush = 0
        self.start_time = 0
    
    def start_logging(self, timestamp_ms):
        """Создание ZIP-файла"""
        date_str = datetime.utcfromtimestamp(timestamp_ms / 1000).strftime("%Y%m%d_%H%M%S")
        self.zip_path = f"{self.base_dir}/logs/Flight_{date_str}.zip"
        self.csv_path = f"Flight_{date_str}.csv"
        
        self.zip_out = ZipFile(self.zip_path, 'w', ZIP_DEFLATED)
        self.csv_buffer = StringIO()
        self.csv_buffer.write(CSV_HEADER + "\n")
        self.start_time = timestamp_ms
        self.last_flush = timestamp_ms
    
    def write_sample(self, data: dict, timestamp_ms):
        """
        Запись одного сэмпла (вызывается на 50 Гц из сенсорного коллбэка).
        
        data содержит:
            gpsSpeed, gpsHeading, gpsLat, gpsLon, gpsAlt, gpsFixAge, gpsAccuracy,
            vario, ax, ay, az, gx, gy, gz, mx, my, mz, pressure, pitch, roll, heading,
            thermalAngle, thermalStrength, thermalDist, thermalSource, snr, noiseFloor, detectStatus
        """
        dt = timestamp_ms - self.start_time
        line = f"{dt},"
        line += f"{data.get('gpsSpeed', 0):.2f},{data.get('gpsHeading', 0):.1f},"
        line += f"{data.get('gpsLat', 0):.7f},{data.get('gpsLon', 0):.7f},"
        line += f"{data.get('gpsAlt', 0):.1f},{data.get('gpsFixAge', 0)},{data.get('gpsAccuracy', 0):.1f},"
        line += f"{data.get('vario', 0):.3f},"
        # Акселерометр (raw, с гравитацией, в milli-g)
        line += f"{data.get('ax', 0):.1f},{data.get('ay', 0):.1f},{data.get('az', 0):.1f},"
        # Гироскоп
        line += f"{data.get('gx', 0):.2f},{data.get('gy', 0):.2f},{data.get('gz', 0):.2f},"
        # Магнитометр
        line += f"{data.get('mx', 0):.2f},{data.get('my', 0):.2f},{data.get('mz', 0):.2f},"
        # Барометр
        line += f"{data.get('pressure', 0):.2f},"
        # Ориентация
        line += f"{data.get('pitch', 0):.1f},{data.get('roll', 0):.1f},{data.get('heading', 0):.1f},"
        # Thermal detection
        line += f"{data.get('thermalAngle', 0):.1f},{data.get('thermalStrength', 0):.3f},"
        line += f"{data.get('thermalDist', 0):.1f},{data.get('thermalSource', 'none')},"
        line += f"{data.get('snr', 0):.2f},{data.get('noiseFloor', 0):.2f},{data.get('detectStatus', 0)}"
        line += "\n"
        
        self.csv_buffer.write(line)
        
        # Периодический сброс
        if timestamp_ms - self.last_flush >= self.flush_interval:
            self.flush()
            self.last_flush = timestamp_ms
    
    def flush(self):
        """Сброс буфера в ZIP"""
        if self.csv_buffer and self.csv_buffer.tell() > 0:
            # Обновляем содержимое CSV внутри ZIP
            if self.csv_path in self.zip_out.namelist():
                self.zip_out.close()
                # Создаём новый ZIP с обновлённым CSV
                pass
            
            content = self.csv_buffer.getvalue()
            self.zip_out.writestr(self.csv_path, content)
            self.csv_buffer = StringIO()
    
    def stop_logging(self):
        """Закрытие ZIP-файла"""
        self.flush()
        self.zip_out.close()
```

### 24.7 Подача ZIP-данных при реплее

```python
def load_sensor_zip(igc_filepath):
    """
    При загрузке IGC для реплея автоматически ищет ZIP-файл.
    Если найден — подаёт реальные ax/ay в AccelThermalDetector.
    """
    zip_path = igc_filepath.replace('.igc', '.zip')
    if not os.path.exists(zip_path):
        # Fallback для _FIXED.igc
        zip_path = igc_filepath.replace('_FIXED.igc', '.zip')
        if not os.path.exists(zip_path):
            return False  # блипы из синтезированного шума
    
    with ZipFile(zip_path, 'r') as z:
        csv_filename = [f for f in z.namelist() if f.endswith('.csv')][0]
        with z.open(csv_filename) as csv_file:
            reader = csv.DictReader(io.TextIOWrapper(csv_file))
            
            for row in reader:
                ax_raw_mg = float(row['ax'])  # milli-g, with gravity
                ay_raw_mg = float(row['ay'])
                heading_deg = float(row['heading'])
                
                # World-transform: raw phone coords → gravity-free horizontal
                gravity_g = 1.0  # приблизительно 1g
                ax_linear_g = (ax_raw_mg / 1000.0) - gravity_g * sin(radians(pitch))
                ay_linear_g = (ay_raw_mg / 1000.0) - gravity_g * sin(radians(roll))
                
                # Подача в детектор (как в живом полёте)
                accel_detector.process_sample(ax_linear_g, ay_linear_g, timestamp_ms)
```

### 24.8 Включение/выключение (чекбокс в настройках)

```python
# SharedPreferences:
# enable_micro_detection = true/false

def on_settings_changed(key):
    if key == 'enable_micro_detection':
        enabled = prefs.getBoolean('enable_micro_detection', False)
        
        if enabled:
            # Включить AccelThermalDetector
            accel_detector = AccelThermalDetector()
            sensor_controller.register_accel_callback(accel_detector.process_sample)
            # Включить SensorLogger
            sensor_logger = SensorLogger(FLIGHT_DATA_DIR)
            sensor_controller.register_all_sensors_callback(sensor_logger.write_sample)
        else:
            # Выключить — только спиральный детектор (штатный)
            accel_detector = None
            sensor_logger = None
            # Стандартная детекция по GPS спиралям остаётся
```

### 24.9 Визуальная интеграция с круговым помощником

```
При включённой микрораскачке внутри круга термического помощника
(раздел 18) дополнительно отрисовываются:

  ┌─────────────────────┐
  │                     │
  │    🟡 сильный       │  — янтарный круг (strength 8)
  │       🟡            │  — средний (strength 4)
  │    🟡   🟡🟡       │  — слабые (strength 1-2)
  │          🟡         │
  │   ╱     ядро    ╲   │  — 7 колец 3D термика (штатно)
  │  ╱   (core)     ╲  │
  │  ╲              ╱   │
  │   ╲            ╱    │
  │     ──────────      │
  └─────────────────────┘

Каждый 🟡 — отдельный блип:
- Позиция на карте: смещение от пилота на distance метров в direction
- Размер: 20-60px (от слабости/силы)
- Яркость: падает с возрастом (3-15 сек)
- Штриховая обводка: confirmed (верифицирован)
- Без обводки: probe (пробный, может быть шумом)
```

### 24.10 Надёжность по дистанции

| Дистанция | Доверие | Комментарий |
|-----------|---------|-------------|
| 10-30 м | Высокое | RMS 0.5-0.8 м/с² — явный термик |
| 30-50 м | Уверенное | RMS 0.2-0.4 м/с² — выше noise floor |
| 50-75 м | Среднее | RMS 0.08-0.15 м/с² — требует верификации |
| 75-100 м | Низкое | RMS 0.05-0.08 м/с² — ~30% ложных срабатываний |
| 100-150 м | Оч. низкое | hardcap, не доверять |

**Известные ложные срабатывания:**
1. **Wind shear** — порыв ветра 1-2 м/с за 1с даёт ускорение 0.5-1 Гц (как термик)
2. **Управляющие движения** — крен, вес-шифт, торможение дают пики 0.3-0.8 Гц
3. **После пролёта** — блип "догоняет" пилота 1-2 сек (lag EMA ~250ms)

### 20.3 Landing Field DB Path
```
Путь к файлу БД: ThermalGlider/fields/landing_fields.txt
Пользователь может редактировать вручную или через приложение.
```

---

## 21. Звук

### 21.2 Ground Proximity Alert (предупреждение о земле)

Критическая функция безопасности. При низкой высоте и/или высоком снижении — генерирует предупреждающий звуковой сигнал.

```python
class GroundProximityAlert:
    """
    Три уровня опасности:
    
    GREEN  (>300m AGL) — тишина
    YELLOW (100-300m AGL) — предупреждение раз в 10 сек
    RED    (<100m AGL) — непрерывный сигнал
    SINK   (снижение >3 м/с на малой высоте) — «SINK RATE!»
    """
    
    def __init__(self):
        self.last_warning_time = 0
        self.level = 'GREEN'
    
    def evaluate(self, agl_m, vario_ms, timestamp_ms):
        if agl_m > 300:
            new_level = 'GREEN'
        elif agl_m > 100:
            new_level = 'YELLOW'
        else:
            new_level = 'RED'
        
        # Снижение >3 м/с ниже 200м — экстренный алерт
        if vario_ms < -3.0 and agl_m < 200:
            new_level = 'SINK_RATE'
        
        if new_level != self.level:
            self.level = new_level
            self._trigger_alert(new_level)
            return True
        
        # YELLOW: повтор раз в 10 секунд
        if new_level == 'YELLOW' and timestamp_ms - self.last_warning_time > 10000:
            self._trigger_alert('YELLOW_REPEAT')
            self.last_warning_time = timestamp_ms
            return True
        
        return False
    
    def _trigger_alert(self, level):
        """Генерация звукового сигнала"""
        if level == 'RED' or level == 'SINK_RATE':
            # Непрерывный высокий тон (2 кГц) + вибрация
            sound_engine.play_alert(2000, 1.0)  # частота, громкость
            vibrator.vibrate(500)
        elif level == 'YELLOW' or level == 'YELLOW_REPEAT':
            # Короткий сигнал
            sound_engine.play_alert(800, 0.5)
```

### 21.3 Тональный PCM-генератор (Vario звук)

```python
class VarioSoundGenerator:
    SAMPLE_RATE = 44100
    BUFFER_SIZE = 2048
    
    def __init__(self):
        self.audioTrack = AudioTrack(
            AudioAttributes.USAGE_GAME,
            AudioFormat.CHANNEL_OUT_MONO,
            SAMPLE_RATE,
            AudioFormat.ENCODING_PCM_16BIT,
            BUFFER_SIZE,
            AudioTrack.MODE_STREAM
        )
        self.is_playing = False
        self.last_freq = 0
        self.last_amp = 0
    
    def update(self, vario_ms):
        """
        Генерация звука варьо.
        Подъём: непрерывный тон, 400-3400 Гц
        Снижение: прерывистый тон, 200 Гц
        """
        if vario_ms > -0.3:
            # Climb or weak sink
            if vario_ms < 0:
                frequency = 400  # нейтральная зона
                amplitude = 0.05
            else:
                frequency = 400 + vario_ms * 300  # 400-3400 Гц
                amplitude = min(0.05 + vario_ms * 0.07, 0.8)
        else:
            # Strong sink
            frequency = 200
            # Прерывистый: 0.5 Гц модуляция
            phase = (current_time_ms() / 1000.0 * 0.5) % 1.0
            if phase < 0.4:
                amplitude = min(0.1 + abs(vario_ms) * 0.05, 0.4)
            else:
                amplitude = 0.0  # пауза
        
        if frequency != self.last_freq or abs(amplitude - self.last_amp) > 0.01:
            self._generate_and_play(frequency, amplitude)
            self.last_freq = frequency
            self.last_amp = amplitude
    
    def _generate_and_play(self, freq_hz, amplitude):
        """Генерация синуса и запись в AudioTrack"""
        n_samples = SAMPLE_RATE * 100 // 1000  # 100ms буфер
        buffer = bytearray(n_samples * 2)  # 16-bit mono
        
        for i in range(n_samples):
            t = i / SAMPLE_RATE
            sample = int(amplitude * 32767 * sin(2 * PI * freq_hz * t))
            buffer[i*2] = sample & 0xFF
            buffer[i*2+1] = (sample >> 8) & 0xFF
        
        self.audioTrack.write(buffer, 0, len(buffer))
    
    def start(self):
        self.audioTrack.play()
        self.is_playing = True
    
    def stop(self):
        self.audioTrack.stop()
        self.is_playing = False
```

---

## 22. Внешние датчики

### 22.1 BlueFlyVario (Bluetooth SPP)
```python
class BlueFlyDevice:
    SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"
    
    def connect(self, address):
        socket = BluetoothSocket(RFCOMM)
        socket.connect(BluetoothDevice(address), SPP_UUID)
        self.input_stream = socket.inputStream()
        # Поток чтения
        Thread(target=self._read_loop).start()
    
    def _read_loop(self):
        reader = BufferedReader(InputStreamReader(self.input_stream))
        while True:
            line = reader.readLine()
            if line.startswith("$BFV"):
                parts = line.split(",")
                if len(parts) >= 5:
                    altitude = float(parts[1])
                    vario = float(parts[2])
                    pressure = float(parts[3]) if parts[3] else 0
                    # Подача в FlightManager
                    flightManager.onExternalSensor(altitude, vario, pressure)
```

### 22.2 NMEA датчики
```python
def parse_nmea(line):
    """$WIMDA,29.9874,I,1.0156,B,19.8,C,,,4.6,M,2.0,M,2.1,M,,,,"""
    if line.startswith("$WIMDA"):
        parts = line.split(",")
        pressure_hg = float(parts[1])  # дюймы рт.ст.
        pressure_hpa = pressure_hg * 33.8639
        return BarometricSample(pressure=pressure_hpa, temperature=float(parts[5]))
    return None
```

---

## 23. Энергосбережение и обработка ошибок

### 23.1 Энергосбережение (Screen Power Management)

Парапланерный полёт длится 1-6 часов. Экран телефона — основной потребитель батареи.

```python
class PowerManager:
    """
    Три уровня яркости + авто-возврат.
    """
    # Таймауты без движения (мс)
    TIMEOUT_DIM_MS   = 60000   # 1 мин → тусклый
    TIMEOUT_OFF_MS   = 300000  # 5 мин → экран выкл (только звук)
    
    # Яркость (0.0 - 1.0)
    BRIGHTNESS_NORMAL = 0.8
    BRIGHTNESS_DIM    = 0.2
    BRIGHTNESS_MIN    = 0.05
    
    def __init__(self, activity):
        self.activity = activity
        self.last_interaction = current_time_ms()
        self.mode = 'NORMAL'
        self.is_circling = False
    
    def on_user_interaction(self):
        """Любое касание экрана — возврат полной яркости"""
        self.last_interaction = current_time_ms()
        if self.mode != 'NORMAL':
            self._set_brightness(BRIGHTNESS_NORMAL)
            self.mode = 'NORMAL'
    
    def on_tick(self, is_circling, timestamp_ms):
        """
        Вызывается каждый тик (100ms).
        В спирали экран не гасим (пилот смотрит на термики).
        """
        self.is_circling = is_circling
        
        if is_circling:
            self.last_interaction = timestamp_ms  # продлеваем
            if self.mode != 'NORMAL':
                self._set_brightness(BRIGHTNESS_NORMAL)
                self.mode = 'NORMAL'
            return
        
        idle_ms = timestamp_ms - self.last_interaction
        
        if idle_ms > TIMEOUT_OFF_MS and self.mode == 'DIM':
            self._set_brightness(BRIGHTNESS_MIN)
            self.mode = 'OFF'
        elif idle_ms > TIMEOUT_DIM_MS and self.mode == 'NORMAL':
            self._set_brightness(BRIGHTNESS_DIM)
            self.mode = 'DIM'
    
    def _set_brightness(self, level):
        """Установка яркости экрана (Android WindowManager)"""
        lp = self.activity.getWindow().getAttributes()
        lp.screenBrightness = level
        self.activity.getWindow().setAttributes(lp)

# В настройках: screen_power_mode
# 0 = Normal (тускнет через 1мин, выкл через 5мин)
# 1 = Always on (не гасить)
# 2 = Dim only (тускнет, но не выключать)
```

### 23.2 Обработка ошибок и индикация состояния

Прибор должен явно показывать пользователю состояние каждого датчика. Никаких "молчаливых" отказов.

```python
class SystemStatus:
    """
    Единый статус системы для статус-бара.
    Показывает пилоту что работает, а что нет.
    """
    def __init__(self):
        self.gps_fix = False
        self.gps_satellites = 0
        self.gps_accuracy = 999.0
        
        self.barometer_available = False
        self.barometer_calibrated = False
        
        self.flight_recording = False
        self.sensor_logging = False  # ZIP лог (чекбокс)
        
        self.map_tiles_loaded = False
        self.airspaces_loaded = 0
        
        self.landing_fields_available = False
        
        self.battery_percent = 0
        self.battery_charging = False
        
        self.elapsed_flight_time = 0
    
    def get_status_bar_text(self):
        """Формирует строку статус-бара"""
        parts = []
        
        # GPS
        if self.gps_fix:
            parts.append(f"🛰 {self.gps_satellites}")
        else:
            parts.append("🛰 NO GPS")  # красный
        
        # Запись
        if self.flight_recording:
            parts.append("🔴 REC")
        
        # Барометр
        if not self.barometer_available:
            parts.append("⚠ BARO")  # жёлтый
        elif not self.barometer_calibrated:
            parts.append("⚠ QNH")  # жёлтый
        
        # Батарея
        if self.battery_percent < 15 and not self.battery_charging:
            parts.append(f"🔋 {self.battery_percent}%")  # красный
        elif self.battery_percent < 30:
            parts.append(f"🔋 {self.battery_percent}%")  # жёлтый
        
        return " | ".join(parts) if parts else "OK"
    
    def get_status_color(self):
        """Цвет статус-бара (зелёный/жёлтый/красный)"""
        if not self.gps_fix:
            return 'RED'
        if self.battery_percent < 15:
            return 'RED'
        if not self.barometer_available:
            return 'YELLOW'
        return 'GREEN'

# Состояния ошибок и поведение прибора:

ERROR_STATES = {
    'NO_GPS': {
        'condition': lambda s: not s.gps_fix and s.elapsed_flight_time > 30,
        'action': 'В статус-баре красная надпись "NO GPS". '
                  'Vario показывает 0. Эллипс долёта не рисуется.',
        'recovery': 'При появлении GPS fix — автоматически восстановить работу.'
    },
    'GPS_ACCURACY_LOW': {
        'condition': lambda s: s.gps_accuracy > 50,
        'action': 'Жёлтая надпись "GPS LOW". Vario фильтруется сильнее.',
        'recovery': 'Accuracy < 30м → нормальный режим.'
    },
    'NO_BAROMETER': {
        'condition': lambda s: not s.barometer_available,
        'action': 'Жёлтая надпись "BARO". Vario из GPS-производной. '
                  'AGL не показывается.',
        'recovery': 'При появлении данных барометра — переключиться на барометрический vario.'
    },
    'STORAGE_FULL': {
        'condition': lambda s: s.storage_free_mb < 50,
        'action': 'Красная надпись "STORAGE FULL". IGC-запись останавливается.',
        'recovery': 'Освободить место на SD-карте.'
    },
    'NO_MAP_TILES': {
        'condition': lambda s: not s.map_tiles_loaded,
        'action': 'Карта показывает пустой фон (чёрный). '
                  'Трек, площадки, термики — рисуются поверх.',
        'recovery': 'При загрузке хотя бы одного тайла — показать карту.'
    },
    'LOW_BATTERY': {
        'condition': lambda s: s.battery_percent < 15 and not s.battery_charging,
        'action': 'Красная надпись "BATTERY 14%". '
                  'Автоматически уменьшить яркость. '
                  'Отключить ZIP-логирование датчиков.',
        'recovery': 'При подключении зарядки — вернуть полную яркость.'
    }
}
```

---

## 24. Хранение данных

### 24.1 Структура каталогов (полный доступ с ПК)
```
ThermalGlider/              ← /sdcard/ThermalGlider/
  igc/                      ← IGC треки (видно через USB)
    2026-06-27_143022.igc
    2026-06-20_091512.igc
  maps/                     ← Кэш карт (видно через USB)
    cache/
      osm/{z}/{x}_{y}.png
      satellite/{z}/{x}_{y}.png
  fields/                   ← База площадок (можно редактировать с ПК)
    landing_fields.txt      ← Пользовательские (txt, редактируется блокнотом)
    default_fields.txt      ← Встроенные
  config/                   ← Настройки
    settings.xml            ← SharedPreferences
    layout.json             ← Раскладка виджетов (9 штук, настройка long-tap)
  waypoints/                ← Точки пути
    my_waypoints.cup        ← SeeYou формат (.cup)
```

### 24.2 Путь к данным
```python
DATA_DIR = Environment.getExternalStorageDirectory() + "/ThermalGlider/"
def get_data_path(subdir):
    return DATA_DIR + subdir + "/"
```

---

## 25. Локализация (i18n)

### 25.1 Файлы перевода

Все строки интерфейса вынесены в JSON-файлы по языкам. Файлы находятся в открытой файловой системе и могут быть отредактированы пользователем.

```
ThermalGlider/i18n/
  ru.json        ← русский (по умолчанию)
  en.json        ← английский
```

### 25.2 Формат файла перевода

```json
{
  "app_name": "ThermalGlider",
  "menu": {
    "settings": "Настройки",
    "about": "О программе",
    "exit": "Выход"
  },
  "status": {
    "gps_ok": "🛰 {satellites}",
    "gps_no": "🛰 NO GPS",
    "recording": "🔴 REC",
    "baro_missing": "⚠ BARO",
    "qnh_uncalibrated": "⚠ QNH",
    "storage_full": "⚠ STORAGE"
  },
  "widgets": {
    "vario": "Vario",
    "altitude": "Высота",
    "speed": "Скорость",
    "heading": "Курс",
    "ld": "L/D",
    "wind": "Ветер",
    "agl": "AGL",
    "distance": "Дистанция",
    "time": "Время",
    "max_alt": "Макс",
    "total_climb": "Набор",
    "avg_ld": "Ср.L/D",
    "avg_climb": "Ср.подъём",
    "glide_to_goal": "Глисс.",
    "goal_dist": "До цели",
    "goal_time": "До цели",
    "ground_elev": "Рельеф",
    "near_thermal": "Термик",
    "speed_circle": "Крутка",
    "airspace": "ВП",
    "airspace_info": "ВП дет."
  },
  "units": {
    "ms": "м/с",
    "kmh": "км/ч",
    "m": "м",
    "km": "км",
    "deg": "°",
    "ft": "фт",
    "kt": "уз",
    "ftmin": "фт/мин",
    "mi": "мили",
    "hpa": "гПа"
  },
  "settings": {
    "title": "Настройки",
    "display": "Дисплей",
    "full_screen": "Полноэкранный режим",
    "orientation": "Ориентация",
    "show_vario_helpers": "Варьо-помощник",
    "show_glide_ellipse": "Эллипс долёта",
    "infobox_size": "Размер боксов",
    "vario": "Варьо",
    "kalman_filter": "Калмановский фильтр",
    "vario_stabilize": "Стабилизация",
    "energy_compensation": "Энерг.компенсация",
    "sink_tone": "Порог снижения",
    "volume": "Громкость",
    "glider": "Параплан",
    "min_speed": "Мин.скорость",
    "qnh": "QNH",
    "recording": "Запись",
    "pilot_name": "Пилот",
    "glider_type": "Тип параплана",
    "micro_detection": "Микрораскачка",
    "language": "Язык"
  },
  "thermal_helpers": {
    "climb": "↑{value}",
    "sink": "↓{value}",
    "avg_climb": "ср {value}",
    "thermal_near": "ТЕРМИК РЯДОМ — {dist}м",
    "no_thermal": "—",
    "searching": "ПОИСК",
    "circling": "СПИРАЛЬ",
    "inside": "ВНУТРИ"
  },
  "errors": {
    "no_gps": "NO GPS",
    "gps_low": "GPS LOW",
    "no_baro": "BARO",
    "storage_full": "STORAGE FULL",
    "low_battery": "BATTERY {percent}%"
  },
  "alerts": {
    "ground_alert": "ЗЕМЛЯ!",
    "sink_rate": "СНИЖЕНИЕ!"
  }
}
```

### 25.3 Механизм переключения

```python
class I18n:
    """Загрузка и кэширование переводов."""
    
    I18N_DIR = "ThermalGlider/i18n/"
    SUPPORTED_LANGUAGES = ["ru", "en"]
    
    def __init__(self, language_code="ru"):
        self.lang = language_code
        self.strings = {}
        self._load()
    
    def _load(self):
        """Загрузка JSON-файла перевода."""
        path = f"{I18N_DIR}{self.lang}.json"
        try:
            with open(path, 'r') as f:
                self.strings = json.load(f)
        except FileNotFoundError:
            # Fallback на русский
            with open(f"{I18N_DIR}ru.json", 'r') as f:
                self.strings = json.load(f)
    
    def get(self, *keys, default=""):
        """
        Получение строки по ключу.
        i18n.get("widgets", "vario") → "Vario"
        i18n.get("status", "gps_ok", satellites=7) → "🛰 7"
        """
        value = self.strings
        for key in keys:
            if isinstance(value, dict) and key in value:
                value = value[key]
            else:
                return default
        return value
    
    def format(self, key, **kwargs):
        """Форматирование строки с подстановками."""
        template = self.get(*key.split("."))
        return template.format(**kwargs)
    
    def switch_to(self, new_lang):
        """Смена языка в рантайме (из настроек)."""
        if new_lang in SUPPORTED_LANGUAGES and new_lang != self.lang:
            self.lang = new_lang
            self._load()
            # Уведомить UI о необходимости перерисовки

# Глобальный экземпляр
i18n = I18n(prefs.getString("language", "ru"))
```

### 25.4 Использование в коде

```python
# Вместо хардкода:
text = "Vario"

# Используем:
text = i18n.get("widgets", "vario")  # → "Vario" / "Варьо"

# С подстановками:
status = i18n.format("status.gps_ok", satellites=7)  # → "🛰 7"

# Единицы измерения:
units = i18n.get("units", "kmh")  # → "км/ч" / "km/h"
```

```python
import math

R_EARTH = 6371.0  # км

def to_rad(deg):
    return deg * math.pi / 180.0

def to_deg(rad):
    return rad * 180.0 / math.pi

def haversine_km(lat1, lon1, lat2, lon2):
    """Расстояние между двумя точками на сфере (км)"""
    dlat = to_rad(lat2 - lat1)
    dlon = to_rad(lon2 - lon1)
    a = math.sin(dlat/2)**2 + \
        math.cos(to_rad(lat1)) * math.cos(to_rad(lat2)) * math.sin(dlon/2)**2
    c = 2 * math.atan2(math.sqrt(a), math.sqrt(1-a))
    return R_EARTH * c

def bearing_deg(lat1, lon1, lat2, lon2):
    """Начальный пеленг от точки 1 к точке 2 (градусы от N)"""
    dlon = to_rad(lon2 - lon1)
    y = math.sin(dlon) * math.cos(to_rad(lat2))
    x = math.cos(to_rad(lat1)) * math.sin(to_rad(lat2)) - \
        math.sin(to_rad(lat1)) * math.cos(to_rad(lat2)) * math.cos(dlon)
    return (to_deg(math.atan2(y, x)) + 360) % 360

def cross_track_error(lat, lon, lat1, lon1, lat2, lon2):
    """Поперечная ошибка (отклонение от линии пути) в км"""
    d13 = haversine_km(lat, lon, lat1, lon1) / R_EARTH
    theta13 = to_rad(bearing_deg(lat1, lon1, lat, lon))
    theta12 = to_rad(bearing_deg(lat1, lon1, lat2, lon2))
    return math.asin(math.sin(d13) * math.sin(theta13 - theta12)) * R_EARTH

def meters_to_feet(m):
    return m / 0.3048

def feet_to_meters(ft):
    return ft * 0.3048

def kmh_to_ms(kmh):
    return kmh / 3.6

def ms_to_kmh(ms):
    return ms * 3.6

def knots_to_ms(kts):
    return kts * 0.514444

def ms_to_knots(ms):
    return ms / 0.514444

def vector_average(ws1, wd1, ws2, wd2, alpha=0.3):
    """Среднее двух ветровых векторов"""
    x1 = ws1 * math.sin(to_rad(wd1))
    y1 = ws1 * math.cos(to_rad(wd1))
    x2 = ws2 * math.sin(to_rad(wd2))
    y2 = ws2 * math.cos(to_rad(wd2))
    x = (1-alpha)*x1 + alpha*x2
    y = (1-alpha)*y1 + alpha*y2
    speed = math.sqrt(x*x + y*y)
    dir = (to_deg(math.atan2(x, y)) + 360) % 360
    return speed, dir

def wind_dir_text(deg):
    """Градусы → текст ('N', 'NE', ...)"""
    dirs = ['N', 'NNE', 'NE', 'ENE', 'E', 'ESE', 'SE', 'SSE',
            'S', 'SSW', 'SW', 'WSW', 'W', 'WNW', 'NW', 'NNW']
    idx = int((deg + 11.25) / 22.5) % 16
    return dirs[idx]
```

---

## Приложение B: Ключевые константы

| Константа | Значение | Использование |
|---|---|---|
| `VIEWPORT_WIDTH` | 1080 (тип.) | Ширина экрана |
| `VIEWPORT_HEIGHT` | 1920 (тип.) | Высота экрана |
| `TICK_INTERVAL_MS` | 100 | Интервал главного тика |
| `GPS_INTERVAL_MS` | 1000 | Интервал GPS (1 сек) |
| `IGC_INTERVAL_MS` | 4000 | Интервал IGC (4 сек) |
| `CIRCLING_RATE_THRESHOLD` | 30 °/с | Порог определения спирали |
| `CIRCLING_MIN_POINTS` | 6 | Мин. точек в спирали |
| `GLIDE_ELLIPSE_N_POINTS` | 36 | Точность эллипса |
| `MAX_LANDING_FIELDS` | 10 | Макс. отображаемых площадок |
| `VARIO_DEADBAND_MPS` | 0.05 | Зона нечувствительности |
| `STALE_TIMEOUT_MS` | 5000 | Таймаут данных (показ "---") |
| `MIN_FLYING_SPEED_MPS` | 3.0 | Мин. скорость для детекции полёта |
| `MIN_FLYING_ALT_M` | 50 | Мин. высота AGL для полёта |
| `LANDING_DETECT_TIME_MS` | 30000 | Пауза на земле для остановки |
| `AUTOCENTER_MARGIN_PX` | 100 | Отступ от края для автовозврата |

---

*Документ создан на основе анализа FlyMe 3.14 Beta (package: com.xcglobe.flyme)*
*Адаптировано для прибора ThermalGlider — пилот выходного дня*
*Дата: 27 Июня 2026*
