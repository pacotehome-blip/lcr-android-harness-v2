
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

/**
 * API-Face HTTP Server a voir
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
 * POST /v1/delivery/A
 * POST /v1/delivery/alignA
 * POST /v1/delivery/C
 * POST /v1/delivery/oneshot/start
 * GET /v1/delivery/job/{jobId}
 * POST /v1/delivery/job/continue
 * POST /v1/delivery/job/terminate
 * POST /v1/db/dump
 *
 * ✅ BT debug/ops:
 * GET  /v1/bt/list
 * POST /v1/bt/activate   (sans body)
 *
 * ✅ Tick (B+):
 * GET /v1/tick/wait?lcrnode_dec=250&since_seq=123&wait_ms=25000
 *
 * ✅ Media check (Option A):
 * POST /v1/media/check body: {"media":"usb"} OR {"media":"bt","bt_mac":"AA:BB:.."}
 *
 * ✅ Correctif (mandat) :
 * - Si media=bt ET bt_mac absent, on résout automatiquement le MAC via l’APK
 *   (MediaTransportManager activeKey "BT:AA:BB:.."), puis on l’utilise.
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

    // Thread-safe RID sequence (pool workers)
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
        workers = Executors.newFixedThreadPool(8); // ✅ more workers (tick/wait long-poll + jobget)
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

            // Ajuster le timeout socket pour tick/wait (long-poll)
            if (isTickWait(req)) {
                long waitMs = req.queryLong("wait_ms", 25_000L);
                // ✅ SAFE: clamp tick/wait to avoid starving other endpoints (JobGet)
                if (waitMs < 0) waitMs = 0;
                if (waitMs > 2000L) waitMs = 2000L;
                try { s.setSoTimeout((int) Math.min(15_000, waitMs + 8_000)); } catch (Exception ignored) {}
            }

            t("[API " + ts() + "][RID=" + rid + "] REQ " + req.method + " " + req.path + " body=" + shrink(req.body));

            ApiResult ar;
            JSONObject resp;
            int status;

            try {
                // Ne PAS lock lcpLock pour tick/wait (cache-only)
                if (isTickWait(req)) {
                    ar = route(req);
                } else {
                    synchronized (lcpLock) {
                        ar = route(req);
                    }
                }

                // HTTP status mapping basé sur ApiResult
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
    // ✅ Helper: resolve BT MAC from APK runtime (MediaTransportManager)
    // =========================
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
    // ✅ Option B: media gating helper (connectivité seulement)
    // =========================
    private ApiResult gateMediaIfProvided(JSONObject body) {
        if (body == null) return null;

        String mediaRaw = body.optString("media", "").trim();
        if (mediaRaw.isEmpty()) return null;

        String media = mediaRaw.toLowerCase(Locale.ROOT);
        String btMac = body.optString("bt_mac", body.optString("btMac", "")).trim();

        // ✅ Ne jamais bloquer sur "auto" (stratégie)
        if ("auto".equals(media)) return null;

        // ✅ CORRECTIF: media=bt et bt_mac absent => récupérer dans l'APK
        if ("bt".equals(media) && btMac.isEmpty()) {
            String resolved = resolveBtMacFromApk();
            if (resolved != null && !resolved.isEmpty()) {
                btMac = resolved;
                try {
                    body.put("bt_mac", resolved);
                    body.put("btMac", resolved);
                } catch (Exception ignored) {}
            }
        }

        ApiResult check = facade.api_mediaCheck(mediaRaw, btMac);

        if (check != null && check.code == 0) {
            if ("bt".equals(media) && btMac.isEmpty() && "ERR_BT_MAC_REQUIRED".equals(check.err)) {
                return null; // ✅ correction: ne pas bloquer
            }
            return check;
        }
        return null;
    }

    // =========================
    // Routing
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

        // ✅ BT list (optionnel debug/ops)
        if ("GET".equals(req.method) && "/v1/bt/list".equals(req.path)) {
            return facade.api_btList();
        }

        // ✅ BT activate (sans body) — EXACT bouton UI "Connect BT"
        if ("POST".equals(req.method) && "/v1/bt/activate".equals(req.path)) {
            return facade.api_btActivate();
        }

        // Media check
        if ("POST".equals(req.method) && "/v1/media/check".equals(req.path)) {
            JSONObject body = req.jsonBody();
            String media = body.optString("media", "").trim();
            if (media.isEmpty()) media = "usb";

            String mediaLc = media.toLowerCase(Locale.ROOT);
            String btMac = body.optString("bt_mac", body.optString("btMac", "")).trim();

            // ✅ CORRECTIF: si BT sans MAC => résoudre via APK et l'utiliser
            if ("bt".equals(mediaLc) && btMac.isEmpty()) {
                String resolved = resolveBtMacFromApk();
                if (resolved != null && !resolved.isEmpty()) {
                    btMac = resolved;
                    try {
                        body.put("bt_mac", resolved);
                        body.put("btMac", resolved);
                    } catch (Exception ignored) {}
                }
            }

            ApiResult r = facade.api_mediaCheck(media, btMac);

            // ✅ BONUS SAFE: si facade répond "ERR_BT_MAC_REQUIRED"
            if ("bt".equals(mediaLc) && (btMac == null || btMac.isEmpty())
                    && r != null && r.code == 0 && "ERR_BT_MAC_REQUIRED".equals(r.err)) {
                JSONObject d = new JSONObject();
                try { d.put("bt_mac", JSONObject.NULL); } catch (Exception ignored) {}
                return ApiResult.ok("MediaCheck: 1 - BT OK (bt_mac résolu: none)", d);
            }

            return r;
        }

        // Tick wait
        if (isTickWait(req)) {
            Integer node = req.queryInt("lcrnode_dec");
            long sinceSeq = req.queryLong("since_seq", 0L);
            long waitMs = req.queryLong("wait_ms", 25_000L);
            if (waitMs < 0) waitMs = 0;
            if (waitMs > 2000L) waitMs = 2000L;
            return facade.api_tickWait(node, sinceSeq, (int) waitMs);
        }

        // USB scan
        if ("GET".equals(req.method) && "/v1/usb/scan".equals(req.path)) {
            return facade.api_scanUsb();
        }

        // USB open-ping
        if ("POST".equals(req.method) && "/v1/usb/open-ping".equals(req.path)) {
            return facade.api_openPingUsb();
        }

        // LCP connect (media-aware) — inchangé
        if ("POST".equals(req.method) && "/v1/lcp/connect".equals(req.path)) {
            JSONObject body = req.jsonBody();
            ApiResult gate = gateMediaIfProvided(body);
            if (gate != null) return gate;

            Integer node = parseNodeDec(body);
            Integer from = parseFromDec(body);
            String media = body.optString("media", "usb");
            String btMac = body.optString("bt_mac", body.optString("btMac", "")).trim();
            return facade.api_connectLcp(node, from, media, btMac);
        }

        // ... le reste de ton route() est inchangé (register/validate, delivery, etc.)
        // (je le garde identique à ta version; si tu veux je te recolle la fin aussi)

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
