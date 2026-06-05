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
import com.pa.lcrdemo.auth.MsalTokenProvider;
import com.pa.lcrdemo.dataverse.WorkOrderUpdater;
import com.pa.lcrdemo.dataverse.DeliveryResultQueueDb;

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
                // =========================================================
                // 1) Vérifier si BT déjà connecté au bon MAC — réutiliser
                // =========================================================
                String transportKey = MediaTransportManager.btKey(mac);
                MediaTransportManager mtm = activity.getMediaTransportManager();
                boolean btDejaConnecte = false;

                if (mtm != null) {
                    TransportIo existing = mtm.getByKey(transportKey);
                    if (existing != null && existing.isOpen()) {
                        btDejaConnecte = true;
                        android.util.Log.i(TAG, "BT déjà connecté: " + mac + " — réutilisation");
                        logEvent(serialId, woNum, DeliveryLogStore.LEVEL_INFO,
                            "BT_REUSE", "BT déjà connecté: " + mac, null);
                    }
                }

                if (!btDejaConnecte) {
                    // ✅ Nouveau BT — déconnecter proprement si différent
                    android.bluetooth.BluetoothAdapter btAdapter = activity.getBtAdapter();

                    // Déconnecter seulement si le MAC actif est différent
                    String lastMac = activity.getLastBtMac();
                    if (lastMac != null && !lastMac.equalsIgnoreCase(mac)) {
                        android.util.Log.i(TAG, "BT différent — déconnexion: " + lastMac);
                        activity.btDisconnect();
                        try { Thread.sleep(500); } catch (Exception ignored) {}
                    }

                    try { if (btAdapter != null) btAdapter.cancelDiscovery(); }
                    catch (Exception ignored) {}

                    BluetoothDevice dev = btAdapter.getRemoteDevice(mac);
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

                    // ✅ Nouveau BT — attendre que probeAndIdentify crée le controller
                    // probeAndIdentify() tourne en background dans onBtConnectedFromDeepLink
                    // On attend max 5s par tranches de 200ms
                    android.util.Log.i(TAG, "Attente controller BT après connexion...");
                    com.pa.lcr.lcp.RegisterSessionManager rsm =
                        com.pa.lcr.lcp.RegisterSessionManager.get(activity);
                    String btKey2 = com.pa.lcr.lcp.transport.MediaTransportManager.btKey(mac);
                    long waitDeadline = System.currentTimeMillis() + 5000;
                    while (System.currentTimeMillis() < waitDeadline) {
                        com.pa.lcr.lcp.DeliveryController dcCheck =
                            rsm.getController(btKey2, node);
                        if (dcCheck != null) {
                            android.util.Log.i(TAG, "Controller BT prêt après "
                                + (5000 - (waitDeadline - System.currentTimeMillis())) + "ms");
                            break;
                        }
                        try { Thread.sleep(200); } catch (Exception ignored) {}
                    }
                }

                // =========================================================
                // 2) Valider le registre — bon serial, bon node
                // =========================================================
                try {
                    MultiRegisterApiFacadeImpl facadeVal =
                        new MultiRegisterApiFacadeImpl(activity);
                    com.pa.lcr.lcp.ApiResult rv = facadeVal.api_registerValidate(
                        woNum, node, null, serialId, null, null, "bt", mac);
                    android.util.Log.i(TAG, "register/validate: code=" + rv.code + " msg=" + rv.msg);

                    if (rv.code != 1) {
                        // ✅ Mauvais registre ou pas prêt — tenter auto-connect
                        android.util.Log.w(TAG, "Registre invalide — tentative auto-connect");
                        MultiRegisterApiFacadeImpl facadeAuto =
                            new MultiRegisterApiFacadeImpl(activity);
                        com.pa.lcr.lcp.ApiResult ra =
                            facadeAuto.api_registerConnectAuto(serialId, node);
                        android.util.Log.i(TAG, "register/connect-auto: code=" + ra.code + " msg=" + ra.msg);

                        if (ra.code != 1) {
                            logError(serialId, woNum, "REGISTER_INVALID",
                                "Registre invalide: " + rv.msg);
                            activity.runOnUiThread(() ->
                                activity.toast("⚠️ Registre invalide — " + rv.msg));
                            retournerFieldService(woNum, woIdGuid, "erreur_registre",
                                buildErrorJson("REGISTER_INVALID", rv.msg));
                            return;
                        }
                    }
                    logEvent(serialId, woNum, DeliveryLogStore.LEVEL_INFO,
                        "REGISTER_OK", "Registre validé node=" + node, null);
                } catch (Exception e) {
                    android.util.Log.w(TAG, "register/validate ERR (ignoré): " + e.getMessage());
                    // Non bloquant — continuer quand même
                }

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
                        MediaTransportManager mtm2 = activity.getMediaTransportManager();
                        if (mtm2 != null) {
                            TransportIo io = mtm2.getByKey(transportKey);
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
                final boolean[] deliveryDone = {false}; // ✅ Flag anti-double

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
                            if (deliveryDone[0]) return;
                            deliveryDone[0] = true;
                            String extraJson = (r.data != null) ? r.data.toString() : "{}";
                            android.util.Log.i(TAG, "Livraison DONE — " + extraJson);
                            logDeliveryEnd(serialId, woNum, jobId, "DONE", extraJson, null);
                            onDeliveryEnded(woNum, woIdGuid, extraJson);
                            return;
                        }

                        // ✅ CONNECTED après terminate = fin propre
                        if ("CONNECTED".equals(state) && terminateSent) {
                            if (deliveryDone[0]) return;
                            deliveryDone[0] = true;
                            String extraJson = (r.data != null) ? r.data.toString() : "{}";
                            android.util.Log.i(TAG,
                                "Livraison terminée (CONNECTED post-terminate) — " + extraJson);
                            logDeliveryEnd(serialId, woNum, jobId, "DONE", extraJson, null);
                            onDeliveryEnded(woNum, woIdGuid, extraJson);
                            return;
                        }

                        // ✅ CONNECTED après FLOWING sans PAUSED = fin directe
                        // (cas 2e livraison successive — le registre termine sans pause)
                        if ("CONNECTED".equals(state) && hasSeenFlowing && !terminateSent) {
                            android.util.Log.i(TAG, "CONNECTED après FLOWING — terminate direct");
                            try {
                                MultiRegisterApiFacadeImpl facadeTerm2 =
                                    new MultiRegisterApiFacadeImpl(activity);
                                com.pa.lcr.lcp.ApiResult rt2 =
                                    facadeTerm2.api_deliveryTerminate(jobId, node);
                                android.util.Log.i(TAG,
                                    "job/terminate (direct): code=" + (rt2 != null ? rt2.code : "null")
                                    + " msg=" + (rt2 != null ? rt2.msg : "null"));
                                terminateSent = true;
                            } catch (Exception e) {
                                android.util.Log.e(TAG, "job/terminate direct ERR: " + e.getMessage());
                            }
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
                // ✅ Écrire aussi dans LcrHttpService pour le serveur HTTP 8766
                com.pa.lcrdemo.LcrHttpService.lastResultJson = lastResult.toString();
                android.util.Log.i(TAG, "last-result sauvegardé: wonum=" + woNum
                    + " net=" + net + " gross=" + gross + " ticket=" + ticket);

                // ✅ PATCH Dataverse directement via cookies WebView
                final String fNetP    = net;
                final String fGrossP  = gross;
                final String fTicketP = ticket;
                final String fGuidP   = woGuid;
                final String fWoNumP  = woNum;
                final String fStatusP = status;
                patchDataverse(fGuidP, fWoNumP, fNetP, fGrossP, fTicketP, fStatusP);

            } catch (Exception ignored) {}

            // ✅ Stratégie B — écrire dans localStorage du WebView Field Service
            // avant finish() pour que onLoadForm puisse lire les données
            final String fNet    = net;
            final String fGross  = gross;
            final String fTicket = ticket;
            final String fWoGuid = woGuid;
            final String fWoNum2 = woNum;
            final String fStatus = status;

            activity.runOnUiThread(() -> {
                try {
                    // ✅ Construire le JSON résultat
                    JSONObject lsData = new JSONObject();
                    try {
                        lsData.put("wonum",  fWoNum2 != null ? fWoNum2 : "");
                        lsData.put("woid",   fWoGuid);
                        lsData.put("net",    fNet);
                        lsData.put("gross",  fGross);
                        lsData.put("ticket", fTicket);
                        lsData.put("status", fStatus != null ? fStatus : "ok");
                        lsData.put("ts",     System.currentTimeMillis());
                    } catch (Exception ignored) {}

                    // ✅ Injecter dans localStorage via le WebView de MainActivity
                    // Le même domaine crm3.dynamics.com est partagé avec Field Service
                    String js = "try { localStorage.setItem('lcr_last_result', '"
                        + lsData.toString().replace("'", "\'") + "'); } catch(e) {}";

                    android.webkit.WebView wv = activity.getFieldServiceWebView();
                    if (wv != null) {
                        wv.evaluateJavascript(js, null);
                        android.util.Log.i(TAG, "localStorage écrit: " + lsData.toString());
                    } else {
                        android.util.Log.w(TAG, "WebView non disponible — localStorage ignoré");
                    }

                } catch (Exception e) {
                    android.util.Log.e(TAG, "localStorage ERR: " + e.getMessage());
                }

                // ✅ Ouvrir filgo_lcr_form avec les données
                // WebResource locale — fonctionne 100% offline
                // Xrm.WebApi.updateRecord() écrit dans SQLite local FS
                String urlRetour = "https://dev-filgo-sonic.crm3.dynamics.com/WebResources/filgo_lcr_form"
                    + "?action=lcr_retour"
                    + "&wonum="  + android.net.Uri.encode(fWoNum2  != null ? fWoNum2  : "")
                    + "&woid="   + android.net.Uri.encode(fWoGuid  != null ? fWoGuid  : "")
                    + "&net="    + android.net.Uri.encode(fNet     != null ? fNet     : "")
                    + "&gross="  + android.net.Uri.encode(fGross   != null ? fGross   : "")
                    + "&ticket=" + android.net.Uri.encode(fTicket  != null ? fTicket  : "")
                    + "&status=" + android.net.Uri.encode(fStatus  != null ? fStatus  : "ok")
                    + "&ts="     + System.currentTimeMillis();

                android.util.Log.i(TAG, "Retour form — " + urlRetour);

                try {
                    android.content.Intent retour = new android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(urlRetour));
                    retour.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    activity.startActivity(retour);
                } catch (Exception e) {
                    android.util.Log.e(TAG, "startActivity retour FAIL: " + e.getMessage());
                    activity.finish();
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

    // =========================================================
    // ✅ PATCH Dataverse via cookies WebView Field Service
    // =========================================================

    private void patchDataverse(String woGuid, String woNum,
                                 String net, String gross, String ticket,
                                 String status) {
        if (woGuid == null || woGuid.isEmpty()) {
            android.util.Log.w(TAG, "patchDataverse: GUID vide — ignoré");
            return;
        }

        // ✅ Extraire delivery_uid depuis lastResultJson
        String deliveryUid = "";
        try {
            if (lastResultJson != null) {
                JSONObject j = new JSONObject(lastResultJson);
                JSONObject payload = j.optJSONObject("payload");
                if (payload != null) {
                    JSONObject result = payload.optJSONObject("result");
                    if (result != null) deliveryUid = result.optString("delivery_uid", "");
                }
            }
        } catch (Exception ignored) {}

        final String fDeliveryUid = deliveryUid;

        // ✅ Sauvegarder dans la queue offline — même si pas de réseau
        try {
            DeliveryResultQueueDb queueDb = new DeliveryResultQueueDb(activity);
            JSONObject queuePayload = new JSONObject();
            queuePayload.put("deliveryUid", fDeliveryUid.isEmpty() ? woNum + "-" + System.currentTimeMillis() : fDeliveryUid);
            queuePayload.put("workOrderId", woGuid.replace("{", "").replace("}", ""));
            queuePayload.put("woNum",       woNum   != null ? woNum   : "");
            queuePayload.put("netTotal",    net     != null ? Double.parseDouble(net)   : 0);
            queuePayload.put("grossTotal",  gross   != null ? Double.parseDouble(gross) : 0);
            queuePayload.put("ticketNo",    ticket  != null ? ticket  : "");
            queuePayload.put("status",      status  != null ? status  : "DONE");
            queueDb.upsertPending(
                fDeliveryUid.isEmpty() ? woNum + "-" + System.currentTimeMillis() : fDeliveryUid,
                queuePayload.toString()
            );
            android.util.Log.i(TAG, "patchDataverse: ajouté à la queue offline");
            // ✅ Déclencher WorkManager immédiatement si réseau disponible
            com.pa.lcrdemo.dataverse.DeliverySyncScheduler.triggerNow(activity);
        } catch (Exception e) {
            android.util.Log.e(TAG, "Queue ERR: " + e.getMessage());
        }

        // ✅ Tenter MSAL + PATCH immédiat si réseau disponible
        MsalTokenProvider tokenProvider = new MsalTokenProvider(activity);
        tokenProvider.init(new MsalTokenProvider.InitCallback() {
            @Override
            public void onReady() {
                tokenProvider.acquireToken(activity, new MsalTokenProvider.TokenCallback() {
                    @Override
                    public void onSuccess(String accessToken) {
                        btExec.execute(() -> {
                            try {
                                WorkOrderUpdater.patchSummary(
                                    accessToken,
                                    woGuid,
                                    net, gross, ticket,
                                    woNum, fDeliveryUid
                                );
                                android.util.Log.i(TAG, "patchDataverse MSAL: OK — wonum=" + woNum);
                                // Marquer comme envoyé dans la queue
                                try {
                                    DeliveryResultQueueDb qdb = new DeliveryResultQueueDb(activity);
                                    java.util.List<DeliveryResultQueueDb.QueueItem> items =
                                        qdb.listPending(5);
                                    for (DeliveryResultQueueDb.QueueItem item : items) {
                                        if (item.deliveryUid.equals(fDeliveryUid)) {
                                            qdb.markSent(item.id);
                                            break;
                                        }
                                    }
                                } catch (Exception ignored) {}
                            } catch (Exception e) {
                                android.util.Log.w(TAG, "patchDataverse MSAL PATCH ERR: " + e.getMessage());
                                // Reste dans la queue pour retry WorkManager
                            }
                        });
                    }
                    @Override
                    public void onError(Exception e) {
                        android.util.Log.w(TAG, "patchDataverse MSAL token ERR: " + e.getMessage()
                            + " — restera dans la queue pour retry");
                    }
                });
            }
            @Override
            public void onError(Exception e) {
                android.util.Log.w(TAG, "patchDataverse MSAL init ERR: " + e.getMessage()
                    + " — restera dans la queue pour retry");
            }
        });
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