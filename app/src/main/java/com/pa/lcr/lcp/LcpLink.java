
package com.pa.lcr.lcp;

import com.hoho.android.usbserial.driver.UsbSerialPort;

import java.io.IOException;
import java.util.Arrays;

public class LcpLink {

    /* ================================================================
       CONSTANTES PROTOCOLE (documentation LC)
       ================================================================ */
    public static final int TILDE = 0x7E;
    public static final int ESC   = 0x1B;

    private static final int SEED = 0x7E7E;   // CRC initial seed
    private static final int POLY = 0x1021;   // CRC polynomial

    // --- LCP opcodes & RC (parité avec Python lcr_simple_deliverV2.py) ---
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

    // --- Masques LCR (delCode) ---
    public static final int LCRSc_DEL_TICKET_PENDING = 0x0001;
    public static final int LCRSc_FLOW_ACTIVE        = 0x0004;
    public static final int LCRSc_DELIVERY_ACTIVE    = 0x0008;
    public static final int LCRSc_BEGIN_DELIVERY     = 0x0400;

    public static boolean DUMP_TX = false;
    public static boolean DUMP_RX = false;

    private static Logger logger = null;

    public static void setLogger(Logger log) {
        logger = log;
    }

    private static void log(String s) {
        if (logger != null) logger.log(s);
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
       CRC LCP
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
        for (byte x : data) {
            crc = crcUpdate(crc, x & 0xFF);
        }
        return crc;
    }

    /* ================================================================
       ESCAPE / UNESCAPE
       ================================================================ */

    private byte[] escapeStream(byte[] in) {
        ByteArrayBuilder out = new ByteArrayBuilder(in.length * 2);
        for (byte x : in) {
            if ((x & 0xFF) == ESC || (x & 0xFF) == TILDE) {
                out.add((byte) ESC);
            }
            out.add(x);
        }
        return out.toByteArray();
    }

    /* ================================================================
       Construction d’un frame (identique Python)
        - status: bit0 toggle, bit1 SYNC première trame si syncFirst
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
        byte c0 = (byte) (crcVal & 0xFF);
        byte c1 = (byte) ((crcVal >> 8) & 0xFF);
        byte[] crcEsc = escapeStream(new byte[]{ c0, c1 });

        ByteArrayBuilder out = new ByteArrayBuilder(varEsc.length + crcEsc.length + 2);
        out.add((byte) TILDE);
        out.add((byte) TILDE);
        out.add(varEsc);
        out.add(crcEsc);

        byte[] frame = out.toByteArray();
        if (DUMP_TX) log("TX: " + hex(frame));
        return frame;
    }

    /* ================================================================
       STRUCTURE RawByte pour lecture ESC
       ================================================================ */

    private static class RawByte {
        final int decoded;     // 0..255
        final byte[] raw;      // 1 ou 2 bytes tels que lus

        RawByte(int decoded, byte[] raw) {
            this.decoded = decoded;
            this.raw = raw;
        }
    }

    /* ================================================================
       Lecture d’un octet, avec gestion ESC
       ================================================================ */

    private RawByte readEscapedByte(int timeout) throws IOException {
        int b = readByte(timeout);
        if (b < 0) return new RawByte(-1, new byte[0]);

        if (b == ESC) {
            int y = readByte(timeout);
            if (y < 0) {
                return new RawByte(-1, new byte[]{ (byte) ESC });
            }
            return new RawByte(y, new byte[]{ (byte) ESC, (byte) y });
        }

        return new RawByte(b, new byte[]{ (byte) b });
    }

    /* ================================================================
       Lecture 1 byte
       ================================================================ */

    private int readByte(int timeoutMs) throws IOException {
        byte[] buf = new byte[1];
        try {
            int n = port.read(buf, timeoutMs);
            if (n <= 0) return -1;
            return buf[0] & 0xFF;
        } catch (Exception e) {
            return -1;
        }
    }

    /* ================================================================
       Lecture d’un frame complet
       ================================================================ */

