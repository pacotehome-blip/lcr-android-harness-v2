package com.pa.lcrdemo;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.net.Uri;

import androidx.fragment.app.Fragment;

import com.pa.lcr.lcp.MultiRegisterApiFacadeImpl;
import com.pa.lcr.lcp.storage.DeliveryLogStore;
import com.pa.lcr.lcp.transport.MediaTransportManager;
import com.pa.lcr.lcp.transport.TransportIo;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

/**
 * DeepLinkHandler — gestion complète du flux deep link Field Service ↔ APK.
 *
 * Responsabilités :
 *  - handleDeepLink()          : parse et route le deep link entrant
 *  - connectBtByMacAndOpenTab(): connexion BT + oneshot/start
 *  - pollJobUntilDone()        : poll état livraison → DONE
 *  - onDeliveryEnded()         : fin de livraison → retournerFieldService
 *  - retournerFieldService()   : construit l'URL retour et lance Field Service
 *
 * Logging dans DeliveryLogStore :
 *  - upsertSummaryAsync → openAttemptAsync → addEventAsync → closeAttemptAsync
 */
public class DeepLinkHandler {

    private static final String TAG = "LCRDEMO_DEEPLINK";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // URL retour Field Service
    private static final String FS_FORM_URL =
        "https://dev-filgo-sonic.crm3.dynamics.com/WebResources/filgo_lcr_form";

    private final MainActivity activity;
    private final DeliveryLogStore deliveryStore;
    private final ExecutorService btExec;

    public DeepLinkHandler(MainActivity activity,
                           DeliveryLogStore deliveryStore,
                           ExecutorService btExec) {
        this.activity      = activity;
        this.deliveryStore = deliveryStore;
        this.btExec        = btExec;
    }

    // =========================================================
    // Point d'entrée principal
    // =========================================================

    public void handleDeepLink(Intent intent) {
        if (intent == null) return;
        Uri data = intent.getData();
        if (data == null) return;
        if (!"lcrdemo".equals(data.getScheme())) return;

        String host = data.getHost();
        android.util.Log.i(TAG, "Deep link reçu: " + data.toString());

        if ("ping".equals(host)) {
            android.util.Log.i(TAG, "Ping reçu — réponse OK");
            activity.toast("✅ LCR Deep Link OK — ping reçu");
            retournerFieldService("ping", "", "ok", null);
            return;
        }

        if ("livraison".equals(host)) {
            String woNum      = data.getQueryParameter("wonum");
            String woIdGuid   = data.getQueryParameter("woid") != null
                                ? data.getQueryParameter("woid") : "";
            String btMac      = data.getQueryParameter("btmac");
            String serialId   = data.getQueryParameter("serialid");
            String produit    = data.getQueryParameter("produit");
            String presetStr  = data.getQueryParameter("preset");
            String lcrnodeStr = data.getQueryParameter("lcrnode");

            Integer lcrnode = null;
            try { if (lcrnodeStr != null) lcrnode = Integer.parseInt(lcrnodeStr); }
            catch (Exception ignored) {}

            android.util.Log.i(TAG,
                "Livraison — WO=" + woNum + " BT=" + btMac +
                " serial=" + serialId + " node=" + lcrnode +
                " produit=" + produit + " preset=" + presetStr);

            // ✅ Log événement départ dans DeliveryLogStore
            final String fWoNum    = woNum;
            final String fSerialId = serialId != null ? serialId : "";
            logDeliveryStart(fSerialId, fWoNum, btMac, lcrnode, produit, presetStr);

            activity.toast("📦 Livraison — " + woNum);
            int finalNode = (lcrnode != null ? lcrnode : 250);
            connectBtByMacAndOpenTab(btMac, finalNode, serialId, woNum, woIdGuid, produit, presetStr);
        }
    }

    // =========================================================
    // Connexion BT + oneshot/start
    // =========================================================

