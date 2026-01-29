
package com.pa.lcr.lcp;

import com.hoho.android.usbserial.driver.UsbSerialPort;
import java.io.IOException;
import java.util.Arrays;

public class LcpLink {

    /* ================================================================
       CONSTANTES PROTOCOLE (spécification LCP officielle)
       ================================================================ */

    public static final int TILDE = 0x7E;
    public static final int ESC   = 0x1B;

    private static final int SEED = 0x7E7E;   // CRC initial seed (doc LC)
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

    // État du status byte (bit ID toggle)
    private int msgId = 0;
    private boolean syncUsed = false;

    public LcpLink(UsbSerialPort port, int to, int from, boolean syncFirst) {
        this.port = port;
        this.toAddr = to & 0xFF;
        this.fromAddr = from & 0xFF;
        this.syncFirst = syncFirst;
    }

    /* ================================================================
       CRC LCP — identique Python + documentation LC
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
       Escape / Unescape (identique Python + spec LC)
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
       Construction d’un frame (identique Python build_frame)
       ================================================================ */

    private byte[] buildFrame(byte[] payload) {
        int status = msgId & 0x01;
        if (syncFirst && !syncUsed) {
            status |= 0x02;     // bit SYNC
            syncUsed = true;
        }
        msgId ^= 0x01;         // toggle ID

        byte[] header = {
            (byte) toAddr,
            (byte) fromAddr,
            (byte) status,
            (byte) payload.length
        };

        byte[] var = ByteArrayBuilder.concat(header, payload);
        byte[] varEsc = escapeStream(var);

        int crcV = crcLCP(varEsc);
        byte crc0 = (byte) (crcV & 0xFF);
        byte crc1 = (byte) ((crcV >> 8) & 0xFF);
        byte[] crcRaw = { crc0, crc1 };
        byte[] crcEsc = escapeStream(crcRaw);

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
       Lecture d’un frame (identique Python read_frame)
       ================================================================ */

    public byte[] readFrame(int timeoutMs) throws IOException {
        long tEnd = System.currentTimeMillis() + timeoutMs;

        // 1) Sync ~~ 
        int syncCount = 0;
        while (System.currentTimeMillis() < tEnd) {
            int b = readByte(timeoutMs);
            if (b < 0) continue;
            if (b == TILDE) {
                syncCount++;
                if (syncCount == 2) break;
            } else {
                syncCount = 0;
            }
        }
        if (syncCount < 2) throw new IOException("Sync ~~ timeout");

        // 2) read header (4 unescaped bytes)
        byte[] rawHdr = new byte[0];
        int[] hdr = new int[4];
        for (int i = 0; i < 4; i++) {
            int[] vRaw = readEscapedByte(timeoutMs);
            if (vRaw[0] < 0) throw new IOException("Header timeout");
            hdr[i] = vRaw[0];
            rawHdr = ByteArrayBuilder.concat(rawHdr, vRaw[1]);
        }

        int plen = hdr[3];
        byte[] rawData = new byte[0];
        byte[] data = new byte[plen];
        for (int i = 0; i < plen; i++) {
            int[] vRaw = readEscapedByte(timeoutMs);
            if (vRaw[0] < 0) throw new IOException("Payload timeout");
            data[i] = (byte) vRaw[0];
            rawData = ByteArrayBuilder.concat(rawData, vRaw[1]);
        }

        // 3) read CRC (2 bytes, ESC-processed)
        byte[] crcRaw = new byte[0];
        int[] c0 = readEscapedByte(timeoutMs);
        int[] c1 = readEscapedByte(timeoutMs);
        if (c0[0] < 0 || c1[0] < 0) throw new IOException("CRC timeout");

        crcRaw = ByteArrayBuilder.concat(crcRaw, c0[1]);
        crcRaw = ByteArrayBuilder.concat(crcRaw, c1[1]);

        int crcR = (c0[0] & 0xFF) | ((c1[0] & 0xFF) << 8);

        // 4) validate CRC
        byte[] fullEsc = ByteArrayBuilder.concat(rawHdr, rawData);
        int crcCalc = crcLCP(fullEsc);
        if (crcCalc != crcR) {
            throw new IOException(String.format("CRC mismatch recv=%04X calc=%04X", crcR, crcCalc));
        }

        ByteArrayBuilder frame = new ByteArrayBuilder(2 + rawHdr.length + rawData.length + 2);
        frame.add((byte) TILDE);
        frame.add((byte) TILDE);
        frame.add(rawHdr);
        frame.add(rawData);
        frame.add((byte) c0[0]);
        frame.add((byte) c1[0]);

        byte[] rsp = frame.toByteArray();
        if (DUMP_RX) log("RX: " + hex(rsp));
        return rsp;
    }

    /* ================================================================
       readEscapedByte : lecture 1 byte en tenant compte ESC
       ================================================================ */

    private int[] readEscapedByte(int timeout) throws IOException {
        int b = readByte(timeout);
        if (b < 0) return new int[]{ -1, nullBytes() };

        if (b == ESC) {
            int y = readByte(timeout);
            if (y < 0) return new int[]{ -1, new byte[]{ (byte) ESC } };
            return new int[]{ y, new byte[]{ (byte) ESC, (byte) y } };
        } else {
            return new int[]{ b, new byte[]{ (byte) b } };
        }
    }

    /* ================================================================
       Lecture 1 byte avec timeout (UsbSerialPort)
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
       sendRecv — identique Python
       ================================================================ */

    public byte[] sendRecv(byte[] payload, int timeoutMs) throws IOException {
        byte[] frame = buildFrame(payload);

        // Purge input
        try { port.purgeHwBuffers(true, true); } catch(Exception ignored){}

        // Write
        port.write(frame, timeoutMs);

        return readFrame(timeoutMs);
    }

    /* ================================================================
       extractStatus / extractPayload — identique Python
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

    private static byte[] nullBytes() { return new byte[0]; }

    private static String hex(byte[] b) {
        if (b == null) return "(null)";
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X ", x));
        return sb.toString().trim();
    }

    /* Builder simple */
    private static class ByteArrayBuilder {
        private byte[] buf; private int len=0;

        ByteArrayBuilder(int cap) { buf = new byte[cap]; }
        ByteArrayBuilder()       { this(64); }

        void add(byte x) {
            ensure(1);
            buf[len++] = x;
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
                int newCap = Math.max(buf.length*2, len+n);
                buf = Arrays.copyOf(buf, newCap);
            }
        }
        static byte[] concat(byte[] a, byte[] b) {
            if (a == null) return b;
            if (b == null) return a;
            byte[] out = Arrays.copyOf(a, a.length + b.length);
            System.arraycopy(b, 0, out, a.length, b.length);
            return out;
        }
    }

    /* Logger */
    public interface Logger { void log(String s); }
}
