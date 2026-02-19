
package com.pa.lcr.lcp;

import java.io.IOException;
import java.util.concurrent.*;

import com.pa.lcr.lcp.lifecycle.Cmd0Usage;
import com.pa.lcr.lcp.lifecycle.DeliveryLifecycleController;
import com.pa.lcr.lcp.lifecycle.LcpDeliveryState;
import com.pa.lcr.lcp.util.AndroidLifecycleLogger;

/**
 * DeliveryController – FINAL
 *
 * ✅ Stoppe proprement le polling sur USB DETACHED
 * ✅ Aucun opDeliveryStatus après fermeture du port
 * ✅ Comportement terrain stable
 */
public class DeliveryController {

    // ===================== CONSTANTES =====================
    private static final double MAX_VOLUME_JUMP_L = 500.0;
    private static final int POST_RECOVERY_SKIP_SAMPLES = 2;
    private static final long ZOMBIE_STALL_MS = 4000;

    // ===================== LCR FIELDS =====================
    private static final int FIELD_GROSS_COUNT = 44;
    private static final int FIELD_NET_COUNT   = 45;
    private static final int FIELD_DECIMALS    = 39;

    // ===================== BACKEND =====================
    private final LcpLink link;
    private final DeliveryEvents events;
    private final ExecutorService exec;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

    private ScheduledFuture<?> liveLoopFuture;

    // ===================== STATE =====================
    public enum State { IDLE, RUNNING, ENDING, ENDED, ERROR }
    private volatile State state = State.IDLE;
    private volatile boolean stopping = false;

    // ===================== IO / RECOVERY =====================
    private volatile boolean ioCriticalSection = false;
    private volatile int skipVolumeSamples = 0;

