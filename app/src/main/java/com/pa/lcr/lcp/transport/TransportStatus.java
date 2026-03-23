
package com.pa.lcr.lcp.transport;

public enum TransportStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    READY,        // ex: USB ouvert & prêt, ou BT connecté + streams OK
    ERROR
}
