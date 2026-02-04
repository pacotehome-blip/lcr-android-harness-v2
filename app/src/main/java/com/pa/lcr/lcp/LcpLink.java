
package com.pa.lcr.lcp;

import com.hoho.android.usbserial.driver.UsbSerialPort;
import java.io.IOException;
import java.util.Arrays;

/**
 * LcpLink — Version finale A2‑Enhanced (2026‑02‑04)
 * --------------------------------------------------
 * - Parsing structuré via ParsedFrame (header+payload décodés)
 * - API inchangée (sendRecv/readFrame → byte[])
 * - ThreadLocal pour extractions sécurisées
 * - CRC XMODEM 0x1021 (conforme LCP) 
 * - Framing LCP ~~ + ESC correct
 */
public class LcpLink {

    /* =========================================================================
       CONSTANTES PROTOCOLE
       ========================================================================= */
    public static final int TILDE = 0x7E;
    public static final int ESC   = 0x1B;

    private static final int SEED = 0x7E7E;
    private static final int POLY = 0x1021;  // CRC16/XMODEM (confirmé) 

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

    /* === FLAGS MACHINE (ds/dc) === */
    public static final int LCRSc_DEL_TICKET_PENDING = 0x0001;
    public static final int LCRSc_FLOW_ACTIVE        = 0x0004;
    public static final int LCRSc_DELIVERY_ACTIVE    = 0x0008;
    public static final int LCRSc_BEGIN_DELIVERY     = 0x0400;

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

