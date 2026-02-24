
package com.pa.lcr.lcp;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DeliveryController implements DeliveryControllerPort {

    private static final int FIELD_ACTIVE_PRODUCT = 0;   // 0..15
    private static final int FIELD_PRESET_NET     = 6;   // preset net
    private static final int FIELD_DECIMALS       = 39;  // decimals index
    private static final int FIELD_GROSS_COUNT    = 44;  // gross count
    private static final int FIELD_NET_COUNT      = 45;  // net count

    private static final int CMD_RUN = 0x00;
    private static final int CMD_END = 0x02;

    // ✅ Python/doc : Issue #6 = print last ticket / clear ticket_pending
    private static final int CMD_PRINT_LAST_TICKET = 0x06;

    // DeliveryCode bits (base reverse fonctionnelle)
    private static final int DC_TICKET_PENDING  = 0x0001;
    private static final int DC_FLOW_ACTIVE     = 0x0004;
    private static final int DC_DELIVERY_ACTIVE = 0x0008;

    private final LcpLink link;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Listener listener;

    private volatile DeliveryState state = DeliveryState.DISCONNECTED;

    // cache digits
    private volatile int cachedDigits = -1;

    // paramètres ticket clear (aligné Python: retry toutes les 200ms)
    private static final long TICKET_RETRY_MS = 200;
    private static final long TICKET_TIMEOUT_MS = 20_000; // ajuste au besoin

    public DeliveryController(LcpLink link) {
        this.link = link;
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
        if (listener != null) link.setTraceSink(listener::onLog);
        else link.setTraceSink(null);
    }

    @Override
    public void initialize() {
        io.execute(() -> {
            setState(DeliveryState.CONNECTED);
            log("LCP prêt (sans refresh automatique)");
        });
    }

    @Override
    public void shutdown() {
        io.shutdownNow();
        setState(DeliveryState.DISCONNECTED);
    }

    @Override
    public void refreshProducts() {
        log("refreshProducts ignoré (mode sans rafraîchissement)");
    }

    @Override
    public void selectProduct(int product1to16) {
        io.execute(() -> {
            try {
                if (state == DeliveryState.DISCONNECTED) { log("selectProduct ignoré: DISCONNECTED"); return; }
                int idx0 = product1to16 - 1;
                link.opSetField(FIELD_ACTIVE_PRODUCT, new byte[]{(byte) idx0});
                notifyActiveNode();
            } catch (Exception e) {
                error("selectProduct", e);
            }
        });
    }

    /* =========================================================
     * START : A) resync 0x28 si timeout, puis CLEAR TICKET_PENDING via Issue #6
     * ========================================================= */
    @Override
    public void startDelivery(int product1to16, double presetNet) {
        io.execute(() -> {
            try {
                if (state == DeliveryState.DISCONNECTED) { log("START bloqué: DISCONNECTED"); return; }
                if (state == DeliveryState.PRESTART || state == DeliveryState.ENDING) {
                    log("START bloqué: action déjà en cours (" + state + ")");
                    return;
                }
                if (state == DeliveryState.RUNNING_FLOWING || state == DeliveryState.RUNNING_PAUSED) {
                    log("START bloqué: livraison déjà active (" + state + ")");
                    return;
                }

                setState(DeliveryState.PRESTART);

                // 1) Précheck 0x28 (avec resync douce)
                int[] st = tryDeliveryStatusWithResync("START/precheck");
                if (st == null) {
                    log("START bloqué: status indisponible (resync échouée)");
                    setState(DeliveryState.CONNECTED);
                    return;
                }

                int delCode28 = st[1];

                // 2) Si ticket pending: clear via Issue #6 (comme Python/doc)
                if ((delCode28 & DC_TICKET_PENDING) != 0) {
                    log("TICKET_PENDING détecté → tentative clear via Issue #6");
                    boolean cleared = clearTicketPending();
                    if (!cleared) {
                        log("START bloqué: Impossible de clear TICKET_PENDING");
                        setState(DeliveryState.CONNECTED);
                        return;
                    }
                }

                // 3) Recheck 0x28 après clear (best-effort)
                int[] st2 = tryDeliveryStatusWithResync("START/post-ticket");
                if (st2 != null) delCode28 = st2[1];

                // 4) Si encore delivery active, on reflète l'état
                if ((delCode28 & DC_DELIVERY_ACTIVE) != 0) {
                    log("START bloqué: DELIVERY_ACTIVE (déjà en cours)");
                    setState((delCode28 & DC_FLOW_ACTIVE) != 0 ? DeliveryState.RUNNING_FLOWING : DeliveryState.RUNNING_PAUSED);
                    return;
                }

                // 5) Séquence START: SET #0 -> best-effort #39 -> SET #6 -> RUN
                int idx0 = product1to16 - 1;
                link.opSetField(FIELD_ACTIVE_PRODUCT, new byte[]{(byte) idx0});
                notifyActiveNode();

                bestEffortReadDecimalsAfterProduct();
                writePresetNet_WithCacheOrFallback(presetNet);

                link.opIssueCommand(CMD_RUN);

                notifyActiveNode();
                setState(DeliveryState.RUNNING_FLOWING);

            } catch (Exception e) {
                error("startDelivery", e);
                setState(DeliveryState.CONNECTED);
            }
        });
    }

    /**
     * Clear ticket pending by repeatedly issuing command 0x06 and re-reading 0x23
     * until the bit clears, or timeout.
     *
     * Aligné au Python fourni. [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/DeliveryControllerPort.java)
     */
    private boolean clearTicketPending() {
        long t0 = System.currentTimeMillis();

        while (System.currentTimeMillis() - t0 < TICKET_TIMEOUT_MS) {
            try {
                link.opIssueCommand(CMD_PRINT_LAST_TICKET);  // Issue #6
            } catch (Exception e) {
                // si le registre est busy, on peut resync puis continuer
                softResync("TICKET/issue6");
            }

            try { Thread.sleep(TICKET_RETRY_MS); } catch (InterruptedException ignored) {}

            try {
                LcpLink.MachineStatus ms = link.opGetMachineStatus();
                notifyActiveNode();
                int dc = ms.delCode;

                if ((dc & DC_TICKET_PENDING) == 0) {
                    log("Ticket cleared");
                    return true;
                }
            } catch (Exception e) {
                // 0x23 peut timeout: resync et continue
                softResync("TICKET/0x23");
            }
        }
        return false;
    }

    @Override
    public void resumeIfPaused() {
        io.execute(() -> {
            try {
                if (state != DeliveryState.RUNNING_PAUSED) { log("RESUME ignoré: état=" + state); return; }
                link.opIssueCommand(CMD_RUN);
                notifyActiveNode();
                setState(DeliveryState.RUNNING_FLOWING);
            } catch (Exception e) {
                error("resumeIfPaused", e);
            }
        });
    }

    /* =========================================================
     * END : seulement si livraison active + resync douce après timeouts
     * ========================================================= */
    @Override
    public void endDelivery() {
        io.execute(() -> {
            try {
                if (state == DeliveryState.DISCONNECTED) { log("END bloqué: DISCONNECTED"); return; }
                if (state == DeliveryState.ENDING) { log("END ignoré: déjà en cours"); return; }

                if (state != DeliveryState.RUNNING_FLOWING && state != DeliveryState.RUNNING_PAUSED) {
                    log("END ignoré: aucune livraison active (state=" + state + ")");
                    return;
                }

                setState(DeliveryState.ENDING);

                link.opIssueCommand(CMD_END);
                notifyActiveNode();

                long deadline = System.currentTimeMillis() + 15000;
                int consecutiveFailures = 0;
                boolean resyncAttempted = false;

                while (System.currentTimeMillis() < deadline) {
                    try {
                        int[] st = link.opDeliveryStatus();
                        consecutiveFailures = 0;

                        int delCode = st[1];
                        boolean active = (delCode & DC_DELIVERY_ACTIVE) != 0;
                        boolean flow = (delCode & DC_FLOW_ACTIVE) != 0;

                        if (!active && !flow) {
                            setState(DeliveryState.ENDED);
                            return;
                        }

                    } catch (Exception pollErr) {
                        consecutiveFailures++;
                        log("END: poll 0x28 timeout (" + consecutiveFailures + ")");
                        if (!resyncAttempted && consecutiveFailures >= 3) {
                            resyncAttempted = true;
                            softResync("END/poll");
                        }
                        try { Thread.sleep(250); } catch (InterruptedException ignored) {}
                    }
                    try { Thread.sleep(250); } catch (InterruptedException ignored) {}
                }

                log("END: timeout attente clear DELIVERY/FLOW (ticket/impression peut être en cours)");
                setState(DeliveryState.ENDED);

            } catch (Exception e) {
                error("endDelivery", e);
                setState(DeliveryState.CONNECTED);
            }
        });
    }

    /* =========================================================
     * Status/Diag (B) : resync douce sur timeout
     * ========================================================= */
    @Override
    public void requestStatus() {
        io.execute(() -> {
            if (state == DeliveryState.DISCONNECTED) {
                log("DIAG: bloqué (DISCONNECTED)");
                return;
            }

            try {
                // 0x23 (avec resync si timeout)
                LcpLink.MachineStatus ms;
                try {
                    ms = link.opGetMachineStatus();
                } catch (Exception e) {
                    log("DIAG: 0x23 <timeout/erreur> → RESYNC");
                    softResync("B/0x23");
                    ms = link.opGetMachineStatus();
                }

                int delStatus23 = ms.delStatus;
                int delCode23 = ms.delCode;
                int prnStatus = ms.prnStatus;

                // 0x28 best-effort (avec resync si timeout)
                int delStatus28, delCode28;
                try {
                    int[] ds = link.opDeliveryStatus();
                    delStatus28 = ds[0];
                    delCode28 = ds[1];
                } catch (Exception e) {
                    log("DIAG: 0x28 <timeout/erreur> → RESYNC");
                    softResync("B/0x28");
                    int[] ds = link.opDeliveryStatus();
                    delStatus28 = ds[0];
                    delCode28 = ds[1];
                }

                boolean ticketPending  = (delCode23 & DC_TICKET_PENDING) != 0;
                boolean flowActive     = (delCode23 & DC_FLOW_ACTIVE) != 0;
                boolean deliveryActive = (delCode23 & DC_DELIVERY_ACTIVE) != 0;

                log("DIAG: " + (ticketPending ? "BLOQUÉ → TICKET_PENDING" : "OK"));
                log("DIAG: 0x28 delStatus=0x" + hex4(delStatus28) + " delCode=0x" + hex4(delCode28));
                log("DIAG: 0x23 prnStatus=0x" + hex2(prnStatus) + " delStatus=0x" + hex4(delStatus23) + " delCode=0x" + hex4(delCode23));

                if (deliveryActive && flowActive) setState(DeliveryState.RUNNING_FLOWING);
                else if (deliveryActive) setState(DeliveryState.RUNNING_PAUSED);
                else setState(DeliveryState.CONNECTED);

                notifyActiveNode();

            } catch (Exception e) {
                error("status", e);
            }
        });
    }

    /* =========================================================
     * LIVE: NET/GROSS seulement quand FLOW_ACTIVE (et lit #39 en flow)
     * ========================================================= */
    @Override
    public void requestLiveSample() {
        io.execute(() -> {
            try {
                if (!(state == DeliveryState.RUNNING_FLOWING || state == DeliveryState.RUNNING_PAUSED)) return;

                int[] st = link.opDeliveryStatus();
                int delCode = st[1];

                boolean flow = (delCode & DC_FLOW_ACTIVE) != 0;
                boolean active = (delCode & DC_DELIVERY_ACTIVE) != 0;

                if (flow) {
                    ensureDigitsInFlow();

                    int grossRaw = beI32(link.opGetField(FIELD_GROSS_COUNT));
                    int netRaw   = beI32(link.opGetField(FIELD_NET_COUNT));

                    double scale = Math.pow(10, cachedDigits);
                    double gross = grossRaw / scale;
                    double net = netRaw / scale;

                    if (listener != null) listener.onLiveQty(net, gross);
                    setState(DeliveryState.RUNNING_FLOWING);
                    return;
                }

                if (active) setState(DeliveryState.RUNNING_PAUSED);
                else setState(DeliveryState.CONNECTED);

            } catch (Exception ignored) {}
        });
    }

    /* ===================== Resync + Decimals ===================== */

    private void softResync(String reason) {
        link.drainInput(250);
        link.forceSyncNext(reason);
    }

    private int[] tryDeliveryStatusWithResync(String reason) {
        try {
            return link.opDeliveryStatus();
        } catch (Exception e) {
            log("RESYNC: 0x28 timeout (" + reason + ")");
            softResync(reason);
            try {
                int[] ds = link.opDeliveryStatus();
                notifyActiveNode();
                return ds;
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
            log("START: DECIMALS idx=" + idx + " digits=" + cachedDigits);
        } catch (Exception e) {
            log("START: DECIMALS indisponible (best-effort)");
        }
    }

    private void ensureDigitsInFlow() throws Exception {
        if (cachedDigits >= 0) return;
        try {
            byte[] dec = link.opGetField(FIELD_DECIMALS);
            int idx = (dec.length >= 1) ? (dec[0] & 0xFF) : 0;
            cachedDigits = decimalsDigits(idx);
            log("LIVE: DECIMALS idx=" + idx + " digits=" + cachedDigits);
        } catch (Exception e) {
            softResync("DECIMALS");
            byte[] dec = link.opGetField(FIELD_DECIMALS);
            int idx = (dec.length >= 1) ? (dec[0] & 0xFF) : 0;
            cachedDigits = decimalsDigits(idx);
            log("LIVE: DECIMALS idx=" + idx + " digits=" + cachedDigits);
        }
    }

    private void writePresetNet_WithCacheOrFallback(double preset) throws Exception {
        int digits = cachedDigits;
        if (digits < 0) {
            digits = 1; // fallback terrain
            log("START: preset sans DECIMALS (fallback digits=1)");
        }
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

    private void notifyActiveNode() {
        if (listener == null) return;
        Integer node = link.getLastResponderNode();
        if (node != null) listener.onLog("Node actif : " + node);
    }

    private void setState(DeliveryState s) {
        state = s;
        if (listener != null) listener.onStateChanged(s);
    }

    private void log(String msg) { if (listener != null) listener.onLog(msg); }
    private void error(String ctx, Exception e) { if (listener != null) listener.onError(ctx, e); }

    @Override public DeliveryState getState() { return state; }
    @Override public boolean isDeliveryActive() { return state == DeliveryState.RUNNING_FLOWING || state == DeliveryState.RUNNING_PAUSED; }
    @Override public boolean isPaused() { return state == DeliveryState.RUNNING_PAUSED; }

    private static String hex2(int v) { return String.format("%02X", v & 0xFF); }
    private static String hex4(int v) { return String.format("%04X", v & 0xFFFF); }
}
