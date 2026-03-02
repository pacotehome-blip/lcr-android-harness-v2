
package com.pa.lcr.lcp;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DeliveryController implements DeliveryControllerPort {

    // Champs LCR
    private static final int FIELD_ACTIVE_PRODUCT = 0;
    private static final int FIELD_PRESET_NET = 6;
    private static final int FIELD_DECIMALS = 39;
    private static final int FIELD_GROSS_COUNT = 44;
    private static final int FIELD_NET_COUNT = 45;

    // Commands
    private static final int CMD_RUN = 0x00;
    private static final int CMD_END = 0x02;
    private static final int CMD_PRINT_LAST_TICKET = 0x06;

    // Bits delCode (LCRSc_* côté Python)
    private static final int DC_TICKET_PENDING = 0x0001;
    private static final int DC_FLOW_ACTIVE = 0x0004;
    private static final int DC_DELIVERY_ACTIVE = 0x0008;

    // OFF conservateur
    private static final long NO_FLOW_CONFIRM_MS = 10_000;

    // START retry: équivalent --start-timeout 20, --poll 0.2
    private static final long START_RETRY_WINDOW_MS = 20_000;
    private static final long START_RETRY_POLL_MS = 200;

    // Ticket loop (Python-like)
    private static final long TICKET_DEVICE_LOOP_MS = 30_000;

    // LIVE backoff (A)
    private static final long LIVE_BASE_MS = 200;     // nominal
    private static final long LIVE_MAX_MS  = 2000;    // plafond backoff
    private static final long LIVE_LOG_THROTTLE_MS = 1000; // max 1 log/sec en mode skip

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

    // (A) Backoff state
    private volatile long liveBackoffMs = LIVE_BASE_MS;
    private volatile long liveNextAllowedMs = 0L;
    private volatile long liveLastSkipLogMs = 0L;

    private static final ThreadLocal<SimpleDateFormat> IO_DF =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("HH:mm:ss.SSS", Locale.CANADA_FRENCH));

    public DeliveryController(LcpLink link) {
        this.link = link;
    }

    // état “stoppé”
    private boolean isStopped() {
        return stopped || link.isClosed();
    }

    // ====== Méthodes obligatoires de DeliveryControllerPort ======
    @Override
    public DeliveryState getState() { return state; }

    @Override
    public boolean isPaused() { return state == DeliveryState.RUNNING_PAUSED; }

    @Override
    public boolean isDeliveryActive() {
        return state == DeliveryState.RUNNING_FLOWING || state == DeliveryState.RUNNING_PAUSED;
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

    // --- normaliser les lignes si LcpLink préfixe [IO ...] ---
    private static String stripIoPrefix(String s) {
        if (s == null) return "";
        if (!s.startsWith("[IO ")) return s;
        int idx = s.indexOf("] ");
        if (idx > 0 && idx + 2 <= s.length()) return s.substring(idx + 2);
        return s;
    }

    private static boolean isTxRxLine(String raw) {
        return raw.startsWith("TX:") || raw.startsWith("RX:") || raw.startsWith("↳");
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

        // reset LIVE backoff on new start attempt
        liveBackoffMs = LIVE_BASE_MS;
        liveNextAllowedMs = 0L;
        liveLastSkipLogMs = 0L;

        retryUntilDeadline(deadlineMs, "SET_FIELD#0", () -> {
            int idx0 = product1to16 - 1;
            link.opSetField(FIELD_ACTIVE_PRODUCT, new byte[]{(byte) idx0});
            return null;
        });

        retryUntilDeadline(deadlineMs, "GET_FIELD#39", () -> {
            ensureDigits();
            return null;
        });

        // Point 1 (pas maintenant) : on conserve preset net #6 comme avant
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
                link.opIssueCommand(CMD_RUN);
                setState(DeliveryState.RUNNING_FLOWING);
                if (listener != null) listener.onLiveStatus("LIVE: RUNNING_FLOWING (Flow OFF — attente progression)");
            } catch (Exception e) {
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
    // LIVE — A+B
    // A) Backoff + log throttle sur busy/timeout
    // B) LIVE basé sur 0x28 (delivery status), pas 0x23
    // =========================
    @Override
    public void requestLiveSample() {
        io.execute(() -> {
            if (isStopped()) return;

            long now = System.currentTimeMillis();
            if (now < liveNextAllowedMs) return; // backoff gate

            if (!liveInFlight.compareAndSet(false, true)) return;
            inLiveSample.set(true);

            try {
                // ===== B) Delivery-first: lire 0x28 =====
                int delStatus;
                int delCode;

                try {
                    int[] ds = link.opDeliveryStatus(); // 0x28 -> [delStatus, delCode]
                    delStatus = ds[0];
                    delCode = ds[1];
                } catch (Exception e) {
                    liveSoftSkip("GET_DELIVERY_STATUS", e);
                    return;
                }

                boolean ticket = (delCode & DC_TICKET_PENDING) != 0;
                boolean flowBit = (delCode & DC_FLOW_ACTIVE) != 0;
                boolean active = (delCode & DC_DELIVERY_ACTIVE) != 0;

                if (!active) {
                    // reset backoff on success
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
                    return;
                }

                // Delivery active: tenter lecture compteurs
                // ensureDigits() throws -> best-effort
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
                    // best effort: garder les derniers si dispo
                    g = (lastGrossRaw >= 0) ? lastGrossRaw : 0;
                    n = (lastNetRaw >= 0) ? lastNetRaw : 0;
                    // et on considère ça comme un "soft skip partiel" -> backoff léger
                    liveBackoffStep("[LIVE] soft-skip counters");
                }

                // reset backoff on success (au moins del status OK)
                liveResetBackoff();

                long t = System.currentTimeMillis();
                int d = 0;
                if (lastGrossRaw >= 0 && lastNetRaw >= 0) {
                    d = Math.abs(g - lastGrossRaw) + Math.abs(n - lastNetRaw);
                }

                if (listener != null) listener.onLiveQty(n / scale, g / scale);

                if (d > 0) {
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
        // Backoff exponentiel
        liveBackoffMs = Math.min(LIVE_MAX_MS, Math.max(LIVE_BASE_MS, liveBackoffMs * 2));
        liveNextAllowedMs = now + liveBackoffMs;

        // throttle logs
        if (now - liveLastSkipLogMs >= LIVE_LOG_THROTTLE_MS) {
            emitLog(reason + " (backoff=" + liveBackoffMs + "ms)");
            liveLastSkipLogMs = now;
        }
    }

    private void liveSoftSkip(String opName, Exception e) {
        String m = (e.getMessage() != null) ? e.getMessage() : "";
        // backoff et log throttle
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
        LcpLink.MachineStatus ms = link.opGetMachineStatus(); // 0x23
        int[] ds = link.opDeliveryStatus(); // 0x28
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
            emitLog("[A] Ticket pending -> clear via #6 loop");
            clearTicketPendingLoop();
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

    private void clearTicketPendingLoop() {
        long deadline = System.currentTimeMillis() + TICKET_DEVICE_LOOP_MS;
        while (!isStopped() && System.currentTimeMillis() < deadline) {
            try {
                link.opIssueCommand(CMD_PRINT_LAST_TICKET);
            } catch (Exception e) {
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
        byte[] buf = new byte[]{
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

        // ✅ LIVE: jamais de resync/stop (requestLiveSample gère déjà son best-effort)
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
}
