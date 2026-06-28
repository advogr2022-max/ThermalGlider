package com.thermalglider.util;

/**
 * GeoUtils — географические расчёты.
 * haversine, bearing, DMS, проекция.
 *
 * Раздел 25 (приложение) ТЗ.
 */
public class GeoUtils {

    private static final double R_EARTH_KM = 6371.0;
    private static final double DEG_TO_RAD = Math.PI / 180.0;
    private static final double RAD_TO_DEG = 180.0 / Math.PI;

    /** Расстояние между двумя точками (км) */
    public static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dlat = (lat2 - lat1) * DEG_TO_RAD;
        double dlon = (lon2 - lon1) * DEG_TO_RAD;
        double a = Math.sin(dlat / 2) * Math.sin(dlat / 2) +
                   Math.cos(lat1 * DEG_TO_RAD) * Math.cos(lat2 * DEG_TO_RAD) *
                   Math.sin(dlon / 2) * Math.sin(dlon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R_EARTH_KM * c;
    }

    /** Расстояние между двумя точками (м) */
    public static double haversineM(double lat1, double lon1, double lat2, double lon2) {
        return haversineKm(lat1, lon1, lat2, lon2) * 1000.0;
    }

    /** Начальный пеленг от точки 1 к точке 2 (° от N) */
    public static double bearingDeg(double lat1, double lon1, double lat2, double lon2) {
        double dlon = (lon2 - lon1) * DEG_TO_RAD;
        double y = Math.sin(dlon) * Math.cos(lat2 * DEG_TO_RAD);
        double x = Math.cos(lat1 * DEG_TO_RAD) * Math.sin(lat2 * DEG_TO_RAD) -
                   Math.sin(lat1 * DEG_TO_RAD) * Math.cos(lat2 * DEG_TO_RAD) * Math.cos(dlon);
        double brg = Math.atan2(y, x) * RAD_TO_DEG;
        return (brg + 360) % 360;
    }

    /** Смещение точки на distance метров в направлении bearing (°) */
    public static double[] offsetPoint(double lat, double lon, double distanceM, double bearingDeg) {
        double distKm = distanceM / 1000.0;
        double brgRad = bearingDeg * DEG_TO_RAD;
        double latRad = lat * DEG_TO_RAD;
        double lat2 = Math.asin(Math.sin(latRad) * Math.cos(distKm / R_EARTH_KM) +
                                Math.cos(latRad) * Math.sin(distKm / R_EARTH_KM) * Math.cos(brgRad));
        double lon2 = lon * DEG_TO_RAD + Math.atan2(
            Math.sin(brgRad) * Math.sin(distKm / R_EARTH_KM) * Math.cos(latRad),
            Math.cos(distKm / R_EARTH_KM) - Math.sin(latRad) * Math.sin(lat2));
        return new double[]{lat2 * RAD_TO_DEG, lon2 * RAD_TO_DEG};
    }

    /** DMS → DD ("55:45:30" → 55.7583) */
    public static double dmsToDd(String dms) {
        String[] parts = dms.split(":");
        double d = Double.parseDouble(parts[0]);
        double m = Double.parseDouble(parts[1]) / 60.0;
        double s = parts.length > 2 ? Double.parseDouble(parts[2]) / 3600.0 : 0;
        return d + m + s;
    }

    /** DD → DMS (55.7583 → "55°45'30\"") */
    public static String ddToDms(double dd) {
        int d = (int) dd;
        double rem = Math.abs(dd - d) * 60;
        int m = (int) rem;
        double s = (rem - m) * 60;
        return String.format("%d°%d'%d\"", d, m, (int) s);
    }

    /** IGC lat: 5507390N → 55.1239 */
    public static double igcLatToDd(String s) {
        char hem = s.charAt(s.length() - 1);
        int deg = Integer.parseInt(s.substring(0, 2));
        double min = Integer.parseInt(s.substring(2, 4)) +
                     Integer.parseInt(s.substring(4, 7)) / 1000.0;
        double lat = deg + min / 60.0;
        return (hem == 'S') ? -lat : lat;
    }

    /** IGC lon: 0373404E → 37.5678 */
    public static double igcLonToDd(String s) {
        char hem = s.charAt(s.length() - 1);
        int deg = Integer.parseInt(s.substring(0, 3));
        double min = Integer.parseInt(s.substring(3, 5)) +
                     Integer.parseInt(s.substring(5, 8)) / 1000.0;
        double lon = deg + min / 60.0;
        return (hem == 'W') ? -lon : lon;
    }

    /** Скорость из дистанции и времени (м/с) */
    public static double speedFromDistance(double distM, double dtSec) {
        if (dtSec <= 0) return 0;
        return distM / dtSec;
    }

    /** Векторное среднее ветра */
    public static float[] vectorAverage(float ws1, float wd1, float ws2, float wd2, float alpha) {
        double x1 = ws1 * Math.sin(wd1 * DEG_TO_RAD);
        double y1 = ws1 * Math.cos(wd1 * DEG_TO_RAD);
        double x2 = ws2 * Math.sin(wd2 * DEG_TO_RAD);
        double y2 = ws2 * Math.cos(wd2 * DEG_TO_RAD);
        double x = (1 - alpha) * x1 + alpha * x2;
        double y = (1 - alpha) * y1 + alpha * y2;
        float speed = (float) Math.sqrt(x * x + y * y);
        float dir = (float) ((Math.atan2(x, y) * RAD_TO_DEG + 360) % 360);
        return new float[]{speed, dir};
    }

    /** Метры → футы */
    public static float metersToFeet(float m) { return m / 0.3048f; }
    public static float feetToMeters(float ft) { return ft * 0.3048f; }
    public static float kmhToMs(float kmh) { return kmh / 3.6f; }
    public static float msToKmh(float ms) { return ms * 3.6f; }
    public static float knotsToMs(float kts) { return kts * 0.514444f; }
    public static float msToKnots(float ms) { return ms / 0.514444f; }
}
