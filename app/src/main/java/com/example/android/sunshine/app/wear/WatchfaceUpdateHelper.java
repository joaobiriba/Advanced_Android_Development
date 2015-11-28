package com.example.android.sunshine.app.wear;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import com.example.android.sunshine.app.Utility;
import com.example.android.sunshine.app.data.WeatherContract;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;

/**
 * Created by joaobiriba on 28/11/15.
 */
public class WatchfaceUpdateHelper implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

    private static final String LOG_TAG = WatchfaceUpdateHelper.class.getSimpleName();

    public static final String PATH_WEATHER_DATA = "/weatherdata";

    private static final String KEY_CONDITION = "WEATHER_CONDITION";
    private static final String KEY_TEMP_MIN = "WEATHER_TEMP_MIN";
    private static final String KEY_TEMP_MAX = "WEATHER_TEMP_MAX";
    private static final String KEY_TEMP_UNIT = "WEATHER_TEMP_UNIT";

    private static final String[] WEAR_WEATHER_PROJECTION = new String[] {
            WeatherContract.WeatherEntry.COLUMN_WEATHER_ID,
            WeatherContract.WeatherEntry.COLUMN_MAX_TEMP,
            WeatherContract.WeatherEntry.COLUMN_MIN_TEMP,
    };

    private static final int INDEX_WEATHER_ID = 0;
    private static final int INDEX_MAX_TEMP = 1;
    private static final int INDEX_MIN_TEMP = 2;

    private GoogleApiClient mGoogleApiClient;
    private WeatherDataMessage mWeatherDataMessage;

    private WeakReference<Context> mContextRef;

    public WatchfaceUpdateHelper(Context context) {
        mContextRef = new WeakReference<>(context);

        mGoogleApiClient = new GoogleApiClient.Builder(context)
                .addApi(Wearable.API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();

        if (!mGoogleApiClient.isConnected()) {
            mGoogleApiClient.connect();
        }
    }

    @Override
    protected void finalize() throws Throwable {
        super.finalize();
        if(mGoogleApiClient != null
                && mGoogleApiClient.isConnected()) {
            mGoogleApiClient.disconnect();
        }
    }

    public void doUpdate() {
        Log.i(LOG_TAG, "Updating wear");

        if(mGoogleApiClient == null
                || !mGoogleApiClient.isConnected()) {
            Log.i(LOG_TAG, "No Google API connection => returning");
            return;
        }

        Context context = mContextRef.get();
        if(context == null) {
            return;
        }

        String locationQuery = Utility.getPreferredLocation(context);

        Uri weatherUri = WeatherContract.WeatherEntry.buildWeatherLocationWithDate(locationQuery, System.currentTimeMillis());

        // we'll query our contentProvider, as always
        Cursor cursor = context.getContentResolver().query(weatherUri, WEAR_WEATHER_PROJECTION, null, null, null);

        Log.i(LOG_TAG, "Querying weather data for wear");

        if (cursor.moveToFirst()) {
            Log.i(LOG_TAG, "Got data");
            int weatherId = cursor.getInt(INDEX_WEATHER_ID);
            double high = cursor.getDouble(INDEX_MAX_TEMP);
            double low = cursor.getDouble(INDEX_MIN_TEMP);

            boolean sendUpdate = true;

            WeatherDataMessage telegram = new WeatherDataMessage(
                    "C",
                    weatherId,
                    high,
                    low
            );

            //just to be sure not to use more power than needed
            if (telegram.equals(mWeatherDataMessage)) {
                sendUpdate = false;
            }

            if(sendUpdate) {
                Log.i(LOG_TAG, "sending update to wear");
                PutDataMapRequest mapRequest = PutDataMapRequest.create(PATH_WEATHER_DATA);
                DataMap dm = mapRequest.getDataMap();
                dm.putInt(KEY_CONDITION, telegram.getWeatherConditionId());
                dm.putDouble(KEY_TEMP_MIN, telegram.getTemperatureMin());
                dm.putDouble(KEY_TEMP_MAX, telegram.getTemperatureMax());
                dm.putString(KEY_TEMP_UNIT, telegram.getWeatherUnit());

                PutDataRequest request = mapRequest.asPutDataRequest();

                Wearable.DataApi.putDataItem(mGoogleApiClient, request);

                mWeatherDataMessage = telegram;
            } else {
                Log.i(LOG_TAG, "no wear update needed");
            }

        }
        cursor.close();
    }

    @Override
    public void onConnected(Bundle bundle) {

    }

    @Override
    public void onConnectionSuspended(int i) {

    }

    @Override
    public void onConnectionFailed(ConnectionResult connectionResult) {

    }
}