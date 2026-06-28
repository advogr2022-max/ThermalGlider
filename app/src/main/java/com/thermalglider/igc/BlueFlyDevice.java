package com.thermalglider.igc;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.UUID;

/**
 * BlueFlyDevice — Bluetooth SPP подключение к BlueFly Vario.
 *
 * Раздел 22.1 ТЗ.
 */
public class BlueFlyDevice {

    private static final String TAG = "BlueFly";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private BluetoothSocket socket;
    private BufferedReader reader;
    private boolean connected = false;

    public interface BlueFlyCallback {
        void onBlueFlyData(float altitude, float vario, float pressure);
    }

    private BlueFlyCallback callback;

    public BlueFlyDevice() {}

    public void setCallback(BlueFlyCallback cb) { this.callback = cb; }

    /** Подключение по MAC-адресу */
    public boolean connect(String macAddress) {
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) return false;

            BluetoothDevice device = adapter.getRemoteDevice(macAddress);
            socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
            socket.connect();

            InputStream is = socket.getInputStream();
            reader = new BufferedReader(new InputStreamReader(is));
            connected = true;

            // Поток чтения
            new Thread(this::readLoop).start();
            Log.d(TAG, "Connected to BlueFly: " + macAddress);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "BlueFly connect failed", e);
            connected = false;
            return false;
        }
    }

    private void readLoop() {
        try {
            String line;
            while (connected && (line = reader.readLine()) != null) {
                if (line.startsWith("$BFV")) {
                    parseBfvLine(line);
                }
            }
        } catch (Exception ignored) {}
    }

    /** $BFV,altitude,vario,pressure,... */
    private void parseBfvLine(String line) {
        try {
            String[] parts = line.split(",");
            if (parts.length >= 4) {
                float altitude = Float.parseFloat(parts[1]);
                float vario = Float.parseFloat(parts[2]);
                float pressure = parts[3].length() > 0 ? Float.parseFloat(parts[3]) : 0;

                if (callback != null) {
                    callback.onBlueFlyData(altitude, vario, pressure);
                }
            }
        } catch (Exception ignored) {}
    }

    public void disconnect() {
        connected = false;
        try {
            if (reader != null) reader.close();
            if (socket != null) socket.close();
        } catch (Exception ignored) {}
    }

    public boolean isConnected() { return connected; }
}
