
package com.pa.lcr.lcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.*;

/**
 * DeliveryController — version corrigée:
 * - Produit actif = Field #0 (ProductNumber_DL, LIST+0) mapping product 1..16 -> value 0..15. [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
 * - Preset net = Field #6 (NetPreset_PL, VOLUME) encodé sur 4 bytes big-endian, décimales via Field #39. [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
 * - FLOW_ACTIVE / DELIVERY_ACTIVE = bits dans delCode (dc) (Get Delivery Status 0x28). [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
 * - Quantités live via Field #44/#45 (GrossCount/NetCount). [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
 * - PrintText via MsgID 0x22 (opPrintText), ProductId via MsgID 0x00 (opGetProductId). [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
 * - Evite POLL_BLOCKED avec poll window temporaire.
 * - Live loop robuste: un raté ponctuel de lecture compteur ne met pas l’état ERROR.
 * - END: force PythonCompat pendant la séquence (queued + 0x7D).
 */
public class DeliveryController {

    // ------------------------- Champs LCR (spec) -------------------------
    private static final int FIELD_PRODUCT_NUMBER = 0;   // ProductNumber_DL [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
    private static final int FIELD_GROSS_PRESET   = 5;   // GrossPreset_PL [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
    private static final int FIELD_NET_PRESET     = 6;   // NetPreset_PL [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
    private static final int FIELD_DECIMALS       = 39;  // Decimals_WM [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
    private static final int FIELD_GROSS_COUNT    = 44;  // GrossCount_NE [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
    private static final int FIELD_NET_COUNT      = 45;  // NetCount_NE [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)

    // ------------------------- Dépendances -------------------------
    private final LcpLink link;
    private final DeliveryEvents events;
    private final ExecutorService exec;

    // Un seul scheduler (évite double live loop / fuites)
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> liveLoopFuture;

    // ------------------------- État -------------------------
    public enum State { IDLE, PRESTART, STARTING, RUNNING, ENDING, ENDED, ERROR }
    private volatile State state = State.IDLE;

    private volatile boolean stopping = false;
    private volatile boolean pollWindowOpen = false;

    // Guard
    private volatile boolean guardEnabled = false;
    private volatile double guardTargetLitres = 0;

    // Mesures
    private volatile long startTimestampMs = 0;
    private volatile double startGross = 0;
    private volatile double startNet = 0;

    private volatile double lastGross = 0;
    private volatile double lastNet = 0;

    // Cache décimales (#39)
    private volatile int cachedDecimals = -1;

    // Flow transitions (évite spam)
    private volatile Boolean lastFlow = null;

