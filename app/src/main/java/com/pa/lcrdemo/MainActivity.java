
package com.pa.lcrdemo;

import androidx.appcompat.app.AppCompatActivity;

import android.app.PendingIntent;
import android.content.*;
import android.hardware.usb.*;
import android.os.*;
import android.view.View;
import android.widget.*;

import com.hoho.android.usbserial.driver.*;
import com.pa.lcr.lcp.DeliveryController;
import com.pa.lcr.lcp.LcpLink;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * MainActivity — UI complète (selon ton activity_main.xml) + correctifs actuels :
 * - Logs TX/RX payload (switchIoLog -> LcpLink.DUMP_TX/DUMP_RX)
 * - Produit actif + bascule à la sélection spinner (ctrl.selectProductFromUi)
 * - btnC désactivé pendant RUNNING (flowing/paused)
 * - btnContinue actif uniquement si paused (resumeIfPaused)
 * - btnFinish actif si running (endDelivery)
 * - log auto-scroll + buffer borné
 */
public class MainActivity extends AppCompatActivity {

    // ================= UI =================
    private Spinner spnUsbDevices, spnProducts, spnTicketRequired;
    private EditText edtTo, edtFrom, edtProduct, edtPreset;

    private Button btnScanUsb, btnPingUsb, btnConnect;
    private Button btnA, btnB, btnC, btnContinue, btnFinish;

    private TextView txtLive, txtQtyNet, txtQtyGross;

    private TextView txtTicketNumber, txtPrinterStatus;
    private Button btnRefreshTicket, btnClearShift, btnRefreshPrinter, btnPrintPending;

    private CheckBox switchIoLog;

    private ScrollView logScroll;
    private TextView txtLog;
    private Button btnClearLog, btnCopyLog;

    // ================= USB / LCP =================
    private UsbManager usbManager;
    private final List<UsbDevice> usbList = new ArrayList<>();
    private UsbSerialPort port;
    private UsbDevice currentDevice;

    private LcpLink link;
    private DeliveryController ctrl;

    private static final int POLL_MS = 200; // poll argument pour startOpenMode (ton choix 0.2s)
    private static final String ACTION_USB_PERMISSION = "com.pa.lcrdemo.USB_PERMISSION";
    private PendingIntent usbPermissionIntent;

    // ================= Logging =================
    private final StringBuilder logBuf = new StringBuilder(30000);
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    // Empêche un onItemSelected “rebond” lors d’un setSelection programmatique
    private boolean suppressProductSelect = false;

