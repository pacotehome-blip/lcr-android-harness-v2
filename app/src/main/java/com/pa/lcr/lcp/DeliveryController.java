
package com.pa.lcr.lcp;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DeliveryController - version terrain (flow canonique).
 *
 * Règles:
 * - A = alignOrRecover() : ne démarre jamais
 * - C = startDelivery(product,preset) : intention nouvelle livraison:
 *     * validate 0x28
 *     * si non prêt -> alignOrRecover + pendingStart=true
 *     * dès que clean -> START auto
 * - LIVE tick uniquement si FLOW_ACTIVE=ON (piloté UI), lectures #44/#45 uniquement en flow
 * - TX/RX filtrables globalement (checkbox), tick toujours filtré pour éviter spam
 */
public final class DeliveryController implements DeliveryControllerPort {

    private static final int FIELD_ACTIVE_PRODUCT = 0; // 0..15
    private static final int FIELD_PRESET_NET = 6;
    private static final int FIELD_DECIMALS = 39;
    private static final int FIELD_GROSS_COUNT = 44;
    private static final int FIELD_NET_COUNT = 45;

    private static final int CMD_RUN = 0x00;
    private static final int CMD_END = 0x02;
    private static final int CMD_PRINT_LAST_TICKET = 0x06;

    // DeliveryCode bits
    private static final int DC_TICKET_PENDING = 0x0001;
    private static final int DC_FLOW_ACTIVE = 0x0004;
    private static final int DC_DELIVERY_ACTIVE = 0x0008;

    private static final long TICKET_TIMEOUT_MS = 20_000;
    private static final long TICKET_POLL_MS = 250;

    private final LcpLink link;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private Listener listener;
    private volatile DeliveryState state = DeliveryState.DISCONNECTED;

    // Cache digits (#39)
    private volatile int cachedDigits = -1;

    // Flow off stable
    private volatile boolean flowOffStable = true;

    // LiveTick filter (TX/RX/↳) only during requestLiveSample()
    private final ThreadLocal<Boolean> inLiveSample = new ThreadLocal<>();

    // Global TX/RX display flag (checkbox)
    private volatile boolean txRxEnabled = false;

    // Start intention from C
    private volatile boolean pendingStart = false;
    private volatile int pendingProduct1to16 = 1;
    private volatile double pendingPresetNet = 0.0;

    // Prevent re-entry
    private volatile boolean startInProgress = false;

    public DeliveryController(LcpLink link) {
        this.link = link;
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
        if (listener == null) {
            link.setTraceSink(null);
            return;
        }

        // Trace filtering:
        // - If checkbox OFF: hide TX/RX/↳ globally
        // - If checkbox ON: show TX/RX/↳ except when liveTick sample is running (avoid spam)
        link.setTraceSink(line -> {
            if (!txRxEnabled) {
                if (line.startsWith("TX:") || line.startsWith("RX:") || line.startsWith("↳")) return;
            }
            if (Boolean.TRUE.equals(inLiveSample.get())) {
                if (line.startsWith("TX:") || line.startsWith("RX:") || line.startsWith("↳")) return;
            }
            listener.onLog(line);
        });
    }

    @Override
    public void setTxRxLoggingEnabled(boolean enabled) {
        io.execute(() -> {
            txRxEnabled = enabled;
            if (listener != null) listener.onLog("[LOG] TX/RX " + (enabled ? "ON" : "OFF"));
        });
    }

    @Override
    public void initialize() {
        io.execute(() -> {
            setState(DeliveryState.CONNECTED);
            if (listener != null) {
                listener.onLog("LCP prêt (sans refresh automatique)");
                listener.onLiveStatus("LIVE: CONNECTED — (validation requise)");
            }
        });
    }

    @Override
    public void shutdown() {
        io.shutdownNow();
        setState(DeliveryState.DISCONNECTED);
    }

    @Override
    public void refreshProducts() {
        if (listener != null) listener.onLog("refreshProducts ignoré (mode sans rafraîchissement)");
    }

    @Override
    public void selectProduct(int product1to16) {
        io.execute(() -> {
            try {
                if (state == DeliveryState.DISCONNECTED) return;
                int idx0 = product1to16 - 1;
                link.opSetField(FIELD_ACTIVE_PRODUCT, new byte[]{(byte) idx0});
            } catch (Exception e) {
                if (listener != null) listener.onError("selectProduct", e);
            }
        });
    }

