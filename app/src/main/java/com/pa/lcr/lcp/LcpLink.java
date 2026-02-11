
package com.pa.lcr.lcp;

import com.hoho.android.usbserial.driver.UsbSerialPort;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * LcpLink — LCP framing (~~ + escaping + CRC), queued handling (0x7D),
 * Printer status decoding (prnStatus bits), CancelIO, PollGate.
 *
 * Spec references:
 * - LCP framing: ~~ <to><from><status><len><data...><crc0><crc1> + ESC rules + CRC seed 0x7E7E poly 0x1021.
 * - Get Machine Status (0x23) returns: rc, devStatus(byte), prnStatus(byte), delStatus(u16), delCode(u16). Total payload=7.
 * - Printer Bits are in prnStatus byte.
 * - Delivery Code bit 0x0001 means delivery ticket pending (blocks new delivery until printed).
 */
public class LcpLink {

    // ============================== VERSION ==============================
    private static final String LCP_VERSION =
            "LcpLink v2026-02-10 pollwindow-any + conditional-purge + robust-0x7D + printer-status(0x23)";

    // ============================ PROTO CONST ============================
    public static final int TILDE = 0x7E;
    public static final int ESC   = 0x1B;

    // CRC16/XMODEM variant per LCP doc: seed includes "~~" (0x7E7E), poly 0x1021
    private static final int SEED = 0x7E7E;
    private static final int POLY = 0x1021;

    // LCP02 message IDs
    public static final int MSG_GET_FIELD      = 0x20;
    public static final int MSG_SET_FIELD      = 0x21;
    public static final int MSG_PRINT_TEXT     = 0x22;
    public static final int MSG_GET_MACHINE    = 0x23;
    public static final int MSG_ISSUE_COMMAND  = 0x24;
    public static final int MSG_GET_DEL_STATUS = 0x28;

    // queued support (legacy-compatible)
    public static final int MSG_CHECK_REQUEST  = 0x7D;
    public static final int MSG_GET_PRODUCTID  = 0x00;

    // Return codes (from doc: 38=queued etc; on the wire we use 0x26/0x27/0x28 observed)
    public static final int RC_OK                = 0x00;
    public static final int RC_REQUEST_QUEUED    = 0x26;
    public static final int RC_NO_REQUEST_ACTIVE = 0x27;
    public static final int RC_REQUEST_ABORTED   = 0x28;

    // Delivery Code bits (documented)
    public static final int LCRSc_DEL_TICKET_PENDING = 0x0001;
    public static final int LCRSc_FLOW_ACTIVE        = 0x0004;
    public static final int LCRSc_DELIVERY_ACTIVE    = 0x0008;
    public static final int LCRSc_BEGIN_DELIVERY     = 0x0400;

    // Debug dumps (hex frames)
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

    // Internal guard to restrict certain messages to op* wrappers
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

    // Inter-frame pacing
    private static final int INTER_FRAME_PAUSE_MS  = 60;
    private static final int INTER_FRAME_JITTER_MS = 8;

    // Locks
    private final Object txRxLock = new Object();
    private final Object portLock;
    private final ReentrantLock globalPortLock;
    private final String portKey;

    // Throttles
    private final Object machinePollLock = new Object();
    private final Object delStatusPollLock = new Object();
    private static final int MIN_POLL_GET_MACHINE_MS_STD = 1000;
    private static final int MIN_POLL_GET_DEL_STATUS_MS_STD = 1000;
    private volatile long lastGetMachineAt = 0L;
    private volatile long lastDelStatusAt = 0L;

    // Global coalesce (disabled in pythonCompat)
    private static final ConcurrentHashMap<String, AtomicLong> LAST_ANY_POLL = new ConcurrentHashMap<>();
    private static final int MIN_POLL_ANY_MS_STD = 500;

    // Per-type throttle
    private static final ConcurrentHashMap<String, AtomicLong> LAST_GET_MACHINE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicLong> LAST_GET_DELSTS  = new ConcurrentHashMap<>();

    // last exchange timestamp (IFΔ)
    private volatile long lastExchangeFinishedAtMs = 0L;

    // Metrics
    private final Metrics metrics = new Metrics();

