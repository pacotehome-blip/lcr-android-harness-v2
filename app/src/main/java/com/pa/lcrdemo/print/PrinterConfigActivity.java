package com.pa.lcrdemo.print;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView;

import com.pa.lcr.lcp.log.LogBus;
import com.pa.lcr.lcp.storage.ImprimanteCiterneStore;
import com.pa.lcr.lcp.storage.RegistreStore;
import com.pa.lcrdemo.auth.MsalTokenProvider;
import com.pa.lcrdemo.dataverse.LcrDeliverySync;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * PrinterConfigActivity — Configurer → Imprimantes (29 oct 2026, demande Paul).
 *
 * Même principe que « Changer d'imprimante » du service d'impression : on associe une imprimante
 * (pilote + BT-MAC) à une citerne/camion, puis la ligne est poussée au service d'impression
 * local (PrintServiceClient). Le service apparie lui-même l'imprimante Bluetooth si elle n'est
 * pas déjà appairée. Une citerne peut avoir une imprimante GRAND (PJ-862) et une PETIT : le
 * format est déduit du pilote et affiché dans un champ non modifiable.
 *
 * Citerne : lue dans Dataverse à partir du registre choisi (filgo_actifciterne). S'il n'y en a
 * pas (ex. TEG, sans registre), saisie manuelle — la ligne est alors marquée « saisie manuelle ».
 * Aucune écriture vers Dataverse dans cette version (tables filgo_print_* pas encore créées).
 */
