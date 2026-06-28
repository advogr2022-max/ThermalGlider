package com.thermalglider.sensors;

import android.os.Environment;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * SensorLogger — логгирование датчиков в ZIP-файл (50 Гц).
 *
 * Раздел 24.6 ТЗ.
 */
public class SensorLogger {

    private static final String LOG_DIR = "ThermalGlider/logs/";
    private static final int FLUSH_INTERVAL_MS = 5000;
    private static final String CSV_HEADER =
        "dtMs,gpsSpeed,gpsHeading,gpsLat,gpsLon,gpsAlt,gpsFixAge,gpsAccuracy," +
        "vario,ax,ay,az,gx,gy,gz,mx,my,mz,pressure,pitch,roll,heading," +
        "thermalAngle,thermalStrength,thermalDist,thermalSource,snr,noiseFloor,detectStatus";

    private String basePath;
    private ZipOutputStream zipOut;
    private OutputStreamWriter csvWriter;
    private long startTime = 0;
    private long lastFlush = 0;
    private boolean isLogging = false;
    private int sampleCount = 0;

    public void start(String basePath, long timestampMs) {
        this.basePath = basePath;
        String dateStr = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .format(new Date(timestampMs));
        String zipPath = basePath + "/" + LOG_DIR + "Flight_" + dateStr + ".zip";

        try {
            new File(basePath + "/" + LOG_DIR).mkdirs();
            FileOutputStream fos = new FileOutputStream(zipPath);
            zipOut = new ZipOutputStream(new BufferedOutputStream(fos));
            zipOut.putNextEntry(new ZipEntry("Flight_" + dateStr + ".csv"));

            csvWriter = new OutputStreamWriter(zipOut, StandardCharsets.UTF_8);
            csvWriter.write(CSV_HEADER + "\n");
            csvWriter.flush();

            this.startTime = timestampMs;
            this.lastFlush = timestampMs;
            this.sampleCount = 0;
            this.isLogging = true;
        } catch (Exception e) {
            isLogging = false;
        }
    }

    /** Запись одного сэмпла (50 Гц) */
    public void writeSample(SensorController sensors,
                            double gpsLat, double gpsLon, double gpsAlt,
                            float gpsSpeed, float gpsHeading,
                            float gpsAccuracy, float vario,
                            float thermalAngle, float thermalStrength,
                            float thermalDist, String thermalSource,
                            float snr, float noiseFloor, int detectStatus,
                            long timestampMs) {
        if (!isLogging) return;

        long dt = timestampMs - startTime;
        try {
            StringBuilder sb = new StringBuilder(256);
            sb.append(dt).append(',');
            sb.append(String.format(Locale.US, "%.2f,", gpsSpeed));
            sb.append(String.format(Locale.US, "%.1f,", gpsHeading));
            sb.append(String.format(Locale.US, "%.7f,", gpsLat));
            sb.append(String.format(Locale.US, "%.7f,", gpsLon));
            sb.append(String.format(Locale.US, "%.1f,", gpsAlt));
            sb.append("0,"); // fixAge
            sb.append(String.format(Locale.US, "%.1f,", gpsAccuracy));
            sb.append(String.format(Locale.US, "%.3f,", vario));
            sb.append(String.format(Locale.US, "%.1f,", sensors.ax));
            sb.append(String.format(Locale.US, "%.1f,", sensors.ay));
            sb.append(String.format(Locale.US, "%.1f,", sensors.az));
            sb.append(String.format(Locale.US, "%.2f,", sensors.gx));
            sb.append(String.format(Locale.US, "%.2f,", sensors.gy));
            sb.append(String.format(Locale.US, "%.2f,", sensors.gz));
            sb.append(String.format(Locale.US, "%.2f,", sensors.mx));
            sb.append(String.format(Locale.US, "%.2f,", sensors.my));
            sb.append(String.format(Locale.US, "%.2f,", sensors.mz));
            sb.append(String.format(Locale.US, "%.2f,", sensors.pressure));
            sb.append(String.format(Locale.US, "%.1f,", sensors.pitch));
            sb.append(String.format(Locale.US, "%.1f,", sensors.roll));
            sb.append(String.format(Locale.US, "%.1f,", sensors.heading));
            sb.append(String.format(Locale.US, "%.1f,", thermalAngle));
            sb.append(String.format(Locale.US, "%.3f,", thermalStrength));
            sb.append(String.format(Locale.US, "%.1f,", thermalDist));
            sb.append(thermalSource).append(',');
            sb.append(String.format(Locale.US, "%.2f,", snr));
            sb.append(String.format(Locale.US, "%.2f,", noiseFloor));
            sb.append(detectStatus);
            sb.append('\n');

            csvWriter.write(sb.toString());
            sampleCount++;

            // Периодический flush
            if (timestampMs - lastFlush >= FLUSH_INTERVAL_MS) {
                csvWriter.flush();
                zipOut.flush();
                lastFlush = timestampMs;
            }
        } catch (Exception ignored) {}
    }

    public void stop() {
        if (!isLogging) return;
        try {
            csvWriter.flush();
            zipOut.closeEntry();
            zipOut.close();
        } catch (Exception ignored) {}
        isLogging = false;
    }

    public boolean isLogging() { return isLogging; }
    public int getSampleCount() { return sampleCount; }
}
