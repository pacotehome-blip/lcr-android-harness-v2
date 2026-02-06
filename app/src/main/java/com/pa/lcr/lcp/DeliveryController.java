
package com.pa.lcr.lcp;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * DeliveryController — 2026-02-05 — LCP-safe + PythonCompat + STOP/cancel + PollGate
 *
 * Références:
 *  - "LCR API Internal Messages for LCP.pdf" (0x23/0x28/0x21/0x24/0x00, RC=0x26 queue)
 *  - "LCR Registers' Fields.xlsx" (#39 digits, #0 product, #5/#6 presets, #44/#45 counters)
 *
 * Comportement:
 *  - Active PythonCompat: 0x7D émis par les op* pour vider la file quand RC=0x26 (comme le script Python)
 *  - PollGate: ouvrerture/fermeture explicite lors de WAIT_FOR_FLOW & live loop
 *  - STARTING reste BLOQUANT tant que FLOW_ACTIVE != 1 (double confirmation); pas de 0x20/0x21/0x00/0x24 dans la boucle d'attente
 *  - AUCUN sendRecv(0x23/0x28/0x7D) direct : seulement op* de LcpLink
 *  - STOP = requestStop(...) : coupe live, bloque les polls, annule les IO, et fait sortir toutes les boucles
 */
public final class DeliveryController {

    public enum State { IDLE, STARTING, WAIT_FOR_FLOW, FLOW_ACTIVE, FINALIZING, ENDED, ERROR }

    private final LcpLink link;
    private final DeliveryEvents events;
    private final Executor exec;

    private volatile State state = State.IDLE;
    private volatile boolean liveRunning = false;

    // Réglages
    private static final int FLOW_CONFIRM_REQUIRED = 2;      // confirmations FLOW=1
    private static final int MIN_LOOP_POLL_MS     = 120;     // tempo mini live loop

    // STOP/cancel flag
    private volatile boolean cancelled = false;

    public DeliveryController(LcpLink link, DeliveryEvents events, Executor executor) {
        this.link   = link;
        this.events = (events != null ? events : new DeliveryEvents(){});
        this.exec   = (executor != null ? executor : Executors.newSingleThreadExecutor());

        // Mode PythonCompat: 0x7D actif + poll court ~200 ms
        this.link.setPythonCompat(true, 200);

        // Par défaut : on BLOQUE les polls (pollGate); on n'ouvrira que dans WAIT_FOR_FLOW et live loop.
        this.link.setPollingBlocked(true);
        android.util.Log.i("DC", "DeliveryController PYCOMPAT engaged (poll=200ms); PollingBlocked=true");
    }

    public State getState() { return state; }
    private void setState(State s){
        state = s;
        try { events.onStateChanged(s); } catch(Exception ignored){}
    }
    private static void sleep(int ms){ try { Thread.sleep(ms); } catch(Exception ignored){} }

    /** STOP immédiat: arrête live, bloque les polls, annule toutes les IO. */
    public void requestStop(String reason) {
        cancelled = true;
        liveRunning = false;
        try { link.setPollingBlocked(true); } catch(Exception ignored){}
        try { link.cancelIO(); } catch(Exception ignored){}
        android.util.Log.w("DC", "STOP requested: " + reason);
    }

    /** Réarme après un STOP (si tu relances une nouvelle session). */
    public void resetStop() {
        cancelled = false;
        try { link.resumeIO(); } catch(Exception ignored){}
        try { link.setPollingBlocked(true); } catch(Exception ignored){}
        android.util.Log.i("DC", "STOP cleared: IO resumed; PollingBlocked=true");
    }

    // ============================== API ================================

    /** Mode ouvert (open) : RUN puis attente FLOW_ACTIVE=1 (bloquant). */
    public void startOpenMode(int productId, long waitFlowTimeoutMs, long pollMs) {
        exec.execute(() -> {
            try {
                if (cancelled) throw new IOException("CANCELLED");
                setState(State.STARTING);
                preStartSequence(productId);

                if (cancelled) throw new IOException("CANCELLED");
                runAndWaitFlow(waitFlowTimeoutMs, pollMs);

                if (cancelled) throw new IOException("CANCELLED");
                setState(State.FLOW_ACTIVE);
                events.onFlowStarted();
            } catch(Exception e) {
                fail("startOpenMode: " + e.getMessage(), e);
            }
        });
    }

    /** Mode preset NET : set #6, clear #5, RUN, attente FLOW_ACTIVE=1. */
    public void startPresetNet(int productId, double litres, long waitFlowTimeoutMs, long pollMs) {
        exec.execute(() -> {
            try {
                if (cancelled) throw new IOException("CANCELLED");
                setState(State.STARTING);
                preStartSequencePreset(productId, litres);

                if (cancelled) throw new IOException("CANCELLED");
                runAndWaitFlow(waitFlowTimeoutMs, pollMs);

                if (cancelled) throw new IOException("CANCELLED");
                setState(State.FLOW_ACTIVE);
                events.onFlowStarted();
            } catch(Exception e) {
                fail("startPresetNet: " + e.getMessage(), e);
            }
        });
    }

    /** Attendre FLOW uniquement (bloquant). */
    public void waitForFlowOnly(long timeoutMs, long pollMs) {
        exec.execute(() -> {
            try {
                if (cancelled) throw new IOException("CANCELLED");
                setState(State.WAIT_FOR_FLOW);

                // Ouvrir la fenêtre de poll, attendre, puis refermer
                link.setPollingBlocked(false);
                try {
                    waitFlowStrict(timeoutMs, pollMs);
                } finally {
                    link.setPollingBlocked(true);
                }

                if (cancelled) throw new IOException("CANCELLED");
                setState(State.FLOW_ACTIVE);
                events.onFlowStarted();
            } catch(Exception e) {
                fail("waitForFlowOnly: " + e.getMessage(), e);
            }
        });
    }

    /** Fin propre (END), attente FLOW=0 & ACTIVE=0, clear ticket si besoin. */
    public void endGracefully(long timeoutMs, long pollMs) {
        exec.execute(() -> {
            try {
                if (cancelled) throw new IOException("CANCELLED");
                setState(State.FINALIZING);
                issueCommandWithRetry(0x02);     // END
                if (cancelled) throw new IOException("CANCELLED");
                waitEnd(timeoutMs, pollMs);      // FLOW=0 & ACTIVE=0
                if (cancelled) throw new IOException("CANCELLED");
                clearTicketIfNeeded();           // si ticket en attente
                setState(State.ENDED);
            } catch(Exception e) {
                fail("endGracefully: " + e.getMessage(), e);
            }
        });
    }

    /** Boucle d’échantillonnage live (notifie la GUI via events). */
    public void runLiveLoop(long pollMs, boolean guardEnabled, double guardMarginLitres) {
        exec.execute(() -> {
            liveRunning = true;
            try {
                // ouvrir pollGate pendant la live loop
                link.setPollingBlocked(false);
                try {
                    liveLoopCore(pollMs, guardEnabled, guardMarginLitres);
                } finally {
                    link.setPollingBlocked(true);
                }
            } catch(Exception e){
                fail("runLiveLoop: " + e.getMessage(), e);
            } finally {
                liveRunning = false;
            }
        });
    }

    public void stopLiveLoop() { liveRunning = false; }

    // ============================ PRE-START =============================

    private void preStartSequence(int productId) throws IOException {
        wake();                // ping + tempo
        if (cancelled) throw new IOException("CANCELLED");
        clearTicketIfNeeded(); // ticket en attente → clear
        if (cancelled) throw new IOException("CANCELLED");
        recoverIfActive();     // livraison active → END + attente

        if (cancelled) throw new IOException("CANCELLED");
        setProductSafe(productId);
        if (cancelled) throw new IOException("CANCELLED");
        clearPresetsSafe();
    }

    private void preStartSequencePreset(int productId, double litres) throws IOException {
        wake();
        if (cancelled) throw new IOException("CANCELLED");
        clearTicketIfNeeded();
        if (cancelled) throw new IOException("CANCELLED");
        recoverIfActive();

        int digits = readDigits(); // #39
        int raw = (int)Math.round(litres * Math.pow(10, digits));

        if (cancelled) throw new IOException("CANCELLED");
        setProductSafe(productId);
        if (cancelled) throw new IOException("CANCELLED");
        writeNetPresetSafe(raw); // #6=raw, #5=0
    }

    // ========================== RUN + WAIT_FLOW =========================

    private void runAndWaitFlow(long timeoutMs, long pollMs) throws IOException {
        try {
            if (cancelled) throw new IOException("CANCELLED");
            link.opIssueCommand(0x00);     // RUN
            setState(State.WAIT_FOR_FLOW);

            // Ouvre pollGate pour la fenêtre d'attente
            link.setPollingBlocked(false);
            try {
                waitFlowStrict(timeoutMs, pollMs);
            } finally {
                link.setPollingBlocked(true);
            }

        } catch(IOException e){
            if (cancelled) throw e;
            resync(); sleep(200);
            if (cancelled) throw new IOException("CANCELLED");

            link.opIssueCommand(0x00);
            setState(State.WAIT_FOR_FLOW);

            link.setPollingBlocked(false);
            try {
                waitFlowStrict(timeoutMs, pollMs);
            } finally {
                link.setPollingBlocked(true);
            }
        }
    }

    /**
     * Attente FLOW=1 façon Python: alterne 0x23/0x28 à poll court,
     * double confirmation FLOW. AUCUN 0x20/0x21/0x00/0x24 dans la boucle.
     */
    private void waitFlowStrict(long timeoutMs, long pollMs) throws IOException {

        long tEnd = System.currentTimeMillis() + timeoutMs;

        int confirm = 0;
        boolean prevFlow = false;
        boolean ask28 = false; // alterne 0x23/0x28

        android.util.Log.i("DC", "WAIT_FOR_FLOW: blocking until FLOW_ACTIVE=1");

        while (System.currentTimeMillis() < tEnd) {
            if (cancelled) throw new IOException("CANCELLED");

            int ds, dc;
            try {
                if (!ask28) {
                    int[] triple = link.opMachineStatusFull(); // 0x23 + 0x7D si RC=0x26
                    ds = triple[1]; dc = triple[2];
                } else {
                    int[] d = link.opDeliveryStatus();         // 0x28 + 0x7D si RC=0x26
                    ds = d[0]; dc = d[1];
                }
            } catch (IOException io) {
                if (cancelled) throw io;
                // USB vient peut-être de tomber : petite pause puis continue si pas cancelled
                sleep(150);
                continue;
            }

            ask28 = !ask28;

            boolean flow   = (dc & LcpLink.LCRSc_FLOW_ACTIVE) != 0;
            boolean active = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;

            if (active && flow) return; // FLOW confirmé + ACTIVE

            // Double confirmation FLOW
            if (flow) confirm++; else confirm = 0;
            if (!prevFlow && confirm >= FLOW_CONFIRM_REQUIRED) return;
            prevFlow = flow;

            sleep((int)Math.max(200, pollMs)); // poll court ~200ms
        }

        throw new IOException("START_TIMEOUT: FLOW non détecté");
    }

    // ============================ LIVE LOOP =============================

    private void liveLoopCore(long pollMs, boolean guardEnabled, double guardMarginLitres)
            throws IOException {

        int digits = readDigits();
        double scale = Math.pow(10, digits);

        int p6 = safeRead32(6);   // preset NET
        int p5 = safeRead32(5);   // preset GROSS

        boolean guardNet = (p6 > 0);
        double targetL = guardNet ? (p6 / scale) :
                         (p5 > 0 ? (p5 / scale) : Double.NaN);

        int g0 = safeRead32(44);
        int n0 = safeRead32(45);

        boolean guardFired = false;

        while (liveRunning && state == State.FLOW_ACTIVE) {
            if (cancelled) return;

            int[] dsdc;
            try {
                dsdc = getMachineStrict(); // via opMachineStatusFull()
            } catch(Exception e){
                if (cancelled) return;
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
                double delivered = guardNet ? ((n - n0) / scale)
                                            : ((g - g0) / scale);

                if (delivered >= targetL + guardMarginLitres) {
                    try { events.onGuardReached(); } catch(Exception ignored){}
                    try { link.opIssueCommand(0x02); } catch(Exception ignored){}
                    guardFired = true;
                }
            }

            sleep((int)Math.max(MIN_LOOP_POLL_MS, pollMs));
        }
    }

    // ============================= FIN/END ==============================

    private void waitEnd(long timeoutMs, long pollMs) throws IOException {
        long tEnd = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < tEnd) {
            if (cancelled) return;
            try {
                int[] dsdc = getMachineStrict();
                int dc = dsdc[1];

                boolean flow   = (dc & LcpLink.LCRSc_FLOW_ACTIVE)    != 0;
                boolean active = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;

                if (!flow && !active) return;

            } catch(Exception ignored){}

            sleep((int)Math.max(100, pollMs));
        }
    }

    private void clearTicketIfNeeded() throws IOException {
        if (cancelled) throw new IOException("CANCELLED");
        try {
            int[] dsdc = getMachineStrict();
            int ds = dsdc[0];
            boolean pending = (ds & LcpLink.LCRSc_DEL_TICKET_PENDING) != 0;
            if (pending) issueCommandWithRetry(0x06); // CLEAR_TICKET
        } catch(Exception ignored){}
    }

    private void recoverIfActive() throws IOException {
        if (cancelled) throw new IOException("CANCELLED");
        try {
            int[] dsdc = getMachineStrict();
            int dc = dsdc[1];
            boolean active = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;
            if (active) {
                issueCommandWithRetry(0x02); // END
                if (cancelled) throw new IOException("CANCELLED");
                waitEnd(3000, 120);
            }
        } catch(Exception ignored){}
    }

    // ============================== WAKE ================================

    /** Petit "ping" LCR + tempo. */
    private void wake() {
        if (cancelled) return;
        try { getMachineStrict(); } catch(Exception ignored){}
        sleep(120);
    }

    // ============================= CORE/GET =============================

    private int[] getMachineStrict() throws IOException {
        int[] triple = link.opMachineStatusFull(); // { dev, ds, dc } (ou {0, ds, dc})
        return new int[]{ triple[1], triple[2] };
    }

    // ============================ SET FIELDS ============================

    private void setProductSafe(int productId) throws IOException {
        if (productId < 1 || productId > 16)
            throw new IllegalArgumentException("product 1..16");
        byte v = (byte)((productId - 1) & 0xFF);
        opSetFieldSafe(0, new byte[]{v}); // #0
        sleep(120);
    }

    private void clearPresetsSafe() throws IOException {
        opSetFieldSafe(5, i32be(0)); // #5 GROSS
        sleep(120);
        opSetFieldSafe(6, i32be(0)); // #6 NET
        sleep(120);
    }

    private void writeNetPresetSafe(int raw) throws IOException {
        opSetFieldSafe(6, i32be(raw)); // #6 NET
        sleep(120);
        opSetFieldSafe(5, i32be(0));   // #5 GROSS
        sleep(120);
    }

    private void opSetFieldSafe(int field, byte[] data) throws IOException {
        if (cancelled) throw new IOException("CANCELLED");
        try {
            link.opSetField(field, data);
        } catch(IOException e){
            if (cancelled) throw e;
            resync(); sleep(150);
            if (cancelled) throw new IOException("CANCELLED");
            link.opSetField(field, data);
        }
    }

    private void issueCommandWithRetry(int cmd) throws IOException {
        if (cancelled) throw new IOException("CANCELLED");
        try {
            link.opIssueCommand(cmd);
        } catch(IOException e){
            if (cancelled) throw e;
            resync(); sleep(150);
            if (cancelled) throw new IOException("CANCELLED");
            link.opIssueCommand(cmd);
        }
    }

    // ============================= RAW READS ============================

    private int readDigits() {
        try {
            byte[] v = link.opGetField(39); // #39
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

    // ============================== RESYNC ==============================

    private void resync() {
        if (cancelled) return;
        try {
            link.sendRecv(new byte[]{0x00}, 2000); // GET_PRODUCT_ID
        } catch(Exception ignored){}
    }

    // ============================= HELPERS ==============================

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

    // ============================== EVENTS ==============================

    public interface DeliveryEvents {
        default void onStateChanged(State s) {}
        default void onFlowStarted() {}
        default void onFlowStopped() {}
        default void onLiveSample(int ds, int dc, double gL, double nL) {}
        default void onGuardReached() {}
        default void onError(String m, Throwable t) {}
    }
}
