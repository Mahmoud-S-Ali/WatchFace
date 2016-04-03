package com.example.android.sunshine.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;

/**
 * Created by Toty on 3/28/2016.
 */
public class SunshineWatchFaceUtils {
    public static final String COLOR_NAME_DEFAULT_AND_AMBIENT_BACKGROUND = "Black";
    public static final int COLOR_VALUE_DEFAULT_AND_AMBIENT_BACKGROUND =
            parseColor(COLOR_NAME_DEFAULT_AND_AMBIENT_BACKGROUND);

    public static final String COLOR_NAME_DEFAULT_AND_AMBIENT_TIME = "White";
    public static final int COLOR_VALUE_DEFAULT_AND_AMBIENT_TIME =
            parseColor(COLOR_NAME_DEFAULT_AND_AMBIENT_TIME);

    public static final String COLOR_NAME_DEFAULT_AND_AMBIENT_DATE = "Grey";
    public static final int COLOR_VALUE_DEFAULT_AND_AMBIENT_DATE =
            parseColor(COLOR_NAME_DEFAULT_AND_AMBIENT_DATE);

    public static final String COLOR_NAME_DEFAULT_AND_AMBIENT_LINE = "Grey";
    public static final int COLOR_VALUE_DEFAULT_AND_AMBIENT_LINE =
            parseColor(COLOR_NAME_DEFAULT_AND_AMBIENT_LINE);

    public static final String COLOR_NAME_DEFAULT_AND_AMBIENT_MAXTEMP = "White";
    public static final int COLOR_VALUE_DEFAULT_AND_AMBIENT_MAXTEMP =
            parseColor(COLOR_NAME_DEFAULT_AND_AMBIENT_MAXTEMP);

    public static final String COLOR_NAME_DEFAULT_AND_AMBIENT_MINTEMP = "Grey";
    public static final int COLOR_VALUE_DEFAULT_AND_AMBIENT_MINTEMP =
            parseColor(COLOR_NAME_DEFAULT_AND_AMBIENT_MINTEMP);

    private static int parseColor(String colorName) {
        return Color.parseColor(colorName.toLowerCase());
    }

    public static Bitmap getBitmapFromAsset(GoogleApiClient googleApiClient, Asset asset) {
        InputStream assetInputStream = Wearable.DataApi.getFdForAsset(
                googleApiClient, asset).await().getInputStream();

        return BitmapFactory.decodeStream(assetInputStream);
    }
}
