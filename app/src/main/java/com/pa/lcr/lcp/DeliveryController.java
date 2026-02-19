
package com.pa.lcr.lcp;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import com.pa.lcr.lcp.lifecycle.Cmd0Usage;
import com.pa.lcr.lcp.lifecycle.DeliveryLifecycleController;
import com.pa.lcr.lcp.lifecycle.LcpDeliveryState;
import com.pa.lcr.lcp.util.AndroidLifecycleLogger;

public class DeliveryController {

    // ===================== SDK FIELDS =====================
    private static final int FIELD_PRODUCT_NUMBER = 0; // ProductNumber
    private static final int FIELD_PRODUCT_CODE   = 1; // ProductCode
    private static final int FIELD_DECIMALS        = 39;
    private static final int FIELD_PRESET_NET      = 6;
    private static final int FIELD_GROSS_TOTAL     = 44;
    private static final int FIELD_NET_TOTAL       = 45;

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

    // ===================== LIFECYCLE =====================
    private final DeliveryLifecycleController lifecycle =
            new DeliveryLifecycleController(new AndroidLifecycleLogger());

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
        public boolean flowActive;
        public LcpDeliveryState deliveryState;
    }

    // ===================== PRODUIT (MÉTIER) =====================
    public static final class ProductInfo {
        public final int number;
        public final String code;

        public ProductInfo(int number, String code) {
            this.number = number;
            this.code = code;
        }

        @Override
        public String toString() {
            return number + " - " + code;
        }
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
    // ✅ PRODUIT ACTIF (SDK STRICT)
    // ======================================================
    public List<ProductInfo> getActiveProducts() throws Exception {
        List<ProductInfo> products = new ArrayList<>();

        int productNumber = decodeU8(link.opGetField(FIELD_PRODUCT_NUMBER));
        String productCode = decodeAscii(link.opGetField(FIELD_PRODUCT_CODE));

        if (productNumber > 0 && productCode != null && !productCode.isEmpty()) {
            products.add(new ProductInfo(productNumber, productCode));
            log("[PROD] Actif: " + productNumber + " - " + productCode);
        } else {
            log("[PROD] Aucun produit actif détecté");
        }

        return products;
    }

    // ======================================================
    // START + LIVE + FINISH (inchangé ici)
    // ======================================================
    public void startOpenMode(int product, double presetNetLitres, int timeoutMs, int pollMs) {
        exec.execute(() -> {
            try {
                presetNetL = presetNetLitres;
                setState(State.PRESTART);

                decimals = decodeU8(link.opGetField(FIELD_DECIMALS));
                link.opSetField(FIELD_PRESET_NET, encodePreset(presetNetL, decimals));

                setState(State.STARTING);
                if (!lifecycle.allowCmd0(Cmd0Usage.START)) {
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
                int[] dsdc = link.opDeliveryStatus();
                boolean active = (dsdc[1] & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;
                boolean flow   = (dsdc[1] & LcpLink.LCRSc_FLOW_ACTIVE) != 0;

                double net   = readNet();
                double gross = readGross();

                DeliveryProgress p = new DeliveryProgress();
                p.netDelta   = net - startNet;
                p.grossDelta = gross - startGross;
                p.flowActive = flow;
                p.deliveryState =
                        active
                                ? (flow ? LcpDeliveryState.ACTIVE_FLOWING
                                        : LcpDeliveryState.ACTIVE_PAUSED)
                                : LcpDeliveryState.IDLE;

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

    // ===================== IO UTILS =====================
    private double readNet() throws Exception {
        return decodeVolume(link.opGetField(FIELD_NET_TOTAL), decimals);
    }

    private double readGross() throws Exception {
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
