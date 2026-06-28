package com.thermalglider.vario;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MedianSmoother — медианный фильтр по скользящему окну.
 * Подавление выбросов варьо.
 *
 * Раздел 6.4 ТЗ.
 */
public class MedianSmoother {

    private final long windowMs;
    private final List<Sample> buffer = new ArrayList<>();

    private static class Sample {
        float value;
        long time;
        Sample(float v, long t) { value = v; time = t; }
    }

    public MedianSmoother(long windowMs) {
        this.windowMs = windowMs;
    }

    public MedianSmoother() {
        this(3000);
    }

    public float update(float value, long timeMs) {
        buffer.add(new Sample(value, timeMs));

        // Очистка старых
        long cutoff = timeMs - windowMs;
        buffer.removeIf(s -> s.time < cutoff);

        if (buffer.size() < 2) return value;

        List<Float> values = new ArrayList<>();
        for (Sample s : buffer) values.add(s.value);
        Collections.sort(values);
        return values.get(values.size() / 2);
    }

    public void reset() {
        buffer.clear();
    }

    public int size() { return buffer.size(); }
}
