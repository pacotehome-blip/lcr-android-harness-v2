package com.pa.lcr.lcp;

import java.io.IOException;

/**
 * Lc3LinkAdapter — adapte Lc3Link vers LcpLink.
 *
 * Requiert que LcpLink ne soit plus "final":
 *   public class LcpLink {   (retirer le mot "final")
 *
 * DeliveryController n'est pas modifié — il reçoit un LcpLink
 * et ne sait pas qu'il parle à un LC3.
 *
 * Chemin: app/src/main/java/com/pa/lcr/lcp/Lc3LinkAdapter.java
 */
public final class Lc3LinkAdapter extends LcpLink {

    private final Lc3Link lc3;

    public Lc3LinkAdapter(Lc3Link lc3, int toAddr, int hostAddr) {
        super(null, toAddr, hostAddr, false);
        this.lc3 = lc3;
    }

    // ── Lifecycle ────────────────────────────────────────────────────────
    @Override public boolean isClosed()              { return lc3.isClosed(); }
    @Override public void    softClose()             { lc3.softClose(); }
    @Override public void    close()                 { lc3.close(); }
    @Override public void    drainInput(int ms)      { lc3.drainInput(ms); }
    @Override public void    forceSyncNext(String r) { lc3.forceSyncNext(r); }
    @Override public String  getTransportKey()       { return lc3.getTransportKey(); }
    @Override public long    getTransportGenerationId() { return lc3.getTransportGenerationId(); }

    @Override
    public void setTraceSink(TraceSink sink) {
        lc3.setTraceSink(sink != null ? sink::onTrace : null);
    }

    // ── API livraison ────────────────────────────────────────────────────
    @Override
    public MachineStatus opGetMachineStatus() throws IOException {
        int[] ds = lc3.opDeliveryStatus();
        return new MachineStatus(0, 0, 0, ds[0], ds[1]);
    }

    @Override
    public int[] opDeliveryStatus() throws IOException {
        return lc3.opDeliveryStatus();
    }

    @Override
    public int[] opDeliveryStatus(int timeoutMs) throws IOException {
        return lc3.opDeliveryStatus(timeoutMs);
    }

    @Override
    public byte[] opGetField(int field) throws IOException {
        return lc3.opGetField(field);
    }

    @Override
    public byte[] opGetField(int field, int timeoutMs) throws IOException {
        return lc3.opGetField(field, timeoutMs);
    }

    @Override
    public void opSetField(int field, byte[] value) throws IOException {
        lc3.opSetField(field, value);
    }

    @Override
    public void opIssueCommand(int cmd) throws IOException {
        lc3.opIssueCommand(cmd);
    }
}