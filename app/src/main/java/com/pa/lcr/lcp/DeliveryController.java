
package com.pa.lcr.lcp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DeliveryController
 *
 * Correctifs ARMED (API OneShot):
 * - ARMED n'est PAS "RUNNING_PAUSED" (car deliveryActive=0). On reste CONNECTED et on expose armed=1.
 * - api_deliveryContinue() doit envoyer RUN même si l'état UI n'est pas RUNNING_PAUSED (cas ARMED).
 * - api_deliveryJobGet(): si deliveryActive=0 et jamais actif -> PENDING + armed=1 + state CONNECTED + live_status ARMED.
 *
 * Correctifs champs binaires:
 * - ticket_no DOIT etre le TicketNumber du registre (#23) et donc lu en U32 (4 bytes).
 * - sale_no DOIT etre le SaleNumber du registre (#22) et donc lu en U32 (4 bytes).
 */
public final class DeliveryController implements DeliveryControllerPort {

    // -------------------------
    // JSON safe put
    // -------------------------
    private static void safeJsonPut(JSONObject o, String k, Object v) {
        if (o == null) return;
        try { o.put(k, v); } catch (Exception ignore) {}
    }

    // Champs LCR
    private static final int FIELD_ACTIVE_PRODUCT = 0;
    private static final int FIELD_PRESET_NET = 6;
    private static final int FIELD_DECIMALS = 39;
    private static final int FIELD_GROSS_COUNT = 44;
    private static final int FIELD_NET_COUNT = 45;

    // Champs LCR (API / reporting)
    private static final int FIELD_GROSS_TOTAL = 17;
    private static final int FIELD_NET_TOTAL = 18;
    private static final int FIELD_SALE_NUMBER = 22;      // U32 (selon ta demande "go")
    private static final int FIELD_TICKET_NUMBER = 23;    // U32 (TicketNumber = index de livraison)
    private static final int FIELD_SERIAL_ID = 80;

    // Commands
    private static final int CMD_RUN = 0x00;
    private static final int CMD_END = 0x02;
    private static final int CMD_PRINT_LAST_TICKET = 0x06;

    // Bits delCode (0x28)
    private static final int DC_TICKET_PENDING = 0x0001;
    private static final int DC_FLOW_ACTIVE = 0x0004;
    private static final int DC_DELIVERY_ACTIVE = 0x0008;

    // OFF conservateur (détection stagnation)
    private static final long NO_FLOW_CONFIRM_MS = 10_000;

    // START retry
    private static final long START_RETRY_WINDOW_MS = 20_000;
    private static final long START_RETRY_POLL_MS = 200;

    // Ticket loop
    private static final long TICKET_DEVICE_LOOP_MS = 30_000;

    // LIVE backoff
    private static final long LIVE_BASE_MS = 200;
    private static final long LIVE_MAX_MS = 2000;
    private static final long LIVE_LOG_THROTTLE_MS = 1000;

    // CONTINUER: fenêtre de grâce 30s
    private static final long CONTINUE_GRACE_MS = 30_000;
    private static final long CONTINUE_DEBOUNCE_MS = 1500;

    private final LcpLink link;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Listener listener;

    private volatile DeliveryState state = DeliveryState.DISCONNECTED;
    private volatile int cachedDigits = -1;

    // LIVE: d>0 => ON réel, d==0 => stagnation
    private volatile boolean flowOffStable = false;
    private volatile boolean sawFlowOnOnce = false;
    private volatile long flowOffStartMs = 0L;
    private volatile long lastCountsChangeMs = 0L;
    private volatile int lastGrossRaw = -1;
    private volatile int lastNetRaw = -1;

    private volatile boolean stopped = false;
    private volatile boolean txRxEnabled = false;
    private volatile boolean logTsEnabled = false;
    private volatile long lastResyncMs = 0L;

    // Pas de chevauchement LIVE
    private final AtomicBoolean liveInFlight = new AtomicBoolean(false);
    private final ThreadLocal<Boolean> inLiveSample = new ThreadLocal<>();

    // Ticket pending: anti-réimpression
    private final java.util.concurrent.atomic.AtomicBoolean ticketPrintInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile long ticketPrintStartMs = 0L;

    // Backoff state
    private volatile long liveBackoffMs = LIVE_BASE_MS;
    private volatile long liveNextAllowedMs = 0L;
    private volatile long liveLastSkipLogMs = 0L;

    // Grâce 30s après Continuer
    private volatile long continueGraceUntilMs = 0L;
    private volatile long lastContinueClickMs = 0L;

    private static final ThreadLocal<SimpleDateFormat> IO_DF =
            ThreadLocal.withInitial(() ->
                    new SimpleDateFormat("HH:mm:ss.SSS", Locale.CANADA_FRENCH));

    // =========================
    // API-Face (jobs + cache)
    // =========================
    private static final class ApiJob {
        final String id;
        final long startedAtMs;

        volatile boolean done = false;
        volatile String state; // PENDING/RUNNING/DONE/ERROR
        volatile String err;

        // Contexte
        volatile String numeroLivraison;
        volatile String deliveryUid;
        volatile String compartment;
        volatile int productNumber;

        // Preset
        volatile double presetNetL_requested;
        volatile double presetNetL_applied;
        volatile long presetRawU32;
        volatile int decimals;

        // Identifiants registre
        volatile String saleNo;    // #22 U32 string
        volatile String ticketNo;  // #23 U32 string
        volatile String serialId;  // #80 (string/ASCII chez toi)

        // Timing
        volatile long startMs = 0L;
        volatile long endMs = 0L;

        // Counters baseline (capturée au 1er sample actif)
        volatile boolean sawDeliveryActiveOnce = false;
        volatile boolean baselineCaptured = false;
        volatile int grossStartRaw = Integer.MIN_VALUE;
        volatile int netStartRaw = Integer.MIN_VALUE;

        // End
        volatile int grossEndRaw = 0;
        volatile int netEndRaw = 0;

        // Totaux
        volatile int grossTotalRaw = 0;
        volatile int netTotalRaw = 0;

        ApiJob(String id) {
            this.id = id;
            this.startedAtMs = System.currentTimeMillis();
            this.state = "PENDING";
        }
    }

    private final Map<String, ApiJob> apiJobs = new HashMap<>();

    public DeliveryController(LcpLink link) {
        this.link = link;
    }

    private boolean isStopped() {
        return stopped || link == null || link.isClosed();
    }

    // ====== DeliveryControllerPort ======
    @Override
    public DeliveryState getState() { return state; }

    @Override
    public boolean isPaused() { return state == DeliveryState.RUNNING_PAUSED; }

    @Override
    public boolean isDeliveryActive() {
        return state == DeliveryState.RUNNING_FLOWING ||
                state == DeliveryState.RUNNING_PAUSED;
    }

    @Override
    public boolean isFlowOffStable() { return flowOffStable; }

    @Override
    public long getFlowOffAgeMs() {
        if (!sawFlowOnOnce) return 0L;
        if (flowOffStartMs <= 0L) return 0L;
        long now = System.currentTimeMillis();
        return Math.max(0L, now - flowOffStartMs);
    }

    // ====== Logging ======
    @Override
    public void setLogTimestampsEnabled(boolean enabled) {
        io.execute(() -> logTsEnabled = enabled);
    }

    private String ioTs() {
        return IO_DF.get().format(new Date(System.currentTimeMillis()));
    }

    private void emitLog(String line) {
        Listener l = this.listener;
        if (l == null) return;
        if (logTsEnabled) {
            if (line != null && line.startsWith("[IO ")) l.onLog(line);
            else l.onLog("[IO " + ioTs() + "] " + line);
        } else {
            l.onLog(line);
        }
    }

    private static String stripIoPrefix(String s) {
        if (s == null) return "";
        if (!s.startsWith("[IO ")) return s;
        int idx = s.indexOf("] ");
        if (idx > 0 && idx + 2 <= s.length()) return s.substring(idx + 2);
        return s;
    }

    private static String msToUtcIso(long ms) {
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
        df.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return df.format(new Date(ms));
    }


    private static boolean isTxRxLine(String raw) {
        return raw.startsWith("TX:") ||
                raw.startsWith("RX:") ||
                raw.startsWith("↳");
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
        if (listener == null) {
            link.setTraceSink(null);
            return;
        }
        link.setTraceSink(line -> {
            String raw = stripIoPrefix(line);
            if (!txRxEnabled && isTxRxLine(raw)) return;
            if (Boolean.TRUE.equals(inLiveSample.get()) && isTxRxLine(raw)) return;
            emitLog(line);
        });
    }

    @Override
    public void setTxRxLoggingEnabled(boolean enabled) {
        io.execute(() -> {
            if (isStopped()) return;
            txRxEnabled = enabled;
            emitLog("[LOG] TX/RX " + (enabled ? "ON" : "OFF"));
        });
    }

    // =========================
    // Connected / init / shutdown
    // =========================
    @Override
    public void initialize() {
        io.execute(() -> {
            if (isStopped()) return;
            setState(DeliveryState.CONNECTED);
            emitLog("LCP pret (sans refresh automatique)");
            if (listener != null) listener.onLiveStatus("LIVE: CONNECTED - (pret)");
        });
    }

    @Override
    public void shutdown() { shutdown(true); }

    @Override
    public void shutdown(boolean closeTransport) {
        stopped = true;
        try { link.setTraceSink(null); } catch (Exception ignored) {}
        try { io.shutdownNow(); } catch (Exception ignored) {}
        setState(DeliveryState.DISCONNECTED);
        if (listener != null) {
            listener.onLiveStatus("LIVE: DISCONNECTED");
            listener.onLog(closeTransport
                    ? "[LINK] Controller stopped / transport closed"
                    : "[LINK] Controller stopped (logic only)");
        }
        if (closeTransport) {
            try { link.close(); } catch (Exception ignored) {}
        } else {
            try { link.softClose(); } catch (Exception ignored) {}
        }
    }

    private void setState(DeliveryState s) {
        if (state == s) return;
        state = s;
        if (listener != null) listener.onStateChanged(s);
    }

    // =========================
    // A / B / C
    // =========================
    @Override
    public void refreshProducts() { emitLog("refreshProducts ignore"); }

    @Override
    public void selectProduct(int product1to16) {
        io.execute(() -> {
            if (isStopped()) return;
            try {
                int idx0 = product1to16 - 1;
                link.opSetField(FIELD_ACTIVE_PRODUCT, new byte[]{(byte) idx0});
            } catch (Exception e) {
                handleIoFailure("selectProduct", e);
            }
        });
    }

    @Override
 public void requestStatus() {
  io.execute(() -> {
   if (isStopped()) return;
   try {
    FullStatus fs = readFullStatus("status/full");
    emitLog(String.format("[STATUS] dev=0x%02X prn=0x%02X ds=0x%04X dc=0x%04X",
      fs.devStatus, fs.prnStatus, fs.delStatus, fs.delCode));

    // B=Status doit aussi rafraichir NET et GROSS (valeurs affichees par le registre)
    ensureDigits();
    double scale = Math.pow(10, cachedDigits);
    int gRaw = beI32(link.opGetField(FIELD_GROSS_COUNT));
    int nRaw = beI32(link.opGetField(FIELD_NET_COUNT));
    double net = (nRaw & 0xFFFFFFFFL) / scale;
    double gross = (gRaw & 0xFFFFFFFFL) / scale;
    if (listener != null) listener.onLiveQty(net, gross);

   } catch (Exception e) {
    handleIoFailure("status", e);
   }
  });
 }

 @Override
    public void alignOrRecover() {
        io.execute(() -> {
            if (isStopped()) return;
            try {
                emitLog("[A] Align / recover requested");
                doAlignOrRecoverFull();
            } catch (Exception e) {
                handleIoFailure("alignOrRecover", e);
            }
        });
    }

    @Override
    public void startDelivery(int product1to16, double presetNet) {
        io.execute(() -> {
            if (isStopped()) return;
            if (state == DeliveryState.PRESTART || state == DeliveryState.ENDING) return;
            emitLog("[C] New delivery requested");
            final long deadline = System.currentTimeMillis() + START_RETRY_WINDOW_MS;
            try {
                FullStatus fs = retryUntilDeadline(deadline, "C/full-precheck", () -> readFullStatus("C/full"));
                if (fs.deliveryActive) {
                    setState(DeliveryState.CONNECTED);
                    if (listener != null) {
                        if (fs.ticketPending) listener.onLiveStatus("LIVE: CONNECTED - Ticket pending");
                        else listener.onLiveStatus("LIVE: CONNECTED - Delivery active (use A)");
                    }
                    return;
                }
                if (fs.ticketPending) {
                    setState(DeliveryState.CONNECTED);
                    if (listener != null) listener.onLiveStatus("LIVE: CONNECTED - Ticket pending");
                    return;
                }
                if (fs.flowActive) {
                    setState(DeliveryState.CONNECTED);
                    if (listener != null) listener.onLiveStatus("LIVE: CONNECTED - Flow active (use A)");
                    return;
                }
                doStartNewDeliveryWithRetry(deadline, product1to16, presetNet);
            } catch (Exception e) {
                handleIoFailure("startDelivery(C-intent)", e);
            }
        });
    }

    private void doStartNewDeliveryWithRetry(long deadlineMs, int product1to16, double presetNet) throws Exception {
        if (isStopped()) return;
        setState(DeliveryState.PRESTART);

        flowOffStable = false;
        sawFlowOnOnce = false;
        flowOffStartMs = 0L;
        lastCountsChangeMs = 0L;
        lastGrossRaw = -1;
        lastNetRaw = -1;

        liveBackoffMs = LIVE_BASE_MS;
        liveNextAllowedMs = 0L;
        liveLastSkipLogMs = 0L;

        continueGraceUntilMs = 0L;

        retryUntilDeadline(deadlineMs, "SET_FIELD#0", () -> {
            int idx0 = product1to16 - 1;
            link.opSetField(FIELD_ACTIVE_PRODUCT, new byte[]{(byte) idx0});
            return null;
        });

        retryUntilDeadline(deadlineMs, "GET_FIELD#39", () -> {
            ensureDigits();
            return null;
        });

        retryUntilDeadline(deadlineMs, "SET_FIELD#6", () -> {
            writePresetNet_WithCacheOrFallback(presetNet);
            return null;
        });

        retryUntilDeadline(deadlineMs, "RUN(0x00)", () -> {
            link.opIssueCommand(CMD_RUN);
            return null;
        });

        setState(DeliveryState.RUNNING_PAUSED);
        if (listener != null) listener.onLiveStatus("LIVE: RUNNING_PAUSED (Flow OFF)");
    }

    // =========================
    // Continue / Finish
    // =========================
    @Override
    public void resumeIfPaused() {
        io.execute(() -> {
            if (isStopped()) return;
            try {
                if (state != DeliveryState.RUNNING_PAUSED) return;
                long now = System.currentTimeMillis();

                if ((now - lastContinueClickMs) < CONTINUE_DEBOUNCE_MS) return;
                lastContinueClickMs = now;

                if (continueGraceUntilMs > now) return;

                link.opIssueCommand(CMD_RUN);

                continueGraceUntilMs = now + CONTINUE_GRACE_MS;
                setState(DeliveryState.RUNNING_FLOWING);
                if (listener != null) {
                    listener.onLiveStatus("LIVE: RUNNING_FLOWING (Flow OFF - waiting progression)");
                }

            } catch (Exception e) {
                continueGraceUntilMs = 0L;
                handleIoFailure("resumeIfPaused", e);
            }
        });
    }

    @Override
    public void endDelivery() {
        io.execute(() -> {
            if (isStopped()) return;
            try {
                if (state != DeliveryState.RUNNING_PAUSED) return;
                if (!flowOffStable || !sawFlowOnOnce) return;

                setState(DeliveryState.ENDING);
                link.opIssueCommand(CMD_END);

                long deadline = System.currentTimeMillis() + 15_000;
                while (!isStopped() && System.currentTimeMillis() < deadline) {
                    FullStatus fs = safeReadFullStatusNoThrow();
                    if (fs != null && !fs.deliveryActive && !fs.flowActive) break;
                    try { Thread.sleep(250); } catch (InterruptedException ignored) {}
                }

                FullStatus fsAfter = safeReadFullStatusNoThrow();
                if (fsAfter != null && fsAfter.ticketPending) {
                    clearTicketPendingLoop();
                }

                setState(DeliveryState.CONNECTED);
                if (listener != null) listener.onLiveStatus("LIVE: CONNECTED - Ready");

            } catch (Exception e) {
                handleIoFailure("endDelivery", e);
            }
        });
    }

    // =========================
    // LIVE sample
    // =========================
    @Override
    public void requestLiveSample() {
        io.execute(() -> {
            if (isStopped()) return;
            long now = System.currentTimeMillis();
            if (now < liveNextAllowedMs) return;
            if (!liveInFlight.compareAndSet(false, true)) return;
            inLiveSample.set(true);
            try {
                int delCode;
                try {
                    int[] ds = link.opDeliveryStatus();
                    delCode = ds[1];
                } catch (Exception e) {
                    liveSoftSkip("GET_DELIVERY_STATUS", e);
                    return;
                }

                boolean ticket = (delCode & DC_TICKET_PENDING) != 0;
                boolean flowBit = (delCode & DC_FLOW_ACTIVE) != 0;
                boolean active = (delCode & DC_DELIVERY_ACTIVE) != 0;

                if (!active) {
                    liveResetBackoff();
                    setState(DeliveryState.CONNECTED);
                    if (listener != null) {
                        listener.onLiveStatus(ticket ? "LIVE: CONNECTED - Ticket pending"
                                : "LIVE: CONNECTED - Ready");
                        listener.onFlowStability(false, false, 0L);
                    }
                    lastGrossRaw = -1;
                    lastNetRaw = -1;
                    flowOffStable = false;
                    sawFlowOnOnce = false;
                    flowOffStartMs = 0L;
                    lastCountsChangeMs = 0L;
                    continueGraceUntilMs = 0L;
                    return;
                }

                try { ensureDigits(); }
                catch (Exception e) {
                    liveSoftSkip("ensureDigits", e);
                    return;
                }

                double scale = Math.pow(10, cachedDigits);
                int g, n;
                try {
                    g = beI32(link.opGetField(FIELD_GROSS_COUNT));
                    n = beI32(link.opGetField(FIELD_NET_COUNT));
                } catch (Exception ex) {
                    g = (lastGrossRaw >= 0) ? lastGrossRaw : 0;
                    n = (lastNetRaw >= 0) ? lastNetRaw : 0;
                    liveBackoffStep("[LIVE] soft-skip counters");
                }

                liveResetBackoff();
                long t = System.currentTimeMillis();

                int d = 0;
                if (lastGrossRaw >= 0 && lastNetRaw >= 0) {
                    d = Math.abs(g - lastGrossRaw) + Math.abs(n - lastNetRaw);
                }

                if (listener != null) listener.onLiveQty(n / scale, g / scale);

                if (d > 0) {
                    continueGraceUntilMs = 0L;
                    sawFlowOnOnce = true;
                    lastCountsChangeMs = t;
                    flowOffStable = false;
                    flowOffStartMs = 0L;
                    lastGrossRaw = g;
                    lastNetRaw = n;
                    if (listener != null) {
                        listener.onFlowStability(flowBit, false, 0L);
                        listener.onLiveStatus("LIVE: RUNNING_FLOWING (FLOW ON)");
                    }
                    setState(DeliveryState.RUNNING_FLOWING);
                    return;
                }

                if (lastCountsChangeMs == 0L) lastCountsChangeMs = t;
                long age = t - lastCountsChangeMs;

                if (!sawFlowOnOnce) {
                    flowOffStable = false;
                    flowOffStartMs = 0L;
                    if (listener != null) {
                        listener.onFlowStability(flowBit, false, 0L);
                        listener.onLiveStatus("LIVE: RUNNING_FLOWING (Flow OFF - waiting progression)");
                    }
                    setState(DeliveryState.RUNNING_FLOWING);
                    lastGrossRaw = g;
                    lastNetRaw = n;
                    return;
                }

                if (flowOffStartMs == 0L) flowOffStartMs = lastCountsChangeMs;
                flowOffStable = age >= NO_FLOW_CONFIRM_MS;

                long now2 = System.currentTimeMillis();
                if (continueGraceUntilMs > now2) {
                    if (listener != null) {
                        listener.onFlowStability(flowBit, false, age);
                        listener.onLiveStatus("LIVE: RUNNING_FLOWING (Flow OFF - waiting progression)");
                    }
                    setState(DeliveryState.RUNNING_FLOWING);
                    lastGrossRaw = g;
                    lastNetRaw = n;
                    return;
                }

                if (listener != null) {
                    listener.onFlowStability(flowBit, flowOffStable, age);
                    listener.onLiveStatus(flowOffStable
                            ? "LIVE: RUNNING_PAUSED (FLOW OFF confirmed)"
                            : "LIVE: RUNNING_FLOWING (FLOW OFF - confirming...)");
                }

                if (flowOffStable) setState(DeliveryState.RUNNING_PAUSED);
                else setState(DeliveryState.RUNNING_FLOWING);

                lastGrossRaw = g;
                lastNetRaw = n;

            } finally {
                inLiveSample.remove();
                liveInFlight.set(false);
            }
        });
    }

    private void liveResetBackoff() {
        liveBackoffMs = LIVE_BASE_MS;
        liveNextAllowedMs = 0L;
    }

    private void liveBackoffStep(String reason) {
        long now = System.currentTimeMillis();
        liveBackoffMs = Math.min(LIVE_MAX_MS, Math.max(LIVE_BASE_MS, liveBackoffMs * 2));
        liveNextAllowedMs = now + liveBackoffMs;
        if (now - liveLastSkipLogMs >= LIVE_LOG_THROTTLE_MS) {
            emitLog(reason + " (backoff=" + liveBackoffMs + "ms)");
            liveLastSkipLogMs = now;
        }
    }

    private void liveSoftSkip(String opName, Exception e) {
        String m = (e.getMessage() != null) ? e.getMessage() : "";
        liveBackoffStep("[LIVE] soft-skip " + opName + ": " + m);
    }

    @Override
    public void requestLiveSnapshot() {
        io.execute(() -> {
            if (isStopped()) return;
            try {
                FullStatus fs = readFullStatus("SNAP/full");
                if (listener != null) {
                    listener.onLiveStatus(fs.ticketPending ? "LIVE: CONNECTED - Ticket pending"
                            : "LIVE: CONNECTED - Ready");
                }
            } catch (Exception e) {
                handleIoFailure("requestLiveSnapshot", e);
            }
        });
    }

    // =========================
    // Full status
    // =========================
    private static final class FullStatus {
        final int devStatus;
        final int prnStatus;
        final int delStatus;
        final int delCode;
        final boolean ticketPending;
        final boolean flowActive;
        final boolean deliveryActive;

        FullStatus(LcpLink.MachineStatus ms, int delStatus, int delCode) {
            this.devStatus = ms.devStatus;
            this.prnStatus = ms.prnStatus;
            this.delStatus = delStatus;
            this.delCode = delCode;
            this.ticketPending = (delCode & DC_TICKET_PENDING) != 0;
            this.flowActive = (delCode & DC_FLOW_ACTIVE) != 0;
            this.deliveryActive = (delCode & DC_DELIVERY_ACTIVE) != 0;
        }
    }

    private FullStatus readFullStatus(String ctx) throws Exception {
        LcpLink.MachineStatus ms = link.opGetMachineStatus();
        int[] ds = link.opDeliveryStatus();
        return new FullStatus(ms, ds[0], ds[1]);
    }

    private FullStatus safeReadFullStatusNoThrow() {
        try { return readFullStatus("safe"); }
        catch (Exception e) { return null; }
    }

    private void doAlignOrRecoverFull() throws Exception {
        FullStatus fs = readFullStatus("A/full");
        if (fs.ticketPending) {
            clearTicketPendingSafeForAlign();
            fs = readFullStatus("A/full-after-ticket");
        }

        if (fs.deliveryActive && !fs.flowActive) {
            setState(DeliveryState.RUNNING_PAUSED);
            if (listener != null) listener.onLiveStatus("LIVE: RUNNING_PAUSED (recovered)");
            return;
        }
        if (fs.deliveryActive && fs.flowActive) {
            setState(DeliveryState.RUNNING_FLOWING);
            if (listener != null) listener.onLiveStatus("LIVE: RUNNING_FLOWING (recovered)");
            return;
        }

        setState(DeliveryState.CONNECTED);
        if (listener != null) {
            listener.onLiveStatus(fs.ticketPending ? "LIVE: CONNECTED - Ticket pending" : "LIVE: CONNECTED - Ready");
        }
    }

    private void clearTicketPendingSafeForAlign() throws Exception {
        try {
            LcpLink.MachineStatus ms0 = link.opGetMachineStatus();
            if ((ms0.delCode & DC_TICKET_PENDING) == 0) {
                ticketPrintInFlight.set(false);
                ticketPrintStartMs = 0L;
                return;
            }
        } catch (Exception ignored) {}

        long now = System.currentTimeMillis();

        if (ticketPrintInFlight.compareAndSet(false, true)) {
            ticketPrintStartMs = now;
            try {
                link.opIssueCommand(CMD_PRINT_LAST_TICKET);
            } catch (Exception e) {
                ticketPrintInFlight.set(false);
                ticketPrintStartMs = 0L;
                throw e;
            }
        } else {
            if (ticketPrintStartMs <= 0L) ticketPrintStartMs = now;
        }

        long deadline = ticketPrintStartMs + TICKET_DEVICE_LOOP_MS;
        while (!isStopped() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            try {
                LcpLink.MachineStatus ms = link.opGetMachineStatus();
                if ((ms.delCode & DC_TICKET_PENDING) == 0) {
                    ticketPrintInFlight.set(false);
                    ticketPrintStartMs = 0L;
                    return;
                }
            } catch (Exception ignored) {}
        }

        ticketPrintInFlight.set(false);
        ticketPrintStartMs = 0L;
    }

    private void clearTicketPendingLoop() {
        long deadline = System.currentTimeMillis() + TICKET_DEVICE_LOOP_MS;
        while (!isStopped() && System.currentTimeMillis() < deadline) {
            try { link.opIssueCommand(CMD_PRINT_LAST_TICKET); } catch (Exception ignored) {}
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            try {
                LcpLink.MachineStatus ms = link.opGetMachineStatus();
                if ((ms.delCode & DC_TICKET_PENDING) == 0) return;
            } catch (Exception ignored) {}
        }
    }

    // =========================
    // Retry helper
    // =========================
    private interface IoSupplier<T> { T get() throws Exception; }

    private <T> T retryUntilDeadline(long deadlineMs, String step, IoSupplier<T> op) throws Exception {
        Exception last = null;
        while (System.currentTimeMillis() < deadlineMs) {
            if (isStopped()) throw new IllegalStateException("Transport closed");
            try {
                return op.get();
            } catch (Exception e) {
                last = e;
                String m = (e.getMessage() != null) ? e.getMessage() : "";
                boolean retryable = m.contains("Queued timeout") || m.contains("Timeout waiting LCP response");
                boolean hardFatal =
                        m.contains("Transport closed") ||
                                m.contains("Error writing") ||
                                m.contains("rc=-1") ||
                                m.contains("Connection closed");
                if (hardFatal) throw e;
                if (!retryable) throw e;
                softResync("retry/" + step);
                try { Thread.sleep(START_RETRY_POLL_MS); } catch (InterruptedException ignored) {}
            }
        }
        if (last != null) throw last;
        return null;
    }

    // =========================
    // Protocol helpers
    // =========================
    private void ensureDigits() throws Exception {
        if (cachedDigits >= 0) return;
        byte[] dec = link.opGetField(FIELD_DECIMALS);
        int idx = (dec.length >= 1) ? (dec[0] & 0xFF) : 0;
        cachedDigits = decimalsDigits(idx);
    }

    private void writePresetNet_WithCacheOrFallback(double preset) throws Exception {
        int digits = cachedDigits;
        if (digits < 0) digits = 1;
        int scale = (int) Math.pow(10, digits);
        int value = (int) Math.round(preset * scale);
        byte[] buf = new byte[] {
                (byte) (value >> 24),
                (byte) (value >> 16),
                (byte) (value >> 8),
                (byte) value
        };
        link.opSetField(FIELD_PRESET_NET, buf);
    }

    private int decimalsDigits(int idx) {
        switch (idx) {
            case 0: return 2;
            case 1: return 1;
            case 2: return 0;
            case 3: return 3;
            default: return 2;
        }
    }

    private int beI32(byte[] b) {
        if (b == null || b.length < 4) return 0;
        return ((b[0] & 0xFF) << 24) |
                ((b[1] & 0xFF) << 16) |
                ((b[2] & 0xFF) << 8) |
                (b[3] & 0xFF);
    }

    private long u32delta(int endRaw, int startRaw) {
        long e = endRaw & 0xFFFFFFFFL;
        long s = startRaw & 0xFFFFFFFFL;
        return (e - s) & 0xFFFFFFFFL;
    }

    private String decodeAzString(byte[] b) {
        if (b == null || b.length == 0) return "";
        String s = new String(b, StandardCharsets.UTF_8);
        int nul = s.indexOf('\0');
        if (nul >= 0) s = s.substring(0, nul);
        return s.trim();
    }

    // -------- U32 helpers (FIX #22/#23) --------
    private String readU32FieldAsDecString(int field) throws Exception {
        long u = beI32(link.opGetField(field)) & 0xFFFFFFFFL;
        return String.valueOf(u);
    }

    private String readTicketNo23() throws Exception {
        return readU32FieldAsDecString(FIELD_TICKET_NUMBER);
    }

    private String readSaleNo22() throws Exception {
        return readU32FieldAsDecString(FIELD_SALE_NUMBER);
    }

    // =========================
    // Error handling / resync
    // =========================
    private void handleIoFailure(String ctx, Exception e) {
        if (listener != null) listener.onError(ctx, e);
        String msg = (e != null && e.getMessage() != null) ? e.getMessage() : "";
        boolean hardFatal =
                msg.contains("Transport closed") ||
                        msg.contains("Error writing") ||
                        msg.contains("rc=-1") ||
                        msg.contains("Connection closed");
        boolean retryish =
                msg.contains("Timeout waiting LCP response") ||
                        msg.contains("Queued timeout");
        if ("requestLiveSample".equals(ctx)) return;
        if (hardFatal) { shutdown(true); return; }
        if (retryish) softResync("timeout/" + ctx);
    }

    private void softResync(String reason) {
        if (isStopped()) return;
        long now = System.currentTimeMillis();
        if (now - lastResyncMs < 1500) return;
        lastResyncMs = now;
        link.drainInput(250);
        link.forceSyncNext(reason);
    }

    // =========================================================
    // ========================= API-Face =======================
    // =========================================================
    public ApiResult api_scanUsb() {
        if (link == null || link.isClosed()) {
            return ApiResult.fail("Scan USB: 0 - Aucun registre detecte.", "NO_DEVICE");
        }
        return ApiResult.ok("Scan USB: 1 - Registre detecte");
    }

    public ApiResult api_openPingUsb() {
        if (link == null || link.isClosed()) {
            return ApiResult.fail("Open/Ping USB: 0 - USB non pret.", "USB_NOT_READY");
        }
        return ApiResult.ok("Open/Ping USB: 1 - USB pret");
    }

    public ApiResult api_connectLcp() {
        if (link == null || link.isClosed()) {
            return ApiResult.fail("Connect LCP: 0 - USB non connecte.", "NO_TRANSPORT");
        }
        try {
            int[] ds = link.opDeliveryStatus();
            int delCode = ds[1];
            boolean ticketPending = (delCode & DC_TICKET_PENDING) != 0;
            boolean flowActive = (delCode & DC_FLOW_ACTIVE) != 0;
            boolean deliveryActive = (delCode & DC_DELIVERY_ACTIVE) != 0;

            JSONObject data = new JSONObject();
            safeJsonPut(data, "deliveryActive", deliveryActive ? 1 : 0);
            safeJsonPut(data, "flowActive", flowActive ? 1 : 0);
            safeJsonPut(data, "ticketPending", ticketPending ? 1 : 0);

            if (!deliveryActive && !ticketPending) {
                safeJsonPut(data, "next", "C");
                return ApiResult.ok("Connect LCP: 1 - CONNECTED pret a livrer (Faire C)", data);
            }
            safeJsonPut(data, "next", "A");
            return ApiResult.ok("Connect LCP: 1 - CONNECTED livraison en attente (Faire A)", data);

        } catch (Exception e) {
            JSONObject d = new JSONObject();
            safeJsonPut(d, "detail", (e.getMessage() != null) ? e.getMessage() : "");
            return ApiResult.fail("Connect LCP: 0 - State check failed (0x28).", "STATE28_FAIL", d);
        }
    }

    /**
     * API wrapper pour démarrer une livraison en mode "C" (legacy/API).
     *
     * - Lance la séquence C via la logique existante startDelivery(product, preset).
     * - Retourne immédiatement un ApiResult (démarrage asynchrone sur thread IO).
     * - Si le registre n'est pas prêt (deliveryActive/ticketPending/flowActive), retourne next=A.
     */
    public ApiResult api_deliveryStartC(int product1to16, double presetNet) {
        if (link == null || link.isClosed()) {
            return ApiResult.fail("Delivery StartC: 0 - USB not ready.", "USB_NOT_READY");
        }
        try {
            int[] ds0 = link.opDeliveryStatus();
            int delCode0 = ds0[1];
            boolean ticketPending0 = (delCode0 & DC_TICKET_PENDING) != 0;
            boolean flowActive0 = (delCode0 & DC_FLOW_ACTIVE) != 0;
            boolean deliveryActive0 = (delCode0 & DC_DELIVERY_ACTIVE) != 0;

            JSONObject data = new JSONObject();
            safeJsonPut(data, "product", product1to16);
            safeJsonPut(data, "preset_net", presetNet);
            safeJsonPut(data, "deliveryActive", deliveryActive0 ? 1 : 0);
            safeJsonPut(data, "flowActive", flowActive0 ? 1 : 0);
            safeJsonPut(data, "ticketPending", ticketPending0 ? 1 : 0);

            // Not ready for C -> instruct A
            if (deliveryActive0 || ticketPending0 || flowActive0) {
                safeJsonPut(data, "next", "A");
                return ApiResult.ok("Delivery StartC: 1 - Not ready for C (use A)", data);
            }

            // Launch C sequence (async)
            startDelivery(product1to16, presetNet);
            safeJsonPut(data, "next", "POLL");
            return ApiResult.ok("Delivery StartC: 1 - Start requested", data);

        } catch (Exception e) {
            JSONObject d = new JSONObject();
            safeJsonPut(d, "detail", (e.getMessage() != null) ? e.getMessage() : "");
            return ApiResult.fail("Delivery StartC: 0 - orchestration error", "STARTC_FAIL", d);
        }
    }

    /**
     * API: Align / Recover (A)
     *
     * - Clear ticket pending (PRINT_LAST_TICKET loop)
     * - Resync state (RUNNING_FLOWING/RUNNING_PAUSED/CONNECTED)
     *
     * Retourne un snapshot (0x28) + next=C/A.
     */
    public ApiResult api_deliveryAlignA() {
        if (link == null || link.isClosed()) {
            return ApiResult.fail("Align A: 0 - USB non pret.", "USB_NOT_READY");
        }
        try {
            doAlignOrRecoverFull();

            int[] ds = link.opDeliveryStatus();
            int delCode = ds[1];
            boolean ticketPending = (delCode & DC_TICKET_PENDING) != 0;
            boolean flowActive = (delCode & DC_FLOW_ACTIVE) != 0;
            boolean deliveryActive = (delCode & DC_DELIVERY_ACTIVE) != 0;

            JSONObject data = new JSONObject();
            safeJsonPut(data, "deliveryActive", deliveryActive ? 1 : 0);
            safeJsonPut(data, "flowActive", flowActive ? 1 : 0);
            safeJsonPut(data, "ticketPending", ticketPending ? 1 : 0);
            safeJsonPut(data, "next", (!deliveryActive && !ticketPending) ? "C" : "A");
            safeJsonPut(data, "state", state.name());
            safeJsonPut(data, "live_status", (!deliveryActive && !ticketPending)
                    ? "LIVE: CONNECTED - Ready"
                    : (ticketPending ? "LIVE: CONNECTED - Ticket pending" : "LIVE: CONNECTED - Delivery/Flow active"));

            return ApiResult.ok("Align A: 1 - Align/Recover executed", data);

        } catch (Exception e) {
            JSONObject d = new JSONObject();
            safeJsonPut(d, "detail", (e.getMessage() != null) ? e.getMessage() : "");
            return ApiResult.fail("Align A: 0 - Failed", "ALIGN_FAIL", d);
        }
    }



    // ------- ARMED helpers -------
    private JSONArray actionsContinueTerminate() {
        JSONArray a = new JSONArray();
        a.put("CONTINUER");
        a.put("TERMINER");
        return a;
    }

    private String liveStatusArmed() {
        return "LIVE: CONNECTED - ARMED (en attente CONTINUER)";
    }

    /**
     * OneShot A2 + ARMED=1 (NO state side effects).
     * NOTE: ticket_no (#23) and sale_no (#22) read as U32.
     */
    public ApiResult api_deliveryOneShotStart(String numero_livraison, int product1to16, double presetNetL, String compartment) {
        if (link == null || link.isClosed()) {
            return ApiResult.fail("Delivery OneShot: 0 - USB not ready.", "USB_NOT_READY");
        }
        try {
            // 0x28
            int[] ds0 = link.opDeliveryStatus();
            int delCode0 = ds0[1];
            boolean ticketPending0 = (delCode0 & DC_TICKET_PENDING) != 0;
            boolean deliveryActive0 = (delCode0 & DC_DELIVERY_ACTIVE) != 0;

            // ✅ FIX #23/#22
            String ticketNo = readTicketNo23();
            String saleNo   = readSaleNo22();
            String serialId = decodeAzString(link.opGetField(FIELD_SERIAL_ID));

            String deliveryUid = (numero_livraison == null ? "" : numero_livraison) + "-" + ticketNo;

            // If already active
            if (deliveryActive0) {
                JSONObject data = new JSONObject();
                safeJsonPut(data, "numero_livraison", numero_livraison);
                safeJsonPut(data, "ticket_no", ticketNo);
                safeJsonPut(data, "sale_no", saleNo);
                safeJsonPut(data, "serial_id", serialId);
                safeJsonPut(data, "delivery_uid", deliveryUid);
                safeJsonPut(data, "deliveryActive", 1);
                safeJsonPut(data, "ticketPending", ticketPending0 ? 1 : 0);
                safeJsonPut(data, "armed", 0);
                safeJsonPut(data, "state", state.name());
                safeJsonPut(data, "live_status", "LIVE: Delivery already active");
                safeJsonPut(data, "available_actions", actionsContinueTerminate());
                return ApiResult.ok("Delivery OneShot: 1 - Delivery already active", data);
            }

            // Auto-A if ticket pending
            boolean autoAAttempted = false;
            boolean autoASuccess = false;

            if (ticketPending0) {
                autoAAttempted = true;
                try { doAlignOrRecoverFull(); } catch (Exception ignore) {}

                int[] dsA = link.opDeliveryStatus();
                int delCodeA = dsA[1];
                boolean ticketPendingA = (delCodeA & DC_TICKET_PENDING) != 0;
                boolean deliveryActiveA = (delCodeA & DC_DELIVERY_ACTIVE) != 0;

                autoASuccess = !ticketPendingA;

                if (ticketPendingA || deliveryActiveA) {
                    JSONObject data = new JSONObject();
                    safeJsonPut(data, "numero_livraison", numero_livraison);
                    safeJsonPut(data, "ticket_no", ticketNo);
                    safeJsonPut(data, "sale_no", saleNo);
                    safeJsonPut(data, "serial_id", serialId);
                    safeJsonPut(data, "delivery_uid", deliveryUid);
                    safeJsonPut(data, "autoA_attempted", 1);
                    safeJsonPut(data, "autoA_success", autoASuccess ? 1 : 0);
                    safeJsonPut(data, "next", "A");
                    safeJsonPut(data, "armed", 0);
                    safeJsonPut(data, "state", DeliveryState.CONNECTED.name());
                    safeJsonPut(data, "live_status", "LIVE: CONNECTED - Ticket pending");
                    JSONArray a = new JSONArray();
                    a.put("ALIGN_A");
                    safeJsonPut(data, "available_actions", a);
                    return ApiResult.ok("Delivery OneShot: 1 - Ticket pending (auto-A attempted)", data);
                }
            }

            // ARM (product + preset confirm)
            ensureDigits();
            double scale = Math.pow(10, cachedDigits);

            int idx0 = product1to16 - 1;
            link.opSetField(FIELD_ACTIVE_PRODUCT, new byte[]{(byte) idx0});

            writePresetNet_WithCacheOrFallback(presetNetL);

            long presetRawU = beI32(link.opGetField(FIELD_PRESET_NET)) & 0xFFFFFFFFL;
            double presetApplied = presetRawU / scale;

            double tol = 1.0 / scale;
            if (Math.abs(presetApplied - presetNetL) > (tol * 1.5)) {
                JSONObject d = new JSONObject();
                safeJsonPut(d, "preset_requested", presetNetL);
                safeJsonPut(d, "preset_applied", presetApplied);
                safeJsonPut(d, "decimals", cachedDigits);
                safeJsonPut(d, "preset_raw_u32", presetRawU);
                return ApiResult.fail("Delivery OneShot: 0 - Preset mismatch", "PRESET_MISMATCH", d);
            }

            String jobId = UUID.randomUUID().toString();
            ApiJob job = new ApiJob(jobId);

            job.numeroLivraison = numero_livraison;
            job.ticketNo = ticketNo; // ✅ FIX
            job.saleNo = saleNo;     // ✅ FIX
            job.serialId = serialId;
            job.deliveryUid = deliveryUid;
            job.compartment = compartment;
            job.productNumber = product1to16;
            job.presetNetL_requested = presetNetL;
            job.presetNetL_applied = presetApplied;
            job.presetRawU32 = presetRawU;
            job.decimals = cachedDigits;
            job.startMs = System.currentTimeMillis();

            synchronized (apiJobs) {
                apiJobs.put(jobId, job);
            }

            // NO state side effects: remain CONNECTED
            setState(DeliveryState.CONNECTED);

            JSONObject data = new JSONObject();
            safeJsonPut(data, "jobId", jobId);
            safeJsonPut(data, "numero_livraison", numero_livraison);
            safeJsonPut(data, "ticket_no", ticketNo);
            safeJsonPut(data, "sale_no", saleNo);
            safeJsonPut(data, "serial_id", serialId);
            safeJsonPut(data, "delivery_uid", deliveryUid);
            safeJsonPut(data, "autoA_attempted", autoAAttempted ? 1 : 0);
            safeJsonPut(data, "autoA_success", autoASuccess ? 1 : 0);
            safeJsonPut(data, "preset_requested", presetNetL);
            safeJsonPut(data, "preset_applied", presetApplied);
            safeJsonPut(data, "decimals", cachedDigits);
            safeJsonPut(data, "armed", 1);
            safeJsonPut(data, "state", DeliveryState.CONNECTED.name());
            safeJsonPut(data, "live_status", liveStatusArmed());
            safeJsonPut(data, "available_actions", actionsContinueTerminate());
            return ApiResult.ok("Delivery OneShot: 1 - ARMED (preset OK, waiting)", data);

        } catch (Exception e) {
            JSONObject d = new JSONObject();
            safeJsonPut(d, "detail", (e.getMessage() != null) ? e.getMessage() : "");
            return ApiResult.fail("Delivery OneShot: 0 - orchestration error", "ONESHOT_FAIL", d);
        }
    }

    /**
     * IMPORTANT: Continue must send RUN even if controller state is CONNECTED (ARMED mode).
     */
    public ApiResult api_deliveryContinue(String jobId) {
        ApiJob job;
        synchronized (apiJobs) { job = apiJobs.get(jobId); }
        if (job == null) return ApiResult.fail("Continue: 0 - Job unknown", "JOB_NOT_FOUND");
        try {
            link.opIssueCommand(CMD_RUN);
            long now = System.currentTimeMillis();
            continueGraceUntilMs = now + CONTINUE_GRACE_MS;
            setState(DeliveryState.RUNNING_FLOWING);

            // refresh ticket/sale (best-effort)
            try { job.ticketNo = readTicketNo23(); } catch (Exception ignored) {}
            try { job.saleNo = readSaleNo22(); } catch (Exception ignored) {}

            JSONObject data = new JSONObject();
            safeJsonPut(data, "jobId", jobId);
            safeJsonPut(data, "numero_livraison", job.numeroLivraison);
            safeJsonPut(data, "ticket_no", job.ticketNo);
            safeJsonPut(data, "sale_no", job.saleNo);
            safeJsonPut(data, "delivery_uid", job.deliveryUid);
            safeJsonPut(data, "armed", 0);
            safeJsonPut(data, "live_status", "LIVE: RUNNING_FLOWING (Flow OFF - waiting progression)");
            return ApiResult.ok("Continue: 1 - RUN sent", data);
        } catch (Exception e) {
            JSONObject d = new JSONObject();
            safeJsonPut(d, "detail", (e.getMessage() != null) ? e.getMessage() : "");
            return ApiResult.fail("Continue: 0 - RUN failed", "RUN_FAIL", d);
        }
    }

    /**
     * Terminate: for API we send END directly (best-effort).
     */
    public ApiResult api_deliveryTerminate(String jobId) {
        ApiJob job;
        synchronized (apiJobs) { job = apiJobs.get(jobId); }
        if (job == null) return ApiResult.fail("Terminate: 0 - Job unknown", "JOB_NOT_FOUND");

        try { link.opIssueCommand(CMD_END); } catch (Exception ignore) {}

        // refresh ticket/sale (best-effort)
        try { job.ticketNo = readTicketNo23(); } catch (Exception ignored) {}
        try { job.saleNo = readSaleNo22(); } catch (Exception ignored) {}

        JSONObject data = new JSONObject();
        safeJsonPut(data, "jobId", jobId);
        safeJsonPut(data, "numero_livraison", job.numeroLivraison);
        safeJsonPut(data, "ticket_no", job.ticketNo);
        safeJsonPut(data, "sale_no", job.saleNo);
        safeJsonPut(data, "delivery_uid", job.deliveryUid);
        safeJsonPut(data, "armed", 0);
        safeJsonPut(data, "live_status", "LIVE: ENDING");
        return ApiResult.ok("Terminate: 1 - END sent", data);
    }

    /**
     * Job polling:
     * - If deliveryActive=0 and never active => PENDING + armed=1 + state CONNECTED + live_status ARMED.
     * - DONE only after sawDeliveryActiveOnce then deliveryActive=0.
     */
    public ApiResult api_deliveryJobGet(String jobId) {
        ApiJob job;
        synchronized (apiJobs) { job = apiJobs.get(jobId); }
        if (job == null) return ApiResult.fail("Job: 0 - Unknown", "JOB_NOT_FOUND");

        // ensure ticket/sale always present (best-effort)
        if (job.ticketNo == null || job.ticketNo.trim().isEmpty()) {
            try { job.ticketNo = readTicketNo23(); } catch (Exception ignored) {}
        }
        if (job.saleNo == null || job.saleNo.trim().isEmpty()) {
            try { job.saleNo = readSaleNo22(); } catch (Exception ignored) {}
        }

        try {
            int[] ds = link.opDeliveryStatus();
            int delCode = ds[1];

            boolean deliveryActive = (delCode & DC_DELIVERY_ACTIVE) != 0;
            boolean flowActive = (delCode & DC_FLOW_ACTIVE) != 0;
            boolean ticketPending = (delCode & DC_TICKET_PENDING) != 0;

            if (deliveryActive) job.sawDeliveryActiveOnce = true;

            ensureDigits();
            double scale = Math.pow(10, cachedDigits);

            int g = beI32(link.opGetField(FIELD_GROSS_COUNT));
            int n = beI32(link.opGetField(FIELD_NET_COUNT));

            // Capture baseline at first active sample
            if (deliveryActive && !job.baselineCaptured) {
                job.baselineCaptured = true;
                job.grossStartRaw = g;
                job.netStartRaw = n;
            }

            Double deliveredNet = null;
            if (job.baselineCaptured) {
                long dnU = u32delta(n, job.netStartRaw);
                deliveredNet = dnU / scale;
            }

            JSONObject data = new JSONObject();
            safeJsonPut(data, "jobId", jobId);
            safeJsonPut(data, "numero_livraison", job.numeroLivraison);
            safeJsonPut(data, "ticket_no", job.ticketNo);
            safeJsonPut(data, "sale_no", job.saleNo);
            safeJsonPut(data, "delivery_uid", job.deliveryUid);

            safeJsonPut(data, "deliveryActive", deliveryActive ? 1 : 0);
            safeJsonPut(data, "flowActive", flowActive ? 1 : 0);
            safeJsonPut(data, "ticketPending", ticketPending ? 1 : 0);

            safeJsonPut(data, "net", (n & 0xFFFFFFFFL) / scale);
            safeJsonPut(data, "gross", (g & 0xFFFFFFFFL) / scale);
            safeJsonPut(data, "decimals", cachedDigits);

            safeJsonPut(data, "preset_requested", job.presetNetL_requested);
            safeJsonPut(data, "preset_applied", job.presetNetL_applied);
            safeJsonPut(data, "delivered_net", (deliveredNet == null) ? JSONObject.NULL : deliveredNet);

            // PENDING/ARMED
            if (!deliveryActive && !job.sawDeliveryActiveOnce) {
                job.state = "PENDING";
                safeJsonPut(data, "state_job", "PENDING");
                safeJsonPut(data, "armed", 1);
                safeJsonPut(data, "state", DeliveryState.CONNECTED.name());
                safeJsonPut(data, "live_status", liveStatusArmed());
                safeJsonPut(data, "available_actions", actionsContinueTerminate());
                return ApiResult.ok("Job: 1 - PENDING", data);
            }

            // DONE
            if (!deliveryActive && job.sawDeliveryActiveOnce) {
                // re-read ticket/sale at DONE (best-effort, authoritative)
                try { job.ticketNo = readTicketNo23(); } catch (Exception ignored) {}
                try { job.saleNo = readSaleNo22(); } catch (Exception ignored) {}

                job.state = "DONE";
                job.done = true;
                job.endMs = System.currentTimeMillis();
                job.grossEndRaw = g;
                job.netEndRaw = n;

                try {
                    job.grossTotalRaw = beI32(link.opGetField(FIELD_GROSS_TOTAL));
                    job.netTotalRaw = beI32(link.opGetField(FIELD_NET_TOTAL));
                } catch (Exception ignored) {}

                JSONObject result = new JSONObject();
                long gdU = job.baselineCaptured ? u32delta(job.grossEndRaw, job.grossStartRaw) : 0L;
                long ndU = job.baselineCaptured ? u32delta(job.netEndRaw, job.netStartRaw) : 0L;

                safeJsonPut(result, "numero_livraison", job.numeroLivraison);
                safeJsonPut(result, "ticket_no", job.ticketNo);
                safeJsonPut(result, "serial_id", job.serialId);
                safeJsonPut(result, "compartment", job.compartment == null ? JSONObject.NULL : job.compartment);
                safeJsonPut(result, "product_number", job.productNumber);

                String uid = (job.numeroLivraison == null ? "" : job.numeroLivraison) + "-" + job.ticketNo;
                job.deliveryUid = uid;
                safeJsonPut(result, "delivery_uid", uid);

                safeJsonPut(result, "start_ms", job.startMs);
                safeJsonPut(result, "end_ms", job.endMs);

                safeJsonPut(result, "start_utc", msToUtcIso(job.startMs));
                safeJsonPut(result, "end_utc", msToUtcIso(job.endMs));
                safeJsonPut(result, "duration_ms", (job.endMs - job.startMs));
                safeJsonPut(result, "duration_s", (job.endMs - job.startMs) / 1000.0);

                safeJsonPut(result, "gross_delta", gdU);
                safeJsonPut(result, "net_delta", ndU);

                safeJsonPut(result, "gross_total", job.grossTotalRaw & 0xFFFFFFFFL);
                safeJsonPut(result, "net_total", job.netTotalRaw & 0xFFFFFFFFL);

                safeJsonPut(result, "inventory_written", JSONObject.NULL);
                safeJsonPut(result, "host_printed", true);

                safeJsonPut(result, "gross_delta_l", gdU / scale);
                safeJsonPut(result, "net_delta_l", ndU / scale);
                safeJsonPut(result, "gross_total_l", (job.grossTotalRaw & 0xFFFFFFFFL) / scale);
                safeJsonPut(result, "net_total_l", (job.netTotalRaw & 0xFFFFFFFFL) / scale);

                safeJsonPut(data, "state_job", "DONE");
                safeJsonPut(data, "armed", 0);
                safeJsonPut(data, "state", DeliveryState.CONNECTED.name());
                safeJsonPut(data, "live_status", "LIVE: CONNECTED - Ready");
                safeJsonPut(data, "available_actions", new JSONArray());
                safeJsonPut(data, "result", result);

                return ApiResult.ok("Job: 1 - DONE", data);
            }

            // RUNNING
            job.state = "RUNNING";
            safeJsonPut(data, "state_job", "RUNNING");
            safeJsonPut(data, "armed", 0);
            safeJsonPut(data, "state", state.name());

            JSONArray a = new JSONArray();
            a.put("CONTINUER");
            a.put("TERMINER");
            safeJsonPut(data, "available_actions", a);

            safeJsonPut(data, "live_status", (state == DeliveryState.RUNNING_PAUSED)
                    ? "LIVE: RUNNING_PAUSED (FLOW OFF confirmed)"
                    : "LIVE: RUNNING_FLOWING");

            return ApiResult.ok("Job: 1 - RUNNING", data);

        } catch (Exception e) {
            JSONObject d = new JSONObject();
            safeJsonPut(d, "detail", (e.getMessage() != null) ? e.getMessage() : "");
            return ApiResult.fail("Job: 0 - Read error", "JOB_READ_FAIL", d);
        }
    }
}
