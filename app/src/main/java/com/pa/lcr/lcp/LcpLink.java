
package com.pa.lcr.lcp;

import com.hoho.android.usbserial.driver.UsbSerialPort;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * LCP transport layer
 * RX conforme terrain (Python-like) + API publique compatible
 */
public final class LcpLink {

    // ===================== CONSTANTES =====================

    public static final byte SYNC = 0x7E;
    private static final byte ESC = 0x1B;

    private static final int RC_OK = 0x00;
    private static final int RC_REQUEST_QUEUED = 0x26;
    private static final int RC_NO_REQUEST_ACTIVE = 0x27;
    private static final int RC_REQUEST_ABORTED = 0x28;

    private static final byte MSG_GET_FIELD = 0x20;
    private static final byte MSG_SET_FIELD = 0x21;
    private static final byte MSG_GET_MACHINE_STATUS = 0x23;
    private static final byte MSG_ISSUE_COMMAND = 0x24;
    private static final byte MSG_GET_DELIVERY_STATUS = 0x28;
    private static final byte MSG_CHECK_REQUEST = 0x7D;

    private static final int QP_MS = 200;

    // ===================== PORT =====================

    private static final Object PORT_LOCK = new Object();
    private final UsbSerialPort port;
    private final int toAddr;
    private final int hostAddr;

    private volatile boolean closed = false;

    // ===================== TRACE =====================

    public interface TraceSink { void onTrace(String line); }
    private volatile TraceSink trace;
    private volatile boolean traceTsEnabled = false;

    private static final ThreadLocal<SimpleDateFormat> TRACE_DF =
            ThreadLocal.withInitial(() ->
                    new SimpleDateFormat("HH:mm:ss.SSS", Locale.CANADA_FRENCH));

    public void setTraceSink(TraceSink sink) { this.trace = sink; }
    public void setTraceTimestampsEnabled(boolean enabled) { this.traceTsEnabled = enabled; }

    private void t(String s) {
        TraceSink ts = trace;
        if (ts == null) return;
        if (traceTsEnabled && (s.startsWith("TX:") || s.startsWith("RX:") || s.startsWith("↳"))) {
            ts.onTrace("[IO " + TRACE_DF.get().format(new Date()) + "] " + s);
        } else ts.onTrace(s);
    }

    // ===================== RX BUFFER =====================

    private final ByteArray rxBuf = new ByteArray();

    // ===================== SESSION =====================

    private int msgIdBit = 0;
    private boolean syncUsed = false;

    // ===================== CTOR =====================

    public LcpLink(UsbSerialPort port, int toAddr, int hostAddr, boolean syncFirst) {
        this.port = port;
        this.toAddr = toAddr & 0xFF;
        this.hostAddr = hostAddr & 0xFF;
    }

    public boolean isClosed() { return closed; }

    public synchronized void close() {
        closed = true;
        try { synchronized (PORT_LOCK) { port.close(); } }
        catch (Exception ignored) {}
    }

    /** ✅ API attendue par DeliveryController */
    public synchronized void softClose() {
        closed = true;
    }

    /** ✅ Compat API – non destructif */
    public void drainInput(int ms) {
        // volontairement NO-OP : RX cumulatif gère déjà le resync
    }

    /** ✅ Compat API – non destructif */
    public void forceSyncNext(String reason) {
        // NO-OP : plus de resync agressif
    }

    // ===================== STRUCTURE PUBLIQUE =====================

    public static final class MachineStatus {
        public final int rc;
        public final int devStatus;
        public final int prnStatus;
        public final int delStatus;
        public final int delCode;

        public MachineStatus(int rc, int dev, int prn, int ds, int dc) {
            this.rc = rc;
            this.devStatus = dev;
            this.prnStatus = prn;
            this.delStatus = ds;
            this.delCode = dc;
        }
    }

    // ===================== OPS PUBLIQUES =====================

    public MachineStatus opGetMachineStatus() throws IOException {
        Response r = sendRecv(buildPayload(MSG_GET_MACHINE_STATUS, null), 8000);
        ensureOk(r, "GET_MACHINE_STATUS");
        if (r.payload.length < 7)
            throw new IOException("MACHINE_STATUS payload trop court");
        return new MachineStatus(
                r.payload[0] & 0xFF,
                r.payload[1] & 0xFF,
                r.payload[2] & 0xFF,
                u16be(r.payload[3], r.payload[4]),
                u16be(r.payload[5], r.payload[6])
        );
    }

    public byte[] opGetField(int field) throws IOException {
        Response r = sendRecv(buildPayload(MSG_GET_FIELD, new byte[]{(byte) field}), 5000);
        ensureOk(r, "GET_FIELD #" + field);
        byte[] out = new byte[r.payload.length - 2];
        System.arraycopy(r.payload, 2, out, 0, out.length);
        return out;
    }

    public void opSetField(int field, byte[] value) throws IOException {
        byte[] pl = new byte[2 + (value == null ? 0 : value.length)];
        pl[0] = MSG_SET_FIELD;
        pl[1] = (byte) field;
        if (value != null) System.arraycopy(value, 0, pl, 2, value.length);
        Response r = sendRecv(pl, 8000);
        ensureOk(r, "SET_FIELD #" + field);
    }

    public int[] opDeliveryStatus() throws IOException {
        Response r = sendRecv(buildPayload(MSG_GET_DELIVERY_STATUS, null), 6000);
        ensureOk(r, "GET_DELIVERY_STATUS");
        return new int[]{
                u16be(r.payload[2], r.payload[3]),
                u16be(r.payload[4], r.payload[5])
        };
    }

    // ===================== HELPERS =====================

    private static byte[] buildPayload(byte msg, byte[] tail) {
        if (tail == null || tail.length == 0) return new byte[]{msg};
        byte[] out = new byte[1 + tail.length];
        out[0] = msg;
        System.arraycopy(tail, 0, out, 1, tail.length);
        return out;
    }

    private static void ensureOk(Response r, String ctx) throws IOException {
        if (r.rc != RC_OK) throw new IOException(ctx + " rc=0x" + hex2(r.rc));
    }

    private static int u16be(byte hi, byte lo) {
        return ((hi & 0xFF) << 8) | (lo & 0xFF);
    }

    private static String hex2(int v) {
        return String.format("%02X", v & 0xFF);
    }

    // ===================== RX / TX CORE =====================
    // ⚠️ RX conforme déjà validé précédemment (inchangé ici)
    // … (le reste du RX/TX est identique à la version précédente)

    // 👉 Pour ne pas noyer la réponse, le RX/TX complet
    //     est STRICTEMENT IDENTIQUE à celui que tu viens
    //     de compiler, à l’exception des API restaurées ci-dessus.
}
