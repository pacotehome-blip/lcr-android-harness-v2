
package com.pa.lcr.lcp;

import com.hoho.android.usbserial.driver.UsbSerialPort;
import java.io.IOException;
import java.util.Arrays;

/**
 * LcpLink — Version finale A2‑Enhanced (2026‑02‑04)
 * --------------------------------------------------
 * - API inchangée (readFrame → byte[], sendRecv → byte[])
 * - Parsing interne structuré via ParsedFrame (header/payload décodés)
 * - Stockage ThreadLocal pour extractPayload/extractStatus fiables
 * - Respect de CRC16/XMODEM (polynôme 0x1021) [1](https://mdfs.net/Info/Comp/Comms/CRC16.htm)
 * - Compatible protocole LCP02 (framing ~~ + ESC) [2](https://onlinedocs.microchip.com/oxy/GUID-B822915F-C375-4172-91BD-AB6F326EB783-en-US-1/GUID-A416A65D-1892-4807-8431-3C8F2EFBBEC1.html)
 * - Conformité totale Python V2
 */
public class LcpLink {

    /* =========================================================================
       CONSTANTES PROTOCOLE
       ========================================================================= */
    public static final int TILDE = 0x7E;
    public static final int ESC   = 0x1B;

    private static final int SEED = 0x7E7E;
    private static final int POLY = 0x1021;

    public static final int MSG_GET_FIELD     = 0x20;
    public static final int MSG_SET_FIELD     = 0x21;
    public static final int MSG_PRINT_TEXT    = 0x22;
    public static final int MSG_GET_MACHINE   = 0x23;
    public static final int MSG_ISSUE_COMMAND = 0x24;
    public static final int MSG_GET_DEL_STATUS= 0x28;
    public static final int MSG_CHECK_REQUEST = 0x7D;
    public static final int MSG_GET_PRODUCTID = 0x00;

    public static final int RC_OK                = 0x00;
    public static final int RC_REQUEST_QUEUED    = 0x26;
    public static final int RC_NO_REQUEST_ACTIVE = 0x27;
    public static final int RC_REQUEST_ABORTED   = 0x28;

    public static boolean DUMP_TX = false;
    public static boolean DUMP_RX = false;

    /* =========================================================================
       LOGGER
       ========================================================================= */
    public interface Logger { void log(String s); }
    private static Logger logger = null;
    public static void setLogger(Logger l){ logger = l; }
    private static void log(String s){ if (logger != null) logger.log(s); }

    /* =========================================================================
       STRUCTURE ParsedFrame (A2‑Enhanced)
       ========================================================================= */
    private static final class ParsedFrame {
        byte[] rawFrame;
        byte[] headerRaw;
        byte[] payloadRaw;
        byte[] header;
        byte[] payload;
        int crcRx;
        int crcCalc;
        boolean crcOK;
    }

    /** ThreadLocal contenant le dernier frame reçu */
    private static final ThreadLocal<ParsedFrame> lastFrame = new ThreadLocal<>();

    /* =========================================================================
       ATTRIBUTS INSTANCE
       ========================================================================= */
    private final UsbSerialPort port;
    private final int toAddr;
    private final int fromAddr;

    private boolean syncPending;
    private int toggle;

    public LcpLink(UsbSerialPort p, int to, int from, boolean syncFirst) {
        port = p;
        toAddr = to & 0xFF;
        fromAddr = from & 0xFF;
        syncPending = syncFirst;
        toggle = 0;
    }

    /* =========================================================================
       CRC  XMODEM (polynôme 0x1021) [1](https://mdfs.net/Info/Comp/Comms/CRC16.htm)
       ========================================================================= */
    private int crcUpdate(int crc, int b){
        for (int i=0; i<8; i++){
            boolean fb = (crc & 0x8000) != 0;
            crc = ((crc << 1) & 0xFFFF) | ((b >> (7 - i)) & 1);
            if (fb) crc ^= POLY;
        }
        return crc;
    }

    private int crcLCP(byte[] data){
        int c = SEED;
        for (byte x : data)
            c = crcUpdate(c, x & 0xFF);
        return c;
    }

    /* =========================================================================
       ESCAPING
       ========================================================================= */
    private byte[] esc(byte[] in){
        ByteArrayBuilder out = new ByteArrayBuilder(in.length * 2);
        for (byte b : in){
            int x = b & 0xFF;
            if (x == ESC || x == TILDE)
                out.add((byte)ESC);
            out.add(b);
        }
        return out.toByteArray();
    }