    private void connectBtByMacAndOpenTab(String btMac, int node, String serialId,
                                           String woNum, String woIdGuid,
                                           String produit, String presetStr) {
        if (btMac == null || btMac.trim().isEmpty()) {
            activity.toast("Deep Link: BT MAC manquant");
            logError(serialId, woNum, "BT_CONNECT", "BT MAC manquant");
            return;
        }
        final String mac = btMac.toUpperCase().trim();

        btExec.execute(() -> {
            try {
                // 1) Connexion BT
                android.bluetooth.BluetoothAdapter btAdapter = activity.getBtAdapter();
                BluetoothDevice dev = btAdapter.getRemoteDevice(mac);
                activity.btDisconnect();
                try { if (btAdapter != null) btAdapter.cancelDiscovery(); }
                catch (Exception ignored) {}

                BluetoothSocket s;
                try {
                    s = dev.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
                } catch (Exception e) {
                    s = dev.createRfcommSocketToServiceRecord(SPP_UUID);
                }
                s.connect();

                InputStream  btIn  = s.getInputStream();
                OutputStream btOut = s.getOutputStream();
                activity.onBtConnectedFromDeepLink(s, btIn, btOut, mac);

                android.util.Log.i(TAG, "BT connecté: " + mac);
                logEvent(serialId, woNum, DeliveryLogStore.LEVEL_INFO,
                    "BT_CONNECT", "BT connecté: " + mac, null);

                String transportKey = MediaTransportManager.btKey(mac);

                // 2) Ouvrir tab UI
                final String fProduit      = produit;
                final String fPreset       = presetStr;
                final String fWoNum        = woNum;
                final String fSerialId     = serialId != null ? serialId : "";
                final String fTransportKey = transportKey;

                activity.runOnUiThread(() -> {
                    try {
                        activity.onConfigureMediaActivated(fTransportKey, "DEEPLINK");
                        activity.upsertRegisterTabFromScan(fTransportKey, node, 255, fSerialId, true);
                        activity.getUiHandler().postDelayed(() -> {
                            try {
                                String   mediaShort = activity.mediaShortFromTransportKey(fTransportKey);
                                String   tabKey     = activity.tabKeyOf(mediaShort, node, fSerialId);
                                Fragment f          = activity.getSupportFragmentManager()
                                                              .findFragmentByTag("regtab_" + tabKey);
                                if (f instanceof RegisterTabFragment) {
                                    ((RegisterTabFragment) f).prefillFromDeepLink(
                                        fWoNum, fProduit, fPreset);
                                }
                            } catch (Exception ignored) {}
                        }, 800);
                        activity.refreshAllTabsMediaStatus();
                        activity.showPage(0);
                        activity.updateBtStatusText("BT : CONNECTED — " + mac + " (FS)");
                    } catch (Exception ignored) {}
                });

                // 3) Attendre que le média soit READY
                int    product = 1;
                double preset  = 0.0;
                try { product = Integer.parseInt(produit);     } catch (Exception ignored) {}
                try { preset  = Double.parseDouble(presetStr); } catch (Exception ignored) {}

                final int    fProduct = product;
                final double fPresetD = preset;

                boolean ready = false;
                for (int i = 0; i < 10; i++) {
                    try { Thread.sleep(500); } catch (Exception ignored) {}
                    try {
                        MediaTransportManager mtm = activity.getMediaTransportManager();
                        if (mtm != null) {
                            TransportIo io = mtm.getByKey(transportKey);
                            if (io != null && io.isOpen()) { ready = true; break; }
                        }
                    } catch (Exception ignored) {}
                }

                if (ready) {
                    try {
                        MultiRegisterApiFacadeImpl facade =
                            new MultiRegisterApiFacadeImpl(activity);
                        com.pa.lcr.lcp.ApiResult r = facade.api_deliveryOneShotStart(
                            node, 255, woNum, fProduct, fPresetD, null, "bt", mac);

                        android.util.Log.i(TAG,
                            "oneshot/start: code=" + r.code + " msg=" + r.msg);

                        if (r.code == 1) {
                            // ✅ Succès — récupérer jobId et démarrer le poll
                            String jobId = (r.data != null)
                                ? r.data.optString("jobId", null) : null;

                            logEvent(fSerialId, woNum, DeliveryLogStore.LEVEL_INFO,
                                "ONESHOT_START", "ARMED jobId=" + jobId, null);

                            if (jobId != null && !jobId.isEmpty()) {
                                android.util.Log.i(TAG, "Poll démarré — jobId=" + jobId);
                                activity.runOnUiThread(() ->
                                    activity.toast("📦 Livraison démarrée — " + woNum));
                                pollJobUntilDone(jobId, node, woNum, woIdGuid, fSerialId);
                            } else {
                                android.util.Log.w(TAG, "oneshot/start: jobId absent");
                                activity.runOnUiThread(() ->
                                    activity.toast("📦 Livraison démarrée (sans jobId) — " + woNum));
                            }
                        } else {
                            // ✅ Erreur oneshot — livraison précédente en cours ?
                            android.util.Log.w(TAG, "oneshot/start code=0: " + r.msg);
                            logEvent(fSerialId, woNum, DeliveryLogStore.LEVEL_WARN,
                                "ONESHOT_ERROR", r.msg,
                                r.data != null ? r.data.toString() : null);

                            // Retourner vers FS avec status=erreur
                            retournerFieldService(woNum, woIdGuid, "erreur_oneshot",
                                buildErrorJson("ONESHOT_FAILED", r.msg));
                        }

                    } catch (Exception e) {
                        android.util.Log.e(TAG, "oneshot/start ERR: " + e.getMessage());
                        logError(fSerialId, woNum, "ONESHOT_EXCEPTION", e.getMessage());
                        retournerFieldService(woNum, woIdGuid, "erreur",
                            buildErrorJson("ONESHOT_EXCEPTION", e.getMessage()));
                    }
                } else {
                    android.util.Log.w(TAG, "Média non prêt après 5s");
                    activity.runOnUiThread(() -> activity.toast("BT non prêt — réessayez"));
                    logError(fSerialId, woNum, "MEDIA_NOT_READY", "Média non prêt après 5s");
                    retournerFieldService(woNum, woIdGuid, "erreur_media",
                        buildErrorJson("MEDIA_NOT_READY", "Média non prêt après 5s"));
                }

            } catch (Exception e) {
                android.util.Log.e(TAG, "BT connect ERR: " + e.getMessage());
                activity.runOnUiThread(() -> activity.toast("BT ERR: " + e.getMessage()));
                logError(serialId != null ? serialId : "", woNum,
                    "BT_CONNECT_ERROR", e.getMessage());
                retournerFieldService(woNum, woIdGuid, "erreur_bt",
                    buildErrorJson("BT_CONNECT_ERROR", e.getMessage()));
            }
        });
    }

