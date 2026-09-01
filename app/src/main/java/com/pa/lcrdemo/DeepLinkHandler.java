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
 * D eepLinkHandler — gestion complète du flux deep link Field Service ↔ APK.
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

    /**
     * ✅ FIX CRITIQUE (13 août 2026, demande Paul — crash réel confirmé dans
     * un vrai logcat : "FATAL EXCEPTION: main —
     * java.util.concurrent.RejectedExecutionException... rejected from
     * ThreadPoolExecutor@...[Terminated...]" à DeepLinkHandler.java:2189,
     * dans un callback MSAL onSuccess) — trouvé : MainActivity.onDestroy()
     * appelle btExec.shutdownNow() (correctif du 6 août pour une vraie fuite
     * de threads sur recréation d'Activity) — mais un callback asynchrone
     * MSAL (vrai appel réseau, peut prendre plusieurs secondes) peut encore
     * être EN VOL au moment où l'Activity se détruit (confirmé aujourd'hui :
     * recréation d'Activity fréquente en arrière-plan). Quand ce callback
     * revient et essaie safeExecute(), l'exécuteur est déjà fermé —
     * crash complet de l'app au lieu d'un échec silencieux et géré.
     * Enveloppe unique : tous les appels safeExecute() du fichier
     * passent maintenant par ici — capture RejectedExecutionException
     * spécifiquement, log un avertissement au lieu de planter, protège
     * automatiquement tout futur appel ajouté à ce fichier aussi.
     */
    private void safeExecute(Runnable task) {
        try {
            btExec.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            android.util.Log.w(TAG, "safeExecute: btExec déjà fermé (Activity probablement recréée/détruite "
                + "pendant un appel asynchrone en vol) — tâche abandonnée proprement au lieu de planter l'app");
        }
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
            // ✅ N-Port TCP — Field Service peut fournir directement l'IP:port du
            // N-Port, exactement comme "btmac" pour le Bluetooth. Si fourni,
            // connexion directe déterministe (pas de scan réseau nécessaire).
            String nportIp     = data.getQueryParameter("nportip");
            String nportPortStr = data.getQueryParameter("nportport");

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

            Integer nportPort = null;
            try { if (nportPortStr != null) nportPort = Integer.parseInt(nportPortStr); }
            catch (Exception ignored) {}

            android.util.Log.i(TAG,
                "Livraison — WO=" + woNum + " BT=" + btMac +
                " serial=" + serialId + " node=" + lcrnode +
                " nportIp=" + nportIp + " nportPort=" + nportPort +
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
            } catch (Exception e) {
                // ✅ FIX (4 août 2026, demande Paul) — si cette sauvegarde échoue,
                // le flux "reprendre une livraison en attente" (RegisterTabFragment
                // .checkPendingDeliveryForThisRegister → bouton "Lancer la
                // livraison") n'a plus rien à lire — silencieusement aveugle sans
                // ce log.
                com.pa.lcr.lcp.log.LogBus.err(
                    lcrnode != null ? lcrnode : 250, "DeepLinkHandler.ActiveDeliveryStore.save", e);
            }

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
            final String fNportIp = nportIp;
            final int fNportPort = (nportPort != null ? nportPort : com.pa.lcr.lcp.api.WifiRegisterScanController.DEFAULT_RAW_PORT);

            // ✅ Résolution transport universel: USB / BT / TCP
            // Chercher d'abord un transport actif pour ce node/serial via RSM.
            // Si trouvé → utiliser directement. Si non → fallback BT si MAC fourni.
            safeExecute(() -> {
                try {
                    com.pa.lcr.lcp.RegisterSessionManager rsm =
                        com.pa.lcr.lcp.RegisterSessionManager.get(activity);

                    // ✅ FIX (UX) : retour visuel IMMÉDIAT dès l'entrée dans la
                    // résolution — avant, le seul "🔌 Connexion au registre..."
                    // n'apparaissait qu'APRÈS resolveOrCreateForNode() (qui peut
                    // sonder plusieurs transports en silence, ex: tentatives TCP
                    // qui échouent avant de basculer sur BT). Le chauffeur voyait
                    // "📦 Livraison" puis rien pendant un moment avant "connexion".
                    activity.runOnUiThread(() -> activity.toast("🔍 Recherche du registre..."));

                    if (fSerialId != null && !fSerialId.isEmpty()) {
                        rsm.bindExpectedSerial(fNode, fSerialId);
                    }

                    // ✅ Étape 0 (demandée) : AVANT de sonder quoi que ce soit,
                    // valider si le node+#série demandés sont déjà ce qui tourne
                    // sur le média ACTUELLEMENT ACTIF. Si oui → réutilisation
                    // immédiate, on saute complètement nportip/resolveOrCreateForNode
                    // /auto-connect — aucun autre transport n'est touché du tout.
                    String activeMatch = activity.resolveIfActiveMatches(fNode, fSerialId);
                    if (activeMatch != null) {
                        android.util.Log.i(TAG, "Deep link: média actif correspond déjà (" + activeMatch
                                + ") — aucune résolution/scan nécessaire");
                        lancerLivraison(activeMatch, fNode, fSerialId, woNum, woIdGuid, fProduit, fPresetStr, fBtMac);
                        return;
                    }

                    // ✅ N-Port fourni directement par Field Service (nportip/nportport)
                    // → connexion TCP déterministe AVANT toute résolution/scan.
                    // Node + #série restent liés dans tous les cas via bindExpectedSerial
                    // ci-dessus, peu importe si cette connexion directe réussit ou non
                    // (le fallback USB/BT/scan-auto plus bas prend le relais sinon).
                    //
                    // ✅ FIX : ne tenter le TCP QUE si cette livraison ne cible pas
                    // déjà explicitement du BT (fBtMac présent). Avant ce correctif,
                    // si Field Service incluait nportip par défaut/résiduel dans
                    // CHAQUE deep link (même pour des livraisons BT), un onglet TCP
                    // se créait systématiquement, peu importe le vrai média visé —
                    // un onglet ne doit exister QUE si un vrai registre est trouvé
                    // ET que ce registre est réellement celui visé par CETTE livraison.
                    boolean btIsTarget = fBtMac != null && !fBtMac.trim().isEmpty();
                    if (!btIsTarget && fNportIp != null && !fNportIp.trim().isEmpty()) {
                        try {
                            android.util.Log.i(TAG, "Deep link: N-Port fourni directement — "
                                + fNportIp + ":" + fNportPort + " node=" + fNode + " serial=" + fSerialId);
                            com.pa.lcr.lcp.api.WifiRegisterScanController tcpCtl =
                                new com.pa.lcr.lcp.api.WifiRegisterScanController(
                                    activity, activity.getMediaTransportManager());
                            com.pa.lcr.lcp.ApiResult rtcp = tcpCtl.connectManual(fNportIp.trim(), fNportPort);
                            android.util.Log.i(TAG, "Deep link: connexion N-Port directe → "
                                + (rtcp != null ? rtcp.msg : "null"));
                        } catch (Exception e) {
                            android.util.Log.w(TAG, "Deep link: connexion N-Port directe échouée: " + e.getMessage());
                            try { com.pa.lcr.lcp.log.LogBus.err(0, "DeepLinkHandler.nPortDirect", e); } catch (Exception ignored) {}
                        }
                    } else if (btIsTarget && fNportIp != null && !fNportIp.trim().isEmpty()) {
                        android.util.Log.i(TAG, "Deep link: nportip fourni mais btmac aussi présent — "
                            + "cette livraison cible BT, connexion TCP ignorée");
                    }

                    com.pa.lcr.lcp.DeliveryController dc =
                        rsm.resolveOrCreateForNode(fNode, 255);

                    if (dc != null) {
                        // ✅ Attendre que le DC soit CONNECTED (probeAndIdentify terminé)
                        // max 15s, 200ms par itération
                        activity.runOnUiThread(() -> activity.toast("🔌 Connexion au registre..."));
                        for (int w = 0; w < 75; w++) {
                            if (dc.getState() == com.pa.lcr.lcp.DeliveryState.CONNECTED) break;
                            // ✅ FIX (UX) : avant, silence total pendant jusqu'à 15s après
                            // le seul toast initial — "temps mort" perçu par le chauffeur,
                            // sans savoir si l'app est bloquée ou travaille encore.
                            // Retour visuel toutes les ~3s (15 x 200ms) pendant l'attente.
                            if (w > 0 && w % 15 == 0) {
                                final int fSec = (w * 200) / 1000;
                                activity.runOnUiThread(() -> activity.toast("🔌 Connexion au registre... (" + fSec + "s)"));
                            }
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
                                        if (w > 0 && w % 15 == 0) {
                                            final int fSec2 = (w * 200) / 1000;
                                            activity.runOnUiThread(() -> activity.toast("🔌 Registre trouvé, connexion... (" + fSec2 + "s)"));
                                        }
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
                        activity.runOnUiThread(() -> activity.toast("📡 Connexion au registre en cours..."));
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
                                    if (w > 0 && w % 15 == 0) {
                                        final int fSec3 = (w * 200) / 1000;
                                        activity.runOnUiThread(() -> activity.toast("🔌 Connexion au registre... (" + fSec3 + "s)"));
                                    }
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
                                    + "n'est pas détecté sur USB-C, Bluetooth, ni réseau TCP (N-Port).\n\n"
                                    + "1. Branchez le câble USB-C du registre\n"
                                    + "   — ou —\n"
                                    + "2. Activez le Bluetooth et connectez le registre\n"
                                    + "   — ou —\n"
                                    + "3. Vérifiez la connexion réseau Wi-Fi vers le N-Port (TCP)\n\n"
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
                    android.util.Log.e(TAG, "Résolution transport ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "DeepLinkHandler.Résolution", e); } catch (Exception ignored) {}
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

    // ✅ CORRIGÉ (28 août 2026, demande Paul) — skipConnexionCheck ne fait
    // plus rien : la pré-vérification qu'il permettait de sauter
    // (api_registerValidate avant tout armement) a été retirée entièrement
    // — voir commentaire plus bas dans le corps de la méthode. Paramètre
    // conservé pour compatibilité avec tous les appelants existants
    // (RegisterConnectionHelper, MainActivity), sans effet.
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

        // ✅ CORRIGÉ (28 août 2026, demande Paul — "si je suis dans le tab
        // et que je fais new c il doit directement armer la livraison,
        // juste si je suis en erreur de communication avec le registre
        // qu'il faut partir le diagnostic et continuer le démarrage de
        // livraison") — RETIRÉ : cette pré-vérification (api_registerValidate,
        // une vraie communication LCP) tournait AVANT MÊME de tenter
        // d'armer, à chaque appel — redondante avec le mécanisme qui
        // existe déjà plus bas (errTransport, autour de l'appel réel à
        // api_deliveryOneShotStart) : si l'armement échoue avec une VRAIE
        // erreur de transport, le diagnostic se déclenche déjà, et
        // RegisterConnectionHelper rappelle lancerLivraison() une fois
        // reconnecté. On a déjà un monitoring de connexion constant
        // (tick rapide, keep-alive) — si l'UI affiche connecté, c'est
        // qu'il l'est. Le modèle voulu : armement direct, diagnostic
        // SEULEMENT sur un échec réel constaté à l'armement lui-même —
        // pas une double vérification avant.

        // Ouvrir/activer le tab
        final String fSerialId = serialId != null ? serialId : "";
        final String fWoNum = woNum;
        final String fProduit = produit;
        final String fPresetStr = presetStr;

        // ✅ (4 août 2026, demande Paul : "retarder le deeplink si le tab n'existait
        // pas avant — démarrer la livraison doit être fait après le scan si le tab
        // vient d'être créé") — déterminer AVANT upsertRegisterTabFromScan() si ce
        // tab existe déjà, pour savoir plus bas s'il faut attendre la fin du scan
        // auto produits (RegisterTabFragment.onTabActivated) avant de démarrer.
        boolean tabWasNewBeforeThisCall;
        try {
            String mediaShortCheck = activity.mediaShortFromTransportKey(transportKey);
            String tabKeyCheck = activity.tabKeyOf(mediaShortCheck, node, fSerialId);
            boolean tabExistsInMap = activity.tabExists(tabKeyCheck);
            // ✅ FIX CRITIQUE (12 août 2026, demande Paul — "corrige-moi les 4
            // trous", trou #2 : "tab neuf" apparaissait même sur une 2e
            // livraison) — même cause de fond que resolveIfActiveMatches
            // (MainActivity.java) : tabsByKey est en mémoire seulement, vide
            // après une recréation d'Activity. Avant de conclure "tab neuf",
            // vérifier aussi si une vraie session vivante existe déjà pour ce
            // node+#série dans RegisterSessionManager (singleton applicatif,
            // survit à la recréation) — si oui, ce n'est PAS un tab neuf,
            // même si tabsByKey (cette instance) ne le connaît pas encore.
            boolean sessionSurvivante = false;
            if (!tabExistsInMap && fSerialId != null && !fSerialId.isEmpty()) {
                try {
                    com.pa.lcr.lcp.DeliveryController dcSurv =
                        com.pa.lcr.lcp.RegisterSessionManager.get(activity)
                            .findLiveControllerByNodeAndSerial(node, fSerialId);
                    sessionSurvivante = (dcSurv != null);
                } catch (Exception ignoredSurv) {}
            }
            tabWasNewBeforeThisCall = !tabExistsInMap && !sessionSurvivante;
            android.util.Log.i(TAG, "lancerLivraison: vérif tab existant — transportKey(reçu)=\"" + transportKey
                + "\" mediaShortCheck=\"" + mediaShortCheck + "\" tabKeyCheck=\"" + tabKeyCheck
                + "\" tabExistsInMap=" + tabExistsInMap + " sessionSurvivante=" + sessionSurvivante
                + " tabWasNew=" + tabWasNewBeforeThisCall
                + " tabsByKey.keys=" + activity.debugDumpTabKeys());
        } catch (Exception e) {
            tabWasNewBeforeThisCall = false;
        }
        final boolean fTabWasNew = tabWasNewBeforeThisCall;

        activity.runOnUiThread(() -> {
            try {
                if (!transportKey.isEmpty()) {
                    activity.onConfigureMediaActivated(transportKey, "DEEPLINK");
                    // ✅ Détection isLc3 centralisée — un seul mécanisme partagé
                    // (voir MainActivity.resolveIsLc3), plus de logique dupliquée ici.
                    // ✅ CORRIGÉ (27 août 2026, demande Paul — "on devrait déjà
                    // tout avoir avant d'armer la livraison... rien d'autre
                    // ne devrait s'exécuter") — trouvé, confirmé avec
                    // certitude par log réel : focus=true forcé ICI,
                    // inconditionnellement, redéclenchait TOUJOURS
                    // showRegisterFragmentByKey() → onTabActivated() →
                    // runInitSequence() AU COMPLET, même quand on clique NEW
                    // depuis un tab DÉJÀ actif — relançant REGISTRE/PRODUIT/
                    // PRESET/LIVE/RETOUR_WO pile au moment où la livraison
                    // s'arme. fTabWasNew (déjà calculé juste au-dessus)
                    // distingue exactement ce cas : ne force la réactivation
                    // complète QUE si le tab vient vraiment d'être créé,
                    // jamais s'il existait déjà.
                    activity.upsertRegisterTabFromScan(transportKey, node, 255, fSerialId, fTabWasNew,
                            activity.resolveIsLc3(transportKey, node));

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

        // ✅ FIX (4 août 2026, demande Paul : "j'arrive de deeplink, je vois le
        // tab usb devenir (off)") — refreshAllTabsMediaStatus() était appelé
        // une seule fois, juste après upsertRegisterTabFromScan() (ligne ~536),
        // AVANT cette boucle d'attente — donc quasi toujours avant que le port
        // USB soit réellement ouvert (énumération/permission USB plus lente que
        // BT). Le tab affichait "(OFF)" à ce moment-là et rien ne le
        // rafraîchissait ensuite, même une fois le média confirmé READY juste
        // au-dessus. On rafraîchit maintenant l'affichage pour refléter l'état
        // réel une fois qu'on SAIT que le média est prêt.
        activity.runOnUiThread(activity::refreshAllTabsMediaStatus);

        // ✅ (4 août 2026, demande Paul) — si ce tab vient d'être créé par cet
        // appel (n'existait pas avant), attendre que le scan auto produits
        // (RegisterTabFragment.onTabActivated → autoScanProduitsSiNecessaire)
        // soit terminé AVANT de démarrer la livraison. Un tab déjà existant
        // (donc déjà scanné lors d'une activation précédente) ne subit AUCUN
        // délai supplémentaire. Max 10s d'attente — best-effort, ne bloque
        // jamais indéfiniment si le fragment n'est pas trouvé ou ne répond pas.
        if (fTabWasNew) {
            boolean scanTermine = false;
            for (int i = 0; i < 20; i++) {
                try {
                    String mediaShort = activity.mediaShortFromTransportKey(transportKey);
                    String tabKey = activity.tabKeyOf(mediaShort, node, fSerialId);
                    Fragment f = activity.getSupportFragmentManager()
                        .findFragmentByTag("regtab_" + tabKey);
                    if (!(f instanceof RegisterTabFragment)
                            || !((RegisterTabFragment) f).isAutoProductScanBusy()) {
                        scanTermine = true;
                        break;
                    }
                } catch (Exception ignored) {
                    scanTermine = true; // best-effort — ne jamais bloquer sur une erreur ici
                    break;
                }
                try { Thread.sleep(500); } catch (Exception ignored) {}
            }
            android.util.Log.i(TAG, "lancerLivraison: attente scan auto produits (tab neuf) — "
                + (scanTermine ? "terminé" : "timeout 10s, poursuite quand même"));
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
            com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.DeliveryRow existing;
            try {
                existing = statusDb.getLatestForWo(woNum);
            } finally {
                try { statusDb.close(); } catch (Exception ignored) {}
            }

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
            try { com.pa.lcr.lcp.log.LogBus.err(0, "DeepLinkHandler.lancerLivraison.verifBonComplete", e); } catch (Exception ignored) {}
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
            //
            // ✅ FIX (4 août 2026, demande Paul : "quand j'arrive de Deeplink,
            // j'ai un trouble avec le transport usb") — activateExclusive()
            // retourne false si le TransportHandle n'est pas encore enregistré
            // (cas fréquent en USB, énumération plus lente qu'en BT — le check
            // "média READY" plus haut peut réussir avant que le handle exclusif
            // soit prêt). Avant ce fix, un retour false OU une exception étaient
            // tous les deux avalés silencieusement, et le code continuait quand
            // même vers api_deliveryOneShotStart() — garanti d'échouer
            // immédiatement via GuardedTransportIo.requireActive(), déguisé en
            // "orchestration error" sans aucun indice sur la vraie cause.
            // Maintenant : jusqu'à 3 tentatives (150ms d'écart, l'énumération USB
            // peut prendre un instant), loggé si ça échoue quand même, mais on
            // continue toujours vers l'oneshot ensuite (best-effort, comme avant)
            // — seule la visibilité change.
            boolean exclusiveOk = false;
            // ✅ FIX (4 août 2026, demande Paul : "on ne doit jamais oublier
            // l'arrivée du deeplink peu importe le transport trouvé") — avant
            // de voler l'exclusivité ici, vérifier qu'aucune livraison n'est
            // active sur un AUTRE registre déjà en cours ailleurs (même garde
            // que MainActivity.ensureActiveTransport, exposée publiquement
            // via isTransportSwitchSafe() pour que ce chemin direct ne puisse
            // plus le contourner).
            if (!activity.isTransportSwitchSafe(transportKey, "DEEPLINK_ONESHOT")) {
                android.util.Log.w(TAG, "oneshot/start: activateExclusive() BLOQUÉ — livraison active "
                    + "sur un autre registre, transportKey=" + transportKey + " n'est pas le même registre");
                logError(fSerialId, woNum, "TRANSPORT_SWITCH_BLOCKED",
                    "Livraison active sur un autre registre — bascule de transport refusée");
                retournerFieldService(woNum, woIdGuid, "erreur",
                    buildErrorJson("TRANSPORT_SWITCH_BLOCKED",
                        "Une livraison est déjà en cours sur un autre registre"));
                return;
            }
            for (int i = 0; i < 3 && !exclusiveOk; i++) {
                try {
                    exclusiveOk = activity.getMediaTransportManager()
                        .activateExclusive(transportKey, "DEEPLINK_ONESHOT");
                } catch (Exception e) {
                    com.pa.lcr.lcp.log.LogBus.err(node,
                        "DeepLinkHandler.activateExclusive[DEEPLINK_ONESHOT] tentative " + (i + 1), e);
                }
                if (!exclusiveOk) { try { Thread.sleep(150); } catch (Exception ignored) {} }
            }
            if (!exclusiveOk) {
                android.util.Log.w(TAG, "oneshot/start: activateExclusive() a échoué après 3 tentatives"
                    + " pour transportKey=" + transportKey + " — l'oneshot va probablement échouer"
                    + " (transport pas encore armé)");
            }

            // ✅ FIX CRITIQUE (12 août 2026, demande Paul — "corrige-moi les 4
            // trous", trou #3 : registre occupé (rc=0x26 en boucle) au moment
            // d'ARMED, confirmé PAS causé par le registre lui-même — ton propre
            // script isolé reste rapide sur le même matériel) — trouvé : des
            // activités de fond (scan produits, WO-DETECT, CUMUL-WO) ont déjà pu
            // mettre des commandes dans la file du registre juste AVANT ce point
            // — le registre les traite encore quand ARMED arrive une fraction de
            // seconde plus tard. Les gardes d'état existantes (PRESTART/
            // RUNNING_FLOWING) ne couvrent pas cette fenêtre puisqu'on n'est PAS
            // encore en PRESTART à ce moment précis — c'est une question de
            // séquencement, pas d'état. Corrigé : attente courte et bornée (max
            // 2s) si un scan produits est en vol (scanInProgress, ajouté plus tôt
            // aujourd'hui) — laisse sa file se vider avant d'ajouter le vrai
            // démarrage par-dessus, au lieu de les faire compétitionner.
            for (int waitScan = 0; waitScan < 20; waitScan++) {
                if (!controllerOneshot.scanInProgress) break;
                try { Thread.sleep(100); } catch (Exception ignored) {}
            }

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
                // ✅ (ajouté 3 août 2026, demande Paul : "RUNNING_FLOWING pas supposé
                // ne pas s'afficher") — forcer le rafraîchissement immédiat du tab sur
                // LE MÊME controller qui vient d'armer la livraison, au lieu d'attendre
                // passivement pollJobUntilDone() (qui interroge via une facade séparée
                // et ne pousse jamais à travers le listener UI du tab). Même appel que
                // runStatusBLikeButton() dans RegisterTabFragment pour Status(B).
                try {
                    controllerOneshot.requestStatus();
                    Thread.sleep(200);
                    controllerOneshot.requestLiveSample();
                } catch (Exception ignored) {}
                if (jobId != null && !jobId.isEmpty()) {
                    // ✅ AJOUTÉ (28 août 2026, demande Paul — "on va
                    // ajouter le ticket de livraison en table avec le
                    // statut running_flowing... si jamais on a un crash
                    // une réinstallation on veut être en mesure de la
                    // reprendre") — écriture initiale, dès l'armement
                    // réussi, AVANT même que le flow ne commence. Utilise
                    // upsertByJobId() (pas insertDelivery()) — job_id
                    // sert d'ancre stable, ticket_no n'étant pas encore
                    // connu à ce stade. Même structure JSON que les autres
                    // backups déjà en place (onDeliveryEnded, annulation)
                    // — écrasée par la vraie fin une fois la livraison
                    // terminée (voir jobId réutilisé comme clé de mise à
                    // jour, pas de nouvelle écriture séparée).
                    try {
                        final String fMacPourArm = fMac.isEmpty() ? transportKey : fMac;
                        android.content.ContentValues cvArm = new android.content.ContentValues();
                        cvArm.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_JOB_ID, jobId);
                        cvArm.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_WO_NUM, woNum != null ? woNum : "");
                        cvArm.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_WO_ID_GUID, woIdGuid != null ? woIdGuid : "");
                        cvArm.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_SERIAL_ID, fSerialId != null ? fSerialId : "");
                        cvArm.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_LCRNODE, node);
                        cvArm.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_BTMAC, fMacPourArm);
                        cvArm.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_NET_L, 0.0);
                        cvArm.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_GROSS_L, 0.0);
                        cvArm.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_TYPE,
                            com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.TYPE_ORIGINAL);
                        cvArm.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_SOURCE, "ARMEMENT");
                        cvArm.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_STOP_TYPE, "RUNNING_FLOWING");
                        cvArm.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_SYNC_STATUS,
                            com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.SYNC_PENDING);
                        com.pa.lcr.lcp.storage.LcrDeliveryStatusDb dbArm =
                            new com.pa.lcr.lcp.storage.LcrDeliveryStatusDb(activity);
                        try {
                            dbArm.upsertByJobId(cvArm);
                        } finally {
                            try { dbArm.close(); } catch (Exception ignored) {}
                        }
                        android.util.Log.i(TAG, "Livraison enregistrée dès l'armement — jobId=" + jobId + " wo=" + woNum);

                        org.json.JSONObject backupPayloadArm = new org.json.JSONObject();
                        backupPayloadArm.put("job_id", jobId);
                        backupPayloadArm.put("wo_num", woNum != null ? woNum : "");
                        backupPayloadArm.put("wo_id_guid", woIdGuid != null ? woIdGuid : "");
                        backupPayloadArm.put("ticket_no", "");
                        backupPayloadArm.put("sale_no", "");
                        backupPayloadArm.put("net_l", 0.0);
                        backupPayloadArm.put("gross_l", 0.0);
                        backupPayloadArm.put("serial_id", fSerialId != null ? fSerialId : "");
                        backupPayloadArm.put("lcrnode", node);
                        backupPayloadArm.put("btmac", fMacPourArm);
                        backupPayloadArm.put("type", com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.TYPE_ORIGINAL);
                        backupPayloadArm.put("backup_ts", System.currentTimeMillis());
                        backupPayloadArm.put("payload_complet", "{\"status\":\"RUNNING_FLOWING\",\"job_id\":\"" + jobId + "\"}");
                        backupPayloadArm.put("sync_status",
                            com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.SYNC_PENDING);
                        com.pa.lcr.lcp.storage.LocalDeliveryBackup.backupDeliveryAsync(
                            activity.getApplicationContext(), woNum, jobId, backupPayloadArm);
                    } catch (Exception e) {
                        android.util.Log.w(TAG, "Enregistrement initial (armement) ERR (non-bloquant): " + e.getMessage());
                    }

                    activity.runOnUiThread(() ->
                        activity.toast("📦 Livraison démarrée — " + woNum));
                    pollJobUntilDone(jobId, node, woNum, woIdGuid, fSerialId,
                        fMac.isEmpty() ? transportKey : fMac, true);
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
            android.util.Log.e(TAG, "lancerLivraison ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "DeepLinkHandler.lancerLivraison", e); } catch (Exception ignored) {}
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

        safeExecute(() -> {
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
                        android.util.Log.w(TAG, "Validation état registre ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "DeepLinkHandler.Validation", e); } catch (Exception ignored) {}
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
                    android.util.Log.w(TAG, "register/validate ERR (ignoré): " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "DeepLinkHandler.register", e); } catch (Exception ignored) {}
                }

                final String fProduit      = produit;
                final String fPreset       = presetStr;
                final String fWoNum        = woNum;
                final String fSerialId     = serialId != null ? serialId : "";
                final String fTransportKey = transportKey;

                activity.runOnUiThread(() -> {
                    try {
                        activity.onConfigureMediaActivated(fTransportKey, "DEEPLINK");
                        // ✅ Détection isLc3 centralisée — même mécanisme partagé.
                        activity.upsertRegisterTabFromScan(fTransportKey, node, 255, fSerialId, true,
                                activity.resolveIsLc3(fTransportKey, node));
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
                    // ✅ FIX (4 août 2026, demande Paul : "ça devrait être le
                    // transport au complet, pas juste USB — BT, TCP, autres")
                    // — même bug que lancerLivraison() : refreshAllTabsMediaStatus()
                    // (ligne ~1074, juste après upsertRegisterTabFromScan) tournait
                    // avant que ce transport soit confirmé réellement ouvert, quel
                    // qu'il soit (BT ici, mais le même code sert aussi TCP/USB
                    // selon transportKey). Rafraîchi maintenant qu'on SAIT que
                    // c'est prêt.
                    activity.runOnUiThread(activity::refreshAllTabsMediaStatus);

                    // ✅ Bloquer si un poll est déjà actif
                    if (!activePolls.isEmpty()) {
                        android.util.Log.w(TAG, "connectBt: poll déjà actif — ignoré");
                        activity.runOnUiThread(() -> activity.toast("↩️ Livraison déjà en cours"));
                        return;
                    }

                    // ✅ (4 août 2026, demande Paul) — même garde qu'ailleurs : ce
                    // chemin crée systématiquement le tab juste au-dessus
                    // (upsertRegisterTabFromScan) — donc quasi toujours un tab
                    // neuf. Attendre la fin du scan auto produits avant de
                    // démarrer, best-effort, max 10s.
                    try {
                        String mediaShortWait = activity.mediaShortFromTransportKey(transportKey);
                        String tabKeyWait = activity.tabKeyOf(mediaShortWait, node, fSerialId);
                        boolean scanTermine = false;
                        for (int i = 0; i < 20; i++) {
                            Fragment fw = activity.getSupportFragmentManager()
                                .findFragmentByTag("regtab_" + tabKeyWait);
                            if (!(fw instanceof RegisterTabFragment)
                                    || !((RegisterTabFragment) fw).isAutoProductScanBusy()) {
                                scanTermine = true;
                                break;
                            }
                            try { Thread.sleep(500); } catch (Exception ignored) {}
                        }
                        android.util.Log.i(TAG, "connectBt: attente scan auto produits — "
                            + (scanTermine ? "terminé" : "timeout 10s, poursuite quand même"));
                    } catch (Exception ignored) {}

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
                                pollJobUntilDone(jobId, node, woNum, woIdGuid, fSerialId, mac, true);
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
                        android.util.Log.e(TAG, "oneshot/start ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "DeepLinkHandler.oneshot", e); } catch (Exception ignored) {}
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
                android.util.Log.e(TAG, "BT connect ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "DeepLinkHandler.BT", e); } catch (Exception ignored) {}
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

    // ✅ AJOUTÉ (27 août 2026, demande Paul — "je veux avoir en bd chaque
    // livraison qui a toi le ticket_number ou le sales_number, je ne
    // comprends pas pourquoi j'ai une contrainte") — logEvent()/logError()
    // utilisaient woNum comme ticket_no pour openAttemptAsync(), mais
    // delivery_summary est re-clée sous le VRAI ticket_no par
    // logDeliveryEnd() dès qu'il est connu (voir ticketNoReel). La ligne
    // sous woNum n'existe alors plus → contrainte FOREIGN KEY échoue à
    // chaque logEvent()/logError() suivant. Ce cache retient le dernier
    // ticket_no réel vu par pollJobUntilDone() (qui l'obtient déjà via
    // api_deliveryJobGet()), clé par serialId+"|"+woNum — jamais par
    // serialId seul, car un nouveau woNum arrive à chaque livraison FSM.
    private static final java.util.Map<String, String> lastKnownTicketNo =
        new java.util.concurrent.ConcurrentHashMap<>();

    private static String ticketCacheKey(String serialId, String woNum) {
        return (serialId != null ? serialId : "") + "|" + (woNum != null ? woNum : "");
    }

    // =========================================================
    // Poll état livraison
    // =========================================================

    private void pollJobUntilDone(String jobId, int node, String woNum,
                                   String woIdGuid, String serialId, String mac) {
        pollJobUntilDone(jobId, node, woNum, woIdGuid, serialId, mac, false);
    }

    // ✅ AJOUTÉ (28 août 2026, demande Paul — "avant je n'avais pas de
    // connected ready avant entre deux running_flowing... fait donc ça"
    // — réduire le délai de 5.7s mesuré entre ARMED et le vrai CMD_RUN) —
    // freshStart=true : appelé juste après un armement RÉUSSI (oneshot/
    // start), où l'on SAIT avec certitude que l'état est CONNECTED
    // (ARMED, pas encore RUN) et qu'il n'y a pas de ticket pending — les
    // 2 lectures api_deliveryJobGet() qui vérifiaient ça (chacune une
    // vraie communication LCP) sont sautées, direct vers CONTINUE.
    // freshStart=false (par défaut, voir overload ci-dessus) : chemin de
    // reprise après crash (ActiveDeliveryStore), où l'état RÉEL est
    // inconnu — garde les vérifications complètes.
    private void pollJobUntilDone(String jobId, int node, String woNum,
                                   String woIdGuid, String serialId, String mac,
                                   boolean freshStart) {
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
        } catch (Exception e) {
            // ✅ FIX (4 août 2026, demande Paul) — même risque que la sauvegarde
            // PENDING plus haut : si ceci échoue, ActiveDeliveryStore reste
            // désynchronisé (status resté PENDING alors que la livraison a
            // réellement démarré), sans aucune trace.
            com.pa.lcr.lcp.log.LogBus.err(node, "DeepLinkHandler.ActiveDeliveryStore.save[STARTED]", e);
        }

        safeExecute(() -> {
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
                if (!ticketNoAtStart.isEmpty()) {
                    lastKnownTicketNo.put(ticketCacheKey(serialId, woNum), ticketNoAtStart);
                }

                // ✅ Délai avant premier continue — USB est plus lent que BT
                if (transportKey.toUpperCase().startsWith("USB")) {
                    try { Thread.sleep(800); } catch (Exception ignored) {}
                }

                try {
                    // ✅ CORRIGÉ (28 août 2026, demande Paul — "avant je
                    // n'avais pas de connected ready avant entre deux
                    // running_flowing") — sur un démarrage frais
                    // (freshStart=true), on SAIT avec certitude que l'état
                    // est CONNECTED (ARMED à l'instant, pas encore RUN) et
                    // qu'il n'y a pas de ticket pending — cette livraison
                    // vient d'être armée par CE MÊME appelant, quelques
                    // lignes plus haut, avec un preset fraîchement écrit.
                    // Les 2 lectures api_deliveryJobGet() ci-dessous (state
                    // puis ticketPending), chacune une vraie communication
                    // LCP séparée, ne servent qu'à distinguer ce cas d'une
                    // VRAIE reprise (où l'état est inconnu) — inutiles ici,
                    // et mesurées comme contribuant au délai de 5.7s
                    // observé entre ARMED et le vrai CMD_RUN. Sautées sur
                    // démarrage frais, direct vers CONTINUE. Le chemin de
                    // reprise après crash (freshStart=false, seul appelant
                    // restant à passer par cette branche) garde les
                    // vérifications complètes, inchangées.
                    String currentState = freshStart ? "CONNECTED" : "";
                    boolean tpFreshStart = false; // toujours faux sur un démarrage qu'on vient d'armer nous-même
                    if (!freshStart) {
                    try {
                        MultiRegisterApiFacadeImpl facadeCheck =
                            new MultiRegisterApiFacadeImpl(activity);
                        com.pa.lcr.lcp.ApiResult stateCheck =
                            facadeCheck.api_deliveryJobGet(jobId);
                        if (stateCheck != null && stateCheck.data != null)
                            currentState = stateCheck.data.optString("state", "");
                    } catch (Exception ignored) {}
                    }

                    if ("RUNNING_FLOWING".equals(currentState)
                            || "RUNNING_PAUSED".equals(currentState)) {
                        android.util.Log.i(TAG, "job/continue ignoré — déjà en " + currentState);
                        hasSeenFlowing = true;
                    } else if ("CONNECTED".equals(currentState)) {
                        // Vérifier si ticket pending — si oui, faire status B et laisser l'opérateur
                        boolean tp = tpFreshStart;
                        if (!freshStart) {
                        try {
                            MultiRegisterApiFacadeImpl facadeCheck2 =
                                new MultiRegisterApiFacadeImpl(activity);
                            com.pa.lcr.lcp.ApiResult stateCheck2 =
                                facadeCheck2.api_deliveryJobGet(jobId);
                            if (stateCheck2 != null && stateCheck2.data != null)
                                tp = stateCheck2.data.optInt("ticketPending", 0) == 1;
                        } catch (Exception ignored) {}
                        }

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
                                if (!ticketNow.isEmpty()) {
                                    lastKnownTicketNo.put(ticketCacheKey(serialId, woNum), ticketNow);
                                }

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
                                safeExecute(() -> {
                                    try {
                                        android.content.ContentValues cv =
                                            new android.content.ContentValues();
                                        cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_WO_NUM,    fWoNum2);
                                        cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_WO_ID_GUID, fWoId2);
                                        cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_TICKET_NO, fTicketNow);
                                        // ✅ AJOUTÉ (26 août 2026, demande Paul
                                        // — même correctif que RegisterTabFragment,
                                        // trouvé via screenshot écran de cohérence)
                                        if (serialId != null && !serialId.trim().isEmpty()) {
                                            cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_SERIAL_ID, serialId.trim());
                                        }
                                        cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_LCRNODE, node);
                                        // ✅ AJOUTÉ (27 août 2026, demande Paul —
                                        // "si on a l'adresse mac du BT on le veut aussi")
                                        if (mac != null && !mac.trim().isEmpty()) {
                                            cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_BTMAC, mac.trim());
                                        }
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
                                        com.pa.lcr.lcp.storage.LcrDeliveryStatusDb dbTc1 =
                                            new com.pa.lcr.lcp.storage.LcrDeliveryStatusDb(activity);
                                        try {
                                            dbTc1.insertDelivery(cv);
                                        } finally {
                                            try { dbTc1.close(); } catch (Exception ignored) {}
                                        }
                                    } catch (Exception e) {
                                        // ✅ FIX (4 août 2026, demande Paul) — ce bloc enregistre déjà
                                        // une ERREUR (ticket changé / job-continue échoué). Si
                                        // l'enregistrement de l'erreur échoue lui-même, on se
                                        // retrouve avec une double invisibilité — ni l'erreur
                                        // d'origine ni cet échec ne laissent de trace. Exactement
                                        // la classe de bug du ticket 10909.
                                        com.pa.lcr.lcp.log.LogBus.err(
                                            node, "DeepLinkHandler.insertDelivery[TICKET_CHANGE/ERROR]", e);
                                    }
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
                            if (!ticketNow2.isEmpty()) {
                                lastKnownTicketNo.put(ticketCacheKey(serialId, woNum), ticketNow2);
                            }

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

                            safeExecute(() -> {
                                try {
                                    android.content.ContentValues cv =
                                        new android.content.ContentValues();
                                    cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_WO_NUM,    fWoNum3);
                                    cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_WO_ID_GUID, fWoId3);
                                    cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_TICKET_NO, fTicketNow2);
                                    // ✅ AJOUTÉ (26 août 2026, demande Paul —
                                    // même correctif que RegisterTabFragment)
                                    if (serialId != null && !serialId.trim().isEmpty()) {
                                        cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_SERIAL_ID, serialId.trim());
                                    }
                                    cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_LCRNODE, node);
                                    // ✅ AJOUTÉ (27 août 2026, demande Paul —
                                    // "si on a l'adresse mac du BT on le veut aussi")
                                    if (mac != null && !mac.trim().isEmpty()) {
                                        cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_BTMAC, mac.trim());
                                    }
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
                                    com.pa.lcr.lcp.storage.LcrDeliveryStatusDb dbTc2 =
                                        new com.pa.lcr.lcp.storage.LcrDeliveryStatusDb(activity);
                                    try {
                                        dbTc2.insertDelivery(cv);
                                    } finally {
                                        try { dbTc2.close(); } catch (Exception ignored) {}
                                    }
                                } catch (Exception e) {
                                    // ✅ FIX (4 août 2026, demande Paul) — même cas que dbTc1 :
                                    // ce bloc enregistre déjà une ERREUR ; s'il échoue lui-même,
                                    // double invisibilité (ni l'erreur d'origine ni cet échec
                                    // ne laissent de trace).
                                    com.pa.lcr.lcp.log.LogBus.err(
                                        node, "DeepLinkHandler.insertDelivery[TICKET_CHANGE/ERROR#2]", e);
                                }
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
                    android.util.Log.e(TAG, "job/continue ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "DeepLinkHandler.job", e); } catch (Exception ignored) {}
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

                        // ✅ CORRIGÉ (28 août 2026, demande Paul — "est-ce
                        // que ces lignes sont nécessaires maintenant") —
                        // avant, loguait inconditionnellement à CHAQUE
                        // cycle de poll (~1s), répétant la même valeur
                        // pendant toute la durée d'un flow stable (jusqu'à
                        // 30+ lignes identiques pour une livraison de 30s).
                        // Ne log plus que sur un vrai changement — réutilise
                        // lastState, déjà déclaré plus haut pour STATE_CHANGE.
                        if (!java.util.Objects.equals(state, lastState)) {
                            android.util.Log.i(TAG, "pollJob: state=" + state);
                        }

                        // ✅ state=null = job disparu du controller — sortir immédiatement
                        if (state == null || state.isEmpty()) {
                            android.util.Log.w(TAG, "pollJob: state=null — job disparu, arrêt poll");
                            return;
                        }

                        // ✅ AJOUTÉ (27 août 2026, demande Paul — "je veux
                        // avoir en bd chaque livraison qui a toi le
                        // ticket_number ou le sales_number") — cas exact du
                        // logcat fourni : logDeliveryEnd() re-clé déjà
                        // delivery_summary sous le vrai ticket_no (ex:
                        // "104") au moment du DONE, puis ce tick-ci passe en
                        // CONNECTED et appelle logEvent(serialId, woNum, ...)
                        // avec l'ancien woNum — ligne qui n'existe plus →
                        // FOREIGN KEY échoue, attemptId=-1. Rafraîchir le
                        // cache ici, à chaque tick, avant tout logEvent.
                        if (r.data != null) {
                            JSONObject resTick = r.data.optJSONObject("result");
                            String tNow = resTick != null ? resTick.optString("ticket_no", "") : "";
                            if (!tNow.isEmpty()) {
                                lastKnownTicketNo.put(ticketCacheKey(serialId, woNum), tNow);
                            }
                        }

                        if (state != null && !state.equals(lastState)) {
                            // ✅ CORRIGÉ (27 août 2026, demande Paul — "je ne
                            // suis pas censé avoir quoi que ce soit de
                            // requête SQL dans les fragments ou le
                            // running_flowing") — trouvé, confirmé par
                            // trace réelle : logEvent() (donc une vraie
                            // écriture SQLite) se déclenchait ici à chaque
                            // transition d'état, y compris l'entrée en
                            // RUNNING_FLOWING/RUNNING_PAUSED. state est déjà
                            // connu ici, pas besoin d'une lecture séparée du
                            // contrôleur — bloqué avant même de tenter
                            // l'écriture, pas juste rendu silencieux après.
                            boolean livraisonActivePourLog = "RUNNING_FLOWING".equals(state) || "RUNNING_PAUSED".equals(state);
                            if (!livraisonActivePourLog) {
                                logEvent(serialId, woNum, DeliveryLogStore.LEVEL_INFO,
                                    "STATE_CHANGE", "state=" + state, null);
                            }
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
                            onDeliveryEnded(woNum, woIdGuid, extraJson, node, serialId, mac);
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
                            onDeliveryEnded(woNum, woIdGuid, extraJson, node, serialId, mac);
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
                            onDeliveryEnded(woNum, woIdGuid, extraJson, node, serialId, mac);
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
                            onDeliveryEnded(woNum, woIdGuid, extraJson, node, serialId, mac);
                            // ✅ AJOUTÉ (28 août 2026, demande Paul — "il
                            // faut vraiment récupérer l'état du registre
                            // pourquoi j'ai été obligé de faire status")
                            // — trouvé : ce même rafraîchissement immédiat
                            // (requestStatus()+requestLiveSample())
                            // existait déjà pour le cas "ticket pending"
                            // (voir plus haut dans cette même boucle),
                            // mais manquait ICI — laissant l'affichage
                            // figé sur l'ancien état (RUNNING_FLOWING)
                            // jusqu'à ce que Paul déclenche manuellement
                            // STATUS_B, 30 secondes plus tard dans le log
                            // fourni. Même patron, appliqué ici aussi.
                            try {
                                com.pa.lcr.lcp.DeliveryController dcFin =
                                    com.pa.lcr.lcp.RegisterSessionManager.get(activity)
                                        .getController(transportKey, node);
                                if (dcFin != null) {
                                    dcFin.requestStatus();
                                    Thread.sleep(200);
                                    dcFin.requestLiveSample();
                                }
                            } catch (Exception ignored) {}
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
                android.util.Log.e(TAG, "pollJob ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "DeepLinkHandler.pollJob", e); } catch (Exception ignored) {}
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
        // ✅ CORRIGÉ (28 août 2026, demande Paul — "les vides ne sont pas
        // supposés l'être... le node, le #série") — cette surcharge (3-arg)
        // relayait vers currentNode/currentSerialId, des champs PARTAGÉS de
        // DeepLinkHandler, jamais mis à jour hors de handleDeepLink() — une
        // livraison arrivée par tout autre chemin (reconnexion, retry)
        // héritait de node=0/serialId="" au moment d'écrire dans
        // LcrDeliveryStatusDb, puis vers Dataverse. Conservée pour tout
        // appelant existant qui n'a pas node/serialId sous la main — mais
        // le VRAI point d'entrée est maintenant la surcharge 6-arg
        // ci-dessous, appelée directement par pollJobUntilDone() (qui, lui,
        // a déjà node/serialId/mac en paramètres explicites, jamais
        // partagés). Pas de champ partagé équivalent pour le mac — repli
        // sur chaîne vide plutôt que d'en inventer un nouveau.
        onDeliveryEnded(woNum, woIdGuid, extraJson, currentNode, currentSerialId, "");
    }

    // ✅ CORRIGÉ (à l'instant — build cassé, "no suitable method found") —
    // j'avais remplacé cette surcharge 5-arg par la 6-arg au lieu d'en
    // garder une EN PLUS de l'autre. MainActivity.onDeliveryEnded(5-arg)
    // appelle encore celle-ci — sans elle, ça ne compile pas. Relais
    // simple vers la 6-arg, mac="" (pas d'info mac disponible à ce
    // point d'appel précis).
    public void onDeliveryEnded(String woNum, String woIdGuid, String extraJson,
                                 int nodeParam, String serialIdParam) {
        onDeliveryEnded(woNum, woIdGuid, extraJson, nodeParam, serialIdParam, "");
    }

    // ✅ AJOUTÉ (28 août 2026, demande Paul — même correctif, "oublie pas
    // d'ajouter toujours le woguid, le bt mac") — vraie implémentation,
    // avec node/serialId/mac REÇUS EN PARAMÈTRES au lieu d'être lus
    // depuis un état partagé potentiellement périmé/jamais initialisé
    // pour ce chemin d'appel précis. woIdGuid était déjà correctement un
    // paramètre explicite depuis le début — seul mac manquait
    // complètement de l'insertion locale jusqu'ici (voir plus bas,
    // COL_BTMAC jamais posé).
    public void onDeliveryEnded(String woNum, String woIdGuid, String extraJson,
                                 int nodeParam, String serialIdParam, String macParam) {
        // ✅ Effacer la livraison courante
        try { new ActiveDeliveryStore(activity).clear(); } catch (Exception ignored) {}
        android.util.Log.i(TAG,
            "Livraison terminée — WO=" + woNum + " extra=" + extraJson);

        // ✅ Écrire dans LcrDeliveryStatusDb (offline safe) avant retour FSM
        safeExecute(() -> {
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
                // ✅ CORRIGÉ (28 août 2026) — nodeParam/serialIdParam reçus
                // en paramètres (voir surcharge 5-arg ci-dessus), plus
                // jamais currentNode/currentSerialId (partagés, périmés
                // hors du chemin handleDeepLink()).
                int lcrnode = nodeParam;
                String serialId = serialIdParam;
                // ✅ AJOUTÉ (28 août 2026, demande Paul — "oublie pas
                // d'ajouter toujours le woguid, le bt mac") — COL_BTMAC
                // était complètement absent de cette insertion jusqu'ici,
                // contrairement à d'autres blocs du fichier (lignes ~1531,
                // ~1645) qui, eux, l'incluaient déjà. Corrigé pour
                // cohérence — toujours écrit ici aussi maintenant.
                String mac = macParam;

                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_WO_NUM,       woNum != null ? woNum : "");
                cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_WO_ID_GUID,   woIdGuid != null ? woIdGuid.replace("{","").replace("}","") : "");
                cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_SERIAL_ID,    serialId);
                cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_LCRNODE,      lcrnode);
                cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_BTMAC,        mac != null ? mac : "");
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
                long localId;
                try {
                    localId = lcrDb.insertDelivery(cv);
                } finally {
                    try { lcrDb.close(); } catch (Exception ignored) {}
                }
                android.util.Log.i(TAG, "LcrDeliveryStatusDb: id=" + localId
                    + " wo=" + woNum + " net=" + netL + " gross=" + grossL
                    + " ticket=" + ticketNo + " duration=" + durationS);

                // ✅ AJOUTÉ (28 août 2026, demande Paul — "j'ai le json
                // uniquement si je fais le bouton bleu, pas à la fin de la
                // livraison") — trouvé : backupDeliveryAsync() n'existait
                // QUE dans retournerAuWorkOrder() (déclenché par le clic
                // sur le bouton bleu, RegisterTabFragment.java) — jamais
                // ici, dans la vraie fin de livraison. Sur une BD vierge
                // (réinstall) sans que le chauffeur n'ait cliqué ce
                // bouton, il n'y avait donc AUCUNE trace locale survivant
                // au réinstall. Même structure exacte que celle déjà
                // utilisée ailleurs — écrite maintenant à la fin de
                // CHAQUE livraison, peu importe si le bouton bleu est
                // cliqué ou non par la suite.
                try {
                    // ✅ RETABLI (28 août 2026, demande Paul — "non on
                    // garde une ligne par transaction... on ne change
                    // rien on ajoute le running_flowing") — revient à
                    // ticket_no comme clé du nom de fichier (comportement
                    // d'origine). insertDelivery() reste une VRAIE ligne
                    // finale distincte de celle créée à l'armement
                    // (running_flowing, via upsertByJobId ailleurs) — pas
                    // une mise à jour de cette dernière. job_id reste
                    // dans le payload pour référence/traçabilité, mais ne
                    // sert plus de clé de fichier ici.
                    org.json.JSONObject backupPayloadFin = new org.json.JSONObject();
                    backupPayloadFin.put("job_id", d.optString("jobId", ""));
                    backupPayloadFin.put("wo_num", woNum != null ? woNum : "");
                    backupPayloadFin.put("wo_id_guid", woIdGuid != null ? woIdGuid : "");
                    backupPayloadFin.put("ticket_no", ticketNo != null ? ticketNo : "");
                    backupPayloadFin.put("sale_no", saleNo != null ? saleNo : "");
                    backupPayloadFin.put("net_l", netL);
                    backupPayloadFin.put("gross_l", grossL);
                    backupPayloadFin.put("serial_id", serialId != null ? serialId : "");
                    backupPayloadFin.put("lcrnode", lcrnode);
                    backupPayloadFin.put("btmac", mac != null ? mac : "");
                    backupPayloadFin.put("type", com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.TYPE_ORIGINAL);
                    backupPayloadFin.put("backup_ts", System.currentTimeMillis());
                    backupPayloadFin.put("payload_complet", extraJson);
                    backupPayloadFin.put("sync_status",
                        com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.SYNC_PENDING);
                    com.pa.lcr.lcp.storage.LocalDeliveryBackup.backupDeliveryAsync(
                        activity.getApplicationContext(), woNum, ticketNo, backupPayloadFin);
                } catch (Exception e) {
                    android.util.Log.w(TAG, "Backup local (fin de livraison) ERR (non-bloquant): " + e.getMessage());
                }

                // ✅ RETIRÉ (28 août 2026, demande Paul — "le plus robuste,
                // patchSummaryConsolidated, se déclenche automatiquement à
                // la fin") — ce patchDataverse() simple est remplacé par
                // l'appel à patchSummaryConsolidated() plus loin (dans le
                // bloc syncAll ci-dessous), qui réutilise le même token
                // déjà acquis — plus robuste (fusion+ETag), pas de
                // deuxième mécanisme séparé.

                // ✅ AJOUTÉ (28 août 2026, demande Paul — "actuellement dans
                // dataverse je n'ai aucune des livraisons tests que j'ai
                // fait" / "utiliser le guid du ticket delivery-uid") —
                // trouvé : ce chemin de sync (LcrDeliveryStatusDb →
                // LcrDeliverySync.pushPending(), un vrai POST par
                // ticket/wo_num, JAMAIS besoin d'un GUID FieldService à
                // l'avance) écrit correctement sync_status=PENDING ici,
                // mais RIEN ne déclenchait syncAll() après une livraison —
                // seul le login MSAL au tout premier démarrage de l'app
                // le faisait. La livraison attendait donc jusqu'à 15
                // minutes (le cycle périodique WorkManager) avant de
                // seulement être TENTÉE. Déclenché ici, immédiatement
                // après l'écriture PENDING — même patron que
                // DeliverySyncScheduler.triggerNow() déjà utilisé côté
                // patchDataverse() pour l'autre file (celle-là exige un
                // GUID, gérée séparément, sans changement ici).
                try {
                    com.pa.lcrdemo.auth.MsalTokenProvider tp =
                        new com.pa.lcrdemo.auth.MsalTokenProvider(activity);
                    tp.init(new com.pa.lcrdemo.auth.MsalTokenProvider.InitCallback() {
                        @Override public void onReady() {
                            tp.acquireTokenSilentFromWorker(
                                new com.pa.lcrdemo.auth.MsalTokenProvider.TokenCallback() {
                                @Override public void onSuccess(String token) {
                                    new Thread(() -> {
                                        try {
                                            com.pa.lcrdemo.dataverse.LcrDeliverySync.syncAll(activity, token);
                                        } catch (Exception e) {
                                            android.util.Log.w(TAG, "syncAll post-livraison ERR: " + e.getMessage());
                                        }
                                        // ✅ AJOUTÉ (28 août 2026, demande
                                        // Paul — "le plus robuste,
                                        // patchSummaryConsolidated, se
                                        // déclenche automatiquement à la
                                        // fin, dans la seule condition que
                                        // cela ne brise rien") — même
                                        // requête/construction EXACTE que
                                        // celle déjà utilisée par le
                                        // bouton bleu (RegisterTabFragment
                                        // .retournerAuWorkOrder()) —
                                        // getAllForWo(), filtre net>0 ou
                                        // ANNULATION, GUID depuis la
                                        // première ligne qui en a un.
                                        // Réutilise le MÊME token déjà
                                        // acquis ci-dessus — pas de
                                        // deuxième authentification. Le
                                        // bouton bleu garde son propre
                                        // appel, inchangé — redevient un
                                        // filet de sécurité, jamais le
                                        // seul chemin.
                                        try {
                                            com.pa.lcr.lcp.storage.LcrDeliveryStatusDb lcrFinal =
                                                new com.pa.lcr.lcp.storage.LcrDeliveryStatusDb(activity);
                                            java.util.List<com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.DeliveryRow> allRows;
                                            try {
                                                allRows = lcrFinal.getAllForWo(woNum);
                                            } finally {
                                                try { lcrFinal.close(); } catch (Exception ignored) {}
                                            }
                                            org.json.JSONArray livraisons = new org.json.JSONArray();
                                            String patchGuid = "";
                                            for (com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.DeliveryRow r : allRows) {
                                                if (r.woIdGuid != null && !r.woIdGuid.isEmpty() && patchGuid.isEmpty()) {
                                                    patchGuid = r.woIdGuid;
                                                }
                                                if (r.netL > 0 || "ANNULATION".equals(r.type)) {
                                                    org.json.JSONObject entry = new org.json.JSONObject();
                                                    entry.put("ticket_no", r.ticketNo != null ? r.ticketNo : "");
                                                    entry.put("net_l",     r.netL);
                                                    entry.put("gross_l",   r.grossL);
                                                    entry.put("type",      r.type != null ? r.type : "");
                                                    entry.put("end_utc",   r.endUtc != null ? r.endUtc : "");
                                                    livraisons.put(entry);
                                                }
                                            }
                                            if (livraisons.length() > 0 && !patchGuid.isEmpty()) {
                                                com.pa.lcrdemo.dataverse.WorkOrderUpdater.patchSummaryConsolidated(
                                                    token, patchGuid, woNum, livraisons);
                                                android.util.Log.i(TAG, "patchSummaryConsolidated post-livraison OK — "
                                                    + livraisons.length() + " livraison(s), wo=" + woNum);
                                            }
                                        } catch (Exception e) {
                                            android.util.Log.w(TAG, "patchSummaryConsolidated post-livraison ERR (non-bloquant): " + e.getMessage());
                                        }
                                    }).start();
                                }
                                @Override public void onError(Exception e) {
                                    android.util.Log.w(TAG, "syncAll post-livraison — token ERR: " + e.getMessage());
                                }
                            });
                        }
                        @Override public void onError(Exception e) {
                            android.util.Log.w(TAG, "syncAll post-livraison — MSAL init ERR: " + e.getMessage());
                        }
                    });
                } catch (Exception e) {
                    android.util.Log.w(TAG, "syncAll post-livraison — déclenchement ERR: " + e.getMessage());
                }

                // ✅ mettreAJourFieldService APRÈS l'insert — garantit que getAllForWo()
                // voit la livraison courante dans le payload consolidé
                mettreAJourFieldService(woNum, woIdGuid, "termine", extraJson);

            } catch (Exception e) {
                android.util.Log.e(TAG, "LcrDeliveryStatusDb ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "DeepLinkHandler.LcrDeliveryStatusDb", e); } catch (Exception ignored) {}
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

            } catch (Exception e) {
                // ✅ FIX (4 août 2026, demande Paul) — patchDataverse() enqueue le
                // résultat de livraison pour sync Dataverse (DeliveryResultQueueDb).
                // Si ça échoue ici, la livraison ne sera JAMAIS mise en file —
                // aucun retry possible puisque rien n'a été enregistré. Risque
                // direct de perte de livraison, classe de bug ticket 10909.
                com.pa.lcr.lcp.log.LogBus.err(-1, "DeepLinkHandler.patchDataverse[retournerFS]", e);
            }

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
                    android.util.Log.e(TAG, "localStorage ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "DeepLinkHandler.localStorage", e); } catch (Exception ignored) {}
                }

                android.util.Log.i(TAG, "Retour FS — finish()");
                try {
                    activity.finish();
                } catch (Exception e) {
                    android.util.Log.e(TAG, "finish() ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "DeepLinkHandler.finish()", e); } catch (Exception ignored) {}
                    activity.moveTaskToBack(true);
                }
            });

        } catch (Exception e) {
            android.util.Log.e(TAG, "Retour FS failed: " + e.getMessage());
            try { com.pa.lcr.lcp.log.LogBus.err(0, "DeepLinkHandler.retourFS", e); } catch (Exception ignored) {}
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
            android.util.Log.e(TAG, "mettreAJourFieldService ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "DeepLinkHandler.mettreAJourFieldService", e); } catch (Exception ignored) {}
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

            // ✅ RETIRÉ (27 août 2026, demande Paul — "c'est exactement ce
            // que j'ai après installation" — confirmé que le correctif
            // précédent (try/catch) n'empêche pas l'erreur elle-même,
            // seulement sa conséquence de plantage) — openAttemptAsync()
            // ici exige une ligne delivery_summary(serial_id, ticket_no)
            // déjà existante (contrainte FOREIGN KEY), mais ticket_no vaut
            // ici le numéro de WO, jamais un vrai ticket — cette écriture
            // échouait à CHAQUE appel, par conception, peu importe l'ordre
            // ou le timing. upsertSummaryAsync ci-dessus capture déjà
            // l'essentiel ("livraison démarrée") sans dépendre de cette
            // contrainte fragile — retiré plutôt que rafistolé encore.
        } catch (Exception ignored) {}
    }

    private void logDeliveryEnd(String serialId, String woNum, String jobId,
                                 String outcome, String resultJson, String errorJson) {
        if (deliveryStore == null || serialId == null || serialId.isEmpty()) return;
        // ✅ CORRIGÉ (27 août 2026, demande Paul — "si on est plus dans la
        // fiche de départ pour x raison, il n'y a aucun dépôt payload dans
        // le champ résumé") — trouvé : cette écriture indexait toujours
        // par woNum, jamais le vrai ticket_no du registre — alors que
        // resultJson (extraJson) contient déjà le vrai ticket_no à
        // l'intérieur (construit par api_deliveryJobGet()). Si le chemin
        // normal de complétion (DeliveryController, indexé par le vrai
        // ticket_no) n'atteint jamais son écriture pour une raison
        // quelconque (contexte de livraison perdu), cette ligne-ci —
        // indexée par woNum — devenait la SEULE trace, mais introuvable
        // par toute recherche basée sur le vrai ticket (comme
        // getLatestResultByTicketNo() construite hier). Extrait le vrai
        // ticket_no du payload quand disponible, repli sur woNum sinon.
        String ticketNoReel = woNum;
        if (resultJson != null && !resultJson.trim().isEmpty()) {
            try {
                org.json.JSONObject rj = new org.json.JSONObject(resultJson);
                String t = rj.optString("ticket_no", null);
                if (t != null && !t.trim().isEmpty() && !"0".equals(t.trim())) {
                    ticketNoReel = t.trim();
                }
            } catch (Exception ignored) {}
        }
        deliveryStore.upsertSummaryAsync(
            serialId, ticketNoReel != null ? ticketNoReel : "DEEPLINK",
            null, outcome, DeliveryLogStore.SOURCE_API,
            jobId, resultJson, errorJson);

        // ✅ AJOUTÉ (27 août 2026) — garder lastKnownTicketNo synchro avec la
        // clé réelle de delivery_summary, pour que logEvent()/logError()
        // appelés juste après (ex: dans logError()) utilisent la même clé.
        if (ticketNoReel != null && !ticketNoReel.trim().isEmpty()) {
            lastKnownTicketNo.put(ticketCacheKey(serialId, woNum), ticketNoReel.trim());
        }
    }

    private void logEvent(String serialId, String woNum, String level,
                           String type, String message, String dataJson) {
        if (deliveryStore == null || serialId == null || serialId.isEmpty()) return;
        try {
            // ✅ CORRIGÉ (27 août 2026, demande Paul) — utilisait woNum brut,
            // qui ne correspond plus à la clé delivery_summary une fois que
            // logDeliveryEnd() a re-clé la ligne sous le vrai ticket_no →
            // contrainte FOREIGN KEY échouait, attemptId=-1, événement
            // sauté. Résout via lastKnownTicketNo (alimenté par
            // pollJobUntilDone à chaque tick), avec repli sur woNum tant
            // que le vrai ticket n'est pas encore connu — à ce moment la
            // ligne existe encore sous woNum (créée par logDeliveryStart).
            String ticketKey = lastKnownTicketNo.getOrDefault(
                ticketCacheKey(serialId, woNum), woNum != null ? woNum : "DEEPLINK");
            deliveryStore.openAttemptAsync(
                serialId, ticketKey,
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

    // 📝 NOTE POUR FUTUR CHANTIER (11 août 2026, demande Paul — "dans
    // Dataverse on enverra que les choses principales et les
    // particularités car on veut intégrer l'IA dans la lecture des logs")
    //
    // Idée validée avec Paul, PAS ENCORE implémentée — juste notée ici pour
    // ne pas l'oublier :
    //
    // Plutôt que d'envoyer le bruit brut complet, envoyer vers Dataverse un
    // RÉSUMÉ STRUCTURÉ par livraison/session, dans une NOUVELLE TABLE
    // DATAVERSE DÉDIÉE (pas un champ JSON ajouté à une table existante) :
    //   - Les infos essentielles (ticket, WO, net/gross, timestamps, etc.)
    //   - Les "particularités" — définies avec Paul comme : niveau ERROR +
    //     WARN + changements d'état inhabituels (ex. déconnexions) — PAS le
    //     bruit de routine (STATUS répété, CUMUL-WO vide, etc.)
    //
    // Déclencheur : configurable, les trois options suivantes doivent être
    // possibles selon le besoin :
    //   1) À la fin de chaque livraison complétée
    //   2) Périodiquement pendant la session (ex. toutes les X minutes)
    //   3) Sur demande manuelle (bouton)
    //
    // But final : que ce résumé structuré (et non le log brut complet)
    // serve de base à une future lecture/analyse par IA — donc le format
    // doit rester assez condensé et structuré pour être digeste, plutôt
    // que de reproduire des milliers de lignes répétitives.
    //
    // Points à trancher avant de commencer l'implémentation : le schéma
    // exact de la nouvelle table Dataverse, et où précisément dans le code
    // brancher chacun des trois déclencheurs.

    void patchDataverse(String woGuid, String woNum,
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

        // ✅ CORRIGÉ (28 août 2026, demande Paul — "elle devient que
        // sécurité et ne doit avoir une deuxième ou Xième fois dans le wo
        // et dataverse") — trouvé : le repli utilisait un TIMESTAMP
        // (woNum + "-" + currentTimeMillis()), différent à chaque appel.
        // Si patchDataverse() est appelé deux fois pour la même livraison
        // (une fois automatique à la fin, une fois en filet de sécurité
        // au clic du bouton bleu), chaque appel générait sa PROPRE clé —
        // deux entrées séparées dans la file, potentiellement deux PATCH
        // distincts. Construit maintenant de façon déterministe
        // (wo_num + "-" + ticket) — même patron que pushDeliveryRow()
        // ailleurs — pour que les deux appels ciblent TOUJOURS la même
        // clé, garantissant un vrai UPSERT, jamais un doublon.
        final String fDeliveryUid = !deliveryUid.isEmpty()
            ? deliveryUid
            : (woNum != null ? woNum : "wo") + "-" + (ticket != null && !ticket.isEmpty() ? ticket : "0");

        try {
            DeliveryResultQueueDb queueDb = new DeliveryResultQueueDb(activity);
            // ✅ FIX : ce PATCH s'exécute automatiquement à la FIN DE CHAQUE
            // LIVRAISON (pas seulement via le bouton Retour WO) — c'était la
            // vraie cause de l'effacement de l'historique dans Field Service :
            // il appelait patchSummary() (écrase tout) au lieu de la version
            // consolidée (fusion+ETag) qu'on vient de construire pour
            // retournerAuWorkOrder(). Marqué "consolidated" pour que
            // DeliverySyncWorker utilise aussi la bonne méthode en cas de retry.
            JSONObject queuePayload = new JSONObject();
            queuePayload.put("consolidated", true);
            queuePayload.put("workOrderId", woGuid.replace("{", "").replace("}", ""));
            queuePayload.put("woNum",       woNum   != null ? woNum   : "");
            queuePayload.put("deliveryUid", fDeliveryUid);
            queueDb.upsertPending(fDeliveryUid, queuePayload.toString());
            android.util.Log.i(TAG, "patchDataverse: ajouté à la queue offline (consolidated)");
            com.pa.lcrdemo.dataverse.DeliverySyncScheduler.triggerNow(activity);
        } catch (Exception e) {
            android.util.Log.e(TAG, "Queue ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "DeepLinkHandler.Queue", e); } catch (Exception ignored) {}
        }

        // ✅ (fix 31 juillet 2026, découvert en validant l'exhaustivité du verrou global
        // demandé par Paul) — ce site MSAL avait été MANQUÉ lors de l'ajout initial du
        // verrou. Flux ASYNCHRONE (pas de CountDownLatch bloquant) — donc `.lock()` posé
        // ici et `.unlock()` posé dans CHACUN des 3 callbacks terminaux ci-dessous (succès
        // token, erreur token, erreur init), pas un simple bloc synchronized qui ne
        // protégerait rien à travers ces frontières asynchrones.
        try {
            MsalTokenProvider.MSAL_SERIAL_LOCK.acquire();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            android.util.Log.w(TAG, "patchDataverse: acquire() interrompu — abandon");
            return;
        }
        MsalTokenProvider tokenProvider = new MsalTokenProvider(activity);
        tokenProvider.init(new MsalTokenProvider.InitCallback() {
            @Override
            public void onReady() {
                tokenProvider.acquireTokenSilentFromWorker(new MsalTokenProvider.TokenCallback() {
                    @Override
                    public void onSuccess(String accessToken) {
                        // Token obtenu — l'état MSAL n'est plus en jeu, libérer le verrou
                        // avant l'envoi HTTP (qui peut prendre plusieurs secondes).
                        MsalTokenProvider.MSAL_SERIAL_LOCK.release();
                        safeExecute(() -> {
                            try {
                                // ✅ FIX : patchSummaryConsolidated() au lieu de patchSummary()
                                // — lit d'abord les livraisons locales fraîches (pas juste
                                // cette livraison-ci), pour que le PATCH fusionne avec
                                // l'historique existant côté Dataverse au lieu de l'écraser.
                                String guidClean = woGuid.replace("{", "").replace("}", "");
                                com.pa.lcr.lcp.storage.LcrDeliveryStatusDb lcrDb =
                                    new com.pa.lcr.lcp.storage.LcrDeliveryStatusDb(activity);
                                org.json.JSONArray livraisons = new org.json.JSONArray();
                                try {
                                    java.util.List<com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.DeliveryRow> rows =
                                        lcrDb.getAllForWo(woNum);
                                    for (com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.DeliveryRow r : rows) {
                                        if (r.netL > 0 || "ANNULATION".equals(r.type)) {
                                            org.json.JSONObject entry = new org.json.JSONObject();
                                            entry.put("ticket_no", r.ticketNo != null ? r.ticketNo : "");
                                            entry.put("net_l",     r.netL);
                                            entry.put("gross_l",   r.grossL);
                                            entry.put("type",      r.type != null ? r.type : "");
                                            entry.put("end_utc",   r.endUtc != null ? r.endUtc : "");
                                            livraisons.put(entry);
                                        }
                                    }
                                } finally {
                                    try { lcrDb.close(); } catch (Exception ignored) {}
                                }
                                if (livraisons.length() == 0) {
                                    // Filet de sécurité — au moins CETTE livraison si la BD locale
                                    // n'en connaît aucune pour une raison quelconque
                                    org.json.JSONObject entry = new org.json.JSONObject();
                                    entry.put("ticket_no", ticket != null ? ticket : "");
                                    entry.put("net_l",     net    != null ? Double.parseDouble(net)   : 0);
                                    entry.put("gross_l",   gross  != null ? Double.parseDouble(gross) : 0);
                                    entry.put("type",      "ORIGINAL");
                                    livraisons.put(entry);
                                }
                                WorkOrderUpdater.patchSummaryConsolidated(
                                    accessToken, guidClean, woNum, livraisons);
                                android.util.Log.i(TAG, "patchDataverse MSAL: OK (consolidated) — wonum=" + woNum);
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
                                android.util.Log.w(TAG, "patchDataverse PATCH ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "DeepLinkHandler.patchDataverse", e); } catch (Exception ignored) {}
                            }
                        });
                    }
                    @Override
                    public void onError(Exception e) {
                        MsalTokenProvider.MSAL_SERIAL_LOCK.release();
                        android.util.Log.w(TAG, "patchDataverse token ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "DeepLinkHandler.patchDataverse", e); } catch (Exception ignored) {}
                    }
                });
            }
            @Override
            public void onError(Exception e) {
                MsalTokenProvider.MSAL_SERIAL_LOCK.release();
                android.util.Log.w(TAG, "patchDataverse MSAL init ERR: " + e.getMessage()); try { com.pa.lcr.lcp.log.LogBus.err(0, "DeepLinkHandler.patchDataverse", e); } catch (Exception ignored) {}
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
