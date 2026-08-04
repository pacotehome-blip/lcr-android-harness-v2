package com.pa.lcrdemo;

// ═══════════════════════════════════════════════════════════════
// COMPATIBILITÉ ANDROID : API 28 (Android 9) → API 35 (Android 15)
// Tester sur Android 9 (192.168.134.105) ET Android 15 (R52X508K2DR)
// ═══════════════════════════════════════════════════════════════

import android.content.Intent;
import android.util.Log;
import android.widget.TextView;

import com.pa.lcr.lcp.MultiRegisterApiFacadeImpl;
import com.pa.lcr.lcp.RegisterSessionManager;
import com.pa.lcr.lcp.storage.LcrDeliveryStatusDb;
import com.pa.lcr.lcp.transport.TransportIo;

/**
 * RegisterConnectionHelper — Validation et diagnostic de connexion au registre LCR.
 *
 * Appelé depuis DeepLinkHandler, RegisterTabFragment et tout autre point
 * qui doit communiquer avec le registre.
 *
 * Flux de validation :
 *   1. Vérifier io.isOpen() sur le transport existant
 *   2. Vérifier api_tickSnapshot() sur le controller
 *   3. Si l'un ou l'autre échoue → 4 étapes de diagnostic avec dialog progressif
 *   4. Dialog final avec boutons : Réessayer / Redémarrer APK / Envoyer courriel
 *
 * Contexte (woNum, ticketNo, node, serialId) lu depuis LcrDeliveryStatusDb.
 *
 * TODO — Amélioration future: envoyer via webhook Teams
 *   Canal: Filgo-Sonic Support / Power Automate Flow
 *   Voir session 6 — plan intégration Teams via Azure
 *
 * TODO — Amélioration future: Backup/Restore tablette
 *   Permettre export/import de LcrDeliveryStatusDb pour reprise sur nouvelle tablette
 *   Voir session 6 — plan intégration Dataverse comme source distante
 */
public class RegisterConnectionHelper {

    private static final String TAG = "RegisterConnHelper";
    private static final String SUPPORT_EMAIL = "paul-andre.cote@filgo.ca";
    // ✅ Guard anti-double diagnostic
    private static volatile boolean diagnosticEnCours = false;

    // ✅ FIX (2026-07-29, preuve logcat 00:38) : horodatage du diagnostic en
    // cours. lancerDiagnosticForce() faisait "reset guard et relance" de façon
    // INCONDITIONNELLE — n'importe quel appelant pouvait donc démarrer un 2e
    // diagnostic par-dessus un 1er encore actif.
    //
    // Observé : diagnostic #1 lancé par DeepLinkHandler (contexte deep link
    // complet), puis #2 lancé par connectThisRegister → validerConnexion
    // (surcharge 4 args, deepLinkHandler=null) qui écrase le guard. Les deux
    // boucles d'étape 3 tournent en parallèle et se ferment mutuellement les
    // sockets — d'où "tentative 1/2/3" en double dans le log, "api_btActivate
    // (already open)" répété, et "Registre non trouvé sur BT ou USB" alors que
    // le matériel était rebranché. En prime, celui qui termine en dernier est
    // celui SANS contexte deep link, donc la relance automatique de la
    // livraison ne se déclenche jamais et le dialog Continuer/Annuler
    // n'apparaît pas.
    //
    // On refuse désormais un diagnostic concurrent, SAUF si le précédent est
    // plus vieux que DIAGNOSTIC_STALE_MS — auquel cas on considère le drapeau
    // comme coincé (c'est le scénario pour lequel la variante "Force" avait
    // été créée) et on reprend la main.
    private static volatile long diagnosticStartMs = 0L;

    /** Au-delà de ce délai, un diagnostic "en cours" est considéré comme coincé.
     *  Marge large : 3 tentatives × (btActivate + connectAuto + attentes) peut
     *  dépasser 60s sur Android 9. */
    private static final long DIAGNOSTIC_STALE_MS = 90_000L;

    /** Réinitialise le guard — appeler au démarrage APK ou si bloqué */
    public static void resetDiagnostic() {
        diagnosticEnCours = false;
        diagnosticStartMs = 0L;
    }

    private final MainActivity activity;

    public RegisterConnectionHelper(MainActivity activity) {
        this.activity = activity;
    }

    // =========================================================
    // Point d'entrée principal — valider avant toute communication
    // =========================================================

