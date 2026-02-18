package com.pa.lcr.lcp.util;

/**
 * Logger minimal pour le DeliveryLifecycleController.
 * Implémentations possibles: Logcat, events.onLog(), fichier, etc.
 */
public interface LifecycleLogger {
    void info(String tag, String msg);
    void warn(String tag, String msg);
    void error(String tag, String msg);
}
