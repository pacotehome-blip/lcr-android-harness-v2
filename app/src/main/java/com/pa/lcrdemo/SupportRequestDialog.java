package com.pa.lcrdemo;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.pa.lcr.lcp.log.LogBus;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * ✅ AJOUTÉ (6 nov 2026, demande Paul) — icône trombone : demande de support avec contexte joint.
 *
 * Joint automatiquement : le contexte (#série, node, WO, état, ticket, produit, net/gross) dans le
 * message, et le journal LogBus des N dernières minutes (SimplifiedConfig.SUPPORT_JOURNAL_MINUTES)
 * en fichier .txt. Le chauffeur peut ajouter un commentaire texte ET/OU un message vocal (.m4a,
 * 60 s max). Envoi : Outlook, Teams (partage Android vers l'application, sinon sélecteur système)
 * ou appel au support (si SimplifiedConfig.SUPPORT_PHONE est renseigné).
 * ✅ 9 nov 2026 : un seul bouton « Envoyer au support » — courriel à support.ti@filgo.ca (crée le ticket)
 * + message Teams, envoyés en silence par Microsoft Graph ; repli : courriel pré-rempli.
 *
 * Rien n'est envoyé sans que le chauffeur appuie sur un bouton d'envoi ; rien n'est stocké
 * ailleurs que dans le cache de l'app (sous-dossier support/, purgé après 1 jour).
 */
final class SupportRequestDialog {

    private static final String TAG = "SupportRequest";

    private SupportRequestDialog() {}

    static void show(final Activity act, final String contexte, final String serie, final String node,
                     final String wo, final Consumer<Runnable> demandeMicro) {
        new Session(act, contexte, serie, node, wo, demandeMicro).ouvrir();
    }

    private static final class Session {
        final Activity act;
        final String contexte, serie, node, wo;
        final Consumer<Runnable> demandeMicro;
        final Handler ui = new Handler(Looper.getMainLooper());

        MediaRecorder recorder = null;
        File fichierAudio = null;
        long debutEnregistrement = 0L;
        boolean enregistre = false;     // un fichier audio valide existe
        MaterialButton btnRec;
        MaterialButton btnEnvoyer;
        TextView txtTimer;
        EditText edtComment;
        androidx.appcompat.app.AlertDialog dialogue;

        final Runnable tickTimer = new Runnable() {
            @Override public void run() {
                if (recorder == null) return;
                long s = (System.currentTimeMillis() - debutEnregistrement) / 1000;
                txtTimer.setText(formaterDuree(s) + " / " + formaterDuree(SimplifiedConfig.AUDIO_MAX_SECONDS));
                ui.postDelayed(this, 400);
            }
        };

        Session(Activity act, String contexte, String serie, String node, String wo, Consumer<Runnable> demandeMicro) {
            this.act = act;
            this.contexte = contexte;
            this.serie = serie;
            this.node = node;
            this.wo = wo;
            this.demandeMicro = demandeMicro;
        }

        float dp(float v) { return v * act.getResources().getDisplayMetrics().density; }

        TextView etiquette(String t) {
            TextView v = new TextView(act);
            v.setText(t);
            v.setTextSize(12f);
            v.setTypeface(null, android.graphics.Typeface.BOLD);
            v.setTextColor(Color.parseColor("#3A3A3A"));
            v.setPadding(0, (int) dp(14), 0, (int) dp(4));
            return v;
        }

        void ouvrir() {
            purgerAnciensFichiers();

            LinearLayout col = new LinearLayout(act);
            col.setOrientation(LinearLayout.VERTICAL);
            int p = (int) dp(20);
            col.setPadding(p, (int) dp(4), p, (int) dp(8));

            col.addView(etiquette("JOINT AUTOMATIQUEMENT"));
            TextView ctx = new TextView(act);
            ctx.setText(contexte + "\nJournal des " + SimplifiedConfig.SUPPORT_JOURNAL_MINUTES + " dernières minutes");
            ctx.setTextSize(13f);
            ctx.setTextColor(Color.parseColor("#111111"));
            col.addView(ctx);

            col.addView(etiquette("VOTRE MESSAGE (FACULTATIF)"));
            LinearLayout rangeeRec = new LinearLayout(act);
            rangeeRec.setOrientation(LinearLayout.HORIZONTAL);
            rangeeRec.setGravity(Gravity.CENTER_VERTICAL);
            btnRec = new MaterialButton(act);
            btnRec.setText("Enregistrer un message vocal");
            btnRec.setAllCaps(false);
            btnRec.setIconResource(R.drawable.ic_mic);
            btnRec.setIconTint(android.content.res.ColorStateList.valueOf(Color.WHITE));
            btnRec.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#024B3C")));
            btnRec.setOnClickListener(v -> basculerEnregistrement());
            rangeeRec.addView(btnRec, new LinearLayout.LayoutParams(0, (int) dp(52), 1f));
            txtTimer = new TextView(act);
            txtTimer.setText("0:00 / " + formaterDuree(SimplifiedConfig.AUDIO_MAX_SECONDS));
            txtTimer.setTextSize(14f);
            txtTimer.setTextColor(Color.parseColor("#111111"));
            txtTimer.setPadding((int) dp(12), 0, 0, 0);
            rangeeRec.addView(txtTimer);
            col.addView(rangeeRec);

            col.addView(etiquette("OU ÉCRIRE UN COMMENTAIRE"));
            edtComment = new EditText(act);
            edtComment.setHint("Décrivez le problème…");
            edtComment.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            edtComment.setMinLines(2);
            edtComment.setMaxLines(5);
            edtComment.setGravity(Gravity.TOP | Gravity.START);
            col.addView(edtComment, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            btnEnvoyer = new MaterialButton(act);
            btnEnvoyer.setText("Envoyer au support");
            btnEnvoyer.setAllCaps(false);
            btnEnvoyer.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#024B3C")));
            btnEnvoyer.setOnClickListener(v -> envoyer());
            LinearLayout.LayoutParams lpEnv = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (int) dp(56));
            lpEnv.topMargin = (int) dp(18);
            col.addView(btnEnvoyer, lpEnv);

            ScrollView sv = new ScrollView(act);
            sv.addView(col);

            dialogue = new MaterialAlertDialogBuilder(act)
                    .setTitle("Demander du support")
                    .setView(sv)
                    .setNegativeButton("Annuler", null)
                    .setOnDismissListener(d -> arreterEnregistrement(false))
                    .show();
        }

        // ---------------------------------------------------------------- audio

        void basculerEnregistrement() {
            if (recorder != null) { arreterEnregistrement(true); return; }
            if (ContextCompat.checkSelfPermission(act, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                demarrerEnregistrement();
            } else if (demandeMicro != null) {
                demandeMicro.accept(this::demarrerEnregistrement);
            }
        }

        void demarrerEnregistrement() {
            try {
                File dir = dossierSupport();
                fichierAudio = new File(dir, "message_vocal.m4a");
                if (fichierAudio.exists()) fichierAudio.delete();
                MediaRecorder r = new MediaRecorder();
                r.setAudioSource(MediaRecorder.AudioSource.MIC);
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                r.setAudioEncodingBitRate(64000);
                r.setAudioSamplingRate(44100);
                r.setOutputFile(fichierAudio.getAbsolutePath());
                r.setMaxDuration(SimplifiedConfig.AUDIO_MAX_SECONDS * 1000);
                r.setOnInfoListener((mr, what, extra) -> {
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        ui.post(() -> arreterEnregistrement(true));
                    }
                });
                r.prepare();
                r.start();
                recorder = r;
                enregistre = false;
                debutEnregistrement = System.currentTimeMillis();
                btnRec.setText("Arrêter");
                btnRec.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#B3261E")));
                ui.post(tickTimer);
            } catch (Exception e) {
                Log.w(TAG, "demarrerEnregistrement ERR: " + e.getMessage());
                liberer();
                Toast.makeText(act, "Impossible de démarrer l’enregistrement", Toast.LENGTH_SHORT).show();
            }
        }

        /** @param garder true = conserver le fichier (arrêt voulu), false = jeter (fermeture du dialogue). */
        void arreterEnregistrement(boolean garder) {
            if (recorder == null) return;
            long ms = System.currentTimeMillis() - debutEnregistrement;
            boolean ok = false;
            try {
                recorder.stop();
                ok = true;
            } catch (RuntimeException e) {
                Log.w(TAG, "stop ERR (enregistrement trop court ?): " + e.getMessage());
            }
            liberer();
            ui.removeCallbacks(tickTimer);
            if (ok && garder && fichierAudio != null && fichierAudio.length() > 0) {
                enregistre = true;
                btnRec.setText("Message vocal prêt (" + formaterDuree(ms / 1000) + ") — refaire");
            } else {
                enregistre = false;
                if (fichierAudio != null) fichierAudio.delete();
                if (btnRec != null) btnRec.setText("Enregistrer un message vocal");
            }
            if (btnRec != null) {
                btnRec.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#024B3C")));
            }
        }

        void liberer() {
            try { if (recorder != null) recorder.release(); } catch (Exception ignored) {}
            recorder = null;
        }

        // ---------------------------------------------------------------- envoi

        void envoyer() {
            if (recorder != null) arreterEnregistrement(true);
            btnEnvoyer.setEnabled(false);
            btnEnvoyer.setText("Envoi en cours…");
            final String commentaire = edtComment.getText() != null ? edtComment.getText().toString().trim() : "";
            final boolean avecAudio = enregistre && fichierAudio != null && fichierAudio.exists();
            // Le journal peut être gros : écrit hors du fil UI.
            new Thread(() -> {
                try {
                    File dir = dossierSupport();
                    String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
                    File journal = new File(dir, "journal_" + nettoyer(serie) + "_" + ts + ".txt");
                    try (Writer w = new OutputStreamWriter(new FileOutputStream(journal), StandardCharsets.UTF_8)) {
                        w.write("Demande de support — Filgo-Registre\n");
                        w.write(contexte + "\n");
                        if (!commentaire.isEmpty()) w.write("\nCommentaire du chauffeur :\n" + commentaire + "\n");
                        w.write("\n===== Journal (" + SimplifiedConfig.SUPPORT_JOURNAL_MINUTES + " dernières minutes) =====\n");
                        w.write(construireJournal());
                    }
                    final File audio = avecAudio ? fichierAudio : null;

                    final StringBuilder corps = new StringBuilder(contexte);
                    if (!commentaire.isEmpty()) corps.append("\n\nCommentaire :\n").append(commentaire);
                    if (avecAudio) corps.append("\n\n(Message vocal joint)");
                    corps.append("\n\nPrise en main à distance (SOTI MobiControl) — ID de la tablette : ")
                         .append(idSoti());
                    if (!SimplifiedConfig.SOTI_CONSOLE_URL.isEmpty())
                        corps.append("\nConsole : ").append(SimplifiedConfig.SOTI_CONSOLE_URL);
                    final String sujet = "Support Filgo-Registre — " + SimplifiedUiState.nomTablette(act)
                            + " — WO " + (wo.isEmpty() ? "—" : wo)
                            + " — #" + serie + " Node " + node;

                    // 1) Envoi silencieux par Microsoft Graph (courriel + Teams)
                    SupportGraphSender.envoyer(act, sujet, corps.toString(), journal, audio,
                            new SupportGraphSender.Resultat() {
                        @Override public void courrielEnvoye(boolean teamsOk) {
                            act.runOnUiThread(() -> {
                                Toast.makeText(act, teamsOk
                                        ? "Demande envoyée au support (courriel + Teams)"
                                        : "Demande envoyée au support (courriel)", Toast.LENGTH_LONG).show();
                                if (dialogue != null) dialogue.dismiss();
                            });
                        }
                        @Override public void echec(String raison) {
                            Log.w(TAG, "Graph indisponible → repli courriel : " + raison);
                            // 2) Repli : courriel pré-rempli, le chauffeur appuie sur Envoyer
                            try {
                                final ArrayList<Uri> uris = new ArrayList<>();
                                String auth = act.getPackageName() + ".fileprovider";
                                uris.add(FileProvider.getUriForFile(act, auth, journal));
                                if (audio != null) uris.add(FileProvider.getUriForFile(act, auth, audio));
                                act.runOnUiThread(() -> {
                                    lancerCourriel(sujet, corps.toString(), uris);
                                    reactiverBouton();
                                });
                            } catch (Exception e) {
                                act.runOnUiThread(() -> {
                                    Toast.makeText(act, "Impossible d’envoyer la demande de support", Toast.LENGTH_LONG).show();
                                    reactiverBouton();
                                });
                            }
                        }
                    });
                } catch (Exception e) {
                    Log.w(TAG, "envoyer ERR: " + e.getMessage());
                    act.runOnUiThread(() -> {
                        Toast.makeText(act, "Impossible de préparer la demande de support", Toast.LENGTH_LONG).show();
                        reactiverBouton();
                    });
                }
            }, "support-prepare").start();
        }

        /** La tablette porte le même identifiant que son profil SOTI : c'est le nom d'appareil Android. */
        String idSoti() {
            String nom = null;
            try { nom = android.provider.Settings.Global.getString(act.getContentResolver(), "device_name"); } catch (Throwable ignored) {}
            return (nom != null && !nom.trim().isEmpty()) ? nom.trim() : SimplifiedUiState.nomTablette(act);
        }

        void reactiverBouton() {
            btnEnvoyer.setEnabled(true);
            btnEnvoyer.setText("Envoyer au support");
        }

        void lancerCourriel(String sujet, String corps, ArrayList<Uri> uris) {
            Intent i = new Intent(Intent.ACTION_SEND_MULTIPLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_SUBJECT, sujet);
            i.putExtra(Intent.EXTRA_TEXT, corps);
            i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            i.putExtra(Intent.EXTRA_EMAIL, new String[]{SimplifiedConfig.SUPPORT_EMAIL});
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                Intent direct = new Intent(i);
                direct.setPackage(SimplifiedConfig.OUTLOOK_PACKAGE);
                act.startActivity(direct);
            } catch (ActivityNotFoundException e) {
                try {
                    act.startActivity(Intent.createChooser(i, "Envoyer la demande de support"));
                } catch (Exception e2) {
                    Toast.makeText(act, "Aucune application disponible pour l’envoi", Toast.LENGTH_LONG).show();
                }
            }
        }

        // ---------------------------------------------------------------- journal & fichiers

        String construireJournal() {
            long depuis = System.currentTimeMillis() - SimplifiedConfig.SUPPORT_JOURNAL_MINUTES * 60_000L;
            List<LogBus.LogEvent> evts = LogBus.snapshotGlobal(5000, depuis);
            SimpleDateFormat df = new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT);
            StringBuilder sb = new StringBuilder(Math.max(1024, evts.size() * 96));
            for (LogBus.LogEvent e : evts) {
                sb.append(df.format(new Date(e.ts))).append(' ')
                  .append(e.src != null ? e.src.name() : "?").append(" n").append(e.node).append(' ')
                  .append(e.msg != null ? e.msg : "").append('\n');
            }
            if (evts.isEmpty()) sb.append("(aucun événement dans cette période)\n");
            return sb.toString();
        }

        File dossierSupport() {
            File dir = new File(act.getCacheDir(), "support");
            if (!dir.exists()) dir.mkdirs();
            return dir;
        }

        void purgerAnciensFichiers() {
            try {
                File[] fs = dossierSupport().listFiles();
                if (fs == null) return;
                long limite = System.currentTimeMillis() - 24L * 3600_000L;
                for (File f : fs) if (f.lastModified() < limite) f.delete();
            } catch (Exception ignored) {}
        }

        String nettoyer(String s) {
            return s == null ? "x" : s.replaceAll("[^A-Za-z0-9_-]", "_");
        }

        String formaterDuree(long secondes) {
            return (secondes / 60) + ":" + String.format(Locale.ROOT, "%02d", secondes % 60);
        }
    }
}