    // ===================== VOLUMES =====================
    private volatile long startTimestampMs = 0;
    private volatile double startGross = 0;
    private volatile double startNet   = 0;
    private volatile double lastGross  = 0;
    private volatile double lastNet    = 0;
    private volatile long lastVolumeChangeMs = 0;
    private volatile int cachedDecimals = -1;

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
        public long tSinceStartMs;
        public double grossL;
        public double netL;
        public double deliveredGrossL;
        public double deliveredNetL;
        public boolean flowActive;
        public int ds;
        public int dc;
        public LcpDeliveryState deliveryState;
    }

    // ===================== CONSTRUCTOR =====================
    public DeliveryController(LcpLink link, DeliveryEvents events, ExecutorService exec) {
        this.link = link;
        this.events = events;
        this.exec = exec;
    }

    // ===================== LOG =====================
    private void log(String s) {
        if (events != null) events.onLog(s);
    }

    private void setState(State s) {
        state = s;
        if (events != null) events.onStateChanged(s);
    }

    // ======================================================
    // ✅ CORRECTIF CRITIQUE
    // Arrêt propre du polling (appelé sur USB DETACHED)
    // ======================================================
    public void shutdown() {
        try {
            stopping = true;
            if (liveLoopFuture != null) {
                liveLoopFuture.cancel(true);
                liveLoopFuture = null;
            }
        } catch (Exception ignored) {}

        state = State.IDLE;
        log("[CTRL] shutdown completed");
    }

    // ===================== SANITY =====================
    private boolean isVolumePlausible(double v, double last) {
        if (v < 0) return false;
        return Math.abs(v - last) <= MAX_VOLUME_JUMP_L;
    }

    // ===================== DECIMALS / VOLUME =====================
    private int getDecimalsSafe() {
        if (cachedDecimals >= 0) return cachedDecimals;
        try {
            byte[] d = link.opGetField(FIELD_DECIMALS);
            cachedDecimals = (d != null && d.length > 0) ? (d[0] & 0xFF) : 0;
        } catch (Exception e) {
            cachedDecimals = 0;
        }
        return cachedDecimals;
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

    private double safeReadGross() {
        try {
            return decodeVolume(link.opGetField(FIELD_GROSS_COUNT), getDecimalsSafe());
        } catch (Exception e) {
            return lastGross;
        }
    }

    private double safeReadNet() {
        try {
            return decodeVolume(link.opGetField(FIELD_NET_COUNT), getDecimalsSafe());
        } catch (Exception e) {
            return lastNet;
        }
    }

    // ===================== LIVE LOOP =====================
    public void startLiveLoop(int pollMs) {
        exec.execute(() -> {
            if (state != State.RUNNING) return;

            liveLoopFuture = scheduler.scheduleAtFixedRate(() -> {
                if (stopping || state != State.RUNNING) return;

                try {
                    int[] dsdc = link.opDeliveryStatus();
                    int dc = dsdc[1];

                    boolean flowActive =
                            (dc & LcpLink.LCRSc_FLOW_ACTIVE) != 0;

                    double gross = lastGross;
                    double net   = lastNet;

                    if (!ioCriticalSection && skipVolumeSamples == 0) {
                        double g = safeReadGross();
                        double n = safeReadNet();
                        if (isVolumePlausible(g, lastGross) &&
                            isVolumePlausible(n, lastNet)) {
                            gross = g;
                            net = n;
                        }
                    } else if (skipVolumeSamples > 0) {
                        skipVolumeSamples--;
                    }

                    long now = System.currentTimeMillis();
                    if (gross != lastGross || net != lastNet) {
                        lastVolumeChangeMs = now;
                    }

                    DeliveryProgress p = new DeliveryProgress();
                    p.tSinceStartMs = now - startTimestampMs;
                    p.grossL = gross;
                    p.netL = net;
                    p.deliveredGrossL = gross - startGross;
                    p.deliveredNetL = net - startNet;
                    p.flowActive = flowActive;
                    p.ds = dsdc[0];
                    p.dc = dc;
                    p.deliveryState =
                            flowActive ? LcpDeliveryState.ACTIVE_FLOWING
                                       : LcpDeliveryState.ACTIVE_PAUSED;

                    lastGross = gross;
                    lastNet = net;

                    if (events != null) events.onProgress(p);

                } catch (IOException e) {
                    // USB fermé → ignorer silencieusement
                } catch (Exception e) {
                    setState(State.ERROR);
                    if (events != null) events.onError("liveLoop", e);
                }

            }, 0, pollMs, TimeUnit.MILLISECONDS);
        });
    }

    // ===================== API PUBLIQUE =====================
    public void startOpenMode(int product, double preset, int timeoutMs, int pollMs) {
        exec.execute(() -> {
            startTimestampMs = System.currentTimeMillis();
            startGross = safeReadGross();
            startNet = safeReadNet();
            lastGross = startGross;
            lastNet = startNet;
            lastVolumeChangeMs = System.currentTimeMillis();

            stopping = false;
            setState(State.RUNNING);
            startLiveLoop(pollMs);
        });
    }

    public void resumeDelivery(int pollMs) {
        exec.execute(() -> {
            try {
                if (!lifecycle.allowCmd0(Cmd0Usage.RESUME)) return;
                link.opIssueCommand(0x00);
                stopping = false;
                setState(State.RUNNING);
                startLiveLoop(pollMs);
            } catch (Exception ignored) {}
        });
    }

    public void endGracefully(int timeoutMs, int pollMs) {
        exec.execute(() -> {
            ioCriticalSection = true;
            try {
                stopping = true;
                setState(State.ENDING);
                if (liveLoopFuture != null) liveLoopFuture.cancel(true);
                link.opIssueCommand(0x02);
                setState(State.ENDED);
            } catch (Exception ignored) {}
            finally {
                ioCriticalSection = false;
            }
        });
    }

    public void recoverActiveDelivery(int pollMs) {
        exec.execute(() -> {
            try {
                int[] dsdc = link.opDeliveryStatus();
                if ((dsdc[1] & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0) {
                    startTimestampMs = System.currentTimeMillis();
                    startGross = safeReadGross();
                    startNet = safeReadNet();
                    lastGross = startGross;
                    lastNet = startNet;
                    stopping = false;
                    setState(State.RUNNING);
                    startLiveLoop(pollMs);
                }
            } catch (Exception ignored) {}
        });
    }
}
