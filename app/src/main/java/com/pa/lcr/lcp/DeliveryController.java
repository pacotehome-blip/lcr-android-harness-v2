
package com.pa.lcr.lcp;

import java.util.concurrent.*;
import java.util.Arrays;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class DeliveryController {

    private ScheduledFuture<?> liveLoopFuture;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

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

    // LCR field numbers per LCP PDF
    private static final int FIELD_PRODUCT_NUMBER = 0;   // ProductNumber_DL [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
    private static final int FIELD_GROSS_PRESET   = 5;   // GrossPreset_PL   [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
    private static final int FIELD_NET_PRESET     = 6;   // NetPreset_PL     [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
    private static final int FIELD_QTY_UNITS      = 38;  // QtyUnits_WM      [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
    private static final int FIELD_DECIMALS       = 39;  // Decimals_WM      [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
    private static final int FIELD_GROSS_COUNT    = 44;  // GrossCount_NE    [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
    private static final int FIELD_NET_COUNT      = 45;  // NetCount_NE      [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)

    public enum State { IDLE, PRESTART, STARTING, RUNNING, ENDING, ENDED, ERROR }
    private volatile State state = State.IDLE;

    public interface DeliveryEvents {
        void onStateChanged(State s);
        void onFlowStarted();
        void onFlowStopped();
        void onLiveSample(int delStatus, int delCode, double grossL, double netL);
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
        public int ds; // delStatus
        public int dc; // delCode
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

    /**
     * Convert user-facing product number (1..16) to LIST+0 value (0..15). [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
     */
    private static int productToList0Value(int product) {
        if (product < 1 || product > 16) throw new IllegalArgumentException("product must be 1..16");
        return product - 1;
    }

    /**
     * Encode a VOLUME field as signed 32-bit integer with implied decimals (Field #39) MSB-first. [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
     */
    private static byte[] encodeVolume(double value, int decimals) {
        // decimals list: 0=hundredths, 1=tenths, 2=whole [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
        int scale;
        if (decimals == 0) scale = 100;
        else if (decimals == 1) scale = 10;
        else scale = 1;

        long raw = Math.round(value * scale);
        int v = (int) raw;

        return new byte[] {
                (byte)((v >> 24) & 0xFF),
                (byte)((v >> 16) & 0xFF),
                (byte)((v >>  8) & 0xFF),
                (byte)( v        & 0xFF)
        };
    }

    /**
     * Decode signed 32-bit MSB-first with implied decimals (Field #39). [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
     */
    private static double decodeVolume(byte[] fieldData4, int decimals) {
        if (fieldData4 == null || fieldData4.length < 4) return 0.0;

        int v = ((fieldData4[0] & 0xFF) << 24)
              | ((fieldData4[1] & 0xFF) << 16)
              | ((fieldData4[2] & 0xFF) <<  8)
              |  (fieldData4[3] & 0xFF);

        int scale;
        if (decimals == 0) scale = 100;
        else if (decimals == 1) scale = 10;
        else scale = 1;

        return v / (double)scale;
    }

    /**
     * Read Decimals (#39) as LIST+14 (byte). [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
     */
    private int readDecimals() throws Exception {
        byte[] rsp = link.opGetField(FIELD_DECIMALS); // expects fieldData at rsp[0..]
        if (rsp == null || rsp.length < 1) return 0; // default hundredths
        return rsp[0] & 0xFF;
    }

    /**
     * Read Gross/Net counts from fields #44/#45 (VOLUME), applying decimals (#39). [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
     */
    private double readGross() throws Exception {
        int decimals = readDecimals();
        byte[] data = link.opGetField(FIELD_GROSS_COUNT);
        return decodeVolume(data, decimals);
    }

    private double readNet() throws Exception {
        int decimals = readDecimals();
        byte[] data = link.opGetField(FIELD_NET_COUNT);
        return decodeVolume(data, decimals);
    }

    /* ============================= PRESTART ============================= */
    public void prestartSequence(int product, double presetLitres, int pollMs) throws Exception {
        log("[PRE] PythonCompat ON");
        link.setPythonCompat(true, pollMs);

        // Optional: open poll window AFTER critical writes, or ensure LcpLink suspends polling during writes.
        log("[PRE] Opening poll window");
        link.openPollWindow();
        pollWindowOpen = true;

        log("[PRE] Reading machine status (#23)");
        int[] st = link.opMachineStatusFull();
        log(String.format("[PRE] MachineStatus dev=0x%04X ds=0x%04X dc=0x%04X", st[0], st[1], st[2]));

        // 1) Set active product: Field #0 (LIST+0), value 0..15 => Product #1..#16 [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
        int list0 = productToList0Value(product);
        log("[PRE] Setting active product (Field #0) product=" + product + " (list0=" + list0 + ")");
        link.opSetField(FIELD_PRODUCT_NUMBER, new byte[] { (byte)list0 });
        this.presetProduct = product;

        // 2) Set preset: Field #6 is NetPreset_PL (VOLUME, 4 bytes, implied decimals). [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
        this.presetLitres = presetLitres;
        if (presetLitres > 0.0) {
            int decimals = readDecimals();
            byte[] enc = encodeVolume(presetLitres, decimals);
            log("[PRE] Setting NET preset (Field #6) value=" + presetLitres + " (decimals=" + decimals + ")");
            link.opSetField(FIELD_NET_PRESET, enc);

            // Good practice: clear the other preset if you're explicitly using net preset.
            byte[] zero = encodeVolume(0.0, decimals);
            log("[PRE] Clearing GROSS preset (Field #5)");
            link.opSetField(FIELD_GROSS_PRESET, zero);
        }

        // 3) Avoid changing configuration fields like PresetsAllowed (#85) on every delivery unless required. [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
        // log("[PRE] (optional) PresetsAllowed Field #85 not modified");

        log("[PRE] Completed PRE-START");
    }

    /* ============================= START ============================= */
    public void startDeliverySequence(int pollMs) throws Exception {
        log("[START] Issue Command START/RESUME (0)");
        link.opIssueCommand(0x00); // Command #0 Start/Resume [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
        setState(State.STARTING);

        // Wait until delivery becomes active (delCode bit 0x0008). [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
        long deadline = System.currentTimeMillis() + 5000;
        boolean active = false;

        while (System.currentTimeMillis() < deadline) {
            int[] dsdc = link.opDeliveryStatus(); // should map to MsgID 0x28 [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
            int ds = dsdc[0]; // delStatus
            int dc = dsdc[1]; // delCode

            active = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0; // these should be delCode bits [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
            if (active) break;

            Thread.sleep(Math.max(50, pollMs));
        }

        if (!active) throw new Exception("Delivery not active after START/RESUME");

        startTimestampMs = System.currentTimeMillis();
        lastPollAt = startTimestampMs;

        // Read real counters from fields (not from ds/dc bit packing). [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
        lastGross = readGross();
        lastNet   = readNet();
        startGross = lastGross;
        startNet   = lastNet;

        setState(State.RUNNING);
        log("[START] Delivery ACTIVE");
    }

    /* ============================= LIVE LOOP ============================= */
    public void startLiveLoop(int pollMs) {
        log("[LIVE] Starting live loop");

        final boolean[] lastFlow = new boolean[] { false };

        liveLoopFuture = scheduler.scheduleAtFixedRate(() -> {
            try {
                if (stopping || state != State.RUNNING) return;

                int[] dsdc = link.opDeliveryStatus();
                int ds = dsdc[0]; // delStatus
                int dc = dsdc[1]; // delCode

                boolean flow   = (dc & LcpLink.LCRSc_FLOW_ACTIVE) != 0;      // delCode bit 0x0004 [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
                boolean active = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;  // delCode bit 0x0008 [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)

                if (!active) {
                    stopping = true;
                    return;
                }

                double gross = readGross();
                double net   = readNet();

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

                // Only on transitions
                if (flow && !lastFlow[0] && events != null) events.onFlowStarted();
                if (!flow && lastFlow[0] && events != null) events.onFlowStopped();
                lastFlow[0] = flow;

                if (guardEnabled) {
                    // Guard should typically be based on delivered amount since start
                    double delivered = net - startNet;
                    if (delivered >= guardTargetLitres) {
                        log("[GUARD] target reached");
                        if (events != null) events.onGuardReached();
                        stopping = true;
                    }
                }

            } catch (Exception e) {
                setState(State.ERROR);
                if(events!=null) events.onError("liveLoop", e);
            }

        }, 0, pollMs, TimeUnit.MILLISECONDS);
    }

    /* ============================= END ============================= */
    public void endDeliverySequence(int timeoutMs, int pollMs) throws Exception {
        log("[END] Issuing END (Command #2)");
        setState(State.ENDING);
        stopping = true;

        // stop live loop first (avoid concurrent reads while ending)
        if (liveLoopFuture != null) {
            liveLoopFuture.cancel(true);
            liveLoopFuture = null;
        }

        link.opIssueCommand(0x02); // Command #2 End delivery [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)

        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            int[] dsdc = link.opDeliveryStatus();
            int dc = dsdc[1];
            boolean active = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0; // delCode [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
            if (!active) break;
            Thread.sleep(pollMs);
        }

        if (pollWindowOpen) {
            link.closePollWindow();
            pollWindowOpen = false;
        }

        setState(State.ENDED);
    }

    /* ============================= PRINT ============================= */
    public void printTicketText(String txt, int heightDots, int timeoutMs){
        exec.execute(() -> {
            try{
                byte[] data = txt.getBytes(StandardCharsets.US_ASCII);
                // Print Text is MsgID 0x22, not SetField. [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
                link.opPrintText(data); // TODO: implement in LcpLink if not present
            } catch(Exception e){
                if (events != null) events.onError("printTicketText", e);
            }
        });
    }

    /* ============================= STOP ============================= */
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

    public void pingStatus(){
        exec.execute(() -> safeOp(() -> {
            try {
                int[] st = link.opMachineStatusFull(); // MsgID 0x23 returns devStatus/prnStatus/delStatus/delCode [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
                log("[PING] " + Arrays.toString(st));
            } catch (IOException e) {
                if (events != null) events.onError("pingStatus", e);
            }
        }, "pingStatus"));
    }

    public void resyncGetProductId(){
        exec.execute(() -> safeOp(() -> {
            try {
                // Product ID message is generic msgID 0x00 (not GetFieldData). [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
                byte[] rsp = link.opGetProductId(); // TODO: implement in LcpLink
                if(rsp != null && rsp.length >= 2){
                    int productId = rsp[1] & 0xFF; // per example: rc then productID [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
                    log("[SYNC] ProductID=" + productId);
                }
            } catch (IOException e) {
                if (events != null) events.onError("resyncGetProductId", e);
            }
        }, "resyncGetProductId"));
    }
}