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
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFaceService extends CanvasWatchFaceService {
    private static final String TAG = "SunshineWFService";

    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a minute since seconds won't
     * appear neither on ambient nor interactive mode
     */
    private static final long NORMAL_UPDATE_RATE_MS = TimeUnit.MINUTES.toMillis(1);

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine implements
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener{
        // DataItem Keys
        private static final String DATAITEM_TEMP_PATH = "/DATAITEM_TEMP_PATH";
        private static final String DATAITEM_TEMP_MAX = "DATAITEM_TEMP_MAX";
        private static final String DATAITEM_TEMP_MIN = "DATAITEM_TEMP_MIN";
        private static final String DATAITEM_TEMP_IMAGE = "DATAITEM_TEMP_IMAGE";

        static final String COLON_STRING = ":";

        /** Alpha value for drawing time when in mute mode. */
        static final int MUTE_ALPHA = 100;

        /** Alpha value for drawing time when not in mute mode. */
        static final int NORMAL_ALPHA = 255;

        /**
         * Handler message id for updating the time periodically in interactive mode.
         */
        private static final int MSG_UPDATE_TIME = 0;

        /** How often {@link #mUpdateTimeHandler} ticks in milliseconds. */
        long mInteractiveUpdateRateMs = NORMAL_UPDATE_RATE_MS;

        /* Handler to update the time periodically in interactive mode */
        final Handler mUpdateTimeHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MSG_UPDATE_TIME:
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.v(TAG, "updating time");
                        }
                        invalidate();
                        /*if (shouldTimerBeRunning()) {
                            long timeMs = System.currentTimeMillis();
                            long delayMs =
                                    mInteractiveUpdateRateMs - (timeMs % mInteractiveUpdateRateMs);
                            mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
                        }*/
                        break;
                }
            }
        };

        GoogleApiClient mGoogleApiClient;

        /**
         * Handles time zone and locale changes.
         */
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                initFormats();
                invalidate();
            }
        };

        /**
         * Unregistering an unregistered receiver throws an exception. Keep track of the
         * registration state to prevent that.
         */
        boolean mRegisteredTimeZoneReceiver = false;

        Paint mBackgroundPaint;
        Paint mHourPaint;
        Paint mColonPaint;
        Paint mMinPaint;
        Paint mAmPmPaint;
        Paint mDatePaint;
        Paint mLinePaint;
        Paint mMaxTempPaint;
        Paint mMinTempPaint;
        float mColonWidth;
        boolean mMute;

        String mAmString;
        String mPmString;

        Calendar mCalendar;
        Date mDate;
        SimpleDateFormat mDayOfWeekFormat;
        SimpleDateFormat mMonthFormat;
        SimpleDateFormat mYearFormat;

        java.text.DateFormat mDateFormat;

        float mYOffset;
        float mLineHeight;
        float mLineWidth;

        int mInteractiveBackgroundColor;
        int mInteractiveTimeColor;
        int mInteractiveDateColor;
        int mInteractiveLineColor;
        int mInteractiveMaxTempColor;
        int mInteractiveMinTempColor;

        /*
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        private double mMaxTemp;
        private double mMinTemp;
        private Bitmap mBitmap;

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            invalidate();
        }

        @Override
        public void onCreate(SurfaceHolder holder) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onCreate");
            }
            super.onCreate(holder);

            mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFaceService.this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API)
                    .build();

            Resources resources = SunshineWatchFaceService.this.getResources();

            mMaxTemp = 0;
            mMinTemp = 0;
            Drawable backgroundDrawable = resources.getDrawable(R.drawable.art_clear, null);
            mBitmap = ((BitmapDrawable) backgroundDrawable).getBitmap();
            /*Bitmap bitmap = BitmapFactory.decodeResource(resources,
                    R.drawable.ic_default_weather);*/

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_SHORT)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());

            mAmString = resources.getString(R.string.digital_am);
            mPmString = resources.getString(R.string.digital_pm);

            mInteractiveBackgroundColor = resources.getColor(R.color.background);
            mInteractiveTimeColor = resources.getColor(R.color.white_text);
            mInteractiveDateColor = resources.getColor(R.color.grey_text);
            mInteractiveLineColor = resources.getColor(R.color.grey_text);
            mInteractiveMaxTempColor = resources.getColor(R.color.white_text);
            mInteractiveMinTempColor = resources.getColor(R.color.grey_text);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(mInteractiveBackgroundColor);

            mHourPaint = createTextPaint(resources.getColor(R.color.white_text), BOLD_TYPEFACE);
            mMinPaint = createTextPaint(resources.getColor(R.color.white_text));
            mColonPaint = createTextPaint(resources.getColor(R.color.white_text), BOLD_TYPEFACE);
            mAmPmPaint = createTextPaint(resources.getColor(R.color.white_text), BOLD_TYPEFACE);
            mDatePaint = createTextPaint(resources.getColor(R.color.grey_text));
            mLinePaint = createTextPaint(resources.getColor(R.color.grey_text));
            mMaxTempPaint = createTextPaint(resources.getColor(R.color.white_text));
            mMinTempPaint = createTextPaint(resources.getColor(R.color.grey_text));

            mCalendar = Calendar.getInstance();
            mDate = new Date();

            initFormats();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int defaultInteractiveColor) {
            return createTextPaint(defaultInteractiveColor, NORMAL_TYPEFACE);
        }

        private Paint createTextPaint(int defaultInteractiveColor, Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(defaultInteractiveColor);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onVisibilityChanged: " + visible);
            }
            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();

                registerReceiver();

                // Update time zone and date formats, in case they changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                initFormats();
            } else {
                unregisterReceiver();

                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, onDataChangedListener);
                    mGoogleApiClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void initFormats() {
            mDayOfWeekFormat = new SimpleDateFormat("EEE", Locale.getDefault());
            mDayOfWeekFormat.setCalendar(mCalendar);

            mMonthFormat = new SimpleDateFormat("MMM dd", Locale.getDefault());
            mMonthFormat.setCalendar(mCalendar);

            mYearFormat = new SimpleDateFormat("yyyy", Locale.getDefault());
            mYearFormat.setCalendar(mCalendar);

            //SimpleDateFormat mDateFormat = new SimpleDateFormat();
            //mDateFormat = DateFormat.getMediumDateFormat(SunshineWatchFaceService.this);
            //mDateFormat.setCalendar(mCalendar);
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            filter.addAction(Intent.ACTION_LOCALE_CHANGED);
            SunshineWatchFaceService.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFaceService.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onApplyWindowInsets: " + (insets.isRound() ? "round" : "square"));
            }
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFaceService.this.getResources();
            boolean isRound = insets.isRound();
            float timeSize = resources.getDimension(isRound
                    ? R.dimen.time_textsize_round : R.dimen.time_textsize);
            float dateSize = resources.getDimension(isRound
                    ? R.dimen.date_textsize_round : R.dimen.date_textsize);
            float tempSize = resources.getDimension(isRound
                    ? R.dimen.temp_textsize_round : R.dimen.temp_textsize);
            float ampmSize = resources.getDimension(isRound
                    ? R.dimen.ampm_textsize_round : R.dimen.ampm_textsize);
            mLineWidth = resources.getDimension(isRound
                    ? R.dimen.digital_line_width_round : R.dimen.digital_line_width);
            mLineHeight = resources.getDimension(isRound
                    ? R.dimen.digital_line_height_round : R.dimen.digital_line_height);
            mYOffset = resources.getDimension(isRound
                    ? R.dimen.digital_y_offset_round : R.dimen.digital_y_offset);

            mHourPaint.setTextSize(timeSize);
            mMinPaint.setTextSize(timeSize);
            mColonPaint.setTextSize(timeSize);
            mAmPmPaint.setTextSize(ampmSize);
            mDatePaint.setTextSize(dateSize);
            mMaxTempPaint.setTextSize(tempSize);
            mMinTempPaint.setTextSize(tempSize);

            mColonWidth = mColonPaint.measureText(COLON_STRING);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);

            boolean burnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false);
            mHourPaint.setTypeface(burnInProtection ? NORMAL_TYPEFACE : BOLD_TYPEFACE);

            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);

            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onPropertiesChanged: burn-in protection = " + burnInProtection
                        + ", low-bit ambient = " + mLowBitAmbient);
            }
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onTimeTick: ambient = " + isInAmbientMode());
            }
            invalidate();
        }

        // Umcomplete: should change colors back when interactive mode
        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onAmbientModeChanged: " + inAmbientMode);
            }
            adjustPaintColorToCurrentMode(mBackgroundPaint, mInteractiveBackgroundColor,
                    SunshineWatchFaceUtils.COLOR_VALUE_DEFAULT_AND_AMBIENT_BACKGROUND);

            adjustPaintColorToCurrentMode(mHourPaint, mInteractiveTimeColor,
                    SunshineWatchFaceUtils.COLOR_VALUE_DEFAULT_AND_AMBIENT_TIME);

            adjustPaintColorToCurrentMode(mMinPaint, mInteractiveTimeColor,
                    SunshineWatchFaceUtils.COLOR_VALUE_DEFAULT_AND_AMBIENT_TIME);

            adjustPaintColorToCurrentMode(mColonPaint, mInteractiveTimeColor,
                    SunshineWatchFaceUtils.COLOR_VALUE_DEFAULT_AND_AMBIENT_TIME);

            adjustPaintColorToCurrentMode(mAmPmPaint, mInteractiveTimeColor,
                    SunshineWatchFaceUtils.COLOR_VALUE_DEFAULT_AND_AMBIENT_TIME);

            adjustPaintColorToCurrentMode(mDatePaint, mInteractiveDateColor,
                    SunshineWatchFaceUtils.COLOR_VALUE_DEFAULT_AND_AMBIENT_DATE);

            adjustPaintColorToCurrentMode(mLinePaint, mInteractiveLineColor,
                    SunshineWatchFaceUtils.COLOR_VALUE_DEFAULT_AND_AMBIENT_LINE);

            adjustPaintColorToCurrentMode(mMaxTempPaint, mInteractiveMaxTempColor,
                    SunshineWatchFaceUtils.COLOR_VALUE_DEFAULT_AND_AMBIENT_MAXTEMP);

            adjustPaintColorToCurrentMode(mMinTempPaint, mInteractiveMinTempColor,
                    SunshineWatchFaceUtils.COLOR_VALUE_DEFAULT_AND_AMBIENT_MINTEMP);

            if (mLowBitAmbient) {
                boolean antiAlias = !inAmbientMode;
                mHourPaint.setAntiAlias(antiAlias);
                mColonPaint.setAntiAlias(antiAlias);
                mMinPaint.setAntiAlias(antiAlias);
                mDatePaint.setAntiAlias(antiAlias);
                mLinePaint.setAntiAlias(antiAlias);
                mMaxTempPaint.setAntiAlias(antiAlias);
                mMinTempPaint.setAntiAlias(antiAlias);
            }
            invalidate();

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void adjustPaintColorToCurrentMode(Paint paint, int interactiveColor,
                                                   int ambientColor) {
            paint.setColor(isInAmbientMode() ? ambientColor : interactiveColor);
        }

        @Override
        public void onInterruptionFilterChanged(int interruptionFilter) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onInterruptionFilterChanged: " + interruptionFilter);
            }
            super.onInterruptionFilterChanged(interruptionFilter);

            boolean inMuteMode = interruptionFilter == WatchFaceService.INTERRUPTION_FILTER_NONE;

            if (mMute != inMuteMode) {
                mMute = inMuteMode;
                int alpha = inMuteMode ? MUTE_ALPHA : NORMAL_ALPHA;
                mDatePaint.setAlpha(alpha);
                mHourPaint.setAlpha(alpha);
                mMinPaint.setAlpha(alpha);
                mColonPaint.setAlpha(alpha);
                mMaxTempPaint.setAlpha(alpha);
                mMinTempPaint.setAlpha(alpha);
                invalidate();
            }
        }

        private void updatePaintIfInteractive(Paint paint, int interactiveColor) {
            if (!isInAmbientMode() && paint != null) {
                paint.setColor(interactiveColor);
            }
        }

        private String formatTwoDigitNumber(int hour) {
            return String.format("%02d", hour);
        }

        private String getAmPmString(int amPm) {
            return amPm == Calendar.AM ? mAmString : mPmString;
        }

        // Not done yet
        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            Log.d(TAG, "Drawing");
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            mDate.setTime(now);

            boolean is24Hour = DateFormat.is24HourFormat(SunshineWatchFaceService.this);

            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(SunshineWatchFaceUtils.COLOR_VALUE_DEFAULT_AND_AMBIENT_BACKGROUND);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Setting offsets
            final float xCenter = (canvas.getWidth() / 2);
            float y = mYOffset;
            float xOffset;
            float centerTextWidth;

            // Drawing Colon First at the center
            centerTextWidth = mColonPaint.measureText(COLON_STRING);
            canvas.drawText(COLON_STRING, xCenter - (centerTextWidth / 2), y, mColonPaint);

            // Draw Hours
            String hourString;
            if (is24Hour) {
                hourString = formatTwoDigitNumber(mCalendar.get(Calendar.HOUR_OF_DAY));
            } else {
                int hour = mCalendar.get(Calendar.HOUR);
                if (hour == 0) {
                    hour = 12;
                }
                hourString = String.valueOf(hour);
            }
            xOffset = (centerTextWidth / 2) + mHourPaint.measureText(hourString);
            canvas.drawText(hourString, xCenter - xOffset, y, mHourPaint);

            // Draw Minutes
            String minuteString = formatTwoDigitNumber(mCalendar.get(Calendar.MINUTE));
            canvas.drawText(minuteString, xCenter + (centerTextWidth / 2), y, mMinPaint);

            // Draw Am Pm
            if (!is24Hour) {
                xOffset = (centerTextWidth / 2) + mMinPaint.measureText(minuteString) + (centerTextWidth / 2);
                canvas.drawText(getAmPmString(
                        mCalendar.get(Calendar.AM_PM)), xCenter + xOffset, mYOffset, mAmPmPaint);
            }

            // update y offset for a new line
            y += mLineHeight;

            // Date
            String date = mDayOfWeekFormat.format(mDate) + ", " + mMonthFormat.format(mDate) +
                    " " + mYearFormat.format(mDate);
            centerTextWidth = mDatePaint.measureText(date);
            canvas.drawText(
                    date,
                    xCenter - (centerTextWidth / 2), y, mDatePaint);
            y += mLineHeight;

            // Only render the line and temps if there is no peek card, so they do not bleed
            // into each other in ambient mode.
            if (getPeekCardPosition().isEmpty()) {
                // Line
                Paint paint = new Paint();
                paint.setColor(mInteractiveDateColor);
                paint.setTypeface(NORMAL_TYPEFACE);
                canvas.drawLine(xCenter - mLineWidth, y, xCenter + mLineWidth, y, paint);
                y += mLineHeight;

                // High temp
                String maxTemp = String.format(SunshineWatchFaceService.this.
                        getResources().getString(R.string.format_temperature), mMaxTemp);
                centerTextWidth = mMaxTempPaint.measureText(maxTemp);
                canvas.drawText(
                        maxTemp,
                        xCenter - (centerTextWidth / 2), y, mMaxTempPaint);

                // Low Temp
                String minTemp = String.format(SunshineWatchFaceService.this.
                        getResources().getString(R.string.format_temperature), mMinTemp);
                xOffset = (centerTextWidth / 2) + mLineWidth;
                canvas.drawText(
                        minTemp,
                        xCenter + xOffset, y, mMinTempPaint);

                // Image
                Resources resources = SunshineWatchFaceService.this.getResources();
                int tempTextHeight = (int) mMaxTempPaint.measureText(maxTemp);
                int imageSize =  tempTextHeight + 20; //resources.getDimensionPixelSize(R.dimen.image_size);
                mBitmap = Bitmap.createScaledBitmap(mBitmap,
                        imageSize, imageSize, true  /*filter*/ );
                Bitmap adjustedBitmap = mBitmap;

                if (isInAmbientMode()) {
                    adjustedBitmap = toGreyScale(mBitmap);
                }

                xOffset = (centerTextWidth / 2) + mLineWidth + imageSize;
                float yOffset = (tempTextHeight / 2) + 20;

                canvas.drawBitmap(adjustedBitmap, xCenter - xOffset, y - yOffset, null);

                /*xOffset = (centerTextWidth / 2) - mLineWidth - mLineWidth;
                Bitmap bitmap = BitmapFactory.decodeResource(getResources(), R.drawable.preview_digital_circular);
                bitmap.setWidth((int) mLineWidth);
                bitmap.setHeight((int) mLineWidth);
                Paint paint = new Paint();
                canvas.drawBitmap(bitmap, xCenter - xOffset, mLineHeight, paint);*/
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            /*String text = mAmbient
                    ? String.format("%d:%02d", mTime.hour, mTime.minute)
                    : String.format("%d:%02d:%02d", mTime.hour, mTime.minute, mTime.second);
            canvas.drawText(text, mXOffset, mYOffset, mTextPaint);*/
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

        private final DataApi.DataListener onDataChangedListener = new DataApi.DataListener() {
            @Override
            public void onDataChanged(DataEventBuffer dataEvents) {
                Log.d(TAG, "DataItem Received");
                if (dataEvents != null){
                    DataEvent dataEvent = dataEvents.get(0);
                    if (dataEvent.getType() == DataEvent.TYPE_CHANGED) {
                        DataItem item = dataEvent.getDataItem();
                        processConfigurationFor(item);
                    }
                }

                dataEvents.release();
            }
        };

        private void processConfigurationFor(DataItem item) {
            DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();
            String path = item.getUri().getPath();
            if (path.equals(DATAITEM_TEMP_PATH)) {
                mMaxTemp = dataMap.getDouble(DATAITEM_TEMP_MAX);
                mMinTemp = dataMap.getDouble(DATAITEM_TEMP_MIN);
                Asset photoAsset = dataMap.getAsset(DATAITEM_TEMP_IMAGE);
                //mBitmap = SunshineWatchFaceUtils.getBitmapFromAsset(mGoogleApiClient, photoAsset);
                new LoadBitmapAsyncTask().execute(photoAsset);
                invalidate();
                //SunshineWatchFaceUtils.updateTempData(max, min);
            }
        }

        private class LoadBitmapAsyncTask extends AsyncTask<Asset, Void, Bitmap> {

            @Override
            protected Bitmap doInBackground(Asset... params) {

                if(params.length > 0) {

                    Asset asset = params[0];

                    InputStream assetInputStream = Wearable.DataApi.getFdForAsset(
                            mGoogleApiClient, asset).await().getInputStream();

                    if (assetInputStream == null) {
                        Log.w(TAG, "Requested an unknown Asset.");
                        return null;
                    }
                    return BitmapFactory.decodeStream(assetInputStream);

                } else {
                    Log.e(TAG, "Asset must be non-null");
                    return null;
                }
            }

            @Override
            protected void onPostExecute(Bitmap bitmap) {

                if(bitmap != null) {
                    Log.d(TAG, "Setting background image on second page..");
                    mBitmap = bitmap;
                    invalidate();
                }
            }
        }

        public Bitmap toGreyScale(Bitmap bmpOriginal)
        {
            int width, height;
            height = bmpOriginal.getHeight();
            width = bmpOriginal.getWidth();

            Bitmap bmpGrayscale = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmpGrayscale);
            Paint paint = new Paint();
            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(0);
            ColorMatrixColorFilter f = new ColorMatrixColorFilter(cm);
            paint.setColorFilter(f);
            c.drawBitmap(bmpOriginal, 0, 0, paint);
            return bmpGrayscale;
        }

        @Override
        public void onConnected(Bundle bundle) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnected: " + "GoogleApi has been connected");
            }
            Wearable.DataApi.addListener(mGoogleApiClient, onDataChangedListener);
        }

        @Override
        public void onConnectionSuspended(int i) {

        }

        @Override
        public void onConnectionFailed(ConnectionResult connectionResult) {

        }
    }
}
