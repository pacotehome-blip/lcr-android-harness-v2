package com.pa.lcr.lcp.util;

import android.util.Log;

/**
 * Logger Logcat pour DeliveryLifecycleController.
 * Zéro dépendance à DeliveryEvents, donc non intrusif.
 */
public class AndroidLifecycleLogger implements LifecycleLogger {

    @Override
    public void info(String tag, String msg) {
        Log.i(tag, msg);
    }

    @Override
    public void warn(String tag, String msg) {
        Log.w(tag, msg);
    }

    @Override
    public void error(String tag, String msg) {
        Log.e(tag, msg);
    }
}
