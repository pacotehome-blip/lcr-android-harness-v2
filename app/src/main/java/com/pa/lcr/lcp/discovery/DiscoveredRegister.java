
package com.pa.lcr.lcp.discovery;

public final class DiscoveredRegister {

    public final String serialId;
    public final int lcrnode;
    public final String media;          // "bt" | "usb"
    public final String transportKey;   // BT:xx:xx... | USB
    public final long firstSeenMs;
    public volatile long lastSeenMs;

    // Flag géré par Field Service
    public volatile boolean configured;

    public DiscoveredRegister(String serialId,
                              int lcrnode,
                              String media,
                              String transportKey) {

        this.serialId = serialId;
        this.lcrnode = lcrnode;
        this.media = media;
        this.transportKey = transportKey;

        long now = System.currentTimeMillis();
        this.firstSeenMs = now;
        this.lastSeenMs = now;
        this.configured = false;
    }

    public void touch() {
        this.lastSeenMs = System.currentTimeMillis();
    }
}
