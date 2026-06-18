package com.pa.lcr.lcp;

import com.pa.lcr.lcp.storage.DeliveryLogStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayDeque;
/**
 * DeliveryController
 *
 * Correctifs ARMED (API OneShot):
 * - ARMED n'est PAS "RUNNING_PAUSED" (car deliveryActive=0). On reste CONNECTED et on expose armed=1.
 * - api_deliveryContinue() doit envoyer RUN même si l'état UI n'est pas RUNNING_PAUSED (cas ARMED).
 * - api_deliveryJobGet(): si deliveryActive=0 et jamais actif -> PENDING + armed=1 + state CONNECTED + live_status ARMED.
 *
 * Correctifs champs binaires:
 * - ticket_no DOIT etre le TicketNumber du registre (#23) et donc lu en U32 (4 bytes).
 * - sale_no DOIT etre le SaleNumber du registre (#22) et donc lu en U32 (4 bytes).
 *
 * FIX (concordance compteur/UI vs API/Field Service):
 * - Quantité envoyée = compteurs affichés (#44/#45) scaled avec #39.
 * - AUCUNE baseline dépendante du timing de polling.
 *
 * AJOUT (API "UI-like"):
 * - Pause WAIT_FLOW_ON qui se désactive dès que la progression démarre.
 * - Pause FLOW_OFF_CONFIRMING/FLOW_OFF_CONFIRMED basée sur stagnation >= 10s (NO_FLOW_CONFIRM_MS).
 * - Si FLOW_OFF_CONFIRMED et preset non atteint => demande CONTINUER ou TERMINER.
 * - Throttling JobGet pour éviter le polling trop rapide (cache + next_poll_ms).
 *
 * ✅ Correctifs ajoutés:
 * - Évite "Continue 2 fois" : après Continue, on met à jour lastOkData pour servir RUNNING sous RATE_LIMIT.
 * - LCP lock global UI+API : sérialise les transactions LCP et réduit les rc=0x26 liés aux chevauchements.
 * - Cache "ENDING" immédiat lors de Terminate pour stabilité sous RATE_LIMIT.
 * - Filet "startingAfterContinue" dans JobGet (RUN demandé mais deliveryActive pas encore monté).
 *
 * ✅ SQLite transactions (optionnel, baseline-safe):
 * - Inscriptions dans delivery_summary / delivery_attempt / delivery_event via DeliveryLogStore.
 * - Si store non injecté: no-op (aucun impact).
 *
 * ✅ B+ TickBus (change-driven):
 * - Incrémente un seq dès que NET/GROSS OU dev/prn OU delStatus/delCode OU state change.
 * - Expose api_tickWait(sinceSeq, waitMs) (long-poll cache-only) pour Field Service Mobile.
 */
public final class DeliveryController implements DeliveryControllerPort {

    // =========================================================
    // OPTIONAL SQLite store (injected)
    // =========================================================
    private volatile DeliveryLogStore logStore;

    /** Injection optionnelle. Si non appelé => aucune écriture DB (baseline-safe). */
    public void setLogStore(DeliveryLogStore store) {
        this.logStore = store;
    }

    // -------------------------
    // JSON safe put / safe copy
    // -------------------------
    private static void safeJsonPut(JSONObject o, String k, Object v) {
        if (o == null) return;
        try { o.put(k, v); } catch (Exception ignore) {}
    }

    // ✅ FIX build: org.json.JSONObject(String) throws checked JSONException -> must be caught.
    private static JSONObject safeJsonCopy(JSONObject src) {
        if (src == null) return null;
        try {
            return new JSONObject(src.toString());
        } catch (JSONException e) {
            JSONObject o = new JSONObject();
            try {
                o.put("copy_error", (e.getMessage() != null) ? e.getMessage() : "JSONException");
            } catch (JSONException ignored) {}
            return o;
        }
    }

// =========================================================
// ✅ A2: Tagging erreurs par niveau dans les payloads API
// - level: MEDIA | TRANSPORT | LCP | REGISTER | DELIVERY | UNKNOWN
// - where : contexte court
// - detail: message technique
// =========================================================
private static void tagErrorLevel(JSONObject d, String level, String where, Exception e) {
    // ✅ A3.1: TRANSPORT déterministe si TransportException
    boolean isTransport = false;
    try {
        isTransport = (e instanceof LcpLink.TransportException);
    } catch (Exception ignored) {}

    String lvl = level;
    if (isTransport) {
        lvl = "TRANSPORT";
    } else if (lvl == null || lvl.trim().isEmpty()) {
        lvl = "UNKNOWN";
    }

    try { safeJsonPut(d, "level", lvl); } catch (Exception ignored) {}
    try { safeJsonPut(d, "where", where); } catch (Exception ignored) {}

    if (e != null) {
        String m0 = (e.getMessage() != null) ? e.getMessage() : e.getClass().getSimpleName();
        try { safeJsonPut(d, "detail", m0); } catch (Exception ignored) {}

        // classification simple (help debug)
        try {
            if (isTransport) {
                safeJsonPut(d, "class", "TRANSPORT");
            } else if (m0.contains("rc=0x26") || m0.contains("rc=0X26")
                    || m0.contains("Queued timeout") || m0.contains("Timeout waiting LCP")) {
                safeJsonPut(d, "class", "LCP");
            } else {
                safeJsonPut(d, "class", "UNKNOWN");
            }
        } catch (Exception ignored) {}
    }
}

// =========================================================
// ✅ REPRO (always-on, DB) - logs de changements (sans TX/RX)
// 1 attempt long-vivant: __REPRO__ / REPRO-<utc>
// =========================================================
private volatile long reproAttemptId = 0L;
private volatile String reproTicketKey = null;
private final ArrayDeque<PendingReproEvent> reproPending = new ArrayDeque<>();
private static final int REPRO_PENDING_MAX = 200;

private static final class PendingReproEvent {
    final String level, type, message, dataJson;
    PendingReproEvent(String level, String type, String message, String dataJson) {
        this.level = level;
        this.type = type;
        this.message = message;
        this.dataJson = dataJson;
    }
}

private void reproStartIfNeeded() {
    DeliveryLogStore store = this.logStore;
    if (store == null) return;
    if (reproAttemptId > 0) return;

    if (reproTicketKey == null || reproTicketKey.trim().isEmpty()) {
        reproTicketKey = "REPRO-" + msToUtcIso(System.currentTimeMillis());
    }

    // Summary repère (best-effort)
    store.upsertSummaryAsync("__REPRO__", reproTicketKey, null,
            "REPRO_OPEN", DeliveryLogStore.SOURCE_UI, null, null, null);

    store.openAttemptAsync("__REPRO__", reproTicketKey, DeliveryLogStore.SOURCE_UI, null, attemptId -> {
        reproAttemptId = attemptId;
        // Flush pending
        try {
            while (!reproPending.isEmpty()) {
                PendingReproEvent e = reproPending.removeFirst();
                store.addEventAsync(attemptId, e.level, e.type, e.message, e.dataJson);
            }
        } catch (Exception ignored) {}
    });
}

private void reproStopBestEffort(String reason) {
    DeliveryLogStore store = this.logStore;
    long id = reproAttemptId;
    reproAttemptId = 0L;
    if (store == null || id <= 0) return;

    JSONObject d = new JSONObject();
    safeJsonPut(d, "reason", (reason != null) ? reason : "");
    safeJsonPut(d, "media", resolveActiveMedia());
    safeJsonPut(d, "transport_key", (link != null) ? link.getTransportKey() : JSONObject.NULL);
    safeJsonPut(d, "to", (link != null) ? (link.getToAddr() & 0xFF) : -1);
    safeJsonPut(d, "from", (link != null) ? (link.getHostAddr() & 0xFF) : -1);

    store.addEventAsync(id, DeliveryLogStore.LEVEL_INFO, "APP_STOP", "Repro session stop", d.toString());
    store.closeAttemptAsync(id, "DONE", d.toString(), null);

    store.upsertSummaryAsync("__REPRO__", reproTicketKey, null,
            "REPRO_CLOSED", DeliveryLogStore.SOURCE_UI, null, null, null);
    try { reproPending.clear(); } catch (Exception ignored) {}
}

private void reproEvent(String level, String type, String message, JSONObject data) {
    DeliveryLogStore store = this.logStore;
    if (store == null) return;

    reproStartIfNeeded();

    String json = (data != null) ? data.toString() : null;
    long id = reproAttemptId;

    if (id > 0) {
        store.addEventAsync(id, level, type, message, json);
        return;
    }

    // Attempt pas encore prêt -> buffer
    try {
        if (reproPending.size() >= REPRO_PENDING_MAX) reproPending.removeFirst();
        reproPending.addLast(new PendingReproEvent(level, type, message, json));
    } catch (Exception ignored) {}
}


    // =========================================================
    // ✅ LCP global lock (UI LIVE + API)
    // Empêche les chevauchements de transactions LCP (source majeure de rc=0x26).
    // =========================================================
    private final Object lcpOpLock = new Object();

    private interface LcpOp<T> { T run() throws Exception; }

    private <T> T withLcpLock(LcpOp<T> op) throws Exception {
        synchronized (lcpOpLock) { return op.run(); }
    }

    private void withLcpLockVoid(LcpOp<Void> op) throws Exception {
        synchronized (lcpOpLock) { op.run(); }
    }

    // Wrappers LCP
    private int[] lcpDeliveryStatus() throws Exception { return withLcpLock(() -> link.opDeliveryStatus()); }
    private LcpLink.MachineStatus lcpMachineStatus() throws Exception { return withLcpLock(() -> link.opGetMachineStatus()); }
    private byte[] lcpGetField(int field) throws Exception { return withLcpLock(() -> link.opGetField(field)); }

    private void lcpSetField(int field, byte[] value) throws Exception {
        withLcpLockVoid(() -> { link.opSetField(field, value); return null; });
    }

    private void lcpIssueCommand(int cmd) throws Exception {
        withLcpLockVoid(() -> { link.opIssueCommand(cmd); return null; });
    }

    // Champs LCR
    private static final int FIELD_ACTIVE_PRODUCT = 0;
    private static final int FIELD_PRESET_NET = 6;
    private static final int FIELD_DECIMALS = 39;
    private static final int FIELD_GROSS_COUNT = 44;
    private static final int FIELD_NET_COUNT = 45;

    // Champs LCR (API / reporting)
    private static final int FIELD_GROSS_TOTAL = 17;
    private static final int FIELD_NET_TOTAL = 18;
    private static final int FIELD_SALE_NUMBER = 22;  // U32
    private static final int FIELD_TICKET_NUMBER = 23; // U32
    private static final int FIELD_SERIAL_ID = 80;

    // Commands
    private static final int CMD_RUN = 0x00;
    private static final int CMD_END = 0x02;
    private static final int CMD_PRINT_LAST_TICKET = 0x06;

    // Bits delCode (0x28)
    private static final int DC_TICKET_PENDING  = 0x0001;
    private static final int DC_FLOW_ACTIVE     = 0x0004;
    private static final int DC_DELIVERY_ACTIVE = 0x0008;

    // OFF conservateur (détection stagnation)
    private static final long NO_FLOW_CONFIRM_MS = 10_000;

    // START retry
    private static final long START_RETRY_WINDOW_MS = 20_000;
    private static final long START_RETRY_POLL_MS = 200;

    // Ticket loop
    private static final long TICKET_DEVICE_LOOP_MS = 30_000;

    // LIVE backoff
    private static final long LIVE_BASE_MS = 300;

    // ✅ Intervalle live tick — configurable selon profil registre
    // LCR-II (19200 baud): 200ms, LC3 (9600 baud): 800ms
    private volatile long liveTickIntervalMs = LIVE_BASE_MS;
    private static final long LIVE_MAX_MS = 2000;
    private static final long LIVE_LOG_THROTTLE_MS = 1000;

    // CONTINUER: fenêtre de grâce 30s
    private static final long CONTINUE_GRACE_MS = 30_000;
    private static final long CONTINUE_DEBOUNCE_MS = 1500;

    // API JobGet throttling
    private static final long API_JOB_MIN_POLL_MS = 900;
    private static final long API_JOB_BACKOFF_ON_FAIL_MS = 1200;

    private final LcpLink link;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    // ✅ Live tick automatique pendant RUNNING_FLOWING/PAUSED
    private final java.util.concurrent.ScheduledExecutorService liveTickScheduler =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
    private volatile java.util.concurrent.ScheduledFuture<?> liveTickFuture = null;
    private Listener listener;

    private volatile DeliveryState state = DeliveryState.DISCONNECTED;
    private volatile int cachedDigits = -1;

    
 // ✅ Cache du dernier NUM reçu via API (numero_livraison) pour reconstruire delivery_uid côté UI
 private volatile String lastNumeroLivraison = null;

 // ✅ Media actif (usb/bt) - best-effort (déduit du transport si non fixé)
 private volatile String activeMedia = null;

 // ===== Livraison métier (auto-clôture) =====
 private volatile boolean deliveryInProgress = false;
 private volatile Long currentDeliveryAttemptId = null;
 private volatile long deliveryStartMs = 0L;
// LIVE
    private volatile boolean flowOffStable = false;
    private volatile boolean sawFlowOnOnce = false;
    private volatile long flowOffStartMs = 0L;
    private volatile long lastCountsChangeMs = 0L;
    private volatile int lastGrossRaw = -1;
    private volatile int lastNetRaw = -1;
    private volatile boolean stopped = false;
    private volatile boolean txRxEnabled = false;
    private volatile boolean logTsEnabled = false;
    private volatile long lastResyncMs = 0L;

    // Pas de chevauchement LIVE
    private final AtomicBoolean liveInFlight = new AtomicBoolean(false);
    private final ThreadLocal<Boolean> inLiveSample = new ThreadLocal<>();

    // Ticket pending: anti-réimpression
    private final java.util.concurrent.atomic.AtomicBoolean ticketPrintInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile long ticketPrintStartMs = 0L;

    // Backoff state
    private volatile long liveBackoffMs = LIVE_BASE_MS;
    private volatile long liveNextAllowedMs = 0L;
    private volatile long liveLastSkipLogMs = 0L;

    // Grâce 30s après Continuer
    private volatile long continueGraceUntilMs = 0L;
    private volatile long lastContinueClickMs = 0L;

