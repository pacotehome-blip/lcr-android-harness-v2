package com.pa.lcrdemo;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.MediaRecorder;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.provider.MediaStore;
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

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.pa.lcr.lcp.log.LogBus;
import com.pa.lcr.lcp.storage.DemandeSupportStore;
import com.pa.lcr.lcp.storage.LcrDeliveryStatusDb;

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

    /** Enregistrement vidéo limité en durée, en poids et en qualité (le courriel accepte ~2,5 Mo en tout). */
    private static final class VideoLimite extends ActivityResultContracts.CaptureVideo {
        @Override public Intent createIntent(Context c, Uri sortie) {
            Intent i = super.createIntent(c, sortie);
            i.putExtra(MediaStore.EXTRA_DURATION_LIMIT, SimplifiedConfig.SUPPORT_VIDEO_MAX_SECONDS);
            i.putExtra(MediaStore.EXTRA_SIZE_LIMIT, (long) SimplifiedConfig.SUPPORT_VIDEO_MAX_OCTETS);
            i.putExtra(MediaStore.EXTRA_VIDEO_QUALITY, 0);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            return i;
        }
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
        /** Ligne du contexte qui commence par {@code prefixe} (sans le préfixe), sinon vide. */
        String ligne(String prefixe) {
            for (String l : contexte.split("\n")) if (l.startsWith(prefixe)) return l.substring(prefixe.length()).trim();
            return "";
        }

        /** Bloc bien visible en haut : l'erreur en cours, sinon l'état actuel de la tâche du registre. */
        void ajouterSituation(LinearLayout col) {
            String alerte = ligne("Alerte :");
            String etat = ligne("État :");
            String live = ligne("Live :");
            String lieu = wo.isEmpty() ? "" : "WO " + wo;
            TextView t = new TextView(act);
            boolean erreur = !alerte.isEmpty();
            StringBuilder sb = new StringBuilder();
            if (erreur) {
                sb.append("⚠ Erreur en cours\n").append(alerte);
            } else {
                sb.append("État actuel : ").append(etat.isEmpty() ? "—" : etat);
                if (!live.isEmpty()) sb.append("\n").append(live);
            }
            if (!lieu.isEmpty()) sb.append("\n").append(lieu);
            t.setText(sb.toString());
            t.setTextSize(14f);
            t.setTextColor(Color.parseColor(erreur ? "#B3261E" : "#024B3C"));
            t.setBackgroundColor(Color.parseColor(erreur ? "#FDECEA" : "#E6F2EE"));
            t.setPadding((int) dp(12), (int) dp(10), (int) dp(12), (int) dp(10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = (int) dp(4);
            col.addView(t, lp);
        }

        /** Demandes déjà envoyées (n° de ticket ou « en attente »). Ouvrir l'écran = réponses vues → flamme normale. */
        void ajouterDemandesRecentes(LinearLayout col) {
            try {
                DemandeSupportStore st = new DemandeSupportStore(act);
                List<DemandeSupportStore.Row> l = st.recentes(3);
                if (l.isEmpty()) return;
                col.addView(etiquette("VOS DEMANDES RÉCENTES"));
                SimpleDateFormat df = new SimpleDateFormat("d MMM HH:mm", Locale.CANADA_FRENCH);
                for (DemandeSupportStore.Row d : l) {
                    TextView t = new TextView(act);
                    String etat = DemandeSupportStore.REPONDUE.equals(d.statut)
                            ? (d.ticket != null && !d.ticket.isEmpty() ? "Ticket #" + d.ticket.replaceFirst("^#", "") : "Réponse reçue")
                            : "en attente de réponse";
                    t.setText(df.format(new Date(d.envoyeLe)) + " — " + d.reference + "\n" + etat);
                    t.setTextSize(13f);
                    t.setTextColor(Color.parseColor(DemandeSupportStore.REPONDUE.equals(d.statut) ? "#1E78C8" : "#3A3A3A"));
                    t.setPadding(0, 0, 0, (int) dp(6));
                    col.addView(t);
                }
                if (st.aReponseNonVue()) { st.marquerToutVu(); SupportSuivi.avertir(); }
            } catch (Exception e) {
                Log.w(TAG, "demandes récentes ERR: " + e.getMessage());
            }
        }

        MaterialButton btnRec;
        // ---- pièces jointes : 3 photos au plus OU une vidéo
        final ArrayList<File> photos = new ArrayList<>();
        File video = null;
        File mediaEnCours = null;
        TextView txtMedias;
        MaterialButton btnPhoto, btnVideo, btnRetirerMedias;
        ActivityResultLauncher<Uri> lanceurPhoto, lanceurVideo;
        String livraison = "";
        LcrDeliveryStatusDb.DeliveryRow rowLiv = null;
        MaterialButton btnEnvoyer;
        TextView txtTimer;
        EditText edtComment;
        androidx.appcompat.app.AlertDialog dialogue;
        ScrollView sv;

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

            livraison = lireLivraison();
            ajouterSituation(col);
            ajouterDemandesRecentes(col);

            col.addView(etiquette("JOINT AUTOMATIQUEMENT"));
            TextView ctx = new TextView(act);
            ctx.setText(contexte + livraison + "\nJournal des " + SimplifiedConfig.SUPPORT_JOURNAL_MINUTES + " dernières minutes");
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

            col.addView(etiquette("PHOTOS (" + SimplifiedConfig.SUPPORT_PHOTOS_MAX + " MAX) OU UNE VIDÉO (FACULTATIF)"));
            LinearLayout rangeeMedias = new LinearLayout(act);
            rangeeMedias.setOrientation(LinearLayout.HORIZONTAL);
            btnPhoto = new MaterialButton(act, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            btnPhoto.setText("Photo");
            btnPhoto.setAllCaps(false);
            btnPhoto.setOnClickListener(v -> prendrePhoto());
            rangeeMedias.addView(btnPhoto, new LinearLayout.LayoutParams(0, (int) dp(52), 1f));
            btnVideo = new MaterialButton(act, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            btnVideo.setText("Vidéo");
            btnVideo.setAllCaps(false);
            btnVideo.setOnClickListener(v -> filmer());
            LinearLayout.LayoutParams lpV = new LinearLayout.LayoutParams(0, (int) dp(52), 1f);
            lpV.setMarginStart((int) dp(8));
            rangeeMedias.addView(btnVideo, lpV);
            col.addView(rangeeMedias);
            txtMedias = new TextView(act);
            txtMedias.setTextSize(13f);
            txtMedias.setTextColor(Color.parseColor("#3A3A3A"));
            txtMedias.setPadding(0, (int) dp(6), 0, 0);
            col.addView(txtMedias);
            btnRetirerMedias = new MaterialButton(act, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            btnRetirerMedias.setText("Retirer les pièces jointes");
            btnRetirerMedias.setAllCaps(false);
            btnRetirerMedias.setOnClickListener(v -> retirerMedias());
            col.addView(btnRetirerMedias);
            if (act instanceof ComponentActivity) {
                androidx.activity.result.ActivityResultRegistry reg = ((ComponentActivity) act).getActivityResultRegistry();
                String cle = "support_" + System.nanoTime();
                lanceurPhoto = reg.register(cle + "_p", new ActivityResultContracts.TakePicture(), ok -> surPhoto(ok));
                lanceurVideo = reg.register(cle + "_v", new VideoLimite(), ok -> surVideo(ok));
            }
            majMedias();

            btnEnvoyer = new MaterialButton(act);
            btnEnvoyer.setText("Envoyer au support");
            btnEnvoyer.setAllCaps(false);
            btnEnvoyer.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#024B3C")));
            btnEnvoyer.setOnClickListener(v -> envoyer());
            LinearLayout.LayoutParams lpEnv = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, (int) dp(56));
            lpEnv.topMargin = (int) dp(18);
            col.addView(btnEnvoyer, lpEnv);

            sv = new ScrollView(act);
            sv.addView(col);

            dialogue = new MaterialAlertDialogBuilder(act)
                    .setTitle("Demander du support")
                    .setView(sv)
                    .setNegativeButton("Annuler", null)
                    .setOnDismissListener(d -> { arreterEnregistrement(false); if (ecouteurTicket != null) SupportSuivi.retirer(ecouteurTicket);
                        try { if (lanceurPhoto != null) lanceurPhoto.unregister(); if (lanceurVideo != null) lanceurVideo.unregister(); } catch (Exception ignored) {} })
                    .show();
        }

        // ---------------------------------------------------------------- photos / vidéo

        void prendrePhoto() {
            if (lanceurPhoto == null || video != null || photos.size() >= SimplifiedConfig.SUPPORT_PHOTOS_MAX) return;
            try {
                mediaEnCours = new File(dossierSupport(), "photo_" + nettoyer(serie) + "_" + System.currentTimeMillis() + ".jpg");
                lanceurPhoto.launch(FileProvider.getUriForFile(act, act.getPackageName() + ".fileprovider", mediaEnCours));
            } catch (Exception e) {
                Log.w(TAG, "photo ERR: " + e.getMessage());
                Toast.makeText(act, "Impossible d’ouvrir l’appareil photo", Toast.LENGTH_SHORT).show();
            }
        }

        void filmer() {
            if (lanceurVideo == null || video != null || !photos.isEmpty()) return;
            try {
                mediaEnCours = new File(dossierSupport(), "video_" + nettoyer(serie) + "_" + System.currentTimeMillis() + ".mp4");
                lanceurVideo.launch(FileProvider.getUriForFile(act, act.getPackageName() + ".fileprovider", mediaEnCours));
            } catch (Exception e) {
                Log.w(TAG, "vidéo ERR: " + e.getMessage());
                Toast.makeText(act, "Impossible d’ouvrir la caméra", Toast.LENGTH_SHORT).show();
            }
        }

        void surPhoto(Boolean ok) {
            final File f = mediaEnCours;
            mediaEnCours = null;
            if (f == null) return;
            if (ok == null || !ok || !f.exists() || f.length() == 0) { f.delete(); return; }
            new Thread(() -> {
                try {
                    reduirePhoto(f);
                    act.runOnUiThread(() -> { photos.add(f); majMedias(); });
                } catch (Exception e) {
                    Log.w(TAG, "réduction photo ERR: " + e.getMessage());
                    act.runOnUiThread(() -> Toast.makeText(act, "Photo illisible", Toast.LENGTH_SHORT).show());
                }
            }, "support-photo").start();
        }

        void surVideo(Boolean ok) {
            final File f = mediaEnCours;
            mediaEnCours = null;
            if (f == null) return;
            if (ok == null || !ok || !f.exists() || f.length() == 0) { f.delete(); return; }
            if (f.length() > SimplifiedConfig.SUPPORT_VIDEO_MAX_OCTETS) {
                f.delete();
                Toast.makeText(act, "Vidéo trop lourde : refaites-la plus courte (" + SimplifiedConfig.SUPPORT_VIDEO_MAX_SECONDS + " s)", Toast.LENGTH_LONG).show();
                return;
            }
            video = f;
            majMedias();
        }

        void retirerMedias() {
            for (File f : photos) f.delete();
            photos.clear();
            if (video != null) { video.delete(); video = null; }
            majMedias();
        }

        void majMedias() {
            boolean aucun = photos.isEmpty() && video == null;
            txtMedias.setText(video != null ? "1 vidéo jointe"
                    : photos.isEmpty() ? "Aucune pièce jointe"
                    : photos.size() + " photo(s) jointe(s) sur " + SimplifiedConfig.SUPPORT_PHOTOS_MAX);
            btnPhoto.setEnabled(video == null && photos.size() < SimplifiedConfig.SUPPORT_PHOTOS_MAX);
            btnVideo.setEnabled(video == null && photos.isEmpty());
            btnRetirerMedias.setVisibility(aucun ? View.GONE : View.VISIBLE);
        }

        /** Réduit la photo (plus grand côté SUPPORT_PHOTO_PX, JPEG 70) et applique la rotation EXIF. */
        void reduirePhoto(File f) throws Exception {
            int deg = 0;
            try {
                int o = new android.media.ExifInterface(f.getPath()).getAttributeInt(
                        android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL);
                deg = o == android.media.ExifInterface.ORIENTATION_ROTATE_90 ? 90
                    : o == android.media.ExifInterface.ORIENTATION_ROTATE_180 ? 180
                    : o == android.media.ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
            } catch (Exception ignored) {}
            BitmapFactory.Options opt = new BitmapFactory.Options();
            opt.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), opt);
            int max = SimplifiedConfig.SUPPORT_PHOTO_PX, ech = 1;
            while (Math.max(opt.outWidth, opt.outHeight) / ech > max * 2) ech *= 2;
            opt.inJustDecodeBounds = false;
            opt.inSampleSize = ech;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), opt);
            if (b == null) throw new IllegalStateException("décodage impossible");
            float k = Math.min(1f, max / (float) Math.max(b.getWidth(), b.getHeight()));
            Matrix m = new Matrix();
            m.postRotate(deg);
            m.postScale(k, k);
            Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            try (FileOutputStream os = new FileOutputStream(f)) {
                r.compress(Bitmap.CompressFormat.JPEG, 70, os);
            }
            if (r != b) r.recycle();
            b.recycle();
        }

        // ---------------------------------------------------------------- lien avec la livraison

        /** Tournée, stop, chauffeur… de la livraison en cours (ligne la plus récente du WO), sinon vide. */
        String lireLivraison() {
            try {
                if (wo == null || wo.isEmpty()) return "";
                LcrDeliveryStatusDb db = new LcrDeliveryStatusDb(act);
                LcrDeliveryStatusDb.DeliveryRow best = null;
                for (LcrDeliveryStatusDb.DeliveryRow r : db.getAllForWo(wo)) {
                    if (best == null || r.tsUpdatedMs > best.tsUpdatedMs) best = r;
                }
                if (best == null) return "";
                rowLiv = best;
                StringBuilder sb = new StringBuilder();
                if (!vide(best.tourneeId)) sb.append("\nTournée : ").append(best.tourneeId);
                sb.append("\nStop : séquence ").append(best.stopSequence);
                if (!vide(best.stopType)) sb.append(" (").append(best.stopType).append(')');
                if (!vide(best.stopid)) sb.append("\nStop (id) : ").append(best.stopid);
                if (!vide(best.livreurId)) sb.append("\nChauffeur : ").append(best.livreurId);
                if (!vide(best.camionId)) sb.append("\nCamion : ").append(best.camionId);
                if (!vide(best.client)) sb.append("\nClient : ").append(best.client);
                return sb.toString();
            } catch (Throwable t) {
                Log.w(TAG, "livraison ERR: " + t.getMessage());
                return "";
            }
        }

        boolean vide(String s) { return s == null || s.trim().isEmpty(); }

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
            final ArrayList<File> medias = new ArrayList<>(photos);
            if (video != null) medias.add(video);
            final String contexteTotal = contexte + livraison;
            // Le journal peut être gros : écrit hors du fil UI.
            new Thread(() -> {
                try {
                    File dir = dossierSupport();
                    String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
                    File journal = new File(dir, "journal_" + nettoyer(serie) + "_" + ts + ".txt");
                    try (Writer w = new OutputStreamWriter(new FileOutputStream(journal), StandardCharsets.UTF_8)) {
                        w.write("Demande de support — Filgo-Registre\n");
                        w.write(contexteTotal + "\n");
                        if (!commentaire.isEmpty()) w.write("\nCommentaire du chauffeur :\n" + commentaire + "\n");
                        w.write("\n===== Journal (" + SimplifiedConfig.SUPPORT_JOURNAL_MINUTES + " dernières minutes) =====\n");
                        w.write(construireJournal());
                    }
                    final File audio = avecAudio ? fichierAudio : null;

                    final StringBuilder corps = new StringBuilder(contexteTotal);
                    if (!commentaire.isEmpty()) corps.append("\n\nCommentaire :\n").append(commentaire);
                    if (avecAudio) corps.append("\n\n(Message vocal joint)");
                    if (video != null) corps.append("\n\n(Vidéo jointe)");
                    else if (!photos.isEmpty()) corps.append("\n\n(").append(photos.size()).append(" photo(s) jointe(s))");
                    corps.append("\n\nPrise en main à distance (SOTI MobiControl) — ID de la tablette : ")
                         .append(idSoti());
                    final String lienSoti = lienSoti();
                    if (!lienSoti.isEmpty()) corps.append("\nLien SOTI : ").append(lienSoti);
                    final long envoyeLe = System.currentTimeMillis();
                    final String reference = "SUP-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date(envoyeLe));
                    final String sujet = "[" + reference + "] Support Filgo-Registre — " + SimplifiedUiState.nomTablette(act)
                            + " — WO " + (wo.isEmpty() ? "—" : wo)
                            + " — #" + serie + " Node " + node;

                    // Ligne locale (envoyée ensuite à Dataverse) : tout ce qu'on sait de la demande.
                    final DemandeSupportStore.Row base = new DemandeSupportStore.Row();
                    base.reference = reference; base.sujet = sujet; base.contexte = contexteTotal;
                    base.commentaire = commentaire; base.avecAudio = avecAudio; base.envoyeLe = envoyeLe;
                    base.nbPhotos = photos.size(); base.avecVideo = video != null;
                    base.tablette = idSoti(); base.lienSoti = lienSoti;
                    base.woNum = wo; base.serie = serie;
                    try { base.node = Integer.parseInt(node.trim()); } catch (Exception ignored) {}
                    base.etatRegistre = ligne("État :"); base.erreur = ligne("Alerte :");
                    if (rowLiv != null) {
                        base.tourneeId = rowLiv.tourneeId; base.stopSequence = rowLiv.stopSequence;
                        base.stopType = rowLiv.stopType; base.stopId = rowLiv.stopid;
                        base.chauffeur = rowLiv.livreurId; base.client = rowLiv.client;
                        base.citerneNom = rowLiv.camionId;
                    }
                    try {
                        com.pa.lcr.lcp.storage.RegistreStore.Row reg = new com.pa.lcr.lcp.storage.RegistreStore(act).getBySerial(serie);
                        if (reg != null) {
                            if (reg.citerneGuid != null) base.citerneId = reg.citerneGuid;
                            if (reg.citerneNom != null && !reg.citerneNom.trim().isEmpty()) base.citerneNom = reg.citerneNom;
                        }
                    } catch (Exception ignored) {}

                    // 1) Envoi silencieux par Microsoft Graph (courriel + Teams)
                    SupportGraphSender.envoyer(act, sujet, corps.toString(), lienSoti, journal, audio, medias,
                            new SupportGraphSender.Resultat() {
                        @Override public void courrielEnvoye(boolean teamsOk) {
                            base.teamsOk = teamsOk; base.canal = "GRAPH";
                            enregistrerDemande(base);
                            act.runOnUiThread(() -> afficherConfirmation(reference, teamsOk));
                        }
                        @Override public void echec(String raison) {
                            Log.w(TAG, "Graph indisponible → repli courriel : " + raison);
                            base.teamsOk = false; base.canal = "OUTLOOK";
                            enregistrerDemande(base);   // la demande est gardée même si elle part par Outlook
                            // 2) Repli : courriel pré-rempli, le chauffeur appuie sur Envoyer
                            try {
                                final ArrayList<Uri> uris = new ArrayList<>();
                                String auth = act.getPackageName() + ".fileprovider";
                                uris.add(FileProvider.getUriForFile(act, auth, journal));
                                if (audio != null) uris.add(FileProvider.getUriForFile(act, auth, audio));
                                for (File mf : medias) if (mf.exists()) uris.add(FileProvider.getUriForFile(act, auth, mf));
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

        String lienSoti() {
            String m = SimplifiedConfig.SOTI_LIEN_MODELE;
            if (m.isEmpty()) return "";
            try { return m.replace("{ID}", java.net.URLEncoder.encode(idSoti(), "UTF-8").replace("+", "%20")); }
            catch (Exception e) { return ""; }
        }

        // ---------------------------------------------------------------- confirmation + n° de ticket

        TextView txtTicket;

        void afficherConfirmation(final String reference, boolean teamsOk) {
            LinearLayout col = new LinearLayout(act);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            int p = (int) dp(20);
            col.setPadding(p, (int) dp(16), p, (int) dp(16));

            TextView coche = new TextView(act);
            coche.setText("✓");
            coche.setTextSize(56f);
            coche.setGravity(Gravity.CENTER);
            coche.setTextColor(Color.parseColor("#024B3C"));
            col.addView(coche);

            TextView titre = new TextView(act);
            titre.setText("Demande envoyée");
            titre.setTextSize(22f);
            titre.setTypeface(null, android.graphics.Typeface.BOLD);
            titre.setGravity(Gravity.CENTER);
            titre.setTextColor(Color.parseColor("#111111"));
            col.addView(titre);

            TextView detail = new TextView(act);
            detail.setText("Courriel envoyé à " + SimplifiedConfig.SUPPORT_EMAIL
                    + (teamsOk ? "\nMessage publié dans Support Camion (Teams)" : ""));
            detail.setTextSize(13f);
            detail.setGravity(Gravity.CENTER);
            detail.setTextColor(Color.parseColor("#3A3A3A"));
            detail.setPadding(0, (int) dp(8), 0, (int) dp(16));
            col.addView(detail);

            TextView ref = new TextView(act);
            ref.setText("Référence : " + reference);
            ref.setTextSize(15f);
            ref.setGravity(Gravity.CENTER);
            ref.setTextColor(Color.parseColor("#111111"));
            col.addView(ref);

            txtTicket = new TextView(act);
            txtTicket.setText("N° de ticket : en attente de la réponse du support…");
            txtTicket.setTextSize(17f);
            txtTicket.setTypeface(null, android.graphics.Typeface.BOLD);
            txtTicket.setGravity(Gravity.CENTER);
            txtTicket.setTextColor(Color.parseColor("#024B3C"));
            txtTicket.setPadding(0, (int) dp(10), 0, 0);
            col.addView(txtTicket);

            sv.removeAllViews();
            sv.addView(col);
            try { dialogue.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).setText("Fermer"); }
            catch (Exception ignored) {}

            ecouteurTicket = () -> act.runOnUiThread(() -> majTicketAffiche(reference));
            SupportSuivi.ecouter(ecouteurTicket);
        }

        Runnable ecouteurTicket;

        /** Affiche le n° de ticket une fois la réponse du support reçue (surveillée par SupportSuivi). */
        void majTicketAffiche(String reference) {
            if (txtTicket == null) return;
            DemandeSupportStore st = new DemandeSupportStore(act);
            for (DemandeSupportStore.Row d : st.recentes(10)) {
                if (!reference.equals(d.reference)) continue;
                if (DemandeSupportStore.REPONDUE.equals(d.statut)) {
                    txtTicket.setText(d.ticket != null && !d.ticket.isEmpty()
                            ? "Ticket #" + d.ticket.replaceFirst("^#", "")
                            : "Réponse du support reçue (consultez votre courriel)");
                    // Le chauffeur la voit à l'écran : la flamme n'a pas à rester bleue.
                    if (st.aReponseNonVue()) { st.marquerToutVu(); SupportSuivi.avertir(); }
                }
                return;
            }
        }

        /** Enregistre la demande en base locale, lance le suivi de la réponse et l'envoi à Dataverse. */
        void enregistrerDemande(DemandeSupportStore.Row d) {
            try {
                new DemandeSupportStore(act).enregistrer(d);
                SupportSuivi.demarrer(act);
                com.pa.lcrdemo.dataverse.SupportDataverseSync.pousser(act, true);
            } catch (Exception e) {
                Log.w(TAG, "enregistrement BD ERR: " + e.getMessage());
            }
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