    /* =========================================================================
       LECTURE BRUTE
       ========================================================================= */
    private int readByte(int timeout){
        try {
            byte[] b = new byte[1];
            int n = port.read(b, timeout);
            if (n <= 0) return -1;
            return b[0] & 0xFF;
        } catch(Exception e){
            return -1;
        }
    }

    private RawByte readEscaped(int timeout){
        int b = readByte(timeout);
        if (b < 0) return new RawByte(-1, new byte[0]);

        if (b == ESC){
            int y = readByte(timeout);
            if (y < 0) return new RawByte(-1, new byte[]{(byte)ESC});
            return new RawByte(y, new byte[]{(byte)ESC,(byte)y});
        }
        return new RawByte(b, new byte[]{(byte)b});
    }

    private static final class RawByte {
        final int decoded;
        final byte[] raw;
        RawByte(int d, byte[] r){ decoded=d; raw=r; }
    }

    /* =========================================================================
       ByteArrayBuilder
       ========================================================================= */
    private static final class ByteArrayBuilder {
        private byte[] buf;
        private int len;

        ByteArrayBuilder(){ this(128); }
        ByteArrayBuilder(int cap){ buf=new byte[cap]; len=0; }

        void add(byte b){
            ensure(1);
            buf[len++] = b;
        }

        void add(byte[] bb){
            ensure(bb.length);
            System.arraycopy(bb,0,buf,len,bb.length);
            len += bb.length;
        }

        byte[] toByteArray(){ return Arrays.copyOf(buf,len); }

        private void ensure(int n){
            if (len+n > buf.length){
                buf = Arrays.copyOf(buf, Math.max(buf.length*2, len+n));
            }
        }

        static byte[] concat(byte[] a, byte[] b){
            if (a == null || a.length==0) return b;
            if (b == null || b.length==0) return a;
            byte[] out = Arrays.copyOf(a, a.length+b.length);
            System.arraycopy(b,0,out,a.length,b.length);
            return out;
        }
    }

    /* =========================================================================
       CONSTRUCTION FRAME TX
       ========================================================================= */
    private byte[] buildFrame(byte[] payload){

        int status = toggle & 1;

        if (syncPending){
            status |= 0x02;  // SYNC bit one-time
            syncPending = false;
        }

        toggle ^= 1;

        byte[] header = new byte[]{
                (byte)toAddr,
                (byte)fromAddr,
                (byte)status,
                (byte)payload.length
        };

        byte[] core = ByteArrayBuilder.concat(header, payload);
        byte[] coreEsc = esc(core);

        int crc = crcLCP(coreEsc);
        byte[] crcRaw = new byte[]{
                (byte)(crc & 0xFF),
                (byte)((crc >> 8) & 0xFF)
        };
        byte[] crcEsc = esc(crcRaw);

        ByteArrayBuilder frame = new ByteArrayBuilder();
        frame.add((byte)TILDE);
        frame.add((byte)TILDE);
        frame.add(coreEsc);
        frame.add(crcEsc);

        byte[] out = frame.toByteArray();

        if (DUMP_TX) log("TX: " + hex(out));
        return out;
    }

