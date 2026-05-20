package com.pa.lcr.lcp;

import com.pa.lcr.lcp.transport.TransportIo;
import java.io.IOException;

/**
 * Lc3LinkAdapter — adapte Lc3Link vers LcpLink.
 *
 * DeliveryController attend un LcpLink. Ce wrapper délègue
 * toutes les méthodes à Lc3Link sans modifier DeliveryController.
 *
 * RegisterSessionManager.getOrCreate() crée un Lc3LinkAdapter
 * quand Lc3Link.probe() détecte un LC3.
 */
public final class Lc3LinkAdapter extends LcpLink {

    private final Lc3Link lc3;

    public Lc3LinkAdapter(Lc3Link lc3, int toAddr, int hostAddr) {
        // LcpLink avec io=null (on n'utilise pas le transport LCP)
        super(null, toAddr, hostAddr, false);
        this.lc3 = lc3;
    }

    // ── Lifecycle ────────────────────────────────────────────────────────
    @Override public boolean isClosed()           { return lc3.isClosed(); }
    @Override public void    softClose()          { lc3.softClose(); }
    @Override public void    close()              { lc3.close(); }
    @Override public void    drainInput(int ms)   { lc3.drainInput(ms); }
    @Override public void    forceSyncNext(String r) { lc3.forceSyncNext(r); }

    @Override public String  getTransportKey()    { return lc3.getTransportKey(); }
    @Override public long    getTransportGenerationId() { return lc3.getGenerationId(); }

    @Override
    public void setTraceSink(TraceSink sink) {
        lc3.setTraceSink(sink != null ? sink::onTrace : null);
    }

    // ── API principale ───────────────────────────────────────────────────
    @Override
    public MachineStatus opGetMachineStatus() throws IOException {
        LcpLink.MachineStatus ms = lc3.opGetMachineStatus();
        return new MachineStatus(ms.rc, ms.devStatus, ms.prnStatus,
                                 ms.delStatus, ms.delCode);
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