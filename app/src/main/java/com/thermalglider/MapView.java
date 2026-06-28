package com.thermalglider;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.view.MotionEvent;
import android.view.View;

import com.thermalglider.data.FlightState;
import com.thermalglider.data.SystemStatus;
import com.thermalglider.ui.GlideEllipsePainter;
import com.thermalglider.ui.InfoBoxManager;
import com.thermalglider.ui.LandingFieldPainter;
import com.thermalglider.ui.MapEngine;
import com.thermalglider.ui.MapTouchHandler;
import com.thermalglider.ui.SettingsActivity;
import com.thermalglider.ui.ThermalPainter;
import com.thermalglider.ui.TileLoader;
import com.thermalglider.util.Units;

import java.util.HashMap;
import java.util.Map;

/**
 * MapView — основной Canvas View.
 * 4% статус-бар + 60% карта (OSM + трек + термики + эллипс + площадки + пилот) + 36% InfoBox.
 */
public class MapView extends View {

    private static final float STATUS_BAR_RATIO = 0.04f;
    private static final float MAP_RATIO = 0.60f;

    private int w, h;
    private int statusBarH, mapH, infoBoxY, infoBoxH;

    private FlightState flightState;
    private Units units;
    private SystemStatus systemStatus;

    private final MapEngine mapEngine = new MapEngine();
    private final TileLoader tileLoader = new TileLoader();
    private final MapTouchHandler touchHandler;
    private final ThermalPainter thermalPainter = new ThermalPainter();
    private final GlideEllipsePainter ellipsePainter = new GlideEllipsePainter();
    private final LandingFieldPainter fieldPainter = new LandingFieldPainter();
    private InfoBoxManager infoBoxManager;

    // Paints
    private final Paint statusTextPaint = new Paint();
    private final Paint dividerPaint = new Paint();
    private final Paint pilotPaint = new Paint();
    private final Paint trackPaint = new Paint();
    private final Paint debugPaint = new Paint();

    // Трек (2000 точек)
    private static final int TRACK_MAX = 2000;
    private final float[] trackLat = new float[TRACK_MAX];
    private final float[] trackLon = new float[TRACK_MAX];
    private int trackHead = 0, trackCount = 0;
    private double lastTrackLat, lastTrackLon;
    private static final float TRACK_MIN_DIST_M = 5;

    // Кэш тайлов: "z/x/y" → Bitmap
    private final Map<String, Bitmap> tileCache = new HashMap<>();
    private boolean tileInitDone = false;
    private int lastTileZoom = -1;

    public MapView(Context context) {
        super(context);
        init();

        touchHandler = new MapTouchHandler(this, mapEngine);
        touchHandler.setLongPressListener((x, y) -> {
            if (y < infoBoxY) {
                showMapLongPressDialog();
            } else {
                onInfoBoxLongPress(x, y - infoBoxY - 2);
            }
        });
    }

    private void init() {
        statusTextPaint.setColor(Color.WHITE);
        statusTextPaint.setTextSize(32);
        statusTextPaint.setAntiAlias(true);

        dividerPaint.setColor(Color.argb(200, 30, 150, 255));
        dividerPaint.setStrokeWidth(2);

        pilotPaint.setColor(Color.argb(255, 255, 100, 100));
        pilotPaint.setStyle(Paint.Style.FILL);
        pilotPaint.setAntiAlias(true);

        trackPaint.setColor(Color.argb(200, 30, 100, 255));
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeWidth(4);
        trackPaint.setStrokeJoin(Paint.Join.ROUND);
        trackPaint.setAntiAlias(true);

        debugPaint.setColor(Color.rgb(80, 80, 80));
        debugPaint.setTextSize(22);
        debugPaint.setAntiAlias(true);

        infoBoxManager = new InfoBoxManager(getContext());
        flightState = new FlightState();
        systemStatus = new SystemStatus();
    }

