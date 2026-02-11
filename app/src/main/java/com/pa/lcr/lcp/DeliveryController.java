
package com.pa.lcr.lcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.*;

/**
 * DeliveryController — corrections protocole LCP:
 * - Produit actif = Field #0 (LIST+0), mapping product 1..16 -> value 0..15.  [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
 * - Net preset = Field #6 (VOLUME), encodé sur 4 bytes big-endian avec décimales implicites via Field #39. [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
 * - FLOW_ACTIVE / DELIVERY_ACTIVE = bits dans delCode (dc) (via Get Delivery Status 0x28). [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
 * - Quantités live via Field #44/#45 (GrossCount/NetCount), pas via ds/dc bitpacking fictif. [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
 * - PrintText via MsgID 0x22 (link.opPrintText), GetProductId via MsgID 0x00 (link.opGetProductId). [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
 * - Evite POLL_BLOCKED en ouvrant une poll window temporaire quand nécessaire.
 * - Evite de lancer 2 live loops.
 */
public class DeliveryController {

    /* ============================= SECTION 1 ============================= */

    private final LcpLink link;
    private final DeliveryEvents events;
    private final ExecutorService exec;

    // Un seul scheduler pour la durée de vie du controller (évite fuites + double loops)
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> liveLoopFuture;

    private volatile boolean stopping = false;
    private volatile boolean pollWindowOpen = false;

    private volatile boolean guardEnabled = false;
    private volatile double guardTargetLitres = 0;

    private volatile long startTimestampMs = 0;
    private volatile double startGross = 0;
    private volatile double startNet = 0;

    private volatile double lastGross = 0;
    private volatile double lastNet = 0;

    // Cache décimales (#39). Chargé à la demande.
    private volatile int cachedDecimals = -1;

    // Flow transition tracking
    private volatile Boolean lastFlow = null;

