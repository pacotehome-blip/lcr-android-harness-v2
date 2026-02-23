
package com.pa.lcr.lcp;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DeliveryController implements DeliveryControllerPort {

    /* ===================== Constantes LCP ===================== */
    private static final int FIELD_ACTIVE_PRODUCT = 0; // 0..15
    private static final int FIELD_PRESET_NET = 6;
    private static final int FIELD_DECIMALS = 39;

    private static final int CMD_RUN = 0x00;
    private static final int CMD_END = 0x02;

    /* ===================== Dépendances ===================== */
    private final LcpLink link;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private Listener listener;
    private volatile DeliveryState state = DeliveryState.DISCONNECTED;

    public DeliveryController(LcpLink link) {
        this.link = link;
    }

    /* ===================== Listener ===================== */
    @Override
    public void setListener(Listener listener) {
        this.listener = listener;

        // ✅ Branche le trace LCP directement vers le log UI
        if (listener != null) {
            link.setTraceSink(listener::onLog);
        } else {
            link.setTraceSink(null);
        }
    }

    /* ===================== Cycle de vie ===================== */
    @Override
    public void initialize() {
        io.execute(() -> {
            // UX figée: aucun accès registre au connect
            setState(DeliveryState.CONNECTED);
            log("LCP prêt (sans refresh automatique)");
        });
    }

    @Override
    public void shutdown() {
        io.shutdownNow();
        setState(DeliveryState.DISCONNECTED);
    }

    /* ===================== Interface obligatoire ===================== */
    @Override
    public void refreshProducts() {
        // NO-OP volontaire : UX = pas de rafraîchissement automatique
        log("refreshProducts ignoré (mode sans rafraîchissement)");
    }

    /* ===================== Produit ===================== */
    @Override
    public void selectProduct(int product1to16) {
        io.execute(() -> {
            try {
                int idx0 = product1to16 - 1;
                link.opSetField(FIELD_ACTIVE_PRODUCT, new byte[]{(byte) idx0});
                notifyActiveNode();
            } catch (Exception e) {
                error("selectProduct", e);
            }
        });
    }

    /* ===================== Livraison ===================== */
    @Override
    public void startDelivery(int product1to16, double presetNet) {
        io.execute(() -> {
            try {
                setState(DeliveryState.PRESTART);

                int idx0 = product1to16 - 1;
                link.opSetField(FIELD_ACTIVE_PRODUCT, new byte[]{(byte) idx0});

                writePresetNet(presetNet);

                link.opIssueCommand(CMD_RUN);
                notifyActiveNode();

                setState(DeliveryState.RUNNING_FLOWING);
            } catch (Exception e) {
                error("startDelivery", e);
            }
        });
    }

    @Override
    public void resumeIfPaused() {
        io.execute(() -> {
            try {
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
                setState(DeliveryState.ENDING);
                link.opIssueCommand(CMD_END);
                notifyActiveNode();
                setState(DeliveryState.ENDED);
            } catch (Exception e) {
                error("endDelivery", e);
            }
        });
    }

    /* ===================== État ===================== */
    @Override
    public DeliveryState getState() {
        return state;
    }

    @Override
    public boolean isDeliveryActive() {
        return state == DeliveryState.RUNNING_FLOWING
                || state == DeliveryState.RUNNING_PAUSED;
    }

    @Override
    public boolean isPaused() {
        return state == DeliveryState.RUNNING_PAUSED;
    }

    /* ===================== Helpers ===================== */
    private void writePresetNet(double preset) throws Exception {
        // Field #39 -> un byte: index (0..3), mapping vers digits
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
            case 0: return 2; // Hundredths
            case 1: return 1; // Tenths
            case 2: return 0; // Whole
            case 3: return 3; // Thousandths
            default: return 2;
        }
    }

    private void notifyActiveNode() {
        if (listener == null) return;
        Integer node = link.getLastResponderNode();
        if (node != null) {
            listener.onLog("Node actif : " + node);
        }
    }

    private void setState(DeliveryState s) {
        state = s;
        if (listener != null) listener.onStateChanged(s);
    }

    private void log(String msg) {
        if (listener != null) listener.onLog(msg);
    }

    private void error(String ctx, Exception e) {
        if (listener != null) listener.onError(ctx, e);
    }
}
