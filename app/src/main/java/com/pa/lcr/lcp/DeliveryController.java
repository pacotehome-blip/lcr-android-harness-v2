package com.pa.lcr.lcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

import com.pa.lcr.lcp.lifecycle.Cmd0Usage;
import com.pa.lcr.lcp.lifecycle.DeliveryLifecycle;
import com.pa.lcr.lcp.lifecycle.DeliveryLifecycleController;
import com.pa.lcr.lcp.lifecycle.LcpDeliveryState;
import com.pa.lcr.lcp.util.AndroidLifecycleLogger;

/**
 * DeliveryController
 *
 * ✅ Version finale intégrée :
 *  - API publique complète (compatible MainActivity / UsbReceiver)
 *  - Détection ZOMBIE (ACTIVE_FLOWING + volumes figés)
 *  - Soft recovery (Cmd#1 Pause)
 *  - Sanity-check volumes (anti NET = NetTotal)
 *  - Suspension IO pendant recovery / END / PRINT
 *  - Reprise sans reset des volumes
 */
public class DeliveryController {

    // ==========================================================
    // CONSTANTES TERRAIN (FIX)
    // ==========================================================
    private static final double MAX_VOLUME_JUMP_L = 500.0;
    private static final int POST_RECOVERY_SKIP_SAMPLES = 2;
    private static final long ZOMBIE_STALL_MS = 4000;
    private static final long ZOMBIE_COOLDOWN_MS = 15000;

    // ==========================================================
    // FIELDS LCR
    // ==========================================================
    private static final int FIELD_GROSS_COUNT = 44;
    private static final int FIELD_NET_COUNT   = 45;
    private static final int FIELD_DECIMALS    = 39;
    private static final int FIELD_NO_FLOW_TIMER = 25;
    private static final int FIELD_TICKET_NUMBER = 23;
    private static final int FIELD_TICKET_REQUIRED = 37;
    private static final int FIELD_CLEAR_SHIFT = 16;

    private static final int DC_SHIFT_TICKET_PENDING = 0x0002;

    // ==========================================================
    // BACKEND
    // ==========================================================
    private final LcpLink link;
    private final DeliveryEvents events;
    private final ExecutorService exec;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

    private ScheduledFuture<?> liveLoopFuture;

    // ==========================================================
    // STATE
    // ==========================================================
    public enum State { IDLE, PRESTART, STARTING, RUNNING, ENDING, ENDED, ERROR }
    private volatile State state = State.IDLE;

    private volatile boolean stopping = false;
    private volatile Boolean lastFlow = null;

    // ==========================================================
    // IO / RECOVERY FLAGS (FIX)
    // ==========================================================
    private volatile boolean ioCriticalSection = false;
    private volatile int skipVolumeSamples = 0;
    private volatile long lastZombieRecoveryMs = 0;
    private volatile boolean baselineLocked = false;

    // ==========================================================
    // VOLUMES
    // ==========================================================
    private volatile long startTimestampMs = 0;

    private volatile double startGross = 0;
    private volatile double startNet   = 0;

    private volatile double lastGross = 0;
    private volatile double lastNet   = 0;

    private volatile double lastStableGross = 0;
    private volatile double lastStableNet   = 0;
    private volatile long   lastVolumeChangeMs = 0;

    private volatile int cachedDecimals = -1;

    // ==========================================================
    // FLOW STOP CONFIRM
    // ==========================================================
    private static final long FLOW_STOP_CONFIRM_MIN_MS = 2000;
    private volatile long flowStopConfirmMs = 3000;

    private volatile boolean flowStopPending = false;
    private volatile boolean flowStopNotified = false;
    private volatile long flowStopSinceMs = 0;

    // ==========================================================
    // LIFECYCLE GUARD
    // ==========================================================
    private final DeliveryLifecycleController lifecycle =
            new DeliveryLifecycleController(new AndroidLifecycleLogger());

    // ==========================================================
    // EVENTS
    // ==========================================================
    public interface DeliveryEvents {
        void onStateChanged(State s);
        void onFlowStarted();
        void onFlowStopped();
        void onProgress(DeliveryProgress p);
        void onTicketNumber(int ticketNumber);
        void onTicketRequired(int mode);
        void onPrinterStatus(LcpLink.MachineStatusEx ms, boolean ticketPending);
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

