
package com.pa.lcr.lcp;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DeliveryController implements DeliveryControllerPort {

    private static final int FIELD_ACTIVE_PRODUCT = 0;
    private static final int FIELD_PRESET_NET = 6;
    private static final int FIELD_DECIMALS = 39;
    private static final int FIELD_GROSS_COUNT = 44;
    private static final int FIELD_NET_COUNT = 45;

    private static final int CMD_RUN = 0x00;
    private static final int CMD_END = 0x02;
    private static final int CMD_PRINT_LAST_TICKET = 0x06;

    // Bits delCode (LCRSc_* côté Python)
    private static final int DC_TICKET_PENDING  = 0x0001;
    private static final int DC_FLOW_ACTIVE     = 0x0004;
    private static final int DC_DELIVERY_ACTIVE = 0x0008;

    // Conservateur: stableOff après 10s de stagnation (comme fixé)
    private static final long NO_FLOW_CONFIRM_MS = 10_000;

    // START retry window (équivalent --start-timeout 20) + poll (équivalent --poll 0.2)
    private static final long START_RETRY_WINDOW_MS = 20_000;
    private static final long START_RETRY_POLL_MS   = 200;

    // Ticket device loop (Python-like)
    private static final long TICKET_DEVICE_LOOP_MS = 30_000;

    private final LcpLink link;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private Listener listener;
    private volatile DeliveryState state = DeliveryState.DISCONNECTED;

    private volatile int cachedDigits = -1;

    // LIVE state (Python parity: d>0 = ON réel, d==0 = stagnation)
    private volatile boolean flowOffStable = false;
    private volatile boolean sawFlowOnOnce = false;
    private volatile long flowOffStartMs = 0L;
    private volatile long lastCountsChangeMs = 0L;
    private volatile int lastGrossRaw = -1;
    private volatile int lastNetRaw = -1;

    private final ThreadLocal<Boolean> inLiveSample = new ThreadLocal<>();

    private volatile boolean txRxEnabled = false;
    private volatile boolean logTsEnabled = false;

    private volatile boolean pendingStart = false;
    private volatile int pendingProduct1to16 = 1;
    private volatile double pendingPresetNet = 0.0;

    private volatile boolean startInProgress = false;

    private volatile long lastResyncMs = 0L;
    private volatile boolean stopped = false;

    // ✅ Python parity: pas de chevauchement LIVE (évite la file d'attente)
    private final AtomicBoolean liveInFlight = new AtomicBoolean(false);

    private static final ThreadLocal<SimpleDateFormat> IO_DF =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("HH:mm:ss.SSS", Locale.CANADA_FRENCH));

    public DeliveryController(LcpLink link) {
        this.link = link;
    }

    private boolean isStopped() {
        return stopped || link.isClosed();
    }

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

        // Anti-double [IO ...] si LcpLink timestamp déjà TX/RX
        if (logTsEnabled) {
            if (line != null && line.startsWith("[IO ")) l.onLog(line);
            else l.onLog("[IO " + ioTs() + "] " + line);
        } else {
            l.onLog(line);
        }
    }

    // --- EB-1: normaliser les lignes si LcpLink préfixe [IO ...] ---
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

    @Override
    public void initialize() {
        io.execute(() -> {
            if (isStopped()) return;

            setState(DeliveryState.CONNECTED);
            emitLog("LCP prêt (sans refresh automatique)");

            // ✅ Anti-pollution: pas de 0x28 automatique ici
            if (listener != null) listener.onLiveStatus("LIVE: CONNECTED — (sans poll INIT)");
        });
    }

    @Override
    public void shutdown() {
        shutdown(true);
    }

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

    @Override
    public void refreshProducts() {
        emitLog("refreshProducts ignoré");
    }

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
    public DeliveryState getState() { return state; }

    @Override
    public boolean isDeliveryActive() {
        return state == DeliveryState.RUNNING_FLOWING || state == DeliveryState.RUNNING_PAUSED;
    }

    @Override
    public boolean isPaused() { return state == DeliveryState.RUNNING_PAUSED; }

    @Override
    public boolean isFlowOffStable() { return flowOffStable; }

    @Override
    public long getFlowOffAgeMs() {
        if (!sawFlowOnOnce) return 0L;
        if (flowOffStartMs <= 0L) return 0L;
        long now = System.currentTimeMillis();
        return Math.max(0L, now - flowOffStartMs);
    }

    @Override
    public void alignOrRecover() {
        io.execute(() -> {
            if (isStopped()) return;
            try {
                pendingStart = false;
                emitLog("[A] Align / recover requested");
                doAlignOrRecover();
            } catch (Exception e) {
                handleIoFailure("alignOrRecover", e);
            }
        });
    }

    /**
     * START (C) — équivalent Python:
     * - retry continu jusqu'à 20s (start-timeout)
     * - poll 0.2s entre essais
     * - support "via 7D": si LcpLink remonte "Queued timeout last=26", on retry dans la fenêtre.
     *
     * Cas C (règle figée):
     * - si deliveryActive=1 => A manuel (on affiche CONNECTED + ticket pending si présent)
     * - si ticketPending=1 sans deliveryActive => A manuel (on affiche CONNECTED — Ticket pending)
     */
    @Override
    public void startDelivery(int product1to16, double presetNet) {
        io.execute(() -> {
            if (isStopped()) return;

            if (state == DeliveryState.PRESTART || state == DeliveryState.ENDING) return;
            if (startInProgress) return;

            startInProgress = true;
            pendingStart = true;
            pendingProduct1to16 = product1to16;
            pendingPresetNet = presetNet;

            emitLog("[C] New delivery requested");

            final long deadline = System.currentTimeMillis() + START_RETRY_WINDOW_MS;

            try {
                // PRECHECK: 0x28 (retry jusqu'à 20s)
                DeliveryStatus st = retryUntilDeadline(deadline, "C/precheck(0x28)", () -> {
                    int[] raw = link.opDeliveryStatus();
                    return new DeliveryStatus(raw[0], raw[1]);
                });

                if (st == null) {
                    // déjà loggé dans retry; on sort
                    return;
                }

                // livraison active => A manuel, on reste CONNECTED et on rend ticket_pending visible
                if (st.deliveryActive) {
                    pendingStart = false;
                    setState(DeliveryState.CONNECTED);
                    if (listener != null) {
                        if (st.ticketPending) listener.onLiveStatus("LIVE: CONNECTED — Ticket pending");
                        else listener.onLiveStatus("LIVE: CONNECTED — Livraison active (utiliser A)");
                    }
                    emitLog(st.ticketPending
                            ? "[C] Delivery active + ticket pending -> use A manually"
                            : "[C] Delivery active -> use A manually");
                    return;
                }

                // ticket pending sans delivery active => A manuel
                if (st.ticketPending) {
                    pendingStart = false;
                    setState(DeliveryState.CONNECTED);
                    if (listener != null) listener.onLiveStatus("LIVE: CONNECTED — Ticket pending");
                    emitLog("[C] Ticket pending -> use A manually");
                    return;
                }

                // registre prêt => START normal (avec retry 20s sur chaque étape)
                if (isReadyToStart(st)) {
                    emitLog("[C] Register ready -> START now");
                    pendingStart = false;

                    doStartNewDeliveryWithRetry(deadline, pendingProduct1to16, pendingPresetNet);

                    return;
                }

                // registre pas prêt => align (baseline)
                if (listener != null) listener.onLiveStatus("LIVE: CONNECTED — Alignement en cours");
                emitLog("[C] Register NOT ready -> align/recover");
                doAlignOrRecover();

            } catch (Exception e) {
                handleIoFailure("startDelivery(C-intent)", e);
            } finally {
                startInProgress = false;
            }
        });
    }

    private void doStartNewDeliveryWithRetry(long deadlineMs, int product1to16, double presetNet) throws Exception {
        if (isStopped()) return;

        setState(DeliveryState.PRESTART);
        emitLog("[PRESTART] internal");

        // reset session LIVE
        flowOffStable = false;
        sawFlowOnOnce = false;
        flowOffStartMs = 0L;
        lastCountsChangeMs = 0L;
        lastGrossRaw = -1;
        lastNetRaw = -1;

        // 1) SET product (#0)
        retryUntilDeadline(deadlineMs, "SET_FIELD#0", () -> {
            int idx0 = product1to16 - 1;
            link.opSetField(FIELD_ACTIVE_PRODUCT, new byte[]{(byte) idx0});
            return null;
        });

        // 2) GET decimals (#39) (via ensureDigits)
        retryUntilDeadline(deadlineMs, "GET_FIELD#39", () -> {
            ensureDigits();
            return null;
        });

        // 3) SET preset net (#6)
        retryUntilDeadline(deadlineMs, "SET_FIELD#6", () -> {
            writePresetNet_WithCacheOrFallback(presetNet);
            return null;
        });

        // 4) RUN 0x00
        retryUntilDeadline(deadlineMs, "RUN(0x00)", () -> {
            link.opIssueCommand(CMD_RUN);
            return null;
        });

        setState(DeliveryState.RUNNING_PAUSED);
        if (listener != null) listener.onLiveStatus("LIVE: RUNNING_PAUSED (Flow OFF)");
    }

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
                    LcpLink.MachineStatus ms = tryGetMachineStatus();
                    if (ms != null) {
                        boolean active = (ms.delCode & DC_DELIVERY_ACTIVE) != 0;
                        boolean flow   = (ms.delCode & DC_FLOW_ACTIVE) != 0;
                        if (!active && !flow) break;
                    }
                    try { Thread.sleep(250); } catch (InterruptedException ignored) {}
                }

                // clear ticket si pending (boucle 30s)
                LcpLink.MachineStatus msAfter = tryGetMachineStatus();
                boolean ticketPending = (msAfter != null) && ((msAfter.delCode & DC_TICKET_PENDING) != 0);
                if (ticketPending) {
                    emitLog("[END] Ticket pending -> clear via #6 loop");
                    clearTicketPendingLoop();
                }

                setState(DeliveryState.CONNECTED);
                refreshConnectedLive("END/done");

            } catch (Exception e) {
                handleIoFailure("endDelivery", e);
            }
        });
    }

    @Override
    public void requestStatus() {
        io.execute(() -> {
            if (isStopped()) return;
            try {
                link.opGetMachineStatus();
                link.opDeliveryStatus();
            } catch (Exception e) {
                handleIoFailure("status", e);
            }
        });
    }

    /**
     * LIVE (Python parity):
     * - pas de chevauchement (liveInFlight)
     * - source = MachineStatus (0x23) pour flow/active/ticket
     * - compteurs toujours lus (fallback)
     * - FLOW ON réel = d>0
     * - stableOff conservateur = 10s (après avoir vu ON réel)
     *
     * Anti-pollution: quand active==false, on n'appelle pas 0x28 (pas de refreshConnectedLive inactive)
     */
    @Override
    public void requestLiveSample() {
        io.execute(() -> {
            if (isStopped()) return;

            if (!liveInFlight.compareAndSet(false, true)) return;

            inLiveSample.set(true);
            try {
                LcpLink.MachineStatus ms = link.opGetMachineStatus(); // 0x23

                boolean flowBit = (ms.delCode & DC_FLOW_ACTIVE) != 0;
                boolean active  = (ms.delCode & DC_DELIVERY_ACTIVE) != 0;
                boolean ticket  = (ms.delCode & DC_TICKET_PENDING) != 0;

                if (!active) {
                    setState(DeliveryState.CONNECTED);

                    if (listener != null) {
                        listener.onLiveStatus(ticket
                                ? "LIVE: CONNECTED — Ticket pending"
                                : "LIVE: CONNECTED — Prêt à livrer");
                        listener.onFlowStability(false, false, 0L);
                    }

                    // reset live session
                    lastGrossRaw = -1;
                    lastNetRaw = -1;
                    flowOffStable = false;
                    sawFlowOnOnce = false;
                    flowOffStartMs = 0L;
                    lastCountsChangeMs = 0L;
                    return;
                }

                ensureDigits();
                double scale = Math.pow(10, cachedDigits);

                int g, n;
                try {
                    g = beI32(link.opGetField(FIELD_GROSS_COUNT));
                    n = beI32(link.opGetField(FIELD_NET_COUNT));
                } catch (Exception ex) {
                    g = (lastGrossRaw >= 0) ? lastGrossRaw : 0;
                    n = (lastNetRaw >= 0) ? lastNetRaw : 0;
                }

                long now = System.currentTimeMillis();

                int d = 0;
                if (lastGrossRaw >= 0 && lastNetRaw >= 0) {
                    d = Math.abs(g - lastGrossRaw) + Math.abs(n - lastNetRaw);
                }

                if (listener != null) listener.onLiveQty(n / scale, g / scale);

                if (d > 0) {
                    sawFlowOnOnce = true;
                    lastCountsChangeMs = now;

                    flowOffStable = false;
                    flowOffStartMs = 0L;

                    lastGrossRaw = g;
                    lastNetRaw = n;

                    if (listener != null) {
                        listener.onFlowStability(true, false, 0L);
                        listener.onLiveStatus("LIVE: RUNNING_FLOWING (FLOW ON)");
                    }
                    setState(DeliveryState.RUNNING_FLOWING);
                    return;
                }

                if (lastCountsChangeMs == 0L) lastCountsChangeMs = now;
                long age = now - lastCountsChangeMs;

                if (!sawFlowOnOnce) {
                    flowOffStable = false;
                    flowOffStartMs = 0L;

                    if (listener != null) {
                        listener.onFlowStability(false, false, 0L);
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

            } catch (Exception e) {
                handleIoFailure("requestLiveSample", e);
            } finally {
                inLiveSample.remove();
                liveInFlight.set(false);
            }
        });
    }

    @Override
    public void requestLiveSnapshot() {
        io.execute(() -> {
            if (isStopped()) return;
            refreshConnectedLive("SNAP"); // snapshot explicite -> 0x28 OK
        });
    }

    /* ===========================
       Core align/recover
       =========================== */

    private void doAlignOrRecover() throws Exception {
        DeliveryStatus st = readDeliveryStatusWithResync("A/0x28");
        if (st == null) return;

        if (st.ticketPending) {
            if (listener != null) listener.onLiveStatus("LIVE: CONNECTED — Ticket_pending (recovering)");
            emitLog("[A] Ticket pending -> clear via #6 loop");
            clearTicketPendingLoop();

            st = readDeliveryStatusWithResync("A/0x28-after-ticket");
            if (st == null) return;
        }

        if (st.deliveryActive && !st.flowActive) {
            setState(DeliveryState.RUNNING_PAUSED);
            if (listener != null) listener.onLiveStatus("LIVE: RUNNING_PAUSED (recovered)");
            return;
        }

        if (st.deliveryActive && st.flowActive) {
            setState(DeliveryState.RUNNING_FLOWING);
            if (listener != null) listener.onLiveStatus("LIVE: RUNNING_FLOWING (recovered)");
            return;
        }

        setState(DeliveryState.CONNECTED);
        if (listener != null) {
            listener.onLiveStatus(st.ticketPending
                    ? "LIVE: CONNECTED — Ticket pending"
                    : "LIVE: CONNECTED — Prêt à livrer");
        }

        if (pendingStart && isReadyToStart(st)) {
            emitLog("[AUTO] Alignment complete -> START");
            pendingStart = false;
            doStartNewDeliveryWithRetry(System.currentTimeMillis() + START_RETRY_WINDOW_MS, pendingProduct1to16, pendingPresetNet);
        }
    }

    private void clearTicketPendingLoop() {
        long deadline = System.currentTimeMillis() + TICKET_DEVICE_LOOP_MS;

        while (!isStopped() && System.currentTimeMillis() < deadline) {
            try {
                link.opIssueCommand(CMD_PRINT_LAST_TICKET);
            } catch (Exception e) {
                softResync("ticket/issue6");
            }

            try { Thread.sleep(200); } catch (InterruptedException ignored) {}

            LcpLink.MachineStatus ms = tryGetMachineStatus();
            if (ms == null) continue;

            boolean pending = (ms.delCode & DC_TICKET_PENDING) != 0;
            if (!pending) {
                emitLog("[TICKET] cleared");
                return;
            }
        }

        emitLog("[TICKET] clear timeout");
    }

    /* ===========================
       START retry helper (Python-like)
       =========================== */

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

                // erreurs fatales => stop immédiat
                if (m.contains("Transport closed") || m.contains("Error writing") || m.contains("rc=-1") || m.contains("Connection closed")) {
                    throw e;
                }

                if (!retryable) {
                    throw e;
                }

                emitLog("[START] " + step + " -> retry (" + (queuedTimeout ? "queued" : "timeout") + ")");
                softResync("start/" + step);

                try { Thread.sleep(START_RETRY_POLL_MS); } catch (InterruptedException ignored) {}
            }
        }

        if (last != null) throw last;
        return null;
    }

    /* ===========================
       Low-level helpers
       =========================== */

    private static final class DeliveryStatus {
        final int delStatus;
        final int delCode;
        final boolean ticketPending;
        final boolean flowActive;
        final boolean deliveryActive;

        DeliveryStatus(int delStatus, int delCode) {
            this.delStatus = delStatus;
            this.delCode = delCode;
            this.ticketPending = (delCode & DC_TICKET_PENDING) != 0;
            this.flowActive = (delCode & DC_FLOW_ACTIVE) != 0;
            this.deliveryActive = (delCode & DC_DELIVERY_ACTIVE) != 0;
        }
    }

    private boolean isReadyToStart(DeliveryStatus st) {
        return !st.ticketPending && !st.deliveryActive && !st.flowActive;
    }

    private DeliveryStatus readDeliveryStatusWithResync(String reason) {
        if (isStopped()) return null;
        try {
            int[] st = link.opDeliveryStatus(); // 0x28
            return new DeliveryStatus(st[0], st[1]);
        } catch (Exception e) {
            handleIoFailure("0x28/" + reason, e);
            return null;
        }
    }

    private LcpLink.MachineStatus tryGetMachineStatus() {
        if (isStopped()) return null;
        try {
            return link.opGetMachineStatus(); // 0x23
        } catch (Exception e) {
            handleIoFailure("0x23/ms", e);
            return null;
        }
    }

    private void refreshConnectedLive(String tag) {
        DeliveryStatus st = readDeliveryStatusWithResync("LIVE/" + tag);
        if (listener == null) return;

        if (st == null) {
            listener.onLiveStatus(isStopped() ? "LIVE: DISCONNECTED" : "LIVE: CONNECTED — (état inconnu)");
            return;
        }

        listener.onLiveStatus(st.ticketPending
                ? "LIVE: CONNECTED — Ticket pending"
                : "LIVE: CONNECTED — Prêt à livrer");
    }

    private void bestEffortReadDecimals() {
        if (cachedDigits >= 0) return;
        try {
            byte[] dec = link.opGetField(FIELD_DECIMALS);
            int idx = (dec.length >= 1) ? (dec[0] & 0xFF) : 0;
            cachedDigits = decimalsDigits(idx);
        } catch (Exception ignored) {}
    }

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
        return ((b[0] & 0xFF) << 24)
                | ((b[1] & 0xFF) << 16)
                | ((b[2] & 0xFF) << 8)
                | (b[3] & 0xFF);
    }

    private void setState(DeliveryState s) {
        if (state == s) return;
        state = s;
        if (listener != null) listener.onStateChanged(s);
    }

    private void handleIoFailure(String ctx, Exception e) {
        if (listener != null) listener.onError(ctx, e);

        String msg = (e != null && e.getMessage() != null) ? e.getMessage() : "";

        boolean hardFatal =
                msg.contains("Transport closed")
                        || msg.contains("Error writing")
                        || msg.contains("rc=-1")
                        || msg.contains("Connection closed");

        boolean isTimeout =
                msg.contains("Timeout waiting LCP response")
                        || msg.contains("Queued timeout");

        if (hardFatal) {
            shutdown(true);
            return;
        }

        if (isTimeout) {
            emitLog("[WARN] Timeout ctx=" + ctx + " (" + msg + ")");
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
