package com.pa.lcrdemo;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.pa.lcr.lcp.LcpLink;
import com.pa.lcr.lcp.LcpNodeLocks;
import com.pa.lcr.lcp.log.LogBus;
import com.pa.lcr.lcp.transport.TransportIo;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 4 nov 2026 (demande Paul) — console registre, LECTURE SEULE. Reprend ce que fait le script
 * lcr_set_field.py du poste de travail (--get N, --show-security) : on tape la commande, on voit les trames
 * envoyées/reçues et le résultat, sur le registre de l'onglet actif (même transport, même node).
 * Aucune écriture, aucune commande d'impression, aucun mot de passe pour le moment (demande de Paul).
 */
public class RegisterConsoleActivity extends Activity {

    private TextView tvOut;
    private ScrollView sv;
    private EditText etCmd;
    private Button btnRun;
    private String transportKey;
    private int node;
    private String serial;
    private boolean isLc3;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        final String[] serials = getIntent().getStringArrayExtra("serials");
        final String[] transports = getIntent().getStringArrayExtra("transports");
        final int[] nodes = getIntent().getIntArrayExtra("nodes");
        final boolean[] lc3s = getIntent().getBooleanArrayExtra("lc3s");
        int sel = getIntent().getIntExtra("selected", 0);
        if (serials == null || transports == null || nodes == null || lc3s == null || serials.length == 0) {
            finish();
            return;
        }
        if (sel < 0 || sel >= serials.length) sel = 0;

        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView head = new TextView(this);
        head.setTextSize(14f);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        head.setText("Console registre (lecture seule) — choisis le registre :");
        root.addView(head);

        final String[] labels = new String[serials.length];
        for (int i = 0; i < serials.length; i++) {
            labels[i] = "#série " + serials[i] + " — node " + nodes[i] + " — " + transports[i] + (lc3s[i] ? " — LC3" : "");
        }
        final Spinner spin = new Spinner(this);
        spin.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        spin.setSelection(sel);
        root.addView(spin);