    // ==========================================================
    // CONSTRUCTOR
    // ==========================================================
    public DeliveryController(LcpLink link, DeliveryEvents events, ExecutorService exec) {
        this.link = link;
        this.events = events;
        this.exec = exec;
    }

    // ==========================================================
    // LOG / STATE
    // ==========================================================
    private void log(String s) {
        if (events != null) events.onLog(s);
    }

    private void setState(State s) {
        this.state = s;
        if (events != null) events.onStateChanged(s);
    }

    private void safeOp(Runnable r, String tag) {
        try { r.run(); }
        catch (Exception e) {
            setState(State.ERROR);
            if (events != null) events.onError(tag, e);
        }
    }

    // ==========================================================
    // DELIVERY STATE (UI)
    // ==========================================================
    private LcpDeliveryState computeDeliveryStateForUi(int dc) {
        boolean ticketPending  = (dc & LcpLink.LCRSc_DEL_TICKET_PENDING) != 0;
        boolean shiftPending   = (dc & DC_SHIFT_TICKET_PENDING) != 0;
        boolean flowActive     = (dc & LcpLink.LCRSc_FLOW_ACTIVE) != 0;
        boolean deliveryActive = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;

        if (deliveryActive) {
            return flowActive ? LcpDeliveryState.ACTIVE_FLOWING
                              : LcpDeliveryState.ACTIVE_PAUSED;
        }
        if (ticketPending) return LcpDeliveryState.PENDING_TICKET;
        if (shiftPending)  return LcpDeliveryState.PENDING_SHIFT;
        return LcpDeliveryState.IDLE;
    }

    // ==========================================================
    // SANITY CHECK (ANTI GLITCH)
    // ==========================================================
    private boolean isVolumePlausible(double v, double last) {
        if (v < 0) return false;
        return Math.abs(v - last) <= MAX_VOLUME_JUMP_L;
    }

    // ==========================================================
    // DECIMALS / VOLUME
    // ==========================================================
    private int getDecimals() throws Exception {
        if (cachedDecimals >= 0) return cachedDecimals;
        byte[] d = link.opGetField(FIELD_DECIMALS);
        cachedDecimals = (d != null && d.length > 0) ? (d[0] & 0xFF) : 0;
        return cachedDecimals;
    }

    private static double decodeVolume(byte[] b4, int decimals) {
        if (b4 == null || b4.length < 4) return 0;
        int v = ((b4[0] & 0xFF) << 24)
              | ((b4[1] & 0xFF) << 16)
              | ((b4[2] & 0xFF) << 8)
              | (b4[3] & 0xFF);
        int scale = (decimals == 1) ? 10 : (decimals == 2 ? 1 : 100);
        return v / (double) scale;
    }

    private double readGrossLitres() throws Exception {
        return decodeVolume(link.opGetField(FIELD_GROSS_COUNT), getDecimals());
    }

    private double readNetLitres() throws Exception {
        return decodeVolume(link.opGetField(FIELD_NET_COUNT), getDecimals());
    }

    // ==========================================================
    // ZOMBIE RECOVERY
    // ==========================================================
    private void softRecoverZombie(int pollMs, int ds, int dc) {
        long now = System.currentTimeMillis();
        if (now - lastZombieRecoveryMs < ZOMBIE_COOLDOWN_MS) return;
        lastZombieRecoveryMs = now;

        if (ioCriticalSection) return;
        ioCriticalSection = true;
        baselineLocked = true;

        try {
            log("[ZOMBIE] Soft recovery → PauseDelivery");
            link.opIssueCommand(0x01); // Cmd#1
            lifecycle.onPauseDetected();
            skipVolumeSamples = POST_RECOVERY_SKIP_SAMPLES;
        } catch (Exception e) {
            log("[ZOMBIE] Recovery failed: " + e.getMessage());
        } finally {
            ioCriticalSection = false;
        }
    }

