
package com.pa.lcr.lcp;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DeliveryController
 *
 * Implémentation officielle de DeliveryControllerPort.
 *
 * RESPONSABILITÉS :
 *  - Orchestration protocolaire LCP (DSK aligned)
 *  - Gestion RS-232 multi-drop (pas de sync agressif)
 *  - Poll contextuel (200ms FLOW / 700ms sinon)
 *  - Aucune logique UI
 *
 * MainActivity NE DOIT JAMAIS accéder à LcpLink.
 */
public final class DeliveryController implements DeliveryControllerPort {

    /* ==========================================================
     * Constantes LCR / DSK
     * ========================================================== */

    private static final int FIELD_ACTIVE_PRODUCT = 0;   // index 0..15
    private static final int FIELD_PRODUCT_CODE   = 1;   // ASCII
    private static final int FIELD_PRESET_NET     = 6;   // int32
    private static final int FIELD_DECIMALS       = 39;  // U8

    private static final int CMD_RUN = 0x00;
    private static final int CMD_END = 0x02;

    /* ==========================================================
     * Dépendances
     * ========================================================== */

    private final LcpLink link;
    private final ExecutorService io;

    private Listener listener;

    /* ==========================================================
     * État interne
     * ========================================================== */

    private volatile DeliveryState state = DeliveryState.DISCONNECTED;
    private volatile boolean monitorRunning = false;
    private volatile boolean suspendMonitor = false;

    private Thread monitorThread;

    /* ==========================================================
     * Construction
     * ========================================================== */

    public DeliveryController(LcpLink link) {
        this.link = link;
        this.io = Executors.newSingleThreadExecutor();
    }

    /* ==========================================================
     * Listener
     * ========================================================== */

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /* ==========================================================
     * Cycle de vie
     * ========================================================== */

    @Override
    public void initialize() {
        io.execute(() -> {
            setState(DeliveryState.CONNECTED);

            // Sync-first BEST EFFORT (UNE FOIS)
            try {
                link.opGetProductId(); // Msg 0x00
                notifyActiveNode();
            } catch (Exception e) {
                log("sync-first skipped: " + e.getMessage());
            }

            refreshProducts();
        });
    }

    @Override
    public void shutdown() {
        io.execute(() -> {
            stopMonitor();
            io.shutdownNow();
            setState(DeliveryState.DISCONNECTED);
        });
    }

    /* ==========================================================
     * Produits
     * ========================================================== */

    @Override
    public void refreshProducts() {
        io.execute(() -> {
            try {
                int activeIdx0 = readActiveProductIndex();
                String code = readProductCode();

                notifyActiveNode();
                publishProducts(activeIdx0, code);

            } catch (Exception e) {
                error("refreshProducts", e);
            }
        });
    }

    @Override
    public void selectProduct(int product1to16) {
        io.execute(() -> {
            suspendMonitor();
            try {
                int idx0 = product1to16 - 1;

                link.opSetField(
                        FIELD_ACTIVE_PRODUCT,
                        new byte[]{(byte) idx0}
                ); // 21 00 xx

                confirmActiveProduct(idx0);

                notifyActiveNode();
                publishProducts(idx0, readProductCode());

            } catch (Exception e) {
                error("selectProduct", e);
            } finally {
                resumeMonitor();
            }
        });
    }

    /* ==========================================================
     * Livraison
     * ========================================================== */

    @Override
    public void startDelivery(int product1to16, double presetNet) {
        io.execute(() -> {
            suspendMonitor();
            try {
                setState(DeliveryState.PRESTART);

                ensureActiveProduct(product1to16);
                writePresetNet(presetNet);

                link.opIssueCommand(CMD_RUN); // RUN

                notifyActiveNode();
                setState(DeliveryState.STARTING);
                startMonitor();

            } catch (Exception e) {
                error("startDelivery", e);
            } finally {
                resumeMonitor();
            }
        });
    }

    @Override
    public void resumeIfPaused() {
        io.execute(() -> {
            if (state == DeliveryState.RUNNING_PAUSED) {
                try {
                    link.opIssueCommand(CMD_RUN);
                    notifyActiveNode();
                } catch (Exception e) {
                    error("resumeIfPaused", e);
                }
            }
        });
    }

    @Override
    public void endDelivery() {
        io.execute(() -> {
            suspendMonitor();
            try {
                setState(DeliveryState.ENDING);

                link.opIssueCommand(CMD_END); // END

                notifyActiveNode();
                waitDeliveryClear();
                setState(DeliveryState.ENDED);

            } catch (Exception e) {
                error("endDelivery", e);
            } finally {
                resumeMonitor();
            }
        });
    }

    /* ==========================================================
     * État
     * ========================================================== */

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

