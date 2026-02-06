
package com.pa.lcr.lcp;

import com.hoho.android.usbserial.driver.UsbSerialPort;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * LcpLink — A2‑Enhanced + PythonCompat + CancelIO
 * (2026‑02‑05, verrous, parsing, metrics, guard 0x23/0x28, low-level throttles,
 *  any-poll coalesce, compat Python: 0x7D actif + cadence courte, cancel E/S global)
 *
 * Version marker:
 *   LcpLink v2026-02-05 fuse+throttle+portlock+delstatus+lowlvlthrottle+anypoll+guard+pycompat+cancel
 */
public class LcpLink {

    // ============================== VERSION ==============================
    private static final String LCP_VERSION =
        "LcpLink v2026-02-05 fuse+throttle+portlock+delstatus+lowlvlthrottle+anypoll+guard+pycompat+cancel";

    // ============================ PROTO CONST ============================
    public static final int TILDE = 0x7E;
    public static final int ESC   = 0x1B;

    private static final int SEED = 0x7E7E;   // CRC16/XMODEM seed
    private static final int POLY = 0x1021;   // CRC16/XMODEM poly

    public static final int MSG_GET_FIELD      = 0x20;
    public static final int MSG_SET_FIELD      = 0x21;
    public static final int MSG_PRINT_TEXT     = 0x22;
    public static final int MSG_GET_MACHINE    = 0x23;
    public static final int MSG_ISSUE_COMMAND  = 0x24;
    public static final int MSG_GET_DEL_STATUS = 0x28;
    public static final int MSG_CHECK_REQUEST  = 0x7D;
    public static final int MSG_GET_PRODUCTID  = 0x00;

    public static final int RC_OK                = 0x00;
    public static final int RC_REQUEST_QUEUED    = 0x26;
    public static final int RC_NO_REQUEST_ACTIVE = 0x27;
    public static final int RC_REQUEST_ABORTED   = 0x28;

    // Machine flags (ds/dc)
    public static final int LCRSc_DEL_TICKET_PENDING = 0x0001;
    public static final int LCRSc_FLOW_ACTIVE        = 0x0004;
    public static final int LCRSc_DELIVERY_ACTIVE    = 0x0008;
    public static final int LCRSc_BEGIN_DELIVERY     = 0x0400;

    // Debug
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

    // ================ GUARD interne (op* → sendRecv ok) =================
    private static final ThreadLocal<Boolean> INTERNAL_OK = new ThreadLocal<>();

    // ============================ PortRegistry ===========================
    private static final class PortRegistry {
        private static final ConcurrentHashMap<String, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
        static ReentrantLock acquire(String key) {
            return LOCKS.computeIfAbsent(key, k -> new ReentrantLock(true));
        }
    }

    // ============================== ATTRS ================================
    private final UsbSerialPort port;
    private final int toAddr, fromAddr;
    private boolean syncPending;
    private int toggle;

    // Cadence inter-trames (file physique)
    private static final int INTER_FRAME_PAUSE_MS  = 60;
    private static final int INTER_FRAME_JITTER_MS = 8;

    // Locks
    private final Object txRxLock = new Object();
    private final Object portLock;
    private final ReentrantLock globalPortLock;

    // Throttles (haut/bas) standards (1 Hz)
    private final Object machinePollLock = new Object();
    private static final int MIN_POLL_GET_MACHINE_MS_STD = 1000;
    private volatile long lastGetMachineAt = 0L;

    private final Object delStatusPollLock = new Object();
    private static final int MIN_POLL_GET_DEL_STATUS_MS_STD = 1000;
    private volatile long lastDelStatusAt = 0L;

    // Coalesce global 0x23/0x28
    private static final ConcurrentHashMap<String, AtomicLong> LAST_ANY_POLL = new ConcurrentHashMap<>();
    private static final int MIN_POLL_ANY_MS_STD = 500;

    // Throttle “par type” (bas niveau)
    private final String portKey;
    private static final ConcurrentHashMap<String, AtomicLong> LAST_GET_MACHINE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicLong> LAST_GET_DELSTS  = new ConcurrentHashMap<>();

