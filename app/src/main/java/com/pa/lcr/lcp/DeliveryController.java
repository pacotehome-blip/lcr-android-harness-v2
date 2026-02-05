
package com.pa.lcr.lcp;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * DeliveryController — 2026-02-05 (LCP-safe, Python V2, no 0x7D TX)
 * -----------------------------------------------------------------
 * - Utilise les op* de LcpLink (opMachineStatusFull/opDeliveryStatus/opSetField/opGetField/opIssueCommand)
 * - Ne fait PLUS aucun sendRecv direct sur 0x23/0x28
 * - N'envoie JAMAIS 0x7D (CHECK_REQUEST) — file gérée côté LcpLink.waitQueued()
 * - START : wake → clear ticket → recover → set product → presets → RUN → WAIT_FLOW
 * - WAIT_FLOW : double FLOW ou Δ#44 > 0 (filet de sécurité)
 * - LIVE LOOP : 0x23 strict → #44/#45
 * - Guard preset (NET ou GROSS)
 * - END : cmd 0x02, attente FLOW=0 & ACTIVE=0, clear ticket
 * - RESYNC : GET_PRODUCT_ID (0x00) via sendRecv permis (fusible 0x7D uniquement)
 *
 * Remarque:
 * - Les throttles 0x23/0x28 sont appliqués dans LcpLink (haut ET bas niveau)
 * - Pour un terrain très exigeant, utiliser pollMs >= 1000 ms
 */
public final class DeliveryController {

    public enum State { IDLE, STARTING, WAIT_FOR_FLOW, FLOW_ACTIVE, FINALIZING, ENDED, ERROR }

    private final LcpLink link;
    private final DeliveryEvents events;
    private final Executor exec;

    private volatile State state = State.IDLE;
    private volatile boolean liveRunning = false;

    public DeliveryController(LcpLink link, DeliveryEvents events, Executor executor) {
        this.link   = link;
        this.events = (events != null ? events : new DeliveryEvents(){});
        // Mono-thread => pas d'overlap d'appels DC
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

    public void startOpenMode(int productId, long waitFlowMs, long pollMs) {
        exec.execute(() -> {
            try {
                setState(State.STARTING);
                preStartSequence(productId, false);
                runAndWaitFlow(waitFlowMs, pollMs);
                setState(State.FLOW_ACTIVE);
                events.onFlowStarted();
            } catch(Exception e) {
                fail("startOpenMode: " + e.getMessage(), e);
            }
        });
    }

    public void startPresetNet(int productId, double litres, long waitFlowMs, long pollMs) {
        exec.execute(() -> {
            try {
                setState(State.STARTING);
                preStartSequencePreset(productId, litres);
                runAndWaitFlow(waitFlowMs, pollMs);
                setState(State.FLOW_ACTIVE);
                events.onFlowStarted();
            } catch(Exception e) {
                fail("startPresetNet: " + e.getMessage(), e);
            }
        });
    }

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
        wake();
        clearTicketIfNeeded();
        recoverIfActive();

        setProductSafe(productId);
        clearPresetsSafe();

        wakeStable(2, 600, 80);
    }

    private void preStartSequencePreset(int productId, double litres) throws IOException {
        wake();
        clearTicketIfNeeded();
        recoverIfActive();

        int digits = readDigits();
        int raw = (int)Math.round(litres * Math.pow(10, digits));

        setProductSafe(productId);
        writeNetPresetSafe(raw);

        wakeStable(2, 600, 80);
    }

    /* =====================================================================
       RUN + WAIT_FLOW STRICT
       ===================================================================== */

    private void runAndWaitFlow(long timeoutMs, long pollMs) throws IOException {
        try {
            link.opIssueCommand(0x00);     // RUN
            // LcpLink applique un gap 80–120 ms après ISSUE_COMMAND
            waitFlowStrict(timeoutMs, pollMs, true, true);
        } catch(IOException e){
            resync(); sleep(200);
            link.opIssueCommand(0x00);
            waitFlowStrict(timeoutMs, pollMs, true, true);
        }
    }

