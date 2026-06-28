package com.thermalglider.engine;

import com.thermalglider.data.FlightState;
import com.thermalglider.data.LandingField;
import com.thermalglider.data.LandingFieldDB;
import com.thermalglider.data.NearThermal;
import com.thermalglider.data.ThermalSector;
import com.thermalglider.vario.VarioEngine;
import com.thermalglider.wind.WindStore;

/**
 * FlightManager — оркестратор всех расчётов.
 * Вызывается из BackgroundService.tick() каждые 100ms.
 *
 * Раздел 1.2 ТЗ.
 */
public class FlightManager {

    // Компоненты
    private final ThermalDetector thermalDetector = new ThermalDetector();
    private final AccelThermalDetector accelDetector = new AccelThermalDetector();
    private final WindEstimator windEstimator = new WindEstimator();
    private final GlideComputer glideComputer = new GlideComputer();
    private final NearThermalFinder thermalFinder = new NearThermalFinder();
    private final AutoZoomController autoZoom = new AutoZoomController();
    private final WindStore windStore = new WindStore();

    private LandingFieldDB fieldDB;

    // Трекинг последних данных для детекции
    private boolean wasCircling = false;
    private long lastThermalAddMs = 0;

    public FlightManager() {}

    public void setFieldDB(LandingFieldDB db) {
        this.fieldDB = db;
    }

    /**
     * Главный тик — вызывается из BackgroundService каждые 100ms.
     *
     * @param state  FlightState (читает GPS, записывает расчёты)
     * @param nowMs  текущее время
     * @param viewW  ширина view (px) для AutoZoom
     * @param viewH  высота view (px) для AutoZoom
     */
    public void tick(FlightState state, long nowMs, int viewW, int viewH) {
        if (!state.hasGpsFix) return;

        // 1. ThermalDetector (спирали + квадранты)
        thermalDetector.update(state, nowMs);

        // 2. Фиксация термика при выходе из спирали
        if (wasCircling && !state.isCircling && state.thermalSector != null) {
            ThermalSector ts = state.thermalSector;
            if (ts.pointCount >= 6 && ts.avgLift > 0.3f) {
                thermalFinder.addThermal(ts.centerLat, ts.centerLon, ts.avgLift, nowMs);
                lastThermalAddMs = nowMs;
            }
        }
        wasCircling = state.isCircling;

        // 3. WindEstimator
        windEstimator.update(state, nowMs);

        // 4. GlideComputer (L/D + эллипс)
        glideComputer.update(state, nowMs);

        // 5. NearThermalFinder
        NearThermal nearest = thermalFinder.findNearest(
            state.latitude, state.longitude, 0.3f);
        state.nearestThermal = nearest;

        // 6. AutoZoom
        autoZoom.update(state, viewW, viewH);
        state.currentKmPerPx = autoZoom.isAutoZoomEnabled() ?
            state.currentKmPerPx : state.currentKmPerPx;

        // 7. Landing fields (если есть БД)
        if (fieldDB != null) {
            fieldDB.computeReachable(state);
            state.reachableFields = fieldDB.getReachableFields();
        }

        // 8. WindStore (для спирального дрейфа)
        if (state.windSpeed > 0 && state.windDirection >= 0) {
            windStore.addMeasurement(state.windDirection, state.windSpeed, nowMs);
        }

        // 9. Спутниковый режим: при входе в спираль
        state.satelliteMode = state.isCircling;

        // 10. Последнее обновление
        state.lastUpdateMs = nowMs;
    }

    public ThermalDetector getThermalDetector() { return thermalDetector; }
    public AccelThermalDetector getAccelDetector() { return accelDetector; }
    public WindEstimator getWindEstimator() { return windEstimator; }
    public GlideComputer getGlideComputer() { return glideComputer; }
    public NearThermalFinder getThermalFinder() { return thermalFinder; }
    public AutoZoomController getAutoZoom() { return autoZoom; }
    public WindStore getWindStore() { return windStore; }

    public void reset() {
        thermalDetector.reset();
        accelDetector.reset();
        windEstimator.reset();
        glideComputer.reset();
        thermalFinder.reset();
        autoZoom.reset();
        windStore.reset();
        wasCircling = false;
    }
}
