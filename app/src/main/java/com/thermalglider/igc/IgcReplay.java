package com.thermalglider.igc;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.util.List;

/**
 * IgcReplay — проигрывание IGC-файлов.
 * Подаёт точки в FlightManager как живые GPS-данные.
 *
 * Раздел 13.2-13.3 ТЗ.
 */
public class IgcReplay {

    private static final String TAG = "IgcReplay";

    private List<IGCParser.IgcFix> records;
    private int index = 0;
    private float speedMultiplier = 1.0f;
    private boolean isPlaying = false;
    private boolean isPaused = false;
    private long startRealtimeMs = 0;
    private int startIndex = 0;

    private HandlerThread thread;
    private Handler handler;

    public interface ReplayCallback {
        void onReplayFix(double lat, double lon, float altGps, float altPress,
                         float speed, float bearing, long timestampMs);
        void onReplayFinished();
    }

    private ReplayCallback callback;

    public IgcReplay() {}

    public void setCallback(ReplayCallback cb) { this.callback = cb; }

    /** Загрузка IGC-файла */
    public boolean load(String filepath) {
        IGCParser parser = new IGCParser();
        records = parser.parse(filepath);
        if (records.isEmpty()) {
            Log.w(TAG, "No records in: " + filepath);
            return false;
        }
        Log.d(TAG, "Loaded " + records.size() + " IGC records");
        return true;
    }

    /** Старт реплея */
    public void start() {
        if (records == null || records.isEmpty()) return;
        index = 0;
        startIndex = 0;
        startRealtimeMs = System.currentTimeMillis();
        isPlaying = true;
        isPaused = false;

        thread = new HandlerThread("igc-replay");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(this::tick);
    }

    private void tick() {
        if (!isPlaying || isPaused || index >= records.size()) {
            if (index >= records.size() && callback != null) {
                callback.onReplayFinished();
            }
            return;
        }

        IGCParser.IgcFix rec = records.get(index);

        // Время между записями
        long elapsedSimMs;
        if (index > startIndex) {
            IGCParser.IgcFix prev = records.get(index - 1);
            elapsedSimMs = (long) ((rec.timeSec - prev.timeSec) * 1000L / speedMultiplier);
        } else {
            elapsedSimMs = 0;
        }

        // Подача в callback
        if (callback != null) {
            callback.onReplayFix(rec.lat, rec.lon, rec.altitudeGps, rec.altitudePress,
                rec.speedMs, rec.trackDeg, System.currentTimeMillis());
        }

        index++;

        // Планирование следующей точки
        if (elapsedSimMs > 0 && index < records.size()) {
            handler.postDelayed(this::tick, elapsedSimMs);
        } else {
            handler.post(this::tick);
        }
    }

    public void pause() { isPaused = true; }
    public void resume() { isPaused = false; handler.post(this::tick); }
    public void stop() {
        isPlaying = false;
        isPaused = false;
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
    }

    public void seekTo(float fraction) {
        if (records == null || records.isEmpty()) return;
        int newIndex = (int) (fraction * records.size());
        index = Math.max(0, Math.min(newIndex, records.size() - 1));
        startIndex = index;
        startRealtimeMs = System.currentTimeMillis();
    }

    public void setSpeed(float mult) { speedMultiplier = Math.max(0.1f, Math.min(mult, 20.0f)); }
    public float getSpeed() { return speedMultiplier; }
    public int getIndex() { return index; }
    public int getTotal() { return records != null ? records.size() : 0; }
    public float getProgress() { return records != null && records.size() > 0 ? (float) index / records.size() : 0; }
    public boolean isPlaying() { return isPlaying; }
    public boolean isPaused() { return isPaused; }
}
