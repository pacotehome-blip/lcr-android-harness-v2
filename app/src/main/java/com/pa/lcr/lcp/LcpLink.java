
package com.pa.lcr.lcp;

import com.hoho.android.usbserial.driver.UsbSerialPort;
import java.io.ByteArrayOutputStream;

public class LcpLink {

    public static boolean DUMP_TX = false, DUMP_RX = false;

    public interface Logger { void log(String s); }
    private static volatile Logger LOGGER;

    public static void setLogger(Logger l) { LOGGER = l; }

    private static void log(String s){
        Logger l = LOGGER;
        if (l != null) l.log(s);
    }

    private static String hex(byte[] b){
        if (b == null) return "(null)";
        StringBuilder sb = new StringBuilder(b.length * 3);
        for (byte x : b) sb.append(String.format("%02X ", x));
        return sb.toString().trim();
    }

    private static class R {
        byte[] raw;
        boolean ok;
    }

    private final UsbSerialPort port;
    private final int to, from;
    private final boolean syncFirst;
    private int msgId = 0;
    private boolean syncUsed = false;

    public LcpLink(UsbSerialPort port, int toAddr, int fromAddr, boolean syncFirst) {
        this.port = port;
        this.to = toAddr & 0xFF;
        this.from = fromAddr & 0xFF;
        this.syncFirst = syncFirst;
    }

    /* ================================================================
       STATUS BIT MANAGEMENT
       ================================================================ */
    private int nextStatus() {
        int st = msgId & 0x01;
        if (syncFirst && !syncUsed) {
            st |= 0x02;   // <-- correct (OR), PAS st=0x02
            syncUsed = true;
        }
        msgId ^= 0x01;
        return st & 0xFF;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

   [] buildFrame(byte[] payload) {
        int st = nextStatus();

        byte[] header = new byte[]{
                (byte) to,
                (byte) from,
                (byte) st,
                (byte) (payload.length & 0xFF)
        };

        byte[] var = concat(header, payload);

        // ESCAPE(header+payload)
        byte[] varEsc = CrcLcp.escape(var);

        // CRC sur ESCAPÉ
        (crc & 0xFF);
        byte hi = (byte) ((crc >>> 8) & 0xFF);

        byte[] crcEsc = CrcLcp.escape(new byte[]{ lo, hi });

        // frame = ~~ + varEsc + crcEsc
        return concat(new byte[]{
                (byte) CrcLcp.TILDE,
                (byte) CrcLcp.TILDE
        }, concat(varEsc, crcEsc));
    }

    /* ================================================================
       SEND / RECEIVE
       ================================================================ */
    public byte[] sendRecv(byte[] payload, int timeoutMs) throws Exception {
        byte[] frm = buildFrame(payload);

        if (DUMP_TX) log("TX: " + hex(frm));

        synchronized (port) {
            port.purgeHwBuffers(true, true);
            port.write(frm, timeoutMs);
        }

        byte[] rx = readFrame(timeoutMs);

        if (DUMP_RX) log("RX: " + hex(rx));

        return rx;
    }

    /* ================================================================
       READ FRAME 100% ESC‑AWARE
       ================================================================ */
    public byte[] readFrame(int timeoutMs) throws Exception {

        long t0 = System.currentTimeMillis();
        int sync = 0;
        byte[] one = new byte[1];

        // CHERCHER ~~
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            int n = port.read(one, 50);
            if (n <= 0) continue;

            int v = one[0] & 0xFF;

            if (v == CrcLcp.TILDE) {
                if (++sync == 2) break;
            } else sync = 0;
        }

        if (sync < 2)
            throw new java.util.concurrent.TimeoutException("Sync ~~ timeout");

        // Reader qui gère ESC 0x1B + next byte
        java.util.function.Supplier<R> r1 = () -> {
            try {
                byte[] b = new byte[1];
                int n = port.read(b, 100);
                if (n <= 0) return r(null, false);

                int v = b[0] & 0xFF;
                if (v == CrcLcp.ESC) {
                    byte[] y = new byte[1];
                    int m = port.read(y, 100);
                    if (m <= 0) return r(null, false);
                    return r(new byte[]{ (byte) CrcLcp.ESC, y[0] }, true);
                }

                return r(new byte[]{ b[0] }, true);

            } catch (Exception e) {
                return r(null, false);
            }
        };

        ByteArrayOutputStream rawHdr = new ByteArrayOutputStream();
        byte[] hdr = new byte[4];
        int hpos = 0;

        while (hpos < 4 && System.currentTimeMillis() - t0 < timeoutMs) {
            R rr = r1.get();
            if (!rr.ok) continue;
            hdr[hpos++] = rr.raw[rr.raw.length - 1];
            rawHdr.write(rr.raw, 0, rr.raw.length);
        }

        if (hpos < 4)
            throw new java.util.concurrent.TimeoutException("Header timeout");

        int plen = hdr[3] & 0xFF;

        ByteArrayOutputStream rawData = new ByteArrayOutputStream();
        byte[] data = new byte[plen];
        int dpos = 0;

        while (dpos < plen && System.currentTimeMillis() - t0 < timeoutMs) {
            R rr = r1.get();
            if (!rr.ok) continue;
            data[dpos++] = rr.raw[rr.raw.length - 1];
            rawData.write(rr.raw, 0, rr.raw.length);
        }

        if (dpos < plen)
            throw new java.util.concurrent.TimeoutException("Payload timeout");

        byte[] crcB = new byte[2];
        int cpos = 0;

        while (cpos < 2 && System.currentTimeMillis() - t0 < timeoutMs) {
            R rr = r1.get();
            if (!rr.ok) continue;
            crcB[cpos++] = rr.raw[rr.raw.length - 1];
        }

        if (cpos < 2)
            throw new java.util.concurrent.TimeoutException("CRC timeout");

        int calc = CrcLcp.crcLcp(concat(rawHdr.toByteArray(), rawData.toByteArray()));
        int recv = (crcB[0] & 0xFF) | ((crcB[1] & 0xFF) << 8);

        if (calc != recv)
            throw new IllegalStateException("CRC mismatch");

        return concat(
                new byte[]{ (byte) CrcLcp.TILDE, (byte) CrcLcp.TILDE },
                concat(hdr, concat(data, crcB))
        );
    }

    private static R r(byte[] raw, boolean ok) {
        R o = new R();
        o.raw = raw;
        o.ok = ok;
        return o;
    }

    public static int extractStatus(byte[] frame) {
        return frame[4] & 0xFF;
    }

    public static byte[] extractPayload(byte[] frame) {
        int ln = frame[5] & 0xFF;
        byte[] p = new byte[ln];
        System.arraycopy(frame, 6, p, 0, ln);
        return p;
    }
}
