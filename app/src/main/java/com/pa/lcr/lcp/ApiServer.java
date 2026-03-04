
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
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * API-Face HTTP Server
 *
 * - Bind strict: 127.0.0.1 only (no LAN)
 * - Port: 8765 (ou configurable plus tard)
 * - Trace: REQ/RESP dans ApiTraceBuffer
 * - Calls: via ApiFacade (bridge vers DeliveryController)
 *
 * Endpoints:
 * GET /v1/ping
 * GET /v1/usb/scan
 * POST /v1/usb/open-ping
 * POST /v1/lcp/connect
 * POST /v1/delivery/C
 * GET /v1/delivery/job/{jobId}
 *
 * + (NOUVEAU)
 * POST /v1/delivery/oneshot/start
 * POST /v1/delivery/job/continue
 * POST /v1/delivery/job/terminate
 */
public final class ApiServer {

    private final ApiFacade facade;
    private final ApiTraceBuffer trace;
    private final int port;

    private ServerSocket serverSocket;
    private ExecutorService acceptor;
    private ExecutorService workers;

    private volatile boolean running = false;

    // ✅ Thread-safe RID sequence (pool workers)
    private final AtomicInteger ridSeq = new AtomicInteger(0);

    // Un seul appel à la fois vers le registre (évite chevauchements côté LCP)
    private final Object lcpLock = new Object();

    public ApiServer(ApiFacade facade, ApiTraceBuffer trace, int port) {
        this.facade = facade;
        this.trace = trace;
        this.port = port;
    }

    public synchronized boolean isRunning() {
        return running;
    }

    public synchronized void start() throws Exception {
        if (running) return;

        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        serverSocket = new ServerSocket(port, 50, loopback);

        workers = Executors.newFixedThreadPool(4);
        acceptor = Executors.newSingleThreadExecutor();

        running = true;

        t("[API] START http://127.0.0.1:" + port);

        acceptor.execute(() -> {
            while (running) {
                try {
                    Socket s = serverSocket.accept();
                    workers.execute(() -> handleClient(s));
                } catch (Exception e) {
                    if (running) t("[API] accept error: " + e.getMessage());
                }
            }
        });
    }

