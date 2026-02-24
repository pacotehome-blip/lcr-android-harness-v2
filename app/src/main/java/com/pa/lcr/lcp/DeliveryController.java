
package com.pa.lcr.lcp;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    private final LcpLink link;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Listener listener;

    private volatile DeliveryState state = DeliveryState.DISCONNECTED;

    // Cache digits (décimales)
    private volatile int cachedDigits = -1;

    // Ticket clear parameters
    private static final long TICKET_TIMEOUT_MS = 20_000;
    private static final long TICKET_POLL_MS = 250;

    private volatile boolean startInProgress = false;

    // --- OFF stable: snapshot + confirm (pas de loop #44/#45 en OFF stable) ---
    private static final long FLOW_OFF_STABLE_MS = 2000;
    private volatile boolean flowOffStable = true;
    private volatile long flowOffCandidateSinceMs = 0L;
    private volatile boolean offConfirmDone = false;
    private volatile int offSnapGrossRaw = Integer.MIN_VALUE;
    private volatile int offSnapNetRaw = Integer.MIN_VALUE;

    public DeliveryController(LcpLink link) {
        this.link = link;
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
        if (listener != null) link.setTraceSink(listener::onLog); // conserve TX/RX si tu veux le log
        else link.setTraceSink(null);
    }

    @Override
    public void initialize() {
        io.execute(() -> {
            setState(DeliveryState.CONNECTED);
            if (listener != null) listener.onLog("LCP prêt (sans refresh automatique)");
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
    public boolean isFlowOffStable() { return flowOffStable; }

    @Override
    public long getFlowOffAgeMs() {
        long s = flowOffCandidateSinceMs;
        if (s <= 0) return 0L;
        return Math.max(0L, System.currentTimeMillis() - s);
    }

    private void resetFlowStateOutsideDelivery() {
        flowOffStable = true;
        flowOffCandidateSinceMs = 0L;
        offConfirmDone = false;
        offSnapGrossRaw = Integer.MIN_VALUE;
        offSnapNetRaw = Integer.MIN_VALUE;
        if (listener != null) listener.onFlowStability(false, true, 0L);
    }

    /* ================= START (ticket-aware) ================= */
    @Override
    public void startDelivery(int product1to16, double presetNet) {
        io.execute(() -> {
            try {
                if (state == DeliveryState.DISCONNECTED) return;
                if (state == DeliveryState.PRESTART || state == DeliveryState.ENDING) return;
                if (state == DeliveryState.RUNNING_FLOWING || state == DeliveryState.RUNNING_PAUSED) return;
                if (startInProgress) return;

                startInProgress = true;
                setState(DeliveryState.PRESTART);

                int[] st = tryDeliveryStatusWithResync("START/precheck");
                if (st == null) {
                    setState(DeliveryState.CONNECTED);
                    startInProgress = false;
                    return;
                }
                int delCode = st[1];

                if ((delCode & DC_TICKET_PENDING) != 0) {
                    if (listener != null) listener.onLog("TICKET_PENDING détecté → Issue #6 (print ticket) puis START");
                    boolean cleared = clearTicketPending();
                    if (!cleared) {
                        if (listener != null) listener.onLog("START différé: ticket pas encore clear (retry auto dans 2s)");
                        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                        int[] stRetry = tryDeliveryStatusWithResync("START/retry-ticket");
                        if (stRetry != null && (stRetry[1] & DC_TICKET_PENDING) == 0) {
                            doStartSequence(product1to16, presetNet);
                            startInProgress = false;
                            return;
                        }
                        setState(DeliveryState.CONNECTED);
                        startInProgress = false;
                        return;
                    }
                }

                doStartSequence(product1to16, presetNet);
                startInProgress = false;

            } catch (Exception e) {
                if (listener != null) listener.onError("startDelivery", e);
                setState(DeliveryState.CONNECTED);
                startInProgress = false;
            }
        });
    }

    private void doStartSequence(int product1to16, double presetNet) throws Exception {
        int idx0 = product1to16 - 1;
        link.opSetField(FIELD_ACTIVE_PRODUCT, new byte[]{(byte) idx0});

        bestEffortReadDecimalsAfterProduct();
        writePresetNet_WithCacheOrFallback(presetNet);

        link.opIssueCommand(CMD_RUN);

        // IMPORTANT: ne pas annoncer FLOWING tant que FLOW_ACTIVE(bit) n’est pas ON
        flowOffStable = false;
        flowOffCandidateSinceMs = 0L;
        offConfirmDone = false;
        offSnapGrossRaw = Integer.MIN_VALUE;
        offSnapNetRaw = Integer.MIN_VALUE;

        setState(DeliveryState.RUNNING_PAUSED);
    }

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
                int[] st = link.opDeliveryStatus();
                int dc = st[1];
                if ((dc & DC_TICKET_PENDING) == 0) return true;
            } catch (Exception e) {
                if (!resyncDone) { resyncDone = true; softResync("TICKET/0x28"); }
            }
            try { Thread.sleep(TICKET_POLL_MS); } catch (InterruptedException ignored) {}
        }
        return false;
    }

    @Override
    public void resumeIfPaused() {
        io.execute(() -> {
            try {
                if (state != DeliveryState.RUNNING_PAUSED) return;
                link.opIssueCommand(CMD_RUN);

                flowOffStable = false;
                flowOffCandidateSinceMs = 0L;
                offConfirmDone = false;
                offSnapGrossRaw = Integer.MIN_VALUE;
                offSnapNetRaw = Integer.MIN_VALUE;

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
                link.opIssueCommand(CMD_END);

                long deadline = System.currentTimeMillis() + 15000;
                while (System.currentTimeMillis() < deadline) {
                    int[] st = link.opDeliveryStatus();
                    int delCode = st[1];
                    boolean active = (delCode & DC_DELIVERY_ACTIVE) != 0;
                    if (!active) {
                        setState(DeliveryState.ENDED);
                        resetFlowStateOutsideDelivery();
                        return;
                    }
                    try { Thread.sleep(250); } catch (InterruptedException ignored) {}
                }
                setState(DeliveryState.ENDED);
                resetFlowStateOutsideDelivery();

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

    /* ================= LIVE ================= */
    @Override
    public void requestLiveSample() {
        io.execute(() -> {
            try {
                if (!(state == DeliveryState.RUNNING_FLOWING || state == DeliveryState.RUNNING_PAUSED)) return;

                int[] st = link.opDeliveryStatus();
                int delCode = st[1];

                boolean flowBit = (delCode & DC_FLOW_ACTIVE) != 0;
                boolean deliveryActive = (delCode & DC_DELIVERY_ACTIVE) != 0;

                if (!deliveryActive) {
                    setState(DeliveryState.CONNECTED);
                    resetFlowStateOutsideDelivery();
                    return;
                }

                // FLOW ON: lit #44/#45 à chaque tick => UI suit le registre
                if (flowBit) {
                    flowOffStable = false;
                    flowOffCandidateSinceMs = 0L;
                    offConfirmDone = false;
                    offSnapGrossRaw = Integer.MIN_VALUE;
                    offSnapNetRaw = Integer.MIN_VALUE;

                    ensureDigitsInFlow();
                    int grossRaw = beI32(link.opGetField(FIELD_GROSS_COUNT));
                    int netRaw = beI32(link.opGetField(FIELD_NET_COUNT));

                    double scale = Math.pow(10, cachedDigits);
                    if (listener != null) {
                        listener.onLiveQty(netRaw / scale, grossRaw / scale);
                        listener.onFlowStability(true, false, 0L);
                    }

                    setState(DeliveryState.RUNNING_FLOWING);
                    return;
                }

                // FLOW OFF: pas de loop #44/#45 en OFF stable (snapshot + confirm)
                long now = System.currentTimeMillis();

                if (offConfirmDone && flowOffStable) {
                    if (listener != null) listener.onFlowStability(false, true, getFlowOffAgeMs());
                    setState(DeliveryState.RUNNING_PAUSED);
                    return;
                }

                if (flowOffCandidateSinceMs == 0L) {
                    flowOffCandidateSinceMs = now;
                    offConfirmDone = false;

                    ensureDigitsInFlow();
                    offSnapGrossRaw = beI32(link.opGetField(FIELD_GROSS_COUNT));
                    offSnapNetRaw = beI32(link.opGetField(FIELD_NET_COUNT));

                    flowOffStable = false;
                    if (listener != null) listener.onFlowStability(false, false, 0L);

                    setState(DeliveryState.RUNNING_PAUSED);
                    return;
                }

                long age = now - flowOffCandidateSinceMs;

                if (age < FLOW_OFF_STABLE_MS) {
                    flowOffStable = false;
                    if (listener != null) listener.onFlowStability(false, false, age);
                    setState(DeliveryState.RUNNING_PAUSED);
                    return;
                }

                ensureDigitsInFlow();
                int gross2 = beI32(link.opGetField(FIELD_GROSS_COUNT));
                int net2 = beI32(link.opGetField(FIELD_NET_COUNT));

                boolean unchanged = (gross2 == offSnapGrossRaw) && (net2 == offSnapNetRaw);

                if (unchanged) {
                    flowOffStable = true;
                    offConfirmDone = true;
                    if (listener != null) listener.onFlowStability(false, true, age);
                } else {
                    flowOffStable = false;
                    flowOffCandidateSinceMs = now;
                    offConfirmDone = false;
                    offSnapGrossRaw = gross2;
                    offSnapNetRaw = net2;
                    if (listener != null) listener.onFlowStability(false, false, 0L);
                }

                setState(DeliveryState.RUNNING_PAUSED);

            } catch (Exception ignored) {}
        });
    }

    /* ================= Helpers ================= */
    private void softResync(String reason) {
        link.drainInput(250);
        link.forceSyncNext(reason);
    }

    private int[] tryDeliveryStatusWithResync(String reason) {
        try {
            return link.opDeliveryStatus();
        } catch (Exception e) {
            softResync(reason);
            try {
                return link.opDeliveryStatus();
            } catch (Exception e2) {
                return null;
            }
        }
    }

    private void bestEffortReadDecimalsAfterProduct() {
        if (cachedDigits >= 0) return;
        try {
            byte[] dec = link.opGetField(FIELD_DECIMALS);
            int idx = (dec.length >= 1) ? (dec[0] & 0xFF) : 0;
            cachedDigits = decimalsDigits(idx);
        } catch (Exception ignored) {}
    }

    private void ensureDigitsInFlow() throws Exception {
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

    @Override public DeliveryState getState() { return state; }
    @Override public boolean isDeliveryActive() { return state == DeliveryState.RUNNING_FLOWING || state == DeliveryState.RUNNING_PAUSED; }
    @Override public boolean isPaused() { return state == DeliveryState.RUNNING_PAUSED; }
}
