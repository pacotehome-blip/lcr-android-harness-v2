
package com.pa.lcr.lcp;

import com.hoho.android.usbserial.driver.UsbSerialPort;

import java.io.IOException;
import java.util.Arrays;

public class LcpLink {

    /* ================================================================
       CONSTANTES PROTOCOLE LCP
       ================================================================ */
    public static final int TILDE = 0x7E;
    public static final int ESC   = 0x1B;

    private static final int SEED = 0x7E7E;   // CRC initial seed
    private static final int POLY = 0x1021;   // CRC polynomial

    public static final int MSG_GET_FIELD        = 0x20;
    public static final int MSG_SET_FIELD        = 0x21;
    public static final int MSG_PRINT_TEXT       = 0x22;
    public static final int MSG_GET_MACHINE      = 0x23;
    public static final int MSG_ISSUE_COMMAND    = 0x24;
    public static final int MSG_GET_DEL_STATUS   = 0x28;
    public static final int MSG_CHECK_REQUEST    = 0x7D;

    public static final int RC_OK                = 0x00;
    public static final int RC_REQUEST_QUEUED    = 0x26;
    public static final int RC_NO_REQUEST_ACTIVE = 0x27;
    public static final int RC_REQUEST_ABORTED   = 0x28;

    public static final int LCRSc_DEL_TICKET_PENDING = 0x0001;
    public static final int LCRSc_FLOW_ACTIVE        = 0x0004;
    public static final int LCRSc_DELIVERY_ACTIVE    = 0x0008;
    public static final int LCRSc_BEGIN_DELIVERY     = 0x0400;

    public static boolean DUMP_TX = false;
    public static boolean DUMP_RX = false;

    private static Logger logger = null;

    public static void setLogger(Logger log) { logger = log; }
    private static void log(String s) { if (logger != null) logger.log(s); }

    /* ================================================================
       STRUCTURE LCP STATUS (header[2])
       ================================================================ */

    public static class LcpStatus {
        public boolean toggle;
        public boolean sync;
        public boolean busy;

        public static LcpStatus fromByte(int b) {
            LcpStatus s = new LcpStatus();
            s.toggle = (b & 0x01) != 0;
            s.sync   = (b & 0x02) != 0;
            s.busy   = (b & 0x04) != 0;  // vrai busy LCP
            return s;
        }
    }

    /* ================================================================
       ATTRIBUTS
       ================================================================ */

    private final UsbSerialPort port;
    private final int toAddr;
    private final int fromAddr;
    private final boolean syncFirst;

    private int msgId = 0;
    private boolean syncUsed = false;

    public LcpLink(UsbSerialPort port, int to, int from, boolean syncFirst) {
        this.port = port;
        this.toAddr = to & 0xFF;
        this.fromAddr = from & 0xFF;
        this.syncFirst = syncFirst;
    }

    /* ================================================================
       CRC
       ================================================================ */

    private int crcUpdate(int crc, int b) {
        for (int i = 7; i >= 0; i--) {
            boolean fb = (crc & 0x8000) != 0;
            crc = ((crc << 1) & 0xFFFF) | ((b >> i) & 0x01);
            if (fb) crc ^= POLY;
        }
        return crc;
    }

    private int crcLCP(byte[] data) {
        int crc = SEED;
        for (byte x : data) crc = crcUpdate(crc, x & 0xFF);
        return crc;
    }

    /* ================================================================
       ESC / UNESC
       ================================================================ */

    private byte[] escapeStream(byte[] in) {
        ByteArrayBuilder out = new ByteArrayBuilder(in.length * 2);
        for (byte x : in) {
            if ((x & 0xFF) == ESC || (x & 0xFF) == TILDE) out.add((byte) ESC);
            out.add(x);
        }
        return out.toByteArray();
    }

    /* ================================================================
       FRAME BUILD
       ================================================================ */