    // =========================================================
    // Poll état livraison
    // =========================================================

    private void pollJobUntilDone(String jobId, int node, String woNum,
                                   String woIdGuid, String serialId) {
        btExec.execute(() -> {
            try {
                // ✅ job/continue — sortir de ARMED
                try {
                    MultiRegisterApiFacadeImpl facadeCont =
                        new MultiRegisterApiFacadeImpl(activity);
                    com.pa.lcr.lcp.ApiResult rc =
                        facadeCont.api_deliveryContinue(jobId, node);
                    android.util.Log.i(TAG,
                        "job/continue: code=" + (rc != null ? rc.code : "null")
                        + " msg=" + (rc != null ? rc.msg : "null"));
                    logEvent(serialId, woNum, DeliveryLogStore.LEVEL_INFO,
                        "JOB_CONTINUE",
                        "code=" + (rc != null ? rc.code : "null") +
                        " msg=" + (rc != null ? rc.msg : "null"), null);
                } catch (Exception e) {
                    android.util.Log.e(TAG, "job/continue ERR: " + e.getMessage());
                    logError(serialId, woNum, "JOB_CONTINUE_ERROR", e.getMessage());
                }

                boolean hasSeenFlowing = false;
                boolean terminateSent  = false;
                String  lastState      = "";

                // Poll max 10 minutes
                for (int i = 0; i < 600; i++) {
                    try { Thread.sleep(1000); } catch (Exception ignored) {}

                    try {
                        MultiRegisterApiFacadeImpl facade =
                            new MultiRegisterApiFacadeImpl(activity);
                        com.pa.lcr.lcp.ApiResult r = facade.api_deliveryJobGet(jobId);
                        if (r == null) continue;

                        String state = null;
                        if (r.data != null)
                            state = r.data.optString("state", null);

                        android.util.Log.i(TAG, "pollJob: state=" + state);

                        // Logger seulement les changements d'état
                        if (state != null && !state.equals(lastState)) {
                            logEvent(serialId, woNum, DeliveryLogStore.LEVEL_INFO,
                                "STATE_CHANGE", "state=" + state, null);
                            lastState = state;
                        }

                        if ("RUNNING_FLOWING".equals(state) || "RUNNING_PAUSED".equals(state)) {
                            hasSeenFlowing = true;
                        }

                        // ✅ DONE ou TERMINATED
                        if ("DONE".equals(state) || "TERMINATED".equals(state)) {
                            String extraJson = (r.data != null) ? r.data.toString() : "{}";
                            android.util.Log.i(TAG, "Livraison DONE — " + extraJson);
                            logDeliveryEnd(serialId, woNum, jobId, "DONE", extraJson, null);
                            onDeliveryEnded(woNum, woIdGuid, extraJson);
                            return;
                        }

                        // ✅ CONNECTED après terminate = fin
                        if ("CONNECTED".equals(state) && terminateSent) {
                            String extraJson = (r.data != null) ? r.data.toString() : "{}";
                            android.util.Log.i(TAG,
                                "Livraison terminée (CONNECTED post-terminate) — " + extraJson);
                            logDeliveryEnd(serialId, woNum, jobId, "DONE", extraJson, null);
                            onDeliveryEnded(woNum, woIdGuid, extraJson);
                            return;
                        }

                        // ✅ RUNNING_PAUSED → terminate
                        if ("RUNNING_PAUSED".equals(state) && hasSeenFlowing && !terminateSent) {
                            android.util.Log.i(TAG, "RUNNING_PAUSED — envoi job/terminate");
                            logEvent(serialId, woNum, DeliveryLogStore.LEVEL_INFO,
                                "JOB_TERMINATE", "RUNNING_PAUSED détecté", null);
                            try {
                                MultiRegisterApiFacadeImpl facadeTerm =
                                    new MultiRegisterApiFacadeImpl(activity);
                                com.pa.lcr.lcp.ApiResult rt =
                                    facadeTerm.api_deliveryTerminate(jobId, node);
                                android.util.Log.i(TAG,
                                    "job/terminate: code=" + (rt != null ? rt.code : "null")
                                    + " msg=" + (rt != null ? rt.msg : "null"));
                                terminateSent = true;
                            } catch (Exception e) {
                                android.util.Log.e(TAG, "job/terminate ERR: " + e.getMessage());
                                logError(serialId, woNum, "JOB_TERMINATE_ERROR", e.getMessage());
                            }
                        }

                    } catch (Exception ignored) {}
                }

                // Timeout
                android.util.Log.w(TAG, "pollJob: timeout 10min sans DONE");
                logError(serialId, woNum, "POLL_TIMEOUT", "Timeout 10 minutes sans DONE");
                retournerFieldService(woNum, woIdGuid, "erreur_timeout",
                    buildErrorJson("POLL_TIMEOUT", "Timeout 10 minutes"));

            } catch (Exception e) {
                android.util.Log.e(TAG, "pollJob ERR: " + e.getMessage());
                logError(serialId, woNum, "POLL_EXCEPTION", e.getMessage());
                retournerFieldService(woNum, woIdGuid, "erreur",
                    buildErrorJson("POLL_EXCEPTION", e.getMessage()));
            }
        });
    }

