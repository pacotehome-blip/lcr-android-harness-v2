
package com.pa.lcr.lcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

import com.pa.lcr.lcp.lifecycle.Cmd0Usage;
import com.pa.lcr.lcp.lifecycle.DeliveryLifecycleController;
import com.pa.lcr.lcp.lifecycle.LcpDeliveryState;
import com.pa.lcr.lcp.util.AndroidLifecycleLogger;

public class DeliveryController {

    // ------------------------- Champs LCR (métier) -------------------------
    private static final int FIELD_PRODUCT_NUMBER = 0;   // ProductNumber (LIST+0)
    private static final int FIELD_NET_PRESET = 6;       // NetPreset (VOLUME/LV)
    private static final int FIELD_DECIMALS = 39;        // Decimals
    private static final int FIELD_GROSS_COUNT = 44;     // GrossCount (VOLUME/LV)
    private static final int FIELD_NET_COUNT = 45;       // NetCount (VOLUME/LV)
    private static final int FIELD_CLEAR_SHIFT = 16;     // ClearShift
    private static final int FIELD_TICKET_NUMBER = 23;   // TicketNumber (LONG/SL)
    private static final int FIELD_TICKET_REQUIRED = 37; // TicketRequired (0/1/2)

    // >>> NoFlowTimer (#25) pour confirmer flow stopped (Python-like)
    private static final int FIELD_NO_FLOW_TIMER = 25;   // seconds

    // ------------------------- Robustesse terrain -------------------------
    private static final int QUEUED_LONG_TIMEOUT_MS = 30_000; // 30s
    private static final int SETFIELD_RETRY_SLEEP_MS = 120;

    // ------------------------- Dépendances -------------------------
    private final LcpLink link;
    private final DeliveryEvents events;
    private final ExecutorService exec;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> liveLoopFuture;

    // [DL] Garde-fou DeliveryLifecycle
    private final DeliveryLifecycleController lifecycle =
            new DeliveryLifecycleController(new AndroidLifecycleLogger());

    // ------------------------- État -------------------------
    public enum State { IDLE, PRESTART, STARTING, RUNNING, ENDING, ENDED, ERROR }
    private volatile State state = State.IDLE;
    private volatile boolean stopping = false;
    private volatile boolean pollWindowOpen = false;

    // Mesures
    private volatile long startTimestampMs = 0;
    private volatile double startGross = 0;
    private volatile double startNet = 0;
    private volatile double lastGross = 0;
    private volatile double lastNet = 0;

    // Cache décimales (#39)
    private volatile int cachedDecimals = -1;

    // Flow transitions
    private volatile Boolean lastFlow = null;

    // Derniers paramètres
    private volatile int presetProduct = 1;
    private volatile double presetLitres = 0;

    // ==========================================================
    // Confirmation flow stopped (basée sur NoFlowTimer #25)
    // ==========================================================
    private static final long FLOW_STOP_CONFIRM_MIN_MS = 2000; // garde-fou
    private volatile long flowStopConfirmMs = 3000;

    private volatile boolean flowStopPending = false;
    private volatile boolean flowStopNotified = false;
    private volatile long flowStopSinceMs = 0;
    private volatile long lastVolumeChangeMs = 0;
    private volatile double lastStableGross = 0;
    private volatile double lastStableNet = 0;

    // ============================= EVENTS =============================
    public interface DeliveryEvents {
        void onStateChanged(State s);
        void onFlowStarted();
        void onFlowStopped();
        void onProgress(DeliveryProgress p);
        void onTicketNumber(int ticketNumber);
        void onTicketRequired(int mode);
        void onPrinterStatus(LcpLink.MachineStatusEx ms, boolean ticketPending);
        void onError(String msg, Throwable t);
        void onLog(String line);
        void onOperatorAlert(OperatorAlert alert);
    }

