
package com.pa.lcr.lcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

import com.pa.lcr.lcp.lifecycle.Cmd0Usage;
import com.pa.lcr.lcp.lifecycle.DeliveryLifecycleController;
import com.pa.lcr.lcp.lifecycle.LcpDeliveryState;
import com.pa.lcr.lcp.util.AndroidLifecycleLogger;

public class DeliveryController {

    // ===================== SDK FIELDS =====================
    private static final int FIELD_PRODUCT_NUMBER  = 0;
    private static final int FIELD_PRODUCT_CODE    = 1;
    private static final int FIELD_PRESET_NET      = 6;
    private static final int FIELD_DECIMALS        = 39;
    private static final int FIELD_NET_TOTAL       = 45;
    private static final int FIELD_GROSS_TOTAL     = 44;
    private static final int FIELD_DEFAULT_PRODUCT = 88;

    private static final int MAX_PRODUCTS = 16;

    // ===================== BACKEND =====================
    private final LcpLink link;
    private final DeliveryEvents events;
    private final ExecutorService exec;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

    private ScheduledFuture<?> liveTask;

    // ===================== STATE =====================
    public enum State { IDLE, PRESTART, STARTING, RUNNING, FINISHING, ENDED, ERROR }
    private volatile State state = State.IDLE;

    // ===================== DELIVERY PARAMS =====================
    private double presetNetL;
    private int decimals;
    private double startNet;
    private double startGross;

    // ===================== PRODUIT =====================
    public static final class ProductInfo {
        public final int slot;
        public final int number;
        public final String code;
        public final boolean active;

        public ProductInfo(int slot, int number, String code, boolean active) {
            this.slot = slot;
            this.number = number;
            this.code = code;
            this.active = active;
        }
    }

    private volatile ProductInfo currentProduct = null;

    // ===================== EVENTS =====================
    public interface DeliveryEvents {
        void onStateChanged(State s);
        void onProgress(DeliveryProgress p);
        void onError(String msg, Throwable t);
        void onLog(String line);
    }

    public static final class DeliveryProgress {
        public double netDelta;
        public double grossDelta;
        public LcpDeliveryState deliveryState;
    }

    // ===================== CONSTRUCTOR =====================
    public DeliveryController(LcpLink link, DeliveryEvents events, ExecutorService exec) {
        this.link = link;
        this.events = events;
        this.exec = exec;
    }

    private void log(String s) {
        if (events != null) events.onLog(s);
    }

    private void setState(State s) {
        state = s;
        if (events != null) events.onStateChanged(s);
    }

    // ======================================================
    // ✅ Scan produit (informatif, non bloquant)
    // ======================================================
    public void scanProducts() {
        exec.execute(() -> {
            log("[PROD] Scan produits (informatif)");

            try {
                int def = decodeU8(link.opGetField(FIELD_DEFAULT_PRODUCT));
                if (def > 0 && def <= MAX_PRODUCTS) {
                    log("[PROD] Produit par défaut (#88) = " + def);
                } else {
                    log("[PROD] Aucun produit par défaut explicite (#88=0)");
                }
            } catch (IOException e) {
                log("[PROD] Lecture #88 échouée: " + e.getMessage());
            }

            for (int slot = 1; slot <= MAX_PRODUCTS; slot++) {
                try {
                    int number = decodeU8(link.opGetField(FIELD_PRODUCT_NUMBER));
                    String code = decodeAscii(link.opGetField(FIELD_PRODUCT_CODE));

                    boolean active = number > 0 && !code.isEmpty();
                    if (active && currentProduct == null) {
                        currentProduct = new ProductInfo(slot, number, code, true);
                        log("[PROD] Produit courant retenu = " + number + " (" + code + ")");
                    }
                } catch (IOException e) {
                    log("[PROD] Slot " + slot + " lecture échouée: " + e.getMessage());
                }
            }

            if (currentProduct == null) {
                log("[PROD] Aucun produit actif détecté (fallback opérateur requis)");
            }
        });
    }

    public ProductInfo getCurrentProduct() {
        return currentProduct;
    }

