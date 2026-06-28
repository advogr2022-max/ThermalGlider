package com.thermalglider.data;

import com.thermalglider.util.GeoUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * LandingFieldDB — база безопасных посадочных площадок.
 * Загружает из TXT-файла, рассчитывает достижимость с учётом ветра.
 *
 * Раздел 11 ТЗ.
 */
public class LandingFieldDB {

    private static final String FIELDS_DIR = "ThermalGlider/fields/";
    private static final String USER_FIELDS = "landing_fields.txt";
    private static final String DEFAULT_FIELDS = "default_fields.txt";

    private final List<LandingField> allFields = new ArrayList<>();
    private final List<LandingField> reachableFields = new ArrayList<>();
    private String basePath;

    // Параметры параплана
    private float airspeedMs = 9.0f; // типичная скорость параплана

    public void init(String basePath) {
        this.basePath = basePath;
        loadFields(DEFAULT_FIELDS);
        loadFields(USER_FIELDS);
    }

    private void loadFields(String filename) {
        String path = basePath + "/" + FIELDS_DIR + filename;
        File f = new File(path);
        if (!f.exists()) return;

        try {
            BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split(",");
                if (parts.length >= 5) {
                    try {
                        LandingField field = new LandingField(
                            parts[0].trim(),
                            Double.parseDouble(parts[1].trim()),
                            Double.parseDouble(parts[2].trim()),
                            Float.parseFloat(parts[3].trim()),
                            Integer.parseInt(parts[4].trim()),
                            parts.length > 5 && Integer.parseInt(parts[5].trim()) != 0
                        );
                        allFields.add(field);
                    } catch (NumberFormatException ignored) {}
                }
            }
            br.close();
        } catch (Exception e) {
            // Файла нет — не критично
        }
    }

    /**
     * Расчёт достижимых площадок с учётом ветра.
     * Вызывается каждый тик (100ms).
     */
    public void computeReachable(FlightState state) {
        reachableFields.clear();

        if (state.altitudeAGL <= 0 || state.glideRatio <= 0) return;

        float maxRangeM = state.altitudeAGL * state.glideRatio * 1.2f; // запас 20%
        if (maxRangeM <= 100) return;

        double pilotLat = state.latitude;
        double pilotLon = state.longitude;
        float windSpeed = state.windSpeed;
        float windDir = state.windDirection;

        for (LandingField field : allFields) {
            double distM = GeoUtils.haversineM(pilotLat, pilotLon, field.centerLat, field.centerLon);
            double bearing = GeoUtils.bearingDeg(pilotLat, pilotLon, field.centerLat, field.centerLon);

            // Учёт ветра
            float windDirTo = (windDir + 180) % 360;
            double windAngle = Math.toRadians(bearing - windDirTo);
            double windComp = windSpeed * Math.cos(windAngle);
            double gndSpeed = airspeedMs + windComp;

            if (gndSpeed <= 0) continue;

            double glideTime = distM / gndSpeed;
            float heightLoss = (float) (glideTime * Math.abs(state.sinkRate > 0 ? state.sinkRate : 1.0f));
            float altAtField = state.altitudeAGL - heightLoss;
            float safeHeight = Math.max(state.altitudeAGL * 0.1f, 50f);

            field.distanceKm = (float) (distM / 1000.0);
            field.bearingDeg = (float) bearing;
            field.heightAboveM = altAtField;
            field.isReachable = altAtField > safeHeight;

            if (field.isReachable && distM <= maxRangeM) {
                reachableFields.add(field);
            }
        }

        // Сортировка по расстоянию
        Collections.sort(reachableFields, new Comparator<LandingField>() {
            @Override
            public int compare(LandingField a, LandingField b) {
                return Float.compare(a.distanceKm, b.distanceKm);
            }
        });

        // Топ-10
        while (reachableFields.size() > 10) {
            reachableFields.remove(reachableFields.size() - 1);
        }
    }

    public List<LandingField> getAllFields() { return allFields; }
    public List<LandingField> getReachableFields() { return reachableFields; }
    public int getFieldCount() { return allFields.size(); }
}
