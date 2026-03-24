
package com.pa.lcr.lcp;

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

/**
 * API-Face HTTP Server
 *
 * - Bind strict: 127.0.0.1 only (no LAN)
 * - Port: 8765
 * - Trace: REQ/RESP dans le log principal (via ApiLogSink)
 * - Calls: via ApiFacade
 *
 * Endpoints:
 * GET /v1/ping
 * GET /v1/usb/scan
 * POST /v1/usb/open-ping
 * POST /v1/lcp/connect
 * POST /v1/register/validate
 * POST /v1/delivery/C
 * POST /v1/delivery/oneshot/start
 * GET /v1/delivery/job/{jobId}
 * POST /v1/delivery/job/continue
 * POST /v1/delivery/job/terminate
 * POST /v1/db/dump
 *
 * ✅ NEW (B+ tick push-like via polling):
 * GET /v1/tick/wait?lcrnode_dec=250&since_seq=123&wait_ms=25000
 *
 * ✅ NEW (Option A):
 * POST /v1/media/check  body: {"media":"usb"} OR {"media":"bt","bt_mac":"AA:BB:.."}
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

    // ✅ Thread-safe RID sequence (pool workers)
    private final AtomicInteger ridSeq = new AtomicInteger(0);

    // Un seul appel à la fois vers le registre (évite chevauchements côté LCP)
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
        workers = Executors.newFixedThreadPool(4);
        acceptor = Executors.newSingleThreadExecutor();
        running = true;
        t("[API " + ts() + "] START http://127.0.0.1:" + port);

        acceptor.execute(() -> {
            while (running) {
                try {
                    Socket s = serverSocket.accept();
                    workers.execute(() -> handleClient(s));
                } catch (Exception e) {
                    if (running) t("[API " + ts() + "] accept error: " + safeMsg(e));
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
            // Default timeout (sera ajusté pour tick/wait après parsing)
            try { s.setSoTimeout(10_000); } catch (Exception ignored) {}

            // Double verrou: n'accepte que loopback
            if (s.getInetAddress() == null || !s.getInetAddress().isLoopbackAddress()) {
                t("[API " + ts() + "][RID=" + rid + "] REJECT remote=" + remote);
                writeJson(s, 403, ApiResult.fail("API: 0 - Forbidden (loopback only)", "NOT_LOOPBACK").toJson());
                return;
            }

            BufferedInputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();

            HttpReq req = readHttpRequest(in);

            // ✅ Ajuster le timeout socket pour tick/wait (long-poll)
            if (isTickWait(req)) {
                long waitMs = req.queryLong("wait_ms", 25_000L);
                if (waitMs < 0) waitMs = 0;
                if (waitMs > 30_000L) waitMs = 30_000L;
                try { s.setSoTimeout((int) Math.min(60_000, waitMs + 8_000)); } catch (Exception ignored) {}
            }

            t("[API " + ts() + "][RID=" + rid + "] REQ " + req.method + " " + req.path + " body=" + shrink(req.body));

            ApiResult ar;
            JSONObject resp;
            int status;

            try {
                // ✅ Ne PAS lock lcpLock pour tick/wait (cache-only + wait/notify)
                if (isTickWait(req)) {
                    ar = route(req);
                } else {
                    synchronized (lcpLock) {
                        ar = route(req);
                    }
                }

                // ✅ HTTP status mapping basé sur ApiResult
                if (ar != null && ar.code == 0) {
                    if ("TICKET_PENDING".equals(ar.err)) status = 422;
                    else status = 400;
                } else {
                    status = 200;
                }

                resp = (ar != null)
                        ? ar.toJson()
                        : ApiResult.fail("API: 0 - Internal error", "INTERNAL").toJson();

            } catch (Exception e) {
                status = 500;
                JSONObject d = new JSONObject();
                try { d.put("detail", safeMsg(e)); } catch (Exception ignored) {}
                resp = ApiResult.fail("API: 0 - Internal error", "INTERNAL", d).toJson();
            }

            writeJson(out, status, resp);

            long dt = System.currentTimeMillis() - t0;
            t("[API " + ts() + "][RID=" + rid + "] RESP " + status + " dt=" + dt + "ms json=" + shrink(resp.toString()));

        } catch (Exception e) {
            t("[API " + ts() + "][RID=" + rid + "] ERROR " + safeMsg(e) + " remote=" + remote);
            try {
                writeJson(s, 500, ApiResult.fail("API: 0 - Internal error", "INTERNAL").toJson());
            } catch (Exception ignored) {}
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private static boolean isTickWait(HttpReq req) {
        return "GET".equals(req.method) && "/v1/tick/wait".equals(req.path);
    }

    // =========================
    // ✅ Option A: media gating helper (si body contient "media")
    // =========================
    private ApiResult gateMediaIfProvided(JSONObject body) {
        if (body == null) return null;

        String media = body.optString("media", "").trim();
        if (media.isEmpty()) return null; // pas de media => legacy (USB)

        String btMac = body.optString("bt_mac", body.optString("btMac", "")).trim();

        // 1) Check connectivité (USB/BT) via facade
        ApiResult check = facade.api_mediaCheck(media, btMac);
        if (check != null && check.code == 0) { // FAIL => stop immédiat
            return check;
        }

        // 2) Si ce n'est pas USB, endpoints LCP/Delivery actuels sont USB-only (Option B fera le vrai BT)
        String m = media.toLowerCase(Locale.ROOT);
        if (!"usb".equals(m)) {
            return ApiResult.fail("Media: 0 - " + media + " non supporté par cet endpoint (USB only).",
                    "ERR_MEDIA_NOT_SUPPORTED_YET");
        }

        return null;
    }

    // =========================
    // Routing (B2 node-aware)
    // =========================
    private ApiResult route(HttpReq req) throws Exception {

        // Health
        if ("GET".equals(req.method) && "/v1/ping".equals(req.path)) {
            JSONObject d = new JSONObject();
            d.put("version", "v1");
            d.put("bind", "127.0.0.1");
            d.put("port", port);
            return ApiResult.ok("PING: 1 - OK", d);
        }

        // ✅ NEW: Media check (Option A)
        // POST /v1/media/check  body: {"media":"usb"} OR {"media":"bt","bt_mac":"AA:BB:.."}
        if ("POST".equals(req.method) && "/v1/media/check".equals(req.path)) {
            JSONObject body = req.jsonBody();
            String media = body.optString("media", "").trim();
            if (media.isEmpty()) media = "usb";
            String btMac = body.optString("bt_mac", body.optString("btMac", "")).trim();
            return facade.api_mediaCheck(media, btMac);
        }

        // ✅ NEW: Tick wait (long-poll)
        // GET /v1/tick/wait?lcrnode_dec=250&since_seq=123&wait_ms=25000
        if (isTickWait(req)) {
            Integer node = req.queryInt("lcrnode_dec");
            long sinceSeq = req.queryLong("since_seq", 0L);
            long waitMs = req.queryLong("wait_ms", 25_000L);
            // ApiFacade gère node default + validation
            return facade.api_tickWait(node, sinceSeq, (int) waitMs);
        }

        // USB
        if ("GET".equals(req.method) && "/v1/usb/scan".equals(req.path)) {
            return facade.api_scanUsb();
        }
        if ("POST".equals(req.method) && "/v1/usb/open-ping".equals(req.path)) {
            return facade.api_openPingUsb();
        }

        // LCP connect (B2: node-aware via body)
        if ("POST".equals(req.method) && "/v1/lcp/connect".equals(req.path)) {
            JSONObject body = req.jsonBody();

            ApiResult gate = gateMediaIfProvided(body);
            if (gate != null) return gate;

            Integer node = parseNodeDec(body);
            Integer from = parseFromDec(body);
            return facade.api_connectLcp(node, from);
        }

        // Registre prêt / validation
        if ("POST".equals(req.method) && "/v1/register/validate".equals(req.path)) {
            JSONObject body = req.jsonBody();

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

            return facade.api_registerValidate(numero, node, from, expectedSerial, product, compartment);
        }

        // Ticket: Reprint current
        if ("POST".equals(req.method) && "/v1/ticket/reprint/current".equals(req.path)) {
            JSONObject body = req.jsonBody();

            ApiResult gate = gateMediaIfProvided(body);
            if (gate != null) return gate;

            Integer node = parseNodeDec(body);
            Integer from = parseFromDec(body);
            return facade.api_ticketReprintCurrent(node, from);
        }

        // DB dump
        if ("POST".equals(req.method) && "/v1/db/dump".equals(req.path)) {
            return facade.api_dbDump();
        }

        // Delivery C
        if ("POST".equals(req.method) && "/v1/delivery/C".equals(req.path)) {
            JSONObject body = req.jsonBody();

            ApiResult gate = gateMediaIfProvided(body);
            if (gate != null) return gate;

            Integer node = parseNodeDec(body);
            Integer from = parseFromDec(body);
            int product = body.optInt("product1to16", body.optInt("productId", 1));
            double presetNet = body.optDouble("presetNet", 0.0);
            return facade.api_deliveryStartC(node, from, product, presetNet);
        }

        // Delivery OneShot
        if ("POST".equals(req.method) && "/v1/delivery/oneshot/start".equals(req.path)) {
            JSONObject body = req.jsonBody();

            ApiResult gate = gateMediaIfProvided(body);
            if (gate != null) return gate;

            Integer node = parseNodeDec(body);
            Integer from = parseFromDec(body);

            String numero = body.optString("numero_livraison", body.optString("numeroLivraison", ""));
            int product = body.optInt("product1to16", body.optInt("product", body.optInt("productId", 1)));
            double preset = body.optDouble("presetNet", body.optDouble("presetNetL", body.optDouble("preset", 0.0)));

            String compartment = null;
            try {
                Object c = body.opt("compartment");
                if (c != null && c != JSONObject.NULL) compartment = String.valueOf(c);
            } catch (Exception ignored) {}

            return facade.api_deliveryOneShotStart(node, from, numero, product, preset, compartment);
        }

        // Delivery controls
        if ("POST".equals(req.method) && "/v1/delivery/job/continue".equals(req.path)) {
            JSONObject body = req.jsonBody();

            ApiResult gate = gateMediaIfProvided(body);
            if (gate != null) return gate;

            String jobId = body.optString("jobId", "").trim();
            if (jobId.isEmpty()) return ApiResult.fail("Continue: 0 - Job invalide", "JOB_ID_EMPTY");

            Integer node = parseNodeDec(body);
            return facade.api_deliveryContinue(jobId, node);
        }

        if ("POST".equals(req.method) && "/v1/delivery/job/terminate".equals(req.path)) {
            JSONObject body = req.jsonBody();

            ApiResult gate = gateMediaIfProvided(body);
            if (gate != null) return gate;

            String jobId = body.optString("jobId", "").trim();
            if (jobId.isEmpty()) return ApiResult.fail("Terminate: 0 - Job invalide", "JOB_ID_EMPTY");

            Integer node = parseNodeDec(body);
            return facade.api_deliveryTerminate(jobId, node);
        }

        // Job
        if ("GET".equals(req.method) && req.path.startsWith("/v1/delivery/job/")) {
            String jobId = req.path.substring("/v1/delivery/job/".length()).trim();
            if (jobId.isEmpty()) return ApiResult.fail("Job: 0 - Invalide", "JOB_ID_EMPTY");

            Integer node = req.queryInt("lcrnode_dec");
            return facade.api_deliveryJobGet(jobId, node);
        }

        JSONObject d = new JSONObject();
        try { d.put("path", req.path).put("method", req.method); } catch (Exception ignored) {}
        return ApiResult.fail("API: 0 - Not found", "NOT_FOUND", d);
    }

    // =========================
    // Helpers: parse node/from from JSON body
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
    // HTTP write helpers
    // =========================
    private void writeJson(Socket s, int status, JSONObject json) throws Exception {
        writeJson(s.getOutputStream(), status, json);
    }

    private void writeJson(OutputStream out, int status, JSONObject json) throws Exception {
        byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);

        String statusText =
                (status == 200) ? "OK" :
                (status == 400) ? "Bad Request" :
                (status == 403) ? "Forbidden" :
                (status == 404) ? "Not Found" :
                (status == 422) ? "Unprocessable Entity" : "Internal Server Error";

        String headers =
                "HTTP/1.1 " + status + " " + statusText + "\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n" +
                "Content-Length: " + body.length + "\r\n" +
                "Connection: close\r\n\r\n";

        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }

    // =========================
    // Minimal HTTP parsing (avec query map)
    // =========================
    private static final class HttpReq {
        final String method;
        final String path; // sans query
        final byte[] body;
        final Map<String, String> query;

        HttpReq(String method, String path, byte[] body, Map<String, String> query) {
            this.method = method;
            this.path = path;
            this.body = (body == null) ? new byte[0] : body;
            this.query = (query == null) ? new HashMap<>() : query;
        }

        Integer queryInt(String key) {
            try {
                String v = query.get(key);
                if (v == null) return null;
                int n = Integer.parseInt(v.trim());
                return (n == 0) ? null : n;
            } catch (Exception e) {
                return null;
            }
        }

        long queryLong(String key, long def) {
            try {
                String v = query.get(key);
                if (v == null) return def;
                return Long.parseLong(v.trim());
            } catch (Exception e) {
                return def;
            }
        }

        JSONObject jsonBody() {
            try {
                if (body.length == 0) return new JSONObject();
                String s = new String(body, StandardCharsets.UTF_8);
                // Strip UTF-8 BOM (U+FEFF) si présent
                if (!s.isEmpty() && s.charAt(0) == '\uFEFF') s = s.substring(1);
                return new JSONObject(s);
            } catch (Exception e) {
                return new JSONObject();
            }
        }
    }

    private static HttpReq readHttpRequest(BufferedInputStream in) throws Exception {
        ByteArrayOutputStream headerOut = new ByteArrayOutputStream();
        int b;
        int state = 0;

        // read headers until CRLFCRLF
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
        String[] lines = header.split("\r\n");
        if (lines.length == 0) throw new Exception("bad request");

        String[] first = lines[0].split(" ");
        String method = (first.length > 0) ? first[0].trim() : "GET";
        String rawPath = (first.length > 1) ? first[1].trim() : "/";

        // Parse query string
        String path = rawPath;
        Map<String, String> query = new HashMap<>();
        int q = rawPath.indexOf('?');
        if (q >= 0) {
            path = rawPath.substring(0, q);
            String qs = rawPath.substring(q + 1);
            for (String kv : qs.split("&")) {
                if (kv == null || kv.trim().isEmpty()) continue;
                int eq = kv.indexOf('=');
                if (eq > 0) query.put(kv.substring(0, eq), kv.substring(eq + 1));
                else query.put(kv.trim(), "1");
            }
        }

        int contentLength = 0;
        for (String line : lines) {
            String ll = line.toLowerCase(Locale.ROOT);
            if (ll.startsWith("content-length:")) {
                String v = line.substring("content-length:".length()).trim();
                try { contentLength = Integer.parseInt(v); } catch (Exception ignored) {}
            }
        }

        byte[] body = new byte[0];
        if (contentLength > 0) {
            body = new byte[contentLength];
            int read = 0;
            while (read < contentLength) {
                int r = in.read(body, read, contentLength - read);
                if (r <= 0) break;
                read += r;
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
        if (body == null || body.length == 0) return "{}";
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
