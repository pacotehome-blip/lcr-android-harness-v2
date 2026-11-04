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

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
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
    // 4 nov 2026 (demande Paul) : table ressource des champs (res/raw/lcp_champs.csv) : champ -> {nom, type, source, description}.
    private final Map<Integer, String[]> champs = new HashMap<>();

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
        chargerChamps();

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
                    if (line.startsWith("TX:") || line.startsWith("RX:")) {
                        out.append(line).append('\n');
                        String it = interpreterTrame(line);
                        if (it != null) out.append("   ").append(it).append('\n');
                    }
                });
                try { LogBus.api(node, "[CONSOLE] " + ligne + " (#série=" + serial + ")"); } catch (Exception ignored) {}
                switch (commande) {
                    case "get": {
                        byte[] v = lk.opGetField(champ, 3000);
                        out.append(descriptionChamp(champ)).append('\n');
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


    // ───────────── table ressource des champs ─────────────
    private void chargerChamps() {
        try (InputStream in = getResources().openRawResource(R.raw.lcp_champs);
             BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String ln;
            boolean premiere = true;
            while ((ln = br.readLine()) != null) {
                if (premiere) { premiere = false; continue; } // en-tête
                if (ln.trim().isEmpty()) continue;
                String[] p = ln.split(";", 5);
                if (p.length < 5) continue;
                try { champs.put(Integer.parseInt(p[0].trim()), new String[]{p[1], p[2], p[3], p[4]}); }
                catch (NumberFormatException ignored) {}
            }
        } catch (Exception e) {
            ajouter("Table des champs illisible : " + e.getMessage());
        }
    }

    private String descriptionChamp(int champ) {
        String[] d = champs.get(champ);
        if (d == null) return "Champ " + champ + " : pas encore décrit dans la table (res/raw/lcp_champs.csv).";
        return "Champ " + champ + " — " + d[0] + " (" + d[1] + ") — " + d[3] + "  [source : " + d[2] + "]";
    }

    // ───────────── interprétation humaine des trames ─────────────
    private static String nomMessage(int m) {
        switch (m) {
            case 0x20: return "GET_FIELD (lire un champ)";
            case 0x21: return "SET_FIELD (écrire un champ)";
            case 0x22: return "PRINT_TEXT (imprimer une ligne)";
            case 0x23: return "GET_MACHINE_STATUS (état machine)";
            case 0x24: return "ISSUE_COMMAND (commande)";
            case 0x27: return "GET_SECURITY (niveau de sécurité)";
            case 0x28: return "GET_DELIVERY_STATUS (état de livraison)";
            case 0x7C: return "SET_BAUD (vitesse)";
            case 0x7D: return "CHECK (reprise d'une requête en file)";
            default: return "message inconnu";
        }
    }

    private static String nomRc(int rc) {
        switch (rc) {
            case 0x00: return "OK";
            case 0x23: return "refusé (champ protégé : sélecteur/niveau de sécurité)";
            case 0x26: return "requête mise en file d'attente";
            case 0x27: return "aucune requête en cours";
            case 0x28: return "requête abandonnée";
            case 0x78: return "état non permis";
            default: return "code non décrit dans l'app";
        }
    }

    private static int crc16(byte[] d, int len) {
        int crc = 0x7E7E;
        for (int k = 0; k < len; k++) {
            int b = d[k] & 0xFF;
            for (int i = 7; i >= 0; i--) {
                boolean x = (crc & 0x8000) != 0;
                crc = ((crc << 1) & 0xFFFF) | ((b >> i) & 1);
                if (x) crc ^= 0x1021;
            }
        }
        return crc;
    }

    /** Traduit une ligne "TX: 7E 7E ..." / "RX: ..." en phrase lisible ; null si la ligne n'est pas une trame. */
    private String interpreterTrame(String line) {
        boolean tx = line.startsWith("TX:");
        String[] tok = line.substring(3).trim().split("\\s+");
        byte[] raw = new byte[tok.length];
        try { for (int i = 0; i < tok.length; i++) raw[i] = (byte) Integer.parseInt(tok[i], 16); }
        catch (NumberFormatException e) { return null; }
        if (raw.length < 8 || raw[0] != 0x7E || raw[1] != 0x7E) return null;
        // retire les échappements (0x1B) après la synchro
        byte[] u = new byte[raw.length];
        int n = 0;
        for (int i = 2; i < raw.length; i++) {
            int b = raw[i] & 0xFF;
            if (b == 0x1B && i + 1 < raw.length) { i++; b = raw[i] & 0xFF; }
            u[n++] = (byte) b;
        }
        if (n < 6) return "trame trop courte";
        int to = u[0] & 0xFF, from = u[1] & 0xFF, st = u[2] & 0xFF, len = u[3] & 0xFF;
        if (n < 4 + len + 2) return "trame incomplète (longueur annoncée " + len + ")";
        int crcLu = (u[4 + len] & 0xFF) | ((u[5 + len] & 0xFF) << 8);
        String crc = (crc16(u, 4 + len) == crcLu) ? "CRC ok" : String.format(Locale.ROOT, "CRC 0x%04X (différent du calcul, non vérifié)", crcLu);
        StringBuilder sb = new StringBuilder();
        sb.append(tx ? "envoi" : "réponse").append(" : vers 0x").append(String.format("%02X", to))
          .append(" de 0x").append(String.format("%02X", from))
          .append(", statut 0x").append(String.format("%02X", st))
          .append(" (séquence ").append(st & 1).append(tx && (st & 2) != 0 ? ", synchro" : "").append("), longueur ").append(len);
        if (len >= 1) {
            int p0 = u[4] & 0xFF;
            if (tx) {
                sb.append(" — ").append(nomMessage(p0));
                if ((p0 == 0x20 || p0 == 0x21) && len >= 2) {
                    int ch = u[5] & 0xFF;
                    String[] d = champs.get(ch);
                    sb.append(" champ ").append(ch).append(d != null ? " « " + d[0] + " »" : "");
                    if (p0 == 0x21 && len > 2) sb.append(", valeur envoyée ").append(len - 2).append(" octet(s)");
                } else if (p0 == 0x24 && len >= 2) {
                    sb.append(" n° ").append(u[5] & 0xFF);
                } else if (p0 == 0x22) {
                    sb.append(", ").append(len - 1).append(" caractère(s)");
                }
            } else {
                sb.append(" — code retour 0x").append(String.format("%02X", p0)).append(" = ").append(nomRc(p0));
                if (len >= 2) {
                    int dev = u[5] & 0xFF;
                    sb.append("; ").append(LcpLink.decodeDeviceStatus(dev).toString());
                }
                if (len > 2) {
                    sb.append("; données ").append(len - 2).append(" octet(s)");
                }
            }
        }
        sb.append(" — ").append(crc);
        return sb.toString();
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
