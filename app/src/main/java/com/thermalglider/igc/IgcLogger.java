package com.thermalglider.igc;

import android.os.Environment;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * IgcLogger — запись IGC-файлов.
 * 4 сек интервал, CRC-CCITT, ATER.
 *
 * Раздел 12.3 ТЗ.
 */
public class IgcLogger {

    private static final String IGC_DIR = "ThermalGlider/igc/";
    private static final long LOG_INTERVAL_MS = 4000;

    private String filename;
    private OutputStreamWriter writer;
    private long lastFixTime = 0;
    private int fixCount = 0;
    private boolean isRecording = false;
    private String basePath;

    // CRC-CCITT
    private int crc = 0xFFFF;
    private boolean collectingCrc = false;

    public void setBasePath(String path) {
        this.basePath = path;
        new File(path + "/" + IGC_DIR).mkdirs();
    }

    /** Старт записи */
    public void startRecording(long startTimeMs) {
        String dateStr = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(new Date(startTimeMs));
        String dateDay = new SimpleDateFormat("ddMMyy", Locale.US)
            .format(new Date(startTimeMs));
        filename = basePath + "/" + IGC_DIR + dateStr + ".igc";

        try {
            new File(basePath + "/" + IGC_DIR).mkdirs();
            writer = new OutputStreamWriter(
                new BufferedOutputStream(new FileOutputStream(filename)),
                StandardCharsets.US_ASCII);

            // Заголовок
            writer.write("AThermalGlider\n");
            writer.write("HFDTE" + dateDay + "\n");
            writer.write("HFFXA100\n");
            writer.write("HFPLT Pilot\n");
            writer.write("HFGTY Paraglider\n");
            writer.write("HFGID TG-001\n");
            writer.write("HFDTM100\n");
            writer.write("HFFRFW 1.0\n");
            writer.write("HFRHZ100\n");
            writer.write("I013638TDS\n"); // T=track, D=alt diff, S=speed

            writer.flush();

            isRecording = true;
            fixCount = 0;
            lastFixTime = 0;
            crc = 0xFFFF;
            collectingCrc = false;
        } catch (Exception e) {
            isRecording = false;
        }
    }

    /** Добавление B-записи */
    public void addFix(double lat, double lon, float baroAlt, float gpsAlt,
                       float speed, float bearing, long timeMs) {
        if (!isRecording || writer == null) return;
        if (timeMs - lastFixTime < LOG_INTERVAL_MS) return;

        try {
            String timeStr = formatIgcTime(timeMs);
            String latStr = formatIgcLat(lat);
            String lonStr = formatIgcLon(lon);
            char fixChar = 'A'; // 3D fix
            int altPressFt = (int) (baroAlt / 0.3048f);
            int altGpsFt = (int) (gpsAlt / 0.3048f);

            // B-запись
            String bRecord = String.format("B%s%s%c%05d%05d",
                timeStr, latStr, fixChar,
                Math.max(0, altPressFt), Math.max(0, altGpsFt));

            // K-запись (доп. данные)
            int trackInt = ((int) bearing) % 360;
            int speedKmh = (int) (speed * 3.6);
            String kRecord = String.format("K%s%03d00%03d000",
                timeStr, trackInt, Math.min(999, speedKmh));

            // Пишем
            String line = bRecord + "\n" + kRecord + "\n";
            writer.write(line);

            // CRC (начинаем с первой B-записи)
            if (!collectingCrc) {
                collectingCrc = true;
            }
            if (collectingCrc) {
                updateCrc(line.getBytes(StandardCharsets.US_ASCII));
            }

            fixCount++;
            lastFixTime = timeMs;
        } catch (Exception ignored) {}
    }

    /** Остановка записи */
    public void stopRecording() {
        if (!isRecording) return;
        try {
            // G-запись: CRC-CCITT
            int finalCrc = crc;
            writer.write(String.format("G%04X\n", finalCrc));
            writer.flush();
            writer.close();
        } catch (Exception ignored) {}
        isRecording = false;
    }

    // === CRC-CCITT ===
    private void updateCrc(byte[] data) {
        for (byte b : data) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x8000) != 0) {
                    crc = (crc << 1) ^ 0x1021;
                } else {
                    crc = crc << 1;
                }
                crc &= 0xFFFF;
            }
        }
    }

    // === Форматирование IGC ===

    private String formatIgcTime(long epochMs) {
        Date d = new Date(epochMs);
        SimpleDateFormat sdf = new SimpleDateFormat("HHmmss", Locale.US);
        return sdf.format(d);
    }

    private String formatIgcLat(double lat) {
        char hem = lat >= 0 ? 'N' : 'S';
        lat = Math.abs(lat);
        int deg = (int) lat;
        double min = (lat - deg) * 60;
        int minInt = (int) min;
        int minFrac = (int) ((min - minInt) * 1000);
        return String.format("%02d%02d%03d%c", deg, minInt, minFrac, hem);
    }

    private String formatIgcLon(double lon) {
        char hem = lon >= 0 ? 'E' : 'W';
        lon = Math.abs(lon);
        int deg = (int) lon;
        double min = (lon - deg) * 60;
        int minInt = (int) min;
        int minFrac = (int) ((min - minInt) * 1000);
        return String.format("%03d%02d%03d%c", deg, minInt, minFrac, hem);
    }

    public boolean isRecording() { return isRecording; }
    public int getFixCount() { return fixCount; }
    public String getFilename() { return filename; }
}
