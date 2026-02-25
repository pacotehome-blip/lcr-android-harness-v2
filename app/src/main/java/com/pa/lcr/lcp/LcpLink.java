
package com.pa.lcr.lcp;

import com.hoho.android.usbserial.driver.UsbSerialPort;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class LcpLink {

    private static final Object PORT_LOCK = new Object();

    public interface TraceSink { void onTrace(String line); }
    private volatile TraceSink trace;
    public void setTraceSink(TraceSink sink) { this.trace = sink; }
    private void t(String s) { TraceSink ts = trace; if (ts != null) ts.onTrace(s); }

    public static final byte SYNC = 0x7E;
    private static final byte ESC = 0x1B;

    private static final int RC_OK = 0x00;
    private static final int RC_REQUEST_QUEUED = 0x26;
    private static final int RC_NO_REQUEST_ACTIVE = 0x27;
    private static final int RC_REQUEST_ABORTED = 0x28;

    private static final byte MSG_GET_PRODUCT_ID = 0x00;
    private static final byte MSG_GET_FIELD = 0x20;
    private static final byte MSG_SET_FIELD = 0x21;
    private static final byte MSG_GET_MACHINE_STATUS = 0x23;
    private static final byte MSG_ISSUE_COMMAND = 0x24;
    private static final byte MSG_GET_DELIVERY_STATUS = 0x28;
    private static final byte MSG_CHECK_REQUEST = 0x7D;

    private final UsbSerialPort port;
    private final int toAddr;
    private final int hostAddr;
    private final boolean syncFirstEnabled;

    private boolean syncUsed = false;
    private int msgIdBit = 0;

    private volatile Integer lastResponderNode = null;
    public Integer getLastResponderNode() { return lastResponderNode; }

    // ✅ Ajout : état “transport fermé”
    private volatile boolean closed = false;
    public boolean isClosed() { return closed; }

    /** ✅ Ajout : fermer proprement la session */
    public synchronized void close() {
        closed = true;
        try {
            synchronized (PORT_LOCK) { port.close(); }
        } catch (Exception ignored) { }
    }

    public LcpLink(UsbSerialPort port, int toAddr, int hostAddr, boolean syncFirstEnabled) {
        this.port = port;
        this.toAddr = toAddr & 0xFF;
        this.hostAddr = hostAddr & 0xFF;
        this.syncFirstEnabled = syncFirstEnabled;
    }

    public void drainInput(int millis) {
        if (closed) return;
        int total = 0;
        long end = System.currentTimeMillis() + Math.max(0, millis);
        try {
            while (!closed && System.currentTimeMillis() < end) {
                byte[] b = new byte[64];
                int n;
                synchronized (PORT_LOCK) {
                    if (closed) break;
                    n = port.read(b, 30);
                }
                if (n <= 0) break;
                total += n;
            }
        } catch (Exception ignored) {}
        t("RESYNC: drainInput bytes=" + total);
    }

    public synchronized void forceSyncNext(String reason) {
        if (closed) return;
        t("RESYNC: forceSyncNext (" + reason + ")");
        this.msgIdBit = 0;
        this.syncUsed = false;
    }

    public static final class MachineStatus {
        public final int rc;
        public final int devStatus;
        public final int prnStatus;
        public final int delStatus;
        public final int delCode;
        public MachineStatus(int rc, int devStatus, int prnStatus, int delStatus, int delCode) {
            this.rc = rc;
            this.devStatus = devStatus;
            this.prnStatus = prnStatus;
            this.delStatus = delStatus;
            this.delCode = delCode;
        }
    }

    /* ============================================================
     * API opérations — timeouts augmentés (éviter faux “Transport down”)
     * ============================================================ */

    public byte[] opGetField(int field) throws IOException {
        // avant: 3000ms dans ta version actuelle [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LcpLink.java)
        Response r = sendRecv(buildPayload(MSG_GET_FIELD, new byte[]{(byte) field}), 5000);
        ensureOk(r, "GET_FIELD #" + field);
        if (r.payload.length < 2) throw new IOException("GET_FIELD payload trop court");
        byte[] fieldData = new byte[r.payload.length - 2];
        System.arraycopy(r.payload, 2, fieldData, 0, fieldData.length);
        return fieldData;
    }

    public void opSetField(int field, byte[] value) throws IOException {
        // avant: 3000ms dans ta version actuelle [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LcpLink.java)
        byte[] pl = new byte[2 + (value == null ? 0 : value.length)];
        pl[0] = MSG_SET_FIELD;
        pl[1] = (byte) field;
        if (value != null && value.length > 0) System.arraycopy(value, 0, pl, 2, value.length);
        Response r = sendRecv(pl, 8000);
        ensureOk(r, "SET_FIELD #" + field);
    }

    public void opIssueCommand(int cmd) throws IOException {
        Response r = sendRecv(buildPayload(MSG_ISSUE_COMMAND, new byte[]{(byte) cmd}), 10000);
        ensureOk(r, "ISSUE_COMMAND 0x" + hex2(cmd));
    }

    public int[] opDeliveryStatus() throws IOException {
        Response r = sendRecv(buildPayload(MSG_GET_DELIVERY_STATUS, null), 6000);
        ensureOk(r, "GET_DELIVERY_STATUS");
        if (r.payload.length < 6) throw new IOException("DELIVERY_STATUS payload trop court len=" + r.payload.length);
        int delStatus = u16be(r.payload[2], r.payload[3]);
        int delCode = u16be(r.payload[4], r.payload[5]);
        return new int[]{delStatus, delCode};
    }

    public void opGetProductId() throws IOException {
        Response r = sendRecv(buildPayload(MSG_GET_PRODUCT_ID, null), 5000);
        ensureOk(r, "GET_PRODUCT_ID");
    }

    public MachineStatus opGetMachineStatus() throws IOException {
        Response r = sendRecv(buildPayload(MSG_GET_MACHINE_STATUS, null), 10000);
        ensureOk(r, "GET_MACHINE_STATUS");
        if (r.payload.length < 7) throw new IOException("MACHINE_STATUS payload trop court len=" + r.payload.length);
        int rc = r.payload[0] & 0xFF;
        int dev = r.payload[1] & 0xFF;
        int prn = r.payload[2] & 0xFF;
        int delStatus = u16be(r.payload[3], r.payload[4]);
        int delCode = u16be(r.payload[5], r.payload[6]);
        return new MachineStatus(rc, dev, prn, delStatus, delCode);
    }

    /* ============================================================
     * send/recv queued aware + 0x7D
     * ============================================================ */
    private synchronized Response sendRecv(byte[] payload, int timeoutMs) throws IOException {
        if (closed) throw new IOException("Transport closed");

        final byte origMsg = (payload != null && payload.length > 0) ? payload[0] : 0;

        byte[] txFrame = encodeFrame(payload);
        traceFrame(true, txFrame, payload);
        synchronized (PORT_LOCK) {
            if (closed) throw new IOException("Transport closed");
            port.write(txFrame, 500);
        }

        long deadline = System.currentTimeMillis() + timeoutMs;

        boolean queued = false;
        final int queuedWindowMs = queuedWindowFor(origMsg, timeoutMs);

        long nextCheckAt = 0L;
        int checkDelayMs = 220;
        final int checkDelayMax = 700;

        while (System.currentTimeMillis() < deadline) {
            if (closed) throw new IOException("Transport closed");

            if (queued && System.currentTimeMillis() >= nextCheckAt) {
                byte[] chkPayload = new byte[]{ MSG_CHECK_REQUEST };
                byte[] chkFrame = encodeFrame(chkPayload);
                traceFrame(true, chkFrame, chkPayload);
                synchronized (PORT_LOCK) {
                    if (closed) throw new IOException("Transport closed");
                    port.write(chkFrame, 500);
                }
                checkDelayMs = Math.min(checkDelayMax, (int)(checkDelayMs * 1.35));
                nextCheckAt = System.currentTimeMillis() + checkDelayMs;
            }

            Frame rx = readFrame(250);
            if (rx == null) continue;
            if (rx.to != hostAddr) continue;

            lastResponderNode = rx.from;
            traceFrame(false, rx.rawFrame, payload);

            int rc0 = (rx.payload.length >= 1) ? (rx.payload[0] & 0xFF) : 0xFF;

            if (rc0 == RC_REQUEST_QUEUED) {
                if (!queued) {
                    queued = true;
                    long qd = System.currentTimeMillis() + queuedWindowMs;
                    deadline = Math.max(deadline, qd);
                    nextCheckAt = System.currentTimeMillis() + checkDelayMs;
                    t("↳ QUEUED: fenêtre étendue à " + queuedWindowMs + " ms (msg=" + explainMsg(origMsg) + ")");
                }
                continue;
            }

            if (queued && rc0 == RC_NO_REQUEST_ACTIVE) continue;

            if (rc0 == RC_REQUEST_ABORTED) throw new IOException("Queued aborted (rc=0x28)");

            // unwrap only if signature [OK, OK, ...]
            if (queued && rc0 == RC_OK && rx.payload.length >= 3 && ((rx.payload[1] & 0xFF) == RC_OK)) {
                byte[] norm = new byte[rx.payload.length - 1];
                System.arraycopy(rx.payload, 1, norm, 0, norm.length);
                return new Response(norm[0] & 0xFF, norm);
            }

            return new Response(rc0, rx.payload);
        }

        t("RX: <timeout>");
        t("↳ Aucun octet reçu / réponse valide avant expiration (msg=" + explainMsg(origMsg) + ")");
        throw new IOException("Timeout waiting LCP response");
    }

    private static int queuedWindowFor(byte origMsg, int baseTimeoutMs) {
        int base = Math.max(baseTimeoutMs, 6000);
        int longWin = 30000;
        switch (origMsg & 0xFF) {
            case 0x28:
            case 0x23:
            case 0x24:
            case 0x21:
                return Math.max(base, longWin);
            default:
                return Math.max(base, 12000);
        }
    }

    private void ensureOk(Response r, String ctx) throws IOException {
        if (r == null) throw new IOException(ctx + ": réponse null");
        if (r.rc != RC_OK) throw new IOException(ctx + ": rc=0x" + hex2(r.rc));
    }

    private byte[] encodeFrame(byte[] payload) {
        int status = nextStatusByte();
        byte[] var = new byte[4 + payload.length];
        var[0] = (byte) toAddr;
        var[1] = (byte) hostAddr;
        var[2] = (byte) status;
        var[3] = (byte) (payload.length & 0xFF);
        System.arraycopy(payload, 0, var, 4, payload.length);

        ByteArray escVar = new ByteArray();
        for (byte b : var) escVar.appendEscaped(b);

        int crc = crcLcp(escVar.bytes(), 0, escVar.length());

        ByteArray out = new ByteArray();
        out.append(SYNC);
        out.append(SYNC);
        out.appendBytes(escVar.bytes(), 0, escVar.length());

        byte crc0 = (byte) (crc & 0xFF);
        byte crc1 = (byte) ((crc >> 8) & 0xFF);
        out.appendEscapedCrc(crc0);
        out.appendEscapedCrc(crc1);

        return out.toArray();
    }

    private int nextStatusByte() {
        int st = (msgIdBit & 0x01);
        if (syncFirstEnabled && !syncUsed) {
            st = 0x02;
            syncUsed = true;
        }
        msgIdBit ^= 1;
        return st;
    }

    private Frame readFrame(int sliceTimeoutMs) throws IOException {
        if (closed) return null;

        int s1;
        do {
            s1 = readRawByte(sliceTimeoutMs);
            if (s1 < 0) return null;
        } while (s1 != (SYNC & 0xFF));

        int s2 = readRawByte(sliceTimeoutMs);
        if (s2 < 0 || s2 != (SYNC & 0xFF)) return null;

        try {
            ByteArray rawForCrc = new ByteArray();
            int to = readUnescapedByte(rawForCrc, sliceTimeoutMs);
            int from = readUnescapedByte(rawForCrc, sliceTimeoutMs);
            int status = readUnescapedByte(rawForCrc, sliceTimeoutMs);
            int len = readUnescapedByte(rawForCrc, sliceTimeoutMs);

            byte[] payload = new byte[len];
            for (int i = 0; i < len; i++) payload[i] = (byte) readUnescapedByte(rawForCrc, sliceTimeoutMs);

            int crc0 = readCrcByte(sliceTimeoutMs);
            int crc1 = readCrcByte(sliceTimeoutMs);

            int calc = crcLcp(rawForCrc.bytes(), 0, rawForCrc.length());
            int recv = ((crc1 & 0xFF) << 8) | (crc0 & 0xFF);
            if (calc != recv) return null;

            byte[] canonical = buildCanonicalFrame(to, from, status, payload, crc0, crc1);
            return new Frame(to, from, status, payload, canonical);

        } catch (IOException e) {
            return null;
        }
    }

    private static byte[] buildCanonicalFrame(int to, int from, int status, byte[] payload, int crc0, int crc1) {
        byte[] out = new byte[2 + 4 + payload.length + 2];
        out[0] = SYNC; out[1] = SYNC;
        out[2] = (byte) to;
        out[3] = (byte) from;
        out[4] = (byte) status;
        out[5] = (byte) (payload.length & 0xFF);
        System.arraycopy(payload, 0, out, 6, payload.length);
        out[out.length - 2] = (byte) (crc0 & 0xFF);
        out[out.length - 1] = (byte) (crc1 & 0xFF);
        return out;
    }

    private int readUnescapedByte(ByteArray rawForCrc, int timeoutMs) throws IOException {
        int b = readRawByte(timeoutMs);
        if (b < 0) throw new IOException("Timeout lecture octet");
        if (b == (ESC & 0xFF)) {
            int nxt = readRawByte(timeoutMs);
            if (nxt < 0) throw new IOException("Timeout après ESC");
            rawForCrc.append(ESC);
            rawForCrc.append((byte) nxt);
            return nxt & 0xFF;
        }
        rawForCrc.append((byte) b);
        return b & 0xFF;
    }

    private int readCrcByte(int timeoutMs) throws IOException {
        int b = readRawByte(timeoutMs);
        if (b < 0) throw new IOException("Timeout CRC");
        if (b == (ESC & 0xFF)) {
            int nxt = readRawByte(timeoutMs);
            if (nxt < 0) throw new IOException("Timeout CRC après ESC");
            return nxt & 0xFF;
        }
        return b & 0xFF;
    }

    private int readRawByte(int timeoutMs) throws IOException {
        if (closed) return -1;
        byte[] b = new byte[1];
        int n;
        synchronized (PORT_LOCK) {
            if (closed) return -1;
            n = port.read(b, timeoutMs);
        }
        return (n == 1) ? (b[0] & 0xFF) : -1;
    }

    private void traceFrame(boolean tx, byte[] canonicalFrame, byte[] relatedTxPayload) {
        String dir = tx ? "TX" : "RX";
        t(dir + ": " + hexDump(canonicalFrame));
        for (String line : explainCanonicalFrame(tx, canonicalFrame, relatedTxPayload)) t("↳ " + line);
    }

    private List<String> explainCanonicalFrame(boolean tx, byte[] f, byte[] relatedTxPayload) {
        List<String> out = new ArrayList<>();
        int len = f[5] & 0xFF;
        byte[] pl = new byte[Math.max(0, Math.min(len, f.length - 8))];
        if (pl.length > 0) System.arraycopy(f, 6, pl, 0, pl.length);

        if (tx) {
            if (pl.length >= 1) out.add("MSG : " + explainMsg(pl[0]));
        } else {
            if (pl.length >= 1) out.add("RC : " + explainRc(pl[0] & 0xFF));
            if (pl.length >= 2) out.add("DEVSTAT : 0x" + hex2(pl[1] & 0xFF));
            byte txMsg = (relatedTxPayload != null && relatedTxPayload.length >= 1) ? relatedTxPayload[0] : 0;
            out.add("REPLY-TO : " + explainMsg(txMsg));
        }
        return out;
    }

    private static String explainMsg(byte msg) {
        switch (msg & 0xFF) {
            case 0x00: return "GET_PRODUCT_ID (0x00)";
            case 0x20: return "GET_FIELD (0x20)";
            case 0x21: return "SET_FIELD (0x21)";
            case 0x23: return "GET_MACHINE_STATUS (0x23)";
            case 0x24: return "ISSUE_COMMAND (0x24)";
            case 0x28: return "GET_DELIVERY_STATUS (0x28)";
            case 0x7D: return "CHECK_REQUEST (0x7D)";
            default: return "UNKNOWN (0x" + hex2(msg) + ")";
        }
    }

    private static String explainRc(int rc) {
        switch (rc & 0xFF) {
            case RC_OK: return "0x00 OK";
            case RC_REQUEST_QUEUED: return "0x26 REQUEST_QUEUED";
            case RC_NO_REQUEST_ACTIVE: return "0x27 NO_REQUEST_ACTIVE";
            case RC_REQUEST_ABORTED: return "0x28 REQUEST_ABORTED";
            default: return "0x" + hex2(rc) + " (ERROR)";
        }
    }

    private static int crcLcp(byte[] data, int off, int len) {
        int crc = 0x7E7E;
        for (int i = off; i < off + len; i++) {
            int b = data[i] & 0xFF;
            for (int bit = 7; bit >= 0; bit--) {
                boolean fb = (crc & 0x8000) != 0;
                crc = ((crc << 1) & 0xFFFF) | ((b >> bit) & 1);
                if (fb) crc ^= 0x1021;
            }
        }
        return crc & 0xFFFF;
    }

    private static byte[] buildPayload(byte msg, byte[] tail) {
        if (tail == null || tail.length == 0) return new byte[]{msg};
        byte[] out = new byte[1 + tail.length];
        out[0] = msg;
        System.arraycopy(tail, 0, out, 1, tail.length);
        return out;
    }

    private static int u16be(byte hi, byte lo) {
        return ((hi & 0xFF) << 8) | (lo & 0xFF);
    }

    private static String hexDump(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(hex2(b[i] & 0xFF));
        }
        return sb.toString();
    }

    private static String hex2(int v) { return String.format("%02X", v & 0xFF); }

    private static final class Frame {
        final int to, from, status;
        final byte[] payload;
        final byte[] rawFrame;
        Frame(int to, int from, int status, byte[] payload, byte[] canonical) {
            this.to = to; this.from = from; this.status = status;
            this.payload = payload; this.rawFrame = canonical;
        }
    }

    private static final class Response {
        final int rc;
        final byte[] payload;
        Response(int rc, byte[] payload) {
            this.rc = rc;
            this.payload = (payload != null) ? payload : new byte[0];
        }
    }

    private static final class ByteArray {
        private byte[] buf = new byte[256];
        private int len = 0;

        void append(byte b) { ensure(1); buf[len++] = b; }
        void appendBytes(byte[] b, int off, int l) {
            if (l > 0) { ensure(l); System.arraycopy(b, off, buf, len, l); len += l; }
        }
        void appendEscaped(byte b) {
            int v = b & 0xFF;
            if (v == (ESC & 0xFF) || v == (SYNC & 0xFF)) append(ESC);
            append(b);
        }
        void appendEscapedCrc(byte b) {
            int v = b & 0xFF;
            if (v == (ESC & 0xFF) || v == (SYNC & 0xFF)) append(ESC);
            append(b);
        }
        byte[] bytes() { return buf; }
        int length() { return len; }
        byte[] toArray() {
            byte[] out = new byte[len];
            System.arraycopy(buf, 0, out, 0, len);
            return out;
        }
        private void ensure(int extra) {
            if (len + extra <= buf.length) return;
            int n = Math.max(buf.length * 2, len + extra + 64);
            byte[] nb = new byte[n];
            System.arraycopy(buf, 0, nb, 0, len);
            buf = nb;
        }
    }
}