    // =========================================================
    // Fin de livraison
    // =========================================================

    public void onDeliveryEnded(String woNum, String extraJson) {
        onDeliveryEnded(woNum, "", extraJson);
    }

    public void onDeliveryEnded(String woNum, String woIdGuid, String extraJson) {
        android.util.Log.i(TAG,
            "Livraison terminée — WO=" + woNum + " extra=" + extraJson);
        retournerFieldService(woNum, woIdGuid, "termine", extraJson);
    }

    // =========================================================
    // Dernier résultat — accessible via GET /v1/delivery/last-result
    // =========================================================

    // Variable statique — partagée entre DeepLinkHandler et ApiServer
    public static volatile String lastResultJson = null;
    public static volatile String lastResultWoNum = null;
    public static volatile String lastResultWoGuid = null;
    public static volatile long   lastResultTs = 0;

    // =========================================================
    // Retour Field Service — Stratégie A + B
    // =========================================================

    private void retournerFieldService(String woNum, String woIdGuid,
                                        String status, String extraJson) {
        try {
            String net    = "";
            String gross  = "";
            String ticket = "";
            try {
                JSONObject d = new JSONObject(extraJson != null ? extraJson : "{}");
                JSONObject result = d.optJSONObject("result");
                if (result != null) {
                    net    = String.valueOf(result.optDouble("fs_net_l",   0));
                    gross  = String.valueOf(result.optDouble("fs_gross_l", 0));
                    ticket = result.optString("ticket_no", "");
                } else {
                    net   = String.valueOf(d.optDouble("net",   0));
                    gross = String.valueOf(d.optDouble("gross", 0));
                }
            } catch (Exception ignored) {}

            // GUID du WO
            String woGuid = (woIdGuid != null && !woIdGuid.isEmpty()) ? woIdGuid : "";
            woGuid = woGuid.replace("{", "").replace("}", "");

            // ✅ Stratégie B — sauvegarder le résultat pour GET /v1/delivery/last-result
            try {
                JSONObject lastResult = new JSONObject();
                lastResult.put("wonum",   woNum   != null ? woNum   : "");
                lastResult.put("woid",    woGuid);
                lastResult.put("net",     net);
                lastResult.put("gross",   gross);
                lastResult.put("ticket",  ticket);
                lastResult.put("status",  status  != null ? status  : "ok");
                lastResult.put("ts",      System.currentTimeMillis());
                if (extraJson != null) {
                    try {
                        lastResult.put("payload", new JSONObject(extraJson));
                    } catch (Exception ignored) {}
                }
                lastResultJson   = lastResult.toString();
                lastResultWoNum  = woNum;
                lastResultWoGuid = woGuid;
                lastResultTs     = System.currentTimeMillis();
                android.util.Log.i(TAG, "last-result sauvegardé: wonum=" + woNum
                    + " net=" + net + " gross=" + gross + " ticket=" + ticket);
            } catch (Exception ignored) {}

            // ✅ Stratégie A — finish() pour revenir à Field Service (même stack)
            // Field Service était en dessous dans le même task stack
            // Sa session OAuth reste vivante — onLoadForm se déclenche
            android.util.Log.i(TAG, "Retour FS — finish() stratégie A");
            activity.runOnUiThread(() -> {
                try {
                    activity.finish();
                } catch (Exception e) {
                    android.util.Log.e(TAG, "finish() failed: " + e.getMessage());
                    activity.moveTaskToBack(true);
                }
            });

        } catch (Exception e) {
            android.util.Log.e(TAG, "Retour FS failed: " + e.getMessage());
            activity.moveTaskToBack(true);
        }
    }