    // ================= USB Permission =================
    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;

            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);

            if (device == null) {
                log("Permission USB: device=null (ignored)");
                return;
            }

            if (granted && port == null) {
                UsbSerialPort p = tryOpenDevice(device);
                if (p != null) setPort(p, device);
            }
        }
    };

    // ================= USB DETACH =================
    private final BroadcastReceiver usbDetachReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) return;

            UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (dev == null || currentDevice == null) return;

            if (dev.getVendorId() == currentDevice.getVendorId()
                    && dev.getProductId() == currentDevice.getProductId()) {
                log("USB DETACHED (port LCP)");
                onUsbDetached();
            }
        }
    };

    // ================= Lifecycle =================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindUI();
        applyDefaults();
        wireHandlers();

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = PendingIntent.FLAG_MUTABLE;

        usbPermissionIntent = PendingIntent.getBroadcast(
                this, 0, new Intent(ACTION_USB_PERMISSION), flags
        );

        registerReceiver(usbPermissionReceiver, new IntentFilter(ACTION_USB_PERMISSION));
        registerReceiver(usbDetachReceiver, new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED));

        log("Prêt. 1) Choisir USB 2) Ouvrir USB 3) Connect (LCP)");
        new Handler().postDelayed(this::scanUsbDevices, 250);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(usbPermissionReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(usbDetachReceiver); } catch (Exception ignored) {}
    }

    // =========================================================
    // INIT LCP
    // =========================================================
    private void initLcp() {
        if (port == null) {
            log("ERR: Port USB non initialisé");
            return;
        }
        if (link != null) {
            log("LCP déjà initialisé");
            return;
        }

        int to = parseHex(edtTo, 0xFA);
        int from = parseHex(edtFrom, 0xFF);
        log("Init LCP → LCRNode=" + fmtNode(to) + ", Host=" + fmtNode(from));

        link = new LcpLink(port, to, from, true);

        // Logger
        LcpLink.setLogger(s -> log("[IO] " + s));

        // Appliquer switch I/O log
        applyIoLogSwitch();

        link.openPollWindow();
        log("LCP prêt — connecté au LCRNode " + fmtNode(to));

        ctrl = new DeliveryController(link, new DeliveryEventsImpl(), Executors.newSingleThreadExecutor());

        // Produit par défaut = product-get-active + code + set selection UI
        ctrl.refreshProductsUi();

        btnConnect.setEnabled(false);
    }

    private void onUsbDetached() {
        runOnUiThread(() -> {
            log("USB débranché → arrêt LCP");

            if (ctrl != null) {
                ctrl.shutdown();
                ctrl = null;
            }
            if (link != null) {
                try { link.closePollWindow(); } catch (Exception ignored) {}
                link = null;
            }
            try { if (port != null) port.close(); } catch (Exception ignored) {}
            port = null;
            currentDevice = null;

            btnConnect.setEnabled(true);
        });
    }

    // =========================================================
    // DeliveryEvents (Controller -> UI)
    // =========================================================
    private final class DeliveryEventsImpl implements DeliveryController.DeliveryEvents {

        @Override
        public void onStateChanged(DeliveryController.State s) {
            log("État=" + s);

            // Affichage LIVE
            txtLive.setText("STATE: " + s);

            // ✅ Règles boutons:
            // - btnC désactivé pendant RUNNING (flowing OU paused)
            // - btnContinue actif uniquement si paused
            // - btnFinish actif si running
            boolean runningFlow = (s == DeliveryController.State.RUNNING_FLOWING);
            boolean runningPause = (s == DeliveryController.State.RUNNING_PAUSED);
            boolean running = runningFlow || runningPause;

            btnC.setEnabled(!running
                    && s != DeliveryController.State.PRESTART
                    && s != DeliveryController.State.STARTING
                    && s != DeliveryController.State.ENDING);

            btnContinue.setEnabled(runningPause);
            btnFinish.setEnabled(running);

            // Optionnel: A/B restent toujours possibles
        }

        @Override
        public void onError(String msg, Throwable t) {
            log("ERR[" + msg + "] " + (t != null ? t.getMessage() : ""));
        }

        @Override
        public void onLog(String line) {
            log(line);
        }

        @Override
        public void onProducts(List<DeliveryController.ProductUiItem> items, int selectedIndex0) {
            runOnUiThread(() -> {
                suppressProductSelect = true;
                try {
                    ArrayAdapter<DeliveryController.ProductUiItem> adapter =
                            new ArrayAdapter<>(MainActivity.this,
                                    android.R.layout.simple_spinner_item,
                                    items);
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                    spnProducts.setAdapter(adapter);

                    if (selectedIndex0 >= 0 && selectedIndex0 < items.size()) {
                        spnProducts.setSelection(selectedIndex0);
                    }
                } finally {
                    suppressProductSelect = false;
                }
            });
        }
    }

    // =========================================================
    // UI binding / defaults / handlers
    // =========================================================
    private void bindUI() {
        spnUsbDevices = findViewById(R.id.spnUsbDevices);
        btnScanUsb = findViewById(R.id.btnScanUsb);
        btnPingUsb = findViewById(R.id.btnPingUsb);

        edtTo = findViewById(R.id.edtTo);
        edtFrom = findViewById(R.id.edtFrom);

        spnProducts = findViewById(R.id.spnProducts);
        edtProduct = findViewById(R.id.edtProduct);
        edtPreset = findViewById(R.id.edtPreset);

        btnConnect = findViewById(R.id.btnConnect);

        btnA = findViewById(R.id.btnA);
        btnB = findViewById(R.id.btnB);
        btnC = findViewById(R.id.btnC);
        btnContinue = findViewById(R.id.btnContinue);
        btnFinish = findViewById(R.id.btnFinish);

        txtLive = findViewById(R.id.txtLive);
        txtQtyNet = findViewById(R.id.txtQtyNet);
        txtQtyGross = findViewById(R.id.txtQtyGross);

        txtTicketNumber = findViewById(R.id.txtTicketNumber);
        spnTicketRequired = findViewById(R.id.spnTicketRequired);
        btnRefreshTicket = findViewById(R.id.btnRefreshTicket);
        btnClearShift = findViewById(R.id.btnClearShift);

        txtPrinterStatus = findViewById(R.id.txtPrinterStatus);
        btnRefreshPrinter = findViewById(R.id.btnRefreshPrinter);
        btnPrintPending = findViewById(R.id.btnPrintPending);

        switchIoLog = findViewById(R.id.switchIoLog);

        logScroll = findViewById(R.id.logScroll);
        txtLog = findViewById(R.id.txtLog);
        btnClearLog = findViewById(R.id.btnClearLog);
        btnCopyLog = findViewById(R.id.btnCopyLog);
    }

    private void applyDefaults() {
        edtTo.setText("0xFA");
        edtFrom.setText("0xFF");
        edtPreset.setText("50.0");
        edtProduct.setText("");

        // Spinner produits init 1..16 (sera remplacé par onProducts)
        List<DeliveryController.ProductUiItem> init = new ArrayList<>();
        for (int i = 1; i <= 16; i++) init.add(new DeliveryController.ProductUiItem(i, "Produit " + i));
        ArrayAdapter<DeliveryController.ProductUiItem> adapter =
                new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, init);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spnProducts.setAdapter(adapter);

        // TicketRequired spinner placeholder
        ArrayAdapter<String> tr = new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item,
                new String[]{"Yes", "No", "Never"}
        );
        tr.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spnTicketRequired.setAdapter(tr);

        txtLive.setText("LIVE: (en attente)");
        txtQtyNet.setText("NET: 0.0");
        txtQtyGross.setText("GROSS: 0.0");
        txtTicketNumber.setText("-");
        txtPrinterStatus.setText("Imprimante: (non connecté / non lu)");

        // au départ
        btnContinue.setEnabled(false);
        btnFinish.setEnabled(false);
    }

    private void wireHandlers() {

        // USB
        btnScanUsb.setOnClickListener(v -> scanUsbDevices());
        btnPingUsb.setOnClickListener(v -> openSelectedUsb());

        // Connect
        btnConnect.setOnClickListener(v -> initLcp());

        // Logs
        btnClearLog.setOnClickListener(v -> clearLog());
        btnCopyLog.setOnClickListener(v -> copyLog());

        // I/O dump
        switchIoLog.setOnCheckedChangeListener((buttonView, isChecked) -> applyIoLogSwitch());

        // A) Sélection produit à la sélection spinner (immediate)
        spnProducts.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (suppressProductSelect) return;
                if (ctrl == null) return;

                Object sel = spnProducts.getSelectedItem();
                if (sel instanceof DeliveryController.ProductUiItem) {
                    int prod = ((DeliveryController.ProductUiItem) sel).product1;
                    log("[UI] Sélection produit → prod" + prod);
                    ctrl.selectProductFromUi(prod);
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        // C (Start) : override manuel si présent
        btnC.setOnClickListener(v -> {
            if (ctrl == null) { log("ERR: LCP non initialisé"); return; }

            int manual = readInt(edtProduct, 0);
            int product1to16;

            if (manual >= 1 && manual <= 16) {
                product1to16 = manual;
                log("[PROD] Produit FORCÉ opérateur = " + product1to16);
            } else {
                Object sel = spnProducts.getSelectedItem();
                if (sel instanceof DeliveryController.ProductUiItem) {
                    product1to16 = ((DeliveryController.ProductUiItem) sel).product1;
                } else {
                    product1to16 = 1;
                }
                log("[PROD] Produit UI = " + product1to16);
            }

            double preset = readDouble(edtPreset, 0);
            log("START livraison → produit=" + product1to16 + " preset=" + preset);

            ctrl.startOpenMode(product1to16, preset, 20_000, POLL_MS);
        });

        // Continue / Finish (liés au controller)
        btnContinue.setOnClickListener(v -> {
            if (ctrl != null) ctrl.resumeIfPaused(20_000);
            else log("Continuer: LCP non initialisé");
        });

        btnFinish.setOnClickListener(v -> {
            if (ctrl != null) ctrl.endDelivery(20_000);
            else log("Terminer: LCP non initialisé");
        });

        // A/B (comme avant, safe)
        btnA.setOnClickListener(v -> {
            if (ctrl != null) ctrl.refreshProductsUi();
            else log("A: LCP non initialisé");
        });

        btnB.setOnClickListener(v -> {
            // B: refresh produits aussi (ou sync-first)
            if (ctrl != null) ctrl.refreshProductsUi();
            else log("B: LCP non initialisé");
        });

        // Ticket / Printer (stubs safe pour l’instant)
        btnRefreshTicket.setOnClickListener(v -> log("Refresh Ticket: TODO (mapping)"));
        btnClearShift.setOnClickListener(v -> log("Clear Shift: TODO (mapping)"));
        btnRefreshPrinter.setOnClickListener(v -> log("Refresh Printer: TODO (mapping)"));
        btnPrintPending.setOnClickListener(v -> {
            // Tu peux mapper plus tard à cmd#6 si tu veux
            log("Print Pending: TODO (mapping)");
        });
    }

    private void applyIoLogSwitch() {
        boolean on = (switchIoLog != null && switchIoLog.isChecked());
        LcpLink.DUMP_TX = on;
        LcpLink.DUMP_RX = on;
        log("[IO] Dump " + (on ? "ON" : "OFF"));
    }

    // =========================================================
    // USB helpers
    // =========================================================
    private void scanUsbDevices() {
        usbList.clear();
        usbList.addAll(usbManager.getDeviceList().values());

        List<String> labels = new ArrayList<>();
        for (UsbDevice d : usbList) labels.add(usbLabel(d));
        spnUsbDevices.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels));

        log("Scan USB : " + labels.size() + " périphérique(s)");
    }

    private void openSelectedUsb() {
        int idx = spnUsbDevices.getSelectedItemPosition();
        if (idx < 0 || idx >= usbList.size()) return;

        UsbDevice dev = usbList.get(idx);
        if (!usbManager.hasPermission(dev)) {
            usbManager.requestPermission(dev, usbPermissionIntent);
            return;
        }
        UsbSerialPort p = tryOpenDevice(dev);
        if (p != null) setPort(p, dev);
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
            p.setDTR(true);
            p.setRTS(true);
            log("Port USB ouvert : " + usbLabel(dev));
            return p;
        } catch (Exception e) {
            log("Open USB failed: " + e.getMessage());
            return null;
        }
    }

    public void setPort(UsbSerialPort p, UsbDevice d) {
        port = p;
        currentDevice = d;
        log("USB prêt");
    }

    // compat UsbReceiver
    public void setPort(UsbSerialPort p) {
        UsbDevice d = null;
        try { if (p != null && p.getDriver() != null) d = p.getDriver().getDevice(); } catch (Exception ignored) {}
        setPort(p, d);
    }

    private static String usbLabel(UsbDevice d) {
        String m = d.getManufacturerName();
        String p = d.getProductName();
        if (m == null) m = "Unknown manufacturer";
        if (p == null) p = "Unknown product";
        return String.format("%s - %s (VID=%04X PID=%04X)", m, p, d.getVendorId(), d.getProductId());
    }

    // =========================================================
    // Utils
    // =========================================================
    private static int parseHex(EditText e, int def) {
        try {
            String s = e.getText().toString().trim();
            if (s.startsWith("0x") || s.startsWith("0X")) return Integer.parseInt(s.substring(2), 16) & 0xFF;
            return Integer.parseInt(s, 16) & 0xFF;
        } catch (Exception ex) { return def; }
    }

    private static int readInt(EditText e, int def) {
        try { return Integer.parseInt(e.getText().toString().trim()); }
        catch (Exception ex) { return def; }
    }

    private static double readDouble(EditText e, double def) {
        try { return Double.parseDouble(e.getText().toString().trim()); }
        catch (Exception ex) { return def; }
    }

    private static String fmtNode(int addr) {
        return String.format("%d (0x%02X)", addr, addr);
    }

    // =========================================================
    // Log
    // =========================================================
    private void log(String s) {
        uiHandler.post(() -> {
            logBuf.append(s).append('\n');

            // Buffer borné pour éviter lag (on garde la fin)
            if (logBuf.length() > 30000) {
                logBuf.delete(0, logBuf.length() - 25000);
            }

            txtLog.setText(logBuf.toString());
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void clearLog() {
        uiHandler.post(() -> {
            logBuf.setLength(0);
            txtLog.setText("");
            logScroll.fullScroll(View.FOCUS_UP);
        });
    }

    private void copyLog() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("log", logBuf.toString()));
        log("Log copié");
    }
}
