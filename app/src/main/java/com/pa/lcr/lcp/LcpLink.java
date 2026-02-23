
package com.pa.lcr.lcp;

import com.hoho.android.usbserial.driver.UsbSerialPort;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * LcpLink - framing + CRC + queue handling (LCP02)
 *
 * Objectifs (UX figée):
 * - AUCUN polling automatique
 * - AUCUNE lecture implicite au connect (c'est le controller qui décide quand appeler opX)
 *
 * Ajout demandé:
 * - Log TX/RX (hex) + explication payload via TraceSink (branché sur UI log).
 *
 * Références protocole:
 * - Format: ~~ <to> <from> <status> <len> <data...> <crc0 low> <crc1 high>
 * - ESC (~ et ESC) dans le flux, CRC seed 0x7E7E, CRC inclut les ESC ajoutés (sauf dans CRC)
 */
public final class LcpLink {

    /* ===================== Trace (vers UI) ===================== */
    public interface TraceSink {
        void onTrace(String line);
    }

    private volatile TraceSink trace;

    public void setTraceSink(TraceSink sink) {
        this.trace = sink;
    }

    private void t(String s) {
        TraceSink ts = this.trace;
        if (ts != null) ts.onTrace(s);
    }

    /* ===================== Constantes LCP ===================== */
    public static final byte SYNC = 0x7E;
    private static final byte ESC = 0x1B;

    // Return codes (payload[0])
    private static final int RC_OK = 0x00;
    private static final int RC_REQUEST_QUEUED = 0x26;
    private static final int RC_NO_REQUEST_ACTIVE = 0x27;
    private static final int RC_REQUEST_ABORTED = 0x28;

    // Msg IDs
    private static final byte MSG_GET_PRODUCT_ID = 0x00;
    private static final byte MSG_GET_FIELD = 0x20;
    private static final byte MSG_SET_FIELD = 0x21;
    private static final byte MSG_PRINT_TEXT = 0x22;
    private static final byte MSG_GET_MACHINE_STATUS = 0x23;
    private static final byte MSG_ISSUE_COMMAND = 0x24;
    private static final byte MSG_GET_DELIVERY_STATUS = 0x28;
    private static final byte MSG_CHECK_REQUEST = 0x7D;
    private static final byte MSG_ABORT_REQUEST = 0x7E;

    /* ===================== Dépendances ===================== */
    private final UsbSerialPort port;
    private final int toAddr;
    private final int hostAddr;

    // Sync/session bits
    private boolean syncFirstEnabled;
    private boolean syncUsed = false;
    private int msgIdBit = 0; // toggles 0/1

    // Dernier node ayant réellement répondu
    private volatile Integer lastResponderNode = null;

    public LcpLink(UsbSerialPort port, int toAddr, int hostAddr, boolean syncFirstEnabled) {
        this.port = port;
        this.toAddr = toAddr & 0xFF;
        this.hostAddr = hostAddr & 0xFF;
        this.syncFirstEnabled = syncFirstEnabled;
    }

    public Integer getLastResponderNode() {
        return lastResponderNode;
    }

    /* ============================================================
     * API opérations (retours nettoyés: rc check + fieldData)
     * ============================================================ */

    public byte[] opGetField(int field) throws IOException {
        Response r = sendRecv(buildPayload(MSG_GET_FIELD, new byte[]{(byte) field}));
        ensureOk(r, "GET_FIELD #" + field);
        // payload = rc, devStatus, fieldData...
        if (r.payload.length < 2) throw new IOException("GET_FIELD payload trop court");
        byte[] fieldData = new byte[r.payload.length - 2];
        System.arraycopy(r.payload, 2, fieldData, 0, fieldData.length);
        return fieldData;
    }

    public void opSetField(int field, byte[] value) throws IOException {
        byte[] pl = new byte[2 + (value == null ? 0 : value.length)];
        pl[0] = MSG_SET_FIELD;
        pl[1] = (byte) field;
        if (value != null && value.length > 0) {
            System.arraycopy(value, 0, pl, 2, value.length);
        }
        Response r = sendRecv(pl);
        ensureOk(r, "SET_FIELD #" + field);
    }

    public void opIssueCommand(int cmd) throws IOException {
        Response r = sendRecv(buildPayload(MSG_ISSUE_COMMAND, new byte[]{(byte) cmd}));
        ensureOk(r, "ISSUE_COMMAND 0x" + hex2(cmd));
    }

    /**
     * Retourne {delStatusWord, delCodeWord} (unsigned 16-bit each).
     */
    public int[] opDeliveryStatus() throws IOException {
        Response r = sendRecv(buildPayload(MSG_GET_DELIVERY_STATUS, null));
        ensureOk(r, "GET_DELIVERY_STATUS");
        // payload = rc, devStatus, delStatus[2], delCode[2]
        if (r.payload.length < 6) throw new IOException("DELIVERY_STATUS payload trop court len=" + r.payload.length);
        int delStatus = u16be(r.payload[2], r.payload[3]);
        int delCode = u16be(r.payload[4], r.payload[5]);
        return new int[]{delStatus, delCode};
    }

    public void opGetProductId() throws IOException {
        Response r = sendRecv(buildPayload(MSG_GET_PRODUCT_ID, null));
        ensureOk(r, "GET_PRODUCT_ID");
    }

    /* ============================================================
     * Core send/recv (queued aware) + TX/RX logging
     * ============================================================ */

    private synchronized Response sendRecv(byte[] payload) throws IOException {
        // TX
        byte[] txFrame = encodeFrame(payload);
        traceFrame(true, txFrame, payload);

        port.write(txFrame, 200);

        long deadline = System.currentTimeMillis() + 3000;
        byte lastTxMsg = payload.length > 0 ? payload[0] : (byte) 0x00;

        boolean waitingQueued = false;

        while (System.currentTimeMillis() < deadline) {
            Frame rx = readFrame(250); // small read slices
            if (rx == null) continue;

            // Filtre: réponse au host
            if (rx.to != hostAddr) continue;

            // Mémorise le node réel
            lastResponderNode = rx.from;

            // RX trace
            traceFrame(false, rx.rawFrame, payload);

            // Return code est dans payload[0]
            int rc = (rx.payload.length >= 1) ? (rx.payload[0] & 0xFF) : 0xFF;

            if (rc == RC_REQUEST_QUEUED) {
                waitingQueued = true;
            } else if (rc == RC_NO_REQUEST_ACTIVE) {
                // Tant qu'on attend un queued: continuer à sonder
                if (waitingQueued) {
                    // continue polling
                } else {
                    // pas de requête active: on laisse remonter comme réponse
                    return new Response(lastTxMsg, rc, rx);
                }
            } else if (rc == RC_REQUEST_ABORTED) {
                throw new IOException("Queued aborted (rc=0x28)");
            } else {
                // OK ou erreur finale
                return new Response(lastTxMsg, rc, rx);
            }

            // Si queued, envoyer CHECK_REQUEST
            if (waitingQueued) {
                try { Thread.sleep(80); } catch (InterruptedException ignored) {}
                byte[] chkPayload = new byte[]{MSG_CHECK_REQUEST};
                byte[] chkFrame = encodeFrame(chkPayload);
                traceFrame(true, chkFrame, chkPayload);
                port.write(chkFrame, 200);
                waitingQueued = false;
            }
        }

        // timeout
        t("RX: <timeout>");
        t("↳ Aucun octet reçu / réponse valide avant expiration (msg=" + explainMsg(lastTxMsg) + ")");
        throw new IOException("Timeout waiting LCP response");
    }

    private void ensureOk(Response r, String ctx) throws IOException {
        if (r == null) throw new IOException(ctx + ": réponse null");
        if (r.rc != RC_OK) {
            throw new IOException(ctx + ": rc=0x" + hex2(r.rc));
        }
    }

    /* ============================================================
     * Frame encode (escape + CRC seed 0x7E7E + CRC low/high)
     * ============================================================ */

    private byte[] encodeFrame(byte[] payload) {
        int status = nextStatusByte();

        byte[] var = new byte[4 + payload.length];
        var[0] = (byte) toAddr;
        var[1] = (byte) hostAddr;
        var[2] = (byte) status;
        var[3] = (byte) (payload.length & 0xFF);
        System.arraycopy(payload, 0, var, 4, payload.length);

        // Escape variable part and compute CRC on escaped variable part
        ByteArray escVar = new ByteArray();
        for (byte b : var) {
            escVar.appendEscaped(b);
        }

        int crc = crcLcp(escVar.bytes(), 0, escVar.length());

        // Build final: ~~ + escVar + escaped crc0(low) crc1(high)
        ByteArray out = new ByteArray();
        out.append(SYNC);
        out.append(SYNC);
        out.appendBytes(escVar.bytes(), 0, escVar.length());

        byte crc0 = (byte) (crc & 0xFF);          // low
        byte crc1 = (byte) ((crc >> 8) & 0xFF);   // high
        out.appendEscapedCrc(crc0);
        out.appendEscapedCrc(crc1);

        return out.toArray();
    }

    private int nextStatusByte() {
        int st = (msgIdBit & 0x01);
        if (syncFirstEnabled && !syncUsed) {
            st |= 0x02; // SYNC bit
            syncUsed = true;
        }
        msgIdBit ^= 1; // toggle
        return st;
    }

    /* ============================================================
     * Frame read (sync + unescape + CRC verify)
     * ============================================================ */

    private Frame readFrame(int sliceTimeoutMs) throws IOException {
        // Wait for "~~"
        int s1;
        do {
            s1 = readRawByte(sliceTimeoutMs);
            if (s1 < 0) return null;
        } while (s1 != (SYNC & 0xFF));

        int s2 = readRawByte(sliceTimeoutMs);
        if (s2 < 0 || s2 != (SYNC & 0xFF)) return null;

        // Read variable part with unescaping.
        // Length counts unescaped payload bytes, but CRC computed on escaped variable bytes.
        ByteArray rawForCrc = new ByteArray();

        int to = readUnescapedByte(rawForCrc, sliceTimeoutMs);
        int from = readUnescapedByte(rawForCrc, sliceTimeoutMs);
        int status = readUnescapedByte(rawForCrc, sliceTimeoutMs);
        int len = readUnescapedByte(rawForCrc, sliceTimeoutMs);

        byte[] payload = new byte[len];
        for (int i = 0; i < len; i++) {
            payload[i] = (byte) readUnescapedByte(rawForCrc, sliceTimeoutMs);
        }

        // CRC bytes (may be escaped, but NOT included in CRC calc)
        int crc0 = readCrcByte(sliceTimeoutMs); // low
        int crc1 = readCrcByte(sliceTimeoutMs); // high

        int calc = crcLcp(rawForCrc.bytes(), 0, rawForCrc.length());
        int recv = ((crc1 & 0xFF) << 8) | (crc0 & 0xFF);

        if (calc != recv) {
            // CRC mismatch: ignore frame (keep listening), but trace if wanted
            return null;
        }

        // Recompose "raw frame" for logging: what we actually received on wire is complex due to ESC,
        // but for log readability we output a canonical unescaped view:
        byte[] canonical = buildCanonicalFrame(to, from, status, payload, crc0, crc1);

        return new Frame(to, from, status, payload, canonical);
    }

    private static byte[] buildCanonicalFrame(int to, int from, int status, byte[] payload, int crc0, int crc1) {
        byte[] out = new byte[2 + 4 + payload.length + 2];
        out[0] = SYNC;
        out[1] = SYNC;
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
            // CRC includes ESC and escaped symbol
            rawForCrc.append((byte) ESC);
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
        byte[] b = new byte[1];
        int n = port.read(b, timeoutMs);
        return (n == 1) ? (b[0] & 0xFF) : -1;
    }

    /* ============================================================
     * Trace formatting (TX/RX + explication)
     * ============================================================ */

    private void traceFrame(boolean tx, byte[] canonicalFrame, byte[] txPayload) {
        if (canonicalFrame == null) return;

        String dir = tx ? "TX" : "RX";
        t(dir + ": " + hexDump(canonicalFrame));

        List<String> expl = explainCanonicalFrame(tx, canonicalFrame, txPayload);
        for (String line : expl) {
            t("↳ " + line);
        }
    }

    private List<String> explainCanonicalFrame(boolean tx, byte[] f, byte[] relatedTxPayload) {
        List<String> out = new ArrayList<>();

        if (f.length < 8) {
            out.add("Frame trop courte");
            return out;
        }

        int to = f[2] & 0xFF;
        int from = f[3] & 0xFF;
        int status = f[4] & 0xFF;
        int len = f[5] & 0xFF;

        out.add("SYNC      : ~~");
        out.add("TO        : 0x" + hex2(to));
        out.add("FROM      : 0x" + hex2(from));
        out.add("STATUS    : 0x" + hex2(status) + " (" + explainStatus(status) + ")");
        out.add("LEN       : " + len);

        // Payload canonical starts at index 6, length = len
        byte[] pl = new byte[Math.max(0, Math.min(len, f.length - 8))];
        if (pl.length > 0) System.arraycopy(f, 6, pl, 0, pl.length);

        if (tx) {
            // TX payload starts with msgId
            if (pl.length >= 1) {
                int msg = pl[0] & 0xFF;
                out.add("MSG       : " + explainMsg((byte) msg));
                if (msg == (MSG_GET_FIELD & 0xFF) && pl.length >= 2) {
                    out.add("FIELD     : #" + (pl[1] & 0xFF));
                } else if (msg == (MSG_SET_FIELD & 0xFF) && pl.length >= 2) {
                    int field = pl[1] & 0xFF;
                    out.add("FIELD     : #" + field);
                    if (pl.length > 2) {
                        out.add("DATA      : " + hexDump(slice(pl, 2, pl.length - 2)));
                    }
                } else if (msg == (MSG_ISSUE_COMMAND & 0xFF) && pl.length >= 2) {
                    out.add("CMD       : " + explainCommand(pl[1] & 0xFF));
                }
            }
        } else {
            // RX payload: rc, devStatus, ... depends on last TX
            if (pl.length >= 1) out.add("RC        : " + explainRc(pl[0] & 0xFF));
            if (pl.length >= 2) out.add("DEVSTAT   : 0x" + hex2(pl[1] & 0xFF));

            byte txMsg = (relatedTxPayload != null && relatedTxPayload.length >= 1) ? relatedTxPayload[0] : 0;
            out.add("REPLY-TO  : " + explainMsg(txMsg));

            if ((txMsg == MSG_GET_DELIVERY_STATUS || txMsg == MSG_GET_MACHINE_STATUS) && pl.length >= 6) {
                int delStatus = u16be(pl[2], pl[3]);
                int delCode = u16be(pl[4], pl[5]);
                out.add("DELSTAT   : 0x" + hex4(delStatus));
                out.add("DELCODE   : 0x" + hex4(delCode) + " " + explainDeliveryCode(delCode));
            }
            if (txMsg == MSG_GET_FIELD && relatedTxPayload != null && relatedTxPayload.length >= 2) {
                int field = relatedTxPayload[1] & 0xFF;
                out.add("FIELD     : #" + field + " (data len=" + Math.max(0, pl.length - 2) + ")");
                if (field == 39 && pl.length >= 3) {
                    int ix = pl[2] & 0xFF;
                    out.add("DECIMALS  : index=" + ix + " -> digits=" + decimalsDigits(ix));
                }
            }
        }

        // CRC canonical at end
        int crc0 = f[f.length - 2] & 0xFF;
        int crc1 = f[f.length - 1] & 0xFF;
        out.add("CRC       : 0x" + hex2(crc1) + hex2(crc0) + " (lo=" + hex2(crc0) + " hi=" + hex2(crc1) + ")");

        return out;
    }

    private static String explainStatus(int st) {
        boolean isResp = (st & 0x80) != 0;
        boolean sync = (st & 0x02) != 0;
        boolean busy = (st & 0x04) != 0; // response busy
        boolean noReq = (st & 0x10) != 0; // response no request active (bit4)
        int msgId = (st & 0x01);
        return (isResp ? "RESP" : "CMD")
                + ", msgId=" + msgId
                + (sync ? ", SYNC" : "")
                + (busy ? ", BUSY" : "")
                + (noReq ? ", NO_REQ_ACTIVE" : "");
    }

    private static String explainMsg(byte msg) {
        int m = msg & 0xFF;
        switch (m) {
            case 0x00: return "GET_PRODUCT_ID (0x00)";
            case 0x20: return "GET_FIELD (0x20)";
            case 0x21: return "SET_FIELD (0x21)";
            case 0x22: return "PRINT_TEXT (0x22)";
            case 0x23: return "GET_MACHINE_STATUS (0x23)";
            case 0x24: return "ISSUE_COMMAND (0x24)";
            case 0x28: return "GET_DELIVERY_STATUS (0x28)";
            case 0x7D: return "CHECK_REQUEST (0x7D)";
            case 0x7E: return "ABORT_REQUEST (0x7E)";
            default:   return "UNKNOWN (0x" + hex2(m) + ")";
        }
    }

    private static String explainCommand(int cmd) {
        switch (cmd & 0xFF) {
            case 0x00: return "RUN (0x00)";
            case 0x01: return "PAUSE (0x01)";
            case 0x02: return "END DELIVERY (0x02)";
            case 0x06: return "PRINT/CLEAR TICKET (0x06)";
            default:   return "UNKNOWN (0x" + hex2(cmd) + ")";
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

    private static String explainDeliveryCode(int delCode) {
        List<String> flags = new ArrayList<>();
        if ((delCode & 0x0001) != 0) flags.add("TICKET_PENDING");
        if ((delCode & 0x0004) != 0) flags.add("FLOW_ACTIVE");
        if ((delCode & 0x0008) != 0) flags.add("DELIVERY_ACTIVE");
        if ((delCode & 0x0400) != 0) flags.add("BEGIN_DELIVERY");
        if (flags.isEmpty()) return "(none)";
        return "(" + join(flags, " | ") + ")";
    }

    private static int decimalsDigits(int idx) {
        switch (idx) {
            case 0: return 2; // Hundredths
            case 1: return 1; // Tenths
            case 2: return 0; // Whole
            case 3: return 3; // Thousandths
            default: return 2;
        }
    }

    /* ============================================================
     * CRC LCP: seed 0x7E7E, POLY 0x1021, bitwise update
     * ============================================================ */

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

    /* ============================================================
     * Helpers
     * ============================================================ */

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
        if (b == null) return "<null>";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(hex2(b[i] & 0xFF));
        }
        return sb.toString();
    }

    private static byte[] slice(byte[] src, int off, int len) {
        if (len <= 0) return new byte[0];
        byte[] out = new byte[len];
        System.arraycopy(src, off, out, 0, len);
        return out;
    }

    private static String hex2(int v) {
        return String.format("%02X", v & 0xFF);
    }

    private static String hex4(int v) {
        return String.format("%04X", v & 0xFFFF);
    }

    private static String join(List<String> items, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(sep);
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    /* ============================================================
     * Data types
     * ============================================================ */

    private static final class Frame {
        final int to, from, status;
        final byte[] payload;
        final byte[] rawFrame; // canonical view (~~ + to/from/status/len/payload/crc0/crc1)

        Frame(int to, int from, int status, byte[] payload, byte[] canonical) {
            this.to = to;
            this.from = from;
            this.status = status;
            this.payload = payload;
            this.rawFrame = canonical;
        }
    }

    private static final class Response {
        final byte txMsg;
        final int rc;          // payload[0]
        final byte[] payload;  // full payload (rc, devStatus, ...)

        Response(byte txMsg, int rc, Frame rx) {
            this.txMsg = txMsg;
            this.rc = rc;
            this.payload = (rx != null && rx.payload != null) ? rx.payload : new byte[0];
        }
    }

    /**
     * Minimal dynamic byte buffer.
     */
    private static final class ByteArray {
        private byte[] buf = new byte[256];
        private int len = 0;

        void append(byte b) {
            ensure(1);
            buf[len++] = b;
        }

        void appendBytes(byte[] b, int off, int l) {
            if (l <= 0) return;
            ensure(l);
            System.arraycopy(b, off, buf, len, l);
            len += l;
        }

        void appendEscaped(byte b) {
            int v = b & 0xFF;
            if (v == (ESC & 0xFF) || v == (SYNC & 0xFF)) {
                append(ESC);
            }
            append(b);
        }

        void appendEscapedCrc(byte b) {
            // Escaping CRC bytes is allowed but they are NOT part of CRC calc.
            int v = b & 0xFF;
            if (v == (ESC & 0xFF) || v == (SYNC & 0xFF)) {
                append(ESC);
            }
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