    private byte[] buildFrame(byte[] payload) {
        int status = msgId & 0x01;

        if (syncFirst && !syncUsed) {
            status |= 0x02;   // SYNC bit
            syncUsed = true;
        }
        msgId ^= 0x01;        // toggle

        byte[] header = {
                (byte) toAddr,
                (byte) fromAddr,
                (byte) status,
                (byte) payload.length
        };

        byte[] var = ByteArrayBuilder.concat(header, payload);
        byte[] varEsc = escapeStream(var);

        int crcVal = crcLCP(varEsc);
        byte[] crcEsc = escapeStream(new byte[]{
                (byte) (crcVal & 0xFF),
                (byte) ((crcVal >> 8) & 0xFF)
        });

        ByteArrayBuilder out = new ByteArrayBuilder();
        out.add((byte) TILDE);
        out.add((byte) TILDE);
        out.add(varEsc);
        out.add(crcEsc);

        byte[] frame = out.toByteArray();
        if (DUMP_TX) log("TX: " + hex(frame));
        return frame;
    }

    /* ================================================================
       READ ONE BYTE (USB)
       ================================================================ */

    private int readByte(int timeoutMs) {
        byte[] buf = new byte[1];
        try {
            int n = port.read(buf, timeoutMs);
            if (n <= 0) return -1;
            return buf[0] & 0xFF;
        } catch (Exception e) { return -1; }
    }

    private static class RawByte {
        final int decoded;
        final byte[] raw;
        RawByte(int d, byte[] r) { decoded = d; raw = r; }
    }

    private RawByte readEscapedByte(int timeout) {
        int b = readByte(timeout);
        if (b < 0) return new RawByte(-1, new byte[0]);

        if (b == ESC) {
            int y = readByte(timeout);
            if (y < 0) return new RawByte(-1, new byte[]{ (byte) ESC });
            return new RawByte(y, new byte[]{ (byte) ESC, (byte) y });
        }
        return new RawByte(b, new byte[]{ (byte) b });
    }

    /* ================================================================
       READ FRAME COMPLET
       ================================================================ */

    public byte[] readFrame(int timeoutMs) throws IOException {
        long tEnd = System.currentTimeMillis() + timeoutMs;

        /* SYNC ~~ */
        int sync = 0;
        while (System.currentTimeMillis() < tEnd) {
            int b = readByte(timeoutMs);
            if (b < 0) continue;
            if (b == TILDE) {
                sync++;
                if (sync == 2) break;
            } else sync = 0;
        }
        if (sync < 2) throw new IOException("Timeout sync ~~");

        /* Header (4 bytes ESC escaped) */
        byte[] rawHdr = new byte[0];
        int[] hdr = new int[4];

        for (int i = 0; i < 4; i++) {
            RawByte v = readEscapedByte(timeoutMs);
            if (v.decoded < 0) throw new IOException("Header timeout");
            hdr[i] = v.decoded;
            rawHdr = ByteArrayBuilder.concat(rawHdr, v.raw);
        }

        int plen = hdr[3] & 0xFF;

        /* Payload */
        byte[] rawPay = new byte[0];
        byte[] pay = new byte[plen];

        for (int i = 0; i < plen; i++) {
            RawByte v = readEscapedByte(timeoutMs);
            if (v.decoded < 0) throw new IOException("Payload timeout");
            pay[i] = (byte) v.decoded;
            rawPay = ByteArrayBuilder.concat(rawPay, v.raw);
        }

        /* CRC */
        RawByte c0 = readEscapedByte(timeoutMs);
        RawByte c1 = readEscapedByte(timeoutMs);

        if (c0.decoded < 0 || c1.decoded < 0)
            throw new IOException("CRC timeout");

        int crcRx = (c0.decoded & 0xFF) | ((c1.decoded & 0xFF) << 8);

        byte[] fullEsc = ByteArrayBuilder.concat(rawHdr, rawPay);
        int crcCalc = crcLCP(fullEsc);

        if (crcCalc != crcRx)
            throw new IOException(String.format("CRC mismatch recv=%04X calc=%04X", crcRx, crcCalc));

        ByteArrayBuilder fr = new ByteArrayBuilder();
        fr.add((byte) TILDE);
        fr.add((byte) TILDE);
        fr.add(rawHdr);
        fr.add(rawPay);
        fr.add((byte) c0.decoded);
        fr.add((byte) c1.decoded);

        byte[] rsp = fr.toByteArray();
        if (DUMP_RX) log("RX: " + hex(rsp));
        return rsp;
    }

