
package com.pa.lcr.lcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

public class DeliveryController {

    // Produit actif : Field #0 = index 0..15 (prod1..16) [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/lcr_simple_deliverV2.py)
    private static final int FIELD_ACTIVE_PRODUCT_INDEX = 0;
    private static final int FIELD_PRODUCT_CODE = 1;

    // Preset
    private static final int FIELD_DECIMALS = 39;
    private static final int FIELD_PRESET_NET = 6;

    public static final int MAX_PRODUCTS = 16;

    private final LcpLink link;
    private final DeliveryEvents events;
    private final ExecutorService exec;

    private volatile int activeIndex0 = 0;
    private volatile String activeCode = "";

    public enum State { IDLE, PRESTART, STARTING, RUNNING, ERROR }
    private volatile State state = State.IDLE;

    public interface DeliveryEvents {
        void onStateChanged(State s);
        void onError(String msg, Throwable t);
        void onLog(String line);
        void onProducts(List<ProductUiItem> items, int selectedIndex0);
    }

    public static final class ProductUiItem {
        public final int product1;   // 1..16
        public final String label;   // "Produit N (code)" pour actif
        public ProductUiItem(int product1, String label) {
            this.product1 = product1;
            this.label = label;
        }
        @Override public String toString() { return label; }
    }

    public DeliveryController(LcpLink link, DeliveryEvents events, ExecutorService exec) {
        this.link = link;
        this.events = events;
        this.exec = exec;
    }

    private void log(String s) { if (events != null) events.onLog(s); }

    private void setState(State s) {
        state = s;
        if (events != null) events.onStateChanged(s);
    }

    // ==========================================================
    // product-get-active : Field #0 (index) + Field #1 (code) [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/lcr_simple_deliverV2.py)
    // ==========================================================
    public void refreshProductsUi() {
        exec.execute(() -> {
            try {
                // sync-first: Get Product ID (0x00) best effort (comme --sync-first) [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/lcr_simple_deliverV2.py)[1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/lcr_simple_deliverV2.py)
                try {
                    link.forceSyncNext();
                    link.opGetProductId();
                } catch (Exception ignored) {}

                int idx0 = getActiveProductIndex0();
                String code = decodeAsciiSafe(link.opGetField(FIELD_PRODUCT_CODE));

                activeIndex0 = idx0;
                activeCode = code;

                log("[PROD] Actif = prod" + (idx0 + 1) + " (index=" + idx0 + ")"
                        + (code.isEmpty() ? "" : " code='" + code + "'"));

                publishProductsUi();

            } catch (Exception e) {
                log("[PROD] refreshProductsUi failed: " + e.getMessage());
                if (events != null) events.onError("refreshProductsUi", e);
                // fallback UI list
                publishProductsUiFallback();
            }
        });
    }

    // ==========================================================
    // A) À la sélection spinner : rendre actif immédiatement (safe-set) [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/lcr_simple_deliverV2.py)
    // ==========================================================
    public void selectProductFromUi(int product1to16) {
        exec.execute(() -> {
            try {
                if (product1to16 < 1 || product1to16 > 16) throw new IOException("Product out of range 1..16");
                int wantedIdx0 = product1to16 - 1;

                // safe-set: refuser si ticket pending / delivery active
                ensureCanSwitchProduct();

                int current = getActiveProductIndex0();
                if (current == wantedIdx0) {
                    log("[PROD] Déjà actif: prod" + product1to16 + " (index=" + current + ")");
                    refreshProductsUi(); // refresh label/code
                    return;
                }

                log("[PROD] Set actif → prod" + product1to16 + " (index=" + wantedIdx0 + ")");
                link.opSetField(FIELD_ACTIVE_PRODUCT_INDEX, new byte[]{ (byte) wantedIdx0 });

                int after = getActiveProductIndex0();
                if (after != wantedIdx0) throw new IOException("PRODUCT_SET_FAILED after=" + after + " wanted=" + wantedIdx0);

                activeIndex0 = after;
                activeCode = decodeAsciiSafe(link.opGetField(FIELD_PRODUCT_CODE));

                publishProductsUi();

            } catch (Exception e) {
                log("[PROD] selectProductFromUi failed: " + e.getMessage());
                if (events != null) events.onError("selectProductFromUi", e);
                // revert selection by republishing current
                publishProductsUi();
            }
        });
    }