    public static final class DeliveryProgress {
        public long tSinceStartMs;
        public double grossL;
        public double netL;
        public double deliveredGrossL;
        public double deliveredNetL;
        public double dGrossL;
        public double dNetL;
        public boolean flowActive;
        public boolean stalled;
        public int ds;
        public int dc;

        // ✅ Correctif: état delivery "officiel" (doc SDK)
        public LcpDeliveryState deliveryState;
    }

    // ============================= OPERATOR ALERTS (terrain) =============================
    public enum OperatorIssueCode {
        RECOVERY_END_TIMEOUT,
        RECOVERY_FLOW_STUCK_ACTIVE,
        PRINTER_NOT_READY,
        PRINT_TIMEOUT_TICKET_PENDING,
        PRINT_FORBIDDEN_DELIVERY_ACTIVE,
        IO_OR_PROTOCOL_ERROR,
        WRITE_OR_VERIFY_FAILED
    }

    public static final class OperatorAlert {
        public final OperatorIssueCode code;
        public final String title;
        public final String message;
        public final String diagnostics;
        public final boolean blocking;

        public OperatorAlert(OperatorIssueCode code, String title, String message, String diagnostics, boolean blocking) {
            this.code = code;
            this.title = title;
            this.message = message;
            this.diagnostics = diagnostics;
            this.blocking = blocking;
        }
    }

    public DeliveryController(LcpLink link, DeliveryEvents cb, ExecutorService svc) {
        this.link = link;
        this.events = cb;
        this.exec = svc;
    }

    // ============================= Helpers =============================
    private void log(String s){ if (events != null) events.onLog(s); }

    private void setState(State s) {
        this.state = s;
        if (events != null) events.onStateChanged(s);
    }

    private void safeOp(Runnable r, String tag){
        try { r.run(); }
        catch(Exception e){
            setState(State.ERROR);
            if(events!=null) events.onError(tag,e);
        }
    }

    private void emitOperatorAlert(OperatorIssueCode code, String title, String message, String diagnostics, boolean blocking) {
        log("[ALERT] " + code + " - " + title);
        if (events != null) events.onOperatorAlert(new OperatorAlert(code, title, message, diagnostics, blocking));
    }

    private String diagDsDc(int ds, int dc) {
        boolean tp = (dc & LcpLink.LCRSc_DEL_TICKET_PENDING) != 0;
        boolean fa = (dc & LcpLink.LCRSc_FLOW_ACTIVE) != 0;
        boolean da = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;
        boolean begin = (dc & LcpLink.LCRSc_BEGIN_DELIVERY) != 0;
        return "DS=" + String.format("0x%04X", ds) +
                " DC=" + String.format("0x%04X", dc) +
                " [TICKET_PENDING=" + tp +
                " FLOW_ACTIVE=" + (fa ? "1" : "0") +
                " DELIVERY_ACTIVE=" + (da ? "1" : "0") +
                " BEGIN_DELIVERY=" + begin + "]";
    }

    /** Ouvre une poll window temporaire si nécessaire (évite POLL_BLOCKED). */
    private <T> T withPollWindow(Callable<T> op) throws Exception {
        boolean openedHere = false;
        if (!pollWindowOpen) {
            link.openPollWindow(); // owner=ANY
            pollWindowOpen = true;
            openedHere = true;
        }
        try {
            return op.call();
        } finally {
            if (openedHere) {
                try { link.closePollWindow(); } catch (Exception ignored) {}
                pollWindowOpen = false;
            }
        }
    }

    private static String hx16(int v) { return String.format("0x%04X", (v & 0xFFFF)); }

    private static String dcFlags(int dc) {
        boolean pending = (dc & LcpLink.LCRSc_DEL_TICKET_PENDING) != 0;
        boolean flow = (dc & LcpLink.LCRSc_FLOW_ACTIVE) != 0;
        boolean active = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;
        boolean begin = (dc & LcpLink.LCRSc_BEGIN_DELIVERY) != 0;
        return String.format("[ticketPending=%s flow=%s active=%s begin=%s]", pending, flow, active, begin);
    }

