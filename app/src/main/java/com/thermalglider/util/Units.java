package com.thermalglider.util;

/**
 * Units — система единиц.
 * Поддерживает метрические и имперские.
 *
 * Раздел 2.8 ТЗ.
 */
public class Units {

    public static final int METRIC = 0;
    public static final int IMPERIAL = 1;

    private int system = METRIC;

    // Коэффициенты пересчёта в базовые единицы (метры, м/с, км)
    public float altToM = 1.0f;
    public String altLabel = "м";
    public float speedToMps = 1.0f;
    public String speedLabel = "м/с";
    public float speedDisplayToMps = 1.0f / 3.6f; // для км/ч → м/с
    public String speedDisplayLabel = "км/ч";
    public float distToKm = 1.0f;
    public String distLabel = "км";
    public float varioToMps = 1.0f;
    public String varioLabel = "м/с";
    public boolean varioIsInteger = false;

    public Units() {
        setSystem(METRIC);
    }

    public void setSystem(int system) {
        this.system = system;
        if (system == METRIC) {
            altToM = 1.0f;
            altLabel = "м";
            speedToMps = 1.0f / 3.6f;  // км/ч → м/с
            speedLabel = "км/ч";
            speedDisplayToMps = 1.0f / 3.6f;
            speedDisplayLabel = "км/ч";
            distToKm = 1.0f;
            distLabel = "км";
            varioToMps = 1.0f;
            varioLabel = "м/с";
            varioIsInteger = false;
        } else {
            altToM = 3.28084f;         // м → футы
            altLabel = "ft";
            speedToMps = 1.0f / 0.514444f;  // м/с → узлы
            speedLabel = "kt";
            speedDisplayToMps = 1.0f / 0.514444f;
            speedDisplayLabel = "kt";
            distToKm = 1.0f / 1.852f;  // км → NM
            distLabel = "nm";
            varioToMps = 196.85f;       // м/с → ft/min
            varioLabel = "ft/min";
            varioIsInteger = true;
        }
    }

    public int getSystem() { return system; }

    /** Отображение скорости: м/с → км/ч или kt */
    public float displaySpeed(float speedMps) {
        return speedMps / speedToMps;
    }

    /** Отображение высоты: м → м или ft */
    public float displayAlt(float altM) {
        return altM * altToM;
    }

    /** Отображение расстояния: км → км или nm */
    public float displayDist(float distKm) {
        return distKm * distToKm;
    }

    /** Отображение варьо: м/с → м/с или ft/min */
    public float displayVario(float varioMps) {
        return varioMps * varioToMps;
    }

    /** Формат варьо: +2.3 м/с или +456 ft/min */
    public String formatVario(float varioMps) {
        float v = displayVario(varioMps);
        if (varioIsInteger) {
            return String.format("%+d", (int) v);
        }
        return String.format("%+.1f", v);
    }

    /** Формат скорости: 36 км/ч или 19 kt */
    public String formatSpeed(float speedMps) {
        return String.format("%.0f", displaySpeed(speedMps));
    }

    /** Формат высоты: 1234 м или 4050 ft */
    public String formatAlt(float altM) {
        return String.format("%.0f", displayAlt(altM));
    }

    /** Формат расстояния: 15.3 км или 8.3 nm */
    public String formatDist(float distKm) {
        return String.format("%.1f", displayDist(distKm));
    }

    /** Направление ветра: градусы → текст */
    public static String windDirText(float deg) {
        String[] dirs = {"N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
                         "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};
        int idx = (int) ((deg + 11.25f) / 22.5f) % 16;
        return dirs[idx];
    }
}
