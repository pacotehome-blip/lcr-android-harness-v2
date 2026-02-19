
package com.pa.lcr.lcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DeliveryController — version "poll adaptatif":
 * - Monitor 0x28 à 200ms uniquement quand DELIVERY_ACTIVE && FLOW_ACTIVE (flow réel) [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/lcr_simple_deliverV2.py)[1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/lcr_simple_deliverV2.py)
 * - Sinon 700ms
 * - Monitor suspendu pendant actions critiques (select produit / start / resume / end)
 * - btnContinue supporté via état RUNNING_PAUSED (delivery active mais flow inactive) [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/lcr_simple_deliverV2.py)[1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/lcr_simple_deliverV2.py)
 */
public class DeliveryController {

    // Produit actif : Field #0 = index 0..15 (prod1..16); Field #1 = code ASCII. [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/lcr_simple_deliverV2.py)
    private static final int FIELD_ACTIVE_PRODUCT_INDEX = 0;
    private static final int FIELD_PRODUCT_CODE = 1;

    // Preset net + decimals
    private static final int FIELD_DECIMALS = 39;
    private static final int FIELD_PRESET_NET = 6;

    public static final int MAX_PRODUCTS = 16;

    // Poll timing
    private static final int POLL_FLOWING_MS = 200; // live réel (comme --poll 0.2) [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/lcr_simple_deliverV2.py)
    private static final int POLL_OTHER_MS   = 700; // idle/paused: réduire le trafic

    private final LcpLink link;
    private final DeliveryEvents events;
    private final ExecutorService exec;

    // monitor thread
    private final Object monitorLock = new Object();
    private volatile boolean monitorRunning = false;
    private volatile boolean monitorStopRequested = false;

    // suspend count (nested)
    private int suspendCount = 0;

    private volatile int activeIndex0 = 0;   // 0..15
    private volatile String activeCode = "";

    public enum State {
        IDLE,
        CONNECTED,
        PRESTART,
        STARTING,
        RUNNING_FLOWING,
        RUNNING_PAUSED,
        ENDING,
        ENDED,
        ERROR
    }
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
        setState(State.CONNECTED);

