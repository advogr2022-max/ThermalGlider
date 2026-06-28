package com.thermalglider.igc;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * IGCParser — парсинг IGC-файлов.
 * Извлекает B-записи (фиксы) и K-записи.
 *
 * Раздел 13.1 ТЗ.
 */
public class IGCParser {

    public static class IgcFix {
        public int timeSec;           // секунд от начала дня
        public double lat, lon;
        public float altitudePress;   // м (из давления)
        public float altitudeGps;     // м (из GPS)
        public char fixType;          // 'A' = 3D, 'V' = valid
        public int trackDeg;          // из K-записи
        public float speedMs;         // из K-записи
    }

    private final List<IgcFix> bRecords = new ArrayList<>();

    /** Парсинг файла */
    public List<IgcFix> parse(String filepath) {
        bRecords.clear();
        File f = new File(filepath);
        if (!f.exists()) return bRecords;

        try {
            BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.length() < 35) continue;

                if (line.charAt(0) == 'B') {
                    IgcFix fix = parseBRecord(line);
                    if (fix != null) bRecords.add(fix);
                } else if (line.charAt(0) == 'K' && line.length() >= 16) {
                    // K-запись: дополняем последнюю B-запись
                    if (!bRecords.isEmpty()) {
                        IgcFix last = bRecords.get(bRecords.size() - 1);
                        try {
                            last.trackDeg = Integer.parseInt(line.substring(7, 10));
                            last.speedMs = Integer.parseInt(line.substring(13, 16)) / 3.6f;
                        } catch (Exception ignored) {}
                    }
                }
            }
            br.close();
        } catch (Exception ignored) {}

        return bRecords;
    }

    /** B0930204650200N03625110EA0009001200 */
    private IgcFix parseBRecord(String line) {
        try {
            IgcFix fix = new IgcFix();
            String time = line.substring(1, 7);   // hhmmss
            int h = Integer.parseInt(time.substring(0, 2));
            int m = Integer.parseInt(time.substring(2, 4));
            int s = Integer.parseInt(time.substring(4, 6));
            fix.timeSec = h * 3600 + m * 60 + s;

            String latRaw = line.substring(7, 15);
            String lonRaw = line.substring(15, 24);
            fix.fixType = line.charAt(24);
            int altPressFt = Integer.parseInt(line.substring(25, 30));
            int altGpsFt = Integer.parseInt(line.substring(30, 35));

            fix.lat = parseIgcLat(latRaw);
            fix.lon = parseIgcLon(lonRaw);
            fix.altitudePress = feetToMeters(altPressFt);
            fix.altitudeGps = feetToMeters(altGpsFt);

            return fix;
        } catch (Exception e) {
            return null;
        }
    }

    /** 5507390N → 55.1239 */
    private double parseIgcLat(String s) {
        char hem = s.charAt(s.length() - 1);
        int deg = Integer.parseInt(s.substring(0, 2));
        double min = Integer.parseInt(s.substring(2, 4))
            + Integer.parseInt(s.substring(4, 7)) / 1000.0;
        double lat = deg + min / 60.0;
        return (hem == 'S') ? -lat : lat;
    }

    /** 0373404E → 37.5678 */
    private double parseIgcLon(String s) {
        char hem = s.charAt(s.length() - 1);
        int deg = Integer.parseInt(s.substring(0, 3));
        double min = Integer.parseInt(s.substring(3, 5))
            + Integer.parseInt(s.substring(5, 8)) / 1000.0;
        double lon = deg + min / 60.0;
        return (hem == 'W') ? -lon : lon;
    }

    private float feetToMeters(float ft) { return ft * 0.3048f; }

    public int size() { return bRecords.size(); }
    public List<IgcFix> getRecords() { return bRecords; }
}
