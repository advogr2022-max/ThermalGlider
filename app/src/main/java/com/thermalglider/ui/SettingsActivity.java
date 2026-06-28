package com.thermalglider.ui;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.File;

/**
 * SettingsActivity — полный экран настроек.
 *
 * Раздел 20 ТЗ.
 */
public class SettingsActivity extends Activity {

    private SharedPreferences prefs;
    private static final String PREFS_NAME = "thermalglider_settings";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(20, 20, 20, 20);
        root.setBackgroundColor(0xFF111111);

        addSection(root, "\u2699 \u041D\u0410\u0421\u0422\u0420\u041E\u0419\u041A\u0418");

        addCategory(root, "\u0414\u0418\u0421\u041F\u041B\u0415\u0419");
        addCheckbox(root, "full_screen", "\u041F\u043E\u043B\u043D\u043E\u044D\u043A\u0440\u0430\u043D\u043D\u044B\u0439 \u0440\u0435\u0436\u0438\u043C", false);
        addCheckbox(root, "show_vario_helpers", "\u0412\u0430\u0440\u044C\u043E-\u043F\u043E\u043C\u043E\u0449\u043D\u0438\u043A", true);
        addCheckbox(root, "show_glide_ellipse", "\u042D\u043B\u043B\u0438\u043F\u0441 \u0434\u043E\u043B\u0451\u0442\u0430", true);

        addCategory(root, "\u0412\u0410\u0420\u042C\u041E");
        addCheckbox(root, "kalman_filter", "\u041A\u0430\u043B\u043C\u0430\u043D\u043E\u0432\u0441\u043A\u0438\u0439 \u0444\u0438\u043B\u044C\u0442\u0440", true);
        addCheckbox(root, "vario_stabilize", "\u0421\u0442\u0430\u0431\u0438\u043B\u0438\u0437\u0430\u0446\u0438\u044F (Deadband)", true);
        addCheckbox(root, "energy_compensation", "\u042D\u043D\u0435\u0440\u0433\u0435\u0442\u0438\u0447\u0435\u0441\u043A\u0430\u044F \u043A\u043E\u043C\u043F\u0435\u043D\u0441\u0430\u0446\u0438\u044F", true);

        addCategory(root, "\u041F\u0410\u0420\u0410\u041F\u041B\u0410\u041D");
        addCheckbox(root, "enable_micro_detection", "\u041C\u0438\u043A\u0440\u043E\u0440\u0430\u0441\u043A\u0430\u0447\u043A\u0430", false);
        // Деактивируем чекбокс микрораскачки — она всегда выключена
        View microView = root.getChildAt(root.getChildCount() - 1);
        if (microView instanceof CheckBox) {
            ((CheckBox) microView).setEnabled(false);
        }

        addCategory(root, "\u0412\u042B\u0421\u041E\u0422\u041E\u041C\u0415\u0420");
        addLabel(root, "QNH: " + prefs.getFloat("qnh", 1013.25f) + " hPa");
        addLabel(root, "\u0412\u044B\u0441\u043E\u0442\u0430 \u043F\u043E\u043B\u044F: " + prefs.getFloat("field_elevation", 0) + " \u043C");

        addCategory(root, "\u0417\u0410\u041F\u0418\u0421\u042C");
        addLabel(root, "\u041F\u0438\u043B\u043E\u0442: " + prefs.getString("pilot_name", ""));
        addLabel(root, "\u041F\u0430\u0440\u0430\u043F\u043B\u0430\u043D: " + prefs.getString("glider_type", ""));

        addCategory(root, "\u041A\u0410\u0420\u0422\u042B");
        Button clearCache = new Button(this);
        clearCache.setText("\u041E\u0427\u0418\u0421\u0422\u0418\u0422\u042C \u041A\u042D\u0428");
        clearCache.setOnClickListener(v -> {
            String basePath = Environment.getExternalStorageDirectory().getAbsolutePath()
                + "/ThermalGlider/maps/cache";
            deleteDir(new File(basePath));
        });
        root.addView(clearCache);

        addCategory(root, "\u0421\u0418\u0421\u0422\u0415\u041C\u0410");
        addLabel(root, "\u0412\u0435\u0440\u0441\u0438\u044F: 0.5.0");

        // Кнопка Закрыть
        Button closeBtn = new Button(this);
        closeBtn.setText("OK");
        closeBtn.setOnClickListener(v -> finish());
        root.addView(closeBtn);

        scroll.addView(root);
        setContentView(scroll);
    }

    private void addSection(LinearLayout root, String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(24);
        tv.setTextColor(0xFFFFC107);
        tv.setPadding(0, 30, 0, 10);
        root.addView(tv);
    }

    private void addCategory(LinearLayout root, String title) {
        TextView tv = new TextView(this);
        tv.setText(title);
        tv.setTextSize(18);
        tv.setTextColor(0xFF2196F3);
        tv.setPadding(0, 20, 0, 5);
        root.addView(tv);
    }

    private void addCheckbox(LinearLayout root, String key, String label, boolean def) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setTextColor(0xFFFFFFFF);
        cb.setChecked(prefs.getBoolean(key, def));
        cb.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs.edit().putBoolean(key, isChecked).apply();
        });
        root.addView(cb);
    }

    private void addLabel(LinearLayout root, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(0xFFAAAAAA);
        tv.setPadding(20, 5, 0, 5);
        root.addView(tv);
    }

    private boolean deleteDir(File dir) {
        if (dir.isDirectory()) {
            String[] children = dir.list();
            if (children != null) {
                for (String child : children) {
                    deleteDir(new File(dir, child));
                }
            }
        }
        return dir.delete();
    }
}