    /* ==========================================================
     * Monitor Delivery Status (0x28)
     * ========================================================== */

    private void startMonitor() {
        if (monitorRunning) return;

        monitorRunning = true;
        monitorThread = new Thread(this::monitorLoop, "LCR-Monitor");
        monitorThread.setDaemon(true);
        monitorThread.start();
    }

    private void stopMonitor() {
        monitorRunning = false;
        if (monitorThread != null) {
            monitorThread.interrupt();
            monitorThread = null;
        }
    }

    private void suspendMonitor() {
        suspendMonitor = true;
    }

    private void resumeMonitor() {
        suspendMonitor = false;
    }

    private void monitorLoop() {
        while (monitorRunning) {
            try {
                if (suspendMonitor) {
                    Thread.sleep(50);
                    continue;
                }

                int[] ds = link.opDeliveryStatus(); // 0x28
                int dc = ds[1];

                boolean delivery = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;
                boolean flow     = (dc & LcpLink.LCRSc_FLOW_ACTIVE) != 0;

                if (!delivery) {
                    setState(DeliveryState.IDLE);
                    Thread.sleep(700);
                } else if (flow) {
                    setState(DeliveryState.RUNNING_FLOWING);
                    Thread.sleep(200);
                } else {
                    setState(DeliveryState.RUNNING_PAUSED);
                    Thread.sleep(700);
                }

            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                log("monitor error: " + e.getMessage());
                try { Thread.sleep(700); } catch (InterruptedException ex) { return; }
            }
        }
    }

    /* ==========================================================
     * Helpers LCP
     * ========================================================== */

    private int readActiveProductIndex() throws Exception {
        byte[] b = link.opGetField(FIELD_ACTIVE_PRODUCT);
        return b[0] & 0xFF;
    }

    private void confirmActiveProduct(int expectedIdx0) throws Exception {
        int v = readActiveProductIndex();
        if (v != expectedIdx0) {
            throw new IllegalStateException(
                    "Product mismatch: expected=" + expectedIdx0 + " got=" + v
            );
        }
    }

    private String readProductCode() throws Exception {
        byte[] b = link.opGetField(FIELD_PRODUCT_CODE);
        return new String(b, StandardCharsets.US_ASCII).trim();
    }

    private void ensureActiveProduct(int product1to16) throws Exception {
        int want = product1to16 - 1;
        int cur = readActiveProductIndex();
        if (cur != want) {
            link.opSetField(
                    FIELD_ACTIVE_PRODUCT,
                    new byte[]{(byte) want}
            );
            confirmActiveProduct(want);
        }
    }

    private void writePresetNet(double preset) throws Exception {
        byte[] dec = link.opGetField(FIELD_DECIMALS);
        int scale = (int) Math.pow(10, dec[0]);

        int value = (int) Math.round(preset * scale);

        byte[] buf = new byte[]{
                (byte) ((value >> 24) & 0xFF),
                (byte) ((value >> 16) & 0xFF),
                (byte) ((value >> 8) & 0xFF),
                (byte) (value & 0xFF)
        };

        link.opSetField(FIELD_PRESET_NET, buf);
    }

   DeliveryClear() throws Exception {
        while (true) {
            int[] ds = link.opDeliveryStatus();
            int dc = ds[1];

            boolean delivery = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;
            boolean flow     = (dc & LcpLink.LCRSc_FLOW_ACTIVE) != 0;

            if (!delivery && !flow) return;

            Thread.sleep(200);
        }
    }

    /* ==========================================================
     * UI publication
     * ========================================================== */

    private void publishProducts(int activeIdx0, String code) {
        if (listener == null) return;

        List<ProductUiItem> list = new ArrayList<>();
        for (int i = 1; i <= 16; i++) {
            if (i - 1 == activeIdx0 && code != null && !code.isEmpty()) {
                list.add(new ProductUiItem(i, "Produit " + i + " (" + code + ")"));
            } else {
                list.add(new ProductUiItem(i, "Produit " + i));
            }
        }

        listener.onProductsUpdated(list, activeIdx0);
    }

    /* ==========================================================
     * Logging / erreurs
     * ========================================================== */

    private void notifyActiveNode() {
        if (listener == null) return;

        Integer node = link.getLastResponderNode();
        if (node != null) {
            listener.onLog(String.format(
                    "Node actif confirmé : %d (0x%02X)",
                    node, node
            ));
        }
    }

    private void setState(DeliveryState s) {
        if (state != s) {
            state = s;
            if (listener != null) listener.onStateChanged(s);
        }
    }

    private void log(String msg) {
        if (listener != null) listener.onLog(msg);
    }

    private void error(String ctx, Exception e) {
        if (listener != null) listener.onError(ctx, e);
    }
}