    // FIN d’échange pour IFΔ
    private volatile long lastExchangeFinishedAtMs = 0L;

    // Metrics
    private final Metrics metrics = new Metrics();

    // ======= PythonCompat (active queue with 0x7D + short cadence) =======
    private volatile boolean pythonCompat = false;
    private volatile int minPollMs = 200; // e.g. --poll 0.2 → 200 ms
    private volatile int minPollAnyMs = MIN_POLL_ANY_MS_STD; // coalesce; ignored in pythonCompat

    // ======= Breaker / Cancel IO global =======
    private volatile boolean ioCancelled = false;

    public void setPythonCompat(boolean enable, int pollMs){
        this.pythonCompat = enable;
        this.minPollMs = Math.max(150, pollMs);
        log("[LCP] PythonCompat=" + enable + " pollMs=" + this.minPollMs);
    }
    public void cancelIO() { ioCancelled = true; log("[LCP] IO CANCELLED"); }
    public void resumeIO() { ioCancelled = false; log("[LCP] IO RESUMED"); }
    private void checkCancelled() throws IOException { if (ioCancelled) throw new IOException("CANCELLED"); }

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
        this.portKey = key;

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
        ByteArrayBuilder out = new ByteArrayBuilder(in.length*2);
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
            return new RawByte(y, new byte[]{(byte)ESC,(byte)y});
        }
        return new RawByte(b, new byte[]{(byte)b});
    }
    private static final class RawByte {
        final int decoded; final byte[] raw;
        RawByte(int d, byte[] r){ decoded=d; raw=r; }
    }

    // ========================= ByteArrayBuilder =========================
    private static final class ByteArrayBuilder {
        private byte[] buf; private int len;
        ByteArrayBuilder(){ this(128); }
        ByteArrayBuilder(int cap){ buf=new byte[cap]; len=0; }
        void add(byte b){ ensure(1); buf[len++] = b; }
        void add(byte[] bb){ ensure(bb.length); System.arraycopy(bb,0,buf,len,bb.length); len += bb.length; }
        byte[] toByteArray(){ return Arrays.copyOf(buf,len); }
        private void ensure(int n){ if(len+n > buf.length) buf = Arrays.copyOf(buf, Math.max(buf.length*2, len+n)); }
        static byte[] concat(byte[] a, byte[] b){
            if(a==null||a.length==0) return b;
            if(b==null||b.length==0) return a;
            byte[] out = Arrays.copyOf(a, a.length+b.length);
            System.arraycopy(b,0,out,a.length,b.length);
            return out;
        }
    }

    private static String hex(byte[] data){
        if(data == null) return "(null)";
        StringBuilder sb = new StringBuilder();
        for(byte b : data) sb.append(String.format("%02X ",b));
        return sb.toString().trim();
    }

    // ============================= BUILD TX =============================
    private byte[] buildFrame(byte[] payload){
        int status = toggle & 1;
        if(syncPending){ status |= 0x02; syncPending = false; }
        toggle ^= 1;

        byte[] header = new byte[]{ (byte)toAddr, (byte)fromAddr, (byte)status, (byte)payload.length };
        byte[] coreEsc = esc(ByteArrayBuilder.concat(header, payload));

        int crc = crcLCP(coreEsc);
        byte[] crcRaw = new byte[]{ (byte)(crc & 0xFF), (byte)((crc>>8)&0xFF) };
        byte[] crcEsc = esc(crcRaw);

        ByteArrayBuilder out = new ByteArrayBuilder();
        out.add((byte)TILDE); out.add((byte)TILDE);
        out.add(coreEsc); out.add(crcEsc);

        byte[] fr = out.toByteArray();
        if(DUMP_TX) log("TX: "+hex(fr));
        return fr;
    }

    // ============================== READ RX =============================
    public byte[] readFrame(int timeout) throws IOException {
        long tEnd = System.currentTimeMillis() + timeout;
        int syncCount = 0;
        while(System.currentTimeMillis() < tEnd){
            checkCancelled(); // cancel point
            int b = readByte(timeout);
            if(b < 0) continue;
            if(b == TILDE){
                syncCount++;
                if(syncCount==2) break;
            } else syncCount=0;
        }
        if(syncCount < 2) throw new IOException("Timeout sync ~~");

        ByteArrayBuilder raw = new ByteArrayBuilder();
        raw.add((byte)TILDE); raw.add((byte)TILDE);

        ParsedFrame pf = new ParsedFrame();
        pf.headerRaw = new byte[0]; pf.payloadRaw= new byte[0];
        pf.header    = new byte[4];

        for(int i=0; i<4; i++){
            checkCancelled(); // cancel point
            RawByte rb = readEscaped(timeout);
            if(rb.decoded < 0) throw new IOException("Header timeout");
            pf.header[i] = (byte)rb.decoded;
            pf.headerRaw = ByteArrayBuilder.concat(pf.headerRaw, rb.raw);
        }
        raw.add(pf.headerRaw);

        int plen = pf.header[3] & 0xFF;
        pf.payload = new byte[plen];

        for(int i=0; i<plen; i++){
            checkCancelled(); // cancel point
            RawByte rb = readEscaped(timeout);
            if(rb.decoded<0) throw new IOException("Payload timeout");
            pf.payload[i] = (byte)rb.decoded;
            pf.payloadRaw = ByteArrayBuilder.concat(pf.payloadRaw, rb.raw);
        }
        raw.add(pf.payloadRaw);

        checkCancelled(); // cancel point
        RawByte c0 = readEscaped(timeout);
        checkCancelled(); // cancel point
        RawByte c1 = readEscaped(timeout);
        if(c0.decoded<0 || c1.decoded<0) throw new IOException("CRC timeout");

        raw.add(c0.raw); raw.add(c1.raw);

        pf.rawFrame = raw.toByteArray();
        pf.crcRx = (c0.decoded & 0xFF) | ((c1.decoded & 0xFF)<<8);

        byte[] coreEsc = ByteArrayBuilder.concat(pf.headerRaw, pf.payloadRaw);
        pf.crcCalc = crcLCP(coreEsc);
        pf.crcOK   = (pf.crcCalc == pf.crcRx);

        if(!pf.crcOK) throw new IOException(String.format("CRC mismatch recv=%04X calc=%04X", pf.crcRx, pf.crcCalc));
        if(DUMP_RX) log("RX: "+hex(pf.rawFrame));
        lastFrame.set(pf);
        metrics.rxFrames.incrementAndGet();
        return pf.rawFrame;
    }

    // ======================= Status / Payload extract ====================
    public static final class LcpStatus {
        public boolean toggle, sync, busy;
        public static LcpStatus fromByte(int b){
            LcpStatus s = new LcpStatus();
            s.toggle = (b & 1)!=0; s.sync=(b&2)!=0; s.busy=(b&4)!=0;
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

    // =============================== I/O =================================
    public byte[] sendRecv(byte[] payload, int timeoutMs) throws IOException {
        checkCancelled(); // cancel point

        globalPortLock.lock();
        try {
        synchronized (portLock) {
        synchronized (txRxLock) {
            checkCancelled(); // cancel point

            long now = System.currentTimeMillis();
            if (lastExchangeFinishedAtMs == 0L) lastExchangeFinishedAtMs = now;
            long since = now - lastExchangeFinishedAtMs;
            metrics.updateInterFrameDelta(since);

            int pause = INTER_FRAME_PAUSE_MS + rnd(0, INTER_FRAME_JITTER_MS);
            int sleepApplied = (since < pause) ? (pause - (int)since) : 0;
            if (sleepApplied > 0) sleepMs(sleepApplied);

            if (DUMP_TX) {
                int msg0 = (payload!=null && payload.length>0) ? (payload[0] & 0xFF) : -1;
                Boolean inOK = INTERNAL_OK.get();
                log(String.format("sendRecv(msg=0x%02X) inOK=%s by [%s] thread=%s",
                        msg0, String.valueOf(inOK!=null && inOK), callerTop(), Thread.currentThread().getName()));
            }

            // 0x7D — autorisé UNIQUEMENT en PythonCompat ET via op* (INTERNAL_OK)
            if (payload != null && payload.length > 0 && (payload[0] & 0xFF) == MSG_CHECK_REQUEST) {
                if (!pythonCompat) {
                    throw new IOException("0x7D interdit (activer PythonCompat)");
                }
                if (INTERNAL_OK.get() == null || !INTERNAL_OK.get()) {
                    throw new IOException("0x7D réservé aux op* (opCheckRequest). Appel direct interdit.");
                }
            }

            // Guard: 0x23/0x28 seulement via op*
            if (payload != null && payload.length > 0) {
                int msg = payload[0] & 0xFF;
                if ((msg == MSG_GET_MACHINE || msg == MSG_GET_DEL_STATUS)
                    && (INTERNAL_OK.get() == null || !INTERNAL_OK.get())) {
                    throw new IOException("Utiliser opMachineStatusFull()/opDeliveryStatus() pour 0x23/0x28.");
                }
            }

            // Throttles bas-niveau (par type)
            if (payload != null && payload.length > 0) {
                int msg = payload[0] & 0xFF;
                if (msg == MSG_GET_MACHINE) {
                    AtomicLong ts = LAST_GET_MACHINE.computeIfAbsent(portKey, k -> new AtomicLong(0));
                    long last = ts.get(), now2 = System.currentTimeMillis();
                    long dt = now2 - last; if (dt < 0) dt = 0;
                    int min = pythonCompat ? minPollMs : MIN_POLL_GET_MACHINE_MS_STD;
                    if (last != 0 && dt < min) sleepMs((int)(min - dt));
                    ts.set(System.currentTimeMillis());
                } else if (msg == MSG_GET_DEL_STATUS) {
                    AtomicLong ts = LAST_GET_DELSTS.computeIfAbsent(portKey, k -> new AtomicLong(0));
                    long last = ts.get(), now2 = System.currentTimeMillis();
                    long dt = now2 - last; if (dt < 0) dt = 0;
                    int min = pythonCompat ? minPollMs : MIN_POLL_GET_DEL_STATUS_MS_STD;
                    if (last != 0 && dt < min) sleepMs((int)(min - dt));
                    ts.set(System.currentTimeMillis());
                }
            }

            // Coalesce global 0x23/0x28 (désactivé en pythonCompat)
            if (!pythonCompat && payload != null && payload.length > 0) {
                int msg = payload[0] & 0xFF;
                if (msg == MSG_GET_MACHINE || msg == MSG_GET_DEL_STATUS) {
                    AtomicLong tsAny = LAST_ANY_POLL.computeIfAbsent(portKey, k -> new AtomicLong(0));
                    long lastA = tsAny.get(), nowA = System.currentTimeMillis();
                    long dtA = nowA - lastA; if (dtA < 0) dtA = 0;
                    if (lastA != 0 && dtA < minPollAnyMs) sleepMs((int)(minPollAnyMs - dtA));
                    tsAny.set(System.currentTimeMillis());
                }
            }

            checkCancelled(); // cancel point
            byte[] fr = buildFrame(payload);
            try { port.purgeHwBuffers(true,true); }catch(Exception ignored){}

            checkCancelled(); // cancel point
            port.write(fr, timeoutMs);

            metrics.txFrames.incrementAndGet();

            checkCancelled(); // cancel point
            byte[] rsp = readFrame(timeoutMs);

            lastExchangeFinishedAtMs = System.currentTimeMillis();
            if (DUMP_TX) log(String.format("IFΔ=%dms, sleep=%dms", (since<0?0:since), sleepApplied));
            return rsp;
        }} } finally {
            globalPortLock.unlock();
        }
    }

    // ===================== Attente passive (par défaut) =================
    private byte[] waitQueued(int timeoutMs, int pollMs) throws IOException {
        long tStart = System.currentTimeMillis(), tEnd = tStart + timeoutMs;
        byte[] last=null; metrics.queuedWaits.incrementAndGet();
        while(System.currentTimeMillis() < tEnd){
            checkCancelled(); // cancel point
            byte[] rsp = readFrame(Math.max(1200,pollMs+800));
            byte[] p   = extractPayload(rsp);
            LcpStatus st = extractStatus(rsp);
            if (st.busy) metrics.busyStatusFrames.incrementAndGet();
            if(p!=null && p.length>0) last = p;
            if(st.busy || p==null || p.length==0){ sleep(pollMs); continue; }
            int rc = p[0] & 0xFF;
            if(rc==RC_REQUEST_ABORTED) throw new IOException("Queue aborted");
            if(rc==RC_REQUEST_QUEUED || rc==RC_NO_REQUEST_ACTIVE){
                if (rc == RC_REQUEST_QUEUED) metrics.rc26.incrementAndGet();
                sleep(pollMs); continue;
            }
            lastExchangeFinishedAtMs = System.currentTimeMillis();
            metrics.queuedWaitTimeMs.addAndGet(System.currentTimeMillis() - tStart);
            return p;
        }
        metrics.queuedWaitTimeMs.addAndGet(System.currentTimeMillis() - tStart);
        throw new IOException("Queued timeout last="+hex(last));
    }

    // ====================== Attente active (Python) ======================
    public byte[] opCheckRequest() throws IOException {
        if (!pythonCompat) throw new IOException("opCheckRequest() nécessite PythonCompat");
        INTERNAL_OK.set(Boolean.TRUE);
        try {
            checkCancelled(); // cancel point
            return sendRecv(new byte[]{ (byte)MSG_CHECK_REQUEST }, Math.max(1200, minPollMs + 800));
        } finally {
            INTERNAL_OK.remove();
        }
    }
    private byte[] waitQueuedPython(int timeoutMs) throws IOException {
        long tEnd = System.currentTimeMillis() + timeoutMs;
        byte[] last = null;
        while (System.currentTimeMillis() < tEnd) {
            checkCancelled(); // cancel point
            byte[] rsp = opCheckRequest();
            byte[] p   = extractPayload(rsp);
            if (p != null && p.length > 0) last = p;

            if (p == null || p.length == 0) { sleep(minPollMs); continue; }
            int rc = p[0] & 0xFF;
            if (rc == RC_REQUEST_ABORTED) throw new IOException("Queued aborted");
            if (rc == RC_REQUEST_QUEUED || rc == RC_NO_REQUEST_ACTIVE) {
                sleep(minPollMs); continue;
            }
            return p; // sortie dès RC != 0x26
        }
        throw new IOException("Queued timeout (python)");
    }

    // ============================== op* =================================
    public byte[] opGetField(int field) throws IOException {
        byte[] req = new byte[]{ (byte)MSG_GET_FIELD, (byte)field };
        checkCancelled();
        byte[] rsp = sendRecv(req,2000);
        LcpStatus st = extractStatus(rsp);
        byte[] p = extractPayload(rsp);

        if (pythonCompat) {
            if (st.busy || p==null || p.length==0
                    || p[0]==RC_REQUEST_QUEUED || p[0]==RC_NO_REQUEST_ACTIVE) {
                p = waitQueuedPython(5000);
            }
        } else {
            if (st.busy || p==null || p.length==0) {
                if (st.busy) metrics.busyStatusFrames.incrementAndGet();
                p = waitQueued(5000,150);
            }
            if(p!=null && p.length>0 &&
                    (p[0]==RC_REQUEST_QUEUED || p[0]==RC_NO_REQUEST_ACTIVE)){
                if (p[0]==RC_REQUEST_QUEUED) metrics.rc26.incrementAndGet();
                p=waitQueued(5000,150);
            }
        }

        if(p==null || p.length<2 || p[0]!=RC_OK) throw new IOException("GET_FIELD #"+field);
        return Arrays.copyOfRange(p,2,p.length);
    }

    public void opSetField(int field, byte[] data) throws IOException {
        byte[] pl = new byte[2+data.length];
        pl[0] = (byte)MSG_SET_FIELD; pl[1] = (byte)field;
        System.arraycopy(data,0,pl,2,data.length);

        checkCancelled();
        byte[] rsp = sendRecv(pl,2000);
        LcpStatus st = extractStatus(rsp);
        byte[] p = extractPayload(rsp);

        if (pythonCompat) {
            if (st.busy || p==null || p.length==0
                    || p[0]==RC_REQUEST_QUEUED || p[0]==RC_NO_REQUEST_ACTIVE) {
                p = waitQueuedPython(5000);
            }
        } else {
            if (st.busy || p==null || p.length==0) {
                if (st.busy) metrics.busyStatusFrames.incrementAndGet();
                p = waitQueued(5000,150);
            }
            if(p!=null && p.length>0 &&
                    (p[0]==RC_REQUEST_QUEUED || p[0]==RC_NO_REQUEST_ACTIVE)){
                if (p[0]==RC_REQUEST_QUEUED) metrics.rc26.incrementAndGet();
                p=waitQueued(5000,150);
            }
        }

        if(p==null || p.length<1 || p[0]!=RC_OK) throw new IOException("SET_FIELD #"+field);
    }

    public byte[] opIssueCommand(int cmd) throws IOException {
        metrics.opIssueCommandCalls.incrementAndGet();
        byte[] req = new byte[]{ (byte)MSG_ISSUE_COMMAND, (byte)cmd };
        checkCancelled();
        byte[] rsp = sendRecv(req,2000);
        LcpStatus st = extractStatus(rsp);
        byte[] p = extractPayload(rsp);

        if (pythonCompat) {
            if (st.busy || p==null || p.length==0
                    || p[0]==RC_REQUEST_QUEUED || p[0]==RC_NO_REQUEST_ACTIVE) {
                p = waitQueuedPython(5000);
            }
        } else {
            if (st.busy || p==null || p.length==0) {
                if (st.busy) metrics.busyStatusFrames.incrementAndGet();
                p = waitQueued(5000,150);
            }
            if(p!=null && p.length>0 &&
                    (p[0]==RC_REQUEST_QUEUED || p[0]==RC_NO_REQUEST_ACTIVE)){
                if (p[0]==RC_REQUEST_QUEUED) metrics.rc26.incrementAndGet();
                p=waitQueued(5000,150);
            }
        }

        if(p==null || p.length<1 || p[0]!=RC_OK) throw new IOException("CMD 0x"+Integer.toHexString(cmd));
        sleepMs(80 + rnd(0, 40)); // gap post commande
        return p;
    }

    public int[] opDeliveryStatus() throws IOException {
        synchronized (delStatusPollLock) {
            long now = System.currentTimeMillis();
            if (lastDelStatusAt != 0L) {
                long dt = now - lastDelStatusAt; if (dt < 0) dt = 0;
                int min = pythonCompat ? minPollMs : MIN_POLL_GET_DEL_STATUS_MS_STD;
                if (dt < min) sleepMs((int)(min - dt));
            }
            lastDelStatusAt = System.currentTimeMillis();
            metrics.opDeliveryStatusCalls.incrementAndGet();

            INTERNAL_OK.set(Boolean.TRUE);
            try {
                checkCancelled();
                byte[] rsp = sendRecv(new byte[]{ (byte)MSG_GET_DEL_STATUS },2000);
                LcpStatus st = extractStatus(rsp);
                byte[] p = extractPayload(rsp);

                if (pythonCompat) {
                    if (st.busy || p==null || p.length==0
                            || p[0]==RC_REQUEST_QUEUED || p[0]==RC_NO_REQUEST_ACTIVE) {
                        p = waitQueuedPython(5000);
                    }
                } else {
                    if (st.busy || p==null || p.length==0) {
                        if (st.busy) metrics.busyStatusFrames.incrementAndGet();
                        p = waitQueued(5000,150);
                    }
                    if(p!=null && p.length>0 &&
                            (p[0]==RC_REQUEST_QUEUED || p[0]==RC_NO_REQUEST_ACTIVE)){
                        if (p[0]==RC_REQUEST_QUEUED) metrics.rc26.incrementAndGet();
                        p=waitQueued(5000,150);
                    }
                }

                if(p==null || p.length<6 || p[0]!=RC_OK) throw new IOException("Invalid 0x28");
                int ds = u16be(p,2), dc = u16be(p,4);
                return new int[]{ ds, dc };
            } finally {
                INTERNAL_OK.remove();
            }
        }
    }

    public int[] opMachineStatusFull() throws IOException {
        synchronized (machinePollLock) {
            long now = System.currentTimeMillis();
            if (lastGetMachineAt != 0L) {
                long dt = now - lastGetMachineAt; if (dt < 0) dt = 0;
                int min = pythonCompat ? minPollMs : MIN_POLL_GET_MACHINE_MS_STD;
                if (dt < min) sleepMs((int)(min - dt));
            }
            lastGetMachineAt = System.currentTimeMillis();
            metrics.opGetMachineCalls.incrementAndGet();
            metrics.updatePollInterval();

            INTERNAL_OK.set(Boolean.TRUE);
            try {
                checkCancelled();
                byte[] rsp = sendRecv(new byte[]{ (byte)MSG_GET_MACHINE },2000);
                LcpStatus st = extractStatus(rsp);
                byte[] p = extractPayload(rsp);

                if (pythonCompat) {
                    if (st.busy || p==null || p.length==0
                            || p[0]==RC_REQUEST_QUEUED || p[0]==RC_NO_REQUEST_ACTIVE) {
                        p = waitQueuedPython(5000);
                    }
                } else {
                    if (st.busy || p==null || p.length==0) {
                        if (st.busy) metrics.busyStatusFrames.incrementAndGet();
                        p = waitQueued(5000,150);
                    }
                    if(p!=null && p.length>0 &&
                            (p[0]==RC_REQUEST_QUEUED || p[0]==RC_NO_REQUEST_ACTIVE)){
                        if (p[0]==RC_REQUEST_QUEUED) metrics.rc26.incrementAndGet();
                        p=waitQueued(5000,150);
                    }
                }

                if(p==null || p.length<8 || p[0]!=RC_OK){
                    int[] d = opDeliveryStatus();
                    return new int[]{ 0x0000, d[0], d[1] };
                }
                int dev = u16be(p,2), ds  = u16be(p,4), dc  = u16be(p,6);
                return new int[]{ dev, ds, dc };
            } finally {
                INTERNAL_OK.remove();
            }
        }
    }

    // ============================== Utils ================================
    private static int u16be(byte[] b, int off){ return ((b[off] & 0xFF)<<8) | (b[off+1] & 0xFF); }
    private static void sleep(int ms){ try { Thread.sleep(ms); } catch(Exception ignored){} }
    private static int rnd(int min, int max){ if (max <= 0) return min; return min + (int)(Math.random()*(max+1)); }
    private static void sleepMs(int ms){ if (ms<=0) return; try { Thread.sleep(ms); } catch (InterruptedException ie){ Thread.currentThread().interrupt(); } }

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

    // ============================== Metrics ==============================
    public MetricsSnapshot getMetrics() { return metrics.snapshot(); }
    public void resetMetrics() { metrics.reset(); }

    private static final class Metrics {
        private static final double EMA_ALPHA = 0.2;
        final AtomicLong txFrames=new AtomicLong(), rxFrames=new AtomicLong(), rc26=new AtomicLong(),
                busyStatusFrames=new AtomicLong(), queuedWaits=new AtomicLong(), queuedWaitTimeMs=new AtomicLong(),
                opGetMachineCalls=new AtomicLong(), opDeliveryStatusCalls=new AtomicLong(), opIssueCommandCalls=new AtomicLong();
        private final Object lock = new Object();
        private long interFrameDeltaMinMs = Long.MAX_VALUE, interFrameDeltaMaxMs = 0L;
        private double interFrameDeltaEmaMs = -1.0, pollIntervalEmaMs = -1.0;
        private long lastPollTs = 0L;
        void updateInterFrameDelta(long d){ if(d<0)d=0; synchronized(lock){ if(d<interFrameDeltaMinMs) interFrameDeltaMinMs=d; if(d>interFrameDeltaMaxMs) interFrameDeltaMaxMs=d; interFrameDeltaEmaMs=ema(interFrameDeltaEmaMs,d,EMA_ALPHA);} }
        void updatePollInterval(){ long now=System.currentTimeMillis(); synchronized(lock){ if(lastPollTs!=0){ long d=now-lastPollTs; if(d<0)d=0; pollIntervalEmaMs=ema(pollIntervalEmaMs,d,EMA_ALPHA);} lastPollTs=now; } }
        void reset(){ txFrames.set(0); rxFrames.set(0); rc26.set(0); busyStatusFrames.set(0); queuedWaits.set(0); queuedWaitTimeMs.set(0); opGetMachineCalls.set(0); opDeliveryStatusCalls.set(0); opIssueCommandCalls.set(0);
            synchronized(lock){ interFrameDeltaMinMs=Long.MAX_VALUE; interFrameDeltaMaxMs=0L; interFrameDeltaEmaMs=-1.0; lastPollTs=0L; pollIntervalEmaMs=-1.0; } }
        MetricsSnapshot snapshot(){ synchronized(lock){ long min=(interFrameDeltaMinMs==Long.MAX_VALUE)?-1:interFrameDeltaMinMs;
            return new MetricsSnapshot(txFrames.get(),rxFrames.get(),rc26.get(),busyStatusFrames.get(),queuedWaits.get(),queuedWaitTimeMs.get(),
                    opGetMachineCalls.get(),opDeliveryStatusCalls.get(),opIssueCommandCalls.get(),min,interFrameDeltaMaxMs,interFrameDeltaEmaMs,pollIntervalEmaMs); } }
        private static double ema(double prev,double v,double a){ return (prev<0)?v:(a*v+(1-a)*prev); }
    }
    public static final class MetricsSnapshot {
        public final long txFrames, rxFrames, rc26, busyStatusFrames, queuedWaits, queuedWaitTimeMs,
                opGetMachineCalls, opDeliveryStatusCalls, opIssueCommandCalls,
                interFrameDeltaMinMs, interFrameDeltaMaxMs;
        public final double interFrameDeltaEmaMs, pollIntervalEmaMs;
        private MetricsSnapshot(long tx,long rx,long rc26,long busy,long q,long qms,long gm,long ds,long ic,long dmin,long dmax,double dema,double pema){
            this.txFrames=tx; this.rxFrames=rx; this.rc26=rc26; this.busyStatusFrames=busy; this.queuedWaits=q; this.queuedWaitTimeMs=qms;
            this.opGetMachineCalls=gm; this.opDeliveryStatusCalls=ds; this.opIssueCommandCalls=ic; this.interFrameDeltaMinMs=dmin; this.interFrameDeltaMaxMs=dmax;
            this.interFrameDeltaEmaMs=dema; this.pollIntervalEmaMs=pema;
        }
        @Override public String toString() {
            return String.format("LcpLinkMetrics{tx=%d, rx=%d, rc26=%d, busy=%d, queuedWaits=%d, queuedWaitTimeMs=%d, getMachine=%d, getDelStatus=%d, issueCmd=%d, IFΔ[min=%dms,max=%dms,ema=%.1fms], pollEMA=%.1fms}",
                    txFrames, rxFrames, rc26, busyStatusFrames, queuedWaits, queuedWaitTimeMs, opGetMachineCalls, opDeliveryStatusCalls, opIssueCommandCalls,
                    interFrameDeltaMinMs, interFrameDeltaMaxMs, interFrameDeltaEmaMs, pollIntervalEmaMs);
        }
    }
}
