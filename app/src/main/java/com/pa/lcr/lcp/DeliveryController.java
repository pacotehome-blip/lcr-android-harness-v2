
package com.pa.lcr.lcp;

import java.io.IOException;                   // ✅ FIX: required for catch(IOException)
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/**
 * DeliveryController — Correctifs complets:
 *  - START-GATE basé sur delCode (0x28) et DEL_TICKET_PENDING (bit 0x0001)
 *  - Cmd #6 "print ticket based on current state" (print pending ticket)
 *  - Timeline DS/DC pour retrouver la séquence gagnante
 *  - 0x23 seulement CONNECT/PRESTART/END (jamais pendant RUNNING)
 *  - Champs/encodage conformes (Field #0/#6/#39/#44/#45/#16/#23/#37)
 *
 * Notes robustesse:
 *  - Les opérations non-idempotentes (SET_FIELD/ISSUE_COMMAND/PRINT_TEXT) ne sont pas auto-retry par LcpLink.
 *    Si un framing-timeout survient, l’UI doit permettre de réessayer.
 */
public class DeliveryController {

    // ------------------------- Champs LCR (métier) -------------------------
    private static final int FIELD_PRODUCT_NUMBER   = 0;   // ProductNumber (LIST+0)
    private static final int FIELD_NET_PRESET       = 6;   // NetPreset (VOLUME/LV)
    private static final int FIELD_DECIMALS         = 39;  // Decimals
    private static final int FIELD_GROSS_COUNT      = 44;  // GrossCount (VOLUME/LV)
    private static final int FIELD_NET_COUNT        = 45;  // NetCount (VOLUME/LV)

    private static final int FIELD_CLEAR_SHIFT      = 16;  // ClearShift
    private static final int FIELD_TICKET_NUMBER    = 23;  // TicketNumber (LONG/SL)
    private static final int FIELD_TICKET_REQUIRED  = 37;  // TicketRequired (0/1/2)

    // ------------------------- Dépendances -------------------------
    private final LcpLink link;
    private final DeliveryEvents events;
    private final ExecutorService exec;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> liveLoopFuture;

    // ------------------------- État -------------------------
    public enum State { IDLE, PRESTART, STARTING, RUNNING, ENDING, ENDED, ERROR }
    private volatile State state = State.IDLE;

    private volatile boolean stopping = false;
    private volatile boolean pollWindowOpen = false;

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

    // Derniers paramètres
    private volatile int presetProduct = 1;
    private volatile double presetLitres = 0;

    // ============================= EVENTS =============================
    public interface DeliveryEvents {
        void onStateChanged(State s);
        void onFlowStarted();
        void onFlowStopped();
        void onProgress(DeliveryProgress p);

        void onTicketNumber(int ticketNumber);
        void onTicketRequired(int mode); // 0/1/2

        void onPrinterStatus(LcpLink.MachineStatusEx ms, boolean ticketPending);

        void onError(String msg, Throwable t);
        void onLog(String line);
    }

    public static final class DeliveryProgress {
        public long tSinceStartMs;

        public double grossL;    // #44
        public double netL;      // #45

        public double deliveredGrossL;
        public double deliveredNetL;

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
            link.openPollWindow(); // owner=ANY
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

    // ---------- Timeline / DS/DC helpers ----------
    private static String hx16(int v) { return String.format("0x%04X", (v & 0xFFFF)); }

    private static String dcFlags(int dc) {
        boolean pending = (dc & LcpLink.LCRSc_DEL_TICKET_PENDING) != 0;
        boolean flow    = (dc & LcpLink.LCRSc_FLOW_ACTIVE) != 0;
        boolean active  = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;
        boolean begin   = (dc & LcpLink.LCRSc_BEGIN_DELIVERY) != 0;
        return String.format("[ticketPending=%s flow=%s active=%s begin=%s]",
                pending, flow, active, begin);
    }

    private void logDsDc(String tag, int ds, int dc) {
        log(String.format("[%s] DS=%s DC=%s %s", tag, hx16(ds), hx16(dc), dcFlags(dc)));
    }

    private int[] readDsDcFast() throws Exception {
        // 0x28 Get Delivery Status (fast; no printer status)
        return withPollWindow(() -> link.opDeliveryStatus());
    }

    private void logTimeline(String step, int pollMs) {
        try {
            link.setPythonCompat(true, pollMs);
            int[] dsdc = readDsDcFast();
            logDsDc("TL:" + step, dsdc[0], dsdc[1]);
        } catch (Exception e) {
            log("[TL:" + step + "] WARN: cannot read DS/DC: " + e.getMessage());
        }
    }

    // ------------------------- Conversions champs -------------------------
    private static int productToList0Value(int product) {
        if (product < 1 || product > 16) throw new IllegalArgumentException("product must be 1..16");
        return product - 1; // Field #0 values 0..15 => products 1..16
    }