    // pythonCompat (queued via 0x7D)
    private volatile boolean pythonCompat = false;
    private volatile int minPollMs = 200;
    private volatile int minPollAnyMs = MIN_POLL_ANY_MS_STD;

    // cancel IO
    private volatile boolean ioCancelled = false;

    // Poll gate
    private volatile boolean pollingBlocked = true;
    private volatile Thread pollOwner = null;

    // Conditional purge flag
    private volatile boolean needPurge = false;

    public void requestPurge() { needPurge = true; log("[LCP] Purge requested"); }

    public void setPythonCompat(boolean enable, int pollMs){
        this.pythonCompat = enable;
        this.minPollMs = Math.max(150, pollMs);
        log("[LCP] PythonCompat=" + enable + " pollMs=" + this.minPollMs);
    }

    public void cancelIO() { ioCancelled = true; log("[LCP] IO CANCELLED"); }
    public void resumeIO() { ioCancelled = false; log("[LCP] IO RESUMED"); }
    private void checkCancelled() throws IOException { if (ioCancelled) throw new IOException("CANCELLED"); }

    /** Force Sync bit on next outbound frame (recovery). */
    public void forceSyncNext() { this.syncPending = true; log("[LCP] forceSyncNext()"); }

    /** Drain RX a bit (recovery). */
    private void drainRx(int ms) {
        long end = System.currentTimeMillis() + ms;
        byte[] buf = new byte[256];
        while (System.currentTimeMillis() < end) {
            try {
                int n = port.read(buf, 30);
                if (n <= 0) break;
            } catch (Exception e) {
                break;
            }
        }
    }

    /** Open poll window: owner=ANY by default. */
    public void openPollWindow() {
        this.pollOwner = null; // ANY thread
        this.pollingBlocked = false;
        log("[LCP] PollWindow OPEN (owner=ANY) caller=" + callerTop());
    }

    /** Open poll window bound to current thread (debug). */
    public void openPollWindowExclusive() {
        this.pollOwner = Thread.currentThread();
        this.pollingBlocked = false;
        log("[LCP] PollWindow OPEN by " + this.pollOwner.getName() + " caller=" + callerTop());
    }

    public void closePollWindow() {
        this.pollingBlocked = true;
        Thread owner = this.pollOwner;
        this.pollOwner = null;
        log("[LCP] PollWindow CLOSE (prevOwner=" + (owner != null ? owner.getName() : "ANY") + ") caller=" + callerTop());
    }

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
        if(DUMP_TX) log("TX: " + hex(fr));
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
        if(DUMP_RX) log("RX: " + hex(pf.rawFrame));

