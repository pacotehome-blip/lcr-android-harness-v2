
package com.pa.lcr.lcp.lifecycle;

/**
 * DeliveryState inspiré de la doc SDK:
 * IDLE, PENDING_TICKET, PENDING_SHIFT, ACTIVE_PAUSED, ACTIVE_FLOWING, CALIBRATION.
 * [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/Android%20SDK%20Documentation-b0.14.pdf)
 */
public enum LcpDeliveryState {
    IDLE,
    PENDING_TICKET,
    PENDING_SHIFT,
    ACTIVE_PAUSED,
    ACTIVE_FLOWING,
    CALIBRATION,
    UNKNOWN
}
