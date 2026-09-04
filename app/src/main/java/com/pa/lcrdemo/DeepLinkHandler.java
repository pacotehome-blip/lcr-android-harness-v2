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

            // ❌ RETIRÉ (2 sept 2026, demande Paul — "ben caliss pourquoi ton
            // correctif a eu un impact direct... j'entre pas sur filgo-registre")
            // — confirmé par log réel (nouveau_7.txt) : ce garde-fou était la
            // VRAIE CAUSE d'une boucle infernale, pas sa solution. Refus ->
            // retournerFieldService() -> finish() -> FieldService renvoie
            // immédiatement un nouveau deep link -> refus à nouveau -> boucle
            // sans fin, empêchant TOUTE entrée dans l'app tant que la fenêtre
            // de 60s n'était pas écoulée. La vraie cause du hijacking original
            // (Intent jamais consommé dans onCreate()/onNewIntent()) est déjà
            // corrigée séparément - ce filet de sécurité causait plus de tort
            // que de bien et a été retiré.

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
                boolean produitEstNumerique3 = false;
                try { iProduit = Integer.parseInt(produit); produitEstNumerique3 = true; } catch (Exception ignored) {}
                if (!produitEstNumerique3 && produit != null && !produit.trim().isEmpty()) {
                    try {
                        com.pa.lcr.lcp.storage.RegisterProductStore prodStoreArm4 =
                            new com.pa.lcr.lcp.storage.RegisterProductStore(activity);
                        com.pa.lcr.lcp.storage.RegisterProductStore.Row rowParNom3 =
                            prodStoreArm4.findByName(fSerialId, produit.trim());
                        if (rowParNom3 != null) iProduit = rowParNom3.noteIdx;
                    } catch (Exception ignored) {}
                }
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

        // ❌ RETIRÉ (2 sept 2026, demande Paul — confirmé par log réel
        // nouveau_7.txt) : ce garde-fou causait une boucle infernale
        // (refus -> retournerFieldService() -> finish() -> FieldService
        // renvoie le même deep link -> refus à nouveau), empêchant toute
        // entrée dans l'app. Retiré - la vraie cause du hijacking (Intent
        // jamais consommé) est corrigée séparément dans MainActivity.

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
                                    // ✅ CORRIGÉ (28 août 2026, demande Paul —
                                    // "si on arrive du deeplink, on doit
                                    // valider le produit sur le scan produit
                                    // du registre... si je reviens suite à un
                                    // crash [...] ce qui nécessite le scan
                                    // produit et sa validation") — trouvé :
                                    // ce point d'appel (tab pas encore créé,
                                    // typique d'un deep link arrivant sur un
                                    // tab neuf OU en reprise après crash)
                                    // utilisait la version 3-arg, sans
                                    // woIdGuid — la revalidation forcée du
                                    // produit (ajoutée plus tôt aujourd'hui
                                    // dans prefillFromDeepLink 4-arg) ne se
                                    // déclenchait donc JAMAIS pour ce chemin
                                    // précis. woIdGuid est déjà disponible
                                    // ici (ligne ~112), jamais réassigné.
                                    ((RegisterTabFragment) f).prefillFromDeepLink(
                                        fWoNum, woIdGuid, fProduit, fPresetStr);
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
        // ✅ CORRIGÉ (2 sept 2026, demande Paul — "on veut savoir si c'est
        // du propane, du gaz, du diesel... actuellement on envoie propane
        // en minuscule alors c'est facile à trouver") — trouvé : ce code
        // faisait TOUJOURS Integer.parseInt(produit) — si FieldService
        // envoie un texte ("propane"), le parsing échouait
        // SILENCIEUSEMENT (catch ignoré), retombant TOUJOURS sur le
        // défaut codé en dur (produit=1), peu importe le vrai produit
        // demandé. resolveProduct()/findByName() (RegisterProductStore)
        // gèrent déjà ce cas correctement (insensible à la casse) —
        // jamais utilisés ici, seulement pour la validation ailleurs.
        int product = 1;
        double preset = 0.0;
        boolean produitEstNumerique = false;
        try { product = Integer.parseInt(produit); produitEstNumerique = true; } catch (Exception ignored) {}
        if (!produitEstNumerique && produit != null && !produit.trim().isEmpty()) {
            try {
                com.pa.lcr.lcp.storage.RegisterProductStore prodStoreArm2 =
                    new com.pa.lcr.lcp.storage.RegisterProductStore(activity);
                com.pa.lcr.lcp.storage.RegisterProductStore.Row rowParNom =
                    prodStoreArm2.findByName(serialId, produit.trim());
                if (rowParNom != null) {
                    product = rowParNom.noteIdx;
                    android.util.Log.i(TAG, "lancerLivraison: produit texte \"" + produit
                        + "\" résolu vers noteIdx=" + product + " (\"" + rowParNom.description + "\")");
                } else {
                    android.util.Log.w(TAG, "lancerLivraison: produit texte \"" + produit
                        + "\" introuvable parmi les produits scannés — défaut=1 utilisé");
                }
            } catch (Exception e) {
                android.util.Log.w(TAG, "lancerLivraison: résolution produit par nom ERR (non-bloquant): " + e.getMessage());
            }
        }
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

            // ❌ RETIRÉ (4 sept 2026, demande Paul — "l'idee est que
            // j'arrive avec un nouveau wo... il ne devrait pas etre la tant
            // que nous n'avons pas passe au travers des 7 inits et avoir
            // demarre une nouvelle livraison") — ce check se declenchait
            // AVANT meme la tentative d'armement (avant activateExclusive)
            // — a ce moment, aucune nouvelle livraison n'existe encore pour
            // ce WO, donc tout bit "preset atteint" lu ici ne peut venir
            // que d'un reste PHYSIQUE du registre appartenant a une AUTRE
            // livraison (le meme registre sert plusieurs WO dans la
            // journee) — pas pertinent pour un armement qui n'a pas encore
            // eu lieu. Retiré de cette position. Si ce genre d'avertissement
            // doit exister, il doit se trouver APRES les 7 inits, une fois
            // qu'une nouvelle livraison est vraiment demarree pour CE WO —
            // pas comme garde-fou pre-armement.


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

            // ✅ AJOUTÉ (28 août 2026, demande Paul — "pas encore réglé le
            // ✅ CORRIGÉ (28 août 2026, demande Paul — "qu'est-ce qui
            // arrive si on pousse le mauvais produit genre du gaz dans du
            // diesel... c'est nous qui armons la livraison, repousse tant
            // que nous n'avons pas validé le produit") — trouvé : mon
            // correctif précédent attendait jusqu'à 3s PUIS ARMAIT QUAND
            // MÊME si la validation n'avait pas conclu — un vrai risque
            // de sécurité (mauvais produit livré), pas juste un
            // désagrément esthétique comme les autres "DÉGRADÉ après 3
            // tentatives" ailleurs dans l'init. Ici : REFUS complet de
            // l'armement si la validation n'a pas conclu après une
            // attente raisonnable (10s, le temps normal d'un vrai scan
            // matériel) — jamais de contournement silencieux. Retourne
            // vers FieldService avec une erreur claire, même patron déjà
            // établi pour les autres échecs génuinement bloquants
            // (POLL_TIMEOUT).
            boolean produitValideAvantArmement = false;
            RegisterTabFragment tabArmRef = null;
            try {
                String mediaShortArm = activity.mediaShortFromTransportKey(transportKey);
                String tabKeyArm = activity.tabKeyOf(mediaShortArm, node, serialId);
                Fragment fArm = activity.getSupportFragmentManager().findFragmentByTag("regtab_" + tabKeyArm);
                if (fArm instanceof RegisterTabFragment) {
                    tabArmRef = (RegisterTabFragment) fArm;
                    for (int waitProduit = 0; waitProduit < 100; waitProduit++) {
                        if (tabArmRef.isPeutDemarrerLivraison()) { produitValideAvantArmement = true; break; }
                        try { Thread.sleep(100); } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}

            if (!produitValideAvantArmement) {
                android.util.Log.w(TAG, "lancerLivraison: REFUS armement — initialisation (7 étapes) jamais approuvée après 10s");
                logError(serialId, woNum, "INIT_NON_APPROUVEE", "Initialisation jamais complétée avant armement (registre/produit/preset/live/retour_wo) — livraison refusée par sécurité");
                retournerFieldService(woNum, woIdGuid, "erreur_init_non_approuvee",
                    buildErrorJson("INIT_NON_APPROUVEE", "L'initialisation n'a pas pu se compléter avant l'armement — livraison refusée par sécurité. Réessayez, ou vérifiez le registre."));
                return;
            }

            // ✅ AJOUTÉ (28 août 2026, demande Paul — "tu as oublié la
            // règle si c'est pas le bon produit on cancel la livraison
            // defacto") — la validation a CONCLU, mais a-t-elle trouvé le
            // BON produit ? Même règle déjà établie pour le bouton local
            // (startNewDeliveryC()), jamais répliquée ici — annulation
            // franche, pas un défaut silencieux sur un produit différent
            // de celui attendu par FieldService.
            if (tabArmRef != null) {
                String raisonMismatch = tabArmRef.getProduitDeepLinkIntrouvableRaison();
                if (raisonMismatch != null && !raisonMismatch.isEmpty()) {
                    android.util.Log.w(TAG, "lancerLivraison: REFUS armement — mismatch produit: " + raisonMismatch);
                    logError(serialId, woNum, "PRODUIT_MISMATCH", raisonMismatch);
                    retournerFieldService(woNum, woIdGuid, "erreur_produit_mismatch",
                        buildErrorJson("PRODUIT_MISMATCH", raisonMismatch));
                    return;
                }
            }

            // ✅ CORRIGÉ (2 sept 2026, demande Paul — "je dirais même que le
            // running_flowing ne devrait jamais démarrer si j'ai pas le
            // sales_number et mais si j'ai le ticket_number c'est bon") —
            // trouvé (en validant mon propre correctif) : api_readTicketNo23Frais()
            // ne convient PAS ici — elle a déjà un repli horodatage
            // intégré qui masque l'échec au lieu de le révéler. Utilise
            // maintenant api_readSaleNumberRaw() (vraie lecture brute,
            // sans repli), avec acceptation si un ticket_number est déjà
            // connu (cas de reprise), exactement comme demandé.
            try {
                // ✅ AJOUTÉ (2 sept 2026, en élargissant la vérification
                // comme demandé) — trouvé : ce garde-fou n'avait AUCUN
                // retry, contrairement à la validation produit (jusqu'à
                // 10s). Une simple lenteur temporaire de communication LCP
                // (pas un vrai registre réinitialisé) aurait causé un
                // faux refus. 3 tentatives, court délai, avant de refuser.
                String saleRaw = null;
                for (int retrySale = 0; retrySale < 3 && saleRaw == null; retrySale++) {
                    try { saleRaw = controllerOneshot.api_readSaleNumberRaw(); } catch (Exception ignoredSale) {}
                    if (saleRaw == null && retrySale < 2) { try { Thread.sleep(300); } catch (Exception ignored) {} }
                }
                boolean saleOk = saleRaw != null && !saleRaw.trim().isEmpty() && !"0".equals(saleRaw.trim());
                String ticketDejaConnu = (tabArmRef != null) ? tabArmRef.getLastKnownTicketNo() : null;
                boolean ticketOk = ticketDejaConnu != null && !ticketDejaConnu.trim().isEmpty();
                if (!saleOk && !ticketOk) {
                    android.util.Log.w(TAG, "lancerLivraison: REFUS armement — ni sale_number ni ticket_number déjà connu");
                    logError(serialId, woNum, "SALE_NUMBER_INDISPONIBLE",
                        "Ni sale_number lisible ni ticket_number déjà connu avant armement");
                    retournerFieldService(woNum, woIdGuid, "erreur_sale_number_indisponible",
                        buildErrorJson("SALE_NUMBER_INDISPONIBLE",
                            "Impossible de lire sale_number sur le registre et aucun ticket_number déjà connu — armement refusé"));
                    return;
                }
            } catch (Exception ePreArm) {
                android.util.Log.w(TAG, "lancerLivraison: REFUS armement — vérification sale_number ERR: " + ePreArm.getMessage());
                logError(serialId, woNum, "SALE_NUMBER_INDISPONIBLE", "Vérification sale_number ERR: " + ePreArm.getMessage());
                retournerFieldService(woNum, woIdGuid, "erreur_sale_number_indisponible",
                    buildErrorJson("SALE_NUMBER_INDISPONIBLE", "Vérification sale_number/ticket_number a échoué"));
                return;
            }

            // ✅ RECONSTRUIT (4 sept 2026, demande Paul — "juste avant
            // l'armement car là on a un nouveau delivery-uid c'est clair
            // ça non??") — trouvé le vrai bon endroit après 3 essais : ni
            // avant le garde-fou sale_number (lisait un reste physique
            // d'une AUTRE livraison, aucun delivery-uid propre encore
            // établi), ni après l'armement complet (trop tard, complexité
            // inutile). C'est ICI, juste après confirmation d'un
            // sale_number frais (donc un delivery-uid propre à CETTE
            // livraison), juste avant l'armement lui-même, que le check a
            // vraiment sa place.
            try {
                int dcPreArm = controllerOneshot.getLastDelCode();
                boolean presetDejaAtteintArm =
                    (dcPreArm & com.pa.lcr.lcp.LcpLink.DC_NET_PRESET_REACHED) != 0
                    || (dcPreArm & com.pa.lcr.lcp.LcpLink.DC_GROSS_PRESET_REACHED) != 0;
                // ✅ CORRIGÉ (4 sept 2026, demande Paul — "c'est le dernier
                // test") — trouvé : mes logs de diagnostic utilisaient
                // android.util.Log, invisible dans le résumé LogBus que
                // Paul partage — impossible de confirmer si ce code
                // s'exécutait vraiment. Remplacé par LogBus.api() partout
                // ici, avec un vrai log dès l'entrée, avant même de savoir
                // si le dialogue va se déclencher.
                com.pa.lcr.lcp.log.LogBus.api(node, "[PRESET-CHECK] avant armement — wo=" + woNum + " delCode=0x"
                    + Integer.toHexString(dcPreArm) + " presetDejaAtteint=" + presetDejaAtteintArm);
                if (presetDejaAtteintArm) {
                    com.pa.lcr.lcp.storage.LcrDeliveryStatusDb statusDbArm =
                        new com.pa.lcr.lcp.storage.LcrDeliveryStatusDb(activity);
                    com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.DeliveryRow existingArm;
                    try {
                        existingArm = statusDbArm.getLatestForWo(woNum);
                    } finally {
                        try { statusDbArm.close(); } catch (Exception ignored) {}
                    }
                    com.pa.lcr.lcp.log.LogBus.api(node, "[PRESET-CHECK] getLatestForWo(" + woNum + ") = "
                        + (existingArm == null ? "AUCUNE ligne trouvée" : "ligne trouvée, ticket=" + existingArm.ticketNo));
                    if (existingArm == null) {
                        com.pa.lcr.lcp.log.LogBus.api(node, "[PRESET-CHECK] AUCUNE ligne pour wo="
                            + woNum + " — reste d'un AUTRE wo, dialogue non déclenché");
                        presetDejaAtteintArm = false;
                    }
                }
                if (presetDejaAtteintArm) {
                    com.pa.lcr.lcp.log.LogBus.api(node, "[PRESET-CHECK] DÉCLENCHEMENT du dialogue (delCode=0x"
                        + Integer.toHexString(dcPreArm) + ") — confirmation requise avant armement");
                    final java.util.concurrent.CountDownLatch latchPresetArm = new java.util.concurrent.CountDownLatch(1);
                    final boolean[] continuerPresetArm = {false};
                    activity.runOnUiThread(() -> {
                        new android.app.AlertDialog.Builder(activity)
                            .setTitle("Preset déjà atteint")
                            .setMessage("Le registre indique que le preset est déjà atteint.\n\n"
                                + "Voulez-vous quand même armer une nouvelle livraison ?")
                            .setPositiveButton("Continuer", (d, w) -> {
                                continuerPresetArm[0] = true;
                                latchPresetArm.countDown();
                            })
                            .setNegativeButton("Annuler", (d, w) -> {
                                continuerPresetArm[0] = false;
                                latchPresetArm.countDown();
                            })
                            .setCancelable(false)
                            .show();
                    });
                    try { latchPresetArm.await(); } catch (InterruptedException ignored) {}
                    logEvent(fSerialId, woNum,
                        continuerPresetArm[0] ? DeliveryLogStore.LEVEL_INFO : DeliveryLogStore.LEVEL_WARN,
                        continuerPresetArm[0] ? "PRESET_DEJA_ATTEINT_CONTINUE" : "PRESET_DEJA_ATTEINT_ANNULE",
                        "delCode=0x" + Integer.toHexString(dcPreArm) + " — chauffeur a choisi "
                            + (continuerPresetArm[0] ? "CONTINUER" : "ANNULER"), null);
                    if (!continuerPresetArm[0]) {
                        android.util.Log.i(TAG, "lancerLivraison: annulé par le chauffeur (preset déjà atteint)");
                        activity.runOnUiThread(() -> activity.showPage(0));
                        return;
                    }
                }
            } catch (Exception ePresetArm) {
                android.util.Log.w(TAG, "lancerLivraison: erreur vérif preset atteint — " + ePresetArm.getMessage());
            }

            // ✅ AJOUTÉ (4 sept 2026, demande Paul — "corrige ça", confirmé
            // par log réel LogBus : applierDescriptionsProduits() interrompue
            // en plein vol, seulement 472ms après son début, avant l'armement
            // physique) — cette résolution tourne en fire-and-forget
            // (safeBg), jamais attendue par l'armement. Vraie attente
            // courte ici, bornée (jamais indéfinie), avant de procéder —
            // laisse une vraie chance à la résolution de finir.
            if (tabArmRef != null && !tabArmRef.produitDejaResoluPourCetteSession) {
                for (int iAttenteProduit = 0; iAttenteProduit < 8; iAttenteProduit++) { // ~800ms max
                    if (tabArmRef.produitDejaResoluPourCetteSession) break;
                    try { Thread.sleep(100); } catch (InterruptedException ignored) { break; }
                }
                com.pa.lcr.lcp.log.LogBus.api(node, "[PRODUIT-ATTENTE] avant armement — résolu="
                    + tabArmRef.produitDejaResoluPourCetteSession);
            }

            if (tabArmRef != null) {
                tabArmRef.armementEnCoursParCetteSession = true;
                // ✅ AJOUTÉ (2 sept 2026, en validant mon propre correctif)
                // — filet de sécurité temporel : aucun try/catch n'entoure
                // l'appel d'armement lui-même (juste en dessous) — une
                // vraie exception non prévue laisserait ce drapeau bloqué
                // à true pour toujours, empêchant définitivement l'init de
                // ce tab. Levée automatique après 30s, peu importe ce qui
                // se passe entre-temps — le finally normal (plus loin)
                // lève le drapeau bien avant dans le cas normal, ce filet
                // ne sert que si tout le reste échoue silencieusement.
                final RegisterTabFragment tabArmRefFinal = tabArmRef;
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    tabArmRefFinal.armementEnCoursParCetteSession = false;
                }, 30000);
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
                        // ✅ AJOUTÉ (28 août 2026, demande Paul — "tu as
                        // tout pour récupérer sur un crash ou bd
                        // vierge... le sales_number qui s'applique
                        // aussitôt qu'on fait running_flowing") — trouvé :
                        // ticket_no/sale_no restaient vides à l'armement,
                        // alors que sale_number (contrairement au vrai
                        // ticket_number, qui exige une impression) est
                        // lisible presque immédiatement dès que le flow
                        // démarre. Lecture fraîche ici, même fonction
                        // déjà utilisée ailleurs (repli sale_number déjà
                        // intégré dans readTicketNo23() lui-même).
                        // ✅ CORRIGÉ (2 sept 2026, demande Paul — "avant
                        // d'armer une livraison on doit lire le
                        // ticket_number et/ou le sales_number. si
                        // l'impression est obligatoire on attend la fin
                        // de la livraison pour avoir le ticket_number, si
                        // l'impression n'est pas obligatoire tu utilises
                        // comme ticket_number le sales_number") — trouvé
                        // (en corrigeant ma propre erreur) : tester
                        // "ticketArm.isEmpty()" ne fonctionne JAMAIS —
                        // api_readTicketNo23Frais() a son propre repli
                        // horodatage interne, jamais vide. Vraie décision
                        // binaire ici : impression obligatoire → ticketArm
                        // reste tel quel (le vrai ticket viendra à la
                        // fin) ; pas obligatoire → sale_number (déjà lu
                        // et validé par le garde-fou) sert directement de
                        // ticket_number, sans attendre.
                        String ticketArm;
                        boolean impressionObligatoireArm = true;
                        try { impressionObligatoireArm = !controllerOneshot.api_isTicketRequiredNeverPrint(); } catch (Exception ignoredReqArm) {}
                        if (impressionObligatoireArm) {
                            ticketArm = "";
                            android.util.Log.i(TAG, "lancerLivraison: impression obligatoire — ticket_number attendu à la fin de la livraison");
                        } else {
                            String saleFallbackArm = null;
                            try { saleFallbackArm = controllerOneshot.api_readSaleNumberRaw(); } catch (Exception ignoredSaleArm) {}
                            ticketArm = (saleFallbackArm != null && !saleFallbackArm.trim().isEmpty() && !"0".equals(saleFallbackArm.trim()))
                                ? saleFallbackArm.trim() : "";
                            android.util.Log.i(TAG, "lancerLivraison: impression non obligatoire — sale_number utilisé comme ticket_number=" + ticketArm);
                        }
                        // besoin d'attendre une éventuelle récupération
                        // ultérieure pour les capturer. description/code/
                        // type recherchés via RegisterProductStore, même
                        // principe déjà établi dans la fonction de
                        // récupération.
                        String descArm = "";
                        String codeArm = "";
                        int typeArm = -1;
                        try {
                            com.pa.lcr.lcp.storage.RegisterProductStore prodStoreArm =
                                new com.pa.lcr.lcp.storage.RegisterProductStore(activity);
                            java.util.List<com.pa.lcr.lcp.storage.RegisterProductStore.Row> lignesArm =
                                prodStoreArm.getAll(fSerialId, node);
                            android.util.Log.i(TAG, "Recherche produit (armement) — serialId=\"" + fSerialId
                                + "\" node=" + node + " → " + lignesArm.size() + " ligne(s) trouvée(s)");
                            // ✅ AJOUTÉ (28 août 2026, demande Paul — "ou est
                            // le code produit, ou est le type de produit...
                            // j'arrive d'un running flowing et j'ai pas ça")
                            // — trouvé (fichier réel : active_product=1
                            // présent, mais description/code/type tous
                            // vides) : getAll(serialId, node) peut revenir
                            // vide si node ne correspond pas exactement à
                            // celui utilisé lors de l'écriture réelle
                            // (store.upsertAll dans api_scanProductNames()).
                            // Repli sur getAll(serialId) SANS node — déjà
                            // prévu dans RegisterProductStore lui-même pour
                            // ce genre de cas.
                            if (lignesArm.isEmpty()) {
                                lignesArm = prodStoreArm.getAll(fSerialId);
                                android.util.Log.i(TAG, "Recherche produit (armement) — repli sans node → "
                                    + lignesArm.size() + " ligne(s) trouvée(s)");
                            }
                            for (com.pa.lcr.lcp.storage.RegisterProductStore.Row ligneArm : lignesArm) {
                                // ✅ CORRIGÉ (2 sept 2026, demande Paul —
                                // "je n'ai pas dans la section init...
                                // comment veux-tu reconstruire la
                                // livraison") — trouvé le VRAI bug :
                                // noteIdx = idx + 1 (1-indexé, voir
                                // LcpLink.java) — cette comparaison
                                // soustrayait 1 à tort ("noteIdx ==
                                // fProduct - 1"), ne trouvant JAMAIS la
                                // bonne ligne, dans TOUS les points
                                // d'écriture d'aujourd'hui (armement, fin
                                // normale, récupération, annulation,
                                // retour WO) — confirmé par les deux
                                // fichiers réels de Paul, description/
                                // code/type vides dans les deux, malgré
                                // le scan ayant réussi.
                                if (ligneArm.noteIdx == fProduct) {
                                    descArm = ligneArm.description;
                                    codeArm = ligneArm.productCode;
                                    typeArm = ligneArm.productType;
                                    break;
                                }
                            }
                        } catch (Exception e) {
                            android.util.Log.w(TAG, "Recherche produit (armement) ERR (non-bloquant): " + e.getMessage());
                        }
                        android.content.ContentValues cvArm =
                            com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.construireLivraisonComplete(
                                jobId, woNum, woIdGuid, ticketArm, ticketArm,
                                0.0, 0.0, fSerialId, node, fMacPourArm,
                                fProduct, descArm, codeArm, typeArm, fPresetD,
                                com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.TYPE_ORIGINAL,
                                "RUNNING_FLOWING",
                                com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.SYNC_PENDING,
                                "{\"status\":\"RUNNING_FLOWING\",\"job_id\":\"" + jobId + "\"}");
                        cvArm.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_SOURCE, "ARMEMENT");
                        com.pa.lcr.lcp.storage.LcrDeliveryStatusDb dbArm =
                            new com.pa.lcr.lcp.storage.LcrDeliveryStatusDb(activity);
                        try {
                            dbArm.upsertByJobId(cvArm);
                        } finally {
                            try { dbArm.close(); } catch (Exception ignored) {}
                        }
                        android.util.Log.i(TAG, "Livraison enregistrée dès l'armement — jobId=" + jobId + " wo=" + woNum
                            + " produit=" + fProduct + " preset=" + fPresetD);

                        org.json.JSONObject backupPayloadArm =
                            com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.construireJsonLivraisonComplet(
                                jobId, woNum, woIdGuid, ticketArm, ticketArm,
                                0.0, 0.0, fSerialId, node, fMacPourArm,
                                fProduct, descArm, codeArm, typeArm, fPresetD,
                                com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.TYPE_ORIGINAL,
                                com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.SYNC_PENDING,
                                "{\"status\":\"RUNNING_FLOWING\",\"job_id\":\"" + jobId + "\"}");
                        com.pa.lcr.lcp.storage.LocalDeliveryBackup.backupDeliveryAsync(
                            activity.getApplicationContext(), woNum, jobId, backupPayloadArm);
                    } catch (Exception e) {
                        android.util.Log.w(TAG, "Enregistrement initial (armement) ERR (non-bloquant): " + e.getMessage());
                    } finally {
                        if (tabArmRef != null) tabArmRef.armementEnCoursParCetteSession = false;
                    }

                    activity.runOnUiThread(() ->
                        activity.toast("📦 Livraison démarrée — " + woNum));
                    pollJobUntilDone(jobId, node, woNum, woIdGuid, fSerialId,
                        fMac.isEmpty() ? transportKey : fMac, true);
                }
            } else {
                if (tabArmRef != null) tabArmRef.armementEnCoursParCetteSession = false;
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

        // ✅ AJOUTÉ (2 sept 2026, demande Paul — "corrige le") — même
        // protection que lancerLivraison(), un seul processus de
        // livraison, pas un chemin protégé et l'autre pas.
        // ❌ RETIRÉ (2 sept 2026, demande Paul — confirmé par log réel
        // nouveau_7.txt) : ce garde-fou causait une boucle infernale
        // (refus -> retournerFieldService() -> finish() -> FieldService
        // renvoie le même deep link -> refus à nouveau), empêchant toute
        // entrée dans l'app. Retiré - la vraie cause du hijacking (Intent
        // jamais consommé) est corrigée séparément dans MainActivity.

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
                final String fWoIdGuid     = woIdGuid;
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
                                    // ✅ CORRIGÉ (28 août 2026, même correctif
                                    // que le premier point d'appel plus haut)
                                    // — même trou, même cause.
                                    ((RegisterTabFragment) f).prefillFromDeepLink(
                                        fWoNum, fWoIdGuid, fProduit, fPreset);
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
                boolean produitEstNumerique2 = false;
                try { product = Integer.parseInt(produit); produitEstNumerique2 = true; } catch (Exception ignored) {}
                if (!produitEstNumerique2 && produit != null && !produit.trim().isEmpty()) {
                    try {
                        com.pa.lcr.lcp.storage.RegisterProductStore prodStoreArm3 =
                            new com.pa.lcr.lcp.storage.RegisterProductStore(activity);
                        com.pa.lcr.lcp.storage.RegisterProductStore.Row rowParNom2 =
                            prodStoreArm3.findByName(serialId, produit.trim());
                        if (rowParNom2 != null) {
                            product = rowParNom2.noteIdx;
                            android.util.Log.i(TAG, "connectBtByMacAndOpenTab: produit texte \"" + produit
                                + "\" résolu vers noteIdx=" + product);
                        }
                    } catch (Exception e) {
                        android.util.Log.w(TAG, "connectBtByMacAndOpenTab: résolution produit par nom ERR (non-bloquant): " + e.getMessage());
                    }
                }
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
                        // ✅ CORRIGÉ (2 sept 2026, demande Paul) — même
                        // correction que lancerLivraison() :
                        // api_readTicketNo23Frais() masquait l'échec via
                        // son repli horodatage intégré. Utilise
                        // api_readSaleNumberRaw() (vraie lecture, sans
                        // repli). Pas de repli ticket_number ici — ce
                        // chemin crée systématiquement un tab neuf, sans
                        // historique de ticket connu.
                        try {
                            com.pa.lcr.lcp.DeliveryController controllerPreArm2 =
                                com.pa.lcr.lcp.RegisterSessionManager.get(activity).getController(fTransportKey, node);
                            String saleRaw2 = null;
                            for (int retrySale2 = 0; retrySale2 < 3 && saleRaw2 == null; retrySale2++) {
                                try { saleRaw2 = (controllerPreArm2 != null) ? controllerPreArm2.api_readSaleNumberRaw() : null; } catch (Exception ignoredSale2b) {}
                                if (saleRaw2 == null && retrySale2 < 2) { try { Thread.sleep(300); } catch (Exception ignored) {} }
                            }
                            boolean saleOk2 = saleRaw2 != null && !saleRaw2.trim().isEmpty() && !"0".equals(saleRaw2.trim());
                            if (!saleOk2) {
                                android.util.Log.w(TAG, "connectBtByMacAndOpenTab: REFUS armement — sale_number non lisible");
                                logError(serialId, woNum, "SALE_NUMBER_INDISPONIBLE",
                                    "sale_number non lisible sur le registre avant armement");
                                retournerFieldService(woNum, woIdGuid, "erreur_sale_number_indisponible",
                                    buildErrorJson("SALE_NUMBER_INDISPONIBLE",
                                        "Impossible de lire sale_number sur le registre — armement refusé"));
                                return;
                            }
                        } catch (Exception ePreArm2) {
                            android.util.Log.w(TAG, "connectBtByMacAndOpenTab: REFUS armement — vérification sale_number ERR: " + ePreArm2.getMessage());
                            retournerFieldService(woNum, woIdGuid, "erreur_sale_number_indisponible",
                                buildErrorJson("SALE_NUMBER_INDISPONIBLE", "Vérification sale_number a échoué"));
                            return;
                        }

                        MultiRegisterApiFacadeImpl facade =
                            new MultiRegisterApiFacadeImpl(activity);
                        // ✅ AJOUTÉ (2 sept 2026, en revérifiant au complet
                        // comme demandé) — ce chemin n'avait PAS le
                        // drapeau armementEnCoursParCetteSession posé pour
                        // lancerLivraison() — même course possible ici,
                        // jamais couverte. Récupère le fragment (créé plus
                        // haut par upsertRegisterTabFromScan) et pose le
                        // même drapeau, avec le même filet de sécurité
                        // temporel (30s).
                        RegisterTabFragment tabArmRef2 = null;
                        try {
                            String mediaShortArm2 = activity.mediaShortFromTransportKey(fTransportKey);
                            String tabKeyArm2 = activity.tabKeyOf(mediaShortArm2, node, serialId);
                            Fragment fArm2 = activity.getSupportFragmentManager()
                                .findFragmentByTag("regtab_" + tabKeyArm2);
                            if (fArm2 instanceof RegisterTabFragment) {
                                tabArmRef2 = (RegisterTabFragment) fArm2;
                            }
                        } catch (Exception ignoredArm2) {}
                        if (tabArmRef2 != null) {
                            tabArmRef2.armementEnCoursParCetteSession = true;
                            final RegisterTabFragment tabArmRef2Final = tabArmRef2;
                            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                                tabArmRef2Final.armementEnCoursParCetteSession = false;
                            }, 30000);
                        }
                        final RegisterTabFragment tabArmRef2ForFinally = tabArmRef2;
                        com.pa.lcr.lcp.ApiResult r = facade.api_deliveryOneShotStart(
                            node, 255, woNum, fProduct, fPresetD, null, "bt", mac);

                        android.util.Log.i(TAG,
                            "oneshot/start: code=" + r.code + " msg=" + r.msg);

                        // ✅ AJOUTÉ (2 sept 2026, demande Paul — "il faut
                        // que ça se rende dans la bd locale et les
                        // fichiers json... on veut reconstruire la
                        // livraison si on redémarre l'application ou si
                        // on a arrive sur une bd vierge") — trouvé : ce
                        // chemin d'armement (connectBtByMacAndOpenTab)
                        // armait le registre mais n'écrivait JAMAIS en
                        // BD/JSON locale — aucun filet de sécurité
                        // initial, contrairement à lancerLivraison().
                        // Même patron exact, job_id généré ici puisque ce
                        // chemin n'en avait jamais eu.
                        try {
                            String jobIdConnect = java.util.UUID.randomUUID().toString();
                            String ticketArmConnect = "";
                            try {
                                com.pa.lcr.lcp.DeliveryController controllerConnect =
                                    com.pa.lcr.lcp.RegisterSessionManager.get(activity).getController(fTransportKey, node);
                                if (controllerConnect != null) {
                                    // ✅ CORRIGÉ (2 sept 2026, demande Paul —
                                    // même règle que les autres chemins :
                                    // impression obligatoire → attendre la
                                    // fin ; sinon → sale_number sert de
                                    // ticket_number, immédiatement.
                                    boolean impressionObligatoireConnect = !controllerConnect.api_isTicketRequiredNeverPrint();
                                    if (!impressionObligatoireConnect) {
                                        String saleFallbackConnect = controllerConnect.api_readSaleNumberRaw();
                                        if (saleFallbackConnect != null && !saleFallbackConnect.trim().isEmpty() && !"0".equals(saleFallbackConnect.trim())) {
                                            ticketArmConnect = saleFallbackConnect.trim();
                                        }
                                    }
                                }
                            } catch (Exception ignored) {}
                            String descConnect = "";
                            String codeConnect = "";
                            int typeConnect = -1;
                            try {
                                com.pa.lcr.lcp.storage.RegisterProductStore prodStoreConnect =
                                    new com.pa.lcr.lcp.storage.RegisterProductStore(activity);
                                java.util.List<com.pa.lcr.lcp.storage.RegisterProductStore.Row> lignesConnect =
                                    prodStoreConnect.getAll(serialId, node);
                                if (lignesConnect.isEmpty()) lignesConnect = prodStoreConnect.getAll(serialId);
                                for (com.pa.lcr.lcp.storage.RegisterProductStore.Row ligneConnect : lignesConnect) {
                                    if (ligneConnect.noteIdx == fProduct) {
                                        descConnect = ligneConnect.description;
                                        codeConnect = ligneConnect.productCode;
                                        typeConnect = ligneConnect.productType;
                                        break;
                                    }
                                }
                            } catch (Exception ignored) {}
                            android.content.ContentValues cvConnect =
                                com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.construireLivraisonComplete(
                                    jobIdConnect, woNum, fWoIdGuid, ticketArmConnect, ticketArmConnect,
                                    0.0, 0.0, serialId, node, mac != null ? mac : "",
                                    fProduct, descConnect, codeConnect, typeConnect, fPresetD,
                                    com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.TYPE_ORIGINAL,
                                    "RUNNING_FLOWING",
                                    com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.SYNC_PENDING,
                                    "{\"status\":\"RUNNING_FLOWING\",\"job_id\":\"" + jobIdConnect + "\"}");
                            com.pa.lcr.lcp.storage.LcrDeliveryStatusDb dbConnect =
                                new com.pa.lcr.lcp.storage.LcrDeliveryStatusDb(activity);
                            try {
                                dbConnect.upsertByJobId(cvConnect);
                            } finally {
                                try { dbConnect.close(); } catch (Exception ignored) {}
                            }
                            org.json.JSONObject jsonConnect =
                                com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.construireJsonLivraisonComplet(
                                    jobIdConnect, woNum, fWoIdGuid, ticketArmConnect, ticketArmConnect,
                                    0.0, 0.0, serialId, node, mac != null ? mac : "",
                                    fProduct, descConnect, codeConnect, typeConnect, fPresetD,
                                    com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.TYPE_ORIGINAL,
                                    com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.SYNC_PENDING,
                                    "{\"status\":\"RUNNING_FLOWING\",\"job_id\":\"" + jobIdConnect + "\"}");
                            com.pa.lcr.lcp.storage.LocalDeliveryBackup.backupDeliveryAsync(
                                activity.getApplicationContext(), woNum, jobIdConnect, jsonConnect);
                            android.util.Log.i(TAG, "connectBtByMacAndOpenTab: livraison enregistrée dès l'armement — jobId=" + jobIdConnect);
                        } catch (Exception e) {
                            android.util.Log.w(TAG, "connectBtByMacAndOpenTab: backup armement ERR (non-bloquant): " + e.getMessage());
                        } finally {
                            if (tabArmRef2ForFinally != null) tabArmRef2ForFinally.armementEnCoursParCetteSession = false;
                        }

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
    // ✅ AJOUTÉ (2 sept 2026, demande Paul — "il faut respecter le
    // processus de livraison c'pas compliqué") — trouvé : la récupération
    // (tenterRecuperationRunningFlowing) s'était déclenchée pour une
    // livraison que CETTE MÊME SESSION suivait déjà activement via
    // pollJobUntilDone() (le vrai suivi, depuis son propre armement) —
    // deux mécanismes différents pour la même livraison, en parallèle.
    // La récupération ne sert que pour un vrai état perdu (crash,
    // BD vierge) — jamais pour une livraison déjà suivie normalement.
    public static boolean isPollActif() {
        return !activePolls.isEmpty();
    }

    // ❌ RETIRÉ (4 sept 2026, demande Paul — "as-tu retiré tes changements
    // pour rétablir comme avant") — le garde-fou des 60 secondes
    // (derniereFinLivraisonParWo/marquerLivraisonTerminee/
    // armementRecentRefuse) avait déjà été retiré de ses 3 points d'appel
    // le 2 sept (confirmé être la vraie cause d'une boucle infernale,
    // pas sa solution — voir historique git). Les fonctions elles-mêmes
    // restaient, code mort : marquerLivraisonTerminee() était encore
    // appelée à 2 endroits sans jamais être consultée par
    // armementRecentRefuse() (elle-même jamais appelée nulle part).
    // Toute la structure retirée proprement.

    // 🪦 CODE MORT — CANDIDAT (4 sept 2026, demande Paul — "marque-les
    // comme mort, on fera une tâche de repérage systématique de tout le
    // code mort de l'apk plus tard, associer les fonctions/méthodes pour
    // voir ce qui est utilisé ou pas") — armementRecentRefuse() n'est
    // JAMAIS appelée nulle part (retirée de ses 3 points d'appel le
    // 2 sept, confirmée être la vraie cause d'une boucle infernale, pas
    // sa solution). marquerLivraisonTerminee() est encore appelée à 2
    // endroits (DeepLinkHandler ligne ~2876, RegisterTabFragment ligne
    // ~4682), mais son seul effet (remplir derniereFinLivraisonParWo)
    // n'est plus jamais consulté par personne. Restaurées ici (pas
    // supprimées) pour ne pas casser la compilation des 2 appels
    // existants — à retirer ensemble (fonctions + appels) lors de la
    // vraie tâche de nettoyage.
    private static final java.util.Map<String, Long> derniereFinLivraisonParWo =
        java.util.Collections.synchronizedMap(new java.util.HashMap<>());
    private static final long FENETRE_REFUS_REARMEMENT_MS = 60000;

    public static void marquerLivraisonTerminee(String woNum) {
        if (woNum != null && !woNum.isEmpty()) {
            derniereFinLivraisonParWo.put(woNum, System.currentTimeMillis());
        }
    }

    public static boolean armementRecentRefuse(String woNum) {
        if (woNum == null || woNum.isEmpty()) return false;
        Long derniereFin = derniereFinLivraisonParWo.get(woNum);
        if (derniereFin == null) return false;
        return (System.currentTimeMillis() - derniereFin) < FENETRE_REFUS_REARMEMENT_MS;
    }

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
                String extraJsonCorrige = extraJson;
                JSONObject result = d.optJSONObject("result");
                JSONObject tick   = d.optJSONObject("tick");

                double netL      = 0, grossL   = 0;
                double deltaNet  = 0, deltaGross = 0;
                String ticketNo  = "", saleNo = "";
                String startUtc  = "", endUtc = "";
                double durationS = 0;
                int    produitNo = 0;
                String presetStatus = "EXACT";
                // ✅ AJOUTÉ (28 août 2026, demande Paul — "pourquoi as-tu
                // manqué de rigueur depuis le début... tu es capable de
                // voir les fonctions, les entrées, les sorties") — trouvé
                // en cartographiant systématiquement : onDeliveryEnded()
                // (la vraie fin normale, la plus fréquente) n'avait
                // JAMAIS été refactorisée pour utiliser la fonction
                // partagée — elle construisait encore son propre JSON
                // séparément, sans jamais extraire description/code/type
                // du tout. Extrait maintenant via la fonction utilitaire
                // partagée, qui vérifie plusieurs variantes possibles.
                String produitDescriptionFin = "";
                String produitCodeFin = "";
                int produitTypeFin = -1;

                if (result != null) {
                    netL       = result.optDouble("fs_net_l",    0);
                    grossL     = result.optDouble("fs_gross_l",  0);
                    deltaNet   = result.optDouble("net_delta_l", 0);
                    deltaGross = result.optDouble("gross_delta_l", 0);
                    ticketNo   = result.optString("ticket_no",   "");
                    saleNo     = result.optString("sale_no",     "");
                    // ✅ AJOUTÉ (2 sept 2026, demande Paul — "est-ce qu'on
                    // est certain qu'on envoie le sales_number=ticket_number
                    // dans le wo de fieldservice et dans dataverse") —
                    // trouvé : ticketNo et saleNo étaient lus séparément
                    // ICI, sans jamais se repli l'un sur l'autre — la
                    // même règle déjà établie ailleurs (sale_number sert
                    // de ticket_number quand le registre n'exige jamais
                    // l'impression) n'était jamais appliquée dans ce
                    // chemin précis (fin de livraison normale). Sans ce
                    // repli, filgo_ticket_no (envoyé à Dataverse) restait
                    // vide même quand sale_no était parfaitement connu.
                    if (ticketNo.isEmpty() && !saleNo.isEmpty()) ticketNo = saleNo;
                    if (saleNo.isEmpty() && !ticketNo.isEmpty()) saleNo = ticketNo;
                    // ✅ AJOUTÉ (2 sept 2026, demande Paul — "il faut que
                    // tu te souviennes que des infos sont dans les
                    // payload aussi") — trouvé : mon correctif précédent
                    // ne touchait que les variables racine (ticketNo/
                    // saleNo), jamais le "result" NICHÉ à l'intérieur
                    // d'extraJsonCorrige lui-même — passé tel quel comme
                    // payloadExtra. payload_complet aurait donc gardé
                    // ticket_no/sale_no vides malgré la correction des
                    // champs racine. Injecte le même repli dans le
                    // result niché, puis reconstruit extraJsonCorrige (la
                    // chaîne réellement passée plus loin) depuis d
                    // modifié.
                    try {
                        result.put("ticket_no", ticketNo);
                        result.put("sale_no", saleNo);
                        extraJsonCorrige = d.toString();
                    } catch (Exception eNestedFix) {
                        android.util.Log.w(TAG, "Injection ticket_no/sale_no dans payload_complet ERR (non-bloquant): " + eNestedFix.getMessage());
                    }
                    startUtc   = result.optString("start_utc",   "");
                    endUtc     = result.optString("end_utc",     "");
                    durationS  = result.optDouble("duration_s",  0);
                    produitNo  = result.optInt("product_number", 0);
                }
                if (produitNo > 0) {
                    try {
                        com.pa.lcr.lcp.storage.RegisterProductStore prodStoreFin =
                            new com.pa.lcr.lcp.storage.RegisterProductStore(activity);
                        java.util.List<com.pa.lcr.lcp.storage.RegisterProductStore.Row> lignesFin =
                            prodStoreFin.getAll(serialIdParam, nodeParam);
                        if (lignesFin.isEmpty()) lignesFin = prodStoreFin.getAll(serialIdParam);
                        for (com.pa.lcr.lcp.storage.RegisterProductStore.Row ligneFin : lignesFin) {
                            if (ligneFin.noteIdx == produitNo) {
                                produitDescriptionFin = ligneFin.description;
                                produitCodeFin = ligneFin.productCode;
                                produitTypeFin = ligneFin.productType;
                                break;
                            }
                        }
                    } catch (Exception e) {
                        android.util.Log.w(TAG, "Recherche produit (fin normale) ERR (non-bloquant): " + e.getMessage());
                    }
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

                android.content.ContentValues cv =
                    com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.construireLivraisonComplete(
                        d.optString("jobId", ""), woNum, woIdGuid, ticketNo, saleNo,
                        netL, grossL, serialId, lcrnode, mac,
                        produitNo, produitDescriptionFin, produitCodeFin, produitTypeFin, presetL,
                        com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.TYPE_ORIGINAL,
                        "LIVRAISON",
                        com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.SYNC_PENDING,
                        extraJsonCorrige);
                cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_WO_ID_GUID,
                    woIdGuid != null ? woIdGuid.replace("{","").replace("}","") : "");
                cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_DELTA_NET_L,  deltaNet);
                cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_DELTA_GROSS_L,deltaGross);
                cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_PRESET_STATUS,presetStatus);
                cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_START_UTC,    startUtc);
                cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_END_UTC,      endUtc);
                cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_DURATION_S,   durationS);
                cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_SOURCE,       "REGISTRE");

                com.pa.lcr.lcp.storage.LcrDeliveryStatusDb lcrDb =
                    new com.pa.lcr.lcp.storage.LcrDeliveryStatusDb(activity);
                long localId;
                try {
                    // ✅ CORRIGÉ (2 sept 2026, demande Paul — "je veux pas
                    // de doublon corrige moi ca") — trouvé : cette fin de
                    // livraison utilisait insertDelivery() (toujours une
                    // NOUVELLE ligne), jamais upsertByJobId() (comme
                    // l'armement et la récupération) — créant une ligne
                    // séparée pour le même job_id au lieu de mettre à
                    // jour celle déjà correctement établie plus tôt dans
                    // le cycle de cette même livraison.
                    // ✅ AJOUTÉ (2 sept 2026, demande Paul — "vérifie le
                    // point resté en suspens : écrasement par payload
                    // périmé") — trouvé (confirmé par fichier réel) :
                    // r.data peut contenir des champs internes non
                    // synchronisés entre eux (state="CONNECTED" vrai,
                    // mais ticket_no/sale_no encore périmés d'un cycle
                    // précédent) — avec upsertByJobId, cette incohérence
                    // écraserait maintenant une valeur déjà correcte
                    // (établie par l'armement/la récupération) au lieu
                    // de simplement créer un doublon inoffensif comme
                    // avant. Garde-fou : si la ligne existante a déjà un
                    // ticket_no valide et que le nouveau semble vide ou
                    // suspect, préserve l'ancien plutôt que de l'écraser.
                    String jobIdPourGuard = cv.getAsString(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_JOB_ID);
                    if (jobIdPourGuard != null && !jobIdPourGuard.isEmpty()) {
                        com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.DeliveryRow existingPourGuard =
                            lcrDb.getByJobId(jobIdPourGuard);
                        if (existingPourGuard != null
                                && existingPourGuard.ticketNo != null && !existingPourGuard.ticketNo.isEmpty()) {
                            String nouveauTicketPourGuard = cv.getAsString(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_TICKET_NO);
                            if (nouveauTicketPourGuard == null || nouveauTicketPourGuard.isEmpty()) {
                                android.util.Log.w(TAG, "onDeliveryEnded: nouveau ticket_no vide, préservation de l'existant=" + existingPourGuard.ticketNo);
                                cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_TICKET_NO, existingPourGuard.ticketNo);
                                cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_SALE_NO, existingPourGuard.saleNo);
                            }
                        }
                    }
                    localId = lcrDb.upsertByJobId(cv);
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
                    org.json.JSONObject backupPayloadFin =
                        com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.construireJsonLivraisonComplet(
                            d.optString("jobId", ""), woNum, woIdGuid, ticketNo, saleNo,
                            netL, grossL, serialId, lcrnode, mac,
                            produitNo, produitDescriptionFin, produitCodeFin, produitTypeFin, presetL,
                            com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.TYPE_ORIGINAL,
                            com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.SYNC_PENDING,
                            extraJsonCorrige);
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
                                            android.util.Log.w(TAG, "patchSummaryConsolidated post-livraison ERR (probablement hors ligne) — "
                                                + "mise en file pour retry automatique: " + e.getMessage());
                                            // ✅ AJOUTÉ (2 sept 2026, demande Paul — "le path doit
                                            // fonctionner offline et online comme le bouton
                                            // blue") — trouvé : ce chemin normal (fin de
                                            // livraison automatique) ne faisait que loguer
                                            // l'échec, sans jamais mettre en file — contrairement
                                            // au bouton bleu qui, lui, avait déjà ce filet. Même
                                            // mécanisme exact ici : DeliveryResultQueueDb, marqué
                                            // "consolidated", retry via DeliverySyncWorker dès que
                                            // le réseau revient.
                                            try {
                                                org.json.JSONObject queuePayloadEnd = new org.json.JSONObject();
                                                queuePayloadEnd.put("consolidated", true);
                                                queuePayloadEnd.put("workOrderId", woIdGuid != null ? woIdGuid : "");
                                                queuePayloadEnd.put("woNum", woNum != null ? woNum : "");
                                                String queueUidEnd = (woNum != null ? woNum : "wo") + "-consolidated-"
                                                    + System.currentTimeMillis();
                                                queuePayloadEnd.put("deliveryUid", queueUidEnd);
                                                com.pa.lcrdemo.dataverse.DeliveryResultQueueDb queueDbEnd =
                                                    new com.pa.lcrdemo.dataverse.DeliveryResultQueueDb(activity);
                                                try {
                                                    queueDbEnd.upsertPending(queueUidEnd, queuePayloadEnd.toString());
                                                } finally {
                                                    try { queueDbEnd.close(); } catch (Exception ignored) {}
                                                }
                                                com.pa.lcrdemo.dataverse.DeliverySyncScheduler.triggerNow(activity);
                                                android.util.Log.i(TAG, "patchSummaryConsolidated post-livraison — mise en file OK, retry dès réseau dispo");
                                            } catch (Exception eQueueEnd) {
                                                android.util.Log.w(TAG, "patchSummaryConsolidated post-livraison — mise en file ERR: " + eQueueEnd.getMessage());
                                            }
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
                mettreAJourFieldService(woNum, woIdGuid, "termine", extraJsonCorrige);
                marquerLivraisonTerminee(woNum);

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

    // ✅ AJOUTÉ (2 sept 2026, demande Paul — "j'arrive de deeplink. tout
    // le processus a été hijacké") — trouvé la vraie source : le
    // raccourci du bouton bleu (RegisterTabFragment, cas "déjà SYNCED")
    // appelait finish() SANS JAMAIS informer FieldService du résultat
    // (aucun appel à retournerFieldService(), donc lastResultJson/
    // LcrHttpService jamais mis à jour). FieldService, ne recevant
    // jamais de confirmation, a renvoyé le même deep link — hijackant
    // tout le processus suivant. Expose publiquement pour que le
    // raccourci puisse enfin informer FieldService avant de fermer.
    public void retournerFieldServicePublic(String woNum, String woIdGuid,
                                             String status, String extraJson) {
        retournerFieldService(woNum, woIdGuid, status, extraJson);
    }

    // ✅ AJOUTÉ (4 sept 2026, demande Paul — "tu as dérogé du processus de
    // livraison... ne pas démarrer de livraison car la on sait qu'il est
    // en running_flowing") — trouvé, confirmé par log réel : la
    // finalisation orpheline finalisait immédiatement sur un simple
    // CONNECTED, sans tenir compte du fait que le registre peut reprendre
    // RUNNING_FLOWING juste après (même livraison physique continue, pas
    // vraiment terminée — cycle continue/protocole déjà documenté
    // ailleurs). Exposé publiquement pour que la finalisation orpheline
    // reprenne le VRAI suivi (déjà conçu pour ce cas précis — "chemin de
    // reprise après crash") au lieu de finaliser prématurément sur un
    // état transitoire.
    public void pollJobUntilDonePublic(String jobId, int node, String woNum,
                                        String woIdGuid, String serialId, String mac) {
        pollJobUntilDone(jobId, node, woNum, woIdGuid, serialId, mac);
    }

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
                    // ✅ AJOUTÉ (2 sept 2026, demande Paul) — repli sur
                    // sale_no si ticket_no vide (même règle que partout
                    // ailleurs) — jamais appliqué dans ce chemin précis
                    // (le vrai retour vers FieldService/Dataverse).
                    if (ticket.isEmpty()) ticket = result.optString("sale_no", "");
                    // ✅ AJOUTÉ (2 sept 2026, demande Paul — "des infos sont
                    // dans les payload aussi") — même repli injecté dans le
                    // result niché, extraJson reconstruit avant d'être
                    // réutilisé comme "payload" plus loin.
                    try {
                        result.put("ticket_no", ticket);
                        result.put("sale_no", ticket);
                        extraJson = d.toString();
                    } catch (Exception eNestedFixA) {
                        android.util.Log.w(TAG, "Injection ticket dans payload (retournerFieldService A) ERR (non-bloquant): " + eNestedFixA.getMessage());
                    }

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
                    if (ticket.isEmpty()) ticket = result.optString("sale_no", "");
                    try {
                        result.put("ticket_no", ticket);
                        result.put("sale_no", ticket);
                        extraJson = d.toString();
                    } catch (Exception eNestedFixB) {
                        android.util.Log.w(TAG, "Injection ticket dans payload (retournerFieldService B) ERR (non-bloquant): " + eNestedFixB.getMessage());
                    }
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