    private int getDecimals() throws Exception {
        if (cachedDecimals >= 0) return cachedDecimals;
        byte[] d = link.opGetField(FIELD_DECIMALS); // #39
        cachedDecimals = (d != null && d.length >= 1) ? (d[0] & 0xFF) : 0;
        return cachedDecimals;
    }

    private static int scaleForDecimalsIndex(int decimalsIndex) {
        // 0=Hundredths, 1=Tenths, 2=Whole, 3=Thousandths
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
        return decodeVolume(link.opGetField(FIELD_GROSS_COUNT), dec); // #44
    }

    private double readNetLitres() throws Exception {
        int dec = getDecimals();
        return decodeVolume(link.opGetField(FIELD_NET_COUNT), dec); // #45
    }

    private static int s32be(byte[] b4) {
        if (b4 == null || b4.length < 4) return 0;
        return ((b4[0] & 0xFF) << 24) | ((b4[1] & 0xFF) << 16) | ((b4[2] & 0xFF) << 8) | (b4[3] & 0xFF);
    }

    private int readTicketNumber() throws Exception {
        return s32be(link.opGetField(FIELD_TICKET_NUMBER)); // #23
    }

    private int readTicketRequired() throws Exception {
        byte[] raw = link.opGetField(FIELD_TICKET_REQUIRED); // #37
        return (raw != null && raw.length > 0) ? (raw[0] & 0xFF) : 0;
    }

    // ============================= PRINTER STATUS =============================

    /**
     * Rafraîchit le statut imprimante détaillé (0x23) + ticketPending (dc bit 0x0001).
     * IMPORTANT: 0x23 peut être lent si printer offline; on l'appelle Connect/Prestart/End (pas RUNNING).
     */
    public void refreshPrinterStatus(int pollMs) {
        exec.execute(() -> safeOp(() -> {
            try {
                link.setPythonCompat(true, pollMs);

                LcpLink.MachineStatusEx ms = withPollWindow(() -> link.opMachineStatusEx());
                boolean pending = (ms.delCode & LcpLink.LCRSc_DEL_TICKET_PENDING) != 0;

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

                log("[TICKET] TicketRequired(#37)=" + tr + " (0=req,1=optional,2=never)");
                log("[TICKET] TicketNumber(#23)=" + tn + " (increments after printing)");

                if (events != null) {
                    events.onTicketRequired(tr);
                    events.onTicketNumber(tn);
                }

            } catch (Exception e) {
                if (events != null) events.onError("refreshTicketInfo", e);
            }
        }, "refreshTicketInfo"));
    }

    /** Met TicketRequired (#37) à une valeur (0/1/2). */
    public void setTicketRequired(int mode, int pollMs) {
        exec.execute(() -> safeOp(() -> {
            try {
                if (mode < 0 || mode > 2) throw new IllegalArgumentException("TicketRequired must be 0..2");
                link.setPythonCompat(true, pollMs);

                int before = readTicketRequired();
                try {
                    link.opSetField(FIELD_TICKET_REQUIRED, new byte[]{ (byte)mode });
                } catch (IOException ex) {
                    log("[TICKET] SET #37 refused/failed (likely mode/security). " + ex.getMessage());
                }
                int after = readTicketRequired();

                log("[TICKET] TicketRequired(#37) now=" + after + " (requested=" + mode + ", before=" + before + ")");
                if (events != null) events.onTicketRequired(after);

            } catch (Exception e) {
                if (events != null) events.onError("setTicketRequired", e);
            }
        }, "setTicketRequired"));
    }

