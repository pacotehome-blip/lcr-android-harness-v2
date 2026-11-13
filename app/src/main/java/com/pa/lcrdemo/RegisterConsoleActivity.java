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
    // 4 nov 2026 : issu de l'Excel LCR_Registers_Fields.xlsx. Une ligne = {nom, description, niveau, typeInterne, typeSdk, valeurs, utilisePar}.
    private final Map<Integer, String[]> champs = new HashMap<>();
    // type de donnée (A, AZ, LL, UB, ...) -> {nom, description}
    private final Map<String, String[]> types = new HashMap<>();

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
                    ajouter("Tape 'aide' pour la liste des commandes. Seule la commande « recuperer » agit sur le registre (imprime puis, au besoin, abort/fin).");
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
                || commande.equals("livraison") || commande.equals("delivery")
                || commande.equals("recuperer") || commande.equals("recup") || commande.equals("recover")) {
            champ = -1;
            if (commande.startsWith("recup") || commande.equals("recover")) {
                // Jamais pendant une livraison active (règle : rien d'automatique/manuel sur le lien pendant le débit)
                try {
                    com.pa.lcr.lcp.RegisterSessionManager sm = com.pa.lcr.lcp.RegisterSessionManager.get(this);
                    if (transportKey != null && sm.hasRunningDeliveryOn(transportKey)) {
                        ajouter("Refusé : une livraison est en cours sur ce registre (RUNNING). Récupération impossible pendant le débit.");
                        try { LogBus.api(node, "[RECUP-REGISTRE] REFUSÉ — livraison active sur " + transportKey); } catch (Exception ignored) {}
                        return;
                    }
                } catch (Exception ignored) {}
            }
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
                        out.append(String.format(Locale.ROOT, "rc=0x%02X\n", ms.rc));
                        out.append(String.format(Locale.ROOT, "devStatus=0x%02X : %s\n", ms.devStatus,
                            LcpLink.decodeDeviceStatus(ms.devStatus)));
                        StringBuilder pb = new StringBuilder();
                        if ((ms.prnStatus & 0x80) != 0) pb.append("occupée ");
                        if ((ms.prnStatus & 0x40) != 0) pb.append("erreur ");
                        if ((ms.prnStatus & 0x20) != 0) pb.append("hors ligne ");
                        if ((ms.prnStatus & 0x10) != 0) pb.append("plus de papier ");
                        out.append(String.format(Locale.ROOT, "prnStatus=0x%02X : %s(bits lus comme dans le test imprimante de l'app)\n",
                            ms.prnStatus, pb.length() == 0 ? "aucun bit actif " : pb.toString()));
                        out.append(String.format(Locale.ROOT, "delStatus=0x%04X (%s)\n", ms.delStatus, LcpLink.describeDelStatus(ms.delStatus)));
                        out.append(String.format(Locale.ROOT, "delCode=0x%04X (%s)\n", ms.delCode, LcpLink.describeDelCode(ms.delCode)));
                        break;
                    }
                    case "recuperer": case "recup": case "recover": {
                        recuperer(lk, out);
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


    // ───────────── récupération du registre (13 nov 2026) ─────────────
    private void rl(StringBuilder out, String m) {
        out.append(m).append('\n');
        try { LogBus.api(node, "[RECUP-REGISTRE] " + m + " (#série=" + serial + ")"); } catch (Exception ignored) {}
    }

    private String etat(LcpLink lk, StringBuilder out, String quand) {
        try {
            int[] ds = lk.opDeliveryStatus(3000);
            String r = String.format(Locale.ROOT, "%s : delStatus=0x%04X (%s) delCode=0x%04X (%s)", quand,
                ds[0], LcpLink.describeDelStatus(ds[0]), ds[1], LcpLink.describeDelCode(ds[1]));
            rl(out, r);
            return String.format(Locale.ROOT, "%04X/%04X", ds[0], ds[1]);
        } catch (Exception e) {
            rl(out, quand + " : lecture état échouée — " + e.getMessage());
            return "?";
        }
    }

    private static int be32(byte[] b) {
        if (b == null) return 0;
        int v = 0;
        for (int i = 0; i < b.length && i < 4; i++) v = (v << 8) | (b[i] & 0xFF);
        return v;
    }

    private static String prnTexte(int prn) {
        StringBuilder b = new StringBuilder();
        if ((prn & 0x80) != 0) b.append("occupée ");
        if ((prn & 0x40) != 0) b.append("ERREUR ");
        if ((prn & 0x20) != 0) b.append("HORS LIGNE ");
        if ((prn & 0x10) != 0) b.append("PLUS DE PAPIER ");
        return b.length() == 0 ? "aucun bit actif" : b.toString().trim();
    }

    private static final int PATIENCE_MS = 45000;

    /** Récupération (13 nov 2026). Cause trouvée : imprimante du registre (TM-T88VII) en erreur →
     *  le registre garde les requêtes en file. Étapes : état livraison, état machine (patient 45 s),
     *  lecture #22 + net/gross (même 0), impression CMD #6 seulement si l'imprimante n'est pas en erreur,
     *  abort + CMD_END seulement si un vrai état bloqué reste (0x0480/0x03A0 = repos normal, on n'y touche pas). */
    private void recuperer(LcpLink lk, StringBuilder out) throws Exception {
        rl(out, "=== DÉBUT récupération registre " + serial + " (attente max " + (PATIENCE_MS / 1000) + " s par lecture) ===");
        String avant = etat(lk, out, "AVANT");

        // 1) état machine, patient
        LcpLink.MachineStatus ms = null;
        long t0 = System.currentTimeMillis();
        try {
            ms = lk.opGetMachineStatusPatient(PATIENCE_MS);
            rl(out, String.format(Locale.ROOT, "AVANT machine (+%d ms) : rc=0x%02X dev=0x%02X [%s] prn=0x%02X [%s] delStatus=0x%04X delCode=0x%04X",
                System.currentTimeMillis() - t0, ms.rc, ms.devStatus, LcpLink.decodeDeviceStatus(ms.devStatus),
                ms.prnStatus, prnTexte(ms.prnStatus), ms.delStatus, ms.delCode));
        } catch (Exception e) {
            rl(out, "AVANT machine : AUCUNE réponse en " + (PATIENCE_MS / 1000) + " s — " + e.getMessage()
                + ". REGISTRE FIGÉ : vérifier l'imprimante (TM-T88VII) : éteindre/débrancher, puis relancer. Aucune commande envoyée.");
            rl(out, "=== FIN (arrêt sans écriture) — état livraison avant=" + avant + " ===");
            return;
        }
        boolean prnErreur = (ms.prnStatus & 0x70) != 0; // erreur / hors ligne / papier
        if (prnErreur) rl(out, "⚠ IMPRIMANTE EN ÉTAT D'ANOMALIE (" + prnTexte(ms.prnStatus) + ") — cause probable du blocage de la file");

        // 2) lecture #22, net, gross — même à 0
        long sale = -1; double net = Double.NaN, gross = Double.NaN;
        try {
            sale = be32(lk.opGetFieldPatient(22, PATIENCE_MS)) & 0xFFFFFFFFL;
            int dig = lk.getDecimalDigits();
            double sc = Math.pow(10, dig);
            gross = be32(lk.opGetFieldPatient(44, PATIENCE_MS)) / sc;
            net = be32(lk.opGetFieldPatient(45, PATIENCE_MS)) / sc;
            rl(out, String.format(Locale.ROOT, "LECTURE : sale_number(#22)=%d net=%.*f gross=%.*f (décimales=%d)%s",
                sale, dig, net, dig, gross, dig, (net == 0 && gross == 0) ? "  ⚠ 0 L — conservé, à signaler" : ""));
        } catch (Exception e) { rl(out, "LECTURE : échec " + e.getMessage()); }

        // 3) impression (CMD #6) — seulement si l'imprimante n'est pas en anomalie
        if (prnErreur) {
            rl(out, "CMD #6 NON envoyée : imprimante en anomalie (envoyer une impression aggraverait le blocage). Corrige l'imprimante puis relance.");
        } else {
            try { lk.opIssueCommand(0x06); rl(out, "CMD #6 (print last ticket) acceptée (rc=0x00)"); }
            catch (Exception e) { rl(out, "CMD #6 : échec " + e.getMessage()); }
            Thread.sleep(2500);
        }
        String apres = etat(lk, out, "APRÈS PRINT");

        // 4) abort + fin seulement si l'état n'est PAS le repos normal 0480/03A0
        boolean reposNormal = "0480/03A0".equals(apres);
        boolean bloque = true;
        try {
            String[] pp = apres.split("/");
            int dsv = Integer.parseInt(pp[0], 16), dcv = Integer.parseInt(pp[1], 16);
            bloque = !reposNormal && ((dsv & 0x0600) != 0 || (dcv & 0x0009) != 0);
        } catch (Exception ignored) {}
        if (!bloque) {
            rl(out, reposNormal ? "État de repos normal (0x0480/0x03A0) — abort/fin NON envoyés."
                                : "Aucun état bloqué — abort/fin NON envoyés.");
        } else {
            try {
                int rc = lk.opAbortRequest();
                rl(out, String.format(Locale.ROOT, "ABORT REQUEST (0x7E) rc=0x%02X (0x28 annulée, 0x29 en cours, 0x2A trop tard, 0x27 aucune)", rc));
            } catch (Exception e) { rl(out, "ABORT : échec " + e.getMessage()); }
            Thread.sleep(1500);
            try { lk.opIssueCommand(0x02); rl(out, "CMD_END (0x02) acceptée"); }
            catch (Exception e) { rl(out, "CMD_END : échec " + e.getMessage()); }
            Thread.sleep(1500);
            etat(lk, out, "APRÈS ABORT+FIN");
        }
        try {
            LcpLink.MachineStatus m2 = lk.opGetMachineStatusPatient(PATIENCE_MS);
            rl(out, String.format(Locale.ROOT, "APRÈS machine : rc=0x%02X dev=0x%02X [%s] prn=0x%02X [%s] delStatus=0x%04X delCode=0x%04X",
                m2.rc, m2.devStatus, LcpLink.decodeDeviceStatus(m2.devStatus), m2.prnStatus, prnTexte(m2.prnStatus), m2.delStatus, m2.delCode));
        } catch (Exception e) { rl(out, "APRÈS machine : échec " + e.getMessage()); }
        rl(out, String.format(Locale.ROOT, "=== FIN — ticket #%d net=%s gross=%s (avant=%s) ===", sale, net, gross, avant));
    }

    // ───────────── table ressource des champs ─────────────
    private void chargerChamps() {
        try {
            for (String[] r : lireCsv(R.raw.lcp_champs)) {
                if (r.length < 8) continue;
                try { champs.put(Integer.parseInt(r[0].trim()), new String[]{r[1], r[2], r[3], r[4], r[5], r[6], r[7]}); }
                catch (NumberFormatException ignored) {}
            }
            for (String[] r : lireCsv(R.raw.lcp_types)) {
                if (r.length >= 3) types.put(r[0].trim(), new String[]{r[1], r[2]});
            }
        } catch (Exception e) {
            ajouter("Table des champs illisible : " + e.getMessage());
        }
    }

    /** Lit un CSV entre guillemets (RFC 4180 : guillemets doublés, retours de ligne permis dans un champ). Saute l'en-tête. */
    private java.util.List<String[]> lireCsv(int resId) throws java.io.IOException {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        try (InputStream in = getResources().openRawResource(resId);
             BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder cur = new StringBuilder();
            java.util.List<String> ligne = new java.util.ArrayList<>();
            boolean guill = false;
            int ch;
            while ((ch = br.read()) != -1) {
                char c = (char) ch;
                if (guill) {
                    if (c == '"') {
                        br.mark(1);
                        int nx = br.read();
                        if (nx == '"') cur.append('"');
                        else { guill = false; if (nx != -1) br.reset(); }
                    } else cur.append(c);
                } else if (c == '"') guill = true;
                else if (c == ',') { ligne.add(cur.toString()); cur.setLength(0); }
                else if (c == '\n') {
                    ligne.add(cur.toString()); cur.setLength(0);
                    out.add(ligne.toArray(new String[0])); ligne = new java.util.ArrayList<>();
                } else if (c != '\r') cur.append(c);
            }
            if (cur.length() > 0 || !ligne.isEmpty()) { ligne.add(cur.toString()); out.add(ligne.toArray(new String[0])); }
        }
        if (!out.isEmpty()) out.remove(0); // en-tête
        return out;
    }

    private String descriptionChamp(int champ) {
        String[] d = champs.get(champ);
        if (d == null) return "Champ " + champ + " : absent de la table de l'Excel (res/raw/lcp_champs.csv).";
        StringBuilder sb = new StringBuilder();
        sb.append("Champ ").append(champ).append(" — ").append(d[0]).append('\n');
        sb.append("  Niveau de sécurité : ").append(d[2]).append('\n');
        String t = d[4].isEmpty() ? d[3] : d[4];
        String[] tn = types.get(t);
        sb.append("  Type : ").append(t);
        if (tn != null) sb.append(" — ").append(tn[0]).append(" (").append(tn[1]).append(')');
        if (!d[3].isEmpty() && !d[3].equals(d[4])) sb.append(" [type interne ").append(d[3]).append(']');
        sb.append('\n');
        sb.append("  Utilisé par : ").append(d[6]).append('\n');
        sb.append("  Description : ").append(d[1]).append('\n');
        if (!d[5].isEmpty()) sb.append("  Valeurs : ").append(d[5].replace("\n", "\n    ")).append('\n');
        return sb.toString();
    }

    /** Taille en octets attendue pour un type de largeur fixe ; -1 si variable ou inconnue. */
    private static int tailleType(String t) {
        switch (t) {
            case "LL": case "UB": case "SB": case "B": return 1;
            case "SS": case "US": return 2;
            case "F": case "LF": case "LM": case "LV": case "SL": case "UL": return 4;
            case "D": return 8;
            default: return -1;
        }
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

    private String decrire(int champ, byte[] v) {
        StringBuilder sb = new StringBuilder();
        sb.append("Données reçues : ").append(hex(v)).append('\n');
        if (v == null || v.length == 0) return sb.toString();
        String[] d = champs.get(champ);
        String t = d == null ? "" : (d[4].isEmpty() ? d[3] : d[4]);
        int attendu = tailleType(t);
        if (attendu > 0 && v.length != attendu) {
            sb.append("  ⚠ taille reçue : ").append(v.length).append(" octet(s), le type ").append(t)
              .append(" en attend ").append(attendu).append(" d'après l'Excel\n");
        }
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(v).order(java.nio.ByteOrder.BIG_ENDIAN);
        Long entier = null;
        switch (t) {
            case "SB": if (v.length >= 1) entier = (long) v[0]; break;
            case "SS": if (v.length >= 2) entier = (long) bb.getShort(); break;
            case "SL": case "LV": case "LF": case "LM": if (v.length >= 4) entier = (long) bb.getInt(); break;
            case "UL": if (v.length >= 4) entier = bb.getInt() & 0xFFFFFFFFL; break;
            case "US": if (v.length >= 2) entier = (long) (bb.getShort() & 0xFFFF); break;
            case "LL": case "UB": default:
                if (v.length <= 4) { long x = 0; for (byte b : v) x = (x << 8) | (b & 0xFF); entier = x; }
        }
        if (t.equals("F") && v.length >= 4) {
            sb.append("  valeur (flottant) : ").append(bb.getFloat()).append('\n');
        } else if (t.equals("D") && v.length >= 8) {
            sb.append("  valeur (double) : ").append(bb.getDouble()).append('\n');
        } else if (entier != null && !t.equals("AZ") && !t.equals("A")) {
            sb.append("  valeur : ").append(entier);
            if (t.equals("LV") || t.equals("LF") || t.equals("LM"))
                sb.append("  (entier avec décimales implicites : voir le champ 39 « Decimals »)");
            sb.append('\n');
            String lib = libelle(d, entier);
            if (lib != null) sb.append("  signification : ").append(entier).append(" = ").append(lib).append('\n');
        }
        boolean texte = true;
        int fin = v.length;
        while (fin > 0 && v[fin - 1] == 0) fin--;
        for (int i = 0; i < fin; i++) { int c = v[i] & 0xFF; if (c < 0x20 || c > 0x7E) { texte = false; break; } }
        if ((t.equals("AZ") || t.equals("A") || d == null) && texte && fin > 0)
            sb.append("  texte : ").append(new String(v, 0, fin, StandardCharsets.ISO_8859_1)).append('\n');
        return sb.toString();
    }

    /** Cherche « N = "libellé" » dans la colonne Valeurs de l'Excel. */
    private static String libelle(String[] d, long n) {
        if (d == null || d[5] == null) return null;
        for (String ln : d[5].split("\n")) {
            ln = ln.trim();
            int eq = ln.indexOf('=');
            if (eq < 0) continue;
            try {
                if (Long.parseLong(ln.substring(0, eq).trim()) == n) {
                    String r = ln.substring(eq + 1).trim();
                    if (r.length() >= 2 && r.startsWith("\"") && r.endsWith("\"")) r = r.substring(1, r.length() - 1);
                    return r;
                }
            } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private void aide() {
        ajouter("Commandes (lecture seule) :\n"
            + "  get <champ>   lit un champ LCP (GET_FIELD)        ex. get 102\n"
            + "  securite      niveau de sécurité (GET_SECURITY)   (aussi : --show-security)\n"
            + "  status        état machine (GET_MACHINE_STATUS)\n"
            + "  livraison     état de livraison (GET_DELIVERY_STATUS)\n"
            + "  recuperer     lit état machine (attente 45 s), #22 + net/gross, imprime (CMD #6) si l'imprimante est OK, abort + CMD_END seulement si état vraiment bloqué. Si l'imprimante est en erreur : n'envoie rien. Tout va au journal support [RECUP-REGISTRE]. Refusé pendant une livraison.\n"
            + "  effacer       vide l'écran\n"
            + "Les 352 champs de l'Excel LCR_Registers_Fields sont décrits : get <champ> affiche le nom, le niveau de sécurité, "
            + "le type, la description et les valeurs possibles, puis la valeur lue. Aucune écriture.");
    }
}