    private void logDsDc(String tag, int ds, int dc) {
        log(String.format("[%s] DS=%s DC=%s %s", tag, hx16(ds), hx16(dc), dcFlags(dc)));
    }

    private int[] readDsDcFast() throws Exception {
        return withPollWindow(() -> link.opDeliveryStatus()); // [ds, dc]
    }

    private int[] readDsDcFastLong(int pollMs) throws Exception {
        long deadline = System.currentTimeMillis() + QUEUED_LONG_TIMEOUT_MS;
        Exception last = null;

        while (System.currentTimeMillis() < deadline) {
            try {
                return readDsDcFast();
            } catch (Exception e) {
                last = e;
                String m = e.getMessage();
                if (m != null && m.contains("Queued timeout (python)")) {
                    Thread.sleep(Math.max(50, pollMs));
                    continue;
                }
                Thread.sleep(Math.max(50, pollMs));
            }
        }

        if (last != null) throw last;
        throw new IOException("readDsDcFastLong: timeout");
    }

    private void logTimeline(String step, int pollMs) {
        try {
            link.setPythonCompat(true, pollMs);
            int[] dsdc = readDsDcFast();
            logDsDc("TL:" + step, dsdc[0], dsdc[1]);
        } catch (Exception e) {
            log("[TL:" + step + "] WARN: cannot read DS/DC: " + e.getMessage());
        }
    }

