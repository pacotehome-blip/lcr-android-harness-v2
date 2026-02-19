
package com.pa.lcrdemo;

import androidx.appcompat.app.AlertDialog;
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
import android.os.Looper;
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
import com.pa.lcr.lcp.lifecycle.LcpDeliveryState;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    // ================= UI =================
    private EditText edtTo, edtFrom, edtProduct, edtPreset;
    private Button btnConnect;
    private Button btnA, btnB, btnC;
    private Button btnContinue, btnFinish;
    private Button btnCopyLog, btnClearLog;

    private CheckBox switchIoLog;
    private TextView txtLog;
    private ScrollView logScroll;

    private TextView txtLive;
    private TextView txtQtyNet, txtQtyGross;

    // ================= USB =================
    private Spinner spnUsbDevices;
    private Button btnScanUsb, btnPingUsb;

    // ================= Ticket / Printer =================
    private TextView txtTicketNumber;
    private Spinner spnTicketRequired;
    private Button btnRefreshTicket;
    private Button btnClearShift;
    private TextView txtPrinterStatus;
    private Button btnRefreshPrinter;
    private Button btnPrintPending;

    // ================= Backend =================
    private UsbManager usbManager;
    private final List<UsbDevice> usbList = new ArrayList<>();

    private UsbSerialPort port = null;
    private LcpLink link;
    private DeliveryController ctrl;

    private static final int POLL_MS = 200;

    private static final String ACTION_USB_PERMISSION = "com.pa.lcrdemo.USB_PERMISSION";
    private PendingIntent usbPermissionIntent;

    // ================= Log batching =================
    private final Object logLock = new Object();
    private final StringBuilder logBuf = new StringBuilder(8192);
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean flushScheduled = false;
    private static final int LOG_FLUSH_MS = 250;
    private static final int LOG_MAX_CHARS = 20000;

    // ================= USB permission receiver =================
    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;

            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);

            if (device == null) {
                log("Permission USB: device=null");
                return;
            }

            if (granted) {
                log("Permission USB accordée: " + usbLabel(device));
                UsbSerialPort p = tryOpenDevice(device);
                if (p != null) setPort(p);
            } else {
                log("Permission USB REFUSÉE: " + usbLabel(device));
            }
        }
    };

    // ================= Lifecycle =================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindUI();
        applyDefaultValues();
        installHandlers();

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags = PendingIntent.FLAG_MUTABLE;
        }
        usbPermissionIntent = PendingIntent.getBroadcast(
                this, 0, new Intent(ACTION_USB_PERMISSION), piFlags
        );
        registerReceiver(usbPermissionReceiver, new IntentFilter(ACTION_USB_PERMISSION));

        LcpLink.DUMP_TX = switchIoLog.isChecked();
        LcpLink.DUMP_RX = switchIoLog.isChecked();

        txtPrinterStatus.setText("Imprimante: (non connecté / non lu)");
        txtPrinterStatus.setBackgroundColor(0xFFEEEEEE);

        txtLive.setText("LIVE: (en attente)");
        txtQtyNet.setText("NET: 0.0");
        txtQtyGross.setText("GROSS: 0.0");

        btnContinue.setEnabled(false);
        btnFinish.setEnabled(false);

        log("Prêt. 1) Choisir USB 2) Ouvrir/Ping 3) Connect (LCP).");
        new Handler().postDelayed(this::scanUsbDevices, 400);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(usbPermissionReceiver); } catch (Exception ignored) {}
    }

    // ================= Bind UI =================
    private void bindUI() {
        edtTo = findViewById(R.id.edtTo);
        edtFrom = findViewById(R.id.edtFrom);
        edtProduct = findViewById(R.id.edtProduct);
        edtPreset = findViewById(R.id.edtPreset);

        btnConnect = findViewById(R.id.btnConnect);
        btnA = findViewById(R.id.btnA);
        btnB = findViewById(R.id.btnB);
        btnC = findViewById(R.id.btnC);
        btnContinue = findViewById(R.id.btnContinue);
        btnFinish = findViewById(R.id.btnFinish);

        switchIoLog = findViewById(R.id.switchIoLog);
        txtLog = findViewById(R.id.txtLog);
        logScroll = findViewById(R.id.logScroll);

        txtLive = findViewById(R.id.txtLive);
        txtQtyNet = findViewById(R.id.txtQtyNet);
        txtQtyGross = findViewById(R.id.txtQtyGross);

        btnCopyLog = findViewById(R.id.btnCopyLog);
        btnClearLog = findViewById(R.id.btnClearLog);

        spnUsbDevices = findViewById(R.id.spnUsbDevices);
        btnScanUsb = findViewById(R.id.btnScanUsb);
        btnPingUsb = findViewById(R.id.btnPingUsb);

        txtTicketNumber = findViewById(R.id.txtTicketNumber);
        spnTicketRequired = findViewById(R.id.spnTicketRequired);
        btnRefreshTicket = findViewById(R.id.btnRefreshTicket);
        btnClearShift = findViewById(R.id.btnClearShift);

        txtPrinterStatus = findViewById(R.id.txtPrinterStatus);
        btnRefreshPrinter = findViewById(R.id.btnRefreshPrinter);
        btnPrintPending = findViewById(R.id.btnPrintPending);
    }

    // ================= Defaults =================
    private void applyDefaultValues() {
        edtTo.setText("0xFA");
        edtFrom.setText("0xFF");
        edtProduct.setText("1");
        edtPreset.setText("50.0");

        switchIoLog.setChecked(true);

        ArrayAdapter<String> ticketReqAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                new String[]{
                        "0 = Oui (ticket requis)",
                        "1 = Non (ticket optionnel)",
                        "2 = Jamais (ne pas imprimer)"
                }
        );
        ticketReqAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spnTicketRequired.setAdapter(ticketReqAdapter);
        spnTicketRequired.setSelection(1);

        txtTicketNumber.setText("-");
    }

    // ================= Handlers =================
    private void installHandlers() {
        switchIoLog.setOnCheckedChangeListener((b, checked) -> {
            LcpLink.DUMP_TX = checked;
            LcpLink.DUMP_RX = checked;
            log("[UI] I/O logs " + (checked ? "ON" : "OFF"));
        });

        btnScanUsb.setOnClickListener(v -> scanUsbDevices());
        btnPingUsb.setOnClickListener(v -> openSelectedUsbDevice());
        btnConnect.setOnClickListener(v -> initLcp());

        btnCopyLog.setOnClickListener(v -> {
            ClipboardManager clip = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clip.setPrimaryClip(ClipData.newPlainText("log", txtLog.getText().toString()));
            log("Log copié.");
        });

        btnClearLog.setOnClickListener(v -> {
            txtLog.setText("");
            synchronized (logLock) {
                logBuf.setLength(0);
                flushScheduled = false;
            }
        });

        btnA.setOnClickListener(v -> {
            if (ctrl == null) return;
            log("A : END");
            ctrl.endGracefully(20000, POLL_MS);
        });

        btnB.setOnClickListener(v -> {
            if (ctrl == null) return;
            log("PING");
            ctrl.pingStatus(POLL_MS);
        });

        btnC.setOnClickListener(v -> {
            if (ctrl == null) return;
            int product = readInt(edtProduct, 1);
            double preset = readDouble(edtPreset, 0);
            log("C : Start Delivery (product=" + product + ", preset=" + preset + ")");
            ctrl.startOpenMode(product, preset, 20000, POLL_MS);
        });

        btnContinue.setOnClickListener(v -> {
            if (ctrl == null) return;
            log("Continuer → Cmd#0");
            ctrl.resumeDelivery(POLL_MS);
        });

        btnFinish.setOnClickListener(v -> {
            if (ctrl == null) return;
            log("Terminer → END");
            ctrl.endGracefully(20000, POLL_MS);
        });

        btnRefreshTicket.setOnClickListener(v -> {
            if (ctrl == null) return;
            ctrl.refreshTicketInfo(POLL_MS);
        });

        spnTicketRequired.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            boolean first = true;
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (first) { first = false; return; }
                if (ctrl != null) ctrl.setTicketRequired(position, POLL_MS);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        btnClearShift.setOnClickListener(v -> {
            if (ctrl == null) return;
            log("SHIFT : ClearShift");
            ctrl.clearShiftNow(POLL_MS);
        });

        btnRefreshPrinter.setOnClickListener(v -> {
            if (ctrl == null) return;
            ctrl.refreshPrinterStatus(POLL_MS);
        });

        btnPrintPending.setOnClickListener(v -> {
            if (ctrl == null) return;
            log("PRINT PENDING");
            ctrl.printPendingTicket(POLL_MS, 25000);
        });
    }

    // ================= UI state =================
    private void setUiForState(DeliveryController.State s) {
        boolean running = (s == DeliveryController.State.RUNNING);
        boolean startingOrPre =
                (s == DeliveryController.State.STARTING ||
                 s == DeliveryController.State.PRESTART ||
                 s == DeliveryController.State.ENDING);

        btnC.setEnabled(!running && !startingOrPre);
        btnFinish.setEnabled(running || startingOrPre);

        btnRefreshPrinter.setEnabled(!running);
        btnPrintPending.setEnabled(!running);
        spnTicketRequired.setEnabled(!running);
        btnClearShift.setEnabled(!running);

        btnContinue.setEnabled(false); // piloté par deliveryState
    }

    // ================= Init LCP + auto-recovery =================
    private void initLcp() {
        if (port == null) {
            log("ERR: Port USB non initialisé.");
            return;
        }
        try {
            int to = parseHex(edtTo, 0xFA);
            int from = parseHex(edtFrom, 0xFF);
            log(String.format("Init LCP → to=0x%02X, from=0x%02X", to, from));

            link = new LcpLink(port, to, from, true);
            LcpLink.setLogger(line -> log("[IO] " + line));

            ctrl = new DeliveryController(
                    link,
                    new DeliveryEventsImpl(),
                    Executors.newSingleThreadExecutor()
            );

            log("LCP prêt.");

            ctrl.ensureDefaultTicketRequiredIs1(POLL_MS);
            ctrl.refreshTicketInfo(POLL_MS);
            ctrl.refreshPrinterStatus(POLL_MS);

            // ✅ auto-recovery si livraison déjà active
            ctrl.recoverActiveDelivery(POLL_MS);

        } catch (Exception e) {
            log("Erreur init LCP : " + e.getMessage());
        }
    }

    // ================= Logging =================
    private void log(String s) {
        if (!switchIoLog.isChecked()) return;

        if (s != null && s.startsWith("[IO]")) {
            boolean keep = s.contains("TX:") || s.contains("RX:");
            if (!keep) return;
        }

        synchronized (logLock) {
            logBuf.append(s).append('\n');
            if (logBuf.length() > LOG_MAX_CHARS) {
                logBuf.delete(0, logBuf.length() - LOG_MAX_CHARS);
            }
            if (!flushScheduled) {
                flushScheduled = true;
                uiHandler.postDelayed(this::flushLogToUi, LOG_FLUSH_MS);
            }
        }
    }

    private void flushLogToUi() {
        final String chunk;
        synchronized (logLock) {
            flushScheduled = false;
            if (logBuf.length() == 0) return;
            chunk = logBuf.toString();
            logBuf.setLength(0);
        }

        txtLog.append(chunk);

        int extra = txtLog.length() - LOG_MAX_CHARS;
        if (extra > 0) {
            txtLog.setText(txtLog.getText().subSequence(extra, txtLog.length()));
        }

        if (logScroll != null) {
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    // ================= Helpers =================
    private int parseHex(EditText edt, int def) {
        try {
            String t = edt.getText().toString().trim();
            if (t.startsWith("0x") || t.startsWith("0X"))
                return Integer.parseInt(t.substring(2), 16) & 0xFF;
            if (!t.isEmpty())
                return Integer.parseInt(t, 16) & 0xFF;
        } catch (Exception ignored) {}
        return def;
    }

    private int readInt(EditText edt, int def) {
        try { return Integer.parseInt(edt.getText().toString().trim()); }
        catch (Exception ignored) { return def; }
    }

    private double readDouble(EditText edt, double def) {
        try { return Double.parseDouble(edt.getText().toString().trim()); }
        catch (Exception ignored) { return def; }
    }

    private static String usbLabel(UsbDevice d) {
        String m = d.getManufacturerName();
        String p = d.getProductName();
        if (m == null) m = "?";
        if (p == null) p = "?";
        return String.format("%s - %s (VID=%04X PID=%04X)",
                m, p, d.getVendorId(), d.getProductId());
    }

    private void scanUsbDevices() {
        usbList.clear();
        if (usbManager == null) return;

        usbList.addAll(usbManager.getDeviceList().values());
        List<String> labels = new ArrayList<>();
        for (UsbDevice d : usbList) labels.add(usbLabel(d));

        spnUsbDevices.setAdapter(new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels
        ));
        log("Scan USB : " + labels.size() + " périphérique(s).");
    }

    private void openSelectedUsbDevice() {
        int index = spnUsbDevices.getSelectedItemPosition();
        if (index < 0 || index >= usbList.size()) return;

        UsbDevice dev = usbList.get(index);

        if (usbManager != null && !usbManager.hasPermission(dev)) {
            usbManager.requestPermission(dev, usbPermissionIntent);
            return;
        }

        UsbSerialPort p = tryOpenDevice(dev);
        if (p != null) setPort(p);
    }

    private UsbSerialPort tryOpenDevice(UsbDevice dev) {
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(dev);
        if (driver == null) return null;

        UsbSerialPort p = driver.getPorts().get(0);
        UsbDeviceConnection conn = usbManager.openDevice(dev);
        if (conn == null) return null;

        try {
            p.open(conn);
            p.setParameters(19200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            log("Port USB ouvert : " + usbLabel(dev));
            return p;
        } catch (Exception e) {
            try { p.close(); } catch (Exception ignored) {}
            return null;
        }
    }

    public void setPort(UsbSerialPort p) {
        this.port = p;
        log("USB prêt.");
    }

    // ================= Delivery Events =================
    private class DeliveryEventsImpl implements DeliveryController.DeliveryEvents {

        @Override
        public void onStateChanged(DeliveryController.State s) {
            log("État = " + s);
            runOnUiThread(() -> setUiForState(s));
        }

        @Override
        public void onFlowStarted() {
            log("Flow START");
        }

        @Override
        public void onFlowStopped() {
            log("Flow STOP → pause confirmée");
        }

        @Override
        public void onProgress(DeliveryController.DeliveryProgress p) {
            final String live = String.format(
                    "STATE=%s NET=%.1f GROSS=%.1f FLOW=%s",
                    p.deliveryState,
                    p.netL,
                    p.grossL,
                    p.flowActive ? "1" : "0"
            );

            runOnUiThread(() -> {
                txtLive.setText(live);
                txtQtyNet.setText(String.format("NET: %.1f", p.netL));
                txtQtyGross.setText(String.format("GROSS: %.1f", p.grossL));

                if (p.deliveryState == LcpDeliveryState.ACTIVE_FLOWING) {
                    btnContinue.setEnabled(false);
                    btnFinish.setEnabled(true);
                } else if (p.deliveryState == LcpDeliveryState.ACTIVE_PAUSED) {
                    btnContinue.setEnabled(true);
                    btnFinish.setEnabled(true);
                } else {
                    btnContinue.setEnabled(false);
                    btnFinish.setEnabled(false);
                }
            });
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
            final String text =
                    "Imprimante: " + ms.printer().summary() +
                    " \n ticketPending=" + ticketPending +
                    " \n ds=0x" + String.format("%04X", ms.delStatus) +
                    " dc=0x" + String.format("%04X", ms.delCode);

            runOnUiThread(() -> {
                txtPrinterStatus.setText(text);
                boolean hardErr =
                        ms.printer().outOfPaper ||
                        ms.printer().noProcessor ||
                        ms.printer().processorError;
                boolean printing = ms.printer().printingStarted;

                int bg;
                if (hardErr) bg = 0xFFFFCDD2;
                else if (printing) bg = 0xFFFFF9C4;
                else bg = 0xFFC8E6C9;

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