    public byte[] readFrame(int timeoutMs) throws IOException {
        long tEnd = System.currentTimeMillis() + timeoutMs;

        /* Sync ~~ */
        int sync = 0;
        while (System.currentTimeMillis() < tEnd) {
            int b = readByte(timeoutMs);
            if (b < 0) continue;
            if (b == TILDE) {
                sync++;
                if (sync == 2) break;
            } else {
                sync = 0;
            }
        }
        if (sync < 2) throw new IOException("Timeout sync ~~");

        /* Header */
        byte[] rawHdr = new byte[0];
        int[] hdr = new int[4];

        for (int i = 0; i < 4; i++) {
            RawByte v = readEscapedByte(timeoutMs);
            if (v.decoded < 0) throw new IOException("Timeout header");
            hdr[i] = v.decoded;
            rawHdr = ByteArrayBuilder.concat(rawHdr, v.raw);
        }

        int plen = hdr[3] & 0xFF;

        /* Payload */
        byte[] rawData = new byte[0];
        byte[] data = new byte[plen];

        for (int i = 0; i < plen; i++) {
            RawByte v = readEscapedByte(timeoutMs);
            if (v.decoded < 0) throw new IOException("Timeout payload");
            data[i] = (byte) v.decoded;
            rawData = ByteArrayBuilder.concat(rawData, v.raw);
        }

        /* CRC (2 bytes) */
        RawByte c0 = readEscapedByte(timeoutMs);
        RawByte c1 = readEscapedByte(timeoutMs);
        if (c0.decoded < 0 || c1.decoded < 0)
            throw new IOException("Timeout CRC");

        byte[] crcRaw = ByteArrayBuilder.concat(c0.raw, c1.raw);
        int crcRx = (c0.decoded & 0xFF) | ((c1.decoded & 0xFF) << 8);

        byte[] fullEsc = ByteArrayBuilder.concat(rawHdr, rawData);
        int crcCalc = crcLCP(fullEsc);

        if (crcCalc != crcRx)
            throw new IOException(String.format("CRC mismatch: recv=%04X calc=%04X", crcRx, crcCalc));

        /* Reconstruction du frame complet */
        ByteArrayBuilder fr = new ByteArrayBuilder();
        fr.add((byte) TILDE);
        fr.add((byte) TILDE);
        fr.add(rawHdr);
        fr.add(rawData);
        fr.add((byte) c0.decoded);
        fr.add((byte) c1.decoded);

        byte[] rsp = fr.toByteArray();
        if (DUMP_RX) log("RX: " + hex(rsp));
        return rsp;
    }

    /* ================================================================
       sendRecv()
       ================================================================ */

    public byte[] sendRecv(byte[] payload, int timeoutMs) throws IOException {
        byte[] frame = buildFrame(payload);

        try { port.purgeHwBuffers(true, true); } catch(Exception ignored){}

        port.write(frame, timeoutMs);
        return readFrame(timeoutMs);
    }

    /* ================================================================
       Helpers : extractors
       ================================================================ */

    public static int extractStatus(byte[] frame) {
        return frame[4] & 0xFF;
    }

    public static byte[] extractPayload(byte[] frame) {
        int ln = frame[5] & 0xFF;
        return Arrays.copyOfRange(frame, 6, 6 + ln);
    }

