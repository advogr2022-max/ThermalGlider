package com.thermalglider;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.thermalglider.data.FlightState;
import com.thermalglider.data.LandingFieldDB;
import com.thermalglider.data.OpenAirParser;
import com.thermalglider.data.SystemStatus;
import com.thermalglider.i18n.I18n;
import com.thermalglider.igc.IgcReplay;
import com.thermalglider.power.PowerManager;
import com.thermalglider.ui.SettingsActivity;
import com.thermalglider.util.Units;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.file.Files;

/**
 * ThermalGlider — единственный экран.
 */
public class MainActivity extends android.app.Activity {

    public static final String ACTION_FLIGHT_UPDATE = "com.thermalglider.FLIGHT_UPDATE";
    private static final int PERMISSION_REQUEST_CODE = 100;

    private MapView mapView;
    private final FlightState flightState = AppState.getInstance().flightState;
    private Units units = new Units();
    private SystemStatus systemStatus = new SystemStatus();
    private LandingFieldDB fieldDB = new LandingFieldDB();
    private OpenAirParser airspaceParser = new OpenAirParser();
    private I18n i18n;
    private PowerManager powerManager;
    private IgcReplay igcReplay;
    private static final int REQUEST_IGC_OPEN = 200;
    private static final String TAG = "MainActivity";

