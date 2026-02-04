
package com.pa.lcr.lcp;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * DeliveryController — Version finale 100% conforme Python V2
 * -----------------------------------------------------------
 * - GET_MACHINE strict (0x23)
 * - Gestion BUSY + queue strict via 0x7D
 * - START : wake → ticket → recover → product → presets → RUN → WAIT_FLOW
 * - WAIT_FLOW : double confirmation FLOW ou Δ#44>0
 * - LIVE loop : 0x23 strict puis lectures #44/#45
 * - Guard preset NET/GROSS
 * - END : cmd 0x02 puis attente FLOW=0 & ACTIVE=0 puis CLEAR_TICKET
 * - RESYNC : GET_PRODUCT_ID (msg 0x00), jamais {0x00} brut
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
        this.events = (events != null ? events : new DeliveryEvents() {});
        this.exec   = (executor != null ? executor : Executors.newSingleThreadExecutor());
    }

    public State getState() { return state; }
    private void setState(State s) {
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
            } catch (Exception e) {
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
            } catch (Exception e) {
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
            } catch (Exception e) {
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

                clearTicketIfNeeded();           // CLEAR_TICKET si bit0=1

                setState(State.ENDED);

            } catch (Exception e) {
                fail("endGracefully: " + e.getMessage(), e);
            }
        });
    }

    public void runLiveLoop(long pollMs, boolean guardEnabled, double guardMarginLitres) {
        exec.execute(() -> {
            liveRunning = true;
            try {
                liveLoopCore(pollMs, guardEnabled, guardMarginLitres);
            } catch (Exception e) {
                fail("runLiveLoop: " + e.getMessage(), e);
            } finally {
                liveRunning = false;
            }
        });
    }

    public void stopLiveLoop() { liveRunning = false; }

    /* =====================================================================
       PRÉ-START (Python V2 exact)
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
        int raw = (int) Math.round(litres * Math.pow(10, digits));

        setProductSafe(productId);
        writeNetPresetSafe(raw);

        wakeStable(2, 600, 80);
    }

    /* =====================================================================
       RUN + WAIT_FLOW STRICT
       ===================================================================== */

    private void runAndWaitFlow(long timeoutMs, long pollMs) throws IOException {
        try {
            link.opIssueCommand(0x00);      // RUN
            waitFlowStrict(timeoutMs, pollMs, true, true);
        } catch (IOException e) {
            resync(); sleep(200);
            link.opIssueCommand(0x00);
            waitFlowStrict(timeoutMs, pollMs, true, true);
        }
    }

    /**
     * WAIT_FLOW strict :
     * - FLOW=1 pendant 2 ticks consécutifs OU
     * - Δ#44 > 0
     * - BEGIN_DELIVERY n'est jamais considéré comme FLOW (Python)
     */
    private void waitFlowStrict(long timeoutMs, long pollMs,
                                boolean acceptFlow, boolean acceptCounts) throws IOException {

        long tEnd = System.currentTimeMillis() + timeoutMs;

        int g0 = acceptCounts ? safeRead32(44) : 0;

        int confirm = 0;
        boolean prevFlow = false;

        while (System.currentTimeMillis() < tEnd) {

            int[] dsdc;
            try {
                dsdc = getMachineStrict(3000);
            } catch (Exception e) {
                sleep((int)pollMs);
                continue;
            }

            int ds = dsdc[0];
            int dc = dsdc[1];

            boolean flow   = (dc & LcpLink.LCRSc_FLOW_ACTIVE) != 0;
            boolean active = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;
            boolean begin  = (dc & LcpLink.LCRSc_BEGIN_DELIVERY) != 0;

            // BEGIN_DELIVERY n'est pas FLOW
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

            sleep((int) pollMs);
        }

        throw new IOException("START_TIMEOUT: FLOW non détecté");
    }

    /* =====================================================================
       LIVE LOOP (STRICT 0x23 → puis #44/#45)
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
                dsdc = getMachineStrict(3000);
            } catch (Exception e) {
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
                double delivered =
                        guardNet ? ((n - n0) / scale) :
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
                int[] dsdc = getMachineStrict(3000);
                int ds = dsdc[0];
                int dc = dsdc[1];

                boolean flow   = (dc & LcpLink.LCRSc_FLOW_ACTIVE) != 0;
                boolean active = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;

                if (!flow && !active) return;

            } catch (Exception ignored) {}

            sleep((int)Math.max(100, pollMs));
        }
    }

    private void clearTicketIfNeeded() throws IOException {
        try {
            int[] dsdc = getMachineStrict(1500);
            int ds = dsdc[0];
            boolean pending = (ds & LcpLink.LCRSc_DEL_TICKET_PENDING) != 0;
            if (pending) {
                issueCommandWithRetry(0x06);  // CLEAR_TICKET
            }
        } catch (Exception ignored) {}
    }

    private void recoverIfActive() throws IOException {
        try {
            int[] dsdc = getMachineStrict(1500);
            int dc = dsdc[1];
            boolean active = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;
            if (active) {
                issueCommandWithRetry(0x02);
                waitEnd(3000, 120);
            }
        } catch (Exception ignored) {}
    }

    /* =====================================================================
       CORE STRICT : GET_MACHINE (0x23) + queue 0x7D
       ===================================================================== */

    private int[] getMachineStrict(int timeoutMs) throws IOException {

        byte[] rsp = link.sendRecv(new byte[]{ (byte)LcpLink.MSG_GET_MACHINE }, timeoutMs);
        byte[] p = LcpLink.extractPayload(rsp);

        if (p == null || p.length < 1)
            throw new IOException("GET_MACHINE empty");

        int rc = p[0] & 0xFF;

        if (rc == LcpLink.RC_REQUEST_QUEUED ||
            rc == LcpLink.RC_NO_REQUEST_ACTIVE) {

            p = waitQueuedStrict(4000, 150);
            rc = p[0] & 0xFF;
        }

        if (rc != LcpLink.RC_OK)
            throw new IOException(String.format("GET_MACHINE rc=0x%02X", rc));

        if (p.length < 8)
            throw new IOException("GET_MACHINE short");

        int ds = u16be(p, 4);
        int dc = u16be(p, 6);
        return new int[]{ ds, dc };
    }

    private byte[] waitQueuedStrict(int timeoutMs, int pollMs) throws IOException {
        long tEnd = System.currentTimeMillis() + timeoutMs;
        byte[] last = null;

        while (System.currentTimeMillis() < tEnd) {

            byte[] rsp = link.sendRecv(
                    new byte[]{ (byte)LcpLink.MSG_CHECK_REQUEST },
                    Math.max(1200, pollMs + 800)
            );

            byte[] p = LcpLink.extractPayload(rsp);
            if (p != null && p.length > 0) last = p;

            if (p == null || p.length == 0) {
                sleep(pollMs);
                continue;
            }

            int rc = p[0] & 0xFF;

            if (rc == LcpLink.RC_REQUEST_ABORTED)
                throw new IOException("Queued aborted");

            if (rc == LcpLink.RC_REQUEST_QUEUED ||
                rc == LcpLink.RC_NO_REQUEST_ACTIVE) {
                sleep(pollMs);
                continue;
            }

            if (rc == LcpLink.RC_OK &&
                p.length >= 2 &&
                (p[1] & 0xFF) == LcpLink.RC_OK) {
                byte[] out = new byte[p.length - 1];
                System.arraycopy(p, 1, out, 0, out.length);
                return out;
            }

            return p;
        }

        throw new IOException("Queued timeout (0x7D)");
    }

    /* =====================================================================
       SET_FIELD & ISSUE_COMMAND Safe
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
        } catch (IOException e) {
            resync(); sleep(150);
            link.opSetField(field, data);
        }
        sleep(120);
    }

    private void issueCommandWithRetry(int cmd) throws IOException {
        try {
            link.opIssueCommand(cmd);
        } catch (IOException e) {
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
        } catch (Exception e) {
            return 1;
        }
    }

    private int safeRead32(int field) {
        try {
            byte[] d = link.opGetField(field);
            return i32beToInt(d);
        } catch (Exception e) {
            return 0;
        }
    }

    /* =====================================================================
       RESYNC — GET_PRODUCT_ID (0x00)
       ===================================================================== */

    private void resync() {
        try {
            link.sendRecv(new byte[]{0x00}, 2000);
        } catch(Exception ignored){}
    }

    /* =====================================================================
       WAKE & WAKE_STABLE
       ===================================================================== */

    private void wake() {
        try { getMachineStrict(1500); } catch (Exception ignored){}
        sleep(120);
    }

    /**
     * wakeStable :
     *  - attend 'okNeeded' lectures GET_MACHINE successives sans erreur.
     *  - Timeout total ~1500ms.
     */
    private void wakeStable(int okNeeded, int timeoutPerPollMs, int pauseMs) {
        int ok = 0;
        long tEnd = System.currentTimeMillis() + 1500;

        while (System.currentTimeMillis() < tEnd && ok < okNeeded) {
            try {
                getMachineStrict(timeoutPerPollMs);
                ok++;
            } catch(Exception ignored) {
                ok = 0;
            }
            sleep(pauseMs);
        }
    }

    /* =====================================================================
       HELPERS
       ===================================================================== */

    private static int digitsFromIndex(int idx) {
        switch (idx) {
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
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static int i32beToInt(byte[] d) {
        return ((d[0] & 0xFF) << 24) |
               ((d[1] & 0xFF) << 16) |
               ((d[2] & 0xFF) << 8)  |
               (d[3] & 0xFF);
    }

    private void fail(String msg, Throwable t) {
        setState(State.ERROR);
        { events.onError(msg, t); } catch(Exception ignored){}
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
