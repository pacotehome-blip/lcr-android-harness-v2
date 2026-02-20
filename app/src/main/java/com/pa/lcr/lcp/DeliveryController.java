
package com.pa.lcr.lcp;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DeliveryController
 *
 * VERSION FINALE NETTOYÉE
 *
 * RÈGLES :
 *  - Aucun rafraîchissement automatique
 *  - L’état = ce que l’UI applique explicitement
 *  - Le controller exécute, il ne décide pas
 *  - Diagnostic minimal (node actif)
 */
public final class DeliveryController implements DeliveryControllerPort {

    /* ===================== Constantes LCP ===================== */

    private static final int FIELD_ACTIVE_PRODUCT = 0; // 0..15
    private static final int FIELD_PRESET_NET     = 6;
    private static final int FIELD_DECIMALS       = 39;

    private static final int CMD_RUN = 0x00;
    private static final int CMD_END = 0x02;

    /* ===================== Dépendances ===================== */

    private final LcpLink link;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private Listener listener;
    private volatile DeliveryState state = DeliveryState.DISCONNECTED;

    /* ===================== Construction ===================== */

    public DeliveryController(LcpLink link) {
        this.link = link;
    }

    /* ===================== Listener ===================== */

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /* ===================== Cycle de vie ===================== */

    @Override
    public void initialize() {
        io.execute(() -> {
            setState(DeliveryState.CONNECTED);

            // Sync-first BEST EFFORT — sans conséquence
            try {
                link.opGetProductId();
            } catch (Exception e) {
                log("sync-first skipped");
            }

            // ✅ AUCUN refresh automatique
            log("LCP prêt (sans refresh automatique)");
        });
    }

    @Override
    public void shutdown() {
        io.shutdownNow();
        setState(DeliveryState.DISCONNECTED);
    }

    /* ===================== Produit ===================== */

    @Override
    public void selectProduct(int product1to16) {
        io.execute(() -> {
            try {
                int idx0 = product1to16 - 1;
                link.opSetField(
                        FIELD_ACTIVE_PRODUCT,
                        new byte[]{(byte) idx0}
                );
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

                // Produit
                int idx0 = product1to16 - 1;
                link.opSetField(
                        FIELD_ACTIVE_PRODUCT,
                        new byte[]{(byte) idx0}
                );

                // Preset net
                writePresetNet(presetNet);

                // RUN
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
        byte[] dec = link.opGetField(FIELD_DECIMALS);
        int scale = (int) Math.pow(10, dec[0]);

        int value = (int) Math.round(preset * scale);

        byte[] buf = new byte[]{
                (byte) (value >> 24),
                (byte) (value >> 16),
                (byte) (value >> 8),
                (byte) value
        };

        link.opSetField(FIELD_PRESET_NET, buf);
    }

    private void notifyActiveNode() {
        if (listener == null) return;

        Integer node = link.getLastResponderNode();
        if (node != null) {
            listener.onLog(
                    "Node actif : " + node
            );
        }
    }

    private void setState(DeliveryState s) {
        state = s;
        if (listener != null) {
            listener.onStateChanged(s);
        }
    }

    private void log(String msg) {
        if (listener != null) {
            listener.onLog(msg);
        }
    }

    private void error(String ctx, Exception e) {
        if (listener != null) {
            listener.onError(ctx, e);
        }
    }
}
