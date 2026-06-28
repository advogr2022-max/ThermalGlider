package com.thermalglider.wind;

/**
 * WindStore — кольцевой буфер измерений ветра.
 * Хранит до 100 записей, возвращает круговое среднее направления
 * и линейное среднее скорости.
 */
public class WindStore {

    private static final int MAX_RECORDS = 100;

    private final float[] windDirBuf = new float[MAX_RECORDS];
    private final float[] windSpdBuf = new float[MAX_RECORDS];
    private final long[] timeBuf = new long[MAX_RECORDS];
    private int head = 0;
    private int count = 0;

    public void addMeasurement(float windFromDeg, float windSpeedMs, long timeMs) {
        windDirBuf[head] = windFromDeg;
        windSpdBuf[head] = windSpeedMs;
        timeBuf[head] = timeMs;
        head = (head + 1) % MAX_RECORDS;
        if (count < MAX_RECORDS) count++;
    }

    /** Средняя скорость ветра за последние N мс */
    public float getAvgSpeed(long windowMs, long nowMs) {
        float sum = 0;
        int n = 0;
        long cutoff = nowMs - windowMs;
        for (int i = 0; i < count; i++) {
            int idx = (head - 1 - i + MAX_RECORDS) % MAX_RECORDS;
            if (timeBuf[idx] < cutoff) break;
            sum += windSpdBuf[idx];
            n++;
        }
        return n > 0 ? sum / n : 0;
    }

    /** Среднее направление (круговое) за последние N мс */
    public float getAvgDirection(long windowMs, long nowMs) {
        float sumSin = 0, sumCos = 0;
        int n = 0;
        long cutoff = nowMs - windowMs;
        for (int i = 0; i < count; i++) {
            int idx = (head - 1 - i + MAX_RECORDS) % MAX_RECORDS;
            if (timeBuf[idx] < cutoff) break;
            double rad = Math.toRadians(windDirBuf[idx]);
            sumSin += Math.sin(rad);
            sumCos += Math.cos(rad);
            n++;
        }
        if (n == 0) return 0;
        float dir = (float) Math.toDegrees(Math.atan2(sumSin / n, sumCos / n));
        return (dir + 360) % 360;
    }

    public int size() { return count; }

    public void reset() {
        head = 0;
        count = 0;
    }
}