    /** Dernier frame lu, parsé (ThreadLocal) */
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
        this.port = p;
        this.toAddr = to & 0xFF;
        this.fromAddr = from & 0xFF;
        this.syncPending = syncFirst;
        this.toggle = 0;
    }

    /* =========================================================================
       CRC16/XMODEM
       ========================================================================= */
    private int crcUpdate(int crc, int b){
        for (int i=0; i<8; i++){
            boolean fb = (crc & 0x8000) != 0;
            crc = ((crc << 1) & 0xFFFF) | ((b >> (7-i)) & 1);
            if (fb) crc ^= POLY;
        }
        return crc;
    }

    private int crcLCP(byte[] data){
        int c = SEED;
        for(byte x : data)
            c = crcUpdate(c, x & 0xFF);
        return c;
    }

    /* =========================================================================
       Escaping LCP
       ========================================================================= */
    private byte[] esc(byte[] in){
        ByteArrayBuilder out = new ByteArrayBuilder(in.length*2);
        for(byte b : in){
            int x = b & 0xFF;
            if(x == ESC || x == TILDE)
                out.add((byte)ESC);
            out.add(b);
        }
        return out.toByteArray();
    }

    /* =========================================================================
       Lecture brute octet / ESC
       ========================================================================= */
    private int readByte(int timeout){
        try{
            byte[] b = new byte[1];
            int n = port.read(b, timeout);
            if(n <= 0) return -1;
            return b[0] & 0xFF;
        }catch(Exception e){
            return -1;
        }
    }

    private RawByte readEscaped(int timeout){
        int b = readByte(timeout);
        if(b < 0) return new RawByte(-1, new byte[0]);

        if(b == ESC){
            int y = readByte(timeout);
            if(y < 0) return new RawByte(-1, new byte[]{(byte)ESC});
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
       ByteArrayBuilder interne
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

        byte[] toByteArray(){ return Arrays.copyOf(buf, len); }

        private void ensure(int n){
            if(len+n > buf.length){
                buf = Arrays.copyOf(buf, Math.max(buf.length*2, len+n));
            }
        }

        static byte[] concat(byte[] a, byte[] b){
            if(a==null||a.length==0) return b;
            if(b==null||b.length==0) return a;
            byte[] o = Arrays.copyOf(a, a.length+b.length);
            System.arraycopy(b,0,o,a.length,b.length);
            return o;
        }
    }

    /* =========================================================================
       hex() utilisé pour logs
       ========================================================================= */
    private static String hex(byte[] data){
        if(data == null) return "(null)";
        StringBuilder sb = new StringBuilder();
        for(byte b : data) sb.append(String.format("%02X ",b));
        return sb.toString().trim();
    }

    /* =========================================================================
       Construction TX frame
       ========================================================================= */
    private byte[] buildFrame(byte[] payload){

        int status = toggle & 1;

        if(syncPending){
            status |= 0x02; // SYNC une seule fois
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
                (byte)((crc>>8)&0xFF)
        };
        byte[] crcEsc = esc(crcRaw);

        ByteArrayBuilder out = new ByteArrayBuilder();
        out.add((byte)TILDE);
        out.add((byte)TILDE);
        out.add(coreEsc);
        out.add(crcEsc);

        byte[] fr = out.toByteArray();

        if(DUMP_TX) log("TX: " + hex(fr));
        return fr;
    }

    /* =========================================================================
       readFrame (parser structuré complet)
       ========================================================================= */
    public byte[] readFrame(int timeout) throws IOException {

        long tEnd = System.currentTimeMillis() + timeout;
        int syncCount = 0;

        while(System.currentTimeMillis() < tEnd){
            int b = readByte(timeout);
            if(b < 0) continue;
            if(b == TILDE){
                syncCount++;
                if(syncCount==2) break;
            }else syncCount=0;
        }

        if(syncCount < 2)
            throw new IOException("Timeout sync ~~");

        ByteArrayBuilder raw = new ByteArrayBuilder();
        raw.add((byte)TILDE);
        raw.add((byte)TILDE);

        ParsedFrame pf = new ParsedFrame();
        pf.headerRaw = new byte[0];
        pf.payloadRaw= new byte[0];
        pf.header    = new byte[4];

        for(int i=0; i<4; i++){
            RawByte rb = readEscaped(timeout);
            if(rb.decoded < 0) throw new IOException("Header timeout");

            pf.header[i] = (byte)rb.decoded;
            pf.headerRaw = ByteArrayBuilder.concat(pf.headerRaw, rb.raw);
        }

        raw.add(pf.headerRaw);

        int plen = pf.header[3] & 0xFF;
        pf.payload = new byte[plen];

        for(int i=0; i<plen; i++){
            RawByte rb = readEscaped(timeout);
            if(rb.decoded < 0) throw new IOException("Payload timeout");

            pf.payload[i] = (byte)rb.decoded;
            pf.payloadRaw = ByteArrayBuilder.concat(pf.payloadRaw, rb.raw);
        }

        raw.add(pf.payloadRaw);

        RawByte c0 = readEscaped(timeout);
        RawByte c1 = readEscaped(timeout);
        if(c0.decoded<0 || c1.decoded<0)
            throw new IOException("CRC timeout");

        raw.add(c0.raw);
        raw.add(c1.raw);

        pf.rawFrame = raw.toByteArray();
        pf.crcRx = (c0.decoded & 0xFF) | ((c1.decoded & 0xFF)<<8);

        byte[] coreEsc = ByteArrayBuilder.concat(pf.headerRaw, pf.payloadRaw);
        pf.crcCalc = crcLCP(coreEsc);
        pf.crcOK   = (pf.crcCalc == pf.crcRx);

        if(!pf.crcOK)
            throw new IOException(
                    String.format("CRC mismatch recv=%04X calc=%04X",
                            pf.crcRx, pf.crcCalc)
            );

        if(DUMP_RX) log("RX: "+hex(pf.rawFrame));

        lastFrame.set(pf);
        return pf.rawFrame;
    }

    /* =========================================================================
       ExtractStatus / ExtractPayload
       ========================================================================= */
    public static final class LcpStatus {
        public boolean toggle;
        public boolean sync;
        public boolean busy;

        public static LcpStatus fromByte(int b){
            LcpStatus s = new LcpStatus();
            s.toggle = (b & 1)!=0;
            s.sync   = (b & 2)!=0;
            s.busy   = (b & 4)!=0;
            return s;
        }
    }

    public static LcpStatus extractStatus(byte[] frameRaw){
        ParsedFrame pf = lastFrame.get();
        if(pf==null || pf.rawFrame!=frameRaw)
            throw new IllegalStateException("extractStatus on unknown frame");
        return LcpStatus.fromByte(pf.header[2] & 0xFF);
    }

    public static byte[] extractPayload(byte[] frameRaw){
        ParsedFrame pf = lastFrame.get();
        if(pf==null || pf.rawFrame!=frameRaw)
            throw new IllegalStateException("extractPayload on unknown frame");
        return pf.payload;
    }

    /* =========================================================================
       sendRecv  (BUSY → queued → 0x7D)
       ========================================================================= */
    public byte[] sendRecv(byte[] payload, int timeoutMs) throws IOException {

        byte[] fr = buildFrame(payload);

        try { port.purgeHwBuffers(true,true); }catch(Exception ignored){}

        port.write(fr, timeoutMs);

        byte[] rsp = readFrame(timeoutMs);
        LcpStatus st = extractStatus(rsp);

        if(st.busy)
            return waitQueued(5000,150);

        return rsp;
    }

    /* =========================================================================
       waitQueued
       ========================================================================= */
    private byte[] waitQueued(int timeoutMs, int pollMs) throws IOException {

        long tEnd = System.currentTimeMillis() + timeoutMs;
        byte[] last=null;

        while(System.currentTimeMillis() < tEnd){

            byte[] rsp = readFrame(Math.max(1200,pollMs+800));
            byte[] p = extractPayload(rsp);
            LcpStatus st = extractStatus(rsp);

            if(p!=null && p.length>0) last=p;

            if(st.busy){
                sleep(pollMs);
                continue;
            }

            if(p==null || p.length==0){
                sleep(pollMs);
                continue;
            }

            int rc = p[0] & 0xFF;

            if(rc==RC_REQUEST_ABORTED)
                throw new IOException("Queue aborted");

            if(rc==RC_REQUEST_QUEUED || rc==RC_NO_REQUEST_ACTIVE){
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
        byte[] rsp = sendRecv(req,2000);
        byte[] p   = extractPayload(rsp);

        if(p!=null && p.length>0 &&
           (p[0]==RC_REQUEST_QUEUED || p[0]==RC_NO_REQUEST_ACTIVE)){
            p = waitQueued(5000,150);
        }

        if(p==null || p.length<2 || p[0]!=RC_OK)
            throw new IOException("GET_FIELD #"+field);

        return Arrays.copyOfRange(p,2,p.length);
    }

    /* =========================================================================
       SET_FIELD
       ========================================================================= */
    public void opSetField(int field, byte[] data) throws IOException {

        byte[] pl = new byte[2+data.length];
        pl[0] = (byte)MSG_SET_FIELD;
        pl[1] = (byte)field;
        System.arraycopy(data,0,pl,2,data.length);

        byte[] rsp = sendRecv(pl,2000);
        byte[] p   = extractPayload(rsp);

        if(p!=null && p.length>0 &&
           (p[0]==RC_REQUEST_QUEUED || p[0]==RC_NO_REQUEST_ACTIVE)){
            p = waitQueued(5000,150);
        }

        if(p==null || p.length<1 || p[0]!=RC_OK)
            throw new IOException("SET_FIELD #"+field);
    }

    /* =========================================================================
       ISSUE_COMMAND
       ========================================================================= */
    public byte[] opIssueCommand(int cmd) throws IOException {

        byte[] req = new byte[]{ (byte)MSG_ISSUE_COMMAND, (byte)cmd };
        byte[] rsp = sendRecv(req,2000);
        byte[] p   = extractPayload(rsp);

        if(p!=null && p.length>0 &&
           (p[0]==RC_REQUEST_QUEUED || p[0]==RC_NO_REQUEST_ACTIVE)){
            p = waitQueued(5000,150);
        }

        if(p==null || p.length<1 || p[0]!=RC_OK)
            throw new IOException("CMD 0x"+Integer.toHexString(cmd));

        return p;
    }

    /* =========================================================================
       GET_DELIVERY_STATUS
       ========================================================================= */
    public int[] opDeliveryStatus() throws IOException {

        byte[] rsp = sendRecv(new byte[]{ (byte)MSG_GET_DEL_STATUS },2000);
        byte[] p   = extractPayload(rsp);

        if(p!=null && p.length>0 &&
           (p[0]==RC_REQUEST_QUEUED || p[0]==RC_NO_REQUEST_ACTIVE)){
            p = waitQueued(5000,150);
        }

        if(p==null || p.length<6 || p[0]!=RC_OK)
            throw new IOException("Invalid 0x28");

        int ds = u16be(p,2);
        int dc = u16be(p,4);
        return new int[]{ ds, dc };
    }

    /* =========================================================================
       GET_MACHINE
       ========================================================================= */
    public int[] opMachineStatusFull() throws IOException {

        byte[] rsp = sendRecv(new byte[]{ (byte)MSG_GET_MACHINE },2000);
        byte[] p   = extractPayload(rsp);

        if(p!=null && p.length>0 &&
           (p[0]==RC_REQUEST_QUEUED || p[0]==RC_NO_REQUEST_ACTIVE)){
            p = waitQueued(5000,150);
        }

        if(p==null || p.length<8 || p[0]!=RC_OK){
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
