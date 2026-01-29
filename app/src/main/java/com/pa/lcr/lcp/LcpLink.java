
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
            if ((x & 0xFF) == ESC || (x & 0xFF) == TILDE)
                out.add((byte) ESC);

            out.add(x);
        }
        return out.toByteArray();
    }

    /* ================================================================
       Construction d’un frame (identique Python)
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
       readEscapedByte()
       ================================================================ */

    private RawByte readEscapedByte(int timeout) throws IOException {
        int b = readByte(timeout);
        if (b < 0) return new RawByte(-1, new byte[0]);

        if (b == ESC) {
            int y = readByte(timeout);
            if (y < 0)
                return new RawByte(-1, new byte[]{ (byte) ESC });

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
