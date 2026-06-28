package com.thermalglider.i18n;

import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * I18n — локализация.
 * Загружает JSON-файлы перевода из ThermalGlider/i18n/.
 *
 * Раздел 25 ТЗ.
 */
public class I18n {

    private static final String I18N_DIR = "ThermalGlider/i18n/";
    private static final String[] SUPPORTED = {"ru", "en"};

    private String lang;
    private JSONObject strings;
    private String basePath;

    public I18n(String basePath, SharedPreferences prefs) {
        this.basePath = basePath;
        this.lang = prefs.getString("language", "ru");
        load();
    }

    private void load() {
        String path = basePath + "/" + I18N_DIR + lang + ".json";
        File f = new File(path);
        if (!f.exists()) {
            // Fallback на русский
            path = basePath + "/" + I18N_DIR + "ru.json";
            f = new File(path);
        }
        if (!f.exists()) {
            strings = new JSONObject();
            return;
        }
        try {
            BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append("\n");
            }
            br.close();
            strings = new JSONObject(sb.toString());
        } catch (Exception e) {
            strings = new JSONObject();
        }
    }

    /** Получение строки по ключу: get("widgets", "vario") → "Vario" */
    public String get(String... keys) {
        try {
            Object val = strings;
            for (String key : keys) {
                if (val instanceof JSONObject) {
                    val = ((JSONObject) val).get(key);
                } else {
                    return "";
                }
            }
            if (val instanceof String) return (String) val;
            return "";
        } catch (Exception e) {
            return "";
        }
    }

    /** Переключение языка */
    public void switchTo(String newLang) {
        if (!newLang.equals(lang)) {
            lang = newLang;
            load();
        }
    }

    public String getLang() { return lang; }
}
