
package com.pa.lcr.lcp;

import com.hoho.android.usbserial.driver.UsbSerialPort;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

public class LcpLink {

    // ============================== VERSION ==============================
    private static final String LCP_VERSION =
            "LcpLink v2026-02-18 payload-logs + queued-0x7D + sync-first-compatible";

    // ============================ PROTO CONST ============================
    public static final int TILDE = 0x7E;
    public static final int ESC   = 0x1B;

    private static final int SEED = 0x7E7E;
    private static final int POLY = 0x1021;

    // Message IDs
    public static final int MSG_GET_PRODUCTID  = 0x00;
    public static final int MSG_GET_FIELD      = 0x20;
    public static final int MSG_SET_FIELD      = 0x21;
    public static final int MSG_PRINT_TEXT     = 0x22;
    public static final int MSG_GET_MACHINE    = 0x23;
    public static final int MSG_ISSUE_COMMAND  = 0x24;
    public static final int MSG_GET_DEL_STATUS = 0x28;

    // Queue support (comme script terrain) [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/lcr_simple_deliverV2.py)
    public static final int MSG_CHECK_REQUEST  = 0x7D;

    public static final int RC_OK                = 0x00;
    public static final int RC_REQUEST_QUEUED    = 0x26; // [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/lcr_simple_deliverV2.py)
    public static final int RC_NO_REQUEST_ACTIVE = 0x27; // [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/lcr_simple_deliverV2.py)
    public static final int RC_REQUEST_ABORTED   = 0x28; // [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/lcr_simple_deliverV2.py)

    // Delivery Code bits
    public static final int LCRSc_DEL_TICKET_PENDING = 0x0001;
    public static final int LCRSc_FLOW_ACTIVE        = 0x0004;
    public static final int LCRSc_DELIVERY_ACTIVE    = 0x0008;

    // Debug dumps
    public static boolean DUMP_TX = false;
    public static boolean DUMP_RX = false;

    // ============================== LOGGER ===============================
    public interface Logger { void log(String s); }
    private static Logger logger = null;
    public static void setLogger(Logger l){ logger = l; }
    private static void log(String s){ if (logger != null) logger.log(s); }

    // ============================ ParsedFrame ============================
    private static final class ParsedFrame {
        byte[] rawFrame, headerRaw, payloadRaw, header, payload;
        int crcRx, crcCalc;
        boolean crcOK;
    }
    private static final ThreadLocal<ParsedFrame> lastFrame = new ThreadLocal<>();

    // ============================ PortRegistry ===========================
    private static final class PortRegistry {
        private static final ConcurrentHashMap<String, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
        static ReentrantLock acquire(String key) { return LOCKS.computeIfAbsent(key, k -> new ReentrantLock(true)); }
    }

    // ============================== ATTRS ================================
    private final UsbSerialPort port;
    private final int toAddr, fromAddr;
    private boolean syncPending;
    private int toggle;

    private final Object txRxLock = new Object();
    private final Object portLock;
    private final ReentrantLock globalPortLock;

    private volatile boolean ioCancelled = false;

    // Poll gate
    private volatile boolean pollingBlocked = true;

    public void openPollWindow() {
        this.pollingBlocked = false;
        log("[LCP] PollWindow OPEN (owner=ANY) caller=" + callerTop());
    }

    public void closePollWindow() {
        this.pollingBlocked = true;
        log("[LCP] PollWindow CLOSE (prevOwner=ANY) caller=" + callerTop());
    }

    public void cancelIO() { ioCancelled = true; log("[LCP] IO CANCELLED"); }
    public void resumeIO() { ioCancelled = false; log("[LCP] IO RESUMED"); }
    private void checkCancelled() throws IOException { if (ioCancelled) throw new IOException("CANCELLED"); }

    /** Force Sync bit on next outbound frame (recovery). */
    public void forceSyncNext() { this.syncPending = true; log("[LCP] forceSyncNext()"); }

    public LcpLink(UsbSerialPort p, int to, int from, boolean syncFirst) {
        this.port = p;
        this.toAddr = to & 0xFF;
        this.fromAddr = from & 0xFF;
        this.syncPending = syncFirst;
        this.toggle = 0;

        this.portLock = p;
        String key = "usb:" +
                (p.getDriver()!=null && p.getDriver().getDevice()!=null
                        ? p.getDriver().getDevice().getDeviceId() : p.hashCode())
                + "/port:" + p.getPortNumber();
        this.globalPortLock = PortRegistry.acquire(key);

        log("[LCP] Loaded " + LCP_VERSION);
    }

