package com.pa.lcrdemo;

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

    /** Réinitialise le guard — appeler au démarrage APK ou si bloqué */
    public static void resetDiagnostic() {
        diagnosticEnCours = false;
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
            lancerDiagnostic(tkFinal, node, serialId, woNum);
            return false;
        }

        // 2. Vérifier état controller
        boolean tickOk = false;
        try {
            com.pa.lcr.lcp.DeliveryController dc =
                RegisterSessionManager.get(activity).getController(tkFinal, node);
            if (dc != null) {
                com.pa.lcr.lcp.DeliveryState st = dc.getState();
                tickOk = (st == com.pa.lcr.lcp.DeliveryState.CONNECTED
                    || st == com.pa.lcr.lcp.DeliveryState.RUNNING_FLOWING
                    || st == com.pa.lcr.lcp.DeliveryState.RUNNING_PAUSED
                    || st == com.pa.lcr.lcp.DeliveryState.ENDING);
                if (!tickOk) {
                    com.pa.lcr.lcp.ApiResult ping = dc.api_tickSnapshot();
                    tickOk = (ping != null && ping.code == 1);
                    if (!tickOk) Log.w(TAG, "tickSnapshot fail: " + (ping != null ? ping.msg : "null"));
                }
            } else {
                Log.w(TAG, "validerConnexion: controller null pour transport=" + tkFinal);
            }
        } catch (Exception e) {
            Log.w(TAG, "tickSnapshot ERR: " + e.getMessage());
        }

        if (!tickOk) {
            Log.w(TAG, "validerConnexion: registre ne répond pas — transport=" + tkFinal);
            lancerDiagnostic(tkFinal, node, serialId, woNum);
            return false;
        }

        Log.i(TAG, "validerConnexion: OK — transport=" + tkFinal + " node=" + node);
        return true;
    }

    // =========================================================
    // Diagnostic en background — 4 étapes avec dialog progressif
    // =========================================================

    private void lancerDiagnostic(String transportKey, int node, String serialId, String woNum) {
        if (diagnosticEnCours) {
            Log.w(TAG, "lancerDiagnostic: diagnostic déjà en cours — ignoré");
            return;
        }
        diagnosticEnCours = true;
        new Thread(() -> {
            try {
                diagnostic(transportKey, node, serialId, woNum);
            } finally {
                diagnosticEnCours = false;
            }
        }).start();
    }

    /**
     * ✅ Force le diagnostic même si diagnosticEnCours=true.
     * Utilisé après oneshot/start orchestration error — le registre ne répond pas
     * même si BT est connecté (câble série débranché par exemple).
     */
    public void lancerDiagnosticForce(String transportKey, int node, String serialId, String woNum) {
        if (diagnosticEnCours) {
            Log.w(TAG, "lancerDiagnosticForce: reset guard et relance");
            diagnosticEnCours = false;
        }
        diagnosticEnCours = true;
        try {
            diagnostic(transportKey, node, serialId, woNum);
        } finally {
            diagnosticEnCours = false;
        }
    }

    private void diagnostic(String transportKey, int node, String serialId, String woNum) {
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
                    bt.disable(); Thread.sleep(1500);
                    bt.enable();  Thread.sleep(2000);
                }
            } catch (Exception ignored) {}
        }
        etapesOk[1] = true;
        updateDlg.run();

        // ÉTAPE 3 — Connexion au registre via api_registerConnectAuto
        // Même fonction que DeepLinkHandler — scan BT/USB, probe serial, connexion LCP
        boolean btConnecte = false;
        com.pa.lcr.lcp.DeliveryController dcFinal = null;
        for (int t = 1; t <= 3; t++) {
            etapes[2] = "Connexion au registre... (tentative " + t + "/3)";
            updateDlg.run();
            try {
                com.pa.lcr.lcp.RegisterSessionManager rsm =
                    com.pa.lcr.lcp.RegisterSessionManager.get(activity);
                if (!fSerialIdFinal.isEmpty()) rsm.bindExpectedSerial(fNodeFinal, fSerialIdFinal);

                Log.i(TAG, "étape 3: tentative " + t + "/3 — api_registerConnectAuto node=" + fNodeFinal + " serial=" + fSerialIdFinal);
                com.pa.lcr.lcp.ApiResult r =
                    new com.pa.lcr.lcp.MultiRegisterApiFacadeImpl(activity)
                        .api_registerConnectAuto(fSerialIdFinal.isEmpty() ? null : fSerialIdFinal, fNodeFinal);
                Log.i(TAG, "étape 3: tentative " + t + "/3 — code=" + (r != null ? r.code : "null") + " msg=" + (r != null ? r.msg : "null"));

                // ✅ Enrichir le message avec BT MAC et identifiant
                String transportInfo = "";
                if (r != null && r.data != null) {
                    String tk = r.data.optString("transportKey", "");
                    String serial = r.data.optString("serial", "");
                    if (!tk.isEmpty()) {
                        String mac = tk.startsWith("BT:") ? tk.substring(3) : tk;
                        transportInfo = "\nBT: " + mac + (serial.isEmpty() ? "" : " | Serial: " + serial);
                    }
                    // Si code=0, afficher les transports essayés
                    if (r.code != 1) {
                        org.json.JSONArray tried = r.data.optJSONArray("tried");
                        if (tried != null && tried.length() > 0) {
                            StringBuilder sb2 = new StringBuilder("\nEssayé: ");
                            for (int i = 0; i < tried.length(); i++) {
                                String k = tried.optString(i, "");
                                if (k.startsWith("BT:")) k = "BT:" + k.substring(3);
                                sb2.append(k).append(" ");
                            }
                            transportInfo = sb2.toString().trim();
                        }
                    }
                }
                final String tInfo = transportInfo;
                final int tFinal = t;
                activity.runOnUiThread(() -> {
                    if (dlg[0] == null || !dlg[0].isShowing()) return;
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 4; i++) {
                        if (etapes[i] == null) break;
                        String icon = etapesOk[i] ? "✅" : (i == 2 && !tInfo.isEmpty() ? "🔄" : "🔄");
                        sb.append(icon).append(" Étape ").append(i+1).append("/4 — ").append(etapes[i]).append("\n");
                    }
                    if (!tInfo.isEmpty()) sb.append(tInfo).append("\n");
                    txtProgress.setText(sb.toString().trim());
                });

                if (r != null && r.code == 1) {
                    // ✅ Registre trouvé — récupérer le controller créé par api_registerConnectAuto
                    String foundKey = r.data != null ? r.data.optString("transportKey", "") : "";
                    if (!foundKey.isEmpty()) {
                        dcFinal = rsm.getController(foundKey, fNodeFinal);
                    }
                    if (dcFinal == null) {
                        dcFinal = rsm.resolveOrCreateForNode(fNodeFinal, 255);
                    }
                    btConnecte = true;
                    etapesOk[2] = true;
                    Log.i(TAG, "étape 3: CONNECTED ✓ transport=" + foundKey);
                    break;
                }
                erreurDetail[0] = r != null ? r.msg : "Timeout";
            } catch (Exception e) {
                erreurDetail[0] = e.getMessage() != null ? e.getMessage() : "Erreur inconnue";
                Log.w(TAG, "étape 3 ERR: " + erreurDetail[0]);
            }
            if (t < 3) {
                etapes[2] = "Connexion au registre... tentative " + t + " échouée — nouvelle tentative dans 5s";
                updateDlg.run();
                try { Thread.sleep(5000); } catch (Exception ignored) {}
            }
        }
        updateDlg.run();

        if (!btConnecte) {
            afficherEchec(dlg[0], etapes, etapesOk,
                "BT Failed to connect (3/3 tentatives)\n" + erreurDetail[0] + "\n\n"
                + "⚡ Assurez-vous que :\n• Le Bluetooth est activé\n• Le registre est sous tension\n• Le registre est en mode communication BT",
                woNum, fTicketNo, fNodeFinal, fSerialIdFinal);
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
                woNum, fTicketNo, fNodeFinal, fSerialIdFinal);
            return;
        }

        // Succès — fermer dialog et basculer vers le tab du registre
        Log.i(TAG, "diagnostic: registre joignable — node=" + fNodeFinal + " serial=" + fSerialIdFinal);
        activity.runOnUiThread(() -> {
            if (dlg[0] != null) dlg[0].dismiss();
            try { activity.showPage(0); } catch (Exception ignored) {}
            activity.getUiHandler().postDelayed(() -> {
                try {
                    com.pa.lcr.lcp.RegisterSessionManager rsm2 =
                        com.pa.lcr.lcp.RegisterSessionManager.get(activity);
                    com.pa.lcr.lcp.DeliveryController dc2 = rsm2.resolveOrCreateForNode(fNodeFinal, 255);
                    if (dc2 != null) {
                        dc2.requestStatus();
                        dc2.requestLiveSample();
                    }
                } catch (Exception ignored) {}
            }, 1000);
        });
    }


    private void afficherEchec(
            android.app.AlertDialog dlgPrev,
            String[] etapes, boolean[] etapesOk,
            String erreur, String woNum, String ticketNo,
            int node, String serialId) {

        activity.runOnUiThread(() -> {
            if (dlgPrev != null && dlgPrev.isShowing()) dlgPrev.dismiss();

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                if (etapes[i] == null) break;
                sb.append(etapesOk[i] ? "✅" : "❌")
                  .append(" Étape ").append(i+1).append("/4 — ").append(etapes[i]).append("\n");
            }
            sb.append("\n⛔ ").append(erreur);
            // ✅ WO vide = ouverture directe APK ou livraison manuelle
            if (woNum == null || woNum.isEmpty()) {
                sb.append("\n\nℹ️ Aucun bon de travail actif\n");
                sb.append("Livraison manuelle — aucun WO associé\n");
                sb.append("\nVous pouvez :\n");
                sb.append("• Lancer une livraison depuis Field Service Mobile\n");
                sb.append("• Ou connecter le registre via l'onglet Configure");
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
                .setCancelable(false)
                .setPositiveButton("🔄 Réessayer", (d, w) -> {
                    d.dismiss();
                    // Relancer le diagnostic
                    lancerDiagnostic(
                        activity.getMediaTransportManager().listSnapshots() != null
                            && !activity.getMediaTransportManager().listSnapshots().isEmpty()
                            ? activity.getMediaTransportManager().listSnapshots().get(0).key : "",
                        node, serialId, woNum);
                })
                .setNeutralButton("🔁 Redémarrer APK", (d, w) -> {
                    // Après restart → reprise automatique depuis ActiveDeliveryStore
                    // via checkPendingDeliveryForThisRegister (PENDING/STARTED)
                    try {
                        Intent intent = activity.getPackageManager()
                            .getLaunchIntentForPackage(activity.getPackageName());
                        if (intent != null) {
                            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                                | Intent.FLAG_ACTIVITY_NEW_TASK);
                            activity.startActivity(intent);
                        }
                        android.os.Process.killProcess(android.os.Process.myPid());
                    } catch (Exception e) {
                        Log.e(TAG, "Restart ERR: " + e.getMessage());
                    }
                })
                .setNegativeButton("📧 Envoyer courriel", (d, w) -> {
                    try {
                        Intent email = new Intent(Intent.ACTION_SEND);
                        email.setType("message/rfc822");
                        email.putExtra(Intent.EXTRA_EMAIL, new String[]{SUPPORT_EMAIL});
                        email.putExtra(Intent.EXTRA_SUBJECT, sujet);
                        email.putExtra(Intent.EXTRA_TEXT, corps);
                        activity.startActivity(Intent.createChooser(email, "Envoyer courriel support"));
                    } catch (Exception e) {
                        Log.e(TAG, "Email ERR: " + e.getMessage());
                    }
                    d.dismiss();
                })
                .setCancelable(true) // ✅ X ferme le dialog (bouton Back ou tap extérieur)
                .show();
        });
    }

    // =========================================================
    // Utilitaires
    // =========================================================

    /**
     * Détecte si une exception est une erreur de connexion au registre.
     * Utilisé dans les catch de RegisterTabFragment pour déclencher
     * automatiquement l'écran de diagnostic.
     */
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

    private int getPremierEchec(boolean[] etapesOk) {
        for (int i = 0; i < etapesOk.length; i++) {
            if (!etapesOk[i]) return i;
        }
        return etapesOk.length - 1;
    }
}