    // =========================================================
    // Logging helpers
    // =========================================================

    private void logDeliveryStart(String serialId, String woNum, String btMac,
                                   Integer node, String produit, String preset) {
        if (deliveryStore == null || serialId == null || serialId.isEmpty()) return;
        try {
            JSONObject d = new JSONObject();
            d.put("woNum",   woNum != null ? woNum : "");
            d.put("btMac",   btMac != null ? btMac : "");
            d.put("node",    node  != null ? node  : 0);
            d.put("produit", produit != null ? produit : "");
            d.put("preset",  preset  != null ? preset  : "");
            d.put("source",  "DEEPLINK_FS");
            d.put("ts",      System.currentTimeMillis());

            deliveryStore.upsertSummaryAsync(
                serialId, woNum != null ? woNum : "DEEPLINK",
                null, "DEEPLINK_START", DeliveryLogStore.SOURCE_API,
                null, null, null);

            deliveryStore.openAttemptAsync(
                serialId, woNum != null ? woNum : "DEEPLINK",
                DeliveryLogStore.SOURCE_API, null, attemptId -> {
                    deliveryStore.addEventAsync(attemptId,
                        DeliveryLogStore.LEVEL_INFO,
                        "DEEPLINK_START",
                        "WO=" + woNum + " BT=" + btMac + " node=" + node,
                        d.toString());
                });
        } catch (Exception ignored) {}
    }

    private void logDeliveryEnd(String serialId, String woNum, String jobId,
                                 String outcome, String resultJson, String errorJson) {
        if (deliveryStore == null || serialId == null || serialId.isEmpty()) return;
        deliveryStore.upsertSummaryAsync(
            serialId, woNum != null ? woNum : "DEEPLINK",
            null, outcome, DeliveryLogStore.SOURCE_API,
            jobId, resultJson, errorJson);
    }

    private void logEvent(String serialId, String woNum, String level,
                           String type, String message, String dataJson) {
        if (deliveryStore == null || serialId == null || serialId.isEmpty()) return;
        try {
            deliveryStore.openAttemptAsync(
                serialId, woNum != null ? woNum : "DEEPLINK",
                DeliveryLogStore.SOURCE_API, null, attemptId ->
                    deliveryStore.addEventAsync(
                        attemptId, level, type, message, dataJson));
        } catch (Exception ignored) {}
    }

    private void logError(String serialId, String woNum, String code, String message) {
        if (deliveryStore == null) return;
        try {
            String errorJson = new JSONObject()
                .put("code", code)
                .put("message", message != null ? message : "")
                .put("ts", System.currentTimeMillis())
                .toString();
            logDeliveryEnd(serialId, woNum, null, "ERROR", null, errorJson);
            logEvent(serialId, woNum, DeliveryLogStore.LEVEL_ERROR, code, message, errorJson);
        } catch (Exception ignored) {}
    }

    private static String buildErrorJson(String code, String message) {
        try {
            return new JSONObject()
                .put("error_code", code)
                .put("error_message", message != null ? message : "")
                .put("ts", System.currentTimeMillis())
                .toString();
        } catch (Exception e) {
            return "{\"error_code\":\"" + code + "\"}";
        }
    }
}