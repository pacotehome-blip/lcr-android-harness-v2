
package com.pa.lcr.lcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.*;

/**
 * DeliveryController — version corrigée "métier" + impression détaillée.
 *
 * Références:
 * - Champs: Excel "LCR Registers' Fields.xlsx" (Field #0, #6, #39, #44/#45, #16, #23, #37). [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
 * - Protocole: PDF "LCR API Internal Messages for LCP.pdf" (0x23 prnStatus bits; 0x28; DeliveryCode bits; TicketRequired behavior). [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
 */
public class DeliveryController {

    // ------------------------- Champs LCR (métier) -------------------------
    private static final int FIELD_PRODUCT_NUMBER = 0;   // ProductNumber [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)[1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
    private static final int FIELD_NET_PRESET     = 6;   // NetPreset [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)[1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
    private static final int FIELD_DECIMALS       = 39;  // Decimals [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)[1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
    private static final int FIELD_GROSS_COUNT    = 44;  // GrossCount [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)[1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
    private static final int FIELD_NET_COUNT      = 45;  // NetCount [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)[1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)

    private static final int FIELD_CLEAR_SHIFT     = 16; // ClearShift [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)[1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
    private static final int FIELD_TICKET_NUMBER   = 23; // TicketNumber [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)[1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
    private static final int FIELD_TICKET_REQUIRED = 37; // TicketRequired [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)[1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)

    // ------------------------- Dépendances -------------------------
    private final LcpLink link;
    private final DeliveryEvents events;
    private final ExecutorService exec;

    // Un seul scheduler
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> liveLoopFuture;

    // ------------------------- État -------------------------
    public enum State { IDLE, PRESTART, STARTING, RUNNING, ENDING, ENDED, ERROR }
    private volatile State state = State.IDLE;

    private volatile boolean stopping = false;
    private volatile boolean pollWindowOpen = false;

    // Guard (optionnel)
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

    // Flow transitions
    private volatile Boolean lastFlow = null;

    // Params preset
    private volatile int presetProduct = 1;
    private volatile double presetLitres = 0;

    // ============================= EVENTS =============================
    public interface DeliveryEvents {
        void onStateChanged(State s);
        void onFlowStarted();
        void onFlowStopped();
        void onProgress(DeliveryProgress p);

        // UI métier
        void onTicketNumber(int ticketNumber);
        void onTicketRequired(int mode); // 0/1/2 [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)[1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)

        // UI statut imprimante détaillé
        void onPrinterStatus(LcpLink.MachineStatusEx ms, boolean ticketPending);

        void onError(String msg, Throwable t);
        void onLog(String line);
    }

    public static final class DeliveryProgress {
        public long tSinceStartMs;

        // compteurs bruts (absolus, current delivery)
        public double grossL;    // #44 [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)[1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
        public double netL;      // #45 [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)[1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)

        // deltas depuis start = "en livraison"
        public double deliveredGrossL;
        public double deliveredNetL;

        // deltas instantanés
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

    // ============================= Helpers =============================
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

