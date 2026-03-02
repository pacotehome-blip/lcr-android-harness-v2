
package com.pa.lcr.lcp;

import com.hoho.android.usbserial.driver.UsbSerialPort;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * LCP transport layer
 * RX conforme terrain (Python-like):
 *  - RX cumulatif
 *  - read() == 0 n'est jamais une erreur
 *  - timeout GLOBAL seulement
 *  - aucun drain/resync destructeur
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

    private static final int QP_MS = 200; // cadence CHECK_REQUEST

    // ===================== PORT =====================

    private static final Object PORT_LOCK = new Object();
    private final UsbSerialPort port;
    private final int toAddr;
    private final int hostAddr;

    private volatile boolean closed = false;

    // ===================== TRACE =====================

    public interface TraceSink {
        void onTrace(String line);
    }

    private volatile TraceSink trace;
    private volatile boolean traceTsEnabled = false;

    private static final ThreadLocal<SimpleDateFormat> TRACE_DF =
            ThreadLocal.withInitial(() ->
                    new SimpleDateFormat("HH:mm:ss.SSS", Locale.CANADA_FRENCH));

    public void setTraceSink(TraceSink sink) {
        this.trace = sink;
    }

    public void setTraceTimestampsEnabled(boolean enabled) {
        this.traceTsEnabled = enabled;
    }

    private void t(String s) {
        TraceSink ts = trace;
        if (ts == null) return;
        if (traceTsEnabled && (s.startsWith("TX:") || s.startsWith("RX:") || s.startsWith("↳"))) {
            ts.onTrace("[IO " + TRACE_DF.get().format(new Date()) + "] " + s);
        } else {
            ts.onTrace(s);
        }
    }

    // ===================== RX BUFFER (CLÉ DU FIX) =====================

    private final ByteArray rxBuf = new ByteArray();

    // ===================== SESSION =====================

    private int msgIdBit = 0;
    private boolean syncUsed = false;

    public LcpLink(UsbSerialPort port, int toAddr, int hostAddr, boolean syncFirst) {
        this.port = port;
        this.toAddr = toAddr & 0xFF;
        this.hostAddr = hostAddr & 0xFF;
    }

    public boolean isClosed() {
        return closed;
    }

    public synchronized void close() {
        closed = true;
        try {
            synchronized (PORT_LOCK) {
                port.close();
            }
        } catch (Exception ignored) {
        }
    }

    // ===================== API PUBLIQUE =====================

    public byte[] opGetField(int field) throws IOException {
        Response r = sendRecv(buildPayload(MSG_GET_FIELD, new byte[]{(byte) field}), 5000);
        ensureOk(r, "GET_FIELD #" + field);
        if (r.payload.length < 2) throw new IOException("GET_FIELD payload trop court");
        byte[] out = new byte[r.payload.length - 2];
        System.arraycopy(r.payload, 2, out, 0, out.length);
        return out;
    }

    public void opSetField(int field, byte[] value) throws IOException {
        byte[] pl = new byte[2 + (value == null ? 0 : value.length)];
        pl[0] = MSG_SET_FIELD;
        pl[1] = (byte) field;
        if (value != null && value.length > 0) {
            System.arraycopy(value, 0, pl, 2, value.length);
        }
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
        if (r.payload.length < 6) throw new IOException("DELIVERY_STATUS payload trop court");
        int ds = u16be(r.payload[2], r.payload[3]);
        int dc = u16be(r.payload[4], r.payload[5]);
        return new int[]{ds, dc};
    }

    // ===================== SEND / RECV (RX CONFORME) =====================

    private synchronized Response sendRecv(byte[] payload, int timeoutMs) throws IOException {
        if (closed) throw new IOException("Transport closed");

        byte[] frame = encodeFrame(payload);
        t("TX: " + hexDump(frame));

        synchronized (PORT_LOCK) {
            port.write(frame, 500);
        }

        long deadline = System.currentTimeMillis() + timeoutMs;
        boolean queued = false;
        int lastQueued = -1;
        long nextCheck = 0;

        while (System.currentTimeMillis() < deadline) {

            if (queued && System.currentTimeMillis() >= nextCheck) {
                byte[] chk = encodeFrame(new byte[]{MSG_CHECK_REQUEST});
                t("TX: " + hexDump(chk));
                synchronized (PORT_LOCK) {
                    port.write(chk, 500);
                }
                nextCheck = System.currentTimeMillis() + QP_MS;
            }

            Frame f = readFrameUntil(deadline);
            if (f == null) break;

            t("RX: " + hexDump(f.raw));

            int rc = (f.payload.length > 0) ? (f.payload[0] & 0xFF) : 0xFF;

            if (rc == RC_REQUEST_QUEUED || rc == RC_NO_REQUEST_ACTIVE) {
                queued = true;
                lastQueued = rc;
                if (nextCheck == 0) nextCheck = System.currentTimeMillis() + QP_MS;
                continue;
            }

            if (rc == RC_REQUEST_ABORTED) {
                throw new IOException("Queued aborted");
            }

            if (queued && rc == RC_OK && f.payload.length >= 2 && (f.payload[1] & 0xFF) == RC_OK) {
                byte[] norm = new byte[f.payload.length - 1];
                System.arraycopy(f.payload, 1, norm, 0, norm.length);
                return new Response(norm[0] & 0xFF, norm);
            }

            return new Response(rc, f.payload);
        }

        if (queued) {
            throw new IOException("Queued timeout last=0x" + hex2(lastQueued));
        }
        throw new IOException("Timeout waiting LCP response");
    }

    // ===================== RX CONFORME =====================

    private void rxReadSome(int timeoutMs) throws IOException {
        byte[] tmp = new byte[64];
        int n;
        synchronized (PORT_LOCK) {
            if (closed) return;
            n = port.read(tmp, timeoutMs);
        }
        if (n > 0) {
            rxBuf.appendBytes(tmp, 0, n);
        }
    }

    private Frame readFrameUntil(long deadlineMs) throws IOException {
        while (!closed && System.currentTimeMillis() < deadlineMs) {

            rxReadSome(50);

            int syncPos = findSync(rxBuf);
            if (syncPos < 0) continue;

            if (syncPos > 0) rxBuf.drop(syncPos);

            Frame f = tryParseFrame(rxBuf);
            if (f != null) return f;
        }
        return null;
    }

    private int findSync(ByteArray b) {
        for (int i = 0; i + 1 < b.len; i++) {
            if ((b.buf[i] & 0xFF) == SYNC && (b.buf[i + 1] & 0xFF) == SYNC) {
                return i;
            }
        }
        return -1;
    }

    private Frame tryParseFrame(ByteArray b) {
        try {
            if (b.len < 6) return null;

            int idx = 2;
            int to = b.peekUnescaped(idx++);
            int from = b.peekUnescaped(idx++);
            int status = b.peekUnescaped(idx++);
            int len = b.peekUnescaped(idx++);

            int payloadStart = idx;
            for (int i = 0; i < len; i++) b.peekUnescaped(idx++);

            int crc0 = b.peekUnescaped(idx++);
            int crc1 = b.peekUnescaped(idx++);

            byte[] crcData = b.sliceUnescaped(2, idx - 2);
            int calc = crcLcp(crcData, 0, crcData.length);
            int recv = ((crc1 & 0xFF) << 8) | (crc0 & 0xFF);

            if (calc != recv) {
                b.drop(1);
                return null;
            }

            byte[] payload = b.extractPayload(payloadStart, len);
            byte[] canonical = b.extractCanonical(idx);

            b.drop(idx);
            return new Frame(to, from, status, payload, canonical);

        } catch (IncompleteFrameException e) {
            return null;
        }
    }

    // ===================== FRAMING / CRC =====================

    private byte[] encodeFrame(byte[] payload) {
        int status = nextStatusByte();
        ByteArray var = new ByteArray();
        var.append((byte) toAddr);
        var.append((byte) hostAddr);
        var.append((byte) status);
        var.append((byte) payload.length);
        var.appendBytes(payload, 0, payload.length);

        ByteArray esc = new ByteArray();
        for (int i = 0; i < var.len; i++) esc.appendEscaped(var.buf[i]);

        int crc = crcLcp(esc.buf, 0, esc.len);

        ByteArray out = new ByteArray();
        out.append(SYNC);
        out.append(SYNC);
        out.appendBytes(esc.buf, 0, esc.len);
        out.appendEscaped((byte) (crc & 0xFF));
        out.appendEscaped((byte) ((crc >> 8) & 0xFF));

        return out.toArray();
    }

    private int nextStatusByte() {
        int st = msgIdBit & 1;
        if (!syncUsed) {
            st |= 0x02;
            syncUsed = true;
        }
        msgIdBit ^= 1;
        return st;
    }

    // ===================== UTILITAIRES =====================

    private static void ensureOk(Response r, String ctx) throws IOException {
        if (r.rc != RC_OK) throw new IOException(ctx + " rc=0x" + hex2(r.rc));
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

    private static String hex2(int v) {
        return String.format("%02X", v & 0xFF);
    }

    // ===================== STRUCTURES =====================

    private static final class Frame {
        final int to, from, status;
        final byte[] payload;
        final byte[] raw;

        Frame(int to, int from, int status, byte[] payload, byte[] raw) {
            this.to = to;
            this.from = from;
            this.status = status;
            this.payload = payload;
            this.raw = raw;
        }
    }

    private static final class Response {
        final int rc;
        final byte[] payload;

        Response(int rc, byte[] payload) {
            this.rc = rc;
            this.payload = payload;
        }
    }

    private static final class IncompleteFrameException extends Exception {
    }

    private static final class ByteArray {
        byte[] buf = new byte[256];
        int len = 0;

        void append(byte b) {
            ensure(1);
            buf[len++] = b;
        }

        void appendBytes(byte[] b, int off, int l) {
            ensure(l);
            System.arraycopy(b, off, buf, len, l);
            len += l;
        }

        void appendEscaped(byte b) {
            int v = b & 0xFF;
            if (v == ESC || v == SYNC) append(ESC);
            append(b);
        }

        int peekUnescaped(int idx) throws IncompleteFrameException {
            if (idx >= len) throw new IncompleteFrameException();
            int b = buf[idx] & 0xFF;
            if (b == ESC) {
                if (idx + 1 >= len) throw new IncompleteFrameException();
                return buf[idx + 1] & 0xFF;
            }
            return b;
        }

        byte[] sliceUnescaped(int off, int lenReq) throws IncompleteFrameException {
            ByteArray out = new ByteArray();
            int idx = off;
            while (out.len < lenReq) {
                if (idx >= len) throw new IncompleteFrameException();
                int b = buf[idx++] & 0xFF;
                if (b == ESC) {
                    if (idx >= len) throw new IncompleteFrameException();
                    b = buf[idx++] & 0xFF;
                }
                out.append((byte) b);
            }
            return out.toArray();
        }

        byte[] extractPayload(int payloadStart, int payloadLen) throws IncompleteFrameException {
            return sliceUnescaped(payloadStart, payloadLen);
        }

        byte[] extractCanonical(int upto) {
            byte[] out = new byte[upto];
            System.arraycopy(buf, 0, out, 0, upto);
            return out;
        }

        void drop(int n) {
            if (n <= 0) return;
            System.arraycopy(buf, n, buf, 0, len - n);
            len -= n;
        }

        byte[] toArray() {
            byte[] out = new byte[len];
            System.arraycopy(buf, 0, out, 0, len);
            return out;
        }

        private void ensure(int extra) {
            if (len + extra <= buf.length) return;
            byte[] nb = new byte[Math.max(buf.length * 2, len + extra + 64)];
            System.arraycopy(buf, 0, nb, 0, len);
            buf = nb;
        }
    }
}
