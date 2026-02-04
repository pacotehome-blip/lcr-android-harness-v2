
package com.pa.lcr.lcp;

import com.hoho.android.usbserial.driver.UsbSerialPort;

import java.io.IOException;
import java.util.Arrays;

/**
 * LcpLink — Version finale conforme LCP02 + Python V2
 * ---------------------------------------------------
 * - Framing ~~ ... CRC (CRC escapé correct)
 * - ESC + UNESC stricts
 * - CRC XMODEM seed 0x7E7E
 * - Status byte : toggle, sync, busy
 * - Toggle management correct
 * - SYNC envoyé une fois (comme Python)
 * - sendRecv robuste (busy → queue → 0x7D)
 * - readFrame renvoie la frame EXACTE (RAW ESCAPÉE)
 * - Pas de {0x00} illégal : 0x00 = GET_PRODUCT_ID conforme
 */
public class LcpLink {

    /* CONSTANTES PROTOCOLE */
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

    public static final int LCRSc_DEL_TICKET_PENDING = 0x0001;
    public static final int LCRSc_FLOW_ACTIVE        = 0x0004;
    public static final int LCRSc_DELIVERY_ACTIVE    = 0x0008;
    public static final int LCRSc_BEGIN_DELIVERY     = 0x0400;

    public static boolean DUMP_TX = false;
    public static boolean DUMP_RX = false;

    private static Logger logger = null;
    public static void setLogger(Logger l){ logger = l; }
    private static void log(String s){ if (logger != null) logger.log(s); }

    /* ATTRIBUTS */
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

    /* ================================================================
       CRC
       ================================================================ */

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

    /* ================================================================
       ESCAPING
       ================================================================ */

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

    /* ================================================================
       CONSTRUCTION FRAME TX (RAW ESCAPED)
       ================================================================ */