        sv = new ScrollView(this);
        tvOut = new TextView(this);
        tvOut.setTypeface(Typeface.MONOSPACE);
        tvOut.setTextSize(12f);
        tvOut.setTextIsSelectable(true);
        sv.addView(tvOut);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        etCmd = new EditText(this);
        etCmd.setHint("ex. get 102    (aide = liste des commandes)");
        etCmd.setSingleLine(true);
        etCmd.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        etCmd.setImeOptions(EditorInfo.IME_ACTION_DONE);
        row.addView(etCmd, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        btnRun = new Button(this);
        btnRun.setText("Exécuter");
        row.addView(btnRun);
        root.addView(row);
        setContentView(root);

        etCmd.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE
                    || (ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN)) {
                executer();
                return true;
            }
            return false;
        });
        btnRun.setOnClickListener(v -> executer());

        spin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, android.view.View view, int pos, long id) {
                serial = serials[pos];
                transportKey = transports[pos];
                node = nodes[pos];
                isLc3 = lc3s[pos];
                ajouter("— Registre sélectionné : " + labels[pos]);
                if (isLc3) {
                    btnRun.setEnabled(false);
                    ajouter("Registre LC3 : cette console parle le protocole LCR-II (LCP) et n'est pas disponible pour un LC3.");
                } else {
                    btnRun.setEnabled(true);
                    ajouter("Tape 'aide' pour la liste des commandes. Rien n'est écrit dans le registre.");
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void ajouter(String s) {
        tvOut.append(s + "\n");
        sv.post(() -> sv.fullScroll(android.view.View.FOCUS_DOWN));
    }

    private void executer() {
        final String ligne = etCmd.getText().toString().trim();
        if (ligne.isEmpty()) return;
        etCmd.setText("");
        ajouter("> " + ligne);

        String[] tok = ligne.split("\\s+");
        String cmd = tok[0].toLowerCase(Locale.ROOT);
        while (cmd.startsWith("-")) cmd = cmd.substring(1); // accepte le style du script : --get 102

        if (cmd.equals("aide") || cmd.equals("help") || cmd.equals("?")) { aide(); return; }
        if (cmd.equals("clear") || cmd.equals("effacer")) { tvOut.setText(""); return; }

        final String commande = cmd;
        final int champ;
        if (commande.equals("get")) {
            if (tok.length < 2) { ajouter("Usage : get <champ>   ex. get 102"); return; }
            int n;
            try { n = Integer.decode(tok[1]); } catch (Exception e) { ajouter("Champ invalide : " + tok[1]); return; }
            if (n < 0 || n > 255) { ajouter("Le champ doit être entre 0 et 255."); return; }
            champ = n;
        } else if (commande.equals("securite") || commande.equals("security") || commande.equals("show-security")
                || commande.equals("status") || commande.equals("statut")
                || commande.equals("livraison") || commande.equals("delivery")) {
            champ = -1;
        } else {
            ajouter("Commande inconnue : " + tok[0] + "  (tape 'aide')");
            return;
        }

        MainActivity m = MainActivity.ACTIVE_INSTANCE;
        final TransportIo io = (m != null && m.getMediaTransportManager() != null && transportKey != null)
            ? m.getMediaTransportManager().getByKey(transportKey) : null;
        if (io == null || !io.isOpen()) { ajouter("Transport non ouvert (" + transportKey + ")."); return; }

        btnRun.setEnabled(false);
        new Thread(() -> {
            final StringBuilder out = new StringBuilder();
            ReentrantLock lock = null;
            try {
                lock = LcpNodeLocks.tryAcquire(node, LcpNodeLocks.LOCK_TIMEOUT_MS);
                if (lock == null) { out.append("Verrou LCP indisponible (registre occupé).\n"); return; }
                LcpLink lk = new LcpLink(io, node, 255, true);
                lk.setTraceSink(line -> {
                    if (line.startsWith("TX:") || line.startsWith("RX:")) out.append(line).append('\n');
                });
                try { LogBus.api(node, "[CONSOLE] " + ligne + " (#série=" + serial + ")"); } catch (Exception ignored) {}
                switch (commande) {
                    case "get": {
                        byte[] v = lk.opGetField(champ, 3000);
                        out.append(decrire(champ, v));
                        break;
                    }
                    case "securite": case "security": case "show-security": {
                        byte[] v = lk.opGetSecurity();
                        out.append("Niveau de sécurité (données) : ").append(hex(v)).append('\n');
                        break;
                    }
                    case "status": case "statut": {
                        LcpLink.MachineStatus ms = lk.opGetMachineStatus();
                        out.append(String.valueOf(ms)).append('\n');
                        break;
                    }
                    default: { // livraison / delivery
                        int[] ds = lk.opDeliveryStatus(3000);
                        out.append(String.format(Locale.ROOT, "delStatus=0x%04X (%s)\ndelCode=0x%04X (%s)\n",
                            ds[0], LcpLink.describeDelStatus(ds[0]), ds[1], LcpLink.describeDelCode(ds[1])));
                        break;
                    }
                }
            } catch (Exception e) {
                out.append("ERREUR : ").append(e.getMessage()).append('\n');
                try { LogBus.api(node, "[CONSOLE] erreur : " + e.getMessage()); } catch (Exception ignored) {}
            } finally {
                if (lock != null) LcpNodeLocks.release(lock);
                runOnUiThread(() -> {
                    ajouter(out.toString().trim());
                    btnRun.setEnabled(true);
                });
            }
        }).start();
    }

    private static String hex(byte[] b) {
        if (b == null || b.length == 0) return "(vide)";
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x ", x & 0xFF));
        return sb.toString().trim();
    }

    private static String decrire(int champ, byte[] v) {
        StringBuilder sb = new StringBuilder();
        sb.append("Champ ").append(champ).append(" : données ").append(hex(v)).append('\n');
        if (v == null || v.length == 0) return sb.toString();
        if (v.length <= 4) sb.append("  entier (big-endian) : ").append(LcpLink.bigEndianInt(v)).append('\n');
        if (v.length == 4) {
            Float f = LcpLink.bigEndianFloat(v);
            if (f != null) sb.append("  flottant : ").append(f).append('\n');
        }
        boolean texte = true;
        int fin = v.length;
        while (fin > 0 && v[fin - 1] == 0) fin--;
        for (int i = 0; i < fin; i++) { int c = v[i] & 0xFF; if (c < 0x20 || c > 0x7E) { texte = false; break; } }
        if (texte && fin > 0) sb.append("  texte : ").append(new String(v, 0, fin, StandardCharsets.ISO_8859_1)).append('\n');
        if (champ == 56 && v.length <= 4) sb.append("  type d'imprimante : ").append(LcpLink.printerTypeName(LcpLink.bigEndianInt(v))).append('\n');
        if (champ == 57 && v.length <= 4) sb.append("  compensation : ").append(LcpLink.compensationTypeName(LcpLink.bigEndianInt(v))).append('\n');
        return sb.toString();
    }

    private void aide() {
        ajouter("Commandes (lecture seule) :\n"
            + "  get <champ>   lit un champ LCP (GET_FIELD)        ex. get 102\n"
            + "  securite      niveau de sécurité (GET_SECURITY)   (aussi : --show-security)\n"
            + "  status        état machine (GET_MACHINE_STATUS)\n"
            + "  livraison     état de livraison (GET_DELIVERY_STATUS)\n"
            + "  effacer       vide l'écran\n"
            + "Champs déjà utilisés par l'app ou donnés dans ton Excel : 22 (vente), 23 (ticket), 37 (TicketRequired), "
            + "39 (décimales), 44 (gross), 45 (net), 56 (imprimante), 57-59 (compensation), 60 (logiciel), 80 (#série), 102 (node).\n"
            + "Les autres champs de ton Excel se lisent aussi avec get <champ>. Aucune écriture.");
    }
}
