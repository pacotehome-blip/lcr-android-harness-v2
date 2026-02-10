
package com.pa.lcr.lcp;

import java.util.concurrent.*;
import java.util.Arrays;
import java.io.IOException;

public class DeliveryController {

    /* ============================= SECTION 1 ============================= */
    private ScheduledFuture<?> liveLoopFuture;

    private volatile boolean guardEnabled = false;
    private volatile double guardTargetLitres = 0;

    private volatile double lastGross = 0;
    private volatile double lastNet = 0;
    private volatile long lastPollAt = 0;

    private final LcpLink link;
    private final DeliveryEvents events;
    private final ExecutorService exec;

    private volatile boolean stopping = false;
    private volatile boolean pollWindowOpen = false;
    private volatile int presetProduct = 1;
    private volatile double presetLitres = 0;

    private volatile long startTimestampMs = 0;
    private volatile double startGross = 0;
    private volatile double startNet = 0;

    public enum State { IDLE, PRESTART, STARTING, RUNNING, ENDING, ENDED, ERROR }
    private volatile State state = State.IDLE;

    public interface DeliveryEvents {
        void onStateChanged(State s);
        void onFlowStarted();
        void onFlowStopped();
        void onLiveSample(int ds, int dc, double grossL, double netL);
        void onProgress(DeliveryProgress p);
        void onGuardReached();
        void onError(String msg, Throwable t);
        void onLog(String line);
    }

    public static final class DeliveryProgress {
        public long tSinceStartMs;
        public double grossL;
        public double netL;
        public double dGrossL;
        public double dNetL;
        public boolean flowActive;
        public boolean stalled;
        public int ds;
        public int dc;
    }

    public DeliveryController(LcpLink link, DeliveryEvents cb, ExecutorService svc) {
        this.link = link;
        this.events = cb;
        this.exec = svc;
    }

    private void log(String s){ if(events!=null) events.onLog(s); }

    private void setState(State s) {
        this.state = s;
        if (events != null) events.onStateChanged(s);
    }

    /* ============================= SECTION 2 ============================= */
    public void prestartSequence(int product, double presetLitres, int pollMs) throws Exception {
        log("[PRE] PythonCompat ON");
        link.setPythonCompat(true, pollMs);

        log("[PRE] Opening poll window");
        link.openPollWindow();
        pollWindowOpen = true;

        log("[PRE] Reading machine status (#23)");
        int[] st = link.opMachineStatusFull();
        log(String.format("[PRE] MachineStatus dev=0x%04X ds=0x%04X dc=0x%04X", st[0], st[1], st[2]));

        log("[PRE] Setting product (#06) = " + product);
        link.opSetField(0x06, new byte[]{ (byte)product });
        this.presetProduct = product;

        log("[PRE] Setting NET preset (#06) value=" + presetLitres);
        int tenths = (int)(presetLitres*10);
        byte[] presetBytes = new byte[]{
                (byte)((tenths>>8)&0xFF),
                (byte)(tenths&0xFF)
        };
        link.opSetField(0x06, presetBytes);
        this.presetLitres = presetLitres;

        log("[PRE] Setting mode auto/net (#85)");
        link.opSetField(0x85, new byte[]{ 0x03 });

        log("[PRE] Completed PRE-START");
    }