    @Override
    public DeliveryState getState() {
        return state;
    }

    @Override
    public boolean isDeliveryActive() {
        return state == DeliveryState.RUNNING_FLOWING || state == DeliveryState.RUNNING_PAUSED;
    }

    @Override
    public boolean isPaused() {
        return state == DeliveryState.RUNNING_PAUSED;
    }

    @Override
    public boolean isFlowOffStable() {
        return flowOffStable;
    }

    @Override
    public long getFlowOffAgeMs() {
        return 0L;
    }

    // ============================================================
    // A = Align / Recover (no START intention)
    // ============================================================
    @Override
    public void alignOrRecover() {
        io.execute(() -> {
            try {
                if (state == DeliveryState.DISCONNECTED) return;
                if (isDeliveryActive()) return; // pas d’align pendant une livraison active (sauf reconnect, traité ailleurs)
                pendingStart = false;
                if (listener != null) listener.onLog("[A] Align / recover requested");
                doAlignOrRecover();
            } catch (Exception e) {
                if (listener != null) listener.onError("alignOrRecover", e);
            }
        });
    }

    // ============================================================
    // C = Start intent (validate; align if needed; auto-start when clean)
    // ============================================================
    @Override
    public void startDelivery(int product1to16, double presetNet) {
        io.execute(() -> {
            try {
                if (state == DeliveryState.DISCONNECTED) return;
                if (isDeliveryActive() || state == DeliveryState.PRESTART || state == DeliveryState.ENDING) return;
                if (startInProgress) return;

                startInProgress = true;
                pendingStart = true;
                pendingProduct1to16 = product1to16;
                pendingPresetNet = presetNet;

                if (listener != null) listener.onLog("[C] New delivery requested");

                DeliveryStatus st = readStatusWithResync("C/precheck");
                if (st == null) {
                    startInProgress = false;
                    return;
                }

                if (isReadyToStart(st)) {
                    if (listener != null) listener.onLog("[C] Register ready → START now");
                    pendingStart = false;
                    doStartNewDelivery(pendingProduct1to16, pendingPresetNet);
                    startInProgress = false;
                    return;
                }

                // Not ready: show recovering + align, then auto-start when clean
                if (listener != null) {
                    if (st.ticketPending) {
                        listener.onLiveStatus("LIVE: CONNECTED — Ticket_pending (recovering)");
                    } else {
                        listener.onLiveStatus("LIVE: CONNECTED — Alignement en cours");
                    }
                    listener.onLog("[C] Register NOT ready → align/recover");
                }

                doAlignOrRecover(); // will auto-start if pendingStart and clean
                startInProgress = false;

            } catch (Exception e) {
                if (listener != null) listener.onError("startDelivery(C-intent)", e);
                startInProgress = false;
            }
        });
    }

    // ============================================================
    // Resume / End
    // ============================================================
    @Override
    public void resumeIfPaused() {
        io.execute(() -> {
            try {
                if (state != DeliveryState.RUNNING_PAUSED) return;
                link.opIssueCommand(CMD_RUN);
                flowOffStable = false;
                setState(DeliveryState.RUNNING_PAUSED);
            } catch (Exception e) {
                if (listener != null) listener.onError("resumeIfPaused", e);
            }
        });
    }

    @Override
    public void endDelivery() {
        io.execute(() -> {
            try {
                if (state == DeliveryState.DISCONNECTED) return;
                if (state == DeliveryState.ENDING) return;
                if (!flowOffStable) return;
                if (state != DeliveryState.RUNNING_PAUSED) return;

                setState(DeliveryState.ENDING);
                if (listener != null) listener.onLog("[END] Issue END (0x02)");
                link.opIssueCommand(CMD_END);

                long deadline = System.currentTimeMillis() + 15000;
                while (System.currentTimeMillis() < deadline) {
                    DeliveryStatus st = readStatusWithResync("END/poll");
                    if (st != null && !st.deliveryActive) {
                        flowOffStable = true;
                        // Retour à CONNECTED — Prêt à livrer
                        setState(DeliveryState.CONNECTED);
                        if (listener != null) listener.onLiveStatus("LIVE: CONNECTED — Prêt à livrer");
                        return;
                    }
                    try { Thread.sleep(250); } catch (InterruptedException ignored) {}
                }

                // fallback
                flowOffStable = true;
                setState(DeliveryState.CONNECTED);
                if (listener != null) listener.onLiveStatus("LIVE: CONNECTED — Prêt à livrer");

            } catch (Exception e) {
                if (listener != null) listener.onError("endDelivery", e);
                setState(DeliveryState.CONNECTED);
            }
        });
    }

