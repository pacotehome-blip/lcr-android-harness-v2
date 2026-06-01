package com.pa.lcrdemo;

import android.webkit.JavascriptInterface;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.security.KeyStore;

/**
 * LcrBridge — Bridge Android WebView → API LCR locale (127.0.0.1:8765)
 *
 * Exposé dans la WebView comme window.LCR
 * Utilisé par Field Service Mobile pour communiquer avec l'APK LCR.
 *
 * Chemin: app/src/main/java/com/pa/lcrdemo/LcrBridge.java
 *
 * Usage côté JavaScript Field Service:
 *   const result = JSON.parse(window.LCR.ping());
 *   const result = JSON.parse(window.LCR.autoConnect(250, "16466294"));
 *   const result = JSON.parse(window.LCR.deliveryB(250, "16466294"));
 */
public class LcrBridge {

    private static final String BASE = "https://127.0.0.1:8765";
    private static final int TIMEOUT_MS = 10_000;
    private static final int TIMEOUT_LONG_MS = 35_000; // pour tick/wait

    // ── GET ───────────────────────────────────────────────────────────────

    @JavascriptInterface
    public String ping() {
        return get("/v1/ping");
    }

    @JavascriptInterface
    public String btList() {
        return get("/v1/bt/list");
    }

    @JavascriptInterface
    public String btSignal() {
        return get("/v1/bt/signal");
    }

    @JavascriptInterface
    public String usbScan() {
        return get("/v1/usb/scan");
    }

    @JavascriptInterface
    public String printerStatus() {
        return get("/v1/printer/status");
    }

    @JavascriptInterface
    public String profileActive() {
        return get("/v1/profile/active");
    }

    @JavascriptInterface
    public String profileList() {
        return get("/v1/profile/list");
    }

    @JavascriptInterface
    public String profileDrift() {
        return get("/v1/profile/drift");
    }

    @JavascriptInterface
    public String registerScanProgress() {
        return get("/v1/register/scan-progress");
    }

    @JavascriptInterface
    public String tickWait() {
        return get("/v1/tick/wait", TIMEOUT_LONG_MS);
    }

    @JavascriptInterface
    public String deliveryJobGet(String jobId) {
        return get("/v1/delivery/job/" + jobId);
    }

    // ── POST ──────────────────────────────────────────────────────────────

    @JavascriptInterface
    public String mediaAutoConnect(String body) {
        return post("/v1/media/auto-connect", body);
    }

    @JavascriptInterface
    public String mediaCheck(String body) {
        return post("/v1/media/check", body);
    }

    @JavascriptInterface
    public String registerScanAuto(String body) {
        return post("/v1/register/scan-auto", body);
    }

    @JavascriptInterface
    public String registerConnectAuto(String body) {
        return post("/v1/register/connect-auto", body);
    }

    @JavascriptInterface
    public String registerValidate(String body) {
        return post("/v1/register/validate", body);
    }

    @JavascriptInterface
    public String deliveryA(String body) {
        return post("/v1/delivery/A", body);
    }

    @JavascriptInterface
    public String deliveryAlignA(String body) {
        return post("/v1/delivery/alignA", body);
    }

    @JavascriptInterface
    public String deliveryB(String body) {
        return post("/v1/delivery/B", body);
    }

    @JavascriptInterface
    public String deliveryC(String body) {
        return post("/v1/delivery/C", body);
    }

    @JavascriptInterface
    public String deliveryOneshot(String body) {
        return post("/v1/delivery/oneshot/start", body);
    }

    @JavascriptInterface
    public String deliveryJobContinue(String body) {
        return post("/v1/delivery/job/continue", body);
    }

    @JavascriptInterface
    public String deliveryJobTerminate(String body) {
        return post("/v1/delivery/job/terminate", body);
    }

    @JavascriptInterface
    public String ticketReprint(String body) {
        return post("/v1/ticket/reprint", body);
    }

    @JavascriptInterface
    public String btActivate(String body) {
        return post("/v1/bt/activate", body);
    }

    @JavascriptInterface
    public String btDisconnect(String body) {
        return post("/v1/bt/disconnect", body);
    }

    @JavascriptInterface
    public String btReset(String body) {
        return post("/v1/bt/reset", body);
    }

    @JavascriptInterface
    public String btSignalScan(String body) {
        return post("/v1/bt/signal/scan", body);
    }

    @JavascriptInterface
    public String usbOpenPing(String body) {
        return post("/v1/usb/open-ping", body);
    }

    @JavascriptInterface
    public String lcpConnect(String body) {
        return post("/v1/lcp/connect", body);
    }

    @JavascriptInterface
    public String profileSave(String body) {
        return post("/v1/profile/save", body);
    }

    @JavascriptInterface
    public String profileActivate(String body) {
        return post("/v1/profile/activate", body);
    }

    @JavascriptInterface
    public String profileValidate(String body) {
        return post("/v1/profile/validate", body);
    }

    @JavascriptInterface
    public String profileAcknowledge(String body) {
        return post("/v1/profile/acknowledge", body);
    }

    @JavascriptInterface
    public String profileDelete(String body) {
        return post("/v1/profile/delete", body);
    }

    @JavascriptInterface
    public String dbDump(String body) {
        return post("/v1/db/dump", body);
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private String get(String path) {
        return get(path, TIMEOUT_MS);
    }

    private String get(String path, int timeoutMs) {
        try {
            URL url = new URL(BASE + path);
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            String body = readStream(code < 400
                ? conn.getInputStream()
                : conn.getErrorStream());
            conn.disconnect();
            return body;
        } catch (Exception e) {
            return error(e);
        }
    }

    private String post(String path, String jsonBody) {
        try {
            URL url = new URL(BASE + path);
            HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            if (jsonBody != null && !jsonBody.isEmpty()) {
                conn.setDoOutput(true);
                byte[] bytes = jsonBody.getBytes("UTF-8");
                conn.setRequestProperty("Content-Length", String.valueOf(bytes.length));
                OutputStream os = conn.getOutputStream();
                os.write(bytes);
                os.flush();
                os.close();
            }
            int code = conn.getResponseCode();
            String body = readStream(code < 400
                ? conn.getInputStream()
                : conn.getErrorStream());
            conn.disconnect();
            return body;
        } catch (Exception e) {
            return error(e);
        }
    }

    private String readStream(java.io.InputStream is) throws Exception {
        if (is == null) return "{}";
        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }

    private String error(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        return "{\"code\":0,\"ok\":false,\"err\":\"BRIDGE_ERROR\","
             + "\"msg\":" + jsonQuote(msg) + "}";
    }

    private String jsonQuote(String s) {
        if (s == null) return "null";
        return "\"" + s.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "\\r") + "\"";
    }
}