    /**
     * Valide que le registre est joignable.
     * Si non joignable → déclenche le diagnostic en background.
     *
     * @return true si le registre répond, false si diagnostic lancé
     */
    public boolean validerConnexion(String transportKey, int node, String serialId, String woNum) {
        return validerConnexion(transportKey, node, serialId, woNum,
                null, null, null, null, null);
    }

    /**
     * Surcharge portant le contexte complet du deep link.
     *
     * ✅ FIX (2026-07-29, preuve logcat 00:38) : la version à 4 arguments ne
     * pouvait STRUCTURELLEMENT pas transporter deepLinkHandler/woIdGuid/produit/
     * preset jusqu'à lancerDiagnosticForce(). Tout diagnostic parti de ce chemin
     * (surErreurConnexion → validerConnexion) arrivait donc au bloc de relance
     * finale avec deepLinkHandler == null :
     *
     *     if (deepLinkHandler != null && woNum != null && !woNum.isEmpty())
     *
     * … condition fausse, donc lancerLivraison() jamais appelé. Le diagnostic
     * réussissait, le tab passait Connected-Ready, et la livraison ne repartait
     * pas — sans dialog Continuer/Annuler, puisque ce dialog vit dans
     * DeepLinkHandler.lancerLivraison().
     *
     * Tous les paramètres de contexte sont facultatifs (null accepté) : quand ils
     * sont absents, le comportement est identique à l'ancienne version.
     */
    public boolean validerConnexion(String transportKey, int node, String serialId, String woNum,
            String woIdGuid, String produit, String presetStr, String mac,
            com.pa.lcrdemo.DeepLinkHandler deepLinkHandler) {
        // ✅ Si transportKey vide — chercher le transport BT actif automatiquement
        String tkResolu = transportKey;
        if (tkResolu == null || tkResolu.isEmpty()) {
            try {
                java.util.List<com.pa.lcr.lcp.transport.TransportSnapshot> snaps =
                    activity.getMediaTransportManager().listSnapshots();
                if (snaps != null) {
                    for (com.pa.lcr.lcp.transport.TransportSnapshot s : snaps) {
                        // ✅ Supporter BT, USB, TCP — pas de filtre par type
                        if (s.key != null && !s.key.isEmpty()) {
                            tkResolu = s.key;
                            Log.i(TAG, "validerConnexion: transport auto-détecté = " + tkResolu);
                            break;
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        final String tkFinal = tkResolu;

        // 1. Vérifier io.isOpen()
        boolean ioOk = false;
        try {
            TransportIo io = activity.getMediaTransportManager().getByKey(tkFinal);
            ioOk = (io != null && io.isOpen());
        } catch (Exception e) {
            Log.w(TAG, "isOpen check ERR: " + e.getMessage());
        }

        if (!ioOk) {
            Log.w(TAG, "validerConnexion: io mort — transport=" + tkFinal);
            // ✅ FIX : lancerDiagnostic() s'auto-ignore silencieusement si
            // diagnosticEnCours est resté bloqué à true (ex: tentative
            // antérieure qui n'a pas remis le drapeau à false proprement) —
            // le chauffeur cliquait Status/Continuer et ne voyait RIEN se
            // passer, sans erreur, sans log visible côté UI. lancerDiagnosticForce()
            // existe justement pour ce cas ("le registre ne répond pas même si
            // BT est connecté, câble débranché") — on l'utilise ici aussi.
            new Thread(() -> lancerDiagnosticForce(tkFinal, node, serialId, woNum,
                    woIdGuid, produit, presetStr, mac, deepLinkHandler)).start();
            return false;
        }

        // 2. ÉPREUVE RÉELLE du registre — pas un flag en mémoire
        //
        // ✅ FIX (2026-07-29, preuve logcat 00:04) : cette étape ne faisait
        // AUCUNE IO hors livraison. Elle lisait dc.getState() (une valeur en
        // mémoire, qui reste à CONNECTED indéfiniment sur un socket BT zombie)
        // et api_tickSnapshot() (lecture de CACHE, qui retourne toujours ok=1).
        // Le contrôle d'âge par tick_age_ms ne s'exécutait que pendant
        // RUNNING_FLOWING/RUNNING_PAUSED.
        //
        // Conséquence observée : validerTransportEtRegistrePuis détectait
        // correctement la déconnexion avec une VRAIE commande
        //   "-> ÉCHEC (err=ERR_LCP_CONNECT_FAILED msg=Validate: 0 - LCP error.)"
        // puis appelait surErreurConnexion → validerConnexion, qui répondait
        //   "validerConnexion: OK"
        // et annulait le verdict. Aucun diagnostic n'était lancé, le bouton
        // Status ne produisait aucune réaction. Un point de décision fort
        // était écrasé par un point de décision faible en aval.
        //
        // On envoie maintenant la MÊME commande réelle que
        // validerTransportEtRegistrePuis — api_registerValidate() — liée au
        // node et au serial précis. Les deux points de décision utilisent
        // désormais le même critère et ne peuvent plus se contredire.
        boolean tickOk = false;
        try {
            com.pa.lcr.lcp.DeliveryController dc =
                RegisterSessionManager.get(activity).getController(tkFinal, node);
            if (dc == null) {
                Log.w(TAG, "validerConnexion: controller null pour transport=" + tkFinal);
            } else if (dc.isStopped()) {
                // Controller arrêté = executor Terminated = incapable d'exécuter
                // quoi que ce soit d'asynchrone. Inutile de le sonder.
                Log.w(TAG, "validerConnexion: controller STOPPED (executor Terminated) — transport=" + tkFinal);
            } else {
                com.pa.lcr.lcp.ApiResult r = dc.api_registerValidate(
                        null,
                        node > 0 ? Integer.valueOf(node) : null,
                        (serialId != null && !serialId.isEmpty()) ? serialId : null,
                        null, null, false);

                // Seul ERR_LCP_CONNECT_FAILED indique une vraie panne de
                // communication. Les autres échecs (ticket en attente, livraison
                // active, mauvais serial) sont des états MÉTIER normaux — le
                // registre a répondu, donc la connexion est bonne. Même critère
                // que validerTransportEtRegistrePuis, volontairement.
                boolean vraieDeconnexion = (r != null) && (r.code != 1)
                        && com.pa.lcr.lcp.RegisterValidator.Codes.ERR_LCP_CONNECT_FAILED.equals(r.err);
                tickOk = (r != null) && !vraieDeconnexion;

                Log.i(TAG, "validerConnexion: registerValidate code="
                        + (r != null ? String.valueOf(r.code) : "null")
                        + " err=" + (r != null ? r.err : "-")
                        + " msg=" + (r != null ? r.msg : "-")
                        + " -> tickOk=" + tickOk);
            }
        } catch (Exception e) {
            // Une exception ici est elle-même un signal de panne — on ne
            // l'avale pas en concluant OK.
            Log.w(TAG, "validerConnexion: registerValidate EXCEPTION: " + e.getMessage());
            tickOk = false;
        }

        if (!tickOk) {
            Log.w(TAG, "validerConnexion: registre ne répond pas — transport=" + tkFinal);
            // ✅ FIX : même traitement que la branche "io mort" ci-dessus.
            // lancerDiagnostic() (non-force) retourne SILENCIEUSEMENT si
            // diagnosticEnCours est resté bloqué à true — et resetDiagnostic()
            // n'est appelé nulle part ailleurs qu'au démarrage de MainActivity,
            // donc le drapeau reste coincé jusqu'au redémarrage de l'APK. Le
            // chauffeur cliquait Status et ne voyait rien. On force, en thread
            // dédié (jamais sur le thread UI : diagnostic() fait des sleep et
            // des probes LCP bloquants).
            new Thread(() -> lancerDiagnosticForce(tkFinal, node, serialId, woNum,
                    woIdGuid, produit, presetStr, mac, deepLinkHandler)).start();
            return false;
        }

        Log.i(TAG, "validerConnexion: OK — transport=" + tkFinal + " node=" + node);
        return true;
    }

    // =========================================================
    // Diagnostic en background — 4 étapes avec dialog progressif
    // =========================================================

    /**
     * Prend le verrou de diagnostic, ou refuse si un diagnostic est déjà actif.
     *
     * Un seul diagnostic à la fois : ses étapes 1 à 3 ferment des sockets et
     * réinitialisent le BT, donc deux instances concurrentes se sabotent
     * mutuellement (voir la note en haut du fichier).
     *
     * @return true si le verrou est pris (l'appelant DOIT appeler
     *         libererLeVerrouDiagnostic() dans un finally), false si refusé.
     */
    private static synchronized boolean prendreLeVerrouDiagnostic(String origine) {
        long now = System.currentTimeMillis();
        if (diagnosticEnCours) {
            long age = now - diagnosticStartMs;
            if (age < DIAGNOSTIC_STALE_MS) {
                Log.w(TAG, origine + ": diagnostic déjà en cours depuis " + age
                        + "ms — REFUSÉ (on laisse le premier terminer)");
                return false;
            }
            Log.w(TAG, origine + ": diagnostic précédent bloqué depuis " + age
                    + "ms (> " + DIAGNOSTIC_STALE_MS + "ms) — reprise du verrou");
        }
        diagnosticEnCours = true;
        diagnosticStartMs = now;
        Log.i(TAG, origine + ": verrou diagnostic pris");
        return true;
    }

    private static synchronized void libererLeVerrouDiagnostic() {
        diagnosticEnCours = false;
        diagnosticStartMs = 0L;
        Log.i(TAG, "verrou diagnostic libéré");
    }

    private void lancerDiagnostic(String transportKey, int node, String serialId, String woNum) {
        if (!prendreLeVerrouDiagnostic("lancerDiagnostic")) return;
        new Thread(() -> {
            try {
                diagnostic(transportKey, node, serialId, woNum);
            } finally {
                libererLeVerrouDiagnostic();
            }
        }).start();
    }

    /**
     * Lance le diagnostic avec reprise d'un verrou COINCÉ uniquement.
     *
     * ⚠️ Le nom "Force" est historique : depuis 2026-07-29, cette méthode ne
     * force PLUS par-dessus un diagnostic réellement actif — elle ne reprend le
     * verrou que si le précédent dépasse DIAGNOSTIC_STALE_MS. Deux diagnostics
     * simultanés se ferment mutuellement les sockets (voir note en haut du
     * fichier).
     *
     * Utilisé après oneshot/start orchestration error — le registre ne répond pas
     * même si BT est connecté (câble série débranché par exemple).
     */
    public void lancerDiagnosticForce(String transportKey, int node, String serialId, String woNum) {
        lancerDiagnosticForce(transportKey, node, serialId, woNum, null, null, null, null, null);
    }

    /**
     * Surcharge avec paramètres complets du deep link.
     * Après succès du diagnostic, relance automatiquement la livraison.
     * Compatible Android 9-15 — pas d'API version-spécifique.
     */
    public void lancerDiagnosticForce(String transportKey, int node, String serialId,
            String woNum, String woIdGuid, String produit, String presetStr,
            String mac, com.pa.lcrdemo.DeepLinkHandler deepLinkHandler) {
        if (!prendreLeVerrouDiagnostic("lancerDiagnosticForce")) return;
        try {
            diagnostic(transportKey, node, serialId, woNum,
                woIdGuid, produit, presetStr, mac, deepLinkHandler);
        } finally {
            libererLeVerrouDiagnostic();
        }
    }

    private void diagnostic(String transportKey, int node, String serialId, String woNum) {
        diagnostic(transportKey, node, serialId, woNum, null, null, null, null, null);
    }

    private void diagnostic(String transportKey, int node, String serialId, String woNum,
            String woIdGuid, String produit, String presetStr,
            String mac, com.pa.lcrdemo.DeepLinkHandler deepLinkHandler) {
        // Lire contexte depuis LcrDeliveryStatusDb
        String ticketNo = "";
        String fSerialId = (serialId != null && !serialId.isEmpty()) ? serialId : "";
        int fNode = node;
        try {
            LcrDeliveryStatusDb db = new LcrDeliveryStatusDb(activity);
            LcrDeliveryStatusDb.DeliveryRow row = (woNum != null && !woNum.isEmpty())
                ? db.getLatestForWo(woNum) : db.getLastDelivery();
            if (row != null) {
                if (row.ticketNo != null) ticketNo = row.ticketNo;
                if (fSerialId.isEmpty() && row.serialId != null && !row.serialId.isEmpty())
                    fSerialId = row.serialId;
                if (fNode <= 0 && row.lcrnode > 0) fNode = row.lcrnode;
            }
        } catch (Exception e) {
            Log.w(TAG, "DB read ERR: " + e.getMessage());
        }

        final String fTicketNo = ticketNo;
        final String fSerialIdFinal = fSerialId;
        final int fNodeFinal = fNode;
        final String[] etapes = new String[4];
        final boolean[] etapesOk = new boolean[4];
        final String[] erreurDetail = {""};

        // Dialog progressif
        final android.app.AlertDialog.Builder dlgBuilder =
            new android.app.AlertDialog.Builder(activity);
        dlgBuilder.setTitle("🔄 Connexion au registre...");
        dlgBuilder.setCancelable(false);
        final android.widget.TextView txtProgress = new android.widget.TextView(activity);
        txtProgress.setPadding(40, 20, 40, 20);
        txtProgress.setTextSize(13f);
        dlgBuilder.setView(txtProgress);
        final android.app.AlertDialog[] dlg = {null};
        final Object dlgLock = new Object();
        activity.runOnUiThread(() -> {
            dlg[0] = dlgBuilder.show();
            synchronized (dlgLock) { dlgLock.notifyAll(); }
        });
        synchronized (dlgLock) {
            try { if (dlg[0] == null) dlgLock.wait(2000); } catch (Exception ignored) {}
        }

        Runnable updateDlg = () -> activity.runOnUiThread(() -> {
            if (dlg[0] == null || !dlg[0].isShowing()) return;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                if (etapes[i] == null) break;
                String icon = etapesOk[i] ? "✅" : (i == getPremierEchec(etapesOk) && !erreurDetail[0].isEmpty() ? "❌" : "🔄");
                sb.append(icon).append(" Étape ").append(i+1).append("/4 — ").append(etapes[i]).append("\n");
            }
            txtProgress.setText(sb.toString().trim());
        });

        // ✅ Vérifier si livraison active avant de déconnecter
        boolean livraisonActive = false;
        try {
            com.pa.lcr.lcp.RegisterSessionManager rsmCheck =
                com.pa.lcr.lcp.RegisterSessionManager.get(activity);
            com.pa.lcr.lcp.DeliveryController dcCheck = rsmCheck.resolveOrCreateForNode(fNodeFinal, 255);
            if (dcCheck != null) {
                com.pa.lcr.lcp.DeliveryState st = dcCheck.getState();
                livraisonActive = (st == com.pa.lcr.lcp.DeliveryState.RUNNING_FLOWING
                    || st == com.pa.lcr.lcp.DeliveryState.RUNNING_PAUSED
                    || st == com.pa.lcr.lcp.DeliveryState.ENDING);
                if (livraisonActive) Log.i(TAG, "Livraison active (" + st + ") — étapes 1 et 2 ignorées");
            }
        } catch (Exception ignored) {}

        // ÉTAPE 1 — Fermeture connexion existante
        etapes[0] = livraisonActive ? "Fermeture connexion — ignorée (livraison active)" : "Fermeture connexion existante";
        updateDlg.run();
        if (!livraisonActive) {
            try { activity.btDisconnect(); Thread.sleep(800); } catch (Exception ignored) {}
        }
        etapesOk[0] = true;
        updateDlg.run();

        // ÉTAPE 2 — Réinitialisation Bluetooth
        etapes[1] = livraisonActive ? "Réinitialisation BT — ignorée (livraison active)" : "Réinitialisation Bluetooth";
        updateDlg.run();
        if (!livraisonActive) {
            try {
                android.bluetooth.BluetoothAdapter bt = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
                if (bt != null && bt.isEnabled()) {
                    // bt.disable()/enable() déprécié Android 13, bloqué Android 14+
                    // Android 9-12 (API 28-32) : reset BT via disable/enable
                    // Android 13+  (API 33+)   : skip — l'OS gère le BT différemment
                    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) {
                        bt.disable(); Thread.sleep(1500);
                        bt.enable();  Thread.sleep(2000);
                    } else {
                        // Android 13+ — juste attendre que le stack BT se stabilise
                        Thread.sleep(1000);
                    }
                }
            } catch (Exception ignored) {}
        }
        etapesOk[1] = true;
        updateDlg.run();

        // ÉTAPE 3 — Scan médias via api_registerConnectAuto
        // 1. connect-auto → trouve le bon média (BT/USB)
        // 2. Si trouvé → upsertRegisterTabFromScan → tab rafraîchi avec état registre
        boolean btConnecte = false;
        com.pa.lcr.lcp.DeliveryController dcFinal = null;
        // ✅ FIX : capturer la transportKey trouvée à l'étape 3 pour la relance finale —
        // l'ancien code passait toujours "" à lancerLivraison(), qui ne peut alors
        // jamais matcher le transport dans sa boucle d'attente READY (10s pour rien,
        // puis MEDIA_NOT_READY sans jamais tenter le oneshot).
        final String[] transportKeyFinal = {""};

        com.pa.lcr.lcp.MultiRegisterApiFacadeImpl facade =
            new com.pa.lcr.lcp.MultiRegisterApiFacadeImpl(activity);

        for (int attempt = 1; attempt <= 3 && !btConnecte; attempt++) {
            etapes[2] = "Recherche registre... (" + attempt + "/3)";
            updateDlg.run();

            // ✅ Fermer les sockets BT zombis SEULEMENT à la 1ère tentative
            if (attempt == 1) {
                try {
                    com.pa.lcr.lcp.transport.MediaTransportManager mtmClose =
                        activity.getMediaTransportManager();
                    for (com.pa.lcr.lcp.transport.TransportSnapshot s : mtmClose.listSnapshots()) {
                        if (s == null || s.key == null || !s.key.startsWith("BT:")) continue;
                        com.pa.lcr.lcp.transport.TransportIo ioClose = mtmClose.getByKey(s.key);
                        if (ioClose != null) {
                            try { ioClose.close(); } catch (Exception ignored) {}
                            Log.i(TAG, "étape 3: socket fermé " + s.key);
                        }
                        String btMacLocal = s.key.substring(3);
                        try { mtmClose.onBtDisconnected(btMacLocal, "diag cleanup"); } catch (Exception ignored) {}
                    }
                    activity.btDisconnect();
                    Thread.sleep(2000);
                } catch (Exception ignored) {}
            }

            // ✅ FIX (4 août 2026, demande Paul — "un seul endroit pour
            // initier la connexion") — l'ancienne étape 2 forçait BT à
            // s'ouvrir AVANT même d'essayer l'étape 3, alors que
            // api_registerConnectAuto() (étape 3) gère maintenant lui-même
            // USB→BT→TCP dans le bon ordre, avec verrou unique. Si le
            // registre est en fait sur USB (le cas réel le plus courant),
            // cette étape forçait un échec inutile avant même d'essayer.
            // Retirée — l'étape 3 gère tout, un seul chemin, plus de
            // duplication.

            // ✅ Étape 3: api_registerConnectAuto — probe LCP (USB→BT→TCP), valide node + serial
            Log.i(TAG, "étape 3: api_registerConnectAuto tentative " + attempt
                + " node=" + fNodeFinal + " serial=" + fSerialIdFinal);
            com.pa.lcr.lcp.ApiResult r = facade.api_registerConnectAuto(
                fSerialIdFinal.isEmpty() ? null : fSerialIdFinal, fNodeFinal);
            Log.i(TAG, "étape 3: code=" + r.code + " msg=" + r.msg);
            if (r.code == 1) {
                String foundKey    = r.data != null ? r.data.optString("transportKey", "") : "";
                String foundSerial = r.data != null ? r.data.optString("serial", fSerialIdFinal) : fSerialIdFinal;
                Log.i(TAG, "étape 3: TROUVÉ ✓ transport=" + foundKey + " serial=" + foundSerial);
                final String fKey    = foundKey;
                final String fSerial = foundSerial;
                transportKeyFinal[0] = foundKey;
                activity.runOnUiThread(() -> {
                    if (!fKey.isEmpty()) {
                        // ✅ Détection isLc3 centralisée — même mécanisme partagé
                        // (voir MainActivity.resolveIsLc3), plus de logique dupliquée.
                        activity.upsertRegisterTabFromScan(fKey, fNodeFinal, 255, fSerial, true,
                                activity.resolveIsLc3(fKey, fNodeFinal));
                    }
                    activity.refreshAllTabsMediaStatus();
                });
                try { Thread.sleep(500); } catch (Exception ignored) {}
                etapes[2] = "✅ Registre trouvé — " + foundKey.replace("BT:", "BT: ") + " | Serial: " + foundSerial;
                updateDlg.run();
                dcFinal = com.pa.lcr.lcp.RegisterSessionManager.get(activity)
                    .resolveOrCreateForNode(fNodeFinal, 255);

                // ✅ Forcer la réattachement du controller au socket actuel
                // Comme Configure le fait — sinon le controller a un io zombi
                try {
                    com.pa.lcr.lcp.transport.MediaTransportManager mtmReattach =
                        activity.getMediaTransportManager();
                    com.pa.lcr.lcp.transport.TransportIo ioReattach =
                        mtmReattach.getByKey(fKey);
                    if (ioReattach != null && ioReattach.isOpen()) {
                        com.pa.lcr.lcp.RegisterSessionManager.get(activity)
                            .getOrCreate(fKey, fNodeFinal, 255, ioReattach);
                        Log.i(TAG, "étape 3: controller réattaché au socket " + fKey);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "étape 3: réattach ERR: " + e.getMessage());
                }

                btConnecte = true;
                etapesOk[2] = true;
            } else {
                erreurDetail[0] = r.msg;
                if (attempt < 3) try { Thread.sleep(2000); } catch (Exception ignored) {}
            }
        }
        updateDlg.run();

        if (!btConnecte) {
            afficherEchec(dlg[0], etapes, etapesOk,
                "Registre non trouvé\n"
                + "⚡ Assurez-vous que :\n• Le Bluetooth est activé\n• Le registre est sous tension\n• Le registre est en mode communication BT",
                woNum, fTicketNo, fNodeFinal, fSerialIdFinal,
                woIdGuid, produit, presetStr, mac, deepLinkHandler);
            return;
        }

        // ÉTAPE 4 — Vérification état registre
        etapes[3] = "Vérification registre LCR...";
        updateDlg.run();
        boolean lcpOk = false;
        try {
            if (dcFinal != null) {
                com.pa.lcr.lcp.DeliveryState st = dcFinal.getState();
                lcpOk = (st == com.pa.lcr.lcp.DeliveryState.CONNECTED
                    || st == com.pa.lcr.lcp.DeliveryState.RUNNING_FLOWING
                    || st == com.pa.lcr.lcp.DeliveryState.RUNNING_PAUSED
                    || st == com.pa.lcr.lcp.DeliveryState.ENDING);
                if (!lcpOk) {
                    try { dcFinal.requestLiveSample(); } catch (Exception ignored) {}
                    for (int w = 0; w < 30; w++) {
                        try { Thread.sleep(300); } catch (Exception ignored) {}
                        st = dcFinal.getState();
                        if (st == com.pa.lcr.lcp.DeliveryState.CONNECTED
                            || st == com.pa.lcr.lcp.DeliveryState.RUNNING_FLOWING
                            || st == com.pa.lcr.lcp.DeliveryState.RUNNING_PAUSED) {
                            lcpOk = true; break;
                        }
                    }
                    if (!lcpOk) erreurDetail[0] = "État: " + dcFinal.getState();
                }
                Log.i(TAG, "étape 4: state=" + dcFinal.getState() + " lcpOk=" + lcpOk);
            } else {
                erreurDetail[0] = "Controller non disponible";
            }
        } catch (Exception e) {
            erreurDetail[0] = e.getMessage() != null ? e.getMessage() : "Timeout LCP";
        }
        etapesOk[3] = lcpOk;
        updateDlg.run();

        if (!lcpOk) {
            afficherEchec(dlg[0], etapes, etapesOk,
                "BT connecté mais registre LCR ne répond pas\n" + erreurDetail[0] + "\n\n"
                + "⚡ Assurez-vous que :\n• L'alimentation du registre est branchée\n• Le registre est en mode communication\n• Aucun autre appareil n'est connecté",
                woNum, fTicketNo, fNodeFinal, fSerialIdFinal,
                woIdGuid, produit, presetStr, mac, deepLinkHandler);
            return;
        }

        // Succès — fermer dialog et basculer vers le tab
        Log.i(TAG, "diagnostic: registre joignable — node=" + fNodeFinal + " serial=" + fSerialIdFinal);
        activity.runOnUiThread(() -> {
            if (dlg[0] != null) dlg[0].dismiss();
            try { activity.showPage(0); } catch (Exception ignored) {}
        });

        // ✅ FIX : ne PAS appeler resolveOrCreateForNode()/getOrCreate() (synchronized,
        // même moniteur pour toute la classe) depuis le thread UI. Si un autre thread
        // (check périodique STATUS_B du tab, réattachement étape 3) est en train de
        // faire un probe LCP bloquant (opGetField jusqu'à 3000ms) en tenant ce verrou,
        // le thread UI se gelait en silence — sans exception, sans log — et la relance
        // de la livraison n'avait jamais lieu.
        new Thread(() -> {
            try { Thread.sleep(1500); } catch (Exception ignored) {}
            try {
                com.pa.lcr.lcp.DeliveryController dc2 =
                    com.pa.lcr.lcp.RegisterSessionManager.get(activity)
                        .resolveOrCreateForNode(fNodeFinal, 255);
                if (dc2 != null) {
                    dc2.requestStatus();
                    dc2.requestLiveSample();
                }
            } catch (Exception ignored) {}

            // ✅ Relancer la livraison automatiquement si paramètres deep link disponibles
            if (deepLinkHandler != null && woNum != null && !woNum.isEmpty()) {
                Log.i(TAG, "diagnostic succès — relance livraison auto woNum=" + woNum
                    + " transportKey=" + transportKeyFinal[0]);
                deepLinkHandler.lancerLivraison(
                    transportKeyFinal[0],
                    fNodeFinal,
                    fSerialIdFinal,
                    woNum,
                    woIdGuid != null ? woIdGuid : "",
                    produit != null ? produit : "",
                    presetStr != null ? presetStr : "",
                    mac != null ? mac : "",
                    true // skipConnexionCheck — diagnostic étape 4 vient de confirmer lcpOk=true
                );
            }
        }).start();
    }

    // =========================================================
    // Utilitaires
    // =========================================================

    private int getPremierEchec(boolean[] etapesOk) {
        for (int i = 0; i < etapesOk.length; i++) {
            if (!etapesOk[i]) return i;
        }
        return etapesOk.length - 1;
    }

    private void afficherEchec(
            android.app.AlertDialog dlgPrev,
            String[] etapes, boolean[] etapesOk,
            String erreur, String woNum, String ticketNo,
            int node, String serialId,
            String woIdGuid, String produit, String presetStr, String mac,
            com.pa.lcrdemo.DeepLinkHandler deepLinkHandler) {

        activity.runOnUiThread(() -> {
            if (dlgPrev != null && dlgPrev.isShowing()) dlgPrev.dismiss();

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                if (etapes[i] == null) break;
                sb.append(etapesOk[i] ? "✅" : "❌")
                  .append(" Étape ").append(i+1).append("/4 — ").append(etapes[i]).append("\n");
            }
            sb.append("\n⛔ ").append(erreur);
            if (woNum == null || woNum.isEmpty()) {
                sb.append("\n\nℹ️ Aucun bon de travail actif\nLivraison manuelle — aucun WO associé");
            } else {
                sb.append("\n\nWO: ").append(woNum);
                sb.append(" | Ticket: ").append(ticketNo != null && !ticketNo.isEmpty() ? ticketNo : "—");
            }
            sb.append("\nNode: ").append(node).append(" | Serial: ").append(serialId);
            sb.append("\n\nContactez le support :\n").append(SUPPORT_EMAIL);

            final String resumeComplet = sb.toString();
            final String sujet = "[Filgo-Sonic] Registre non joignable — "
                + (woNum != null && !woNum.isEmpty() ? "WO:" + woNum : "Livraison manuelle");
            final String corps = resumeComplet + "\n\nTimestamp: " + new java.util.Date();

            new android.app.AlertDialog.Builder(activity)
                .setTitle("⛔ Registre non joignable  ✕")
                .setMessage(resumeComplet)
                .setCancelable(true)
                .setPositiveButton("🔄 Réessayer", (d, w) -> {
                    d.dismiss();
                    // ✅ FIX : l'ancien code appelait lancerDiagnostic("", node, serialId, woNum)
                    // — la version à 4 arguments, qui perd woIdGuid/produit/presetStr/mac ET
                    // surtout deepLinkHandler. Résultat : même si ce nouveau diagnostic réussit,
                    // le bloc de relance finale ne se déclenche jamais (deepLinkHandler == null),
                    // donc le tab devient Connected-Ready (communication réellement confirmée,
                    // ticket_no lu) mais la livraison ne redémarre jamais. On garde le contexte
                    // complet ici pour que la relance auto fonctionne aussi depuis ce bouton.
                    lancerDiagnosticForce("", node, serialId, woNum,
                        woIdGuid, produit, presetStr, mac, deepLinkHandler);
                })
                .setNeutralButton("🔁 Redémarrer APK", (d, w) -> {
                    try {
                        android.content.Intent intent = activity.getPackageManager()
                            .getLaunchIntentForPackage(activity.getPackageName());
                        if (intent != null) {
                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                            activity.startActivity(intent);
                        }
                        android.os.Process.killProcess(android.os.Process.myPid());
                    } catch (Exception e) {
                        Log.e(TAG, "Restart ERR: " + e.getMessage());
                    }
                })
                .setNegativeButton("📧 Envoyer courriel", (d, w) -> {
                    try {
                        android.content.Intent email = new android.content.Intent(
                            android.content.Intent.ACTION_SEND);
                        email.setType("message/rfc822");
                        email.putExtra(android.content.Intent.EXTRA_EMAIL,
                            new String[]{SUPPORT_EMAIL});
                        email.putExtra(android.content.Intent.EXTRA_SUBJECT, sujet);
                        email.putExtra(android.content.Intent.EXTRA_TEXT, corps);
                        activity.startActivity(android.content.Intent.createChooser(
                            email, "Envoyer courriel support"));
                    } catch (Exception e) {
                        Log.e(TAG, "Email ERR: " + e.getMessage());
                    }
                    d.dismiss();
                })
                .show();
        });
    }

    public static boolean estErreurConnexion(Exception e) {
        if (e == null) return false;
        if (e instanceof java.io.IOException) return true;
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        return msg.contains("timeout")
            || msg.contains("socket")
            || msg.contains("broken pipe")
            || msg.contains("connection")
            || msg.contains("transport")
            || msg.contains("bt")
            || msg.contains("lcp")
            || msg.contains("io error");
    }
}