    public synchronized void stop() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        try { if (acceptor != null) acceptor.shutdownNow(); } catch (Exception ignored) {}
        try { if (workers != null) workers.shutdownNow(); } catch (Exception ignored) {}
        t("[API] STOP");
    }

    // =========================
    // Core handling
    // =========================
    private void handleClient(Socket s) {
        int rid = nextRid();
        String remote = String.valueOf(s.getInetAddress());
        long t0 = System.currentTimeMillis();

        try {
            // ✅ Evite worker bloqué sur clients mal formés
            try { s.setSoTimeout(10_000); } catch (Exception ignored) {}

            // Double verrou: n'accepte que loopback
            if (s.getInetAddress() == null || !s.getInetAddress().isLoopbackAddress()) {
                t(ts() + " [API][RID=" + rid + "] REJECT remote=" + remote);
                writeJson(s, 403, ApiResult.fail("API: 0 - Forbidden (loopback only)", "NOT_LOOPBACK").toJson());
                return;
            }

            BufferedInputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();

            HttpReq req = readHttpRequest(in);

            t(ts() + " [API][RID=" + rid + "] REQ " + req.method + " " + req.path + " body=" + shrink(req.body));

            JSONObject resp;
            int status = 200;

            try {
                // Sérialise toute interaction LCP (sécurité/robustesse)
                synchronized (lcpLock) {
                    resp = route(req).toJson();
                }
            } catch (Exception e) {
                status = 500;
                JSONObject d = new JSONObject();
                try { d.put("detail", safeMsg(e)); } catch (Exception ignored) {}
                resp = ApiResult.fail("API: 0 - Internal error", "INTERNAL", d).toJson();
            }

            writeJson(out, status, resp);

            long dt = System.currentTimeMillis() - t0;
            t(ts() + " [API][RID=" + rid + "] RESP " + status + " dt=" + dt + "ms json=" + shrink(resp.toString()));

        } catch (Exception e) {
            t(ts() + " [API][RID=" + rid + "] ERROR " + safeMsg(e) + " remote=" + remote);
            try {
                writeJson(s, 500, ApiResult.fail("API: 0 - Internal error", "INTERNAL").toJson());
            } catch (Exception ignored) {}
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private ApiResult route(HttpReq req) throws Exception {

        // Health
        if ("GET".equals(req.method) && "/v1/ping".equals(req.path)) {
            JSONObject d = new JSONObject();
            d.put("version", "v1");
            d.put("bind", "127.0.0.1");
            d.put("port", port);
            return ApiResult.ok("PING: 1 - OK", d);
        }

        // USB
        if ("GET".equals(req.method) && "/v1/usb/scan".equals(req.path)) {
            return facade.api_scanUsb();
        }
        if ("POST".equals(req.method) && "/v1/usb/open-ping".equals(req.path)) {
            // Body optionnel, pas requis dans ton architecture (MainActivity fait l'open)
            return facade.api_openPingUsb();
        }

        // LCP connect (A/C basé sur 0x28)
        if ("POST".equals(req.method) && "/v1/lcp/connect".equals(req.path)) {
            return facade.api_connectLcp();
        }

        // Delivery C -> job
        if ("POST".equals(req.method) && "/v1/delivery/C".equals(req.path)) {
            JSONObject body = req.jsonBody();
            int product = body.optInt("product1to16", body.optInt("productId", 1));
            double presetNet = body.optDouble("presetNet", 0.0);
            return facade.api_deliveryStartC(product, presetNet);
        }

        // Delivery OneShot
        if ("POST".equals(req.method) && "/v1/delivery/oneshot/start".equals(req.path)) {
            JSONObject body = req.jsonBody();
            String numero = body.optString("numero_livraison", body.optString("numeroLivraison", ""));
            int product = body.optInt("product1to16", body.optInt("product", body.optInt("productId", 1)));
            double preset = body.optDouble("presetNet", body.optDouble("presetNetL", body.optDouble("preset", 0.0)));
            String compartment = null;
            try {
                Object c = body.opt("compartment");
                if (c != null && c != JSONObject.NULL) compartment = String.valueOf(c);
            } catch (Exception ignored) {}
            return facade.api_deliveryOneShotStart(numero, product, preset, compartment);
        }

        // Delivery controls
        if ("POST".equals(req.method) && "/v1/delivery/job/continue".equals(req.path)) {
            JSONObject body = req.jsonBody();
            String jobId = body.optString("jobId", "").trim();
            if (jobId.isEmpty()) return ApiResult.fail("Continue: 0 - Job invalide", "JOB_ID_EMPTY");
            return facade.api_deliveryContinue(jobId);
        }

        if ("POST".equals(req.method) && "/v1/delivery/job/terminate".equals(req.path)) {
            JSONObject body = req.jsonBody();
            String jobId = body.optString("jobId", "").trim();
            if (jobId.isEmpty()) return ApiResult.fail("Terminate: 0 - Job invalide", "JOB_ID_EMPTY");
            return facade.api_deliveryTerminate(jobId);
        }

        // Job
        if ("GET".equals(req.method) && req.path.startsWith("/v1/delivery/job/")) {
            String jobId = req.path.substring("/v1/delivery/job/".length()).trim();
            if (jobId.isEmpty()) return ApiResult.fail("Job: 0 - Invalide", "JOB_ID_EMPTY");
            return facade.api_deliveryJobGet(jobId);
        }

        JSONObject d = new JSONObject();
        try { d.put("path", req.path).put("method", req.method); } catch (Exception ignored) {}
        return ApiResult.fail("API: 0 - Not found", "NOT_FOUND", d);
    }

    // =========================
    // HTTP write helpers
    // =========================
    private void writeJson(Socket s, int status, JSONObject json) throws Exception {
        writeJson(s.getOutputStream(), status, json);
    }

    private void writeJson(OutputStream out, int status, JSONObject json) throws Exception {
        byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);

        String statusText = (status == 200) ? "OK" :
                (status == 403) ? "Forbidden" :
                        (status == 404) ? "Not Found" : "Internal Server Error";

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
    // Minimal HTTP parsing
    // =========================
    private static final class HttpReq {
        final String method;
        final String path;
        final byte[] body;

        HttpReq(String method, String path, byte[] body) {
            this.method = method;
            this.path = path;
            this.body = (body == null) ? new byte[0] : body;
        }

        JSONObject jsonBody() {
            try {
                if (body.length == 0) return new JSONObject();
                return new JSONObject(new String(body, StandardCharsets.UTF_8));
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
        String path = (first.length > 1) ? first[1].trim() : "/";

        // ✅ Strip query string (baseline-safe)
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);

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

        return new HttpReq(method, path, body);
    }

    // =========================
    // Trace helpers
    // =========================
    private void t(String s) {
        if (trace != null) trace.add(s);
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