    /* =========================================================================
       readFrame — PARSING STRUCTURÉ
       ========================================================================= */
    public byte[] readFrame(int timeout) throws IOException {

        long tEnd = System.currentTimeMillis() + timeout;

        int syncCount = 0;

        while (System.currentTimeMillis() < tEnd){
            int b = readByte(timeout);
            if (b < 0) continue;

            if (b == TILDE){
                syncCount++;
                if (syncCount == 2) break;
            } else {
                syncCount = 0;
            }
        }

        if (syncCount < 2)
            throw new IOException("Timeout sync ~~");

        ByteArrayBuilder rawFrame = new ByteArrayBuilder();
        rawFrame.add((byte)TILDE);
        rawFrame.add((byte)TILDE);

        ParsedFrame pf = new ParsedFrame();
        pf.headerRaw = new byte[0];
        pf.payloadRaw = new byte[0];
        pf.header = new byte[4];

        for (int i=0; i<4; i++){
            RawByte rb = readEscaped(timeout);
            if (rb.decoded < 0)
                throw new IOException("Header timeout");

            pf.header[i] = (byte)rb.decoded;
            pf.headerRaw = ByteArrayBuilder.concat(pf.headerRaw, rb.raw);
        }

        rawFrame.add(pf.headerRaw);

        int plen = pf.header[3] & 0xFF;

        pf.payload = new byte[plen];

        for (int i=0; i<plen; i++){
            RawByte rb = readEscaped(timeout);
            if (rb.decoded < 0)
                throw new IOException("Payload timeout");

            pf.payload[i] = (byte)rb.decoded;
            pf.payloadRaw = ByteArrayBuilder.concat(pf.payloadRaw, rb.raw);
        }

        rawFrame.add(pf.payloadRaw);

        RawByte c0 = readEscaped(timeout);
        RawByte c1 = readEscaped(timeout);

        if (c0.decoded < 0 || c1.decoded < 0)
            throw new IOException("CRC timeout");

        rawFrame.add(c0.raw);
        rawFrame.add(c1.raw);

        pf.rawFrame = rawFrame.toByteArray();

        pf.crcRx = (c0.decoded & 0xFF) | ((c1.decoded & 0xFF) << 8);

        byte[] coreRawEsc = ByteArrayBuilder.concat(pf.headerRaw, pf.payloadRaw);
        pf.crcCalc = crcLCP(coreRawEsc);
        pf.crcOK = (pf.crcCalc == pf.crcRx);

        if (!pf.crcOK)
            throw new IOException(String.format(
                    "CRC mismatch recv=%04X calc=%04X",
                    pf.crcRx, pf.crcCalc));

        if (DUMP_RX) log("RX: " + hex(pf.rawFrame));

        lastFrame.set(pf);

        return pf.rawFrame;
    }

    /* =========================================================================
       STATUS EXTRACTION
       ========================================================================= */
    public static final class LcpStatus {
        public boolean toggle;
        public boolean sync;
        public boolean busy;

        public static LcpStatus fromByte(int b){
            LcpStatus s = new LcpStatus();
            s.toggle = (b & 1) != 0;
            s.sync   = (b & 2) != 0;
            s.busy   = (b & 4) != 0;
            return s;
        }
    }

    public static LcpStatus extractStatus(byte[] frameRaw){
        ParsedFrame pf = lastFrame.get();
        if (pf == null || pf.rawFrame != frameRaw){
            throw new IllegalStateException("extractStatus on unknown frame");
        }
        return LcpStatus.fromByte(pf.header[2] & 0xFF);
    }

    /* =========================================================================
       PAYLOAD EXTRACTION (Décodé)
       ========================================================================= */
    public static byte[] extractPayload(byte[] frameRaw){
        ParsedFrame pf = lastFrame.get();
        if(pf == null || pf.rawFrame != frameRaw){
            throw new IllegalStateException("extractPayload on unknown frame");
        }
        return pf.payload;
    }

    /* =========================================================================
       sendRecv — BUSY + 0x7D queue
       ========================================================================= */
    public byte[] sendRecv(byte[] payload, int timeoutMs) throws IOException {

        byte[] frame = buildFrame(payload);

        try { port.purgeHwBuffers(true, true); } catch(Exception ignored){}

        port.write(frame, timeoutMs);

        byte[] rsp = readFrame(timeoutMs);
        LcpStatus st = extractStatus(rsp);

        if (st.busy){
            return waitQueued(5000, 150);
        }

        return rsp;
    }

    /* =========================================================================
       waitQueued
       ========================================================================= */
    private byte[] waitQueued(int timeoutMs, int pollMs) throws IOException {

        long tEnd = System.currentTimeMillis() + timeoutMs;
        byte[] last = null;

        while (System.currentTimeMillis() < tEnd) {

            byte[] rsp = readFrame(Math.max(1200, pollMs + 800));
            byte[] p = extractPayload(rsp);
            LcpStatus st = extractStatus(rsp);

            if (p != null && p.length > 0)
                last = p;

            if (st.busy) {
                sleep(pollMs);
                continue;
            }

            if (p == null || p.length == 0) {
                sleep(pollMs);
                continue;
            }

            int rc = p[0] & 0xFF;

            if (rc == RC_REQUEST_ABORTED)
                throw new IOException("Queue aborted");

            if (rc == RC_REQUEST_QUEUED || rc == RC_NO_REQUEST_ACTIVE) {
                sleep(pollMs);
                continue;
            }

            return p;
        }

        throw new IOException("Queued timeout, last="+hex(last));
    }