    // ======================================================
    // START DELIVERY
    // ======================================================
    public void startOpenMode(int product, double presetNetLitres, int timeoutMs, int pollMs) {
        exec.execute(() -> {
            try {
                log("[PRE] Produit demandé = " + product);

                presetNetL = presetNetLitres;
                setState(State.PRESTART);

                decimals = decodeU8(link.opGetField(FIELD_DECIMALS));
                link.opSetField(FIELD_PRESET_NET, encodePreset(presetNetL, decimals));

                setState(State.STARTING);
                if (!new DeliveryLifecycleController(new AndroidLifecycleLogger())
                        .allowCmd0(Cmd0Usage.START)) {
                    throw new IllegalStateException("RUN not allowed");
                }

                link.opIssueCommand(0x00);
                waitForActive(timeoutMs);

                startNet   = readNet();
                startGross = readGross();

                setState(State.RUNNING);
                startLiveLoop(pollMs);

            } catch (Exception e) {
                setState(State.ERROR);
                if (events != null) events.onError("startOpenMode", e);
            }
        });
    }

    private void startLiveLoop(int pollMs) {
        liveTask = scheduler.scheduleAtFixedRate(() -> {
            if (state != State.RUNNING) return;
            try {
                double net   = readNet();
                double gross = readGross();

                DeliveryProgress p = new DeliveryProgress();
                p.netDelta   = net - startNet;
                p.grossDelta = gross - startGross;
                p.deliveryState = LcpDeliveryState.ACTIVE_FLOWING;

                if (events != null) events.onProgress(p);

                if (p.netDelta >= presetNetL) finishDelivery();

            } catch (Exception e) {
                if (events != null) events.onError("liveLoop", e);
            }
        }, 0, pollMs, TimeUnit.MILLISECONDS);
    }

    private void finishDelivery() {
        if (state != State.RUNNING) return;
        exec.execute(() -> {
            try {
                setState(State.FINISHING);
                if (liveTask != null) liveTask.cancel(false);
                link.opIssueCommand(0x02);
                setState(State.ENDED);
            } catch (Exception e) {
                setState(State.ERROR);
            }
        });
    }

    private void waitForActive(int timeoutMs) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            int[] dsdc = link.opDeliveryStatus();
            if ((dsdc[1] & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0) return;
            Thread.sleep(200);
        }
        throw new TimeoutException("ACTIVE not confirmed");
    }

    // ===================== IO =====================
    private double readNet() throws IOException {
        return decodeVolume(link.opGetField(FIELD_NET_TOTAL), decimals);
    }

    private double readGross() throws IOException {
        return decodeVolume(link.opGetField(FIELD_GROSS_TOTAL), decimals);
    }

    private static int decodeU8(byte[] b) {
        return (b != null && b.length > 0) ? (b[0] & 0xFF) : 0;
    }

    private static String decodeAscii(byte[] b) {
        if (b == null) return "";
        int len = 0;
        while (len < b.length && b[len] != 0) len++;
        return new String(b, 0, len, StandardCharsets.US_ASCII).trim();
    }

    private static double decodeVolume(byte[] b4, int decimals) {
        if (b4 == null || b4.length < 4) return 0;
        int v = ((b4[0] & 0xFF) << 24)
              | ((b4[1] & 0xFF) << 16)
              | ((b4[2] & 0xFF) << 8)
              |  (b4[3] & 0xFF);
        int scale = (decimals == 1) ? 10 : (decimals == 2 ? 1 : 100);
        return v / (double) scale;
    }

    private static byte[] encodePreset(double litres, int decimals) {
        int scale = (decimals == 1) ? 10 : (decimals == 2 ? 1 : 100);
        int v = (int) Math.round(litres * scale);
        return new byte[] {
                (byte)((v >> 24) & 0xFF),
                (byte)((v >> 16) & 0xFF),
                (byte)((v >>  8) & 0xFF),
                (byte)( v        & 0xFF)
        };
    }

    public void shutdown() {
        if (liveTask != null) liveTask.cancel(true);
        setState(State.IDLE);
    }
}