    // ✅ Correctif: compute "DeliveryState" (doc SDK) depuis DC bits
    // Doc: ticket pending 0x0001, shift pending 0x0002, flow active 0x0004, delivery active 0x0008. [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/Android%20SDK%20Documentation-b0.14.pdf)
    public static LcpDeliveryState computeDeliveryStateFromDc(int dc) {
        boolean ticketPending = (dc & LcpLink.LCRSc_DEL_TICKET_PENDING) != 0;
        boolean shiftPending  = (dc & 0x0002) != 0; // doc SDK: shift ticket requested bit. [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/Android%20SDK%20Documentation-b0.14.pdf)
        boolean flowActive    = (dc & LcpLink.LCRSc_FLOW_ACTIVE) != 0;
        boolean deliveryActive= (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;

        if (deliveryActive) {
            return flowActive ? LcpDeliveryState.ACTIVE_FLOWING : LcpDeliveryState.ACTIVE_PAUSED;
        }
        if (ticketPending) return LcpDeliveryState.PENDING_TICKET;
        if (shiftPending)  return LcpDeliveryState.PENDING_SHIFT;
        return LcpDeliveryState.IDLE;
    }

    private static int productToList0Value(int product) {
        if (product < 1 || product > 16) throw new IllegalArgumentException("product must be 1..16");
        return product - 1;
    }

    private int getDecimals() throws Exception {
        if (cachedDecimals >= 0) return cachedDecimals;

        Exception last = null;
        for (int i = 1; i <= 5; i++) {
            try {
                byte[] d = link.opGetField(FIELD_DECIMALS); // #39
                cachedDecimals = (d != null && d.length >= 1) ? (d[0] & 0xFF) : 0;
                return cachedDecimals;
            } catch (Exception e) {
                last = e;
                log("[DECIMALS] WARN read #39 failed (" + i + "/5): " + e.getMessage());
                try {
                    link.forceSyncNext();
                    if (i >= 3) link.requestPurge();
                } catch (Exception ignored) {}
                try { Thread.sleep(SETFIELD_RETRY_SLEEP_MS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }

        if (last != null) throw last;
        throw new IOException("getDecimals: failed");
    }

    private int getNoFlowTimerSecondsSafe(int pollMs) {
        try {
            link.setPythonCompat(true, pollMs);
            byte[] raw = link.opGetField(FIELD_NO_FLOW_TIMER); // #25
            if (raw != null && raw.length >= 1) return (raw[0] & 0xFF);
        } catch (Exception ignored) {}
        return -1;
    }

    private static int scaleForDecimalsIndex(int decimalsIndex) {
        if (decimalsIndex == 1) return 10;
        if (decimalsIndex == 2) return 1;
        if (decimalsIndex == 3) return 1000;
        return 100;
    }

    private static byte[] encodeVolume(double litres, int decimalsIndex) {
        int scale = scaleForDecimalsIndex(decimalsIndex);
        long raw = Math.round(litres * scale);
        int v = (int) raw;
        return new byte[] {
                (byte)((v >> 24) & 0xFF),
                (byte)((v >> 16) & 0xFF),
                (byte)((v >> 8) & 0xFF),
                (byte)( v & 0xFF)
        };
    }

    private static double decodeVolume(byte[] b4, int decimalsIndex) {
        if (b4 == null || b4.length < 4) return 0.0;
        int v = ((b4[0] & 0xFF) << 24)
                | ((b4[1] & 0xFF) << 16)
                | ((b4[2] & 0xFF) << 8)
                | (b4[3] & 0xFF);
        int scale = scaleForDecimalsIndex(decimalsIndex);
        return v / (double) scale;
    }

    private double readGrossLitres() throws Exception {
        int dec = getDecimals();
        return decodeVolume(link.opGetField(FIELD_GROSS_COUNT), dec); // #44
    }

    private double readNetLitres() throws Exception {
        int dec = getDecimals();
        return decodeVolume(link.opGetField(FIELD_NET_COUNT), dec); // #45
    }

    private static int s32be(byte[] b4) {
        if (b4 == null || b4.length < 4) return 0;
        return ((b4[0] & 0xFF) << 24)
                | ((b4[1] & 0xFF) << 16)
                | ((b4[2] & 0xFF) << 8)
                | (b4[3] & 0xFF);
    }

    private int readTicketNumber() throws Exception {
        return s32be(link.opGetField(FIELD_TICKET_NUMBER)); // #23
    }

    private int readTicketRequired() throws Exception {
        byte[] raw = link.opGetField(FIELD_TICKET_REQUIRED); // #37
        return (raw != null && raw.length > 0) ? (raw[0] & 0xFF) : 0;
    }

    private static boolean isFramingTimeoutMsg(String msg) {
        if (msg == null) return false;
        return msg.contains("Timeout sync ~~")
                || msg.contains("Header timeout")
                || msg.contains("Payload timeout")
                || msg.contains("CRC timeout");
    }

    private static boolean isFramingTimeoutException(Throwable t) {
        if (t == null) return false;
        String m = t.getMessage();
        if (m != null && (m.startsWith("Framing timeout") || isFramingTimeoutMsg(m))) return true;
        Throwable c = t.getCause();
        if (c != null) {
            String cm = c.getMessage();
            if (cm != null && isFramingTimeoutMsg(cm)) return true;
        }
        return false;
    }

    private void safeSetFieldWithRetry(int field, byte[] data, int pollMs, int verifyLen) throws Exception {
        try {
            link.opSetField(field, data);
        } catch (IOException e) {
            if (!isFramingTimeoutException(e)) throw e;

            log("[SAFE-SET] Framing timeout on SET_FIELD #" + field + " -> recovery+retry once. msg=" + e.getMessage());
            link.forceSyncNext();
            link.requestPurge();
            Thread.sleep(SETFIELD_RETRY_SLEEP_MS);
            link.opSetField(field, data);
        }

        byte[] rb = link.opGetField(field);
        if (rb == null) throw new IOException("SET_FIELD verify failed: null");

        int n = Math.min(Math.min(verifyLen, data.length), rb.length);
        if (n <= 0) throw new IOException("SET_FIELD verify failed: empty");

        for (int i = 0; i < n; i++) {
            if (rb[i] != data[i]) {
                throw new IOException("SET_FIELD verify mismatch at i=" + i);
            }
        }
    }

    public void refreshPrinterStatus(int pollMs) {
        exec.execute(() -> safeOp(() -> {
            try {
                link.setPythonCompat(true, pollMs);
                LcpLink.MachineStatusEx ms = withPollWindow(() -> link.opMachineStatusEx());
                boolean pending = (ms.delCode & LcpLink.LCRSc_DEL_TICKET_PENDING) != 0;
                log("[PRN] " + ms.toString() + " ticketPending=" + pending);
                if (events != null) events.onPrinterStatus(ms, pending);
            } catch (Exception e) {
                if (events != null) events.onError("refreshPrinterStatus", e);
            }
        }, "refreshPrinterStatus"));
    }

    public void refreshTicketInfo(int pollMs) {
        exec.execute(() -> safeOp(() -> {
            try {
                link.setPythonCompat(true, pollMs);
                int tr = readTicketRequired();
                int tn = readTicketNumber();
                log("[TICKET] TicketRequired(#37)=" + tr + " (0=req,1=optional,2=never)");
                log("[TICKET] TicketNumber(#23)=" + tn + " (increments after printing)");
                if (events != null) {
                    events.onTicketRequired(tr);
                    events.onTicketNumber(tn);
                }
            } catch (Exception e) {
                if (events != null) events.onError("refreshTicketInfo", e);
            }
        }, "refreshTicketInfo"));
    }

    public void clearShiftNow(int pollMs) {
        exec.execute(() -> safeOp(() -> {
            try {
                link.setPythonCompat(true, pollMs);
                int tr = readTicketRequired();
                log("[SHIFT] TicketRequired(#37)=" + tr + " ; sending ClearShift(#16)=0");
                link.opSetField(FIELD_CLEAR_SHIFT, new byte[]{ 0x00 });
                log("[SHIFT] ClearShift(#16)=0 sent");
            } catch (Exception e) {
                if (events != null) events.onError("clearShiftNow", e);
            }
        }, "clearShiftNow"));
    }

    private boolean startGateAllow(int pollMs) throws Exception {
        int[] dsdc = readDsDcFast();
        int ds = dsdc[0], dc = dsdc[1];
        logDsDc("START-GATE", ds, dc);

        boolean pending = (dc & LcpLink.LCRSc_DEL_TICKET_PENDING) != 0;
        if (!pending) {
            log("[START-GATE] OK: ticketPending=false");
            return true;
        }

        log("[START-GATE] BLOCKED: ticketPending=true -> must print/clear last delivery ticket before starting");
        refreshPrinterStatus(pollMs);
        return false;
    }

    public void prestartSequence(int product, double presetLitres, int pollMs) throws Exception {
        setState(State.PRESTART);
        logTimeline("PRESTART:ENTER", pollMs);

        refreshPrinterStatus(pollMs);

        if (!startGateAllow(pollMs)) {
            setState(State.IDLE);
            throw new Exception("START-GATE blocked (ticketPending=true)");
        }

        link.setPythonCompat(true, pollMs);

        int list0 = productToList0Value(product);
        log("[PRE] Setting active product (Field #0) product=" + product + " (list0=" + list0 + ")");
        safeSetFieldWithRetry(FIELD_PRODUCT_NUMBER, new byte[]{ (byte)list0 }, pollMs, 1);
        this.presetProduct = product;

        this.presetLitres = presetLitres;
        if (presetLitres > 0.0) {
            int dec = getDecimals();
            log("[PRE] Setting NET preset (Field #6) value=" + presetLitres + " (decimalsIndex=" + dec + ")");
            safeSetFieldWithRetry(FIELD_NET_PRESET, encodeVolume(presetLitres, dec), pollMs, 4);
        }

        log("[PRE] Completed PRE-START");
    }

    public void startDeliverySequence(int pollMs) throws Exception {
        logTimeline("START:ENTER", pollMs);

        // [DL] Guard Cmd#0 START
        if (!lifecycle.allowCmd0(Cmd0Usage.START)) {
            throw new IllegalStateException("Cmd#0 START blocked by DeliveryLifecycle state=" + lifecycle.getState());
        }

        log("[START] RUN (Command #0)");
        link.opIssueCommand(0x00);
        setState(State.STARTING);

        boolean active = withPollWindow(() -> {
            long deadline = System.currentTimeMillis() + 12000;
            while (System.currentTimeMillis() < deadline) {
                int[] dsdc = link.opDeliveryStatus();
                int ds = dsdc[0];
                int dc = dsdc[1];

                boolean isActive = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;
                if (isActive) {
                    logDsDc("START:ACTIVE", ds, dc);
                    return true;
                }
                Thread.sleep(Math.max(50, pollMs));
            }
            return false;
        });

        if (!active) throw new Exception("Delivery not active after RUN");

        // [DL] START confirmé (aligné avec l'existant)
        lifecycle.onStartConfirmed(true);

        startTimestampMs = System.currentTimeMillis();
        startGross = readGrossLitres();
        startNet = readNetLitres();
        lastGross = startGross;
        lastNet = startNet;

        long now = System.currentTimeMillis();
        lastStableGross = startGross;
        lastStableNet = startNet;
        lastVolumeChangeMs = now;

        flowStopPending = false;
        flowStopNotified = false;
        flowStopSinceMs = 0;

        lastFlow = null;
        stopping = false;

        setState(State.RUNNING);
        log("[START] Delivery ACTIVE");
    }

    private double readVolumeQueuedAware(int field, int pollMs) throws Exception {
        long longDeadline = System.currentTimeMillis() + 30_000;
        Exception last = null;

        while (System.currentTimeMillis() < longDeadline) {
            try {
                int dec = getDecimals();
                byte[] raw = link.opGetField(field);
                return decodeVolume(raw, dec);
            } catch (Exception e) {
                last = e;
                String m = (e.getMessage() == null) ? "" : e.getMessage();
                boolean transientOrQueued =
                        m.contains("Queued timeout") ||
                        m.contains("POLL_BLOCKED") ||
                        m.contains("Timeout sync") ||
                        m.contains("Header timeout") ||
                        m.contains("Payload timeout") ||
                        m.contains("CRC timeout");

                if (!transientOrQueued) throw e;

                long sleep = Math.max(200, pollMs);
                try { Thread.sleep(sleep); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw ie; }
            }
        }

        if (last != null) throw last;
        throw new IOException("readVolumeQueuedAware: timeout");
    }

    public void startLiveLoop(int pollMs) {
        exec.execute(() -> safeOp(() -> {

            if (state != State.RUNNING) {
                log("[LIVE] ignored: state=" + state);
                return;
            }
            if (liveLoopFuture != null && !liveLoopFuture.isCancelled()) {
                log("[LIVE] already running");
                return;
            }

            int nft = getNoFlowTimerSecondsSafe(pollMs); // #25
            if (nft >= 0) {
                long ms = Math.max(FLOW_STOP_CONFIRM_MIN_MS, (long)nft * 1000L);
                flowStopConfirmMs = ms;
                log("[LIVE] FlowStopConfirm = NoFlowTimer(#25)=" + nft + "s => " + flowStopConfirmMs + "ms");
            } else {
                log("[LIVE] FlowStopConfirm = default " + flowStopConfirmMs + "ms (NoFlowTimer #25 unread)");
            }

            log("[LIVE] Starting live loop");

            liveLoopFuture = scheduler.scheduleAtFixedRate(() -> {
                try {
                    if (stopping || state != State.RUNNING) return;

                    int ds, dc;
                    try {
                        int[] dsdc = withPollWindow(() -> link.opDeliveryStatus());
                        ds = dsdc[0];
                        dc = dsdc[1];
                    } catch (Exception ex) {
                        return;
                    }

                    boolean flow = (dc & LcpLink.LCRSc_FLOW_ACTIVE) != 0;
                    boolean active = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;

                    if (!active) {
                        stopping = true;
                        flowStopPending = false;
                        flowStopNotified = false;
                        flowStopSinceMs = 0;
                        return;
                    }

                    double gross = lastGross;
                    double net = lastNet;
                    try { gross = readVolumeQueuedAware(FIELD_GROSS_COUNT, pollMs); } catch (Exception ignored) {}
                    try { net   = readVolumeQueuedAware(FIELD_NET_COUNT, pollMs); } catch (Exception ignored) {}

                    long now = System.currentTimeMillis();

                    boolean progressed = (Math.abs(gross - lastStableGross) > 1e-9) ||
                                         (Math.abs(net   - lastStableNet)   > 1e-9);
                    if (progressed) {
                        lastVolumeChangeMs = now;
                        lastStableGross = gross;
                        lastStableNet = net;
                    }

                    DeliveryProgress p = new DeliveryProgress();
                    p.tSinceStartMs = now - startTimestampMs;
                    p.grossL = gross;
                    p.netL = net;
                    p.deliveredGrossL = gross - startGross;
                    p.deliveredNetL = net - startNet;
                    p.dGrossL = gross - lastGross;
                    p.dNetL = net - lastNet;
                    p.flowActive = flow;
                    p.stalled = !flow;
                    p.ds = ds;
                    p.dc = dc;

                    // ✅ Correctif: DeliveryState officiel
                    p.deliveryState = computeDeliveryStateFromDc(dc);

                    lastGross = gross;
                    lastNet = net;

                    if (events != null) events.onProgress(p);

                    if (lastFlow == null) {
                        lastFlow = flow;
                        flowStopPending = false;
                        flowStopNotified = false;
                        flowStopSinceMs = 0;
                    }

                    if (flow && !lastFlow) {
                        flowStopPending = false;
                        flowStopNotified = false;
                        flowStopSinceMs = 0;
                        if (events != null) events.onFlowStarted();
                    }

                    if (!flow && lastFlow) {
                        flowStopPending = true;
                        flowStopNotified = false;
                        flowStopSinceMs = now;
                    }

                    if (!flow && active && flowStopPending && !flowStopNotified) {
                        long quietMs = now - lastVolumeChangeMs;
                        long sinceMs = now - flowStopSinceMs;
                        if (quietMs >= flowStopConfirmMs && sinceMs >= flowStopConfirmMs) {

                            // [DL] Pause confirmée (pas un glitch)
                            lifecycle.onPauseDetected();

                            flowStopNotified = true;
                            flowStopPending = false;
                            if (events != null) events.onFlowStopped();
                        }
                    }

                    lastFlow = flow;

                } catch (Exception e) {
                    setState(State.ERROR);
                    if (events != null) events.onError("liveLoop", e);
                }
            }, 0, pollMs, TimeUnit.MILLISECONDS);

        }, "startLiveLoop"));
    }

    public void endDeliverySequence(int timeoutMs, int pollMs) throws Exception {
        logTimeline("END:ENTER", pollMs);

        refreshPrinterStatus(pollMs);

        int trBefore = readTicketRequired();
        int tnBefore = readTicketNumber();
        log(String.format("[PRINT] Policy TicketRequired(#37)=%d (0=req,1=optional,2=never) TicketNumber(#23) before=%d",
                trBefore, tnBefore));

        if (!lifecycle.allowEnd()) {
            log("[END] blocked by DeliveryLifecycle state=" + lifecycle.getState());
            return;
        }

        log("[END] Issuing END (Command #2)");

        setState(State.ENDING);
        stopping = true;

        if (liveLoopFuture != null) {
            liveLoopFuture.cancel(true);
            liveLoopFuture = null;
        }

        link.setPythonCompat(true, pollMs);
        link.opIssueCommand(0x02);

        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            int[] dsdc;
            try {
                dsdc = readDsDcFast();
            } catch (Exception e) {
                String m = e.getMessage();
                if (m != null && m.contains("Queued timeout (python)")) {
                    Thread.sleep(Math.max(50, pollMs));
                    continue;
                }
                Thread.sleep(Math.max(50, pollMs));
                continue;
            }

            int dc = dsdc[1];
            boolean active = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;

            if (!active) break;
            Thread.sleep(pollMs);
        }

        refreshPrinterStatus(pollMs);

        int tnAfter = readTicketNumber();
        log(String.format("[PRINT] TicketNumber(#23) after=%d delta=%+d", tnAfter, (tnAfter - tnBefore)));
        if (tnAfter > tnBefore) {
            log("[PRINT] CONFIRMED: TicketNumber incremented => delivery ticket printed");
        } else {
            log("[PRINT] NOT CONFIRMED: TicketNumber did not increment (ticket may be pending or printer not ready)");
        }

        lifecycle.onEndConfirmed();
        lifecycle.onResetSyncCompleted();

        setState(State.ENDED);
    }

    public void startOpenMode(int product, double presetLitres, int timeoutMs, int pollMs){
        exec.execute(() -> safeOp(() -> {

            if (state == State.RUNNING || state == State.STARTING || state == State.PRESTART || state == State.ENDING) {
                log("[START] ignored: delivery already in progress state=" + state);
                return;
            }

            boolean ticketPending = false;
            try {
                int[] dsdc = readDsDcFast();
                ticketPending = (dsdc[1] & LcpLink.LCRSc_DEL_TICKET_PENDING) != 0;
            } catch (Exception ignored) {}

            if (!lifecycle.allowStart(ticketPending)) {
                log("[START] blocked by DeliveryLifecycle (ticketPending=" + ticketPending + ")");
                return;
            }

            stopping = false;
            cachedDecimals = -1;
            lastFlow = null;

            logTimeline("STARTOPEN:ENTER", pollMs);

            try { prestartSequence(product, presetLitres, pollMs); }
            catch (Exception ex) { if(events!=null) events.onError("prestartSequence", ex); return; }

            lifecycle.onPrestartConfirmed();

            try { startDeliverySequence(pollMs); }
            catch (Exception ex) { if(events!=null) events.onError("startDeliverySequence", ex); return; }

            startLiveLoop(pollMs);

        }, "startOpenMode"));
    }

    public void resumeDelivery(int pollMs) {
        exec.execute(() -> safeOp(() -> {
            try {
                link.setPythonCompat(true, pollMs);

                if (!lifecycle.allowCmd0(Cmd0Usage.RESUME)) {
                    log("[RESUME] Cmd#0 RESUME blocked by DeliveryLifecycle state=" + lifecycle.getState());
                    return;
                }
                if (!lifecycle.allowResume()) {
                    log("[RESUME] blocked by DeliveryLifecycle state=" + lifecycle.getState());
                    return;
                }

                logTimeline("RESUME:BEFORE", pollMs);

                link.opIssueCommand(0x00);
                log("[RESUME] Cmd#0 sent (Start/Resume)");

                lifecycle.onStartConfirmed(true);

                startLiveLoop(pollMs);

            } catch (Exception e) {
                if (events != null) events.onError("resumeDelivery", e);
            }
        }, "resumeDelivery"));
    }

    public void printText(String txt){
        exec.execute(() -> safeOp(() -> {
            try {
                byte[] data = (txt == null ? "" : txt).getBytes(StandardCharsets.US_ASCII);
                link.opPrintText(data);
            } catch (Exception ex) {
                if (events != null) events.onError("printText", ex);
            }
        }, "printText"));
    }
}
