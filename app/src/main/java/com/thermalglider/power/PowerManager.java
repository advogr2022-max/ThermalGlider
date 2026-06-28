package com.thermalglider.power;

import android.app.Activity;
import android.view.WindowManager;

/**
 * PowerManager — управление яркостью экрана.
 * Три уровня: NORMAL, DIM, OFF.
 * В спирали — полная яркость.
 *
 * Раздел 23.1 ТЗ.
 */
public class PowerManager {

    private static final long TIMEOUT_DIM_MS = 60000;   // 1 мин → тусклый
    private static final long TIMEOUT_OFF_MS = 300000;  // 5 мин → мин.яркость

    private static final float BRIGHTNESS_NORMAL = 0.8f;
    private static final float BRIGHTNESS_DIM = 0.2f;
    private static final float BRIGHTNESS_MIN = 0.05f;

    private final Activity activity;
    private long lastInteractionMs;
    private String mode = "NORMAL";

    public PowerManager(Activity activity) {
        this.activity = activity;
        this.lastInteractionMs = System.currentTimeMillis();
    }

    public void onUserInteraction() {
        lastInteractionMs = System.currentTimeMillis();
        if (!"NORMAL".equals(mode)) {
            setBrightness(BRIGHTNESS_NORMAL);
            mode = "NORMAL";
        }
    }

    public void onTick(boolean isCircling, long nowMs) {
        if (isCircling) {
            lastInteractionMs = nowMs;
            if (!"NORMAL".equals(mode)) {
                setBrightness(BRIGHTNESS_NORMAL);
                mode = "NORMAL";
            }
            return;
        }

        long idleMs = nowMs - lastInteractionMs;

        if (idleMs > TIMEOUT_OFF_MS && "DIM".equals(mode)) {
            setBrightness(BRIGHTNESS_MIN);
            mode = "OFF";
        } else if (idleMs > TIMEOUT_DIM_MS && "NORMAL".equals(mode)) {
            setBrightness(BRIGHTNESS_DIM);
            mode = "DIM";
        }
    }

    private void setBrightness(float level) {
        if (activity == null || activity.isFinishing()) return;
        WindowManager.LayoutParams lp = activity.getWindow().getAttributes();
        lp.screenBrightness = level;
        activity.getWindow().setAttributes(lp);
    }

    public void reset() {
        lastInteractionMs = System.currentTimeMillis();
        mode = "NORMAL";
        setBrightness(BRIGHTNESS_NORMAL);
    }
}
