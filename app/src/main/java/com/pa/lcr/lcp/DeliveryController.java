
package com.pa.lcr.lcp;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * DeliveryController — 2026-02-05 — LCP-safe (référence Python V2)
 * -----------------------------------------------------------------
 * Références:
 *   - "LCR API Internal Messages for LCP.pdf" (framing, RC=0x26 queue, 0x23/0x28/0x21/0x24/0x00)
 *   - "LCR Registers' Fields.xlsx" (#44/#45 compteurs, #39 digits, #0 produit courant, #5/#6 presets)
 *
 * Principes:
 *   - AUCUN sendRecv(0x23/0x28) direct → utiliser link.opMachineStatusFull() / link.opDeliveryStatus()
 *   - AUCUN 0x7D (CHECK_REQUEST) coté app → la file (RC=0x26) est gérée par LcpLink.waitQueued()
 *   - START → wake → clear ticket → recover → set product → presets → RUN → WAIT_FOR_FLOW
 *   - WAIT_FOR_FLOW → attente BLOQUANTE jusqu’à ce que FLOW_ACTIVE (=1) soit confirmé
 *                    (double confirmation + filet sécurité delta #44)
 *   - LIVE LOOP → échantillonnage 0x23 (#44/#45)
 *   - END → 0x02, attente FLOW=0 & ACTIVE=0, clear ticket si nécessaire
 *   - RESYNC → 0x00 (autorisé; 0x23/0x28 restent exclusivement via op*)
 */
public final class DeliveryController {

    public enum State { IDLE, STARTING, WAIT_FOR_FLOW, FLOW_ACTIVE, FINALIZING, ENDED, ERROR }

    private final LcpLink link;
    private final DeliveryEvents events;
    private final Executor exec;

    private volatile State state = State.IDLE;
    private volatile boolean liveRunning = false;

    // --- Paramétrage logique (peut être ajusté selon terrain) ---
    private static final int DEFAULT_STABLE_POLL_MS     = 600;    // attente entre polls pour stabiliser
    private static final int DEFAULT_STABLE_POLL_NEED   = 2;      // nb polls consécutifs "OK" pour considérer stable
    private static final int FLOW_CONFIRM_REQUIRED      = 2;      // nb confirmations consécutives de FLOW=1
    private static final int MIN_LOOP_POLL_MS           = 120;    // live loop: tempo mini si pollMs trop petit

    public DeliveryController(LcpLink link, DeliveryEvents events, Executor executor) {
        this.link   = link;
        this.events = (events != null ? events : new DeliveryEvents(){});
        // Mono-thread: pas d'overlap des appels contrôleur
        this.exec   = (executor != null ? executor : Executors.newSingleThreadExecutor());
    }

    public State getState() { return state; }
    private void setState(State s){
        state = s;
        try { events.onStateChanged(s); } catch(Exception ignored){}
    }
    private static void sleep(int ms){ try { Thread.sleep(ms); } catch(Exception ignored){} }

    /* =====================================================================
       API PUBLIC
       ===================================================================== */

    /**
     * MODE OUVERT (open) — démarre le RUN puis attend FLOW=1 avant de sortir.
     */
    public void startOpenMode(int productId, long waitFlowTimeoutMs, long pollMs) {
        exec.execute(() -> {
            try {
                setState(State.STARTING);
                preStartSequence(productId, false);

                // Issue RUN puis stabilisation + attente FLOW
                runAndWaitFlow(waitFlowTimeoutMs, pollMs);

                setState(State.FLOW_ACTIVE);
                events.onFlowStarted();

            } catch(Exception e) {
                fail("startOpenMode: " + e.getMessage(), e);
            }
        });
    }

    /**
     * MODE PRÉSET NET — écrit preset net (#6), efface preset gross (#5), puis RUN et attente FLOW=1.
     */
    public void startPresetNet(int productId, double litres, long waitFlowTimeoutMs, long pollMs) {
        exec.execute(() -> {
            try {
                setState(State.STARTING);
                preStartSequencePreset(productId, litres);

                runAndWaitFlow(waitFlowTimeoutMs, pollMs);

                setState(State.FLOW_ACTIVE);
                events.onFlowStarted();

            } catch(Exception e) {
                fail("startPresetNet: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Attendre le flow uniquement (sans RUN) — bloque jusqu'à FLOW_ACTIVE=1.
     */
    public void waitForFlowOnly(long timeoutMs, long pollMs) {
        exec.execute(() -> {
            try {
                setState(State.WAIT_FOR_FLOW);
                waitFlowStrict(timeoutMs, pollMs, true, true);
                setState(State.FLOW_ACTIVE);
                events.onFlowStarted();
            } catch(Exception e) {
                fail("waitForFlowOnly: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Fin propre: 0x02, attente FLOW=0 & ACTIVE=0, clear ticket si nécessaire.
     */
    public void endGracefully(long timeoutMs, long pollMs) {
        exec.execute(() -> {
            try {
                setState(State.FINALIZING);
                issueCommandWithRetry(0x02);     // END
                waitEnd(timeoutMs, pollMs);      // FLOW=0 & ACTIVE=0
                clearTicketIfNeeded();           // CLEAR_TICKET si ds bit0 = 1
                setState(State.ENDED);
            } catch(Exception e) {
                fail("endGracefully: " + e.getMessage(), e);
            }
        });
    }

    /**
     * Boucle "live" d'échantillonnage — informe la GUI via events.onLiveSample(...).
     */
    public void runLiveLoop(long pollMs, boolean guardEnabled, double guardMarginLitres) {
        exec.execute(() -> {
            liveRunning = true;
            try {
                liveLoopCore(pollMs, guardEnabled, guardMarginLitres);
            } catch(Exception e){
                fail("runLiveLoop: " + e.getMessage(), e);
            } finally {
                liveRunning = false;
            }
        });
    }

    public void stopLiveLoop() { liveRunning = false; }

    /* =====================================================================
       PRÉ‑START — EXACT Python V2 (LCP-safe)
       ===================================================================== */

    private void preStartSequence(int productId, boolean presetMode) throws IOException {
        wake();                       // "ping" LCR & tempo
        clearTicketIfNeeded();        // si ticket en attente: clear
        recoverIfActive();            // si delivery active: END + attente

        setProductSafe(productId);    // #0
        clearPresetsSafe();           // #5/#6 = 0

        // Fenêtre de stabilisation
        waitStablePolls(DEFAULT_STABLE_POLL_NEED, DEFAULT_STABLE_POLL_MS, 4000);
    }

    private void preStartSequencePreset(int productId, double litres) throws IOException {
        wake();
        clearTicketIfNeeded();
        recoverIfActive();

        int digits = readDigits(); // #39
        int raw = (int)Math.round(litres * Math.pow(10, digits));

        setProductSafe(productId);
        writeNetPresetSafe(raw);   // #6=raw, #5=0 (net preset)

        // Fenêtre de stabilisation
        waitStablePolls(DEFAULT_STABLE_POLL_NEED, DEFAULT_STABLE_POLL_MS, 4000);
    }

    /* =====================================================================
       RUN + WAIT_FLOW STRICT (FLOW_ACTIVE=1 confirmée)
       ===================================================================== */

    private void runAndWaitFlow(long timeoutMs, long pollMs) throws IOException {
        try {
            link.opIssueCommand(0x00);     // RUN (gap 80–120 ms appliqué par LcpLink)
            // Petite stabilisation initiale (réduit RC=0x26 très tôt)
            waitStablePolls(1, DEFAULT_STABLE_POLL_MS, 3000);

            // Passage explicite en état attente
            setState(State.WAIT_FOR_FLOW);
            waitFlowStrict(timeoutMs, pollMs, true, true);

        } catch(IOException e){
            // Tentative RESYNC puis retry
            resync(); sleep(200);
            link.opIssueCommand(0x00);
            waitStablePolls(1, DEFAULT_STABLE_POLL_MS, 3000);
            setState(State.WAIT_FOR_FLOW);
            waitFlowStrict(timeoutMs, pollMs, true, true);
        }
    }

    /**
     * Attente stricte FLOW=1 : double confirmation + filet sécurité (#44 qui augmente).
     * - acceptFlow=true → on attend FLOW_ACTIVE
     * - acceptCounts=true → filet sécurité : delta #44 détecté
     */
    private void waitFlowStrict(long timeoutMs, long pollMs,
                                boolean acceptFlow, boolean acceptCounts) throws IOException {

        long tEnd = System.currentTimeMillis() + timeoutMs;

        int g0 = acceptCounts ? safeRead32(44) : 0;   // #44 (réf doc xlsx)
        int confirm = 0;
        boolean prevFlow = false;

        while (System.currentTimeMillis() < tEnd) {

            int[] dsdc;
            try {
                dsdc = getMachineStrict();   // via opMachineStatusFull() — throttles & queue gérés en bas
            } catch(Exception e){
                sleep((int)pollMs);
                continue;
            }

            int ds = dsdc[0];
            int dc = dsdc[1];

            boolean flow   = (dc & LcpLink.LCRSc_FLOW_ACTIVE)     != 0;
            boolean active = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;
            boolean begin  = (dc & LcpLink.LCRSc_BEGIN_DELIVERY)  != 0;

            // BEGIN_DELIVERY n'est pas encore "flow"
            if (active && flow) {
                // Flow réellement actif
                return;
            }

            if (acceptFlow) {
                if (flow) confirm++; else confirm = 0;
                // Double confirmation pour éviter un faux "1" transitoire
                if (!prevFlow && confirm >= FLOW_CONFIRM_REQUIRED) return;
                prevFlow = flow;
            }

            if (acceptCounts) {
                int g = safeRead32(44);
                if (g > g0) return; // filet sécurité : volume en mouvement
            }

            sleep((int)pollMs);
        }

        throw new IOException("START_TIMEOUT: FLOW non détecté");
    }

    /* =====================================================================
       LIVE LOOP STRICT
       ===================================================================== */

    private void liveLoopCore(long pollMs, boolean guardEnabled, double guardMarginLitres)
            throws IOException {

        int digits = readDigits();
        double scale = Math.pow(10, digits);

        int p6 = safeRead32(6);   // NET preset
        int p5 = safeRead32(5);   // GROSS preset

        boolean guardNet = (p6 > 0);
        double targetL = guardNet ? (p6 / scale) :
                         (p5 > 0 ? (p5 / scale) : Double.NaN);

        int g0 = safeRead32(44);  // GROSS delivered start
        int n0 = safeRead32(45);  // NET delivered start

        boolean guardFired = false;

        while (liveRunning && state == State.FLOW_ACTIVE) {

            int[] dsdc;
            try {
                dsdc = getMachineStrict();
            } catch(Exception e){
                sleep(80);
                continue;
            }

            int ds = dsdc[0];
            int dc = dsdc[1];

            boolean flow = (dc & LcpLink.LCRSc_FLOW_ACTIVE) != 0;

            int g = safeRead32(44);
            int n = safeRead32(45);

            double gL = g / scale;
            double nL = n / scale;

            try { events.onLiveSample(ds, dc, gL, nL); } catch(Exception ignored){}

            if (!flow) {
                try { events.onFlowStopped(); } catch(Exception ignored){}
                return;
            }

            if (guardEnabled && !Double.isNaN(targetL) && !guardFired) {
                double delivered = guardNet ? ((n - n0) / scale) : ((g - g0) / scale);
                if (delivered >= targetL + guardMarginLitres) {
                    try { events.onGuardReached(); } catch(Exception ignored){}
                    try { link.opIssueCommand(0x02); } catch(Exception ignored){}
                    guardFired = true;
                }
            }

            sleep((int)Math.max(MIN_LOOP_POLL_MS, pollMs));
        }
    }

    /* =====================================================================
       END = FLOW=0 & ACTIVE=0 + CLEAR_TICKET
       ===================================================================== */

    private void waitEnd(long timeoutMs, long pollMs) throws IOException {
        long tEnd = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < tEnd) {
            try {
                int[] dsdc = getMachineStrict();
                int ds = dsdc[0];
                int dc = dsdc[1];

                boolean flow   = (dc & LcpLink.LCRSc_FLOW_ACTIVE)     != 0;
                boolean active = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE)  != 0;

                if (!flow && !active) return;

            } catch(Exception ignored){}

            sleep((int)Math.max(100, pollMs));
        }
    }

    private void clearTicketIfNeeded() throws IOException {
        try {
            int[] dsdc = getMachineStrict();
            int ds = dsdc[0];
            boolean pending = (ds & LcpLink.LCRSc_DEL_TICKET_PENDING) != 0;
            if (pending) issueCommandWithRetry(0x06); // CLEAR_TICKET
        } catch(Exception ignored){}
    }

    private void recoverIfActive() throws IOException {
        try {
            int[] dsdc = getMachineStrict();
            int dc = dsdc[1];
            boolean active = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;
            if (active) {
                issueCommandWithRetry(0x02); // END
                waitEnd(3000, 120);
            }
        } catch(Exception ignored){}
    }

    /* =====================================================================
       STABILISATION — fenêtre de polls consécutifs OK
       ===================================================================== */

    /**
     * Attendre 'needed' polls consécutifs "OK" (gérés par opMachineStatusFull), sinon timeout.
     * Utilisé après RUN, avant SET_FIELD, après RESYNC — pour éviter des rafales 0x21/0x00 qui provoquent RC=0x26.
     */
    private void waitStablePolls(int needed, long pollMs, long timeoutMs) throws IOException {
        long tEnd = System.currentTimeMillis() + timeoutMs;
        int ok = 0;
        while (System.currentTimeMillis() < tEnd && ok < needed) {
            try {
                getMachineStrict();
                ok++;
            } catch(Exception e){
                ok = 0;
            }
            sleep((int)pollMs);
        }
        if (ok < needed) throw new IOException("STABILIZE_TIMEOUT");
    }

    /* =====================================================================
       CORE STRICT : GET_MACHINE via op* (aucun 0x7D ni sendRecv direct)
       ===================================================================== */

    private int[] getMachineStrict() throws IOException {
        // opMachineStatusFull() gère 0x26/0x27 via waitQueued(), throttle 1 Hz H/L, fallback 0x28 si besoin
        int[] triple = link.opMachineStatusFull(); // { dev, ds, dc } ou {0, ds, dc}
        int ds = triple[1];
        int dc = triple[2];
        return new int[]{ ds, dc };
    }

    /* =====================================================================
       SAFE FIELD OPS (avec resync retry & tempo)
       ===================================================================== */

    private void setProductSafe(int productId) throws IOException {
        if (productId < 1 || productId > 16)
            throw new IllegalArgumentException("product 1..16");
        byte v = (byte)((productId - 1) & 0xFF);

        // Stabiliser avant et après SET_FIELD
        waitStablePolls(1, DEFAULT_STABLE_POLL_MS, 3000);
        opSetFieldSafe(0, new byte[]{v});
        waitStablePolls(1, DEFAULT_STABLE_POLL_MS, 3000);
    }

    private void clearPresetsSafe() throws IOException {
        waitStablePolls(1, DEFAULT_STABLE_POLL_MS, 3000);
        opSetFieldSafe(5, i32be(0));
        waitStablePolls(1, DEFAULT_STABLE_POLL_MS, 3000);
        opSetFieldSafe(6, i32be(0));
        waitStablePolls(1, DEFAULT_STABLE_POLL_MS, 3000);
    }

    private void writeNetPresetSafe(int raw) throws IOException {
        waitStablePolls(1, DEFAULT_STABLE_POLL_MS, 3000);
        opSetFieldSafe(6, i32be(raw));
        waitStablePolls(1, DEFAULT_STABLE_POLL_MS, 3000);
        opSetFieldSafe(5, i32be(0));
        waitStablePolls(1, DEFAULT_STABLE_POLL_MS, 3000);
    }

    private void opSetFieldSafe(int field, byte[] data) throws IOException {
        try {
            link.opSetField(field, data);
        } catch(IOException e){
            resync(); sleep(150);
            link.opSetField(field, data);
        }
        // Respirer après SET_FIELD
        sleep(120);
    }

    private void issueCommandWithRetry(int cmd) throws IOException {
        try {
            link.opIssueCommand(cmd);
        } catch(IOException e){
            resync(); sleep(150);
            link.opIssueCommand(cmd);
        }
    }

    /* =====================================================================
       RAW FIELD READS (via opGetField)
       ===================================================================== */

    private int readDigits() {
        try {
            byte[] v = link.opGetField(39);    // #39 (réf doc xlsx)
            int idx = (v != null && v.length > 0) ? (v[0] & 0xFF) : 1;
            return digitsFromIndex(idx);
        } catch(Exception e){
            return 1;
        }
    }

    private int safeRead32(int field) {
        try {
            byte[] d = link.opGetField(field);
            return i32beToInt(d);
        } catch(Exception e){
            return 0;
        }
    }

    /* =====================================================================
       RESYNC — GET_PRODUCT_ID (0x00)
       ===================================================================== */

    private void resync() {
        try {
            // Autorisé (le guard LcpLink bloque uniquement 0x23/0x28 hors op*)
            link.sendRecv(new byte[]{0x00}, 2000);
        } catch(Exception ignored){}
        // Stabiliser après RESYNC
        try { waitStablePolls(1, DEFAULT_STABLE_POLL_MS, 3000); } catch(Exception ignored){}
    }

    /* =====================================================================
       WAKE & WAKE_STABLE (via opMachineStatusFull)
       ===================================================================== */

    private void wake() {
        try { getMachineStrict(); } catch(Exception ignored){}
        sleep(120);
    }

    /* =====================================================================
       HELPERS
       ===================================================================== */

    private static int digitsFromIndex(int idx) {
        switch(idx) {
            case 0: return 2;   // mapping observé terrain (réf doc xlsx)
            case 1: return 1;
            case 2: return 0;
            case 3: return 3;
            default: return 1;
        }
    }

    private static byte[] i32be(int v) {
        return new byte[]{
                (byte)((v >> 24) & 0xFF),
                (byte)((v >> 16) & 0xFF),
                (byte)((v >> 8)  & 0xFF),
                (byte)(v & 0xFF)
        };
    }

    private static int i32beToInt(byte[] d) {
        return ((d[0] & 0xFF) << 24) |
               ((d[1] & 0xFF) << 16) |
               ((d[2] & 0xFF) << 8)  |
               (d[3] & 0xFF);
    }

    private void fail(String msg, Throwable t) {
        setState(State.ERROR);
        try { events.onError(msg, t); } catch(Exception ignored){}
    }

    /* =====================================================================
       EVENTS
       ===================================================================== */

    public interface DeliveryEvents {
        default void onStateChanged(State s) {}
        default void onFlowStarted() {}
        default void onFlowStopped() {}
        default void onLiveSample(int ds, int dc, double gL, double nL) {}
        default void onGuardReached() {}
        default void onError(String m, Throwable t) {}
    }
}