    // =============================== CRC ================================
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
        for(byte x : data) c = crcUpdate(c, x & 0xFF);
        return c;
    }

    // ============================= ESCAPING =============================
    private byte[] esc(byte[] in){
        ByteArrayBuilder out = new ByteArrayBuilder(in.length * 2);
        for(byte b : in){
            int x = b & 0xFF;
            if(x == ESC || x == TILDE) out.add((byte)ESC);
            out.add(b);
        }
        return out.toByteArray();
    }

    // =========================== READ RAW/ESC ===========================
    private int readByte(int timeout){
        try{
            byte[] b = new byte[1];
            int n = port.read(b, timeout);
            if(n <= 0) return -1;
            return b[0] & 0xFF;
        }catch(Exception e){ return -1; }
    }

    private RawByte readEscaped(int timeout){
        int b = readByte(timeout);
        if(b < 0) return new RawByte(-1, new byte[0]);
        if(b == ESC){
            int y = readByte(timeout);
            if(y < 0) return new RawByte(-1, new byte[]{(byte)ESC});
            return new RawByte(y, new byte[]{(byte)ESC, (byte)y});
        }
        return new RawByte(b, new byte[]{(byte)b});
    }

    private static final class RawByte {
        final int decoded;
        final byte[] raw;
        RawByte(int d, byte[] r){ decoded=d; raw=r; }
    }

    // ========================= ByteArrayBuilder =========================
    private static final class ByteArrayBuilder {
        private byte[] buf;
        private int len;
        ByteArrayBuilder(){ this(128); }
        ByteArrayBuilder(int cap){ buf=new byte[cap]; len=0; }
        void add(byte b){ ensure(1); buf[len++] = b; }
        void add(byte[] bb){ ensure(bb.length); System.arraycopy(bb,0,buf,len,bb.length); len += bb.length; }
        byte[] toByteArray(){ return Arrays.copyOf(buf,len); }
        private void ensure(int n){
            if(len + n > buf.length) buf = Arrays.copyOf(buf, Math.max(buf.length * 2, len + n));
        }
        static byte[] concat(byte[] a, byte[] b){
            if(a==null || a.length==0) return b;
            if(b==null || b.length==0) return a;
            byte[] out = Arrays.copyOf(a, a.length + b.length);
            System.arraycopy(b, 0, out, a.length, b.length);
            return out;
        }
    }

    private static String hex(byte[] data){
        if(data == null) return "(null)";
        StringBuilder sb = new StringBuilder();
        for(byte b : data) sb.append(String.format("%02X ", b));
        return sb.toString().trim();
    }

    // ============================= STATUS =============================
    public static final class LcpStatus {
        public boolean toggle, sync, busy;
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
        if(pf == null || pf.rawFrame != frameRaw) throw new IllegalStateException("extractStatus: frame mismatch");
        return LcpStatus.fromByte(pf.header[2] & 0xFF);
    }

    public static byte[] extractPayload(byte[] frameRaw){
        ParsedFrame pf = lastFrame.get();
        if(pf == null || pf.rawFrame != frameRaw) throw new IllegalStateException("extractPayload: frame mismatch");
        return pf.payload;
    }

    // ============================= BUILD TX =============================
    private byte[] buildFrame(byte[] payload){
        int status = toggle & 1;
        if(syncPending){ status |= 0x02; syncPending = false; }
        toggle ^= 1;

        byte[] header = new byte[]{ (byte)toAddr, (byte)fromAddr, (byte)status, (byte)payload.length };
        byte[] coreEsc = esc(ByteArrayBuilder.concat(header, payload));

        int crc = crcLCP(coreEsc);
        byte[] crcRaw = new byte[]{ (byte)(crc & 0xFF), (byte)((crc >> 8) & 0xFF) };
        byte[] crcEsc = esc(crcRaw);

        ByteArrayBuilder out = new ByteArrayBuilder();
        out.add((byte)TILDE); out.add((byte)TILDE);
        out.add(coreEsc);
        out.add(crcEsc);

        byte[] fr = out.toByteArray();

        // TX log (payload clair)
        if (DUMP_TX) {
            int msg = (payload != null && payload.length > 0) ? (payload[0] & 0xFF) : -1;
            String hdr = String.format("to=0x%02X from=0x%02X st=0x%02X len=%d",
                    toAddr, fromAddr, status & 0xFF, (payload != null ? payload.length : 0));
            log("TX: " + hex(fr) + " | " + hdr
                    + " | msg=0x" + (msg >= 0 ? String.format("%02X", msg) : "??")
                    + " | PL=" + hex(payload));
        }
        return fr;
    }

    // ============================== READ RX =============================
    public byte[] readFrame(int timeout) throws IOException {
        long tEnd = System.currentTimeMillis() + timeout;
        int syncCount = 0;

        while(System.currentTimeMillis() < tEnd){
            checkCancelled();
            int b = readByte(timeout);
            if(b < 0) continue;
            if(b == TILDE){
                syncCount++;
                if(syncCount == 2) break;
            } else {
                syncCount = 0;
            }
        }
        if(syncCount < 2) throw new IOException("Timeout sync ~~");

        ByteArrayBuilder raw = new ByteArrayBuilder();
        raw.add((byte)TILDE); raw.add((byte)TILDE);

        ParsedFrame pf = new ParsedFrame();
        pf.headerRaw = new byte[0];
        pf.payloadRaw = new byte[0];
        pf.header = new byte[4];

        for(int i=0; i<4; i++){
            checkCancelled();
            RawByte rb = readEscaped(timeout);
            if(rb.decoded < 0) throw new IOException("Header timeout");
            pf.header[i] = (byte)rb.decoded;
            pf.headerRaw = ByteArrayBuilder.concat(pf.headerRaw, rb.raw);
        }
        raw.add(pf.headerRaw);

        int plen = pf.header[3] & 0xFF;
        pf.payload = new byte[plen];

        for(int i=0; i<plen; i++){
            checkCancelled();
            RawByte rb = readEscaped(timeout);
            if(rb.decoded < 0) throw new IOException("Payload timeout");
            pf.payload[i] = (byte)rb.decoded;
            pf.payloadRaw = ByteArrayBuilder.concat(pf.payloadRaw, rb.raw);
        }
        raw.add(pf.payloadRaw);

        checkCancelled();
        RawByte c0 = readEscaped(timeout);
        checkCancelled();
        RawByte c1 = readEscaped(timeout);
        if(c0.decoded < 0 || c1.decoded < 0) throw new IOException("CRC timeout");

        raw.add(c0.raw);
        raw.add(c1.raw);

        pf.rawFrame = raw.toByteArray();
        pf.crcRx = (c0.decoded & 0xFF) | ((c1.decoded & 0xFF) << 8);

        byte[] coreEsc = ByteArrayBuilder.concat(pf.headerRaw, pf.payloadRaw);
        pf.crcCalc = crcLCP(coreEsc);
        pf.crcOK = (pf.crcCalc == pf.crcRx);

        if(!pf.crcOK) throw new IOException(String.format("CRC mismatch recv=%04X calc=%04X", pf.crcRx, pf.crcCalc));

        // RX log (rc/b1 + payload clair)
        if (DUMP_RX) {
            int to = pf.header[0] & 0xFF;
            int from = pf.header[1] & 0xFF;
            int st = pf.header[2] & 0xFF;
            int ln = pf.header[3] & 0xFF;
            int rc = (pf.payload != null && pf.payload.length > 0) ? (pf.payload[0] & 0xFF) : -1;
            int b1 = (pf.payload != null && pf.payload.length > 1) ? (pf.payload[1] & 0xFF) : -1;
            String hdr = String.format("to=0x%02X from=0x%02X st=0x%02X len=%d", to, from, st, ln);
            log("RX: " + hex(pf.rawFrame) + " | " + hdr
                    + " | rc=0x" + (rc>=0?String.format("%02X",rc):"??")
                    + " b1=0x" + (b1>=0?String.format("%02X",b1):"??")
                    + " | PL=" + hex(pf.payload));
        }

        lastFrame.set(pf);
        return pf.rawFrame;
    }

    // =============================== I/O =================================
    private boolean isFramingTimeout(IOException io) {
        String m = (io.getMessage() == null) ? "" : io.getMessage();
        return m.contains("Timeout sync ~~")
                || m.contains("Header timeout")
                || m.contains("Payload timeout")
                || m.contains("CRC timeout");
    }

    private void purgeInputBestEffort() {
        try { port.purgeHwBuffers(true, false); } catch (Exception ignored) {}
    }

    public byte[] sendRecv(byte[] payload, int timeoutMs) throws IOException {
        checkCancelled();

        globalPortLock.lock();
        try {
            synchronized (portLock) {
                synchronized (txRxLock) {
                    purgeInputBestEffort(); // comme reset_input_buffer() avant TX [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/lcr_simple_deliverV2.py)

                    byte[] fr = buildFrame(payload);
                    port.write(fr, timeoutMs);

                    byte[] rsp;
                    try {
                        rsp = readFrame(timeoutMs);
                    } catch (IOException io) {
                        if (!isFramingTimeout(io)) throw io;

                        log("[LCP] sendRecv framing-timeout -> recover: " + io.getMessage());
                        forceSyncNext();
                        purgeInputBestEffort();

                        // pas d’auto-retry sur non-idempotent (SET / CMD) — comme ton comportement actuel
                        throw io;
                    }
                    return rsp;
                }
            }
        } finally {
            globalPortLock.unlock();
        }
    }

    // ============================ QUEUE WAIT =============================
    // Reproduit l’approche wait_queued() (0x7D) + RC=0x26/0x27. [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/lcr_simple_deliverV2.py)
    private byte[] waitQueued(long timeoutMs, long pollMs) throws IOException {
        long end = System.currentTimeMillis() + timeoutMs;
        byte[] last = null;

        while (System.currentTimeMillis() < end) {
            byte[] rsp = sendRecv(new byte[]{ (byte)MSG_CHECK_REQUEST }, (int)Math.max(1500, timeoutMs));
            byte[] p = extractPayload(rsp);
            last = p;

            if (p == null || p.length == 0) {
                sleepMs((int)pollMs);
                continue;
            }

            int rc = p[0] & 0xFF;
            if (rc == RC_REQUEST_QUEUED || rc == RC_NO_REQUEST_ACTIVE) {
                sleepMs((int)pollMs);
                continue;
            }
            if (rc == RC_REQUEST_ABORTED) throw new IOException("Queued aborted");

            // python: if rc==0 and p[1]==0 then return p[1:] (résultat original) [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/lcr_simple_deliverV2.py)
            if (rc == RC_OK && p.length >= 3 && (p[1] & 0xFF) == RC_OK) {
                return Arrays.copyOfRange(p, 1, p.length);
            }
            return p;
        }
        throw new IOException("Queued timeout last=" + (last == null ? "(null)" : hex(last)));
    }

    private byte[] sendRecvMaybeQueued(byte[] payload, int timeoutMs, long qTimeoutMs, long qPollMs) throws IOException {
        byte[] rsp = sendRecv(payload, timeoutMs);
        LcpStatus st = extractStatus(rsp);
        byte[] p = extractPayload(rsp);

        boolean queued = st.busy || (p != null && p.length > 0 && (p[0] & 0xFF) == RC_REQUEST_QUEUED);
        if (!queued) return p;

        return waitQueued(qTimeoutMs, qPollMs);
    }

    // ============================== op* =================================

    public byte[] opGetProductId() throws IOException {
        byte[] p = sendRecvMaybeQueued(new byte[]{ (byte)MSG_GET_PRODUCTID }, 2500, 5000, 200);
        if (p == null || p.length < 2 || (p[0] & 0xFF) != RC_OK) throw new IOException("GET_PRODUCT_ID");
        return p;
    }

    public byte[] opGetField(int field) throws IOException {
        byte[] p = sendRecvMaybeQueued(new byte[]{ (byte)MSG_GET_FIELD, (byte)field }, 2500, 6000, 200);
        if (p == null || p.length < 2 || (p[0] & 0xFF) != RC_OK) throw new IOException("GET_FIELD #" + field);
        return Arrays.copyOfRange(p, 2, p.length);
    }

    public void opSetField(int field, byte[] data) throws IOException {
        if (data == null) data = new byte[0];
        byte[] pl = new byte[2 + data.length];
        pl[0] = (byte)MSG_SET_FIELD;
        pl[1] = (byte)field;
        System.arraycopy(data, 0, pl, 2, data.length);

        byte[] p = sendRecvMaybeQueued(pl, 3500, 12000, 200);
        if (p == null || p.length < 1 || (p[0] & 0xFF) != RC_OK) throw new IOException("SET_FIELD #" + field);
    }

    public byte[] opIssueCommand(int cmd) throws IOException {
        byte[] p = sendRecvMaybeQueued(new byte[]{ (byte)MSG_ISSUE_COMMAND, (byte)cmd }, 3500, 12000, 200);
        if (p == null || p.length < 1 || (p[0] & 0xFF) != RC_OK) throw new IOException("CMD 0x" + Integer.toHexString(cmd));
        return p;
    }

    public int[] opDeliveryStatus() throws IOException {
        if (pollingBlocked) throw new IOException("POLL_BLOCKED");
        byte[] p = sendRecvMaybeQueued(new byte[]{ (byte)MSG_GET_DEL_STATUS }, 2500, 8000, 200);
        if (p == null || p.length < 6 || (p[0] & 0xFF) != RC_OK) throw new IOException("Invalid 0x28 payload");
        int ds = u16be(p, 2);
        int dc = u16be(p, 4);
        return new int[]{ ds, dc };
    }

    // ============================== Utils ================================
    private static int u16be(byte[] b, int off){ return ((b[off] & 0xFF) << 8) | (b[off+1] & 0xFF); }

    private static void sleepMs(int ms){
        if (ms <= 0) return;
        try { Thread.sleep(ms); }
        catch (InterruptedException ie){ Thread.currentThread().interrupt(); }
    }

    private static String callerTop() {
        try {
            StackTraceElement[] st = new Exception().getStackTrace();
            for (StackTraceElement e : st) {
                String cn = e.getClassName();
                if (!cn.startsWith("com.pa.lcr.lcp")) return e.toString();
            }
            return st.length > 0 ? st[0].toString() : "(unknown)";
        } catch (Exception ignored) { return "(trace unavailable)"; }
    }
}
