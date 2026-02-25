
package com.pa.lcr.lcp;

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

    private static final long TICKET_TIMEOUT_MS = 20_000;
    private static final long TICKET_POLL_MS = 250;

    private final LcpLink link;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private Listener listener;
    private volatile DeliveryState state = DeliveryState.DISCONNECTED;

    private volatile int cachedDigits = -1;
    private volatile boolean flowOffStable = true;

    private final ThreadLocal<Boolean> inLiveSample = new ThreadLocal<>();
    private volatile boolean txRxEnabled = false;

    private volatile boolean pendingStart = false;
    private volatile int pendingProduct1to16 = 1;
    private volatile double pendingPresetNet = 0.0;

    private volatile boolean startInProgress = false;

    private volatile long lastResyncMs = 0L;

    public DeliveryController(LcpLink link) { this.link = link; }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
        if (listener == null) { link.setTraceSink(null); return; }

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
            if (listener != null) listener.onLog("LCP prêt (sans refresh automatique)");
            refreshConnectedLive("INIT");
        });
    }

    @Override public void shutdown() { io.shutdownNow(); setState(DeliveryState.DISCONNECTED); }
    @Override public void refreshProducts() { if (listener != null) listener.onLog("refreshProducts ignoré"); }

    @Override
    public void selectProduct(int product1to16) {
        io.execute(() -> {
            try {
                int idx0 = product1to16 - 1;
                link.opSetField(FIELD_ACTIVE_PRODUCT, new byte[]{(byte) idx0});
            } catch (Exception e) {
                if (listener != null) listener.onError("selectProduct", e);
            }
        });
    }

    @Override public DeliveryState getState() { return state; }
    @Override public boolean isDeliveryActive() { return state == DeliveryState.RUNNING_FLOWING || state == DeliveryState.RUNNING_PAUSED; }
    @Override public boolean isPaused() { return state == DeliveryState.RUNNING_PAUSED; }
    @Override public boolean isFlowOffStable() { return flowOffStable; }
    @Override public long getFlowOffAgeMs() { return 0L; }

    // A = align/recover (doit traiter TicketPending)
    @Override
    public void alignOrRecover() {
        io.execute(() -> {
            try {
                pendingStart = false;
                if (listener != null) listener.onLog("[A] Align / recover requested");
                doAlignOrRecover();
            } catch (Exception e) {
                if (listener != null) listener.onError("alignOrRecover", e);
                setState(DeliveryState.CONNECTED);
                refreshConnectedLive("A/ERR");
            }
        });
    }

    // C = intent start (si ticket pending: align+print puis auto-start)
    @Override
    public void startDelivery(int product1to16, double presetNet) {
        io.execute(() -> {
            try {
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
                    refreshConnectedLive("C/precheck-null");
                    return;
                }

                if (isReadyToStart(st)) {
                    if (listener != null) listener.onLog("[C] Register ready → START now");
                    pendingStart = false;
                    doStartNewDelivery(pendingProduct1to16, pendingPresetNet);
                    startInProgress = false;
                    return;
                }

                if (listener != null) {
                    listener.onLiveStatus(st.ticketPending
                            ? "LIVE: CONNECTED — Ticket_pending (recovering)"
                            : "LIVE: CONNECTED — Alignement en cours");
                    listener.onLog("[C] Register NOT ready → align/recover");
                }

                doAlignOrRecover(); // WILL clear ticket and then auto-start
                startInProgress = false;

            } catch (Exception e) {
                if (listener != null) listener.onError("startDelivery(C-intent)", e);
                startInProgress = false;
                refreshConnectedLive("C/ERR");
            }
        });
    }

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
                if (state != DeliveryState.RUNNING_PAUSED) return;
                if (!flowOffStable) return;

                setState(DeliveryState.ENDING);
                if (listener != null) listener.onLog("[END] Issue END (0x02)");
                link.opIssueCommand(CMD_END);

                long deadline = System.currentTimeMillis() + 15000;
                while (System.currentTimeMillis() < deadline) {
                    DeliveryStatus st = readStatusWithResync("END/poll");
                    if (st != null && !st.deliveryActive) break;
                    try { Thread.sleep(250); } catch (InterruptedException ignored) {}
                }

                setState(DeliveryState.CONNECTED);
                refreshConnectedLive("END/done");

            } catch (Exception e) {
                if (listener != null) listener.onError("endDelivery", e);
                setState(DeliveryState.CONNECTED);
                refreshConnectedLive("END/ERR");
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

    @Override
    public void requestLiveSample() {
        io.execute(() -> {
            inLiveSample.set(true);
            try {
                DeliveryStatus st = readStatusWithResync("LIVE/0x28");
                if (st == null) return;

                if (!st.deliveryActive) {
                    setState(DeliveryState.CONNECTED);
                    refreshConnectedLive("LIVE/inactive");
                    flowOffStable = true;
                    return;
                }

                if (!st.flowActive) {
                    setState(DeliveryState.RUNNING_PAUSED);
                    if (listener != null) listener.onLiveStatus("LIVE: RUNNING_PAUSED (FLOW OFF)");
                    flowOffStable = true;
                    return;
                }

                ensureDigits();
                int grossRaw = beI32(link.opGetField(FIELD_GROSS_COUNT));
                int netRaw = beI32(link.opGetField(FIELD_NET_COUNT));
                double scale = Math.pow(10, cachedDigits);

                if (listener != null) {
                    listener.onLiveQty(netRaw / scale, grossRaw / scale);
                    listener.onLiveStatus("LIVE: RUNNING_FLOWING (FLOW ON)");
                }
                setState(DeliveryState.RUNNING_FLOWING);
                flowOffStable = false;

            } catch (Exception ignored) {
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
                    setState(DeliveryState.CONNECTED);
                    refreshConnectedLive("SNAP/inactive");
                    return;
                }

                setState(DeliveryState.RUNNING_PAUSED);
                if (listener != null) listener.onLiveStatus("LIVE: RUNNING_PAUSED (snapshot)");

            } catch (Exception ignored) {}
        });
    }

    // === Align/recover core ===
    private void doAlignOrRecover() throws Exception {
        DeliveryStatus st = readStatusWithResync("A/0x28");
        if (st == null) return;

        // ✅ ALWAYS handle ticket pending (like python Issue #6)
        if (st.ticketPending) {
            if (listener != null) {
                listener.onLiveStatus("LIVE: CONNECTED — Ticket_pending (recovering)");
                listener.onLog("[A] Ticket pending → Issue #6");
            }
            boolean ok = clearTicketPending();
            if (!ok) return;
            st = readStatusWithResync("A/0x28-after-ticket");
            if (st == null) return;
        }

        // If active delivery but no flow -> paused (operator decides continue/end)
        if (st.deliveryActive && !st.flowActive) {
            setState(DeliveryState.RUNNING_PAUSED);
            flowOffStable = true;
            if (listener != null) listener.onLiveStatus("LIVE: RUNNING_PAUSED (recovered)");
            return;
        }

        // If active delivery & flow -> reflect
        if (st.deliveryActive && st.flowActive) {
            setState(DeliveryState.RUNNING_FLOWING);
            flowOffStable = false;
            if (listener != null) listener.onLiveStatus("LIVE: RUNNING_FLOWING (recovered)");
            return;
        }

        // clean idle
        setState(DeliveryState.CONNECTED);
        if (listener != null) listener.onLiveStatus(st.ticketPending
                ? "LIVE: CONNECTED — Ticket pending"
                : "LIVE: CONNECTED — Prêt à livrer");

        // auto-start if C intent pending and now clean
        if (pendingStart && isReadyToStart(st)) {
            if (listener != null) listener.onLog("[AUTO] Alignment complete → START");
            pendingStart = false;
            doStartNewDelivery(pendingProduct1to16, pendingPresetNet);
        }
    }

    // START direct + rollback
    private void doStartNewDelivery(int product1to16, double presetNet) {
        try {
            setState(DeliveryState.PRESTART);
            if (listener != null) listener.onLiveStatus("LIVE: PRESTART (internal)");

            int idx0 = product1to16 - 1;
            link.opSetField(FIELD_ACTIVE_PRODUCT, new byte[]{(byte) idx0});

            bestEffortReadDecimals();
            writePresetNet_WithCacheOrFallback(presetNet);

            link.opIssueCommand(CMD_RUN);

            setState(DeliveryState.RUNNING_PAUSED);
            flowOffStable = true;
            if (listener != null) listener.onLiveStatus("LIVE: RUNNING_PAUSED");

        } catch (Exception e) {
            if (listener != null) listener.onError("START", e);
            setState(DeliveryState.CONNECTED);
            refreshConnectedLive("START/ERR");
        }
    }

    private boolean clearTicketPending() {
        final long deadline = System.currentTimeMillis() + TICKET_TIMEOUT_MS;
        try {
            link.opIssueCommand(CMD_PRINT_LAST_TICKET);
        } catch (Exception e) {
            softResync("TICKET/issue6");
        }
        while (System.currentTimeMillis() < deadline) {
            DeliveryStatus st = readStatusWithResync("TICKET/0x28");
            if (st != null && !st.ticketPending) return true;
            try { Thread.sleep(TICKET_POLL_MS); } catch (InterruptedException ignored) {}
        }
        return false;
    }

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
        long now = System.currentTimeMillis();
        if (now - lastResyncMs < 1500) return;
        lastResyncMs = now;
        link.drainInput(250);
        link.forceSyncNext(reason);
    }

    private void refreshConnectedLive(String tag) {
        DeliveryStatus st = readStatusWithResync("LIVE/" + tag);
        if (listener == null) return;
        if (st == null) {
            listener.onLiveStatus("LIVE: CONNECTED — (état inconnu)");
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
}
