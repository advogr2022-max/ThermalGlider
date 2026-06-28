package com.thermalglider.gps;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.GnssStatus;
import android.location.GpsStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;

import com.thermalglider.data.FlightState;

/**
 * GpsManager — Android LocationManager.
 * GPS_PROVIDER, 1 Гц, фильтр точности <100м, спутники.
 *
 * Раздел 3 ТЗ.
 */
public class GpsManager implements LocationListener {

    private static final long MIN_TIME_MS = 1000;
    private static final float MIN_DISTANCE_M = 0;
    private static final float MAX_ACCURACY_M = 100;

    private LocationManager locationManager;
    private final FlightState flightState;
    private Context context;
    private boolean isListening = false;
    private int satelliteCount = 0;

    // GnssStatus callback (API 24+)
    private GnssStatus.Callback gnssCallback;

    public GpsManager(FlightState flightState) {
        this.flightState = flightState;
    }

    @SuppressLint("MissingPermission")
    public void start(Context context) {
        this.context = context;
        locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) return;

        try {
            // GPS_PROVIDER — основной источник (на эмуляторе может не быть, ловим Exception)
            try {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    MIN_TIME_MS,
                    MIN_DISTANCE_M,
                    this
                );
            } catch (Exception ignored) {}

            // Network provider для быстрого первого fix (до GPS)
            try {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    MIN_TIME_MS * 2,
                    MIN_DISTANCE_M,
                    this
                );
            } catch (Exception ignored) {}

            // GnssStatus (API 24+)
            if (Build.VERSION.SDK_INT >= 24) {
                try {
                    gnssCallback = new GnssStatus.Callback() {
                        @Override
                        public void onSatelliteStatusChanged(GnssStatus status) {
                            int count = 0;
                            for (int i = 0; i < status.getSatelliteCount(); i++) {
                                if (status.usedInFix(i)) count++;
                            }
                            satelliteCount = count;
                            flightState.satelliteCount = count;
                        }
                    };
                    locationManager.registerGnssStatusCallback(gnssCallback);
                } catch (Exception ignored) {}
            }

            isListening = true;
        } catch (SecurityException e) {
            flightState.hasGpsFix = false;
        }
    }

    @SuppressLint("MissingPermission")
    public void stop() {
        if (locationManager != null && isListening) {
            locationManager.removeUpdates(this);
            if (gnssCallback != null && Build.VERSION.SDK_INT >= 24) {
                locationManager.unregisterGnssStatusCallback(gnssCallback);
            }
        }
        isListening = false;
    }

    @Override
    public void onLocationChanged(Location loc) {
        if (loc == null) return;

        // Фильтр точности
        if (!loc.hasAccuracy() || loc.getAccuracy() > MAX_ACCURACY_M) {
            return;
        }

        flightState.latitude = loc.getLatitude();
        flightState.longitude = loc.getLongitude();
        flightState.gpsAltitude = (float) loc.getAltitude();
        flightState.speed = loc.hasSpeed() ? loc.getSpeed() : flightState.speed;
        flightState.bearing = loc.hasBearing() ? loc.getBearing() : flightState.bearing;
        flightState.gpsAccuracy = loc.getAccuracy();
        flightState.satelliteCount = satelliteCount;
        flightState.hasGpsFix = true;
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        if (provider.equals(LocationManager.GPS_PROVIDER)) {
            flightState.hasGpsFix = (status == GpsStatus.GPS_EVENT_FIRST_FIX
                || status == android.location.LocationProvider.AVAILABLE);
        }
    }

    @Override
    public void onProviderEnabled(String provider) {
        if (LocationManager.GPS_PROVIDER.equals(provider)) {
            flightState.hasGpsFix = true;
        }
    }

    @Override
    public void onProviderDisabled(String provider) {
        if (LocationManager.GPS_PROVIDER.equals(provider)) {
            flightState.hasGpsFix = false;
        }
    }

    public boolean isListening() { return isListening; }
}
