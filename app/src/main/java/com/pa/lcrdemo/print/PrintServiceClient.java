package com.pa.lcrdemo.print;

import android.util.Log;

import com.pa.lcr.lcp.log.LogBus;
import com.pa.lcr.lcp.storage.ImprimanteCiterneStore;
import com.pa.lcrdemo.config.LcrConfig;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * PrintServiceClient — envoie une association imprimante↔citerne au service d'impression local
 * (POST http://127.0.0.1:9182/truck-printer, en-tête X-App-Token). 29 oct 2026.
 *
 * Appel réseau local, bloquant : à lancer hors du thread UI. Le service lui-même apparie la
 * imprimante Bluetooth (createBond) si elle n'est pas encore appairée, à partir de la MAC.
 *
 * Résultat : OK, LOCAL_DIRTY (le service a une ligne modifiée à la main pas encore confirmée —
 * demander à l'usager puis renvoyer avec ack=true), ou ERREUR (service absent, jeton refusé, 4xx…).
 */
public final class PrintServiceClient {

    private static final String TAG = "PrintServiceClient";

    private PrintServiceClient() {}

    public static final class Result {
        public final boolean ok;
        public final boolean localDirty;
        public final String  message;
        Result(boolean ok, boolean localDirty, String message) {
            this.ok = ok; this.localDirty = localDirty; this.message = message;
        }
    }

    public static Result push(ImprimanteCiterneStore.Row r, boolean ack) {
        try {
            JSONObject body = new JSONObject();
            body.put("truckId",     r.truckId);
            body.put("company",     r.company == null ? "" : r.company);
            body.put("driverType",  r.driverType);
            body.put("target",      r.btMac == null ? "" : r.btMac);
            body.put("printerName", r.printerName == null ? "" : r.printerName);
            body.put("format",      r.format);
            if (ack) body.put("ack", true);

            HttpURLConnection conn = (HttpURLConnection) new URL(LcrConfig.PRINT_SERVICE_URL).openConnection();
            try {
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(8000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("X-App-Token", LcrConfig.PRINT_SERVICE_TOKEN);
                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) { os.write(payload); }

                int code = conn.getResponseCode();
                InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
                String resp = is == null ? "" : readAll(is);
                String err = "", msg = "";
                try {
                    JSONObject j = new JSONObject(resp);
                    err = j.optString("err", "");
                    msg = j.optString("msg", "");
                } catch (Exception ignored) {}

                if (code == 200) {
                    LogBus.api(0, "[PRINT-SYNC] service OK truck=" + r.truckId + " format=" + r.format
                        + " driver=" + r.driverType + " cible=" + r.btMac + (ack ? " (ack)" : ""));
                    return new Result(true, false, "Envoyé au service d'impression");
                }
                if (code == 409 && "LOCAL_DIRTY".equals(err)) {
                    LogBus.api(0, "[PRINT-SYNC] service 409 LOCAL_DIRTY truck=" + r.truckId + " format=" + r.format);
                    return new Result(false, true,
                        "Le service d'impression a une version modifiée à la main de cette imprimante");
                }
                String m = "HTTP " + code + (err.isEmpty() ? "" : " " + err) + (msg.isEmpty() ? "" : " — " + msg);
                LogBus.api(0, "[PRINT-SYNC] service ERR truck=" + r.truckId + " : " + m);
                return new Result(false, false, m);
            } finally {
                conn.disconnect();
            }
        } catch (java.net.ConnectException e) {
            Log.w(TAG, "service injoignable: " + e.getMessage());
            LogBus.api(0, "[PRINT-SYNC] service injoignable (installé/démarré ?) : " + e.getMessage());
            return new Result(false, false, "Service d'impression injoignable (127.0.0.1:9182)");
        } catch (Exception e) {
            Log.w(TAG, "push ERR: " + e.getMessage());
            LogBus.api(0, "[PRINT-SYNC] ERR " + e.getClass().getSimpleName() + " : " + e.getMessage());
            return new Result(false, false, "Erreur : " + e.getMessage());
        }
    }

    private static String readAll(InputStream is) throws Exception {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[2048]; int n;
        while ((n = is.read(b)) > 0) bo.write(b, 0, n);
        return new String(bo.toByteArray(), StandardCharsets.UTF_8);
    }
}
