
/*  DeliveryController.java — version corrigée (2026-02-09)
 *
 *  - Fix: suppression des double-unlock dans endGracefully() et polling final
 *  - Fix: aucun unlock dans des catch
 *  - Sécurisé et stable (plus de IllegalMonitorStateException)
 */

package com.pa.lcr.lcp;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

public final class DeliveryController {

    /* ============================ Types & Events ============================ */

    public enum State {
        IDLE, STARTING, WAIT_FOR_FLOW, FLOW_ACTIVE, FINALIZING, ENDED, ERROR
    }

    public interface DeliveryEvents {
        void onStateChanged(State s);
        void onFlowStarted();
        void onFlowStopped();
        void onLiveSample(int ds, int dc, double grossL, double netL);
        void onGuardReached();
        void onError(String message, Throwable t);
        default void onProgress(DeliveryProgress p) {}
        default void onLog(String line) {}
    }

    public static final class DeliveryProgress {
        public final long tEpochMs, tSinceStartMs, tSinceLastDeltaMs;
        public final double grossL, netL, dGrossL, dNetL, flowGrossLpm, flowNetLpm;
        public final boolean flowActive, stalled;
        public final int ds, dc;

        public DeliveryProgress(long tEpochMs, long tSinceStartMs, long tSinceLastDeltaMs,
                                double grossL, double netL, double dGrossL, double dNetL,
                                double flowGrossLpm, double flowNetLpm,
                                boolean flowActive, boolean stalled,
                                int ds, int dc) {
            this.tEpochMs = tEpochMs;
            this.tSinceStartMs = tSinceStartMs;
            this.tSinceLastDeltaMs = tSinceLastDeltaMs;
            this.grossL = grossL; this.netL = netL;
            this.dGrossL = dGrossL; this.dNetL = dNetL;
            this.flowGrossLpm = flowGrossLpm; this.flowNetLpm = flowNetLpm;
            this.flowActive = flowActive; this.stalled = stalled;
            this.ds = ds; this.dc = dc;
        }
    }

    /* ============================ Config ============================ */

    private static final long   STALL_MS = 3_000;
    private static final double EPS_L    = 0.001;
    private static final int    DEFAULT_POLL_MS = 250;

    /* ============================ Dependencies ============================ */

    private final LcpLink link;
    private final DeliveryEvents cb;
    private final Executor cbExec;

    /* ============================ Concurrency ============================ */