    // ==========================================================
    // LIVE LOOP
    // ==========================================================
    public void startLiveLoop(int pollMs) {
        exec.execute(() -> safeOp(() -> {

            if (state != State.RUNNING) return;
            if (liveLoopFuture != null && !liveLoopFuture.isCancelled()) return;

            liveLoopFuture = scheduler.scheduleAtFixedRate(() -> {
                try {
                    if (stopping || state != State.RUNNING) return;

                    int[] dsdc = link.opDeliveryStatus();
                    int ds = dsdc[0];
                    int dc = dsdc[1];

                    boolean deliveryActive =
                            (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;
                    boolean flowActive =
                            (dc & LcpLink.LCRSc_FLOW_ACTIVE) != 0;

                    if (!deliveryActive) return;

                    double gross = lastGross;
                    double net   = lastNet;

                    if (!ioCriticalSection && skipVolumeSamples == 0) {
                        double g = readGrossLitres();
                        double n = readNetLitres();

                        if (isVolumePlausible(g, lastGross) &&
                            isVolumePlausible(n, lastNet)) {
                            gross = g;
                            net = n;
                        } else {
                            log("[SANITY] Reject sample g=" + g + " n=" + n);
                        }
                    } else if (skipVolumeSamples > 0) {
                        skipVolumeSamples--;
                    }

                    long now = System.currentTimeMillis();
                    boolean progressed =
                            Math.abs(gross - lastStableGross) > 1e-9 ||
                            Math.abs(net   - lastStableNet)   > 1e-9;

                    if (progressed) {
                        lastStableGross = gross;
                        lastStableNet   = net;
                        lastVolumeChangeMs = now;
                    }

                    LcpDeliveryState uiState =
                            computeDeliveryStateForUi(dc);

                    if (uiState == LcpDeliveryState.ACTIVE_FLOWING &&
                        now - lastVolumeChangeMs >= ZOMBIE_STALL_MS) {
                        softRecoverZombie(pollMs, ds, dc);
                    }

                    DeliveryProgress p = new DeliveryProgress();
                    p.tSinceStartMs = now - startTimestampMs;
                    p.grossL = gross;
                    p.netL = net;
                    p.deliveredGrossL = gross - startGross;
                    p.deliveredNetL   = net - startNet;
                    p.flowActive = flowActive;
                    p.ds = ds;
                    p.dc = dc;
                    p.deliveryState = uiState;

                    lastGross = gross;
                    lastNet   = net;

                    if (events != null) events.onProgress(p);

                    if (lastFlow == null) lastFlow = flowActive;

                    if (flowActive && !lastFlow && events != null)
                        events.onFlowStarted();

                    if (!flowActive && lastFlow && events != null)
                        events.onFlowStopped();

                    lastFlow = flowActive;

                } catch (Exception e) {
                    setState(State.ERROR);
                    if (events != null) events.onError("liveLoop", e);
                }
            }, 0, pollMs, TimeUnit.MILLISECONDS);

        }, "startLiveLoop"));
    }

    // ==========================================================
    // ================= API PUBLIQUE ===========================
    // ==========================================================

    public void startOpenMode(int product, double preset, int timeoutMs, int pollMs) {
        exec.execute(() -> safeOp(() -> {
            startTimestampMs = System.currentTimeMillis();
            setState(State.STARTING);
            startGross = readGrossLitres();
            startNet   = readNetLitres();
            lastGross = startGross;
            lastNet   = startNet;
            lastStableGross = lastGross;
            lastStableNet   = lastNet;
            lastVolumeChangeMs = System.currentTimeMillis();
            baselineLocked = false;
            stopping = false;
            setState(State.RUNNING);
            startLiveLoop(pollMs);
        }, "startOpenMode"));
    }

    public void startOpenMode(int product, int timeoutMs, int pollMs) {
        startOpenMode(product, 0.0, timeoutMs, pollMs);
    }

    public void resumeDelivery(int pollMs) {
        exec.execute(() -> safeOp(() -> {
            if (!lifecycle.allowCmd0(Cmd0Usage.RESUME)) return;
            if (!lifecycle.allowResume()) return;

            link.opIssueCommand(0x00); // Cmd#0

            if (!baselineLocked) {
                startGross = readGrossLitres();
                startNet   = readNetLitres();
            }

            lastGross = startGross;
            lastNet   = startNet;
            lastStableGross = lastGross;
            lastStableNet   = lastNet;
            lastVolumeChangeMs = System.currentTimeMillis();

            stopping = false;
            setState(State.RUNNING);
            startLiveLoop(pollMs);
        }, "resumeDelivery"));
    }

    public void endGracefully(int timeoutMs, int pollMs) {
        exec.execute(() -> safeOp(() -> {
            ioCriticalSection = true;
            try {
                lifecycle.allowEnd();
                setState(State.ENDING);
                stopping = true;
                if (liveLoopFuture != null) liveLoopFuture.cancel(true);
                link.opIssueCommand(0x02); // END
                baselineLocked = false;
                setState(State.ENDED);
            } finally {
                ioCriticalSection = false;
                skipVolumeSamples = POST_RECOVERY_SKIP_SAMPLES;
            }
        }, "endGracefully"));
    }

    public void pingStatus(int pollMs) {
        exec.execute(() -> safeOp(() -> {
            int[] dsdc = link.opDeliveryStatus();
            log("PING ds=" + dsdc[0] + " dc=" + dsdc[1]);
        }, "pingStatus"));
    }

    public void refreshTicketInfo(int pollMs) {
        exec.execute(() -> safeOp(() -> {
            int tr = decodeU8(link.opGetField(FIELD_TICKET_REQUIRED));
            int tn = decodeS32(link.opGetField(FIELD_TICKET_NUMBER));
            if (events != null) {
                events.onTicketRequired(tr);
                events.onTicketNumber(tn);
            }
        }, "refreshTicketInfo"));
    }

    public void refreshPrinterStatus(int pollMs) {
        exec.execute(() -> safeOp(() -> {
            LcpLink.MachineStatusEx ms = link.opMachineStatusEx();
            boolean pending = (ms.delCode & LcpLink.LCRSc_DEL_TICKET_PENDING) != 0;
            if (events != null) events.onPrinterStatus(ms, pending);
        }, "refreshPrinterStatus"));
    }

    public void setTicketRequired(int mode, int pollMs) {
        exec.execute(() -> safeOp(() -> {
            link.opSetField(FIELD_TICKET_REQUIRED, new byte[]{ (byte) mode });
        }, "setTicketRequired"));
    }

    public void ensureDefaultTicketRequiredIs1(int pollMs) {
        exec.execute(() -> safeOp(() -> {
            int tr = decodeU8(link.opGetField(FIELD_TICKET_REQUIRED));
            if (tr != 1) {
                link.opSetField(FIELD_TICKET_REQUIRED, new byte[]{ 1 });
            }
        }, "ensureDefaultTicketRequiredIs1"));
    }

    public void clearShiftNow(int pollMs) {
        exec.execute(() -> safeOp(() -> {
            link.opSetField(FIELD_CLEAR_SHIFT, new byte[]{ 0 });
        }, "clearShiftNow"));
    }

    public void printPendingTicket(int pollMs, int timeoutMs) {
        exec.execute(() -> safeOp(() -> {
            ioCriticalSection = true;
            try {
                link.opIssueCommand(0x06); // PRINT
            } finally {
                ioCriticalSection = false;
                skipVolumeSamples = POST_RECOVERY_SKIP_SAMPLES;
            }
        }, "printPendingTicket"));
    }

    public void recoverActiveDelivery(int pollMs) {
        exec.execute(() -> safeOp(() -> {
            int[] dsdc = link.opDeliveryStatus();
            int dc = dsdc[1];
            if ((dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0) {
                baselineLocked = true;
                startTimestampMs = System.currentTimeMillis();
                startGross = readGrossLitres();
                startNet   = readNetLitres();
                lastGross = startGross;
                lastNet   = startNet;
                lastStableGross = lastGross;
                lastStableNet   = lastNet;
                lastVolumeChangeMs = System.currentTimeMillis();
                setState(State.RUNNING);
                startLiveLoop(pollMs);
            }
        }, "recoverActiveDelivery"));
    }

    // ==========================================================
    // HELPERS
    // ==========================================================
    private static int decodeU8(byte[] b) {
        return (b != null && b.length > 0) ? (b[0] & 0xFF) : 0;
    }

    private static int decodeS32(byte[] b) {
        if (b == null || b.length < 4) return 0;
        return ((b[0] & 0xFF) << 24)
             | ((b[1] & 0xFF) << 16)
             | ((b[2] & 0xFF) << 8)
             |  (b[3] & 0xFF);
    }
}