    @Override
    public void requestStatus() {
        io.execute(() -> {
            try {
                link.opGetMachineStatus();
                link.opDeliveryStatus();
            } catch (Exception e) {
                if (listener != null) listener.onError("status", e);
            }
        });
    }

    // ============================================================
    // LIVE tick (UI calls only when RUNNING_FLOWING)
    // ============================================================
    @Override
    public void requestLiveSample() {
        io.execute(() -> {
            inLiveSample.set(true);
            try {
                DeliveryStatus st = readStatusWithResync("LIVE/0x28");
                if (st == null) return;

                if (!st.deliveryActive) {
                    flowOffStable = true;
                    setState(DeliveryState.CONNECTED);
                    if (listener != null) listener.onLiveStatus("LIVE: CONNECTED — Prêt à livrer");
                    return;
                }

                if (!st.flowActive) {
                    flowOffStable = true;
                    setState(DeliveryState.RUNNING_PAUSED);
                    if (listener != null) listener.onLiveStatus("LIVE: RUNNING_PAUSED (FLOW OFF)");
                    return;
                }

                // Flow ON
                flowOffStable = false;
                ensureDigits();
                int grossRaw = beI32(link.opGetField(FIELD_GROSS_COUNT));
                int netRaw = beI32(link.opGetField(FIELD_NET_COUNT));
                double scale = Math.pow(10, cachedDigits);

                if (listener != null) {
                    listener.onLiveQty(netRaw / scale, grossRaw / scale);
                    listener.onFlowStability(true, false, 0L);
                    listener.onLiveStatus("LIVE: RUNNING_FLOWING (FLOW ON)");
                }
                setState(DeliveryState.RUNNING_FLOWING);

            } catch (Exception ignored) {
                // on garde silencieux pour éviter spam; erreurs visibles via logs métier ailleurs
            } finally {
                inLiveSample.remove();
            }
        });
    }

    @Override
    public void requestLiveSnapshot() {
        io.execute(() -> {
            try {
                DeliveryStatus st = readStatusWithResync("SNAP/0x28");
                if (st == null) return;

                if (!st.deliveryActive) {
                    flowOffStable = true;
                    if (listener != null) listener.onLiveStatus(st.ticketPending
                            ? "LIVE: CONNECTED — Ticket pending"
                            : "LIVE: CONNECTED — Prêt à livrer");
                    return;
                }

                ensureDigits();
                int grossRaw = beI32(link.opGetField(FIELD_GROSS_COUNT));
                int netRaw = beI32(link.opGetField(FIELD_NET_COUNT));
                double scale = Math.pow(10, cachedDigits);

                if (listener != null) listener.onLiveQty(netRaw / scale, grossRaw / scale);

                if (st.flowActive) {
                    setState(DeliveryState.RUNNING_FLOWING);
                    if (listener != null) listener.onLiveStatus("LIVE: RUNNING_FLOWING (FLOW ON)");
                } else {
                    setState(DeliveryState.RUNNING_PAUSED);
                    if (listener != null) listener.onLiveStatus("LIVE: RUNNING_PAUSED (FLOW OFF)");
                }

            } catch (Exception ignored) { }
        });
    }

