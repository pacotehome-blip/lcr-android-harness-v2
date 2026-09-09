package com.pa.lcrdemo;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * ✅ AJOUTÉ (9 sept 2026, demande Paul — "un moyen efficace pour tester la
 * connectivité aux registres... écran à part car le scroll du bouton
 * démarrer n'est pas efficace car il est déjà dans un scroll") — remplace
 * la section "Démarrer la validation" du scroll Configure.
 *
 * Garde le même principe que l'ancien flux (montrer tous les médias
 * disponibles, tester en lecture seule, jamais toucher au débit d'un
 * pont BT/TCP — voir commentaire du 20 août dans MainActivity), mais
 * ajoute des cases à cocher par candidat + "Tout cocher/décocher",
 * plutôt que de toujours tout tester automatiquement.
 *
 * Ne duplique AUCUNE logique — passe entièrement par le pont public de
 * MainActivity (ACTIVE_INSTANCE, discoverValidationCandidates(),
 * runValidationOnCandidats()) : même fermeture des connexions, même
 * suppression des tabs, même garde livraison active, même test par
 * candidat déjà en place depuis le 20/28 août.
 */
public class RegisterValidationActivity extends Activity {

    private LinearLayout containerCandidats;
    private LinearLayout containerResultats;
    private Button btnToutCocher;
    private Button btnDemarrer;
    private Button btnAnnuler;
    private TextView txtEtat;
    private final List<CheckBox> cases = new ArrayList<>();
    private final List<String[]> candidats = new ArrayList<>(); // {label, key}, même ordre que `cases`
    private boolean touteCocheState = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = dp(16);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        setContentView(scroll);

        TextView titre = new TextView(this);
        titre.setText("🔬 Validation connectivité registres");
        titre.setTextSize(20);
        titre.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(titre);

        TextView sousTitre = new TextView(this);
        sousTitre.setText("Coche les médias à tester, puis Démarrer. Lecture seule — "
            + "ne touche jamais au débit d'un pont BT/TCP (voir guide support).");
        sousTitre.setTextColor(Color.DKGRAY);
        sousTitre.setPadding(0, dp(4), 0, dp(12));
        root.addView(sousTitre);

        txtEtat = new TextView(this);
        txtEtat.setPadding(0, 0, 0, dp(12));
        root.addView(txtEtat);

        containerCandidats = new LinearLayout(this);
        containerCandidats.setOrientation(LinearLayout.VERTICAL);
        root.addView(containerCandidats);

        btnToutCocher = new Button(this);
        btnToutCocher.setText("Tout décocher");
        btnToutCocher.setOnClickListener(v -> {
            touteCocheState = !touteCocheState;
            for (CheckBox c : cases) c.setChecked(touteCocheState);
            btnToutCocher.setText(touteCocheState ? "Tout décocher" : "Tout cocher");
        });
        root.addView(btnToutCocher);

        LinearLayout ligneBoutons = new LinearLayout(this);
        ligneBoutons.setOrientation(LinearLayout.HORIZONTAL);
        ligneBoutons.setPadding(0, dp(8), 0, dp(8));

        btnDemarrer = new Button(this);
        btnDemarrer.setText("▶ Démarrer");
        btnDemarrer.setOnClickListener(v -> demarrerValidation());
        ligneBoutons.addView(btnDemarrer, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        btnAnnuler = new Button(this);
        btnAnnuler.setText("⛔ Annuler");
        btnAnnuler.setVisibility(View.GONE);
        btnAnnuler.setOnClickListener(v -> {
            MainActivity m = MainActivity.ACTIVE_INSTANCE;
            if (m != null) m.validationExterneAnnulee = true;
        });
        ligneBoutons.addView(btnAnnuler, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        root.addView(ligneBoutons);

        TextView titreResultats = new TextView(this);
        titreResultats.setText("Résultats :");
        titreResultats.setTypeface(null, android.graphics.Typeface.BOLD);
        titreResultats.setPadding(0, dp(12), 0, dp(4));
        root.addView(titreResultats);

        containerResultats = new LinearLayout(this);
        containerResultats.setOrientation(LinearLayout.VERTICAL);
        root.addView(containerResultats);

        // ✅ AJOUTÉ (9 sept 2026, demande Paul — "un bouton retour qui
        // relance la connexion au registre à la fin") — réutilise
        // api_registerConnectAuto() (MultiRegisterApiFacadeImpl), le même
        // point d'entrée unifié déjà utilisé partout ailleurs pour la
        // reconnexion automatique après suppression du dernier tab (voir
        // MainActivity.removeTabAndFragment()) — jamais une nouvelle
        // logique de reconnexion réinventée ici.
        Button btnRetourReconnecter = new Button(this);
        btnRetourReconnecter.setText("🔌 Retour et reconnecter");
        btnRetourReconnecter.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#00695C")));
        btnRetourReconnecter.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams lpRetour = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpRetour.topMargin = dp(20);
        btnRetourReconnecter.setLayoutParams(lpRetour);
        btnRetourReconnecter.setOnClickListener(v -> {
            Toast.makeText(this, "Reconnexion en cours...", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                try {
                    com.pa.lcr.lcp.MultiRegisterApiFacadeImpl facade =
                        new com.pa.lcr.lcp.MultiRegisterApiFacadeImpl(getApplicationContext());
                    com.pa.lcr.lcp.ApiResult r = facade.api_registerConnectAuto(null, null);
                    android.util.Log.i("RegisterValidationActivity", "Retour et reconnecter — code="
                        + (r != null ? r.code : "null") + " msg=" + (r != null ? r.msg : "null"));
                } catch (Exception e) {
                    android.util.Log.w("RegisterValidationActivity", "Retour et reconnecter ERR: " + e.getMessage());
                }
            }).start();
            finish();
        });
        root.addView(btnRetourReconnecter);

        chargerCandidats();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // ✅ Rafraîchit la liste à chaque retour sur cet écran — un
        // appareil BT nouvellement appairé ou un TCP nouvellement ajouté
        // (depuis Configure, avant d'ouvrir cet écran) doit apparaître
        // sans avoir à fermer/rouvrir l'app.
        chargerCandidats();
    }