    public void updateFlightData(FlightState fs, Units u, SystemStatus ss) {
        this.flightState = fs;
        this.units = u;
        this.systemStatus = ss;

        if (fs.hasGpsFix) {
            mapEngine.centerOn(fs.latitude, fs.longitude);
            mapEngine.setTrackUp(false, fs.bearing);
            if (fs.currentKmPerPx > 0) mapEngine.setKmPerPx(fs.currentKmPerPx);

            double dist = Math.sqrt(
                Math.pow(fs.latitude - lastTrackLat, 2) +
                Math.pow(fs.longitude - lastTrackLon, 2)) * 111320;
            if (dist > TRACK_MIN_DIST_M || trackCount == 0) {
                trackLat[trackHead] = (float) fs.latitude;
                trackLon[trackHead] = (float) fs.longitude;
                trackHead = (trackHead + 1) % TRACK_MAX;
                if (trackCount < TRACK_MAX) trackCount++;
                lastTrackLat = fs.latitude;
                lastTrackLon = fs.longitude;
            }
        }
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        this.w = w;
        this.h = h;
        statusBarH = (int) (h * STATUS_BAR_RATIO);
        if (statusBarH < 40) statusBarH = 40;
        mapH = (int) (h * MAP_RATIO);
        infoBoxY = statusBarH + mapH;
        infoBoxH = h - infoBoxY;
        mapEngine.setViewSize(w, infoBoxY - statusBarH);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.rgb(0, 0, 0));
        drawStatusBar(canvas);
        drawMap(canvas);
        canvas.drawLine(0, infoBoxY, w, infoBoxY, dividerPaint);
        drawInfoBoxes(canvas);
    }

    // ========== STATUS BAR ==========

    private void drawStatusBar(Canvas canvas) {
        if (systemStatus == null || flightState == null) return;
        systemStatus.update(flightState);
        String text = systemStatus.getStatusBarText();
        int color;
        switch (systemStatus.statusColor) {
            case "RED": color = Color.rgb(255, 80, 80); break;
            case "YELLOW": color = Color.rgb(255, 200, 50); break;
            default: color = Color.rgb(100, 255, 100); break;
        }
        statusTextPaint.setColor(color);

        if (flightState.isFlying) {
            String altText = units != null ? units.formatAlt(flightState.baroAltitude)
                : String.format("%.0f", flightState.baroAltitude);
            String timeText = String.format("%02d:%02d",
                flightState.flightDurationSec / 60, flightState.flightDurationSec % 60);
            text += "  \u2191" + altText + "  " + timeText;
        }
        canvas.drawText(text, 20, statusBarH - 8, statusTextPaint);
    }

    // ========== MAP ==========

    private void drawMap(Canvas canvas) {
        canvas.save();
        int mapTop = statusBarH, mapBot = infoBoxY;
        canvas.clipRect(0, mapTop, w, mapBot);

        if (flightState == null || !flightState.hasGpsFix) {
            debugPaint.setTextSize(36);
            debugPaint.setColor(Color.argb(160, 255, 255, 255));
            float tw = debugPaint.measureText("WAITING FOR GPS...");
            canvas.drawText("WAITING FOR GPS...", (w - tw) / 2, mapTop + mapH / 2, debugPaint);
            canvas.restore();
            return;
        }

        // Инициализация TileLoader
        if (!tileInitDone) {
            java.io.File extDir = getContext().getExternalFilesDir(null);
            String basePath = extDir != null
                ? extDir.getAbsolutePath() + "/ThermalGlider"
                : getContext().getFilesDir().getAbsolutePath() + "/ThermalGlider";
            tileLoader.init(basePath);
            tileLoader.cleanDiskCache();
            tileInitDone = true;
        }

        // === ТАЙЛЫ OSM ===
        drawOsmTiles(canvas, mapTop, mapBot);

        // === ТРЕК ===
        drawTrack(canvas);

        // === ЭЛЛИПС ДОЛЁТА ===
        if (flightState.glideEllipse != null) {
            ellipsePainter.draw(canvas, mapEngine, flightState.glideEllipse);
        }

        // === ТЕРМИКИ (3D кольца + квадранты) ===
        if (flightState.isCircling) {
            thermalPainter.draw(canvas, mapEngine, flightState,
                null, null, 0);
        }

        // === ПЛОЩАДКИ ===
        if (flightState.reachableFields != null) {
            fieldPainter.draw(canvas, mapEngine, flightState.reachableFields,
                flightState.selectedField);
        }

        // === ПИЛОТ ===
        PointF pilot = mapEngine.geoToScreen(flightState.latitude, flightState.longitude);
        canvas.drawCircle(pilot.x, pilot.y, 8, pilotPaint);
        Paint hl = new Paint();
        hl.setColor(Color.argb(150, 255, 255, 255));
        hl.setStrokeWidth(2);
        float hx = pilot.x + (float) Math.sin(Math.toRadians(flightState.bearing)) * 40;
        float hy = pilot.y - (float) Math.cos(Math.toRadians(flightState.bearing)) * 40;
        canvas.drawLine(pilot.x, pilot.y, hx, hy, hl);

        // === DEBUG ===
        debugPaint.setColor(Color.rgb(80, 80, 80));
        String dbg = String.format("L/D:%.1f W:%.0f\u00B0%.1f %s S:%.0f AGL:%.0f Z:z%d",
            flightState.glideRatio, flightState.windDirection, flightState.windSpeed,
            flightState.isCircling ? "CIR" : "CRS",
            flightState.speed * 3.6, flightState.altitudeAGL, lastTileZoom);
        canvas.drawText(dbg, 10, mapTop + 24, debugPaint);

        canvas.restore();
    }

    /** Отрисовка OSM тайлов */
    private void drawOsmTiles(Canvas canvas, int mapTop, int mapBot) {
        double[] bounds = mapEngine.getVisibleBounds();
        int zoom = (int) (10 - Math.log(mapEngine.getKmPerPx()) / Math.log(2));
        zoom = Math.max(5, Math.min(zoom, 18));
        lastTileZoom = zoom;

        double tileGeoDeg = 360.0 / (1L << zoom);
        int x0 = (int) Math.floor((bounds[1] + 180) / tileGeoDeg);
        int x1 = (int) Math.ceil((bounds[3] + 180) / tileGeoDeg);
        int y0 = (int) Math.floor((90 - bounds[0]) / tileGeoDeg);
        int y1 = (int) Math.ceil((90 - bounds[2]) / tileGeoDeg);

        // Фон для незагруженных тайлов
        Paint bg = new Paint();
        bg.setColor(Color.rgb(25, 30, 35));
        canvas.drawRect(0, mapTop, w, mapBot, bg);

        // Сетка границ тайлов (тонкая)
        Paint border = new Paint();
        border.setColor(Color.rgb(50, 55, 60));
        border.setStrokeWidth(1);

        for (int tx = x0; tx <= x1; tx++) {
            for (int ty = y0; ty <= y1; ty++) {
                String key = zoom + "/" + tx + "/" + ty;
                Bitmap bmp;
                synchronized (tileCache) {
                    bmp = tileCache.get(key);
                }

                // Гео-координаты углов тайла
                double lon0 = tx * tileGeoDeg - 180;
                double lat0 = 90 - ty * tileGeoDeg;
                double lon1 = (tx + 1) * tileGeoDeg - 180;
                double lat1 = 90 - (ty + 1) * tileGeoDeg;

                PointF pNW = mapEngine.geoToScreen(lat0, lon0);
                PointF pSE = mapEngine.geoToScreen(lat1, lon1);

                float left = Math.min(pNW.x, pSE.x);
                float top = Math.min(pNW.y, pSE.y);
                float right = Math.max(pNW.x, pSE.x);
                float bottom = Math.max(pNW.y, pSE.y);

                if (bmp != null && !bmp.isRecycled()) {
                    // Рисуем реальный тайл
                    canvas.drawBitmap(bmp, null,
                        new android.graphics.RectF(left, top, right, bottom), null);
                } else {
                    // Placeholder + рамка
                    Paint ph = new Paint();
                    ph.setColor(Color.rgb(35, 40, 45));
                    canvas.drawRect(left, top, right, bottom, ph);
                    canvas.drawRect(left, top, right, bottom, border);

                    // Запрос загрузки
                    final String fkey = key;
                    final int fz = zoom, ftx = tx, fty = ty;
                    tileLoader.requestTile(fz, ftx, fty, (z, x, y, bitmap) -> {
                        synchronized (tileCache) {
                            tileCache.put(fkey, bitmap);
                        }
                        invalidate();
                    });
                }
            }
        }
    }

    /** Отрисовка трека */
    private void drawTrack(Canvas canvas) {
        if (trackCount < 2) return;
        android.graphics.Path path = new android.graphics.Path();
        boolean first = true;
        for (int i = 0; i < trackCount; i++) {
            int idx = (trackHead - trackCount + i + TRACK_MAX) % TRACK_MAX;
            PointF pt = mapEngine.geoToScreen(trackLat[idx], trackLon[idx]);
            if (first) { path.moveTo(pt.x, pt.y); first = false; }
            else path.lineTo(pt.x, pt.y);
        }
        canvas.drawPath(path, trackPaint);
    }

    // ========== INFOBOX ==========

    private void drawInfoBoxes(Canvas canvas) {
        Paint bg = new Paint();
        bg.setColor(Color.rgb(10, 10, 10));
        canvas.drawRect(0, infoBoxY, w, h, bg);

        if (infoBoxManager == null) return;

        infoBoxManager.gridX = 0;
        infoBoxManager.gridY = infoBoxY + 2;
        infoBoxManager.cellW = w / (float) infoBoxManager.cols;
        infoBoxManager.cellH = (h - infoBoxY - 2) / (float) infoBoxManager.rows;

        infoBoxManager.update(flightState != null ? flightState : new FlightState());
        infoBoxManager.draw(canvas);
    }

    // ========== LONG-PRESS DIALOG ==========

    private void showMapLongPressDialog() {
        Context ctx = getContext();
        new AlertDialog.Builder(ctx)
            .setTitle("\u0414\u0435\u0439\u0441\u0442\u0432\u0438\u044F")
            .setItems(new String[]{
                "\u0422\u0440\u0435\u043A\u0438 (IGC)",
                "\u041D\u0430\u0441\u0442\u0440\u043E\u0439\u043A\u0438",
                "\u0412\u044B\u0445\u043E\u0434"
            }, (dialog, which) -> {
                switch (which) {
                    case 1:
                        ctx.startActivity(new Intent(ctx, SettingsActivity.class));
                        break;
                    case 2:
                        ((android.app.Activity) ctx).finishAffinity();
                        break;
                }
            })
            .setNegativeButton("\u041E\u0442\u043C\u0435\u043D\u0430", null)
            .show();
    }

    // ========== WIDGET LONG-PRESS (FlyMe-style) ==========

    public void onInfoBoxLongPress(float x, float y) {
        if (infoBoxManager == null) return;
        int idx = infoBoxManager.hitTest(x, y);
        if (idx < 0 || idx >= infoBoxManager.getBoxes().size()) return;

        final int boxIndex = idx;
        String currentName = infoBoxManager.getBoxes().get(idx).title;
        Context ctx = getContext();

        final String[] allNames = InfoBoxManager.WIDGET_NAMES;
        final boolean[] checked = new boolean[allNames.length];
        for (int i = 0; i < allNames.length; i++) {
            checked[i] = allNames[i].equals(currentName);
        }

        new AlertDialog.Builder(ctx)
            .setTitle("\u0412\u044B\u0431\u043E\u0440 \u0432\u0438\u0434\u0436\u0435\u0442\u0430")
            .setMultiChoiceItems(allNames, checked, (dialog, which, isChecked) -> {
                checked[which] = isChecked;
            })
            .setPositiveButton("OK", (dialog, which) -> {
                // Собираем выбранные
                java.util.ArrayList<String> selected = new java.util.ArrayList<>();
                for (int i = 0; i < allNames.length; i++) {
                    if (checked[i]) selected.add(allNames[i]);
                }
                if (selected.isEmpty()) return;

                if (selected.size() == 1) {
                    infoBoxManager.replaceBox(boxIndex, selected.get(0));
                } else {
                    // BoxSet — группа
                    StringBuilder sb = new StringBuilder();
                    for (String s : selected) sb.append(s).append(" ");
                    infoBoxManager.replaceBox(boxIndex, "Set " + sb.toString().trim());
                }
                infoBoxManager.saveLayout();
                invalidate();
            })
            .setNegativeButton("\u041E\u0442\u043C\u0435\u043D\u0430", null)
            .show();
    }

    public void onInfoBoxTap(float x, float y) {
        if (infoBoxManager != null) infoBoxManager.onWidgetTap(x, y);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return touchHandler.onTouch(event);
    }

    public MapEngine getMapEngine() { return mapEngine; }
    public TileLoader getTileLoader() { return tileLoader; }
}
