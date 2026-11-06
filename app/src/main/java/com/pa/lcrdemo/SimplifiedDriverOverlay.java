package com.pa.lcrdemo;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import com.pa.lcr.lcp.diagnostic.DiagnosticMatch;
import com.pa.lcr.lcp.diagnostic.SupportTriageEngine;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ✅ AJOUTÉ (6 nov 2026, demande Paul) — ÉCRAN SIMPLIFIÉ CHAUFFEUR.
 *
 * Surcouche posée PAR-DESSUS l'onglet existant (RegisterTabFragment), qui reste intact dessous :
 *  - AUCUNE donnée n'est dupliquée ni recalculée : tout est LU sur les vues existantes de l'onglet
 *    (txtLive, txtQtyNet/Gross, txtTicketNo/SaleNo avec leur soulignement, panelWoCumul…), toutes
 *    les 250 ms ;
 *  - AUCUN bouton n'a de logique propre : Terminer / Annuler / Lancer la livraison / Retour au
 *    Bon de travail déclenchent (performClick) les boutons existants — donc le contexte (bouton vert
 *    ou bleu, règles d'activation, armement en cours…) reste exactement celui de l'onglet actuel ;
 *  - le mode ADMIN (mot de passe fixe) masque simplement cette surcouche : l'interface actuelle
 *    complète réapparaît telle quelle ; un bouton flottant ramène à l'écran simplifié.
 *
 * Deux dispositions (layout/ portrait, layout-land/ paysage, mêmes ids). MainActivity gère les
 * changements d'orientation sans recréer la vue : la disposition est donc reconstruite ici quand
 * l'orientation change.
 */
final class SimplifiedDriverOverlay {

    private static final String TAG = "SimplifiedOverlay";
    private static final long REFRESH_MS = 250L;

    /** true = mode Admin (interface actuelle complète). Commun à tous les onglets, jamais persisté. */
    static volatile boolean adminUnlocked = false;

    private enum Etat { CONNEXION, ATTENTE, PREP, PRET, DEBIT, CONFIRM, PAUSE, ENDING, TICKET, FIN }

    private final RegisterTabFragment host;
    private final View original;
    private final FrameLayout holder;
    private final MaterialButton btnRetourSimple;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean running = false;
    private int builtOrientation = -1;

    // Vues existantes de l'onglet (lecture seule / clics délégués)
    private final TextView txtLive, txtQtyNet, txtQtyGross, txtSerialId, txtLcrNode;
    private final TextView txtTicketNo, txtSaleNo, txtDeliveryUid, txtWoCumulNet, txtWoCumulCount;
    private final android.widget.AutoCompleteTextView spnProduct;
    private final EditText edtPreset;
    private final View panelWoCumul;
    private final android.widget.Button btnFinish, btnAnnuler, btnRetourWO;

    // Vues de la surcouche
    private View root;
    private TextView sTxtRegistre, sTxtRegistreEtat, sTxtTitre, sTxtSous, sTxtNet, sTxtGross, sTxtDejaLivre;
    private TextView sTxtWo, sTxtPreset, sTxtProdFs, sTxtProdReg, sTxtTicket, sTxtVente, sTxtWoCumul, sTxtInit;
    private View sDot, sBlocProdFs, sPanelWo;
    private MaterialCardView sBanner;
    private ProgressBar sProgConn;
    private MaterialButton sBtnTerminer, sBtnAnnuler, sBtnLancer, sBtnRetour, sBtnAdmin;
    private View sBtnSupport;   // mini flamme (remplace l'ancienne icône trombone)

    private Etat dernierEtat = null;
    private boolean dernierPulseTerminer = false;
    private Animator animDot = null;
    private Animator animTerminer = null;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            try { refresh(); } catch (Exception e) { Log.w(TAG, "refresh ERR (non-bloquant): " + e.getMessage()); }
            ui.postDelayed(this, REFRESH_MS);
        }
    };

    /** Pose la surcouche dans le wrapper de l'onglet (au-dessus du contenu, sous le guide d'init). */
    static SimplifiedDriverOverlay attach(RegisterTabFragment host, FrameLayout wrapper, View original) {
        return new SimplifiedDriverOverlay(host, wrapper, original);
    }

    private SimplifiedDriverOverlay(RegisterTabFragment host, FrameLayout wrapper, View original) {
        this.host = host;
        this.original = original;
        Context ctx = wrapper.getContext();

        txtLive = original.findViewById(R.id.txtLive);
        txtQtyNet = original.findViewById(R.id.txtQtyNet);
        txtQtyGross = original.findViewById(R.id.txtQtyGross);
        txtSerialId = original.findViewById(R.id.txtSerialId);
        txtLcrNode = original.findViewById(R.id.txtLcrNode);
        txtTicketNo = original.findViewById(R.id.txtTicketNo);
        txtSaleNo = original.findViewById(R.id.txtSaleNo);
        txtDeliveryUid = original.findViewById(R.id.txtDeliveryUid);
        txtWoCumulNet = original.findViewById(R.id.txtWoCumulNet);
        txtWoCumulCount = original.findViewById(R.id.txtWoCumulCount);
        spnProduct = original.findViewById(R.id.spnProduct);
        edtPreset = original.findViewById(R.id.edtPreset);
        panelWoCumul = original.findViewById(R.id.panelWoCumul);
        btnFinish = original.findViewById(R.id.btnFinish);
        btnAnnuler = original.findViewById(R.id.btnAnnuler);
        btnRetourWO = original.findViewById(R.id.btnRetourWO);

        holder = new FrameLayout(ctx);
        wrapper.addView(holder, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Bouton flottant visible seulement en mode Admin : retour à l'écran simplifié
        btnRetourSimple = new MaterialButton(ctx);
        btnRetourSimple.setText("Retour à l’écran simplifié");
        btnRetourSimple.setAllCaps(false);
        btnRetourSimple.setTextSize(13f);
        btnRetourSimple.setTextColor(Color.WHITE);
        btnRetourSimple.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#024B3C")));
        btnRetourSimple.setElevation(16f);
        btnRetourSimple.setVisibility(View.GONE);
        btnRetourSimple.setOnClickListener(v -> { adminUnlocked = false; });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        int m = (int) (8 * ctx.getResources().getDisplayMetrics().density);
        lp.setMargins(m, m, m, m);
        wrapper.addView(btnRetourSimple, lp);
        construireBulle(ctx, wrapper);

        holder.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { start(); }
            @Override public void onViewDetachedFromWindow(View v) { stop(); }
        });
        build(ctx.getResources().getConfiguration().orientation);
    }

    // ------------------------------------------------------------------ cycle de vie

    private void start() {
        if (running) return;
        running = true;
        ui.post(tick);
    }

    private void stop() {
        running = false;
        ui.removeCallbacks(tick);
        arreterAnimations();
    }

    // ------------------------------------------------------------------ construction

    private void build(int orientation) {
        Context ctx = holder.getContext();
        arreterAnimations();
        holder.removeAllViews();
        root = LayoutInflater.from(ctx).inflate(R.layout.overlay_simplified, holder, false);
        holder.addView(root);
        builtOrientation = orientation;
        dernierEtat = null;
        dernierPulseTerminer = false;

        sTxtRegistre = root.findViewById(R.id.sTxtRegistre);
        sTxtRegistreEtat = root.findViewById(R.id.sTxtRegistreEtat);
        sTxtTitre = root.findViewById(R.id.sTxtTitre);
        sTxtSous = root.findViewById(R.id.sTxtSous);
        sTxtNet = root.findViewById(R.id.sTxtNet);
        sTxtGross = root.findViewById(R.id.sTxtGross);
        sTxtDejaLivre = root.findViewById(R.id.sTxtDejaLivre);
        sTxtWo = root.findViewById(R.id.sTxtWo);
        sTxtPreset = root.findViewById(R.id.sTxtPreset);
        sTxtProdFs = root.findViewById(R.id.sTxtProdFs);
        sTxtProdReg = root.findViewById(R.id.sTxtProdReg);
        sTxtTicket = root.findViewById(R.id.sTxtTicket);
        sTxtVente = root.findViewById(R.id.sTxtVente);
        sTxtWoCumul = root.findViewById(R.id.sTxtWoCumul);
        sTxtInit = root.findViewById(R.id.sTxtInit);
        sDot = root.findViewById(R.id.sDot);
        sBlocProdFs = root.findViewById(R.id.sBlocProdFs);
        sPanelWo = root.findViewById(R.id.sPanelWo);
        sBanner = root.findViewById(R.id.sBanner);
        sProgConn = root.findViewById(R.id.sProgConn);
        sBtnTerminer = root.findViewById(R.id.sBtnTerminer);
        sBtnAnnuler = root.findViewById(R.id.sBtnAnnuler);
        sBtnLancer = root.findViewById(R.id.sBtnLancer);
        sBtnRetour = root.findViewById(R.id.sBtnRetour);
        // Bouton de support : la trombone est remplacée par une mini flamme Filgo (même emplacement, même clic)
        View ancienSupport = root.findViewById(R.id.sBtnSupport);
        FlammeView mini = new FlammeView(ctx);
        mini.setMini(true);
        mini.setContentDescription("Demander du support");
        if (ancienSupport != null && ancienSupport.getParent() instanceof android.view.ViewGroup) {
            android.view.ViewGroup parent = (android.view.ViewGroup) ancienSupport.getParent();
            int idx = parent.indexOfChild(ancienSupport);
            android.view.ViewGroup.LayoutParams nlp = ancienSupport.getLayoutParams();
            float dpi = ctx.getResources().getDisplayMetrics().density;
            nlp.width = (int) (48 * dpi);
            nlp.height = (int) (48 * dpi);
            parent.removeView(ancienSupport);
            parent.addView(mini, idx, nlp);
        }
        sBtnSupport = mini;
        sBtnAdmin = root.findViewById(R.id.sBtnAdmin);

        // Les boutons d'action délèguent aux boutons existants de l'onglet.
        sBtnTerminer.setOnClickListener(v -> cliquer(btnFinish));
        sBtnAnnuler.setOnClickListener(v -> cliquer(btnAnnuler));
        sBtnLancer.setOnClickListener(v -> cliquer(btnRetourWO));
        sBtnRetour.setOnClickListener(v -> cliquer(btnRetourWO));
        sPanelWo.setOnClickListener(v -> cliquer(panelWoCumul));
        sBtnSupport.setOnClickListener(v -> { rebondTrombone(); ouvrirSupport(); });
        sBtnAdmin.setOnClickListener(v -> demanderMotDePasse());
        sTxtRegistre.setOnClickListener(v -> choisirRegistre());
    }


    // ------------------------------------------------------------------ assistant (trombone + bulle)
    // Quand le registre est déconnecté OU que le service d'impression ne répond pas, la trombone
    // « sort » du logo Filgo avec une bulle : titre du problème + action recommandée (diagnostic des
    // règles de l'onglet Support : SupportTriageEngine) + « Contacter le support » / « Plus tard ».
    // Jamais pendant une livraison (débit / pause / fin) ; « Plus tard » = silence 10 min pour ce problème.

    private static final long SNOOZE_MS = 10 * 60 * 1000L;
    private LinearLayout bulle;
    private TextView bTitre, bAction;
    private FlammeView bFlamme;
    private MaterialButton btnPrint;
    private boolean bulleVisible = false;
    private String cleAlerte = "";
    private String snoozeCle = "";
    private long snoozeJusquaMs = 0L;
    private String alerteContexte = "";
    private volatile boolean triageEnCours = false;
    private volatile String triageCle = null;
    private volatile String triageAction = null;
    private volatile String triageNiveau = null;
    private volatile String triageDiag = null;

    private void construireBulle(Context ctx, FrameLayout wrapper) {
        final float dp = ctx.getResources().getDisplayMetrics().density;
        bulle = new LinearLayout(ctx);
        bulle.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (14 * dp);
        bulle.setPadding(pad, pad, pad, pad / 2);
        android.graphics.drawable.GradientDrawable fond = new android.graphics.drawable.GradientDrawable();
        fond.setColor(Color.WHITE);
        fond.setCornerRadius(16 * dp);
        fond.setStroke((int) (2 * dp), Color.parseColor("#185FA5"));
        bulle.setBackground(fond);
        bulle.setElevation(24f);

        LinearLayout entete = new LinearLayout(ctx);
        entete.setOrientation(LinearLayout.HORIZONTAL);
        entete.setGravity(Gravity.CENTER_VERTICAL);
        // Mascotte Filgo : une flamme (dessinée au Canvas, aucune image)
        bFlamme = new FlammeView(ctx);
        entete.addView(bFlamme, new LinearLayout.LayoutParams((int) (84 * dp), (int) (84 * dp)));
        bTitre = new TextView(ctx);
        bTitre.setTextColor(Color.parseColor("#0B2A4A"));
        bTitre.setTextSize(17f);
        bTitre.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lpt = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        lpt.setMargins((int) (10 * dp), 0, 0, 0);
        entete.addView(bTitre, lpt);
        bulle.addView(entete);

        bAction = new TextView(ctx);
        bAction.setTextColor(Color.parseColor("#33475B"));
        bAction.setTextSize(15f);
        bAction.setPadding(0, (int) (8 * dp), 0, (int) (8 * dp));
        bulle.addView(bAction);

        LinearLayout boutons = new LinearLayout(ctx);
        boutons.setOrientation(LinearLayout.HORIZONTAL);
        boutons.setGravity(Gravity.END);
        MaterialButton plusTard = new MaterialButton(ctx);
        plusTard.setText("Plus tard");
        plusTard.setAllCaps(false);
        plusTard.setTextColor(Color.parseColor("#0B2A4A"));
        plusTard.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#E6EEF5")));
        plusTard.setOnClickListener(v -> {
            snoozeCle = cleAlerte;
            snoozeJusquaMs = android.os.SystemClock.elapsedRealtime() + SNOOZE_MS;
            cacherBulle();
        });
        MaterialButton contacter = new MaterialButton(ctx);
        contacter.setText("Contacter le support");
        contacter.setAllCaps(false);
        contacter.setTextColor(Color.WHITE);
        contacter.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#024B3C")));
        contacter.setOnClickListener(v -> {
            snoozeCle = cleAlerte;
            snoozeJusquaMs = android.os.SystemClock.elapsedRealtime() + SNOOZE_MS;
            cacherBulle();
            ouvrirSupport();
        });
        LinearLayout.LayoutParams lpb = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpb.setMargins((int) (8 * dp), 0, 0, 0);
        btnPrint = new MaterialButton(ctx);
        btnPrint.setText("Démarrer l’impression");
        btnPrint.setAllCaps(false);
        btnPrint.setTextColor(Color.WHITE);
        btnPrint.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#185FA5")));
        btnPrint.setVisibility(View.GONE);
        btnPrint.setOnClickListener(v -> demarrerServiceImpression());
        boutons.addView(btnPrint);
        boutons.addView(plusTard);
        boutons.addView(contacter, lpb);
        bulle.addView(boutons);

        int largeur = (int) Math.min(360 * dp, ctx.getResources().getDisplayMetrics().widthPixels * 0.9f);
        FrameLayout.LayoutParams lpBulle = new FrameLayout.LayoutParams(largeur,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        lpBulle.gravity = Gravity.TOP | Gravity.START;
        lpBulle.setMargins((int) (12 * dp), (int) (84 * dp), 0, 0);
        bulle.setVisibility(View.GONE);
        wrapper.addView(bulle, lpBulle);
    }

    private void gererAssistant(boolean coupe, boolean echecDeepLink, Etat etat) {
        if (bulle == null) return;
        boolean enLivraison = etat == Etat.DEBIT || etat == Etat.CONFIRM
                || etat == Etat.PAUSE || etat == Etat.ENDING;
        String cle = coupe ? "COMM" : (echecDeepLink ? "DEEPLINK" : (!printActif ? "PRINT" : ""));
        if (sBtnSupport instanceof FlammeView) ((FlammeView) sBtnSupport).setInquiet(!cle.isEmpty());
        if (cle.isEmpty()) {          // tout va bien : on range et on remet à zéro
            cacherBulle();
            cleAlerte = ""; snoozeCle = ""; alerteContexte = ""; dernierSonMs = 0L;
            return;
        }
        if (enLivraison) { cacherBulle(); return; }
        if (cle.equals(snoozeCle) && android.os.SystemClock.elapsedRealtime() < snoozeJusquaMs) return;
        if (!cle.equals(cleAlerte)) {
            cleAlerte = cle;
            dernierSonMs = 0L;
            triageCle = null; triageAction = null; triageNiveau = null; triageDiag = null;
        }
        String titre, action;
        if ("COMM".equals(cle)) {
            lancerTriage(cle);
            titre = "Communication perdue avec le registre";
            String a = cle.equals(triageCle) ? triageAction : null;
            action = (a != null && !a.trim().isEmpty()) ? a.trim()
                    : "Vérifiez le câble ou la connexion du registre, puis touchez Status. Sinon, contactez le support.";
        } else if ("DEEPLINK".equals(cle)) {
            titre = "Registre introuvable";
            action = "Le registre n’a pas répondu après plusieurs essais. Branchez le câble USB-C ou activez le "
                    + "Bluetooth du registre, puis relancez depuis Field Service. Sinon, contactez le support.";
        } else {
            titre = "Impression indisponible";
            action = "Le service d’impression ne répond pas. Redémarrez la tablette ou contactez le support.";
        }
        StringBuilder ctxA = new StringBuilder(titre).append(" — ").append(action);
        String niv = cle.equals(triageCle) ? triageNiveau : null;
        if (niv != null && !niv.isEmpty()) ctxA.append(" [").append(niv).append(']');
        String diag = cle.equals(triageCle) ? triageDiag : null;
        if (diag != null && !diag.trim().isEmpty()) ctxA.append(" | Diagnostic : ").append(diag.trim());
        alerteContexte = ctxA.toString();
        bTitre.setText(titre);
        bAction.setText(action);
        if (btnPrint != null) btnPrint.setVisibility("PRINT".equals(cle) ? View.VISIBLE : View.GONE);
        boolean nouvelle = !bulleVisible;
        afficherBulle();
        long now = android.os.SystemClock.elapsedRealtime();
        if (dernierSonMs == 0L || now - dernierSonMs >= SimplifiedConfig.SON_ALERTE_RAPPEL_MS) {
            dernierSonMs = now;
            // Laisse la bulle finir son entrée (500 ms) avant de « cogner »
            final long delai = nouvelle && ValueAnimator.areAnimatorsEnabled() ? 550L : 0L;
            ui.postDelayed(this::cognerEcran, delai);
        }
    }

    // ------------------------------------------------------------------ la flamme explose
    /** La flamme cogne deux fois dans la vitre (toc-toc), puis explose en étincelles et se reforme inquiète. */
    private void cognerEcran() {
        if (bFlamme == null || !bulleVisible) return;
        bFlamme.setInquiet(false);
        final boolean anime = ValueAnimator.areAnimatorsEnabled();
        bFlamme.cogner(() -> { if (bFlamme != null && bulleVisible) bFlamme.exploser(); });
        // Le son (deux petits coups espacés de 200 ms) démarre au premier coup de la flamme
        if (SimplifiedConfig.SON_ALERTE_ACTIF) ui.postDelayed(this::jouerToc, anime ? FlammeView.COUP1_MS : 0L);
    }

    // ------------------------------------------------------------------ son : « toc-toc » de clé sur une vitre
    private long dernierSonMs = 0L;
    private short[] pcmToc = null;

    /** Fabrique (une fois) deux petits coups secs : bruit très bref + résonances aiguës qui s'éteignent vite. */
    private short[] fabriquerToc() {
        final int sr = 22050;
        final int espace = (int) (0.20f * sr);              // 200 ms entre les deux coups
        final int duree = (int) (0.14f * sr);               // 140 ms par coup
        final int total = espace + duree;
        float[] buf = new float[total];
        java.util.Random rnd = new java.util.Random(7);
        double[] freqs = { 2150.0, 3300.0, 5200.0 };
        double[] gains = { 0.55, 0.35, 0.20 };
        double[] amorts = { 38.0, 55.0, 80.0 };             // plus c'est grand, plus ça s'éteint vite
        for (int coup = 0; coup < 2; coup++) {
            int debut = coup * espace;
            float force = coup == 0 ? 1.0f : 0.8f;          // le deuxième coup un peu plus léger
            for (int i = 0; i < duree; i++) {
                double t = i / (double) sr;
                double v = 0;
                for (int k = 0; k < freqs.length; k++) {
                    v += gains[k] * Math.exp(-amorts[k] * t) * Math.sin(2 * Math.PI * freqs[k] * t);
                }
                if (i < (int) (0.003 * sr)) v += (rnd.nextDouble() * 2 - 1) * 0.9 * (1.0 - i / (0.003 * sr)); // « clic » initial
                int idx = debut + i;
                if (idx < total) buf[idx] += (float) (v * force);
            }
        }
        float max = 0f;
        for (float f : buf) max = Math.max(max, Math.abs(f));
        short[] out = new short[total];
        float k = max > 0 ? (0.85f * SimplifiedConfig.SON_ALERTE_VOLUME * 32767f) / max : 0f;
        for (int i = 0; i < total; i++) out[i] = (short) Math.max(-32768, Math.min(32767, buf[i] * k));
        return out;
    }

    private void jouerToc() {
        try {
            Context ctx = holder.getContext();
            android.media.AudioManager am = (android.media.AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                if (am.getRingerMode() != android.media.AudioManager.RINGER_MODE_NORMAL) return;   // silencieux / vibreur
                if (am.getStreamVolume(android.media.AudioManager.STREAM_NOTIFICATION) == 0) return;
            }
            if (pcmToc == null) pcmToc = fabriquerToc();
            final short[] pcm = pcmToc;
            final android.media.AudioTrack track = new android.media.AudioTrack.Builder()
                    .setAudioAttributes(new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build())
                    .setAudioFormat(new android.media.AudioFormat.Builder()
                            .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(22050)
                            .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setBufferSizeInBytes(pcm.length * 2)
                    .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                    .build();
            track.write(pcm, 0, pcm.length);
            track.play();
            ui.postDelayed(() -> { try { track.release(); } catch (Throwable ignored) { } }, 1500);
        } catch (Throwable t) {
            Log.w(TAG, "jouerToc ERR (non-bloquant): " + t.getMessage());
        }
    }

    /** Triage de l'onglet Support (lecture seule du journal) — une fois par épisode d'alerte, en arrière-plan. */
    private void lancerTriage(final String cle) {
        if (triageEnCours || cle.equals(triageCle)) return;
        triageEnCours = true;
        final Context app = holder.getContext().getApplicationContext();
        String t = apresDeuxPoints(txtTicketNo);
        final String ticket = "—".equals(t) ? "" : t;
        String n = apresDeuxPoints(txtLcrNode);
        final String node = "—".equals(n) ? "" : n;
        new Thread(() -> {
            String action = null, niveau = null, diag = null;
            try {
                SupportTriageEngine.TriageResult r = SupportTriageEngine.computeTriage(app, ticket, node);
                DiagnosticMatch meilleur = null;
                for (DiagnosticMatch m : r.matches) {
                    if (meilleur == null || (m.supportLevel != null && meilleur.supportLevel != null
                            && m.supportLevel.compareTo(meilleur.supportLevel) > 0)) meilleur = m;
                }
                niveau = r.supportLevel;
                if (meilleur != null) { action = meilleur.recommendedAction; diag = meilleur.diagnostic; }
            } catch (Throwable ignored) {
            }
            triageAction = action; triageNiveau = niveau; triageDiag = diag;
            triageCle = cle;
            triageEnCours = false;
        }, "simplifie-triage").start();
    }

    /** Un toucher pour relancer le service d'impression (com.filgo.printspooler). */
    private void demarrerServiceImpression() {
        final Context ctx = holder.getContext();
        boolean ok = false;
        try {
            if (!SimplifiedConfig.PRINT_SERVICE_CLASS.isEmpty()) {
                try {
                    Intent svc = new Intent();
                    svc.setClassName(SimplifiedConfig.PRINT_PACKAGE, SimplifiedConfig.PRINT_SERVICE_CLASS);
                    if (android.os.Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc);
                    else ctx.startService(svc);
                    ok = true;
                } catch (Throwable t) {
                    Log.w(TAG, "demarrerServiceImpression: démarrage direct impossible → ouverture de l'app: " + t.getMessage());
                }
            }
            if (!ok) {
                Intent li = ctx.getPackageManager().getLaunchIntentForPackage(SimplifiedConfig.PRINT_PACKAGE);
                if (li != null) {
                    li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(li);
                    ok = true;
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "demarrerServiceImpression ERR", t);
        }
        if (ok) {
            Toast.makeText(ctx, "Démarrage du service d’impression…", Toast.LENGTH_SHORT).show();
            dernierSondageMs = 0L;                    // re-teste tout de suite : la bulle se range si le service répond
            cleAlerte = ""; dernierSonMs = 0L;
        } else {
            Toast.makeText(ctx, "Service d’impression introuvable sur cette tablette", Toast.LENGTH_LONG).show();
        }
    }

    private void afficherBulle() {
        if (bulle == null || bulleVisible) return;
        bulleVisible = true;
        bulle.setVisibility(View.VISIBLE);
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        // Sort du logo Filgo (coin haut-gauche) : petit, décalé vers le logo, puis grandit avec un rebond
        float dp = bulle.getResources().getDisplayMetrics().density;
        bulle.setPivotX(0f);
        bulle.setPivotY(0f);
        bulle.setAlpha(0f);
        bulle.setScaleX(0.2f);
        bulle.setScaleY(0.2f);
        bulle.setTranslationX(-40 * dp);
        bulle.setTranslationY(-70 * dp);
        bulle.animate().alpha(1f).scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
                .setDuration(500).setInterpolator(new android.view.animation.OvershootInterpolator(1.1f)).start();
    }

    private void cacherBulle() {
        if (bulle == null || !bulleVisible) return;
        bulleVisible = false;
        if (!ValueAnimator.areAnimatorsEnabled()) { bulle.setVisibility(View.GONE); return; }
        float dp = bulle.getResources().getDisplayMetrics().density;
        bulle.animate().alpha(0f).scaleX(0.2f).scaleY(0.2f).translationX(-40 * dp).translationY(-70 * dp)
                .setDuration(300).withEndAction(() -> { if (!bulleVisible) bulle.setVisibility(View.GONE); }).start();
    }

    // ------------------------------------------------------------------ trombone animée

    private static final long TROMBONE_PERIODE_MS = 10_000L;
    private static final long SONDE_PERIODE_MS = 15_000L;
    private volatile boolean printActif = true;
    private volatile boolean sondeEnCours = false;
    private long dernierSondageMs = 0L;
    private long dernierBalanceMs = 0L;

    /** Teste (en arrière-plan) si le service d'impression écoute : simple connexion TCP, aucune requête. */
    private void sonderServiceImpression() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (sondeEnCours || now - dernierSondageMs < SONDE_PERIODE_MS) return;
        sondeEnCours = true;
        dernierSondageMs = now;
        new Thread(() -> {
            boolean ok = false;
            try (java.net.Socket s = new java.net.Socket()) {
                s.connect(new java.net.InetSocketAddress("127.0.0.1", 9182), 800);
                ok = true;
            } catch (Exception ignored) {
            }
            printActif = ok;
            sondeEnCours = false;
        }, "simplifie-sonde-print").start();
    }

    private void balancerTrombone() {
        if (sBtnSupport == null || !ValueAnimator.areAnimatorsEnabled()) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - dernierBalanceMs < TROMBONE_PERIODE_MS) return;
        dernierBalanceMs = now;
        sBtnSupport.setPivotX(sBtnSupport.getWidth() / 2f);
        sBtnSupport.setPivotY(sBtnSupport.getHeight() * 0.9f);
        ObjectAnimator a = ObjectAnimator.ofFloat(sBtnSupport, View.ROTATION, 0f, -16f, 14f, -10f, 8f, -4f, 0f);
        a.setDuration(900);
        a.start();
    }

    private void rebondTrombone() {
        if (sBtnSupport == null || !ValueAnimator.areAnimatorsEnabled()) return;
        ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(sBtnSupport,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.25f, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.25f, 1f));
        a.setDuration(250);
        a.start();
    }

    /** Sélecteur de registre (la barre d'onglets est cachée en mode simplifié). */
    private void choisirRegistre() {
        try {
            android.app.Activity act = host.getActivity();
            if (!(act instanceof MainActivity)) return;
            if (dernierEtat == Etat.DEBIT || dernierEtat == Etat.CONFIRM
                    || dernierEtat == Etat.PAUSE || dernierEtat == Etat.ENDING) {
                Toast.makeText(act, "Livraison en cours — terminez-la avant de changer de registre",
                        Toast.LENGTH_LONG).show();
                return;
            }
            final MainActivity ma = (MainActivity) act;
            final java.util.List<String[]> regs = ma.registresPourSimplifie();
            if (regs.size() < 2) return;
            String[] libelles = new String[regs.size()];
            int courant = 0;
            for (int i = 0; i < regs.size(); i++) {
                boolean actif = "1".equals(regs.get(i)[2]);
                if (actif) courant = i;
                libelles[i] = (actif ? "✓  " : "      ") + regs.get(i)[1];
            }
            new android.app.AlertDialog.Builder(act)
                    .setTitle("Changer de registre")
                    .setSingleChoiceItems(libelles, courant, (d, which) -> {
                        d.dismiss();
                        if (which != regs.size() && !"1".equals(regs.get(which)[2])) {
                            ma.choisirRegistrePourSimplifie(regs.get(which)[0]);
                        }
                    })
                    .setNegativeButton("Fermer", null)
                    .show();
        } catch (Throwable t) {
            Log.e(TAG, "choisirRegistre ERR", t);
        }
    }

    /** Nombre de registres ouverts (0 si inconnu). */
    private int nbRegistres() {
        try {
            android.app.Activity act = host.getActivity();
            return act instanceof MainActivity ? ((MainActivity) act).registresPourSimplifie().size() : 0;
        } catch (Throwable t) { return 0; }
    }

    private void cliquer(View cible) {
        if (cible != null) cible.performClick();
    }

    // ------------------------------------------------------------------ rafraîchissement

    private void refresh() {
        int o = holder.getContext().getResources().getConfiguration().orientation;
        if (o != builtOrientation) build(o);

        boolean admin = adminUnlocked;
        // Barre du haut, onglets Main/API/Configure/Support et Quit : cachés en mode simplifié
        android.app.Activity act = host.getActivity();
        if (act instanceof MainActivity) ((MainActivity) act).appliquerModeSimplifie(!admin);
        holder.setVisibility(admin ? View.GONE : View.VISIBLE);
        btnRetourSimple.setVisibility(admin ? View.VISIBLE : View.GONE);
        if (admin) { arreterAnimations(); dernierEtat = null; cacherBulle(); return; }

        String live = txt(txtLive).toUpperCase(Locale.ROOT);
        double net = nombre(txtQtyNet);
        double gross = nombre(txtQtyGross);
        Etat etat = calculerEtat(live, net, gross);

        // --- Registre
        String serie = apresDeuxPoints(txtSerialId);
        String node = apresDeuxPoints(txtLcrNode);
        boolean plusieurs = nbRegistres() > 1;
        sTxtRegistre.setText("Registre #" + serie + " · Node " + node + (plusieurs ? "  ▾" : ""));
        sTxtRegistre.setClickable(plusieurs);
        sTxtRegistre.setPadding(0, plusieurs ? 8 : 0, 0, plusieurs ? 8 : 0);
        boolean coupe = live.contains("DISCONNECTED");
        // Trombone : se balance de temps en temps si le registre est déconnecté OU si le service
        // d'impression (127.0.0.1:9182) ne répond pas. Immobile sinon.
        sonderServiceImpression();
        // Registre rétabli / livraison avancée : on oublie les échecs de détection du deeplink
        if (etat == Etat.PRET || etat == Etat.DEBIT || etat == Etat.CONFIRM || etat == Etat.PAUSE
                || etat == Etat.ENDING || etat == Etat.TICKET || etat == Etat.FIN) {
            SimplifiedUiState.reinitialiserEchecsDeepLink();
        }
        boolean echecDeepLink = !coupe
                && SimplifiedUiState.getEchecsDeepLink(woCourant()) >= SimplifiedConfig.DEEPLINK_ECHECS_AVANT_ALERTE;
        if (coupe || echecDeepLink || !printActif) balancerTrombone();
        gererAssistant(coupe, echecDeepLink, etat);
        sTxtRegistreEtat.setText(etat == Etat.CONNEXION
                ? (coupe ? "Reconnexion au registre nécessaire" : "Connexion en cours…")
                : "Connecté");

        // --- Bandeau d'état (texte, couleurs, indicateur de connexion)
        appliquerEtat(etat);

        // --- Live
        boolean volume = net > 0.0 || gross > 0.0;
        sTxtNet.setText(formater(txt(txtQtyNet)));
        sTxtGross.setText(formater(txt(txtQtyGross)) + " L");
        sTxtNet.setTextColor(Color.parseColor(volume ? couleurs(etat)[2] : "#8A9591"));

        String wo = woCourant();
        sTxtWo.setText(wo.isEmpty() ? "—" : wo);
        String preset = edtPreset != null && edtPreset.getText() != null ? edtPreset.getText().toString().trim() : "";
        sTxtPreset.setText(preset.isEmpty() ? "—" : preset + " L");
        String prodReg = spnProduct != null && spnProduct.getText() != null ? spnProduct.getText().toString().trim() : "";
        sTxtProdReg.setText(prodReg.isEmpty() ? "—" : prodReg);

        String fsDesc = SimplifiedUiState.getFsProduitDesc(wo);
        sBlocProdFs.setVisibility(fsDesc.isEmpty() ? View.GONE : View.VISIBLE);
        sTxtProdFs.setText(fsDesc);

        // Ticket / Sale Number : le texte (avec son soulignement) est copié tel quel
        sTxtTicket.setText(txtTicketNo != null ? txtTicketNo.getText() : "");
        sTxtVente.setText(txtSaleNo != null ? txtSaleNo.getText() : "");

        // --- WO complété + déjà livré
        boolean cumulVisible = panelWoCumul != null && panelWoCumul.getVisibility() == View.VISIBLE;
        sPanelWo.setVisibility(cumulVisible ? View.VISIBLE : View.GONE);
        String cumulNet = formater(txt(txtWoCumulNet)); // "Total NET: 212.4 L"
        sTxtDejaLivre.setText(cumulVisible ? cumulNet + " L" : "—");
        String nb = txtWoCumulCount != null ? txt(txtWoCumulCount) : "";
        sTxtWoCumul.setText(cumulNet + " L" + (nb.isEmpty() ? "" : " · " + nb));

        // --- Boutons (mêmes conditions que l'onglet actuel)
        boolean montrerTerminer = etat == Etat.PRET || etat == Etat.DEBIT || etat == Etat.CONFIRM || etat == Etat.PAUSE;
        boolean terminerActif = btnFinish != null && btnFinish.isEnabled();
        sBtnTerminer.setVisibility(montrerTerminer ? View.VISIBLE : View.GONE);
        sBtnTerminer.setEnabled(terminerActif);
        sBtnTerminer.setAlpha(terminerActif ? 1f : 0.35f);

        boolean montrerAnnuler = btnAnnuler != null && btnAnnuler.getVisibility() == View.VISIBLE
                && etat != Etat.CONNEXION && etat != Etat.PREP;
        sBtnAnnuler.setVisibility(montrerAnnuler ? View.VISIBLE : View.GONE);
        if (montrerAnnuler) {
            sBtnAnnuler.setEnabled(btnAnnuler.isEnabled());
            sBtnAnnuler.setAlpha(btnAnnuler.isEnabled() ? 1f : 0.45f);
            String t = btnAnnuler.getText() != null ? btnAnnuler.getText().toString().replace("⛔", "").trim() : "";
            sBtnAnnuler.setText(t.isEmpty() ? "Annuler la livraison" : t);
        }

        boolean retourVisible = btnRetourWO != null && btnRetourWO.getVisibility() == View.VISIBLE && etat != Etat.PREP;
        boolean estLancer = retourVisible && btnRetourWO.getText() != null
                && btnRetourWO.getText().toString().toLowerCase(Locale.ROOT).contains("lancer");
        sBtnLancer.setVisibility(retourVisible && estLancer ? View.VISIBLE : View.GONE);
        sBtnRetour.setVisibility(retourVisible && !estLancer ? View.VISIBLE : View.GONE);

        // --- Animations
        boolean pulseTerminer = etat == Etat.PAUSE && terminerActif;
        if (etat != dernierEtat) {
            dernierEtat = etat;
            demarrerAnimationPoint(etat);
        }
        if (pulseTerminer != dernierPulseTerminer) {
            dernierPulseTerminer = pulseTerminer;
            if (animTerminer != null) { animTerminer.cancel(); animTerminer = null; }
            sBtnTerminer.setScaleX(1f);
            sBtnTerminer.setScaleY(1f);
            if (pulseTerminer && ValueAnimator.areAnimatorsEnabled()) {
                ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(sBtnTerminer,
                        PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.04f),
                        PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.04f));
                a.setDuration(650);
                a.setRepeatCount(ValueAnimator.INFINITE);
                a.setRepeatMode(ValueAnimator.REVERSE);
                a.start();
                animTerminer = a;
            }
        }
    }

    // ------------------------------------------------------------------ état

    private Etat calculerEtat(String live, double net, double gross) {
        boolean mediaPret = host.isMediaReadyPourSimplifie();
        if (!mediaPret || live.contains("DISCONNECTED") || live.contains("(EN ATTENTE)") || live.isEmpty()) {
            return Etat.CONNEXION;
        }
        if (live.contains("RUNNING_PAUSED")) return Etat.PAUSE;
        if (live.contains("RUNNING_FLOWING")) {
            if (live.contains("ARMED") || live.contains("ARMÉ")) return Etat.PREP;
            if (live.contains("FLOW ON") || live.contains("RECOVERED")) return Etat.DEBIT;
            // « Flow OFF » : avant tout volume = registre prêt ; après du volume = arrêt à confirmer
            if (live.contains("CONFIRMING")) return Etat.CONFIRM;
            return (net > 0.0 || gross > 0.0) ? Etat.CONFIRM : Etat.PRET;
        }
        if (live.contains("ARMÉ") || live.contains("ARMED")) return Etat.PREP;
        if (host.armementEnCoursParCetteSession) return Etat.PREP;
        if (live.contains("ENDING")) return Etat.ENDING;
        if (live.contains("TICKET PENDING")) return Etat.TICKET;
        boolean retourBleu = btnRetourWO != null && btnRetourWO.getVisibility() == View.VISIBLE
                && btnRetourWO.getText() != null
                && !btnRetourWO.getText().toString().toLowerCase(Locale.ROOT).contains("lancer");
        return retourBleu ? Etat.FIN : Etat.ATTENTE;
    }

    /** {couleur, fond, texte}. Couleurs Filgo (vert) + bleu/ambre pour les états d'attente. */
    private String[] couleurs(Etat e) {
        switch (e) {
            case CONNEXION: case ATTENTE: case PREP: case ENDING:
                return new String[]{"#185FA5", "#E8F0F9", "#0E3F70"};
            case PRET: case DEBIT:
                return new String[]{"#0B6B55", "#E3F2EC", "#01372C"};
            case CONFIRM:
                return new String[]{"#B45309", "#FFF3DC", "#6B3300"};
            default: // PAUSE, TICKET, FIN
                return new String[]{"#024B3C", "#DDEFE8", "#01372C"};
        }
    }

    private void appliquerEtat(Etat e) {
        String titre; String sous;
        switch (e) {
            case CONNEXION:
                titre = "Connexion au registre…";
                sous = txt(txtLive).toUpperCase(Locale.ROOT).contains("DISCONNECTED")
                        ? "Reconnexion au registre nécessaire. Patientez ou vérifiez le branchement."
                        : "Veuillez patienter, le registre se connecte.";
                break;
            case ATTENTE:
                titre = "En attente d’une livraison";
                sous = btnRetourWO != null && btnRetourWO.getVisibility() == View.VISIBLE
                        ? "Appuyez sur Lancer la livraison." : "Le registre est connecté.";
                break;
            case PREP:
                titre = "En préparation du démarrage…";
                sous = "Ne démarrez pas encore. Le registre se prépare.";
                break;
            case PRET:
                titre = "Registre prêt — démarrez le débit";
                sous = "Vous pouvez démarrer la livraison.";
                break;
            case DEBIT:
                titre = "Livraison en cours";
                sous = "Le débit est en cours. Laissez la livraison se poursuivre.";
                break;
            case CONFIRM:
                titre = "Arrêt du débit détecté";
                sous = "Confirmation en cours, veuillez patienter…";
                break;
            case PAUSE:
                titre = "Livraison en pause";
                sous = "Appuyez sur Terminer. Si le débit reprend, la livraison continue automatiquement.";
                break;
            case ENDING:
                titre = "Fin de livraison en cours…";
                sous = "Veuillez patienter.";
                break;
            case TICKET:
                titre = "Livraison terminée";
                sous = "Ticket en cours de traitement…";
                break;
            default: // FIN
                titre = "Livraison terminée";
                sous = "Vous pouvez revenir au bon de travail.";
                break;
        }
        String[] c = couleurs(e);
        int couleur = Color.parseColor(c[0]);
        sTxtTitre.setText(titre);
        sTxtSous.setText(sous);
        sTxtTitre.setTextColor(Color.parseColor(c[2]));
        sTxtSous.setTextColor(Color.parseColor(c[2]));
        sBanner.setCardBackgroundColor(Color.parseColor(c[1]));
        sBanner.setStrokeColor(couleur);
        sDot.setBackgroundTintList(ColorStateList.valueOf(couleur));
        // Bande bleue qui défile : pendant la connexion, ou tant qu'un message d'initialisation est en cours
        String msgInit = host.getInitGuideMessagePourSimplifie();
        boolean aMsgInit = msgInit != null && !msgInit.trim().isEmpty();
        // (demande Paul 6 nov) : une fois « Vous pouvez revenir au bon de travail » affiché, la bande ne défile plus
        sProgConn.setVisibility(e == Etat.CONNEXION || (aMsgInit && e != Etat.FIN) ? View.VISIBLE : View.GONE);
        sProgConn.setIndeterminateTintList(ColorStateList.valueOf(Color.parseColor("#185FA5")));
        sTxtInit.setVisibility(aMsgInit ? View.VISIBLE : View.GONE);
        if (aMsgInit) {
            sTxtInit.setText(msgInit);
            sTxtInit.setTextColor(Color.parseColor(c[2]));
        }
    }

    // ------------------------------------------------------------------ animations

    private void demarrerAnimationPoint(Etat e) {
        arreterAnimationPoint();
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        PropertyValuesHolder[] h;
        long dur;
        switch (e) {
            case PREP: case PRET: case DEBIT: // pulsation
                h = new PropertyValuesHolder[]{
                        PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.35f),
                        PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.35f),
                        PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.55f)};
                dur = 700;
                break;
            case CONNEXION: case ATTENTE: case CONFIRM: // clignotement
                h = new PropertyValuesHolder[]{PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.25f)};
                dur = 550;
                break;
            default:
                return;
        }
        ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(sDot, h);
        a.setDuration(dur);
        a.setInterpolator(new LinearInterpolator());
        a.setRepeatCount(ValueAnimator.INFINITE);
        a.setRepeatMode(ValueAnimator.REVERSE);
        a.start();
        animDot = a;
    }

    private void arreterAnimationPoint() {
        if (animDot != null) { animDot.cancel(); animDot = null; }
        if (sDot != null) { sDot.setScaleX(1f); sDot.setScaleY(1f); sDot.setAlpha(1f); }
    }

    private void arreterAnimations() {
        arreterAnimationPoint();
        if (animTerminer != null) { animTerminer.cancel(); animTerminer = null; }
        if (sBtnTerminer != null) { sBtnTerminer.setScaleX(1f); sBtnTerminer.setScaleY(1f); }
        dernierPulseTerminer = false;
    }

    // ------------------------------------------------------------------ Admin

    private void demanderMotDePasse() {
        // NB : la version avec TextInputLayout (demanderMotDePasseMaterial) plantait à la mesure de la
        // boîte (logcat 6 nov, AlertDialogLayout.onMeasure) — erreur hors de portée d'un try/catch.
        // On utilise donc des vues de base : champ masqué + bouton œil à droite.
        try {
            demanderMotDePasseSimple();
        } catch (Throwable t) {
            Log.e(TAG, "demanderMotDePasse ERR", t);
            Toast.makeText(holder.getContext(), "Impossible d’ouvrir la saisie du mot de passe", Toast.LENGTH_LONG).show();
        }
    }

    private void demanderMotDePasseSimple() {
        final Context ctx = holder.getContext();
        final float dp = ctx.getResources().getDisplayMetrics().density;
        final android.widget.EditText saisie = new android.widget.EditText(ctx);
        saisie.setHint("Mot de passe");
        saisie.setSingleLine(true);
        saisie.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final android.widget.ImageButton oeil = new android.widget.ImageButton(ctx);
        oeil.setImageResource(android.R.drawable.ic_menu_view);
        oeil.setBackgroundColor(Color.TRANSPARENT);
        oeil.setContentDescription("Afficher / cacher le mot de passe");
        oeil.setOnClickListener(new View.OnClickListener() {
            boolean visible = false;
            @Override public void onClick(View v) {
                visible = !visible;
                saisie.setInputType(InputType.TYPE_CLASS_TEXT
                        | (visible ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
                saisie.setSelection(saisie.getText().length());
                oeil.setAlpha(visible ? 1f : 0.55f);
            }
        });
        oeil.setAlpha(0.55f);
        android.widget.LinearLayout ligne = new android.widget.LinearLayout(ctx);
        ligne.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        ligne.setGravity(Gravity.CENTER_VERTICAL);
        ligne.addView(saisie, new android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        ligne.addView(oeil, new android.widget.LinearLayout.LayoutParams((int) (48 * dp), (int) (48 * dp)));
        android.widget.LinearLayout box = new android.widget.LinearLayout(ctx);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (20 * dp);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(ligne, new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT));
        new android.app.AlertDialog.Builder(ctx)
                .setTitle("Accès Admin")
                .setMessage("Entrez le mot de passe pour afficher l’interface complète. "
                        + "L’écran simplifié sera désactivé jusqu’au retour.")
                .setView(box)
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Entrer", (d, w) -> {
                    if (saisie.getText().toString().equals(SimplifiedConfig.ADMIN_PASSWORD)) adminUnlocked = true;
                    else Toast.makeText(ctx, "Mot de passe incorrect", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void demanderMotDePasseMaterial() {
        Context ctx = holder.getContext();
        // Saisie masquée, avec un œil à droite du champ pour afficher / cacher le mot de passe
        final TextInputLayout champ = new TextInputLayout(ctx);
        champ.setHint("Mot de passe");
        champ.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);
        final TextInputEditText saisie = new TextInputEditText(champ.getContext());
        saisie.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        saisie.setSingleLine(true);
        champ.addView(saisie, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        int pad = (int) (20 * ctx.getResources().getDisplayMetrics().density);
        FrameLayout box = new FrameLayout(ctx);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(champ, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        new MaterialAlertDialogBuilder(ctx)
                .setTitle("Accès Admin")
                .setMessage("Entrez le mot de passe pour afficher l’interface complète. "
                        + "L’écran simplifié sera désactivé jusqu’au retour.")
                .setView(box)
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Entrer", (d, w) -> {
                    String saisi = saisie.getText() != null ? saisie.getText().toString() : "";
                    if (saisi.equals(SimplifiedConfig.ADMIN_PASSWORD)) {
                        adminUnlocked = true;
                    } else {
                        Toast.makeText(ctx, "Mot de passe incorrect", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    // ------------------------------------------------------------------ Support (trombone)

    private void ouvrirSupport() {
        try {
            String wo = woCourant();
            String serie = apresDeuxPoints(txtSerialId);
            String node = apresDeuxPoints(txtLcrNode);
            StringBuilder sb = new StringBuilder();
            sb.append("Tablette : ").append(SimplifiedUiState.nomTablette(holder.getContext())).append('\n');
            sb.append("Registre : #").append(serie).append(" · Node ").append(node).append('\n');
            sb.append("WO : ").append(wo.isEmpty() ? "—" : wo).append('\n');
            sb.append("État : ").append(sTxtTitre.getText()).append('\n');
            sb.append("Ticket : ").append(txtTicketNo != null ? txtTicketNo.getText() : "—").append('\n');
            sb.append("Vente : ").append(txtSaleNo != null ? txtSaleNo.getText() : "—").append('\n');
            String prod = spnProduct != null && spnProduct.getText() != null ? spnProduct.getText().toString() : "";
            sb.append("Produit (registre) : ").append(prod.isEmpty() ? "—" : prod).append('\n');
            String fs = SimplifiedUiState.getFsProduitDesc(wo);
            if (!fs.isEmpty()) sb.append("Produit (FieldService) : ").append(fs).append('\n');
            sb.append("Net : ").append(txt(txtQtyNet)).append(" · ").append(txt(txtQtyGross)).append('\n');
            sb.append("Live : ").append(txt(txtLive));
            if (!alerteContexte.isEmpty()) sb.append('\n').append("Alerte : ").append(alerteContexte);
            SupportRequestDialog.show(host.requireActivity(), sb.toString(), serie, node, wo,
                    host::demanderMicroPourSimplifie);
        } catch (Exception e) {
            Log.w(TAG, "ouvrirSupport ERR: " + e.getMessage());
            Toast.makeText(holder.getContext(), "Impossible d’ouvrir la demande de support", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ utilitaires

    private static String txt(TextView t) {
        return t != null && t.getText() != null ? t.getText().toString() : "";
    }

    /** Texte après le premier « : » (ex. « #Série : 16466294 » → « 16466294 »), « — » si vide. */
    private static String apresDeuxPoints(TextView t) {
        String s = txt(t);
        int i = s.indexOf(':');
        String r = (i >= 0 ? s.substring(i + 1) : s).trim();
        return r.isEmpty() ? "—" : r;
    }

    private static final Pattern NOMBRE = Pattern.compile("-?\\d+(?:[.,]\\d+)?");

    private static double nombre(TextView t) {
        try {
            Matcher m = NOMBRE.matcher(txt(t));
            if (m.find()) return Double.parseDouble(m.group().replace(',', '.'));
        } catch (Exception ignored) {}
        return 0.0;
    }

    /** « NET: 12.345 » / « Total NET: 212.4 L » → « 12,345 » / « 212,4 » (virgule, sans libellé). */
    private static String formater(String s) {
        if (s == null) return "0,0";
        Matcher m = NOMBRE.matcher(s.substring(s.indexOf(':') >= 0 ? s.indexOf(':') + 1 : 0));
        return m.find() ? m.group().replace('.', ',') : "0,0";
    }

    private String woCourant() {
        String wo = host.getWoNumPourSimplifie();
        if (wo != null && !wo.trim().isEmpty()) return wo.trim();
        Matcher m = Pattern.compile("WO:\\s*([^|\\s]+)").matcher(txt(txtDeliveryUid));
        return m.find() ? m.group(1) : "";
    }
}
