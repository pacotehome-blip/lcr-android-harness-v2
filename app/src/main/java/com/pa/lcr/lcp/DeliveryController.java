
package com.pa.lcr.lcp;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * DeliveryController — Orchestration "SDK" d'une livraison LCR-II
 * - START (RUN) -> WAIT_FOR_FLOW (0x23 only + anti-rebond + filet #44)
 * - LIVE loop (états + compteurs) avec guard preset (END #2)
 * - END gracieux (clear FLOW/DEL)
 *
 * Threading :
 * - Toutes les opérations I/O LCP sont sérialisées via un SingleThreadExecutor interne (ou fourni).
 * - Les callbacks DeliveryEvents sont appelés depuis ce même thread (laisser l'UI remonter sur main thread si désiré).
 */
public final class DeliveryController {

    public enum State { IDLE, STARTING, WAIT_FOR_FLOW, FLOW_ACTIVE, FINALIZING, ENDED, ERROR }

    private final LcpLink link;
    private final DeliveryEvents events;
    private final Executor exec;

    private volatile State state = State.IDLE;
    private volatile boolean liveRunning = false;

    // Décimales volumétriques (#39) => 0:2, 1:1, 2:0, 3:3
    private static int digitsFromIndex(int idx) {
        switch (idx) {
            case 0:  return 2;
            case 1:  return 1;
            case 2:  return 0;
            case 3:  return 3;
            default: return 1;
        }
    }

    public DeliveryController(LcpLink link, DeliveryEvents events, Executor executor) {
        this.link   = link;
        this.events = (events != null ? events : new DeliveryEvents(){});
        this.exec   = (executor != null ? executor : Executors.newSingleThreadExecutor());
    }

    public State getState() { return state; }
    private void setState(State s){ state = s; try { events.onStateChanged(s); } catch(Exception ignored){} }
    private static void sleep(int ms){ try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }

    /* ================================================================
       Recipes haut niveau
       ================================================================ */

    /** Mode ouvert (preset=0) : set product -> clear presets -> RUN 0x00 -> WAIT_FLOW */
    public void startOpenMode(int productId, long waitFlowTimeoutMs, long pollMs) {
        exec.execute(() -> {
            try {
                setState(State.STARTING);
                setProduct(productId);
                clearPresets();
                // RUN 0x00 + attente FLOW (0x23 only)
                link.startDeliveryAndWaitFlow(0x00, waitFlowTimeoutMs, pollMs, true, true);
                setState(State.FLOW_ACTIVE);
                events.onFlowStarted();
            } catch (Exception e) {
                fail("startOpenMode: " + e.getMessage(), e);
            }
        });
    }

    /** Mode preset NET : set product -> write #6 -> #5=0 -> RUN 0x00 -> WAIT_FLOW */
    public void startPresetNet(int productId, double litres, long waitFlowTimeoutMs, long pollMs) {
        exec.execute(() -> {
            try {
                setState(State.STARTING);
                int digits = readDigits();
                int p6 = (int)Math.round(litres * Math.pow(10, digits));
                setProduct(productId);
                writeNetPreset(p6);
                // RUN 0x00 + attente FLOW (0x23 only)
                link.startDeliveryAndWaitFlow(0x00, waitFlowTimeoutMs, pollMs, true, true);
                setState(State.FLOW_ACTIVE);
                events.onFlowStarted();
            } catch (Exception e) {
                fail("startPresetNet: " + e.getMessage(), e);
            }
        });
    }

    /** WAIT_FLOW seul (si RUN a été envoyé ailleurs) */
    public void waitForFlowOnly(long timeoutMs, long pollMs) {
        exec.execute(() -> {
            try {
                setState(State.WAIT_FOR_FLOW);
                link.waitForFlowOnly(timeoutMs, pollMs, true, true);
                setState(State.FLOW_ACTIVE);
                events.onFlowStarted();
            } catch (Exception e) {
                fail("waitForFlowOnly: " + e.getMessage(), e);
            }
        });
    }

    /** END (#2) puis attendre clear FLOW/DEL */
    public void endGracefully(long timeoutMs, long pollMs) {
        exec.execute(() -> {
            try {
                setState(State.FINALIZING);
                link.opIssueCommand(0x02); // END
                long tEnd = System.currentTimeMillis() + timeoutMs;
                while (System.currentTimeMillis() < tEnd) {
                    int[] ms = link.opMachineStatusFull();
                    int dc = ms[2];
                    boolean flow   = (dc & LcpLink.LCRSc_FLOW_ACTIVE) != 0;
                    boolean active = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;
                    if (!flow && !active) { setState(State.ENDED); return; }
                    sleep((int)Math.max(100, pollMs));
                }
                setState(State.ENDED); // on sort quand même (à l'appelant de vérifier)
            } catch (Exception e) {
                fail("endGracefully: " + e.getMessage(), e);
            }
        });
    }

    /* ================================================================
       LIVE loop (états + compteurs) avec guard optionnel
       ================================================================ */
    public void runLiveLoop(long pollMs,
                            boolean guardEnabled,
                            double guardMarginLitres) {
        exec.execute(() -> {
            liveRunning = true;
            try {
                int digits = readDigits();
                double scale = Math.pow(10, digits);

                // Lire presets pour savoir si NET (#6) actif
                int p5 = readI32OrZero(5);
                int p6 = readI32OrZero(6);
                final boolean guardUseNet = (p6 > 0);
                final Double targetLitres = (p6 > 0) ? (p6 / scale) : (p5 > 0 ? (p5 / scale) : null);

                Integer g0 = readI32Nullable(44);
                Integer n0 = readI32Nullable(45);
                if (g0 == null) g0 = 0;
                if (n0 == null) n0 = 0;

                boolean guardFired = false;

                while (liveRunning) {
                    int[] ms = link.opMachineStatusFull();
                    int ds = ms[1], dc = ms[2];
                    boolean flow   = (dc & LcpLink.LCRSc_FLOW_ACTIVE) != 0;
                    boolean active = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;

                    int g = readI32OrFallback(44, g0);
                    int n = readI32OrFallback(45, n0);

                    double gL = g / scale, nL = n / scale;
                    try { events.onLiveSample(ds, dc, gL, nL); } catch(Exception ignored){}

                    if (!flow) {
                        try { events.onFlowStopped(); } catch(Exception ignored){}
                        break;
                    }

                    if (guardEnabled && targetLitres != null && !guardFired) {
                        double deliveredL = guardUseNet ? ((n - n0) / scale) : ((g - g0) / scale);
                        if (deliveredL >= (targetLitres + guardMarginLitres)) {
                            try { events.onGuardReached(); } catch(Exception ignored){}
                            // END (#2) + petite fenêtre "queued"
                            try { link.opIssueCommand(0x02); } catch(Exception ignored){}
                            guardFired = true;
                            // Laisse la vanne/gun fermer ; la boucle sortira quand flow=false
                        }
                    }

                    sleep((int)Math.max(100, pollMs));
                }
            } catch (Exception e) {
                fail("runLiveLoop: " + e.getMessage(), e);
            } finally {
                liveRunning = false;
            }
        });
    }

    public void stopLiveLoop() { liveRunning = false; }

    /* ================================================================
       Helpers privés : #39, #0,#5,#6, #44,#45
       ================================================================ */
    private int readDigits() {
        try {
            byte[] v = link.opGetField(39);
            int idx = (v != null && v.length > 0) ? (v[0] & 0xFF) : 1;
            return digitsFromIndex(idx);
        } catch (Exception e) { return 1; }
    }

    private void setProduct(int productId) throws IOException {
        if (productId < 1 || productId > 16) throw new IllegalArgumentException("ProductNumber doit être 1..16");
        // champ #0 = 0-based
        link.opSetField(0, new byte[]{ (byte)((productId - 1) & 0xFF) });
    }

    private void clearPresets() throws IOException {
        writeGrossPreset(0);
        writeNetPreset(0);
    }

    private void writeGrossPreset(int raw) throws IOException { link.opSetField(5, i32be(raw)); }
    private void writeNetPreset  (int raw) throws IOException {
        link.opSetField(6, i32be(raw));
        link.opSetField(5, i32be(0)); // sécurité : seul #6 actif en NET
    }

    private static byte[] i32be(int v) {
        return new byte[]{
            (byte)((v >> 24) & 0xFF),
            (byte)((v >> 16) & 0xFF),
            (byte)((v >> 8)  & 0xFF),
            (byte)(v & 0xFF)
        };
    }

    private int readI32OrZero(int field) {
        try {
            byte[] d = link.opGetField(field);
            return ((d[0] & 0xFF) << 24) | ((d[1] & 0xFF) << 16) | ((d[2] & 0xFF) << 8) | (d[3] & 0xFF);
        } catch (Exception e) { return 0; }
    }
    private Integer readI32Nullable(int field) {
        try {
            byte[] d = link.opGetField(field);
            return ((d[0] & 0xFF) << 24) | ((d[1] & 0xFF) << 16) | ((d[2] & 0xFF) << 8) | (d[3] & 0xFF);
        } catch (Exception e) { return null; }
    }
    private int readI32OrFallback(int field, int fb) {
        try {
            byte[] d = link.opGetField(field);
            return ((d[0] & 0xFF) << 24) | ((d[1] & 0xFF) << 16) | ((d[2] & 0xFF) << 8) | (d[3] & 0xFF);
        } catch (Exception e) { return fb; }
    }

    private void fail(String msg, Throwable t) {
        setState(State.ERROR);
        try { events.onError(msg, t); } catch(Exception ignored){}
    }

    /* ================================================================
       Callbacks SDK
       ================================================================ */
    public interface DeliveryEvents {
        default void onStateChanged(State s) {}
        default void onFlowStarted() {}
        default void onFlowStopped() {}
        default void onLiveSample(int ds, int dc, double grossL, double netL) {}
        default void onGuardReached() {}
        default void onTicketPending() {}
        default void onError(String message, Throwable cause) {}
    }
}
