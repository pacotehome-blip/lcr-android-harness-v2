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

    // ✅ Contexte livraison courante — persisté pour onDeliveryEnded
    private volatile int    currentNode     = 0;
    private volatile String currentSerialId = "";

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
            String orgUrl     = data.getQueryParameter("orgurl"); // ✅ env auto-detect

            // ✅ Configurer l'environnement selon l'URL Dataverse reçue de FSM
            // Un seul APK pour DEV / QA / STAGING / PROD
            if (orgUrl != null && !orgUrl.isEmpty()) {
                com.pa.lcrdemo.config.LcrConfig.applyFromOrgUrl(activity, orgUrl);
                android.util.Log.i(TAG, "Env détecté depuis orgurl: " + orgUrl
                    + " → " + com.pa.lcrdemo.config.LcrConfig.getEnvironmentName(activity));
            }

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

            // ✅ Persister le contexte livraison pour onDeliveryEnded
            currentSerialId = fSerialId;

            // ✅ Sauvegarder PENDING dès réception — avant connexion BT
            // Le tab peut lire ces infos même si la livraison n'est pas encore démarrée
            try {
                int iProduit = 1;
                double dPreset = 0.0;
                try { iProduit = Integer.parseInt(produit); } catch (Exception ignored) {}
                try { dPreset = Double.parseDouble(presetStr); } catch (Exception ignored) {}
                new ActiveDeliveryStore(activity).save(
                    woNum, woIdGuid, "", // jobId vide — pas encore démarré
                    btMac != null ? btMac : "",
                    lcrnode != null ? lcrnode : 250,
                    fSerialId, iProduit, dPreset, "PENDING");
            } catch (Exception ignored) {}

            // ✅ Vérifier si une livraison est déjà en cours
            try {
                ActiveDeliveryStore ads = new ActiveDeliveryStore(activity);
                ActiveDeliveryStore.ActiveDelivery active = ads.load();
                if (active != null && active.jobId != null && !active.jobId.isEmpty()) {
                    if (woNum != null && woNum.equals(active.woNum)) {
                        // Même WO — reprendre le poll sans toucher au registre
                        android.util.Log.i(TAG, "Reprise poll — même WO jobId=" + active.jobId);
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
            currentNode = finalNode;
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
                        // ✅ Attendre que le DC soit CONNECTED (probeAndIdentify terminé)
                        // max 15s, 200ms par itération
                        activity.runOnUiThread(() -> activity.toast("🔌 Connexion au registre..."));
                        for (int w = 0; w < 75; w++) {
                            if (dc.getState() == com.pa.lcr.lcp.DeliveryState.CONNECTED) break;
                            try { Thread.sleep(200); } catch (Exception ignored) {}
                        }
                        android.util.Log.i(TAG, "DC state avant lancerLivraison: " + dc.getState());
                        if (dc.getState() != com.pa.lcr.lcp.DeliveryState.CONNECTED) {
                            android.util.Log.w(TAG, "DC non prêt après 15s — état: " + dc.getState()
                                + " — tentative auto-connect");
                            // ✅ Tenter auto-connect (USB ou BT) avant d'abandonner
                            MultiRegisterApiFacadeImpl facadeRetry = new MultiRegisterApiFacadeImpl(activity);
                            com.pa.lcr.lcp.ApiResult ra2 = facadeRetry.api_registerConnectAuto(
                                fSerialId.isEmpty() ? null : fSerialId, fNode);
                            if (ra2 != null && ra2.code == 1) {
                                com.pa.lcr.lcp.DeliveryController dc3 =
                                    rsm.resolveOrCreateForNode(fNode, 255);
                                if (dc3 != null) {
                                    for (int w = 0; w < 75; w++) {
                                        if (dc3.getState() == com.pa.lcr.lcp.DeliveryState.CONNECTED) break;
                                        try { Thread.sleep(200); } catch (Exception ignored) {}
                                    }
                                    if (dc3.getState() == com.pa.lcr.lcp.DeliveryState.CONNECTED) {
                                        String foundKey3 = rsm.findTransportKeyForController(dc3);
                                        lancerLivraison(foundKey3 != null ? foundKey3 : "", fNode,
                                            fSerialId, woNum, woIdGuid, fProduit, fPresetStr, fBtMac);
                                        return;
                                    }
                                }
                            }
                            logError(fSerialId, woNum, "REGISTER_NOT_READY",
                                "DC non CONNECTED après 15s + retry auto-connect — état: " + dc.getState());
                            activity.runOnUiThread(() ->
                                activity.toast("⚠️ Registre non joignable — tentative de reconnexion..."));
                            // Lancer sur thread dédié — ne pas bloquer btExec
                            // diagnosticEnCours static garantit un seul diagnostic à la fois
                            new Thread(() -> new com.pa.lcrdemo.RegisterConnectionHelper(activity)
                                .lancerDiagnosticForce("", fNode, fSerialId, woNum,
                                    woIdGuid, fProduit, fPresetStr, fBtMac,
                                    DeepLinkHandler.this)).start();
                            return;
                        }
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
                            // Auto-connect réussi — attendre DC CONNECTED
                            com.pa.lcr.lcp.DeliveryController dc2 =
                                rsm.resolveOrCreateForNode(fNode, 255);
                            if (dc2 != null) {
                                activity.runOnUiThread(() -> activity.toast("🔌 Connexion au registre..."));
                                for (int w = 0; w < 75; w++) {
                                    if (dc2.getState() == com.pa.lcr.lcp.DeliveryState.CONNECTED) break;
                                    try { Thread.sleep(200); } catch (Exception ignored) {}
                                }
                                if (dc2.getState() != com.pa.lcr.lcp.DeliveryState.CONNECTED) {
                                    android.util.Log.w(TAG, "DC2 non prêt après 15s — état: " + dc2.getState());
                                    logError(fSerialId, woNum, "REGISTER_NOT_READY",
                                        "DC2 non CONNECTED après 15s — état: " + dc2.getState());
                                    activity.runOnUiThread(() ->
                                        activity.toast("⚠️ Registre non joignable — tentative de reconnexion..."));
                                    new Thread(() -> new com.pa.lcrdemo.RegisterConnectionHelper(activity)
                                        .lancerDiagnosticForce("", fNode, fSerialId, woNum,
                                            woIdGuid, fProduit, fPresetStr, fBtMac,
                                            DeepLinkHandler.this)).start();
                                    return;
                                }
                            }
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

                            // ✅ Rester dans l'APK — pas de finish() pour éviter bounce FSM
                            // Le chauffeur va dans Configure pour connecter le registre
                            final String fWoNumR = woNum;
                            final String fWoIdR  = woIdGuid;
                            final int    fNodeR  = fNode;
                            activity.runOnUiThread(() -> {
                                android.app.AlertDialog.Builder dlg =
                                    new android.app.AlertDialog.Builder(activity);
                                dlg.setTitle("⚠️ Registre non connecté");
                                dlg.setMessage(
                                    "Le registre (node " + fNodeR + " · serial " + fSerialId + ") "
                                    + "n'est pas détecté sur USB ou Bluetooth.\n\n"
                                    + "1. Branchez le câble USB-C du registre\n"
                                    + "   — ou —\n"
                                    + "2. Activez le Bluetooth et connectez le registre\n\n"
                                    + "Ensuite, allez dans l'onglet Configure pour établir\n"
                                    + "la connexion, puis relancez depuis Field Service.");
                                dlg.setPositiveButton("Aller à Configure", (d, w) -> {
                                    activity.showPage(1); // onglet Configure
                                });
                                dlg.setNegativeButton("Annuler", null);
                                dlg.setCancelable(true);
                                dlg.show();
                            });

                            // Logger l'événement
                            logError(fSerialId, woNum, "NO_TRANSPORT",
                                "Registre node=" + fNode + " introuvable sur tous les transports");
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

    public void lancerLivraison(String transportKey, int node, String serialId,
                                  String woNum, String woIdGuid,
                                  String produit, String presetStr, String mac) {
        lancerLivraison(transportKey, node, serialId, woNum, woIdGuid,
            produit, presetStr, mac, false);
    }

    // ✅ skipConnexionCheck=true : utilisé uniquement par la relance automatique de
    // RegisterConnectionHelper juste après un diagnostic réussi (étape 4: lcpOk=true).
    // Refaire une vraie vérification LCP (api_registerValidate) immédiatement après
    // reconnexion, sur un socket BT qui vient tout juste d'être rétabli, est redondant
    // et risque d'ajouter un délai voire un blocage — le diagnostic vient déjà de
    // confirmer la connexion à l'instant.
    public void lancerLivraison(String transportKey, int node, String serialId,
                                  String woNum, String woIdGuid,
                                  String produit, String presetStr, String mac,
                                  boolean skipConnexionCheck) {
        // ✅ FIX : vérifier AVANT de toucher au tab — l'ancien code rafraîchissait
        // l'UI (upsertRegisterTabFromScan / showPage) même quand un poll était
        // déjà actif, ce qui faisait apparaître le tab en "CONNECTED — prêt"
        // pendant qu'une livraison tournait toujours dessous (désync live/toast).
        if (!activePolls.isEmpty()) {
            android.util.Log.w(TAG, "lancerLivraison: poll déjà actif — ignoré (avant UI)");
            activity.runOnUiThread(() -> activity.toast("↩️ Livraison déjà en cours"));
            return;
        }

        // ✅ FIX : confirmer la connexion RÉELLE au registre avant tout — pas un flag
        // getState()/snapshot en cache (qui peut mentir sur un socket zombie). Si la
        // vérification échoue, on relance le diagnostic complet (même média d'abord,
        // sinon recherche du registre sur tous les médias) AVANT de toucher au tab,
        // avant le check "Bon déjà complété", avant tout. Le diagnostic, une fois
        // réussi, rappelle lancerLivraison() lui-même avec une connexion confirmée.
        if (!skipConnexionCheck && !transportKey.isEmpty()) {
            boolean connexionOk = false;
            try {
                com.pa.lcr.lcp.DeliveryController dcCheck =
                    com.pa.lcr.lcp.RegisterSessionManager.get(activity).getController(transportKey, node);
                if (dcCheck != null) {
                    com.pa.lcr.lcp.ApiResult vr = dcCheck.api_registerValidate(
                        woNum, node, serialId, null, null);
                    // ✅ code==1 = validé sans blocage métier. Mais un code==0 peut aussi
                    // vouloir dire "ticket pending"/"delivery active"/mismatch — des cas
                    // où la communication LCP a RÉUSSI, ce n'est pas une panne transport.
                    // Seul un vrai échec de communication (pas de "ticket_no" dans data,
                    // signe que readFullStatus()/readTicketNo23() n'ont jamais abouti)
                    // doit déclencher le diagnostic de reconnexion.
                    connexionOk = (vr != null)
                        && (vr.code == 1 || (vr.data != null && vr.data.has("ticket_no")));
                }
            } catch (Exception e) {
                android.util.Log.w(TAG, "lancerLivraison: vérif connexion ERR: " + e.getMessage());
            }

            if (!connexionOk) {
                android.util.Log.w(TAG, "lancerLivraison: connexion registre non confirmée"
                    + " — diagnostic + reconnexion avant tout autre traitement");
                new Thread(() -> new com.pa.lcrdemo.RegisterConnectionHelper(activity)
                    .lancerDiagnosticForce(transportKey, node, serialId, woNum,
                        woIdGuid, produit, presetStr, mac,
                        DeepLinkHandler.this)).start();
                return;
            }
            android.util.Log.i(TAG, "lancerLivraison: connexion registre confirmée — poursuite");
        }

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

        // ✅ Attendre que le média soit READY (max 10s) avant oneshot/start
        boolean ready = false;
        for (int i = 0; i < 20; i++) {
            try { Thread.sleep(500); } catch (Exception ignored) {}
            try {
                java.util.List<com.pa.lcr.lcp.transport.TransportSnapshot> snaps =
                    activity.getMediaTransportManager().listSnapshots();
                if (snaps != null) {
                    for (com.pa.lcr.lcp.transport.TransportSnapshot s : snaps) {
                        if (s != null && transportKey.equals(s.key)
                                && s.status == com.pa.lcr.lcp.transport.TransportStatus.READY) {
                            ready = true;
                            break;
                        }
                    }
                }
                if (ready) break;
            } catch (Exception ignored) {}
        }

        if (!ready) {
            android.util.Log.w(TAG, "lancerLivraison: média non prêt après 10s");
            activity.runOnUiThread(() -> activity.toast("Média non prêt — réessayez"));
            logError(fSerialId, woNum, "MEDIA_NOT_READY", "Média non prêt après 10s");
            retournerFieldService(woNum, woIdGuid, "erreur_media",
                buildErrorJson("MEDIA_NOT_READY", "Média non prêt après 10s"));
            return;
        }

        // Démarrer oneshot/start
        int product = 1;
        double preset = 0.0;
        try { product = Integer.parseInt(produit);     } catch (Exception ignored) {}
        try { preset  = Double.parseDouble(presetStr); } catch (Exception ignored) {}

        final int fProduct = product;
        final double fPresetD = preset;
        final String fMac = mac != null ? mac : "";

        // ✅ Même vérification que le bouton C dans RegisterTabFragment (onClick btnC) :
        // comparer au DERNIER enregistrement du WO (getLatestForWo), pas une somme —
        // si ce dernier net >= preset (ou preset non fourni), demander confirmation
        // avant de démarrer une nouvelle livraison sur le même bon.
        try {
            com.pa.lcr.lcp.storage.LcrDeliveryStatusDb statusDb =
                new com.pa.lcr.lcp.storage.LcrDeliveryStatusDb(activity);
            com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.DeliveryRow existing =
                statusDb.getLatestForWo(woNum);

            if (existing != null && existing.type != null && !"ANNULATION".equals(existing.type)) {
                boolean livraisonComplete = (fPresetD <= 0 || existing.netL >= fPresetD);
                if (livraisonComplete) {
                    android.util.Log.w(TAG, "lancerLivraison: bon " + woNum
                        + " déjà complété (ticket #" + existing.ticketNo
                        + ", " + existing.netL + "L net, preset=" + fPresetD + "L) — confirmation requise");

                    final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                    final boolean[] continuer = {false};
                    final com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.DeliveryRow fExisting = existing;

                    activity.runOnUiThread(() -> {
                        new android.app.AlertDialog.Builder(activity)
                            .setTitle("Bon déjà complété")
                            .setMessage("Le bon " + woNum + " a déjà été livré"
                                + " (ticket #" + fExisting.ticketNo
                                + ", " + fExisting.netL + "L net).\n\n"
                                + "Voulez-vous créer une nouvelle livraison sur ce même bon ?")
                            .setPositiveButton("Continuer", (d, w) -> {
                                continuer[0] = true;
                                latch.countDown();
                            })
                            .setNegativeButton("Annuler", (d, w) -> {
                                continuer[0] = false;
                                latch.countDown();
                            })
                            .setCancelable(false)
                            .show();
                    });

                    try { latch.await(); } catch (InterruptedException ignored) {}

                    // ✅ Traçabilité: enregistrer le choix du chauffeur dans la table event,
                    // que ce soit Continuer ou Annuler — action explicite requise (accountability).
                    logEvent(fSerialId, woNum,
                        continuer[0] ? DeliveryLogStore.LEVEL_INFO : DeliveryLogStore.LEVEL_WARN,
                        continuer[0] ? "BON_DEJA_COMPLETE_CONTINUE" : "BON_DEJA_COMPLETE_ANNULE",
                        "ticket=" + fExisting.ticketNo + " net=" + fExisting.netL
                            + "L preset=" + fPresetD + "L — chauffeur a choisi "
                            + (continuer[0] ? "CONTINUER" : "ANNULER"),
                        null);

                    if (!continuer[0]) {
                        // ✅ Annuler = retour simple au tab, sans toast ni retour Field Service.
                        // Le chauffeur reste libre d'utiliser le tab (imprimer, custom print,
                        // voir le total, etc.) — s'il relance le bouton C, le même dialogue
                        // reviendra puisque rien n'a changé dans l'historique.
                        android.util.Log.i(TAG, "lancerLivraison: annulé par le chauffeur (bon déjà complété)");
                        activity.runOnUiThread(() -> activity.showPage(0));
                        return;
                    }
                    android.util.Log.i(TAG, "lancerLivraison: chauffeur confirme — nouvelle livraison sur bon déjà complété");
                }
            }
        } catch (Exception e) {
            android.util.Log.w(TAG, "lancerLivraison: erreur vérif bon complété — " + e.getMessage());
        }

        try {
            // ✅ FIX régression session 9 : réutiliser le DeliveryController déjà résolu
            // (CONNECTED, socket ouvert) au lieu de créer une nouvelle
            // MultiRegisterApiFacadeImpl — celle-ci ouvrait un second accès au transport
            // et entrait en conflit avec le socket déjà détenu, causant le
            // "Timeout waiting LCP response" même si le DC affichait CONNECTED.
            com.pa.lcr.lcp.RegisterSessionManager rsmOneshot =
                com.pa.lcr.lcp.RegisterSessionManager.get(activity);
            com.pa.lcr.lcp.DeliveryController controllerOneshot =
                rsmOneshot.getController(transportKey, node);

            if (controllerOneshot == null) {
                android.util.Log.w(TAG, "oneshot/start: controller introuvable pour transportKey="
                    + transportKey + " node=" + node);
                logError(fSerialId, woNum, "REGISTER_NOT_READY",
                    "Controller introuvable au moment du oneshot/start");
                retournerFieldService(woNum, woIdGuid, "erreur",
                    buildErrorJson("REGISTER_NOT_READY", "Controller introuvable au moment du oneshot/start"));
                return;
            }

            // ✅ FIX #2 : activer le transport en exclusivité avant l'oneshot.
            // getState()==CONNECTED n'est qu'un état FSM en cache — sans
            // activateExclusive(), le transport n'est pas garanti armé pour
            // l'écriture, ce qui produisait un échec quasi instantané
            // (~1s, pas un vrai timeout LCP) déguisé en "Timeout waiting LCP response".
            // Même pattern que RegisterTabFragment.lancerDepuisStore().
            try {
                activity.getMediaTransportManager()
                    .activateExclusive(transportKey, "DEEPLINK_ONESHOT");
            } catch (Exception ignored) {}

            com.pa.lcr.lcp.ApiResult r = controllerOneshot.api_deliveryOneShotStart(
                woNum, fProduct, fPresetD, null);

            // ✅ FIX #3 : la détection de timeout ne regardait que r.msg, qui vaut
            // toujours "Delivery OneShot: 0 - orchestration error" — le mot
            // "timeout" est dans r.data.detail (JSON imbriqué). Le retry ne se
            // déclenchait donc jamais, avant comme après le fix #1.
            boolean isTimeout = false;
            if (r != null && r.code == 0) {
                if (r.msg != null && r.msg.toLowerCase().contains("timeout")) isTimeout = true;
                if (!isTimeout && r.data != null) {
                    String detail = r.data.optString("detail", "");
                    if (detail.toLowerCase().contains("timeout")) isTimeout = true;
                }
            }

            if (isTimeout) {
                android.util.Log.w(TAG, "oneshot/start: timeout LCP — retry dans 1.5s");
                try { Thread.sleep(1500); } catch (Exception ignored) {}
                r = controllerOneshot.api_deliveryOneShotStart(
                    woNum, fProduct, fPresetD, null);
                android.util.Log.i(TAG, "oneshot/start retry: code=" + r.code + " msg=" + r.msg);
            }

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
                android.util.Log.w(TAG, "oneshot/start detail: " + (r.data != null ? r.data.toString() : "null"));
                logEvent(fSerialId, woNum, DeliveryLogStore.LEVEL_WARN,
                    "ONESHOT_ERROR", r.msg, r.data != null ? r.data.toString() : null);

                // ✅ Détecter ticket pending — ne pas retourner dans FSM
                // ds=0x0400 = ticketPending sur le registre
                boolean ticketPending = false;
                if (r.data != null) {
                    ticketPending = r.data.optBoolean("ticketPending", false)
                        || r.data.optInt("delStatus", 0) == 0x0400;
                }
                if (r.msg != null && r.msg.toLowerCase().contains("ticket")) {
                    ticketPending = true;
                }

                if (ticketPending) {
                    // Ticket pending — rester dans l'APK, alerter le chauffeur
                    android.util.Log.w(TAG, "oneshot/start: ticket pending — rester dans APK");
                    activity.runOnUiThread(() ->
                        activity.toast("⚠️ Ticket en attente — imprimez le ticket précédent avant de démarrer"));
                    activity.runOnUiThread(() -> activity.showPage(0));
                } else {
                    // Erreur orchestration — rester dans l'APK (pas de finish() pour éviter bounce FSM)
                    android.util.Log.w(TAG, "oneshot/start: orchestration error — rester dans APK");

                    // ✅ FIX : sur une vraie erreur TRANSPORT (BT/USB coupé), lancer le
                    // diagnostic avec le contexte complet du deep link (lancerDiagnosticForce)
                    // au lieu de juste toaster. Sans ça, seule la vérification périodique
                    // du tab (STATUS_B) détecte la coupure et relance un diagnostic — mais
                    // SANS connaître woNum/produit/preset/mac, donc SANS jamais relancer
                    // la livraison une fois le registre reconnecté (voir diagnostic()
                    // à 4 arguments dans RegisterConnectionHelper, qui passe null partout).
                    boolean errTransport = false;
                    if (r.data != null) {
                        String classErr = r.data.optString("class", "");
                        String levelErr = r.data.optString("level", "");
                        errTransport = "TRANSPORT".equalsIgnoreCase(classErr)
                            || "TRANSPORT".equalsIgnoreCase(levelErr);
                    }

                    if (errTransport) {
                        android.util.Log.w(TAG, "oneshot/start: erreur TRANSPORT — diagnostic + relance auto");
                        activity.runOnUiThread(() ->
                            activity.toast("⚠️ Registre déconnecté — reconnexion en cours..."));
                        new Thread(() -> new com.pa.lcrdemo.RegisterConnectionHelper(activity)
                            .lancerDiagnosticForce(transportKey, node, fSerialId, woNum,
                                woIdGuid, produit, presetStr, mac,
                                DeepLinkHandler.this)).start();
                    } else {
                        activity.runOnUiThread(() -> {
                            activity.toast("⚠️ Registre non disponible — vérifiez l'état du registre et réessayez");
                            activity.showPage(0);
                        });
                    }
                }
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

                            // ✅ Mauvais registre — anomalie opérationnelle
                            // Le chauffeur doit aviser le répartiteur
                            final String fSerialConnecte = ra.data != null
                                ? ra.data.optString("serial_id", "inconnu") : "inconnu";
                            final String fSerialAttendu  = serialId != null ? serialId : "inconnu";
                            final int    fNodeAttendu    = node;
                            final String fWoNumI         = woNum;

                            activity.runOnUiThread(() -> {
                                android.app.AlertDialog.Builder dlg =
                                    new android.app.AlertDialog.Builder(activity);
                                dlg.setTitle("⚠️ Mauvais registre détecté");
                                dlg.setMessage(
                                    "Le registre connecté ne correspond pas au bon de travail.\n\n"
                                    + "Attendu  : serial=" + fSerialAttendu
                                        + " · node=" + fNodeAttendu + "\n"
                                    + "Connecté : serial=" + fSerialConnecte + "\n\n"
                                    + "AVISEZ LE RÉPARTITEUR avant de continuer.\n\n"
                                    + "Il se peut que le camion soit équipé du mauvais registre "
                                    + "ou que la configuration du bon de travail soit incorrecte.");
                                dlg.setPositiveButton("J'ai avisé le répartiteur", (d, w) -> {
                                    // Le chauffeur confirme — rester dans l'APK
                                    activity.showPage(0);
                                });
                                dlg.setNegativeButton("Annuler", null);
                                dlg.setCancelable(false); // Force la lecture du message
                                dlg.show();
                            });
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
                    // ✅ Bloquer si un poll est déjà actif
                    if (!activePolls.isEmpty()) {
                        android.util.Log.w(TAG, "connectBt: poll déjà actif — ignoré");
                        activity.runOnUiThread(() -> activity.toast("↩️ Livraison déjà en cours"));
                        return;
                    }
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
                    logError(fSerialId != null ? fSerialId : "",
                        fWoNum != null ? fWoNum : "",
                        "REGISTER_NOT_AVAILABLE",
                        "Registre non disponible oneshot/start dans connectBtByMac");
                            // Rester dans l'APK — pas de finish() pour éviter bounce FSM
                            activity.runOnUiThread(() -> {
                                activity.toast("⚠️ Registre non disponible — vérifiez l'état du registre et réessayez");
                                activity.showPage(0);
                            });
                        }

                    } catch (Exception e) {
                        android.util.Log.e(TAG, "oneshot/start ERR: " + e.getMessage());
                        logError(fSerialId, woNum, "ONESHOT_EXCEPTION", e.getMessage());
                        retournerFieldService(woNum, woIdGuid, "erreur",
                            buildErrorJson("ONESHOT_EXCEPTION", e.getMessage()));
                    }
                } else {
                    android.util.Log.w(TAG, "Média non prêt après 5s");
                    logError(fSerialId != null ? fSerialId : serialId,
                        woNum, "BT_NOT_READY", "BT non prêt après 5s dans connectBtByMac");
                    activity.runOnUiThread(() -> activity.toast("BT non prêt — réessayez"));
                new Thread(() -> new com.pa.lcrdemo.RegisterConnectionHelper(activity)
                    .lancerDiagnosticForce("", node, serialId != null ? serialId : "", woNum,
                        woIdGuid, produit, presetStr, mac,
                        DeepLinkHandler.this)).start();
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

        // ✅ Déterminer le transportKey correct — BT ou USB
        // mac peut contenir un BT MAC ("00:01:95:87:72:A1") ou directement "USB"
        final String transportKey;
        if (mac != null && mac.toUpperCase().startsWith("USB")) {
            transportKey = MediaTransportManager.KEY_USB; // USB
        } else if (mac != null && mac.contains(":")) {
            transportKey = MediaTransportManager.btKey(mac); // BT:XX:XX:XX
        } else {
            transportKey = mac != null ? mac : "";
        }
        // ✅ Persister la livraison courante avec status STARTED
        try {
            ActiveDeliveryStore ads = new ActiveDeliveryStore(activity);
            ActiveDeliveryStore.ActiveDelivery existing = ads.load();
            int produitSave = (existing != null) ? existing.produit : 1;
            double presetSave = (existing != null) ? existing.preset : 0.0;
            ads.save(woNum, woIdGuid, jobId, mac, node, serialId,
                produitSave, presetSave, "STARTED");
        } catch (Exception ignored) {}

        btExec.execute(() -> {
            try {
                final boolean[] deliveryDone = {false};

                boolean hasSeenFlowing = false;
                boolean terminateSent  = false;
                String  lastState      = "";

                // ✅ Lire ticket# au démarrage pour détecter changement ultérieur
                String ticketNoAtStartTmp = "";
                try {
                    MultiRegisterApiFacadeImpl facadeT =
                        new MultiRegisterApiFacadeImpl(activity);
                    com.pa.lcr.lcp.ApiResult tickSnap = facadeT.api_deliveryJobGet(jobId);
                    if (tickSnap != null && tickSnap.data != null)
                        ticketNoAtStartTmp = tickSnap.data.optString("ticket_no", "");
                } catch (Exception ignored) {}
                final String ticketNoAtStart = ticketNoAtStartTmp;

                // ✅ Délai avant premier continue — USB est plus lent que BT
                if (transportKey.toUpperCase().startsWith("USB")) {
                    try { Thread.sleep(800); } catch (Exception ignored) {}
                }

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
                                com.pa.lcr.lcp.DeliveryController dc =
                                    com.pa.lcr.lcp.RegisterSessionManager.get(activity)
                                        .getController(transportKey, node);
                                if (dc != null) {
                                    dc.requestStatus();
                                    Thread.sleep(200);
                                    dc.requestLiveSample();
                                }
                            } catch (Exception ignored) {}
                            android.util.Log.i(TAG, "pollJob: ticket pending — sortie poll, opérateur gère via bouton A");
                            // Sortir du poll — l'opérateur gère via bouton A
                            return;
                        } else {
                            // CONNECTED sans ticket pending — envoyer continue avec retry
                            boolean continueOk = false;
                            for (int retry = 0; retry < 5; retry++) {
                                if (retry > 0) {
                                    try { Thread.sleep(600 + retry * 400L); } catch (Exception ignored) {}
                                }
                                MultiRegisterApiFacadeImpl facadeCont =
                                    new MultiRegisterApiFacadeImpl(activity);
                                com.pa.lcr.lcp.ApiResult rc =
                                    facadeCont.api_deliveryContinue(jobId, node);
                                android.util.Log.i(TAG,
                                    "job/continue [" + (retry+1) + "/5]: code="
                                    + (rc != null ? rc.code : "null")
                                    + " msg=" + (rc != null ? rc.msg : "null"));
                                logEvent(serialId, woNum, DeliveryLogStore.LEVEL_INFO,
                                    "JOB_CONTINUE",
                                    "retry=" + retry + " code=" + (rc != null ? rc.code : "null") +
                                    " msg=" + (rc != null ? rc.msg : "null"), null);
                                if (rc != null && rc.code == 1) {
                                    continueOk = true;
                                    break;
                                }
                            }
                            if (!continueOk) {
                                android.util.Log.w(TAG, "job/continue: échec après 5 tentatives — chauffeur prend charge");

                                // ✅ Détecter changement de ticket (impression entre-temps)
                                String ticketNow = "";
                                try {
                                    MultiRegisterApiFacadeImpl facadeT2 =
                                        new MultiRegisterApiFacadeImpl(activity);
                                    com.pa.lcr.lcp.ApiResult snap2 = facadeT2.api_deliveryJobGet(jobId);
                                    if (snap2 != null && snap2.data != null)
                                        ticketNow = snap2.data.optString("ticket_no", "");
                                } catch (Exception ignored) {}

                                final boolean ticketChanged = !ticketNoAtStart.isEmpty()
                                    && !ticketNow.isEmpty()
                                    && !ticketNoAtStart.equals(ticketNow);
                                final String fTicketNow = ticketNow;

                                // ✅ Logger événement dans LcrDeliveryStatusDb + Dataverse
                                final String fWoNum2 = woNum;
                                final String fWoId2  = woIdGuid;
                                activity.runOnUiThread(() -> {
                                    activity.toast(ticketChanged
                                        ? "⚠️ Connexion perdue — ticket changé (" + ticketNoAtStart
                                            + "→" + fTicketNow + "). Reconnectez et relancez manuellement."
                                        : "⚠️ Registre non joignable — reconnectez via Configure et relancez manuellement.");
                                    activity.showPage(0);
                                });

                                // Logger dans SQLite + Dataverse
                                btExec.execute(() -> {
                                    try {
                                        android.content.ContentValues cv =
                                            new android.content.ContentValues();
                                        cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_WO_NUM,    fWoNum2);
                                        cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_WO_ID_GUID, fWoId2);
                                        cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_TICKET_NO, fTicketNow);
                                        cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_TYPE,
                                            ticketChanged ? "TICKET_CHANGE" : "ERROR");
                                        cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_ERROR_CODE,
                                            "CONTINUE_FAILED");
                                        cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_ERROR_MSG,
                                            ticketChanged
                                                ? "Ticket changé pendant perte connexion: "
                                                    + ticketNoAtStart + "→" + fTicketNow
                                                : "job/continue échec après 5 tentatives");
                                        cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_SOURCE,    "SYSTEM");
                                        cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_STOP_TYPE, "LIVRAISON");
                                        cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_SYNC_STATUS,
                                            com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.SYNC_PENDING);
                                        new com.pa.lcr.lcp.storage.LcrDeliveryStatusDb(activity)
                                            .insertDelivery(cv);
                                    } catch (Exception ignored) {}
                                });

                                // ✅ Arrêter le poll — chauffeur prend charge
                                // ActiveDeliveryStore conserve woNum/woIdGuid/jobId pour reprise
                                logEvent(serialId, woNum, DeliveryLogStore.LEVEL_WARN,
                                    ticketChanged ? "TICKET_CHANGE" : "CONTINUE_FAILED",
                                    ticketChanged
                                        ? "Ticket " + ticketNoAtStart + "→" + fTicketNow
                                        : "job/continue échec après 5 tentatives", null);
                                return; // Sortir du poll
                            }
                        }
                    } else {
                        boolean continueOk2 = false;
                        for (int retry = 0; retry < 5; retry++) {
                            if (retry > 0) {
                                try { Thread.sleep(600 + retry * 400L); } catch (Exception ignored) {}
                            }
                            MultiRegisterApiFacadeImpl facadeCont =
                                new MultiRegisterApiFacadeImpl(activity);
                            com.pa.lcr.lcp.ApiResult rc =
                                facadeCont.api_deliveryContinue(jobId, node);
                            android.util.Log.i(TAG,
                                "job/continue [" + (retry+1) + "/5]: code="
                                + (rc != null ? rc.code : "null")
                                + " msg=" + (rc != null ? rc.msg : "null"));
                            logEvent(serialId, woNum, DeliveryLogStore.LEVEL_INFO,
                                "JOB_CONTINUE",
                                "retry=" + retry + " code=" + (rc != null ? rc.code : "null") +
                                " msg=" + (rc != null ? rc.msg : "null"), null);
                            if (rc != null && rc.code == 1) {
                                continueOk2 = true;
                                break;
                            }
                        }
                        if (!continueOk2) {
                            android.util.Log.w(TAG, "job/continue: échec après 5 tentatives — chauffeur prend charge");

                            // ✅ Détecter changement de ticket
                            String ticketNow2 = "";
                            try {
                                MultiRegisterApiFacadeImpl facadeT3 =
                                    new MultiRegisterApiFacadeImpl(activity);
                                com.pa.lcr.lcp.ApiResult snap3 = facadeT3.api_deliveryJobGet(jobId);
                                if (snap3 != null && snap3.data != null)
                                    ticketNow2 = snap3.data.optString("ticket_no", "");
                            } catch (Exception ignored) {}

                            final boolean ticketChanged2 = !ticketNoAtStart.isEmpty()
                                && !ticketNow2.isEmpty()
                                && !ticketNoAtStart.equals(ticketNow2);
                            final String fTicketNow2 = ticketNow2;
                            final String fWoNum3 = woNum;
                            final String fWoId3  = woIdGuid;

                            activity.runOnUiThread(() -> {
                                activity.toast(ticketChanged2
                                    ? "⚠️ Connexion perdue — ticket changé (" + ticketNoAtStart
                                        + "→" + fTicketNow2 + "). Reconnectez et relancez manuellement."
                                    : "⚠️ Registre non joignable — reconnectez via Configure et relancez manuellement.");
                                activity.showPage(0);
                            });

                            btExec.execute(() -> {
                                try {
                                    android.content.ContentValues cv =
                                        new android.content.ContentValues();
                                    cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_WO_NUM,    fWoNum3);
                                    cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_WO_ID_GUID, fWoId3);
                                    cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_TICKET_NO, fTicketNow2);
                                    cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_TYPE,
                                        ticketChanged2 ? "TICKET_CHANGE" : "ERROR");
                                    cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_ERROR_CODE,
                                        "CONTINUE_FAILED");
                                    cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_ERROR_MSG,
                                        ticketChanged2
                                            ? "Ticket changé: " + ticketNoAtStart + "→" + fTicketNow2
                                            : "job/continue échec après 5 tentatives");
                                    cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_SOURCE,    "SYSTEM");
                                    cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_STOP_TYPE, "LIVRAISON");
                                    cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_SYNC_STATUS,
                                        com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.SYNC_PENDING);
                                    new com.pa.lcr.lcp.storage.LcrDeliveryStatusDb(activity)
                                        .insertDelivery(cv);
                                } catch (Exception ignored) {}
                            });

                            logEvent(serialId, woNum, DeliveryLogStore.LEVEL_WARN,
                                ticketChanged2 ? "TICKET_CHANGE" : "CONTINUE_FAILED",
                                ticketChanged2
                                    ? "Ticket " + ticketNoAtStart + "→" + fTicketNow2
                                    : "job/continue échec après 5 tentatives", null);
                            return; // Sortir du poll
                        }
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

                        // ✅ CONNECTED après FLOWING — le registre a terminé seul (preset atteint)
                        // Ne pas envoyer job/terminate — le registre a déjà imprimé.
                        // Terminer directement → retour Field Service.
                        if ("CONNECTED".equals(state) && hasSeenFlowing && !terminateSent) {
                            if (deliveryDone[0]) return;
                            deliveryDone[0] = true;
                            String extraJson = (r.data != null) ? r.data.toString() : "{}";
                            android.util.Log.i(TAG,
                                "Livraison terminée (CONNECTED preset atteint) — " + extraJson);
                            logDeliveryEnd(serialId, woNum, jobId, "DONE", extraJson, null);
                            onDeliveryEnded(woNum, woIdGuid, extraJson);
                            return;
                        }

                        // ✅ RUNNING_PAUSED — NE PAS terminer automatiquement.
                        // L'opérateur doit cliquer "Terminer" dans le tab.
                        // Le terminate automatique causait une impression non voulue
                        // quand la venne était coupée manuellement avant le preset.

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

        // ✅ Écrire dans LcrDeliveryStatusDb (offline safe) avant retour FSM
        btExec.execute(() -> {
            try {
                JSONObject d      = new JSONObject(extraJson != null ? extraJson : "{}");
                JSONObject result = d.optJSONObject("result");
                JSONObject tick   = d.optJSONObject("tick");

                double netL      = 0, grossL   = 0;
                double deltaNet  = 0, deltaGross = 0;
                String ticketNo  = "", saleNo = "";
                String startUtc  = "", endUtc = "";
                double durationS = 0;
                int    produitNo = 0;
                String presetStatus = "EXACT";

                if (result != null) {
                    netL       = result.optDouble("fs_net_l",    0);
                    grossL     = result.optDouble("fs_gross_l",  0);
                    deltaNet   = result.optDouble("net_delta_l", 0);
                    deltaGross = result.optDouble("gross_delta_l", 0);
                    ticketNo   = result.optString("ticket_no",   "");
                    saleNo     = result.optString("sale_no",     "");
                    startUtc   = result.optString("start_utc",   "");
                    endUtc     = result.optString("end_utc",     "");
                    durationS  = result.optDouble("duration_s",  0);
                    produitNo  = result.optInt("product_number", 0);
                }
                // Fallback tick
                if ((netL == 0 || grossL == 0) && tick != null) {
                    double tn = tick.optDouble("net", 0);
                    double tg = tick.optDouble("gross", 0);
                    if (tn > 0) netL   = tn;
                    if (tg > 0) grossL = tg;
                }

                // preset_status
                double presetL = d.optDouble("preset_requested", 0);
                if (presetL > 0) {
                    if (Math.abs(netL - presetL) < 0.2)       presetStatus = "EXACT";
                    else if (netL < presetL)                   presetStatus = "UNDER";
                    else                                       presetStatus = "OVER";
                }

                // lcrnode + serialId depuis contexte livraison courante
                int lcrnode = currentNode;
                String serialId = currentSerialId;

                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_WO_NUM,       woNum != null ? woNum : "");
                cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_WO_ID_GUID,   woIdGuid != null ? woIdGuid.replace("{","").replace("}","") : "");
                cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_SERIAL_ID,    serialId);
                cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_LCRNODE,      lcrnode);
                cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_PRODUIT_NO,   produitNo);
                cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_TICKET_NO,    ticketNo);
                cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_SALE_NO,      saleNo);
                cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_NET_L,        netL);
                cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_GROSS_L,      grossL);
                cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_DELTA_NET_L,  deltaNet);
                cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_DELTA_GROSS_L,deltaGross);
                cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_PRESET_L,     presetL);
                cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_PRESET_STATUS,presetStatus);
                cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_START_UTC,    startUtc);
                cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_END_UTC,      endUtc);
                cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_DURATION_S,   durationS);
                cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_TYPE,
                    com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.TYPE_ORIGINAL);
                cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_SOURCE,       "REGISTRE");
                cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_STOP_TYPE,    "LIVRAISON");
                cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_SYNC_STATUS,
                    com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.SYNC_PENDING);
                cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_PAYLOAD_JSON, extraJson);

                com.pa.lcr.lcp.storage.LcrDeliveryStatusDb lcrDb =
                    new com.pa.lcr.lcp.storage.LcrDeliveryStatusDb(activity);
                long localId = lcrDb.insertDelivery(cv);
                android.util.Log.i(TAG, "LcrDeliveryStatusDb: id=" + localId
                    + " wo=" + woNum + " net=" + netL + " gross=" + grossL
                    + " ticket=" + ticketNo + " duration=" + durationS);

                // ✅ mettreAJourFieldService APRÈS l'insert — garantit que getAllForWo()
                // voit la livraison courante dans le payload consolidé
                mettreAJourFieldService(woNum, woIdGuid, "termine", extraJson);

            } catch (Exception e) {
                android.util.Log.e(TAG, "LcrDeliveryStatusDb ERR: " + e.getMessage());
            }
        });
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

    /**
     * Met à jour FSM (patchDataverse + lastResult) sans retourner dans FSM.
     * Appelé depuis onDeliveryEnded() — le chauffeur reste dans l'APK.
     */
    private void mettreAJourFieldService(String woNum, String woIdGuid,
                                          String status, String extraJson) {
        try {
            String net = "", gross = "", ticket = "";
            try {
                JSONObject d = new JSONObject(extraJson != null ? extraJson : "{}");
                JSONObject result = d.optJSONObject("result");
                if (result != null) {
                    net    = String.valueOf(result.optDouble("fs_net_l",   0));
                    gross  = String.valueOf(result.optDouble("fs_gross_l", 0));
                    ticket = result.optString("ticket_no", "");
                    boolean stale  = d.optBoolean("stale", false);
                    double fsNet   = result.optDouble("fs_net_l",   0);
                    double fsGross = result.optDouble("fs_gross_l", 0);
                    if (stale || fsNet == 0 || fsGross == 0) {
                        JSONObject tick = d.optJSONObject("tick");
                        if (tick != null) {
                            double tn = tick.optDouble("net",   0);
                            double tg = tick.optDouble("gross", 0);
                            if (tn > 0 && tg > 0) { net = String.valueOf(tn); gross = String.valueOf(tg); }
                        }
                    }
                } else {
                    net   = String.valueOf(d.optDouble("net",   0));
                    gross = String.valueOf(d.optDouble("gross", 0));
                }
            } catch (Exception ignored) {}

            String woGuid = (woIdGuid != null && !woIdGuid.isEmpty()) ? woIdGuid : "";
            woGuid = woGuid.replace("{", "").replace("}", "");

            // Sauvegarder lastResult
            JSONObject lastResult = new JSONObject();
            lastResult.put("wonum",  woNum  != null ? woNum  : "");
            lastResult.put("woid",   woGuid);
            lastResult.put("net",    net);
            lastResult.put("gross",  gross);
            lastResult.put("ticket", ticket);
            lastResult.put("status", status != null ? status : "ok");
            lastResult.put("ts",     System.currentTimeMillis());
            if (extraJson != null) {
                try { lastResult.put("payload", new JSONObject(extraJson)); } catch (Exception ignored) {}
            }
            lastResultJson   = lastResult.toString();
            lastResultWoNum  = woNum;
            lastResultWoGuid = woGuid;
            lastResultTs     = System.currentTimeMillis();
            com.pa.lcrdemo.LcrHttpService.lastResultJson = lastResult.toString();
            android.util.Log.i(TAG, "last-result sauvegardé: wonum=" + woNum
                + " net=" + net + " gross=" + gross + " ticket=" + ticket);

            // Patch Dataverse (msdyn_workordersummary) sans finish()
            final String fNet = net, fGross = gross, fTicket = ticket;
            final String fGuid = woGuid, fWoNum = woNum, fStatus = status;
            patchDataverse(fGuid, fWoNum, fNet, fGross, fTicket, fStatus);

        } catch (Exception e) {
            android.util.Log.e(TAG, "mettreAJourFieldService ERR: " + e.getMessage());
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

        // ✅ Bloquer si net=0 et status=erreur — ne jamais écraser avec des données vides
        double netVal = 0;
        try { netVal = net != null ? Double.parseDouble(net) : 0; } catch (Exception ignored) {}
        if (netVal == 0 && status != null && status.startsWith("erreur")) {
            android.util.Log.w(TAG, "patchDataverse: net=0 + status=" + status + " — ignoré");
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
