package com.pa.lcrdemo;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 3 nov 2026 (demande Paul) — Configurer → changer le node d'un registre LCR-II (champ LCP 102).
 * Écran minimal : affiche le registre de l'onglet actif, demande le nouveau node (1 à 250), confirme,
 * puis délègue à MainActivity.changerNodeRegistre(). Chaque étape est aussi écrite dans Support (LogBus).
 */
public class RegisterNodeActivity extends Activity {

    private TextView tvLog;
    private Button btnGo;
    private EditText etNode;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        final String serial = getIntent().getStringExtra("serial");
        final String transportKey = getIntent().getStringExtra("transportKey");
        final int node = getIntent().getIntExtra("node", -1);
        final boolean isLc3 = getIntent().getBooleanExtra("isLc3", false);

        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView tv = new TextView(this);
        tv.setTextSize(16f);
        tv.setText("Registre #série : " + serial + "\nNode actuel : " + node + "\nTransport : " + transportKey
            + "\n\nChamp LCP 102 (2 octets). Testé au script : l'écriture est acceptée avec le sélecteur en RUN. "
            + "Si le registre refuse (rc=0x23), il est protégé : le code de retour est affiché. Tout est écrit dans Support.");
        root.addView(tv);

        etNode = new EditText(this);
        etNode.setInputType(InputType.TYPE_CLASS_NUMBER);
        etNode.setHint("Nouveau node (1 à 250)");
        root.addView(etNode);

        btnGo = new Button(this);
        btnGo.setText("Changer le node");
        root.addView(btnGo);

        tvLog = new TextView(this);
        tvLog.setTextSize(13f);
        tvLog.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this);
        sv.addView(tvLog);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        if (isLc3) {
            btnGo.setEnabled(false);
            tvLog.setText("Registre LC3 : changement de node non pris en charge ici (champ 102 = LCR-II).");
        }

        btnGo.setOnClickListener(v -> {
            int nn;
            try { nn = Integer.parseInt(etNode.getText().toString().trim()); }
            catch (Exception e) { tvLog.setText("Node invalide."); return; }
            if (nn < 1 || nn > 250) { tvLog.setText("Le node doit être entre 1 et 250."); return; }
            final int nouveau = nn;
            new AlertDialog.Builder(this)
                .setTitle("Confirmer")
                .setMessage("Changer le node du registre #" + serial + " de " + node + " à " + nouveau + " ?\n\n"
                    + "Le registre ne répondra plus au node " + node + ".")
                .setNegativeButton("Annuler", null)
                .setPositiveButton("Changer", (d, w) -> lancer(transportKey, node, serial, nouveau))
                .show();
        });
    }

    private void lancer(String transportKey, int ancien, String serial, int nouveau) {
        MainActivity m = MainActivity.ACTIVE_INSTANCE;
        if (m == null) {
            tvLog.setText("⚠ MainActivity non disponible — retourne à l'app et réessaie.");
            return;
        }
        btnGo.setEnabled(false);
        tvLog.setText("");
        m.changerNodeRegistre(transportKey, ancien, serial, nouveau, new MainActivity.NodeChangeListener() {
            @Override public void onStep(String m) { tvLog.append(m + "\n"); }
            @Override public void onDone(boolean ok, String m) {
                tvLog.append("\n" + (ok ? "✅ " : "❌ ") + m + "\n");
                btnGo.setEnabled(!ok);
            }
        });
    }
}