    // ============================================================
    // Align/recover core
    // ============================================================
    private void doAlignOrRecover() throws Exception {
        DeliveryStatus st = readStatusWithResync("A/0x28");
        if (st == null) return;

        // Ticket pending
        if (st.ticketPending) {
            if (listener != null) {
                listener.onLiveStatus("LIVE: CONNECTED — Ticket_pending (recovering)");
                listener.onLog("[A] Ticket pending → Issue #6");
            }
            boolean ok = clearTicketPending();
            if (!ok) {
                if (listener != null) listener.onLog("[A] Ticket not cleared (timeout)");
                // on laisse en ticket pending
                return;
            }
            // Revalider après clear
            st = readStatusWithResync("A/0x28-after-ticket");
            if (st == null) return;
        }

        // Delivery active paused: resume
        if (st.deliveryActive && !st.flowActive) {
            if (listener != null) listener.onLog("[A] Delivery active paused → RUN (resume)");
            link.opIssueCommand(CMD_RUN);
            setState(DeliveryState.RUNNING_PAUSED);
            if (listener != null) listener.onLiveStatus("LIVE: RUNNING_PAUSED (recovered)");
            return;
        }

        // If delivery active & flowing, just reflect state
        if (st.deliveryActive && st.flowActive) {
            setState(DeliveryState.RUNNING_FLOWING);
            if (listener != null) listener.onLiveStatus("LIVE: RUNNING_FLOWING (recovered)");
            return;
        }

        // Now clean (or idle)
        if (listener != null) listener.onLiveStatus(st.ticketPending
                ? "LIVE: CONNECTED — Ticket pending"
                : "LIVE: CONNECTED — Prêt à livrer");

        // Auto-start if C intent is pending and register clean
        if (pendingStart && isReadyToStart(st)) {
            if (listener != null) listener.onLog("[AUTO] Alignment complete → START");
            pendingStart = false;
            doStartNewDelivery(pendingProduct1to16, pendingPresetNet);
        }
    }

    private void doStartNewDelivery(int product1to16, double presetNet) throws Exception {
        // strict check: must be ready
        DeliveryStatus st = readStatusWithResync("START/0x28");
        if (st == null || !isReadyToStart(st)) {
            if (listener != null) listener.onLog("[START] Refused: register not ready");
            return;
        }

        setState(DeliveryState.PRESTART);
        if (listener != null) listener.onLiveStatus("LIVE: PRESTART (internal)");

        // product
        int idx0 = product1to16 - 1;
        link.opSetField(FIELD_ACTIVE_PRODUCT, new byte[]{(byte) idx0});
        bestEffortReadDecimals();

        // preset net
        writePresetNet_WithCacheOrFallback(presetNet);

        // RUN
        link.opIssueCommand(CMD_RUN);

        flowOffStable = false;
        setState(DeliveryState.RUNNING_PAUSED);
        if (listener != null) listener.onLiveStatus("LIVE: RUNNING_PAUSED");
    }

    // ============================================================
    // Ticket clear
    // ============================================================
    private boolean clearTicketPending() {
        final long deadline = System.currentTimeMillis() + TICKET_TIMEOUT_MS;
        boolean resyncDone = false;

        try {
            link.opIssueCommand(CMD_PRINT_LAST_TICKET);
        } catch (Exception e) {
            softResync("TICKET/issue6");
        }

        while (System.currentTimeMillis() < deadline) {
            try {
                DeliveryStatus st = readStatusWithResync("TICKET/0x28");
                if (st != null && !st.ticketPending) return true;
            } catch (Exception e) {
                if (!resyncDone) {
                    resyncDone = true;
                    softResync("TICKET/0x28");
                }
            }
            try { Thread.sleep(TICKET_POLL_MS); } catch (InterruptedException ignored) {}
        }
        return false;
    }

    // ============================================================
    // Status helpers
    // ============================================================
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
        try {
            int[] st = link.opDeliveryStatus();
            return new DeliveryStatus(st[0], st[1]);
        } catch (Exception e) {
            softResync(reason);
            try {
                int[] st2 = link.opDeliveryStatus();
                return new DeliveryStatus(st2[0], st2[1]);
            } catch (Exception e2) {
                if (listener != null) listener.onLog("[0x28] Failed after resync: " + reason);
                return null;
            }
        }
    }

    private void softResync(String reason) {
        link.drainInput(250);
        link.forceSyncNext(reason);
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

    // Helpers called from startNewDelivery (kept simple)
    private void setProduct() { /* not used in this version; start uses doStartNewDelivery */ }
    private void readDecimalsIfNeeded() { /* not used */ }
    private void writePreset() { /* not used */ }
    private void issueRun() { /* not used */ }
    private void enterPrestart() { /* not used */ }
    private void printLastTicket() { /* handled by clearTicketPending */ }
    private void resumePausedDelivery() { /* handled by doAlignOrRecover */ }
}