    private byte[] buildFrame(byte[] payload){

        int status = toggle & 1;
        if (syncPending){
            status |= 0x02;
            syncPending = false;
        }
        toggle ^= 1;

        byte[] header = new byte[]{
                (byte)toAddr,
                (byte)fromAddr,
                (byte)status,
                (byte)(payload.length)
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

    /* ================================================================
       LECTURE — RAW RX ESCAPÉ, EXACTEMENT comme reçu
       ================================================================ */

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

    private RawByte readEsc(int timeout){
        int b = readByte(timeout);
        if (b < 0) return new RawByte(-1, new byte[0]);

        if (b == ESC){
            int y = readByte(timeout);
            if (y < 0) return new RawByte(-1, new byte[]{(byte)ESC});
            return new RawByte(y, new byte[]{(byte)ESC,(byte)y});
        }
        return new RawByte(b, new byte[]{(byte)b});
    }

    private static class RawByte{
        final int decoded;
        final byte[] raw;
        RawByte(int d, byte[] r){ decoded=d; raw=r; }
    }

    public byte[] readFrame(int timeout) throws IOException {

        long tEnd = System.currentTimeMillis() + timeout;

        /* sync ~~ */
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

        byte[] hdrRaw = new byte[0];
        int[] hdr = new int[4];

        for (int i=0; i<4; i++){
            RawByte rb = readEsc(timeout);
            if (rb.decoded < 0)
                throw new IOException("Header timeout");

            hdr[i] = rb.decoded;
            hdrRaw = ByteArrayBuilder.concat(hdrRaw, rb.raw);
        }

        rawFrame.add(hdrRaw);

        int plen = hdr[3] & 0xFF;

        byte[] payload = new byte[plen];
        byte[] payRaw = new byte[0];

        for (int i=0; i<plen; i++){
            RawByte rb = readEsc(timeout);
            if (rb.decoded < 0)
                throw new IOException("Payload timeout");
            payload[i] = (byte) rb.decoded;
            payRaw = ByteArrayBuilder.concat(payRaw, rb.raw);
        }

        rawFrame.add(payRaw);

        RawByte c0 = readEsc(timeout);
        RawByte c1 = readEsc(timeout);
        if (c0.decoded < 0 || c1.decoded < 0)
            throw new IOException("CRC timeout");

        rawFrame.add(c0.raw);
        rawFrame.add(c1.raw);

        byte[] fullRaw = rawFrame.toByteArray();

        /* CRC check on ESCAPED header+payload only (as per protocol) */
        int crcRx = (c0.decoded & 0xFF) | ((c1.decoded & 0xFF)<<8);
        int crcCalc = crcLCP(ByteArrayBuilder.concat(hdrRaw, payRaw));

        if (crcCalc != crcRx)
            throw new IOException(String.format("CRC mismatch recv=%04X calc=%04X", crcRx, crcCalc));

        if (DUMP_RX) log("RX: " + hex(fullRaw));
        return fullRaw;
    }

    /* ================================================================
       sendRecv (BUSY → queue 0x7D)
       ================================================================ */

    public byte[] sendRecv(byte[] payload, int timeout) throws IOException {

        byte[] fr = buildFrame(payload);
        try { port.purgeHwBuffers(true,true); } catch(Exception ignored){}
        port.write(fr, timeout);

        byte[] rsp = readFrame(timeout);
        LcpStatus st = extractStatus(rsp);

        if (st.busy)
            return waitQueued(4000, 150);

        return rsp;
    }

    /* ================================================================
       STATUS + PAYLOAD
       ================================================================ */

    public static class LcpStatus {
        public boolean toggle;
        public boolean sync;
        public boolean busy;

        public static LcpStatus fromByte(int b){
            LcpStatus s = new LcpStatus();
            s.toggle = (b & 0x01) != 0;
            s.sync   = (b & 0x02) != 0;
            s.busy   = (b & 0x04) != 0;
            return s;
        }
    }

    public static LcpStatus extractStatus(byte[] frame){
        /* frame = [~~] <headerRaw> ...
           headerRaw[2] = status
        */
        int statusIndex = 2;
        return LcpStatus.fromByte(frame[statusIndex] & 0xFF);
    }

    public static byte[] extractPayload(byte[] frame){
        // frame: ~~ hdrRaw(POSSIBLY multiple bytes) payRaw ...
        // decoding NOT possible raw → so decode logically:
        // method used: reparse header decoded
        int idx = 2;  // after ~~: frame[2] = first hdr raw byte
        int h0 = frame[2] & 0xFF;   // to
        int h1 = frame[3] & 0xFF;   // from
        int status = frame[4] & 0xFF;
        int plen = frame[5] & 0xFF;

        int payStart = 6;
        if (frame.length < payStart + plen)
            return null;

        return Arrays.copyOfRange(frame, payStart, payStart + plen);
    }

    private static int u16be(byte[] b, int off){
        return ((b[off] & 0xFF)<<8) | (b[off+1] & 0xFF);
    }

    /* ================================================================
       WAIT_QUEUED STRICT — identical to Python
       ================================================================ */

    private byte[] waitQueued(int timeoutMs, int pollMs) throws IOException {

        long tEnd = System.currentTimeMillis() + timeoutMs;
        byte[] last = null;

        while (System.currentTimeMillis() < tEnd){

            byte[] rsp = readFrame(2000);
            LcpStatus st = extractStatus(rsp);
            byte[] p = extractPayload(rsp);

            if (p != null && p.length > 0)
                last = p;

            if (st.busy){
                sleep(pollMs);
                continue;
            }

            if (p == null || p.length == 0){
                sleep(pollMs);
                continue;
            }

            int rc = p[0] & 0xFF;

            if (rc == RC_REQUEST_ABORTED)
                throw new IOException("Queue aborted");

            if (rc == RC_REQUEST_QUEUED ||
                rc == RC_NO_REQUEST_ACTIVE){
                sleep(pollMs);
                continue;
            }

            return p;
        }

        throw new IOException("Queued timeout last=" + hex(last));
    }

    /* ================================================================
       OPÉRATIONS : GetField / SetField / IssueCommand / GetMachine / GetDelivery
       ================================================================ */

    public byte[] opGetField(int field) throws IOException {

        byte[] pl = new byte[]{
                (byte)MSG_GET_FIELD,
                (byte)field
        };

        byte[] rsp = sendRecv(pl,2000);
        byte[] p = extractPayload(rsp);

        /* busy/queued */
        if (p != null && p.length > 0 &&
                (p[0]==RC_REQUEST_QUEUED || p[0]==RC_NO_REQUEST_ACTIVE)){
            p = waitQueued(4000,150);
        }

        if (p == null || p.length < 2 || p[0] != RC_OK)
            throw new IOException("GET_FIELD #" + field);

        return Arrays.copyOfRange(p,2,p.length);
    }

    public void opSetField(int field, byte[] data) throws IOException {

        byte[] pl = new byte[2 + data.length];
        pl[0] = (byte)MSG_SET_FIELD;
        pl[1] = (byte)field;
        System.arraycopy(data,0,pl,2,data.length);

        byte[] rsp = sendRecv(pl,2000);
        byte[] p = extractPayload(rsp);

        if (p != null && p.length > 0 &&
                (p[0]==RC_REQUEST_QUEUED || p[0]==RC_NO_REQUEST_ACTIVE)){
            p = waitQueued(4000,150);
        }

        if (p == null || p.length < 1 || p[0] != RC_OK)
            throw new IOException("SET_FIELD #" + field);
    }

    public byte[] opIssueCommand(int cmd) throws IOException {

        byte[] pl = new byte[]{
                (byte)MSG_ISSUE_COMMAND,
                (byte)cmd
        };

        byte[] rsp = sendRecv(pl,2000);
        byte[] p = extractPayload(rsp);

        if (p != null && p.length > 0 &&
                (p[0]==RC_REQUEST_QUEUED || p[0]==RC_NO_REQUEST_ACTIVE)){
            p = waitQueued(4000,150);
        }

        if (p == null || p.length < 1 || p[0] != RC_OK)
            throw new IOException(String.format("CMD 0x%02X",cmd));

        return p;
    }

    public int[] opDeliveryStatus() throws IOException {

        byte[] rsp = sendRecv(new byte[]{(byte)MSG_GET_DEL_STATUS}, 2000);
        byte[] p = extractPayload(rsp);

        if (p != null && p.length > 0 &&
                (p[0]==RC_REQUEST_QUEUED || p[0]==RC_NO_REQUEST_ACTIVE)){
            p = waitQueued(4000,150);
        }

        if (p == null || p.length < 6 || p[0] != RC_OK)
            throw new IOException("Invalid 0x28");

        int ds = u16be(p,2);
        int dc = u16be(p,4);
        return new int[]{ds,dc};
    }

    public int[] opMachineStatusFull() throws IOException {

        byte[] rsp = sendRecv(new byte[]{(byte)MSG_GET_MACHINE}, 2000);
        byte[] p = extractPayload(rsp);

        if (p != null && p.length > 0 &&
                (p[0]==RC_REQUEST_QUEUED || p[0]==RC_NO_REQUEST_ACTIVE)){
            p = waitQueued(4000,150);
        }

        if (p == null || p.length < 8 || p[0] != RC_OK){
            int[] d = opDeliveryStatus();
            return new int[]{0, d[0], d[1]};
        }

        int dev = u16be(p,2);
        int ds  = u16be(p,4);
        int dc  = u16be(p,6);

        return new int[]{dev,ds,dc};
    }

    /* ================================================================
       UTILS
       ================================================================ */

    private static void sleep(int ms){
        try { Thread.sleep(ms); } catch(Exception ignored){}
    }

    private static String hex(byte[] data){
        if (data == null) return "(null)";
        StringBuilder sb = new StringBuilder();
        for (byte b : data)
            sb.append(String.format("%02X ", b));
        return sb.toString().trim();
    }

    private static class ByteArrayBuilder{
        private byte[] buf;
        private int len;
        ByteArrayBuilder(){ this(64); }
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

        byte[] toByteArray(){
            return Arrays.copyOf(buf,len);
        }

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

    public interface Logger { void log(String s); }
}