    private volatile int presetProduct = 1;
    private volatile double presetLitres = 0;

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
        public int ds; // delStatus
        public int dc; // delCode
    }

    // Champs LCR selon spec PDF
    private static final int FIELD_PRODUCT_NUMBER = 0;  // ProductNumber_DL (LIST+0) [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
    private static final int FIELD_GROSS_PRESET   = 5;  // GrossPreset_PL (VOLUME) [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
    private static final int FIELD_NET_PRESET     = 6;  // NetPreset_PL (VOLUME) [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
    private static final int FIELD_DECIMALS       = 39; // Decimals_WM (LIST+14) [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
    private static final int FIELD_GROSS_COUNT    = 44; // GrossCount_NE (VOLUME) [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
    private static final int FIELD_NET_COUNT      = 45; // NetCount_NE (VOLUME) [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)

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

    /* ============================= HELPERS ============================= */

    private static int productToList0Value(int product) {
        if (product < 1 || product > 16) throw new IllegalArgumentException("product must be 1..16");
        return product - 1; // LIST+0: 0=>prod1, 1=>prod2, ... [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
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

        // Big-endian MSB first [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
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

    /* ============================= SECTION 2 ============================= */

    /**
     * PRE-START:
     * - Active PythonCompat + poll cadence
     * - Read machine status (0x23)
     * - Set product (Field #0)
     * - Set net preset (Field #6) si > 0
     */
    public void prestartSequence(int product, double presetLitres, int pollMs) throws Exception {
        log("[PRE] PythonCompat ON");
        link.setPythonCompat(true, pollMs);

        // Lire machine status (MsgID 0x23) nécessite poll window [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
        withPollWindow(() -> {
            log("[PRE] Reading machine status (#23)");
            int[] st = link.opMachineStatusFull();
            log(String.format("[PRE] MachineStatus dev=0x%04X ds=0x%04X dc=0x%04X", st[0], st[1], st[2]));
            return null;
        });

        // Set product (Field #0 LIST+0) [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
        int list0 = productToList0Value(product);
        log("[PRE] Setting active product (Field #0) product=" + product + " (list0=" + list0 + ")");
        link.opSetField(FIELD_PRODUCT_NUMBER, new byte[]{ (byte)list0 });
        this.presetProduct = product;

        // Set net preset (Field #6 VOLUME, 4 bytes) [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
        this.presetLitres = presetLitres;
        if (presetLitres > 0.0) {
            int dec = getDecimals();
            log("[PRE] Setting NET preset (Field #6) value=" + presetLitres + " (decimalsIndex=" + dec + ")");
            link.opSetField(FIELD_NET_PRESET, encodeVolume(presetLitres, dec));

            // Optionnel: clear gross preset si tu veux forcer net-only
            // link.opSetField(FIELD_GROSS_PRESET, encodeVolume(0.0, dec));
        }

        log("[PRE] Completed PRE-START");
    }

    /* ============================= SECTION 3 ============================= */

    /**
     * START:
     * - Issue RUN command (IssueCommand 0x24 cmd=0)
     * - Attendre DELIVERY_ACTIVE (delCode bit 0x0008) via Get Delivery Status (0x28)
     */
    public void startDeliverySequence(int pollMs) throws Exception {
        log("[START] RUN (Command #0)");
        link.opIssueCommand(0x00);
        setState(State.STARTING);

        boolean active = withPollWindow(() -> {
            long deadline = System.currentTimeMillis() + 7000;
            while (System.currentTimeMillis() < deadline) {
                int[] dsdc = link.opDeliveryStatus(); // MsgID 0x28 [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
                int dc = dsdc[1]; // delCode word [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
                boolean isActive = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;
                if (isActive) return true;
                Thread.sleep(Math.max(50, pollMs));
            }
            return false;
        });

        if (!active) throw new Exception("Delivery not active after RUN");

        startTimestampMs = System.currentTimeMillis();

        lastGross = readGrossLitres();
        lastNet   = readNetLitres();
        startGross = lastGross;
        startNet   = lastNet;

        lastFlow = null;
        stopping = false;

        setState(State.RUNNING);
        log("[START] Delivery ACTIVE");
    }

    /* ============================= SECTION 4 ============================= */

    /**
     * LIVE LOOP:
     * - poll Get Delivery Status (0x28) + read counters #44/#45
     * - fire progress + flow transitions
     */
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

                    double gross = readGrossLitres();
                    double net   = readNetLitres();

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

    /* ============================= SECTION 5 ============================= */

    /**
     * END:
     * - stop live loop
     * - Issue END command (IssueCommand 0x24 cmd=2)
     * - wait delivery inactive via delCode
     */
    public void endDeliverySequence(int timeoutMs, int pollMs) throws Exception {
        log("[END] Issuing END (Command #2)");
        setState(State.ENDING);
        stopping = true;

        if (liveLoopFuture != null) {
            liveLoopFuture.cancel(true);
            liveLoopFuture = null;
        }

        link.opIssueCommand(0x02);

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

    /* ============================= PRINT / MISC ============================= */

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

    private void safeOp(Runnable r, String tag){
        try{ r.run(); }
        catch(Exception e){
            setState(State.ERROR);
            if(events!=null) events.onError(tag,e);
        }
    }

    /* ============================= PUBLIC ACTIONS ============================= */

    /**
     * Compat signature (sans preset): ouvre en mode "open" (preset=0).
     * Ton MainActivity actuel appelle cette version.
     */
    public void startOpenMode(int product, int timeoutMs, int pollMs){
        startOpenMode(product, 0.0, timeoutMs, pollMs);
    }

    /**
     * Version corrigée avec preset transmis.
     * -> Mets MainActivity à jour pour appeler cette overload avec preset.
     */
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

    /* ============================= REQUIRED BY MainActivity ============================= */

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

    /* ============================= OPTIONAL GUARD API ============================= */

    public void setGuardEnabled(boolean enabled, double targetLitres) {
        this.guardEnabled = enabled;
        this.guardTargetLitres = Math.max(0.0, targetLitres);
    }
}
