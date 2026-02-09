
package com.pa.lcr.lcp;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * DeliveryController
 * ------------------
 * Orchestrateur de livraison pour LCR-II via LCP.
 *
 * Publie:
 *  - onStateChanged(State)
 *  - onFlowStarted(), onFlowStopped()
 *  - onLiveSample(int ds, int dc, double grossL, double netL)   // compat UI actuelle
 *  - onProgress(DeliveryProgress p)                              // progression "métier" (Δ, débit, stalled)
 *  - onGuardReached()
 *  - onError(String, Throwable)
 *
 * Dépendances:
 *  - LcpLink : abstrait l'I/O (framing/CRC/adresses, fenêtre de poll, etc.)
 *
 * IMPORTANT (TODO):
 *  - Renseigner le mapping 0x28 (gross/net + digits) selon "LCR Registers' Fields.xlsx"
 *    dans decodeGrossNetFrom0x28(...).
 *
 * NOTE:
 *  - Ce contrôleur n'arrête JAMAIS la live-loop automatiquement quand le flow=0:
 *    c'est l'APK qui décide (tu l'as demandé). Ici on publie l'info 'stalled' pour l'UI.
 */
public class DeliveryController {

    /* ============================ Types & Events ============================ */

    public enum State {
        IDLE,
        STARTING,
        WAIT_FOR_FLOW,
        FLOW_ACTIVE,
        FINALIZING,
        ENDED,
        ERROR
    }

    public interface DeliveryEvents {
        void onStateChanged(State s);
        void onFlowStarted();
        void onFlowStopped();
        void onLiveSample(int ds, int dc, double grossL, double netL);
        void onGuardReached();
        void onError(String message, Throwable t);

        /** Nouveau (opt-in par défaut): progression "prête à afficher" */
        default void onProgress(DeliveryProgress p) { /* no-op */ }
    }

    /**
     * Progression "métier" prête à consommer côté UI
     * Le SDK fait la conversion REG→L, calcule Δ, débit L/min, flow/stalled.
     */
    public static final class DeliveryProgress {
        public final long tEpochMs;          // now()
        public final long tSinceStartMs;     // depuis le "start" perçu
        public final long tSinceLastDeltaMs; // depuis dernière progression réelle

        public final double grossL;
        public final double netL;
        public final double dGrossL;
        public final double dNetL;

        public final double flowGrossLpm;
        public final double flowNetLpm;

        public final boolean flowActive;     // "ON" si progression (et/ou bit DC si tu veux OR)
        public final boolean stalled;        // stagnation (≥ STALL_MS)

        public final int ds;                 // brut pour debug (0x23)
        public final int dc;                 // brut pour debug (0x23)

        public DeliveryProgress(long tEpochMs, long tSinceStartMs, long tSinceLastDeltaMs,
                                double grossL, double netL, double dGrossL, double dNetL,
                                double flowGrossLpm, double flowNetLpm,
                                boolean flowActive, boolean stalled, int ds, int dc) {
            this.tEpochMs = tEpochMs;
            this.tSinceStartMs = tSinceStartMs;
            this.tSinceLastDeltaMs = tSinceLastDeltaMs;
            this.grossL = grossL;
            this.netL = netL;
            this.dGrossL = dGrossL;
            this.dNetL = dNetL;
            this.flowGrossLpm = flowGrossLpm;
            this.flowNetLpm = flowNetLpm;
            this.flowActive = flowActive;
            this.stalled = stalled;
            this.ds = ds;
            this.dc = dc;
        }
    }

    /* ============================ Configuration ============================ */

    private static final long STALL_MS = 3_000;     // stagnation ≥ 3s (aligné Python)
    private static final double EPS_L  = 0.001;     // 1 mL (clamp anti-quantification)

    /* ============================ Dependencies ============================ */

    private final LcpLink link;
    private final DeliveryEvents cb;
    private final Executor cbExec; // exécuteur pour callbacks (évite de bloquer la loop)

    /* ============================ State ============================ */

    private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);

    private final AtomicBoolean liveLoopRunning = new AtomicBoolean(false);
    private Thread liveLoopThread;

    private volatile long startedAtMs      = 0L;
    private volatile long lastEmitMs       = 0L;
    private volatile long lastProgressAtMs = 0L;

    private volatile double lastGrossL = Double.NaN;
    private volatile double lastNetL   = Double.NaN;

    /* ============================ Ctor ============================ */

    public DeliveryController(LcpLink link, DeliveryEvents cb) {
        this(link, cb, Executors.newSingleThreadExecutor());
    }

    public DeliveryController(LcpLink link, DeliveryEvents cb, Executor callbackExecutor) {
        this.link = Objects.requireNonNull(link, "link");
        this.cb   = Objects.requireNonNull(cb,   "cb");
        this.cbExec = (callbackExecutor != null ? callbackExecutor : Executors.newSingleThreadExecutor());
    }

    /* ============================ Public API ============================ */

    public State getState() { return state.get(); }

    /**
     * Démarrage "OPEN" (aucune présélection de volume).
     * NOTE: Sans la doc "start" exacte, on publie STARTING/WAIT_FOR_FLOW,
     *       et on laisse la détection "flow" (progression) déclencher onFlowStarted côté UI.
     *       Si tu as la commande LCP de "start delivery open", renseigne-la ici (TODO).
     */
    public void startOpenMode(int productId, int startTimeoutMs, int pollMs) {
        setState(State.STARTING);
        startedAtMs = System.currentTimeMillis();
        lastEmitMs = startedAtMs;
        lastProgressAtMs = startedAtMs;
        lastGrossL = Double.NaN;
        lastNetL   = Double.NaN;

        // TODO: envoyer la/les commande(s) de START OPEN si définies dans ta doc LCP.
        //       Exemple imaginaire :
        // sendSimple((byte)0xC0, new byte[]{ (byte)productId }, 2000);

        setState(State.WAIT_FOR_FLOW);

        // L'UI (MainActivity) déclenchera runLiveLoop(...) dans onFlowStarted().
        // Ici, on peut optionnellement lancer une courte surveillance pour publier onFlowStarted
        // dès qu'on observe une progression (Δ > 0) dans les premières secondes.
        // Mais comme ton UI démarre la live loop après onFlowStarted(), on laisse la logique
        // de "détection flow" au début de runLiveLoop (ci-dessous).
    }

    /** Démarrage "PRESET NET" (exemple) — à implémenter si/qd tu as la commande LCP. */
    public void startPresetNet(int productId, double presetLiters, int startTimeoutMs, int pollMs) {
        setState(State.STARTING);
        startedAtMs = System.currentTimeMillis();
        lastEmitMs = startedAtMs;
        lastProgressAtMs = startedAtMs;
        lastGrossL = Double.NaN;
        lastNetL   = Double.NaN;

        // TODO: envoyer la/les commande(s) de START PRESET NET selon la doc LCP (PDF/XLSX).
        // Exemple imaginaire:
        // byte[] payload = buildPresetPayload(productId, presetLiters);
        // sendSimple((byte)0xC1, payload, 2000);

        setState(State.WAIT_FOR_FLOW);
    }

    /**
     * Live-loop : poll 0x23 (DS/DC) + 0x28 (Delivery Status), publie onLiveSample + onProgress.
     * Ne s'arrête QUE via stopLiveLoop() ou changement d'état par endGracefully().
     */
    public void runLiveLoop(int pollMs, boolean reserved, double reserved2) {
        if (!liveLoopRunning.compareAndSet(false, true)) return;
        final int safePollMs = Math.max(50, pollMs);

        liveLoopThread = new Thread(() -> {
            try {
                // Si on arrive ici depuis WAIT_FOR_FLOW, le premier Δ>0 fera passer FLOW_ACTIVE
                boolean flowAnnounced = (getState() == State.FLOW_ACTIVE);

                while (liveLoopRunning.get()) {
                    long loopStart = System.currentTimeMillis();

                    int[] dsdc = null;
                    try {
                        dsdc = link.opMachineStatusFull(); // attendu triple: [?, DS, DC]
                    } catch (Exception e) {
                        fireError("opMachineStatusFull failed", e);
                    }

                    int ds = 0, dc = 0;
                    if (dsdc != null && dsdc.length >= 3) {
                        ds = dsdc[1];
                        dc = dsdc[2];
                    }

                    // 0x28: delivery status → volumes
                    double grossL = 0.0, netL = 0.0;
                    try {
                        byte[] resp28 = sendSimple((byte)0x28, new byte[0], 3000);
                        double[] gn = decodeGrossNetFrom0x28(resp28);
                        grossL = gn[0];
                        netL   = gn[1];
                    } catch (Exception e) {
                        // En cas d'échec, conserve les derniers connus (ou 0.0 si N/A)
                        grossL = (Double.isNaN(lastGrossL) ? 0.0 : lastGrossL);
                        netL   = (Double.isNaN(lastNetL)   ? 0.0 : lastNetL);
                    }

                    // Publication compat UI actuelle
                    fireLiveSample(ds, dc, grossL, netL);

                    // Progression "métier"
                    long now = System.currentTimeMillis();
                    if (Double.isNaN(lastGrossL) || Double.isNaN(lastNetL)) {
                        // Premier sample
                        lastGrossL = grossL; lastNetL = netL;
                        lastEmitMs = now; lastProgressAtMs = now;
                    }
                    double dG = grossL - lastGrossL;
                    double dN = netL   - lastNetL;
                    if (Math.abs(dG) < EPS_L) dG = 0.0;
                    if (Math.abs(dN) < EPS_L) dN = 0.0;

                    boolean progressed = (dG != 0.0 || dN != 0.0);
                    if (progressed) lastProgressAtMs = now;

                    long dtMs = Math.max(1L, now - lastEmitMs);
                    double flowGrossLpm = (dG * 60000.0) / dtMs;
                    double flowNetLpm   = (dN * 60000.0) / dtMs;

                    boolean stalled   = (now - lastProgressAtMs) >= STALL_MS;
                    boolean flowActive = progressed; // tu peux OR avec un bit DC si tu le confirms

                    fireProgress(new DeliveryProgress(
                            now,
                            Math.max(0L, now - startedAtMs),
                            Math.max(0L, now - lastProgressAtMs),
                            grossL, netL, dG, dN,
                            flowGrossLpm, flowNetLpm,
                            flowActive, stalled, ds, dc
                    ));

                    // Transition d'état vers FLOW_ACTIVE la première fois qu'on voit une progression
                    if (!flowAnnounced && progressed && (getState() == State.WAIT_FOR_FLOW || getState() == State.STARTING)) {
                        setState(State.FLOW_ACTIVE);
                        fireFlowStarted();
                        flowAnnounced = true;
                    }

                    // Mémorise
                    lastGrossL = grossL; lastNetL = netL; lastEmitMs = now;

                    // Cadence
                    long spent = System.currentTimeMillis() - loopStart;
                    long sleep = safePollMs - spent;
                    if (sleep > 0) {
                        try { Thread.sleep(sleep); } catch (InterruptedException ignored) {}
                    }
                }

                // Sortie de boucle → flow stoppé (si on était en FLOW_ACTIVE)
                if (getState() == State.FLOW_ACTIVE) {
                    fireFlowStopped();
                }

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

    /** Arrête la live loop (non bloquant). */
    public void stopLiveLoop() {
        liveLoopRunning.set(false);
        Thread t = liveLoopThread;
        if (t != null) {
            try { t.join(500); } catch (InterruptedException ignored) {}
        }
    }

    /**
     * Fin propre : envoie END (0x24), puis poll jusqu'à stabilisation ou timeout.
     * Laisse l'UI décider de lancer l'impression après ENDED (côté APK).
     */
    public void endGracefully(int endTimeoutMs, int pollMs) {
        setState(State.FINALIZING);

        try {
            // END (0x24) — selon tes logs, souvent "0x24 0x02" ou "0x24 0x00".
            // On commence par 0x24 0x02 ; ajuste si nécessaire.
            sendSimple((byte)0x24, new byte[]{ 0x02 }, 3000);
        } catch (Exception e) {
            // Tente une variante "0x24 0x00" si la première refuse
            try {
                sendSimple((byte)0x24, new byte[]{ 0x00 }, 3000);
            } catch (Exception ex) {
                fireError("END (0x24) failed", ex);
                setState(State.ERROR);
                return;
            }
        }

        // Attendre stabilisation: on poll 0x23/0x28 jusqu'à stagnation "longue" ou fin
        long deadline = System.currentTimeMillis() + Math.max(3000, endTimeoutMs);
        long localLastProgressAt = System.currentTimeMillis();
        double gPrev = Double.NaN, nPrev = Double.NaN;

        while (System.currentTimeMillis() < deadline) {
            try {
                int[] dsdc = link.opMachineStatusFull();
                int ds = (dsdc != null && dsdc.length >= 2) ? dsdc[1] : 0;
                int dc = (dsdc != null && dsdc.length >= 3) ? dsdc[2] : 0;

                byte[] r28 = sendSimple((byte)0x28, new byte[0], 3000);
                double[] gn = decodeGrossNetFrom0x28(r28);
                double g = gn[0], n = gn[1];

                fireLiveSample(ds, dc, g, n); // compat UI
                fireProgress(buildProgressForFinalize(gPrev, nPrev, g, n, ds, dc));

                boolean progressed = false;
                if (!Double.isNaN(gPrev) && !Double.isNaN(nPrev)) {
                    double dG = g - gPrev, dN = n - nPrev;
                    if (Math.abs(dG) >= EPS_L || Math.abs(dN) >= EPS_L) progressed = true;
                }
                if (progressed) localLastProgressAt = System.currentTimeMillis();

                // sortie si stagnation "longue"
                if (System.currentTimeMillis() - localLastProgressAt >= STALL_MS) {
                    break;
                }

                gPrev = g; nPrev = n;

                try { Thread.sleep(Math.max(50, pollMs)); } catch (InterruptedException ignored) {}

            } catch (Exception e) {
                fireError("Finalize polling failed", e);
                break;
            }
        }

        setState(State.ENDED);
    }

    /** Demande d'arrêt externe (ex. USB détaché) */
    public void requestStop(String reason) {
        stopLiveLoop();
        fireError("requestStop: " + reason, null);
        setState(State.ERROR);
    }

    /* ============================ Helpers I/O ============================ */

    private byte[] sendSimple(byte cmd, byte[] payload, int timeoutMs) throws Exception {
        // LcpLink encapsule framing/CRC/adresses; on envoie seulement [cmd, data...]
        byte[] msg = new byte[1 + (payload != null ? payload.length : 0)];
        msg[0] = cmd;
        if (payload != null && payload.length > 0) {
            System.arraycopy(payload, 0, msg, 1, payload.length);
        }
        // PollWindow "courte" autour de l'I/O (si ton LcpLink l'exige)
        try { link.openPollWindow(); } catch (Throwable ignored) {}
        try {
            return link.sendRecv(msg, Math.max(500, timeoutMs));
        } finally {
            try { link.closePollWindow(); } catch (Throwable ignored) {}
        }
    }

    /* ============================ Parsing 0x28 (TODO) ============================ */

    /**
     * Décode les volumes Gross/Net (en L) depuis la réponse 0x28.
     *
     * TODO (à RENSEIGNER):
     *  - Selon "LCR Registers' Fields.xlsx", placer les offsets/taille pour G/N et digits.
     *  - Implémenter scaleToLiters(...) en conséquence si digits ≠ 3.
     *
     * Actuellement, renvoie {0.0, 0.0} si mapping non renseigné.
     */
    private double[] decodeGrossNetFrom0x28(byte[] resp28) {
        if (resp28 == null || resp28.length < 6) {
            return new double[]{ 0.0, 0.0 };
        }

        // La réponse inclut l'en-tête LcpLink déjà retiré (payload brut "app").
        // Suivant tes logs typiques:
        //   RX:  ... 80 06  00 01 00 00 01 0D  ...
        // Ici, "80 06" ressemblent à [dir|len], puis des champs.
        // SANS la doc exacte, on ne peut pas deviner fiablement.
        //
        // → Renseigne ci-dessous "OFFSET/Tailles" d'après ton XLSX:

        final int OFFSET_GROSS = -1;  // TODO: offset du gross (ex: 2, 4 octets)
        final int SIZE_GROSS   = -1;  // TODO: taille en octets (ex: 4)
        final int OFFSET_NET   = -1;  // TODO: offset du net   (ex: 6, 4 octets)
        final int SIZE_NET     = -1;  // TODO: taille en octets (ex: 4)
        final int DIGITS_GROSS = 3;   // TODO: digits (ex: 3 → milli-litres)
        final int DIGITS_NET   = 3;   // TODO: digits

        if (OFFSET_GROSS >= 0 && SIZE_GROSS > 0 && OFFSET_NET >= 0 && SIZE_NET > 0
                && resp28.length >= Math.max(OFFSET_GROSS + SIZE_GROSS, OFFSET_NET + SIZE_NET)) {

            long rawGross = readUnsigned(resp28, OFFSET_GROSS, SIZE_GROSS);
            long rawNet   = readUnsigned(resp28, OFFSET_NET,   SIZE_NET);

            double grossL = scaleToLiters(rawGross, DIGITS_GROSS);
            double netL   = scaleToLiters(rawNet,   DIGITS_NET);

            return new double[]{ grossL, netL };
        }

        // Fallback: 0.0 tant que le mapping n'est pas renseigné
        return new double[]{ 0.0, 0.0 };
    }

    private static long readUnsigned(byte[] a, int off, int len) {
        long v = 0;
        for (int i = 0; i < len; i++) {
            v = (v << 8) | (a[off + i] & 0xFFL);
        }
        return v;
    }

    /**
     * Convertit une valeur entière "digits" en litres:
     *  - digits = 0 → déjà en litres (multiplie par 10^0)
     *  - digits = 3 → milli-litres → / 10^3
     *  - etc.
     */
    private static double scaleToLiters(long raw, int digits) {
        if (digits <= 0) return (double) raw;
        double div = 1.0;
        for (int i = 0; i < digits; i++) div *= 10.0;
        return raw / div;
    }

    /* ============================ Callbacks Safe ============================ */

    private void setState(State s) {
        State prev = state.getAndSet(s);
        if (prev != s) fireStateChanged(s);
    }

    private void fireStateChanged(State s) {
        safeExec(() -> cb.onStateChanged(s));
    }

    private void fireFlowStarted() {
        safeExec(cb::onFlowStarted);
    }

    private void fireFlowStopped() {
        safeExec(cb::onFlowStopped);
    }

    private void fireLiveSample(int ds, int dc, double grossL, double netL) {
        safeExec(() -> cb.onLiveSample(ds, dc, grossL, netL));
    }

    private void fireProgress(DeliveryProgress p) {
        safeExec(() -> cb.onProgress(p));
    }

    private void fireError(String message, Throwable t) {
        safeExec(() -> cb.onError(message, t));
    }

    private void safeExec(Runnable r) {
        try { cbExec.execute(r); } catch (Throwable ignored) {}
    }

    /* ============================ Helpers finalize() ============================ */

    private DeliveryProgress buildProgressForFinalize(double gPrev, double nPrev, double g, double n, int ds, int dc) {
        long now = System.currentTimeMillis();
        if (Double.isNaN(gPrev) || Double.isNaN(nPrev)) {
            gPrev = g; nPrev = n;
        }
        double dG = g - gPrev;
        double dN = n - nPrev;
        if (Math.abs(dG) < EPS_L) dG = 0.0;
        if (Math.abs(dN) < EPS_L) dN = 0.0;

        long dtMs = Math.max(1L, now - lastEmitMs);
        double flowGrossLpm = (dG * 60000.0) / dtMs;
        double flowNetLpm   = (dN * 60000.0) / dtMs;

        boolean progressed = (dG != 0.0 || dN != 0.0);
        if (progressed) lastProgressAtMs = now;

        boolean stalled = (now - lastProgressAtMs) >= STALL_MS;
        boolean flowActive = progressed;

        return new DeliveryProgress(
                now,
                Math.max(0L, now - startedAtMs),
                Math.max(0L, now - lastProgressAtMs),
                g, n, dG, dN,
                flowGrossLpm, flowNetLpm,
                flowActive, stalled, ds, dc
        );
    }
}