public class PrinterConfigActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private ImprimanteCiterneStore store;
    private RegistreStore registres;
    private LinearLayout listBox;
    private TextView status;
    // Registre de l'onglet actif (passé par MainActivity) : déjà connu, donc pas de choix à faire.
    private String fixSerial = null;
    private int fixNode = -1;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        fixSerial = getIntent().getStringExtra("serial");
        if (fixSerial != null && fixSerial.trim().isEmpty()) fixSerial = null;
        if (fixSerial != null) fixSerial = fixSerial.trim();
        fixNode = getIntent().getIntExtra("node", -1);
        store = new ImprimanteCiterneStore(this);
        registres = new RegistreStore(this);

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14);
        root.setPadding(p, p, p, p);
        scroll.addView(root);
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("🖨 Imprimantes par citerne");
        title.setTextSize(20);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Associe une imprimante (pilote + BT-MAC) à une citerne ou un camion et l'envoie au "
            + "service d'impression. Une citerne peut avoir une imprimante grand format et une petit format.");
        hint.setTextSize(13);
        hint.setPadding(0, dp(4), 0, dp(8));
        root.addView(hint);

        Button add = new Button(this);
        add.setText("➕ Ajouter une imprimante");
        add.setOnClickListener(v -> showEditor(null));
        root.addView(add);

        status = new TextView(this);
        status.setTextSize(13);
        status.setPadding(0, dp(8), 0, dp(4));
        root.addView(status);

        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(listBox);

        Button close = new Button(this);
        close.setText("Fermer");
        close.setOnClickListener(v -> finish());
        root.addView(close);

        refreshList();
    }

    // ── Liste ───────────────────────────────────────────────────

    private void refreshList() {
        listBox.removeAllViews();
        List<ImprimanteCiterneStore.Row> rows = store.listAll();
        if (rows.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("Aucune imprimante configurée.");
            listBox.addView(t);
            return;
        }
        for (ImprimanteCiterneStore.Row r : rows) {
            TextView t = new TextView(this);
            PrinterCatalog.Driver d = PrinterCatalog.find(r.driverType);
            String etat = ImprimanteCiterneStore.SYNC_SENT.equals(r.serviceSync) ? "✅ envoyée au service"
                : ImprimanteCiterneStore.SYNC_ERROR.equals(r.serviceSync) ? "❌ " + nz(r.serviceMsg)
                : "⏳ à envoyer";
            t.setText(r.truckId + "  —  " + PrinterCatalog.formatLabel(r.format)
                + "\n" + (d != null ? d.label : r.driverType) + "  ·  " + nz(r.btMac)
                + (ImprimanteCiterneStore.SRC_MANUEL.equals(r.citerneSource) ? "\n(citerne : saisie manuelle)" : "")
                + "\n" + etat);
            t.setTextSize(14);
            t.setPadding(dp(10), dp(8), dp(10), dp(8));
            t.setBackgroundColor(Color.parseColor("#EEEEEE"));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            t.setLayoutParams(lp);
            t.setOnClickListener(v -> showRowActions(r));
            listBox.addView(t);
        }
    }

    private void showRowActions(ImprimanteCiterneStore.Row r) {
        new AlertDialog.Builder(this)
            .setTitle(r.truckId + " — " + r.format)
            .setItems(new String[]{ "Modifier", "Renvoyer au service", "Supprimer (ici seulement)" }, (dlg, which) -> {
                if (which == 0) showEditor(r);
                else if (which == 1) send(r, false);
                else new AlertDialog.Builder(this)
                    .setMessage("Supprimer cette ligne de Filgo-Registre ?\n(La ligne déjà envoyée reste dans le service d'impression.)")
                    .setPositiveButton("Supprimer", (d2, w2) -> { store.delete(r.id); refreshList(); })
                    .setNegativeButton("Annuler", null).show();
            }).show();
    }

    // ── Éditeur ─────────────────────────────────────────────────

    private void showEditor(final ImprimanteCiterneStore.Row edit) {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14);
        f.setPadding(p, p, p, p);

        // Registre (sert à retrouver la citerne dans Dataverse)
        final List<RegistreStore.Row> regs = registres.listAll();
        final List<String> regLabels = new ArrayList<>();
        regLabels.add("(aucun registre — citerne saisie à la main)");
        for (RegistreStore.Row r : regs) {
            regLabels.add(r.serialId + (r.nom != null && !r.nom.isEmpty() ? " — " + r.nom : "")
                + (r.citerneNom != null && !r.citerneNom.isEmpty() ? "  [" + r.citerneNom + "]" : ""));
        }
        final RegistreStore.Row fixRow = fixSerial != null ? registres.getBySerial(fixSerial) : null;
        final TextView lblReg = label(fixSerial != null ? "Registre de l'onglet actif" : "Registre");
        f.addView(lblReg);
        final Spinner spReg = new Spinner(this);
        spReg.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, regLabels));
        if (fixSerial != null) {
            spReg.setVisibility(View.GONE);
            TextView fixe = new TextView(this);
            fixe.setText(fixSerial + (fixNode >= 0 ? "  (node " + fixNode + ")" : "")
                + (fixRow != null && fixRow.nom != null && !fixRow.nom.isEmpty() ? " — " + fixRow.nom : ""));
            fixe.setTextSize(15);
            fixe.setTypeface(null, android.graphics.Typeface.BOLD);
            f.addView(fixe);
        }
        f.addView(spReg);

        f.addView(label("Citerne / camion"));
        final EditText etTruck = new EditText(this);
        etTruck.setSingleLine(true);
        etTruck.setHint("ex. nom de la citerne");
        f.addView(etTruck);
        final TextView srcInfo = new TextView(this);
        srcInfo.setTextSize(12);
        f.addView(srcInfo);
        final Button btnLire = new Button(this);
        btnLire.setText("Lire la citerne dans Dataverse");
        btnLire.setVisibility(View.GONE);
        f.addView(btnLire);

        f.addView(label("Compagnie (TEG : pas de registre)"));
        final Spinner spCo = new Spinner(this);
        spCo.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, PrinterCatalog.COMPANY_LABELS));
        f.addView(spCo);

        f.addView(label("Pilote d'imprimante"));
        final Spinner spDrv = new Spinner(this);
        spDrv.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, PrinterCatalog.DRIVERS));
        f.addView(spDrv);

        f.addView(label("Format (déduit du pilote)"));
        final EditText etFmt = new EditText(this);
        etFmt.setEnabled(false);
        etFmt.setFocusable(false);
        f.addView(etFmt);

        final TextView lblMac = label("BT-MAC de l'imprimante (ou adresse IP si Wi-Fi)");
        f.addView(lblMac);
        final EditText etMac = new EditText(this);
        etMac.setSingleLine(true);
        etMac.setHint("AA:BB:CC:DD:EE:FF");
        f.addView(etMac);
        final Button btnBonded = new Button(this);
        btnBonded.setText("Choisir parmi les appareils déjà appairés");
        f.addView(btnBonded);

        f.addView(label("Nom de l'imprimante (facultatif)"));
        final EditText etName = new EditText(this);
        etName.setSingleLine(true);
        f.addView(etName);

        // État dynamique : source de la citerne (DATAVERSE/MANUEL) et registre choisi
        final String[] source = { ImprimanteCiterneStore.SRC_MANUEL };
        final String[] regGuid = { null };

        spDrv.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> a, View v, int pos, long id) {
                String dn = PrinterCatalog.DRIVERS[pos].name;
                etFmt.setText(PrinterCatalog.formatLabel(PrinterCatalog.formatFor(dn)));
                // Imprimante branchée au registre (ex. Epson TM-T88VII) : pas de MAC, cible fixe 127.0.0.1.
                String cur = etMac.getText().toString().trim();
                boolean reg = PrinterCatalog.isRegistre(dn);
                if (reg) {
                    etMac.setText(PrinterCatalog.REGISTRE_TARGET);
                } else if (PrinterCatalog.REGISTRE_TARGET.equals(cur)) {
                    etMac.setText("");
                }
                // Registre = HTTP seulement : aucun Bluetooth, on cache le champ MAC et le choix d'appareil appairé.
                lblMac.setText(reg ? "Cible : relais HTTP du registre (aucun Bluetooth)"
                                   : "BT-MAC de l'imprimante (ou adresse IP si Wi-Fi)");
                etMac.setVisibility(reg ? View.GONE : View.VISIBLE);
                btnBonded.setVisibility(reg ? View.GONE : View.VISIBLE);
            }
            @Override public void onNothingSelected(AdapterView<?> a) {}
        });

        final boolean[] premierAppel = { edit != null }; // en modification, ne pas écraser la citerne enregistrée
        spReg.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> a, View v, int pos, long id) {
                if (fixSerial != null) return; // registre imposé par l'onglet actif
                if (premierAppel[0]) {
                    premierAppel[0] = false;
                    if (pos > 0) {
                        regGuid[0] = regs.get(pos - 1).dataverseId;
                        btnLire.setVisibility(View.VISIBLE);
                    }
                    return;
                }
                if (pos == 0) {
                    btnLire.setVisibility(View.GONE);
                    source[0] = ImprimanteCiterneStore.SRC_MANUEL;
                    regGuid[0] = null;
                    srcInfo.setText("Citerne : saisie manuelle.");
                    return;
                }
                RegistreStore.Row r = regs.get(pos - 1);
                regGuid[0] = r.dataverseId;
                btnLire.setVisibility(View.VISIBLE);
                if (r.citerneNom != null && !r.citerneNom.isEmpty()) {
                    etTruck.setText(r.citerneNom);
                    source[0] = ImprimanteCiterneStore.SRC_DATAVERSE;
                    srcInfo.setText("Citerne lue dans Dataverse (registre " + r.serialId + ").");
                } else {
                    source[0] = ImprimanteCiterneStore.SRC_MANUEL;
                    srcInfo.setText("Pas de citerne connue pour ce registre : lis-la dans Dataverse ou saisis-la (saisie manuelle).");
                }
            }
            @Override public void onNothingSelected(AdapterView<?> a) {}
        });

        final Runnable lireCiterne = () -> {
            final String serialLu;
            if (fixSerial != null) serialLu = fixSerial;
            else {
                int pos = spReg.getSelectedItemPosition();
                if (pos <= 0) return;
                serialLu = regs.get(pos - 1).serialId;
            }
            btnLire.setEnabled(false);
            srcInfo.setText("Lecture dans Dataverse…");
            withToken(token -> {
                String[] cit = token == null ? null : LcrDeliverySync.lookupCiterne(getApplicationContext(), token, serialLu);
                ui.post(() -> {
                    btnLire.setEnabled(true);
                    if (cit != null && !cit[1].isEmpty()) {
                        etTruck.setText(cit[1]);
                        source[0] = ImprimanteCiterneStore.SRC_DATAVERSE;
                        srcInfo.setText("Citerne lue dans Dataverse : " + cit[1]);
                    } else if (token == null) {
                        srcInfo.setText("Dataverse injoignable (jeton indisponible). Saisie manuelle possible.");
                    } else {
                        srcInfo.setText("Dataverse n'a pas de citerne pour ce registre. Saisie manuelle (marquée comme telle).");
                    }
                });
            });
        };
        btnLire.setOnClickListener(v -> lireCiterne.run());

        btnBonded.setOnClickListener(v -> chooseBonded(etMac, etName));

        // Pré-remplissage en modification
        if (edit != null) {
            etTruck.setText(edit.truckId);
            etMac.setText(nz(edit.btMac));
            etName.setText(nz(edit.printerName));
            source[0] = edit.citerneSource == null ? ImprimanteCiterneStore.SRC_MANUEL : edit.citerneSource;
            srcInfo.setText(ImprimanteCiterneStore.SRC_DATAVERSE.equals(source[0])
                ? "Citerne lue dans Dataverse." : "Citerne : saisie manuelle.");
            for (int i = 0; i < PrinterCatalog.DRIVERS.length; i++)
                if (PrinterCatalog.DRIVERS[i].name.equals(edit.driverType)) spDrv.setSelection(i);
            for (int i = 0; i < PrinterCatalog.COMPANIES.length; i++)
                if (PrinterCatalog.COMPANIES[i].equals(nz(edit.company))) spCo.setSelection(i);
            if (edit.registreSerial != null)
                for (int i = 0; i < regs.size(); i++)
                    if (edit.registreSerial.equals(regs.get(i).serialId)) spReg.setSelection(i + 1);
            etTruck.setText(edit.truckId); // le listener du registre a pu l'écraser
        } else {
            etFmt.setText(PrinterCatalog.formatLabel(PrinterCatalog.formatFor(PrinterCatalog.DRIVERS[0].name)));
            srcInfo.setText("Citerne : saisie manuelle.");
            if (fixSerial != null) {
                btnLire.setVisibility(View.VISIBLE);
                if (fixRow != null && fixRow.citerneNom != null && !fixRow.citerneNom.isEmpty()) {
                    etTruck.setText(fixRow.citerneNom);
                    source[0] = ImprimanteCiterneStore.SRC_DATAVERSE;
                    srcInfo.setText("Citerne lue dans Dataverse (registre " + fixSerial + ").");
                } else {
                    lireCiterne.run(); // le registre est connu : on va chercher sa citerne tout de suite
                }
            }
        }

        ScrollView sv = new ScrollView(this);
        sv.addView(f);
        final AlertDialog dlg = new AlertDialog.Builder(this)
            .setTitle(edit == null ? "Ajouter une imprimante" : "Modifier l'imprimante")
            .setView(sv)
            .setPositiveButton("Enregistrer et envoyer", null) // surchargé plus bas (validation sans fermer)
            .setNegativeButton("Annuler", null)
            .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String truck = etTruck.getText().toString().trim();
            if (truck.isEmpty()) { etTruck.setError("Citerne requise"); return; }
            PrinterCatalog.Driver drv = PrinterCatalog.DRIVERS[spDrv.getSelectedItemPosition()];
            String mac = etMac.getText().toString().trim();
            if (drv.bluetooth) {
                String norm = PrinterCatalog.normalizeMac(mac);
                if (norm == null) { etMac.setError("MAC invalide (AA:BB:CC:DD:EE:FF)"); return; }
                mac = norm;
            } else if (mac.isEmpty()) { etMac.setError("Adresse requise"); return; }

            ImprimanteCiterneStore.Row r = new ImprimanteCiterneStore.Row();
            r.truckId = truck;
            r.driverType = drv.name;
            r.format = PrinterCatalog.formatFor(drv.name);
            r.company = PrinterCatalog.COMPANIES[spCo.getSelectedItemPosition()];
            r.btMac = mac;
            r.printerName = etName.getText().toString().trim();
            r.citerneSource = source[0];
            int rp = spReg.getSelectedItemPosition();
            if (fixSerial != null) {
                r.registreSerial = fixSerial;
                r.registreNud = fixNode >= 0 ? fixNode : (fixRow != null ? fixRow.nud : null);
                r.registreGuid = fixRow != null ? fixRow.dataverseId : null;
            } else if (rp > 0) {
                RegistreStore.Row rr = regs.get(rp - 1);
                r.registreSerial = rr.serialId;
                r.registreNud = rr.nud;
                r.registreGuid = rr.dataverseId;
            }
            // Une citerne peut avoir plusieurs imprimantes : seule la MÊME cible est refusée (hors modification de cette ligne).
            ImprimanteCiterneStore.Row dejaLa = store.get(truck, mac);
            if (dejaLa != null && (edit == null || dejaLa.id != edit.id)) {
                etMac.setError("Cette citerne a déjà une imprimante sur cette cible");
                return;
            }
            // Changement de citerne ou de cible en modification : l'ancienne ligne ne doit pas rester.
            if (edit != null && (!ImprimanteCiterneStore.keyOf(edit.truckId).equals(ImprimanteCiterneStore.keyOf(truck))
                    || !ImprimanteCiterneStore.macOf(edit.btMac).equals(ImprimanteCiterneStore.macOf(mac)))) {
                store.delete(edit.id);
            }
            store.save(r);
            LogBus.api(0, "[PRINT-SYNC] enregistré localement truck=" + truck + " format=" + r.format
                + " driver=" + r.driverType + " cible=" + mac + " source=" + r.citerneSource);
            dlg.dismiss();
            refreshList();
            send(store.get(truck, mac), false);
        });
    }

    // ── Envoi au service ────────────────────────────────────────

    private void send(final ImprimanteCiterneStore.Row r, final boolean ack) {
        if (r == null) return;
        status.setText("Envoi au service d'impression…");
        new Thread(() -> {
            final PrintServiceClient.Result res = PrintServiceClient.push(r, ack);
            ui.post(() -> {
                if (res.ok) {
                    store.markService(r.id, ImprimanteCiterneStore.SYNC_SENT, null);
                    status.setText("✅ " + r.truckId + " (" + r.format + ") : " + res.message);
                } else if (res.localDirty) {
                    status.setText("⚠ " + res.message);
                    new AlertDialog.Builder(this)
                        .setTitle("Version locale du service")
                        .setMessage("Le service d'impression contient pour " + r.truckId + " (" + r.format
                            + ") une imprimante modifiée à la main, pas encore confirmée.\n\n"
                            + "Remplacer par ce que tu viens d'enregistrer ici ?")
                        .setPositiveButton("Remplacer", (d, w) -> send(r, true))
                        .setNegativeButton("Garder celle du service", (d, w) -> {
                            store.markService(r.id, ImprimanteCiterneStore.SYNC_ERROR, "Refusé : version du service conservée");
                            refreshList();
                        }).show();
                    return;
                } else {
                    store.markService(r.id, ImprimanteCiterneStore.SYNC_ERROR, res.message);
                    status.setText("❌ " + res.message);
                }
                refreshList();
            });
        }, "print-sync").start();
    }

    // ── Utilitaires ─────────────────────────────────────────────

    private interface TokenUse { void run(String tokenOrNull); }

    /** Jeton Dataverse silencieux, hors thread UI ; null si indisponible (hors ligne, pas connecté). */
    private void withToken(final TokenUse use) {
        new Thread(() -> {
            final String[] holder = { null };
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            boolean locked = false;
            try {
                MsalTokenProvider.MSAL_SERIAL_LOCK.acquire();
                locked = true;
                final MsalTokenProvider msal = new MsalTokenProvider(getApplicationContext());
                msal.init(new MsalTokenProvider.InitCallback() {
                    @Override public void onReady() {
                        msal.acquireTokenSilentFromWorker(new MsalTokenProvider.TokenCallback() {
                            @Override public void onSuccess(String t) { holder[0] = t; latch.countDown(); }
                            @Override public void onError(Exception e) { latch.countDown(); }
                        });
                    }
                    @Override public void onError(Exception e) { latch.countDown(); }
                });
                latch.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception ignored) {
            } finally {
                if (locked) MsalTokenProvider.MSAL_SERIAL_LOCK.release();
            }
            use.run(holder[0]);
        }, "print-token").start();
    }

    private void chooseBonded(final EditText etMac, final EditText etName) {
        try {
            android.bluetooth.BluetoothAdapter ad = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (ad == null) { toast("Bluetooth indisponible"); return; }
            if (android.os.Build.VERSION.SDK_INT >= 31
                    && checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{ android.Manifest.permission.BLUETOOTH_CONNECT }, 77);
                toast("Autorise le Bluetooth puis réessaie");
                return;
            }
            Set<android.bluetooth.BluetoothDevice> bonded = ad.getBondedDevices();
            final List<android.bluetooth.BluetoothDevice> devs = new ArrayList<>(bonded);
            if (devs.isEmpty()) { toast("Aucun appareil appairé"); return; }
            String[] names = new String[devs.size()];
            for (int i = 0; i < names.length; i++)
                names[i] = devs.get(i).getName() + "  (" + devs.get(i).getAddress() + ")";
            new AlertDialog.Builder(this).setTitle("Appareils appairés")
                .setItems(names, (d, w) -> {
                    etMac.setText(devs.get(w).getAddress());
                    if (etName.getText().toString().trim().isEmpty()) etName.setText(devs.get(w).getName());
                }).show();
        } catch (SecurityException se) {
            toast("Permission Bluetooth refusée");
        }
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(12);
        t.setPadding(0, dp(10), 0, 0);
        t.setGravity(Gravity.START);
        return t;
    }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
    private static String nz(String s) { return s == null ? "" : s; }
}