        // Monitor actif MAIS "light" (700ms) tant qu'on n'est pas en flow.
        startMonitorIfNeeded();
    }

    private void log(String s) { if (events != null) events.onLog(s); }

    private void setState(State s) {
        if (state == s) return;
        state = s;
        if (events != null) events.onStateChanged(s);
    }

    // ==========================================================
    // Monitor adaptatif 0x28 (200ms en FLOW, sinon 700ms)
    // ==========================================================
    private void startMonitorIfNeeded() {
        synchronized (monitorLock) {
            if (monitorRunning) return;
            monitorStopRequested = false;
            monitorRunning = true;

            Thread t = new Thread(this::monitorLoop, "LCP-Monitor");
            t.setDaemon(true);
            t.start();
        }
    }

    private void stopMonitorNow() {
        synchronized (monitorLock) {
            monitorStopRequested = true;
        }
    }

    private void monitorLoop() {
        int sleepMs = POLL_OTHER_MS;
        while (true) {
            synchronized (monitorLock) {
                if (monitorStopRequested) {
                    monitorRunning = false;
                    return;
                }
            }

            // Suspend monitor if a critical operation is in progress
            boolean suspended;
            synchronized (monitorLock) { suspended = (suspendCount > 0); }
            if (suspended) {
                sleepQuiet(50);
                continue;
            }

            try {
                int[] dsdc = link.opDeliveryStatus(); // 0x28 [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/lcr_simple_deliverV2.py)[1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/lcr_simple_deliverV2.py)
                int dc = dsdc[1];

                boolean deliveryActive = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;
                boolean flowActive     = (dc & LcpLink.LCRSc_FLOW_ACTIVE) != 0;

                if (!deliveryActive) {
                    // si on sort d'un RUNNING/ENDING -> ENDED, sinon IDLE
                    if (state == State.RUNNING_FLOWING || state == State.RUNNING_PAUSED || state == State.ENDING) {
                        setState(State.ENDED);
                    } else if (state != State.ERROR && state != State.PRESTART && state != State.STARTING) {
                        setState(State.IDLE);
                    }
                    sleepMs = POLL_OTHER_MS;
                } else {
                    // delivery active
                    if (flowActive) {
                        setState(State.RUNNING_FLOWING);
                        sleepMs = POLL_FLOWING_MS;
                    } else {
                        setState(State.RUNNING_PAUSED);
                        sleepMs = POLL_OTHER_MS;
                    }
                }

            } catch (Exception e) {
                // Ne pas spammer ERROR pour un poll; juste log.
                log("[MON] opDeliveryStatus fail: " + e.getMessage());
                sleepMs = POLL_OTHER_MS;
            }

            sleepQuiet(sleepMs);
        }
    }

    private static void sleepQuiet(int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    // ==========================================================
    // Monitor suspension helpers (critical sections)
    // ==========================================================
    private void suspendMonitor() {
        synchronized (monitorLock) { suspendCount++; }
    }
    private void resumeMonitor() {
        synchronized (monitorLock) {
            suspendCount = Math.max(0, suspendCount - 1);
        }
    }

    // ==========================================================
    // Produit actif: get-active (Field #0) + code (Field #1) [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/lcr_simple_deliverV2.py)
    // ==========================================================
    public void refreshProductsUi() {
        exec.execute(() -> {
            suspendMonitor();
            try {
                // sync-first best effort (Get Product ID 0x00) [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/lcr_simple_deliverV2.py)[1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/lcr_simple_deliverV2.py)
                try { link.forceSyncNext(); link.opGetProductId(); } catch (Exception ignored) {}

                activeIndex0 = getActiveProductIndex0();
                activeCode = decodeAsciiSafe(link.opGetField(FIELD_PRODUCT_CODE));

                log("[PROD] Actif=prod" + (activeIndex0 + 1) + " (index=" + activeIndex0 + ")"
                        + (activeCode.isEmpty() ? "" : " code='" + activeCode + "'"));

                publishProductsUi();
            } catch (Exception e) {
                log("[PROD] refreshProductsUi ERR: " + e.getMessage());
                if (events != null) events.onError("refreshProductsUi", e);
                publishProductsUiFallback();
            } finally {
                resumeMonitor();
            }
        });
    }

    // A) À la sélection spinner : set-product immédiat
    public void selectProductFromUi(int product1to16) {
        exec.execute(() -> {
            suspendMonitor(); // ✅ critique: stop poll 0x28 pendant set-product
            try {
                if (product1to16 < 1 || product1to16 > 16) throw new IOException("Product out of range 1..16");
                int wantedIdx0 = product1to16 - 1;

                // sync-first best effort (payload propre) [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/lcr_simple_deliverV2.py)
                try { link.forceSyncNext(); link.opGetProductId(); } catch (Exception ignored) {}

                // safe-set: si 0x28 échoue, on retente 2 fois sync-first puis on skip (tu l’as demandé: moins de garbage)
                boolean safeOk = false;
                for (int i = 0; i < 2; i++) {
                    try { ensureCanSwitchProduct(); safeOk = true; break; }
                    catch (Exception e) {
                        log("[PROD] safe-check unavailable (" + e.getMessage() + "), retry sync-first");
                        try { link.forceSyncNext(); link.opGetProductId(); } catch (Exception ignored2) {}
                    }
                }
                if (!safeOk) log("[PROD] safe-check SKIPPED (framing instable) → tentative set product");

                int current = getActiveProductIndex0();
                if (current == wantedIdx0) {
                    activeIndex0 = current;
                    activeCode = decodeAsciiSafe(link.opGetField(FIELD_PRODUCT_CODE));
                    log("[PROD] Déjà actif: prod" + product1to16 + " (index=" + current + ")");
                    publishProductsUi();
                    return;
                }

                log("[PROD] SET actif Field#0=" + wantedIdx0 + " (prod" + product1to16 + ")");
                link.opSetField(FIELD_ACTIVE_PRODUCT_INDEX, new byte[]{ (byte) wantedIdx0 }); // [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/lcr_simple_deliverV2.py)

                int after = getActiveProductIndex0(); // confirm [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/lcr_simple_deliverV2.py)
                if (after != wantedIdx0) throw new IOException("PRODUCT_SET_FAILED after=" + after + " wanted=" + wantedIdx0);

                activeIndex0 = after;
                activeCode = decodeAsciiSafe(link.opGetField(FIELD_PRODUCT_CODE));
                log("[PROD] Produit actif confirmé: prod" + (after + 1) + " code='" + activeCode + "'");

                publishProductsUi();

            } catch (Exception e) {
                log("[PROD] selectProductFromUi ERR: " + e.getMessage());
                if (events != null) events.onError("selectProductFromUi", e);
                publishProductsUi(); // revert visuel vers actif connu
            } finally {
                resumeMonitor();
            }
        });
    }

    // ==========================================================
    // START : preset + RUN (0x00)
    // ==========================================================
    public void startOpenMode(int product1to16, double presetNetLitres, int timeoutMs, int pollMs) {
        exec.execute(() -> {
            suspendMonitor(); // ✅ critique
            try {
                setState(State.PRESTART);
                log("[PRE] Produit demandé = " + product1to16);

                // Sécurité: si override opérateur ≠ actif -> set Field#0
                if (product1to16 >= 1 && product1to16 <= 16) {
                    int want = product1to16 - 1;
                    int now = getActiveProductIndex0();
                    if (now != want) {
                        log("[PRE] Produit actif différent → set Field#0 index=" + want);
                        ensureCanSwitchProduct();
                        link.opSetField(FIELD_ACTIVE_PRODUCT_INDEX, new byte[]{ (byte) want });
                        int after = getActiveProductIndex0();
                        if (after != want) throw new IOException("PRODUCT_SET_FAILED at start");
                        activeIndex0 = after;
                        activeCode = decodeAsciiSafe(link.opGetField(FIELD_PRODUCT_CODE));
                        publishProductsUi();
                    }
                }

                int decimals = decodeU8Safe(link.opGetField(FIELD_DECIMALS));
                link.opSetField(FIELD_PRESET_NET, encodePreset(presetNetLitres, decimals));

                setState(State.STARTING);
                link.opIssueCommand(0x00); // RUN [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/lcr_simple_deliverV2.py)

                // Monitor reprend et passera à 200ms automatiquement si FLOW_ACTIVE=1
            } catch (Exception e) {
                setState(State.ERROR);
                if (events != null) events.onError("startOpenMode", e);
            } finally {
                resumeMonitor();
            }
        });
    }

    // ==========================================================
    // B) Continue = resume uniquement si PAUSED (DELIVERY_ACTIVE=1 & FLOW_ACTIVE=0) [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/lcr_simple_deliverV2.py)[1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/lcr_simple_deliverV2.py)
    // ==========================================================
    public void resumeIfPaused(int timeoutMs) {
        exec.execute(() -> {
            suspendMonitor();
            try {
                int[] dsdc = link.opDeliveryStatus();
                int dc = dsdc[1];
                boolean deliveryActive = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;
                boolean flowActive = (dc & LcpLink.LCRSc_FLOW_ACTIVE) != 0;

                if (!deliveryActive || flowActive) {
                    log("[RESUME] Ignoré: pas en PAUSE (deliveryActive=" + deliveryActive + " flowActive=" + flowActive + ")");
                    return;
                }

                log("[RESUME] PAUSE détectée → RUN 0x00");
                link.opIssueCommand(0x00); // resume [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/lcr_simple_deliverV2.py)
            } catch (Exception e) {
                setState(State.ERROR);
                if (events != null) events.onError("resumeIfPaused", e);
            } finally {
                resumeMonitor();
            }
        });
    }

    // ==========================================================
    // END = CMD#2 + wait clear (FLOW & DELIVERY) [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/lcr_simple_deliverV2.py)
    // ==========================================================
    public void endDelivery(int timeoutMs) {
        exec.execute(() -> {
            suspendMonitor();
            try {
                setState(State.ENDING);
                log("[END] Issue #2 (END DELIVERY)");
                link.opIssueCommand(0x02); // END [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/lcr_simple_deliverV2.py)

                long end = System.currentTimeMillis() + timeoutMs;
                while (System.currentTimeMillis() < end) {
                    int[] dsdc = link.opDeliveryStatus();
                    int dc = dsdc[1];
                    boolean deliveryActive = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;
                    boolean flowActive = (dc & LcpLink.LCRSc_FLOW_ACTIVE) != 0;
                    if (!deliveryActive && !flowActive) {
                        log("[END] DELIVERY/FLOW cleared");
                        setState(State.ENDED);
                        return;
                    }
                    Thread.sleep(200);
                }
                throw new IOException("END timeout: DELIVERY/FLOW still active");
            } catch (Exception e) {
                setState(State.ERROR);
                if (events != null) events.onError("endDelivery", e);
            } finally {
                resumeMonitor();
            }
        });
    }

    // ==========================================================
    // Helpers
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
        int[] dsdc = link.opDeliveryStatus(); // 0x28 flags [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/lcr_simple_deliverV2.py)[1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/lcr_simple_deliverV2.py)
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

    public State getState() { return state; }

    public void shutdown() {
        stopMonitorNow();
        setState(State.IDLE);
        log("[CTRL] shutdown");
    }
}
