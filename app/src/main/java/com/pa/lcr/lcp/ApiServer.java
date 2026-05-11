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
                writeHtml(s.getOutputStream(), buildDiagnosticHtml());
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

        } catch (Exception e) {
            t("[API " + ts() + "] IO ERR #" + rid + ": " + safeMsg(e));
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private static boolean isTickWait(HttpReq req) {
        return "GET".equals(req.method) && "/v1/tick/wait".equals(req.path);
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
                return facade.api_deliveryAlignA(node, from, media, btMac);
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

            for (int node = start; node <= end; node++) {
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

            if (regs.length() == 0) {
                JSONObject d = new JSONObject();
                try { d.put("startNode", start); } catch (Exception ignored) {}
                try { d.put("endNode", end); } catch (Exception ignored) {}
                try { d.put("suggestion", "Aucun registre trouvé sur BT ou USB. Vérifier médias READY, puis relancer scan-auto."); } catch (Exception ignored) {}
                return ApiResult.fail("ScanAuto: 0 - Aucun registre trouvé sur BT ou USB", "NO_REGISTER_FOUND", d);
            }

            JSONObject out = new JSONObject();
            try { out.put("startNode", start); } catch (Exception ignored) {}
            try { out.put("endNode", end); } catch (Exception ignored) {}
            try { out.put("count", regs.length()); } catch (Exception ignored) {}
            try { out.put("registers", regs); } catch (Exception ignored) {}
            return ApiResult.ok("ScanAuto: 1 - OK", out);
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

    private static String buildDiagnosticHtml() {
        return "<!DOCTYPE html><html lang='fr'><head><meta charset='UTF-8'>" +
            "<meta name='viewport' content='width=device-width,initial-scale=1'>" +
            "<title>LCR Diagnostic</title>" +
            "<style>" +
            "*{box-sizing:border-box;margin:0;padding:0}" +
            "body{background:#0a0c10;color:#d4daf0;font-family:'IBM Plex Mono',monospace;font-size:13px}" +
            "@import url('https://fonts.googleapis.com/css2?family=IBM+Plex+Mono:wght@400;600;700&display=swap');" +
            ":root{--g:#00e5a0;--r:#ff4d6a;--y:#ffc930;--b:#4d9fff;--bg2:#111318;--bg3:#181c24;--border:rgba(255,255,255,0.08);--border2:rgba(255,255,255,0.18);--muted:#5a6280;--dim:#3a4060}" +
            "header{background:#111318;border-bottom:1px solid var(--border);padding:14px 16px;display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:10px;position:sticky;top:0;z-index:100}" +
            ".logo{font-size:15px;font-weight:700;color:var(--g)}.logo span{color:var(--muted);font-weight:400}" +
            ".conn{display:flex;align-items:center;gap:8px;flex-wrap:wrap}" +
            ".conn label{font-size:10px;color:var(--muted);text-transform:uppercase;letter-spacing:.1em}" +
            ".conn input{background:#181c24;border:1px solid var(--border2);border-radius:5px;color:#d4daf0;font-family:monospace;font-size:12px;padding:5px 9px;width:80px;outline:none}" +
            ".conn input:focus{border-color:var(--g)}" +
            ".btn{background:var(--g);color:#000;border:none;border-radius:5px;font-family:monospace;font-size:11px;font-weight:700;padding:6px 14px;cursor:pointer}" +
            ".dot{width:8px;height:8px;border-radius:50%;background:#3a4060;flex-shrink:0;transition:background .3s}" +
            ".dot.ok{background:var(--g);box-shadow:0 0 6px var(--g)}.dot.err{background:var(--r);box-shadow:0 0 6px var(--r)}" +
            ".stxt{font-size:10px;color:var(--muted)}" +
            ".layout{display:grid;grid-template-columns:240px 1fr;min-height:calc(100vh - 53px)}" +
            ".side{background:#111318;border-right:1px solid var(--border);padding:10px 0;overflow-y:auto}" +
            ".sec{padding:8px 12px 3px;font-size:9px;color:var(--dim);text-transform:uppercase;letter-spacing:.12em}" +
            ".cb{display:flex;align-items:center;gap:7px;width:100%;background:none;border:none;border-left:2px solid transparent;color:var(--muted);font-family:monospace;font-size:11px;padding:7px 12px;cursor:pointer;text-align:left}" +
            ".cb:hover{background:#181c24;color:#d4daf0;border-left-color:var(--border2)}" +
            ".cb.act{background:#181c24;color:#d4daf0;border-left-color:var(--g)}" +
            ".tag{font-size:8px;font-weight:700;padding:1px 5px;border-radius:3px;flex-shrink:0}" +
            ".tg{background:rgba(77,159,255,.2);color:var(--b)}.tp{background:rgba(0,229,160,.2);color:var(--g)}" +
            ".main{padding:16px;display:flex;flex-direction:column;gap:14px;max-width:800px}" +
            ".panel{background:#111318;border:1px solid var(--border);border-radius:8px;overflow:hidden}" +
            ".ph{padding:11px 14px;border-bottom:1px solid var(--border);background:#181c24;display:flex;align-items:center;gap:9px}" +
            ".pt{font-size:12px;font-weight:600;flex:1}.ps{font-size:10px;color:var(--muted)}" +
            ".pb{padding:13px 14px}" +
            ".fr{display:flex;align-items:center;gap:9px;margin-bottom:9px;flex-wrap:wrap}" +
            ".fr label{font-size:10px;color:var(--muted);text-transform:uppercase;letter-spacing:.08em;width:120px;flex-shrink:0}" +
            ".fr input,.fr select{background:#181c24;border:1px solid var(--border2);border-radius:5px;color:#d4daf0;font-family:monospace;font-size:12px;padding:6px 9px;flex:1;min-width:100px;outline:none}" +
            ".fr input:focus,.fr select:focus{border-color:var(--g)}" +
            ".fh{font-size:10px;color:var(--dim);margin-left:129px;margin-top:-5px;margin-bottom:9px}" +
            ".bx{background:var(--g);color:#000;border:none;border-radius:6px;font-family:monospace;font-size:12px;font-weight:700;padding:8px 18px;cursor:pointer;margin-top:4px}" +
            ".bx:disabled{opacity:.4;cursor:not-allowed}" +
            ".rp{background:#111318;border:1px solid var(--border);border-radius:8px;overflow:hidden}" +
            ".rh{padding:9px 13px;border-bottom:1px solid var(--border);background:#181c24;display:flex;align-items:center;justify-content:space-between;gap:8px}" +
            ".rt{font-size:10px;color:var(--muted);text-transform:uppercase;letter-spacing:.1em}" +
            ".badge{font-family:monospace;font-size:10px;font-weight:700;padding:2px 8px;border-radius:20px}" +
            ".bok{background:rgba(0,229,160,.12);color:var(--g)}.berr{background:rgba(255,77,106,.12);color:var(--r)}.bwait{background:rgba(77,159,255,.12);color:var(--b)}" +
            ".rtime{font-size:10px;color:var(--dim)}" +
            ".rb{padding:11px 14px;font-family:monospace;font-size:11px;white-space:pre-wrap;word-break:break-all;max-height:380px;overflow-y:auto;color:#c5d0e8;line-height:1.7}" +
            ".re{padding:28px 14px;text-align:center;color:var(--dim);font-size:11px}" +
            ".rg{display:grid;grid-template-columns:repeat(auto-fill,minmax(160px,1fr));gap:9px;padding:11px 14px 0}" +
            ".rc{background:#181c24;border:1px solid var(--border);border-radius:6px;padding:9px 11px}" +
            ".rl{font-size:9px;color:var(--dim);text-transform:uppercase;letter-spacing:.1em;margin-bottom:3px}" +
            ".rv{font-family:monospace;font-size:14px;font-weight:600}" +
            ".rok{color:var(--g)}.rerr{color:var(--r)}.rwarn{color:var(--y)}" +
            ".rssiw{background:#181c24;border:1px solid var(--border);border-radius:6px;padding:11px 14px;margin-bottom:8px}" +
            ".rsl{font-size:9px;color:var(--dim);text-transform:uppercase;letter-spacing:.1em;margin-bottom:7px}" +
            ".rsbw{height:8px;background:#1e2330;border-radius:4px;overflow:hidden;margin-bottom:5px}" +
            ".rsb{height:100%;border-radius:4px;transition:width .5s,background .5s}" +
            ".rsv{display:flex;justify-content:space-between;font-family:monospace;font-size:11px}" +
            ".log{background:#111318;border:1px solid var(--border);border-radius:8px;overflow:hidden}" +
            ".lh{padding:9px 13px;border-bottom:1px solid var(--border);background:#181c24;display:flex;align-items:center;justify-content:space-between}" +
            ".lt{font-size:10px;color:var(--muted);text-transform:uppercase;letter-spacing:.1em}" +
            ".lc{background:none;border:1px solid var(--border2);border-radius:4px;color:var(--dim);font-family:monospace;font-size:10px;padding:3px 8px;cursor:pointer}" +
            ".lb{padding:9px 13px;font-family:monospace;font-size:10px;max-height:180px;overflow-y:auto;color:var(--muted);line-height:1.8}" +
            ".ll{display:flex;gap:9px}.lts{color:var(--dim);flex-shrink:0}.lok{color:var(--g)}.lerr{color:var(--r)}" +
            ".pl{display:flex;flex-direction:column;align-items:center;justify-content:center;gap:10px;padding:50px 16px;color:var(--dim);font-size:12px;text-align:center}" +
            ".sp{display:inline-block;width:11px;height:11px;border:2px solid rgba(255,255,255,.12);border-top-color:var(--g);border-radius:50%;animation:spin .6s linear infinite;vertical-align:middle}" +
            "@keyframes spin{to{transform:rotate(360deg)}}" +
            "::-webkit-scrollbar{width:5px;height:5px}::-webkit-scrollbar-thumb{background:#1e2330;border-radius:3px}" +
            "@media(max-width:580px){.layout{grid-template-columns:1fr}.side{max-height:200px;border-right:none;border-bottom:1px solid var(--border)}.main{padding:10px}}" +
            "</style></head><body>" +
            "<header>" +
            "<div class='logo'>LCR <span>/ Diagnostic</span></div>" +
            "<div class='conn'>" +
            "<label>Port</label><input id='iPort' value='8765'>" +
            "<div class='dot' id='dot'></div><span class='stxt' id='stxt'>—</span>" +
            "<button class='btn' onclick='doPing()'>Ping</button>" +
            "</div></header>" +
            "<div class='layout'>" +
            "<nav class='side'>" +
            "<div class='sec'>Diagnostic</div>" +
            "<button class='cb' onclick='sel(\"ping\")'><span class='tag tg'>GET</span>/v1/ping</button>" +
            "<div class='sec'>BT Signal</div>" +
            "<button class='cb' onclick='sel(\"bt_scan\")'><span class='tag tp'>POST</span>/v1/bt/signal/scan</button>" +
            "<button class='cb' onclick='sel(\"bt_get\")'><span class='tag tg'>GET</span>/v1/bt/signal</button>" +
            "<div class='sec'>Bluetooth</div>" +
            "<button class='cb' onclick='sel(\"bt_list\")'><span class='tag tg'>GET</span>/v1/bt/list</button>" +
            "<button class='cb' onclick='sel(\"bt_act\")'><span class='tag tp'>POST</span>/v1/bt/activate</button>" +
            "<div class='sec'>USB</div>" +
            "<button class='cb' onclick='sel(\"usb_scan\")'><span class='tag tg'>GET</span>/v1/usb/scan</button>" +
            "<button class='cb' onclick='sel(\"usb_ping\")'><span class='tag tp'>POST</span>/v1/usb/open-ping</button>" +
            "<div class='sec'>Média</div>" +
            "<button class='cb' onclick='sel(\"media_check\")'><span class='tag tp'>POST</span>/v1/media/check</button>" +
            "<button class='cb' onclick='sel(\"media_auto\")'><span class='tag tp'>POST</span>/v1/media/auto-connect</button>" +
            "<div class='sec'>Registre</div>" +
            "<button class='cb' onclick='sel(\"lcp_connect\")'><span class='tag tp'>POST</span>/v1/lcp/connect</button>" +
            "<button class='cb' onclick='sel(\"align\")'><span class='tag tp'>POST</span>/v1/delivery/alignA</button>" +
            "<div class='sec'>Base de données</div>" +
            "<button class='cb' onclick='sel(\"db_dump\")'><span class='tag tp'>POST</span>/v1/db/dump</button>" +
            "</nav>" +
            "<div class='main'>" +
            "<div id='ca'><div class='pl'><div style='font-size:28px'>🛠</div><div>Sélectionnez une commande</div><div style='font-size:10px;color:var(--dim)'>API: http://127.0.0.1:[port]/v1/...</div></div></div>" +
            "<div class='rp' id='rp' style='display:none'>" +
            "<div class='rh'><span class='rt'>Réponse</span><span class='badge bwait' id='rb'>—</span><span class='rtime' id='rt'></span></div>" +
            "<div id='rs' style='display:none'></div>" +
            "<div id='rg' style='display:none'></div>" +
            "<div class='rb' id='rbody'><div class='re'>Aucune réponse</div></div>" +
            "</div>" +
            "<div class='log'><div class='lh'><span class='lt'>Journal</span><button class='lc' onclick='clrLog()'>Effacer</button></div><div class='lb' id='lb'><div style='color:var(--dim)'>Aucune activité</div></div></div>" +
            "</div></div>" +
            "<script>" +
            "var CMDS={ping:{m:'GET',p:'/v1/ping',l:'Ping',f:[]}," +
            "bt_scan:{m:'POST',p:'/v1/bt/signal/scan',l:'Scan RSSI BT',f:[{k:'bt_mac',l:'MAC BT',h:'Vide = tous les appairés',t:'text'}]}," +
            "bt_get:{m:'GET',p:'/v1/bt/signal',l:'Dernier signal (DB)',f:[{k:'bt_mac',l:'MAC BT',h:'Vide = BT actif',t:'text'}]}," +
            "bt_list:{m:'GET',p:'/v1/bt/list',l:'Liste BT',f:[]}," +
            "bt_act:{m:'POST',p:'/v1/bt/activate',l:'Activer BT',f:[]}," +
            "usb_scan:{m:'GET',p:'/v1/usb/scan',l:'Scan USB',f:[]}," +
            "usb_ping:{m:'POST',p:'/v1/usb/open-ping',l:'Ping USB',f:[]}," +
            "media_check:{m:'POST',p:'/v1/media/check',l:'Vérifier média',f:[{k:'media',l:'Média',h:'usb ou bt',t:'select',o:['usb','bt']},{k:'bt_mac',l:'MAC BT',h:'Si bt',t:'text'}]}," +
            "media_auto:{m:'POST',p:'/v1/media/auto-connect',l:'Auto-connexion',f:[{k:'lcrnode_dec',l:'Node LCP',h:'250',t:'number'}]}," +
            "lcp_connect:{m:'POST',p:'/v1/lcp/connect',l:'Connexion LCP',f:[{k:'lcrnode_dec',l:'Node LCP',h:'250',t:'number'},{k:'media',l:'Média',h:'usb ou bt',t:'select',o:['usb','bt']}]}," +
            "align:{m:'POST',p:'/v1/delivery/alignA',l:'Alignement A',f:[{k:'lcrnode_dec',l:'Node LCP',h:'250',t:'number'},{k:'media',l:'Média',h:'usb ou bt',t:'select',o:['usb','bt']}]}," +
            "db_dump:{m:'POST',p:'/v1/db/dump',l:'Export DB',f:[]}};" +
            "var cur=null,log=[];" +
            "function port(){return document.getElementById('iPort').value.trim()||'8765';}" +
            "function base(){return 'http://127.0.0.1:'+port()+'/v1';}" +
            "function sel(id){cur=id;document.querySelectorAll('.cb').forEach(function(b){b.classList.remove('act');if(b.getAttribute('onclick')==='sel(\"'+id+'\")') b.classList.add('act');});render(id);}" +
            "function render(id){var c=CMDS[id];if(!c)return;" +
            "var mc=c.m==='GET'?'tg':'tp';" +
            "var fh='';c.f.forEach(function(f){" +
            "if(f.t==='select'){var opts=(f.o||[]).map(function(o){return'<option>'+o+'</option>';}).join('');fh+='<div class=\"fr\"><label>'+f.l+'</label><select id=\"f_'+f.k+'\">'+opts+'</select></div>';}" +
            "else{fh+='<div class=\"fr\"><label>'+f.l+'</label><input id=\"f_'+f.k+'\" type=\"'+(f.t||'text')+'\" placeholder=\"'+(f.h||'')+'\"></div>';}" +
            "if(f.h) fh+='<div class=\"fh\">'+f.h+'</div>';});" +
            "document.getElementById('ca').innerHTML='<div class=\"panel\"><div class=\"ph\"><span class=\"tag '+mc+'\">'+c.m+'</span><span class=\"pt\">'+c.l+'</span><span class=\"ps\">'+c.p+'</span></div><div class=\"pb\">'+(fh||'<div style=\"font-size:11px;color:var(--dim);margin-bottom:9px\">Aucun paramètre</div>')+'<button class=\"bx\" id=\"bx\" onclick=\"exec()\">▶ Exécuter</button></div></div>';" +
            "document.getElementById('rp').style.display='none';}" +
            "async function exec(){if(!cur)return;var c=CMDS[cur];var bx=document.getElementById('bx');bx.disabled=true;bx.innerHTML='<span class=\"sp\"></span> En cours…';" +
            "var body={};c.f.forEach(function(f){var el=document.getElementById('f_'+f.k);if(el){var v=el.value.trim();if(v)body[f.k]=f.t==='number'?parseInt(v):v;}});" +
            "var url=base()+c.p;if(c.m==='GET'&&body.bt_mac)url+='?bt_mac='+encodeURIComponent(body.bt_mac);" +
            "var t0=Date.now();" +
            "try{var opts={method:c.m};if(c.m==='POST'){opts.headers={'Content-Type':'application/json'};opts.body=JSON.stringify(body);}" +
            "var res=await fetch(url,opts);var ms=Date.now()-t0;var j=await res.json();showResp(j,ms);addLog(c.m,c.p,j.code===1?'ok':'err',ms);}" +
            "catch(e){var ms=Date.now()-t0;showErr(e.message,ms);addLog(c.m,c.p,'err',ms,e.message);}" +
            "bx.disabled=false;bx.innerHTML='▶ Exécuter';}" +
            "function showResp(j,ms){var rp=document.getElementById('rp');rp.style.display='block';" +
            "var rb=document.getElementById('rb');rb.className='badge '+(j.code===1?'bok':'berr');rb.textContent=j.code===1?'✓ OK':'✗ FAIL';" +
            "document.getElementById('rt').textContent=ms+'ms';" +
            "var rs=document.getElementById('rs'),rg=document.getElementById('rg');rs.style.display='none';rg.style.display='none';" +
            "var d=j.data||{};var sc=d.scanned;" +
            "if(sc&&sc.length){rs.style.display='block';rs.innerHTML='<div style=\"padding:11px 14px 0\">'+sc.map(rssiCard).join('')+'</div>';}" +
            "else if(d.rssi!==undefined){rs.style.display='block';rs.innerHTML='<div style=\"padding:11px 14px 0\">'+rssiCard(d)+'</div>';}" +
            "if(d.io_score){rg.style.display='block';rg.innerHTML='<div class=\"rg\">'+sumCards(d)+'</div>';}" +
            "document.getElementById('rbody').innerHTML=hl(JSON.stringify(j,null,2));}" +
            "function showErr(msg,ms){var rp=document.getElementById('rp');rp.style.display='block';" +
            "document.getElementById('rb').className='badge berr';document.getElementById('rb').textContent='✗ ERREUR';" +
            "document.getElementById('rt').textContent=ms+'ms';" +
            "document.getElementById('rs').style.display='none';document.getElementById('rg').style.display='none';" +
            "document.getElementById('rbody').innerHTML='<span style=\"color:#ff4d6a\">'+esc(msg)+'</span>\\n\\n<span style=\"color:#3a4060\">Vérifiez:\\n• Port: '+port()+'\\n• API démarrée (onglet API-Face → Start)\\n• ADB forward depuis PC: adb -d forward tcp:'+port()+' tcp:8765</span>';}" +
            "function rssiCard(r){var rs=r.rssi||-999;var q=r.rssi_quality||'?';var n=r.name||r.mac||'?';var mac=r.mac||'';var src=r.source||'';" +
            "var pct=Math.max(0,Math.min(100,((rs+100)/60)*100));" +
            "var col=rs>=-60?'#00e5a0':rs>=-70?'#4d9fff':rs>=-80?'#ffc930':rs>=-90?'#ff9d4d':'#ff4d6a';" +
            "var qcls=rs>=-60?'rok':rs>=-70?'':rs>=-80?'rwarn':'rerr';" +
            "return '<div class=\"rssiw\"><div class=\"rsl\">'+esc(n)+(mac?' — '+esc(mac):'')+(src?' <span style=\"color:var(--dim);font-size:9px\">['+esc(src)+']</span>':'')+' </div><div class=\"rsbw\"><div class=\"rsb\" style=\"width:'+pct+'%;background:'+col+'\"></div></div><div class=\"rsv\"><span class=\"'+qcls+'\" style=\"font-weight:600\">'+( rs===-999?'N/A':rs+' dBm')+'</span><span style=\"color:'+col+'\">'+esc(q)+'</span></div></div>';}" +
            "function sumCards(d){var cs=[{l:'IO Score',v:d.io_score||'—',c:scls(d.io_score)},{l:'Erreurs',v:d.io_errors||0,c:d.io_errors>0?'rwarn':'rok'},{l:'Timeouts',v:d.io_timeouts||0,c:d.io_timeouts>0?'rerr':'rok'},{l:'Latence',v:(d.io_latency_avg_ms||0)+'ms',c:''},{l:'Échantillons',v:d.io_samples||0,c:''}];return cs.map(function(c){return '<div class=\"rc\"><div class=\"rl\">'+c.l+'</div><div class=\"rv '+c.c+'\">'+esc(String(c.v))+'</div></div>';}).join('');}" +
            "function scls(s){if(!s)return '';if(s==='EXCELLENT'||s==='BON')return 'rok';if(s==='MOYEN')return 'rwarn';return 'rerr';}" +
            "async function doPing(){var d=document.getElementById('dot'),st=document.getElementById('stxt');d.className='dot';st.textContent='…';" +
            "try{var t0=Date.now();var r=await fetch(base()+'/ping');var ms=Date.now()-t0;var j=await r.json();" +
            "if(j.code===1){d.className='dot ok';st.textContent='OK — '+ms+'ms';addLog('GET','/v1/ping','ok',ms);}" +
            "else{d.className='dot err';st.textContent='FAIL';addLog('GET','/v1/ping','err',ms);}}" +
            "catch(e){d.className='dot err';st.textContent='Hors ligne';addLog('GET','/v1/ping','err',0,e.message);}}" +
            "function addLog(m,p,s,ms,d){var n=new Date();var ts=n.toTimeString().slice(0,8)+'.'+String(n.getMilliseconds()).padStart(3,'0');log.unshift({ts,m,p,s,ms,d});if(log.length>50)log.pop();renderLog();}" +
            "function renderLog(){var el=document.getElementById('lb');if(!log.length){el.innerHTML='<div style=\"color:var(--dim)\">Aucune activité</div>';return;}el.innerHTML=log.map(function(e){var cls=e.s==='ok'?'lok':'lerr';var ic=e.s==='ok'?'✓':'✗';var det=e.d?' — '+esc(e.d.slice(0,50)):'';return '<div class=\"ll\"><span class=\"lts\">'+e.ts+'</span><span class=\"'+cls+'\">'+ic+' '+e.m+' '+esc(e.p)+' '+e.ms+'ms'+det+'</span></div>';}).join('');}" +
            "function clrLog(){log=[];renderLog();}" +
            "function hl(s){return esc(s).replace(/&quot;([^&]+)&quot;:/g,'<span style=\"color:#7ec8e8\">&quot;$1&quot;</span>:').replace(/: &quot;([^&]*)&quot;/g,': <span style=\"color:#e0a868\">&quot;$1&quot;</span>').replace(/: (\\d+\\.?\\d*)/g,': <span style=\"color:#ffc930\">$1</span>').replace(/: (true|false)/g,': <span style=\"color:#00e5a0\">$1</span>').replace(/: (null)/g,': <span style=\"color:#ff4d6a\">$1</span>');}" +
            "function esc(s){return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');}" +
            "<\\/script></body></html>";
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