    private final BroadcastReceiver flightReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_FLIGHT_UPDATE.equals(intent.getAction())) {
                mapView.updateFlightData(flightState, units, systemStatus);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mapView = new MapView(this);
        setContentView(mapView);

        // Колбэки long-press диалога
        mapView.setLongPressActionListener(new MapView.LongPressActionListener() {
            @Override
            public void onTracksClicked() {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                startActivityForResult(intent, REQUEST_IGC_OPEN);
            }
            @Override
            public void onSettingsClicked() {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
            @Override
            public void onExitClicked() {
                finishAffinity();
            }
        });

        powerManager = new PowerManager(this);

        // Инициализация директорий
        initDirectories();

        // Инициализация i18n
        java.io.File extDir = getExternalFilesDir(null);
        String basePath = extDir != null
            ? extDir.getAbsolutePath()
            : getFilesDir().getAbsolutePath();
        i18n = new I18n(basePath, getPreferences(MODE_PRIVATE));

        // Инициализация БД площадок
        fieldDB.init(basePath);

        // Инициализация airspace
        String airspacePath = basePath + "/ThermalGlider/airspaces/";
        File airspaceDir = new File(airspacePath);
        if (airspaceDir.exists()) {
            File[] files = airspaceDir.listFiles((d, name) -> name.endsWith(".txt") || name.endsWith(".openair"));
            if (files != null) {
                for (File f : files) {
                    airspaceParser.parse(f.getAbsolutePath());
                }
            }
        }

        // Регистрация ресивера — RECEIVER_NOT_EXPORTED на всех API 26+
        // (на 26-32 флаг игнорируется, на 33+ защищает от внешних broadcast)
        registerReceiver(flightReceiver, new IntentFilter(ACTION_FLIGHT_UPDATE),
            Context.RECEIVER_NOT_EXPORTED);

        checkPermissionsAndStart();
    }

    private void initDirectories() {
        // Используем getExternalFilesDir — не требует MANAGE_EXTERNAL_STORAGE
        java.io.File extDir = getExternalFilesDir(null);
        String basePath = extDir != null
            ? extDir.getAbsolutePath() + "/ThermalGlider"
            : getFilesDir().getAbsolutePath() + "/ThermalGlider";
        String[] dirs = {"igc", "maps/cache/osm", "maps/cache/satellite", "fields", "config", "waypoints", "i18n", "logs", "airspaces"};
        for (String dir : dirs) {
            new File(basePath + "/" + dir).mkdirs();
        }
        // Копирование дефолтных файлов из assets
        copyAssetToStorage("fields/landing_fields.txt", basePath + "/fields/landing_fields.txt");
        copyAssetToStorage("i18n/ru.json", basePath + "/i18n/ru.json");
        copyAssetToStorage("i18n/en.json", basePath + "/i18n/en.json");
    }

    private void copyAssetToStorage(String assetPath, String destPath) {
        File dest = new File(destPath);
        if (dest.exists()) return;
        try {
            InputStream is = getAssets().open(assetPath);
            FileOutputStream os = new FileOutputStream(dest);
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            is.close();
            os.close();
        } catch (Exception ignored) {}
    }

    private void checkPermissionsAndStart() {
        String[] needed = getNeededPermissions();
        boolean allGranted = true;
        for (String p : needed) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }
        if (allGranted) {
            startBgService();
        } else {
            ActivityCompat.requestPermissions(this, needed, PERMISSION_REQUEST_CODE);
        }
    }

    private String[] getNeededPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            };
        } else if (Build.VERSION.SDK_INT >= 31) {
            return new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                Manifest.permission.BLUETOOTH_CONNECT
            };
        } else if (Build.VERSION.SDK_INT >= 29) {
            return new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            };
        } else {
            return new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            };
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(code, permissions, grantResults);
        if (code == PERMISSION_REQUEST_CODE) {
            // Достаточно хотя бы ACCESS_FINE_LOCATION
            boolean hasGps = false;
            for (int i = 0; i < permissions.length; i++) {
                if (Manifest.permission.ACCESS_FINE_LOCATION.equals(permissions[i])
                    && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    hasGps = true;
                    break;
                }
            }
            if (hasGps) {
                initDirectories();
                startBgService();
            } else {
                Toast.makeText(this, "GPS разрешение необходимо", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startBgService() {
        Intent intent = new Intent(this, BackgroundService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_IGC_OPEN && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            startIgcReplay(data.getData());
        }
    }

    /** Запуск реплея из IGC-файла */
    private void startIgcReplay(Uri igcUri) {
        try {
            // Копируем во внутренний кэш
            InputStream is = getContentResolver().openInputStream(igcUri);
            if (is == null) {
                Toast.makeText(this, "Не удалось открыть файл", Toast.LENGTH_LONG).show();
                return;
            }
            File tmp = new File(getCacheDir(), "replay_" + System.currentTimeMillis() + ".igc");
            FileOutputStream fos = new FileOutputStream(tmp);
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
            fos.close();
            is.close();

            if (igcReplay != null && igcReplay.isPlaying()) {
                igcReplay.stop();
            }

            igcReplay = new IgcReplay();
            igcReplay.setCallback(new IgcReplay.ReplayCallback() {
                @Override
                public void onReplayFix(double lat, double lon, float altGps, float altPress,
                                        float speed, float bearing, long simTimeMs) {
                    FlightState fs = AppState.getInstance().flightState;
                    fs.latitude = lat;
                    fs.longitude = lon;
                    fs.gpsAltitude = altGps;
                    fs.baroAltitude = altPress > 0 ? altPress : altGps;
                    fs.speed = speed;
                    fs.bearing = bearing;
                    fs.hasGpsFix = true;
                    fs.isReplayMode = true;
                    fs.lastUpdateMs = simTimeMs;
                }

                @Override
                public void onReplayProgress(int index, int total) {
                }

                @Override
                public void onReplayFinished() {
                    FlightState fs = AppState.getInstance().flightState;
                    fs.isReplayMode = false;
                    fs.hasGpsFix = false;
                    runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "Реплей завершён", Toast.LENGTH_SHORT).show());
                }
            });

            if (igcReplay.load(tmp.getAbsolutePath())) {
                AppState.getInstance().flightState.reset();
                igcReplay.start();
                Toast.makeText(this, "Реплей запущен (" + igcReplay.getTotal() + " точек)",
                    Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Файл не содержит IGC-данных", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            android.util.Log.e(TAG, "Replay start failed", e);
            Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        if (powerManager != null) powerManager.onUserInteraction();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(flightReceiver);
        } catch (Exception ignored) {}
        stopService(new Intent(this, BackgroundService.class));
    }

    public FlightState getFlightState() { return flightState; }
    public Units getUnits() { return units; }
    public LandingFieldDB getFieldDB() { return fieldDB; }
    public SystemStatus getSystemStatus() { return systemStatus; }
}