    private void waitFlowStrict(long timeoutMs, long pollMs,
                                boolean acceptFlow, boolean acceptCounts) throws IOException {

        long tEnd = System.currentTimeMillis() + timeoutMs;

        int g0 = acceptCounts ? safeRead32(44) : 0;
        int confirm = 0;
        boolean prevFlow = false;

        while (System.currentTimeMillis() < tEnd) {

            int[] dsdc;
            try {
                dsdc = getMachineStrict();   // LCP-safe : passe par opMachineStatusFull()
            } catch(Exception e){
                sleep((int)pollMs);
                continue;
            }

            int ds = dsdc[0];
            int dc = dsdc[1];

            boolean flow   = (dc & LcpLink.LCRSc_FLOW_ACTIVE) != 0;
            boolean active = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;
            boolean begin  = (dc & LcpLink.LCRSc_BEGIN_DELIVERY) != 0;

            // BEGIN_DELIVERY n'est PAS flow
            if (active && flow) return;

            if (acceptFlow) {
                if (flow) confirm++; else confirm = 0;
                if (!prevFlow && confirm >= 2) return;
                prevFlow = flow;
            }

            if (acceptCounts) {
                int g = safeRead32(44);
                if (g > g0) return;
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

        int p6 = safeRead32(6);
        int p5 = safeRead32(5);

        boolean guardNet = (p6 > 0);
        double targetL = guardNet ? (p6 / scale) :
                         (p5 > 0 ? (p5 / scale) : Double.NaN);

        int g0 = safeRead32(44);
        int n0 = safeRead32(45);

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
                double delivered = guardNet ?
                        ((n - n0) / scale) :
                        ((g - g0) / scale);

                if (delivered >= targetL + guardMarginLitres) {
                    try { events.onGuardReached(); } catch(Exception ignored){}
                    try { link.opIssueCommand(0x02); } catch(Exception ignored){}
                    guardFired = true;
                }
            }

            sleep((int)Math.max(120, pollMs));
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

                boolean flow   = (dc & LcpLink.LCRSc_FLOW_ACTIVE) != 0;
                boolean active = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;

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
                issueCommandWithRetry(0x02);
                waitEnd(3000, 120);
            }
        } catch(Exception ignored){}
    }

    /* =====================================================================
       CORE STRICT : GET_MACHINE via op* (plus aucun 0x7D ni sendRecv direct)
       ===================================================================== */

    private int[] getMachineStrict() throws IOException {
        // opMachineStatusFull() gère 0x26/0x27 via waitQueued(), et throttle 1 Hz
        int[] triple = link.opMachineStatusFull(); // { dev, ds, dc } ou {0, ds, dc} si fallback 0x28
        int ds = triple[1];
        int dc = triple[2];
        return new int[]{ ds, dc };
    }

    /* =====================================================================
       SAFE FIELD OPS
       ===================================================================== */

    private void setProductSafe(int productId) throws IOException {
        if (productId < 1 || productId > 16)
            throw new IllegalArgumentException("product 1..16");
        byte v = (byte)((productId - 1) & 0xFF);
        opSetFieldSafe(0, new byte[]{v});
    }

    private void clearPresetsSafe() throws IOException {
        opSetFieldSafe(5, i32be(0));
        opSetFieldSafe(6, i32be(0));
    }

    private void writeNetPresetSafe(int raw) throws IOException {
        opSetFieldSafe(6, i32be(raw));
        opSetFieldSafe(5, i32be(0));
    }

    private void opSetFieldSafe(int field, byte[] data) throws IOException {
        try {
            link.opSetField(field, data);
        } catch(IOException e){
            resync(); sleep(150);
            link.opSetField(field, data);
        }
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
       RAW FIELD READS
       ===================================================================== */

    private int readDigits() {
        try {
            byte[] v = link.opGetField(39);
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
    // Note: le fusible de LcpLink bloque seulement 0x7D ; 0x00 est permis.
    // Si tu veux, on peut ajouter un opGetProductId() plus “propre”.
    private void resync() {
        try {
            link.sendRecv(new byte[]{0x00}, 2000);
        } catch(Exception ignored){}
    }

    /* =====================================================================
       WAKE & WAKE_STABLE (via opMachineStatusFull)
       ===================================================================== */

    private void wake() {
        try { getMachineStrict(); } catch(Exception ignored){}
        sleep(120);
    }

    private void wakeStable(int okNeeded, int timeoutPerPollMs, int pauseMs) {
        int ok = 0;
        long tEnd = System.currentTimeMillis() + 1500;

        while (System.currentTimeMillis() < tEnd && ok < okNeeded) {
            try {
                getMachineStrict();
                ok++;
            } catch(Exception ignored){
                ok = 0;
            }
            sleep(pauseMs);
        }
    }

    /* =====================================================================
       HELPERS
       ===================================================================== */

    private static int digitsFromIndex(int idx) {
        switch(idx) {
            case 0: return 2;
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

    private static int u16be(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off+1] & 0xFF);
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
