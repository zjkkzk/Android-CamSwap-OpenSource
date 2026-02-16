package com.example.camswap.utils;

import android.util.Log;

public class LogUtil {
    public static void log(String message) {
        if (message == null) message = "null";
        try {
            Class<?> xposedBridge = Class.forName("de.robv.android.xposed.XposedBridge");
            xposedBridge.getMethod("log", String.class).invoke(null, message);
        } catch (Throwable t) {
            Log.i("LSPosed-Bridge", message);
        }
    }
}