    // ==========================================================
    // START : suppose le produit déjà actif (car bascule faite à la sélection)
    // ==========================================================
    public void startOpenMode(int product1to16, double presetNetLitres, int timeoutMs, int pollMs) {
        exec.execute(() -> {
            try {
                setState(State.PRESTART);
                log("[PRE] Produit demandé = " + product1to16);

                // sécurité: si l’opérateur a tapé un produit différent, on force ici aussi (double sécurité)
                if (product1to16 >= 1 && product1to16 <= 16) {
                    int idxNow = getActiveProductIndex0();
                    int want = product1to16 - 1;
                    if (idxNow != want) {
                        log("[PRE] Produit actif différent → set Field#0 index=" + want);
                        link.opSetField(FIELD_ACTIVE_PRODUCT_INDEX, new byte[]{ (byte) want });
                        int after = getActiveProductIndex0();
                        if (after != want) throw new IOException("PRODUCT_SET_FAILED at start");
                    }
                }

                int decimals = decodeU8Safe(link.opGetField(FIELD_DECIMALS));
                link.opSetField(FIELD_PRESET_NET, encodePreset(presetNetLitres, decimals));

                setState(State.STARTING);
                link.opIssueCommand(0x00); // RUN
                setState(State.RUNNING);

            } catch (Exception e) {
                setState(State.ERROR);
                if (events != null) events.onError("startOpenMode", e);
            }
        });
    }

    // ==========================================================
    // Helpers produit
    // ==========================================================
    private int getActiveProductIndex0() throws IOException {
        byte[] data = link.opGetField(FIELD_ACTIVE_PRODUCT_INDEX);
        if (data == null || data.length != 1) {
            throw new IOException("Field#0 unexpected len=" + (data == null ? -1 : data.length));
        }
        int idx0 = data[0] & 0xFF;
        if (idx0 > 15) throw new IOException("Active product index out of range: " + idx0);
        return idx0;
    }

    private void ensureCanSwitchProduct() throws IOException {
        int[] dsdc = link.opDeliveryStatus();
        int dc = dsdc[1];
        boolean deliveryActive = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;
        boolean ticketPending  = (dc & LcpLink.LCRSc_DEL_TICKET_PENDING) != 0;
        if (deliveryActive || ticketPending) {
            throw new IOException("PRODUCT_SWITCH_BLOCKED: "
                    + (deliveryActive ? "DELIVERY_ACTIVE " : "")
                    + (ticketPending ? "TICKET_PENDING" : ""));
        }
    }

    private void publishProductsUi() {
        List<ProductUiItem> items = new ArrayList<>();
        for (int p = 1; p <= MAX_PRODUCTS; p++) {
            if (p == (activeIndex0 + 1) && !activeCode.isEmpty()) {
                items.add(new ProductUiItem(p, "Produit " + p + " (" + activeCode + ")"));
            } else {
                items.add(new ProductUiItem(p, "Produit " + p));
            }
        }
        if (events != null) events.onProducts(items, activeIndex0);
    }

    private void publishProductsUiFallback() {
        List<ProductUiItem> items = new ArrayList<>();
        for (int p = 1; p <= MAX_PRODUCTS; p++) items.add(new ProductUiItem(p, "Produit " + p));
        if (events != null) events.onProducts(items, 0);
    }

    private static int decodeU8Safe(byte[] b) {
        return (b != null && b.length > 0) ? (b[0] & 0xFF) : 0;
    }

    private static String decodeAsciiSafe(byte[] b) {
        if (b == null || b.length == 0) return "";
        int len = 0;
        while (len < b.length && b[len] != 0) len++;
        return new String(b, 0, len, StandardCharsets.US_ASCII).trim();
    }

    private static byte[] encodePreset(double litres, int decimalsIx) {
        int scale = (decimalsIx == 1) ? 10 : (decimalsIx == 2 ? 1 : 100);
        int v = (int) Math.round(litres * scale);
        return new byte[] {
                (byte)((v >> 24) & 0xFF),
                (byte)((v >> 16) & 0xFF),
                (byte)((v >>  8) & 0xFF),
                (byte)( v        & 0xFF)
        };
    }

    public void shutdown() {
        setState(State.IDLE);
        log("[CTRL] shutdown");
    }
}
