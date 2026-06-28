package com.thermalglider.ui;

import android.os.Handler;
import android.view.MotionEvent;
import android.view.View;

/**
 * MapTouchHandler — обработка касаний карты.
 * - Pan (перетаскивание)
 * - Long-press (500ms) → диалог
 * - Тап по площадке
 *
 * Раздел 16.2-16.3 ТЗ.
 */
public class MapTouchHandler {

    private static final long LONG_PRESS_TIMEOUT_MS = 500;
    private static final float DRAG_THRESHOLD_PX = 10;

    private final View view;
    private final MapEngine engine;
    private final Handler handler = new Handler();

    private float downX, downY;
    private boolean isDragging = false;
    private Runnable longPressRunnable;

    public interface LongPressListener {
        void onLongPress(float x, float y);
    }

    private LongPressListener longPressListener;

    public MapTouchHandler(View view, MapEngine engine) {
        this.view = view;
        this.engine = engine;
    }

    public void setLongPressListener(LongPressListener listener) {
        this.longPressListener = listener;
    }

    public boolean onTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                isDragging = false;

                // Запуск таймера long-press
                longPressRunnable = () -> {
                    if (longPressListener != null && !isDragging) {
                        longPressListener.onLongPress(downX, downY);
                    }
                };
                handler.postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT_MS);
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;

                if (Math.abs(dx) > DRAG_THRESHOLD_PX || Math.abs(dy) > DRAG_THRESHOLD_PX) {
                    handler.removeCallbacks(longPressRunnable);
                    isDragging = true;
                    engine.panBy(-dx, -dy);
                    downX = event.getX();
                    downY = event.getY();
                    view.invalidate();
                }
                return true;

            case MotionEvent.ACTION_UP:
                handler.removeCallbacks(longPressRunnable);
                if (!isDragging) {
                    // Короткое касание
                    view.performClick();
                }
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                // Pinch zoom — TODO
                return true;
        }
        return false;
    }
}
