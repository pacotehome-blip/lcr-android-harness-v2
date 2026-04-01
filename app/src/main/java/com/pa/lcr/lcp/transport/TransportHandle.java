
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
        long g = 0;
 try { g = (io != null ? io.getGenerationId() : 0); } catch (Exception ignored) {}
 this.generationId = (g > 0 ? g : GEN.incrementAndGet());
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
        long g = 0;
 try { g = (io != null ? io.getGenerationId() : 0); } catch (Exception ignored) {}
 this.generationId = (g > 0 ? g : GEN.incrementAndGet());
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

    

    // =========================
    // B1 FSM minimaliste
    // =========================
    public synchronized void setActive(String why) {
        this.status = TransportStatus.READY; // compat
        this.updatedAtMs = System.currentTimeMillis();
        if (why != null && !why.trim().isEmpty() && this.description != null && !this.description.contains("active:")) {
            this.description = this.description + " (active:" + why + ")";
        }
    }

    public synchronized void setSuspended(String why) {
        this.status = TransportStatus.READY; // compat
        this.updatedAtMs = System.currentTimeMillis();
        if (why != null && !why.trim().isEmpty() && this.description != null && !this.description.contains("suspended:")) {
            this.description = this.description + " (suspended:" + why + ")";
        }
    }

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