    /** Ouvre une poll window temporaire si nécessaire (évite POLL_BLOCKED). */
    private <T> T withPollWindow(Callable<T> op) throws Exception {
        boolean openedHere = false;
        if (!pollWindowOpen) {
            link.openPollWindow(); // owner=ANY [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
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

    private static int productToList0Value(int product) {
        if (product < 1 || product > 16) throw new IllegalArgumentException("product must be 1..16");
        return product - 1; // Field #0 values 0..15 => products 1..16 [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)[1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
    }

    private int getDecimals() throws Exception {
        if (cachedDecimals >= 0) return cachedDecimals;
        byte[] d = link.opGetField(FIELD_DECIMALS); // #39 [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)[1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
        cachedDecimals = (d != null && d.length >= 1) ? (d[0] & 0xFF) : 0;
        return cachedDecimals;
    }

    private static int scaleForDecimalsIndex(int decimalsIndex) {
        // doc/excel: 0=Hundredths, 1=Tenths, 2=Whole, 3=Thousandths (on supporte 3) [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)[1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
        if (decimalsIndex == 1) return 10;
        if (decimalsIndex == 2) return 1;
        if (decimalsIndex == 3) return 1000;
        return 100;
    }

    private static byte[] encodeVolume(double litres, int decimalsIndex) {
        int scale = scaleForDecimalsIndex(decimalsIndex);
        long raw = Math.round(litres * scale);
        int v = (int) raw;
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
        return decodeVolume(link.opGetField(FIELD_GROSS_COUNT), dec); // #44 [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)[1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
    }

    private double readNetLitres() throws Exception {
        int dec = getDecimals();
        return decodeVolume(link.opGetField(FIELD_NET_COUNT), dec); // #45 [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)[1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
    }

    // Field #23 TicketNumber = LONG/SL = 4 bytes signed int [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)[1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
    private static int s32be(byte[] b4) {
        if (b4 == null || b4.length < 4) return 0;
        return ((b4[0] & 0xFF) << 24) | ((b4[1] & 0xFF) << 16) | ((b4[2] & 0xFF) << 8) | (b4[3] & 0xFF);
    }

    private int readTicketNumber() throws Exception {
        return s32be(link.opGetField(FIELD_TICKET_NUMBER)); // #23 [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)[1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
    }

    private int readTicketRequired() throws Exception {
        byte[] raw = link.opGetField(FIELD_TICKET_REQUIRED); // #37 [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)[1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
        return (raw != null && raw.length > 0) ? (raw[0] & 0xFF) : 0;
    }

    // ============================= PRINTER STATUS =============================

    /**
     * Rafraîchit le statut imprimante détaillé (0x23) + ticketPending (dc bit 0x0001).
     * IMPORTANT: 0x23 peut introduire ~2s si printer offline; on l'appelle seulement Connect/Prestart/End. [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
     */
    public void refreshPrinterStatus(int pollMs) {
        exec.execute(() -> safeOp(() -> {
            try {
                link.setPythonCompat(true, pollMs);

                LcpLink.MachineStatusEx ms = withPollWindow(() -> link.opMachineStatusEx()); // 0x23 includes prnStatus [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
                boolean pending = (ms.delCode & LcpLink.LCRSc_DEL_TICKET_PENDING) != 0;     // blocks next delivery until printed [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)

                log("[PRN] " + ms.toString() + " ticketPending=" + pending);

                if (events != null) events.onPrinterStatus(ms, pending);

            } catch (Exception e) {
                if (events != null) events.onError("refreshPrinterStatus", e);
            }
        }, "refreshPrinterStatus"));
    }

    // ============================= TICKET INFO (UI) =============================

    public void refreshTicketInfo(int pollMs) {
        exec.execute(() -> safeOp(() -> {
            try {
                link.setPythonCompat(true, pollMs);

                int tr = readTicketRequired();
                int tn = readTicketNumber();

                log("[TICKET] TicketRequired(#37)=" + tr + " (0=req,1=optional,2=never)"); // [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)[1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
                log("[TICKET] TicketNumber(#23)=" + tn + " (increments after printing)");  // [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)[1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)

                if (events != null) {
                    events.onTicketRequired(tr);
                    events.onTicketNumber(tn);
                }

            } catch (Exception e) {
                if (events != null) events.onError("refreshTicketInfo", e);
            }
        }, "refreshTicketInfo"));
    }

    /** Met TicketRequired (#37) à une valeur (0/1/2) et loggue avant/après. [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)[1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf) */
    public void setTicketRequired(int mode, int pollMs) {
        exec.execute(() -> safeOp(() -> {
            try {
                if (mode < 0 || mode > 2) throw new IllegalArgumentException("TicketRequired must be 0..2");

                link.setPythonCompat(true, pollMs);

                int before = readTicketRequired();
                link.opSetField(FIELD_TICKET_REQUIRED, new byte[]{ (byte)mode });
                int after = readTicketRequired();

                log("[TICKET] TicketRequired(#37) change " + before + " -> " + after + " (requested=" + mode + ")"); // [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)[1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
                if (events != null) events.onTicketRequired(after);

            } catch (Exception e) {
                if (events != null) events.onError("setTicketRequired", e);
            }
        }, "setTicketRequired"));
    }

    /** Force la politique par défaut: TicketRequired(#37)=1 (optional), pour éviter blocage terrain. [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)[1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf) */
    public void ensureDefaultTicketRequiredIs1(int pollMs) {
        exec.execute(() -> safeOp(() -> {
            try {
                link.setPythonCompat(true, pollMs);
                int tr = readTicketRequired();
                if (tr != 1) {
                    log("[TICKET] TicketRequired(#37) read=" + tr + " -> setting to 1 (default optional)"); // [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)[1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
                    link.opSetField(FIELD_TICKET_REQUIRED, new byte[]{ (byte)1 });
                    int tr2 = readTicketRequired();
                    log("[TICKET] TicketRequired(#37) now=" + tr2);
                    if (events != null) events.onTicketRequired(tr2);
                } else {
                    log("[TICKET] TicketRequired(#37) already 1 (default OK)");
                }
            } catch (Exception e) {
                if (events != null) events.onError("ensureDefaultTicketRequiredIs1", e);
            }
        }, "ensureDefaultTicketRequiredIs1"));
    }

    /**
     * ClearShift (#16)=0 (clear).
     * NOTE: comportement dépend de TicketRequired (#37):
     * - si #37=0 => shift ticket doit être imprimé avec succès avant clear
     * - si #37=1 => imprime si possible, mais clear dans tous les cas
     * [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)[1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
     */
    public void clearShiftNow(int pollMs) {
        exec.execute(() -> safeOp(() -> {
            try {
                link.setPythonCompat(true, pollMs);

                int tr = readTicketRequired();
                log("[SHIFT] TicketRequired(#37)=" + tr + " ; sending ClearShift(#16)=0"); // [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)[1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
                link.opSetField(FIELD_CLEAR_SHIFT, new byte[]{ 0x00 });
                log("[SHIFT] ClearShift(#16)=0 sent");

            } catch (Exception e) {
                if (events != null) events.onError("clearShiftNow", e);
            }
        }, "clearShiftNow"));
    }

    // ============================= PING / STATUS =============================

    public void pingStatus(int pollMs){
        exec.execute(() -> safeOp(() -> {
            try {
                link.setPythonCompat(true, pollMs);

                LcpLink.MachineStatusEx ms = withPollWindow(() -> link.opMachineStatusEx());
                log("[PING] " + ms.toString());

                boolean pending = (ms.delCode & LcpLink.LCRSc_DEL_TICKET_PENDING) != 0; // [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
                if (events != null) events.onPrinterStatus(ms, pending);

            } catch (Exception e) {
                if (events != null) events.onError("pingStatus", e);
            }
        }, "pingStatus"));
    }

    // ============================= PRE-START =============================
    public void prestartSequence(int product, double presetLitres, int pollMs) throws Exception {

        // Refresh printer status BEFORE starting a delivery (option 2)
        refreshPrinterStatus(pollMs);

        log("[PRE] PythonCompat ON");
        link.setPythonCompat(true, pollMs);

        // Read machine status (0x23) once (already done above), but keep delivery status via 0x28 inside start logic.
        // Set product
        int list0 = productToList0Value(product);
        log("[PRE] Setting active product (Field #0) product=" + product + " (list0=" + list0 + ")"); // [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)[1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
        link.opSetField(FIELD_PRODUCT_NUMBER, new byte[]{ (byte)list0 });
        this.presetProduct = product;

        // Set net preset (Field #6)
        this.presetLitres = presetLitres;
        if (presetLitres > 0.0) {
            int dec = getDecimals();
            log("[PRE] Setting NET preset (Field #6) value=" + presetLitres + " (decimalsIndex=" + dec + ")"); // [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)[1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
            link.opSetField(FIELD_NET_PRESET, encodeVolume(presetLitres, dec));
        }

        log("[PRE] Completed PRE-START");
    }

    // ============================= START =============================
    public void startDeliverySequence(int pollMs) throws Exception {
        log("[START] RUN (Command #0)");
        link.opIssueCommand(0x00);
        setState(State.STARTING);

        boolean active = withPollWindow(() -> {
            long deadline = System.currentTimeMillis() + 9000;

            while (System.currentTimeMillis() < deadline) {
                int[] dsdc = link.opDeliveryStatus(); // 0x28 fast (no prnStatus) [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
                int dc = dsdc[1];

                boolean isActive = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0; // [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
                if (isActive) return true;

                // block reason: ticket pending prevents start of new delivery
                boolean pending = (dc & LcpLink.LCRSc_DEL_TICKET_PENDING) != 0; // [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
                if (pending) {
                    log("[START] blocked: TicketPending=true (dc bit0). Print ticket required to clear."); // [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
                }

                Thread.sleep(Math.max(50, pollMs));
            }
            return false;
        });

        if (!active) throw new Exception("Delivery not active after RUN");

        startTimestampMs = System.currentTimeMillis();

        // baselines
        startGross = readGrossLitres();
        startNet   = readNetLitres();
        lastGross  = startGross;
        lastNet    = startNet;
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

                    // Use 0x28 during running (fast, no printer delay) [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
                    int[] dsdc = withPollWindow(() -> link.opDeliveryStatus());
                    int ds = dsdc[0];
                    int dc = dsdc[1];

                    boolean flow   = (dc & LcpLink.LCRSc_FLOW_ACTIVE) != 0;     // [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
                    boolean active = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0; // [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
                    if (!active) { stopping = true; return; }

                    // Lire compteurs (#44/#45). Si une lecture rate, conserver last (robuste terrain).
                    double gross = lastGross;
                    double net   = lastNet;

                    try { gross = readGrossLitres(); } catch (Exception ex) { log("[LIVE] WARN gross read failed: " + ex.getMessage()); }
                    try { net   = readNetLitres(); }   catch (Exception ex) { log("[LIVE] WARN net read failed: " + ex.getMessage()); }

                    DeliveryProgress p = new DeliveryProgress();
                    p.tSinceStartMs = System.currentTimeMillis() - startTimestampMs;

                    p.grossL = gross;
                    p.netL = net;

                    p.deliveredGrossL = gross - startGross;
                    p.deliveredNetL = net - startNet;

                    p.dGrossL = gross - lastGross;
                    p.dNetL = net - lastNet;

                    p.flowActive = flow;
                    p.stalled = !flow;
                    p.ds = ds;
                    p.dc = dc;

                    lastGross = gross;
                    lastNet = net;

                    if (events != null) events.onProgress(p);

                    // transitions flow
                    if (lastFlow == null) lastFlow = flow;
                    if (flow && !lastFlow && events != null) events.onFlowStarted();
                    if (!flow && lastFlow && events != null) events.onFlowStopped();
                    lastFlow = flow;

                    // guard optional
                    if (guardEnabled) {
                        if (p.deliveredNetL >= guardTargetLitres) {
                            log("[GUARD] target reached");
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

        // Refresh printer status BEFORE ending (option 2)
        refreshPrinterStatus(pollMs);

        // Ticket proof (before)
        int trBefore = readTicketRequired();
        int tnBefore = readTicketNumber();
        log(String.format("[PRINT] Policy TicketRequired(#37)=%d (0=req,1=optional,2=never) TicketNumber(#23) before=%d",
                trBefore, tnBefore)); // [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)[1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)

        log("[END] Issuing END (Command #2)");
        setState(State.ENDING);
        stopping = true;

        if (liveLoopFuture != null) {
            liveLoopFuture.cancel(true);
            liveLoopFuture = null;
        }

        // Command #2 ends delivery and prints ticket if settings allow [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
        link.setPythonCompat(true, pollMs);
        link.opIssueCommand(0x02);

        // Wait inactive and observe ticketPending transitions via 0x28 (fast)
        withPollWindow(() -> {
            long deadline = System.currentTimeMillis() + timeoutMs;
            Boolean lastPending = null;

            while (System.currentTimeMillis() < deadline) {
                int[] dsdc = link.opDeliveryStatus(); // 0x28 fast [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
                int dc = dsdc[1];

                boolean active  = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0; // [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
                boolean pending = (dc & LcpLink.LCRSc_DEL_TICKET_PENDING) != 0; // blocks next delivery until printed [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)

                if (lastPending == null || pending != lastPending) {
                    log("[PRINT] TicketPending changed => " + pending);
                    lastPending = pending;
                }

                if (!active) break;
                Thread.sleep(pollMs);
            }
            return null;
        });

        // Refresh printer status AFTER ending (option 2)
        refreshPrinterStatus(pollMs);

        // Ticket proof (after)
        int tnAfter = readTicketNumber();
        log(String.format("[PRINT] TicketNumber(#23) after=%d delta=%+d", tnAfter, (tnAfter - tnBefore))); // [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)[1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)

        if (tnAfter > tnBefore) {
            log("[PRINT] CONFIRMED: TicketNumber incremented => delivery ticket printed"); // [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)[1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
        } else {
            log("[PRINT] NOT CONFIRMED: TicketNumber did not increment (ticket may be pending or printer not ready)");
        }

        setState(State.ENDED);
    }

    // ============================= PUBLIC ACTIONS =============================

    /** Compat signature (sans preset) */
    public void startOpenMode(int product, int timeoutMs, int pollMs){
        startOpenMode(product, 0.0, timeoutMs, pollMs);
    }

    /** Version corrigée avec preset transmis */
    public void startOpenMode(int product, double presetLitres, int timeoutMs, int pollMs){
        exec.execute(() -> safeOp(() -> {

            // Interdire un 2e start si livraison déjà en cours (cohérence simple)
            if (state == State.RUNNING || state == State.STARTING || state == State.PRESTART || state == State.ENDING) {
                log("[START] ignored: delivery already in progress state=" + state);
                return;
            }

            setState(State.PRESTART);
            stopping = false;
            cachedDecimals = -1;
            lastFlow = null;

            // Option 2: refresh printer status before starting
            refreshPrinterStatus(pollMs);

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
            if (state == State.ENDED || state == State.IDLE) {
                log("[END] ignored: already ended");
                return;
            }
            try {
                endDeliverySequence(timeoutMs, pollMs);
            } catch (Exception ex) {
                if(events!=null) events.onError("endDeliverySequence", ex);
            }
        }, "endGracefully"));
    }

    /** Impression texte (MsgID 0x22) - utile si tu veux imprimer une ligne custom */
    public void printText(String txt){
        exec.execute(() -> safeOp(() -> {
            try {
                byte[] data = (txt == null ? "" : txt).getBytes(StandardCharsets.US_ASCII);
                link.opPrintText(data); // 0x22 [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
            } catch (Exception ex) {
                if (events != null) events.onError("printText", ex);
            }
        }, "printText"));
    }

    // ============================= GUARD API =============================
    public void setGuardEnabled(boolean enabled, double targetLitres) {
        this.guardEnabled = enabled;
        this.guardTargetLitres = Math.max(0.0, targetLitres);
    }
}
