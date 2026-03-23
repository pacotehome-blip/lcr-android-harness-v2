
package com.pa.lcr.lcp.transport;

public final class TransportSnapshot {
    public final String key;
    public final String description;
    public final TransportStatus status;
    public final long generationId;
    public final String lastError;
    public final long updatedAtMs;

    public TransportSnapshot(String key,
                             String description,
                             TransportStatus status,
                             long generationId,
                             String lastError,
                             long updatedAtMs) {
        this.key = key;
        this.description = description;
        this.status = status;
        this.generationId = generationId;
        this.lastError = lastError;
        this.updatedAtMs = updatedAtMs;
    }
}
