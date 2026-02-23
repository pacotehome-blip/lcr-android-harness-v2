
package com.pa.lcr.lcp;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DeliveryController implements DeliveryControllerPort {

    private static final int FIELD_ACTIVE_PRODUCT = 0; // 0..15
    private static final int FIELD_PRESET_NET = 6;
    private static final int FIELD_DECIMALS = 39;

    private static final int CMD_RUN = 0x00;
    private static final int CMD_END = 0x02;

    // DeliveryCode bits (16-bit)
    private static final int DC_TICKET_PENDING  = 0x0001;
    private static final int DC_FLOW_ACTIVE     = 0x0004;
    private static final int DC_DELIVERY_ACTIVE = 0x0008;

    private final LcpLink link;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Listener listener;
    private volatile DeliveryState state = DeliveryState.DISCONNECTED;

    public DeliveryController(LcpLink link) {
        this.link = link;
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
        if (listener != null) link.setTraceSink(listener::onLog);  // TX/RX → UI log
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
        // NO-OP volontaire (UX figée)
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

    @Override
    public void startDelivery(int product1to16, double presetNet) {
        io.execute(() -> {
            try {
                if (state == DeliveryState.DISCONNECTED) { log("START bloqué: DISCONNECTED"); return; }
                if (state == DeliveryState.PRESTART || state == DeliveryState.ENDING) { log("START bloqué: action déjà en cours ("+state+")"); return; }
                if (state == DeliveryState.RUNNING_FLOWING || state == DeliveryState.RUNNING_PAUSED) { log("START bloqué: livraison déjà active ("+state+")"); return; }

                setState(DeliveryState.PRESTART);

                // Action utilisateur → on peut lire 0x28 pour éviter start impossible
                int[] st = link.opDeliveryStatus();
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

                int idx0 = product1to16 - 1;
                link.opSetField(FIELD_ACTIVE_PRODUCT, new byte[]{(byte) idx0});
                writePresetNet(presetNet);
                link.opIssueCommand(CMD_RUN);

                notifyActiveNode();
                setState(DeliveryState.RUNNING_FLOWING);

            } catch (Exception e) {
                error("startDelivery", e);
                // très important: ne pas rester bloqué en PRESTART
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

    @Override
    public void endDelivery() {
        io.execute(() -> {
            try {
                if (state == DeliveryState.DISCONNECTED) { log("END bloqué: DISCONNECTED"); return; }
                if (state == DeliveryState.ENDING) { log("END ignoré: déjà en cours"); return; }

                setState(DeliveryState.ENDING);
                link.opIssueCommand(CMD_END);
                notifyActiveNode();

                // Fin réelle (action utilisateur A/Finish)
                long deadline = System.currentTimeMillis() + 15000;
                while (System.currentTimeMillis() < deadline) {
                    int[] st = link.opDeliveryStatus();
                    int delCode = st[1];
                    boolean active = (delCode & DC_DELIVERY_ACTIVE) != 0;
                    boolean flow = (delCode & DC_FLOW_ACTIVE) != 0;
                    if (!active && !flow) {
                        setState(DeliveryState.ENDED);
                        return;
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

    @Override
    public void requestStatus() {
        io.execute(() -> {
            try {
                if (state == DeliveryState.DISCONNECTED) { log("STATUS bloqué: DISCONNECTED"); return; }

                int[] st = link.opDeliveryStatus();
                int delStatus = st[0];
                int delCode = st[1];

                log("STATUS: delStatus=0x" + hex4(delStatus)
                        + " delCode=0x" + hex4(delCode)
                        + " flags=" + decodeFlags(delCode));

                boolean active = (delCode & DC_DELIVERY_ACTIVE) != 0;
                boolean flow = (delCode & DC_FLOW_ACTIVE) != 0;

                if (active && flow) setState(DeliveryState.RUNNING_FLOWING);
                else if (active) setState(DeliveryState.RUNNING_PAUSED);
                else setState(DeliveryState.CONNECTED);

                notifyActiveNode();

            } catch (Exception e) {
                error("status", e);
            }
        });
    }

    @Override public DeliveryState getState() { return state; }

    @Override public boolean isDeliveryActive() {
        return state == DeliveryState.RUNNING_FLOWING || state == DeliveryState.RUNNING_PAUSED;
    }

    @Override public boolean isPaused() { return state == DeliveryState.RUNNING_PAUSED; }

    private void writePresetNet(double preset) throws Exception {
        byte[] dec = link.opGetField(FIELD_DECIMALS);
        int idx = (dec.length >= 1) ? (dec[0] & 0xFF) : 0;
        int digits = decimalsDigits(idx);
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

    private String decodeFlags(int delCode) {
        List<String> flags = new ArrayList<>();
        if ((delCode & DC_TICKET_PENDING) != 0) flags.add("TICKET_PENDING");
        if ((delCode & DC_FLOW_ACTIVE) != 0) flags.add("FLOW_ACTIVE");
        if ((delCode & DC_DELIVERY_ACTIVE) != 0) flags.add("DELIVERY_ACTIVE");
        if (flags.isEmpty()) flags.add("(none)");
        return flags.toString();
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

    private void error(String ctx, Exception e) {
        if (listener != null) listener.onError(ctx, e);
    }

    private static String hex4(int v) {
        return String.format("%04X", v & 0xFFFF);
    }
}
