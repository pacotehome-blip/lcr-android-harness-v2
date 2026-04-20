
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
 * ApiServer.java — VERSION STRICTE AVEC TOUTES LES ROUTES + CORRECTIFS TECHNIQUES
 *
 * ✅ ROUTING COMPLET (AUCUNE ROUTE SUPPRIMÉE)
 * ✅ LOGIQUE MÉTIER STRICTEMENT IDENTIQUE À LA SOURCE
 * ✅ CORRECTIFS UNIQUEMENT TECHNIQUES (CRLF, parsing HTTP, char literals)
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
            try { s.setSoTimeout(10_000); } catch (Exception ignored) {}

            if (s.getInetAddress() == null || !s.getInetAddress().isLoopbackAddress()) {
                t("[API " + ts() + "][RID=" + rid + "] REJECT remote=" + remote);
                writeJson(s, 403, ApiResult.fail("API: 0 - Forbidden (loopback only)", "NOT_LOOPBACK").toJson());
                return;
            }

            BufferedInputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            HttpReq req = readHttpRequest(in);

            if (isTickWait(req)) {
                long waitMs = req.queryLong("wait_ms", 25_000L);
                if (waitMs < 0) waitMs = 0;
                if (waitMs > 2000L) waitMs = 2000L;
                try { s.setSoTimeout((int) Math.min(15_000, waitMs + 8_000)); } catch (Exception ignored) {}
            }

            ApiResult ar;
            JSONObject resp;
            int status;

            try {
                if (isTickWait(req)) ar = route(req);
                else synchronized (lcpLock) { ar = route(req); }

                if (ar != null && ar.code == 0) {
                    status = "TICKET_PENDING".equals(ar.err) ? 422 : 400;
                } else {
                    status = 200;
                }

                resp = (ar != null) ? ar.toJson()
                        : ApiResult.fail("API: 0 - Internal error", "INTERNAL").toJson();

            } catch (Exception e) {
                status = 500;
                JSONObject d = new JSONObject();
                try { d.put("detail", safeMsg(e)); } catch (Exception ignored) {}
                resp = ApiResult.fail("API: 0 - Internal error", "INTERNAL", d).toJson();
            }

            writeJson(out, status, resp);

            long dt = System.currentTimeMillis() - t0;
            t("[API " + ts() + "][RID=" + rid + "] RESP " + status + " dt=" + dt + "ms");

        } catch (Exception e) {
            t("[API " + ts() + "][RID=" + rid + "] ERROR " + safeMsg(e) + " remote=" + remote);
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private static boolean isTickWait(HttpReq req) {
        return "GET".equals(req.method) && "/v1/tick/wait".equals(req.path);
    }

    private static String resolveBtMacFromApk() {
        try {
            String k = MediaTransportManager.getActiveKeyStatic();
            if (k == null) return null;
            k = k.trim();
            if (!k.toUpperCase(Locale.ROOT).startsWith("BT:")) return null;
            return k.substring(3).trim();
        } catch (Exception ignored) {
            return null;
        }
    }

    private ApiResult gateMediaIfProvided(JSONObject body) {
        if (body == null) return null;
        String mediaRaw = body.optString("media", "").trim();
        if (mediaRaw.isEmpty()) return null;

        String media = mediaRaw.toLowerCase(Locale.ROOT);
        String btMac = body.optString("bt_mac", body.optString("btMac", "")).trim();

        if ("auto".equals(media)) return null;

        if ("bt".equals(media) && btMac.isEmpty()) {
            String resolved = resolveBtMacFromApk();
            if (resolved != null && !resolved.isEmpty()) {
                btMac = resolved;
                try { body.put("bt_mac", resolved); body.put("btMac", resolved); } catch (Exception ignored) {}
            }
        }

        ApiResult check = facade.api_mediaCheck(mediaRaw, btMac);
        if (check != null && check.code == 0) {
            if ("bt".equals(media) && btMac.isEmpty() && "ERR_BT_MAC_REQUIRED".equals(check.err)) return null;
            return check;
        }
        return null;
    }

    // =========================
    // ROUTING — TOUTES LES ROUTES
    // =========================
    private ApiResult route(HttpReq req) throws Exception {
        if ("GET".equals(req.method) && "/v1/ping".equals(req.path)) {
            JSONObject d = new JSONObject();
            d.put("version", "v1"); d.put("bind", "127.0.0.1"); d.put("port", port);
            return ApiResult.ok("PING", d);
        }

        if ("GET".equals(req.method) && "/v1/bt/list".equals(req.path)) return facade.api_btList();
        if ("POST".equals(req.method) && "/v1/bt/activate".equals(req.path)) return facade.api_btActivate();


        if ("POST".equals(req.method) && "/v1/media/check".equals(req.path)) {
            JSONObject b = req.jsonBody();
            return facade.api_mediaCheck(b.optString("media", "usb"), b.optString("bt_mac", resolveBtMacFromApk()));
        }

        if (isTickWait(req)) return facade.api_tickWait(req.queryInt("lcrnode_dec"), req.queryLong("since_seq", 0L), (int)Math.min(2000L, Math.max(0L, req.queryLong("wait_ms", 25_000L))));
        if ("GET".equals(req.method) && "/v1/usb/scan".equals(req.path)) return facade.api_scanUsb();
        if ("POST".equals(req.method) && "/v1/usb/open-ping".equals(req.path)) return facade.api_openPingUsb();


        if ("POST".equals(req.method) && "/v1/lcp/connect".equals(req.path)) {
            JSONObject b = req.jsonBody(); ApiResult g = gateMediaIfProvided(b); if (g != null) return g;
            return facade.api_connectLcp(b.optInt("lcrnode_dec"), b.optInt("from_dec"), b.optString("media", "usb"), b.optString("bt_mac", resolveBtMacFromApk()));
        }

        if ("POST".equals(req.method) && "/v1/register/validate".equals(req.path)) {
            JSONObject b = req.jsonBody(); ApiResult g = gateMediaIfProvided(b); if (g != null) return g;
            return facade.api_registerValidate(b.optString("numero_livraison", null), b.optInt("lcrnode_dec"), b.optInt("from_dec"), b.optString("expected_serial_id", null), b.has("expected_product_number") ? b.optInt("expected_product_number") : null, b.optString("expected_compartment", null), b.optString("media", "usb"), b.optString("bt_mac", resolveBtMacFromApk()));
        }

        if ("POST".equals(req.method) && "/v1/delivery/A".equals(req.path)) return facade.api_deliveryAlignA(req.jsonBody().optInt("lcrnode_dec"), req.jsonBody().optInt("from_dec"), req.jsonBody().optString("media", "usb"), req.jsonBody().optString("bt_mac", resolveBtMacFromApk()));
        if ("POST".equals(req.method) && "/v1/delivery/alignA".equals(req.path)) return facade.api_deliveryAlignA(req.jsonBody().optInt("lcrnode_dec"), req.jsonBody().optInt("from_dec"), req.jsonBody().optString("media", "usb"), req.jsonBody().optString("bt_mac", resolveBtMacFromApk()));
        if ("POST".equals(req.method) && "/v1/delivery/C".equals(req.path)) return facade.api_deliveryStartC(req.jsonBody().optInt("lcrnode_dec"), req.jsonBody().optInt("from_dec"), req.jsonBody().optInt("product1to16", 1), req.jsonBody().optDouble("presetNet", 0.0), req.jsonBody().optString("media", "usb"), req.jsonBody().optString("bt_mac", resolveBtMacFromApk()));
        if ("POST".equals(req.method) && "/v1/delivery/oneshot/start".equals(req.path)) return facade.api_deliveryOneShotStart(req.jsonBody().optInt("lcrnode_dec"), req.jsonBody().optInt("from_dec"), req.jsonBody().optString("numero_livraison", null), req.jsonBody().optInt("product1to16", 1), req.jsonBody().optDouble("presetNet", 0.0), req.jsonBody().optString("compartment", null), req.jsonBody().optString("media", "usb"), req.jsonBody().optString("bt_mac", resolveBtMacFromApk()));
        if ("POST".equals(req.method) && "/v1/delivery/job/continue".equals(req.path)) return facade.api_deliveryContinue(req.jsonBody().optString("jobId", null), req.jsonBody().optInt("lcrnode_dec"));
        if ("POST".equals(req.method) && "/v1/delivery/job/terminate".equals(req.path)) return facade.api_deliveryTerminate(req.jsonBody().optString("jobId", null), req.jsonBody().optInt("lcrnode_dec"));
        if ("GET".equals(req.method) && req.path.startsWith("/v1/delivery/job/")) return facade.api_deliveryJobGet(req.path.substring("/v1/delivery/job/".length()), req.queryInt("lcrnode_dec"));
        if ("POST".equals(req.method) && "/v1/db/dump".equals(req.path)) return facade.api_dbDump();


        JSONObject d = new JSONObject(); d.put("path", req.path).put("method", req.method);
        return ApiResult.fail("API: 0 - Not found", "NOT_FOUND", d);
    }

    private void writeJson(Socket s, int status, JSONObject json) throws Exception { writeJson(s.getOutputStream(), status, json); }
    private void writeJson(OutputStream out, int status, JSONObject json) throws Exception {
        byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
        String statusText = (status == 200) ? "OK" : "ERROR";
        String headers = "HTTP/1.1 " + status + " " + statusText + "\r
" + "Content-Type: application/json; charset=utf-8\r
" + "Content-Length: " + body.length + "\r
" + "Connection: close\r
\r
";
        out.write(headers.getBytes(StandardCharsets.UTF_8)); out.write(body); out.flush();
    }

    private static final class HttpReq {
        final String method, path; final byte[] body; final Map<String, String> query;
        HttpReq(String m, String p, byte[] b, Map<String, String> q) { method=m; path=p; body=b==null?new byte[0]:b; query=q==null?new HashMap<>():q; }
        Integer queryInt(String k){try{String v=query.get(k);if(v==null)return null;int n=Integer.parseInt(v.trim());return n==0?null:n;}catch(Exception e){return null;}}
        long queryLong(String k,long d){try{String v=query.get(k);if(v==null)return d;return Long.parseLong(v.trim());}catch(Exception e){return d;}}
        JSONObject jsonBody(){try{if(body.length==0)return new JSONObject();String s=new String(body,StandardCharsets.UTF_8);if(!s.isEmpty()&&s.charAt(0)=='\uFEFF')s=s.substring(1);return new JSONObject(s);}catch(Exception e){return new JSONObject();}}
    }

    private static HttpReq readHttpRequest(BufferedInputStream in) throws Exception {
        ByteArrayOutputStream h=new ByteArrayOutputStream();int b,st=0;while((b=in.read())!=-1){h.write(b);if(st==0&&b=='\r')st=1;else if(st==1&&b=='
')st=2;else if(st==2&&b=='\r')st=3;else if(st==3&&b=='
')break;else st=0;if(h.size()>16384)break;}String hd=h.toString(StandardCharsets.UTF_8.name());String[] l=hd.split("\r
");if(l.length==0)throw new Exception("bad request");String[] f=l[0].split(" ");String m=f[0],rp=f[1];String p=rp;Map<String,String> q=new HashMap<>();int qi=rp.indexOf('?');if(qi>=0){p=rp.substring(0,qi);String qs=rp.substring(qi+1);for(String kv:qs.split("&")){if(kv.isEmpty())continue;int e=kv.indexOf('=');if(e>0)q.put(kv.substring(0,e),kv.substring(e+1));else q.put(kv,"1");}}int cl=0;for(String ln:l){String ll=ln.toLowerCase(Locale.ROOT);if(ll.startsWith("content-length:")){try{cl=Integer.parseInt(ln.substring(15).trim());}catch(Exception ignored){}}}byte[] bd=new byte[0];if(cl>0){bd=new byte[cl];int r=0;while(r<cl){int rr=in.read(bd,r,cl-r);if(rr<=0)break;r+=rr;}}return new HttpReq(m,p,bd,q);
    }

    private void t(String s) { if (trace != null) trace.onApiLine(s); }
    private int nextRid() { return ridSeq.incrementAndGet(); }
    private static String ts() { return new SimpleDateFormat("HH:mm:ss.SSS", Locale.CANADA_FRENCH).format(new Date()); }
    private static String safeMsg(Exception e) { return e == null ? "" : String.valueOf(e.getMessage()); }
}