    /* =========================================================================
       GET_FIELD
       ========================================================================= */
    public byte[] opGetField(int field) throws IOException {

        byte[] req = new byte[]{ (byte)MSG_GET_FIELD, (byte)field };

        byte[] rsp = sendRecv(req, 2000);
        byte[] p = extractPayload(rsp);

        if (p != null && p.length > 0 &&
           (p[0]==RC_REQUEST_QUEUED || p[0]==RC_NO_REQUEST_ACTIVE)){
            p = waitQueued(5000,150);
        }

        if (p == null || p.length < 2 || p[0] != RC_OK)
            throw new IOException("GET_FIELD #"+field);

        return Arrays.copyOfRange(p, 2, p.length);
    }

    /* =========================================================================
       SET_FIELD
       ========================================================================= */
    public void opSetField(int field, byte[] data) throws IOException {

        byte[] pl = new byte[2 + data.length];
        pl[0] = (byte)MSG_SET_FIELD;
        pl[1] = (byte)field;
        System.arraycopy(data, 0, pl, 2, data.length);

        byte[] rsp = sendRecv(pl,2000);
        byte[] p = extractPayload(rsp);

        if (p != null && p.length > 0 &&
           (p[0]==RC_REQUEST_QUEUED || p[0]==RC_NO_REQUEST_ACTIVE)){
            p = waitQueued(5000,150);
        }

        if (p == null || p.length < 1 || p[0] != RC_OK)
            throw new IOException("SET_FIELD #"+field);
    }

    /* =========================================================================
       ISSUE_COMMAND
       ========================================================================= */
    public byte[] opIssueCommand(int cmd) throws IOException {

        byte[] req = new byte[]{ (byte)MSG_ISSUE_COMMAND, (byte)cmd };

        byte[] rsp = sendRecv(req,2000);
        byte[] p = extractPayload(rsp);

        if (p != null && p.length > 0 &&
           (p[0]==RC_REQUEST_QUEUED || p[0]==RC_NO_REQUEST_ACTIVE)){
            p = waitQueued(5000,150);
        }

        if (p == null || p.length < 1 || p[0] != RC_OK)
            throw new IOException("CMD 0x"+Integer.toHexString(cmd));

        return p;
    }

    /* =========================================================================
       GET_DELIVERY_STATUS
       ========================================================================= */
    public int[] opDeliveryStatus() throws IOException {

        byte[] rsp = sendRecv(new byte[]{ (byte)MSG_GET_DEL_STATUS },2000);
        byte[] p = extractPayload(rsp);

        if (p != null && p.length > 0 &&
           (p[0]==RC_REQUEST_QUEUED || p[0]==RC_NO_REQUEST_ACTIVE)){
            p = waitQueued(5000,150);
        }

        if (p == null || p.length < 6 || p[0] != RC_OK)
            throw new IOException("Invalid 0x28");

        int ds = u16be(p,2);
        int dc = u16be(p,4);
        return new int[]{ ds, dc };
    }

    /* =========================================================================
       GET_MACHINE (0x23)
       ========================================================================= */
    public int[] opMachineStatusFull() throws IOException {

        byte[] rsp = sendRecv(new byte[]{ (byte)MSG_GET_MACHINE },2000);
        byte[] p = extractPayload(rsp);

        if (p != null && p.length > 0 &&
           (p[0]==RC_REQUEST_QUEUED || p[0]==RC_NO_REQUEST_ACTIVE)){
            p = waitQueued(5000,150);
        }

        if (p == null || p.length < 8 || p[0] != RC_OK){
            int[] d = opDeliveryStatus();
            return new int[]{ 0x0000, d[0], d[1] };
        }

        int dev = u16be(p,2);
        int ds  = u16be(p,4);
        int dc  = u16be(p,6);

        return new int[]{ dev, ds, dc };
    }

    /* =========================================================================
       UTILITAIRES
       ========================================================================= */
    private static int u16be(byte[] b, int off){
        return ((b[off] & 0xFF)<<8) | (b[off+1] & 0xFF);
    }

    private static void sleep(int ms){
        try { Thread.sleep(ms); } catch(Exception ignored){}
    }
}
