package com.thermalglider.vario;

import java.util.Arrays;

/**
 * MedianSmoother — медианный фильтр по скользящему окну.
 * Кольцевой буфер float[] + long[], без boxing, без аллокаций.
 *
 * Раздел 6.4 ТЗ.
 */
public class MedianSmoother {

    private final long windowMs;
    private final float[] valueBuf;
    private final long[] timeBuf;
    private final int capacity;
    private int head = 0;
    private int count = 0;

    // Временный массив для сортировки (переиспользуется)
    private final float[] sortBuf;

    public MedianSmoother(long windowMs, int capacity) {
        this.windowMs = windowMs;
        this.capacity = capacity;
        this.valueBuf = new float[capacity];
        this.timeBuf = new long[capacity];
        this.sortBuf = new float[capacity];
    }

    public MedianSmoother(long windowMs) {
        this(windowMs, 64); // ~6.4 с при 10 Гц
    }

    public MedianSmoother() {
        this(3000, 64);
    }

    public float update(float value, long timeMs) {
        // Добавляем в кольцевой буфер
        valueBuf[head] = value;
        timeBuf[head] = timeMs;
        head = (head + 1) % capacity;
        if (count < capacity) count++;

        // Собираем валидные данные (в пределах окна)
        long cutoff = timeMs - windowMs;
        int validCount = 0;
        // Идём от newest к oldest (данные упорядочены по времени)
        for (int i = 0; i < count; i++) {
            int idx = (head - 1 - i + capacity) % capacity;
            if (timeBuf[idx] >= cutoff) {
                sortBuf[validCount++] = valueBuf[idx];
            } else {
                break; // данные упорядочены, дальше все старше
            }
        }

        if (validCount < 2) return value;

        // Сортируем и берём медиану (только validCount элементов)
        Arrays.sort(sortBuf, 0, validCount);
        return sortBuf[validCount / 2];
    }

    public void reset() {
        head = 0;
        count = 0;
    }

    public int size() { return count; }
}
