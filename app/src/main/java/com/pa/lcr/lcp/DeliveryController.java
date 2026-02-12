
package com.pa.lcr.lcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/**
 * DeliveryController — version corrigée:
 * - START-GATE: bloque start si delCode.TICKET_PENDING (bit 0x0001)
 * - Cmd #6: Print pending ticket (jamais si delivery active)
 * - Cmd #0: Start/Resume via resumeDelivery()
 * - Timeline DS/DC (0x28) + Printer status (0x23)
 * - Live loop: 0x28 (fast) + fields #44/#45, détection FLOW_ACTIVE (bit 0x0004)
 *
 * Ajouts "terrain":
 * - onOperatorAlert(OperatorAlert): erreurs métier compréhensibles par le livreur
 * - endRecoveryOrExplain(): tente END même si state local IDLE, et explique l'échec
 *   en mettant FLOW_ACTIVE en évidence si bloqué.
 */
public class DeliveryController {

    // ------------------------- Champs LCR (métier) -------------------------
    private static final int FIELD_PRODUCT_NUMBER = 0;   // ProductNumber (LIST+0)
    private static final int FIELD_NET_PRESET = 6;       // NetPreset (VOLUME/LV)
    private static final int FIELD_DECIMALS = 39;        // Decimals
    private static final int FIELD_GROSS_COUNT = 44;     // GrossCount (VOLUME/LV)
    private static final int FIELD_NET_COUNT = 45;       // NetCount (VOLUME/LV)
    private static final int FIELD_CLEAR_SHIFT = 16;     // ClearShift
    private static final int FIELD_TICKET_NUMBER = 23;   // TicketNumber (LONG/SL)
    private static final int FIELD_TICKET_REQUIRED = 37; // TicketRequired (0/1/2)

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