    /* ============================= SECTION 3 ============================= */
    public void startDeliverySequence(int pollMs) throws Exception {
        log("[START] RUN 0x00");
        link.opIssueCommand(0x00);
        setState(State.STARTING);

        log("[START] First poll after RUN");
        int[] dsdc = link.opDeliveryStatus();
        int ds = dsdc[0];
        int dc = dsdc[1];

        boolean flow = (ds & LcpLink.LCRSc_FLOW_ACTIVE) != 0;
        boolean active = (ds & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;

        if (!active) throw new Exception("Delivery not active after RUN");

        startTimestampMs = System.currentTimeMillis();
        lastPollAt = startTimestampMs;

        lastGross = decodeGrossLitres(ds, dc);
        lastNet = decodeNetLitres(ds, dc);
        startGross = lastGross;
        startNet = lastNet;

        setState(State.RUNNING);
        log("[START] Delivery ACTIVE");
    }

    /* ============================= SECTION 4 ============================= */
    public void startLiveLoop(int pollMs) {
        log("[LIVE] Starting live loop");

        liveLoopFuture = Executors.newSingleThreadScheduledExecutor()
                .scheduleAtFixedRate(() -> {
                    try {
                        if (stopping || state != State.RUNNING) return;

                        int[] dsdc = link.opDeliveryStatus();
                        int ds = dsdc[0];
                        int dc = dsdc[1];

                        boolean flow = (ds & LcpLink.LCRSc_FLOW_ACTIVE) != 0;
                        boolean active = (ds & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;

                        if (!active) { stopping = true; return; }

                        double gross = decodeGrossLitres(ds, dc);
                        double net   = decodeNetLitres(ds, dc);

                        DeliveryProgress p = new DeliveryProgress();
                        p.tSinceStartMs = System.currentTimeMillis() - startTimestampMs;
                        p.grossL = gross;
                        p.netL = net;
                        p.dGrossL = gross - lastGross;
                        p.dNetL = net - lastNet;
                        p.flowActive = flow;
                        p.stalled = !flow;
                        p.ds = ds;
                        p.dc = dc;

                        lastGross = gross;
                        lastNet = net;

                        if (events != null) events.onProgress(p);
                        if (events != null) events.onLiveSample(ds, dc, gross, net);
                        if (flow && events != null) events.onFlowStarted();
                        if (!flow && events != null) events.onFlowStopped();

                        if (guardEnabled && net >= guardTargetLitres) {
                            log("[GUARD] target reached");
                            if (events != null) events.onGuardReached();
                            stopping = true;
                        }

                    } catch (Exception e) {
                        setState(State.ERROR);
                        if(events!=null) events.onError("liveLoop", e);
                    }

                }, 0, pollMs, TimeUnit.MILLISECONDS);
    }

    /* ============================= SECTION 5 ============================= */
    public void endDeliverySequence(int timeoutMs, int pollMs) throws Exception {
        log("[END] Issuing END (0x02)");
        setState(State.ENDING);
        stopping = true;

        link.opIssueCommand(0x02);

        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            int[] dsdc = link.opDeliveryStatus();
            int ds = dsdc[0];
            boolean active = (ds & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;
            if (!active) break;
            Thread.sleep(pollMs);
        }

        if (pollWindowOpen) {
            link.closePollWindow();
            pollWindowOpen = false;
        }
        if (liveLoopFuture != null) {
            liveLoopFuture.cancel(true);
            liveLoopFuture = null;
        }

        setState(State.ENDED);
    }

    /* ============================= SECTION 6 ============================= */
    private double decodeGrossLitres(int ds, int dc){
        return ((dc >> 4) & 0xFFF) / 10.0;
    }
    private double decodeNetLitres(int ds, int dc){
        return (dc & 0x0F) / 10.0;
    }

    public void printTicketText(String txt, int heightDots, int timeoutMs){
        exec.execute(() -> {
            try{
                byte[] data = txt.getBytes();
                link.opSetField(LcpLink.MSG_PRINT_TEXT, data);
            } catch(Exception e){
                if (events != null) events.onError("printTicketText", e);
            }
        });
    }

    /* ============================= SECTION 7 ============================= */
    public void requestStop(String reason){
        exec.execute(() -> {
            try {
                stopping = true;
                if(liveLoopFuture != null){
                    liveLoopFuture.cancel(true);
                    liveLoopFuture = null;
                }
                try { link.cancelIO(); } catch(Exception ignored){}
                if(pollWindowOpen){
                    try{ link.closePollWindow(); } catch(Exception ignored){}
                    pollWindowOpen = false;
                }
                setState(State.ENDED);
            }catch(Exception e){
                if(events!=null) events.onError("requestStop",e);
            }
        });
    }

    private void safeOp(Runnable r, String tag){
        try{ r.run(); }
        catch(Exception e){
            setState(State.ERROR);
            if(events!=null) events.onError(tag,e);
        }
    }

    /* ============================= SECTION 8 ============================= */
    public void startOpenMode(int product, int timeoutMs, int pollMs){
        exec.execute(() -> safeOp(() -> {
            setState(State.PRESTART);
            stopping = false;

            try {
                prestartSequence(product, 0.0, pollMs);
            } catch (Exception ex) {
                if(events!=null) events.onError("prestartSequence", ex);
                return;
            }

            try {
                startDeliverySequence(pollMs);
            } catch (Exception ex) {
                if(events!=null) events.onError("startDeliverySequence", ex);
                return;
            }

            startLiveLoop(pollMs);
        }, "startOpenMode"));
    }

    public void endGracefully(int timeoutMs, int pollMs){
        exec.execute(() -> safeOp(() -> {
            stopping = true;

            try {
                endDeliverySequence(timeoutMs,pollMs);
            } catch (Exception ex) {
                if(events!=null) events.onError("endDeliverySequence", ex);
            }

        }, "endGracefully"));
    }

    /* ============================= EXTRA METHODS REQUIRED BY MainActivity ============================= */

    public void pingStatus(){
        exec.execute(() -> safeOp(() -> {
            try {
                int[] st = link.opMachineStatusFull();
                log("[PING] " + Arrays.toString(st));
            } catch (IOException e) {
                if (events != null) events.onError("pingStatus", e);
            }
        }, "pingStatus"));
    }

    public void resyncGetProductId(){
        exec.execute(() -> safeOp(() -> {
            try {
                byte[] rsp = link.opGetField(LcpLink.MSG_GET_PRODUCTID);
                if(rsp != null && rsp.length >= 1){
                    presetProduct = rsp[0] & 0xFF;
                    log("[SYNC] Product=" + presetProduct);
                }
            } catch (IOException e) {
                if (events != null) events.onError("resyncGetProductId", e);
            }
        }, "resyncGetProductId"));
    }

}
