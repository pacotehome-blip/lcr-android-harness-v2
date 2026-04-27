
package com.pa.lcr.lcp;

import com.pa.lcr.lcp.transport.MediaTransportManager;
import org.json.JSONObject;

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

/**
 * API-Face HTTP Server
 * - Bind strict: 127.0.0.1 only (no LAN)
 * - Port: fourni par MainActivity (API_PORT)
 * - Trace: REQ/RESP dans le log principal (via ApiLogSink)
 *
 * ✅ OPTION 2 :
 *   - Sur toutes les routes media-aware: si body.media absent -> media = activeKey
 *     (BT si activeKey=BT:..., sinon USB)
 *
 * ✅ AUTO-HEAL GLOBAL :
 *   - Sur toutes les routes SAUF /v1/register/connect-auto :
 *     si l'opération échoue car "registre/session/média pas prêt",
 *     on tente 3x /register/connect-auto (via facade.api_registerConnectAuto) puis on réessaie.
 *
 * IMPORTANT (FIX):
 *   - media/btMac DOIVENT être recalculés à chaque tentative (dans la lambda),
 *     sinon on reste "collé" sur USB même après auto-connect BT.
 */
public final class ApiServer {

    public interface ApiLogSink { void onApiLine(String line); }

    private final ApiFacade facade;
    private final ApiLogSink trace;
    private final int port;

    private ServerSocket serverSocket;
    private ExecutorService acceptor;
    private ExecutorService workers;
    private volatile boolean running = false;

    private final AtomicInteger ridSeq = new AtomicInteger(0);
    private final Object lcpLock = new Object();

    public ApiServer(ApiFacade facade, ApiLogSink trace, int port) {
        this.facade = facade;
        this.trace = trace;
        this.port = port;
    }

    public synchronized boolean isRunning() { return running; }

    public synchronized void start() throws Exception {
        if (running) return;
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        serverSocket = new ServerSocket(port, 50, loopback);
        workers = Executors.newFixedThreadPool(8);
        acceptor = Executors.newSingleThreadExecutor();
        running = true;
        t("[API " + ts() + "] START http://127.0.0.1:" + port);

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

    // =========================
    // Core handling
    // =========================
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

        } catch (Exception e) {
            t("[API " + ts() + "] IO ERR #" + rid + ": " + safeMsg(e));
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private static boolean isTickWait(HttpReq req) {
        return "GET".equals(req.method) && "/v1/tick/wait".equals(req.path);
    }

    // =========================
    // Ping local (ne dépend pas de ApiFacade)
    // =========================
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

    // =========================
    // Helpers: default media = activeKey (Option 2)
    // =========================
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

    // =========================================================
    // Auto-heal (global): 3 tentatives connect-auto avant de réessayer l'opération
    // =========================================================
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

    /**
     * Wrapper générique:
     * - Exécute op
     * - Si échec "session/média/registre pas prêt", tente 3x connect-auto puis réessaie op
     * - IMPORTANT: NE JAMAIS l'utiliser sur /register/connect-auto
     */
    private ApiResult withAutoConnectRetry(JSONObject body, Supplier<ApiResult> op) {
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

        // Media auto-connect (ALIAS -> register/connect-auto)
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

        // LCP connect  ✅ FIX: media/btMac recalculés à chaque tentative
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

        // Register validate ✅ FIX: media/btMac recalculés à chaque tentative
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

        // Delivery A (alias alignA) ✅ FIX
        if ("POST".equals(req.method) && "/v1/delivery/A".equals(req.path)) {
            JSONObject body = safeBody(req.jsonBody());
            ApiResult gate = gateMediaIfProvided(body);
            if (gate != null) return gate;

            Integer node = parseNodeDec(body);
            Integer from = parseFromDec(body);

            return withAutoConnectRetry(body, () -> {
                String media = resolveMediaDefault(body);
                String btMac = resolveBtMacDefault(body);
                return facade.api_deliveryAlignA(node, from, media, btMac);
            });
        }

        // Delivery alignA ✅ FIX
        if ("POST".equals(req.method) && "/v1/delivery/alignA".equals(req.path)) {
            JSONObject body = safeBody(req.jsonBody());
            ApiResult gate = gateMediaIfProvided(body);
            if (gate != null) return gate;

            Integer node = parseNodeDec(body);
            Integer from = parseFromDec(body);

            return withAutoConnectRetry(body, () -> {
                String media = resolveMediaDefault(body);
                String btMac = resolveBtMacDefault(body);
                return facade.api_deliveryAlignA(node, from, media, btMac);
            });
        }

        // Delivery C ✅ FIX
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

        // OneShot start ✅ FIX
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

        // Delivery job get
        if ("GET".equals(req.method) && req.path != null && req.path.startsWith("/v1/delivery/job/")) {
            String jobId = req.path.substring("/v1/delivery/job/".length());
            if (jobId != null && jobId.trim().isEmpty()) jobId = null;
            final String fJobId = jobId;
            return withAutoConnectRetry(null, () -> facade.api_deliveryJobGet(fJobId));
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

    private void writeJson(OutputStream out, int status, JSONObject json) throws Exception {
        byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
        String hdr = "HTTP/1.1 " + status + " OK\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: " + body.length + "\r\n" +
                "Connection: close\r\n\r\n";
        out.write(hdr.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    // =========================
    // Minimal HTTP parsing
    // =========================
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

    // =========================
    // Trace helpers
    // =========================
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

    private int nextRid() {
        return ridSeq.incrementAndGet();
    }

    private static String safeMsg(Exception e) {
        if (e == null) return "";
        String m = e.getMessage();
        return (m == null) ? e.getClass().getSimpleName() : m;
    }
}