    /* ================================================================
       sendRecv() — AVEC GESTION BUSY LCP
       ================================================================ */

    public byte[] sendRecv(byte[] payload, int timeoutMs) throws IOException {
        byte[] frame = buildFrame(payload);
        try { port.purgeHwBuffers(true, true); } catch(Exception ignored){}

        port.write(frame, timeoutMs);

        byte[] rsp = readFrame(timeoutMs);
        LcpStatus st = extractStatus(rsp);

        // Si le LCP dit BUSY → passer par 0x7D
        if (st.busy)
            return waitQueued(5.0, 0.2);

        return rsp;
    }

    /* ================================================================
       STATUS & PAYLOAD EXTRACTORS
       ================================================================ */

    public static LcpStatus extractStatus(byte[] frame) {
        return LcpStatus.fromByte(frame[4] & 0xFF);
    }

    public static byte[] extractPayload(byte[] frame) {
        int ln = frame[5] & 0xFF;
        return Arrays.copyOfRange(frame, 6, 6 + ln);
    }

    private static int u16be(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static int i32be(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24)
                | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8)
                |  (b[off + 3] & 0xFF);
    }

    /* ================================================================
       WAIT QUEUED — CORRIGÉ
       ================================================================ */

    private byte[] waitQueued(double timeoutSec, double pollSec) throws IOException {
        long tEnd = System.currentTimeMillis() + (long)(timeoutSec * 1000);
        byte[] last = new byte[0];

        while (System.currentTimeMillis() < tEnd) {

            byte[] rsp = readFrame(3000);
            LcpStatus st = extractStatus(rsp);

            byte[] p = extractPayload(rsp);
            if (p != null && p.length > 0) last = p;

            if (st.busy) {
                sleepMs((int)(pollSec*1000));
                continue;
            }

            if (p == null || p.length == 0) {
                sleepMs((int)(pollSec*1000));
                continue;
            }

            int rc = p[0] & 0xFF;

            if (rc == RC_REQUEST_ABORTED) throw new IOException("Queue aborted");

            if (rc == RC_REQUEST_QUEUED || rc == RC_NO_REQUEST_ACTIVE) {
                sleepMs((int)(pollSec*1000));
                continue;
            }

            if (rc == RC_OK && p.length >= 2 && (p[1] & 0xFF) == RC_OK)
                return Arrays.copyOfRange(p, 1, p.length);

            return p;
        }
        throw new IOException("Queued timeout last=" + hex(last));
    }

    /* ================================================================
       OP GET_FIELD / SET_FIELD / ISSUE_COMMAND — CORRIGÉS
       ================================================================ */

    public byte[] opGetField(int fieldNum) throws IOException {
        byte[] rsp = sendRecv(new byte[]{ (byte)MSG_GET_FIELD, (byte)fieldNum }, 2000);
        LcpStatus st = extractStatus(rsp);
        byte[] p = extractPayload(rsp);

        if (st.busy || (p != null && p.length > 0 && p[0] == RC_REQUEST_QUEUED))
            p = waitQueued(5.0, 0.2);

        if (p == null || p.length < 1 || p[0] != RC_OK)
            throw new IOException("GET_FIELD #" + fieldNum);

        return Arrays.copyOfRange(p, 2, p.length);
    }

    public void opSetField(int fieldNum, byte[] data) throws IOException {
        byte[] pl = new byte[2 + (data == null ? 0 : data.length)];
        pl[0] = (byte)MSG_SET_FIELD;
        pl[1] = (byte)fieldNum;
        if (data != null) System.arraycopy(data, 0, pl, 2, data.length);

        byte[] rsp = sendRecv(pl, 2000);
        LcpStatus st = extractStatus(rsp);
        byte[] p = extractPayload(rsp);

        if (st.busy || (p != null && p.length>0 && p[0] == RC_REQUEST_QUEUED))
            p = waitQueued(5.0, 0.2);

        if (p == null || p.length < 1 || p[0] != RC_OK)
            throw new IOException("SET_FIELD #" + fieldNum);
    }

    public byte[] opIssueCommand(int cmd) throws IOException {
        byte[] rsp = sendRecv(new byte[]{ (byte)MSG_ISSUE_COMMAND, (byte)cmd }, 2000);
        LcpStatus st = extractStatus(rsp);
        byte[] p = extractPayload(rsp);

        if (st.busy || (p != null && p.length>0 && p[0] == RC_REQUEST_QUEUED))
            p = waitQueued(5.0, 0.2);

        if (p == null || p.length < 1 || p[0] != RC_OK)
            throw new IOException(String.format("CMD 0x%02X", cmd));

        return p;
    }

    /* ================================================================
       MACHINE STATUS + DELIVERY STATUS
       ================================================================ */

    public int[] opDeliveryStatus() throws IOException {
        byte[] rsp = sendRecv(new byte[]{ (byte)MSG_GET_DEL_STATUS }, 2000);
        LcpStatus st = extractStatus(rsp);
        byte[] p = extractPayload(rsp);

        if (st.busy || (p != null && p.length>0 && p[0] == RC_REQUEST_QUEUED))
            p = waitQueued(5.0, 0.2);

        if (p == null || p.length < 6 || p[0] != RC_OK)
            throw new IOException("Invalid 0x28");

        int ds = u16be(p, 2);
        int dc = u16be(p, 4);
        return new int[]{ ds, dc };
    }

    public int[] opMachineStatusFull() throws IOException {
        byte[] rsp = sendRecv(new byte[]{ (byte)MSG_GET_MACHINE }, 2000);
        LcpStatus st = extractStatus(rsp);
        byte[] p = extractPayload(rsp);

        if (st.busy || (p != null && p.length>0 && p[0] == RC_REQUEST_QUEUED))
            p = waitQueued(5.0, 0.2);

        if (p == null || p.length < 8 || p[0] != RC_OK) {
            int[] d = opDeliveryStatus();
            return new int[]{ 0x0000, d[0], d[1] };
        }

        int dev = u16be(p, 2);
        int ds  = u16be(p, 4);
        int dc  = u16be(p, 6);
        return new int[]{ dev, ds, dc };
    }

    /* ================================================================
       WAIT_FOR_FLOW_ONLY – sera corrigé dans DeliveryController.
       ================================================================ */

    /* ================================================================
       UTIL
       ================================================================ */

    private static void sleepMs(int ms) {
        try { Thread.sleep(ms); } catch(Exception ignored){}
    }

    private static String hex(byte[] b) {
        if (b == null) return "(null)";
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X ", x));
        return sb.toString().trim();
    }

    private static class ByteArrayBuilder {
        private byte[] buf;
        private int len;

        ByteArrayBuilder() { this(64); }
        ByteArrayBuilder(int cap) { buf = new byte[cap]; len = 0; }

        void add(byte b) { ensure(1); buf[len++] = b; }
        void add(byte[] bb) {
            if (bb == null) return;
            ensure(bb.length);
            System.arraycopy(bb, 0, buf, len, bb.length);
            len += bb.length;
        }

        byte[] toByteArray() { return Arrays.copyOf(buf, len); }

        private void ensure(int n) {
            if (len + n > buf.length) {
                int nc = Math.max(buf.length * 2, len + n);
                buf = Arrays.copyOf(buf, nc);
            }
        }

        static byte[] concat(byte[] a, byte[] b) {
            if (a == null || a.length == 0) return b;
            if (b == null || b.length == 0) return a;
            byte[] out = Arrays.copyOf(a, a.length + b.length);
            System.arraycopy(b, 0, out, a.length, b.length);
            return out;
        }
    }

    /* Logger */
    public interface Logger { void log(String s); }
}
