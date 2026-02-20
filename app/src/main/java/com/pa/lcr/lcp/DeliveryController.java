
package com.pa.lcr.lcp;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DeliveryController implements DeliveryControllerPort {

    private static final int FIELD_ACTIVE_PRODUCT = 0;
    private static final int FIELD_PRODUCT_CODE   = 1;
    private static final int FIELD_PRESET_NET     = 6;
    private static final int FIELD_DECIMALS       = 39;

    private static final int CMD_RUN = 0x00;
    private static final int CMD_END = 0x02;

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
    }

    @Override
    public void initialize() {
        io.execute(() -> {
            setState(DeliveryState.CONNECTED);
            try {
                link.opGetProductId();
                notifyActiveNode();
            } catch (Exception e) {
                log("sync-first skipped");
            }
            refreshProducts();
        });
    }

    @Override
    public void shutdown() {
        io.shutdownNow();
        setState(DeliveryState.DISCONNECTED);
    }

    @Override
    public void refreshProducts() {
        io.execute(() -> {
            try {
                int idx0 = link.opGetField(FIELD_ACTIVE_PRODUCT)[0] & 0xFF;
                notifyActiveNode();
                if (listener != null) {
                    listener.onProductsUpdated(null, idx0);
                }
            } catch (Exception e) {
                error("refreshProducts", e);
            }
        });
    }

    @Override
    public void selectProduct(int product1to16) {
        io.execute(() -> {
            try {
                link.opSetField(
                        FIELD_ACTIVE_PRODUCT,
                        new byte[]{(byte) (product1to16 - 1)}
                );
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
                link.opSetField(
                        FIELD_ACTIVE_PRODUCT,
                        new byte[]{(byte) (product1to16 - 1)}
                );
                link.opIssueCommand(CMD_RUN);
                notifyActiveNode();
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
            } catch (Exception e) {
                error("resumeIfPaused", e);
            }
        });
    }

    @Override
    public void endDelivery() {
        io.execute(() -> {
            try {
                link.opIssueCommand(CMD_END);
                notifyActiveNode();
            } catch (Exception e) {
                error("endDelivery", e);
            }
        });
    }

    // ✅ CORRECTION BLOQUANTE
    @Override
    public boolean isPaused() {
        return state == DeliveryState.RUNNING_PAUSED;
    }

    @Override
    public DeliveryState getState() {
        return state;
    }

    @Override
    public boolean isDeliveryActive() {
        return state == DeliveryState.RUNNING_FLOWING
                || state == DeliveryState.RUNNING_PAUSED;
    }

    private void notifyActiveNode() {
        if (listener == null) return;
        Integer node = link.getLastResponderNode();
        if (node != null) {
            listener.onLog(String.format(
                    "Node actif : %d (0x%02X)", node, node));
        }
    }

    private void setState(DeliveryState s) {
        state = s;
        if (listener != null) listener.onStateChanged(s);
    }

    private void log(String m) {
        if (listener != null) listener.onLog(m);
    }

    private void error(String ctx, Exception e) {
        if (listener != null) listener.onError(ctx, e);
    }
}
