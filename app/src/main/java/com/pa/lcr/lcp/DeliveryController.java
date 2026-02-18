
package com.pa.lcr.lcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

import com.pa.lcr.lcp.lifecycle.Cmd0Usage;
import com.pa.lcr.lcp.lifecycle.DeliveryLifecycle;
import com.pa.lcr.lcp.lifecycle.DeliveryLifecycleController;
import com.pa.lcr.lcp.lifecycle.LcpDeliveryState;
import com.pa.lcr.lcp.util.AndroidLifecycleLogger;

public class DeliveryController {

    // ------------------------- Champs LCR (métier) -------------------------
    private static final int FIELD_PRODUCT_NUMBER = 0;   // ProductNumber (LIST+0)
    private static final int FIELD_NET_PRESET = 6;       // NetPreset (VOLUME/LV)
    private static final int FIELD_DECIMALS = 39;        // Decimals
    private static final int FIELD_GROSS_COUNT = 44;     // GrossCount (VOLUME/LV) [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/Android%20SDK%20Documentation-b0.14.pdf)
    private static final int FIELD_NET_COUNT = 45;       // NetCount (VOLUME/LV) [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/Android%20SDK%20Documentation-b0.14.pdf)
    private static final int FIELD_CLEAR_SHIFT = 16;     // ClearShift
    private static final int FIELD_TICKET_NUMBER = 23;   // TicketNumber
    private static final int FIELD_TICKET_REQUIRED = 37; // TicketRequired
    private static final int FIELD_NO_FLOW_TIMER = 25;   // NoFlowTimer (#25) [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/Android%20SDK%20Documentation-b0.14.pdf)

    // ------------------------- Robustesse terrain -------------------------
    private static final int QUEUED_LONG_TIMEOUT_MS = 30_000; // 30s
    private static final int SETFIELD_RETRY_SLEEP_MS = 120;

    // --- Delivery Code Word bits (doc SDK) --- [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/Android%20SDK%20Documentation-b0.14.pdf)
    private static final int DC_SHIFT_TICKET_PENDING = 0x0002; // shift ticket pending
    private static final int DC_DELIVERY_STARTING    = 0x0400; // delivery is in process of being started
    private static final int DC_DELIVERY_QUEUED      = 0x0800; // delivery queued

    // --- Zombie detection (terrain) ---
    private static final long ZOMBIE_STALL_MS = 4000;     // 4s sans augmentation
    private static final long ZOMBIE_COOLDOWN_MS = 15000; // 15s entre tentatives
    private volatile long lastZombieRecoveryMs = 0;

    // UI override si flow bit reste coincé
    private volatile boolean uiForcePaused = false;
    private volatile long uiForcePausedUntilMs = 0;

    // baseline lock: préserver startGross/startNet sur recover/zombie (continuer reprend au même point)
    private volatile boolean baselineLocked = false;

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

    // ==========================================================
    // Confirmation flow stopped (basée sur NoFlowTimer #25)
    // ==========================================================
    private static final long FLOW_STOP_CONFIRM_MIN_MS = 2000; // garde-fou
    private volatile long flowStopConfirmMs = 3000;

    private volatile boolean flowStopPending = false;
    private volatile boolean flowStopNotified = false;
    private volatile long flowStopSinceMs = 0;
    private volatile long lastVolumeChangeMs = 0;
    private volatile double lastStableGross = 0;
    private volatile double lastStableNet = 0;

    // ==========================================================
    // Garde-fou DeliveryLifecycle
    // ==========================================================
    private final DeliveryLifecycleController lifecycle =
            new DeliveryLifecycleController(new AndroidLifecycleLogger());

