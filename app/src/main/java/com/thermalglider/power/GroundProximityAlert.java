package com.thermalglider.power;

import android.content.Context;
import android.os.Vibrator;

/**
 * GroundProximityAlert — предупреждение о земле.
 * Три уровня: GREEN, YELLOW, RED.
 *
 * Раздел 21.2 ТЗ.
 */
public class GroundProximityAlert {

    private String level = "GREEN";
    private long lastWarningMs = 0;
    private Vibrator vibrator;

    public interface AlertSoundCallback {
        void playAlert(float frequencyHz, float volume);
    }

    private AlertSoundCallback soundCb;

    public GroundProximityAlert(Context context) {
        vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
    }

    public void setSoundCallback(AlertSoundCallback cb) {
        this.soundCb = cb;
    }

    /** Оценка уровня опасности */
    public String evaluate(float aglM, float varioMs, long nowMs) {
        String newLevel;

        if (varioMs < -3.0f && aglM < 200) {
            newLevel = "SINK_RATE";
        } else if (aglM < 100) {
            newLevel = "RED";
        } else if (aglM < 300) {
            newLevel = "YELLOW";
        } else {
            newLevel = "GREEN";
        }

        if (!newLevel.equals(level)) {
            level = newLevel;
            triggerAlert(newLevel);
            return newLevel;
        }

        // YELLOW: повтор раз в 10 секунд
        if ("YELLOW".equals(newLevel) && nowMs - lastWarningMs > 10000) {
            triggerAlert("YELLOW_REPEAT");
            lastWarningMs = nowMs;
        }

        return level;
    }

    private void triggerAlert(String alertLevel) {
        switch (alertLevel) {
            case "RED":
            case "SINK_RATE":
                if (soundCb != null) soundCb.playAlert(2000, 1.0f);
                if (vibrator != null) vibrator.vibrate(500);
                break;
            case "YELLOW":
            case "YELLOW_REPEAT":
                if (soundCb != null) soundCb.playAlert(800, 0.5f);
                break;
        }
    }

    public String getLevel() { return level; }
    public void reset() { level = "GREEN"; lastWarningMs = 0; }
}
