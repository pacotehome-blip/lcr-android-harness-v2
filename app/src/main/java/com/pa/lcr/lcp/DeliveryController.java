
package com.pa.lcr.lcp;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * DeliveryController — Orchestration SDK robuste (STRICT 0x23, sans fallback 0x28 en phases critiques)
 * - Wake + SET_FIELD (retry soft) -> RUN (retry soft) -> WAIT_FOR_FLOW (0x23 strict + anti-rebond + filet #44)
 * - LIVE loop (0x23 strict + #44/#45) -> guard optionnel (END #2)
 * - END gracieux (0x23 strict jusqu’à clear FLOW/DEL)
 *
 * Toutes les I/O LCP sont sérialisées via un SingleThreadExecutor.
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
        this.exec   = (executor != null ? executor : Executors.newSingleThreadExecutor());
    }

    public State getState() { return state; }
    private void setState(State s){ state = s; try { events.onStateChanged(s); } catch(Exception ignored){} }
    private static void sleep(int ms){ try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }

    /* ================================================================
       RECIPES HAUT NIVEAU
       ================================================================ */

    /** Mode ouvert (preset=0) : wake -> set product -> clear presets -> RUN 0x00 -> WAIT_FLOW (strict) */
    public void startOpenMode(int productId, long waitFlowTimeoutMs, long pollMs) {
        exec.execute(() -> {
            try {
                setState(State.STARTING);
                wake();                       // réveil léger
                setProductSafe(productId);    // best-effort
                clearPresetsSafe();           // #5=0 puis #6=0 (safe)
                sleep(150);
                wakeStable(2, 600, 80);       // bus calme : 2 ticks 0x23 OK
                sleep(120);
                runAndWaitFlowStrictWithRetry(waitFlowTimeoutMs, pollMs, true /*cmd00*/);
                setState(State.FLOW_ACTIVE);
                events.onFlowStarted();
            } catch (Exception e) {
                fail("startOpenMode: " + e.getMessage(), e);
            }
        });
    }

    /** Mode preset NET : wake -> set product -> write #6 -> #5=0 -> RUN 0x00 -> WAIT_FLOW (strict) */
    public void startPresetNet(int productId, double litres, long waitFlowTimeoutMs, long pollMs) {
        exec.execute(() -> {
            try {
                setState(State.STARTING);
                wake();
                int digits = readDigits();
                int p6 = (int)Math.round(litres * Math.pow(10, digits));
                setProductSafe(productId);      // best-effort
                writeNetPresetSafe(p6);         // #6=p6 + #5=0 (safe)
                sleep(150);
                wakeStable(2, 600, 80);
                sleep(120);
                runAndWaitFlowStrictWithRetry(waitFlowTimeoutMs, pollMs, true /*cmd00*/);
                setState(State.FLOW_ACTIVE);
                events.onFlowStarted();
            } catch (Exception e) {
                fail("startPresetNet: " + e.getMessage(), e);
            }
        });
    }

    /** WAIT_FLOW seul (strict) — si RUN a déjà été envoyé ailleurs */
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

    /** END (#2) puis attendre clear FLOW/DEL (STRICT 0x23, tolérant aux micro-timeouts) */
    public void endGracefully(long timeoutMs, long pollMs) {
        exec.execute(() -> {
            try {
                setState(State.FINALIZING);
                // END avec retry soft léger
                try { link.opIssueCommand(0x02); }
                catch (IOException e1) { softResync(); sleep(150); link.opIssueCommand(0x02); }

                long tEnd = System.currentTimeMillis() + timeoutMs;
                while (System.currentTimeMillis() < tEnd) {
                    try {
                        int[] dsdc = getMachineStrict(3000);
                        int dc = dsdc[1];
                        boolean flow   = (dc & LcpLink.LCRSc_FLOW_ACTIVE) != 0;
                        boolean active = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;
                        if (!flow && !active) { setState(State.ENDED); return; }
                    } catch (Exception pollEx) {
                        // tolérance: on continue à poller 0x23 jusqu'au timeout global
                    }
                    sleep((int)Math.max(100, pollMs));
                }
                setState(State.ENDED);
            } catch (Exception e) {
                fail("endGracefully: " + e.getMessage(), e);
            }
        });
    }

    /* ================================================================
       LIVE loop (STRICT 0x23 + #44/#45) + guard optionnel (sans Double nullable)
       ================================================================ */
    public void runLiveLoop(long pollMs, boolean guardEnabled, double guardMarginLitres) {
        exec.execute(() -> {
            liveRunning = true;
            try {
                int digits = readDigits();
                double scale = Math.pow(10, digits);

                int p5 = readI32OrZero(5);
                int p6 = readI32OrZero(6);
                final boolean guardUseNet = (p6 > 0);
                final double targetLitres = guardUseNet ? (p6 / scale) : (p5 > 0 ? (p5 / scale) : Double.NaN);

                Integer g0 = readI32Nullable(44); if (g0 == null) g0 = 0;
                Integer n0 = readI32Nullable(45); if (n0 == null) n0 = 0;

                boolean guardFired = false;

                while (liveRunning && state == State.FLOW_ACTIVE) {
                    int ds = 0, dc = 0;
                    try {
                        int[] dsdc = getMachineStrict(3000);
                        ds = dsdc[0]; dc = dsdc[1];
                    } catch (Exception pollEx) {
                        // tolérance: on saute ce tick
                        sleep(80);
                        continue;
                    }

                    boolean flow = (dc & LcpLink.LCRSc_FLOW_ACTIVE) != 0;

                    int g = readI32OrFallback(44, g0);
                    int n = readI32OrFallback(45, n0);

                    double gL = g / scale, nL = n / scale;
                    try { events.onLiveSample(ds, dc, gL, nL); } catch(Exception ignored){}

                    if (!flow) { try { events.onFlowStopped(); } catch(Exception ignored){} break; }

                    if (guardEnabled && !Double.isNaN(targetLitres) && !guardFired) {
                        double deliveredL = guardUseNet ? ((n - n0) / scale) : ((g - g0) / scale);
                        if (deliveredL >= (targetLitres + guardMarginLitres)) {
                            try { events.onGuardReached(); } catch(Exception ignored){}
                            // END "opportuniste" (pas critique si rc transitoire)
                            try { link.opIssueCommand(0x02); } catch(Exception ignored) {}
                            guardFired = true;
                        }
                    }

                    sleep((int)Math.max(120, pollMs));
                }
            } catch (Exception e) {
                fail("runLiveLoop: " + e.getMessage(), e);
            } finally {
                liveRunning = false;
            }
        });
    }

    public void stopLiveLoop() { liveRunning = false; }

    /* ================================================================
       Noyau STRICT (0x23 direct, aucun fallback 0x28) + queue 0x7D
       ================================================================ */

    /** Lecture stricte (0x23) : retourne [ds, dc] — gère rc=0x26/0x27 via 0x7D */
    private int[] getMachineStrict(int timeoutMs) throws IOException {
        byte[] rsp = link.sendRecv(new byte[]{ (byte) LcpLink.MSG_GET_MACHINE }, timeoutMs);
        byte[] p = LcpLink.extractPayload(rsp);

        if (p == null || p.length < 1) throw new IOException("GET_MACHINE empty");
        int rc = p[0] & 0xFF;

        if (rc == LcpLink.RC_REQUEST_QUEUED || rc == LcpLink.RC_NO_REQUEST_ACTIVE) {
            // Aller chercher le résultat via 0x7D (STRICT)
            p = waitQueuedStrict(4000, 150); // retourne payload "final"
            if (p == null || p.length < 1) throw new IOException("0x7D empty");
            rc = p[0] & 0xFF;
        }

        if (rc != LcpLink.RC_OK) throw new IOException(String.format("GET_MACHINE rc=0x%02X", rc));
        if (p.length < 8) throw new IOException("GET_MACHINE short");

        int ds = u16be(p, 4);
        int dc = u16be(p, 6);
        return new int[]{ ds, dc };
    }

    /** Attente FLOW (0x23 strict) : anti‑rebond + filet via #44 */
    private void waitFlowStrict(long timeoutMs, long pollMs, boolean acceptFlow, boolean acceptCounts) throws IOException {
        long tEnd = System.currentTimeMillis() + timeoutMs;

        int g0 = 0;
        if (acceptCounts) { try { g0 = readI32OrZero(44); } catch(Exception ignored){} }

        int confirm = 0;
        boolean prevFlow = false;

        while (System.currentTimeMillis() < tEnd) {
            try {
                int[] dsdc = getMachineStrict(3000);
                int dc = dsdc[1];
                boolean flow   = (dc & LcpLink.LCRSc_FLOW_ACTIVE) != 0;
                boolean active = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;
                boolean begin  = (dc & LcpLink.LCRSc_BEGIN_DELIVERY) != 0;

                if (active || begin) return;

                if (acceptFlow) {
                    if (flow) confirm++; else confirm = 0;
                    if (!prevFlow && confirm >= 2) return; // 2 ticks consécutifs
                    prevFlow = flow;
                }

                if (acceptCounts) {
                    try {
                        int g = readI32OrFallback(44, g0);
                        if (g > g0) return;
                    } catch(Exception ignored){}
                }
            } catch (Exception ignored) {
                // micro-glitch : on attend le tick suivant
            }
            sleep((int)pollMs);
        }
        throw new IOException("START_TIMEOUT: FLOW non détecté dans le délai");
    }

    /** RUN + WAIT_FLOW strict avec retry soft */
    private void runAndWaitFlowStrictWithRetry(long waitFlowTimeoutMs, long pollMs, boolean cmd00) throws IOException {
        int runCmd = (cmd00 ? 0x00 : 0x01);
        try {
            link.opIssueCommand(runCmd);
            waitFlowStrict(waitFlowTimeoutMs, pollMs, true, true);
        } catch (IOException e1) {
            softResync();
            sleep(200);
            link.opIssueCommand(runCmd);
            waitFlowStrict(waitFlowTimeoutMs, pollMs, true, true);
        }
    }

    /** Poll 0x7D jusqu’au résultat final (STRICT, sans 0x28) ; retourne le payload "final" */
    private byte[] waitQueuedStrict(int timeoutMs, int pollMs) throws IOException {
        long tEnd = System.currentTimeMillis() + timeoutMs;
        byte[] last = null;
        int tries = 0;

        while (System.currentTimeMillis() < tEnd) {
            byte[] rsp = link.sendRecv(new byte[]{ (byte) LcpLink.MSG_CHECK_REQUEST },
                                       Math.max(1200, pollMs + 800));
            byte[] p = LcpLink.extractPayload(rsp);
            if (p != null && p.length > 0) last = p;

            if (p == null || p.length == 0) { sleep(pollMs); continue; }
            int rc = p[0] & 0xFF;
            if (rc == LcpLink.RC_REQUEST_ABORTED) throw new IOException("Queued aborted");

            if (rc == LcpLink.RC_REQUEST_QUEUED || rc == LcpLink.RC_NO_REQUEST_ACTIVE) {
                tries++;
                if (tries % 6 == 0) { // toutes ~6 itérations (~1s)
                    softResync();
                    sleep(80);
                }
                sleep(pollMs);
                continue;
            }

            // Cas "OK + OK + <payload_orig>" (convention LCP) : on retourne à partir du 2e OK
            if (rc == LcpLink.RC_OK && p.length >= 2 && (p[1] & 0xFF) == LcpLink.RC_OK) {
                byte[] out = new byte[p.length - 1];
                System.arraycopy(p, 1, out, 0, out.length);
                return out;
            }
            return p;
        }
        throw new IOException("Queued timeout (0x7D), last=" + (last==null?"(null)":String.format("%02X", last[0])));
    }

    /* ================================================================
       ROBUSTESSE : Wake + SET_FIELD safe + WakeStable
       ================================================================ */

    private void wake() {
        try { getMachineStrict(1500); } catch(Exception ignored){}
        sleep(120);
    }

    /** Garanti un bus "calme" : okNeeded lectures 0x23 consécutives OK avant de poursuivre */
    private void wakeStable(int okNeeded, int timeoutPerPollMs, int pauseMs) {
        int ok = 0;
        long tEnd = System.currentTimeMillis() + 1500;
        while (System.currentTimeMillis() < tEnd && ok < okNeeded) {
            try { getMachineStrict(timeoutPerPollMs); ok++; } catch(Exception ignored){ ok = 0; }
            sleep(pauseMs);
        }
    }

    /** SET_FIELD #0 (product) — best‑effort : 2 tentatives puis continue si refus */
    private void setProductSafe(int productId) throws IOException {
        if (productId < 1 || productId > 16)
            throw new IllegalArgumentException("ProductNumber doit être 1..16");
        byte v = (byte)((productId - 1) & 0xFF);

        try {
            link.opSetField(0, new byte[]{ v });
            sleep(120);
            return;
        } catch (IOException e1) {
            softResync();
            sleep(150);
            try {
                link.opSetField(0, new byte[]{ v });
                sleep(120);
                return;
            } catch (IOException e2) {
                // Best-effort : log et continue avec le produit courant
                try { events.onError("SET_FIELD #0 ignoré (best-effort): " + e2.getMessage(), e2); } catch(Exception ignored){}
            }
        }
    }

    private void clearPresetsSafe() throws IOException {
        opSetFieldSafe(5, i32be(0), "SET_FIELD #5 (gross=0)");
        sleep(120);
        opSetFieldSafe(6, i32be(0), "SET_FIELD #6 (net=0)");
    }

    private void writeNetPresetSafe(int raw) throws IOException {
        opSetFieldSafe(6, i32be(raw), "SET_FIELD #6 (net)");
        sleep(120);
        opSetFieldSafe(5, i32be(0),   "SET_FIELD #5 (gross=0)");
    }

    /** Écriture résiliente : try -> (softResync + pause) -> retry unique */
    private void opSetFieldSafe(int field, byte[] data, String label) throws IOException {
        try {
            link.opSetField(field, data);
        } catch (IOException e1) {
            softResync();
            sleep(150);
            link.opSetField(field, data);
        }
        sleep(120);
    }

    private void softResync() {
        try { link.sendRecv(new byte[]{0x00}, 1200); } catch(Exception ignored){}
    }

    /* ================================================================
       Helpers : décimales (#39), lectures champs & endianness, fail()
       ================================================================ */

    private static int digitsFromIndex(int idx) {
        switch (idx) {
            case 0:  return 2; // Hundredths
            case 1:  return 1; // Tenths
            case 2:  return 0; // Whole
            case 3:  return 3; // Thousandths
            default: return 1;
        }
    }

    /** Lit #39 et traduit en nombre de décimales (0..3). Par défaut: 1 (tenths). */
    private int readDigits() {
        try {
            byte[] v = link.opGetField(39);
            int idx = (v != null && v.length > 0) ? (v[0] & 0xFF) : 1;
            return digitsFromIndex(idx);
        } catch (Exception e) { return 1; }
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

    private int readI32OrZero(int field) {
        try {
            byte[] d = link.opGetField(field);
            return ((d[0] & 0xFF) << 24) | ((d[1] & 0xFF) << 16) | ((d[2] & 0xFF) << 8) | (d[3] & 0xFF);
        } catch (Exception e) { return 0; }
    }

    private Integer readI32Nullable(int field) {
        try {
            byte[] d = link.opGetField(field);
            return ((d[0] & 0xFF) << 24) | ((d[1] & 0xFF) << 16) | ((d[2] & 0xFF) << 8) | (d[3] & 0xFF);
        } catch (Exception e) { return null; }
    }

    private int readI32OrFallback(int field, int fb) {
        try {
            byte[] d = link.opGetField(field);
            return ((d[0] & 0xFF) << 24) | ((d[1] & 0xFF) << 16) | ((d[2] & 0xFF) << 8) | (d[3] & 0xFF);
        } catch (Exception e) { return fb; }
    }

    private void fail(String msg, Throwable t) {
        setState(State.ERROR);
        try { events.onError(msg, t); } catch(Exception ignored){}
    }

    /* ================================================================
       Callbacks SDK
       ================================================================ */
    public interface DeliveryEvents {
        default void onStateChanged(State s) {}
        default void onFlowStarted() {}
        default void onFlowStopped() {}
        default void onLiveSample(int ds, int dc, double grossL, double netL) {}
        default void onGuardReached() {}
        default void onTicketPending() {}
        default void onError(String message, Throwable cause) {}
    }
}
