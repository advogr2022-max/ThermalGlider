package com.thermalglider.igc;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.thermalglider.util.GeoUtils;

import java.util.List;

/**
 * IgcReplay — проигрывание IGC-файлов с интерполяцией.
 * Подаёт точки в FlightManager как живые GPS-данные.
 * Исправлено: симуляционное время, линейная интерполяция между B-записями.
 *
 * Раздел 13.2-13.3 ТЗ.
 */
public class IgcReplay {

    private static final String TAG = "IgcReplay";
    private static final long TICK_MS = 100; // 10 Гц вывод

    private List<IGCParser.IgcFix> records;
    private int index = 0;
    private float speedMultiplier = 1.0f;
    private volatile boolean isPlaying = false;
    private volatile boolean isPaused = false;

    // Симуляционное время
    private long simTimeMs = 0;

    private HandlerThread thread;
    private Handler handler;

    public interface ReplayCallback {
        void onReplayFix(double lat, double lon, float altGps, float altPress,
                         float speed, float bearing, long simTimeMs);
        void onReplayProgress(int index, int total);
        void onReplayFinished();
    }

    private ReplayCallback callback;

    public IgcReplay() {}

    public void setCallback(ReplayCallback cb) { this.callback = cb; }

    /** Загрузка IGC-файла */
    public boolean load(String filepath) {
        IGCParser parser = new IGCParser();
        records = parser.parse(filepath);
        if (records == null || records.isEmpty()) {
            Log.w(TAG, "No records in: " + filepath);
            return false;
        }
        // Вычисляем speed/bearing из B-записей, если нет K-записей
        for (int i = 1; i < records.size(); i++) {
            IGCParser.IgcFix prev = records.get(i - 1);
            IGCParser.IgcFix cur = records.get(i);
            if (cur.speedMs <= 0) {
                long dt = cur.timeSec - prev.timeSec;
                if (dt > 0) {
                    double distM = GeoUtils.haversineM(prev.lat, prev.lon, cur.lat, cur.lon);
                    cur.speedMs = (float)(distM / dt);
                }
            }
            if (cur.trackDeg <= 0) {
                cur.trackDeg = (int) GeoUtils.bearingDeg(prev.lat, prev.lon, cur.lat, cur.lon);
            }
        }
        Log.d(TAG, "Loaded " + records.size() + " IGC records");
        return true;
    }

    /** Загрузка из готового списка (для тестов) */
    public boolean load(List<IGCParser.IgcFix> list) {
        if (list == null || list.isEmpty()) return false;
        records = list;
        return true;
    }

    /** Старт реплея */
    public void start() {
        if (records == null || records.isEmpty()) return;
        index = 0;
        simTimeMs = records.get(0).timeSec * 1000L;
        isPlaying = true;
        isPaused = false;

        thread = new HandlerThread("igc-replay");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(this::tick);
    }

    private void tick() {
        if (!isPlaying || isPaused) {
            if (index >= records.size() && callback != null) {
                callback.onReplayFinished();
            }
            return;
        }

        // Продвигаем симуляционное время
        long simStepMs = (long) (TICK_MS * speedMultiplier);
        simTimeMs += simStepMs;

        // Находим интервал [index, index+1], в который попадает simTimeMs
        while (index < records.size() - 1
               && records.get(index + 1).timeSec * 1000L <= simTimeMs) {
            index++;
        }

        if (index >= records.size() - 1) {
            // Финальная точка
            IGCParser.IgcFix last = records.get(records.size() - 1);
            if (callback != null) {
                callback.onReplayFix(last.lat, last.lon, last.altitudeGps, last.altitudePress,
                    last.speedMs, last.trackDeg, last.timeSec * 1000L);
                callback.onReplayProgress(records.size(), records.size());
                callback.onReplayFinished();
            }
            isPlaying = false;
            return;
        }

        IGCParser.IgcFix f0 = records.get(index);
        IGCParser.IgcFix f1 = records.get(index + 1);

        long t0 = f0.timeSec * 1000L;
        long t1 = f1.timeSec * 1000L;
        float frac = (t1 > t0) ? (float)(simTimeMs - t0) / (t1 - t0) : 0f;
        frac = Math.max(0f, Math.min(1f, frac));

        // Линейная интерполяция
        double lat = lerp(f0.lat, f1.lat, frac);
        double lon = lerp(f0.lon, f1.lon, frac);
        float altGps = lerp(f0.altitudeGps, f1.altitudeGps, frac);
        float altPress = lerp(f0.altitudePress, f1.altitudePress, frac);
        float speed = lerp(f0.speedMs, f1.speedMs, frac);
        float bearing = interpolateBearing(f0.trackDeg, f1.trackDeg, frac);

        if (callback != null) {
            callback.onReplayFix(lat, lon, altGps, altPress, speed, bearing, simTimeMs);
            callback.onReplayProgress(index, records.size());
        }

        // Планируем следующий тик
        if (isPlaying && !isPaused) {
            handler.postDelayed(this::tick, TICK_MS);
        }
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }
    private static double lerp(double a, double b, float t) { return a + (b - a) * t; }

    /** Интерполяция угла (кратчайший путь через 0/360) */
    private static float interpolateBearing(float a, float b, float t) {
        float diff = ((b - a + 540) % 360) - 180;
        return ((a + diff * t) % 360 + 360) % 360;
    }

    public void pause() { isPaused = true; }
    public void resume() {
        if (!isPlaying) return;
        isPaused = false;
        if (handler != null) handler.post(this::tick);
    }

    public void stop() {
        isPlaying = false;
        isPaused = false;
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
            handler = null;
        }
        if (thread != null) {
            thread.quitSafely();
            try {
                thread.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
    }

    public void seekTo(float fraction) {
        if (records == null || records.isEmpty()) return;
        int newIndex = (int) (fraction * (records.size() - 1));
        index = Math.max(0, Math.min(newIndex, records.size() - 1));
        simTimeMs = records.get(index).timeSec * 1000L;
    }

    public void setSpeed(float mult) {
        speedMultiplier = Math.max(0.1f, Math.min(mult, 20.0f));
    }

    public float getSpeed() { return speedMultiplier; }
    public int getIndex() { return index; }
    public int getTotal() { return records != null ? records.size() : 0; }
    public float getProgress() {
        return records != null && records.size() > 0 ? (float) index / records.size() : 0;
    }
    public boolean isPlaying() { return isPlaying; }
    public boolean isPaused() { return isPaused; }
}
