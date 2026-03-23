
package com.pa.lcr.lcp.transport;

import java.util.concurrent.atomic.AtomicLong;

public final class TransportHandle {
    private static final AtomicLong GEN = new AtomicLong(1000);

    private final String key;
    private volatile String description;
    private volatile TransportStatus status = TransportStatus.DISCONNECTED;
    private volatile String lastError = null;
    private volatile long updatedAtMs = System.currentTimeMillis();

    private volatile TransportIo io = null;
    private volatile long generationId = 0;

    public TransportHandle(String key) {
        this.key = key;
    }

    public String getKey() { return key; }

    public synchronized void setConnected(TransportIo io, String description) {
        this.io = io;
        this.description = description;
        this.generationId = GEN.incrementAndGet();
        this.status = TransportStatus.READY;
        this.lastError = null;
        this.updatedAtMs = System.currentTimeMillis();
    }

    public synchronized void setDisconnected(String description) {
        this.description = description;
        this.status = TransportStatus.DISCONNECTED;
        this.lastError = null;
        this.updatedAtMs = System.currentTimeMillis();
        if (this.io != null) {
            try { this.io.close(); } catch (Exception ignored) {}
        }
        this.io = null;
        this.generationId = GEN.incrementAndGet();
    }

    public synchronized void setError(String description, String error) {
        this.description = description;
        this.status = TransportStatus.ERROR;
        this.lastError = error;
        this.updatedAtMs = System.currentTimeMillis();
        // on ne ferme pas automatiquement ici (décision manager)
    }

    public TransportIo getIo() { return io; }

    public TransportStatus getStatus() { return status; }

    public long getGenerationId() { return generationId; }

    public String getLastError() { return lastError; }

    public String getDescription() { return description; }

    public long getUpdatedAtMs() { return updatedAtMs; }

    public TransportSnapshot snapshot() {
        return new TransportSnapshot(
                key,
                description,
                status,
                generationId,
                lastError,
                updatedAtMs
        );
    }
}
