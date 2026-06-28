package com.thermalglider;

import com.thermalglider.data.FlightState;

/**
 * AppState — синглтон для обмена состоянием между компонентами.
 */
public class AppState {
    private static AppState instance;
    public final FlightState flightState = new FlightState();

    private AppState() {}

    public static synchronized AppState getInstance() {
        if (instance == null) instance = new AppState();
        return instance;
    }
}
