
package com.pa.lcrdemo;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;

import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;

import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Context;

import android.util.Log;

import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import com.pa.lcr.lcp.LcpLink;
import com.pa.lcr.lcp.DeliveryController;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    // UI de base
    private EditText edtTo, edtFrom, edtProduct, edtPreset;
    private Button btnCopyLog, btnClearLog, btnConnect;
    private Button btnA, btnB, btnC;
    private Button btnContinue, btnFinish;
    private CheckBox switchIoLog;
    private TextView txtLog;
    private ScrollView logScroll;

    // UI USB
    private Spinner spnUsbDevices;
    private Button btnScanUsb, btnPingUsb;

    // USB backend
    private UsbManager usbManager;
    private final List<UsbDevice> usbList = new ArrayList<>();

    // LCP backend
    private UsbSerialPort port = null;
    private LcpLink link;
    private DeliveryController ctrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindUI();
        applyDefaultValues();
        installHandlers();

        // Option 1: la case "I/O" contrôle aussi DUMP_TX/DUMP_RX
        // -> on applique l'état initial
        LcpLink.DUMP_TX = switchIoLog.isChecked();
        LcpLink.DUMP_RX = switchIoLog.isChecked();

        log("Prêt. En attente du port USB… Brancher l'adaptateur RS‑232.");

        new android.os.Handler().postDelayed(() -> {
            if (port == null) {
                log("UsbReceiver silencieux → tentative fallback USB…");
                scanUsbDevices();
            }
        }, 800);
    }

    // ==========================================================
    // Reçoit le port USB depuis UsbReceiver
    // ==========================================================
    public void setPort(UsbSerialPort p) {
        this.port = p;
        log("USB détecté — port ouvert (19200 8N1).");
    }

    // ==========================================================
    // Bind UI elements
    // ==========================================================
    private void bindUI() {
        edtTo = findViewById(R.id.edtTo);
        edtFrom = findViewById(R.id.edtFrom);
        edtProduct = findViewById(R.id.edtProduct);
        edtPreset = findViewById(R.id.edtPreset);

        btnCopyLog = findViewById(R.id.btnCopyLog);
        btnClearLog = findViewById(R.id.btnClearLog);
        btnConnect = findViewById(R.id.btnConnect);

        btnA = findViewById(R.id.btnA);
        btnB = findViewById(R.id.btnB);
        btnC = findViewById(R.id.btnC);

        btnContinue = findViewById(R.id.btnContinue);
        btnFinish = findViewById(R.id.btnFinish);
        switchIoLog = findViewById(R.id.switchIoLog);

        txtLog = findViewById(R.id.txtLog);
        logScroll = findViewById(R.id.logScroll);

        spnUsbDevices = findViewById(R.id.spnUsbDevices);
        btnScanUsb = findViewById(R.id.btnScanUsb);
        btnPingUsb = findViewById(R.id.btnPingUsb);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
    }

    // ==========================================================
    // Valeurs par défaut
    // ==========================================================
    private void applyDefaultValues() {
        edtTo.setText("0xFA");
        edtFrom.setText("0xFF");
        edtProduct.setText("1");
        edtPreset.setText("50.0");

        // Option 1 : coché par défaut = logs + I/O dumps
        switchIoLog.setChecked(true);
    }

    // ==========================================================
    // Handlers UI
    // ==========================================================
    private void installHandlers() {

        // Option 1: checkbox = master logs + I/O dumps
        switchIoLog.setOnCheckedChangeListener((btn, checked) -> {
            LcpLink.DUMP_TX = checked;
            LcpLink.DUMP_RX = checked;

            // Note: si checked=false, log() n'affiche rien (silence total) — OK pour Option 1
            if (checked) log("[UI] I/O + logs activés");
        });

        btnConnect.setOnClickListener(v -> initLcp());

        btnCopyLog.setOnClickListener(v -> {
            ClipboardManager clip =
                    (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData data = ClipData.newPlainText("log", txtLog.getText().toString());
            clip.setPrimaryClip(data);
            log("Log copié.");
        });

        btnClearLog.setOnClickListener(v -> txtLog.setText(""));

        btnA.setOnClickListener(v -> {
            if (ctrl == null) { log("Pas connecté."); return; }
            log("A : END (reset)");
            ctrl.endGracefully(5000, 200);
        });

        btnB.setOnClickListener(v -> {
            if (ctrl == null) { log("Pas connecté."); return; }
            log("PING (#23)");
            ctrl.pingStatus();
        });

        btnC.setOnClickListener(v -> {
            if (ctrl == null) { log("Pas connecté."); return; }
            int product = readInt(edtProduct, 1);
            double preset = readDouble(edtPreset, 0);
            log("C : Start Delivery (product=" + product + ", preset=" + preset + ")");
            ctrl.startOpenMode(product, 5000, 200);
            enableLiveButtons(true);
        });

        btnContinue.setOnClickListener(v -> {
            if (ctrl == null) return;
            log("Continuer...");
            ctrl.startLiveLoop(200);
        });

        btnFinish.setOnClickListener(v -> {
            if (ctrl == null) return;
            log("Terminé.");
            ctrl.endGracefully(5000, 200);
            enableLiveButtons(false);
        });

        btnScanUsb.setOnClickListener(v -> scanUsbDevices());
        btnPingUsb.setOnClickListener(v -> pingSelectedUsbDevice());
    }

    // ==========================================================
    // Initialisation LCP
    // ==========================================================
    private void initLcp() {
        if (port == null) {
            log("ERR: Port USB non initialisé. Brancher l'adaptateur RS‑232.");
            return;
        }

        try {
            int to = parseHex(edtTo, 0xFA);
            int from = parseHex(edtFrom, 0xFF);

            log(String.format("Init LCP → to=0x%02X, from=0x%02X…", to, from));

            link = new LcpLink(port, to, from, true);

            // Bridge LcpLink -> UI (TX/RX + logs bas niveau)
            LcpLink.setLogger(line -> log("[IO] " + line));
            LcpLink.DUMP_TX = switchIoLog.isChecked();
            LcpLink.DUMP_RX = switchIoLog.isChecked();

            ctrl = new DeliveryController(
                    link,
                    new DeliveryEventsImpl(),
                    Executors.newSingleThreadExecutor()
            );

            log("LCP prêt. Appareil LCR-II accessible.");
            ctrl.pingStatus();

        } catch (Exception e) {
            log("Erreur init LCP : " + e.getMessage());
        }
    }

    // ==========================================================
    // Log helper
    // ==========================================================
    private void log(String s) {
        // Option 1 : master mute (décoché = silence total)
        if (!switchIoLog.isChecked()) return;

        runOnUiThread(() -> {
            txtLog.append(s + "\n");
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void enableLiveButtons(boolean en) {
        btnContinue.setEnabled(en);
        btnFinish.setEnabled(en);
    }

    private int parseHex(EditText edt, int def) {
        try {
            String t = edt.getText().toString().trim();
            if (t.startsWith("0x") || t.startsWith("0X"))
                return Integer.parseInt(t.substring(2), 16) & 0xFF;
            if (t.length() > 0)
                return Integer.parseInt(t, 16) & 0xFF;
        } catch (Exception ignored) {}
        return def;
    }

    private int readInt(EditText edt, int def) {
        try { return Integer.parseInt(edt.getText().toString().trim()); }
        catch(Exception ignored){ return def; }
    }

    private double readDouble(EditText edt, double def) {
        try { return Double.parseDouble(edt.getText().toString().trim()); }
        catch(Exception ignored){ return def; }
    }

    // ==========================================================
    // USB — Scan devices
    // ==========================================================
    private void scanUsbDevices() {

        usbList.clear();

        if (usbManager == null) {
            log("USB Manager non disponible.");
            return;
        }

        for (UsbDevice dev : usbManager.getDeviceList().values()) {
            usbList.add(dev);
        }

        if (usbList.isEmpty()) {
            log("Aucun périphérique USB détecté.");
        }

        List<String> labels = new ArrayList<>();
        for (UsbDevice d : usbList) {
            labels.add(String.format("VID=%04X PID=%04X", d.getVendorId(), d.getProductId()));
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spnUsbDevices.setAdapter(adapter);

        log("Scan terminé : " + labels.size() + " périphérique(s) trouvé(s).");
    }

    // ==========================================================
    // USB — Open port
    // ==========================================================
    private UsbSerialPort tryOpenDevice(UsbDevice dev) {

        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(dev);
        if (driver == null) {
            log("Aucun driver USB‑Série pour ce périphérique.");
            return null;
        }

        UsbSerialPort p = driver.getPorts().get(0);
        UsbDeviceConnection conn = usbManager.openDevice(dev);

        if (conn == null) {
            log("Permission USB refusée / impossible d'ouvrir le device.");
            return null;
        }

        try {
            p.open(conn);
            p.setParameters(19200, 8,
                    UsbSerialPort.STOPBITS_1,
                    UsbSerialPort.PARITY_NONE);

            log("Port ouvert pour " + dev);
            return p;

        } catch (Exception e) {
            log("Erreur ouverture: " + e.getMessage());
            return null;
        }
    }

    // ==========================================================
    // USB — Ping LCR-II
    // ==========================================================
    private void pingSelectedUsbDevice() {

        int index = spnUsbDevices.getSelectedItemPosition();
        if (index < 0 || index >= usbList.size()) {
            log("Aucun device sélectionné.");
            return;
        }

        UsbDevice dev = usbList.get(index);
        UsbSerialPort testPort = tryOpenDevice(dev);

        if (testPort == null) {
            log("Impossible d'ouvrir le port USB sélectionné.");
            return;
        }

        try {
            log("PING (#23)…");

            LcpLink testLink = new LcpLink(testPort, 0xFA, 0xFF, true);

            // Bridge logs I/O du test vers UI
            LcpLink.setLogger(line -> log("[IO] " + line));
            LcpLink.DUMP_TX = switchIoLog.isChecked();
            LcpLink.DUMP_RX = switchIoLog.isChecked();

            // Donne un callback au test controller pour voir erreurs/états
            DeliveryController testCtrl = new DeliveryController(
                    testLink,
                    new DeliveryEventsImpl(),
                    Executors.newSingleThreadExecutor()
            );

            testCtrl.pingStatus();

            // Ici on ne met plus "OK" immédiatement comme avant.
            // Le vrai succès sera visible via RX + absence d'erreur.
            log("PING déclenché (voir I/O TX/RX).");

            // Si tu veux conserver le port du test si c'est le bon device:
            setPort(testPort);

        } catch (Exception e) {
            log("✖ PING FAIL — ce device n'est pas un registre LCR-II : " + e.getMessage());
            try { testPort.close(); } catch(Exception ignored){}
        }
    }

    // ==========================================================
    // Delivery Events
    // ==========================================================
    private class DeliveryEventsImpl implements DeliveryController.DeliveryEvents {

        @Override public void onStateChanged(DeliveryController.State s) {
            log("État = " + s);
        }

        @Override public void onFlowStarted() { log("Flow START"); }
        @Override public void onFlowStopped() { log("Flow STOP"); }

        @Override
        public void onLiveSample(int ds, int dc, double g, double n) {
            log(String.format("LIVE ds=%04X dc=%04X G=%.1f N=%.1f", ds, dc, g, n));
        }

        @Override
        public void onProgress(DeliveryController.DeliveryProgress p) {
            log(String.format("PROG t=%dms G=%.1f N=%.1f dG=%.1f dN=%.1f",
                    p.tSinceStartMs, p.grossL, p.netL, p.dGrossL, p.dNetL));
        }

        @Override public void onGuardReached() { log("GUARD reached"); }

        @Override
        public void onError(String msg, Throwable t) {
            log("ERR[" + msg + "] → " + (t != null ? t.getMessage() : "(null)"));
        }

        @Override
        public void onLog(String line) {
            // Logs applicatifs LCP (pas les dumps TX/RX)
            log("[LCP] " + line);
        }
    }
}
