package com.example.android.sunshine.app.wear;

/**
 * Created by joaobiriba on 28/11/15.
 */
public class WeatherDataMessage {
    private String mWeatherUnit;
    private int mWeatherConditionId;
    private double mTemperatureMax;
    private double mTemperatureMin;

    public WeatherDataMessage(String weatherUnit, int weatherConditionId, double temperatureMax, double temperatureMin) {
        this.mWeatherUnit = weatherUnit;
        this.mWeatherConditionId = weatherConditionId;
        this.mTemperatureMax = temperatureMax;
        this.mTemperatureMin = temperatureMin;
    }

    public String getWeatherUnit() {
        return mWeatherUnit;
    }

    public int getWeatherConditionId() {
        return mWeatherConditionId;
    }

    public double getTemperatureMax() {
        return mTemperatureMax;
    }

    public double getTemperatureMin() {
        return mTemperatureMin;
    }
}