    private static final ThreadLocal<SimpleDateFormat> IO_DF =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("HH:mm:ss.SSS", Locale.CANADA_FRENCH));

    // =========================================================
    // ✅ B+ TickBus (change-driven) : net/gross OR dev/prn OR delStatus/delCode OR state
    // =========================================================
    private static final class LastTick {
        final long seq;
        final long tsMs;

        final double net;
        final double gross;

        final int devStatus;
        final int prnStatus;
        final int delStatus;
        final int delCode;

        final String stateName; // DeliveryState.name()

        LastTick(long seq, long tsMs, double net, double gross,
                 int devStatus, int prnStatus, int delStatus, int delCode,
                 String stateName) {
            this.seq = seq;
            this.tsMs = tsMs;
            this.net = net;
            this.gross = gross;
            this.devStatus = devStatus;
            this.prnStatus = prnStatus;
            this.delStatus = delStatus;
            this.delCode = delCode;
            this.stateName = stateName;
        }
    }

    // seq monotone: incrémenté uniquement sur changement
    private final java.util.concurrent.atomic.AtomicLong tickSeq =
            new java.util.concurrent.atomic.AtomicLong(0);

    // dernier tick publié
    private volatile LastTick lastTick = null;

    // lock de synchronisation pour long-poll API (wait/notify)
    private final Object tickLock = new Object();

    // tolérance changement (litres) -> évite faux changements dûs au float
    private static final double TICK_EPS = 0.0005; // 0.5 mL si unité=L

    // dernier dev/prn connu (mis à jour quand on lit machine status)
    private volatile int lastDevStatusKnown = -1;
    private volatile int lastPrnStatusKnown = -1;

    private static boolean changed(double a, double b) {
        return Math.abs(a - b) > TICK_EPS;
    }

    private boolean tickChanged(LastTick prev,
                                double net, double gross,
                                int devStatus, int prnStatus,
                                int delStatus, int delCode,
                                DeliveryState st) {
        if (prev == null) return true;
        if (changed(prev.net, net)) return true;
        if (changed(prev.gross, gross)) return true;
        if (prev.devStatus != devStatus) return true;
        if (prev.prnStatus != prnStatus) return true;
        if (prev.delStatus != delStatus) return true;
        if (prev.delCode != delCode) return true;

        String sn = (st == null) ? "null" : st.name();
        return !sn.equals(prev.stateName);
    }

    /**
     * Publie un tick si (B+) net/gross OU dev/prn OU delStatus/delCode OU state change.
     *
     * devStatus/prnStatus : si on passe -1, on réutilise le dernier connu (ou le prev).
     */
    private void publishTickIfChanged(double net, double gross,
                                      int devStatus, int prnStatus,
                                      int delStatus, int delCode,
                                      DeliveryState st) {

        LastTick prev = lastTick;

        // Normaliser dev/prn si inconnus
        if (devStatus < 0) {
            if (prev != null) devStatus = prev.devStatus;
            else if (lastDevStatusKnown >= 0) devStatus = lastDevStatusKnown;
            else devStatus = 0;
        }
        if (prnStatus < 0) {
            if (prev != null) prnStatus = prev.prnStatus;
            else if (lastPrnStatusKnown >= 0) prnStatus = lastPrnStatusKnown;
            else prnStatus = 0;
        }

        if (!tickChanged(prev, net, gross, devStatus, prnStatus, delStatus, delCode, st)) return;

        long seq = tickSeq.incrementAndGet();
        long now = System.currentTimeMillis();

        LastTick t = new LastTick(
                seq, now,
                net, gross,
                devStatus, prnStatus,
                delStatus, delCode,
                (st == null) ? "null" : st.name()
        );

        lastTick = t;

        // réveiller tout long-poll API en attente
        synchronized (tickLock) {
            tickLock.notifyAll();
        }
    }

    private JSONObject buildTickJsonSnapshot() {
        LastTick t = lastTick;
        JSONObject d = new JSONObject();
        if (t == null) {
            safeJsonPut(d, "has_tick", 0);
            safeJsonPut(d, "seq", 0);
            return d;
        }
        safeJsonPut(d, "has_tick", 1);
        safeJsonPut(d, "seq", t.seq);
        safeJsonPut(d, "ts_ms", t.tsMs);
        safeJsonPut(d, "tick_age_ms", Math.max(0L, System.currentTimeMillis() - t.tsMs));
        safeJsonPut(d, "net", t.net);
        safeJsonPut(d, "gross", t.gross);
        safeJsonPut(d, "devStatus", t.devStatus);
        safeJsonPut(d, "prnStatus", t.prnStatus);
        safeJsonPut(d, "delStatus", t.delStatus);
        safeJsonPut(d, "delCode", t.delCode);
        safeJsonPut(d, "state", t.stateName);
        // ✅ jobId dans le tick pour FieldService
        if (lastActiveJobId != null) safeJsonPut(d, "jobId", lastActiveJobId);
        return d;
    }

    /** API: snapshot immédiat (cache-only). */
    public ApiResult api_tickSnapshot() {
        JSONObject d = buildTickJsonSnapshot();
        safeJsonPut(d, "changed", 1);
        safeJsonPut(d, "timeout", 0);
        return ApiResult.ok("Tick: 1 - SNAPSHOT", d);
    }

    /**
     * API: long-poll (cache-only).
     * sinceSeq: dernière séquence vue par le client.
     * waitMs: max attente serveur (ex: 25000). Clamp à [0..30000].
     */
    public ApiResult api_tickWait(long sinceSeq, long waitMs) {
        if (waitMs <= 0) waitMs = 25_000;
        if (waitMs > 30_000) waitMs = 30_000;

        long deadline = System.currentTimeMillis() + waitMs;

        // 1) réponse immédiate si tick déjà plus récent
        LastTick cur = lastTick;
        if (cur != null && cur.seq > sinceSeq) {
            JSONObject d = buildTickJsonSnapshot();
            safeJsonPut(d, "changed", 1);
            safeJsonPut(d, "timeout", 0);
            safeJsonPut(d, "since_seq", sinceSeq);
            return ApiResult.ok("Tick: 1 - CHANGED", d);
        }

        // 2) attente bloquante (sans LCP IO)
        synchronized (tickLock) {
            while (System.currentTimeMillis() < deadline) {
                try {
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) break;
                    tickLock.wait(Math.min(left, 1000));
                } catch (InterruptedException ignored) {
                    break;
                }

                LastTick t = lastTick;
                if (t != null && t.seq > sinceSeq) {
                    JSONObject d = buildTickJsonSnapshot();
                    safeJsonPut(d, "changed", 1);
                    safeJsonPut(d, "timeout", 0);
                    safeJsonPut(d, "since_seq", sinceSeq);
                    return ApiResult.ok("Tick: 1 - CHANGED", d);
                }
            }
        }

        // 3) timeout -> retourne snapshot courant (ou empty), marqué timeout
        JSONObject d = buildTickJsonSnapshot();
        safeJsonPut(d, "changed", 0);
        safeJsonPut(d, "timeout", 1);
        safeJsonPut(d, "wait_ms", waitMs);
        safeJsonPut(d, "since_seq", sinceSeq);
        return ApiResult.ok("Tick: 0 - TIMEOUT", d);
    }

    // =========================
    // API-Face (jobs + cache)
    // =========================
    private static final class ApiJob {
        final String id;
        final long startedAtMs;

        volatile boolean done = false;
        volatile String state; // PENDING/RUNNING/DONE/ERROR
        volatile String err;

        // trace attempt id (SQLite)
        volatile long attemptId = 0L;

        // Contexte
        volatile String numeroLivraison;
        volatile String deliveryUid;
        volatile String compartment;
        volatile int productNumber;

        
 // Media utilisé pour ce job (usb/bt)
 volatile String media;
// Preset
        volatile double presetNetL_requested;
        volatile double presetNetL_applied;
        volatile long presetRawU32;
        volatile int decimals;

        // Identifiants registre
        volatile String saleNo;   // #22 U32
        volatile String ticketNo; // #23 U32
        volatile String serialId; // #80

        // Timing
        volatile long startMs = 0L;
        volatile long endMs = 0L;

        // Baseline flags
        volatile boolean sawDeliveryActiveOnce = false;
        volatile boolean baselineCaptured = false;
        volatile int grossStartRaw = 0;
        volatile int netStartRaw = 0;

        // End
        volatile int grossEndRaw = 0;
        volatile int netEndRaw = 0;

        // =========================
        // DISPLAY tick (delivery counters #44/#45 - register UI path)
        // =========================
        volatile long displayTickMs = 0L;
        volatile long displayTickSeq = 0L;
        volatile int displayGrossRaw = 0; // #44
        volatile int displayNetRaw   = 0; // #45
        volatile double displayGrossL = 0.0;
        volatile double displayNetL   = 0.0;


        // Totaux
        volatile int grossTotalRaw = 0;
        volatile int netTotalRaw = 0;

        // Pause tracking
        volatile int lastGrossSeen = Integer.MIN_VALUE;
        volatile int lastNetSeen = Integer.MIN_VALUE;
        volatile long lastCountsChangeMs = 0L;
        volatile boolean sawFlowOnOnceJob = false;
        volatile long continueGraceUntilMs = 0L;

        // JobGet throttling
        volatile long nextAllowedReadMs = 0L;
        volatile JSONObject lastOkData = null;
        volatile String lastOkMsg = null;

        ApiJob(String id) {
            this.id = id;
            this.startedAtMs = System.currentTimeMillis();
            this.state = "PENDING";
        }
    }

    // ✅ Global job registry: survive controller rebind/recreate (prevents JOB_NOT_FOUND after DONE)
    private static final Map<String, ApiJob> apiJobs = new ConcurrentHashMap<>();
    private volatile String lastActiveJobId = null; // ✅ dernier jobId actif
    
    public DeliveryController(LcpLink link) {
        this.link = link;
        // ✅ Adapter l'intervalle live tick au profil du registre (LCR-II vs LC3)
        this.liveTickIntervalMs = link != null ? link.getRecommendedLiveIntervalMs() : LIVE_BASE_MS;
    }

    private boolean isStopped() {
        return stopped || link == null || link.isClosed();
    }

    // ====== DeliveryControllerPort ======
    @Override public DeliveryState getState() { return state; }
    @Override public boolean isPaused() { return state == DeliveryState.RUNNING_PAUSED; }

    @Override
    public boolean isDeliveryActive() {
        return state == DeliveryState.RUNNING_FLOWING || state == DeliveryState.RUNNING_PAUSED;
    }

    @Override public boolean isFlowOffStable() { return flowOffStable; }

    @Override
    public long getFlowOffAgeMs() {
        if (!sawFlowOnOnce) return 0L;
        if (flowOffStartMs <= 0L) return 0L;
        long now = System.currentTimeMillis();
        return Math.max(0L, now - flowOffStartMs);
    }

    // ✅ v7: exposer le nombre de décimales (#39) pour le formatage UI
    public int getDisplayDigits() {
        return (cachedDigits >= 0 ? cachedDigits : 3);
    }


    // ====== Logging ======
    @Override
    public void setLogTimestampsEnabled(boolean enabled) {
        io.execute(() -> logTsEnabled = enabled);
    }

    private String ioTs() {
        return IO_DF.get().format(new Date(System.currentTimeMillis()));
    }

    private void emitLog(String line) {
        Listener l = this.listener;
        if (l == null) return;
        if (logTsEnabled) {
            if (line != null && line.startsWith("[IO ")) l.onLog(line);
            else l.onLog("[IO " + ioTs() + "] " + line);
        } else {
            l.onLog(line);
        }
    }

    private static String stripIoPrefix(String s) {
        if (s == null) return "";
        if (!s.startsWith("[IO ")) return s;
        int idx = s.indexOf("] ");
        if (idx > 0 && idx + 2 <= s.length()) return s.substring(idx + 2);
        return s;
    }

    private static String msToUtcIso(long ms) {
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT);
        df.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return df.format(new Date(ms));
    }

    private static boolean isTxRxLine(String raw) {
        return raw.startsWith("TX:") || raw.startsWith("RX:") || raw.startsWith("↳");
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
        if (listener == null) {
            link.setTraceSink(null);
            return;
        }
        link.setTraceSink(line -> {
            String raw = stripIoPrefix(line);
            if (!txRxEnabled && isTxRxLine(raw)) return;
            if (Boolean.TRUE.equals(inLiveSample.get()) && isTxRxLine(raw)) return;
            emitLog(line);
        });
    }

    @Override
    public void setTxRxLoggingEnabled(boolean enabled) {
        io.execute(() -> {
            if (isStopped()) return;
            txRxEnabled = enabled;
            emitLog("[LOG] TX/RX " + (enabled ? "ON" : "OFF"));
        });
    }

    // =========================
    // Connected / init / shutdown
    // =========================
    @Override
    public void initialize() {
        io.execute(() -> {
            if (isStopped()) return;
            setState(DeliveryState.CONNECTED);
 // Auto close delivery for END path
 try { onDeliveryFinishedIfNeeded("END"); } catch (Exception ignored) {}

            emitLog("LCP pret (sans refresh automatique)");
            if (listener != null) listener.onLiveStatus("LIVE: CONNECTED - (pret)");

            // ✅ Sync date/heure tablette → registre à chaque connexion
            try {
                if (link instanceof com.pa.lcr.lcp.LcpLink) {
                    ((com.pa.lcr.lcp.LcpLink) link).opSyncDateTime();
                    emitLog("[DATETIME] Sync date/heure OK");
                }
            } catch (Exception e) {
                emitLog("[DATETIME] Sync date/heure ERR: " + e.getMessage());
            }

// ✅ REPRO: APP_START best-effort (sans TX/RX)
try {
    JSONObject d = new JSONObject();
    safeJsonPut(d, "state", state.name());
    safeJsonPut(d, "media", resolveActiveMedia());
    safeJsonPut(d, "transport_key", (link != null) ? link.getTransportKey() : JSONObject.NULL);
    safeJsonPut(d, "to", (link != null) ? (link.getToAddr() & 0xFF) : -1);
    safeJsonPut(d, "from", (link != null) ? (link.getHostAddr() & 0xFF) : -1);
    reproEvent(DeliveryLogStore.LEVEL_INFO, "APP_START", "Controller initialize", d);
} catch (Exception ignored) {}

        });
    }

    @Override public void shutdown() { shutdown(true); }

    @Override
    public void shutdown(boolean closeTransport) {
        stopped = true;

        // ✅ Arrêter la boucle live tick
        try {
            if (liveTickFuture != null) { liveTickFuture.cancel(false); liveTickFuture = null; }
            liveTickScheduler.shutdownNow();
        } catch (Exception ignored) {}

// ✅ REPRO: close session best-effort
try { reproStopBestEffort(closeTransport ? "shutdown/closeTransport" : "shutdown/logicOnly"); }
catch (Exception ignored) {}

        try { link.setTraceSink(null); } catch (Exception ignored) {}
        try { io.shutdownNow(); } catch (Exception ignored) {}
        setState(DeliveryState.DISCONNECTED);

        if (listener != null) {
            listener.onLiveStatus("LIVE: DISCONNECTED");
            listener.onLog(closeTransport ? "[LINK] Controller stopped / transport closed"
                    : "[LINK] Controller stopped (logic only)");
        }

        if (closeTransport) {
            try { link.close(); } catch (Exception ignored) {}
        } else {
            try { link.softClose(); } catch (Exception ignored) {}
        }
    }

    private void setState(DeliveryState s) {
        if (state == s) return;
        DeliveryState prevState = state;
        state = s;

        // ✅ Live tick automatique — seulement pendant RUNNING_FLOWING (flow ON)
        // Arrêt sur RUNNING_PAUSED, CONNECTED, ENDING, etc.
        if (s == DeliveryState.RUNNING_FLOWING) {
            if (liveTickFuture == null || liveTickFuture.isDone()) {
                liveTickFuture = liveTickScheduler.scheduleWithFixedDelay(
                    () -> {
                        try {
                            if (!isStopped()) requestLiveSampleFast();
                        } catch (Exception ignored) {}
                    },
                    0, liveTickIntervalMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        } else {
            if (liveTickFuture != null) {
                liveTickFuture.cancel(false);
                liveTickFuture = null;
            }
        }
 // Auto close delivery on end-of-delivery transitions
 if ((prevState == DeliveryState.RUNNING_FLOWING || prevState == DeliveryState.RUNNING_PAUSED)
         && s == DeliveryState.CONNECTED) {
     onDeliveryFinishedIfNeeded("FSM");
 }


        if (listener != null) listener.onStateChanged(s);

// ✅ REPRO: transition FSM (sans TX/RX)
try {
    JSONObject d = new JSONObject();
    safeJsonPut(d, "from", (prevState != null) ? prevState.name() : "null");
    safeJsonPut(d, "to", (s != null) ? s.name() : "null");
    safeJsonPut(d, "media", resolveActiveMedia());
    safeJsonPut(d, "transport_key", (link != null) ? link.getTransportKey() : JSONObject.NULL);
    safeJsonPut(d, "to", (link != null) ? (link.getToAddr() & 0xFF) : -1);
    safeJsonPut(d, "from_addr", (link != null) ? (link.getHostAddr() & 0xFF) : -1);
    reproEvent(DeliveryLogStore.LEVEL_INFO, "FSM_STATE_CHANGED", "State transition", d);
} catch (Exception ignored) {}


        // ✅ TickBus: publier le changement d'état immédiatement
        try {
            LastTick prev = lastTick;
            double net = (prev != null) ? prev.net : 0.0;
            double gross = (prev != null) ? prev.gross : 0.0;
            int dev = (prev != null) ? prev.devStatus : (lastDevStatusKnown >= 0 ? lastDevStatusKnown : -1);
            int prn = (prev != null) ? prev.prnStatus : (lastPrnStatusKnown >= 0 ? lastPrnStatusKnown : -1);
            int ds = (prev != null) ? prev.delStatus : 0;
            int dc = (prev != null) ? prev.delCode : 0;
            publishTickIfChanged(net, gross, dev, prn, ds, dc, s);
        } catch (Exception ignored) {}
    }

    // =========================
    // A / B / C (UI)
    // =========================
    @Override public void refreshProducts() { emitLog("refreshProducts ignore"); }

    @Override
    public void selectProduct(int product1to16) {
        io.execute(() -> {
            if (isStopped()) return;
            try {
                int idx0 = product1to16 - 1;
                lcpSetField(FIELD_ACTIVE_PRODUCT, new byte[]{(byte) idx0});
            } catch (Exception e) {
                handleIoFailure("selectProduct", e);
            }
        });
    }

    @Override
    public void requestStatus() {
        io.execute(() -> {
            if (isStopped()) return;
            try {
                
// ✅ REPRO: intent Status (B)
try {
    JSONObject d0 = new JSONObject();
    safeJsonPut(d0, "media", resolveActiveMedia());
    safeJsonPut(d0, "state", state.name());
    safeJsonPut(d0, "transport_key", (link != null) ? link.getTransportKey() : JSONObject.NULL);
    reproEvent(DeliveryLogStore.LEVEL_INFO, "UI_STATUS_B", "Status requested", d0);
} catch (Exception ignored) {}

FullStatus fs = readFullStatus("status/full");


                // keep last known dev/prn for B+ tick bus
                lastDevStatusKnown = fs.devStatus;
                lastPrnStatusKnown = fs.prnStatus;

                emitLog(String.format("[STATUS] dev=0x%02X prn=0x%02X ds=0x%04X dc=0x%04X",
                        fs.devStatus, fs.prnStatus, fs.delStatus, fs.delCode));

                ensureDigits();
                double scale = Math.pow(10, cachedDigits);

                int gRaw = beI32(lcpGetField(FIELD_GROSS_COUNT));
                int nRaw = beI32(lcpGetField(FIELD_NET_COUNT));

                double net = (nRaw & 0xFFFFFFFFL) / scale;
                double gross = (gRaw & 0xFFFFFFFFL) / scale;

                if (listener != null) listener.onLiveQty(net, gross);

                // ✅ TickBus: push on change (B+ includes dev/prn/ds/dc/state)
                publishTickIfChanged(net, gross, fs.devStatus, fs.prnStatus, fs.delStatus, fs.delCode, state);

                // ✅ Ticket info (UI): ticket_no (#23). delivery_uid est inconnu ici => null
                try {
 String tno = readTicketNo23();
 String uid = null;
 String n = lastNumeroLivraison;
 if (n != null && !n.trim().isEmpty() && tno != null && !tno.trim().isEmpty()) {
 uid = n.trim() + "-" + tno.trim();
 }
 if (listener != null) listener.onTicketInfo(tno, uid);
 } catch (Exception ignored) {}

            } catch (Exception e) {
                handleIoFailure("status", e);
            }
        });
    }

    @Override
    public void alignOrRecover() {
        io.execute(() -> {
            if (isStopped()) return;
            try {
                emitLog("[A] Align / recover requested");

// ✅ REPRO: action A
try {
    JSONObject d = new JSONObject();
    safeJsonPut(d, "media", resolveActiveMedia());
    safeJsonPut(d, "state", state.name());
    safeJsonPut(d, "transport_key", (link != null) ? link.getTransportKey() : JSONObject.NULL);
    reproEvent(DeliveryLogStore.LEVEL_INFO, "UI_A", "Align/recover requested", d);
} catch (Exception ignored) {}

                doAlignOrRecoverFull();
            } catch (Exception e) {
                handleIoFailure("alignOrRecover", e);
            }
        });
    }

    @Override
    public void startDelivery(int product1to16, double presetNet) {
        io.execute(() -> {
            if (isStopped()) return;
            if (state == DeliveryState.PRESTART || state == DeliveryState.ENDING) return;
            emitLog("[C] New delivery requested");

// ✅ REPRO: intent C (sans IO additionnel)
try {
    JSONObject d = new JSONObject();
    safeJsonPut(d, "media", resolveActiveMedia());
    safeJsonPut(d, "transport_key", (link != null) ? link.getTransportKey() : JSONObject.NULL);
    safeJsonPut(d, "state", state.name());
    safeJsonPut(d, "product", product1to16);
    safeJsonPut(d, "preset_net", presetNet);
    reproEvent(DeliveryLogStore.LEVEL_INFO, "UI_C", "New delivery requested", d);
} catch (Exception ignored) {}

            final long deadline = System.currentTimeMillis() + START_RETRY_WINDOW_MS;
            try {
                FullStatus fs = retryUntilDeadline(deadline, "C/full-precheck", () -> readFullStatus("C/full"));

// ✅ REPRO: décision C (precheck) sans changer la logique
try {
    JSONObject d = new JSONObject();
    safeJsonPut(d, "media", resolveActiveMedia());
    safeJsonPut(d, "transport_key", (link != null) ? link.getTransportKey() : JSONObject.NULL);
    safeJsonPut(d, "delStatus", fs.delStatus);
    safeJsonPut(d, "delCode", fs.delCode);
    safeJsonPut(d, "ticketPending", fs.ticketPending ? 1 : 0);
    safeJsonPut(d, "deliveryActive", fs.deliveryActive ? 1 : 0);
    safeJsonPut(d, "flowActive", fs.flowActive ? 1 : 0);
    safeJsonPut(d, "product", product1to16);
    safeJsonPut(d, "preset_net", presetNet);
    reproEvent(DeliveryLogStore.LEVEL_INFO, "UI_C_PRECHECK", "C precheck status", d);
} catch (Exception ignored) {}


                // keep last known dev/prn for B+ tick bus
                lastDevStatusKnown = fs.devStatus;
                lastPrnStatusKnown = fs.prnStatus;

                // TickBus publish on status check
                // (net/gross unknown here, keep prev values)
                try {
                    LastTick prev = lastTick;
                    double net = (prev != null) ? prev.net : 0.0;
                    double gross = (prev != null) ? prev.gross : 0.0;
                    publishTickIfChanged(net, gross, fs.devStatus, fs.prnStatus, fs.delStatus, fs.delCode, state);
                } catch (Exception ignored) {}

                if (fs.deliveryActive) {
                    setState(DeliveryState.CONNECTED);
                    if (listener != null) {
                        if (fs.ticketPending) listener.onLiveStatus("LIVE: CONNECTED - Ticket pending");
                        else listener.onLiveStatus("LIVE: CONNECTED - Delivery active (use A)");
                    }
                    return;
                }
                if (fs.ticketPending) {
                    setState(DeliveryState.CONNECTED);
                    if (listener != null) listener.onLiveStatus("LIVE: CONNECTED - Ticket pending");
                    return;
                }
                if (fs.flowActive) {
                    setState(DeliveryState.CONNECTED);
                    if (listener != null) listener.onLiveStatus("LIVE: CONNECTED - Flow active (use A)");
                    return;
                }
                doStartNewDeliveryWithRetry(deadline, product1to16, presetNet);
            } catch (Exception e) {
                handleIoFailure("startDelivery(C-intent)", e);
            }
        });
    }

    private void doStartNewDeliveryWithRetry(long deadlineMs, int product1to16, double presetNet) throws Exception {
        if (isStopped()) return;
        setState(DeliveryState.PRESTART);

        flowOffStable = false;
        sawFlowOnOnce = false;
        flowOffStartMs = 0L;
        lastCountsChangeMs = 0L;
        lastGrossRaw = -1;
        lastNetRaw = -1;
        liveBackoffMs = LIVE_BASE_MS;
        liveNextAllowedMs = 0L;
        liveLastSkipLogMs = 0L;
        continueGraceUntilMs = 0L;

        retryUntilDeadline(deadlineMs, "SET_FIELD#0", () -> {
            int idx0 = product1to16 - 1;
            lcpSetField(FIELD_ACTIVE_PRODUCT, new byte[]{(byte) idx0});
            return null;
        });

        retryUntilDeadline(deadlineMs, "GET_FIELD#39", () -> { ensureDigits(); return null; });

        retryUntilDeadline(deadlineMs, "SET_FIELD#6", () -> { writePresetNet_WithCacheOrFallback(presetNet); return null; });

        retryUntilDeadline(deadlineMs, "RUN(0x00)", () -> { lcpIssueCommand(CMD_RUN);

// ✅ REPRO: RUN sent
try {
    JSONObject d = new JSONObject();
    safeJsonPut(d, "media", resolveActiveMedia());
    safeJsonPut(d, "transport_key", (link != null) ? link.getTransportKey() : JSONObject.NULL);
    safeJsonPut(d, "product", product1to16);
    safeJsonPut(d, "preset_net", presetNet);
    safeJsonPut(d, "digits", cachedDigits);
    reproEvent(DeliveryLogStore.LEVEL_INFO, "DELIVERY_RUN_SENT", "RUN sent", d);
} catch (Exception ignored) {}
 

 // === DELIVERY START (UI path) ===
 try {
     deliveryInProgress = true;
     deliveryStartMs = System.currentTimeMillis();
 } catch (Exception ignored) {}
return null; });

        setState(DeliveryState.RUNNING_PAUSED);
        if (listener != null) listener.onLiveStatus("LIVE: RUNNING_PAUSED (Flow OFF)");
    }

    // =========================
    // Continue / Finish (UI)
    // =========================
    @Override
    public void resumeIfPaused() {
        io.execute(() -> {
            if (isStopped()) return;
            try {
                if (state != DeliveryState.RUNNING_PAUSED) return;
                long now = System.currentTimeMillis();
                if ((now - lastContinueClickMs) < CONTINUE_DEBOUNCE_MS) return;
                lastContinueClickMs = now;
                if (continueGraceUntilMs > now) return;

                lcpIssueCommand(CMD_RUN);
                continueGraceUntilMs = now + CONTINUE_GRACE_MS;

// ✅ REPRO: continue
try {
    JSONObject d = new JSONObject();
    safeJsonPut(d, "media", resolveActiveMedia());
    safeJsonPut(d, "transport_key", (link != null) ? link.getTransportKey() : JSONObject.NULL);
    safeJsonPut(d, "state", state.name());
    safeJsonPut(d, "grace_until_ms", continueGraceUntilMs);
    reproEvent(DeliveryLogStore.LEVEL_INFO, "UI_CONTINUE", "Continue (RUN) requested", d);
} catch (Exception ignored) {}


                setState(DeliveryState.RUNNING_FLOWING);
                if (listener != null) listener.onLiveStatus("LIVE: RUNNING_FLOWING (Flow OFF - waiting progression)");

            } catch (Exception e) {
                continueGraceUntilMs = 0L;
                handleIoFailure("resumeIfPaused", e);
            }
        });
    }

    @Override
    /**
     * Termine la livraison quel que soit l'état courant (RUNNING_FLOWING ou RUNNING_PAUSED).
     * Utilisé uniquement par le flux d'annulation opérateur.
     * N'affecte pas endDelivery() ni le flux normal.
     */
    public void forceEnd() {
        io.execute(() -> {
            if (isStopped()) return;
            try {
                emitLog("[CANCEL] forceEnd — CMD_END depuis état " + state.name());
                lcpIssueCommand(CMD_END);
            } catch (Exception e) {
                emitLog("[CANCEL] forceEnd ERR: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            }
        });
    }

    public void endDelivery() {
        io.execute(() -> {
            if (isStopped()) return;
            try {
                if (state != DeliveryState.RUNNING_PAUSED) return;
                if (!flowOffStable && !sawFlowOnOnce) return;

                

// ✅ REPRO: END requested
try {
    JSONObject d0 = new JSONObject();
    safeJsonPut(d0, "media", resolveActiveMedia());
    safeJsonPut(d0, "transport_key", (link != null) ? link.getTransportKey() : JSONObject.NULL);
    safeJsonPut(d0, "state", state.name());
    safeJsonPut(d0, "flowOffStable", flowOffStable ? 1 : 0);
    safeJsonPut(d0, "sawFlowOnOnce", sawFlowOnOnce ? 1 : 0);
    reproEvent(DeliveryLogStore.LEVEL_INFO, "UI_END", "End requested", d0);
} catch (Exception ignored) {}

setState(DeliveryState.ENDING);

                lcpIssueCommand(CMD_END);

// ✅ REPRO: END sent
try {
    JSONObject d1 = new JSONObject();
    safeJsonPut(d1, "media", resolveActiveMedia());
    safeJsonPut(d1, "transport_key", (link != null) ? link.getTransportKey() : JSONObject.NULL);
    reproEvent(DeliveryLogStore.LEVEL_INFO, "DELIVERY_END_SENT", "END sent", d1);
} catch (Exception ignored) {}


                long deadline = System.currentTimeMillis() + 15_000;
                while (!isStopped() && System.currentTimeMillis() < deadline) {
                    FullStatus fs = safeReadFullStatusNoThrow();
                    if (fs != null && !fs.deliveryActive && !fs.flowActive) break;
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                }

                FullStatus fsAfter = safeReadFullStatusNoThrow();
                if (fsAfter != null && fsAfter.ticketPending) clearTicketPendingLoop();

                setState(DeliveryState.CONNECTED);
                if (listener != null) listener.onLiveStatus("LIVE: CONNECTED - Ready");

// ✅ REPRO: end completed (ready)
try {
    JSONObject d2 = new JSONObject();
    safeJsonPut(d2, "media", resolveActiveMedia());
    safeJsonPut(d2, "transport_key", (link != null) ? link.getTransportKey() : JSONObject.NULL);
    safeJsonPut(d2, "state", state.name());
    if (fsAfter != null) {
        safeJsonPut(d2, "delStatus", fsAfter.delStatus);
        safeJsonPut(d2, "delCode", fsAfter.delCode);
        safeJsonPut(d2, "ticketPending", fsAfter.ticketPending ? 1 : 0);
    }
    reproEvent(DeliveryLogStore.LEVEL_INFO, "DELIVERY_END_DONE", "End done (ready)", d2);
} catch (Exception ignored) {}


            } catch (Exception e) {
                handleIoFailure("endDelivery", e);
            }
        });
    }

    // =========================
    // LIVE sample (UI logic)
    // =========================
    // ✅ Configurer l'intervalle live tick selon profil registre
    // Appeler depuis applyRegisterProfile: LCR-II → 200ms, LC3 → 800ms
    public void setLiveTickIntervalMs(long intervalMs) {
        liveTickIntervalMs = Math.max(100, intervalMs);
    }

    // ✅ Lecture rapide net/gross uniquement — pour le live tick pendant RUNNING_FLOWING
    // Évite GET_DELIVERY_STATUS + GET_MACHINE_STATUS à chaque tick
    private void requestLiveSampleFast() {
        io.execute(() -> {
            if (isStopped()) return;
            if (state != DeliveryState.RUNNING_FLOWING) return;
            long now = System.currentTimeMillis();
            if (now < liveNextAllowedMs) return;
            if (!liveInFlight.compareAndSet(false, true)) return;
            inLiveSample.set(true);
            try {
                try { ensureDigits(); } catch (Exception ignored) { return; }
                double scale = Math.pow(10, cachedDigits);
                int g, n;
                try {
                    g = beI32(lcpGetField(FIELD_GROSS_COUNT));
                    n = beI32(lcpGetField(FIELD_NET_COUNT));
                } catch (Exception e) {
                    return;
                }
                double gross = (g & 0xFFFFFFFFL) / scale;
                double net   = (n & 0xFFFFFFFFL) / scale;
                if (listener != null) listener.onLiveQty(net, gross);
                publishTickIfChanged(net, gross,
                    lastDevStatusKnown, lastPrnStatusKnown,
                    (lastTick != null ? lastTick.delStatus : 0),
                    (lastTick != null ? lastTick.delCode   : 0),
                    state);
            } finally {
                inLiveSample.set(false);
                liveInFlight.set(false);
            }
        });
    }

    @Override
    public void requestLiveSample() {
        io.execute(() -> {
            if (isStopped()) return;
            long now = System.currentTimeMillis();
            if (now < liveNextAllowedMs) return;
            if (!liveInFlight.compareAndSet(false, true)) return;

            inLiveSample.set(true);
            try {
                int delStatus;
                int delCode;
                try {
                    int[] ds = lcpDeliveryStatus();
                    delStatus = ds[0];
                    delCode = ds[1];
                } catch (Exception e) {
                    liveSoftSkip("GET_DELIVERY_STATUS", e);
                    return;
                }

                boolean ticket = (delCode & DC_TICKET_PENDING) != 0;
                boolean flowBit = (delCode & DC_FLOW_ACTIVE) != 0;
                boolean active = (delCode & DC_DELIVERY_ACTIVE) != 0;

                if (!active) {
                    liveResetBackoff();
                    setState(DeliveryState.CONNECTED);

                    if (listener != null) {
                        listener.onLiveStatus(ticket ? "LIVE: CONNECTED - Ticket pending" : "LIVE: CONNECTED - Ready");
                        listener.onFlowStability(false, false, 0L);
                    }

                    lastGrossRaw = -1;
                    lastNetRaw = -1;
                    flowOffStable = false;
                    sawFlowOnOnce = false;
                    flowOffStartMs = 0L;
                    lastCountsChangeMs = 0L;
                    continueGraceUntilMs = 0L;

                    // ✅ TickBus: publish state/del changes even if quantities unknown
                    try {
                        LastTick prev = lastTick;
                        double net = (prev != null) ? prev.net : 0.0;
                        double gross = (prev != null) ? prev.gross : 0.0;
                        publishTickIfChanged(net, gross, -1, -1, delStatus, delCode, state);
                    } catch (Exception ignored) {}

                    return;
                }

                try { ensureDigits(); }
                catch (Exception e) { liveSoftSkip("ensureDigits", e); return; }

                double scale = Math.pow(10, cachedDigits);

                int g, n;
                try {
                    g = beI32(lcpGetField(FIELD_GROSS_COUNT));
                    n = beI32(lcpGetField(FIELD_NET_COUNT));
                } catch (Exception ex) {
                    g = (lastGrossRaw >= 0) ? lastGrossRaw : 0;
                    n = (lastNetRaw >= 0) ? lastNetRaw : 0;
                    liveBackoffStep("[LIVE] soft-skip counters");
                }

                liveResetBackoff();
                long t = System.currentTimeMillis();
                int d = 0;
                if (lastGrossRaw >= 0 && lastNetRaw >= 0) {
                    d = Math.abs(g - lastGrossRaw) + Math.abs(n - lastNetRaw);
                }

                double netL = n / scale;
                double grossL = g / scale;

                if (listener != null) listener.onLiveQty(netL, grossL);

                // ✅ TickBus: publish on change (B+ includes delStatus/delCode + state; dev/prn best-effort via last known)
                publishTickIfChanged(netL, grossL, -1, -1, delStatus, delCode, state);

                if (d > 0) {
                    continueGraceUntilMs = 0L;
                    sawFlowOnOnce = true;
                    lastCountsChangeMs = t;
                    flowOffStable = false;
                    flowOffStartMs = 0L;
                    lastGrossRaw = g;
                    lastNetRaw = n;

                    if (listener != null) {
                        listener.onFlowStability(flowBit, false, 0L);
                        listener.onLiveStatus("LIVE: RUNNING_FLOWING (FLOW ON)");
                    }
                    setState(DeliveryState.RUNNING_FLOWING);
                    return;
                }

                if (lastCountsChangeMs == 0L) lastCountsChangeMs = t;
                long age = t - lastCountsChangeMs;

                if (!sawFlowOnOnce) {
                    flowOffStable = false;
                    flowOffStartMs = 0L;

                    if (listener != null) {
                        listener.onFlowStability(flowBit, false, 0L);
                        listener.onLiveStatus("LIVE: RUNNING_FLOWING (Flow OFF - waiting progression)");
                    }

                    setState(DeliveryState.RUNNING_FLOWING);
                    lastGrossRaw = g;
                    lastNetRaw = n;
                    return;
                }

                if (flowOffStartMs == 0L) flowOffStartMs = lastCountsChangeMs;
                flowOffStable = age >= NO_FLOW_CONFIRM_MS;

                long now2 = System.currentTimeMillis();
                if (continueGraceUntilMs > now2) {
                    if (listener != null) {
                        listener.onFlowStability(flowBit, false, age);
                        listener.onLiveStatus("LIVE: RUNNING_FLOWING (Flow OFF - waiting progression)");
                    }
                    setState(DeliveryState.RUNNING_FLOWING);
                    lastGrossRaw = g;
                    lastNetRaw = n;
                    return;
                }

                if (listener != null) {
                    listener.onFlowStability(flowBit, flowOffStable, age);
                    listener.onLiveStatus(flowOffStable
                            ? "LIVE: RUNNING_PAUSED (FLOW OFF confirmed)"
                            : "LIVE: RUNNING_FLOWING (FLOW OFF - confirming...)");
                }

                // ✅ Arrêter boucle live dès que flow off stable — APK fluide
                if (flowOffStable && liveTickFuture != null) {
                    liveTickFuture.cancel(false);
                    liveTickFuture = null;
                }

                if (flowOffStable) setState(DeliveryState.RUNNING_PAUSED);
                else setState(DeliveryState.RUNNING_FLOWING);

                lastGrossRaw = g;
                lastNetRaw = n;

            } finally {
                inLiveSample.remove();
                liveInFlight.set(false);
            }
        });
    }

    private void liveResetBackoff() {
        liveBackoffMs = LIVE_BASE_MS;
        liveNextAllowedMs = 0L;
    }

    private void liveBackoffStep(String reason) {
        long now = System.currentTimeMillis();
        liveBackoffMs = Math.min(LIVE_MAX_MS, Math.max(LIVE_BASE_MS, liveBackoffMs * 2));
        liveNextAllowedMs = now + liveBackoffMs;
        if (now - liveLastSkipLogMs >= LIVE_LOG_THROTTLE_MS) {
            emitLog(reason + " (backoff=" + liveBackoffMs + "ms)");
            liveLastSkipLogMs = now;
        }
    }

    private void liveSoftSkip(String opName, Exception e) {
        String m = (e.getMessage() != null) ? e.getMessage() : "";
        liveBackoffStep("[LIVE] soft-skip " + opName + ": " + m);
    }

    @Override
    public void requestLiveSnapshot() {
        io.execute(() -> {
            if (isStopped()) return;
            try {
                FullStatus fs = readFullStatus("SNAP/full");

                // keep last known dev/prn for B+ tick bus
                lastDevStatusKnown = fs.devStatus;
                lastPrnStatusKnown = fs.prnStatus;

                if (listener != null) {
                    listener.onLiveStatus(fs.ticketPending ? "LIVE: CONNECTED - Ticket pending" : "LIVE: CONNECTED - Ready");
                }

                // publish state/ds/dc (net/gross unchanged)
                try {
                    LastTick prev = lastTick;
                    double net = (prev != null) ? prev.net : 0.0;
                    double gross = (prev != null) ? prev.gross : 0.0;
                    publishTickIfChanged(net, gross, fs.devStatus, fs.prnStatus, fs.delStatus, fs.delCode, state);
                } catch (Exception ignored) {}

            } catch (Exception e) {
                handleIoFailure("requestLiveSnapshot", e);
            }
        });
    }

    // =========================
    // Full status
    // =========================
    private static final class FullStatus {
        final int devStatus;
        final int prnStatus;
        final int delStatus;
        final int delCode;
        final boolean ticketPending;
        final boolean flowActive;
        final boolean deliveryActive;

        FullStatus(LcpLink.MachineStatus ms, int delStatus, int delCode) {
            this.devStatus = ms.devStatus;
            this.prnStatus = ms.prnStatus;
            this.delStatus = delStatus;
            this.delCode = delCode;
            this.ticketPending = (delCode & DC_TICKET_PENDING) != 0;
            this.flowActive = (delCode & DC_FLOW_ACTIVE) != 0;
            this.deliveryActive = (delCode & DC_DELIVERY_ACTIVE) != 0;
        }
    }

    private FullStatus readFullStatus(String ctx) throws Exception {
        LcpLink.MachineStatus ms = lcpMachineStatus();
        int[] ds = lcpDeliveryStatus();
        return new FullStatus(ms, ds[0], ds[1]);
    }

    private FullStatus safeReadFullStatusNoThrow() {
        try { return readFullStatus("safe"); }
        catch (Exception e) { return null; }
    }

    private void doAlignOrRecoverFull() throws Exception {
        FullStatus fs = readFullStatus("A/full");

        // keep last known dev/prn for B+ tick bus
        lastDevStatusKnown = fs.devStatus;
        lastPrnStatusKnown = fs.prnStatus;

        // publish state/ds/dc changes quickly (net/gross unchanged here)
        try {
            LastTick prev = lastTick;
            double net = (prev != null) ? prev.net : 0.0;
            double gross = (prev != null) ? prev.gross : 0.0;
            publishTickIfChanged(net, gross, fs.devStatus, fs.prnStatus, fs.delStatus, fs.delCode, state);
        } catch (Exception ignored) {}

        if (fs.ticketPending) {
            clearTicketPendingSafeForAlign();
            fs = readFullStatus("A/full-after-ticket");

            lastDevStatusKnown = fs.devStatus;
            lastPrnStatusKnown = fs.prnStatus;
        }

        if (fs.deliveryActive && !fs.flowActive) {
            setState(DeliveryState.RUNNING_PAUSED);
            if (listener != null) listener.onLiveStatus("LIVE: RUNNING_PAUSED (recovered)");
            return;
        }
        if (fs.deliveryActive && fs.flowActive) {
            setState(DeliveryState.RUNNING_FLOWING);
            if (listener != null) listener.onLiveStatus("LIVE: RUNNING_FLOWING (recovered)");
            return;
        }

        setState(DeliveryState.CONNECTED);
        if (listener != null) {
            listener.onLiveStatus(fs.ticketPending ? "LIVE: CONNECTED - Ticket pending" : "LIVE: CONNECTED - Ready");
        }
    }

    private void clearTicketPendingSafeForAlign() throws Exception {
        try {
            LcpLink.MachineStatus ms0 = lcpMachineStatus();
            if ((ms0.delCode & DC_TICKET_PENDING) == 0) {
                ticketPrintInFlight.set(false);
                ticketPrintStartMs = 0L;
                return;
            }
        } catch (Exception ignored) {}

        long now = System.currentTimeMillis();
        if (ticketPrintInFlight.compareAndSet(false, true)) {
            ticketPrintStartMs = now;
            try {
                lcpIssueCommand(CMD_PRINT_LAST_TICKET);
            } catch (Exception e) {
                ticketPrintInFlight.set(false);
                ticketPrintStartMs = 0L;
                throw e;
            }
        } else {
            if (ticketPrintStartMs <= 0L) ticketPrintStartMs = now;
        }

        long deadline = ticketPrintStartMs + TICKET_DEVICE_LOOP_MS;
        while (!isStopped() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            try {
                LcpLink.MachineStatus ms = lcpMachineStatus();
                if ((ms.delCode & DC_TICKET_PENDING) == 0) {
                    ticketPrintInFlight.set(false);
                    ticketPrintStartMs = 0L;
                    return;
                }
            } catch (Exception ignored) {}
        }

        ticketPrintInFlight.set(false);
        ticketPrintStartMs = 0L;
    }

    // =========================
    // ✅ SAFE PRINT helper (PRINT ONCE -> WAIT -> TIMEOUT)
    // =========================
    /**
     * Envoie CMD_PRINT_LAST_TICKET UNE fois (idempotent) puis attend que DC_TICKET_PENDING retombe à 0.
     * @return true si ticketPending cleared, false si timeout (ou transport fermé).
     */
    private boolean waitTicketPendingClearedOrTimeout(String ctx) {
        if (isStopped()) return false;

        // 0) Déjà clean ?
        try {
            LcpLink.MachineStatus ms0 = lcpMachineStatus();
            if ((ms0.delCode & DC_TICKET_PENDING) == 0) {
                ticketPrintInFlight.set(false);
                ticketPrintStartMs = 0L;
                return true;
            }
        } catch (Exception ignored) {}

        long now = System.currentTimeMillis();

        // 1) PRINT une seule fois (idempotent)
        if (ticketPrintInFlight.compareAndSet(false, true)) {
            ticketPrintStartMs = now;
            try {
                emitLog("[TICKET] PRINT_LAST_TICKET sent (ctx=" + ctx + ")");
                lcpIssueCommand(CMD_PRINT_LAST_TICKET);
            } catch (Exception e) {
                ticketPrintInFlight.set(false);
                ticketPrintStartMs = 0L;
                emitLog("[TICKET] PRINT send failed (ctx=" + ctx + "): " + safeMsg(e));
                return false;
            }
        } else {
            // déjà en cours -> attente seulement
            if (ticketPrintStartMs <= 0L) ticketPrintStartMs = now;
        }

        // 2) WAIT until cleared or timeout
        long deadline = ticketPrintStartMs + TICKET_DEVICE_LOOP_MS;

        while (!isStopped() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(250); } catch (InterruptedException ignored) {}

            try {
                LcpLink.MachineStatus ms = lcpMachineStatus();
                if ((ms.delCode & DC_TICKET_PENDING) == 0) {
                    emitLog("[TICKET] ticketPending cleared (ctx=" + ctx + ")");
                    ticketPrintInFlight.set(false);
                    ticketPrintStartMs = 0L;
                    return true;
                }
            } catch (Exception ignored) {}
        }

        emitLog("[TICKET] PRINT TIMEOUT (ctx=" + ctx + ")");
        ticketPrintInFlight.set(false);
        // keep ticketPrintStartMs for diagnostics
        return false;
    }


    private void clearTicketPendingLoop() {
        // ✅ SAFE: PRINT ONCE then WAIT for register confirmation
        waitTicketPendingClearedOrTimeout("legacy/clearTicketPendingLoop");
    }

    // =========================
    // Retry helper
    // =========================
    private interface IoSupplier<T> { T get() throws Exception; }

    private <T> T retryUntilDeadline(long deadlineMs, String step, IoSupplier<T> op) throws Exception {
        Exception last = null;
        while (System.currentTimeMillis() < deadlineMs) {
            if (isStopped()) throw new IllegalStateException("Transport closed");
            try {
                return op.get();
            } catch (Exception e) {
                last = e;
                String m = (e.getMessage() != null) ? e.getMessage() : "";
                boolean retryable = m.contains("Queued timeout") || m.contains("Timeout waiting LCP response");
                boolean hardFatal =
                        m.contains("Transport closed") ||
                        m.contains("Error writing") ||
                        m.contains("rc=-1") ||
                        m.contains("Connection closed");

                if (hardFatal) throw e;
                if (!retryable) throw e;

                

// ✅ REPRO: retry (best-effort)
try {
    JSONObject d = new JSONObject();
    safeJsonPut(d, "step", step);
    safeJsonPut(d, "err", m);
    safeJsonPut(d, "media", resolveActiveMedia());
    safeJsonPut(d, "transport_key", (link != null) ? link.getTransportKey() : JSONObject.NULL);
    reproEvent(DeliveryLogStore.LEVEL_WARN, "RETRY", "Retry step", d);
} catch (Exception ignored) {}

softResync("retry/" + step);

                try { Thread.sleep(START_RETRY_POLL_MS); } catch (InterruptedException ignored) {}
            }
        }
        if (last != null) throw last;
        return null;
    }

    // =========================
    // Protocol helpers
    // =========================
    private void ensureDigits() throws Exception {
        if (cachedDigits >= 0) return;
        byte[] dec = lcpGetField(FIELD_DECIMALS);
        int idx = (dec.length >= 1) ? (dec[0] & 0xFF) : 0;
        cachedDigits = decimalsDigits(idx);
    }

    private void writePresetNet_WithCacheOrFallback(double preset) throws Exception {
        int digits = cachedDigits;
        if (digits < 0) digits = 1;
        int scale = (int) Math.pow(10, digits);

        // ✅ preset=0 → plein complet — ne pas écrire de preset sur le registre
        // Le LCR-II utilisera son propre preset max ou sera arrêté manuellement
        if (preset <= 0.0) {
            android.util.Log.i("DeliveryController",
                "writePresetNet: preset=0 → PLEIN COMPLET — aucune écriture sur FIELD_PRESET_NET");
            return;
        }

        int value = (int) Math.round(preset * scale);
        android.util.Log.i("DeliveryController",
            "writePresetNet: preset=" + preset + "L → value=" + value);

        byte[] buf = new byte[] {
                (byte) (value >> 24),
                (byte) (value >> 16),
                (byte) (value >> 8),
                (byte) value
        };
        lcpSetField(FIELD_PRESET_NET, buf);
    }

    private int decimalsDigits(int idx) {
        switch (idx) {
            case 0: return 2;
            case 1: return 1;
            case 2: return 0;
            case 3: return 3;
            default: return 2;
        }
    }

    private int beI32(byte[] b) {
        if (b == null || b.length < 4) return 0;
        return ((b[0] & 0xFF) << 24) |
                ((b[1] & 0xFF) << 16) |
                ((b[2] & 0xFF) << 8) |
                (b[3] & 0xFF);
    }

    private String decodeAzString(byte[] b) {
        if (b == null || b.length == 0) return "";
        String s = new String(b, StandardCharsets.UTF_8);
        int nul = s.indexOf('\0');
        if (nul >= 0) s = s.substring(0, nul);
        return s.trim();
    }

    // -------- U32 helpers --------
    private String readU32FieldAsDecString(int field) throws Exception {
        long u = beI32(lcpGetField(field)) & 0xFFFFFFFFL;
        return String.valueOf(u);
    }

    private String readTicketNo23() throws Exception { return readU32FieldAsDecString(FIELD_TICKET_NUMBER); }
    private String readSaleNo22() throws Exception { return readU32FieldAsDecString(FIELD_SALE_NUMBER); }

    // =========================
    // Error handling / resync
    // =========================
    private void handleIoFailure(String ctx, Exception e) {
        if (listener != null) listener.onError(ctx, e);

// ✅ REPRO: erreur (sans TX/RX), best-effort
try {
    JSONObject d = new JSONObject();
    safeJsonPut(d, "ctx", ctx);
    safeJsonPut(d, "media", resolveActiveMedia());
    safeJsonPut(d, "transport_key", (link != null) ? link.getTransportKey() : JSONObject.NULL);
    tagErrorLevel(d, null, ctx, e);
    reproEvent(DeliveryLogStore.LEVEL_ERROR, "ERR_IO", "IO failure", d);
} catch (Exception ignored) {}

        String msg = (e != null && e.getMessage() != null) ? e.getMessage() : "";

        boolean hardFatal =
                msg.contains("Transport closed") ||
                msg.contains("Error writing") ||
                msg.contains("rc=-1") ||
                msg.contains("Connection closed");

        boolean retryish =
                msg.contains("Timeout waiting LCP response") ||
                msg.contains("Queued timeout");

        if ("requestLiveSample".equals(ctx)) return;

        if (hardFatal) { shutdown(true); return; }
        if (retryish) softResync("timeout/" + ctx);
    }

    private void softResync(String reason) {
        if (isStopped()) return;
        long now = System.currentTimeMillis();
        if (now - lastResyncMs < 1500) return;
        lastResyncMs = now;

// ✅ REPRO: resync (sans IO additionnel)
try {
    JSONObject d = new JSONObject();
    safeJsonPut(d, "reason", reason);
    safeJsonPut(d, "media", resolveActiveMedia());
    safeJsonPut(d, "transport_key", (link != null) ? link.getTransportKey() : JSONObject.NULL);
    reproEvent(DeliveryLogStore.LEVEL_WARN, "SOFT_RESYNC", "Soft resync", d);
} catch (Exception ignored) {}

        link.drainInput(250);
        link.forceSyncNext(reason);
    }

    // =========================================================
    // ========================= API-Face =======================
    // =========================================================
    public ApiResult api_scanUsb() {
        if (link == null || link.isClosed()) {
            return ApiResult.fail("Scan USB: 0 - Aucun registre detecte.", "NO_DEVICE");
        }
        return ApiResult.ok("Scan USB: 1 - Registre detecte");
    }

    public ApiResult api_openPingUsb() {
        if (link == null || link.isClosed()) {
            return ApiResult.fail("Open/Ping USB: 0 - USB non pret.", "USB_NOT_READY");
        }
        return ApiResult.ok("Open/Ping USB: 1 - USB pret");
    }

    public ApiResult api_connectLcp() {
        if (link == null || link.isClosed()) {
            return ApiResult.fail("Connect LCP: 0 - USB non connecte.", "NO_TRANSPORT");
        }
        try {
            int[] ds = lcpDeliveryStatus();
            int delStatus = ds[0];
            int delCode = ds[1];

            boolean ticketPending = (delCode & DC_TICKET_PENDING) != 0;
            boolean flowActive = (delCode & DC_FLOW_ACTIVE) != 0;
            boolean deliveryActive = (delCode & DC_DELIVERY_ACTIVE) != 0;

            JSONObject data = new JSONObject();
            safeJsonPut(data, "deliveryActive", deliveryActive ? 1 : 0);
            safeJsonPut(data, "flowActive", flowActive ? 1 : 0);
            safeJsonPut(data, "ticketPending", ticketPending ? 1 : 0);

            // TickBus: publish ds/dc changes immediately
            try {
                LastTick prev = lastTick;
                double net = (prev != null) ? prev.net : 0.0;
                double gross = (prev != null) ? prev.gross : 0.0;
                publishTickIfChanged(net, gross, -1, -1, delStatus, delCode, state);
            } catch (Exception ignored) {}

            if (!deliveryActive && !ticketPending) {
                safeJsonPut(data, "next", "C");
                return ApiResult.ok("Connect LCP: 1 - CONNECTED pret a livrer (Faire C)", data);
            }
            safeJsonPut(data, "next", "A");
            return ApiResult.ok("Connect LCP: 1 - CONNECTED livraison en attente (Faire A)", data);

        } catch (Exception e) {
            JSONObject d = new JSONObject();
            safeJsonPut(d, "detail", (e.getMessage() != null) ? e.getMessage() : "");
            tagErrorLevel(d, "LCP", "api_connectLcp", e);
            return ApiResult.fail("Connect LCP: 0 - State check failed (0x28).", "STATE28_FAIL", d);
        }
    }

 // =========================================================
 // ✅ Ticket: Reprint current (API)
 // =========================================================
 /**
  * API: Reprint du ticket courant (PRINT_LAST_TICKET = 0x06).
  * Règle: interdit si ticketPending=1 -> FAIL code "TICKET_PENDING" (HTTP 422 côté ApiServer).
  */
 public ApiResult api_ticketReprintCurrent() {
        if (link == null || link.isClosed()) {
            return ApiResult.fail("Reprint: 0 - USB not ready.", "USB_NOT_READY");
        }
        try {
            // 0) Lire état ticketPending
            int[] ds = lcpDeliveryStatus();
            int delStatus = ds[0];
            int delCode = ds[1];
            boolean ticketPending = (delCode & DC_TICKET_PENDING) != 0;

            // 1) Lire serial_id registre (#80) = clé DB
            String serialId = null;
            try { serialId = decodeAzString(lcpGetField(FIELD_SERIAL_ID)); } catch (Exception ignored) {}

            // 2) Lookup DB: dernier RESULT pour ce serial_id (source de vérité)
            JSONObject resultObj = null;
            boolean resultFound = false;
            String ticketNoDb = null;
            DeliveryLogStore store = this.logStore;
            if (store != null && serialId != null && !serialId.trim().isEmpty()) {
                try {
                    DeliveryLogStore.LatestResultRow row = store.getLatestResultBySerial(serialId);
                    if (row != null && row.resultJson != null && !row.resultJson.trim().isEmpty()) {
                        ticketNoDb = row.ticketNo;
                        try {
                            resultObj = new JSONObject(row.resultJson);
                            resultFound = true;
                        } catch (Exception ignored) {
                            resultObj = null;
                            resultFound = false;
                        }
                    }
                } catch (Exception ignored) {
                }
            }

            // 3) Construire réponse
            JSONObject data = new JSONObject();
            safeJsonPut(data, "ticketPending", ticketPending ? 1 : 0);
            safeJsonPut(data, "delStatus", delStatus);
            safeJsonPut(data, "delCode", delCode);
            safeJsonPut(data, "serial_id", (serialId == null || serialId.trim().isEmpty()) ? JSONObject.NULL : serialId);

            // ticket_no = celui du RESULT DB (ou fallback row.ticketNo)
            String ticketNo = null;
            if (resultObj != null) ticketNo = resultObj.optString("ticket_no", null);
            if ((ticketNo == null || ticketNo.trim().isEmpty()) && ticketNoDb != null && !ticketNoDb.trim().isEmpty()) {
                ticketNo = ticketNoDb;
            }
            safeJsonPut(data, "ticket_no", (ticketNo == null || ticketNo.trim().isEmpty()) ? JSONObject.NULL : ticketNo);

            // delivery_uid depuis result_json si possible
            String deliveryUid = null;
            if (resultObj != null) deliveryUid = resultObj.optString("delivery_uid", null);
            safeJsonPut(data, "delivery_uid", (deliveryUid == null || deliveryUid.trim().isEmpty()) ? JSONObject.NULL : deliveryUid);

            safeJsonPut(data, "result_found", resultFound ? 1 : 0);
            safeJsonPut(data, "result", (resultObj == null) ? JSONObject.NULL : resultObj);

            // ✅ Option 2: string prête à persister côté Field Service
            safeJsonPut(data, "result_text", (resultObj == null) ? JSONObject.NULL : ("RESULT: " + resultObj.toString()));

            // 4) Gate: ticket pending -> demander Resolve (A)
            if (ticketPending) {
                safeJsonPut(data, "next", "A");
                return ApiResult.fail("Reprint: 0 - Ticket pending", "TICKET_PENDING", data);
            }

            // 5) Ticket DONE -> imprimer le dernier ticket (duplicate/last ticket)
            lcpIssueCommand(CMD_PRINT_LAST_TICKET);
            safeJsonPut(data, "action", "PRINT_LAST_TICKET_SENT");

            // 6) Si result absent, on ne bloque pas la commande; on indique simplement l'absence.
            if (!resultFound) {
                safeJsonPut(data, "result_note", "RESULT_NOT_FOUND_IN_DB");
            }

            return ApiResult.ok("Reprint: 1 - Print sent", data);

        } catch (Exception e) {
            JSONObject d = new JSONObject();
            safeJsonPut(d, "detail", safeMsg(e));
            tagErrorLevel(d, "LCP", "api_ticketReprintCurrent", e);
            return ApiResult.fail("Reprint: 0 - LCP error.", "REPRINT_FAIL", d);
        }
    }



    // =========================================================
    // ✅ Registre prêt / validateRegister
    // =========================================================
    public ApiResult api_registerValidate(
            String numero_livraison,
            Integer expected_lcrnode_dec,
            String expected_serial_id,
            Integer expected_product_number,
            String expected_compartment
    ) {
        return api_registerValidate(numero_livraison, expected_lcrnode_dec, expected_serial_id, expected_product_number, expected_compartment, true);
    }

public ApiResult api_registerValidate(
            String numero_livraison,
            Integer expected_lcrnode_dec,
            String expected_serial_id,
            Integer expected_product_number,
            String expected_compartment,
 boolean persist
    ){
        if (link == null || link.isClosed()) {
            return ApiResult.fail("Validate: 0 - USB not ready.", RegisterValidator.Codes.ERR_USB_PORT_NOT_READY);
        }

        // Vérification optionnelle du node attendu (si fourni)
        if (expected_lcrnode_dec != null) {
            try {
                int cur = link.getToAddr();
                if ((cur & 0xFF) != (expected_lcrnode_dec & 0xFF)) {
                    JSONObject d = new JSONObject();
                    safeJsonPut(d, "current_lcrnode_dec", cur & 0xFF);
                    safeJsonPut(d, "current_lcrnode_hex", String.format("0x%02X", cur & 0xFF));
                    safeJsonPut(d, "expected_lcrnode_dec", expected_lcrnode_dec & 0xFF);
                    safeJsonPut(d, "expected_lcrnode_hex", String.format("0x%02X", expected_lcrnode_dec & 0xFF));
                    return ApiResult.fail("Validate: 0 - LCR node mismatch", RegisterValidator.Codes.ERR_LCP_CONNECT_FAILED, d);
                }
            } catch (Exception ignored) {}
        }

        try {
            FullStatus fs = readFullStatus("VALIDATE/full");

            // keep last known dev/prn for B+ tick bus
            lastDevStatusKnown = fs.devStatus;
            lastPrnStatusKnown = fs.prnStatus;

            // Identifiants registre
            String ticketNo = readTicketNo23();
            String saleNo = readSaleNo22();
            String serialId = decodeAzString(lcpGetField(FIELD_SERIAL_ID));

            // Produit actif (si lisible)
            Integer activeProduct1to16 = null;
            try {
                byte[] p = lcpGetField(FIELD_ACTIVE_PRODUCT);
                if (p != null && p.length >= 1) {
                    activeProduct1to16 = (p[0] & 0xFF) + 1;
                }
            } catch (Exception ignored) {}

            // delivery_uid (peut être null)
            String deliveryUid = null;
            if (numero_livraison != null && !numero_livraison.trim().isEmpty()
                    && ticketNo != null && !ticketNo.trim().isEmpty()) {
                deliveryUid = numero_livraison + "-" + ticketNo;
            }

            // Validations
            boolean serialMatch = true;
            if (expected_serial_id != null && !expected_serial_id.trim().isEmpty()) {
                serialMatch = expected_serial_id.trim().equalsIgnoreCase(serialId);
            }

            boolean productOk = true;
            if (expected_product_number != null && activeProduct1to16 != null) {
                productOk = expected_product_number.intValue() == activeProduct1to16.intValue();
            }

            boolean compartmentOk = true;
            if (expected_compartment != null) {
                // Non lisible sur LCP ici: validation présence
                compartmentOk = !expected_compartment.trim().isEmpty();
            }

            JSONObject data = new JSONObject();
            safeJsonPut(data, "numero_livraison", numero_livraison == null ? JSONObject.NULL : numero_livraison);
            safeJsonPut(data, "lcrnode_dec", link.getToAddr() & 0xFF);
            safeJsonPut(data, "lcrnode_hex", String.format("0x%02X", link.getToAddr() & 0xFF));
            safeJsonPut(data, "from_dec", link.getHostAddr() & 0xFF);
            safeJsonPut(data, "from_hex", String.format("0x%02X", link.getHostAddr() & 0xFF));
            safeJsonPut(data, "deliveryActive", fs.deliveryActive ? 1 : 0);
            safeJsonPut(data, "flowActive", fs.flowActive ? 1 : 0);
            safeJsonPut(data, "ticketPending", fs.ticketPending ? 1 : 0);
            safeJsonPut(data, "ticket_no", ticketNo);
            safeJsonPut(data, "sale_no", saleNo);
            safeJsonPut(data, "serial_id", serialId);
            safeJsonPut(data, "delivery_uid", deliveryUid == null ? JSONObject.NULL : deliveryUid);
            safeJsonPut(data, "active_product", activeProduct1to16 == null ? JSONObject.NULL : activeProduct1to16);
            safeJsonPut(data, "expected_product_number", expected_product_number == null ? JSONObject.NULL : expected_product_number);
            safeJsonPut(data, "expected_compartment", expected_compartment == null ? JSONObject.NULL : expected_compartment);
            safeJsonPut(data, "serial_match", serialMatch ? 1 : 0);
            safeJsonPut(data, "product_ok", productOk ? 1 : 0);
            safeJsonPut(data, "compartment_ok", compartmentOk ? 1 : 0);

            // READY = toutes conditions OK + pas ticket pending + pas delivery active
            boolean ready = (!fs.ticketPending && !fs.deliveryActive && serialMatch && productOk && compartmentOk);
            safeJsonPut(data, "ready", ready ? 1 : 0);

            // ✅ TickBus: publish ds/dc/dev/prn/state changes quickly
            try {
                LastTick prev = lastTick;
                double net = (prev != null) ? prev.net : 0.0;
                double gross = (prev != null) ? prev.gross : 0.0;
                publishTickIfChanged(net, gross, fs.devStatus, fs.prnStatus, fs.delStatus, fs.delCode, state);
            } catch (Exception ignored) {}

            // UI callback
            try {
                if (listener != null) listener.onTicketInfo(ticketNo, deliveryUid);
            } catch (Exception ignored) {}

            // SQLite logs (best-effort)
            DeliveryLogStore store = this.logStore;
            if (persist && store != null && serialId != null && !serialId.isEmpty()
                    && ticketNo != null && !ticketNo.isEmpty()) {

                String stateTxt = ready ? "VALIDATE_READY" : "VALIDATE_BLOCKED";
                if (fs.ticketPending) stateTxt = "TICKET_PENDING";
                else if (fs.deliveryActive) stateTxt = "DELIVERY_ACTIVE";
                else if (!serialMatch) stateTxt = "SERIAL_MISMATCH";
                else if (!productOk) stateTxt = "PRODUCT_MISMATCH";
                else if (!compartmentOk) stateTxt = "COMPARTMENT_MISMATCH";

                store.upsertSummaryAsync(serialId, ticketNo, saleNo, stateTxt, DeliveryLogStore.SOURCE_API, null,
                        data.toString(), null);

                store.openAttemptAsync(serialId, ticketNo, DeliveryLogStore.SOURCE_API, null, attemptId -> {
                    store.addEventAsync(attemptId, DeliveryLogStore.LEVEL_INFO, "REGISTER_VALIDATE",
                            "Validate register", data.toString());
                    store.closeAttemptAsync(attemptId, ready ? "READY" : "BLOCKED", data.toString(), null);
                });
            }

            // Décisions bloquantes
            if (fs.ticketPending) {
                return ApiResult.fail("Validate: 0 - Ticket pending.", RegisterValidator.Codes.ERR_TICKET_PENDING, data);
            }
            if (fs.deliveryActive) {
                return ApiResult.fail("Validate: 0 - Delivery active.", RegisterValidator.Codes.ERR_DELIVERY_ACTIVE, data);
            }
            if (!serialMatch) {
                return ApiResult.fail("Validate: 0 - Serial mismatch.", RegisterValidator.Codes.ERR_SERIAL_ID_MISMATCH, data);
            }
            if (!productOk) {
                return ApiResult.fail("Validate: 0 - Product mismatch.", RegisterValidator.Codes.ERR_PRODUCT_MISMATCH, data);
            }
            if (!compartmentOk) {
                return ApiResult.fail("Validate: 0 - Compartment mismatch.", RegisterValidator.Codes.ERR_COMPARTMENT_MISMATCH, data);
            }

            return ApiResult.ok("Validate: 1 - READY", data);

        } catch (Exception e) {
            JSONObject d = new JSONObject();
            safeJsonPut(d, "detail", safeMsg(e));
            tagErrorLevel(d, "LCP", "api_registerValidate", e);
            return ApiResult.fail("Validate: 0 - LCP error.", RegisterValidator.Codes.ERR_LCP_CONNECT_FAILED, d);
        }
    }

    public ApiResult api_deliveryAlignA() {
        if (link == null || link.isClosed()) {
            return ApiResult.fail("Align A: 0 - USB non pret.", "USB_NOT_READY");
        }
        try {
            doAlignOrRecoverFull();
            int[] ds = lcpDeliveryStatus();
            int delStatus = ds[0];
            int delCode = ds[1];

            boolean ticketPending = (delCode & DC_TICKET_PENDING) != 0;
            boolean flowActive = (delCode & DC_FLOW_ACTIVE) != 0;
            boolean deliveryActive = (delCode & DC_DELIVERY_ACTIVE) != 0;

            JSONObject data = new JSONObject();
            safeJsonPut(data, "deliveryActive", deliveryActive ? 1 : 0);
            safeJsonPut(data, "flowActive", flowActive ? 1 : 0);
            safeJsonPut(data, "ticketPending", ticketPending ? 1 : 0);
            safeJsonPut(data, "next", (!deliveryActive && !ticketPending) ? "C" : "A");
            safeJsonPut(data, "state", state.name());
            safeJsonPut(data, "live_status", (!deliveryActive && !ticketPending)
                    ? "LIVE: CONNECTED - Ready"
                    : (ticketPending ? "LIVE: CONNECTED - Ticket pending" : "LIVE: CONNECTED - Delivery/Flow active"));

            // TickBus publish ds/dc change (net/gross unchanged)
            try {
                LastTick prev = lastTick;
                double net = (prev != null) ? prev.net : 0.0;
                double gross = (prev != null) ? prev.gross : 0.0;
                publishTickIfChanged(net, gross, -1, -1, delStatus, delCode, state);
            } catch (Exception ignored) {}

            return ApiResult.ok("Align A: 1 - Align/Recover executed", data);

        } catch (Exception e) {
            JSONObject d = new JSONObject();
            safeJsonPut(d, "detail", (e.getMessage() != null) ? e.getMessage() : "");
            tagErrorLevel(d, "LCP", "api_deliveryAlignA", e);
            return ApiResult.fail("Align A: 0 - Failed", "ALIGN_FAIL", d);
        }
    }

    public ApiResult api_deliveryStartC(int product1to16, double presetNet) {
        if (link == null || link.isClosed()) {
            return ApiResult.fail("Delivery StartC: 0 - USB not ready.", "USB_NOT_READY");
        }
        try {
            int[] ds0 = lcpDeliveryStatus();
            int delStatus0 = ds0[0];
            int delCode0 = ds0[1];

            boolean ticketPending0 = (delCode0 & DC_TICKET_PENDING) != 0;
            boolean flowActive0 = (delCode0 & DC_FLOW_ACTIVE) != 0;
            boolean deliveryActive0 = (delCode0 & DC_DELIVERY_ACTIVE) != 0;

            JSONObject data = new JSONObject();
            safeJsonPut(data, "product", product1to16);
            safeJsonPut(data, "preset_net", presetNet);
            safeJsonPut(data, "deliveryActive", deliveryActive0 ? 1 : 0);
            safeJsonPut(data, "flowActive", flowActive0 ? 1 : 0);
            safeJsonPut(data, "ticketPending", ticketPending0 ? 1 : 0);

            // TickBus publish ds/dc change (net/gross unchanged)
            try {
                LastTick prev = lastTick;
                double net = (prev != null) ? prev.net : 0.0;
                double gross = (prev != null) ? prev.gross : 0.0;
                publishTickIfChanged(net, gross, -1, -1, delStatus0, delCode0, state);
            } catch (Exception ignored) {}

            if (deliveryActive0 || ticketPending0 || flowActive0) {
                safeJsonPut(data, "next", "A");
                return ApiResult.ok("Delivery StartC: 1 - Not ready for C (use A)", data);
            }

            // ✅ Générer jobId UUID comme OneShot
            String jobId = java.util.UUID.randomUUID().toString();
            ApiJob job = new ApiJob(jobId);
            job.productNumber    = product1to16;
            job.presetNetL_requested = presetNet;
            job.presetNetL_applied   = presetNet;
            job.startMs          = System.currentTimeMillis();
            job.media            = "bt";
            synchronized (apiJobs) { apiJobs.put(jobId, job); }
            lastActiveJobId = jobId;
            
            startDelivery(product1to16, presetNet);
            safeJsonPut(data, "jobId",        jobId);
            String tno = "";
            try { tno = readTicketNo23(); } catch (Exception ignored) {}
            safeJsonPut(data, "delivery_uid", "-" + (tno.isEmpty() ? "?" : tno));
            safeJsonPut(data, "ticket_no",    tno.isEmpty() ? JSONObject.NULL : tno);
            safeJsonPut(data, "media",        "bt");
            safeJsonPut(data, "state",        "CONNECTED");
            safeJsonPut(data, "next",         "POLL");
            return ApiResult.ok("Delivery StartC: 1 - Start requested", data);

        } catch (Exception e) {
            JSONObject d = new JSONObject();
            safeJsonPut(d, "detail", (e.getMessage() != null) ? e.getMessage() : "");
            tagErrorLevel(d, "LCP", "api_deliveryStartC", e);
            return ApiResult.fail("Delivery StartC: 0 - orchestration error", "STARTC_FAIL", d);
        }
    }

    private JSONArray actionsContinueTerminate() {
        JSONArray a = new JSONArray();
        a.put("CONTINUER");
        a.put("TERMINER");
        return a;
    }

    private String liveStatusArmed() {
        return "LIVE: CONNECTED - ARMED (en attente CONTINUER)";
    }

    /**
     * Diagnostic reset — remet les compteurs net/gross à zéro si négatifs.
     *
     * Appelable depuis :
     *   - api_deliveryOneShotStart() (automatique si net/gross < 0)
     *   - UI entretien (manuel)
     *   - UI admin (bouton diagnostic)
     *
     * LCR-II : opDiagnosticReset() → Auxiliary(0x03) + Print(0x06) + poll
     * LC3    : NO-OP (à implémenter quand spec LC3 disponible)
     *
     * Colonnes Dataverse à ajouter (TODO):
     *   lcr_pre_delivery_net, lcr_pre_delivery_gross, lcr_diagnostic_reset
     */
    public ApiResult api_diagnosticReset() {
        JSONObject d = new JSONObject();
        try {
            // Lire net/gross actuels
            byte[] netRaw   = lcpGetField(45); // FIELD_NET_COUNT
            byte[] grossRaw = lcpGetField(44); // FIELD_GROSS_COUNT
            int netRaw32    = toInt32(netRaw);
            int grossRaw32  = toInt32(grossRaw);

            // Lire décimales pour affichage
            byte[] decRaw = lcpGetField(39);
            int decimals  = decRaw != null && decRaw.length > 0
                ? new int[]{2,1,0,3}[decRaw[0] & 0x03] : 1;
            double scale  = Math.pow(10, decimals);
            double netL   = netRaw32   / scale;
            double grossL = grossRaw32 / scale;

            safeJsonPut(d, "net_before_l",   netL);
            safeJsonPut(d, "gross_before_l", grossL);
            safeJsonPut(d, "reset_done",     false);

            if (netRaw32 >= 0 && grossRaw32 >= 0) {
                // Pas de reset nécessaire
                safeJsonPut(d, "msg", "net/gross OK — pas de reset nécessaire");
                return ApiResult.ok("Diagnostic: pas de reset nécessaire", d);
            }

            emitLog("[DIAGNOSTIC] net=" + netL + " gross=" + grossL
                + " négatif — reset en cours...");

            // Envoyer séquence reset via link
            withLcpLockVoid(() -> {
                link.opDiagnosticReset(10000);
                return null;
            });

            safeJsonPut(d, "reset_done", true);
            safeJsonPut(d, "msg", "Reset effectué — net_avant=" + netL + " gross_avant=" + grossL);
            emitLog("[DIAGNOSTIC] Reset OK");
            return ApiResult.ok("Diagnostic reset OK", d);

        } catch (Exception e) {
            safeJsonPut(d, "error", e.getMessage());
            emitLog("[DIAGNOSTIC] ERR: " + e.getMessage());
            return ApiResult.fail("Diagnostic reset ERR: " + e.getMessage(), "DIAGNOSTIC_ERR", d);
        }
    }

    /** Convertit bytes big-endian signé en int */
    private static int toInt32(byte[] b) {
        if (b == null || b.length < 4) return 0;
        return ((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16)
             | ((b[2] & 0xFF) << 8)  |  (b[3] & 0xFF);
    }

    public ApiResult api_deliveryOneShotStart(String numero_livraison, int product1to16, double presetNetL, String compartment) {
        if (link == null || link.isClosed()) {
            return ApiResult.fail("Delivery OneShot: 0 - USB not ready.", "USB_NOT_READY");
        }

        // =====================================================================
        // TODO: DIAGNOSTIC RESET — à implémenter (LCR-II + LC3)
        // ---------------------------------------------------------------------
        // Avant de démarrer la livraison, vérifier si net/gross sont négatifs
        // (retour d'air après livraison précédente — ex: -0.1L).
        //
        // Si négatif → appeler api_diagnosticReset() — méthode STANDALONE:
        //
        //   public ApiResult api_diagnosticReset()
        //     → utilisable depuis: démarrage livraison, entretien, UI admin
        //     → LCR-II : opDiagnosticReset() dans LcpLink
        //                  issue_command(0x03 Auxiliary)
        //                  issue_command(0x06 Print)
        //                  poll net/gross jusqu'à == 0 (max 10s)
        //     → LC3    : opDiagnosticReset() dans Lc3Link — NO-OP
        //                  (comportement à définir avec spec LC3)
        //
        // Logger dans lcr_delivery_status:
        //   lcr_pre_delivery_net   = valeur net avant reset
        //   lcr_pre_delivery_gross = valeur gross avant reset
        //   lcr_diagnostic_reset   = true (bit Dataverse)
        //
        // Colonnes Dataverse à ajouter via pac CLI:
        //   lcr_pre_delivery_net   (decimal)
        //   lcr_pre_delivery_gross (decimal)
        //   lcr_diagnostic_reset   (bit)
        //
        // Référence Python: lcp_bypass3_test.py + lcp_print_and_bypass.py
        //
        // NOTE: vérifier si oneshot/start remet lui-même les compteurs à zéro
        // auquel cas le reset manuel n'est peut-être pas nécessaire — juste logger.
        // =====================================================================

        // ✅ Vérifier net/gross avant démarrage — diagnostic reset si négatif
        // NOTE: api_diagnosticReset() est disponible comme commande standalone
        // depuis l'entretien ou l'UI admin. Ne pas l'appeler automatiquement ici
        // pour éviter de saturer le transport si le média n'est pas encore stabilisé.
        // TODO: réactiver quand le flux média est déterminé AVANT cet appel.
        
 // ✅ Mémoriser le NUM (WorkOrder) pour l’UI: delivery_uid = NUM-ticketNo
 if (numero_livraison != null && !numero_livraison.trim().isEmpty()) {
     lastNumeroLivraison = numero_livraison.trim();
 }
try {
            int[] ds0 = lcpDeliveryStatus();
            int delStatus0 = ds0[0];
            int delCode0 = ds0[1];

            boolean ticketPending0 = (delCode0 & DC_TICKET_PENDING) != 0;
            boolean deliveryActive0 = (delCode0 & DC_DELIVERY_ACTIVE) != 0;

            String ticketNo = readTicketNo23();
            String saleNo = readSaleNo22();
            String serialId = decodeAzString(lcpGetField(FIELD_SERIAL_ID));
            String deliveryUid = (numero_livraison == null ? "" : numero_livraison) + "-" + ticketNo;

            // TickBus publish ds/dc change (net/gross unchanged)
            try {
                LastTick prev = lastTick;
                double net = (prev != null) ? prev.net : 0.0;
                double gross = (prev != null) ? prev.gross : 0.0;
                publishTickIfChanged(net, gross, -1, -1, delStatus0, delCode0, state);
            } catch (Exception ignored) {}

            if (deliveryActive0) {
                JSONObject data = new JSONObject();
                safeJsonPut(data, "numero_livraison", numero_livraison);
                safeJsonPut(data, "ticket_no", ticketNo);
                safeJsonPut(data, "sale_no", saleNo);
                safeJsonPut(data, "serial_id", serialId);
                safeJsonPut(data, "delivery_uid", deliveryUid);
                safeJsonPut(data, "deliveryActive", 1);
                safeJsonPut(data, "ticketPending", ticketPending0 ? 1 : 0);
                safeJsonPut(data, "armed", 0);
                safeJsonPut(data, "state", state.name());
                safeJsonPut(data, "live_status", "LIVE: Delivery already active");
                safeJsonPut(data, "available_actions", actionsContinueTerminate());
                return ApiResult.ok("Delivery OneShot: 1 - Delivery already active", data);
            }

            boolean autoAAttempted = false;
            boolean autoASuccess = false;

            if (ticketPending0) {
                autoAAttempted = true;
                try { doAlignOrRecoverFull(); } catch (Exception ignore) {}

                int[] dsA = lcpDeliveryStatus();
                int delStatusA = dsA[0];
                int delCodeA = dsA[1];

                boolean ticketPendingA = (delCodeA & DC_TICKET_PENDING) != 0;
                boolean deliveryActiveA = (delCodeA & DC_DELIVERY_ACTIVE) != 0;
                autoASuccess = !ticketPendingA;

                // TickBus publish ticket pending changes
                try {
                    LastTick prev = lastTick;
                    double net = (prev != null) ? prev.net : 0.0;
                    double gross = (prev != null) ? prev.gross : 0.0;
                    publishTickIfChanged(net, gross, -1, -1, delStatusA, delCodeA, state);
                } catch (Exception ignored) {}

                if (ticketPendingA || deliveryActiveA) {
                    JSONObject data = new JSONObject();
                    safeJsonPut(data, "numero_livraison", numero_livraison);
                    safeJsonPut(data, "ticket_no", ticketNo);
                    safeJsonPut(data, "sale_no", saleNo);
                    safeJsonPut(data, "serial_id", serialId);
                    safeJsonPut(data, "delivery_uid", deliveryUid);
                    safeJsonPut(data, "autoA_attempted", 1);
                    safeJsonPut(data, "autoA_success", autoASuccess ? 1 : 0);
                    safeJsonPut(data, "next", "A");
                    safeJsonPut(data, "armed", 0);
                    safeJsonPut(data, "state", DeliveryState.CONNECTED.name());
                    safeJsonPut(data, "live_status", "LIVE: CONNECTED - Ticket pending");
                    JSONArray a = new JSONArray();
                    a.put("ALIGN_A");
                    safeJsonPut(data, "available_actions", a);

                    DeliveryLogStore store = this.logStore;
                    if (store != null && serialId != null && !serialId.isEmpty() && ticketNo != null && !ticketNo.isEmpty()) {
                        store.upsertSummaryAsync(serialId, ticketNo, saleNo, "TICKET_PENDING", DeliveryLogStore.SOURCE_API, null, null, null);
                    }
                    return ApiResult.ok("Delivery OneShot: 1 - Ticket pending (auto-A attempted)", data);
                }
            }

            ensureDigits();
            double scale = Math.pow(10, cachedDigits);

            int idx0 = product1to16 - 1;
            lcpSetField(FIELD_ACTIVE_PRODUCT, new byte[]{(byte) idx0});

            writePresetNet_WithCacheOrFallback(presetNetL);

            long presetRawU = beI32(lcpGetField(FIELD_PRESET_NET)) & 0xFFFFFFFFL;
            double presetApplied = presetNetL <= 0.0 ? 0.0 : (presetRawU / scale);
            double tol = 1.0 / scale;

            // ✅ Si preset=0 (plein complet) — skip vérification mismatch
            if (presetNetL > 0.0 && Math.abs(presetApplied - presetNetL) > (tol * 1.5)) {
                JSONObject d = new JSONObject();
                safeJsonPut(d, "preset_requested", presetNetL);
                safeJsonPut(d, "preset_applied", presetApplied);
                safeJsonPut(d, "decimals", cachedDigits);
                safeJsonPut(d, "preset_raw_u32", presetRawU);
                return ApiResult.fail("Delivery OneShot: 0 - Preset mismatch", "PRESET_MISMATCH", d);
            }

            String jobId = UUID.randomUUID().toString();
            ApiJob job = new ApiJob(jobId);
            job.numeroLivraison = numero_livraison;
            job.ticketNo = ticketNo;
            job.saleNo = saleNo;
            job.serialId = serialId;
            job.deliveryUid = deliveryUid;
            job.compartment = compartment;
            job.productNumber = product1to16;
            
 job.media = resolveActiveMedia();
job.presetNetL_requested = presetNetL;
            job.presetNetL_applied = presetApplied;
            job.presetRawU32 = presetRawU;
            job.decimals = cachedDigits;
            job.startMs = System.currentTimeMillis();

            synchronized (apiJobs) {
                apiJobs.put(jobId, job);
            }
            lastActiveJobId = jobId; // ✅ mémoriser le dernier jobId actif
            
            // SQLite
            DeliveryLogStore store = this.logStore;
            if (store != null && serialId != null && !serialId.trim().isEmpty() && ticketNo != null && !ticketNo.trim().isEmpty()) {
                store.upsertSummaryAsync(serialId, ticketNo, saleNo, "ARMED", DeliveryLogStore.SOURCE_API, jobId, null, null);
                store.openAttemptAsync(serialId, ticketNo, DeliveryLogStore.SOURCE_API, jobId, attemptId -> {
                    job.attemptId = attemptId;
                    if (attemptId > 0) {
                        store.addEventAsync(attemptId, DeliveryLogStore.LEVEL_INFO, "ONESHOT_ARMED", "Job armed", null);
                    }
                });
            }

            setState(DeliveryState.CONNECTED);

            JSONObject data = new JSONObject();
            safeJsonPut(data, "jobId", jobId);
 safeJsonPut(data, "media", (job.media == null ? resolveActiveMedia() : job.media));
            safeJsonPut(data, "numero_livraison", numero_livraison);
            safeJsonPut(data, "ticket_no", ticketNo);
            safeJsonPut(data, "sale_no", saleNo);
            safeJsonPut(data, "serial_id", serialId);
            safeJsonPut(data, "delivery_uid", deliveryUid);
            safeJsonPut(data, "autoA_attempted", autoAAttempted ? 1 : 0);
            safeJsonPut(data, "autoA_success", autoASuccess ? 1 : 0);
            safeJsonPut(data, "preset_requested", presetNetL);
            safeJsonPut(data, "preset_applied", presetApplied);
            safeJsonPut(data, "decimals", cachedDigits);
            safeJsonPut(data, "armed", 1);
            safeJsonPut(data, "state", DeliveryState.CONNECTED.name());
            safeJsonPut(data, "live_status", liveStatusArmed());
            safeJsonPut(data, "available_actions", actionsContinueTerminate());

            return ApiResult.ok("Delivery OneShot: 1 - ARMED (preset OK, waiting)", data);

        } catch (Exception e) {
            JSONObject d = new JSONObject();
            safeJsonPut(d, "detail", (e.getMessage() != null) ? e.getMessage() : "");
            tagErrorLevel(d, "LCP", "api_deliveryOneShotStart", e);
            return ApiResult.fail("Delivery OneShot: 0 - orchestration error", "ONESHOT_FAIL", d);
        }
    }

    // ✅ Correctif "Continue 2 fois": cache RUNNING immédiat + DB event
    public ApiResult api_deliveryContinue(String jobId) {
        ApiJob job;
        synchronized (apiJobs) { job = apiJobs.get(jobId); }
        if (job == null) return ApiResult.fail("Continue: 0 - Job unknown", "JOB_NOT_FOUND");
        try {
            lcpIssueCommand(CMD_RUN);
            long now = System.currentTimeMillis();
            continueGraceUntilMs = now + CONTINUE_GRACE_MS;
            job.continueGraceUntilMs = now + CONTINUE_GRACE_MS;
            setState(DeliveryState.RUNNING_FLOWING);

            try { job.ticketNo = readTicketNo23(); } catch (Exception ignored) {}
            try { job.saleNo = readSaleNo22(); } catch (Exception ignored) {}

            // Mettre à jour le cache JobGet immédiatement
            try {
                JSONObject cached = (job.lastOkData != null) ? safeJsonCopy(job.lastOkData) : new JSONObject();
                if (cached == null) cached = new JSONObject();

                safeJsonPut(cached, "jobId", jobId);
                safeJsonPut(cached, "numero_livraison", job.numeroLivraison);
                safeJsonPut(cached, "ticket_no", job.ticketNo);
                safeJsonPut(cached, "sale_no", job.saleNo);
                safeJsonPut(cached, "delivery_uid", job.deliveryUid);
                safeJsonPut(cached, "deliveryActive", 0);
                safeJsonPut(cached, "flowActive", 0);
                safeJsonPut(cached, "ticketPending", 0);
                safeJsonPut(cached, "state_job", "RUNNING");
                safeJsonPut(cached, "armed", 0);
                safeJsonPut(cached, "state", DeliveryState.RUNNING_FLOWING.name());
                safeJsonPut(cached, "pause_active", 1);
                safeJsonPut(cached, "pause_reason", "WAIT_FLOW_ON");
                safeJsonPut(cached, "flow_off_age_ms", 0L);
                safeJsonPut(cached, "flow_off_confirmed", 0);
                safeJsonPut(cached, "continue_grace_ms_left", Math.max(0L, job.continueGraceUntilMs - now));
                safeJsonPut(cached, "next_poll_ms", API_JOB_MIN_POLL_MS);
                safeJsonPut(cached, "live_status", "LIVE: RUNNING_FLOWING (Flow OFF - waiting progression)");

                JSONArray a0 = new JSONArray();
                a0.put("CONTINUER");
                a0.put("TERMINER");
                safeJsonPut(cached, "available_actions", a0);

                job.lastOkData = safeJsonCopy(cached);
                job.lastOkMsg = "Job: 1 - RUNNING";
                job.nextAllowedReadMs = now + API_JOB_MIN_POLL_MS;
            } catch (Exception ignored) {}

            // SQLite
            DeliveryLogStore store = this.logStore;
            if (store != null && job.serialId != null && !job.serialId.isEmpty()
                    && job.ticketNo != null && !job.ticketNo.isEmpty()) {

                store.upsertSummaryAsync(job.serialId, job.ticketNo, job.saleNo, "RUN_SENT", DeliveryLogStore.SOURCE_API, jobId, null, null);
                if (job.attemptId > 0) {
                    store.addEventAsync(job.attemptId, DeliveryLogStore.LEVEL_INFO, "CONTINUE_RUN_SENT", "RUN sent", null);
                }
            }

            JSONObject data = new JSONObject();
            safeJsonPut(data, "jobId", jobId);
 safeJsonPut(data, "media", (job.media == null ? resolveActiveMedia() : job.media));
            safeJsonPut(data, "numero_livraison", job.numeroLivraison);
            safeJsonPut(data, "ticket_no", job.ticketNo);
            safeJsonPut(data, "sale_no", job.saleNo);
            safeJsonPut(data, "delivery_uid", job.deliveryUid);
            safeJsonPut(data, "armed", 0);
            safeJsonPut(data, "live_status", "LIVE: RUNNING_FLOWING (Flow OFF - waiting progression)");

            return ApiResult.ok("Continue: 1 - RUN sent", data);

        } catch (Exception e) {
            DeliveryLogStore store = this.logStore;
            if (store != null && job.attemptId > 0) {
                store.addEventAsync(job.attemptId, DeliveryLogStore.LEVEL_ERROR, "CONTINUE_RUN_FAIL",
                        "RUN failed: " + safeMsg(e), null);
            }
            JSONObject d = new JSONObject();
            safeJsonPut(d, "detail", (e.getMessage() != null) ? e.getMessage() : "");
            tagErrorLevel(d, "LCP", "api_deliveryContinue", e);
            return ApiResult.fail("Continue: 0 - RUN failed", "RUN_FAIL", d);
        }
    }

    public ApiResult api_deliveryTerminate(String jobId) {
        ApiJob job;
        synchronized (apiJobs) { job = apiJobs.get(jobId); }
        if (job == null) return ApiResult.fail("Terminate: 0 - Job unknown", "JOB_NOT_FOUND");

        try { lcpIssueCommand(CMD_END); } catch (Exception ignore) {}

        try { job.ticketNo = readTicketNo23(); } catch (Exception ignored) {}
        try { job.saleNo = readSaleNo22(); } catch (Exception ignored) {}

        // Cache "ENDING" immédiat
        try {
            long now = System.currentTimeMillis();
            JSONObject cached = (job.lastOkData != null) ? safeJsonCopy(job.lastOkData) : new JSONObject();
            if (cached == null) cached = new JSONObject();

            safeJsonPut(cached, "jobId", jobId);
            safeJsonPut(cached, "numero_livraison", job.numeroLivraison);
            safeJsonPut(cached, "ticket_no", job.ticketNo);
            safeJsonPut(cached, "sale_no", job.saleNo);
            safeJsonPut(cached, "delivery_uid", job.deliveryUid);
            safeJsonPut(cached, "armed", 0);
            safeJsonPut(cached, "live_status", "LIVE: ENDING");

            job.lastOkData = safeJsonCopy(cached);
            job.lastOkMsg = (job.lastOkMsg != null) ? job.lastOkMsg : "Job: 1 - RUNNING";
            job.nextAllowedReadMs = now + API_JOB_MIN_POLL_MS;
        } catch (Exception ignored) {}

        // SQLite
        DeliveryLogStore store = this.logStore;
        if (store != null && job.serialId != null && !job.serialId.isEmpty()
                && job.ticketNo != null && !job.ticketNo.isEmpty()) {

            store.upsertSummaryAsync(job.serialId, job.ticketNo, job.saleNo, "END_SENT", DeliveryLogStore.SOURCE_API, jobId, null, null);
            if (job.attemptId > 0) {
                store.addEventAsync(job.attemptId, DeliveryLogStore.LEVEL_INFO, "TERMINATE_END_SENT", "END sent", null);
            }
        }

        JSONObject data = new JSONObject();
        safeJsonPut(data, "jobId", jobId);
 safeJsonPut(data, "media", (job.media == null ? resolveActiveMedia() : job.media));
        safeJsonPut(data, "numero_livraison", job.numeroLivraison);
        safeJsonPut(data, "ticket_no", job.ticketNo);
        safeJsonPut(data, "sale_no", job.saleNo);
        safeJsonPut(data, "delivery_uid", job.deliveryUid);
        safeJsonPut(data, "armed", 0);
        safeJsonPut(data, "live_status", "LIVE: ENDING");

        return ApiResult.ok("Terminate: 1 - END sent", data);
    }
    public ApiResult api_deliveryStatusB() {
        try {
            // ✅ Lecture directe via lcpOpLock — même verrou que UI
            // PAS de requestStatus()/requestLiveSample() qui lancent des threads async
            // et créent des collisions de trames BT avec le UI
            int[] ds = lcpDeliveryStatus();
            int delCode = ds[1];
            ensureDigits();
            double scale = Math.pow(10, cachedDigits);

            int g = beI32(lcpGetField(FIELD_GROSS_COUNT));
            int n = beI32(lcpGetField(FIELD_NET_COUNT));

            // ✅ Sanity check — overflow protection (unsigned 32-bit → signé Java)
            long nLong = (n & 0xFFFFFFFFL);
            long gLong = (g & 0xFFFFFFFFL);
            // Si valeur > 1 million de litres → probablement overflow/corruption
            double netL   = (nLong / scale > 1_000_000.0) ? -1.0 : nLong / scale;
            double grossL = (gLong / scale > 1_000_000.0) ? -1.0 : gLong / scale;

            // Mettre à jour lastDev/PrnStatus depuis un readFullStatus non-intrusif
            try {
                FullStatus fs = readFullStatus("api_statusB");
                lastDevStatusKnown = fs.devStatus;
                lastPrnStatusKnown = fs.prnStatus;

            } catch (Exception ignored) {}

            boolean deliveryActive = (delCode & DC_DELIVERY_ACTIVE) != 0;
            boolean flowActive     = (delCode & DC_FLOW_ACTIVE) != 0;
            boolean ticketPending  = (delCode & DC_TICKET_PENDING) != 0;

            JSONObject data = new JSONObject();
            safeJsonPut(data, "deliveryActive",  deliveryActive ? 1 : 0);
            safeJsonPut(data, "flowActive",      flowActive ? 1 : 0);
            safeJsonPut(data, "ticketPending",   ticketPending ? 1 : 0);
            safeJsonPut(data, "net",             netL >= 0 ? netL : JSONObject.NULL);
            safeJsonPut(data, "gross",           grossL >= 0 ? grossL : JSONObject.NULL);
            safeJsonPut(data, "net_l",           netL >= 0 ? netL : JSONObject.NULL);
            safeJsonPut(data, "gross_l",         grossL >= 0 ? grossL : JSONObject.NULL);
            safeJsonPut(data, "decimals",        cachedDigits);
            safeJsonPut(data, "delCode",         delCode);
            safeJsonPut(data, "state",           state != null ? state.name() : "UNKNOWN");
            safeJsonPut(data, "ts_ms",           System.currentTimeMillis());
            // ✅ jobId — dernier job actif connu
            if (lastActiveJobId != null) safeJsonPut(data, "jobId", lastActiveJobId);            

            try {
                JSONObject tick = buildTickJsonSnapshot();
                safeJsonPut(data, "tick", tick);
            } catch (Exception ignored2) {}

            return ApiResult.ok("StatusB: 1 - OK", data);

        } catch (Exception e) {
            JSONObject d = new JSONObject();
            safeJsonPut(d, "detail", e.getMessage() != null ? e.getMessage() : "");
            return ApiResult.fail("StatusB: 0 - Read error", "STATUS_B_READ_FAIL", d);
        }
    }

    /**
     * Impression custom ligne par ligne via MSG_PRINT_TEXT (0x22).
     * LCR-II : envoie la ligne à l'imprimante série du registre.
     * LC3    : NO-OP (opPrintText est NO-OP dans Lc3Link).
     */
    public ApiResult api_printTextLine(String line) {
        JSONObject d = new JSONObject();
        try {
            withLcpLockVoid(() -> { link.opPrintText(line); return null; });
            safeJsonPut(d, "line", line);
            return ApiResult.ok("PrintText: OK", d);
        } catch (Exception e) {
            safeJsonPut(d, "error", e.getMessage());
            return ApiResult.fail("PrintText ERR: " + e.getMessage(), "PRINT_ERR", d);
        }
    }

        public ApiResult api_printerStatus() {
        try {
            int prn = lastPrnStatusKnown;
            int dev = lastDevStatusKnown;
            int tick_no = -1;
            boolean tickPending = false;

            // Lire delCode pour ticketPending
            try {
                int[] ds = lcpDeliveryStatus();
                int delCode = ds[1];
                tickPending = (delCode & DC_TICKET_PENDING) != 0;
                tick_no = beI32(lcpGetField(FIELD_TICKET_NUMBER));
            } catch (Exception ignored) {}

            // Interpréter prnStatus
            boolean prnOnline  = (prn == 0);
            boolean prnOffline = (prn & 64) != 0;

            boolean devError   = (dev > 0) && (dev != 1) && (dev != 32) && (dev != 33);

            String prnLabel;
            if (prn < 0)        prnLabel = "UNKNOWN";
            else if (prnOnline) prnLabel = "ONLINE";
            else if (prnOffline)prnLabel = "OFFLINE";
            else                prnLabel = "ERROR";

            String status;
            if (tickPending && prnOffline)  status = "TICKET_PENDING_PRINTER_OFFLINE";
            else if (tickPending)            status = "TICKET_PENDING";
            else if (prnOffline)             status = "PRINTER_OFFLINE";
            else if (devError)               status = "DEVICE_ERROR";
            else                             status = "OK";

            JSONObject data = new JSONObject();
            safeJsonPut(data, "status",        status);
            safeJsonPut(data, "printer",       prnLabel);
            safeJsonPut(data, "prnStatus",     prn);
            safeJsonPut(data, "devStatus",     dev);
            safeJsonPut(data, "prnOnline",     prnOnline ? 1 : 0);
            safeJsonPut(data, "prnOffline",    prnOffline ? 1 : 0);
            safeJsonPut(data, "devError",      devError ? 1 : 0);
            safeJsonPut(data, "ticketPending", tickPending ? 1 : 0);
            safeJsonPut(data, "ts_ms",         System.currentTimeMillis());
            safeJsonPut(data, "state",         state != null ? state.name() : "UNKNOWN");

            boolean ok = (status.equals("OK") || status.equals("TICKET_PENDING"));
            return ok
                ? ApiResult.ok("Printer: 1 - " + status, data)
                : ApiResult.fail("Printer: 0 - " + status, status, data);

        } catch (Exception e) {
            JSONObject d = new JSONObject();
            safeJsonPut(d, "detail", e.getMessage() != null ? e.getMessage() : "");
            return ApiResult.fail("Printer: 0 - Read error", "PRINTER_READ_FAIL", d);
        }
    }
    
    public ApiResult api_deliveryJobGet(String jobId) {
        ApiJob job;
        synchronized (apiJobs) { job = apiJobs.get(jobId); }
        if (job == null) return ApiResult.fail("Job: 0 - Unknown", "JOB_NOT_FOUND");

        final long now = System.currentTimeMillis();

        // --- Throttle (avoid too fast polling) ---
        if (job.lastOkData != null && now < job.nextAllowedReadMs) {
            JSONObject data = safeJsonCopy(job.lastOkData);
            if (data == null) data = new JSONObject();

            safeJsonPut(data, "stale", true);
            safeJsonPut(data, "stale_reason", "RATE_LIMIT");
            safeJsonPut(data, "next_poll_ms", Math.max(0L, job.nextAllowedReadMs - now));

            // ✅ Bonus: injecter le snapshot TickBus dans JobGet (pour UI FieldService)
            try {
                JSONObject tick = buildTickJsonSnapshot();
                safeJsonPut(data, "tick", tick);
            } catch (Exception ignored) {}

            return ApiResult.ok(job.lastOkMsg != null ? job.lastOkMsg : "Job: 1 - RUNNING", data);
        }

        // ensure ticket/sale always present (best-effort)
        if (job.ticketNo == null || job.ticketNo.trim().isEmpty()) {
            try { job.ticketNo = readTicketNo23(); } catch (Exception ignored) {}
        }
        if (job.saleNo == null || job.saleNo.trim().isEmpty()) {
            try { job.saleNo = readSaleNo22(); } catch (Exception ignored) {}
        }

        try {
            int[] ds = lcpDeliveryStatus();
            int delCode = ds[1];

            boolean deliveryActive = (delCode & DC_DELIVERY_ACTIVE) != 0;
            boolean flowActive = (delCode & DC_FLOW_ACTIVE) != 0;
            boolean ticketPending = (delCode & DC_TICKET_PENDING) != 0;

            if (deliveryActive) job.sawDeliveryActiveOnce = true;

            ensureDigits();
            double scale = Math.pow(10, cachedDigits);
            double tol = 1.0 / scale;

            int g = beI32(lcpGetField(FIELD_GROSS_COUNT));
            int n = beI32(lcpGetField(FIELD_NET_COUNT));

            double netL = (n & 0xFFFFFFFFL) / scale;
            double grossL = (g & 0xFFFFFFFFL) / scale;

            
if (deliveryActive && !job.baselineCaptured) {
    job.baselineCaptured = true;

    // ✅ Capture START réel (DE) à partir des TOTAUX registre (#17/#18)
    try {
        job.grossStartRaw = beI32(lcpGetField(FIELD_GROSS_TOTAL));
        job.netStartRaw   = beI32(lcpGetField(FIELD_NET_TOTAL));
    } catch (Exception e0) {
        // fallback ultra-sûr : on se rabat sur les derniers compteurs vus
        job.grossStartRaw = (job.grossTotalRaw != 0) ? job.grossTotalRaw : job.grossEndRaw;
        job.netStartRaw   = (job.netTotalRaw != 0) ? job.netTotalRaw : job.netEndRaw;
    }

    // log BD (snapshot START)
    try {
        DeliveryLogStore store0 = this.logStore;
        if (store0 != null && job.attemptId > 0) {
            JSONObject ev = new JSONObject();
            safeJsonPut(ev, "event", "DELIVERY_START_SNAPSHOT");
            safeJsonPut(ev, "media", job.media == null ? resolveActiveMedia() : job.media);
            safeJsonPut(ev, "gross_start", job.grossStartRaw & 0xFFFFFFFFL);
            safeJsonPut(ev, "net_start", job.netStartRaw & 0xFFFFFFFFL);
            store0.addEventAsync(job.attemptId, DeliveryLogStore.LEVEL_INFO,
                    "DELIVERY_START", "Start snapshot captured", ev.toString());
        }
    } catch (Exception ignored) {}
}

            int d = 0;
            if (job.lastGrossSeen != Integer.MIN_VALUE && job.lastNetSeen != Integer.MIN_VALUE) {
                d = Math.abs(g - job.lastGrossSeen) + Math.abs(n - job.lastNetSeen);
            }
            job.lastGrossSeen = g;
            job.lastNetSeen = n;

            if (d > 0) {
                job.sawFlowOnOnceJob = true;
                job.lastCountsChangeMs = now;
            } else {
                if (job.lastCountsChangeMs == 0L) job.lastCountsChangeMs = now;
            }

            long offAge = job.sawFlowOnOnceJob ? Math.max(0L, now - job.lastCountsChangeMs) : 0L;
            boolean inGrace = (job.continueGraceUntilMs > now);
            boolean flowOffConfirmed = job.sawFlowOnOnceJob && !inGrace && (offAge >= NO_FLOW_CONFIRM_MS);

            boolean pauseActive = false;
            String pauseReason = null;

            if (deliveryActive) {
                if (!job.sawFlowOnOnceJob) {
                    pauseActive = true;
                    pauseReason = "WAIT_FLOW_ON";
                } else if (d == 0) {
                    if (inGrace) {
                        pauseActive = true;
                        pauseReason = "WAIT_PROGRESS_GRACE";
                    } else if (flowOffConfirmed) {
                        pauseActive = true;
                        pauseReason = "FLOW_OFF_CONFIRMED";
                    } else {
                        pauseActive = true;
                        pauseReason = "FLOW_OFF_CONFIRMING";
                    }
                }
            }

            JSONObject data = new JSONObject();
            safeJsonPut(data, "jobId", jobId);
 safeJsonPut(data, "media", (job.media == null ? resolveActiveMedia() : job.media));
            safeJsonPut(data, "numero_livraison", job.numeroLivraison);
            safeJsonPut(data, "ticket_no", job.ticketNo);
            safeJsonPut(data, "sale_no", job.saleNo);
            safeJsonPut(data, "delivery_uid", job.deliveryUid);

            safeJsonPut(data, "deliveryActive", deliveryActive ? 1 : 0);
            safeJsonPut(data, "flowActive", flowActive ? 1 : 0);
            safeJsonPut(data, "ticketPending", ticketPending ? 1 : 0);

            safeJsonPut(data, "net", netL);
            safeJsonPut(data, "gross", grossL);
            safeJsonPut(data, "decimals", cachedDigits);

            safeJsonPut(data, "preset_requested", job.presetNetL_requested);
            safeJsonPut(data, "preset_applied", job.presetNetL_applied);

            safeJsonPut(data, "delivered_net", deliveryActive ? netL : JSONObject.NULL);

            safeJsonPut(data, "pause_active", pauseActive ? 1 : 0);
            safeJsonPut(data, "pause_reason", (pauseReason == null) ? JSONObject.NULL : pauseReason);

            safeJsonPut(data, "flow_off_age_ms", offAge);
            safeJsonPut(data, "flow_off_confirmed", flowOffConfirmed ? 1 : 0);
            safeJsonPut(data, "continue_grace_ms_left", Math.max(0L, job.continueGraceUntilMs - now));
            safeJsonPut(data, "next_poll_ms", API_JOB_MIN_POLL_MS);

            // ✅ Inclure tick snapshot
            try {
                JSONObject tick = buildTickJsonSnapshot();
                safeJsonPut(data, "tick", tick);
            } catch (Exception ignored) {}

            // ✅ Filet "startingAfterContinue"
            boolean startingAfterContinue = (job.continueGraceUntilMs > now) && !deliveryActive && !job.sawDeliveryActiveOnce;
            if (startingAfterContinue) {
                job.state = "RUNNING";
                safeJsonPut(data, "state_job", "RUNNING");
                safeJsonPut(data, "armed", 0);
                safeJsonPut(data, "state", DeliveryState.RUNNING_FLOWING.name());
                safeJsonPut(data, "pause_active", 1);
                safeJsonPut(data, "pause_reason", "WAIT_FLOW_ON");
                safeJsonPut(data, "live_status", "LIVE: RUNNING_FLOWING (Flow OFF - waiting progression)");

                JSONArray a0 = new JSONArray();
                a0.put("CONTINUER");
                a0.put("TERMINER");
                safeJsonPut(data, "available_actions", a0);

                job.lastOkData = safeJsonCopy(data);
                job.lastOkMsg = "Job: 1 - RUNNING";
                job.nextAllowedReadMs = now + API_JOB_MIN_POLL_MS;
                return ApiResult.ok("Job: 1 - RUNNING", data);
            }

            // PENDING (ARMED)
            if (!deliveryActive && !job.sawDeliveryActiveOnce) {
                job.state = "PENDING";
                safeJsonPut(data, "state_job", "PENDING");
                safeJsonPut(data, "armed", 1);
                safeJsonPut(data, "state", DeliveryState.CONNECTED.name());
                safeJsonPut(data, "live_status", liveStatusArmed());
                safeJsonPut(data, "available_actions", actionsContinueTerminate());

                job.lastOkData = safeJsonCopy(data);
                job.lastOkMsg = "Job: 1 - PENDING";
                job.nextAllowedReadMs = now + API_JOB_MIN_POLL_MS;
                return ApiResult.ok("Job: 1 - PENDING", data);
            }

            // DONE
            if (!deliveryActive && job.sawDeliveryActiveOnce) {

            // ✅ SAFE PRINT: si ticketPending reste à 1, attendre clear; sinon erreur API explicite
            if (ticketPending) {
                boolean ok = waitTicketPendingClearedOrTimeout("api/job/done");
                if (!ok) {
                    job.state = "ERROR";
                    job.err = "PRINT_TIMEOUT";
                    JSONObject dd = new JSONObject();
                    safeJsonPut(dd, "jobId", jobId);
                    safeJsonPut(dd, "ticketPending", 1);
                    safeJsonPut(dd, "state_job", "ERROR");
                    safeJsonPut(dd, "err", "PRINT_TIMEOUT");
                    safeJsonPut(dd, "wait_ms", TICKET_DEVICE_LOOP_MS);
                    safeJsonPut(dd, "live_status", "LIVE: CONNECTED - Ticket pending (PRINT TIMEOUT)");
                    // cache pour cohérence sous rate-limit
                    job.lastOkData = safeJsonCopy(dd);
                    job.lastOkMsg = "Job: 0 - ERROR";
                    job.nextAllowedReadMs = now + API_JOB_BACKOFF_ON_FAIL_MS;
                    return ApiResult.fail("Job: 0 - Print timeout (ticket pending stuck)", "PRINT_TIMEOUT", dd);
                }
                // cleared
                ticketPending = false;
            }

                try { job.ticketNo = readTicketNo23(); } catch (Exception ignored) {}
                try { job.saleNo = readSaleNo22(); } catch (Exception ignored) {}

                job.state = "DONE";
                job.done = true;
                job.endMs = now;
                job.grossEndRaw = g;
                job.netEndRaw = n;

                try {
                    job.grossTotalRaw = beI32(lcpGetField(FIELD_GROSS_TOTAL));
                    job.netTotalRaw = beI32(lcpGetField(FIELD_NET_TOTAL));
                } catch (Exception ignored) {}

                
JSONObject result = new JSONObject();

// ✅ Media (usb/bt)
String media = (job.media == null) ? resolveActiveMedia() : job.media;

// START totals (snapshot)
long grossStartU = job.grossStartRaw & 0xFFFFFFFFL;
long netStartU   = job.netStartRaw & 0xFFFFFFFFL;

// END totals
long grossEndU = job.grossTotalRaw & 0xFFFFFFFFL;
long netEndU   = job.netTotalRaw & 0xFFFFFFFFL;

// Fallback si start non capturé (cas rare) -> delta 0
if (!job.baselineCaptured) {
    grossStartU = grossEndU;
    netStartU = netEndU;
}

long grossDeltaU = grossEndU - grossStartU;
long netDeltaU   = netEndU - netStartU;

safeJsonPut(result, "media", media);
safeJsonPut(result, "numero_livraison", job.numeroLivraison);
safeJsonPut(result, "ticket_no", job.ticketNo);
safeJsonPut(result, "serial_id", job.serialId);
safeJsonPut(result, "compartment", job.compartment == null ? JSONObject.NULL : job.compartment);
safeJsonPut(result, "product_number", job.productNumber);
safeJsonPut(result, "sale_no", job.saleNo);

String uid = (job.numeroLivraison == null ? "" : job.numeroLivraison) + "-" + job.ticketNo;
job.deliveryUid = uid;
safeJsonPut(result, "delivery_uid", uid);
try { if (listener != null) listener.onTicketInfo(job.ticketNo, uid); } catch (Exception ignored) {}

safeJsonPut(result, "start_ms", job.startMs);
safeJsonPut(result, "end_ms", job.endMs);
safeJsonPut(result, "start_utc", msToUtcIso(job.startMs));
safeJsonPut(result, "end_utc", msToUtcIso(job.endMs));
safeJsonPut(result, "duration_ms", (job.endMs - job.startMs));
safeJsonPut(result, "duration_s", (job.endMs - job.startMs) / 1000.0);

// ✅ START / END totals
safeJsonPut(result, "gross_total_start", grossStartU);
safeJsonPut(result, "net_total_start", netStartU);
safeJsonPut(result, "gross_total_end", grossEndU);
safeJsonPut(result, "net_total_end", netEndU);

// Backward compat: keep existing keys as END
safeJsonPut(result, "gross_total", grossEndU);
safeJsonPut(result, "net_total", netEndU);

// ✅ DELTA totals
safeJsonPut(result, "gross_delta", grossDeltaU);
safeJsonPut(result, "net_delta", netDeltaU);

safeJsonPut(result, "inventory_written", JSONObject.NULL);
safeJsonPut(result, "host_printed", false); // ✅ false = pas encore imprimé par FieldService
safeJsonPut(result, "ticket_ready_to_print", true); // ✅ signal pour FieldService

// ✅ FieldService printing info
safeJsonPut(result, "fs_action_required", "PRINT_TICKET");
safeJsonPut(result, "fs_net_l",   netDeltaU / scale);
safeJsonPut(result, "fs_gross_l", grossDeltaU / scale);

// ✅ Litres
safeJsonPut(result, "gross_total_start_l", grossStartU / scale);
safeJsonPut(result, "net_total_start_l", netStartU / scale);
safeJsonPut(result, "gross_total_end_l", grossEndU / scale);
safeJsonPut(result, "net_total_end_l", netEndU / scale);

// Backward compat: keep existing keys as END
safeJsonPut(result, "gross_total_l", grossEndU / scale);
safeJsonPut(result, "net_total_l", netEndU / scale);

safeJsonPut(result, "gross_delta_l", grossDeltaU / scale);
safeJsonPut(result, "net_delta_l", netDeltaU / scale);

                // =========================
                // DISPLAY (final register tick - UI truth)
                // =========================
                safeJsonPut(result, "display_tick_ms",  job.displayTickMs);
                safeJsonPut(result, "display_tick_seq", job.displayTickSeq);
                safeJsonPut(result, "display_gross_end_raw", (job.displayGrossRaw & 0xFFFFFFFFL));
                safeJsonPut(result, "display_net_end_raw",   (job.displayNetRaw & 0xFFFFFFFFL));
                safeJsonPut(result, "display_gross_end_l", job.displayGrossL);
                safeJsonPut(result, "display_net_end_l",   job.displayNetL);


safeJsonPut(data, "state_job", "DONE");
                safeJsonPut(data, "armed", 0);
                safeJsonPut(data, "state", DeliveryState.CONNECTED.name());
                safeJsonPut(data, "live_status", "LIVE: CONNECTED - Ready");
                safeJsonPut(data, "available_actions", new JSONArray());
                safeJsonPut(data, "result", result);

                // SQLite
                DeliveryLogStore store = this.logStore;
                if (store != null && job.serialId != null && !job.serialId.isEmpty() && job.ticketNo != null && !job.ticketNo.isEmpty()) {
                    store.upsertSummaryAsync(job.serialId, job.ticketNo, job.saleNo, "DONE", DeliveryLogStore.SOURCE_API, jobId, result.toString(), null);
                    store.updateSummaryTimesAsync(
                            job.serialId, job.ticketNo,
                            job.startMs, job.endMs,
                            msToUtcIso(job.startMs), msToUtcIso(job.endMs),
                            (job.endMs - job.startMs)
                    );
                    if (job.attemptId > 0) {
                        store.addEventAsync(job.attemptId, DeliveryLogStore.LEVEL_INFO, "DONE", "Delivery completed", result.toString());
                        store.closeAttemptAsync(job.attemptId, "DONE", result.toString(), null);
                    }
                }

                job.lastOkData = safeJsonCopy(data);
                job.lastOkMsg = "Job: 1 - DONE";
                job.nextAllowedReadMs = now + API_JOB_MIN_POLL_MS;
                return ApiResult.ok("Job: 1 - DONE", data);
            }

            // RUNNING (normal)
            job.state = "RUNNING";
            safeJsonPut(data, "state_job", "RUNNING");
            safeJsonPut(data, "armed", 0);
            safeJsonPut(data, "state", state.name());

            boolean presetReached = (netL >= (job.presetNetL_applied - tol));
            if (flowOffConfirmed && !presetReached) {
                safeJsonPut(data, "needs_action", "PROMPT_CONTINUE_OR_TERMINATE");
            }

            JSONArray a = new JSONArray();
            a.put("CONTINUER");
            a.put("TERMINER");
            safeJsonPut(data, "available_actions", a);

            if (pauseActive && "WAIT_FLOW_ON".equals(pauseReason)) {
                safeJsonPut(data, "live_status", "LIVE: RUNNING_FLOWING (Flow OFF - waiting progression)");
            } else if (pauseActive && "FLOW_OFF_CONFIRMING".equals(pauseReason)) {
                safeJsonPut(data, "live_status", "LIVE: RUNNING_FLOWING (FLOW OFF - confirming...)");
            } else if (pauseActive && "FLOW_OFF_CONFIRMED".equals(pauseReason)) {
                safeJsonPut(data, "live_status", presetReached
                        ? "LIVE: RUNNING_PAUSED (FLOW OFF confirmed)"
                        : "LIVE: RUNNING_PAUSED (FLOW OFF confirmed - preset not reached)");
            } else {
                safeJsonPut(data, "live_status", "LIVE: RUNNING_FLOWING");
            }

            job.lastOkData = safeJsonCopy(data);
            job.lastOkMsg = "Job: 1 - RUNNING";
            job.nextAllowedReadMs = now + API_JOB_MIN_POLL_MS;
            return ApiResult.ok("Job: 1 - RUNNING", data);

        } catch (Exception e) {
            DeliveryLogStore store = this.logStore;
            if (store != null && job.attemptId > 0) {
                store.addEventAsync(job.attemptId, DeliveryLogStore.LEVEL_WARN, "JOBGET_READ_FAIL", safeMsg(e), null);
            }
            if (job.lastOkData != null) {
                JSONObject data = safeJsonCopy(job.lastOkData);
                if (data == null) data = new JSONObject();
                safeJsonPut(data, "stale", true);
                safeJsonPut(data, "stale_reason", "READ_FAIL");
                safeJsonPut(data, "next_poll_ms", API_JOB_BACKOFF_ON_FAIL_MS);
                job.nextAllowedReadMs = now + API_JOB_BACKOFF_ON_FAIL_MS;

                // tick snapshot even on fail
                try {
                    JSONObject tick = buildTickJsonSnapshot();
                    safeJsonPut(data, "tick", tick);
                } catch (Exception ignored) {}

                return ApiResult.ok(job.lastOkMsg != null ? job.lastOkMsg : "Job: 1 - RUNNING", data);
            }
            JSONObject d = new JSONObject();
            safeJsonPut(d, "detail", (e.getMessage() != null) ? e.getMessage() : "");
            tagErrorLevel(d, "LCP", "api_deliveryJobGet", e);
            return ApiResult.fail("Job: 0 - Read error", "JOB_READ_FAIL", d);
        }
    }

    // ------------------------------------
    // helpers
    // ------------------------------------

// =========================================================
// ✅ Media helper (usb/bt)
// =========================================================
/** Permet à la façade de fixer le média actif (usb/bt). */
public void setActiveMedia(String media) {
    try {
        if (media == null) return;
        String m = media.trim().toLowerCase(Locale.ROOT);
        if (m.isEmpty()) return;
        activeMedia = m;
    } catch (Exception ignored) {}
}

/** Déduit le média du transport si non fixé explicitement. */
private String resolveActiveMedia() {
    try {
        String m = activeMedia;
        if (m != null && !m.trim().isEmpty()) return m.trim().toLowerCase(Locale.ROOT);
    } catch (Exception ignored) {}
    try {
        String k = (link != null) ? link.getTransportKey() : null;
        if (k != null) {
            String u = k.trim().toUpperCase(Locale.ROOT);
            if (u.startsWith("BT") || u.contains("BT:")) return "bt";
            if (u.startsWith("USB") || u.contains("USB")) return "usb";
        }
    } catch (Exception ignored) {}
    return "usb";
}
    private static String safeMsg(Exception e) {
        if (e == null) return "";
        String m = e.getMessage();
        return (m == null) ? e.getClass().getSimpleName() : m;
    }


 // ===== Auto close delivery on FSM end (write in SQLite) =====
 private void onDeliveryFinishedIfNeeded(String reason) {
     if (!deliveryInProgress) return;
     deliveryInProgress = false;
     final long endMs = System.currentTimeMillis();
     final long startMs = (deliveryStartMs > 0L) ? deliveryStartMs : 0L;
     deliveryStartMs = 0L;

     try {
         DeliveryLogStore store = this.logStore;
         if (store == null) return;

         String serialId = null;
         String ticketNo = null;
         String saleNo = null;
         try { serialId = decodeAzString(lcpGetField(FIELD_SERIAL_ID)); } catch (Exception ignored) {}
         try { ticketNo = readTicketNo23(); } catch (Exception ignored) {}
         try { saleNo = readSaleNo22(); } catch (Exception ignored) {}

         if (serialId == null || serialId.trim().isEmpty()) serialId = "__UNKNOWN__";
         if (ticketNo == null || ticketNo.trim().isEmpty()) ticketNo = "TICKET-UNKNOWN";

         JSONObject result = new JSONObject();
         safeJsonPut(result, "event_type", "DELIVERY_DONE");
         safeJsonPut(result, "reason", (reason == null) ? "" : reason);
         safeJsonPut(result, "media", resolveActiveMedia());
         safeJsonPut(result, "transport_key", (link != null) ? link.getTransportKey() : JSONObject.NULL);
         safeJsonPut(result, "serial_id", serialId);
         safeJsonPut(result, "ticket_no", ticketNo);
         safeJsonPut(result, "sale_no", (saleNo == null || saleNo.trim().isEmpty()) ? JSONObject.NULL : saleNo);
         safeJsonPut(result, "start_ms", (startMs > 0L) ? startMs : JSONObject.NULL);
         safeJsonPut(result, "end_ms", endMs);
         safeJsonPut(result, "duration_ms", (startMs > 0L) ? Math.max(0L, endMs - startMs) : JSONObject.NULL);
         if (startMs > 0L) safeJsonPut(result, "start_utc", msToUtcIso(startMs));
         safeJsonPut(result, "end_utc", msToUtcIso(endMs));

         // Summary: clé métier (serial_id, ticket_no)
         store.upsertSummaryAsync(serialId, ticketNo, saleNo, "DELIVERY_DONE", DeliveryLogStore.SOURCE_UI,
                 null, result.toString(), null);

         // Times: si helper disponible (déjà utilisé côté API JobGet)
         try {
             if (startMs > 0L) {
                 store.updateSummaryTimesAsync(serialId, ticketNo,
                         startMs, endMs,
                         msToUtcIso(startMs), msToUtcIso(endMs),
                         Math.max(0L, endMs - startMs));
             }
         } catch (Exception ignored) {}

         // Attempt: on crée une attempt et on la ferme immédiatement (trace livraison)
         store.openAttemptAsync(serialId, ticketNo, DeliveryLogStore.SOURCE_UI, null, attemptId -> {
             try { currentDeliveryAttemptId = attemptId; } catch (Exception ignored) {}
             try {
                 store.addEventAsync(attemptId, DeliveryLogStore.LEVEL_INFO,
                         "DELIVERY_DONE", "Delivery finished", result.toString());
                 store.closeAttemptAsync(attemptId, "DONE", result.toString(), null);
             } catch (Exception ignored) {}
         });

     } catch (Exception ignored) {
     } finally {
         currentDeliveryAttemptId = null;
     }
 }
}
