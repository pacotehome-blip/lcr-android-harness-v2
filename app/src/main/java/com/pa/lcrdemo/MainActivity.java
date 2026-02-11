
package com.pa.lcrdemo;

import androidx.appcompat.app.AppCompatActivity;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;

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

    // ---------- USB backend ----------
    private UsbManager usbManager;
    private final List<UsbDevice> usbList = new ArrayList<>();

    // ---------- LCP backend ----------
    private UsbSerialPort port = null;
    private LcpLink link;
    private DeliveryController ctrl;

    // pollMs used across the app (consistency)
    private static final int POLL_MS = 200;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindUI();
        applyDefaultValues();
        installHandlers();

        // Apply initial dump flags (Option 1)
        LcpLink.DUMP_TX = switchIoLog.isChecked();
        LcpLink.DUMP_RX = switchIoLog.isChecked();

        // Printer status always visible (default)
        txtPrinterStatus.setText("Imprimante: (non connecté / non lu)");
        txtPrinterStatus.setBackgroundColor(0xFFEEEEEE);

        log("Prêt. En attente du port USB… Brancher l'adaptateur RS‑232.");

        // Fallback USB scan if no receiver port
        new Handler().postDelayed(() -> {
            if (port == null) {
                log("UsbReceiver silencieux → tentative fallback USB…");
                scanUsbDevices();
            }
        }, 800);
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

        // Ticket / Printer UI (always visible)
        txtTicketNumber = findViewById(R.id.txtTicketNumber);
        spnTicketRequired = findViewById(R.id.spnTicketRequired);
        btnRefreshTicket = findViewById(R.id.btnRefreshTicket);
        btnClearShift = findViewById(R.id.btnClearShift);

        txtPrinterStatus = findViewById(R.id.txtPrinterStatus);
        btnRefreshPrinter = findViewById(R.id.btnRefreshPrinter);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
    }

    // ==========================================================
    // Defaults
    // ==========================================================
    private void applyDefaultValues() {
        edtTo.setText("0xFA");
        edtFrom.setText("0xFF");
        edtProduct.setText("1");
        edtPreset.setText("50.0");

        // Option 1: master logs + dumps
        switchIoLog.setChecked(true);

        // TicketRequired default = 1 (No ticket required) — métier terrain [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)[1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
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

        // Option 1: checkbox controls UI logs and I/O dumps
        switchIoLog.setOnCheckedChangeListener((btn, checked) -> {
            LcpLink.DUMP_TX = checked;
            LcpLink.DUMP_RX = checked;
            if (checked) log("[UI] I/O + logs activés");
        });

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
            log("A : END (reset)");
            ctrl.endGracefully(15000, POLL_MS);
        });

        // B: PING / status
        btnB.setOnClickListener(v -> {
            if (ctrl == null) { log("Pas connecté."); return; }
            log("PING (#23)");
            ctrl.pingStatus(POLL_MS);
        });

        // C: Start Delivery (simple delivery) — uses preset!
        btnC.setOnClickListener(v -> {
            if (ctrl == null) { log("Pas connecté."); return; }
            int product = readInt(edtProduct, 1);
            double preset = readDouble(edtPreset, 0);

            log("C : Start Delivery (product=" + product + ", preset=" + preset + ")");
            ctrl.startOpenMode(product, preset, 15000, POLL_MS);

            // UI lock handled by onStateChanged
        });

        // Continue (debug only): keep but it will ignore unless RUNNING
        btnContinue.setOnClickListener(v -> {
            if (ctrl == null) return;
            log("Continuer...");
            ctrl.startLiveLoop(POLL_MS);
        });

        // Finish (END)
        btnFinish.setOnClickListener(v -> {
            if (ctrl == null) return;
            log("Terminé.");
            ctrl.endGracefully(15000, POLL_MS);
        });

        // USB scan & ping
        btnScanUsb.setOnClickListener(v -> scanUsbDevices());
        btnPingUsb.setOnClickListener(v -> pingSelectedUsbDevice());

        // Ticket refresh
        btnRefreshTicket.setOnClickListener(v -> {
            if (ctrl == null) { log("Pas connecté."); return; }
            ctrl.refreshTicketInfo(POLL_MS);
        });

        // TicketRequired UI -> set field #37
        spnTicketRequired.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            boolean first = true;
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (first) { first = false; return; } // avoid immediate set at init
                if (ctrl == null) return;
                ctrl.setTicketRequired(position, POLL_MS); // 0/1/2 [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)[1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        // Clear Shift button (Field #16=0) depends on #37 behavior [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)[1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
        btnClearShift.setOnClickListener(v -> {
            if (ctrl == null) { log("Pas connecté."); return; }
            log("SHIFT : ClearShift (#16)=0");
            ctrl.clearShiftNow(POLL_MS);
        });

        // Printer status refresh (0x23). Disabled during RUNNING by onStateChanged. [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
        btnRefreshPrinter.setOnClickListener(v -> {
            if (ctrl == null) { log("Pas connecté."); return; }
            ctrl.refreshPrinterStatus(POLL_MS);
        });
    }

    // ==========================================================
    // Init LCP
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

            // Bridge low-level logs (TX/RX dumps + internal)
            LcpLink.setLogger(line -> log("[IO] " + line));
            LcpLink.DUMP_TX = switchIoLog.isChecked();
            LcpLink.DUMP_RX = switchIoLog.isChecked();

            ctrl = new DeliveryController(
                    link,
                    new DeliveryEventsImpl(),
                    Executors.newSingleThreadExecutor()
            );

            log("LCP prêt. Appareil LCR-II accessible.");

            // Default ticket policy: force TicketRequired(#37)=1 (optional) [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)[1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
            ctrl.ensureDefaultTicketRequiredIs1(POLL_MS);

            // Refresh ticket info + printer status at connect (Option 2)
            ctrl.refreshTicketInfo(POLL_MS);
            ctrl.refreshPrinterStatus(POLL_MS);

            // Optional: ping (includes printer status in our controller)
            ctrl.pingStatus(POLL_MS);

        } catch (Exception e) {
            log("Erreur init LCP : " + e.getMessage());
        }
    }

    // ==========================================================
    // Logging helper
    // ==========================================================
    private void log(String s) {
        // Option 1: checkbox is master mute
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

        // Start disabled while in progress
        btnC.setEnabled(!(running || startingOrPre || ending));
        // Finish enabled when running/starting (allow user to stop quickly)
        btnFinish.setEnabled(running || startingOrPre);

        // Avoid printer status refresh during RUNNING (0x23 may delay if printer offline) [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
        btnRefreshPrinter.setEnabled(!running);

        // Ticket controls enabled when not running (optional policy)
        spnTicketRequired.setEnabled(!running);
        btnClearShift.setEnabled(!running);

        // Continue: keep enabled only if running
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
    // USB scan
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
        for (UsbDevice d : usbList) {
            labels.add(String.format("VID=%04X PID=%04X", d.getVendorId(), d.getProductId()));
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spnUsbDevices.setAdapter(adapter);

        log("Scan terminé : " + labels.size() + " périphérique(s) trouvé(s).");
        if (labels.isEmpty()) log("Aucun périphérique USB détecté.");
    }

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
            p.setParameters(19200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            log("Port ouvert pour " + dev);
            return p;
        } catch (Exception e) {
            log("Erreur ouverture: " + e.getMessage());
            return null;
        }
    }

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
            // Reuse our normal connect path
            setPort(testPort);
            log("PING déclenché (voir I/O TX/RX).");
        } catch (Exception e) {
            log("✖ PING FAIL : " + e.getMessage());
            try { testPort.close(); } catch(Exception ignored){}
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
            // "en livraison" = deliveredNetL / deliveredGrossL
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
            // mode: 0/1/2 [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)[1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
            int idx = Math.max(0, Math.min(2, mode));
            runOnUiThread(() -> spnTicketRequired.setSelection(idx));
        }

        @Override
        public void onPrinterStatus(LcpLink.MachineStatusEx ms, boolean ticketPending) {
            // prnStatus bits are decoded in LcpLink according to PDF Printer Bits [1](https://groupefilgo-my.sharepoint.com/personal/paul-andre_cote_filgo_ca/Documents/Fichiers%20Microsoft%20Copilot%20Chat/LCR%20API%20Internal%20Messages%20for%20LCP.pdf)
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
            // App logs
            log("[LCP] " + line);
        }
    }
}
