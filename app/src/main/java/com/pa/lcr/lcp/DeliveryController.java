
package com.pa.lcr.lcp;

import java.util.concurrent.*;

import com.pa.lcr.lcp.lifecycle.Cmd0Usage;
import com.pa.lcr.lcp.lifecycle.DeliveryLifecycleController;
import com.pa.lcr.lcp.lifecycle.LcpDeliveryState;
import com.pa.lcr.lcp.util.AndroidLifecycleLogger;

public class DeliveryController {

    // ===================== LCR FIELDS =====================
    private static final int FIELD_DECIMALS        = 39;
    private static final int FIELD_PRESET_NET      = 6;
    private static final int FIELD_GROSS_TOTAL     = 44;
    private static final int FIELD_NET_TOTAL       = 45;
    private static final int FIELD_PRESETS_ALLOWED = 85;

    // ===================== BACKEND =====================
    private final LcpLink link;
    private final DeliveryEvents events;
    private final ExecutorService exec;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();

    private ScheduledFuture<?> liveTask;

    // ===================== STATE =====================
    public enum State {
        IDLE,
        PRESTART,
        STARTING,
        RUNNING,
        FINISHING,
        ENDED,
        ERROR
    }

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
        public double netL;
        public double grossL;
        public double netDelta;
        public double grossDelta;
        public boolean flowActive;
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
    // START DELIVERY (ALIGNÉ PYTHON)
    // ======================================================
    public void startOpenMode(int product, double presetNetLitres, int timeoutMs, int pollMs) {
        exec.execute(() -> {
            try {
                presetNetL = presetNetLitres;
                setState(State.PRESTART);

                // -------- PRE-START --------
                log("[PRE] MachineStatus");
                LcpLink.MachineStatusEx ms = link.opMachineStatusEx();

                decimals = decodeU8(link.opGetField(FIELD_DECIMALS));
                log("[PRE] Decimals(#39)=" + decimals);

                int presetsAllowed = decodeU8(link.opGetField(FIELD_PRESETS_ALLOWED));
                log("[PRE] PresetsAllowed(#85)=" + presetsAllowed);

                log("[PRE] Select product=" + product);
                link.opSelectProduct(product);

                log("[PRE] SET net preset (#6) = " + presetNetL);
                link.opSetField(
                        FIELD_PRESET_NET,
                        encodePreset(presetNetL, decimals)
                );

                // -------- START --------
                setState(State.STARTING);
                log("[START] RUN 0x00");
                if (!lifecycle.allowCmd0(Cmd0Usage.START)) {
                    throw new IllegalStateException("RUN not allowed");
                }

                link.opIssueCommand(0x00);
                waitForActive(timeoutMs);

                // -------- BASELINE --------
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

    // ======================================================
    // LIVE LOOP + GUARD
    // ======================================================
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
                p.netL = net;
                p.grossL = gross;
                p.netDelta = net - startNet;
                p.grossDelta = gross - startGross;
                p.flowActive = flow;
                p.deliveryState =
                        active
                                ? (flow ? LcpDeliveryState.ACTIVE_FLOWING
                                        : LcpDeliveryState.ACTIVE_PAUSED)
                                : LcpDeliveryState.IDLE;

                if (events != null) events.onProgress(p);

                // -------- GUARD --------
                if (p.netDelta >= presetNetL) {
                    log("[GUARD] Net target reached → END");
                    finishDelivery();
                }

            } catch (Exception e) {
                if (events != null) events.onError("liveLoop", e);
            }
        }, 0, pollMs, TimeUnit.MILLISECONDS);
    }

    // ======================================================
    // FINISH SEQUENCE (ALIGNÉ PYTHON)
    // ======================================================
    private void finishDelivery() {
        if (state != State.RUNNING) return;

        exec.execute(() -> {
            try {
                setState(State.FINISHING);

                if (liveTask != null) {
                    liveTask.cancel(false);
                    liveTask = null;
                }

                log("[FIN] END 0x02");
                link.opIssueCommand(0x02);

                waitForEndRequest(20_000);

                LcpLink.MachineStatusEx ms = link.opMachineStatusEx();
                boolean ticketPending =
                        (ms.delCode & LcpLink.LCRSc_DEL_TICKET_PENDING) != 0;

                if (ticketPending) {
                    log("[FIN] Ticket pending → PRINT 0x06");
                    link.opIssueCommand(0x06);
                }

                setState(State.ENDED);
                log("[FIN] Delivery completed");

            } catch (Exception e) {
                setState(State.ERROR);
                if (events != null) events.onError("finishDelivery", e);
            }
        });
    }

    // ======================================================
    // WAITS
    // ======================================================
    private void waitForActive(int timeoutMs) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            int[] dsdc = link.opDeliveryStatus();
            if ((dsdc[1] & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0) {
                log("[POLL] ACTIVE confirmed");
                return;
            }
            Thread.sleep(200);
        }
        throw new TimeoutException("ACTIVE not confirmed");
    }

    private void waitForEndRequest(int timeoutMs) throws Exception {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            int[] dsdc = link.opDeliveryStatus();
            if ((dsdc[0] & 0x0400) != 0) { // END_REQUEST
                log("[POLL] END_REQUEST confirmed");
                return;
            }
            Thread.sleep(200);
        }
        throw new TimeoutException("END_REQUEST not confirmed");
    }

    // ======================================================
    // IO UTILS
    // ======================================================
    private double readNet() throws Exception {
        return decodeVolume(link.opGetField(FIELD_NET_TOTAL), decimals);
    }

    private double readGross() throws Exception {
        return decodeVolume(link.opGetField(FIELD_GROSS_TOTAL), decimals);
    }

    private static int decodeU8(byte[] b) {
        return (b != null && b.length > 0) ? (b[0] & 0xFF) : 0;
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

    // ======================================================
    // SHUTDOWN (USB DETACHED)
    // ======================================================
    public void shutdown() {
        if (liveTask != null) {
            liveTask.cancel(true);
            liveTask = null;
        }
        setState(State.IDLE);
        log("[CTRL] shutdown");
    }
}
