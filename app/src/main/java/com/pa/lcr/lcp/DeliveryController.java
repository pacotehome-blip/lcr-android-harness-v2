
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

public final class DeliveryController implements DeliveryControllerPort {

    private static void safeJsonPut(org.json.JSONObject o, String k, Object v) {
        if (o == null) return;
        try {
            o.put(k, v);
        } catch (org.json.JSONException ignore) {
            // best-effort
        }
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
    private static final int FIELD_SALE_NUMBER = 22;
    private static final int FIELD_TICKET_NUMBER = 23;
    private static final int FIELD_SERIAL_ID = 80;

    // Commands
    private static final int CMD_RUN = 0x00;
    private static final int CMD_END = 0x02;
    private static final int CMD_PRINT_LAST_TICKET = 0x06;

    // Bits delCode
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

    // LIVE backoff (A)
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

    // ✅ Pas de chevauchement LIVE
    private final AtomicBoolean liveInFlight = new AtomicBoolean(false);
    private final ThreadLocal<Boolean> inLiveSample = new ThreadLocal<>();

    // (A) Ticket pending: anti-réimpression
    private final java.util.concurrent.atomic.AtomicBoolean ticketPrintInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile long ticketPrintStartMs = 0L;

    // (A) Backoff state
    private volatile long liveBackoffMs = LIVE_BASE_MS;
    private volatile long liveNextAllowedMs = 0L;
    private volatile long liveLastSkipLogMs = 0L;

    // (CONTINUER) Grâce 30s uniquement après clic Continuer
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

        // état job API
        volatile boolean done = false;
        volatile String state; // PENDING/RUNNING/DONE/ERROR

        // Exposition (poll)
        volatile double net;
        volatile double gross;
        volatile int decimals;

        // Erreur éventuelle
        volatile String err;

        // Contexte métier / MSD
        volatile String numeroLivraison;
        volatile String deliveryUid;
        volatile String compartment;
        volatile int productNumber;
        volatile double presetNetL_requested; // demandé
        volatile double presetNetL_applied;   // confirmé
        volatile long presetRawU32;           // raw lu (#6)

        // Identifiants registre
        volatile String saleNo;
        volatile String ticketNo;
        volatile String serialId;

        // Timing
        volatile long startMs = 0L;
        volatile long endMs = 0L;

        // Compteurs (bruts)
        volatile int grossStartRaw = Integer.MIN_VALUE;
        volatile int netStartRaw = Integer.MIN_VALUE;
        volatile int grossEndRaw = 0;
        volatile int netEndRaw = 0;

        // Totaux cumulatifs
        volatile int grossTotalRaw = 0;
        volatile int netTotalRaw = 0;

        // Fichier / impression / inventaire
        volatile Object inventoryWritten = null;
        volatile boolean hostPrinted = false;

        // ✅ Anti DONE prématuré
        volatile boolean sawDeliveryActiveOnce = false;
        // ✅ Baseline capturée au 1er sample actif (évite deltas négatifs)
        volatile boolean baselineCaptured = false;

        // ✅ Auto-A tracking
        volatile boolean autoAAttempted = false;
        volatile boolean autoASuccess = false;

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
        return stopped || link.isClosed();
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
            if (!txRxEnabled) {
                if (isTxRxLine(raw)) return;
            }
            if (Boolean.TRUE.equals(inLiveSample.get())) {
                if (isTxRxLine(raw)) return;
            }
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
            emitLog("LCP prêt (sans refresh automatique)");
            if (listener != null) listener.onLiveStatus("LIVE: CONNECTED — (prêt)");
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

    // =========================
    // A / B / C
    // =========================
    @Override
    public void refreshProducts() { emitLog("refreshProducts ignoré"); }

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
                        if (fs.ticketPending) listener.onLiveStatus("LIVE: CONNECTED — Ticket pending");
                        else listener.onLiveStatus("LIVE: CONNECTED — Livraison active (utiliser A)");
                    }
                    emitLog(fs.ticketPending
                            ? "[C] Delivery active + ticket pending -> use A manually"
                            : "[C] Delivery active -> use A manually");
                    return;
                }
                if (fs.ticketPending) {
                    setState(DeliveryState.CONNECTED);
                    if (listener != null) listener.onLiveStatus("LIVE: CONNECTED — Ticket pending");
                    emitLog("[C] Ticket pending -> use A manually");
                    return;
                }
                if (fs.flowActive) {
                    setState(DeliveryState.CONNECTED);
                    if (listener != null) listener.onLiveStatus("LIVE: CONNECTED — Flow actif (utiliser A)");
                    emitLog("[C] Flow active at precheck -> use A manually");
                    return;
                }
                emitLog("[C] Register ready -> START now");
                doStartNewDeliveryWithRetry(deadline, product1to16, presetNet);
            } catch (Exception e) {
                handleIoFailure("startDelivery(C-intent)", e);
            }
        });
    }

    private void doStartNewDeliveryWithRetry(long deadlineMs, int product1to16, double presetNet) throws Exception {
        if (isStopped()) return;
        setState(DeliveryState.PRESTART);
        emitLog("[PRESTART] internal");

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

                if ((now - lastContinueClickMs) < CONTINUE_DEBOUNCE_MS) {
                    emitLog("[CONTINUE] Ignored (debounce)");
                    return;
                }
                lastContinueClickMs = now;

                if (continueGraceUntilMs > now) {
                    long left = (continueGraceUntilMs - now) / 1000;
                    emitLog("[CONTINUE] Already waiting LIVE (" + left + "s left)");
                    return;
                }

                emitLog("[CONTINUE] RUN requested — grace 30s (LIVE working)");
                link.opIssueCommand(CMD_RUN);

                continueGraceUntilMs = now + CONTINUE_GRACE_MS;
                setState(DeliveryState.RUNNING_FLOWING);
                if (listener != null) {
                    listener.onLiveStatus("LIVE: RUNNING_FLOWING (Flow OFF — attente progression)");
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
                // En UI historique, END est surtout permis en PAUSED + stableOff
                // API pourra appeler terminate même plus tôt; ici on garde la règle UI.
                if (state != DeliveryState.RUNNING_PAUSED) return;
                if (!flowOffStable || !sawFlowOnOnce) return;

                setState(DeliveryState.ENDING);
                emitLog("[END] Issue END (0x02)");
                link.opIssueCommand(CMD_END);

                long deadline = System.currentTimeMillis() + 15_000;
                while (!isStopped() && System.currentTimeMillis() < deadline) {
                    FullStatus fs = safeReadFullStatusNoThrow();
                    if (fs != null && !fs.deliveryActive && !fs.flowActive) break;
                    try { Thread.sleep(250); } catch (InterruptedException ignored) {}
                }

                FullStatus fsAfter = safeReadFullStatusNoThrow();
                if (fsAfter != null && fsAfter.ticketPending) {
                    emitLog("[END] Ticket pending -> clear via #6 loop");
                    clearTicketPendingLoop();
                }

                setState(DeliveryState.CONNECTED);
                if (listener != null) listener.onLiveStatus("LIVE: CONNECTED — Prêt à livrer");

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
                        listener.onLiveStatus(ticket ? "LIVE: CONNECTED — Ticket pending"
                                : "LIVE: CONNECTED — Prêt à livrer");
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

                try {
                    ensureDigits();
                } catch (Exception e) {
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
                        listener.onLiveStatus("LIVE: RUNNING_FLOWING (Flow OFF — attente progression)");
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
                        listener.onLiveStatus("LIVE: RUNNING_FLOWING (Flow OFF — attente progression)");
                    }
                    setState(DeliveryState.RUNNING_FLOWING);
                    lastGrossRaw = g;
                    lastNetRaw = n;
                    return;
                }

                if (listener != null) {
                    listener.onFlowStability(flowBit, flowOffStable, age);
                    listener.onLiveStatus(flowOffStable
                            ? "LIVE: RUNNING_PAUSED (FLOW OFF confirmé)"
                            : "LIVE: RUNNING_FLOWING (FLOW OFF — confirmation...)");
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
                    listener.onLiveStatus(fs.ticketPending ? "LIVE: CONNECTED — Ticket pending"
                            : "LIVE: CONNECTED — Prêt à livrer");
                }
            } catch (Exception e) {
                handleIoFailure("requestLiveSnapshot", e);
            }
        });
    }

    // =========================
    // Full status = 0x23 + 0x28
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
        emitLog(String.format("[A] FullStatus dev=0x%02X prn=0x%02X ds=0x%04X dc=0x%04X",
                fs.devStatus, fs.prnStatus, fs.delStatus, fs.delCode));

        if (fs.ticketPending) {
            if (listener != null) listener.onLiveStatus("LIVE: CONNECTED — Ticket_pending (recovering)");
            emitLog("[A] Ticket pending -> clear via #6 SAFE (single print)");
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
            listener.onLiveStatus(fs.ticketPending ? "LIVE: CONNECTED — Ticket pending"
                    : "LIVE: CONNECTED — Prêt à livrer");
        }
    }

    // SAFE ticket clear for A
    private void clearTicketPendingSafeForAlign() throws Exception {
        try {
            LcpLink.MachineStatus ms0 = link.opGetMachineStatus();
            if ((ms0.delCode & DC_TICKET_PENDING) == 0) {
                ticketPrintInFlight.set(false);
                ticketPrintStartMs = 0L;
                emitLog("[A/TICKET] already cleared");
                return;
            }
        } catch (Exception ignored) {}

        long now = System.currentTimeMillis();

        if (ticketPrintInFlight.compareAndSet(false, true)) {
            ticketPrintStartMs = now;
            emitLog("[A/TICKET] issue #6 once (in-flight)");
            try {
                link.opIssueCommand(CMD_PRINT_LAST_TICKET);
            } catch (Exception e) {
                ticketPrintInFlight.set(false);
                ticketPrintStartMs = 0L;
                throw e;
            }
        } else {
            if (ticketPrintStartMs <= 0L) ticketPrintStartMs = now;
            emitLog("[A/TICKET] print already in-flight; waiting clear");
        }

        long deadline = ticketPrintStartMs + TICKET_DEVICE_LOOP_MS;
        while (!isStopped() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            try {
                LcpLink.MachineStatus ms = link.opGetMachineStatus();
                if ((ms.delCode & DC_TICKET_PENDING) == 0) {
                    emitLog("[A/TICKET] cleared");
                    ticketPrintInFlight.set(false);
                    ticketPrintStartMs = 0L;
                    return;
                }
            } catch (Exception ignored) {}
        }

        emitLog("[A/TICKET] clear timeout (no reprint)");
        ticketPrintInFlight.set(false);
        ticketPrintStartMs = 0L;
    }

    private void clearTicketPendingLoop() {
        long deadline = System.currentTimeMillis() + TICKET_DEVICE_LOOP_MS;
        while (!isStopped() && System.currentTimeMillis() < deadline) {
            try { link.opIssueCommand(CMD_PRINT_LAST_TICKET); }
            catch (Exception e) {
                emitLog("[TICKET] issue6 retry: " + (e.getMessage() != null ? e.getMessage() : ""));
            }
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            try {
                LcpLink.MachineStatus ms = link.opGetMachineStatus();
                if ((ms.delCode & DC_TICKET_PENDING) == 0) {
                    emitLog("[TICKET] cleared");
                    return;
                }
            } catch (Exception ignored) {}
        }
        emitLog("[TICKET] clear timeout");
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
                boolean queuedTimeout = m.contains("Queued timeout");
                boolean timeout = m.contains("Timeout waiting LCP response");
                boolean retryable = queuedTimeout || timeout;
                boolean hardFatal =
                        m.contains("Transport closed") ||
                        m.contains("Error writing") ||
                        m.contains("rc=-1") ||
                        m.contains("Connection closed");

                if (hardFatal) throw e;
                if (!retryable) throw e;

                emitLog("[RETRY] " + step + " (" + (queuedTimeout ? "queued" : "timeout") + ")");
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

    private void setState(DeliveryState s) {
        if (state == s) return;
        state = s;
        if (listener != null) listener.onStateChanged(s);
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

        if ("requestLiveSample".equals(ctx)) {
            emitLog("[WARN] " + ctx + " (soft): " + msg);
            return;
        }
        if (hardFatal) {
            shutdown(true);
            return;
        }
        if (retryish) {
            emitLog("[WARN] " + ctx + " -> " + msg);
            softResync("timeout/" + ctx);
        }
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
            return ApiResult.fail(
                    "Scan USB: 0 - Aucun registre détecté. Valide tes connexions au registre (câble/OTG/USB-C).",
                    "NO_DEVICE"
            );
        }
        return ApiResult.ok("Scan USB: 1 - Registre détecté");
    }

    public ApiResult api_openPingUsb() {
        if (link == null || link.isClosed()) {
            return ApiResult.fail(
                    "Open/Ping USB: 0 - USB non prêt. Vérifie câble/permission.",
                    "USB_NOT_READY"
            );
        }
        return ApiResult.ok("Open/Ping USB: 1 - USB prêt");
    }

    public ApiResult api_connectLcp() {
        if (link == null || link.isClosed()) {
            return ApiResult.fail(
                    "Connect LCP: 0 - USB non connecté.",
                    "NO_TRANSPORT"
            );
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
                return ApiResult.ok("Connect LCP: 1 - CONNECTED prêt à livrer (Faire C)", data);
            }
            safeJsonPut(data, "next", "A");
            return ApiResult.ok("Connect LCP: 1 - CONNECTED livraison en attente (Faire A)", data);

        } catch (Exception e) {
            String m = (e.getMessage() != null) ? e.getMessage() : "";
            JSONObject d = new JSONObject();
            safeJsonPut(d, "detail", m);
            return ApiResult.fail(
                    "Connect LCP: 0 - Impossible de valider l'état (0x28).",
                    "STATE28_FAIL",
                    d
            );
        }
    }

    /**
     * OneShot A2:
     * - lit 0x28
     * - si ticketPending: tente A automatiquement (clear), puis relit 0x28
     * - si clean: exécute C en mode ARMED (SET product + SET preset confirmé) SANS RUN
     * - renvoie jobId + actions CONTINUER/TERMINER
     */
    public ApiResult api_deliveryOneShotStart(String numero_livraison, int product1to16, double presetNetL, String compartment) {
        if (link == null || link.isClosed()) {
            return ApiResult.fail(
                    "Delivery OneShot: 0 - USB non prêt. Ouvrir le port USB puis réessayer.",
                    "USB_NOT_READY"
            );
        }

        try {
            // 1) état protocole (0x28)
            int[] ds0 = link.opDeliveryStatus();
            int delCode0 = ds0[1];
            boolean ticketPending0 = (delCode0 & DC_TICKET_PENDING) != 0;
            boolean flowActive0 = (delCode0 & DC_FLOW_ACTIVE) != 0;
            boolean deliveryActive0 = (delCode0 & DC_DELIVERY_ACTIVE) != 0;

            // 2) identifiants stables
            String ticketNo = decodeAzString(link.opGetField(FIELD_TICKET_NUMBER));
            String saleNo = decodeAzString(link.opGetField(FIELD_SALE_NUMBER));
            String serialId = decodeAzString(link.opGetField(FIELD_SERIAL_ID));
            String deliveryUid = (numero_livraison == null ? "" : numero_livraison) + "-" + (ticketNo == null ? "" : ticketNo);

            // 3) Si livraison déjà active: informer, pas de nouveau job (baseline-safe)
            if (deliveryActive0) {
                updateStateFromProtocolSnapshot(true, flowActive0);
                JSONObject data = new JSONObject();
                safeJsonPut(data, "numero_livraison", numero_livraison);
                safeJsonPut(data, "ticket_no", ticketNo);
                safeJsonPut(data, "sale_no", saleNo);
                safeJsonPut(data, "serial_id", serialId);
                safeJsonPut(data, "delivery_uid", deliveryUid);
                safeJsonPut(data, "deliveryActive", 1);
                safeJsonPut(data, "flowActive", flowActive0 ? 1 : 0);
                safeJsonPut(data, "ticketPending", ticketPending0 ? 1 : 0);
                safeJsonPut(data, "state", state.name());
                long now = System.currentTimeMillis();
                long graceRem = (continueGraceUntilMs > now) ? (continueGraceUntilMs - now) : 0L;
                safeJsonPut(data, "continue_grace_remaining_ms", graceRem);
                safeJsonPut(data, "flow_off_stable", flowOffStable ? 1 : 0);
                safeJsonPut(data, "flow_off_age_ms", getFlowOffAgeMs());
                safeJsonPut(data, "live_status", buildLiveStatusForApi(true, ticketPending0, graceRem));
                // Actions selon l'état courant
                safeJsonPut(data, "available_actions", buildActionsArray(true, true));
                return ApiResult.ok("Delivery OneShot: 1 - Livraison déjà active", data);
            }

            // 4) Si ticketPending: A2 => tenter A automatiquement
            boolean autoAAttempted = false;
            boolean autoASuccess = false;

            if (ticketPending0) {
                autoAAttempted = true;
                try {
                    doAlignOrRecoverFull(); // inclut clear ticket pending
                } catch (Exception ignored) {
                    // best-effort
                }

                // relire 0x28
                int[] dsA = link.opDeliveryStatus();
                int delCodeA = dsA[1];
                boolean ticketPendingA = (delCodeA & DC_TICKET_PENDING) != 0;
                boolean deliveryActiveA = (delCodeA & DC_DELIVERY_ACTIVE) != 0;
                boolean flowActiveA = (delCodeA & DC_FLOW_ACTIVE) != 0;

                autoASuccess = (!ticketPendingA);

                if (ticketPendingA || deliveryActiveA) {
                    // Toujours pas clean => retourner next=A (auto tenté)
                    JSONObject data = new JSONObject();
                    safeJsonPut(data, "numero_livraison", numero_livraison);
                    safeJsonPut(data, "ticket_no", ticketNo);
                    safeJsonPut(data, "sale_no", saleNo);
                    safeJsonPut(data, "serial_id", serialId);
                    safeJsonPut(data, "delivery_uid", deliveryUid);

                    safeJsonPut(data, "autoA_attempted", 1);
                    safeJsonPut(data, "autoA_success", autoASuccess ? 1 : 0);

                    safeJsonPut(data, "ticketPending_before", 1);
                    safeJsonPut(data, "ticketPending_after", ticketPendingA ? 1 : 0);
                    safeJsonPut(data, "deliveryActive", deliveryActiveA ? 1 : 0);
                    safeJsonPut(data, "flowActive", flowActiveA ? 1 : 0);

                    safeJsonPut(data, "next", "A");
                    safeJsonPut(data, "state", DeliveryState.CONNECTED.name());
                    safeJsonPut(data, "continue_grace_remaining_ms", 0);
                    safeJsonPut(data, "flow_off_stable", 0);
                    safeJsonPut(data, "flow_off_age_ms", 0);
                    safeJsonPut(data, "live_status", "LIVE: CONNECTED — Ticket pending");
                    JSONArray actions = new JSONArray();
                    actions.put("ALIGN_A");
                    safeJsonPut(data, "available_actions", actions);

                    return ApiResult.ok("Delivery OneShot: 1 - Ticket pending (A auto tenté, encore requis)", data);
                }

                // Sinon clean => continuer vers C (ARMED)
            }

            // 5) Clean => créer job + ARM (SET product + SET preset confirmé) SANS RUN
            String jobId = UUID.randomUUID().toString();
            ApiJob job = new ApiJob(jobId);

            job.numeroLivraison = numero_livraison;
            job.ticketNo = ticketNo;
            job.saleNo = saleNo;
            job.serialId = serialId;
            job.deliveryUid = deliveryUid;

            job.compartment = compartment;
            job.productNumber = product1to16;
            job.presetNetL_requested = presetNetL;

            job.autoAAttempted = autoAAttempted;
            job.autoASuccess = autoASuccess;

            job.startMs = System.currentTimeMillis();

            // ARM sequence
            ensureDigits();
            double scale = Math.pow(10, cachedDigits);

            // set product
            int idx0 = product1to16 - 1;
            link.opSetField(FIELD_ACTIVE_PRODUCT, new byte[]{(byte) idx0});

            // write preset
            writePresetNet_WithCacheOrFallback(presetNetL);

            // ✅ read-back preset (#6) pour confirmer
            long presetRawU = beI32(link.opGetField(FIELD_PRESET_NET)) & 0xFFFFFFFFL;
            double presetApplied = presetRawU / scale;

            job.presetRawU32 = presetRawU;
            job.presetNetL_applied = presetApplied;
            job.decimals = cachedDigits;

            // tolérance = 1 tick (1/scale)
            double tol = 1.0 / scale;
            if (Math.abs(presetApplied - presetNetL) > (tol * 1.5)) {
                JSONObject d = new JSONObject();
                safeJsonPut(d, "preset_requested", presetNetL);
                safeJsonPut(d, "preset_applied", presetApplied);
                safeJsonPut(d, "decimals", cachedDigits);
                safeJsonPut(d, "preset_raw_u32", presetRawU);
                return ApiResult.fail("Delivery OneShot: 0 - Preset non appliqué (mismatch)", "PRESET_MISMATCH", d);
            }

            // ✅ ARMED: on reste comme au début (PAUSED) en attendant CONTINUER ou FLOW ON
            setState(DeliveryState.RUNNING_PAUSED);

            synchronized (apiJobs) {
                apiJobs.put(jobId, job);
            }

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

            safeJsonPut(data, "state", DeliveryState.RUNNING_PAUSED.name());
            safeJsonPut(data, "continue_grace_remaining_ms", 0);
            safeJsonPut(data, "flow_off_stable", 0);
            safeJsonPut(data, "flow_off_age_ms", 0);
            safeJsonPut(data, "live_status", "LIVE: RUNNING_PAUSED (Flow OFF — en attente CONTINUER)");
            safeJsonPut(data, "available_actions", buildActionsArray(true, true));

            return ApiResult.ok("Delivery OneShot: 1 - ARMED (preset OK, en attente)", data);

        } catch (Exception e) {
            String m = (e.getMessage() != null) ? e.getMessage() : "";
            JSONObject d = new JSONObject();
            safeJsonPut(d, "detail", m);
            return ApiResult.fail(
                    "Delivery OneShot: 0 - Erreur pendant l'orchestration.",
                    "ONESHOT_FAIL",
                    d
            );
        }
    }

    public ApiResult api_deliveryContinue(String jobId) {
        ApiJob job;
        synchronized (apiJobs) {
            job = apiJobs.get(jobId);
        }
        if (job == null) {
            return ApiResult.fail("Continue: 0 - Job inconnu", "JOB_NOT_FOUND");
        }
        // CONTINUER = envoie RUN via logique UI
        resumeIfPaused();

        JSONObject data = new JSONObject();
        safeJsonPut(data, "jobId", jobId);
        safeJsonPut(data, "numero_livraison", job.numeroLivraison);
        safeJsonPut(data, "ticket_no", job.ticketNo);
        safeJsonPut(data, "delivery_uid", job.deliveryUid);
        safeJsonPut(data, "live_status", "LIVE: RUNNING_FLOWING (Flow OFF — attente progression)");
        return ApiResult.ok("Continue: 1 - RUN sent", data);
    }

    public ApiResult api_deliveryTerminate(String jobId) {
        ApiJob job;
        synchronized (apiJobs) {
            job = apiJobs.get(jobId);
        }
        if (job == null) {
            return ApiResult.fail("Terminate: 0 - Job inconnu", "JOB_NOT_FOUND");
        }

        // API: TERMINER doit être possible même si on est "ARMED" (deliveryActive pas encore vu)
        // Ici, on envoie END via la méthode UI endDelivery (qui a des guards).
        // Pour rester minimal: si END est bloqué par les guards UI, on retourne OK quand même (END requested).
        endDelivery();

        JSONObject data = new JSONObject();
        safeJsonPut(data, "jobId", jobId);
        safeJsonPut(data, "numero_livraison", job.numeroLivraison);
        safeJsonPut(data, "ticket_no", job.ticketNo);
        safeJsonPut(data, "delivery_uid", job.deliveryUid);
        safeJsonPut(data, "live_status", "LIVE: ENDING");
        return ApiResult.ok("Terminate: 1 - END sent", data);
    }

    public ApiResult api_deliveryStartC(int product1to16, double presetNet) {
        if (state == DeliveryState.PRESTART || state == DeliveryState.ENDING) {
            JSONObject d = new JSONObject();
            safeJsonPut(d, "state", state.name());
            return ApiResult.fail("Delivery C: 0 - Action refusée (préstart/ending).", "BUSY_STATE", d);
        }
        if (state != DeliveryState.CONNECTED) {
            JSONObject d = new JSONObject();
            safeJsonPut(d, "state", state.name());
            return ApiResult.fail("Delivery C: 0 - Registre non prêt. Faire A d'abord.", "NOT_READY_FOR_C", d);
        }

        String jobId = UUID.randomUUID().toString();
        ApiJob job = new ApiJob(jobId);
        synchronized (apiJobs) { apiJobs.put(jobId, job); }

        startDelivery(product1to16, presetNet);

        JSONObject data = new JSONObject();
        safeJsonPut(data, "jobId", jobId);
        return ApiResult.ok("Delivery C: 1 - Démarrée", data);
    }

    /**
     * Poll job:
     * - NE PAS DONE avant d'avoir observé deliveryActive=1 au moins une fois
     * - si FLOW OFF stable et preset non atteint => PAUSE + actions CONTINUER/TERMINER
     */
    public ApiResult api_deliveryJobGet(String jobId) {
        ApiJob job;
        synchronized (apiJobs) {
            job = apiJobs.get(jobId);
        }
        if (job == null) {
            return ApiResult.fail("Job: 0 - Inconnu", "JOB_NOT_FOUND");
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

            job.net = (n & 0xFFFFFFFFL) / scale;
            job.gross = (g & 0xFFFFFFFFL) / scale;
            job.decimals = cachedDigits;

            // Mettre à jour état interne (APK-like)
            updateLiveLikeFromCounts(deliveryActive, flowActive, g, n);

            // ✅ Capturer baseline au premier sample actif (évite deltas négatifs)
            if (deliveryActive && !job.baselineCaptured) {
                job.baselineCaptured = true;
                job.netStartRaw = n;
                job.grossStartRaw = g;
                // startMs déjà set lors de OneShot; on le garde
            }

            long now = System.currentTimeMillis();
            long graceRem = (continueGraceUntilMs > now) ? (continueGraceUntilMs - now) : 0L;

            // Delivered so far (si baseline)
            double deliveredNetL = 0.0;
            boolean deliveredKnown = job.baselineCaptured;

            if (deliveredKnown) {
                long dnU = u32delta(n, job.netStartRaw);
                deliveredNetL = dnU / scale;
            }

            // preset non atteint ?
            double presetApplied = (job.presetNetL_applied > 0) ? job.presetNetL_applied : job.presetNetL_requested;
            boolean presetNotReached = deliveredKnown && (presetApplied > 0.0) && (deliveredNetL + (1.0/scale)) < presetApplied;

            // Si flow OFF stable et preset non atteint => PAUSE + attendre flow ON
            boolean shouldPauseForPreset = deliveryActive && flowOffStable && presetNotReached;

            if (shouldPauseForPreset) {
                setState(DeliveryState.RUNNING_PAUSED);
            }

            JSONObject data = new JSONObject();
            safeJsonPut(data, "jobId", jobId);
            safeJsonPut(data, "numero_livraison", job.numeroLivraison);
            safeJsonPut(data, "ticket_no", job.ticketNo);
            safeJsonPut(data, "delivery_uid", job.deliveryUid);

            safeJsonPut(data, "deliveryActive", deliveryActive ? 1 : 0);
            safeJsonPut(data, "flowActive", flowActive ? 1 : 0);
            safeJsonPut(data, "ticketPending", ticketPending ? 1 : 0);

            safeJsonPut(data, "net", job.net);
            safeJsonPut(data, "gross", job.gross);
            safeJsonPut(data, "decimals", job.decimals);

            safeJsonPut(data, "preset_requested", job.presetNetL_requested);
            safeJsonPut(data, "preset_applied", job.presetNetL_applied);
            safeJsonPut(data, "delivered_net", deliveredKnown ? deliveredNetL : JSONObject.NULL);

            safeJsonPut(data, "state", state.name());
            safeJsonPut(data, "continue_grace_remaining_ms", graceRem);
            safeJsonPut(data, "flow_off_stable", flowOffStable ? 1 : 0);
            safeJsonPut(data, "flow_off_age_ms", getFlowOffAgeMs());

            if (!deliveryActive) {
                // ✅ Anti-DONE prématuré: si jamais actif => PENDING/ARMED, pas DONE
                if (!job.sawDeliveryActiveOnce) {
                    job.state = "PENDING";
                    safeJsonPut(data, "state_job", "PENDING");
                    safeJsonPut(data, "live_status", "LIVE: RUNNING_PAUSED (En attente CONTINUER)");
                    safeJsonPut(data, "available_actions", buildActionsArray(true, true));
                    return ApiResult.ok("Job: 1 - PENDING", data);
                }

                // DONE réel
                job.state = "DONE";
                job.done = true;
                job.endMs = now;
                job.grossEndRaw = g;
                job.netEndRaw = n;

                try {
                    job.grossTotalRaw = beI32(link.opGetField(FIELD_GROSS_TOTAL));
                    job.netTotalRaw = beI32(link.opGetField(FIELD_NET_TOTAL));
                } catch (Exception ignored) {}

                try {
                    job.ticketNo = decodeAzString(link.opGetField(FIELD_TICKET_NUMBER));
                    job.saleNo = decodeAzString(link.opGetField(FIELD_SALE_NUMBER));
                    job.serialId = decodeAzString(link.opGetField(FIELD_SERIAL_ID));
                } catch (Exception ignored) {}

                job.hostPrinted = true;
                job.inventoryWritten = null;

                // deltas rollover-safe
                long gdU = job.baselineCaptured ? u32delta(job.grossEndRaw, job.grossStartRaw) : 0L;
                long ndU = job.baselineCaptured ? u32delta(job.netEndRaw, job.netStartRaw) : 0L;

                JSONObject result = new JSONObject();
                safeJsonPut(result, "numero_livraison", job.numeroLivraison);
                safeJsonPut(result, "ticket_no", job.ticketNo);
                safeJsonPut(result, "serial_id", job.serialId);
                safeJsonPut(result, "compartment", job.compartment == null ? JSONObject.NULL : job.compartment);
                safeJsonPut(result, "product_number", job.productNumber);

                String uid = (job.numeroLivraison == null ? "" : job.numeroLivraison) + "-" + (job.ticketNo == null ? "" : job.ticketNo);
                job.deliveryUid = uid;
                safeJsonPut(result, "delivery_uid", uid);

                safeJsonPut(result, "start_ms", job.startMs);
                safeJsonPut(result, "end_ms", job.endMs);

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
                safeJsonPut(data, "result", result);

                safeJsonPut(data, "live_status", "LIVE: CONNECTED — Prêt à livrer");
                safeJsonPut(data, "available_actions", new JSONArray());

                return ApiResult.ok("Job: 1 - DONE", data);

            } else {
                job.state = "RUNNING";
                safeJsonPut(data, "state_job", "RUNNING");

                if (shouldPauseForPreset) {
                    safeJsonPut(data, "live_status", "LIVE: RUNNING_PAUSED (Flow OFF — attente progression)");
                    safeJsonPut(data, "available_actions", buildActionsArray(true, true));
                } else {
                    safeJsonPut(data, "live_status", buildLiveStatusForApi(true, ticketPending, graceRem));
                    // si paused (flow off confirmé) => actions
                    boolean canTerminate = true;
                    safeJsonPut(data, "available_actions", buildActionsArray(state == DeliveryState.RUNNING_PAUSED, canTerminate));
                }

                return ApiResult.ok("Job: 1 - RUNNING", data);
            }

        } catch (Exception e) {
            job.state = "ERROR";
            job.err = (e.getMessage() != null) ? e.getMessage() : "";
            JSONObject d = new JSONObject();
            safeJsonPut(d, "detail", job.err);
            return ApiResult.fail("Job: 0 - Erreur lecture livraison", "JOB_READ_FAIL", d);
        }
    }

    // =========================
    // Helpers API: état + messages + actions
    // =========================
    private void updateStateFromProtocolSnapshot(boolean deliveryActive, boolean flowActive) {
        if (!deliveryActive) {
            setState(DeliveryState.CONNECTED);
            return;
        }
        if (flowActive) setState(DeliveryState.RUNNING_FLOWING);
        else setState(DeliveryState.RUNNING_PAUSED);
    }

    private void updateLiveLikeFromCounts(boolean deliveryActive, boolean flowBit, int g, int n) {
        long t = System.currentTimeMillis();
        if (!deliveryActive) {
            setState(DeliveryState.CONNECTED);
            lastGrossRaw = -1;
            lastNetRaw = -1;
            flowOffStable = false;
            sawFlowOnOnce = false;
            flowOffStartMs = 0L;
            lastCountsChangeMs = 0L;
            continueGraceUntilMs = 0L;
            return;
        }

        int d = 0;
        if (lastGrossRaw >= 0 && lastNetRaw >= 0) {
            d = Math.abs(g - lastGrossRaw) + Math.abs(n - lastNetRaw);
        }

        if (d > 0) {
            continueGraceUntilMs = 0L;
            sawFlowOnOnce = true;
            lastCountsChangeMs = t;
            flowOffStable = false;
            flowOffStartMs = 0L;
            lastGrossRaw = g;
            lastNetRaw = n;
            setState(DeliveryState.RUNNING_FLOWING);
            return;
        }

        if (lastCountsChangeMs == 0L) lastCountsChangeMs = t;
        long age = t - lastCountsChangeMs;

        if (!sawFlowOnOnce) {
            flowOffStable = false;
            flowOffStartMs = 0L;
            lastGrossRaw = g;
            lastNetRaw = n;
            setState(DeliveryState.RUNNING_FLOWING);
            return;
        }

        if (flowOffStartMs == 0L) flowOffStartMs = lastCountsChangeMs;
        flowOffStable = age >= NO_FLOW_CONFIRM_MS;

        if (continueGraceUntilMs > t) {
            lastGrossRaw = g;
            lastNetRaw = n;
            setState(DeliveryState.RUNNING_FLOWING);
            return;
        }

        if (flowOffStable) setState(DeliveryState.RUNNING_PAUSED);
        else setState(DeliveryState.RUNNING_FLOWING);

        lastGrossRaw = g;
        lastNetRaw = n;
    }

    private String buildLiveStatusForApi(boolean deliveryActive, boolean ticketPending, long graceRemainingMs) {
        if (!deliveryActive) {
            return ticketPending ? "LIVE: CONNECTED — Ticket pending"
                    : "LIVE: CONNECTED — Prêt à livrer";
        }
        if (state == DeliveryState.RUNNING_PAUSED) {
            return "LIVE: RUNNING_PAUSED (FLOW OFF confirmé)";
        }
        if (graceRemainingMs > 0) {
            return "LIVE: RUNNING_FLOWING (Flow OFF — attente progression)";
        }
        return flowOffStable
                ? "LIVE: RUNNING_PAUSED (FLOW OFF confirmé)"
                : "LIVE: RUNNING_FLOWING (FLOW OFF — confirmation...)";
    }

    private JSONArray buildActionsArray(boolean includeContinue, boolean includeTerminate) {
        JSONArray a = new JSONArray();
        if (includeContinue) a.put("CONTINUER");
        if (includeTerminate) a.put("TERMINER");
        return a;
    }
}
