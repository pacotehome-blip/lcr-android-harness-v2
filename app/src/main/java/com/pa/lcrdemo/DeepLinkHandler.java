package com.pa.lcrdemo;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.net.Uri;

import androidx.fragment.app.Fragment;

import com.pa.lcr.lcp.MultiRegisterApiFacadeImpl;
import com.pa.lcr.lcp.storage.ActiveDeliveryStore;
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

            final String fWoNum    = woNum;
            final String fSerialId = serialId != null ? serialId : "";
            logDeliveryStart(fSerialId, fWoNum, btMac, lcrnode, produit, presetStr);

            // ✅ Vérifier si une livraison est déjà en cours
            try {
                ActiveDeliveryStore ads = new ActiveDeliveryStore(activity);
                ActiveDeliveryStore.ActiveDelivery active = ads.load();
                if (active != null && active.jobId != null && !active.jobId.isEmpty()) {
                    if (woNum != null && woNum.equals(active.woNum)) {
                        // Même WO — reprendre le poll
                        android.util.Log.i(TAG, "Livraison en cours détectée — reprise poll jobId="
                            + active.jobId + " woNum=" + woNum);
                        activity.toast("↩️ Reprise livraison — " + woNum);
                        int resumeNode = active.node > 0 ? active.node : (lcrnode != null ? lcrnode : 250);
                        String resumeMac = (active.mac != null && !active.mac.isEmpty())
                            ? active.mac : btMac;
                        pollJobUntilDone(active.jobId, resumeNode, woNum, woIdGuid,
                            fSerialId, resumeMac);
                        return;
                    } else {
                        // WO différent — bloquer et alerter l'opérateur
                        android.util.Log.w(TAG, "Livraison en cours: " + active.woNum
                            + " — impossible de démarrer " + woNum);
                        final String activeWo = active.woNum;
                        activity.runOnUiThread(() ->
                            activity.toast("⚠️ Livraison " + activeWo
                                + " en cours — terminez-la avant de passer à " + woNum));
                        retournerFieldService(woNum, woIdGuid, "erreur_livraison_en_cours",
                            buildErrorJson("DELIVERY_IN_PROGRESS",
                                "Livraison " + activeWo + " en cours sur ce registre"));
                        return;
                    }
                }
            } catch (Exception ignored) {}

            activity.toast("📦 Livraison — " + woNum);
            int finalNode = (lcrnode != null ? lcrnode : 250);
            final int fNode = finalNode;
            final String fBtMac = btMac;
            final String fProduit = produit;
            final String fPresetStr = presetStr;

            // ✅ Résolution transport universel: USB / BT / TCP
            // Chercher d'abord un transport actif pour ce node/serial via RSM.
            // Si trouvé → utiliser directement. Si non → fallback BT si MAC fourni.
            btExec.execute(() -> {
                try {
                    com.pa.lcr.lcp.RegisterSessionManager rsm =
                        com.pa.lcr.lcp.RegisterSessionManager.get(activity);

                    if (fSerialId != null && !fSerialId.isEmpty()) {
                        rsm.bindExpectedSerial(fNode, fSerialId);
                    }

                    com.pa.lcr.lcp.DeliveryController dc =
                        rsm.resolveOrCreateForNode(fNode, 255);

                    if (dc != null) {
                        String foundKey = rsm.findTransportKeyForController(dc);
                        android.util.Log.i(TAG, "Transport trouvé pour node=" + fNode
                            + " transportKey=" + foundKey);
                        lancerLivraison(foundKey != null ? foundKey : "", fNode,
                            fSerialId, woNum, woIdGuid, fProduit, fPresetStr, fBtMac);
                    } else {
                        // Aucun transport actif — tenter auto-connect (USB / BT / TCP)
                        android.util.Log.i(TAG, "Aucun transport actif — tentative auto-connect node="
                            + fNode + " serial=" + fSerialId);
                        MultiRegisterApiFacadeImpl facadeAuto = new MultiRegisterApiFacadeImpl(activity);
                        com.pa.lcr.lcp.ApiResult ra = facadeAuto.api_registerConnectAuto(
                            fSerialId.isEmpty() ? null : fSerialId, fNode);
                        android.util.Log.i(TAG, "auto-connect: code=" + (ra != null ? ra.code : "null")
                            + " msg=" + (ra != null ? ra.msg : "null"));

                        if (ra != null && ra.code == 1) {
                            // Auto-connect réussi — résoudre à nouveau
                            com.pa.lcr.lcp.DeliveryController dc2 =
                                rsm.resolveOrCreateForNode(fNode, 255);
                            String foundKey2 = dc2 != null ? rsm.findTransportKeyForController(dc2) : null;
                            lancerLivraison(foundKey2 != null ? foundKey2 : "", fNode,
                                fSerialId, woNum, woIdGuid, fProduit, fPresetStr, fBtMac);
                        } else if (fBtMac != null && !fBtMac.trim().isEmpty()) {
                            // Fallback BT explicite
                            android.util.Log.i(TAG, "Auto-connect échoué — connexion BT: " + fBtMac);
                            connectBtByMacAndOpenTab(fBtMac, fNode, serialId, woNum, woIdGuid,
                                fProduit, fPresetStr);
                        } else {
                            android.util.Log.w(TAG, "Registre introuvable — node=" + fNode);
                            activity.runOnUiThread(() ->
                                activity.toast("⚠️ Registre non connecté — connectez le registre"));
                            retournerFieldService(woNum, woIdGuid, "erreur_registre",
                                buildErrorJson("NO_TRANSPORT",
                                    "Registre node=" + fNode + " introuvable sur tous les transports"));
                        }
                    }
                } catch (Exception e) {
                    android.util.Log.e(TAG, "Résolution transport ERR: " + e.getMessage());
                    if (fBtMac != null && !fBtMac.trim().isEmpty()) {
                        connectBtByMacAndOpenTab(fBtMac, fNode, serialId, woNum, woIdGuid,
                            fProduit, fPresetStr);
                    }
                }
            });
        }
    }

    // =========================================================
    // Connexion BT + oneshot/start
    // =========================================================

    // =========================================================
    // Lancer livraison sur transport déjà actif (USB/BT/TCP)
    // =========================================================

    private void lancerLivraison(String transportKey, int node, String serialId,
                                  String woNum, String woIdGuid,
                                  String produit, String presetStr, String mac) {
        // Ouvrir/activer le tab
        final String fSerialId = serialId != null ? serialId : "";
        final String fWoNum = woNum;
        final String fProduit = produit;
        final String fPresetStr = presetStr;
        activity.runOnUiThread(() -> {
            try {
                if (!transportKey.isEmpty()) {
                    activity.onConfigureMediaActivated(transportKey, "DEEPLINK");
                    activity.upsertRegisterTabFromScan(transportKey, node, 255, fSerialId, true);

                    // ✅ Retry prefill — le tab peut prendre du temps à être créé après auto-connect
                    Runnable prefill = new Runnable() {
                        int attempts = 0;
                        @Override public void run() {
                            try {
                                String mediaShort = activity.mediaShortFromTransportKey(transportKey);
                                String tabKey = activity.tabKeyOf(mediaShort, node, fSerialId);
                                Fragment f = activity.getSupportFragmentManager()
                                    .findFragmentByTag("regtab_" + tabKey);
                                if (f instanceof RegisterTabFragment) {
                                    ((RegisterTabFragment) f).prefillFromDeepLink(
                                        fWoNum, fProduit, fPresetStr);
                                } else if (attempts++ < 5) {
                                    // Tab pas encore créé — réessayer
                                    activity.getUiHandler().postDelayed(this, 800);
                                }
                            } catch (Exception ignored) {}
                        }
                    };
                    activity.getUiHandler().postDelayed(prefill, 1200);
                    activity.refreshAllTabsMediaStatus();
                    activity.showPage(0);
                }
            } catch (Exception ignored) {}
        });

        // Démarrer oneshot/start
        int product = 1;
        double preset = 0.0;
        try { product = Integer.parseInt(produit);     } catch (Exception ignored) {}
        try { preset  = Double.parseDouble(presetStr); } catch (Exception ignored) {}

        final int fProduct = product;
        final double fPresetD = preset;
        final String fMac = mac != null ? mac : "";

        try {
            MultiRegisterApiFacadeImpl facade = new MultiRegisterApiFacadeImpl(activity);
            com.pa.lcr.lcp.ApiResult r = facade.api_deliveryOneShotStart(
                node, 255, woNum, fProduct, fPresetD, null,
                fMac.isEmpty() ? "usb" : "bt",
                fMac.isEmpty() ? null : fMac);

            android.util.Log.i(TAG, "oneshot/start: code=" + r.code + " msg=" + r.msg);

            if (r.code == 1) {
                String jobId = (r.data != null) ? r.data.optString("jobId", null) : null;
                logEvent(fSerialId, woNum, DeliveryLogStore.LEVEL_INFO,
                    "ONESHOT_START", "ARMED jobId=" + jobId, null);
                if (jobId != null && !jobId.isEmpty()) {
                    activity.runOnUiThread(() ->
                        activity.toast("📦 Livraison démarrée — " + woNum));
                    pollJobUntilDone(jobId, node, woNum, woIdGuid, fSerialId,
                        fMac.isEmpty() ? transportKey : fMac);
                }
            } else {
                android.util.Log.w(TAG, "oneshot/start code=0: " + r.msg);
                logEvent(fSerialId, woNum, DeliveryLogStore.LEVEL_WARN,
                    "ONESHOT_ERROR", r.msg, r.data != null ? r.data.toString() : null);
                retournerFieldService(woNum, woIdGuid, "erreur_oneshot",
                    buildErrorJson("ONESHOT_FAILED", r.msg));
            }
        } catch (Exception e) {
            android.util.Log.e(TAG, "lancerLivraison ERR: " + e.getMessage());
            logError(fSerialId, woNum, "ONESHOT_EXCEPTION", e.getMessage());
            retournerFieldService(woNum, woIdGuid, "erreur",
                buildErrorJson("ONESHOT_EXCEPTION", e.getMessage()));
        }
    }

    // =========================================================
    // Connexion BT + oneshot/start (fallback si pas de transport actif)
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

                // ✅ Si BT déjà connecté — valider l'état du registre avant tout
                if (btDejaConnecte) {
                    try {
                        String tKey = MediaTransportManager.btKey(mac);
                        com.pa.lcr.lcp.DeliveryController dc =
                            com.pa.lcr.lcp.RegisterSessionManager.get(activity)
                                .getController(tKey, node);

                        if (dc == null) {
                            // Controller absent — BT zombi
                            android.util.Log.w(TAG, "BT zombi — controller absent, restart BT");
                            activity.btDisconnect();
                            try { Thread.sleep(1500); } catch (Exception ignored) {}
                            btDejaConnecte = false; // forcer reconnexion
                        } else {
                            // Lire l'état du registre via tickSnapshot
                            com.pa.lcr.lcp.ApiResult snap = dc.api_tickSnapshot();
                            int delCode = (snap != null && snap.data != null)
                                ? snap.data.optInt("delCode", 0) : 0;
                            boolean deliveryActive = (delCode & 0x0008) != 0;
                            boolean ticketPending  = (delCode & 0x0001) != 0;

                            if (deliveryActive) {
                                // Livraison active sur le registre
                                ActiveDeliveryStore ads = new ActiveDeliveryStore(activity);
                                ActiveDeliveryStore.ActiveDelivery active = ads.load();
                                if (active != null && woNum != null && woNum.equals(active.woNum)) {
                                    // Même WO — déjà géré dans handleDeepLink, ne devrait pas arriver ici
                                    android.util.Log.i(TAG, "Livraison active même WO — reprise tab");
                                } else {
                                    // WO différent ou inconnu — bloquer
                                    String activeWo = (active != null) ? active.woNum : "inconnue";
                                    android.util.Log.w(TAG, "Registre: livraison active " + activeWo
                                        + " — impossible de démarrer " + woNum);
                                    final String fActiveWo = activeWo;
                                    activity.runOnUiThread(() ->
                                        activity.toast("⚠️ Livraison " + fActiveWo
                                            + " active sur le registre — terminez-la d'abord"));
                                    retournerFieldService(woNum, woIdGuid, "erreur_livraison_en_cours",
                                        buildErrorJson("DELIVERY_IN_PROGRESS",
                                            "Livraison " + fActiveWo + " active sur le registre"));
                                    return;
                                }
                            } else if (ticketPending) {
                                // Ticket pending seulement — impression en attente
                                // Le registre permet de démarrer une nouvelle livraison
                                // On laisse passer — juste loguer
                                android.util.Log.i(TAG, "Ticket pending détecté — démarrage nouvelle livraison quand même");
                            } else {
                                // Registre idle — vérifier si le controller répond (zombi?)
                                com.pa.lcr.lcp.ApiResult statusCheck = dc.api_tickSnapshot();
                                if (statusCheck == null) {
                                    android.util.Log.w(TAG, "Registre zombi — pas de réponse");
                                    activity.runOnUiThread(() ->
                                        activity.toast("⚠️ Registre ne répond pas — utilisez Résoudre (A) dans le tab"));
                                }
                            }
                        }
                    } catch (Exception e) {
                        android.util.Log.w(TAG, "Validation état registre ERR: " + e.getMessage());
                        // BT zombi probable — restart
                        android.util.Log.w(TAG, "Possible BT zombi — restart BT");
                        activity.btDisconnect();
                        try { Thread.sleep(1500); } catch (Exception ignored) {}
                        btDejaConnecte = false;
                    }
                }

                if (!btDejaConnecte) {
                    android.bluetooth.BluetoothAdapter btAdapter = activity.getBtAdapter();
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
                }

                try {
                    MultiRegisterApiFacadeImpl facadeVal =
                        new MultiRegisterApiFacadeImpl(activity);
                    com.pa.lcr.lcp.ApiResult rv = facadeVal.api_registerValidate(
                        woNum, node, null, serialId, null, null, "bt", mac);
                    android.util.Log.i(TAG, "register/validate: code=" + rv.code + " msg=" + rv.msg);

                    if (rv.code != 1) {
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
                }

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
                            String jobId = (r.data != null)
                                ? r.data.optString("jobId", null) : null;

                            logEvent(fSerialId, woNum, DeliveryLogStore.LEVEL_INFO,
                                "ONESHOT_START", "ARMED jobId=" + jobId, null);

                            if (jobId != null && !jobId.isEmpty()) {
                                android.util.Log.i(TAG, "Poll démarré — jobId=" + jobId);
                                activity.runOnUiThread(() ->
                                    activity.toast("📦 Livraison démarrée — " + woNum));
                                pollJobUntilDone(jobId, node, woNum, woIdGuid, fSerialId, mac);
                            } else {
                                android.util.Log.w(TAG, "oneshot/start: jobId absent");
                                activity.runOnUiThread(() ->
                                    activity.toast("📦 Livraison démarrée (sans jobId) — " + woNum));
                            }
                        } else {
                            android.util.Log.w(TAG, "oneshot/start code=0: " + r.msg);
                            logEvent(fSerialId, woNum, DeliveryLogStore.LEVEL_WARN,
                                "ONESHOT_ERROR", r.msg,
                                r.data != null ? r.data.toString() : null);
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

    // ✅ Guard anti-double poll — un seul poll par jobId
    private static final java.util.Set<String> activePolls =
        java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    // =========================================================
    // Poll état livraison
    // =========================================================

    private void pollJobUntilDone(String jobId, int node, String woNum,
                                   String woIdGuid, String serialId, String mac) {
        // ✅ Anti-double poll — si ce jobId est déjà en cours de poll, ignorer
        if (!activePolls.add(jobId)) {
            android.util.Log.w(TAG, "pollJobUntilDone: déjà actif pour jobId=" + jobId + " — ignoré");
            return;
        }
        // ✅ Persister la livraison courante
        try {
            new ActiveDeliveryStore(activity).save(woNum, woIdGuid, jobId, mac, node, serialId);
        } catch (Exception ignored) {}

        btExec.execute(() -> {
            try {
                final boolean[] deliveryDone = {false};

                boolean hasSeenFlowing = false;
                boolean terminateSent  = false;
                String  lastState      = "";

                try {
                    // ✅ Vérifier l'état avant d'envoyer continue
                    // Si déjà RUNNING_FLOWING ou RUNNING_PAUSED, ne pas renvoyer continue
                    // (évite de redémarrer une livraison en pause lors d'une reprise)
                    String currentState = "";
                    try {
                        MultiRegisterApiFacadeImpl facadeCheck =
                            new MultiRegisterApiFacadeImpl(activity);
                        com.pa.lcr.lcp.ApiResult stateCheck =
                            facadeCheck.api_deliveryJobGet(jobId);
                        if (stateCheck != null && stateCheck.data != null)
                            currentState = stateCheck.data.optString("state", "");
                    } catch (Exception ignored) {}

                    if ("RUNNING_FLOWING".equals(currentState)
                            || "RUNNING_PAUSED".equals(currentState)) {
                        android.util.Log.i(TAG, "job/continue ignoré — déjà en " + currentState);
                        hasSeenFlowing = true;
                    } else if ("CONNECTED".equals(currentState)) {
                        // Vérifier si ticket pending — si oui, faire status B et laisser l'opérateur
                        boolean tp = false;
                        try {
                            MultiRegisterApiFacadeImpl facadeCheck2 =
                                new MultiRegisterApiFacadeImpl(activity);
                            com.pa.lcr.lcp.ApiResult stateCheck2 =
                                facadeCheck2.api_deliveryJobGet(jobId);
                            if (stateCheck2 != null && stateCheck2.data != null)
                                tp = stateCheck2.data.optInt("ticketPending", 0) == 1;
                        } catch (Exception ignored) {}

                        if (tp) {
                            android.util.Log.i(TAG, "Reprise: ticket pending — status B + attente opérateur");
                            try {
                                String tKey = MediaTransportManager.btKey(mac);
                                com.pa.lcr.lcp.DeliveryController dc =
                                    com.pa.lcr.lcp.RegisterSessionManager.get(activity)
                                        .getController(tKey, node);
                                if (dc != null) {
                                    dc.requestStatus();
                                    Thread.sleep(200);
                                    dc.requestLiveSample();
                                }
                            } catch (Exception ignored) {}
                            // Sortir du poll — l'opérateur gère via bouton A
                            return;
                        } else {
                            // CONNECTED sans ticket pending — envoyer continue normalement
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
                        }
                    } else {
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
                    }
                } catch (Exception e) {
                    android.util.Log.e(TAG, "job/continue ERR: " + e.getMessage());
                    logError(serialId, woNum, "JOB_CONTINUE_ERROR", e.getMessage());
                }

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

                        // ✅ state=null = job disparu du controller — sortir immédiatement
                        if (state == null || state.isEmpty()) {
                            android.util.Log.w(TAG, "pollJob: state=null — job disparu, arrêt poll");
                            return;
                        }

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

                        // ✅ PRINT_TIMEOUT: imprimante offline — écrire quand même dans Dataverse
                        if ("ERROR".equals(state) && r.data != null
                                && "PRINT_TIMEOUT".equals(r.data.optString("err", ""))) {
                            if (deliveryDone[0]) return;
                            deliveryDone[0] = true;
                            String extraJson = r.data.toString();
                            android.util.Log.w(TAG, "Livraison PRINT_TIMEOUT — Dataverse quand même — " + extraJson);
                            logDeliveryEnd(serialId, woNum, jobId, "DONE_PRINT_TIMEOUT", extraJson, null);
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
                        if ("CONNECTED".equals(state) && hasSeenFlowing && !terminateSent) {
                            android.util.Log.i(TAG, "CONNECTED après FLOWING — terminate direct");
                            try {
                                MultiRegisterApiFacadeImpl facadeTerm2 =
                                    new MultiRegisterApiFacadeImpl(activity);
                                try {
                                    String tKey = MediaTransportManager.btKey(mac);
                                    com.pa.lcr.lcp.DeliveryController dc =
                                        com.pa.lcr.lcp.RegisterSessionManager.get(activity)
                                            .getController(tKey, node);
                                    if (dc != null) { dc.requestLiveSample(); Thread.sleep(300); }
                                } catch (Exception ignored) {}
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
                                try {
                                    String tKey = MediaTransportManager.btKey(mac);
                                    com.pa.lcr.lcp.DeliveryController dc =
                                        com.pa.lcr.lcp.RegisterSessionManager.get(activity)
                                            .getController(tKey, node);
                                    if (dc != null) { dc.requestLiveSample(); Thread.sleep(300); }
                                } catch (Exception ignored) {}
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

                android.util.Log.w(TAG, "pollJob: timeout 10min sans DONE");
                logError(serialId, woNum, "POLL_TIMEOUT", "Timeout 10 minutes sans DONE");
                retournerFieldService(woNum, woIdGuid, "erreur_timeout",
                    buildErrorJson("POLL_TIMEOUT", "Timeout 10 minutes"));

            } catch (Exception e) {
                android.util.Log.e(TAG, "pollJob ERR: " + e.getMessage());
                logError(serialId, woNum, "POLL_EXCEPTION", e.getMessage());
                retournerFieldService(woNum, woIdGuid, "erreur",
                    buildErrorJson("POLL_EXCEPTION", e.getMessage()));
            } finally {
                // ✅ Toujours retirer du set — libère le guard pour ce jobId
                activePolls.remove(jobId);
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
        // ✅ Effacer la livraison courante
        try { new ActiveDeliveryStore(activity).clear(); } catch (Exception ignored) {}
        android.util.Log.i(TAG,
            "Livraison terminée — WO=" + woNum + " extra=" + extraJson);
        retournerFieldService(woNum, woIdGuid, "termine", extraJson);
    }

    // =========================================================
    // Dernier résultat
    // =========================================================

    public static volatile String lastResultJson  = null;
    public static volatile String lastResultWoNum  = null;
    public static volatile String lastResultWoGuid = null;
    public static volatile long   lastResultTs     = 0;

    // =========================================================
    // Retour Field Service
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

                    // ✅ FIX universel: si stale ou valeurs nulles, utiliser le tick.
                    boolean stale  = d.optBoolean("stale", false);
                    double fsNet   = result.optDouble("fs_net_l",   0);
                    double fsGross = result.optDouble("fs_gross_l", 0);
                    if (stale || fsNet == 0 || fsGross == 0) {
                        JSONObject tick = d.optJSONObject("tick");
                        if (tick != null) {
                            double tickNet   = tick.optDouble("net",   0);
                            double tickGross = tick.optDouble("gross", 0);
                            if (tickNet > 0 && tickGross > 0) {
                                net   = String.valueOf(tickNet);
                                gross = String.valueOf(tickGross);
                                android.util.Log.i(TAG,
                                    "retournerFS: stale/zero corrigé — tick net=" + tickNet
                                    + " gross=" + tickGross);
                            }
                        }
                    }
                } else {
                    net   = String.valueOf(d.optDouble("net",   0));
                    gross = String.valueOf(d.optDouble("gross", 0));
                }
            } catch (Exception ignored) {}

            String woGuid = (woIdGuid != null && !woIdGuid.isEmpty()) ? woIdGuid : "";
            woGuid = woGuid.replace("{", "").replace("}", "");

            try {
                JSONObject lastResult = new JSONObject();
                lastResult.put("wonum",  woNum  != null ? woNum  : "");
                lastResult.put("woid",   woGuid);
                lastResult.put("net",    net);
                lastResult.put("gross",  gross);
                lastResult.put("ticket", ticket);
                lastResult.put("status", status != null ? status : "ok");
                lastResult.put("ts",     System.currentTimeMillis());
                if (extraJson != null) {
                    try { lastResult.put("payload", new JSONObject(extraJson)); }
                    catch (Exception ignored) {}
                }
                lastResultJson   = lastResult.toString();
                lastResultWoNum  = woNum;
                lastResultWoGuid = woGuid;
                lastResultTs     = System.currentTimeMillis();
                com.pa.lcrdemo.LcrHttpService.lastResultJson = lastResult.toString();
                android.util.Log.i(TAG, "last-result sauvegardé: wonum=" + woNum
                    + " net=" + net + " gross=" + gross + " ticket=" + ticket);

                final String fNetP   = net;
                final String fGrossP = gross;
                final String fTicketP = ticket;
                final String fGuidP  = woGuid;
                final String fWoNumP = woNum;
                final String fStatusP = status;
                patchDataverse(fGuidP, fWoNumP, fNetP, fGrossP, fTicketP, fStatusP);

            } catch (Exception ignored) {}

            final String fNet    = net;
            final String fGross  = gross;
            final String fTicket = ticket;
            final String fWoGuid = woGuid;
            final String fWoNum2 = woNum;
            final String fStatus = status;

            activity.runOnUiThread(() -> {
                try {
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

                    String js = "try { localStorage.setItem('lcr_last_result', '"
                        + lsData.toString().replace("'", "\\'") + "'); } catch(e) {}";

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

                android.util.Log.i(TAG, "Retour FS — finish()");
                try {
                    activity.finish();
                } catch (Exception e) {
                    android.util.Log.e(TAG, "finish() ERR: " + e.getMessage());
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
            d.put("woNum",   woNum   != null ? woNum   : "");
            d.put("btMac",   btMac   != null ? btMac   : "");
            d.put("node",    node    != null ? node    : 0);
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
                .put("code",    code)
                .put("message", message != null ? message : "")
                .put("ts",      System.currentTimeMillis())
                .toString();
            logDeliveryEnd(serialId, woNum, null, "ERROR", null, errorJson);
            logEvent(serialId, woNum, DeliveryLogStore.LEVEL_ERROR, code, message, errorJson);
        } catch (Exception ignored) {}
    }

    // =========================================================
    // Patch Dataverse
    // =========================================================

    private void patchDataverse(String woGuid, String woNum,
                                 String net, String gross, String ticket,
                                 String status) {
        if (woGuid == null || woGuid.isEmpty()) {
            android.util.Log.w(TAG, "patchDataverse: GUID vide — ignoré");
            return;
        }

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

        final String fDeliveryUid = deliveryUid.isEmpty()
            ? woNum + "-" + System.currentTimeMillis()
            : deliveryUid;

        try {
            DeliveryResultQueueDb queueDb = new DeliveryResultQueueDb(activity);
            JSONObject queuePayload = new JSONObject();
            queuePayload.put("deliveryUid", fDeliveryUid);
            queuePayload.put("workOrderId", woGuid.replace("{", "").replace("}", ""));
            queuePayload.put("woNum",       woNum   != null ? woNum   : "");
            queuePayload.put("netTotal",    net     != null ? Double.parseDouble(net)   : 0);
            queuePayload.put("grossTotal",  gross   != null ? Double.parseDouble(gross) : 0);
            queuePayload.put("ticketNo",    ticket  != null ? ticket  : "");
            queuePayload.put("status",      status  != null ? status  : "DONE");
            queueDb.upsertPending(fDeliveryUid, queuePayload.toString());
            android.util.Log.i(TAG, "patchDataverse: ajouté à la queue offline");
            com.pa.lcrdemo.dataverse.DeliverySyncScheduler.triggerNow(activity);
        } catch (Exception e) {
            android.util.Log.e(TAG, "Queue ERR: " + e.getMessage());
        }

        MsalTokenProvider tokenProvider = new MsalTokenProvider(activity);
        tokenProvider.init(new MsalTokenProvider.InitCallback() {
            @Override
            public void onReady() {
                tokenProvider.acquireTokenSilentFromWorker(new MsalTokenProvider.TokenCallback() {
                    @Override
                    public void onSuccess(String accessToken) {
                        btExec.execute(() -> {
                            try {
                                WorkOrderUpdater.patchSummary(
                                    accessToken, woGuid,
                                    net, gross, ticket,
                                    woNum, fDeliveryUid);
                                android.util.Log.i(TAG, "patchDataverse MSAL: OK — wonum=" + woNum);
                                try {
                                    DeliveryResultQueueDb qdb = new DeliveryResultQueueDb(activity);
                                    java.util.List<DeliveryResultQueueDb.QueueItem> items =
                                        qdb.listPending(5);
                                    for (DeliveryResultQueueDb.QueueItem item : items) {
                                        if (fDeliveryUid.equals(item.deliveryUid)) {
                                            qdb.markSent(item.id);
                                            break;
                                        }
                                    }
                                } catch (Exception ignored) {}
                            } catch (Exception e) {
                                android.util.Log.w(TAG, "patchDataverse PATCH ERR: " + e.getMessage());
                            }
                        });
                    }
                    @Override
                    public void onError(Exception e) {
                        android.util.Log.w(TAG, "patchDataverse token ERR: " + e.getMessage());
                    }
                });
            }
            @Override
            public void onError(Exception e) {
                android.util.Log.w(TAG, "patchDataverse MSAL init ERR: " + e.getMessage());
            }
        });
    }

    private static String buildErrorJson(String code, String message) {
        try {
            return new JSONObject()
                .put("error_code",    code)
                .put("error_message", message != null ? message : "")
                .put("ts",            System.currentTimeMillis())
                .toString();
        } catch (Exception e) {
            return "{\"error_code\":\"" + code + "\"}";
        }
    }
}