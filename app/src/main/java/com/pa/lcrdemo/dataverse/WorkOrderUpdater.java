package com.pa.lcrdemo.dataverse;

import android.util.Log;
import org.json.JSONObject;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * WorkOrderUpdater — PATCH msdyn_workordersummary via Dataverse API.
 *
 * Chemin : app/src/main/java/com/pa/lcrdemo/dataverse/WorkOrderUpdater.java
 */
public class WorkOrderUpdater {

    private static final String TAG     = "WorkOrderUpdater";
    private static final String ORG_URL = "https://dev-filgo-sonic.crm3.dynamics.com";

    /**
     * Met à jour msdyn_workordersummary avec le JSON résultat de livraison.
     * Appeler depuis un thread background (btExec ou WorkManager).
     */
    public static void patchSummary(String accessToken,
                                     String workOrderGuid,
                                     String net,
                                     String gross,
                                     String ticket,
                                     String woNum,
                                     String deliveryUid) throws Exception {

        String guid = workOrderGuid.replace("{", "").replace("}", "").trim();

        // ✅ JSON minimal MVP
        JSONObject summary = new JSONObject();
        summary.put("delivery_uid", deliveryUid != null ? deliveryUid : "");
        summary.put("wonum",        woNum       != null ? woNum       : "");
        summary.put("net_l",        net         != null ? Double.parseDouble(net)   : 0);
        summary.put("gross_l",      gross       != null ? Double.parseDouble(gross) : 0);
        summary.put("ticket",       ticket      != null ? ticket      : "");
        summary.put("status",       "DONE");
        summary.put("source",       "LCR");
        summary.put("ts",           new java.util.Date().toString());

        JSONObject body = new JSONObject();
        body.put("msdyn_workordersummary", summary.toString());

        String urlStr = ORG_URL + "/api/data/v9.2/msdyn_workorders(" + guid + ")";
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        try {
            conn.setRequestMethod("PATCH");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestProperty("Authorization",    "Bearer " + accessToken);
            conn.setRequestProperty("Content-Type",     "application/json; charset=utf-8");
            conn.setRequestProperty("Accept",           "application/json");
            conn.setRequestProperty("OData-MaxVersion", "4.0");
            conn.setRequestProperty("OData-Version",    "4.0");

            byte[] bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));

            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
            }

            int code = conn.getResponseCode();
            if (code == 204 || code == 200) {
                Log.i(TAG, "PATCH OK " + code + " — wonum=" + woNum
                    + " net=" + net + " gross=" + gross + " ticket=" + ticket);
            } else {
                String err = "";
                try {
                    java.io.InputStream es = conn.getErrorStream();
                    if (es != null) {
                        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                        byte[] chunk = new byte[4096];
                        int n;
                        while ((n = es.read(chunk)) != -1) buf.write(chunk, 0, n);
                        err = buf.toString(StandardCharsets.UTF_8.name());
                    }
                } catch (Exception ignored) {}
                throw new RuntimeException("PATCH HTTP " + code + ": " + err.substring(0, Math.min(200, err.length())));
            }
        } finally {
            conn.disconnect();
        }
    }
}