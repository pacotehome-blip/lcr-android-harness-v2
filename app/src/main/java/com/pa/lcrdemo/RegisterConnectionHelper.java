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

    // ✅ AJOUTÉ (14 août 2026, demande Paul — "la connexion avec le
    // registre a été faite, il devrait donc le redétecter et le remettre
    // le tab dans l'état qu'il était avant") — trouvé : validerConnexion()
    // ne vérifiait QUE le transportKey précis passé en paramètre. Si ce
    // transportKey est périmé (l'ancien transport, mort, alors que le
    // chien de garde a déjà retrouvé et reconnecté le même #série sur un
    // AUTRE transport ailleurs), cette méthode déclenchait le diagnostic
    // d'échec complet ("réessayer, redémarrer l'apk") sans jamais vérifier
    // si le registre répondait déjà ailleurs. Cette méthode comble ce
    // trou : sonde tous les transports actuellement ouverts, et pour
    // chacun (sauf celui à exclure), interroge son DeliveryController
    // déjà vivant via api_registerValidate() (déjà utilisée ailleurs dans
    // ce fichier, coût faible — GET_DELIVERY_STATUS + GET_FIELD, jamais
    // GET_MACHINE_STATUS) pour comparer le #série. Retourne la clé du
    // transport trouvé, ou null si vraiment introuvable ailleurs.
    private String findAliveTransportForSerial(String serialId, int node, String excludeTransportKey) {
        if (serialId == null || serialId.trim().isEmpty()) return null;
        try {
            java.util.List<com.pa.lcr.lcp.transport.TransportSnapshot> snaps =
                    activity.getMediaTransportManager().listSnapshots();
            if (snaps == null) return null;
            for (com.pa.lcr.lcp.transport.TransportSnapshot s : snaps) {
                if (s == null || s.key == null || s.key.equals(excludeTransportKey)) continue;
                com.pa.lcr.lcp.DeliveryController dc =
                        RegisterSessionManager.get(activity).getController(s.key, node);
                if (dc == null || dc.isStopped()) continue;
                try {
                    com.pa.lcr.lcp.ApiResult r = dc.api_registerValidate(
                            null, node > 0 ? Integer.valueOf(node) : null, null, null, null, false);
                    if (r != null && r.data != null) {
                        String foundSerial = r.data.optString("serial_id", "");
                        if (serialId.trim().equalsIgnoreCase(foundSerial.trim())) {
                            Log.i(TAG, "findAliveTransportForSerial: #série=" + serialId
                                    + " trouvé sur transport=" + s.key + " (excluait " + excludeTransportKey + ")");
                            return s.key;
                        }
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            Log.w(TAG, "findAliveTransportForSerial: erreur — " + e.getMessage());
        }
        return null;
    }

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
            try { com.pa.lcr.lcp.log.LogBus.err(node, "RegisterConnectionHelper.isOpenCheck", e); } catch (Exception ignored) {}
        }

        if (!ioOk) {
            Log.w(TAG, "validerConnexion: io mort — transport=" + tkFinal);
            // ✅ AJOUTÉ (14 août 2026) — avant de lancer le diagnostic
            // d'échec, vérifier si ce même #série répond déjà ailleurs
            // (le chien de garde a peut-être déjà reconnecté sur un autre
            // transport pendant que ce tab-ci tenait encore l'ancien).
            String transportAilleurs = findAliveTransportForSerial(serialId, node, tkFinal);
            if (transportAilleurs != null) {
                Log.i(TAG, "validerConnexion: registre déjà reconnecté ailleurs ("
                        + transportAilleurs + ") — pas un échec, mise à jour du tab au lieu du diagnostic");
                try { com.pa.lcr.lcp.log.LogBus.ui(node, "[MEDIA][CONTINUITÉ] #série=" + serialId
                        + " déjà actif sur " + transportAilleurs + " — tab redirigé au lieu d'un diagnostic d'échec"); } catch (Exception ignored) {}
                try {
                    activity.runOnUiThread(() -> {
                        try { activity.onConfigureMediaActivated(transportAilleurs, "REDIRECT_ALREADY_ALIVE"); }
                        catch (Exception ignored) {}
                    });
                } catch (Exception ignored) {}
                return true;
            }
            // ✅ CORRIGÉ (25 août 2026, demande Paul — précisé : "on garde
            // le diagnostique en affichage et réessayer fait le même
            // principe qu'après la suppression du tab") — le dialogue
            // diagnostic reste affiché (le chauffeur doit voir qu'il y a
            // un problème), mais son bouton "Réessayer" réutilise
            // maintenant removeTabAndFragment() au lieu de l'ancienne
            // boucle de 3 tentatives (confirmée trop courte par log réel).
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
            try { com.pa.lcr.lcp.log.LogBus.err(node, "RegisterConnectionHelper.validerConnexion", e); } catch (Exception ignored) {}
            tickOk = false;
        }

        if (!tickOk) {
            Log.w(TAG, "validerConnexion: registre ne répond pas — transport=" + tkFinal);
            // ✅ AJOUTÉ (14 août 2026) — même vérification que la branche
            // "io mort" ci-dessus : avant de conclure à un vrai échec,
            // vérifier si ce #série répond déjà ailleurs.
            String transportAilleurs2 = findAliveTransportForSerial(serialId, node, tkFinal);
            if (transportAilleurs2 != null) {
                Log.i(TAG, "validerConnexion: registre déjà reconnecté ailleurs ("
                        + transportAilleurs2 + ") — pas un échec, mise à jour du tab au lieu du diagnostic");
                try { com.pa.lcr.lcp.log.LogBus.ui(node, "[MEDIA][CONTINUITÉ] #série=" + serialId
                        + " déjà actif sur " + transportAilleurs2 + " — tab redirigé au lieu d'un diagnostic d'échec"); } catch (Exception ignored) {}
                try {
                    activity.runOnUiThread(() -> {
                        try { activity.onConfigureMediaActivated(transportAilleurs2, "REDIRECT_ALREADY_ALIVE"); }
                        catch (Exception ignored) {}
                    });
                } catch (Exception ignored) {}
                return true;
            }
            // ✅ CORRIGÉ (25 août 2026, demande Paul) — même correctif que
            // la branche "io mort" ci-dessus : le dialogue reste affiché,
            // mais son bouton "Réessayer" fait maintenant le même principe
            // qu'une suppression manuelle de tab (voir plus bas).
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

    /** ✅ AJOUTÉ (7 août 2026, demande Paul — "bouton cancel sur l'écran
     *  diagnostique les phases un à 3") — fermeture propre du dialogue et
     *  log clair quand l'utilisateur annule entre deux étapes. Ne relance
     *  rien — l'utilisateur devra redéclencher le diagnostic manuellement
     *  s'il le souhaite. Le verrou diagnostic est déjà libéré par le
     *  finally de l'appelant (lancerDiagnosticForce) — rien à faire ici. */
    private void fermerDialogueAnnule(android.app.AlertDialog[] dlg) {
        Log.w(TAG, "Diagnostic interrompu par annulation utilisateur");
        try {
            activity.runOnUiThread(() -> {
                if (dlg[0] != null && dlg[0].isShowing()) dlg[0].dismiss();
                try { android.widget.Toast.makeText(activity, "Diagnostic annulé", android.widget.Toast.LENGTH_SHORT).show(); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
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
            try { com.pa.lcr.lcp.log.LogBus.err(node, "RegisterConnectionHelper.dbRead", e); } catch (Exception ignored) {}
        }

        final String fTicketNo = ticketNo;
        final String fSerialIdFinal = fSerialId;
        final int fNodeFinal = fNode;
        final String[] etapes = new String[4];
        final boolean[] etapesOk = new boolean[4];
        final String[] erreurDetail = {""};

        // Dialog progressif
        // ✅ FIX (7 août 2026, demande Paul — "un bouton cancel sur l'écran
        // diagnostique les phases un à 3") — avant ce fix, setCancelable(false)
        // sans aucun bouton, impossible d'interrompre un diagnostic en cours
        // même si l'utilisateur voulait reprendre le contrôle manuellement.
        // Le drapeau est vérifié au DÉBUT de chaque étape (1, 2, 3) — une
        // étape déjà en vol (ex. un Thread.sleep() ou un appel LCP bloquant)
        // continue jusqu'à sa fin naturelle, mais la SUIVANTE ne démarre pas.
        final boolean[] annule = {false};
        final android.app.AlertDialog.Builder dlgBuilder =
            new android.app.AlertDialog.Builder(activity);
        dlgBuilder.setTitle("🔄 Connexion au registre...");
        dlgBuilder.setCancelable(false);
        dlgBuilder.setNegativeButton("Annuler", (d, w) -> {
            annule[0] = true;
            Log.w(TAG, "Diagnostic annulé par l'utilisateur");
        });
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

        // ✅ FIX (4 août 2026, demande Paul — "la recherche du registre selon
        // son média ne fonctionne pas avec diagnostic... il ne cherche pas
        // selon son média") — trouvé : les étapes 1-2 faisaient un reset BT
        // complet (déconnexion + bt.disable()/enable(), ~3.5s) de façon
        // INCONDITIONNELLE, peu importe si le registre visé est sur USB, BT
        // ou TCP. Le deep link appelle TOUJOURS lancerDiagnosticForce("", ...)
        // avec un transportKey VIDE — donc Diagnostic n'avait de toute façon
        // aucune info sur le média réel et traitait systématiquement ça comme
        // un problème BT par défaut, gaspillant ~3.5s de reset BT inutile
        // avant même d'essayer USB (qui est pourtant la priorité #1 depuis le
        // fix d'ordre). Ici : on vérifie si un périphérique USB est
        // physiquement présent AVANT de faire quoi que ce soit — si oui, les
        // étapes 1-2 (spécifiques BT) sont sautées entièrement, on va
        // directement à l'étape 3 (recherche unifiée USB→BT→TCP).
        boolean usbDevicePresent = false;
        try {
            android.hardware.usb.UsbManager um =
                (android.hardware.usb.UsbManager) activity.getSystemService(android.content.Context.USB_SERVICE);
            if (um != null) {
                java.util.Map<String, android.hardware.usb.UsbDevice> devs = um.getDeviceList();
                usbDevicePresent = (devs != null && !devs.isEmpty());
            }
        } catch (Exception ignored) {}
        if (usbDevicePresent) {
            Log.i(TAG, "Diagnostic: périphérique USB physiquement présent — étapes 1-2 (reset BT) sautées, direct à l'étape 3");
        }

        // ÉTAPE 1 — Fermeture connexion existante
        if (annule[0]) { fermerDialogueAnnule(dlg); return; }
        // ✅ FIX (10 août 2026, demande Paul — "je vois toujours que le USB
        // est ignoré, ça fait peur") — formulation corrigée : c'est un
        // comportement NORMAL et VOULU (les étapes spécifiques au BT n'ont
        // pas de sens quand USB est déjà branché), mais "ignorée" sonnait
        // comme un problème plutôt qu'un simple saut logique attendu.
        etapes[0] = (livraisonActive || usbDevicePresent) ? "Fermeture connexion — non nécessaire ("
                + (livraisonActive ? "livraison active" : "USB déjà branché, étape BT non applicable") + ")" : "Fermeture connexion existante";
        updateDlg.run();
        if (!livraisonActive && !usbDevicePresent) {
            try { activity.btDisconnect(); Thread.sleep(800); } catch (Exception ignored) {}
        }
        etapesOk[0] = true;
        updateDlg.run();

        // ÉTAPE 2 — Réinitialisation Bluetooth
        if (annule[0]) { fermerDialogueAnnule(dlg); return; }
        etapes[1] = (livraisonActive || usbDevicePresent) ? "Réinitialisation BT — non nécessaire ("
                + (livraisonActive ? "livraison active" : "USB déjà branché, étape BT non applicable") + ")" : "Réinitialisation Bluetooth";
        updateDlg.run();
        if (!livraisonActive && !usbDevicePresent) {
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
            if (annule[0]) { fermerDialogueAnnule(dlg); return; }
            etapes[2] = "Recherche registre... (" + attempt + "/3)";
            updateDlg.run();

            // ✅ Fermer les sockets BT zombis SEULEMENT à la 1ère tentative,
            // et SEULEMENT si USB n'est pas physiquement présent (voir fix
            // plus haut — inutile et potentiellement nuisible de toucher au
            // BT quand le registre visé est sur USB).
            if (attempt == 1 && !usbDevicePresent) {
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

            // ✅ FIX (4 août 2026, demande Paul — "je veux le détail complet
            // de chaque tentative de connexion sur chaque transport") —
            // affiche maintenant, ligne par ligne, ce qui a été tenté sur
            // CHAQUE transport (USB/BT/TCP), pas juste "Recherche... (1/3)".
            try {
                org.json.JSONArray details = (r.data != null) ? r.data.optJSONArray("attemptsDetail") : null;
                if (details != null && details.length() > 0) {
                    StringBuilder sbDetail = new StringBuilder();
                    for (int di = 0; di < details.length(); di++) {
                        org.json.JSONObject a = details.optJSONObject(di);
                        if (a == null) continue;
                        sbDetail.append("  • ").append(a.optString("media", "?"))
                            .append(" (").append(a.optString("transportKey", "?")).append(") — ")
                            .append(a.optString("outcome", "?")).append("\n");
                    }
                    Log.i(TAG, "étape 3: détail par transport:\n" + sbDetail);
                    etapes[2] = "Recherche registre... (" + attempt + "/3)\n" + sbDetail.toString().trim();
                    updateDlg.run();
                }
            } catch (Exception ignored) {}

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

                // ✅ CORRIGÉ (25 août 2026, demande Paul — "le diagnostique ne
                // fonctionne pas... pourquoi le diagnostic ne suit pas le
                // même chemin que la détection") — trouvé, exactement le
                // même bug que celui corrigé plus tôt aujourd'hui dans le
                // refresh de migration : getController() ne fait que LIRE
                // une session déjà existante. L'hypothèse du 4 août
                // (api_registerConnectAuto() aurait déjà tout créé) ne tient
                // pas toujours en pratique — confirmé par "Controller non
                // disponible" à l'étape 4, alors que l'étape 3 vient de
                // confirmer que le registre a bien été trouvé. getOrCreate()
                // est sûr à appeler même si une session existe déjà
                // (retourne simplement l'existante) — plus de dépendance
                // fragile sur le timing d'un autre appel.
                com.pa.lcr.lcp.transport.TransportIo ioPourDiag = null;
                try { ioPourDiag = activity.getMediaTransportManager().getByKey(fKey); } catch (Exception ignored) {}
                if (ioPourDiag != null) {
                    dcFinal = com.pa.lcr.lcp.RegisterSessionManager.get(activity)
                        .getOrCreate(fKey, fNodeFinal, 255, ioPourDiag);
                } else {
                    dcFinal = com.pa.lcr.lcp.RegisterSessionManager.get(activity)
                        .getController(fKey, fNodeFinal);
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
                // ✅ CORRIGÉ (25 août 2026) — même bug que REGISTRE/PRODUIT
                // corrigés plus tôt aujourd'hui : IDLE manquait ici aussi.
                lcpOk = (st == com.pa.lcr.lcp.DeliveryState.CONNECTED
                    || st == com.pa.lcr.lcp.DeliveryState.IDLE
                    || st == com.pa.lcr.lcp.DeliveryState.RUNNING_FLOWING
                    || st == com.pa.lcr.lcp.DeliveryState.RUNNING_PAUSED
                    || st == com.pa.lcr.lcp.DeliveryState.ENDING);
                if (!lcpOk) {
                    try { dcFinal.requestLiveSample(); } catch (Exception ignored) {}
                    for (int w = 0; w < 30; w++) {
                        try { Thread.sleep(300); } catch (Exception ignored) {}
                        st = dcFinal.getState();
                        if (st == com.pa.lcr.lcp.DeliveryState.CONNECTED
                            || st == com.pa.lcr.lcp.DeliveryState.IDLE
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

                    // ✅ AJOUTÉ (28 août 2026, demande Paul — "s'il y avait
                    // une livraison en cours il ferait quoi, le tab nous
                    // ramènerait automatiquement à running_flowing") —
                    // trouvé : le "dejaLivre" plus bas ne vérifie QUE
                    // l'historique BD (une livraison déjà TERMINÉE) —
                    // aveugle à une livraison RÉELLEMENT EN COURS
                    // maintenant (qui n'a pas encore de ligne en BD,
                    // puisqu'elle n'est pas terminée). Une vraie lecture
                    // synchrone du registre, ici, AVANT toute décision de
                    // relance : si une livraison coule vraiment en ce
                    // moment, requestStatus()/requestLiveSample()
                    // ci-dessus suffisent déjà à ramener l'état à
                    // RUNNING_FLOWING naturellement — aucun besoin
                    // d'appeler lancerLivraison(), qui pourrait montrer le
                    // dialogue "bon déjà complété" ou même réarmer une
                    // livraison, alors qu'une vraie est déjà en cours.
                    if (dc2.api_isDeliveryActiveNow()) {
                        Log.i(TAG, "diagnostic succès — livraison RÉELLEMENT active sur le registre (deliveryActive=1) — "
                            + "aucune relance automatique, l'état RUNNING_FLOWING se rétablit déjà via requestStatus/requestLiveSample ci-dessus");
                        return;
                    }
                }
            } catch (Exception ignored) {}

            // ✅ Relancer la livraison automatiquement si paramètres deep link disponibles
            // ✅ FIX (5 août 2026, demande Paul — "valider si le ticket_number a
            // une quantité net/gross, l'idée est de ne pas avoir de multiples
            // livraisons") — avant de relancer automatiquement, vérifier si la
            // DERNIÈRE livraison enregistrée pour ce WO a déjà du net/gross
            // non-nul. Si oui, une vraie livraison a déjà eu lieu — relancer
            // créerait une livraison EN PLUS, pas une reprise légitime d'une
            // tentative qui n'avait jamais vraiment démarré. On ne relance
            // automatiquement QUE si la dernière tentative était vide (0L),
            // signe qu'elle n'avait jamais réellement livré quoi que ce soit.
            boolean dejaLivre = false;
            try {
                LcrDeliveryStatusDb dbCheck = new LcrDeliveryStatusDb(activity);
                try {
                    LcrDeliveryStatusDb.DeliveryRow last = (woNum != null && !woNum.isEmpty())
                        ? dbCheck.getLatestForWo(woNum) : null;
                    if (last != null && (last.netL > 0.0 || last.grossL > 0.0)) {
                        dejaLivre = true;
                        Log.w(TAG, "diagnostic succès — relance auto ANNULÉE : dernière livraison pour "
                            + woNum + " a déjà net=" + last.netL + " gross=" + last.grossL
                            + " (ticket=" + last.ticketNo + ") — pas de relance pour éviter une livraison en plus");
                    }
                } finally {
                    try { dbCheck.close(); } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                Log.w(TAG, "diagnostic succès — check net/gross avant relance ERR: " + e.getMessage());
                try { com.pa.lcr.lcp.log.LogBus.err(node, "RegisterConnectionHelper.checkNetGrossAvantRelance", e); } catch (Exception ignored) {}
            }

            if (!dejaLivre && deepLinkHandler != null && woNum != null && !woNum.isEmpty()) {
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
                    // ✅ CORRIGÉ (25 août 2026, demande Paul — "on corrige
                    // l'écran diagnostique... réessayer fait le même
                    // principe qu'après la suppression du tab") — d'abord
                    // vérifie si déjà reconnecté ailleurs (comme avant);
                    // sinon, au lieu de l'ancienne boucle interne
                    // (lancerDiagnosticForce, confirmée trop courte par
                    // log réel — 54s observés pour une vraie reconnexion
                    // BT), délègue à removeTabBySerial() — même
                    // comportement qu'une suppression manuelle de tab,
                    // qui laisse la détection passive (déjà fiable)
                    // reprendre le relais.
                    new Thread(() -> {
                        String transportDejaVivant = findAliveTransportForSerial(serialId, node, null);
                        if (transportDejaVivant != null) {
                            Log.i(TAG, "Réessayer: registre déjà joignable sur " + transportDejaVivant
                                    + " — diagnostic sauté, fermeture seule");
                            try { com.pa.lcr.lcp.log.LogBus.ui(node, "[RETRY] Déjà reconnecté sur "
                                    + transportDejaVivant + " — diagnostic sauté"); } catch (Exception ignored) {}
                            activity.runOnUiThread(() -> {
                                try {
                                    android.widget.Toast.makeText(activity, "✅ Registre déjà reconnecté",
                                            android.widget.Toast.LENGTH_SHORT).show();
                                } catch (Exception ignored) {}
                            });
                            return;
                        }
                        Log.i(TAG, "Réessayer: pas déjà vivant — suppression du tab "
                                + "(même principe qu'une suppression manuelle) pour serial=" + serialId + " node=" + node);
                        try { com.pa.lcr.lcp.log.LogBus.ui(node, "[RETRY] Suppression du tab — "
                                + "détection passive prend le relais (comme une suppression manuelle)"); } catch (Exception ignored) {}
                        activity.runOnUiThread(() -> {
                            try { activity.removeTabBySerial(serialId, node, "Réessayer (diagnostic) — même principe que suppression manuelle"); }
                            catch (Exception ignored) {}
                        });
                    }).start();
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
                        try { com.pa.lcr.lcp.log.LogBus.err(node, "RegisterConnectionHelper.restart", e); } catch (Exception ignored) {}
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
                        try { com.pa.lcr.lcp.log.LogBus.err(node, "RegisterConnectionHelper.emailSupport", e); } catch (Exception ignored) {}
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