    // UI state info
    private volatile int presetProduct = 1;
    private volatile double presetLitres = 0;

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
        public int ds; // delStatus
        public int dc; // delCode
    }

    public DeliveryController(LcpLink link, DeliveryEvents cb, ExecutorService svc) {
        this.link = link;
        this.events = cb;
        this.exec = svc;
    }

    // ------------------------- Helpers log/etat -------------------------
    private void log(String s){ if (events != null) events.onLog(s); }

    private void setState(State s) {
        this.state = s;
        if (events != null) events.onStateChanged(s);
    }

    private void safeOp(Runnable r, String tag){
        try { r.run(); }
        catch(Exception e){
            setState(State.ERROR);
            if(events!=null) events.onError(tag,e);
        }
    }

    // ------------------------- Helpers protocole -------------------------
    private static int productToList0Value(int product) {
        if (product < 1 || product > 16) throw new IllegalArgumentException("product must be 1..16");
        return product - 1; // LIST+0: 0=>prod1, 1=>prod2, ...
    }

    private int getDecimals() throws Exception {
        if (cachedDecimals >= 0) return cachedDecimals;
        byte[] d = link.opGetField(FIELD_DECIMALS);
        cachedDecimals = (d != null && d.length >= 1) ? (d[0] & 0xFF) : 0;
        return cachedDecimals;
    }

    private static int scaleForDecimalsIndex(int decimalsIndex) {
        // LIST+14: 0=hundredths, 1=tenths, 2=whole [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
        if (decimalsIndex == 1) return 10;
        if (decimalsIndex == 2) return 1;
        return 100;
    }

    private static byte[] encodeVolume(double litres, int decimalsIndex) {
        int scale = scaleForDecimalsIndex(decimalsIndex);
        long raw = Math.round(litres * scale);
        int v = (int) raw;
        // Big-endian (MSB first) [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
        return new byte[] {
                (byte)((v >> 24) & 0xFF),
                (byte)((v >> 16) & 0xFF),
                (byte)((v >>  8) & 0xFF),
                (byte)( v        & 0xFF)
        };
    }

    private static double decodeVolume(byte[] b4, int decimalsIndex) {
        if (b4 == null || b4.length < 4) return 0.0;
        int v = ((b4[0] & 0xFF) << 24)
              | ((b4[1] & 0xFF) << 16)
              | ((b4[2] & 0xFF) <<  8)
              |  (b4[3] & 0xFF);
        int scale = scaleForDecimalsIndex(decimalsIndex);
        return v / (double) scale;
    }

    private double readGrossLitres() throws Exception {
        int dec = getDecimals();
        return decodeVolume(link.opGetField(FIELD_GROSS_COUNT), dec);
    }

    private double readNetLitres() throws Exception {
        int dec = getDecimals();
        return decodeVolume(link.opGetField(FIELD_NET_COUNT), dec);
    }

    /** Ouvre une poll window temporaire si nécessaire (évite POLL_BLOCKED). */
    private <T> T withPollWindow(Callable<T> op) throws Exception {
        boolean openedHere = false;
        if (!pollWindowOpen) {
            link.openPollWindow();
            pollWindowOpen = true;
            openedHere = true;
        }
        try {
            return op.call();
        } finally {
            if (openedHere) {
                try { link.closePollWindow(); } catch (Exception ignored) {}
                pollWindowOpen = false;
            }
        }
    }

    // ============================= PRE-START =============================
    public void prestartSequence(int product, double presetLitres, int pollMs) throws Exception {
        log("[PRE] PythonCompat ON");
        link.setPythonCompat(true, pollMs);

        // Read machine status (0x23) via poll window
        withPollWindow(() -> {
            log("[PRE] Reading machine status (#23)");
            int[] st = link.opMachineStatusFull();
            log(String.format("[PRE] MachineStatus dev=0x%04X ds=0x%04X dc=0x%04X", st[0], st[1], st[2]));
            return null;
        });

        // Set active product (Field #0)
        int list0 = productToList0Value(product);
        log("[PRE] Setting active product (Field #0) product=" + product + " (list0=" + list0 + ")");
        link.opSetField(FIELD_PRODUCT_NUMBER, new byte[]{ (byte)list0 });
        this.presetProduct = product;

        // Set net preset (Field #6) if > 0
        this.presetLitres = presetLitres;
        if (presetLitres > 0.0) {
            int dec = getDecimals();
            log("[PRE] Setting NET preset (Field #6) value=" + presetLitres + " (decimalsIndex=" + dec + ")");
            link.opSetField(FIELD_NET_PRESET, encodeVolume(presetLitres, dec));

            // Optionnel: clear gross preset
            // link.opSetField(FIELD_GROSS_PRESET, encodeVolume(0.0, dec));
        }

        log("[PRE] Completed PRE-START");
    }

    // ============================= START =============================
    public void startDeliverySequence(int pollMs) throws Exception {
        log("[START] RUN (Command #0)");
        link.opIssueCommand(0x00);
        setState(State.STARTING);

        // wait until delivery becomes active (delCode bit DELIVERY_ACTIVE)
        boolean active = withPollWindow(() -> {
            long deadline = System.currentTimeMillis() + 9000;
            while (System.currentTimeMillis() < deadline) {
                int[] dsdc = link.opDeliveryStatus(); // 0x28
                int dc = dsdc[1];
                boolean isActive = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;
                if (isActive) return true;
                Thread.sleep(Math.max(50, pollMs));
            }
            return false;
        });

        if (!active) throw new Exception("Delivery not active after RUN");

        startTimestampMs = System.currentTimeMillis();

        // Counters
        lastGross = readGrossLitres();
        lastNet   = readNetLitres();
        startGross = lastGross;
        startNet   = lastNet;

        lastFlow = null;
        stopping = false;

        setState(State.RUNNING);
        log("[START] Delivery ACTIVE");
    }

    // ============================= LIVE LOOP =============================
    public void startLiveLoop(int pollMs) {
        exec.execute(() -> safeOp(() -> {
            if (state != State.RUNNING) {
                log("[LIVE] ignored: state=" + state);
                return;
            }
            if (liveLoopFuture != null && !liveLoopFuture.isCancelled()) {
                log("[LIVE] already running");
                return;
            }

            log("[LIVE] Starting live loop");

            liveLoopFuture = scheduler.scheduleAtFixedRate(() -> {
                try {
                    if (stopping || state != State.RUNNING) return;

                    int[] dsdc = withPollWindow(() -> link.opDeliveryStatus());
                    int ds = dsdc[0];
                    int dc = dsdc[1];

                    boolean flow   = (dc & LcpLink.LCRSc_FLOW_ACTIVE) != 0;
                    boolean active = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;

                    if (!active) { stopping = true; return; }

                    // Lire compteurs, mais ne pas tomber ERROR si un read rate ponctuellement
                    double gross = lastGross;
                    double net   = lastNet;

                    try { gross = readGrossLitres(); } catch (Exception ex) { log("[LIVE] WARN gross read failed: " + ex.getMessage()); }
                    try { net   = readNetLitres(); } catch (Exception ex) { log("[LIVE] WARN net read failed: " + ex.getMessage()); }

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

                    if (lastFlow == null) lastFlow = flow;
                    if (flow && !lastFlow && events != null) events.onFlowStarted();
                    if (!flow && lastFlow && events != null) events.onFlowStopped();
                    lastFlow = flow;

                    if (guardEnabled) {
                        double delivered = net - startNet;
                        if (delivered >= guardTargetLitres) {
                            log("[GUARD] target reached");
                            if (events != null) events.onGuardReached();
                            stopping = true;
                        }
                    }

                } catch (Exception e) {
                    setState(State.ERROR);
                    if (events != null) events.onError("liveLoop", e);
                }
            }, 0, pollMs, TimeUnit.MILLISECONDS);

        }, "startLiveLoop"));
    }

    // ============================= END =============================
    public void endDeliverySequence(int timeoutMs, int pollMs) throws Exception {
        // IMPORTANT: le LCR peut répondre RC=0x26 (queued) aussi pendant END -> besoin 0x7D
        link.setPythonCompat(true, pollMs);

        log("[END] Issuing END (Command #2)");
        setState(State.ENDING);
        stopping = true;

        if (liveLoopFuture != null) {
            liveLoopFuture.cancel(true);
            liveLoopFuture = null;
        }

        link.opIssueCommand(0x02);

        // Wait inactive
        withPollWindow(() -> {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                int[] dsdc = link.opDeliveryStatus();
                int dc = dsdc[1];
                boolean active = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;
                if (!active) break;
                Thread.sleep(pollMs);
            }
            return null;
        });

        setState(State.ENDED);
    }

    // ============================= PRINT =============================
    public void printTicketText(String txt, int heightDots, int timeoutMs){
        exec.execute(() -> {
            try{
                byte[] data = (txt == null ? "" : txt).getBytes(StandardCharsets.US_ASCII);
                link.opPrintText(data); // MsgID 0x22 [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
            } catch(Exception e){
                if (events != null) events.onError("printTicketText", e);
            }
        });
    }

    // ============================= CONTROL API =============================
    public void requestStop(String reason){
        exec.execute(() -> safeOp(() -> {
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
        }, "requestStop"));
    }

    /** Signature historique (sans preset) */
    public void startOpenMode(int product, int timeoutMs, int pollMs){
        startOpenMode(product, 0.0, timeoutMs, pollMs);
    }

    /** Version corrigée avec preset */
    public void startOpenMode(int product, double presetLitres, int timeoutMs, int pollMs){
        exec.execute(() -> safeOp(() -> {
            setState(State.PRESTART);
            stopping = false;

            try {
                prestartSequence(product, presetLitres, pollMs);
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

    // ============================= REQUIRED BY MainActivity =============================
    public void pingStatus(){
        exec.execute(() -> safeOp(() -> {
            try {
                int[] st = withPollWindow(() -> link.opMachineStatusFull());
                log("[PING] " + Arrays.toString(st));
            } catch (IOException e) {
                if (events != null) events.onError("pingStatus", e);
            } catch (Exception e) {
                if (events != null) events.onError("pingStatus", e);
            }
        }, "pingStatus"));
    }

    public void resyncGetProductId(){
        exec.execute(() -> safeOp(() -> {
            try {
                byte[] p = link.opGetProductId(); // MsgID 0x00 [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
                if (p != null && p.length >= 2) {
                    int productId = p[1] & 0xFF;
                    log("[SYNC] ProductID=" + productId);
                }
            } catch (IOException e) {
                if (events != null) events.onError("resyncGetProductId", e);
            }
        }, "resyncGetProductId"));
    }

    // ============================= GUARD API =============================
    public void setGuardEnabled(boolean enabled, double targetLitres) {
        this.guardEnabled = enabled;
        this.guardTargetLitres = Math.max(0.0, targetLitres);
    }
}
