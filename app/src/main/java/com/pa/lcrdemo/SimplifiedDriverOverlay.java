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
    private View sBtnSupport;   // ancien bouton « Support » (trombone) : CACHÉ, la flamme le remplace (9 nov 2026)
    private FlammeView sMascotte; // mini mascotte à côté : verte, orange seulement en cas d'ERREUR
    private View sBtnQuitter;   // rond avec le symbole power simplifié
    private boolean erreurActive = false;

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

    private static volatile java.lang.ref.WeakReference<SimplifiedDriverOverlay> courant = null;

    /**
     * Appelé par RegisterConnectionHelper quand le diagnostic de reconnexion échoue. En mode simplifié, l'écran
     * « Registre non joignable » n'a plus à rester ouvert : la flamme cogne (tic tic) et la carte donne la consigne
     * N1 ou « Ouvrir un ticket », le dernier résultat du diagnostic étant joint à la demande de support.
     * @return true si l'alerte de la flamme a pris le relais (l'appelant n'affiche alors pas son écran).
     */
    static boolean signalerEchecDiagnostic(String resume) { return signalerEchecDiagnostic(resume, ""); }

    // ✅ FIX (11 nov 2026, demande Paul — « à l'arrivée dans Filgo-Registre, s'il voit le BT mais pas le registre, il doit
    // ouvrir l'écran support avec la flamme et le toc-toc, avec le dernier message de Filgo-Registre ») — avant, si la
    // surcouche n'était pas encore prête (Activity recréée, onglet pas encore reconstruit), l'alerte était PERDUE et le
    // chauffeur voyait l'ancien écran d'échec ou rien. Maintenant l'alerte est mise en attente (5 min) et jouée dès que
    // la surcouche est prête. La carte affiche la CAUSE trouvée par le diagnostic (pas de BT, registre muet, mauvais registre).
    private static volatile String pendingResume = null;
    private static volatile String pendingCause = "";
    private static volatile long pendingMs = 0L;
    private volatile String causeDiag = "";
    private volatile boolean echecForce = false;
    // ✅ AJOUTÉ (11 nov 2026, demande Paul — « Filgo-Registre ouvert avec le BT déconnecté du registre : ça tourne en rond
    // sans cesse ; après même pas 5 s on sait que le registre ne répond pas ») — « Connexion au registre… » ne dure plus
    // indéfiniment : au-delà de CONNEXION_LONGUE_MS, la flamme alerte (toc-toc + carte) et la reconnexion/diagnostic part.
    private volatile boolean perteEnLivraison = false;
    private Etat dernEtatLivr = null;
    private long dernEtatLivrMs = 0L;
    private volatile int perteCas = 0;   // 1 = BT connecté mais registre muet ; 2 = BT du registre (Parani) injoignable   // liaison BT/registre perdue PENDANT une livraison
    private long connexionDepuisMs = 0L;
    private boolean connexionLongueSignalee = false;
    private static final long CONNEXION_LONGUE_MS = 6000L;   // alerte demandée par le diagnostic, tenue jusqu'au retour du registre

    static boolean signalerEchecDiagnostic(String resume, String cause) {
        SimplifiedDriverOverlay o = courant != null ? courant.get() : null;
        if (adminUnlocked) return false;
        if (o == null || o.bulle == null || o.dernEtat == null) {
            pendingResume = resume == null ? "" : resume;
            pendingCause = cause == null ? "" : cause;
            pendingMs = System.currentTimeMillis();
            try { com.pa.lcr.lcp.log.LogBus.api(0, "[DIAGNOSTIC] surcouche pas prête — alerte de la flamme mise en attente"); } catch (Throwable ignored) {}
            return true;
        }
        o.causeDiag = cause == null ? "" : cause;
        o.echecForce = true;
        o.diagEchecTexte = resume == null ? "" : resume;
        // On veut que le chauffeur voie l'alerte : plus de silence demandé, diagnostic relancé avec le journal à jour.
        o.snoozeCle = ""; o.snoozeJusquaMs = 0L; o.oublieErreur = false;
        o.triageCle = null; o.triageAction = null; o.triageNiveau = null; o.triageDiag = null;
        o.gererAssistant(o.dernCoupe, o.dernEchec, o.dernEtat);
        return !o.cleAlerte.isEmpty() && o.bulleVisible;
    }

    private volatile String diagEchecTexte = "";

    private SimplifiedDriverOverlay(RegisterTabFragment host, FrameLayout wrapper, View original) {
        this.host = host;
        courant = new java.lang.ref.WeakReference<>(this);
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
        sBtnSupport = root.findViewById(R.id.sBtnSupport);
        sBtnAdmin = root.findViewById(R.id.sBtnAdmin);
        final float dpi = ctx.getResources().getDisplayMetrics().density;
        // Mascotte (mini flamme) à côté du bouton Support : VERTE ; elle ne change de couleur qu'en cas d'ERREUR
        sMascotte = new FlammeView(ctx);
        sMascotte.setMini(true);
        sMascotte.setErreur(erreurActive);
        sMascotte.setInquiet(erreurActive);
        sMascotte.setContentDescription("Support Filgo");
        // Flamme BLEUE tant qu'une réponse du support n'a pas été consultée (table demande_support).
        final Runnable majReponseSupport = () -> {
            try {
                final boolean bleue = new com.pa.lcr.lcp.storage.DemandeSupportStore(ctx).aReponseNonVue();
                if (sMascotte != null) sMascotte.post(() -> {
                    sMascotte.setReponse(bleue);
                    flammeBleue = bleue;
                    if (!bleue) dernierRappelBleuMs = 0L;
                    rappelBulleBleue();
                });
            } catch (Throwable t) { Log.w(TAG, "flamme bleue ERR: " + t.getMessage()); }
        };
        SupportSuivi.ecouter(majReponseSupport);
        sMascotte.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) { SupportSuivi.ecouter(majReponseSupport); majReponseSupport.run(); }
            @Override public void onViewDetachedFromWindow(View v) { SupportSuivi.retirer(majReponseSupport); }
        });
        SupportSuivi.demarrer(ctx);   // reprend la surveillance des demandes encore sans réponse (après redémarrage)
        majReponseSupport.run();
        if (sBtnSupport != null && sBtnSupport.getParent() instanceof android.view.ViewGroup) {
            android.view.ViewGroup parent = (android.view.ViewGroup) sBtnSupport.getParent();
            LinearLayout.LayoutParams lpm = new LinearLayout.LayoutParams((int) (48 * dpi), (int) (48 * dpi));
            lpm.setMargins(0, 0, (int) (6 * dpi), 0);
            parent.addView(sMascotte, parent.indexOfChild(sBtnSupport), lpm);
            // La flamme REMPLACE le bouton Support (trombone) : on le cache, la flamme ouvre la demande de support.
            sBtnSupport.setVisibility(View.GONE);
        }
        // Bouton Quitter (la barre du haut avec Quit est cachée en mode simplifié), après Admin
        if (sBtnAdmin != null && sBtnAdmin.getParent() instanceof android.view.ViewGroup) {
            android.view.ViewGroup parent = (android.view.ViewGroup) sBtnAdmin.getParent();
            View q = new View(ctx);
            android.graphics.drawable.ShapeDrawable masque = new android.graphics.drawable.ShapeDrawable(
                    new android.graphics.drawable.shapes.OvalShape());
            q.setBackground(new android.graphics.drawable.RippleDrawable(
                    ColorStateList.valueOf(Color.parseColor("#33FFFFFF")), new PowerIconDrawable(dpi), masque));
            q.setClickable(true);
            q.setFocusable(true);
            q.setContentDescription("Quitter");
            sBtnQuitter = q;
            LinearLayout.LayoutParams lpq = new LinearLayout.LayoutParams((int) (44 * dpi), (int) (44 * dpi));
            lpq.setMargins((int) (8 * dpi), 0, 0, 0);
            parent.addView(sBtnQuitter, parent.indexOfChild(sBtnAdmin) + 1, lpq);
        }

        // Les boutons d'action délèguent aux boutons existants de l'onglet.
        sBtnTerminer.setOnClickListener(v -> cliquer(btnFinish));
        sBtnAnnuler.setOnClickListener(v -> cliquer(btnAnnuler));
        sBtnLancer.setOnClickListener(v -> cliquer(btnRetourWO));
        sBtnRetour.setOnClickListener(v -> cliquer(btnRetourWO));
        sPanelWo.setOnClickListener(v -> cliquer(panelWoCumul));
        if (sBtnSupport != null) sBtnSupport.setOnClickListener(v -> { rebondTrombone(); ouvrirSupport(); });
        sMascotte.setOnClickListener(v -> clicMascotte());
        sMascotte.setOnLongClickListener(v -> { basculerFlammeAnimee(); return true; });
        if (sBtnQuitter != null) sBtnQuitter.setOnClickListener(v -> {
            android.app.Activity act = host.getActivity();
            if (act instanceof MainActivity) ((MainActivity) act).confirmQuitPourSimplifie();
        });
        // Appui long sur le bouton power : redémarrer l'application (appui court = quitter)
        if (sBtnQuitter != null) sBtnQuitter.setOnLongClickListener(v -> {
            android.app.Activity act = host.getActivity();
            if (act instanceof MainActivity) ((MainActivity) act).confirmRestartPourSimplifie();
            return true;
        });
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

    private LinearLayout bCarte;           // le message d'erreur (carte blanche) — affiché APRÈS l'explosion
    private MaterialButton btnReconnecter;
    private boolean carteVisible = false;
    private boolean oublieErreur = false;      // « Oublie-moi » : silence total jusqu'à la PROCHAINE erreur

    private MaterialButton btnContacter;

    private void construireBulle(Context ctx, FrameLayout wrapper) {
        final float dp = ctx.getResources().getDisplayMetrics().density;
        // « bulle » = la mascotte seule (sans boîte) ; la carte du message vient en dessous, plus tard
        bulle = new LinearLayout(ctx);
        bulle.setOrientation(LinearLayout.VERTICAL);
        bulle.setPadding((int) (4 * dp), 0, 0, 0);
        bFlamme = new FlammeView(ctx);
        bulle.addView(bFlamme, new LinearLayout.LayoutParams((int) (140 * dp), (int) (140 * dp)));

        bCarte = new LinearLayout(ctx);
        bCarte.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (14 * dp);
        bCarte.setPadding(pad, pad, pad, pad / 2);
        android.graphics.drawable.GradientDrawable fond = new android.graphics.drawable.GradientDrawable();
        fond.setColor(Color.WHITE);
        fond.setCornerRadius(16 * dp);
        fond.setStroke((int) (2 * dp), Color.parseColor("#185FA5"));
        bCarte.setBackground(fond);
        bCarte.setElevation(24f);
        bCarte.setVisibility(View.GONE);

        bTitre = new TextView(ctx);
        bTitre.setTextColor(Color.parseColor("#0B2A4A"));
        bTitre.setTextSize(18f);
        bTitre.setTypeface(null, android.graphics.Typeface.BOLD);
        bCarte.addView(bTitre);

        bAction = new TextView(ctx);
        bAction.setTextColor(Color.parseColor("#33475B"));
        bAction.setTextSize(15f);
        bAction.setPadding(0, (int) (8 * dp), 0, (int) (10 * dp));
        bCarte.addView(bAction);

        // Action principale : Reconnecter (erreur registre) ou Démarrer l'impression (avertissement)
        LinearLayout principal = new LinearLayout(ctx);
        principal.setOrientation(LinearLayout.HORIZONTAL);
        btnReconnecter = new MaterialButton(ctx);
        btnReconnecter.setText("Reconnecter");
        btnReconnecter.setAllCaps(false);
        btnReconnecter.setTextColor(Color.WHITE);
        btnReconnecter.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#185FA5")));
        btnReconnecter.setVisibility(View.GONE);
        btnReconnecter.setOnClickListener(v -> reconnecterRegistre());
        principal.addView(btnReconnecter, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        btnPrint = new MaterialButton(ctx);
        btnPrint.setText("Démarrer l’impression");
        btnPrint.setAllCaps(false);
        btnPrint.setTextColor(Color.WHITE);
        btnPrint.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#185FA5")));
        btnPrint.setVisibility(View.GONE);
        btnPrint.setOnClickListener(v -> demarrerServiceImpression());
        principal.addView(btnPrint, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bCarte.addView(principal);

        LinearLayout secondaires = new LinearLayout(ctx);
        secondaires.setOrientation(LinearLayout.HORIZONTAL);
        secondaires.setGravity(Gravity.END);
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
        btnContacter = contacter;
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
        secondaires.addView(plusTard);
        secondaires.addView(contacter, lpb);
        bCarte.addView(secondaires);

        MaterialButton oublie = new MaterialButton(ctx, null, com.google.android.material.R.attr.borderlessButtonStyle);
        oublie.setText("Oublie-moi jusqu’à la prochaine erreur");
        oublie.setAllCaps(false);
        oublie.setTextSize(13f);
        oublie.setTextColor(Color.parseColor("#185FA5"));
        oublie.setOnClickListener(v -> {
            oublieErreur = true;
            snoozeCle = cleAlerte;
            snoozeJusquaMs = Long.MAX_VALUE;     // jusqu'à ce que l'erreur disparaisse puis revienne (ou change)
            cacherBulle();
        });
        LinearLayout ligneOublie = new LinearLayout(ctx);
        ligneOublie.setGravity(Gravity.END);
        ligneOublie.addView(oublie);
        bCarte.addView(ligneOublie);
        bulle.addView(bCarte, new LinearLayout.LayoutParams((int) Math.min(360 * dp,
                ctx.getResources().getDisplayMetrics().widthPixels * 0.9f), LinearLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams lpBulle = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lpBulle.gravity = Gravity.TOP | Gravity.START;
        lpBulle.setMargins((int) (12 * dp), (int) (76 * dp), 0, 0);
        bulle.setVisibility(View.GONE);
        wrapper.addView(bulle, lpBulle);
    }

    /** Même geste que le long press « Reconnect » d'un onglet de registre. */
    private void reconnecterRegistre() {
        try {
            snoozeCle = cleAlerte;
            snoozeJusquaMs = android.os.SystemClock.elapsedRealtime() + 60_000L;   // laisse 60 s à la reconnexion
            cacherBulle();
            Toast.makeText(holder.getContext(), "Reconnexion au registre…", Toast.LENGTH_SHORT).show();
            host.reconnectFromDialog();
        } catch (Throwable t) {
            Log.e(TAG, "reconnecterRegistre ERR", t);
            Toast.makeText(holder.getContext(), "Impossible de relancer la reconnexion", Toast.LENGTH_LONG).show();
        }
    }

    /** Toucher la mascotte : réaffiche l'erreur en cours, sinon ouvre la demande de support. */
    private void clicMascotte() {
        if (!cleAlerte.isEmpty() && !bulleVisible) {
            snoozeCle = ""; snoozeJusquaMs = 0L; oublieErreur = false;
            afficherBulle();
            montrerCarte(true);
        } else if (cleAlerte.isEmpty()) {
            saluerPuisSupport();
        }
    }

    private boolean salutEnCours = false;

    /** La flamme dit « hey, je suis là, youhoo ! » (sauts, bras qui font signe), puis la demande de support s'ouvre. */
    private void saluerPuisSupport() {
        if (sMascotte == null || !ValueAnimator.areAnimatorsEnabled()) { ouvrirSupport(); return; }
        if (salutEnCours) return;
        salutEnCours = true;
        sMascotte.bonjour();
        ui.postDelayed(() -> { salutEnCours = false; ouvrirSupport(); }, FlammeView.DUREE_BONJOUR_MS - 200L);
    }

    private void montrerCarte(boolean voir) {
        if (bCarte == null || carteVisible == voir) return;
        carteVisible = voir;
        if (!voir) { bCarte.setVisibility(View.GONE); return; }
        bCarte.setVisibility(View.VISIBLE);
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        bCarte.setAlpha(0f);
        bCarte.setTranslationY(-12 * bCarte.getResources().getDisplayMetrics().density);
        bCarte.animate().alpha(1f).translationY(0f).setDuration(280).start();
    }

    private boolean dernCoupe = false, dernEchec = false;
    private Etat dernEtat = null;

    private void gererAssistant(boolean coupe, boolean echecDeepLink, Etat etat) {
        if (bulle == null) return;
        if (pendingResume != null) {
            if (System.currentTimeMillis() - pendingMs < 5 * 60_000L) {
                diagEchecTexte = pendingResume; causeDiag = pendingCause; echecForce = true;
                echecDeepLink = true;     // alerte demandée par le diagnostic pendant que la surcouche n'était pas prête
                snoozeCle = ""; snoozeJusquaMs = 0L; oublieErreur = false;
                triageCle = null; triageAction = null; triageNiveau = null; triageDiag = null;
            }
            pendingResume = null; pendingCause = "";
        }
        dernCoupe = coupe; dernEchec = echecDeepLink; dernEtat = etat;
        boolean enLivraison = etat == Etat.DEBIT || etat == Etat.CONFIRM
                || etat == Etat.PAUSE || etat == Etat.ENDING;
        // ERREUR = communication perdue ou registre introuvable ; AVERTISSEMENT = impression (mascotte reste verte)
        boolean erreur = coupe || echecDeepLink || perteEnLivraison;
        String cle = perteEnLivraison ? ("PERTE" + perteCas) : coupe ? "COMM" : (echecDeepLink ? "DEEPLINK" : (!printActif ? "PRINT" : ""));
        erreurActive = erreur;
        if (sMascotte != null) { sMascotte.setErreur(erreur); sMascotte.setInquiet(erreur); }
        if (cle.isEmpty()) {          // tout va bien : on range et on remet à zéro
            cacherBulle();
            cleAlerte = ""; snoozeCle = ""; alerteContexte = ""; dernierSonMs = 0L; diagEchecTexte = "";
            oublieErreur = false;             // l'erreur est terminée : la prochaine sera annoncée normalement
            return;
        }
        if (enLivraison && !perteEnLivraison) { cacherBulle(); return; }
        if (cle.equals(snoozeCle) && android.os.SystemClock.elapsedRealtime() < snoozeJusquaMs) return;
        if (!cle.equals(cleAlerte)) {
            cleAlerte = cle;
            dernierSonMs = 0L;
            oublieErreur = false;             // nouvelle erreur (ou autre type) : on prévient de nouveau
            triageCle = null; triageAction = null; triageNiveau = null; triageDiag = null;
        }
        String titre, action;
        if ("PERTE1".equals(cle)) {
            titre = "Le registre ne répond plus";
            action = "Arrêtez le débit (fermez la vanne). Le Bluetooth est connecté, mais le registre ne répond pas : "
                + "vérifiez qu’il est sous tension et que son câble série est bien branché. "
                + "Dès qu’il répond, la livraison reprend exactement là où elle était.";
        } else if ("PERTE2".equals(cle)) {
            titre = "Bluetooth du registre injoignable";
            action = "Arrêtez le débit (fermez la vanne). La tablette ne voit plus le Bluetooth du registre (Parani) : "
                + "vérifiez son alimentation, rapprochez-vous du registre et éloignez les sources d’interférence. "
                + "Dès que la connexion revient, la livraison reprend exactement là où elle était.";
        } else if ("COMM".equals(cle)) {
            lancerTriage(cle);
            titre = "Communication perdue avec le registre";
            action = (causeDiag != null && !causeDiag.isEmpty()) ? causeDiag : actionCommunication(cle);
        } else if ("DEEPLINK".equals(cle)) {
            titre = "Registre introuvable";
            lancerTriage(cle);
            action = (causeDiag != null && !causeDiag.isEmpty()) ? causeDiag
                    : cle.equals(triageCle) ? actionCommunication(cle)
                    : "Le registre n’a pas répondu après plusieurs essais. Touchez Reconnecter, ou branchez le câble USB-C / "
                      + "activez le Bluetooth du registre. Sinon, contactez le support.";
        } else {
            titre = "Impression indisponible";
            action = "Le service d’impression ne répond pas. Touchez Démarrer l’impression, ou redémarrez la tablette.";
        }
        // Perte en livraison : le chauffeur a touché Reconnecter et le diagnostic (3 tentatives) a échoué → on ajoute la cause
        // trouvée ; le bouton devient « Ouvrir un ticket ». La reconnexion ne part JAMAIS toute seule pendant la livraison.
        boolean perteDiagEchoue = cle.startsWith("PERTE") && echecForce;
        if (perteDiagEchoue && causeDiag != null && !causeDiag.isEmpty()) {
            action = action + "\n\nReconnexion sans succès : " + causeDiag;
        }
        StringBuilder ctxA = new StringBuilder(titre).append(" — ").append(action);
        String niv = cle.equals(triageCle) ? triageNiveau : null;
        if (niv != null && !niv.isEmpty()) ctxA.append(" [").append(niv).append(']');
        String diag = cle.equals(triageCle) ? triageDiag : null;
        if (diag != null && !diag.trim().isEmpty()) ctxA.append(" | Diagnostic : ").append(diag.trim());
        String dj = diagEchecTexte == null ? "" : diagEchecTexte.trim().replace("\r", "").replaceAll("\\s*\n+\\s*", " · ");
        if (!dj.isEmpty() && !"PRINT".equals(cle)) {
            ctxA.append(" | Dernier diagnostic de reconnexion : ").append(dj.length() > 700 ? dj.substring(0, 700) : dj);
        }
        alerteContexte = ctxA.toString();
        bTitre.setText(titre);
        bAction.setText(action);
        if (btnContacter != null) {
            boolean ticket = perteDiagEchoue
                    || (("COMM".equals(cle) || "DEEPLINK".equals(cle)) && cle.equals(triageCle) && !diagnosticN1(cle));
            btnContacter.setText(ticket ? "Ouvrir un ticket" : "Contacter le support");
        }
        if (btnReconnecter != null) btnReconnecter.setVisibility(erreur ? View.VISIBLE : View.GONE);
        if (btnPrint != null) btnPrint.setVisibility("PRINT".equals(cle) ? View.VISIBLE : View.GONE);
        bFlamme.setVisibility(erreur && flammeAnimee() ? View.VISIBLE : View.GONE);
        boolean nouvelle = !bulleVisible;
        afficherBulle();
        if (!erreur) {                 // simple avertissement : carte directe, sans mascotte, sans son
            montrerCarte(true);
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (dernierSonMs == 0L || now - dernierSonMs >= SimplifiedConfig.SON_ALERTE_RAPPEL_MS) {
            dernierSonMs = now;
            // Laisse la mascotte finir son entrée (500 ms) avant de « cogner »
            final long delai = nouvelle && ValueAnimator.areAnimatorsEnabled() ? 550L : 0L;
            final boolean premiere = nouvelle;
            ui.postDelayed(() -> cognerEcran(premiere), delai);
        }
    }

    // ------------------------------------------------------------------ la mascotte cogne, explose, puis l'erreur s'affiche
    /** Elle cogne deux fois dans la vitre (toc-toc), explose en étincelles, se reforme inquiète ; ENSUITE la carte d'erreur apparaît. */
    private void cognerEcran(final boolean premiere) {
        if (bFlamme == null || !bulleVisible) return;
        if (!flammeAnimee()) {                      // animation coupée : carte tout de suite, le toc-toc sonore reste
            montrerCarte(true);
            if (SimplifiedConfig.SON_ALERTE_ACTIF) jouerToc();
            return;
        }
        if (premiere) montrerCarte(false);          // première fois : le message n'apparaît qu'après l'explosion
        bFlamme.setInquiet(false);
        final boolean anime = ValueAnimator.areAnimatorsEnabled();
        bFlamme.cogner(() -> {
            if (bFlamme == null || !bulleVisible) return;
            bFlamme.exploser(() -> { if (bulleVisible) montrerCarte(true); });
        });
        // Le son (deux petits coups espacés de 200 ms) démarre au premier coup de la mascotte
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
    /** Niveau de support du diagnostic : N1 = le chauffeur peut agir ; N2 et plus (ou inconnu) = ouvrir un ticket. */
    private boolean diagnosticN1(String cle) {
        return cle.equals(triageCle) && triageNiveau != null && "N1".equalsIgnoreCase(triageNiveau.trim());
    }

    private String actionCommunication(String cle) {
        if (!cle.equals(triageCle)) {
            return "Diagnostic en cours… Touchez Reconnecter. Si le problème continue, contactez le support.";
        }
        String a = triageAction == null ? "" : triageAction.trim();
        if (diagnosticN1(cle)) {
            return "Niveau 1 — faites ceci : "
                    + (a.isEmpty() ? "touchez Reconnecter, puis vérifiez le câble ou le Bluetooth du registre." : a);
        }
        String niv = triageNiveau == null ? "" : triageNiveau.trim();
        boolean niveauConnu = !niv.isEmpty() && !"N/A".equalsIgnoreCase(niv);
        return "Ce problème dépasse le niveau 1" + (niveauConnu ? " (" + niv + ")" : "")
                + ". Ouvrez un ticket : touchez « Ouvrir un ticket »."
                + (a.isEmpty() ? "" : "\nPiste : " + a);
    }

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
            // Le diagnostic est terminé et l'erreur est toujours là : on met la carte à jour (consigne N1 ou ticket)
            // et la flamme cogne une fois de plus (tic tic) pour attirer l'attention.
            ui.post(() -> {
                if (!cle.equals(cleAlerte) || dernEtat == null) return;
                gererAssistant(dernCoupe, dernEchec, dernEtat);
                if (bulleVisible && bFlamme != null) {
                    dernierSonMs = android.os.SystemClock.elapsedRealtime();
                    cognerEcran(false);
                }
            });
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
        carteVisible = false;
        if (bCarte != null) bCarte.setVisibility(View.GONE);
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

    // Animation de la flamme (mascotte qui cogne/explose + balancement) : désactivable par appui long sur la petite flamme du haut.
    // Le « toc-toc » sonore et la carte d'erreur restent actifs dans tous les cas.
    private boolean flammeAnimee() {
        try {
            return holder.getContext().getSharedPreferences("simplifie_prefs", Context.MODE_PRIVATE)
                    .getBoolean("flamme_animee", true);
        } catch (Throwable t) { return true; }
    }

    private void basculerFlammeAnimee() {
        boolean nouveau = !flammeAnimee();
        try {
            holder.getContext().getSharedPreferences("simplifie_prefs", Context.MODE_PRIVATE)
                    .edit().putBoolean("flamme_animee", nouveau).apply();
        } catch (Throwable ignored) {}
        if (!nouveau && bFlamme != null) bFlamme.setVisibility(View.GONE);
        Toast.makeText(holder.getContext(), nouveau
                ? "Grande flamme activée"
                : "Grande flamme désactivée — le toc-toc reste actif", Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ bulle de la petite flamme (en haut)
    private TextView sBulleFlamme;
    private final Runnable cacherBulleFlamme = () -> { if (sBulleFlamme != null) sBulleFlamme.setVisibility(View.GONE); };
    private boolean flammeBleue = false;
    private long dernierRappelBleuMs = 0L;

    /** Petite bulle sous la petite flamme : « Youhoo, attention ! » quand elle danse, message de support quand elle est bleue. */
    private void montrerBulleFlamme(final String texte, final long dureeMs) {
        try {
            if (sMascotte == null || holder == null) return;
            final Context ctx = holder.getContext();
            final float dp = ctx.getResources().getDisplayMetrics().density;
            if (sBulleFlamme == null) {
                sBulleFlamme = new TextView(ctx);
                sBulleFlamme.setTextSize(13f);
                sBulleFlamme.setTextColor(Color.parseColor("#0B2A4A"));
                sBulleFlamme.setPadding((int) (12 * dp), (int) (8 * dp), (int) (12 * dp), (int) (8 * dp));
                sBulleFlamme.setMaxWidth((int) (260 * dp));
                android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
                gd.setColor(Color.WHITE);
                gd.setCornerRadius(16 * dp);
                gd.setStroke((int) (2 * dp), Color.parseColor("#185FA5"));
                sBulleFlamme.setBackground(gd);
                sBulleFlamme.setElevation(12 * dp);
                sBulleFlamme.setVisibility(View.GONE);
                holder.addView(sBulleFlamme, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
            }
            sBulleFlamme.setText(texte);
            sBulleFlamme.setVisibility(View.INVISIBLE);
            sBulleFlamme.bringToFront();
            sBulleFlamme.post(() -> {
                try {
                    int[] f = new int[2], h = new int[2];
                    sMascotte.getLocationOnScreen(f);
                    holder.getLocationOnScreen(h);
                    float x = f[0] - h[0] + sMascotte.getWidth() - sBulleFlamme.getWidth();
                    float y = f[1] - h[1] + sMascotte.getHeight() + 4 * dp;
                    sBulleFlamme.setTranslationX(Math.max(4 * dp, x));
                    sBulleFlamme.setTranslationY(y);
                    sBulleFlamme.setVisibility(View.VISIBLE);
                } catch (Throwable ignored) {}
            });
            ui.removeCallbacks(cacherBulleFlamme);
            ui.postDelayed(cacherBulleFlamme, dureeMs);
        } catch (Throwable t) { Log.w(TAG, "bulle flamme ERR: " + t.getMessage()); }
    }

    /** Flamme bleue : rappel de la demande de support (à l'apparition, puis au plus une fois par minute). */
    private void rappelBulleBleue() {
        if (!flammeBleue) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (dernierRappelBleuMs != 0L && now - dernierRappelBleuMs < 60_000L) return;
        dernierRappelBleuMs = now;
        montrerBulleFlamme("Demande de support : une réponse est arrivée — touchez la flamme", 8000L);
    }

    private void balancerTrombone() {
        if (sMascotte == null || !ValueAnimator.areAnimatorsEnabled()) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - dernierBalanceMs < TROMBONE_PERIODE_MS) return;
        dernierBalanceMs = now;
        sMascotte.setPivotX(sMascotte.getWidth() / 2f);
        sMascotte.setPivotY(sMascotte.getHeight() * 0.9f);
        ObjectAnimator a = ObjectAnimator.ofFloat(sMascotte, View.ROTATION, 0f, -16f, 14f, -10f, 8f, -4f, 0f);
        a.setDuration(900);
        a.start();
        montrerBulleFlamme("Youhoo, attention !", 4000L);
    }

    private void rebondTrombone() {
        if (sMascotte == null || !ValueAnimator.areAnimatorsEnabled()) return;
        ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(sMascotte,
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
        // ✅ 11 nov 2026 (demande Paul) — texte LIVE périmé après une reconnexion : on se fie à la session vivante.
        if (live.contains("DISCONNECTED") || live.contains("(OFF)") || live.contains("COUPÉ")) {
            if (host.sessionVivantePourSimplifie()) {
                host.corrigerLivePerimePourSimplifie();
                live = txt(txtLive).toUpperCase(Locale.ROOT);
            }
        }
        double net = nombre(txtQtyNet);
        double gross = nombre(txtQtyGross);
        Etat etat = calculerEtat(live, net, gross);

        // 12 nov 2026 (demande Paul — « l'écran simplifié suit le LIVE du tab ») : le tab décide de la liaison pendant une
        // livraison et l'écrit dans son LIVE (voir RegisterTabFragment.evaluerLiaisonLive). Ici on ne fait que traduire :
        // en cas de perte, le bandeau garde le dernier état de livraison vu et la carte « Arrêtez le débit » s'affiche.
        int casLiaison = host.etatLiaisonPourSimplifie();
        long maintenantS = android.os.SystemClock.elapsedRealtime();
        if (casLiaison != 0) {
            etat = dernEtatLivr != null ? dernEtatLivr : Etat.DEBIT;
        } else if (etat == Etat.DEBIT || etat == Etat.PAUSE || etat == Etat.CONFIRM || etat == Etat.ENDING) {
            dernEtatLivr = etat; dernEtatLivrMs = maintenantS;
        } else if (etat == Etat.PRET || etat == Etat.ATTENTE || etat == Etat.TICKET || etat == Etat.FIN) {
            dernEtatLivr = null;
        }

        // --- Registre
        String serie = apresDeuxPoints(txtSerialId);
        String node = apresDeuxPoints(txtLcrNode);
        boolean plusieurs = nbRegistres() > 1;
        sTxtRegistre.setText("Registre #" + serie + " · Node " + node + (plusieurs ? "  ▾" : ""));
        sTxtRegistre.setClickable(plusieurs);
        sTxtRegistre.setPadding(0, plusieurs ? 8 : 0, 0, plusieurs ? 8 : 0);
        // ✅ FIX (11 nov 2026, demande Paul — « l'interface normale indique (OFF) — reconnect requis, pourquoi pas la
        // simplifiée ? ») — l'onglet normal écrit « LIVE: BT(OFF) — reconnect requis » quand le média est coupé, sans jamais
        // le mot DISCONNECTED : l'écran simplifié ne cherchait que ce mot, donc restait sur « Connexion en cours… ».
        // Mêmes indices que l'onglet normal (voir RegisterTabFragment, test « DISCONNECTED / (OFF) / COUPÉ »).
        boolean coupe = live.contains("DISCONNECTED") || live.contains("(OFF)") || live.contains("COUPÉ")
                || live.toLowerCase(Locale.ROOT).contains("reconnect requis");
        if (etat == Etat.CONNEXION) {
            long maintenantC = android.os.SystemClock.elapsedRealtime();
            if (connexionDepuisMs == 0L) {
                connexionDepuisMs = maintenantC;
            } else if (!connexionLongueSignalee && maintenantC - connexionDepuisMs > CONNEXION_LONGUE_MS && !adminUnlocked) {
                connexionLongueSignalee = true;
                if (!echecForce) {
                    echecForce = true;
                    if (causeDiag == null || causeDiag.isEmpty()) {
                        causeDiag = "Le registre ne répond pas pour le moment. Vérifiez qu’il est sous tension et que son Bluetooth est actif ; "
                            + "la reconnexion est en cours — vous pouvez aussi toucher Reconnecter.";
                    }
                    diagEchecTexte = "Connexion au registre sans réponse depuis plus de " + (CONNEXION_LONGUE_MS / 1000) + " s (écran simplifié)";
                    snoozeCle = ""; snoozeJusquaMs = 0L; oublieErreur = false;
                    triageCle = null; triageAction = null; triageNiveau = null; triageDiag = null;
                }
                try { com.pa.lcr.lcp.log.LogBus.api(0, "[CONNEXION-LONGUE] « Connexion au registre… » depuis plus de "
                    + (CONNEXION_LONGUE_MS / 1000) + " s — alerte de la flamme + reconnexion/diagnostic"); } catch (Throwable ignoredLb) {}
                // Règle Paul (12 nov 2026) : rien d'automatique ne touche au lien pendant une livraison (RUNNING_FLOWING/PAUSED).
                if (!RegisterConnectionHelper.isDiagnosticEnCours()
                        && !com.pa.lcr.lcp.MultiRegisterApiFacadeImpl.isConnectAutoEnCours()
                        && !host.livraisonActivePourSimplifie()) {
                    try { host.reconnectFromDialog(); } catch (Throwable t) { Log.w(TAG, "reconnexion auto ERR: " + t.getMessage()); }
                }
            }
        } else {
            connexionDepuisMs = 0L; connexionLongueSignalee = false;
        }
        // Trombone : se balance de temps en temps si le registre est déconnecté OU si le service
        // d'impression (127.0.0.1:9182) ne répond pas. Immobile sinon.
        sonderServiceImpression();
        rappelBulleBleue();
        // Registre rétabli / livraison avancée : on oublie les échecs de détection du deeplink
        // (pas pendant une perte de liaison en livraison : le bandeau garde alors l'état de livraison vu, et l'échec du
        // diagnostic de reconnexion doit rester affiché jusqu'au retour réel du registre)
        if (casLiaison == 0 && (etat == Etat.PRET || etat == Etat.DEBIT || etat == Etat.CONFIRM || etat == Etat.PAUSE
                || etat == Etat.ENDING || etat == Etat.TICKET || etat == Etat.FIN)) {
            SimplifiedUiState.reinitialiserEchecsDeepLink();
            echecForce = false; causeDiag = "";
        }
        boolean echecDeepLink = !coupe
                && (echecForce || SimplifiedUiState.getEchecsDeepLink(woCourant()) >= SimplifiedConfig.DEEPLINK_ECHECS_AVANT_ALERTE);
        // Perte de liaison PENDANT une livraison : le texte LIVE reste figé sur RUNNING_FLOWING, donc ni « DISCONNECTED » ni
        // « (OFF) » n'apparaissent. On regarde le lien lui-même (2 s de confirmation) ; la carte disparaît dès qu'il revient.
        boolean perte = casLiaison != 0;
        if (perte != perteEnLivraison) {
            try { com.pa.lcr.lcp.log.LogBus.api(0, perte
                ? "[PERTE-EN-LIVRAISON] liaison BT/registre perdue pendant la livraison — consigne affichée : arrêter le débit et vérifier le Bluetooth"
                : "[PERTE-EN-LIVRAISON] liaison rétablie — la livraison reprend à l'état réel du registre, consigne retirée"); } catch (Throwable ignoredLb) {}
            if (perte) { snoozeCle = ""; snoozeJusquaMs = 0L; oublieErreur = false; }
        }
        perteCas = perte ? casLiaison : 0;
        perteEnLivraison = perte;
        // Flamme + toc-toc : registre/BT déconnecté (hors livraison) OU perte de liaison pendant la livraison.
        if ((coupe || echecDeepLink || perte) && !oublieErreur) balancerTrombone();
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

    /** Symbole power simplifié : cercle, arc ouvert en haut et barre verticale du haut jusqu'au centre. */
    private static final class PowerIconDrawable extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final float dp;
        PowerIconDrawable(float density) {
            dp = density;
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
        }
        @Override public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            float cx = b.exactCenterX(), cy = b.exactCenterY();
            float r = Math.min(b.width(), b.height()) / 2f - 1.5f * dp;
            p.setColor(Color.parseColor("#80FFFFFF"));
            p.setStrokeWidth(1.5f * dp);
            c.drawCircle(cx, cy, r, p);
            float g = r * 0.46f;
            p.setColor(Color.WHITE);
            p.setStrokeWidth(2.4f * dp);
            // arc ouvert en haut : angles Android (sens horaire depuis 3 h), l'ouverture est centrée sur 270°
            c.drawArc(new android.graphics.RectF(cx - g, cy - g, cx + g, cy + g), 305f, 290f, false, p);
            c.drawLine(cx, cy - g * 1.18f, cx, cy, p);
        }
        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(android.graphics.ColorFilter f) { p.setColorFilter(f); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
}