    private final ReentrantLock ioLock = new ReentrantLock();
    private final AtomicBoolean liveLoopRunning = new AtomicBoolean(false);
    private Thread liveLoopThread;
    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);

    /* ============================ Progression ============================ */

    private volatile long startedAtMs = 0L, lastEmitMs = 0L, lastProgressAtMs = 0L;
    private volatile double lastGrossL = Double.NaN, lastNetL = Double.NaN;

    /* ============================ Ctor ============================ */

    public DeliveryController(LcpLink link, DeliveryEvents events) {
        this(link, events, Executors.newSingleThreadExecutor());
    }

    public DeliveryController(LcpLink link, DeliveryEvents events, Executor executor) {
        this.link = Objects.requireNonNull(link, "link");
        this.cb = Objects.requireNonNull(events, "events");
        this.cbExec = (executor != null ? executor : Executors.newSingleThreadExecutor());
    }

    /* ============================ Public API ============================ */

    public State getState() { return state.get(); }
    private void log(String line) { safeExec(() -> cb.onLog(line)); }

    /* ------------------------------ START ------------------------------ */

    public void startOpenMode(int productId, int startTimeoutMs, int pollMs) {
        setState(State.STARTING);
        startedAtMs = System.currentTimeMillis();
        lastEmitMs = lastProgressAtMs = startedAtMs;
        lastGrossL = lastNetL = Double.NaN;

        setState(State.WAIT_FOR_FLOW);
        runLiveLoop(Math.max(50, (pollMs > 0 ? pollMs : DEFAULT_POLL_MS)));
    }

    public void startPresetNet(int productId, double presetLiters, int startTimeoutMs, int pollMs) {
        setState(State.STARTING);
        startedAtMs = System.currentTimeMillis();
        lastEmitMs = lastProgressAtMs = startedAtMs;
        lastGrossL = lastNetL = Double.NaN;

        setState(State.WAIT_FOR_FLOW);
        runLiveLoop(Math.max(50, (pollMs > 0 ? pollMs : DEFAULT_POLL_MS)));
    }

    /* ------------------------------ PING ------------------------------ */

    public void pingStatus() {
        ioLock.lock();
        try {
            log("PING 0x23");
            int[] triple = link.opMachineStatusFull();
            int ds = (triple != null && triple.length >= 2) ? triple[1] : 0;
            int dc = (triple != null && triple.length >= 3) ? triple[2] : 0;
            log(String.format("PING → DS=0x%04X DC=0x%04X", ds, dc));
            fireLiveSample(ds, dc, isNaN0(lastGrossL), isNaN0(lastNetL));
        } catch (Exception e) {
            fireError("pingStatus failed", e);
        } finally {
            ioLock.unlock();
        }
    }

    /* ------------------------------ POLL 0x28 ------------------------------ */

    public void pollDeliveryStatusOnce() {
        ioLock.lock();
        try {
            log("POLL 0x28");
            byte[] r28 = sendSimple((byte)0x28, new byte[0], 3000);
            double[] gn = decodeGrossNetFrom0x28(r28);
            fireLiveSample(0, 0, gn[0], gn[1]);

            long now = System.currentTimeMillis();
            fireProgress(new DeliveryProgress(
                    now, now - startedAtMs, now - lastProgressAtMs,
                    gn[0], gn[1], 0.0, 0.0, 0.0, 0.0,
                    false, false, 0, 0
            ));
        } catch (Exception e) {
            fireError("pollDeliveryStatusOnce failed", e);
        } finally {
            ioLock.unlock();
        }
    }

    /* ------------------------------ RESYNC ------------------------------ */

    public void resyncGetProductId() {
        ioLock.lock();
        try {
            log("RESYNC 0x00");
            sendSimple((byte)0x00, new byte[0], 2000);
        } catch (Exception e) {
            fireError("resyncGetProductId failed", e);
        } finally {
            ioLock.unlock();
        }
    }

    /* ------------------------------ PRINT ------------------------------ */

    public void printTicketText(String asciiText, int chunkSize, int timeoutPerChunkMs) {
        if (asciiText == null) asciiText = "";
        final byte CMD = (byte)0x22;
        final int CHUNK = Math.max(32, chunkSize);
        final int TMO   = Math.max(1000, timeoutPerChunkMs);
        byte[] all = asciiText.getBytes(StandardCharsets.US_ASCII);

        ioLock.lock();
        try {
            log("PRINT len=" + all.length + " chunk=" + CHUNK);
            int off = 0;
            while (off < all.length) {
                int len = Math.min(CHUNK, all.length - off);
                byte[] payload = Arrays.copyOfRange(all, off, off + len);
                sendSimple(CMD, payload, TMO);
                off += len;
            }
            log("PRINT done");
        } catch (Exception e) {
            fireError("printTicketText failed", e);
        } finally {
            ioLock.unlock();
        }
    }

    /* ============================ END (A / Terminer) ============================ */

    public void endGracefully(int endTimeoutMs, int pollMs) {
        setState(State.FINALIZING);
        stopLiveLoop();

        /* ---------- END 0x24 ---------- */
        ioLock.lock();
        try {
            log("END 0x24 02");
            try { sendSimple((byte)0x24, new byte[]{ 0x02 }, 3000); }
            catch (Exception ex) {
                log("END 0x24 02 failed → retry 0x24 00");
                sendSimple((byte)0x24, new byte[]{ 0x00 }, 3000);
            }
        } catch (Exception e) {
            fireError("END (0x24) failed", e);
            setState(State.ERROR);
            return;                // ❗ pas d'unlock ici
        } finally {
            ioLock.unlock();        // ✔ unique
        }

        /* ---------- Poll final ---------- */
        long deadline = System.currentTimeMillis() + Math.max(3000, endTimeoutMs);
        long localLastProgressAt = System.currentTimeMillis();
        double gPrev = Double.NaN, nPrev = Double.NaN;
        final int sleepMs = Math.max(50, (pollMs > 0 ? pollMs : DEFAULT_POLL_MS));

        while (System.currentTimeMillis() < deadline) {
            int ds = 0, dc = 0; double g = 0.0, n = 0.0;

            ioLock.lock();
            try {
                int[] dsdc = link.opMachineStatusFull();
                ds = (dsdc != null && dsdc.length >= 2) ? dsdc[1] : 0;
                dc = (dsdc != null && dsdc.length >= 3) ? dsdc[2] : 0;

                byte[] r28 = sendSimple((byte)0x28, new byte[0], 3000);
                double[] gn = decodeGrossNetFrom0x28(r28);
                g = gn[0]; n = gn[1];
            } catch (Exception e) {
                fireError("Finalize polling failed", e);
                break;             // ❗ sans unlock
            } finally {
                ioLock.unlock();    // ✔ unique
            }

            fireLiveSample(ds, dc, g, n);
            DeliveryProgress p = progressForFinalize(gPrev, nPrev, g, n, ds, dc);
            fireProgress(p);

            boolean progressed = (!Double.isNaN(gPrev) && Math.abs(g - gPrev) >= EPS_L)
                              || (!Double.isNaN(nPrev) && Math.abs(n - nPrev) >= EPS_L);
            if (progressed) localLastProgressAt = System.currentTimeMillis();

            if (System.currentTimeMillis() - localLastProgressAt >= STALL_MS)
                break;

            gPrev = g; nPrev = n;
            try { Thread.sleep(sleepMs); } catch (InterruptedException ignored) {}
        }

        setState(State.ENDED);
    }

    /* ============================ STOP ============================ */

    public void stopLiveLoop() {
        liveLoopRunning.set(false);
        Thread t = liveLoopThread;
        if (t != null) {
            try { t.join(500); } catch (InterruptedException ignored) {}
        }
    }

    public void requestStop(String reason) {
        stopLiveLoop();
        fireError("requestStop: " + reason, null);
        setState(State.ERROR);
    }

    /* ============================ Live-loop ============================ */

    private void runLiveLoop(int pollMs) {
        if (!liveLoopRunning.compareAndSet(false, true)) return;

        liveLoopThread = new Thread(() -> {
            try {
                boolean flowAnnounced = (getState() == State.FLOW_ACTIVE);
                final int sleepMs = Math.max(50, pollMs);

                while (liveLoopRunning.get()) {
                    long iterStart = System.currentTimeMillis();

                    int ds = 0, dc = 0; double grossL = 0.0, netL = 0.0;

                    ioLock.lock();
                    try {
                        int[] dsdc = link.opMachineStatusFull();
                        if (dsdc != null && dsdc.length >= 3) {
                            ds = dsdc[1];
                            dc = dsdc[2];
                        }

                        byte[] r28 = sendSimple((byte)0x28, new byte[0], 3000);
                        double[] gn = decodeGrossNetFrom0x28(r28);
                        grossL = gn[0]; netL = gn[1];
                    } catch (Exception e) {
                        fireError("Live loop I/O failed", e);
                    } finally {
                        ioLock.unlock();
                    }

                    fireLiveSample(ds, dc, grossL, netL);

                    long now = System.currentTimeMillis();
                    if (Double.isNaN(lastGrossL) || Double.isNaN(lastNetL)) {
                        lastGrossL = grossL; lastNetL = netL;
                        lastEmitMs = lastProgressAtMs = now;
                    }

                    double dG = grossL - lastGrossL;
                    double dN = netL - lastNetL;
                    if (Math.abs(dG) < EPS_L) dG = 0.0;
                    if (Math.abs(dN) < EPS_L) dN = 0.0;

                    boolean progressed = (dG != 0.0 || dN != 0.0);
                    if (progressed) lastProgressAtMs = now;

                    long dtMs = Math.max(1L, now - lastEmitMs);
                    double fG = (dG * 60000.0) / dtMs;
                    double fN = (dN * 60000.0) / dtMs;

                    boolean stalled = (now - lastProgressAtMs >= STALL_MS);
                    boolean flowActive = progressed;

                    fireProgress(new DeliveryProgress(
                            now, now - startedAtMs, now - lastProgressAtMs,
                            grossL, netL, dG, dN, fG, fN,
                            flowActive, stalled, ds, dc
                    ));

                    if (!flowAnnounced &&
                        progressed &&
                        (getState() == State.WAIT_FOR_FLOW || getState() == State.STARTING)) {
                        setState(State.FLOW_ACTIVE);
                        fireFlowStarted();
                        flowAnnounced = true;
                    }

                    lastGrossL = grossL; lastNetL = netL; lastEmitMs = now;

                    long spent = System.currentTimeMillis() - iterStart;
                    long sleep = sleepMs - spent;
                    if (sleep > 0) {
                        try { Thread.sleep(sleep); } catch (InterruptedException ignored) {}
                    }
                }

                if (getState() == State.FLOW_ACTIVE)
                    fireFlowStopped();

            } catch (Throwable t) {
                fireError("Live loop crashed", t);
                setState(State.ERROR);
            } finally {
                liveLoopRunning.set(false);
            }
        }, "lcp-live-loop");

        liveLoopThread.setDaemon(true);
        liveLoopThread.start();
    }

    /* ============================ I/O helpers ============================ */

    private byte[] sendSimple(byte cmd, byte[] payload, int timeoutMs) throws Exception {
        byte[] msg = new byte[1 + (payload != null ? payload.length : 0)];
        msg[0] = cmd;
        if (payload != null && payload.length > 0)
            System.arraycopy(payload, 0, msg, 1, payload.length);

        return link.sendRecv(msg, Math.max(500, timeoutMs));
    }

    /* ============================ Decode 0x28 ============================ */

    private double[] decodeGrossNetFrom0x28(byte[] r28) {
        if (r28 == null || r28.length < 6) return new double[]{0.0, 0.0};

        // TODO offset / sizes from XLSX
        final int OFFSET_GROSS = -1;
        final int SIZE_GROSS   = -1;
        final int OFFSET_NET   = -1;
        final int SIZE_NET     = -1;
        final int DIGITS_GROSS = 3;
        final int DIGITS_NET   = 3;

        if (OFFSET_GROSS >= 0 && SIZE_GROSS > 0 &&
            OFFSET_NET   >= 0 && SIZE_NET   > 0 &&
            r28.length >= Math.max(OFFSET_GROSS + SIZE_GROSS, OFFSET_NET + SIZE_NET)) {

            long rawG = readUnsigned(r28, OFFSET_GROSS, SIZE_GROSS);
            long rawN = readUnsigned(r28, OFFSET_NET,   SIZE_NET);

            return new double[]{
                    scaleToLiters(rawG, DIGITS_GROSS),
                    scaleToLiters(rawN, DIGITS_NET)
            };
        }

        return new double[]{0.0, 0.0};
    }

    private static long readUnsigned(byte[] a, int off, int len) {
        long v = 0;
        for (int i = 0; i < len; i++)
            v = (v << 8) | (a[off + i] & 0xFFL);
        return v;
    }

    private static double scaleToLiters(long raw, int digits) {
        if (digits <= 0) return (double) raw;
        double div = 1.0;
        for (int i = 0; i < digits; i++) div *= 10.0;
        return raw / div;
    }

    private static double isNaN0(double v) {
        return Double.isNaN(v) ? 0.0 : v;
    }

    /* ============================ Callbacks ============================ */

    private void setState(State s) {
        State prev = state.getAndSet(s);
        if (prev != s) fireStateChanged(s);
    }

    private void fireStateChanged(State s) { safeExec(() -> cb.onStateChanged(s)); }
    private void fireFlowStarted()        { safeExec(cb::onFlowStarted); }
    private void fireFlowStopped()        { safeExec(cb::onFlowStopped); }
    private void fireLiveSample(int ds, int dc, double g, double n) { safeExec(() -> cb.onLiveSample(ds, dc, g, n)); }
    private void fireProgress(DeliveryProgress p) { safeExec(() -> cb.onProgress(p)); }
    private void fireError(String m, Throwable t) { safeExec(() -> cb.onError(m, t)); }

    private void safeExec(Runnable r) {
        try { cbExec.execute(r); } catch (Throwable ignored) {}
    }

    private DeliveryProgress progressForFinalize(double gPrev, double nPrev,
                                                 double g, double n, int ds, int dc) {

        long now = System.currentTimeMillis();

        if (Double.isNaN(gPrev) || Double.isNaN(nPrev)) {
            gPrev = g; nPrev = n;
        }

        double dG = g - gPrev;
        double dN = n - nPrev;
        if (Math.abs(dG) < EPS_L) dG = 0.0;
        if (Math.abs(dN) < EPS_L) dN = 0.0;

        long dtMs = Math.max(1L, now - lastEmitMs);
        double fG = (dG * 60000.0) / dtMs;
        double fN = (dN * 60000.0) / dtMs;

        boolean progressed = (dG != 0.0 || dN != 0.0);
        if (progressed) lastProgressAtMs = now;

        boolean stalled = (now - lastProgressAtMs >= STALL_MS);
        boolean flowActive = progressed;

        return new DeliveryProgress(
                now, now - startedAtMs, now - lastProgressAtMs,
                g, n, dG, dN, fG, fN,
                flowActive, stalled, ds, dc
        );
    }
}
