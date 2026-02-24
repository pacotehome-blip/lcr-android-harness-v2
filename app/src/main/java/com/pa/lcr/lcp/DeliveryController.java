
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

    // DeliveryCode bits (base reverse fonctionnelle) [2](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/MainActivity.java)
    private static final int DC_TICKET_PENDING  = 0x0001;
    private static final int DC_FLOW_ACTIVE     = 0x0004;
    private static final int DC_DELIVERY_ACTIVE = 0x0008;

    private final LcpLink link;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Listener listener;

    private volatile DeliveryState state = DeliveryState.DISCONNECTED;

    // ✅ Cache digits: lu best-effort après #0 validé, et obligatoire en FLOW_ACTIVE
    private volatile int cachedDigits = -1;

    public DeliveryController(LcpLink link) {
        this.link = link;
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
        if (listener != null) link.setTraceSink(listener::onLog); // TX/RX -> UI log [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/DeliveryControllerPort.java)[2](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/MainActivity.java)
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
     * A) START : resync douce si 0x28 timeout
     *     + best-effort DECIMALS juste après #0 validé
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

                // 1) Pré-check 0x28 (avec resync douce si timeout) [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/DeliveryControllerPort.java)[2](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/MainActivity.java)
                int[] st = tryDeliveryStatusWithResync("START/precheck");
                if (st == null) {
                    log("START bloqué: status indisponible (resync échouée)");
                    setState(DeliveryState.CONNECTED);
                    return;
                }

                int delCode = st[1];

                if ((delCode & DC_TICKET_PENDING) != 0) {
                    log("START bloqué: TICKET_PENDING (imprimer/clear requis)");
                    setState(DeliveryState.CONNECTED);
                    return;
                }
                if ((delCode & DC_DELIVERY_ACTIVE) != 0) {
                    log("START bloqué: DELIVERY_ACTIVE (déjà en cours)");
                    setState((delCode & DC_FLOW_ACTIVE) != 0 ? DeliveryState.RUNNING_FLOWING : DeliveryState.RUNNING_PAUSED);
                    return;
                }

                // 2) Sélection produit (#0) — queued aware via LcpLink.sendRecv [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/DeliveryControllerPort.java)[2](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/MainActivity.java)
                int idx0 = product1to16 - 1;
                link.opSetField(FIELD_ACTIVE_PRODUCT, new byte[]{(byte) idx0});
                notifyActiveNode();

                // ✅ 3) Best-effort DECIMALS (#39) tout de suite après #0 validé
                //    (on ne bloque jamais START si #39 est lent/busy)
                bestEffortReadDecimalsAfterProduct();

                // 4) Preset (#6) en utilisant digits si connus, sinon fallback
                writePresetNet_WithCacheOrFallback(presetNet);

                // 5) RUN
                link.opIssueCommand(CMD_RUN);

                notifyActiveNode();
                setState(DeliveryState.RUNNING_FLOWING);

            } catch (Exception e) {
                error("startDelivery", e);
                setState(DeliveryState.CONNECTED);
            }
        });
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
     * C) END : poll 0x28 + resync douce après timeouts
     * ========================================================= */
    @Override
    public void endDelivery() {
        io.execute(() -> {
            try {
                if (state == DeliveryState.DISCONNECTED) { log("END bloqué: DISCONNECTED"); return; }
                if (state == DeliveryState.ENDING) { log("END ignoré: déjà en cours"); return; }

                // ✅ garde-fou: END seulement si livraison active
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
     * B) Status/Diag : resync douce sur timeout
     * ========================================================= */
    @Override
    public void requestStatus() {
        io.execute(() -> {
            if (state == DeliveryState.DISCONNECTED) {
                log("DIAG: bloqué (DISCONNECTED)");
                return;
            }

            Integer prnStatus = null;
            Integer delStatus23 = null, delCode23 = null;
            Integer delStatus28 = null, delCode28 = null;

            // 1) 0x23 d'abord (avec resync si timeout) [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/DeliveryControllerPort.java)[2](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/MainActivity.java)
            try {
                LcpLink.MachineStatus ms = link.opGetMachineStatus();
                prnStatus = ms.prnStatus;
                delStatus23 = ms.delStatus;
                delCode23 = ms.delCode;
            } catch (Exception e) {
                log("DIAG: 0x23 <timeout/erreur> → RESYNC");
                softResync("B/0x23");
                try {
                    LcpLink.MachineStatus ms = link.opGetMachineStatus();
                    prnStatus = ms.prnStatus;
                    delStatus23 = ms.delStatus;
                    delCode23 = ms.delCode;
                } catch (Exception ignored) {
                    log("DIAG: 0x23 <timeout/erreur> (après resync)");
                }
            }

            // 2) 0x28 best-effort (avec resync si timeout) [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/DeliveryControllerPort.java)[2](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/MainActivity.java)
            try {
                int[] ds = link.opDeliveryStatus();
                delStatus28 = ds[0];
                delCode28 = ds[1];
            } catch (Exception e) {
                log("DIAG: 0x28 <timeout/erreur> → RESYNC");
                softResync("B/0x28");
                try {
                    int[] ds = link.opDeliveryStatus();
                    delStatus28 = ds[0];
                    delCode28 = ds[1];
                } catch (Exception ignored) {
                    log("DIAG: 0x28 <timeout/erreur> (après resync)");
                }
            }

            Integer effectiveDelCode = (delCode23 != null) ? delCode23 : delCode28;
            if (effectiveDelCode == null) {
                log("DIAG: <timeout/erreur> (aucun état lisible)");
                return;
            }

            boolean ticketPending  = (effectiveDelCode & DC_TICKET_PENDING) != 0;
            boolean flowActive     = (effectiveDelCode & DC_FLOW_ACTIVE) != 0;
            boolean deliveryActive = (effectiveDelCode & DC_DELIVERY_ACTIVE) != 0;

            boolean outOfPaper = false, noProcessor = false, printerError = false, printing = false;
            if (prnStatus != null) {
                int prn = prnStatus;
                outOfPaper   = (prn & 0x10) != 0;
                noProcessor  = (prn & 0x20) != 0;
                printerError = (prn & 0x40) != 0;
                printing     = (prn & 0x80) != 0;
            }

            StringBuilder sb = new StringBuilder();
            sb.append("DIAG: ");
            if (prnStatus != null && (outOfPaper || noProcessor || printerError)) {
                sb.append("BLOQUÉ → ");
                if (outOfPaper) sb.append("OUT_OF_PAPER ");
                if (noProcessor) sb.append("NO_PROCESSOR ");
                if (printerError) sb.append("PRINTER_ERROR ");
                if (ticketPending) sb.append("+ TICKET_PENDING ");
            } else if (ticketPending) {
                sb.append("BLOQUÉ → TICKET_PENDING");
                if (printing) sb.append(" (printing)");
            } else if (deliveryActive || flowActive) {
                sb.append("LIVRAISON ACTIVE → ");
                if (deliveryActive) sb.append("DELIVERY_ACTIVE ");
                if (flowActive) sb.append("FLOW_ACTIVE ");
            } else if (printing) {
                sb.append("EN COURS → PRINTING");
            } else {
                sb.append("OK");
            }
            log(sb.toString().trim());

            if (delStatus28 != null && delCode28 != null) {
                log("DIAG: 0x28 delStatus=0x" + hex4(delStatus28) + " delCode=0x" + hex4(delCode28));
            }
            if (prnStatus != null && delStatus23 != null && delCode23 != null) {
                log("DIAG: 0x23 prnStatus=0x" + hex2(prnStatus) +
                        " delStatus=0x" + hex4(delStatus23) +
                        " delCode=0x" + hex4(delCode23));
            }

            if (deliveryActive && flowActive) setState(DeliveryState.RUNNING_FLOWING);
            else if (deliveryActive) setState(DeliveryState.RUNNING_PAUSED);
            else setState(DeliveryState.CONNECTED);

            notifyActiveNode();
        });
    }

    /* =========================================================
     * LIVE: NET/GROSS seulement quand FLOW_ACTIVE
     *     + assure DECIMALS en flow
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

            } catch (Exception ignored) {
                // LIVE tolérant
            }
        });
    }

    /* ===================== RESYNC helpers ===================== */

    private void softResync(String reason) {
        // Utilise tes primitives LcpLink (déjà présentes) [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/DeliveryControllerPort.java)
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

    /* ===================== DECIMALS logic ===================== */

    /**
     * Best-effort après #0 validé : si ça timeoute, on ne bloque pas START.
     */
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

    /**
     * Obligatoire en flow : si on n’a pas encore cachedDigits, on lit #39.
     * Si timeout, resync + retry une fois.
     */
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

    /**
     * Écrit preset net (#6) en utilisant cachedDigits, sinon fallback digits=1.
     * (On évite absolument de lire #39 ici.)
     */
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

    /* ===================== Misc ===================== */

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