    // Aides endianness (parité Python)
    private static int u16be(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }
    private static int i32be(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24)
             | ((b[off + 1] & 0xFF) << 16)
             | ((b[off + 2] & 0xFF) << 8)
             |  (b[off + 3] & 0xFF);
    }

    // Alias conviviaux
    public static int  status(byte[] frame)  { return extractStatus(frame); }
    public static byte[] payload(byte[] fr)  { return extractPayload(fr); }

    /* ================================================================
       QUEUE 0x7D : attente des requêtes "queued"
       (parité avec wait_queued() Python)
       ================================================================ */
    private byte[] waitQueued(double timeoutSec, double pollSec) throws IOException {
        long tEnd = System.currentTimeMillis() + (long)(timeoutSec * 1000);
        byte[] last = new byte[0];
        while (System.currentTimeMillis() < tEnd) {
            byte[] rsp = sendRecv(new byte[]{ (byte)MSG_CHECK_REQUEST }, 2000);
            byte[] p = payload(rsp);
            if (p != null && p.length > 0) last = p;

            if (p == null || p.length == 0) {
                sleepMs((int)(pollSec*1000));
                continue;
            }
            int rc = p[0] & 0xFF;
            if (rc == RC_REQUEST_ABORTED) throw new IOException("Queued aborted");
            if (rc == RC_REQUEST_QUEUED || rc == RC_NO_REQUEST_ACTIVE) {
                sleepMs((int)(pollSec*1000));
                continue;
            }
            if (rc == RC_OK && p.length >= 3 && (p[1] & 0xFF) == RC_OK) {
                return Arrays.copyOfRange(p, 1, p.length);
            }
            return p;
        }
        throw new IOException("Queued timeout, last=" + hex(last));
    }

    private static void sleepMs(int ms){
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    /* ================================================================
       OPÉRATIONS HAUT NIVEAU (parité avec Python)
       ================================================================ */

    // GET_FIELD (#num)
    public byte[] opGetField(int fieldNum) throws IOException {
        byte[] rsp = sendRecv(new byte[]{ (byte)MSG_GET_FIELD, (byte)(fieldNum & 0xFF) }, 2000);
        int st = status(rsp); byte[] p = payload(rsp);
        if ( ((st & 0x04) != 0) || (p != null && p.length>0 && (p[0]&0xFF)==RC_REQUEST_QUEUED) ) {
            p = waitQueued(5.0, 0.2);
        }
        if (p == null || p.length < 1) throw new IOException("Empty GET field #"+fieldNum);
        if ((p[0] & 0xFF) != RC_OK) throw new IOException(String.format("GET field #%d rc=0x%02X", fieldNum, p[0]));
        return Arrays.copyOfRange(p, 2, p.length); // p[2:]
    }

    // SET_FIELD (#num, data)
    public void opSetField(int fieldNum, byte[] data) throws IOException {
        byte[] pl = new byte[2 + (data==null?0:data.length)];
        pl[0] = (byte)MSG_SET_FIELD; 
        pl[1] = (byte)(fieldNum & 0xFF);
        if (data != null && data.length > 0) {
            System.arraycopy(data, 0, pl, 2, data.length);
        }
        byte[] rsp = sendRecv(pl, 2000);
        int st = status(rsp); byte[] p = payload(rsp);
        if ( ((st & 0x04) != 0) || (p != null && p.length>0 && (p[0]&0xFF)==RC_REQUEST_QUEUED) ) {
            p = waitQueued(5.0, 0.2);
        }
        if (p == null || p.length < 1 || (p[0] & 0xFF) != RC_OK) {
            throw new IOException(String.format("SET field #%d rc=%s", fieldNum, p==null?"null":String.format("0x%02X", p[0])));
        }
    }

    // ISSUE_COMMAND (RUN/END/etc.)
    public byte[] opIssueCommand(int cmd) throws IOException {
        byte[] rsp = sendRecv(new byte[]{ (byte)MSG_ISSUE_COMMAND, (byte)(cmd & 0xFF) }, 2000);
        int st = status(rsp); byte[] p = payload(rsp);
        if ( ((st & 0x04) != 0) || (p != null && p.length>0 && (p[0]&0xFF)==RC_REQUEST_QUEUED) ) {
            p = waitQueued(5.0, 0.2);
        }
        if (p == null || p.length < 1 || (p[0] & 0xFF) != RC_OK) {
            throw new IOException(String.format("Issue cmd=0x%02X rc=%s", cmd, p==null?"null":String.format("0x%02X", p[0])));
        }
        return p; // [rcOK, ...]
    }

    // GET_DEL_STATUS (0x28) -> (ds, dc)
    public int[] opDeliveryStatus() throws IOException {
        byte[] rsp = sendRecv(new byte[]{ (byte)MSG_GET_DEL_STATUS }, 2000);
        int st = status(rsp); byte[] p = payload(rsp);
        if ( ((st & 0x04) != 0) || (p != null && p.length>0 && (p[0]&0xFF)==RC_REQUEST_QUEUED) ) {
            p = waitQueued(5.0, 0.2);
        }
        if (p == null || p.length < 6 || (p[0] & 0xFF) != RC_OK) {
            throw new IOException("Delivery status invalid");
        }
        int ds = u16be(p, 2);
        int dc = u16be(p, 4);
        return new int[]{ ds, dc };
    }

    // GET_MACHINE (0x23) -> (dev, ds, dc), fallback 0x28 si payload court
    public int[] opMachineStatusFull() throws IOException {
        byte[] rsp = sendRecv(new byte[]{ (byte)MSG_GET_MACHINE }, 2000);
        int st = status(rsp); byte[] p = payload(rsp);
        if ( ((st & 0x04) != 0) || (p != null && p.length>0 && (p[0]&0xFF)==RC_REQUEST_QUEUED) ) {
            p = waitQueued(5.0, 0.2);
        }

        if (p == null || (p.length >= 1 && (p[0] & 0xFF) != RC_OK) || p.length < 8) {
            int[] d = opDeliveryStatus();
            return new int[]{ 0x0000, d[0], d[1] };
        }
        int dev = u16be(p, 2);
        int ds  = u16be(p, 4);
        int dc  = u16be(p, 6);
        return new int[]{ dev, ds, dc };
    }

    /* ================================================================
       START + ATTENTE FLOW (état WAIT_FOR_FLOW reproduit depuis Python)
       - RUN 0x00 (ou 0x01)
       - poll 200–300 ms
       - front montant FLOW (anti-rebond 2 confirmations)
       - filet : variation de GrossCount0 (#44)
       ================================================================ */
    public boolean startDeliveryAndWaitFlow(boolean useCmd00,
                                            long timeoutMs,
                                            long pollMs,
                                            boolean acceptFlow,
                                            boolean acceptCounts) throws IOException {
        // 1) RUN
        if (useCmd00) opIssueCommand(0x00); else opIssueCommand(0x01);

        // 2) Référence compteur (#44) optionnelle
        int g0 = 0;
        try { g0 = i32be(opGetField(44), 0); } catch (Exception ignored){}

        long tEnd = System.currentTimeMillis() + timeoutMs;
        boolean prevFlow = false;
        int flowTrueConsec = 0; // anti-rebond minimal (2 confirmations)

        while (System.currentTimeMillis() < tEnd) {
            int[] ms = opMachineStatusFull(); // (dev, ds, dc)
            int dc = ms[2];
            boolean flow   = (dc & LCRSc_FLOW_ACTIVE) != 0;
            boolean active = (dc & LCRSc_DELIVERY_ACTIVE) != 0;
            boolean begin  = (dc & LCRSc_BEGIN_DELIVERY) != 0;

            log(String.format("[POLL] delCode=0x%04X flow=%s active=%s", dc, flow, active));

            // Conditions de sortie
            if (active || begin) return true;

            // Front montant FLOW avec anti-rebond
            if (acceptFlow) {
                if (flow) { flowTrueConsec++; } else { flowTrueConsec = 0; }
                if (!prevFlow && flowTrueConsec >= 2) return true; // 2 ticks consécutifs
                prevFlow = flow;
            }

            // Filet de sécurité: variation des compteurs => débit
            if (acceptCounts) {
                try {
                    int g = i32be(opGetField(44), 0);
                    if (g > g0) return true;
                } catch (Exception ignored){}
            }

            sleepMs((int)pollMs);
        }
        throw new IOException("START_TIMEOUT: FLOW non détecté dans le délai");
    }

    /* ================================================================
       LIVE minimal : un échantillon lecture états + compteurs (#44/#45)
       ================================================================ */
    public static class LiveSample {
        public int ds, dc;
        public double grossL, netL;
        public boolean flow, active;
    }

    public LiveSample readLiveOnce(int decimalsDigits) throws IOException {
        int scale = (int)Math.pow(10, decimalsDigits);
        int[] ms = opMachineStatusFull(); // dev, ds, dc
        int g = 0, n = 0;
        try { g = i32be(opGetField(44), 0); } catch (Exception ignored){}
        try { n = i32be(opGetField(45), 0); } catch (Exception ignored){}
        LiveSample s = new LiveSample();
        s.ds = ms[1]; s.dc = ms[2];
        s.flow   = (s.dc & LCRSc_FLOW_ACTIVE) != 0;
        s.active = (s.dc & LCRSc_DELIVERY_ACTIVE) != 0;
        s.grossL = g / (double)scale;
        s.netL   = n / (double)scale;
        return s;
    }

    /* ================================================================
       UTIL
       ================================================================ */

    private static String hex(byte[] b) {
        if (b == null) return "(null)";
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X ", x));
        return sb.toString().trim();
    }

    /* Petit builder */
    private static class ByteArrayBuilder {
        private byte[] buf;
        private int len;

        ByteArrayBuilder() {
            this(64);
        }

        ByteArrayBuilder(int cap) {
            buf = new byte[cap];
            len = 0;
        }

        void add(byte b) {
            ensure(1);
            buf[len++] = b;
        }

        void add(byte[] bb) {
            if (bb == null) return;
            ensure(bb.length);
            System.arraycopy(bb, 0, buf, len, bb.length);
            len += bb.length;
        }

        byte[] toByteArray() {
            return Arrays.copyOf(buf, len);
        }

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