        lastFrame.set(pf);
        metrics.rxFrames.incrementAndGet();
        return pf.rawFrame;
    }

    // ======================= Status / Payload extract ====================
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

    // =============================== I/O =================================
    public byte[] sendRecv(byte[] payload, int timeoutMs) throws IOException {
        checkCancelled();

        globalPortLock.lock();
        try {
            synchronized (portLock) {
                synchronized (txRxLock) {

                    long now = System.currentTimeMillis();
                    if (lastExchangeFinishedAtMs == 0L) lastExchangeFinishedAtMs = now;
                    long since = now - lastExchangeFinishedAtMs;
                    metrics.updateInterFrameDelta(since);

                    int pause = INTER_FRAME_PAUSE_MS + rnd(0, INTER_FRAME_JITTER_MS);
                    int sleepApplied = (since < pause) ? (pause - (int)since) : 0;
                    if (sleepApplied > 0) sleepMs(sleepApplied);

                    // 0x7D only allowed in pythonCompat and via opCheckRequest (guard)
                    if (payload != null && payload.length > 0 && (payload[0] & 0xFF) == MSG_CHECK_REQUEST) {
                        if (!pythonCompat) throw new IOException("0x7D forbidden (enable PythonCompat)");
                        if (INTERNAL_OK.get() == null || !INTERNAL_OK.get())
                            throw new IOException("0x7D reserved for opCheckRequest().");
                    }

                    // Guard: 0x23/0x28 only via op* wrappers (prevents random calls outside poll window discipline)
                    if (payload != null && payload.length > 0) {
                        int msg = payload[0] & 0xFF;
                        if ((msg == MSG_GET_MACHINE || msg == MSG_GET_DEL_STATUS)
                                && (INTERNAL_OK.get() == null || !INTERNAL_OK.get())) {
                            throw new IOException("Use opMachineStatusEx()/opMachineStatusFull()/opDeliveryStatus() for 0x23/0x28.");
                        }
                    }

                    // per-type throttles
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

                    // global coalesce (disabled in pythonCompat)
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

                    // purge only if requested
                    if (needPurge) {
                        try { port.purgeHwBuffers(true, true); } catch(Exception ignored) {}
                        needPurge = false;
                        log("[LCP] Purge applied");
                    }

                    byte[] fr = buildFrame(payload);
                    port.write(fr, timeoutMs);
                    metrics.txFrames.incrementAndGet();

                    byte[] rsp = readFrame(timeoutMs);
                    lastExchangeFinishedAtMs = System.currentTimeMillis();

                    if (DUMP_TX) log(String.format("IFΔ=%dms, sleep=%dms", (since < 0 ? 0 : since), sleepApplied));
                    return rsp;
                }
            }
        } finally {
            globalPortLock.unlock();
        }
    }

    // ===================== wait queued (non-python) ======================
    private byte[] waitQueued(int timeoutMs, int pollMs) throws IOException {
        long tEnd = System.currentTimeMillis() + timeoutMs;
        byte[] last = null;
        metrics.queuedWaits.incrementAndGet();

        while (System.currentTimeMillis() < tEnd) {
            checkCancelled();
            byte[] rsp = readFrame(Math.max(1200, pollMs + 800));
            byte[] p = extractPayload(rsp);
            LcpStatus st = extractStatus(rsp);
            if (st.busy) metrics.busyStatusFrames.incrementAndGet();
            if (p != null && p.length > 0) last = p;

            if (st.busy || p == null || p.length == 0) { sleep(pollMs); continue; }

            int rc = p[0] & 0xFF;
            if (rc == RC_REQUEST_ABORTED) throw new IOException("Queue aborted");
            if (rc == RC_REQUEST_QUEUED || rc == RC_NO_REQUEST_ACTIVE) {
                if (rc == RC_REQUEST_QUEUED) metrics.rc26.incrementAndGet();
                sleep(pollMs);
                continue;
            }

            return p;
        }

        throw new IOException("Queued timeout last=" + hex(last));
    }

    // ====================== queued via 0x7D (pythonCompat) ======================

    /** Check Request (0x7D) */
    public byte[] opCheckRequest() throws IOException {
        if (!pythonCompat) throw new IOException("opCheckRequest requires PythonCompat");

        INTERNAL_OK.set(Boolean.TRUE);
        try {
            checkCancelled();
            int to = Math.max(3000, minPollMs + 1200);
            return sendRecv(new byte[]{ (byte)MSG_CHECK_REQUEST }, to);
        } finally {
            INTERNAL_OK.remove();
        }
    }

    /** Robust wait for queued response via 0x7D; ignores rc=0x27 and retries on framing timeouts. */
    private byte[] waitQueuedPython(int timeoutMs) throws IOException {
        long tEnd = System.currentTimeMillis() + timeoutMs;
        int consecutiveFramingTimeouts = 0;

        while (System.currentTimeMillis() < tEnd) {
            checkCancelled();

            byte[] rsp;
            try {
                rsp = opCheckRequest();
                consecutiveFramingTimeouts = 0;
            } catch (IOException io) {
                if (isFramingTimeout(io)) {
                    consecutiveFramingTimeouts++;
                    log("[LCP] waitQueuedPython: 0x7D framing-timeout -> retry (" + consecutiveFramingTimeouts + ")");
                    drainRx(120);
                    forceSyncNext();
                    if (consecutiveFramingTimeouts >= 3) {
                        requestPurge();
                        consecutiveFramingTimeouts = 0;
                    }
                    sleep(minPollMs);
                    continue;
                }
                throw io;
            }

            byte[] p = extractPayload(rsp);
            if (p == null || p.length == 0) { sleep(minPollMs); continue; }

            int rc = p[0] & 0xFF;
            if (rc == RC_REQUEST_ABORTED) throw new IOException("Queued aborted");

            if (rc == RC_REQUEST_QUEUED) { sleep(minPollMs); continue; }

            // IMPORTANT: rc=0x27 => no queued response ready; keep polling
            if (rc == RC_NO_REQUEST_ACTIVE) { sleep(minPollMs); continue; }

            return p;
        }

        throw new IOException("Queued timeout (python)");
    }

    private static boolean isFramingTimeout(IOException io) {
        String m = (io.getMessage() == null) ? "" : io.getMessage();
        return m.contains("Timeout sync ~~")
                || m.contains("Header timeout")
                || m.contains("Payload timeout")
                || m.contains("CRC timeout");
    }

    // ============================== op* =================================

    public byte[] opGetField(int field) throws IOException {
        byte[] req = new byte[]{ (byte)MSG_GET_FIELD, (byte)field };

        byte[] rsp = sendRecv(req, 2500);
        LcpStatus st = extractStatus(rsp);
        byte[] p = extractPayload(rsp);

        if (pythonCompat) {
            if (st.busy || p == null || p.length == 0
                    || ((p[0] & 0xFF) == RC_REQUEST_QUEUED || (p[0] & 0xFF) == RC_NO_REQUEST_ACTIVE)) {
                p = waitQueuedPython(7000);
            }
        } else {
            if (st.busy || p == null || p.length == 0) p = waitQueued(7000, 150);
            if ((p[0] & 0xFF) == RC_REQUEST_QUEUED || (p[0] & 0xFF) == RC_NO_REQUEST_ACTIVE) p = waitQueued(7000, 150);
        }

        // Return from Get Field Data: rc, devStatus, fieldData...
        if (p == null || p.length < 2 || (p[0] & 0xFF) != RC_OK) throw new IOException("GET_FIELD #" + field);
        return Arrays.copyOfRange(p, 2, p.length);
    }

    public void opSetField(int field, byte[] data) throws IOException {
        byte[] pl = new byte[2 + data.length];
        pl[0] = (byte)MSG_SET_FIELD;
        pl[1] = (byte)field;
        System.arraycopy(data, 0, pl, 2, data.length);

        byte[] rsp = sendRecv(pl, 3000);
        LcpStatus st = extractStatus(rsp);
        byte[] p = extractPayload(rsp);

        if (pythonCompat) {
            if (st.busy || p == null || p.length == 0
                    || ((p[0] & 0xFF) == RC_REQUEST_QUEUED || (p[0] & 0xFF) == RC_NO_REQUEST_ACTIVE)) {
                p = waitQueuedPython(9000);
            }
        } else {
            if (st.busy || p == null || p.length == 0) p = waitQueued(9000, 150);
            if ((p[0] & 0xFF) == RC_REQUEST_QUEUED || (p[0] & 0xFF) == RC_NO_REQUEST_ACTIVE) p = waitQueued(9000, 150);
        }

        // Return from Set Field Data: rc, devStatus
        if (p == null || p.length < 1 || (p[0] & 0xFF) != RC_OK) throw new IOException("SET_FIELD #" + field);
    }

    public byte[] opIssueCommand(int cmd) throws IOException {
        metrics.opIssueCommandCalls.incrementAndGet();
        byte[] req = new byte[]{ (byte)MSG_ISSUE_COMMAND, (byte)cmd };

        byte[] rsp = sendRecv(req, 3500);
        LcpStatus st = extractStatus(rsp);
        byte[] p = extractPayload(rsp);

        if (pythonCompat) {
            if (st.busy || p == null || p.length == 0
                    || ((p[0] & 0xFF) == RC_REQUEST_QUEUED || (p[0] & 0xFF) == RC_NO_REQUEST_ACTIVE)) {
                p = waitQueuedPython(12000);
            }
        } else {
            if (st.busy || p == null || p.length == 0) p = waitQueued(12000, 150);
            if ((p[0] & 0xFF) == RC_REQUEST_QUEUED || (p[0] & 0xFF) == RC_NO_REQUEST_ACTIVE) p = waitQueued(12000, 150);
        }

        if (p == null || p.length < 1 || (p[0] & 0xFF) != RC_OK) throw new IOException("CMD 0x" + Integer.toHexString(cmd));
        sleepMs(80 + rnd(0, 40));
        return p;
    }

    /** MsgID 0x22: Print Text on LCR Printer */
    public byte[] opPrintText(byte[] text) throws IOException {
        if (text == null) text = new byte[0];
        byte[] req = new byte[1 + text.length];
        req[0] = (byte)MSG_PRINT_TEXT;
        System.arraycopy(text, 0, req, 1, text.length);

        byte[] rsp = sendRecv(req, 6000);
        LcpStatus st = extractStatus(rsp);
        byte[] p = extractPayload(rsp);

        if (pythonCompat) {
            if (st.busy || p == null || p.length == 0
                    || ((p[0] & 0xFF) == RC_REQUEST_QUEUED || (p[0] & 0xFF) == RC_NO_REQUEST_ACTIVE)) {
                p = waitQueuedPython(15000);
            }
        } else {
            if (st.busy || p == null || p.length == 0) p = waitQueued(15000, 200);
            if ((p[0] & 0xFF) == RC_REQUEST_QUEUED || (p[0] & 0xFF) == RC_NO_REQUEST_ACTIVE) p = waitQueued(15000, 200);
        }

        if (p == null || p.length < 1 || (p[0] & 0xFF) != RC_OK) throw new IOException("PRINT_TEXT");
        return p;
    }

    /** MsgID 0x00: Get Product ID */
    public byte[] opGetProductId() throws IOException {
        byte[] req = new byte[]{ (byte)MSG_GET_PRODUCTID };
        byte[] rsp = sendRecv(req, 3000);
        byte[] p = extractPayload(rsp);

        if (p == null || p.length < 2 || (p[0] & 0xFF) != RC_OK) throw new IOException("GET_PRODUCT_ID");
        return p;
    }

    // ============================== 0x23 / 0x28 ==============================

    /**
     * Printer status bits (prnStatus byte) are defined in the spec:
     * 0x01 delivery request, 0x02 shift request, 0x04 diag request, 0x08 user request,
     * 0x10 out of paper, 0x20 no processor online, 0x40 processor error, 0x80 begun to print.
     */
    public static final class PrinterStatus {
        public final int raw;
        public final boolean reqDelivery;
        public final boolean reqShift;
        public final boolean reqDiag;
        public final boolean reqUser;
        public final boolean outOfPaper;
        public final boolean noProcessor;
        public final boolean processorError;
        public final boolean printingStarted;

        public PrinterStatus(int raw) {
            this.raw = raw & 0xFF;
            this.reqDelivery     = (this.raw & 0x01) != 0;
            this.reqShift        = (this.raw & 0x02) != 0;
            this.reqDiag         = (this.raw & 0x04) != 0;
            this.reqUser         = (this.raw & 0x08) != 0;
            this.outOfPaper      = (this.raw & 0x10) != 0;
            this.noProcessor     = (this.raw & 0x20) != 0;
            this.processorError  = (this.raw & 0x40) != 0;
            this.printingStarted = (this.raw & 0x80) != 0;
        }

        public String summary() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("0x%02X", raw));
            if (outOfPaper) sb.append(" OUT_OF_PAPER");
            if (noProcessor) sb.append(" NO_PROCESSOR");
            if (processorError) sb.append(" PROCESSOR_ERROR");
            if (printingStarted) sb.append(" PRINTING_STARTED");
            if (reqDelivery) sb.append(" REQ_DELIVERY");
            if (reqShift) sb.append(" REQ_SHIFT");
            if (reqDiag) sb.append(" REQ_DIAG");
            if (reqUser) sb.append(" REQ_USER");
            return sb.toString().trim();
        }
    }

    public static PrinterStatus decodePrinterStatusByte(int prnStatus) {
        return new PrinterStatus(prnStatus);
    }

    public static final class MachineStatusEx {
        public final int devStatus;   // 1 byte
        public final int prnStatus;   // 1 byte
        public final int delStatus;   // u16
        public final int delCode;     // u16

        public MachineStatusEx(int devStatus, int prnStatus, int delStatus, int delCode) {
            this.devStatus = devStatus & 0xFF;
            this.prnStatus = prnStatus & 0xFF;
            this.delStatus = delStatus & 0xFFFF;
            this.delCode = delCode & 0xFFFF;
        }

        public PrinterStatus printer() { return decodePrinterStatusByte(prnStatus); }

        @Override public String toString() {
            return String.format("dev=0x%02X prn=%s ds=0x%04X dc=0x%04X",
                    devStatus, printer().summary(), delStatus, delCode);
        }
    }

    /** Get Machine Status (0x23): returns printer status byte; may delay if printer offline. */
    public MachineStatusEx opMachineStatusEx() throws IOException {
        if (pollingBlocked) throw new IOException("POLL_BLOCKED");
        if (pollOwner != null && Thread.currentThread() != pollOwner)
            throw new IOException("POLL_OWNER_MISMATCH");

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
                // 0x23 can take ~2s if printer offline => slightly higher timeout
                byte[] rsp = sendRecv(new byte[]{ (byte)MSG_GET_MACHINE }, 3500);
                LcpStatus st = extractStatus(rsp);
                byte[] p = extractPayload(rsp);

                if (pythonCompat) {
                    if (st.busy || p == null || p.length == 0
                            || ((p[0] & 0xFF) == RC_REQUEST_QUEUED || (p[0] & 0xFF) == RC_NO_REQUEST_ACTIVE)) {
                        p = waitQueuedPython(9000);
                    }
                } else {
                    if (st.busy || p == null || p.length == 0) p = waitQueued(9000, 200);
                    if ((p[0] & 0xFF) == RC_REQUEST_QUEUED || (p[0] & 0xFF) == RC_NO_REQUEST_ACTIVE) p = waitQueued(9000, 200);
                }

                // payload: rc(1), devStatus(1), prnStatus(1), delStatus(2), delCode(2) => 7
                if (p == null || p.length < 7 || (p[0] & 0xFF) != RC_OK) {
                    throw new IOException("Invalid 0x23 payload");
                }

                int dev = p[1] & 0xFF;
                int prn = p[2] & 0xFF;
                int ds  = u16be(p, 3);
                int dc  = u16be(p, 5);

                return new MachineStatusEx(dev, prn, ds, dc);

            } finally {
                INTERNAL_OK.remove();
            }
        }
    }

    /** Backward-compatible helper: returns {devStatusByte, delStatus, delCode}. */
    public int[] opMachineStatusFull() throws IOException {
        MachineStatusEx ms = opMachineStatusEx();
        return new int[]{ ms.devStatus, ms.delStatus, ms.delCode };
    }

    /** Get Delivery Status (0x28): no prnStatus (fast). Returns {delStatus, delCode}. */
    public int[] opDeliveryStatus() throws IOException {
        if (pollingBlocked) throw new IOException("POLL_BLOCKED");
        if (pollOwner != null && Thread.currentThread() != pollOwner)
            throw new IOException("POLL_OWNER_MISMATCH");

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
                byte[] rsp = sendRecv(new byte[]{ (byte)MSG_GET_DEL_STATUS }, 2500);
                LcpStatus st = extractStatus(rsp);
                byte[] p = extractPayload(rsp);

                if (pythonCompat) {
                    if (st.busy || p == null || p.length == 0
                            || ((p[0] & 0xFF) == RC_REQUEST_QUEUED || (p[0] & 0xFF) == RC_NO_REQUEST_ACTIVE)) {
                        p = waitQueuedPython(7000);
                    }
                } else {
                    if (st.busy || p == null || p.length == 0) p = waitQueued(7000, 150);
                    if ((p[0] & 0xFF) == RC_REQUEST_QUEUED || (p[0] & 0xFF) == RC_NO_REQUEST_ACTIVE) p = waitQueued(7000, 150);
                }

                // payload: rc(1), devStatus(1), delStatus(2), delCode(2) => 6
                if (p == null || p.length < 6 || (p[0] & 0xFF) != RC_OK) throw new IOException("Invalid 0x28 payload");
                int ds = u16be(p, 2);
                int dc = u16be(p, 4);
                return new int[]{ ds, dc };

            } finally {
                INTERNAL_OK.remove();
            }
        }
    }

    // ============================== Utils ================================
    private static int u16be(byte[] b, int off){ return ((b[off] & 0xFF) << 8) | (b[off+1] & 0xFF); }

    private static void sleep(int ms){ try { Thread.sleep(ms); } catch(Exception ignored){} }

    private static int rnd(int min, int max){
        if (max <= 0) return min;
        return min + (int)(Math.random() * (max + 1));
    }

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

        void updateInterFrameDelta(long d){
            if(d < 0) d = 0;
            synchronized(lock){
                if(d < interFrameDeltaMinMs) interFrameDeltaMinMs = d;
                if(d > interFrameDeltaMaxMs) interFrameDeltaMaxMs = d;
                interFrameDeltaEmaMs = ema(interFrameDeltaEmaMs, d, EMA_ALPHA);
            }
        }

        void updatePollInterval(){
            long now = System.currentTimeMillis();
            synchronized(lock){
                if(lastPollTs != 0){
                    long d = now - lastPollTs;
                    if(d < 0) d = 0;
                    pollIntervalEmaMs = ema(pollIntervalEmaMs, d, EMA_ALPHA);
                }
                lastPollTs = now;
            }
        }

        void reset(){
            txFrames.set(0); rxFrames.set(0); rc26.set(0); busyStatusFrames.set(0);
            queuedWaits.set(0); queuedWaitTimeMs.set(0);
            opGetMachineCalls.set(0); opDeliveryStatusCalls.set(0); opIssueCommandCalls.set(0);
            synchronized(lock){
                interFrameDeltaMinMs = Long.MAX_VALUE; interFrameDeltaMaxMs = 0L;
                interFrameDeltaEmaMs = -1.0; lastPollTs = 0L; pollIntervalEmaMs = -1.0;
            }
        }

        MetricsSnapshot snapshot(){
            synchronized(lock){
                long min = (interFrameDeltaMinMs == Long.MAX_VALUE) ? -1 : interFrameDeltaMinMs;
                return new MetricsSnapshot(
                        txFrames.get(), rxFrames.get(), rc26.get(), busyStatusFrames.get(),
                        queuedWaits.get(), queuedWaitTimeMs.get(),
                        opGetMachineCalls.get(), opDeliveryStatusCalls.get(), opIssueCommandCalls.get(),
                        min, interFrameDeltaMaxMs, interFrameDeltaEmaMs, pollIntervalEmaMs
                );
            }
        }

        private static double ema(double prev, double v, double a){
            return (prev < 0) ? v : (a * v + (1 - a) * prev);
        }
    }

    public static final class MetricsSnapshot {
        public final long txFrames, rxFrames, rc26, busyStatusFrames, queuedWaits, queuedWaitTimeMs,
                opGetMachineCalls, opDeliveryStatusCalls, opIssueCommandCalls,
                interFrameDeltaMinMs, interFrameDeltaMaxMs;
        public final double interFrameDeltaEmaMs, pollIntervalEmaMs;

        private MetricsSnapshot(long tx,long rx,long rc26,long busy,long q,long qms,long gm,long ds,long ic,
                                long dmin,long dmax,double dema,double pema){
            this.txFrames=tx; this.rxFrames=rx; this.rc26=rc26; this.busyStatusFrames=busy;
            this.queuedWaits=q; this.queuedWaitTimeMs=qms;
            this.opGetMachineCalls=gm; this.opDeliveryStatusCalls=ds; this.opIssueCommandCalls=ic;
            this.interFrameDeltaMinMs=dmin; this.interFrameDeltaMaxMs=dmax;
            this.interFrameDeltaEmaMs=dema; this.pollIntervalEmaMs=pema;
        }

        @Override public String toString() {
            return String.format(
                    "LcpLinkMetrics{tx=%d, rx=%d, rc26=%d, busy=%d, queuedWaits=%d, queuedWaitTimeMs=%d, " +
                            "getMachine=%d, getDelStatus=%d, issueCmd=%d, IFΔ[min=%dms,max=%dms,ema=%.1fms], pollEMA=%.1fms}",
                    txFrames, rxFrames, rc26, busyStatusFrames, queuedWaits, queuedWaitTimeMs,
                    opGetMachineCalls, opDeliveryStatusCalls, opIssueCommandCalls,
                    interFrameDeltaMinMs, interFrameDeltaMaxMs, interFrameDeltaEmaMs, pollIntervalEmaMs
            );
        }
    }
}