    private void chargerCandidats() {
        MainActivity m = MainActivity.ACTIVE_INSTANCE;
        containerCandidats.removeAllViews();
        cases.clear();
        candidats.clear();
        if (m == null) {
            txtEtat.setText("⚠ MainActivity non disponible — retourne à l'app et réessaie.");
            btnDemarrer.setEnabled(false);
            return;
        }
        String refus = m.verifierAucuneLivraisonActive();
        if (refus != null) {
            txtEtat.setText(refus);
            btnDemarrer.setEnabled(false);
            return;
        }
        btnDemarrer.setEnabled(true);
        List<String[]> found = m.discoverValidationCandidates();
        candidats.addAll(found);
        for (String[] c : found) {
            CheckBox cb = new CheckBox(this);
            cb.setText(c[0]);
            cb.setChecked(true);
            cases.add(cb);
            containerCandidats.addView(cb);
        }
        touteCocheState = true;
        btnToutCocher.setText("Tout décocher");
        txtEtat.setText(found.size() + " candidat(s) disponible(s).");
    }

    private void demarrerValidation() {
        MainActivity m = MainActivity.ACTIVE_INSTANCE;
        if (m == null) {
            Toast.makeText(this, "MainActivity non disponible", Toast.LENGTH_SHORT).show();
            return;
        }
        String refus = m.verifierAucuneLivraisonActive();
        if (refus != null) {
            txtEtat.setText(refus);
            return;
        }
        List<String[]> selectionnes = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            if (cases.get(i).isChecked()) selectionnes.add(candidats.get(i));
        }
        if (selectionnes.isEmpty()) {
            Toast.makeText(this, "Aucun candidat coché", Toast.LENGTH_SHORT).show();
            return;
        }
        containerResultats.removeAllViews();
        btnDemarrer.setEnabled(false);
        btnAnnuler.setVisibility(View.VISIBLE);
        txtEtat.setText("Validation en cours — " + selectionnes.size() + " candidat(s)...");

        m.runValidationOnCandidats(selectionnes, new MainActivity.ValidationProgressListener() {
            @Override public void onPreparationStep(String message) {
                TextView ligne = new TextView(RegisterValidationActivity.this);
                ligne.setText(message);
                ligne.setPadding(dp(4), dp(4), dp(4), dp(4));
                ligne.setTextColor(Color.parseColor("#00695C"));
                containerResultats.addView(ligne);
            }
            @Override public void onCandidatStart(String label, String candidatKey) {
                TextView ligne = new TextView(RegisterValidationActivity.this);
                ligne.setText(label + " : ⏳ en cours...");
                ligne.setPadding(dp(4), dp(6), dp(4), dp(6));
                ligne.setTag(candidatKey);
                containerResultats.addView(ligne);
            }
            @Override public void onCandidatResult(String label, String candidatKey, String resultat) {
                for (int i = 0; i < containerResultats.getChildCount(); i++) {
                    View v = containerResultats.getChildAt(i);
                    if (v instanceof TextView && candidatKey.equals(v.getTag())) {
                        TextView tv = (TextView) v;
                        tv.setText(label + " :\n" + resultat);
                        if (resultat.startsWith("✅")) tv.setTextColor(Color.parseColor("#1B5E20"));
                        else if (resultat.startsWith("⚠")) tv.setTextColor(Color.parseColor("#E65100"));
                        else tv.setTextColor(Color.parseColor("#B71C1C"));
                        break;
                    }
                }
            }
            @Override public void onDone(boolean annule, String messageFinal) {
                txtEtat.setText(messageFinal);
                btnDemarrer.setEnabled(true);
                btnAnnuler.setVisibility(View.GONE);
            }
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
