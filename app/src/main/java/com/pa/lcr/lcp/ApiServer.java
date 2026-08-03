package com.pa.lcr.lcp;

import com.pa.lcr.lcp.transport.MediaTransportManager;
import org.json.JSONObject;
import java.util.Set;
import java.util.HashSet;
import org.json.JSONArray;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import java.io.InputStream;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;

public final class ApiServer {

    public interface ApiLogSink { void onApiLine(String line); }

    private final ApiFacade facade;
    private final ApiLogSink trace;
    private final int port;
    private final android.content.Context appCtx;
    private final com.pa.lcr.lcp.storage.ApiTraceStore apiTraceStore;

    private ServerSocket serverSocket;
    private ExecutorService acceptor;
    private ExecutorService workers;
    private volatile boolean running = false;

    private final AtomicInteger ridSeq = new AtomicInteger(0);
    private final Object lcpLock = new Object();

    // ✅ Scan-auto progress tracking
    private static volatile int     scanAutoProgress = 0;
    private static volatile int     scanAutoTotal    = 0;
    private static volatile boolean scanAutoRunning  = false;

    public ApiServer(ApiFacade facade, ApiLogSink trace, int port, android.content.Context ctx) {
        this.facade = facade;
        this.trace = trace;
        this.port = port;
        this.appCtx = ctx;
        this.apiTraceStore = new com.pa.lcr.lcp.storage.ApiTraceStore(ctx);
    }

    public synchronized boolean isRunning() { return running; }

public synchronized void start() throws Exception {
    if (running) return;
    android.util.Log.e("LCRDEMO", "=== ApiServer.start() BEGIN port=" + port + " ===");
    InetAddress loopback = InetAddress.getByName("127.0.0.1");

    // ── HTTPS avec certificat auto-signé ──────────────────────────
    android.util.Log.e("LCRDEMO", "Step 1: opening BKS resource");
    InputStream ksPstream = appCtx.getResources().openRawResource(
        appCtx.getResources().getIdentifier("lcr_keystore", "raw", appCtx.getPackageName())
    );
    android.util.Log.e("LCRDEMO", "Step 2: InputStream ok, bytes=" + ksPstream.available());

    // FIX: fournir explicitement le provider "BC" (BouncyCastle)
    // Sans "BC", Android 9+ ne trouve pas le type BKS → KeyStoreException silencieuse
    KeyStore ks;
    try {
        ks = KeyStore.getInstance("BKS", "BC");
        android.util.Log.e("LCRDEMO", "Step 3: KeyStore BKS/BC ok");
    } catch (Exception e) {
        android.util.Log.e("LCRDEMO", "Step 3 FAIL BKS/BC, essai BKS seul: " + e.getMessage());
        ks = KeyStore.getInstance("BKS");
        android.util.Log.e("LCRDEMO", "Step 3b: KeyStore BKS (sans provider) ok");
    }

    ks.load(ksPstream, "lcr2024secure".toCharArray());
    ksPstream.close();
    android.util.Log.e("LCRDEMO", "Step 4: KeyStore chargé, nb entrées=" + ks.size());

    KeyManagerFactory kmf = KeyManagerFactory.getInstance(
        KeyManagerFactory.getDefaultAlgorithm()
    );
    kmf.init(ks, "lcr2024secure".toCharArray());
    android.util.Log.e("LCRDEMO", "Step 5: KeyManagerFactory ok, algo=" + KeyManagerFactory.getDefaultAlgorithm());

    SSLContext sslCtx = SSLContext.getInstance("TLS");
    sslCtx.init(kmf.getKeyManagers(), null, null);
    android.util.Log.e("LCRDEMO", "Step 6: SSLContext TLS ok");

    SSLServerSocketFactory ssf = sslCtx.getServerSocketFactory();
    serverSocket = ssf.createServerSocket(port, 50, loopback);
    android.util.Log.e("LCRDEMO", "Step 7: SSLServerSocket créé sur 127.0.0.1:" + port);
    // ─────────────────────────────────────────────────────────────

    workers = Executors.newFixedThreadPool(8);
    acceptor = Executors.newSingleThreadExecutor();
    running = true;
    android.util.Log.e("LCRDEMO", "=== ApiServer.start() SUCCESS ===");
    t("[API " + ts() + "] START https://127.0.0.1:" + port);

        acceptor.execute(() -> {
            while (running) {
                try {
                    Socket s = serverSocket.accept();
                    workers.execute(() -> handleClient(s));
                } catch (Exception e) {
                    if (running) t("[API " + ts() + "] accept ERR: " + safeMsg(e));
                }
            }
        });
    }

