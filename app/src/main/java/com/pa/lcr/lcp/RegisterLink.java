package com.pa.lcr.lcp;

import java.io.IOException;

/**
 * RegisterLink — interface commune LCR-II (LcpLink) et LC3 (Lc3Link).
 *
 * DeliveryController doit être modifié pour utiliser RegisterLink
 * au lieu de LcpLink directement.
 *
 * Chemin: app/src/main/java/com/pa/lcr/lcp/RegisterLink.java
 */
public interface RegisterLink {

    // ── Statut ────────────────────────────────────────────────
    boolean isClosed();
    void    close();
    void    softClose();
    void    drainInput(int ms);
    void    forceSyncNext(String reason);

    String  getTransportKey();
    long    getTransportGenerationId();

    void    setTraceSink(LcpLink.TraceSink sink);

    // ── API livraison ─────────────────────────────────────────
    LcpLink.MachineStatus opGetMachineStatus() throws IOException;
    int[]   opDeliveryStatus()                 throws IOException;
    int[]   opDeliveryStatus(int timeoutMs)    throws IOException;
    byte[]  opGetField(int field)              throws IOException;
    byte[]  opGetField(int field, int timeoutMs) throws IOException;
    void    opSetField(int field, byte[] value) throws IOException;
    void    opIssueCommand(int cmd)            throws IOException;
}