        // ✅ Nouveau: alerte "terrain" compréhensible pour le livreur
        void onOperatorAlert(OperatorAlert alert);
    }

    public static final class DeliveryProgress {
        public long tSinceStartMs;
        public double grossL; // #44
        public double netL;   // #45
        public double deliveredGrossL;
        public double deliveredNetL;
        public double dGrossL;
        public double dNetL;
        public boolean flowActive;
        public boolean stalled;
        public int ds;
        public int dc;
    }

    // ============================= OPERATOR ALERTS (terrain) =============================
    public enum OperatorIssueCode {
        RECOVERY_END_TIMEOUT,
        RECOVERY_FLOW_STUCK_ACTIVE,
        PRINTER_NOT_READY,
        PRINT_TIMEOUT_TICKET_PENDING,
        PRINT_FORBIDDEN_DELIVERY_ACTIVE,
        IO_OR_PROTOCOL_ERROR
    }

    public static final class OperatorAlert {
        public final OperatorIssueCode code;
        public final String title;
        public final String message;
        public final String diagnostics;
        public final boolean blocking;

        public OperatorAlert(OperatorIssueCode code, String title, String message, String diagnostics, boolean blocking) {
            this.code = code;
            this.title = title;
            this.message = message;
            this.diagnostics = diagnostics;
            this.blocking = blocking;
        }
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

    private void emitOperatorAlert(OperatorIssueCode code, String title, String message, String diagnostics, boolean blocking) {
        log("[ALERT] " + code + " - " + title);
        if (events != null) events.onOperatorAlert(new OperatorAlert(code, title, message, diagnostics, blocking));
    }

    private String diagDsDc(int ds, int dc) {
        boolean tp = (dc & LcpLink.LCRSc_DEL_TICKET_PENDING) != 0;
        boolean fa = (dc & LcpLink.LCRSc_FLOW_ACTIVE) != 0;
        boolean da = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;
        boolean begin = (dc & LcpLink.LCRSc_BEGIN_DELIVERY) != 0;
        return "DS=" + String.format("0x%04X", ds) +
                " DC=" + String.format("0x%04X", dc) +
                " [TICKET_PENDING=" + tp +
                " FLOW_ACTIVE=" + (fa ? "1" : "0") +
                " DELIVERY_ACTIVE=" + (da ? "1" : "0") +
                " BEGIN_DELIVERY=" + begin + "]";
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
        boolean flow = (dc & LcpLink.LCRSc_FLOW_ACTIVE) != 0;
        boolean active = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;
        boolean begin = (dc & LcpLink.LCRSc_BEGIN_DELIVERY) != 0;
        return String.format("[ticketPending=%s flow=%s active=%s begin=%s]", pending, flow, active, begin);
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
                (byte)((v >> 8) & 0xFF),
                (byte)( v & 0xFF)
        };
    }

    private static double decodeVolume(byte[] b4, int decimalsIndex) {
        if (b4 == null || b4.length < 4) return 0.0;
        int v = ((b4[0] & 0xFF) << 24)
                | ((b4[1] & 0xFF) << 16)
                | ((b4[2] & 0xFF) << 8)
                | (b4[3] & 0xFF);
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
        return ((b4[0] & 0xFF) << 24)
                | ((b4[1] & 0xFF) << 16)
                | ((b4[2] & 0xFF) << 8)
                | (b4[3] & 0xFF);
    }

    private int readTicketNumber() throws Exception {
        return s32be(link.opGetField(FIELD_TICKET_NUMBER)); // #23
    }

    private int readTicketRequired() throws Exception {
        byte[] raw = link.opGetField(FIELD_TICKET_REQUIRED); // #37
        return (raw != null && raw.length > 0) ? (raw[0] & 0xFF) : 0;
    }

    // ============================= PRINTER STATUS =============================
    /** Rafraîchit le statut imprimante détaillé (0x23) + ticketPending. */
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

    // ============================= PRINT PENDING (#6) =============================
    public void printPendingTicket(int pollMs, int timeoutMs) {
        exec.execute(() -> safeOp(() -> {
            try {
                link.setPythonCompat(true, pollMs);

                int[] dsdc0 = readDsDcFast();
                int dc0 = dsdc0[1];
                boolean active = (dc0 & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;

                if (active) {
                    // message terrain optionnel
                    emitOperatorAlert(
                            OperatorIssueCode.PRINT_FORBIDDEN_DELIVERY_ACTIVE,
                            "Impression interdite",
                            "Impossible d'imprimer: la livraison est encore active.\nTerminer la livraison (Cmd #2) avant d'imprimer.",
                            diagDsDc(dsdc0[0], dc0),
                            true
                    );
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

                // Vérifier si ticket encore pending
                int[] dsdcEnd = readDsDcFast();
                boolean stillPending = (dsdcEnd[1] & LcpLink.LCRSc_DEL_TICKET_PENDING) != 0;
                if (stillPending) {
                    emitOperatorAlert(
                            OperatorIssueCode.PRINT_TIMEOUT_TICKET_PENDING,
                            "Ticket toujours en attente",
                            "Le ticket n'a pas été imprimé.\nVérifie l'imprimante (papier/connexion) puis réessaie.",
                            diagDsDc(dsdcEnd[0], dsdcEnd[1]),
                            true
                    );
                }

                logTimeline("PRINTPEND:AFTER", pollMs);
                refreshPrinterStatus(pollMs);

            } catch (Exception e) {
                if (events != null) events.onError("printPendingTicket", e);
                emitOperatorAlert(
                        OperatorIssueCode.IO_OR_PROTOCOL_ERROR,
                        "Erreur impression",
                        "Erreur de communication lors de l'impression. Vérifie USB/RS-232 et réessaie.",
                        "Exception=" + e.getMessage(),
                        true
                );
            }
        }, "printPendingTicket"));
    }

    // ============================= PRE-START =============================
    public void prestartSequence(int product, double presetLitres, int pollMs) throws Exception {
        setState(State.PRESTART);
        logTimeline("PRESTART:ENTER", pollMs);

        refreshPrinterStatus(pollMs);

        if (!startGateAllow(pollMs)) {
            setState(State.IDLE);
            throw new Exception("START-GATE blocked (ticketPending=true)");
        }

        link.setPythonCompat(true, pollMs);

        int list0 = productToList0Value(product);
        log("[PRE] Setting active product (Field #0) product=" + product + " (list0=" + list0 + ")");
        link.opSetField(FIELD_PRODUCT_NUMBER, new byte[]{ (byte)list0 });
        this.presetProduct = product;

        logTimeline("PRESTART:AFTER_PRODUCT", pollMs);

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
                Thread.sleep(Math.max(50, pollMs));
            }
            return false;
        });

        if (!active) throw new Exception("Delivery not active after RUN");

        startTimestampMs = System.currentTimeMillis();
        startGross = readGrossLitres();
        startNet = readNetLitres();
        lastGross = startGross;
        lastNet = startNet;
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

                    boolean flow = (dc & LcpLink.LCRSc_FLOW_ACTIVE) != 0;
                    boolean active = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;

                    if (!active) {
                        stopping = true;
                        return;
                    }

                    double gross = lastGross;
                    double net = lastNet;
                    try { gross = readGrossLitres(); } catch (Exception ex) { log("[LIVE] WARN gross read failed: " + ex.getMessage()); }
                    try { net = readNetLitres(); } catch (Exception ex) { log("[LIVE] WARN net read failed: " + ex.getMessage()); }

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

    // ============================= END SEQUENCE (#2) =============================
    public void endDeliverySequence(int timeoutMs, int pollMs) throws Exception {
        logTimeline("END:ENTER", pollMs);

        refreshPrinterStatus(pollMs);

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

        withPollWindow(() -> {
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                int[] dsdc = link.opDeliveryStatus();
                int ds = dsdc[0];
                int dc = dsdc[1];

                boolean active = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;
                if (!active) break;

                Thread.sleep(pollMs);
                logDsDc("END:STATE", ds, dc);
            }
            return null;
        });

        logTimeline("END:INACTIVE", pollMs);

        refreshPrinterStatus(pollMs);

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

            try { prestartSequence(product, presetLitres, pollMs); }
            catch (Exception ex) { if(events!=null) events.onError("prestartSequence", ex); return; }

            try { startDeliverySequence(pollMs); }
            catch (Exception ex) { if(events!=null) events.onError("startDeliverySequence", ex); return; }

            startLiveLoop(pollMs);

        }, "startOpenMode"));
    }

    public void startOpenMode(int product, int timeoutMs, int pollMs){
        startOpenMode(product, 0.0, timeoutMs, pollMs);
    }

    /**
     * ✅ Ajusté: ne pas ignorer IDLE si le compteur est actif (recovery).
     * - Si compteur inactif ET déjà ENDED -> ignorer
     * - Sinon -> tenter endDeliverySequence
     */
    public void endGracefully(int timeoutMs, int pollMs){
        exec.execute(() -> safeOp(() -> {
            try {
                link.setPythonCompat(true, pollMs);

                // Lire l'état réel du compteur (0x28)
                int[] dsdc = readDsDcFast();
                int ds = dsdc[0], dc = dsdc[1];
                boolean da = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;

                if (!da && state == State.ENDED) {
                    log("[END] ignored: meter inactive + already ENDED. " + diagDsDc(ds, dc));
                    return;
                }

                // Sinon on tente l'END (même si state=IDLE)
                endDeliverySequence(timeoutMs, pollMs);

            } catch (Exception ex) {
                if(events!=null) events.onError("endGracefully", ex);
            }
        }, "endGracefully"));
    }

    /**
     * ✅ Reprendre une livraison (Cmd #0) et remettre RUNNING/loop si nécessaire.
     */
    public void resumeDelivery(int pollMs) {
        exec.execute(() -> safeOp(() -> {
            try {
                link.setPythonCompat(true, pollMs);

                logTimeline("RESUME:BEFORE", pollMs);

                link.opIssueCommand(0x00); // Cmd #0
                log("[RESUME] Cmd#0 sent (Start/Resume)");

                // Attendre que DA/BEGIN soient visibles
                boolean becameActive = withPollWindow(() -> {
                    long deadline = System.currentTimeMillis() + 12000;
                    while (System.currentTimeMillis() < deadline) {
                        int[] dsdc = link.opDeliveryStatus();
                        int ds = dsdc[0], dc = dsdc[1];
                        boolean da = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;
                        boolean begin = (dc & LcpLink.LCRSc_BEGIN_DELIVERY) != 0;
                        if (da || begin) {
                            logDsDc("RESUME:ACTIVE", ds, dc);
                            return true;
                        }
                        Thread.sleep(Math.max(50, pollMs));
                    }
                    return false;
                });

                if (!becameActive) {
                    log("[RESUME] WARN: delivery not active after Cmd#0 (timeout).");
                    logTimeline("RESUME:AFTER_TIMEOUT", pollMs);
                    return;
                }

                // Si on n'était pas RUNNING, réinitialiser la baseline
                if (state != State.RUNNING) {
                    setState(State.STARTING);

                    startTimestampMs = System.currentTimeMillis();
                    try { startGross = readGrossLitres(); } catch (Exception ignored) { startGross = lastGross; }
                    try { startNet   = readNetLitres();   } catch (Exception ignored) { startNet   = lastNet;   }

                    lastGross = startGross;
                    lastNet = startNet;
                    lastFlow = null;
                    stopping = false;

                    setState(State.RUNNING);
                    log("[RESUME] State -> RUNNING (baseline reset)");
                } else {
                    stopping = false;
                    log("[RESUME] Already RUNNING");
                }

                startLiveLoop(pollMs);
                logTimeline("RESUME:AFTER", pollMs);

            } catch (Exception e) {
                if (events != null) events.onError("resumeDelivery", e);
            }
        }, "resumeDelivery"));
    }

    /**
     * ✅ END Recovery avec message opérateur clair si échec.
     * Met FLOW_ACTIVE en évidence si persistant à 1.
     */
    public void endRecoveryOrExplain(int timeoutMs, int pollMs) {
        exec.execute(() -> safeOp(() -> {
            try {
                link.setPythonCompat(true, pollMs);

                int[] dsdc0 = readDsDcFast();
                int ds0 = dsdc0[0], dc0 = dsdc0[1];
                boolean da0 = (dc0 & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;

                if (!da0) {
                    log("[RECOVER-END] Meter already inactive. " + diagDsDc(ds0, dc0));
                    return;
                }

                // tenter END
                try {
                    endDeliverySequence(timeoutMs, pollMs);
                    return;
                } catch (Exception ignored) {
                    // produire une alerte terrain
                }

                // relire état final
                int[] dsdc1 = readDsDcFast();
                int ds = dsdc1[0], dc = dsdc1[1];
                boolean fa = (dc & LcpLink.LCRSc_FLOW_ACTIVE) != 0;
                boolean da = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;

                String title = "Impossible de terminer la livraison";
                String msg =
                        "Le compteur refuse de clôturer.\n\n" +
                        "État critique: FLOW_ACTIVE=" + (fa ? "1" : "0") +
                        ", DELIVERY_ACTIVE=" + (da ? "1" : "0") + ".\n\n" +
                        "Actions:\n" +
                        "1) Fermer pompe/valve (arrêter le débit).\n" +
                        "2) Vérifier qu'il n'y a plus de débit réel.\n" +
                        "3) Si aucun débit mais FLOW_ACTIVE reste à 1: capteur/pulses bloqué → support.\n\n" +
                        "Puis réessayer « Terminer ».";

                String diag = diagDsDc(ds, dc);

                emitOperatorAlert(
                        fa ? OperatorIssueCode.RECOVERY_FLOW_STUCK_ACTIVE : OperatorIssueCode.RECOVERY_END_TIMEOUT,
                        title,
                        msg,
                        diag,
                        true
                );

            } catch (Exception e) {
                emitOperatorAlert(
                        OperatorIssueCode.IO_OR_PROTOCOL_ERROR,
                        "Erreur communication",
                        "Impossible de lire l'état du compteur. Vérifie USB/RS-232 et réessaie.",
                        "Exception=" + e.getMessage(),
                        true
                );
            }
        }, "endRecoveryOrExplain"));
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
