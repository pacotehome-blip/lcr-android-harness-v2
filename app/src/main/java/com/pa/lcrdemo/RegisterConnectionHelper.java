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
        // 1. Vérifier io.isOpen()
        boolean ioOk = false;
        try {
            TransportIo io = activity.getMediaTransportManager().getByKey(transportKey);
            ioOk = (io != null && io.isOpen());
        } catch (Exception e) {
            Log.w(TAG, "isOpen check ERR: " + e.getMessage());
        }

        if (!ioOk) {
            Log.w(TAG, "validerConnexion: io mort — transport=" + transportKey);
            lancerDiagnostic(transportKey, node, serialId, woNum);
            return false;
        }

        // 2. Vérifier api_tickSnapshot()
        boolean tickOk = false;
        try {
            com.pa.lcr.lcp.DeliveryController dc =
                RegisterSessionManager.get(activity).getController(transportKey, node);
            if (dc != null) {
                com.pa.lcr.lcp.ApiResult ping = dc.api_tickSnapshot();
                tickOk = (ping != null && ping.code == 1);
                if (!tickOk) {
                    Log.w(TAG, "tickSnapshot fail: " + (ping != null ? ping.msg : "null"));
                }
            } else {
                Log.w(TAG, "validerConnexion: controller null");
            }
        } catch (Exception e) {
            Log.w(TAG, "tickSnapshot ERR: " + e.getMessage());
        }

        if (!tickOk) {
            Log.w(TAG, "validerConnexion: registre ne répond pas — transport=" + transportKey);
            lancerDiagnostic(transportKey, node, serialId, woNum);
            return false;
        }

        Log.i(TAG, "validerConnexion: OK — transport=" + transportKey + " node=" + node);
        return true;
    }

    // =========================================================
    // Diagnostic en background — 4 étapes avec dialog progressif
    // =========================================================

    private void lancerDiagnostic(String transportKey, int node, String serialId, String woNum) {
        new Thread(() -> diagnostic(transportKey, node, serialId, woNum)).start();
    }

    private void diagnostic(String transportKey, int node, String serialId, String woNum) {
        // Lire contexte depuis LcrDeliveryStatusDb
        String ticketNo = "";
        String serialIdDb = serialId;
        try {
            LcrDeliveryStatusDb db = new LcrDeliveryStatusDb(activity);
            LcrDeliveryStatusDb.DeliveryRow row = db.getLatestForWo(woNum != null ? woNum : "");
            if (row != null) {
                if (row.ticketNo != null) ticketNo = row.ticketNo;
                if (row.serialId != null && !row.serialId.isEmpty()) serialIdDb = row.serialId;
            }
        } catch (Exception e) {
            Log.w(TAG, "DB read ERR: " + e.getMessage());
        }

        final String fTicketNo  = ticketNo;
        final String fSerialId  = serialIdDb;
        final String[] etapes   = new String[4];
        final boolean[] etapesOk = new boolean[4];
        final String[] erreurDetail = {""};

        // Dialog progressif
        final android.app.AlertDialog.Builder dlgBuilder =
            new android.app.AlertDialog.Builder(activity);
        dlgBuilder.setTitle("🔄 Connexion au registre...");
        dlgBuilder.setCancelable(false);
        final TextView txtProgress = new TextView(activity);
        txtProgress.setPadding(40, 20, 40, 20);
        txtProgress.setTextSize(13f);
        dlgBuilder.setView(txtProgress);
        final android.app.AlertDialog[] dlg = {null};
        activity.runOnUiThread(() -> dlg[0] = dlgBuilder.show());

        Runnable updateDlg = () -> activity.runOnUiThread(() -> {
            if (dlg[0] == null || !dlg[0].isShowing()) return;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                if (etapes[i] == null) break;
                String icon = etapesOk[i] ? "✅" : (!erreurDetail[0].isEmpty() && i == getPremierEchec(etapesOk) ? "❌" : "🔄");
                sb.append(icon).append(" Étape ").append(i+1).append("/4 — ").append(etapes[i]).append("\n");
            }
            txtProgress.setText(sb.toString().trim());
        });

        // ÉTAPE 1 — Fermeture connexion existante
        etapes[0] = "Fermeture connexion existante";
        updateDlg.run();
        try {
            activity.btDisconnect();
            Thread.sleep(800);
            etapesOk[0] = true;
        } catch (Exception e) {
            etapesOk[0] = true; // non bloquant
        }
        updateDlg.run();

        // ÉTAPE 2 — Réinitialisation Bluetooth
        etapes[1] = "Réinitialisation Bluetooth";
        updateDlg.run();
        try {
            android.bluetooth.BluetoothAdapter bt =
                android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (bt != null && bt.isEnabled()) {
                bt.disable();
                Thread.sleep(1500);
                bt.enable();
                Thread.sleep(2000);
            }
            etapesOk[1] = true;
        } catch (Exception e) {
            etapesOk[1] = true; // non bloquant
        }
        updateDlg.run();

        // ÉTAPE 3 — Connexion BT au registre (3 tentatives)
        // Vérifier d'abord si io est vraiment ouvert
        boolean btConnecte = false;
        try {
            TransportIo existingIo = activity.getMediaTransportManager().getByKey(transportKey);
            if (existingIo != null && existingIo.isOpen()) {
                Log.i(TAG, "étape 3: io ouvert — ping direct");
                btConnecte = true;
                etapesOk[2] = true;
                etapes[2] = "Connexion au registre... transport actif ✓";
                updateDlg.run();
            } else {
                Log.w(TAG, "étape 3: io fermé — zombi BT détecté");
            }
        } catch (Exception ignored) {}

        if (!btConnecte) {
            for (int t = 1; t <= 3; t++) {
                etapes[2] = "Connexion au registre... (tentative " + t + "/3)";
                updateDlg.run();
                try {
                    com.pa.lcr.lcp.ApiResult r =
                        new MultiRegisterApiFacadeImpl(activity)
                            .api_registerConnectAuto(
                                fSerialId.isEmpty() ? null : fSerialId, node);
                    if (r != null && r.code == 1) {
                        btConnecte = true;
                        etapesOk[2] = true;
                        break;
                    }
                    erreurDetail[0] = r != null ? r.msg : "Timeout";
                } catch (Exception e) {
                    erreurDetail[0] = e.getMessage() != null ? e.getMessage() : "Erreur inconnue";
                }
                if (t < 3) {
                    try { Thread.sleep(1500); } catch (Exception ignored) {}
                }
            }
            updateDlg.run();
        }

        if (!btConnecte) {
            afficherEchec(dlg[0], etapes, etapesOk,
                "BT Failed to connect (3/3 tentatives)\n" + erreurDetail[0],
                woNum, fTicketNo, node, fSerialId);
            return;
        }

        // ÉTAPE 4 — Vérification registre LCR (ping)
        etapes[3] = "Vérification registre LCR...";
        updateDlg.run();
        boolean lcpOk = false;
        try {
            Thread.sleep(700);
            com.pa.lcr.lcp.RegisterSessionManager rsm =
                com.pa.lcr.lcp.RegisterSessionManager.get(activity);
            com.pa.lcr.lcp.DeliveryController dc =
                rsm.getController(transportKey, node);

            // ✅ Si controller absent — le créer via getOrCreate
            if (dc == null) {
                com.pa.lcr.lcp.transport.TransportIo io =
                    activity.getMediaTransportManager().getByKey(transportKey);
                if (io != null && io.isOpen()) {
                    dc = rsm.getOrCreate(transportKey, node, 255, io);
                    Log.i(TAG, "étape 4: controller créé — attente CONNECTED");
                    // Attendre CONNECTED max 15s
                    for (int w = 0; w < 75; w++) {
                        if (dc.getState() == com.pa.lcr.lcp.DeliveryState.CONNECTED) break;
                        Thread.sleep(200);
                    }
                }
            }

            // ✅ Vérifier que le controller est vraiment CONNECTED avant de pinger
            if (dc != null && dc.getState() != com.pa.lcr.lcp.DeliveryState.CONNECTED) {
                Log.w(TAG, "étape 4: controller état=" + dc.getState() + " — attente supplémentaire");
                for (int w = 0; w < 25; w++) {
                    if (dc.getState() == com.pa.lcr.lcp.DeliveryState.CONNECTED) break;
                    Thread.sleep(200);
                }
            }

            if (dc != null) {
                com.pa.lcr.lcp.ApiResult ping = dc.api_tickSnapshot();
                lcpOk = (ping != null && ping.code == 1);
                if (!lcpOk) erreurDetail[0] = ping != null ? ping.msg : "Pas de réponse LCP";
                Log.i(TAG, "étape 4: tickSnapshot code=" + (ping != null ? ping.code : "null")
                    + " state=" + dc.getState());
            } else {
                erreurDetail[0] = "Controller non disponible après reconnexion";
            }
        } catch (Exception e) {
            erreurDetail[0] = e.getMessage() != null ? e.getMessage() : "Timeout LCP";
        }
        etapesOk[3] = lcpOk;
        updateDlg.run();

        if (!lcpOk) {
            afficherEchec(dlg[0], etapes, etapesOk,
                "BT connecté mais registre LCR ne répond pas\n"
                    + "Vérifiez que le registre est allumé\n" + erreurDetail[0],
                woNum, fTicketNo, node, fSerialId);
            return;
        }

        // Succès — fermer dialog et basculer vers le tab du registre
        Log.i(TAG, "diagnostic: registre joignable après reconnexion — basculer vers tab registre");
        activity.runOnUiThread(() -> {
            if (dlg[0] != null) dlg[0].dismiss();
            // ✅ Basculer vers le tab du registre
            try { activity.showPage(0); } catch (Exception ignored) {}
            // ✅ Forcer lecture état registre après 1s — ticket, net, status
            activity.getUiHandler().postDelayed(() -> {
                try {
                    com.pa.lcr.lcp.RegisterSessionManager rsm2 =
                        com.pa.lcr.lcp.RegisterSessionManager.get(activity);
                    com.pa.lcr.lcp.DeliveryController dc2 =
                        rsm2.getController(transportKey, node);
                    if (dc2 != null) {
                        dc2.requestStatus();
                        dc2.requestLiveSample();
                    }
                } catch (Exception ignored) {}
            }, 1000);
        });
    }

    // =========================================================
    // Dialog échec final
    // =========================================================

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
            sb.append("\n\nWO: ").append(woNum != null ? woNum : "—");
            sb.append(" | Ticket: ").append(ticketNo != null && !ticketNo.isEmpty() ? ticketNo : "—");
            sb.append("\nNode: ").append(node).append(" | Serial: ").append(serialId);
            sb.append("\n\nContactez le support :\n").append(SUPPORT_EMAIL);

            final String resumeComplet = sb.toString();
            final String sujet = "[Filgo-Sonic] Registre non joignable — WO:" + woNum;
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