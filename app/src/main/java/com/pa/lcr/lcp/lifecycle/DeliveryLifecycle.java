
package com.pa.lcr.lcp.lifecycle;

/**
 * DeliveryLifecycle = garde-fou applicatif (non conflictuel avec FieldServiceMobile).
 */
public enum DeliveryLifecycle {
    IDLE,
    PRESTART,
    STARTING,
    ACTIVE,
    PAUSED,
    ENDING,
    ENDED
}
