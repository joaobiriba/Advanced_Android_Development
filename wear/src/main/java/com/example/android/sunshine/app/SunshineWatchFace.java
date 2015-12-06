/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.util.TypedValue;
import android.view.SurfaceHolder;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Analog watch face with a ticking second hand. In ambient mode, the second hand isn't shown. On
 * devices with low-bit ambient mode, the hands are drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {
    /**
     * Update rate in milliseconds for interactive mode. We update once a second to advance the
     * second hand.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;
    private static final String LOG_TAG = SunshineWatchFace.class.getSimpleName();


    private static final String KEY_CONDITION = "WEATHER_CONDITION";
    private static final String KEY_TEMP_MIN = "WEATHER_TEMP_MIN";
    private static final String KEY_TEMP_MAX = "WEATHER_TEMP_MAX";
    private static final String KEY_TEMP_UNIT = "WEATHER_TEMP_UNIT";


    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mHandPaint;
        boolean mAmbient;
        Time mTime;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };
        int mTapCount;

        private final Resources mResources = getResources();


        private long mTimeMotionStart = -1;
        private long mTimeElapsed;
        private int mLoop;
        private Bitmap[] mCloudBitmaps;
        private int[] mCloudSpeeds;
        private int[] mCloudDegrees;
        private Paint[] mCloudFilterPaints;
        private Bitmap[] mRainBitmaps;
        private int[] mRainSpeeds;
        private int[] mRainDegrees;
        private Paint[] mRainFilterPaints;
        private Bitmap[] mSnowBitmaps;
        private int[] mSnowSpeeds;
        private int[] mSnowDegrees;
        private Paint[] mSnowFilterPaints;
        private float mRadius;

        GoogleApiClient mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFace.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();


        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;
        private double mTempMax;
        private double mTempMin;
        private int mWeather;


        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());

            Resources resources = SunshineWatchFace.this.getResources();

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mHandPaint = new Paint();
            mHandPaint.setColor(resources.getColor(R.color.analog_hands));
            mHandPaint.setStrokeWidth(resources.getDimension(R.dimen.analog_hand_stroke));
            mHandPaint.setAntiAlias(true);
            mHandPaint.setStrokeCap(Paint.Cap.ROUND);

            mTime = new Time();

            // Initialing cloud bitmaps and settings
            mCloudDegrees = mResources.getIntArray(R.array.cloudDegrees);
            mCloudBitmaps = loadBitmaps(R.array.cloudIds);
            mCloudSpeeds = mResources.getIntArray(R.array.cloudSpeed);
            mCloudFilterPaints = new Paint[mCloudBitmaps.length];

            // Initialing rain bitmaps and settings
            mRainDegrees = mResources.getIntArray(R.array.rainDegrees);
            mRainBitmaps = loadBitmaps(R.array.rainIds);
            mRainSpeeds = mResources.getIntArray(R.array.rainSpeed);
            mRainFilterPaints = new Paint[mRainBitmaps.length];

            // We need different paints because the alpha applied is different for different clouds
            for (int i = 0; i < mCloudBitmaps.length; i++) {
                Paint paint = new Paint();
                paint.setFilterBitmap(true);
                mCloudFilterPaints[i] = paint;
            }

            // We need different paints because the alpha applied is different for different clouds
            for (int i = 0; i < mRainBitmaps.length; i++) {
                Paint paint = new Paint();
                paint.setFilterBitmap(true);
                mRainFilterPaints[i] = paint;
            }

        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (!inAmbientMode) {
                // Watch has just been set to active mode.
                mTimeMotionStart = System.currentTimeMillis();
            }
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mHandPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            Resources resources = SunshineWatchFace.this.getResources();
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    mTapCount++;
                    mBackgroundPaint.setColor(resources.getColor(mTapCount % 2 == 0 ?
                            R.color.background : R.color.background2));
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            mTime.setToNow();
            Resources resources = SunshineWatchFace.this.getResources();


            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                if (mWeather == 800) {
                    mBackgroundPaint.setColor(resources.getColor(R.color.sun));
                } else {
                    mBackgroundPaint.setColor(resources.getColor(R.color.background));
                }


                canvas.drawRect(0, 0, canvas.getWidth(), canvas.getHeight(), mBackgroundPaint);
            }

            // Find the center. Ignore the window insets so that, on round watches with a
            // "chin", the watch face is centered on the entire screen, not just the usable
            // portion.
            float centerX = bounds.width() / 2f;
            float centerY = bounds.height() / 2f;

            float secRot = mTime.second / 30f * (float) Math.PI;
            int minutes = mTime.minute;
            float minRot = minutes / 30f * (float) Math.PI;
            float hrRot = ((mTime.hour + (minutes / 60f)) / 6f) * (float) Math.PI;

            float secLength = centerX - 20;
            float minLength = centerX - 40;
            float hrLength = centerX - 80;


            if (!mAmbient) {
                // Draw animation layer (above the background, below the figure and arms.)
                drawAnimationLayer(canvas, centerX, centerY);
            }


            if (!mAmbient) {
                float secX = (float) Math.sin(secRot) * secLength;
                float secY = (float) -Math.cos(secRot) * secLength;
                canvas.drawLine(centerX, centerY, centerX + secX, centerY + secY, mHandPaint);

                String tMin = String.format(getResources().getString(R.string.format_temperature), mTempMax);
                String tMax = String.format(getResources().getString(R.string.format_temperature), mTempMin);
                canvas.drawText("T Max " + tMax, centerX - 50, centerY + 80, mHandPaint);
                canvas.drawText("T Min " + tMin, centerX + 50, centerY + 80, mHandPaint);
                canvas.drawText(Integer.toString(mWeather), centerX + 80, centerY, mHandPaint);
            }

            float minX = (float) Math.sin(minRot) * minLength;
            float minY = (float) -Math.cos(minRot) * minLength;
            canvas.drawLine(centerX, centerY, centerX + minX, centerY + minY, mHandPaint);

            float hrX = (float) Math.sin(hrRot) * hrLength;
            float hrY = (float) -Math.cos(hrRot) * hrLength;
            canvas.drawLine(centerX, centerY, centerX + hrX, centerY + hrY, mHandPaint);
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();

                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                unregisterReceiver();

                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.d(LOG_TAG, "onDataChanged:  ");
            for (DataEvent dataEvent : dataEventBuffer) {
                if (dataEvent.getType() != DataEvent.TYPE_CHANGED) {
                    continue;
                }

                DataItem dataItem = dataEvent.getDataItem();
                if (!dataItem.getUri().getPath().equals(
                        SunshineWatchFaceUtil.PATH_WEATHER_DATA)) {
                    continue;
                }

                DataMapItem dataMapItem = DataMapItem.fromDataItem(dataItem);
                DataMap config = dataMapItem.getDataMap();
                if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                    Log.d(LOG_TAG, "Config DataItem updated:" + config);
                }
                updateUiForWeatherDataMap(config);
            }
        }


        private void updateUiForWeatherDataMap(final DataMap dm) {
            int conditionId = dm.getInt(KEY_CONDITION);
            double tempMin = dm.getDouble(KEY_TEMP_MIN);
            double tempMax = dm.getDouble(KEY_TEMP_MAX);
            String unitValue = dm.getString(KEY_TEMP_UNIT);
            mTempMax = tempMax;
            mTempMin = tempMin;
            mWeather = conditionId;
            Log.d(LOG_TAG, "updateUiForWeatherDataMap: " + conditionId + " " +
                    tempMin + " " +
                    tempMax + " " +
                    unitValue);
            invalidate();

        }


        @Override  // GoogleApiClient.ConnectionCallbacks
        public void onConnected(Bundle connectionHint) {
            if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                Log.d(LOG_TAG, "onConnected: " + connectionHint);
            }
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);
            //updateConfigDataItemAndUiOnStartup();
        }

        @Override  // GoogleApiClient.ConnectionCallbacks
        public void onConnectionSuspended(int cause) {
            if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                Log.d(LOG_TAG, "onConnectionSuspended: " + cause);
            }
        }

        @Override  // GoogleApiClient.OnConnectionFailedListener
        public void onConnectionFailed(ConnectionResult result) {
            if (Log.isLoggable(LOG_TAG, Log.DEBUG)) {
                Log.d(LOG_TAG, "onConnectionFailed: " + result);
            }
        }

        /**
         * Drawing the animated weather
         *
         * @param canvas  Canvas to be drawn on
         * @param centerX Center of the display
         * @param centerY Center of the display
         */
        private void drawAnimationLayer(Canvas canvas, float centerX, float centerY) {
            if (mAmbient) {
                // Do nothing - static background in ambient mode
                mTimeMotionStart = -1;
            } else {

                if (mTimeMotionStart < 0) {
                    mTimeMotionStart = System.currentTimeMillis();
                }

                mTimeElapsed = System.currentTimeMillis() - mTimeMotionStart;
                animateBackgroundForWeatherCondition(mWeather, canvas, centerX, centerY);
            }
        }

        /**
         * Loading an int array from resource file
         *
         * @param resId ResourceId of the integer array
         * @return int array
         */
        private int[] getIntArray(int resId) {
            TypedArray array = mResources.obtainTypedArray(resId);
            int[] rc = new int[array.length()];
            TypedValue value = new TypedValue();
            for (int i = 0; i < array.length(); i++) {
                array.getValue(i, value);
                rc[i] = value.resourceId;
            }
            return rc;
        }

        /**
         * Loading all versions (interactive, ambient and low bit) into a bitmap array. The correct
         * version will be pluck out at runtime.
         *
         * @param arrayId Key to the type of bitmap that we are initialising. The full list can be
         *                found in res/values/images_santa_watchface.xml
         * @return Array of three bitmaps for interactive, ambient and low bit modes
         */
        private Bitmap[] loadBitmaps(int arrayId) {
            int[] bitmapIds = getIntArray(arrayId);
            Bitmap[] bitmaps = new Bitmap[bitmapIds.length];
            for (int i = 0; i < bitmapIds.length; i++) {
                Drawable backgroundDrawable = mResources.getDrawable(bitmapIds[i]);
                bitmaps[i] = ((BitmapDrawable) backgroundDrawable).getBitmap();
            }
            return bitmaps;
        }

        public void animateBackgroundForWeatherCondition(int weatherId, Canvas canvas, float centerX, float centerY) {
            // Based on weather code data found at:
            // http://bugs.openweathermap.org/projects/api/wiki/Weather_Condition_Codes
            if (weatherId >= 200 && weatherId <= 232) {
                animateRain(canvas, centerX, centerY, 1); //storm
            } else if (weatherId >= 300 && weatherId <= 321) {
                animateRain(canvas, centerX, centerY, 0.2); //light_rain
            } else if (weatherId >= 500 && weatherId <= 504) {
                animateRain(canvas, centerX, centerY, 0.5); //rain
            } else if (weatherId == 511) {
                animateSnow(canvas, centerX, centerY, 0.5); //snow
            } else if (weatherId >= 520 && weatherId <= 531) {
                animateRain(canvas, centerX, centerY, 0.5); //rain
            } else if (weatherId >= 600 && weatherId <= 622) {
                animateSnow(canvas, centerX, centerY, 1); //snow
            } else if (weatherId >= 701 && weatherId <= 761) {
                animateFog(canvas, centerX, centerY);
            } else if (weatherId == 761 || weatherId == 781) {
                animateRain(canvas, centerX, centerY, 1); //storm
            } else if (weatherId == 800) {
                animateRain(canvas, centerX, centerY, 0); //clear
            } else if (weatherId == 801) {
                animateClouds(canvas, centerX, centerY, 0.5); //light clouds
            } else if (weatherId >= 802 && weatherId <= 804) {
                animateClouds(canvas, centerX, centerY, 1); //clouds
            }
        }

        public void animateClouds(Canvas canvas, float centerX, float centerY, double intensity) {
            for (mLoop = 0; mLoop < (mCloudBitmaps.length * intensity); mLoop++) {
                canvas.save();
                canvas.rotate(mCloudDegrees[mLoop], centerX, centerY);

                mRadius = centerX - (mTimeElapsed / (mCloudSpeeds[mLoop])) % centerX;
                mCloudFilterPaints[mLoop].setAlpha((int) (mRadius / centerX * 255));

                canvas.drawBitmap(mCloudBitmaps[mLoop], centerX, centerY - mRadius,
                        mCloudFilterPaints[mLoop]);

                canvas.restore();
            }
        }

        public void animateRain(Canvas canvas, float centerX, float centerY, double intensity) {
            for (mLoop = 0; mLoop < (mRainBitmaps.length * intensity); mLoop++) {
                canvas.save();
                canvas.translate(mRainDegrees[mLoop], 0);

                mRadius = centerX - (mTimeElapsed / (mRainSpeeds[mLoop])) % centerX;
                mRainFilterPaints[mLoop].setAlpha((int) (mRadius / centerX * 255));

                canvas.drawBitmap(mRainBitmaps[mLoop], centerX, centerY - mRadius,
                        mRainFilterPaints[mLoop]);

                canvas.restore();
            }
        }

        public void animateSnow(Canvas canvas, float centerX, float centerY, double intensity) {
            for (mLoop = 0; mLoop < (mSnowBitmaps.length * intensity); mLoop++) {
                canvas.save();
                canvas.translate(mSnowDegrees[mLoop], 0);

                mRadius = centerX - (mTimeElapsed / (mSnowSpeeds[mLoop])) % centerX;
                mSnowFilterPaints[mLoop].setAlpha((int) (mRadius / centerX * 255));

                canvas.drawBitmap(mSnowBitmaps[mLoop], centerX, centerY - mRadius,
                        mSnowFilterPaints[mLoop]);

                canvas.restore();
            }
        }

        public void animateFog(Canvas canvas, float centerX, float centerY) {
            for (mLoop = 0; mLoop < mCloudBitmaps.length; mLoop++) {
                canvas.save();
                canvas.translate(mRainDegrees[0], 0);

                mRadius = centerX - (mTimeElapsed / (mCloudSpeeds[mLoop])) % centerX;
                mCloudFilterPaints[0].setAlpha((int) (mRadius / centerX * 255));
                canvas.scale(mRadius, mRadius);
                canvas.drawBitmap(mCloudBitmaps[0], centerX, centerY - mRadius,
                        mCloudFilterPaints[0]);

                canvas.restore();
            }
        }
    }
}
