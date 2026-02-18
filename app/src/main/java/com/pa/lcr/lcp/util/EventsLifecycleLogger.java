
package com.pa.lcr.lcp.util;

import com.pa.lcr.lcp.DeliveryController;

/**
 * Logger qui pousse les logs dans le pipeline existant (events.onLog via log()).
 * Optionnel. Utilise DeliveryEvents, donc dépend du wiring existant.
 */
public class EventsLifecycleLogger implements LifecycleLogger {

    private final DeliveryController.DeliveryEvents events;

    public EventsLifecycleLogger(DeliveryController.DeliveryEvents events) {
        this.events = events;
    }

    private void emit(String level, String tag, String msg) {
        if (events != null) {
            events.onLog("[" + level + "][" + tag + "] " + msg);
        }
    }

    @Override public void info(String tag, String msg)  { emit("I", tag, msg); }
    @Override public void warn(String tag, String msg)  { emit("W", tag, msg); }
    @Override public void error(String tag, String msg) { emit("E", tag, msg); }
}