    public synchronized void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        try { if (acceptor != null) acceptor.shutdownNow(); } catch (Exception ignored) {}
        try { if (workers != null) workers.shutdownNow(); } catch (Exception ignored) {}
        t("[API " + ts() + "] STOP");
    }

    private void handleClient(Socket s) {
        int rid = nextRid();
        String remote = String.valueOf(s.getInetAddress());
        long t0 = System.currentTimeMillis();

        try {
            try { s.setSoTimeout(10_000); } catch (Exception ignored) {}

            BufferedInputStream in = new BufferedInputStream(s.getInputStream());
            HttpReq req = readHttpRequest(in);
            if (req == null) return;

            long t1 = System.currentTimeMillis();

            String shortBody = shrink(req.body);
            t("[API " + ts() + "] REQ #" + rid + " " + remote + " " + req.method + " " + req.path +
                    (shortBody.isEmpty() ? "" : (" body=" + shortBody)));
            
            // ✅ Diagnostic HTML — servi directement (pas de JSON)
            if ("GET".equals(req.method) && "/diagnostic".equals(req.path)) {
                writeHtml(s.getOutputStream(), loadDiagnosticHtml());
                t("[API " + ts() + "] RESP #" + rid + " diagnostic HTML");
                return;
            }

            ApiResult result;
            try {
                if (isTickWait(req)) {
                    synchronized (lcpLock) {
                        result = route(req);
                    }
                } else {
                    result = route(req);
                }
            } catch (Exception e) {
                JSONObject d = new JSONObject();
                try { d.put("detail", safeMsg(e)); } catch (Exception ignored) {}
                result = ApiResult.fail("API: 0 - Exception", "EXCEPTION", d);
            }

            JSONObject json = (result != null) ? result.toJson() : ApiResult.fail("API: 0 - Null", "NULL").toJson();
            writeJson(s, 200, json);

            long t2 = System.currentTimeMillis();
            t("[API " + ts() + "] RESP #" + rid + " " + (t2 - t1) + "ms total=" + (t2 - t0) +
                    "ms -> " + shrink(json.toString()));

            // Phase 1c — trace API best-effort (async, ne bloque jamais la réponse déjà envoyée)
            try {
                JSONObject reqBody = req.jsonBody();
                String serialId = extractBestEffort(reqBody, req.query, "serialId", "serial_id");
                String ticketNo = extractBestEffort(reqBody, req.query, "ticketNo", "ticket_no", "ticket");
                Integer status = (result != null) ? result.code : null; // code métier ApiResult (1=OK/0=FAIL), pas le status HTTP (toujours 200 ici)
                String detail = (result != null && result.code == 0) ? shrink(String.valueOf(result.err)) : null;
                apiTraceStore.addTraceAsync(req.method, req.path, status, (t2 - t1),
                        serialId, ticketNo, null, detail);
            } catch (Exception ignored) {
                // Best-effort seulement — ne doit jamais impacter la requête réelle.
            }

        } catch (Exception e) {
            t("[API " + ts() + "] IO ERR #" + rid + ": " + safeMsg(e));
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private static boolean isTickWait(HttpReq req) {
        return "GET".equals(req.method) && "/v1/tick/wait".equals(req.path);
    }

    /**
     * Phase 1c — extraction best-effort de serial_id/ticket_no pour api_trace.
     * Cherche d'abord dans le body JSON (plusieurs clés possibles, l'API utilisant
     * des conventions inconsistantes selon l'endpoint), puis dans la query string.
     * Retourne null si rien n'est trouvé — c'est un cas normal et attendu, pas une erreur.
     */
    // =========================================================
    // Implémentation des 3 routes Support (demandé 31 juillet 2026)
    // =========================================================

    /** GET /v1/support/events?ticket_no=X */
    private ApiResult apiSupportEvents(String ticketNo) {
        com.pa.lcr.lcp.storage.DeliveryDb dbHelper = null;
        android.database.sqlite.SQLiteDatabase db = null;
        try {
            dbHelper = new com.pa.lcr.lcp.storage.DeliveryDb(appCtx);
            db = dbHelper.getReadableDatabase();

            JSONArray events = new JSONArray();
            try (android.database.Cursor c = db.rawQuery(
                    "SELECT ts, serial_id, ticket_no, event_type, event_code, event_where, detail_short " +
                    "FROM v_diagnostic_events WHERE ticket_no = ? ORDER BY ts DESC LIMIT 300",
                    new String[]{ticketNo})) {
                while (c.moveToNext()) {
                    JSONObject e = new JSONObject();
                    e.put("ts", c.getLong(0));
                    e.put("serial_id", c.getString(1));
                    e.put("ticket_no", c.getString(2));
                    e.put("event_type", c.getString(3));
                    e.put("event_code", c.getString(4));
                    e.put("event_where", c.getString(5));
                    e.put("detail_short", c.getString(6));
                    events.put(e);
                }
            }

            JSONObject data = new JSONObject();
            data.put("ticket_no", ticketNo);
            data.put("count", events.length());
            data.put("events", events);
            return ApiResult.ok("support/events: " + events.length() + " événement(s)", data);
        } catch (Exception e) {
            return ApiResult.fail("support/events: 0 - " + e.getMessage(), "SUPPORT_EVENTS_ERROR");
        } finally {
            if (db != null) try { db.close(); } catch (Exception ignored) {}
            if (dbHelper != null) try { dbHelper.close(); } catch (Exception ignored) {}
        }
    }

    /** GET /v1/support/logbus?node=N */
    private ApiResult apiSupportLogBus(String nodeStr) {
        int node;
        try {
            node = Integer.parseInt(nodeStr);
        } catch (NumberFormatException e) {
            return ApiResult.fail("support/logbus: node invalide", "BAD_PARAM");
        }

        com.pa.lcr.lcp.storage.DeliveryDb dbHelper = null;
        android.database.sqlite.SQLiteDatabase db = null;
        try {
            dbHelper = new com.pa.lcr.lcp.storage.DeliveryDb(appCtx);
            db = dbHelper.getReadableDatabase();

            JSONArray lines = new JSONArray();
            try (android.database.Cursor c = db.rawQuery(
                    "SELECT ts, src, msg FROM log_bus_event WHERE node = ? ORDER BY ts DESC LIMIT 500",
                    new String[]{String.valueOf(node)})) {
                while (c.moveToNext()) {
                    JSONObject e = new JSONObject();
                    e.put("ts", c.getLong(0));
                    e.put("src", c.getString(1));
                    e.put("msg", c.getString(2));
                    lines.put(e);
                }
            }

            JSONObject data = new JSONObject();
            data.put("node", node);
            data.put("count", lines.length());
            data.put("lines", lines);
            return ApiResult.ok("support/logbus: " + lines.length() + " ligne(s)", data);
        } catch (Exception e) {
            return ApiResult.fail("support/logbus: 0 - " + e.getMessage(), "SUPPORT_LOGBUS_ERROR");
        } finally {
            if (db != null) try { db.close(); } catch (Exception ignored) {}
            if (dbHelper != null) try { dbHelper.close(); } catch (Exception ignored) {}
        }
    }

    /** GET /v1/support/diagnose?ticket_no=X&node=N */
    private ApiResult apiSupportDiagnose(String ticketNo, String nodeStr) {
        try {
            com.pa.lcr.lcp.diagnostic.SupportTriageEngine.TriageResult r =
                    com.pa.lcr.lcp.diagnostic.SupportTriageEngine.computeTriage(appCtx, ticketNo, nodeStr);

            JSONArray matchesJson = new JSONArray();
            for (com.pa.lcr.lcp.diagnostic.DiagnosticMatch m : r.matches) {
                JSONObject mj = new JSONObject();
                mj.put("rule_name", m.ruleName);
                mj.put("diagnostic", m.diagnostic);
                mj.put("confidence", m.confidence);
                mj.put("support_level", m.supportLevel);
                mj.put("recommended_action", m.recommendedAction);
                matchesJson.put(mj);
            }

            JSONObject counts = new JSONObject();
            counts.put("transport", r.transportCount);
            counts.put("api", r.apiCount);
            counts.put("ui", r.uiCount);
            counts.put("indetermine", r.indetermineCount);

            JSONObject data = new JSONObject();
            data.put("ticket_no", ticketNo);
            data.put("support_level", r.supportLevel);
            data.put("layer", r.layer);
            data.put("layer_counts", counts);
            data.put("matches", matchesJson);

            return ApiResult.ok("support/diagnose: niveau=" + r.supportLevel + " couche=" + r.layer, data);
        } catch (Exception e) {
            return ApiResult.fail("support/diagnose: 0 - " + e.getMessage(), "SUPPORT_DIAGNOSE_ERROR");
        }
    }


    /**
     * Phase 1c — extraction best-effort de serial_id/ticket_no pour api_trace.
     * Cherche d'abord dans le body JSON (plusieurs clés possibles, l'API utilisant
     * des conventions inconsistantes selon l'endpoint), puis dans la query string.
     * Retourne null si rien n'est trouvé — c'est un cas normal et attendu, pas une erreur.
     */
    private static String extractBestEffort(JSONObject body, Map<String, String> query, String... keys) {
        for (String k : keys) {
            if (body != null) {
                String v = body.optString(k, "").trim();
                if (!v.isEmpty()) return v;
            }
        }
        if (query != null) {
            for (String k : keys) {
                String v = query.get(k);
                if (v != null && !v.trim().isEmpty()) return v.trim();
            }
        }
        return null;
    }

    private ApiResult pingLocal() {
        JSONObject d = new JSONObject();
        try { d.put("version", "v1"); } catch (Exception ignored) {}
        try { d.put("bind", "127.0.0.1"); } catch (Exception ignored) {}
        try { d.put("port", port); } catch (Exception ignored) {}
        return ApiResult.ok("PING: 1 - OK", d);
    }

    private static String resolveBtMacFromApk() {
        try {
            String k = MediaTransportManager.getActiveKeyStatic();
            if (k == null) return null;
            k = k.trim();
            if (k.isEmpty()) return null;
            if (!k.toUpperCase(Locale.ROOT).startsWith("BT:")) return null;
            String mac = k.substring(3).trim();
            return mac.isEmpty() ? null : mac;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String resolveMediaDefault(JSONObject body) {
        try {
            String m = (body != null) ? body.optString("media", "") : "";
            if (m != null) m = m.trim().toLowerCase(Locale.ROOT);
            if (m != null && !m.isEmpty()) return m;

            String activeKey = MediaTransportManager.getActiveKeyStatic();
            if (activeKey != null) {
                String ak = activeKey.trim().toUpperCase(Locale.ROOT);
                if (ak.startsWith("BT:")) return "bt";
            }
            return "usb";
        } catch (Exception ignored) {
            return "usb";
        }
    }

    private static String resolveBtMacDefault(JSONObject body) {
        try {
            String btMac = (body != null)
                    ? body.optString("bt_mac", body.optString("btMac", ""))
                    : "";
            if (btMac != null) btMac = btMac.trim();
            if (btMac != null && !btMac.isEmpty()) return btMac;

            String resolved = resolveBtMacFromApk();
            return (resolved != null) ? resolved.trim() : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static JSONObject safeBody(JSONObject body) {
        return (body != null) ? body : new JSONObject();
    }

    private static final int AUTO_CONNECT_MAX_TRIES = 3;
    private static final long AUTO_CONNECT_DELAY_MS = 250;

    private static boolean shouldRetryViaConnectAuto(ApiResult r) {
        if (r == null) return true;
        if (r.code == 1) return false;

        String err = (r.err != null) ? r.err : "";
        String msg = (r.msg != null) ? r.msg : "";
        String e = err.toUpperCase(Locale.ROOT);
        String m = msg.toUpperCase(Locale.ROOT);

        if (e.contains("ERR_REGISTER_NOT_FOUND")) return true;
        if (e.contains("ERR_MEDIA_NOT_READY")) return true;
        if (e.contains("ERR_USB_PORT_NOT_READY")) return true;
        if (e.contains("ERR_USB_NOT_CONNECTED")) return true;
        if (e.contains("ERR_BT_NOT_CONNECTED")) return true;
        if (e.contains("ERR_LCP_NOT_CONNECTED")) return true;
        if (e.contains("ERR_SESSION")) return true;
        if (m.contains("NON PRÊT") || m.contains("NOT READY")) return true;

        return false;
    }

    private static Integer extractAutoNode(JSONObject body, Integer fallback) {
        Integer n = parseNodeDec(body);
        if (n != null) return n;
        if (body != null && body.has("lcrnode")) {
            int v = body.optInt("lcrnode", 0);
            if (v != 0) return v;
        }
        return fallback;
    }

    private static String extractAutoSerial(JSONObject body) {
        if (body == null) return null;

        String s = body.optString("serialId", "").trim();
        if (s.isEmpty()) s = body.optString("serial_id", "").trim();
        if (s.isEmpty()) s = body.optString("expected_serial_id", "").trim();

        return s.isEmpty() ? null : s;
    }

    private static void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (Exception ignored) {}
    }

    public ApiResult withAutoConnectRetry(JSONObject body, Supplier<ApiResult> op) {
        ApiResult r0 = op.get();
        if (!shouldRetryViaConnectAuto(r0)) return r0;

        Integer node = extractAutoNode(body, null);
        String serial = extractAutoSerial(body);

        if (node == null && (serial == null || serial.isEmpty())) {
            return r0;
        }

        ApiResult lastAuto = null;
        ApiResult lastOp = r0;

        for (int i = 1; i <= AUTO_CONNECT_MAX_TRIES; i++) {
            lastAuto = facade.api_registerConnectAuto(serial, node);
            if (lastAuto != null && lastAuto.code == 1) {
                ApiResult r2 = op.get();
                lastOp = r2;
                if (r2 != null && r2.code == 1) {
                    try {
                        if (r2.data != null) {
                            r2.data.put("autoConnectUsed", true);
                            r2.data.put("autoConnectTries", i);
                        }
                    } catch (Exception ignored) {}
                    return r2;
                }
            }
            sleepQuiet(AUTO_CONNECT_DELAY_MS);
        }

        try {
            if (lastOp != null && lastOp.data != null) {
                lastOp.data.put("autoConnectUsed", true);
                lastOp.data.put("autoConnectTries", AUTO_CONNECT_MAX_TRIES);
                if (lastAuto != null) lastOp.data.put("autoConnectLast", lastAuto.toJson());
            }
        } catch (Exception ignored) {}

        return (lastOp != null) ? lastOp : r0;
    }

    private ApiResult gateMediaIfProvided(JSONObject body) {
        return null;
    }

    // =========================
    // ROUTING
    // =========================
    private ApiResult route(HttpReq req) throws Exception {

        // Ping (local)
        if ("GET".equals(req.method) && "/v1/ping".equals(req.path)) {
            return withAutoConnectRetry(null, this::pingLocal);
        }

        // USB scan
        if ("GET".equals(req.method) && "/v1/usb/scan".equals(req.path)) {
            return withAutoConnectRetry(null, facade::api_scanUsb);
        }

        // BT list
        if ("GET".equals(req.method) && "/v1/bt/list".equals(req.path)) {
            return withAutoConnectRetry(null, facade::api_btList);
        }

        // BT activate
        if ("POST".equals(req.method) && "/v1/bt/activate".equals(req.path)) {
            return withAutoConnectRetry(null, facade::api_btActivate);
        }

        // ✅ BT disconnect
        if ("POST".equals(req.method) && "/v1/bt/disconnect".equals(req.path)) {
            JSONObject body = safeBody(req.jsonBody());
            String btMac = body != null ? body.optString("bt_mac", "").trim() : "";
            return facade.api_btDisconnect(btMac.isEmpty() ? null : btMac);
        }

        // ✅ BT reset
        if ("POST".equals(req.method) && "/v1/bt/reset".equals(req.path)) {
            JSONObject body = safeBody(req.jsonBody());
            String btMac = body != null ? body.optString("bt_mac", "").trim() : "";
            return facade.api_btReset(btMac.isEmpty() ? null : btMac);
        }

        // Media check
        if ("POST".equals(req.method) && "/v1/media/check".equals(req.path)) {
            JSONObject body = safeBody(req.jsonBody());
            return withAutoConnectRetry(body, () -> {
                String media = resolveMediaDefault(body);
                String btMac = resolveBtMacDefault(body);
                if ("bt".equals(media.toLowerCase(Locale.ROOT)) && (btMac == null || btMac.trim().isEmpty())) {
                    String resolved = resolveBtMacFromApk();
                    if (resolved != null && !resolved.isEmpty()) btMac = resolved;
                }
                return facade.api_mediaCheck(media, btMac);
            });
        }

        // Media auto-connect
        if ("POST".equals(req.method) && "/v1/media/auto-connect".equals(req.path)) {
            JSONObject body = safeBody(req.jsonBody());
            return withAutoConnectRetry(body, () -> {
                String serialId = extractAutoSerial(body);
                Integer node = extractAutoNode(body, parseNodeDec(body));
                return facade.api_registerConnectAuto(serialId, node);
            });
        }

        // USB open-ping
        if ("POST".equals(req.method) && "/v1/usb/open-ping".equals(req.path)) {
            return withAutoConnectRetry(null, facade::api_openPingUsb);
        }

        // LCP connect
        if ("POST".equals(req.method) && "/v1/lcp/connect".equals(req.path)) {
            JSONObject body = safeBody(req.jsonBody());
            ApiResult gate = gateMediaIfProvided(body);
            if (gate != null) return gate;
            Integer node = parseNodeDec(body);
            Integer from = parseFromDec(body);
            return withAutoConnectRetry(body, () -> {
                String media = resolveMediaDefault(body);
                String btMac = resolveBtMacDefault(body);
                return facade.api_connectLcp(node, from, media, btMac);
            });
        }

        // Register validate
        if ("POST".equals(req.method) && "/v1/register/validate".equals(req.path)) {
            JSONObject body = safeBody(req.jsonBody());
            ApiResult gate = gateMediaIfProvided(body);
            if (gate != null) return gate;

            String numero = body.optString("numero_livraison", body.optString("numeroLivraison", ""));
            if (numero != null && numero.trim().isEmpty()) numero = null;

            Integer node = parseNodeDec(body);
            Integer from = parseFromDec(body);

            String expectedSerial = body.optString("expected_serial_id", body.optString("serial_id", "")).trim();
            if (expectedSerial.isEmpty()) expectedSerial = null;

            Integer product = null;
            if (body.has("expected_product_number")) product = body.optInt("expected_product_number", 0);
            else if (body.has("product_number")) product = body.optInt("product_number", 0);
            if (product != null && product == 0) product = null;

            String compartment = null;
            try {
                Object c = body.opt("expected_compartment");
                if (c == null || c == JSONObject.NULL) c = body.opt("compartment");
                if (c != null && c != JSONObject.NULL) compartment = String.valueOf(c);
            } catch (Exception ignored) {}

            final String fNumero = numero;
            final String fExpectedSerial = expectedSerial;
            final Integer fProduct = product;
            final String fCompartment = compartment;

            return withAutoConnectRetry(body, () -> {
                String media = resolveMediaDefault(body);
                String btMac = resolveBtMacDefault(body);
                return facade.api_registerValidate(fNumero, node, from, fExpectedSerial, fProduct, fCompartment, media, btMac);
            });
        }

        // Delivery A
        if ("POST".equals(req.method) && "/v1/delivery/A".equals(req.path)) {
            JSONObject body = safeBody(req.jsonBody());
            ApiResult gate = gateMediaIfProvided(body);
            if (gate != null) return gate;
            Integer node = parseNodeDec(body);
            Integer from = parseFromDec(body);
            return withAutoConnectRetry(body, () -> {
                String media = resolveMediaDefault(body);
                String btMac = resolveBtMacDefault(body);
                // ✅ Validation #série optionnelle
                String expSerial = body != null ? body.optString("expected_serial_id","").trim() : "";
                if (!expSerial.isEmpty()) {
                    ApiResult sc = facade.api_connectLcp(node, from, media, btMac);
                    if (sc != null && sc.code == 1 && sc.data != null) {
                        String actualSerial = sc.data.optString("serialId", sc.data.optString("serial_id","")).trim();
                        if (!actualSerial.isEmpty() && !expSerial.equalsIgnoreCase(actualSerial)) {
                            JSONObject ed = new JSONObject();
                            try { ed.put("expected_serial_id", expSerial); ed.put("actual_serial_id", actualSerial); ed.put("node", node); } catch (Exception ignored) {}
                            return ApiResult.fail("Serial mismatch: attendu=" + expSerial + " réel=" + actualSerial, "ERR_SERIAL_MISMATCH", ed);
                        }
                    }
                }
                return facade.api_deliveryAlignA(node, from, media, btMac);
            });
        }
        // ✅ Delivery B — statut live (net/gross temps réel)
        if ("POST".equals(req.method) && "/v1/delivery/B".equals(req.path)) {
            JSONObject body = safeBody(req.jsonBody());
            ApiResult gate = gateMediaIfProvided(body);
            if (gate != null) return gate;
            Integer node = parseNodeDec(body);
            Integer from = parseFromDec(body);
            return withAutoConnectRetry(body, () -> {
                String media = resolveMediaDefault(body);
                String btMac = resolveBtMacDefault(body);
                // ✅ Validation #série optionnelle
                String expSerial = body != null ? body.optString("expected_serial_id","").trim() : "";
                if (!expSerial.isEmpty()) {
                    ApiResult sc = facade.api_connectLcp(node, from, media, btMac);
                    if (sc != null && sc.code == 1 && sc.data != null) {
                        String actualSerial = sc.data.optString("serialId", sc.data.optString("serial_id","")).trim();
                        if (!actualSerial.isEmpty() && !expSerial.equalsIgnoreCase(actualSerial)) {
                            JSONObject ed = new JSONObject();
                            try { ed.put("expected_serial_id", expSerial); ed.put("actual_serial_id", actualSerial); ed.put("node", node); } catch (Exception ignored) {}
                            return ApiResult.fail("Serial mismatch: attendu=" + expSerial + " réel=" + actualSerial, "ERR_SERIAL_MISMATCH", ed);
                        }
                    }
                }
                return facade.api_deliveryStatusB(node, from, media, btMac);
            });
        }
        // ✅ Printer status
        if ("GET".equals(req.method) && "/v1/printer/status".equals(req.path)) {
            JSONObject body = safeBody(req.jsonBody());
            Integer node = parseNodeDec(body);
            Integer from = parseFromDec(body);
            return withAutoConnectRetry(body, () -> {
                String media = resolveMediaDefault(body);
                String btMac = resolveBtMacDefault(body);
                return facade.api_printerStatus(node, from, media, btMac);
            });
        }
        // Delivery alignA
        if ("POST".equals(req.method) && "/v1/delivery/alignA".equals(req.path)) {
            JSONObject body = safeBody(req.jsonBody());
            ApiResult gate = gateMediaIfProvided(body);
            if (gate != null) return gate;
            Integer node = parseNodeDec(body);
            Integer from = parseFromDec(body);
            return withAutoConnectRetry(body, () -> {
                String media = resolveMediaDefault(body);
                String btMac = resolveBtMacDefault(body);
                // ✅ Validation #série optionnelle
                String expSerial = body != null ? body.optString("expected_serial_id","").trim() : "";
                if (!expSerial.isEmpty()) {
                    ApiResult sc = facade.api_connectLcp(node, from, media, btMac);
                    if (sc != null && sc.code == 1 && sc.data != null) {
                        String actualSerial = sc.data.optString("serialId", sc.data.optString("serial_id","")).trim();
                        if (!actualSerial.isEmpty() && !expSerial.equalsIgnoreCase(actualSerial)) {
                            JSONObject ed = new JSONObject();
                            try { ed.put("expected_serial_id", expSerial); ed.put("actual_serial_id", actualSerial); ed.put("node", node); } catch (Exception ignored) {}
                            return ApiResult.fail("Serial mismatch: attendu=" + expSerial + " réel=" + actualSerial, "ERR_SERIAL_MISMATCH", ed);
                        }
                    }
                }
                return facade.api_deliveryAlignA(node, from, media, btMac);
            });
        }


        // Delivery C
        if ("POST".equals(req.method) && "/v1/delivery/C".equals(req.path)) {
            JSONObject body = safeBody(req.jsonBody());
            ApiResult gate = gateMediaIfProvided(body);
            if (gate != null) return gate;
            Integer node = parseNodeDec(body);
            Integer from = parseFromDec(body);
            int product = body.optInt("product1to16", body.optInt("productId", 1));
            double presetNet = body.optDouble("presetNet", 0.0);
            return withAutoConnectRetry(body, () -> {
                String media = resolveMediaDefault(body);
                String btMac = resolveBtMacDefault(body);
                return facade.api_deliveryStartC(node, from, product, presetNet, media, btMac);
            });
        }

        // OneShot start
        if ("POST".equals(req.method) && "/v1/delivery/oneshot/start".equals(req.path)) {
            JSONObject body = safeBody(req.jsonBody());
            ApiResult gate = gateMediaIfProvided(body);
            if (gate != null) return gate;
            Integer node = parseNodeDec(body);
            Integer from = parseFromDec(body);
            String numero = body.optString("numero_livraison", body.optString("numeroLivraison", "")).trim();
            int product = body.optInt("product1to16", body.optInt("productId", 1));
            double presetNetL = body.optDouble("presetNetL", body.optDouble("presetNet", 0.0));
            String compartment = null;
            try {
                Object c = body.opt("compartment");
                if (c != null && c != JSONObject.NULL) compartment = String.valueOf(c);
            } catch (Exception ignored) {}
            final String fNumero = numero;
            final String fCompartment = compartment;
            return withAutoConnectRetry(body, () -> {
                String media = resolveMediaDefault(body);
                String btMac = resolveBtMacDefault(body);
                return facade.api_deliveryOneShotStart(node, from, fNumero, product, presetNetL, fCompartment, media, btMac);
            });
        }

        // ✅ Delivery last-result — dernier résultat livraison (Stratégie B DeepLinkHandler)
        // Utilisé par filgo_lcr_ping.js onLoadForm via fetch 127.0.0.1:8765
        if ("GET".equals(req.method) && "/v1/delivery/last-result".equals(req.path)) {
            String lastJson = com.pa.lcrdemo.DeepLinkHandler.lastResultJson;
            if (lastJson == null || lastJson.isEmpty()) {
                return ApiResult.fail("last-result: 0 - Aucun résultat disponible", "NO_RESULT");
            }
            try {
                JSONObject payload = new JSONObject(lastJson);
                // Vérifier que le résultat est récent (< 10 minutes)
                long ts  = payload.optLong("ts", 0);
                long age = System.currentTimeMillis() - ts;
                if (age > 10 * 60 * 1000L) {
                    return ApiResult.fail("last-result: 0 - Résultat expiré (" + (age/1000) + "s)", "RESULT_EXPIRED");
                }
                return ApiResult.ok("last-result: 1 - OK", payload);
            } catch (Exception e) {
                return ApiResult.fail("last-result: 0 - Parse error", "PARSE_ERROR");
            }
        }

        // Delivery job get
        if ("GET".equals(req.method) && req.path != null && req.path.startsWith("/v1/delivery/job/")) {
            String jobId = req.path.substring("/v1/delivery/job/".length());
            if (jobId != null && jobId.trim().isEmpty()) jobId = null;
            final String fJobId = jobId;
            return withAutoConnectRetry(null, () -> facade.api_deliveryJobGet(fJobId));
        }

        // =========================================================
        // Support (demandé 31 juillet 2026) — expose l'onglet Support à l'API pour que
        // Field Service Mobile ou tout autre appelant puisse interroger le diagnostic.
        // Lecture seule — aucune de ces 3 routes ne modifie delivery_event/api_trace/
        // log_bus_event/diagnostic_rules. diagnose écrit dans diagnostic_match_history
        // (même comportement que le bouton Diagnostiquer — c'est voulu, pas un effet de bord).
        // =========================================================

        // GET /v1/support/events?ticket_no=X — historique v_diagnostic_events pour un ticket
        if ("GET".equals(req.method) && "/v1/support/events".equals(req.path)) {
            String ticketNo = req.query != null ? req.query.get("ticket_no") : null;
            if (ticketNo == null || ticketNo.trim().isEmpty()) {
                return ApiResult.fail("support/events: ticket_no requis", "MISSING_PARAM");
            }
            return apiSupportEvents(ticketNo.trim());
        }

        // GET /v1/support/logbus?node=N — log brut du registre (UI/API/IO_TX/IO_RX) persisté
        if ("GET".equals(req.method) && "/v1/support/logbus".equals(req.path)) {
            String nodeStr = req.query != null ? req.query.get("node") : null;
            if (nodeStr == null || nodeStr.trim().isEmpty()) {
                return ApiResult.fail("support/logbus: node requis", "MISSING_PARAM");
            }
            return apiSupportLogBus(nodeStr.trim());
        }

        // GET /v1/support/diagnose?ticket_no=X&node=N — triage complet (niveau + couche)
        if ("GET".equals(req.method) && "/v1/support/diagnose".equals(req.path)) {
            String ticketNo = req.query != null ? req.query.get("ticket_no") : null;
            String nodeStr = req.query != null ? req.query.get("node") : null;
            if (ticketNo == null || ticketNo.trim().isEmpty()) {
                return ApiResult.fail("support/diagnose: ticket_no requis", "MISSING_PARAM");
            }
            return apiSupportDiagnose(ticketNo.trim(), nodeStr != null ? nodeStr.trim() : null);
        }

        // Delivery job continue
        if ("POST".equals(req.method) && "/v1/delivery/job/continue".equals(req.path)) {
            JSONObject body = safeBody(req.jsonBody());
            String jobId = body.optString("jobId", "").trim();
            if (jobId.isEmpty()) jobId = null;
            Integer node = parseNodeDec(body);
            final String fJobId = jobId;
            final Integer fNode = node;
            return withAutoConnectRetry(body, () -> facade.api_deliveryContinue(fJobId, fNode));
        }

        // Delivery job terminate
        if ("POST".equals(req.method) && "/v1/delivery/job/terminate".equals(req.path)) {
            JSONObject body = safeBody(req.jsonBody());
            String jobId = body.optString("jobId", "").trim();
            if (jobId.isEmpty()) jobId = null;
            Integer node = parseNodeDec(body);
            final String fJobId = jobId;
            final Integer fNode = node;
            return withAutoConnectRetry(body, () -> facade.api_deliveryTerminate(fJobId, fNode));
        }

        // DB dump
        if ("POST".equals(req.method) && "/v1/db/dump".equals(req.path)) {
            return withAutoConnectRetry(null, facade::api_dbDump);
        }

        // ✅ Truck Profile — save
        if ("POST".equals(req.method) && "/v1/profile/save".equals(req.path)) {
            JSONObject body = safeBody(req.jsonBody());
            String truckId      = body != null ? body.optString("truck_id",        "").trim() : "";
            String btMac        = body != null ? body.optString("bt_mac",          "").trim() : "";
            String btName       = body != null ? body.optString("bt_name",         "").trim() : "";
            String serialId     = body != null ? body.optString("serial_id",       "").trim() : "";
            String compartments = body != null ? body.optString("compartments",    "").trim() : "";
            String notes        = body != null ? body.optString("notes",           "").trim() : "";
            Integer lcrnode     = body != null ? (body.has("lcrnode_dec") ? body.optInt("lcrnode_dec") : null) : null;
            Integer defProduct  = body != null ? (body.has("default_product") ? body.optInt("default_product") : null) : null;
            return facade.api_profileSave(
                    truckId.isEmpty() ? null : truckId,
                    btMac.isEmpty()   ? null : btMac,
                    btName.isEmpty()  ? null : btName,
                    lcrnode, serialId.isEmpty() ? null : serialId,
                    defProduct, compartments.isEmpty() ? null : compartments,
                    notes.isEmpty() ? null : notes);
        }

        // ✅ Truck Profile — list
        if ("GET".equals(req.method) && "/v1/profile/list".equals(req.path)) {
            return facade.api_profileList();
        }

        // ✅ Truck Profile — active
        if ("GET".equals(req.method) && "/v1/profile/active".equals(req.path)) {
            return facade.api_profileActive();
        }

        // ✅ Truck Profile — activate
        if ("POST".equals(req.method) && "/v1/profile/activate".equals(req.path)) {
            JSONObject body = safeBody(req.jsonBody());
            String truckId = body != null ? body.optString("truck_id", "").trim() : "";
            if (truckId.isEmpty()) return ApiResult.fail("Profile: 0 - truck_id requis", "ERR_TRUCK_ID_REQUIRED");
            return facade.api_profileActivate(truckId);
        }

        // ✅ Truck Profile — validate (drift detection)
        if ("POST".equals(req.method) && "/v1/profile/validate".equals(req.path)) {
            JSONObject body = safeBody(req.jsonBody());
            String truckId     = body != null ? body.optString("truck_id",         "").trim() : "";
            String actualMac   = body != null ? body.optString("actual_bt_mac",    "").trim() : "";
            String actualName  = body != null ? body.optString("actual_bt_name",   "").trim() : "";
            String actualSerial= body != null ? body.optString("actual_serial_id", "").trim() : "";
            String delivUid    = body != null ? body.optString("delivery_uid",     "").trim() : "";
            Integer actualNode = body != null ? (body.has("actual_lcrnode") ? body.optInt("actual_lcrnode") : null) : null;
            return facade.api_profileValidate(
                    truckId.isEmpty()      ? null : truckId,
                    actualMac.isEmpty()    ? null : actualMac,
                    actualName.isEmpty()   ? null : actualName,
                    actualNode,
                    actualSerial.isEmpty() ? null : actualSerial,
                    delivUid.isEmpty()     ? null : delivUid);
        }

        // ✅ Truck Profile — drift list
        if ("GET".equals(req.method) && "/v1/profile/drift".equals(req.path)) {
            String truckId    = req.query != null ? req.query.getOrDefault("truck_id", "").trim() : "";
            String unackedStr = req.query != null ? req.query.getOrDefault("only_unacked", "1").trim() : "1";
            boolean onlyUnacked = !"0".equals(unackedStr);
            return facade.api_profileDrift(truckId.isEmpty() ? null : truckId, onlyUnacked);
        }

        // ✅ Truck Profile — acknowledge drifts
        if ("POST".equals(req.method) && "/v1/profile/acknowledge".equals(req.path)) {
            JSONObject body = safeBody(req.jsonBody());
            String truckId = body != null ? body.optString("truck_id", "").trim() : "";
            return facade.api_profileAcknowledge(truckId.isEmpty() ? null : truckId);
        }

        // ✅ Truck Profile — delete
        if ("POST".equals(req.method) && "/v1/profile/delete".equals(req.path)) {
            JSONObject body = safeBody(req.jsonBody());
            String truckId = body != null ? body.optString("truck_id", "").trim() : "";
            if (truckId.isEmpty()) return ApiResult.fail("Profile: 0 - truck_id requis", "ERR_TRUCK_ID_REQUIRED");
            return facade.api_profileDelete(truckId);
        }

        // Tick wait
        if ("GET".equals(req.method) && "/v1/tick/wait".equals(req.path)) {
            Integer node = null;
            Long since = null;
            Integer wait = null;
            try {
                if (req.query != null) {
                    String n = req.query.get("lcrnode_dec");
                    if (n == null) n = req.query.get("lcrnode");
                    if (n != null && !n.trim().isEmpty()) node = Integer.parseInt(n.trim());

                    String s = req.query.get("since_seq");
                    if (s != null && !s.trim().isEmpty()) since = Long.parseLong(s.trim());

                    String w = req.query.get("wait_ms");
                    if (w != null && !w.trim().isEmpty()) wait = Integer.parseInt(w.trim());
                }
            } catch (Exception ignored) {}

            JSONObject bodyHint = new JSONObject();
            try { if (node != null) bodyHint.put("lcrnode_dec", node); } catch (Exception ignored) {}

            final Integer fNode = node;
            final Long fSince = since;
            final Integer fWait = wait;

            return withAutoConnectRetry(bodyHint, () -> facade.api_tickWait(fNode, fSince, fWait));
        }

        // Register scan-auto
        if ("POST".equals(req.method) && "/v1/register/scan-auto".equals(req.path)) {
            JSONObject body = safeBody(req.jsonBody());
            int start = body.has("startNode") ? body.optInt("startNode", 1) : 1;
            int end = body.has("endNode") ? body.optInt("endNode", 250) : 250;
            if (start < 1) start = 1;
            if (end > 250) end = 250;
            if (end < start) { int tmp = start; start = end; end = tmp; }

            JSONArray regs = new JSONArray();
            Set<String> seenSerial = new HashSet<>();

            // ✅ Progress tracking
            int scanTotal = end - start + 1;
            scanAutoTotal    = scanTotal;
            scanAutoProgress = 0;
            scanAutoRunning  = true;

            for (int node = start; node <= end; node++) {
                scanAutoProgress = node - start + 1;
                ApiResult ar;
                try {
                    ar = facade.api_registerConnectAuto(null, node);
                } catch (Exception e) {
                    continue;
                }
                if (ar == null || ar.code != 1) continue;

                JSONObject d = (ar.data != null) ? ar.data : new JSONObject();

                String serial = null;
                try {
                    serial = d.optString("serialId", d.optString("serial_id", d.optString("serial", "")));
                    if (serial != null) serial = serial.trim();
                } catch (Exception ignored) {}
                if (serial != null && !serial.isEmpty()) {
                    if (seenSerial.contains(serial)) continue;
                    seenSerial.add(serial);
                }
                regs.put(d);
            }

            scanAutoRunning = false;

            if (regs.length() == 0) {
                JSONObject d = new JSONObject();
                try { d.put("startNode",  start); } catch (Exception ignored) {}
                try { d.put("endNode",    end); } catch (Exception ignored) {}
                try { d.put("scanned",    scanTotal); } catch (Exception ignored) {}
                try { d.put("suggestion", "Aucun registre trouvé sur BT ou USB. Vérifier médias READY, puis relancer scan-auto."); } catch (Exception ignored) {}
                return ApiResult.fail("ScanAuto: 0 - Aucun registre trouvé sur BT ou USB", "NO_REGISTER_FOUND", d);
            }

            JSONObject out = new JSONObject();
            try { out.put("startNode", start); } catch (Exception ignored) {}
            try { out.put("endNode",   end); } catch (Exception ignored) {}
            try { out.put("scanned",   scanTotal); } catch (Exception ignored) {}
            try { out.put("count",     regs.length()); } catch (Exception ignored) {}
            try { out.put("registers", regs); } catch (Exception ignored) {}
            return ApiResult.ok("ScanAuto: 1 - OK (" + regs.length() + "/" + scanTotal + " nodes scannés)", out);
        }

        // ✅ Scan-auto progress
        if ("GET".equals(req.method) && "/v1/register/scan-progress".equals(req.path)) {
            JSONObject d = new JSONObject();
            try {
                d.put("running",  scanAutoRunning  ? 1 : 0);
                d.put("progress", scanAutoProgress);
                d.put("total",    scanAutoTotal);
                d.put("pct",      scanAutoTotal > 0 ? (scanAutoProgress * 100 / scanAutoTotal) : 0);
            } catch (Exception ignored) {}
            return ApiResult.ok("ScanProgress: 1 - OK", d);
        }

        // ✅ BT Signal — lecture (RSSI + IO score)
        if ("GET".equals(req.method) && "/v1/bt/signal".equals(req.path)) {
            String btMac = null;
            try {
                if (req.query != null) btMac = req.query.get("bt_mac");
            } catch (Exception ignored) {}
            final String fBtMac = btMac;
            return withAutoConnectRetry(null, () -> facade.api_btSignalGet(fBtMac));
        }

        // ✅ BT Signal — scan RSSI ponctuel (bloqué si livraison active)
        if ("POST".equals(req.method) && "/v1/bt/signal/scan".equals(req.path)) {
            JSONObject body = safeBody(req.jsonBody());
            String btMac = body.optString("bt_mac", "").trim();
            if (btMac.isEmpty()) btMac = null;
            final String fBtMac = btMac;
            return facade.api_btSignalScan(fBtMac); // PAS de withAutoConnectRetry (scan indépendant)
        }

        // ✅ Ticket reprint (MEDIA-AWARE)
        if ("POST".equals(req.method) && "/v1/ticket/reprint".equals(req.path)) {
            JSONObject body = safeBody(req.jsonBody());
            Integer node = parseNodeDec(body);
            Integer from = parseFromDec(body);
            return withAutoConnectRetry(body, () -> {
                String media = resolveMediaDefault(body);
                String btMac = resolveBtMacDefault(body);
                // 1) Forcer la session sur le bon média (BT ou USB)
                ApiResult c = facade.api_connectLcp(node, from, media, btMac);
                if (c == null || c.code != 1) return c;
                // ✅ Validation #série optionnelle
                String expSerial = body != null ? body.optString("expected_serial_id","").trim() : "";
                if (!expSerial.isEmpty() && c.data != null) {
                    String actualSerial = c.data.optString("serialId", c.data.optString("serial_id","")).trim();
                    if (!actualSerial.isEmpty() && !expSerial.equalsIgnoreCase(actualSerial)) {
                        JSONObject ed = new JSONObject();
                        try { ed.put("expected_serial_id", expSerial); ed.put("actual_serial_id", actualSerial); } catch (Exception ignored) {}
                        return ApiResult.fail("Serial mismatch: attendu=" + expSerial + " réel=" + actualSerial, "ERR_SERIAL_MISMATCH", ed);
                    }
                }
                // 2) Reprint avec media+btMac explicites
                return facade.api_ticketReprintCurrent(node, from, media, btMac);
            });
        }

        // ✅ /v1/register/connect-auto — EXCLU de l'auto-heal (sinon récursion)
        if ("POST".equals(req.method) && "/v1/register/connect-auto".equals(req.path)) {
            JSONObject body = safeBody(req.jsonBody());
            String serialId = (body.has("serialId")) ? body.optString("serialId", null) : null;
            Integer lcrnode = (body.has("lcrnode")) ? body.optInt("lcrnode", 0) : null;
            if (lcrnode != null && lcrnode == 0) lcrnode = null;
            return facade.api_registerConnectAuto(serialId, lcrnode);
        }

        return ApiResult.fail("API: 0 - Not found", "NOT_FOUND",
                new JSONObject().put("path", req.path));
    }

    // =========================
    // Helpers: parse node/from
    // =========================
    private static Integer parseNodeDec(JSONObject body) {
        if (body == null) return null;
        Integer to = null;
        if (body.has("lcrnode_dec")) to = body.optInt("lcrnode_dec", 0);
        else if (body.has("expected_lcrnode_dec")) to = body.optInt("expected_lcrnode_dec", 0);
        else if (body.has("lcrnode")) to = body.optInt("lcrnode", 0);
        if (to != null && to == 0) to = null;
        return to;
    }

    private static Integer parseFromDec(JSONObject body) {
        if (body == null) return null;
        Integer f = null;
        if (body.has("from_dec")) f = body.optInt("from_dec", 0);
        else if (body.has("from")) f = body.optInt("from", 0);
        if (f != null && f == 0) f = null;
        return f;
    }

    // =========================
    // HTTP helpers
    // =========================
    private void writeJson(Socket s, int status, JSONObject json) throws Exception {
        writeJson(s.getOutputStream(), status, json);
    }
    private void writeHtml(OutputStream out, String html) throws Exception {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        String hdr = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: " + body.length + "\r\n" +
                "Connection: close\r\n\r\n";
        out.write(hdr.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private String loadDiagnosticHtml() {
        try {
            java.io.InputStream is = appCtx.getAssets().open("diagnostic.html");
            byte[] buf = new byte[is.available()];
            is.read(buf);
            is.close();
            return new String(buf, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "<html><body>diagnostic.html introuvable</body></html>";
        }
    }
    private void writeJson(OutputStream out, int status, JSONObject json) throws Exception {
        byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
        String hdr = "HTTP/1.1 " + status + " OK\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: " + body.length + "\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Content-Type, Accept, OData-MaxVersion, OData-Version\r\n" +
                "Connection: close\r\n\r\n";
        out.write(hdr.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    private static final class HttpReq {
        final String method;
        final String path;
        final byte[] body;
        final Map<String, String> query;

        HttpReq(String method, String path, byte[] body, Map<String, String> query) {
            this.method = method;
            this.path = path;
            this.body = body;
            this.query = query;
        }

        JSONObject jsonBody() {
            try {
                if (body == null || body.length == 0) return null;
                String s = new String(body, StandardCharsets.UTF_8).trim();
                if (s.isEmpty()) return null;
                return new JSONObject(s);
            } catch (Exception e) {
                return null;
            }
        }
    }

    private static HttpReq readHttpRequest(BufferedInputStream in) throws Exception {
        ByteArrayOutputStream headerOut = new ByteArrayOutputStream();
        int b;
        int state = 0;
        while ((b = in.read()) != -1) {
            headerOut.write(b);
            if (state == 0 && b == '\r') state = 1;
            else if (state == 1 && b == '\n') state = 2;
            else if (state == 2 && b == '\r') state = 3;
            else if (state == 3 && b == '\n') break;
            else state = 0;
            if (headerOut.size() > 16_384) break;
        }

        String header = headerOut.toString(StandardCharsets.UTF_8.name());
        if (header == null || header.trim().isEmpty()) return null;

        String[] lines = header.split("\n");
        if (lines.length == 0) return null;

        String[] first = lines[0].trim().split(" ");
        if (first.length < 2) return null;
        String method = first[0].trim().toUpperCase(Locale.ROOT);
        String fullPath = first[1].trim();

        String path = fullPath;
        Map<String, String> query = new HashMap<>();
        int q = fullPath.indexOf('?');
        if (q >= 0) {
            path = fullPath.substring(0, q);
            String qs = fullPath.substring(q + 1);
            for (String kv : qs.split("&")) {
                int eq = kv.indexOf('=');
                if (eq > 0) {
                    String k = kv.substring(0, eq);
                    String v = kv.substring(eq + 1);
                    query.put(k, v);
                } else if (!kv.trim().isEmpty()) {
                    query.put(kv.trim(), "");
                }
            }
        }

        int contentLength = 0;
        for (String l : lines) {
            String s = l.trim();
            if (s.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                try {
                    contentLength = Integer.parseInt(s.substring("content-length:".length()).trim());
                } catch (Exception ignored) {}
            }
        }

        byte[] body = null;
        if (contentLength > 0 && contentLength < 1_000_000) {
            body = new byte[contentLength];
            int off = 0;
            while (off < contentLength) {
                int r = in.read(body, off, contentLength - off);
                if (r < 0) break;
                off += r;
            }
        }

        return new HttpReq(method, path, body, query);
    }

    private void t(String s) {
        if (trace != null) trace.onApiLine(s);
    }

    private int nextRid() {
        return ridSeq.incrementAndGet();
    }

    private static String ts() {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.CANADA_FRENCH).format(new Date());
    }

    private static String shrink(byte[] body) {
        if (body == null || body.length == 0) return "";
        String s = new String(body, StandardCharsets.UTF_8);
        return shrink(s);
    }

    private static String shrink(String s) {
        if (s == null) return "";
        s = s.replace("\r", "").replace("\n", "");
        if (s.length() > 300) return s.substring(0, 300) + "...";
        return s;
    }

    private static String safeMsg(Exception e) {
        if (e == null) return "";
        String m = e.getMessage();
        return (m == null) ? e.getClass().getSimpleName() : m;
    }
}