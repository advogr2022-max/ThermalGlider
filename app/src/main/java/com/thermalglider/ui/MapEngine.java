package com.thermalglider.ui;

import android.graphics.PointF;
import android.util.Log;

/**
 * MapEngine — проекция Plate Carrée (Equirectangular).
 * geo↔screen, zoom, pan.
 *
 * Раздел 14.3 ТЗ.
 */
public class MapEngine {

    private static final String TAG = "MapEngine";

    // Константы
    private static final double DEG_TO_RAD = Math.PI / 180.0;
    private static final double RAD_TO_DEG = 180.0 / Math.PI;

    // Центр карты (гео)
    private double centerLat = 55.0;
    private double centerLon = 37.0;

    // Масштаб (км/пиксель)
    private float kmPerPx = 0.02f;  // ~20 м/px

    // Размер viewport
    private int viewW = 1080;
    private int viewH = 1920;

    // Режимы
    private boolean trackUp = false;
    private float rotationDeg = 0;

    // Ограничения
    private static final float MIN_KM_PER_PX = 0.001f; // ~1 м/px
    private static final float MAX_KM_PER_PX = 50.0f;  // ~50 км/px

    // Кэш Scale
    private float cachedScale = 0;
    private boolean scaleDirty = true;

    public MapEngine() {}

    /** Установка viewport */
    public void setViewSize(int w, int h) {
        this.viewW = w;
        this.viewH = h;
        scaleDirty = true;
    }

    /** Центрирование на позиции */
    public void centerOn(double lat, double lon) {
        centerLat = lat;
        centerLon = lon;
    }

    /** Гео → экран */
    public PointF geoToScreen(double lat, double lon) {
        float scale = getScale();
        double dx = (lon - centerLon) * Math.cos(centerLat * DEG_TO_RAD) * scale;
        double dy = -(lat - centerLat) * scale;

        float sx = (float) (viewW / 2.0 + dx);
        float sy = (float) (viewH / 2.0 + dy);

        // Применение поворота (track-up)
        if (trackUp && rotationDeg != 0) {
            float cx = viewW / 2f;
            float cy = viewH / 2f;
            double rad = Math.toRadians(rotationDeg);
            float rx = (float) (Math.cos(rad) * (sx - cx) - Math.sin(rad) * (sy - cy)) + cx;
            float ry = (float) (Math.sin(rad) * (sx - cx) + Math.cos(rad) * (sy - cy)) + cy;
            sx = rx;
            sy = ry;
        }

        return new PointF(sx, sy);
    }

    /** Экран → гео */
    public double[] screenToGeo(float sx, float sy) {
        float scale = getScale();

        // Обратный поворот
        float rx = sx, ry = sy;
        if (trackUp && rotationDeg != 0) {
            float cx = viewW / 2f;
            float cy = viewH / 2f;
            double rad = Math.toRadians(-rotationDeg);
            rx = (float) (Math.cos(rad) * (sx - cx) - Math.sin(rad) * (sy - cy)) + cx;
            ry = (float) (Math.sin(rad) * (sx - cx) + Math.cos(rad) * (sy - cy)) + cy;
        }

        double dx = rx - viewW / 2.0;
        double dy = -(ry - viewH / 2.0);

        double lon = centerLon + dx / (scale * Math.cos(centerLat * DEG_TO_RAD));
        double lat = centerLat + dy / scale;

        return new double[]{lat, lon};
    }

    /** Масштаб: пикселей на градус широты */
    private float getScale() {
        if (scaleDirty || cachedScale == 0) {
            if (kmPerPx > 0) {
                cachedScale = viewH / (float) (2 * 111.32 / kmPerPx);
            } else {
                cachedScale = viewH;
            }
            scaleDirty = false;
        }
        return cachedScale;
    }

    /** Метров в пикселе */
    public float metersPerPixel() {
        return kmPerPx * 1000;
    }

    /** Zoom */
    public void zoomIn(float factor) {
        kmPerPx /= factor;
        kmPerPx = Math.max(kmPerPx, MIN_KM_PER_PX);
        scaleDirty = true;
    }

    public void zoomOut(float factor) {
        kmPerPx *= factor;
        kmPerPx = Math.min(kmPerPx, MAX_KM_PER_PX);
        scaleDirty = true;
    }

    public void setKmPerPx(float kmpp) {
        kmPerPx = Math.max(MIN_KM_PER_PX, Math.min(kmpp, MAX_KM_PER_PX));
        scaleDirty = true;
    }

    /** Pan на dx, dy пикселей */
    public void panBy(float dxPx, float dyPx) {
        float scale = getScale();
        if (scale <= 0) return;
        double dlat = -dyPx / scale;
        double dlon = dxPx / (scale * Math.cos(centerLat * DEG_TO_RAD));
        centerLat += dlat;
        centerLon += dlon;
    }

    /** Track-up / North-up */
    public void setTrackUp(boolean enabled, float headingDeg) {
        trackUp = enabled;
        rotationDeg = enabled ? headingDeg : 0;
    }

    public boolean isTrackUp() { return trackUp; }
    public float getKmPerPx() { return kmPerPx; }
    public double getCenterLat() { return centerLat; }
    public double getCenterLon() { return centerLon; }
    public int getViewW() { return viewW; }
    public int getViewH() { return viewH; }

    /** Видимый geo-прямоугольник */
    public double[] getVisibleBounds() {
        double[] nw = screenToGeo(0, 0);
        double[] se = screenToGeo(viewW, viewH);
        return new double[]{nw[0], nw[1], se[0], se[1]}; // latMin, lonMin, latMax, lonMax (упрощённо)
    }
}
