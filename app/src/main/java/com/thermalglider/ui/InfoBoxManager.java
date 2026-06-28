package com.thermalglider.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Canvas;
import android.graphics.Paint;

import com.thermalglider.data.FlightState;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * InfoBoxManager — управление 9 виджетами (3×3).
 * Сохранение/загрузка layout.json, long-tap меню.
 *
 * Раздел 17 ТЗ.
 */
public class InfoBoxManager {

    private static final String LAYOUT_FILE = "ThermalGlider/config/layout.json";

    private final List<InfoBox> boxes = new ArrayList<>(9);
    private final Context context;
    private String basePath;

    // Параметры сетки (устанавливаются MapView)
    public float gridX, gridY, cellW, cellH;
    public int cols = 3, rows = 3;

    public static final String[] WIDGET_NAMES = {
        "Vario", "Alt", "Speed", "L/D", "Wind", "AGL", "Dist", "Time",
        "Max", "Climb", "Avg L/D", "Avg\u2191", "Head", "Glide", "To Goal",
        "ETA", "Terrain", "Thermal", "Circle", "AS", "AS Det", "Set"
    };

    // Кэшированные Paint'ы (не аллоцировать каждый кадр)
    private final Paint titlePaint = new Paint();
    private final Paint valuePaint = new Paint();
    private final Paint unitPaint = new Paint();

    public InfoBoxManager(Context context) {
        this.context = context;
        titlePaint.setAntiAlias(true);
        valuePaint.setAntiAlias(true);
        unitPaint.setAntiAlias(true);
        // Дефолтная раскладка (9 виджетов)
        setDefaultLayout();
    }

    public void init(String basePath) {
        this.basePath = basePath;
        loadLayout();
    }

    private void setDefaultLayout() {
        boxes.clear();
        boxes.add(new InfoBox.VarioBox());
        boxes.add(new InfoBox.AltBox());
        boxes.add(new InfoBox.SpeedBox());
        boxes.add(new InfoBox.LdBox());
        boxes.add(new InfoBox.WindBox());
        boxes.add(new InfoBox.AglBox());
        boxes.add(new InfoBox.HeadingBox());
        boxes.add(new InfoBox.NearThermalBox());
        boxes.add(new InfoBox.DistBox());
    }

    /** Обновление всех виджетов */
    public void update(FlightState state) {
        for (InfoBox box : boxes) {
            box.update(state);
        }
    }

    /** Отрисовка всех виджетов в сетке 3×3 */
    public void draw(Canvas canvas) {
        if (boxes.isEmpty()) return;

        for (int i = 0; i < boxes.size() && i < cols * rows; i++) {
            int r = i / cols;
            int c = i % cols;
            InfoBox box = boxes.get(i);
            box.rect.set(
                gridX + c * cellW,
                gridY + r * cellH,
                gridX + (c + 1) * cellW,
                gridY + (r + 1) * cellH
            );
            box.draw(canvas, titlePaint, valuePaint, unitPaint);
        }
    }

    /** Long-tap на виджете — показать диалог выбора */
    public void onWidgetLongPress(float x, float y) {
        int idx = hitTest(x, y);
        if (idx < 0 || idx >= boxes.size()) return;

        final int boxIndex = idx;
        String currentName = boxes.get(idx).title;

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Widget: " + currentName);
        builder.setItems(WIDGET_NAMES, (dialog, which) -> {
            replaceBox(boxIndex, WIDGET_NAMES[which]);
            saveLayout();
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    /** Тап по виджету — toggle expanded или выбор площадки */
    public void onWidgetTap(float x, float y) {
        int idx = hitTest(x, y);
        if (idx >= 0 && idx < boxes.size()) {
            InfoBox box = boxes.get(idx);
            if (box instanceof InfoBox.GoalDistBox || box instanceof InfoBox.GlideToGoalBox) {
                // Можно переключить целевую площадку
            }
        }
    }

    /** Какой виджет под координатой */
    public int hitTest(float x, float y) {
        for (int i = 0; i < boxes.size(); i++) {
            if (boxes.get(i).rect.contains(x, y)) return i;
        }
        return -1;
    }

    /** Замена виджета */
    public void replaceBox(int index, String name) {
        InfoBox newBox = createBox(name);
        if (newBox != null) {
            boxes.set(index, newBox);
        }
    }

    /** Фабрика виджетов */
    private InfoBox createBox(String name) {
        switch (name) {
            case "Vario": return new InfoBox.VarioBox();
            case "Alt": return new InfoBox.AltBox();
            case "Speed": return new InfoBox.SpeedBox();
            case "L/D": return new InfoBox.LdBox();
            case "Wind": return new InfoBox.WindBox();
            case "AGL": return new InfoBox.AglBox();
            case "Dist": return new InfoBox.DistBox();
            case "Time": return new InfoBox.TimeBox();
            case "Max": return new InfoBox.MaxAltBox();
            case "Climb": return new InfoBox.TotalClimbBox();
            case "Avg L/D": return new InfoBox.AvgLdBox();
            case "Avg\u2191": return new InfoBox.AvgClimbBox();
            case "Head": return new InfoBox.HeadingBox();
            case "Glide": return new InfoBox.GlideToGoalBox();
            case "To Goal": return new InfoBox.GoalDistBox();
            case "ETA": return new InfoBox.GoalTimeBox();
            case "Terrain": return new InfoBox.GroundElevBox();
            case "Thermal": return new InfoBox.NearThermalBox();
            case "Circle": return new InfoBox.SpeedCircleBox();
            case "AS": return new InfoBox.AirspaceBox();
            case "AS Det": return new InfoBox.AirspaceInfoBox();
            case "Set": return new InfoBox.BoxSet();
            default: return null;
        }
    }

    // === Сохранение/загрузка layout.json ===
    public void saveLayout() {
        if (basePath == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (InfoBox box : boxes) {
                arr.put(box.title);
            }
            JSONObject root = new JSONObject();
            root.put("boxes", arr);
            root.put("cols", cols);
            root.put("rows", rows);

            File file = new File(basePath + "/" + LAYOUT_FILE);
            file.getParentFile().mkdirs();
            OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8);
            w.write(root.toString(2));
            w.close();
        } catch (Exception ignored) {}
    }

    private void loadLayout() {
        if (basePath == null) return;
        File file = new File(basePath + "/" + LAYOUT_FILE);
        if (!file.exists()) return;

        try {
            BufferedReader r = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();

            JSONObject root = new JSONObject(sb.toString());
            JSONArray arr = root.getJSONArray("boxes");
            List<InfoBox> loaded = new ArrayList<>(9);
            for (int i = 0; i < arr.length(); i++) {
                InfoBox box = createBox(arr.getString(i));
                if (box != null) loaded.add(box);
            }
            if (loaded.size() == 9) {
                boxes.clear();
                boxes.addAll(loaded);
            }
        } catch (Exception ignored) {}
    }

    public List<InfoBox> getBoxes() { return boxes; }
}
