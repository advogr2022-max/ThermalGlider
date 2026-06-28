package com.thermalglider.data;

/**
 * SystemStatus — статус системы для статус-бара.
 * Показывает пилоту что работает, а что нет.
 *
 * Раздел 23.2 ТЗ.
 */
public class SystemStatus {

    public boolean gpsFix;
    public int gpsSatellites;
    public float gpsAccuracy = 999;

    public boolean barometerAvailable;
    public boolean barometerCalibrated;

    public boolean flightRecording;
    public boolean sensorLogging;

    public boolean mapTilesLoaded;
    public int airspacesLoaded;

    public boolean landingFieldsAvailable;

    public int batteryPercent;
    public boolean batteryCharging;

    public long elapsedFlightTimeSec;

    // Уровень: GREEN / YELLOW / RED
    public String statusColor = "GREEN";

    /** Формирует строку статус-бара */
    public String getStatusBarText() {
        StringBuilder sb = new StringBuilder();

        if (gpsFix) {
            sb.append("\uD83D\uDEF0 ").append(gpsSatellites);
        } else {
            sb.append("\uD83D\uDEF0 NO GPS");
        }

        if (flightRecording) {
            sb.append("  \uD83D\uDD34 REC");
        }

        if (!barometerAvailable) {
            sb.append("  \u26A0 BARO");
        } else if (!barometerCalibrated) {
            sb.append("  \u26A0 QNH");
        }

        if (batteryPercent < 15 && !batteryCharging) {
            sb.append("  \uD83D\uDD0B ").append(batteryPercent).append("%");
        } else if (batteryPercent < 30) {
            sb.append("  \uD83D\uDD0B ").append(batteryPercent).append("%");
        }

        return sb.toString();
    }

    /** Цвет статус-бара */
    public String evaluateColor() {
        if (!gpsFix) return "RED";
        if (batteryPercent < 15 && !batteryCharging) return "RED";
        if (!barometerAvailable) return "YELLOW";
        return "GREEN";
    }

    public void update(FlightState state) {
        this.gpsFix = state.hasGpsFix;
        this.gpsSatellites = state.satelliteCount;
        this.gpsAccuracy = state.gpsAccuracy;
        this.batteryPercent = state.batteryPercent;
        this.batteryCharging = state.batteryCharging;
        this.flightRecording = state.isRecording;
        this.elapsedFlightTimeSec = state.flightDurationSec;
        this.statusColor = evaluateColor();
    }
}