    // ============================= EVENTS =============================
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
        void onOperatorAlert(OperatorAlert alert);
    }

    public static final class DeliveryProgress {
        public long tSinceStartMs;
        public double grossL;
        public double netL;
        public double deliveredGrossL;
        public double deliveredNetL;
        public double dGrossL;
        public double dNetL;
        public boolean flowActive;
        public boolean stalled;
        public int ds;
        public int dc;

        // Etat "officiel" pour l'UI (ACTIVE_FLOWING / ACTIVE_PAUSED / etc.) [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/Android%20SDK%20Documentation-b0.14.pdf)
        public LcpDeliveryState deliveryState;
    }

    // ============================= OPERATOR ALERTS (terrain) =============================
    public enum OperatorIssueCode {
        RECOVERY_END_TIMEOUT,
        RECOVERY_FLOW_STUCK_ACTIVE,
        PRINTER_NOT_READY,
        PRINT_TIMEOUT_TICKET_PENDING,
        PRINT_FORBIDDEN_DELIVERY_ACTIVE,
        IO_OR_PROTOCOL_ERROR,
        WRITE_OR_VERIFY_FAILED
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

    private int[] readDsDcFast() throws Exception {
        return withPollWindow(() -> link.opDeliveryStatus()); // [ds, dc]
    }

    private int[] readDsDcFastLong(int pollMs) throws Exception {
        long deadline = System.currentTimeMillis() + QUEUED_LONG_TIMEOUT_MS;
        Exception last = null;

        while (System.currentTimeMillis() < deadline) {
            try {
                return readDsDcFast();
            } catch (Exception e) {
                last = e;
                String m = e.getMessage();
                if (m != null && m.contains("Queued timeout (python)")) {
                    Thread.sleep(Math.max(50, pollMs));
                    continue;
                }
                Thread.sleep(Math.max(50, pollMs));
            }
        }

        if (last != null) throw last;
        throw new IOException("readDsDcFastLong: timeout");
    }

    // ---------------- DeliveryState (doc SDK) ---------------- [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/Android%20SDK%20Documentation-b0.14.pdf)

    public static LcpDeliveryState computeDeliveryStateFromDc(int dc) {
        boolean ticketPending  = (dc & LcpLink.LCRSc_DEL_TICKET_PENDING) != 0; // 0x0001 [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/Android%20SDK%20Documentation-b0.14.pdf)
        boolean shiftPending   = (dc & DC_SHIFT_TICKET_PENDING) != 0;         // 0x0002 [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/Android%20SDK%20Documentation-b0.14.pdf)
        boolean flowActive     = (dc & LcpLink.LCRSc_FLOW_ACTIVE) != 0;       // 0x0004 [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/Android%20SDK%20Documentation-b0.14.pdf)
        boolean deliveryActive = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;   // 0x0008 [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/Android%20SDK%20Documentation-b0.14.pdf)

        if (deliveryActive) return flowActive ? LcpDeliveryState.ACTIVE_FLOWING : LcpDeliveryState.ACTIVE_PAUSED;
        if (ticketPending)  return LcpDeliveryState.PENDING_TICKET;
        if (shiftPending)   return LcpDeliveryState.PENDING_SHIFT;
        return LcpDeliveryState.IDLE;
    }

    private LcpDeliveryState computeDeliveryStateForUi(int dc) {
        boolean ticketPending  = (dc & LcpLink.LCRSc_DEL_TICKET_PENDING) != 0;
        boolean shiftPending   = (dc & DC_SHIFT_TICKET_PENDING) != 0;
        boolean flowActive     = (dc & LcpLink.LCRSc_FLOW_ACTIVE) != 0;
        boolean deliveryActive = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;

        // expire override
        if (uiForcePaused && System.currentTimeMillis() > uiForcePausedUntilMs) {
            uiForcePaused = false;
        }

        if (deliveryActive) {
            if (!flowActive || uiForcePaused) return LcpDeliveryState.ACTIVE_PAUSED;
            return LcpDeliveryState.ACTIVE_FLOWING;
        }
        if (ticketPending) return LcpDeliveryState.PENDING_TICKET;
        if (shiftPending)  return LcpDeliveryState.PENDING_SHIFT;
        return LcpDeliveryState.IDLE;
    }

    private static boolean dcIndicatesStartAccepted(int dc) {
        boolean deliveryActive = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;
        boolean starting       = (dc & DC_DELIVERY_STARTING) != 0; // [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/Android%20SDK%20Documentation-b0.14.pdf)
        boolean queued         = (dc & DC_DELIVERY_QUEUED) != 0;    // [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/Android%20SDK%20Documentation-b0.14.pdf)
        return deliveryActive || starting || queued;
    }

    private int[] safeReadDsDcAfterRecovery(int pollMs) throws Exception {
        try { link.forceSyncNext(); } catch (Exception ignored) {}
        try { link.requestPurge();  } catch (Exception ignored) {}
        try { Thread.sleep(Math.max(50, SETFIELD_RETRY_SLEEP_MS)); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        return readDsDcFastLong(pollMs);
    }

    private void rollbackStartToIdle(String why) {
        log("[START] ROLLBACK -> IDLE : " + why);
        lifecycle.forceIdle("Start failed: " + why);
        stopping = true;
        if (liveLoopFuture != null) {
            try { liveLoopFuture.cancel(true); } catch (Exception ignored) {}
            liveLoopFuture = null;
        }
        setState(State.IDLE);
    }

    // ---------------- Field read/encode helpers ----------------

    private int getDecimals() throws Exception {
        if (cachedDecimals >= 0) return cachedDecimals;

        Exception last = null;
        for (int i = 1; i <= 5; i++) {
            try {
                byte[] d = link.opGetField(FIELD_DECIMALS); // #39
                cachedDecimals = (d != null && d.length >= 1) ? (d[0] & 0xFF) : 0;
                return cachedDecimals;
            } catch (Exception e) {
                last = e;
                log("[DECIMALS] WARN read #39 failed (" + i + "/5): " + e.getMessage());
                try {
                    link.forceSyncNext();
                    if (i >= 3) link.requestPurge();
                } catch (Exception ignored) {}
                try { Thread.sleep(SETFIELD_RETRY_SLEEP_MS); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }

        if (last != null) throw last;
        throw new IOException("getDecimals: failed");
    }

    private int getNoFlowTimerSecondsSafe(int pollMs) {
        try {
            link.setPythonCompat(true, pollMs);
            byte[] raw = link.opGetField(FIELD_NO_FLOW_TIMER); // #25 [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/Android%20SDK%20Documentation-b0.14.pdf)
            if (raw != null && raw.length >= 1) return (raw[0] & 0xFF);
        } catch (Exception ignored) {}
        return -1;
    }

    private static int scaleForDecimalsIndex(int decimalsIndex) {
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
        return decodeVolume(link.opGetField(FIELD_GROSS_COUNT), dec); // #44 [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/Android%20SDK%20Documentation-b0.14.pdf)
    }

    private double readNetLitres() throws Exception {
        int dec = getDecimals();
        return decodeVolume(link.opGetField(FIELD_NET_COUNT), dec); // #45 [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/Android%20SDK%20Documentation-b0.14.pdf)
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

    private static boolean isFramingTimeoutMsg(String msg) {
        if (msg == null) return false;
        return msg.contains("Timeout sync ~~")
                || msg.contains("Header timeout")
                || msg.contains("Payload timeout")
                || msg.contains("CRC timeout");
    }

    private static boolean isFramingTimeoutException(Throwable t) {
        if (t == null) return false;
        String m = t.getMessage();
        if (m != null && (m.startsWith("Framing timeout") || isFramingTimeoutMsg(m))) return true;
        Throwable c = t.getCause();
        if (c != null) {
            String cm = c.getMessage();
            if (cm != null && isFramingTimeoutMsg(cm)) return true;
        }
        return false;
    }

    private void safeSetFieldWithRetry(int field, byte[] data, int pollMs, int verifyLen) throws Exception {
        try {
            link.opSetField(field, data);
        } catch (IOException e) {
            if (!isFramingTimeoutException(e)) throw e;

            log("[SAFE-SET] Framing timeout on SET_FIELD #" + field + " -> recovery+retry once. msg=" + e.getMessage());
            link.forceSyncNext();
            link.requestPurge();
            Thread.sleep(SETFIELD_RETRY_SLEEP_MS);
            link.opSetField(field, data);
        }

        byte[] rb = link.opGetField(field);
        if (rb == null) throw new IOException("SET_FIELD verify failed: null");

        int n = Math.min(Math.min(verifyLen, data.length), rb.length);
        if (n <= 0) throw new IOException("SET_FIELD verify failed: empty");

        for (int i = 0; i < n; i++) {
            if (rb[i] != data[i]) {
                throw new IOException("SET_FIELD verify mismatch at i=" + i);
            }
        }
    }

    // ============================
    // ✅ Recovery auto (connect)
    // ============================
    public void recoverActiveDelivery(int pollMs) {
        exec.execute(() -> safeOp(() -> {
            try {
                link.setPythonCompat(true, pollMs);

                int[] dsdc = readDsDcFastLong(pollMs);
                int ds = dsdc[0], dc = dsdc[1];
                LcpDeliveryState st = computeDeliveryStateFromDc(dc);

                logDsDc("RECOVER:DSDC", ds, dc);
                log("[RECOVER] deliveryState=" + st);

                if (st != LcpDeliveryState.ACTIVE_FLOWING && st != LcpDeliveryState.ACTIVE_PAUSED) {
                    return;
                }

                // Baseline lock: on veut poursuivre la même livraison sans reset
                baselineLocked = true;

                // #44/#45 sont des quantités de livraison courante => baseline=0 pour afficher "déjà livré" = valeur courante [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/Android%20SDK%20Documentation-b0.14.pdf)
                startTimestampMs = System.currentTimeMillis();
                startGross = 0.0;
                startNet = 0.0;

                double gross = readGrossLitres();
                double net = readNetLitres();
                lastGross = gross;
                lastNet = net;

                long now = System.currentTimeMillis();
                lastStableGross = gross;
                lastStableNet = net;
                lastVolumeChangeMs = now;

                flowStopPending = false;
                flowStopNotified = false;
                flowStopSinceMs = 0;

                // Forcer les états applicatifs sans envoyer de commande
                lifecycle.forceState(DeliveryLifecycle.ACTIVE, "Recovered active delivery: " + st);

                stopping = false;
                setState(State.RUNNING);

                startLiveLoop(pollMs);

            } catch (Exception e) {
                if (events != null) events.onError("recoverActiveDelivery", e);
            }
        }, "recoverActiveDelivery"));
    }

    // ============================
    // Zombie soft recovery
    // ============================
    private void softRecoverZombie(int pollMs, int ds, int dc) {
        long now = System.currentTimeMillis();
        if (now - lastZombieRecoveryMs < ZOMBIE_COOLDOWN_MS) return;
        lastZombieRecoveryMs = now;

        log("[ZOMBIE] Detected: ACTIVE_FLOWING but volumes stalled >= " + ZOMBIE_STALL_MS + "ms. " + diagDsDc(ds, dc));
        baselineLocked = true;

        try {
            link.setPythonCompat(true, pollMs);

            // Command #1: PauseDelivery (doc SDK) [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/Android%20SDK%20Documentation-b0.14.pdf)
            link.opIssueCommand(0x01);

            long deadline = now + 3000;
            boolean paused = false;

            while (System.currentTimeMillis() < deadline) {
                int[] dsdc = readDsDcFast();
                int dc2 = dsdc[1];

                boolean deliveryActive = (dc2 & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;
                boolean flowActive = (dc2 & LcpLink.LCRSc_FLOW_ACTIVE) != 0;

                if (deliveryActive && !flowActive) {
                    paused = true;
                    break;
                }
                Thread.sleep(Math.max(50, pollMs));
            }

            if (paused) {
                log("[ZOMBIE] Soft recovery OK: flow is now inactive => ACTIVE_PAUSED.");
                uiForcePaused = false;
                uiForcePausedUntilMs = 0;

                // permettre RESUME côté guard
                lifecycle.onPauseDetected();
            } else {
                log("[ZOMBIE] Pause request did not clear FLOW bit -> forcing UI PAUSED override (10s).");
                uiForcePaused = true;
                uiForcePausedUntilMs = System.currentTimeMillis() + 10000;

                // permettre RESUME côté guard (UI pourra tenter Continue)
                lifecycle.forceState(DeliveryLifecycle.PAUSED, "Zombie override PAUSED");
            }
        } catch (Exception e) {
            log("[ZOMBIE] Soft recovery failed: " + e.getMessage() + " -> forcing UI PAUSED override (10s).");
            uiForcePaused = true;
            uiForcePausedUntilMs = System.currentTimeMillis() + 10000;
            lifecycle.forceState(DeliveryLifecycle.PAUSED, "Zombie override PAUSED (exception)");
        }
    }

    // =============================
    // Public: Printer/Ticket APIs
    // =============================

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

    public void setTicketRequired(int mode, int pollMs) {
        exec.execute(() -> safeOp(() -> {
            try {
                if (mode < 0 || mode > 2) throw new IllegalArgumentException("TicketRequired must be 0..2");
                link.setPythonCompat(true, pollMs);

                int before = readTicketRequired();
                try { link.opSetField(FIELD_TICKET_REQUIRED, new byte[]{ (byte)mode }); }
                catch (IOException ex) { log("[TICKET] SET #37 refused/failed. " + ex.getMessage()); }

                int after = readTicketRequired();
                log("[TICKET] TicketRequired(#37) now=" + after + " (requested=" + mode + ", before=" + before + ")");
                if (events != null) events.onTicketRequired(after);

            } catch (Exception e) {
                if (events != null) events.onError("setTicketRequired", e);
            }
        }, "setTicketRequired"));
    }

    public void ensureDefaultTicketRequiredIs1(int pollMs) {
        exec.execute(() -> safeOp(() -> {
            try {
                link.setPythonCompat(true, pollMs);
                int tr = readTicketRequired();
                if (tr != 1) {
                    log("[TICKET] TicketRequired(#37) read=" + tr + " -> setting to 1 (default optional)");
                    try { link.opSetField(FIELD_TICKET_REQUIRED, new byte[]{ (byte)1 }); }
                    catch (IOException ex) { log("[TICKET] SET #37 refused/failed. " + ex.getMessage()); }
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

    public void pingStatus(int pollMs){
        exec.execute(() -> safeOp(() -> {
            try {
                link.setPythonCompat(true, pollMs);
                log("[PING] Request MachineStatusEx");
                LcpLink.MachineStatusEx ms = withPollWindow(() -> link.opMachineStatusEx());
                log("[PING] " + ms.toString());
                boolean pending = (ms.delCode & LcpLink.LCRSc_DEL_TICKET_PENDING) != 0;
                if (events != null) events.onPrinterStatus(ms, pending);
            } catch (Exception e) {
                if (events != null) events.onError("pingStatus", e);
            }
        }, "pingStatus"));
    }

    public void printPendingTicket(int pollMs, int timeoutMs) {
        exec.execute(() -> safeOp(() -> {
            try {
                link.setPythonCompat(true, pollMs);

                int[] dsdc0 = readDsDcFastLong(pollMs);
                int ds0 = dsdc0[0], dc0 = dsdc0[1];
                boolean active = (dc0 & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;

                if (active) {
                    emitOperatorAlert(
                            OperatorIssueCode.PRINT_FORBIDDEN_DELIVERY_ACTIVE,
                            "Impression interdite",
                            "Impossible d'imprimer: la livraison est encore active.\nTerminer la livraison avant d'imprimer.",
                            diagDsDc(ds0, dc0),
                            true
                    );
                    log("[PRINT-PENDING] Refus: delivery active -> cmd#6 won't print while active.");
                    return;
                }

                log("[PRINT-PENDING] Issue Command #6");
                link.opIssueCommand(0x06);

                long deadline = System.currentTimeMillis() + timeoutMs;
                boolean lastPending = true;

                while (System.currentTimeMillis() < deadline) {
                    int[] dsdc;
                    try { dsdc = readDsDcFast(); }
                    catch (Exception e) { Thread.sleep(Math.max(50, pollMs)); continue; }

                    int ds = dsdc[0], dc = dsdc[1];
                    boolean pending = (dc & LcpLink.LCRSc_DEL_TICKET_PENDING) != 0;

                    if (pending != lastPending) {
                        logDsDc("PRINTPEND:STATE", ds, dc);
                        lastPending = pending;
                    }

                    if (!pending) break;
                    Thread.sleep(pollMs);
                }

                int[] dsdcEnd = readDsDcFastLong(pollMs);
                boolean stillPending = (dsdcEnd[1] & LcpLink.LCRSc_DEL_TICKET_PENDING) != 0;
                if (stillPending) {
                    emitOperatorAlert(
                            OperatorIssueCode.PRINT_TIMEOUT_TICKET_PENDING,
                            "Ticket toujours en attente",
                            "Le ticket n'a pas été imprimé.\nVérifie l'imprimante puis réessaie.",
                            diagDsDc(dsdcEnd[0], dsdcEnd[1]),
                            true
                    );
                }

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

    // ----------------------------- Start / Prestart -----------------------------

    private static int productToList0Value(int product) {
        if (product < 1 || product > 16) throw new IllegalArgumentException("product must be 1..16");
        return product - 1;
    }

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

    public void prestartSequence(int product, double presetLitres, int pollMs) throws Exception {
        setState(State.PRESTART);
        refreshPrinterStatus(pollMs);

        if (!startGateAllow(pollMs)) {
            setState(State.IDLE);
            throw new Exception("START-GATE blocked (ticketPending=true)");
        }

        link.setPythonCompat(true, pollMs);

        int list0 = productToList0Value(product);
        log("[PRE] Setting active product (Field #0) product=" + product + " (list0=" + list0 + ")");
        safeSetFieldWithRetry(FIELD_PRODUCT_NUMBER, new byte[]{ (byte)list0 }, pollMs, 1);
        this.presetProduct = product;

        this.presetLitres = presetLitres;
        if (presetLitres > 0.0) {
            int dec = getDecimals();
            log("[PRE] Setting NET preset (Field #6) value=" + presetLitres + " (decimalsIndex=" + dec + ")");
            safeSetFieldWithRetry(FIELD_NET_PRESET, encodeVolume(presetLitres, dec), pollMs, 4);
        }

        log("[PRE] Completed PRE-START");
    }

    // ----------------------------- START sequence (safe retry) -----------------------------

    public void startDeliverySequence(int pollMs) throws Exception {

        if (!lifecycle.allowCmd0(Cmd0Usage.START)) {
            throw new IllegalStateException("Cmd#0 START blocked by DeliveryLifecycle state=" + lifecycle.getState());
        }

        setState(State.STARTING);

        boolean sentOrAccepted = false;
        Exception lastSendError = null;

        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                log("[START] RUN (Command #0) attempt=" + attempt);
                link.opIssueCommand(0x00);
                sentOrAccepted = true;
                break;
            } catch (Exception e) {
                lastSendError = e;

                if (isFramingTimeoutException(e)) {
                    log("[START] WARN: framing timeout on Cmd#0 attempt=" + attempt + " -> verify DS/DC; msg=" + e.getMessage());

                    int[] dsdc = safeReadDsDcAfterRecovery(pollMs);
                    int ds = dsdc[0], dc = dsdc[1];
                    logDsDc("START:VERIFY", ds, dc);

                    if (dcIndicatesStartAccepted(dc)) {
                        log("[START] VERIFY OK: register indicates start accepted (active/starting/queued).");
                        sentOrAccepted = true;
                        break;
                    } else {
                        log("[START] VERIFY NO: register shows no start accepted; will retry once.");
                        continue;
                    }
                }

                throw e;
            }
        }

        if (!sentOrAccepted) {
            rollbackStartToIdle("Cmd#0 not accepted after retries; last=" + (lastSendError != null ? lastSendError.getMessage() : "null"));
            throw new IOException("Start Cmd#0 failed (not accepted).", lastSendError);
        }

        boolean active = withPollWindow(() -> {
            long deadline = System.currentTimeMillis() + 12000;
            while (System.currentTimeMillis() < deadline) {
                int[] dsdc = link.opDeliveryStatus();
                int ds = dsdc[0];
                int dc = dsdc[1];

                boolean isActive = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;
                boolean isQueuedOrStarting = ((dc & DC_DELIVERY_STARTING) != 0) || ((dc & DC_DELIVERY_QUEUED) != 0);

                if (isActive) {
                    logDsDc("START:ACTIVE", ds, dc);
                    return true;
                }
                if (isQueuedOrStarting) {
                    logDsDc("START:QUEUED/STARTING", ds, dc);
                }

                Thread.sleep(Math.max(50, pollMs));
            }
            return false;
        });

        if (!active) {
            rollbackStartToIdle("Delivery not active after RUN (timeout)");
            throw new Exception("Delivery not active after RUN");
        }

        // start baseline (delivery from zero)
        baselineLocked = false;
        uiForcePaused = false;

        lifecycle.onStartConfirmed(true);

        startTimestampMs = System.currentTimeMillis();
        startGross = readGrossLitres();
        startNet = readNetLitres();
        lastGross = startGross;
        lastNet = startNet;

        long now = System.currentTimeMillis();
        lastStableGross = startGross;
        lastStableNet = startNet;
        lastVolumeChangeMs = now;

        flowStopPending = false;
        flowStopNotified = false;
        flowStopSinceMs = 0;

        lastFlow = null;
        stopping = false;

        setState(State.RUNNING);
        log("[START] Delivery ACTIVE");
    }

    // ----------------------------- LIVE LOOP (with zombie detection) -----------------------------

    private double readVolumeQueuedAware(int field, int pollMs) throws Exception {
        long longDeadline = System.currentTimeMillis() + 30_000;
        Exception last = null;

        while (System.currentTimeMillis() < longDeadline) {
            try {
                int dec = getDecimals();
                byte[] raw = link.opGetField(field);
                return decodeVolume(raw, dec);
            } catch (Exception e) {
                last = e;

                String m = (e.getMessage() == null) ? "" : e.getMessage();
                boolean transientOrQueued =
                        m.contains("Queued timeout") ||
                        m.contains("POLL_BLOCKED") ||
                        m.contains("Timeout sync") ||
                        m.contains("Header timeout") ||
                        m.contains("Payload timeout") ||
                        m.contains("CRC timeout");

                if (!transientOrQueued) throw e;

                long sleep = Math.max(200, pollMs);
                try { Thread.sleep(sleep); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw ie; }
            }
        }

        if (last != null) throw last;
        throw new IOException("readVolumeQueuedAware: timeout");
    }

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

            int nft = getNoFlowTimerSecondsSafe(pollMs);
            if (nft >= 0) {
                flowStopConfirmMs = Math.max(FLOW_STOP_CONFIRM_MIN_MS, (long)nft * 1000L);
                log("[LIVE] FlowStopConfirm = NoFlowTimer(#25)=" + nft + "s => " + flowStopConfirmMs + "ms");
            } else {
                log("[LIVE] FlowStopConfirm = default " + flowStopConfirmMs + "ms (NoFlowTimer #25 unread)");
            }

            log("[LIVE] Starting live loop");

            liveLoopFuture = scheduler.scheduleAtFixedRate(() -> {
                try {
                    if (stopping || state != State.RUNNING) return;

                    int ds, dc;
                    try {
                        int[] dsdc = withPollWindow(() -> link.opDeliveryStatus());
                        ds = dsdc[0];
                        dc = dsdc[1];
                    } catch (Exception ex) {
                        return;
                    }

                    boolean flow = (dc & LcpLink.LCRSc_FLOW_ACTIVE) != 0;
                    boolean active = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;

                    if (!active) {
                        stopping = true;
                        flowStopPending = false;
                        flowStopNotified = false;
                        flowStopSinceMs = 0;
                        return;
                    }

                    double gross = lastGross;
                    double net = lastNet;

                    try { gross = readVolumeQueuedAware(FIELD_GROSS_COUNT, pollMs); } catch (Exception ignored) {}
                    try { net   = readVolumeQueuedAware(FIELD_NET_COUNT, pollMs); } catch (Exception ignored) {}

                    long now = System.currentTimeMillis();

                    boolean progressed = (Math.abs(gross - lastStableGross) > 1e-9) ||
                                         (Math.abs(net   - lastStableNet)   > 1e-9);
                    if (progressed) {
                        lastVolumeChangeMs = now;
                        lastStableGross = gross;
                        lastStableNet = net;
                    }

                    // ---------------- Zombie detection ----------------
                    LcpDeliveryState uiState = computeDeliveryStateForUi(dc);
                    boolean isActiveFlowingUi = (uiState == LcpDeliveryState.ACTIVE_FLOWING);
                    boolean stalledTooLong = (now - lastVolumeChangeMs) >= ZOMBIE_STALL_MS;

                    if (isActiveFlowingUi && stalledTooLong) {
                        softRecoverZombie(pollMs, ds, dc);
                        // recalculer état UI après tentative
                        uiState = computeDeliveryStateForUi(dc);
                    }
                    // --------------------------------------------------

                    DeliveryProgress p = new DeliveryProgress();
                    p.tSinceStartMs = now - startTimestampMs;
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
                    p.deliveryState = uiState;

                    lastGross = gross;
                    lastNet = net;

                    if (events != null) events.onProgress(p);

                    // 4) Transitions flow avec confirmation #25 (pause confirmée)
                    if (lastFlow == null) {
                        lastFlow = flow;
                        flowStopPending = false;
                        flowStopNotified = false;
                        flowStopSinceMs = 0;
                    }

                    if (flow && !lastFlow) {
                        flowStopPending = false;
                        flowStopNotified = false;
                        flowStopSinceMs = 0;
                        if (events != null) events.onFlowStarted();
                    }

                    if (!flow && lastFlow) {
                        flowStopPending = true;
                        flowStopNotified = false;
                        flowStopSinceMs = now;
                    }

                    if (!flow && active && flowStopPending && !flowStopNotified) {
                        long quietMs = now - lastVolumeChangeMs;
                        long sinceMs = now - flowStopSinceMs;
                        if (quietMs >= flowStopConfirmMs && sinceMs >= flowStopConfirmMs) {
                            flowStopNotified = true;
                            flowStopPending = false;

                            lifecycle.onPauseDetected();

                            if (events != null) events.onFlowStopped();
                        }
                    }

                    lastFlow = flow;

                } catch (Exception e) {
                    setState(State.ERROR);
                    if (events != null) events.onError("liveLoop", e);
                }
            }, 0, pollMs, TimeUnit.MILLISECONDS);

        }, "startLiveLoop"));
    }

    // ----------------------------- UI entry points -----------------------------

    public void startOpenMode(int product, double presetLitres, int timeoutMs, int pollMs){
        exec.execute(() -> safeOp(() -> {

            if (state == State.RUNNING || state == State.STARTING || state == State.PRESTART || state == State.ENDING) {
                log("[START] ignored: delivery already in progress state=" + state);
                return;
            }

            boolean ticketPending = false;
            try {
                int[] dsdc = readDsDcFast();
                ticketPending = (dsdc[1] & LcpLink.LCRSc_DEL_TICKET_PENDING) != 0;
            } catch (Exception ignored) {}

            if (!lifecycle.allowStart(ticketPending)) {
                log("[START] blocked by DeliveryLifecycle (ticketPending=" + ticketPending + ")");
                return;
            }

            stopping = false;
            cachedDecimals = -1;
            lastFlow = null;

            try { prestartSequence(product, presetLitres, pollMs); }
            catch (Exception ex) { if(events!=null) events.onError("prestartSequence", ex); return; }

            lifecycle.onPrestartConfirmed();

            try { startDeliverySequence(pollMs); }
            catch (Exception ex) { if(events!=null) events.onError("startDeliverySequence", ex); return; }

            startLiveLoop(pollMs);

        }, "startOpenMode"));
    }

    public void startOpenMode(int product, int timeoutMs, int pollMs){
        startOpenMode(product, 0.0, timeoutMs, pollMs);
    }

    public void endGracefully(int timeoutMs, int pollMs){
        exec.execute(() -> safeOp(() -> {
            try {
                link.setPythonCompat(true, pollMs);

                int[] dsdc = readDsDcFastLong(pollMs);
                int ds = dsdc[0], dc = dsdc[1];
                boolean da = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;

                if (!da && state == State.ENDED) {
                    log("[END] ignored: meter inactive + already ENDED. " + diagDsDc(ds, dc));
                    return;
                }

                endDeliverySequence(timeoutMs, pollMs);

            } catch (Exception ex) {
                if(events!=null) events.onError("endGracefully", ex);
            }
        }, "endGracefully"));
    }

    public void endDeliverySequence(int timeoutMs, int pollMs) throws Exception {
        refreshPrinterStatus(pollMs);

        int trBefore = readTicketRequired();
        int tnBefore = readTicketNumber();
        log(String.format("[PRINT] Policy TicketRequired(#37)=%d TicketNumber before=%d", trBefore, tnBefore));

        if (!lifecycle.allowEnd()) {
            log("[END] blocked by DeliveryLifecycle state=" + lifecycle.getState());
            return;
        }

        log("[END] Issuing END (Command #2)");

        setState(State.ENDING);
        stopping = true;

        if (liveLoopFuture != null) {
            liveLoopFuture.cancel(true);
            liveLoopFuture = null;
        }

        link.setPythonCompat(true, pollMs);
        link.opIssueCommand(0x02);

        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            int[] dsdc;
            try { dsdc = readDsDcFast(); }
            catch (Exception e) { Thread.sleep(Math.max(50, pollMs)); continue; }

            int dc = dsdc[1];
            boolean active = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;
            if (!active) break;
            Thread.sleep(pollMs);
        }

        refreshPrinterStatus(pollMs);

        int tnAfter = readTicketNumber();
        log(String.format("[PRINT] TicketNumber after=%d delta=%+d", tnAfter, (tnAfter - tnBefore)));

        // reset baseline locks after ending
        baselineLocked = false;
        uiForcePaused = false;

        lifecycle.onEndConfirmed();
        lifecycle.onResetSyncCompleted();
        setState(State.ENDED);
    }

    public void resumeDelivery(int pollMs) {
        exec.execute(() -> safeOp(() -> {
            try {
                link.setPythonCompat(true, pollMs);

                // Autoriser RESUME uniquement quand PAUSED (garde-fou)
                if (!lifecycle.allowCmd0(Cmd0Usage.RESUME)) {
                    log("[RESUME] Cmd#0 RESUME blocked by DeliveryLifecycle state=" + lifecycle.getState());
                    return;
                }
                if (!lifecycle.allowResume()) {
                    log("[RESUME] blocked by DeliveryLifecycle state=" + lifecycle.getState());
                    return;
                }

                link.opIssueCommand(0x00);
                log("[RESUME] Cmd#0 sent (Start/Resume)");

                // Attendre que delivery soit active (ou begin)
                boolean becameActive = withPollWindow(() -> {
                    long deadline = System.currentTimeMillis() + 12000;
                    while (System.currentTimeMillis() < deadline) {
                        int[] dsdc = link.opDeliveryStatus();
                        int dc = dsdc[1];
                        boolean da = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;
                        boolean begin = (dc & LcpLink.LCRSc_BEGIN_DELIVERY) != 0;
                        if (da || begin) return true;
                        Thread.sleep(Math.max(50, pollMs));
                    }
                    return false;
                });

                if (!becameActive) {
                    log("[RESUME] WARN: delivery not active after Cmd#0 (timeout).");
                    return;
                }

                lifecycle.onStartConfirmed(true);

                // Baseline: si baselineLocked => ne pas reset startGross/startNet (reprendre où c'était)
                if (baselineLocked) {
                    log("[RESUME] baselineLocked=true -> preserving startGross/startNet (no reset).");
                    try { lastGross = readGrossLitres(); } catch (Exception ignored) {}
                    try { lastNet   = readNetLitres();   } catch (Exception ignored) {}
                } else {
                    // Comportement classique: reset baseline de session
                    startTimestampMs = System.currentTimeMillis();
                    try { startGross = readGrossLitres(); } catch (Exception ignored) { startGross = lastGross; }
                    try { startNet   = readNetLitres();   } catch (Exception ignored) { startNet = lastNet; }
                    lastGross = startGross;
                    lastNet = startNet;
                }

                long now = System.currentTimeMillis();
                lastStableGross = lastGross;
                lastStableNet = lastNet;
                lastVolumeChangeMs = now;
                flowStopPending = false;
                flowStopNotified = false;
                flowStopSinceMs = 0;
                lastFlow = null;

                stopping = false;
                setState(State.RUNNING);

                startLiveLoop(pollMs);

            } catch (Exception e) {
                if (events != null) events.onError("resumeDelivery", e);
            }
        }, "resumeDelivery"));
    }

    public void endRecoveryOrExplain(int timeoutMs, int pollMs) {
        exec.execute(() -> safeOp(() -> {
            try {
                link.setPythonCompat(true, pollMs);

                int[] dsdc0 = readDsDcFastLong(pollMs);
                int ds0 = dsdc0[0], dc0 = dsdc0[1];
                boolean da0 = (dc0 & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;

                if (!da0) {
                    log("[RECOVER-END] Meter already inactive. " + diagDsDc(ds0, dc0));
                    return;
                }

                try {
                    endDeliverySequence(timeoutMs, pollMs);
                    return;
                } catch (Exception ignored) { }

                int[] dsdc1 = readDsDcFastLong(pollMs);
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
