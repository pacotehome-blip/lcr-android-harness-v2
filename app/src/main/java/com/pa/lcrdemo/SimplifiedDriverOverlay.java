package com.pa.lcrdemo;

import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Context;
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
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

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
    private MaterialButton sBtnTerminer, sBtnAnnuler, sBtnLancer, sBtnRetour, sBtnSupport, sBtnAdmin;

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

        // Les boutons d'action délèguent aux boutons existants de l'onglet.
        sBtnTerminer.setOnClickListener(v -> cliquer(btnFinish));
        sBtnAnnuler.setOnClickListener(v -> cliquer(btnAnnuler));
        sBtnLancer.setOnClickListener(v -> cliquer(btnRetourWO));
        sBtnRetour.setOnClickListener(v -> cliquer(btnRetourWO));
        sPanelWo.setOnClickListener(v -> cliquer(panelWoCumul));
        sBtnSupport.setOnClickListener(v -> ouvrirSupport());
        sBtnAdmin.setOnClickListener(v -> demanderMotDePasse());
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
        if (admin) { arreterAnimations(); dernierEtat = null; return; }

        String live = txt(txtLive).toUpperCase(Locale.ROOT);
        double net = nombre(txtQtyNet);
        double gross = nombre(txtQtyGross);
        Etat etat = calculerEtat(live, net, gross);

        // --- Registre
        String serie = apresDeuxPoints(txtSerialId);
        String node = apresDeuxPoints(txtLcrNode);
        sTxtRegistre.setText("Registre #" + serie + " · Node " + node);
        boolean coupe = live.contains("DISCONNECTED");
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
        sProgConn.setVisibility(e == Etat.CONNEXION || aMsgInit ? View.VISIBLE : View.GONE);
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
