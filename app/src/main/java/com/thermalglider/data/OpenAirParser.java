package com.thermalglider.data;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAirParser — парсинг воздушного пространства в формате OpenAir.
 *
 * Раздел 19 ТЗ.
 */
public class OpenAirParser {

    public static class AirspaceZone {
        public String name = "";
        public String airspaceClass = "";
        public float altTopM = 0;
        public float altBottomM = 0;
        public List<double[]> points = new ArrayList<>(); // (lat, lon)
    }

    private final List<AirspaceZone> zones = new ArrayList<>();
    private int loadCount = 0;

    /** Загрузка из файла */
    public int parse(String filepath) {
        zones.clear();
        File f = new File(filepath);
        if (!f.exists()) return 0;

        try {
            BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
            String line;
            AirspaceZone current = null;

            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                String code = line.length() >= 2 ? line.substring(0, 2) : "";
                String data = line.length() > 2 ? line.substring(2).trim() : "";

                switch (code) {
                    case "AC":
                        current = new AirspaceZone();
                        current.airspaceClass = data;
                        zones.add(current);
                        break;
                    case "AN":
                        if (current != null) current.name = data;
                        break;
                    case "AH":
                        if (current != null) current.altTopM = parseAlt(data);
                        break;
                    case "AL":
                        if (current != null) current.altBottomM = parseAlt(data);
                        break;
                    case "DP":
                        if (current != null) {
                            double[] coord = parseCoord(data);
                            if (coord != null) current.points.add(coord);
                        }
                        break;
                }
            }
            br.close();
            loadCount = zones.size();
        } catch (Exception e) {
            loadCount = 0;
        }
        return loadCount;
    }

    private float parseAlt(String data) {
        String[] parts = data.split("\\s+");
        try {
            float value = Float.parseFloat(parts[0]);
            if (data.contains("ft") || data.contains("FL")) {
                value *= 0.3048f; // футы → метры
            } else if (data.contains("m")) {
                // уже метры
            }
            return value;
        } catch (Exception e) {
            return 0;
        }
    }

    private double[] parseCoord(String data) {
        // "55:45:00 N 37:30:00 E"
        try {
            String[] parts = data.split("\\s+");
            if (parts.length < 4) return null;

            double lat = dmsToDd(parts[0]);
            if (parts[1].equals("S")) lat = -lat;

            double lon = dmsToDd(parts[2]);
            if (parts[3].equals("W")) lon = -lon;

            return new double[]{lat, lon};
        } catch (Exception e) {
            return null;
        }
    }

    private double dmsToDd(String dms) {
        String[] parts = dms.split(":");
        double d = Double.parseDouble(parts[0]);
        double m = Double.parseDouble(parts[1]) / 60.0;
        double s = parts.length > 2 ? Double.parseDouble(parts[2]) / 3600.0 : 0;
        return d + m + s;
    }

    /** Проверка: точка внутри полигона (ray casting) */
    public static boolean pointInPolygon(double lat, double lon, List<double[]> polygon) {
        boolean inside = false;
        int n = polygon.size();
        int j = n - 1;
        for (int i = 0; i < n; i++) {
            double yi = polygon.get(i)[0]; // lat
            double xi = polygon.get(i)[1]; // lon
            double yj = polygon.get(j)[0];
            double xj = polygon.get(j)[1];
            if ((yi > lat) != (yj > lat) &&
                lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) {
                inside = !inside;
            }
            j = i;
        }
        return inside;
    }

    /** Проверка всех ВП на пересечение */
    public List<AirspaceZone> checkAlerts(double lat, double lon, float altMsl) {
        List<AirspaceZone> alerts = new ArrayList<>();
        for (AirspaceZone zone : zones) {
            if (altMsl < zone.altBottomM || altMsl > zone.altTopM) continue;
            if (pointInPolygon(lat, lon, zone.points)) {
                alerts.add(zone);
            }
        }
        return alerts;
    }

    public int getZoneCount() { return loadCount; }
    public List<AirspaceZone> getZones() { return zones; }
}
