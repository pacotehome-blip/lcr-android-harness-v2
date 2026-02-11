
package com.pa.lcrdemo;

import androidx.appcompat.app.AppCompatActivity;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;

import android.os.Build;
import android.os.Bundle;
import android.os.Handler;

import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import com.pa.lcr.lcp.DeliveryController;
import com.pa.lcr.lcp.LcpLink;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    // ---------- UI base ----------
    private EditText edtTo, edtFrom, edtProduct, edtPreset;
    private Button btnCopyLog, btnClearLog, btnConnect;
    private Button btnA, btnB, btnC;
    private Button btnContinue, btnFinish;
    private CheckBox switchIoLog;
    private TextView txtLog;
    private ScrollView logScroll;

    // ---------- UI USB ----------
    private Spinner spnUsbDevices;
    private Button btnScanUsb, btnPingUsb;

    // ---------- UI ticket + printer ----------
    private TextView txtTicketNumber;
    private Spinner spnTicketRequired;
    private Button btnRefreshTicket;
    private Button btnClearShift;

    private TextView txtPrinterStatus;
    private Button btnRefreshPrinter;

    // ---------- UI print pending (Cmd #6) ----------
    private Button btnPrintPending;

    // ---------- USB backend ----------
    private UsbManager usbManager;
    private final List<UsbDevice> usbList = new ArrayList<>();

    // ---------- LCP backend ----------
    private UsbSerialPort port = null;
    private UsbDevice lastSelectedDevice = null;

    private LcpLink link;
    private DeliveryController ctrl;

    // Poll / timeouts
    private static final int POLL_MS = 200;

    // USB Permission
    private static final String ACTION_USB_PERMISSION = "com.pa.lcrdemo.USB_PERMISSION";
    private PendingIntent usbPermissionIntent;

    // ==========================================================
    // USB Permission Receiver
    // ==========================================================
    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;

            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);

            if (device == null) {
                log("Permission USB: device=null");
                return;
            }

            if (granted) {
                log("Permission USB accordée: " + usbLabel(device));
                // Try open immediately
                UsbSerialPort p = tryOpenDevice(device);
                if (p != null) {
                    setPort(p);
                }
            } else {
                log("Permission USB REFUSÉE: " + usbLabel(device));
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindUI();
        applyDefaultValues();
        installHandlers();

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        // PendingIntent flags (Android 12+)
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // For USB permission broadcast, mutable is safer on some devices; immutable also works in many cases.
            // We'll use MUTABLE for compatibility.
            piFlags |= PendingIntent.FLAG_MUTABLE;
        }
        usbPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), piFlags);

        registerReceiver(usbPermissionReceiver, new IntentFilter(ACTION_USB_PERMISSION));

        // Apply initial dump flags
        LcpLink.DUMP_TX = switchIoLog.isChecked();
        LcpLink.DUMP_RX = switchIoLog.isChecked();

        // Printer status always visible (default text)
        txtPrinterStatus.setText("Imprimante: (non connecté / non lu)");
        txtPrinterStatus.setBackgroundColor(0xFFEEEEEE);

        log("Prêt. 1) Choisir USB  2) Ouvrir/Ping  3) Connect (LCP).");

        // Auto-scan shortly after start
        new Handler().postDelayed(this::scanUsbDevices, 400);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(usbPermissionReceiver); } catch (Exception ignored) {}
    }

    // ==========================================================
    // Port injection (UsbReceiver can call this)
    // ==========================================================
    public void setPort(UsbSerialPort p) {
        this.port = p;
        log("USB détecté — port ouvert (19200 8N1).");
    }

    // ==========================================================
    // Bind UI
    // ==========================================================
    private void bindUI() {
        // Connexion
        edtTo = findViewById(R.id.edtTo);
        edtFrom = findViewById(R.id.edtFrom);

        // Delivery params
        edtProduct = findViewById(R.id.edtProduct);
        edtPreset = findViewById(R.id.edtPreset);

        // Buttons
        btnConnect = findViewById(R.id.btnConnect);

        btnA = findViewById(R.id.btnA);
        btnB = findViewById(R.id.btnB);
        btnC = findViewById(R.id.btnC);

        btnContinue = findViewById(R.id.btnContinue);
        btnFinish = findViewById(R.id.btnFinish);

        // Logs
        switchIoLog = findViewById(R.id.switchIoLog);
        txtLog = findViewById(R.id.txtLog);
        logScroll = findViewById(R.id.logScroll);
        btnCopyLog = findViewById(R.id.btnCopyLog);
        btnClearLog = findViewById(R.id.btnClearLog);

        // USB
        spnUsbDevices = findViewById(R.id.spnUsbDevices);
        btnScanUsb = findViewById(R.id.btnScanUsb);
        btnPingUsb = findViewById(R.id.btnPingUsb);

        // Ticket
        txtTicketNumber = findViewById(R.id.txtTicketNumber);
        spnTicketRequired = findViewById(R.id.spnTicketRequired);
        btnRefreshTicket = findViewById(R.id.btnRefreshTicket);
        btnClearShift = findViewById(R.id.btnClearShift);

        // Printer
        txtPrinterStatus = findViewById(R.id.txtPrinterStatus);
        btnRefreshPrinter = findViewById(R.id.btnRefreshPrinter);

        // Print pending (Cmd #6)
        btnPrintPending = findViewById(R.id.btnPrintPending);
    }

    // ==========================================================
    // Defaults
    // ==========================================================
    private void applyDefaultValues() {
        edtTo.setText("0xFA");
        edtFrom.setText("0xFF");

        edtProduct.setText("1");
        edtPreset.setText("50.0");

        switchIoLog.setChecked(true);

        // TicketRequired default = 1 (ticket non requis; imprime si possible) [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)[1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
        ArrayAdapter<String> ticketReqAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                new String[]{
                        "0 = Oui (ticket requis)",
                        "1 = Non (ticket non requis, imprime si possible)",
                        "2 = Jamais (ne pas imprimer)"
                }
        );
        ticketReqAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spnTicketRequired.setAdapter(ticketReqAdapter);
        spnTicketRequired.setSelection(1);

        txtTicketNumber.setText("-");
    }

    // ==========================================================
    // Handlers
    // ==========================================================
    private void installHandlers() {

        switchIoLog.setOnCheckedChangeListener((btn, checked) -> {
            LcpLink.DUMP_TX = checked;
            LcpLink.DUMP_RX = checked;
            if (checked) log("[UI] I/O + logs activés");
        });

        btnScanUsb.setOnClickListener(v -> scanUsbDevices());
        btnPingUsb.setOnClickListener(v -> openSelectedUsbDevice());

        btnConnect.setOnClickListener(v -> initLcp());

        btnCopyLog.setOnClickListener(v -> {
            ClipboardManager clip = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData data = ClipData.newPlainText("log", txtLog.getText().toString());
            clip.setPrimaryClip(data);
            log("Log copié.");
        });

        btnClearLog.setOnClickListener(v -> txtLog.setText(""));

        // A: END
        btnA.setOnClickListener(v -> {
            if (ctrl == null) { log("Pas connecté."); return; }
            log("A : END");
            ctrl.endGracefully(20000, POLL_MS);
        });

        // B: PING / status (0x23)
        btnB.setOnClickListener(v -> {
            if (ctrl == null) { log("Pas connecté."); return; }
            log("PING (#23)");
            ctrl.pingStatus(POLL_MS);
        });

        // C: Start Delivery (product + preset)
        btnC.setOnClickListener(v -> {
            if (ctrl == null) { log("Pas connecté."); return; }
            int product = readInt(edtProduct, 1);
            double preset = readDouble(edtPreset, 0);

            log("C : Start Delivery (product=" + product + ", preset=" + preset + ")");
            ctrl.startOpenMode(product, preset, 20000, POLL_MS);
        });

        btnContinue.setOnClickListener(v -> {
            if (ctrl == null) return;
            log("Continuer live loop...");
            ctrl.startLiveLoop(POLL_MS);
        });

        btnFinish.setOnClickListener(v -> {
            if (ctrl == null) return;
            log("Terminé (END)");
            ctrl.endGracefully(20000, POLL_MS);
        });

        // Ticket refresh
        btnRefreshTicket.setOnClickListener(v -> {
            if (ctrl == null) { log("Pas connecté."); return; }
            ctrl.refreshTicketInfo(POLL_MS);
        });

        // TicketRequired selection -> set field #37
        spnTicketRequired.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            boolean first = true;
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (first) { first = false; return; }
                if (ctrl == null) return;
                ctrl.setTicketRequired(position, POLL_MS); // 0/1/2 [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)[1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        // Clear Shift (#16)=0
        btnClearShift.setOnClickListener(v -> {
            if (ctrl == null) { log("Pas connecté."); return; }
            log("SHIFT : ClearShift (#16)=0");
            ctrl.clearShiftNow(POLL_MS);
        });

        // Printer status refresh (0x23) — avoid during RUNNING [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
        btnRefreshPrinter.setOnClickListener(v -> {
            if (ctrl == null) { log("Pas connecté."); return; }
            ctrl.refreshPrinterStatus(POLL_MS);
        });

        // Print pending ticket (Cmd #6) — only when not RUNNING [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
        btnPrintPending.setOnClickListener(v -> {
            if (ctrl == null) { log("Pas connecté."); return; }
            log("PRINT PENDING : Cmd #6");
            ctrl.printPendingTicket(POLL_MS, 25000);
        });
    }

    // ==========================================================
    // Init LCP (after USB port is opened)
    // ==========================================================
    private void initLcp() {
        if (port == null) {
            log("ERR: Port USB non initialisé. 1) Scan 2) Ouvrir/Ping 3) Connect");
            return;
        }

        try {
            int to = parseHex(edtTo, 0xFA);
            int from = parseHex(edtFrom, 0xFF);

            log(String.format("Init LCP → to=0x%02X, from=0x%02X…", to, from));

            link = new LcpLink(port, to, from, true);

            // Bridge low-level logs
            LcpLink.setLogger(line -> log("[IO] " + line));
            LcpLink.DUMP_TX = switchIoLog.isChecked();
            LcpLink.DUMP_RX = switchIoLog.isChecked();

            ctrl = new DeliveryController(
                    link,
                    new DeliveryEventsImpl(),
                    Executors.newSingleThreadExecutor()
            );

            log("LCP prêt. Appareil LCR-II accessible.");

            // Keep connect sequence minimal (avoid too many 0x23 right away). [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
            ctrl.ensureDefaultTicketRequiredIs1(POLL_MS);
            ctrl.refreshTicketInfo(POLL_MS);
            ctrl.refreshPrinterStatus(POLL_MS);

        } catch (Exception e) {
            log("Erreur init LCP : " + e.getMessage());
        }
    }

    // ==========================================================
    // Logging helper (respects checkbox)
    // ==========================================================
    private void log(String s) {
        if (!switchIoLog.isChecked()) return;

        runOnUiThread(() -> {
            txtLog.append(s + "\n");
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    // ==========================================================
    // UI state lock
    // ==========================================================
    private void setUiForState(DeliveryController.State s) {
        boolean running = (s == DeliveryController.State.RUNNING);
        boolean startingOrPre = (s == DeliveryController.State.STARTING || s == DeliveryController.State.PRESTART);
        boolean ending = (s == DeliveryController.State.ENDING);

        btnC.setEnabled(!(running || startingOrPre || ending));
        btnFinish.setEnabled(running || startingOrPre);

        // Avoid 0x23 during RUNNING (may be slow if printer offline) [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
        btnRefreshPrinter.setEnabled(!running);
        btnPrintPending.setEnabled(!running); // cmd#6 won't print if delivery active [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)

        spnTicketRequired.setEnabled(!running);
        btnClearShift.setEnabled(!running);

        btnContinue.setEnabled(running);
    }

    // ==========================================================
    // Parse helpers
    // ==========================================================
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
    // USB helpers (labels with Manufacturer/Product)
    // ==========================================================
    private static String safe(String s) {
        return (s == null || s.trim().isEmpty()) ? "?" : s.trim();
    }

    private static String usbLabel(UsbDevice d) {
        String m = safe(d.getManufacturerName());
        String p = safe(d.getProductName());
        return String.format("%s - %s (VID=%04X PID=%04X)",
                m, p, d.getVendorId(), d.getProductId());
    }

    // ==========================================================
    // USB scan / open
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

        List<String> labels = new ArrayList<>();
        for (UsbDevice d : usbList) labels.add(usbLabel(d));

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spnUsbDevices.setAdapter(adapter);

        log("Scan terminé : " + labels.size() + " périphérique(s) trouvé(s).");
        if (labels.isEmpty()) log("Aucun périphérique USB détecté.");
        else log("USB[0] = " + labels.get(0));
    }

    private void openSelectedUsbDevice() {
        int index = spnUsbDevices.getSelectedItemPosition();
        if (index < 0 || index >= usbList.size()) {
            log("Aucun device sélectionné.");
            return;
        }

        UsbDevice dev = usbList.get(index);
        lastSelectedDevice = dev;

        // Permission check
        if (usbManager != null && !usbManager.hasPermission(dev)) {
            log("Permission USB requise pour: " + usbLabel(dev));
            usbManager.requestPermission(dev, usbPermissionIntent);
            return;
        }

        UsbSerialPort p = tryOpenDevice(dev);
        if (p == null) {
            log("Impossible d'ouvrir le port USB sélectionné.");
            return;
        }

        setPort(p);
        log("PING (#23)…");
        log("USB ouvert; maintenant tu peux faire Connect (LCP).");
    }

    private UsbSerialPort tryOpenDevice(UsbDevice dev) {
        log("USB sélectionné: " + usbLabel(dev));

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
            p.setParameters(19200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            log("Port ouvert: " + usbLabel(dev) + " (19200 8N1)");
            return p;
        } catch (Exception e) {
            log("Erreur ouverture: " + e.getMessage());
            try { p.close(); } catch (Exception ignored) {}
            return null;
        }
    }

    // ==========================================================
    // Delivery Events Impl
    // ==========================================================
    private class DeliveryEventsImpl implements DeliveryController.DeliveryEvents {

        @Override public void onStateChanged(DeliveryController.State s) {
            log("État = " + s);
            runOnUiThread(() -> setUiForState(s));
        }

        @Override public void onFlowStarted() { log("Flow START"); }
        @Override public void onFlowStopped() { log("Flow STOP"); }

        @Override
        public void onProgress(DeliveryController.DeliveryProgress p) {
            log(String.format("PROG t=%dms NET=%.1f (en_liv=%.1f) | GROSS=%.1f (en_liv=%.1f) ds=%04X dc=%04X",
                    p.tSinceStartMs,
                    p.netL, p.deliveredNetL,
                    p.grossL, p.deliveredGrossL,
                    p.ds, p.dc));
        }

        @Override
        public void onTicketNumber(int ticketNumber) {
            runOnUiThread(() -> txtTicketNumber.setText(String.valueOf(ticketNumber)));
        }

        @Override
        public void onTicketRequired(int mode) {
            int idx = Math.max(0, Math.min(2, mode));
            runOnUiThread(() -> spnTicketRequired.setSelection(idx));
        }

        @Override
        public void onPrinterStatus(LcpLink.MachineStatusEx ms, boolean ticketPending) {
            final String text = "Imprimante: " + ms.printer().summary()
                    + " | ticketPending=" + ticketPending
                    + " | ds=0x" + String.format("%04X", ms.delStatus)
                    + " dc=0x" + String.format("%04X", ms.delCode);

            runOnUiThread(() -> {
                txtPrinterStatus.setText(text);

                boolean hardErr = ms.printer().outOfPaper || ms.printer().noProcessor || ms.printer().processorError;
                boolean printing = ms.printer().printingStarted;

                int bg;
                if (hardErr) bg = 0xFFFFCDD2;        // rouge clair
                else if (printing) bg = 0xFFFFF9C4;  // jaune clair
                else bg = 0xFFC8E6C9;                // vert clair

                txtPrinterStatus.setBackgroundColor(bg);
            });
        }

        @Override
        public void onError(String msg, Throwable t) {
            log("ERR[" + msg + "] → " + (t != null ? t.getMessage() : "(null)"));
        }

        @Override
        public void onLog(String line) {
            log("[LCP] " + line);
        }
    }
}