    /** Force la politique par défaut: TicketRequired(#37)=1 (optional). */
    public void ensureDefaultTicketRequiredIs1(int pollMs) {
        exec.execute(() -> safeOp(() -> {
            try {
                link.setPythonCompat(true, pollMs);
                int tr = readTicketRequired();
                if (tr != 1) {
                    log("[TICKET] TicketRequired(#37) read=" + tr + " -> setting to 1 (default optional)");
                    try {
                        link.opSetField(FIELD_TICKET_REQUIRED, new byte[]{ (byte)1 });
                    } catch (IOException ex) {
                        log("[TICKET] SET #37 refused/failed (likely mode/security). " + ex.getMessage());
                    }
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

    /** ClearShift (#16)=0 (clear). */
    public void clearShiftNow(int pollMs) {
        exec.execute(() -> safeOp(() -> {
            try {
                link.setPythonCompat(true, pollMs);

                int tr = readTicketRequired();
                log("[SHIFT] TicketRequired(#37)=" + tr + " ; sending ClearShift(#16)=0");

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
                logTimeline("PING:BEFORE", pollMs);

                LcpLink.MachineStatusEx ms = withPollWindow(() -> link.opMachineStatusEx());
                log("[PING] " + ms.toString());

                boolean pending = (ms.delCode & LcpLink.LCRSc_DEL_TICKET_PENDING) != 0;
                if (events != null) events.onPrinterStatus(ms, pending);

                logTimeline("PING:AFTER", pollMs);

            } catch (Exception e) {
                if (events != null) events.onError("pingStatus", e);
            }
        }, "pingStatus"));
    }

    // ============================= START-GATE =============================

    /**
     * START-GATE: bloque si un ticket de livraison est en attente (dc bit 0x0001).
     * Utilise 0x28 (fast) et affiche 0x23 seulement pour diagnostiquer si bloqué.
     */
    private boolean startGateAllow(int pollMs) throws Exception {
        int[] dsdc = readDsDcFast();
        int ds = dsdc[0], dc = dsdc[1];

        logDsDc("START-GATE", ds, dc);

        boolean pending = (dc & LcpLink.LCRSc_DEL_TICKET_PENDING) != 0;
        if (!pending) {
            log("[START-GATE] OK: ticketPending=false");
            return true;
        }

        log("[START-GATE] BLOCKED: ticketPending=true -> must print/clear last delivery ticket before starting");
        refreshPrinterStatus(pollMs);
        return false;
    }

    /**
     * Command #6: print a ticket based on current state.
     * Attends la chute de ticketPending.
     */
    public void printPendingTicket(int pollMs, int timeoutMs) {
        exec.execute(() -> safeOp(() -> {
            try {
                link.setPythonCompat(true, pollMs);

                int[] dsdc0 = readDsDcFast();
                int dc0 = dsdc0[1];
                boolean active = (dc0 & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;
                if (active) {
                    log("[PRINT-PENDING] Refus: delivery active -> cmd#6 won't print while active.");
                    return;
                }

                logTimeline("PRINTPEND:BEFORE", pollMs);
                refreshPrinterStatus(pollMs);

                log("[PRINT-PENDING] Issue Command #6");
                link.opIssueCommand(0x06);

                long deadline = System.currentTimeMillis() + timeoutMs;
                boolean lastPending = true;

                while (System.currentTimeMillis() < deadline) {
                    int[] dsdc = readDsDcFast();
                    int ds = dsdc[0], dc = dsdc[1];
                    boolean pending = (dc & LcpLink.LCRSc_DEL_TICKET_PENDING) != 0;
                    if (pending != lastPending) {
                        logDsDc("PRINTPEND:STATE", ds, dc);
                        lastPending = pending;
                    }
                    if (!pending) break;
                    Thread.sleep(pollMs);
                }

                logTimeline("PRINTPEND:AFTER", pollMs);
                refreshPrinterStatus(pollMs);

            } catch (Exception e) {
                if (events != null) events.onError("printPendingTicket", e);
            }
        }, "printPendingTicket"));
    }

    // ============================= PRE-START =============================
    public void prestartSequence(int product, double presetLitres, int pollMs) throws Exception {

        setState(State.PRESTART);
        logTimeline("PRESTART:ENTER", pollMs);

        // Refresh printer status BEFORE starting
        refreshPrinterStatus(pollMs);

        // START-GATE: refuse si ticket pending
        if (!startGateAllow(pollMs)) {
            setState(State.IDLE);
            throw new Exception("START-GATE blocked (ticketPending=true)");
        }

        log("[PRE] PythonCompat ON");
        link.setPythonCompat(true, pollMs);

        // Set product
        int list0 = productToList0Value(product);
        log("[PRE] Setting active product (Field #0) product=" + product + " (list0=" + list0 + ")");
        link.opSetField(FIELD_PRODUCT_NUMBER, new byte[]{ (byte)list0 });
        this.presetProduct = product;

        logTimeline("PRESTART:AFTER_PRODUCT", pollMs);

        // Set net preset
        this.presetLitres = presetLitres;
        if (presetLitres > 0.0) {
            int dec = getDecimals();
            log("[PRE] Setting NET preset (Field #6) value=" + presetLitres + " (decimalsIndex=" + dec + ")");
            link.opSetField(FIELD_NET_PRESET, encodeVolume(presetLitres, dec));
        }

        logTimeline("PRESTART:AFTER_PRESET", pollMs);
        log("[PRE] Completed PRE-START");
    }

    // ============================= START =============================
    public void startDeliverySequence(int pollMs) throws Exception {
        logTimeline("START:ENTER", pollMs);

        log("[START] RUN (Command #0)");
        link.opIssueCommand(0x00);

        setState(State.STARTING);
        logTimeline("START:AFTER_RUN", pollMs);

        boolean active = withPollWindow(() -> {
            long deadline = System.currentTimeMillis() + 12000;

            while (System.currentTimeMillis() < deadline) {
                int[] dsdc = link.opDeliveryStatus(); // 0x28
                int ds = dsdc[0];
                int dc = dsdc[1];

                boolean isActive = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;
                if (isActive) {
                    logDsDc("START:ACTIVE", ds, dc);
                    return true;
                }

                boolean pending = (dc & LcpLink.LCRSc_DEL_TICKET_PENDING) != 0;
                if (pending) log("[START] note: ticketPending=true (may block future starts)");

                Thread.sleep(Math.max(50, pollMs));
            }
            return false;
        });

        if (!active) throw new Exception("Delivery not active after RUN");

        startTimestampMs = System.currentTimeMillis();
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

                    // Use 0x28 during running
                    int[] dsdc = withPollWindow(() -> link.opDeliveryStatus());
                    int ds = dsdc[0];
                    int dc = dsdc[1];

                    boolean flow   = (dc & LcpLink.LCRSc_FLOW_ACTIVE) != 0;
                    boolean active = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;
                    if (!active) { stopping = true; return; }

                    // Lire #44/#45. En cas d'échec, garder dernière valeur.
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

                } catch (Exception e) {
                    setState(State.ERROR);
                    if (events != null) events.onError("liveLoop", e);
                }
            }, 0, pollMs, TimeUnit.MILLISECONDS);

        }, "startLiveLoop"));
    }

    // ============================= END =============================
    public void endDeliverySequence(int timeoutMs, int pollMs) throws Exception {

        logTimeline("END:ENTER", pollMs);

        // Refresh printer status BEFORE ending
        refreshPrinterStatus(pollMs);

        // Ticket proof (before)
        int trBefore = readTicketRequired();
        int tnBefore = readTicketNumber();
        log(String.format("[PRINT] Policy TicketRequired(#37)=%d (0=req,1=optional,2=never) TicketNumber(#23) before=%d",
                trBefore, tnBefore));

        log("[END] Issuing END (Command #2)");
        setState(State.ENDING);
        stopping = true;

        if (liveLoopFuture != null) {
            liveLoopFuture.cancel(true);
            liveLoopFuture = null;
        }

        link.setPythonCompat(true, pollMs);
        link.opIssueCommand(0x02);

        // Wait inactive and observe ticketPending transitions via 0x28
        withPollWindow(() -> {
            long deadline = System.currentTimeMillis() + timeoutMs;
            Boolean lastPending = null;

            while (System.currentTimeMillis() < deadline) {
                int[] dsdc = link.opDeliveryStatus();
                int ds = dsdc[0];
                int dc = dsdc[1];

                boolean active  = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;
                boolean pending = (dc & LcpLink.LCRSc_DEL_TICKET_PENDING) != 0;

                if (lastPending == null || pending != lastPending) {
                    logDsDc("END:STATE", ds, dc);
                    log("[PRINT] TicketPending changed => " + pending);
                    lastPending = pending;
                }

                if (!active) break;
                Thread.sleep(pollMs);
            }
            return null;
        });

        logTimeline("END:INACTIVE", pollMs);

        // Refresh printer status AFTER ending
        refreshPrinterStatus(pollMs);

        // Ticket proof (after)
        int tnAfter = readTicketNumber();
        log(String.format("[PRINT] TicketNumber(#23) after=%d delta=%+d", tnAfter, (tnAfter - tnBefore)));

        if (tnAfter > tnBefore) {
            log("[PRINT] CONFIRMED: TicketNumber incremented => delivery ticket printed");
        } else {
            log("[PRINT] NOT CONFIRMED: TicketNumber did not increment (ticket may be pending or printer not ready)");
        }

        setState(State.ENDED);
    }

    // ============================= PUBLIC ACTIONS =============================

    /** Start with product + preset */
    public void startOpenMode(int product, double presetLitres, int timeoutMs, int pollMs){
        exec.execute(() -> safeOp(() -> {

            if (state == State.RUNNING || state == State.STARTING || state == State.PRESTART || state == State.ENDING) {
                log("[START] ignored: delivery already in progress state=" + state);
                return;
            }

            stopping = false;
            cachedDecimals = -1;
            lastFlow = null;

            logTimeline("STARTOPEN:ENTER", pollMs);

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

    /** Compat signature (sans preset) */
    public void startOpenMode(int product, int timeoutMs, int pollMs){
        startOpenMode(product, 0.0, timeoutMs, pollMs);
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

    /** Impression texte (MsgID 0x22) */
    public void printText(String txt){
        exec.execute(() -> safeOp(() -> {
            try {
                byte[] data = (txt == null ? "" : txt).getBytes(StandardCharsets.US_ASCII);
                link.opPrintText(data);
            } catch (Exception ex) {
                if (events != null) events.onError("printText", ex);
            }
        }, "printText"));
    }
}
