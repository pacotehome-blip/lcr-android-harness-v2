
package com.pa.lcr.lcp;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DeliveryController implements DeliveryControllerPort {

    private static final int FIELD_ACTIVE_PRODUCT = 0;
    private static final int FIELD_PRESET_NET = 6;
    private static final int FIELD_DECIMALS = 39;
    private static final int FIELD_GROSS_COUNT = 44;
    private static final int FIELD_NET_COUNT = 45;

    private static final int CMD_RUN = 0x00;
    private static final int CMD_END = 0x02;
    private static final int CMD_PRINT_LAST_TICKET = 0x06;

    private static final int DC_TICKET_PENDING = 0x0001;
    private static final int DC_FLOW_ACTIVE = 0x0004;
    private static final int DC_DELIVERY_ACTIVE = 0x0008;

    private static final long TICKET_TIMEOUT_MS = 60_000;
    private static final long TICKET_POLL_MS = 250;

    // Confirmation FLOW OFF uniquement après ON→OFF
    private static final long NO_FLOW_CONFIRM_MS = 10_000;

    private final LcpLink link;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private Listener listener;
    private volatile DeliveryState state = DeliveryState.DISCONNECTED;

    private volatile int cachedDigits = -1;

    private volatile boolean flowOffStable = false;

    // ✅ “timer 10s” seulement après avoir vu FLOW ON au moins une fois
    private volatile boolean sawFlowOnOnce = false;
    private volatile long flowOffStartMs = 0L;

    private volatile long lastCountsChangeMs = 0L;
    private volatile int lastGrossRaw = -1;
    private volatile int lastNetRaw = -1;

    private final ThreadLocal<Boolean> inLiveSample = new ThreadLocal<>();

    private volatile boolean txRxEnabled = false;

    private volatile boolean pendingStart = false;
    private volatile int pendingProduct1to16 = 1;
    private volatile double pendingPresetNet = 0.0;

    private volatile boolean startInProgress = false;

    private volatile long lastResyncMs = 0L;
    private volatile boolean stopped = false;

    private volatile int consecutiveTimeouts = 0;
    private volatile long lastTimeoutMs = 0L;
    private static final long TIMEOUT_WINDOW_MS = 10_000;

    private volatile boolean logTsEnabled = false;

    private static final ThreadLocal<SimpleDateFormat> IO_DF =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("HH:mm:ss.SSS", Locale.CANADA_FRENCH));

    public DeliveryController(LcpLink link) {
        this.link = link;
    }

    /** ✅ Correctif obligatoire */
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

        // ✅ Anti-double préfixe: si le transport (LcpLink) a déjà mis [IO ...], on ne remet pas un 2e [IO ...]
        if (logTsEnabled) {
            if (line != null && line.startsWith("[IO ")) l.onLog(line);
            else l.onLog("[IO " + ioTs() + "] " + line);
        } else {
            l.onLog(line);
        }
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;

        if (listener == null) {
            link.setTraceSink(null);
            return;
        }

        link.setTraceSink(line -> {
            // Filtrage TX/RX/↳ si l’option n’est pas activée
            if (!txRxEnabled) {
                // NOTE: si LcpLink préfixe avec [IO ...], le startsWith("TX:") ne matchera pas.
                // Dans ton dernier correctif EB-1, on normalise ici. Si tu veux, je te remets la version stripIoPrefix.
                if (line.startsWith("TX:") || line.startsWith("RX:") || line.startsWith("↳")) return;
            }

            // Option: on évite de spammer le log pendant le tick live
            if (Boolean.TRUE.equals(inLiveSample.get())) {
                if (line.startsWith("TX:") || line.startsWith("RX:") || line.startsWith("↳")) return;
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
            refreshConnectedLive("INIT");
        });
    }

    @Override
    public void shutdown() {
        shutdown(true);
    }

    /** ✅ Correctif obligatoire: closeTransport=false → softClose */
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
    public void refreshProducts() { emitLog("refreshProducts ignoré"); }

    @Override
    public void selectProduct(int product1to16) {
        io.execute(() -> {
            if (isStopped()) return;
            try {
                int idx0 = product1to16 - 1;
                link.opSetField(FIELD_ACTIVE_PRODUCT, new byte[]{(byte) idx0});
                markIoSuccess();
            } catch (Exception e) {
                handleIoFailure("selectProduct", e);
            }
        });
    }

    @Override public DeliveryState getState() { return state; }

    @Override public boolean isDeliveryActive() {
        return state == DeliveryState.RUNNING_FLOWING || state == DeliveryState.RUNNING_PAUSED;
    }

    @Override public boolean isPaused() { return state == DeliveryState.RUNNING_PAUSED; }

    @Override public boolean isFlowOffStable() { return flowOffStable; }

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
                markIoSuccess();
            } catch (Exception e) {
                handleIoFailure("alignOrRecover", e);
            }
        });
    }

    // ✅ CAS C: START normal seulement si aucune livraison active.
    // ✅ Si livraison active: on n'applique PAS A automatiquement, mais on doit afficher CONNECTED — Ticket pending si présent.
    @Override
    public void startDelivery(int product1to16, double presetNet) {
        io.execute(() -> {
            if (isStopped()) return;
            try {
                if (state == DeliveryState.PRESTART || state == DeliveryState.ENDING) return;
                if (startInProgress) return;

                startInProgress = true;
                pendingStart = true;
                pendingProduct1to16 = product1to16;
                pendingPresetNet = presetNet;

                emitLog("[C] New delivery requested");

                DeliveryStatus st = readStatusWithResync("C/precheck");
                if (st == null) {
                    startInProgress = false;
                    refreshConnectedLive("C/precheck-null");
                    return;
                }

                // ✅ Si livraison active: on n'essaie pas de START. On informe et on laisse l'opérateur faire A.
                if (st.deliveryActive) {
                    pendingStart = false;
                    startInProgress = false;

                    setState(DeliveryState.CONNECTED);

                    if (listener != null) {
                        if (st.ticketPending) {
                            listener.onLiveStatus("LIVE: CONNECTED — Ticket pending");
                        } else {
                            listener.onLiveStatus("LIVE: CONNECTED — Livraison active (utiliser A)");
                        }
                    }

                    emitLog(st.ticketPending
                            ? "[C] Delivery active + ticket pending -> use A manually"
                            : "[C] Delivery active -> use A manually");
                    return;
                }

                // ✅ Si pas active, mais ticket pending: on NE fait pas A automatiquement (selon ton choix Cas C).
                if (st.ticketPending) {
                    pendingStart = false;
                    startInProgress = false;

                    setState(DeliveryState.CONNECTED);
                    if (listener != null) listener.onLiveStatus("LIVE: CONNECTED — Ticket pending");
                    emitLog("[C] Ticket pending -> use A manually");
                    return;
                }

                // ✅ START normal
                if (isReadyToStart(st)) {
                    emitLog("[C] Register ready -> START now");
                    pendingStart = false;

                    doStartNewDelivery(pendingProduct1to16, pendingPresetNet);

                    startInProgress = false;
                    markIoSuccess();
                    return;
                }

                // Sinon align/recover (A) ? — Pour Cas C, on garde ton comportement existant (align si registre pas prêt)
                if (listener != null) listener.onLiveStatus("LIVE: CONNECTED — Alignement en cours");
                emitLog("[C] Register NOT ready -> align/recover");
                doAlignOrRecover();

                startInProgress = false;
                markIoSuccess();

            } catch (Exception e) {
                startInProgress = false;
                handleIoFailure("startDelivery(C-intent)", e);
            }
        });
    }

    @Override
    public void resumeIfPaused() {
        io.execute(() -> {
            if (isStopped()) return;
            try {
                if (state != DeliveryState.RUNNING_PAUSED) return;

                link.opIssueCommand(CMD_RUN);
                markIoSuccess();

                flowOffStable = false;
                flowOffStartMs = 0L;

                setState(DeliveryState.RUNNING_FLOWING);
                if (listener != null) listener.onLiveStatus("LIVE: RUNNING_FLOWING (Flow OFF — attente Flow ON)");

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
                markIoSuccess();

                long deadline = System.currentTimeMillis() + 15_000;
                while (!isStopped() && System.currentTimeMillis() < deadline) {
                    DeliveryStatus st = readStatusWithResync("END/poll");
                    if (st != null && !st.deliveryActive && !st.flowActive) break;
                    try { Thread.sleep(250); } catch (InterruptedException ignored) {}
                }

                DeliveryStatus after = readStatusWithResync("END/after");
                if (after != null && after.ticketPending) {
                    emitLog("[END] Ticket pending -> Issue #6 to clear");
                    clearTicketPending();
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
                markIoSuccess();
            } catch (Exception e) {
                handleIoFailure("status", e);
            }
        });
    }

    @Override
    public void requestLiveSample() {
        io.execute(() -> {
            if (isStopped()) return;

            inLiveSample.set(true);
            try {
                DeliveryStatus st = readStatusWithResync("LIVE/0x28");
                if (st == null) return;

                if (!st.deliveryActive) {
                    setState(DeliveryState.CONNECTED);
                    refreshConnectedLive("LIVE/inactive");

                    lastGrossRaw = -1;
                    lastNetRaw = -1;

                    flowOffStable = false;
                    sawFlowOnOnce = false;
                    flowOffStartMs = 0L;
                    lastCountsChangeMs = 0L;
                    return;
                }

                if (st.flowActive) {
                    ensureDigits();

                    int grossRaw = beI32(link.opGetField(FIELD_GROSS_COUNT));
                    int netRaw = beI32(link.opGetField(FIELD_NET_COUNT));
                    double scale = Math.pow(10, cachedDigits);

                    boolean moved = hasCountersMoved(grossRaw, netRaw);
                    if (moved) {
                        sawFlowOnOnce = true;
                        lastCountsChangeMs = System.currentTimeMillis();
                    }

                    flowOffStable = false;
                    flowOffStartMs = 0L;

                    lastGrossRaw = grossRaw;
                    lastNetRaw = netRaw;

                    if (listener != null) {
                        listener.onLiveQty(netRaw / scale, grossRaw / scale);
                        listener.onFlowStability(true, false, 0L);
                        listener.onLiveStatus("LIVE: RUNNING_FLOWING (FLOW ON)");
                    }

                    setState(DeliveryState.RUNNING_FLOWING);
                    markIoSuccess();
                    return;
                }

                if (!sawFlowOnOnce) {
                    if (listener != null) {
                        listener.onFlowStability(false, false, 0L);
                        listener.onLiveStatus("LIVE: RUNNING_FLOWING (Flow OFF — attente Flow ON)");
                    }
                    setState(DeliveryState.RUNNING_FLOWING);
                    markIoSuccess();
                    return;
                }

                long now = System.currentTimeMillis();
                if (flowOffStartMs == 0L) flowOffStartMs = now;

                long age = Math.max(0L, now - flowOffStartMs);
                flowOffStable = age >= NO_FLOW_CONFIRM_MS;

                if (listener != null) {
                    listener.onFlowStability(false, flowOffStable, age);
                    listener.onLiveStatus(flowOffStable
                            ? "LIVE: RUNNING_PAUSED (FLOW OFF confirmé)"
                            : "LIVE: RUNNING_FLOWING (FLOW OFF — confirmation...)"
                    );
                }

                if (flowOffStable) setState(DeliveryState.RUNNING_PAUSED);
                else setState(DeliveryState.RUNNING_FLOWING);

                markIoSuccess();

            } catch (Exception e) {
                handleIoFailure("requestLiveSample", e);
            } finally {
                inLiveSample.remove();
            }
        });
    }

    @Override
    public void requestLiveSnapshot() {
        io.execute(() -> {
            if (isStopped()) return;
            refreshConnectedLive("SNAP");
        });
    }

    /* ===== Core align/recover ===== */

    private void doAlignOrRecover() throws Exception {
        DeliveryStatus st = readStatusWithResync("A/0x28");
        if (st == null) return;

        if (st.ticketPending) {
            if (listener != null) listener.onLiveStatus("LIVE: CONNECTED — Ticket_pending (recovering)");
            emitLog("[A] Ticket pending -> Issue #6");

            boolean ok = clearTicketPending();
            if (!ok) return;

            st = readStatusWithResync("A/0x28-after-ticket");
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
            doStartNewDelivery(pendingProduct1to16, pendingPresetNet);
        }
    }

    private void doStartNewDelivery(int product1to16, double presetNet) throws Exception {
        if (isStopped()) return;

        setState(DeliveryState.PRESTART);
        emitLog("[PRESTART] internal");

        flowOffStable = false;
        sawFlowOnOnce = false;
        flowOffStartMs = 0L;

        int idx0 = product1to16 - 1;
        link.opSetField(FIELD_ACTIVE_PRODUCT, new byte[]{(byte) idx0});

        bestEffortReadDecimals();
        writePresetNet_WithCacheOrFallback(presetNet);

        link.opIssueCommand(CMD_RUN);

        setState(DeliveryState.RUNNING_PAUSED);
        lastCountsChangeMs = System.currentTimeMillis();

        if (listener != null) listener.onLiveStatus("LIVE: RUNNING_PAUSED (Flow OFF)");
    }

    private boolean clearTicketPending() {
        final long deadline = System.currentTimeMillis() + TICKET_TIMEOUT_MS;

        try {
            link.opIssueCommand(CMD_PRINT_LAST_TICKET);
            markIoSuccess();
        } catch (Exception e) {
            handleIoFailure("TICKET/issue6", e);
            return false;
        }

        while (!isStopped() && System.currentTimeMillis() < deadline) {
            DeliveryStatus st = readStatusWithResync("TICKET/0x28");
            if (st != null && !st.ticketPending) return true;
            try { Thread.sleep(TICKET_POLL_MS); } catch (InterruptedException ignored) {}
        }
        return false;
    }

    /* ===== Helpers ===== */

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

    private DeliveryStatus readStatusWithResync(String reason) {
        if (isStopped()) return null;
        try {
            int[] st = link.opDeliveryStatus();
            markIoSuccess();
            return new DeliveryStatus(st[0], st[1]);
        } catch (Exception e) {
            handleIoFailure("0x28/" + reason, e);
            return null;
        }
    }

    private void refreshConnectedLive(String tag) {
        DeliveryStatus st = readStatusWithResync("LIVE/" + tag);
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
            markIoSuccess();
        } catch (Exception ignored) {}
    }

    private void ensureDigits() throws Exception {
        if (cachedDigits >= 0) return;
        byte[] dec = link.opGetField(FIELD_DECIMALS);
        int idx = (dec.length >= 1) ? (dec[0] & 0xFF) : 0;
        cachedDigits = decimalsDigits(idx);
        markIoSuccess();
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
        markIoSuccess();
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

    private boolean hasCountersMoved(int grossRaw, int netRaw) {
        if (lastGrossRaw < 0 || lastNetRaw < 0) return true;
        return (grossRaw != lastGrossRaw) || (netRaw != lastNetRaw);
    }

    private void markIoSuccess() {
        consecutiveTimeouts = 0;
    }

    private void handleIoFailure(String ctx, Exception e) {
        if (listener != null) listener.onError(ctx, e);

        String msg = (e != null && e.getMessage() != null) ? e.getMessage() : "";

        boolean hardFatal =
                msg.contains("Transport closed")
                        || msg.contains("Error writing")
                        || msg.contains("rc=-1")
                        || msg.contains("Connection closed");

        boolean isTimeout = msg.contains("Timeout waiting LCP response");

        if (hardFatal) {
            shutdown(true);
            return;
        }

        if (isTimeout) {
            long now = System.currentTimeMillis();
            if (now - lastTimeoutMs > TIMEOUT_WINDOW_MS) consecutiveTimeouts = 0;
            lastTimeoutMs = now;
            consecutiveTimeouts++;
            emitLog("[WARN] Timeout (" + consecutiveTimeouts + ") ctx=" + ctx);
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
