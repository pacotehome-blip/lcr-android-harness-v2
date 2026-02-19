
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
import com.pa.lcr.lcp.lifecycle.LcpDeliveryState;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    // ================= UI =================
    private EditText edtTo, edtFrom, edtProduct, edtPreset;
    private Button btnConnect, btnA, btnB, btnC, btnContinue, btnFinish;
    private Button btnScanUsb, btnPingUsb, btnClearShift, btnPrintPending;
    private TextView txtLog, txtLive, txtQtyNet, txtQtyGross, txtPrinterStatus;
    private ScrollView logScroll;
    private Spinner spnUsbDevices;

    // ================= USB / LCP =================
    private UsbManager usbManager;
    private final List<UsbDevice> usbList = new ArrayList<>();
    private UsbSerialPort port;
    private LcpLink link;
    private DeliveryController ctrl;

    private static final int POLL_MS = 200;
    private static final String ACTION_USB_PERMISSION = "com.pa.lcrdemo.USB_PERMISSION";
    private PendingIntent usbPermissionIntent;

    // ================= Logging =================
    private final StringBuilder logBuf = new StringBuilder(8192);
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    // ================= USB Permission =================
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
                if (port == null) {
                    UsbSerialPort p = tryOpenDevice(device);
                    if (p != null) setPort(p);
                } else {
                    log("USB déjà ouvert, permission ignorée");
                }
            } else {
                log("Permission USB REFUSÉE");
            }
        }
    };

    // ================= USB DETACH =================
    private final BroadcastReceiver usbDetachReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
                log("USB DETACHED");
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
        installHandlers();

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            piFlags = PendingIntent.FLAG_MUTABLE;

        usbPermissionIntent = PendingIntent.getBroadcast(
                this, 0, new Intent(ACTION_USB_PERMISSION), piFlags
        );

        registerReceiver(usbPermissionReceiver, new IntentFilter(ACTION_USB_PERMISSION));
        registerReceiver(usbDetachReceiver, new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED));

        log("Prêt. 1) Choisir USB 2) Ouvrir USB 3) Connect (LCP)");
        new Handler().postDelayed(this::scanUsbDevices, 300);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(usbPermissionReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(usbDetachReceiver); } catch (Exception ignored) {}
    }

    // ================= Init LCP =================
    private void initLcp() {
        if (port == null) {
            log("ERR: Port USB non initialisé");
            return;
        }
        if (link != null) {
            log("LCP déjà initialisé");
            return;
        }

        try {
            int to = parseHex(edtTo, 0xFA);
            int from = parseHex(edtFrom, 0xFF);
            log(String.format("Init LCP → to=0x%02X, from=0x%02X", to, from));

            link = new LcpLink(port, to, from, true);
            LcpLink.setLogger(s -> log("[IO] " + s));

            // ✅ CRITIQUE
            link.openPollWindow();

            ctrl = new DeliveryController(
                    link,
                    new DeliveryEventsImpl(),
                    Executors.newSingleThreadExecutor()
            );

            disableUsbUi();
            log("LCP prêt");
            ctrl.recoverActiveDelivery(POLL_MS);

        } catch (Exception e) {
            log("Init LCP failed: " + e.getMessage());
            link = null;
        }
    }

    // ================= USB DETACH HANDLER =================
    private void onUsbDetached() {
        runOnUiThread(() -> {
            log("USB débranché → attente reconnexion");

            try { if (port != null) port.close(); } catch (Exception ignored) {}
            port = null;
            link = null;
            ctrl = null;

            enableUsbUi();
            disableLcpUi();

            txtLive.setText("USB débranché – en attente");
            txtQtyNet.setText("NET: -");
            txtQtyGross.setText("GROSS: -");
        });
    }

    // ================= UI Wiring =================
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
        btnScanUsb = findViewById(R.id.btnScanUsb);
        btnPingUsb = findViewById(R.id.btnPingUsb);
        btnClearShift = findViewById(R.id.btnClearShift);
        btnPrintPending = findViewById(R.id.btnPrintPending);

        txtLog = findViewById(R.id.txtLog);
        logScroll = findViewById(R.id.logScroll);
        txtLive = findViewById(R.id.txtLive);
        txtQtyNet = findViewById(R.id.txtQtyNet);
        txtQtyGross = findViewById(R.id.txtQtyGross);
        txtPrinterStatus = findViewById(R.id.txtPrinterStatus);

        spnUsbDevices = findViewById(R.id.spnUsbDevices);
    }

    private void applyDefaults() {
        edtTo.setText("0xFA");
        edtFrom.setText("0xFF");
        edtProduct.setText("1");
        edtPreset.setText("50.0");
        disableLcpUi();
    }

    private void installHandlers() {
        btnScanUsb.setOnClickListener(v -> scanUsbDevices());
        btnPingUsb.setOnClickListener(v -> openSelectedUsb());
        btnConnect.setOnClickListener(v -> initLcp());

        btnC.setOnClickListener(v -> {
            if (ctrl != null)
                ctrl.startOpenMode(readInt(edtProduct, 1), readDouble(edtPreset, 0), 20000, POLL_MS);
        });

        btnContinue.setOnClickListener(v -> {
            if (ctrl != null) ctrl.resumeDelivery(POLL_MS);
        });

        btnFinish.setOnClickListener(v -> {
            if (ctrl != null) ctrl.endGracefully(20000, POLL_MS);
        });
    }

    // ================= UI Enable / Disable =================
    private void disableUsbUi() {
        btnScanUsb.setEnabled(false);
        btnPingUsb.setEnabled(false);
        spnUsbDevices.setEnabled(false);
    }

    private void enableUsbUi() {
        btnScanUsb.setEnabled(true);
        btnPingUsb.setEnabled(true);
        spnUsbDevices.setEnabled(true);
    }

    private void disableLcpUi() {
        btnA.setEnabled(false);
        btnB.setEnabled(false);
        btnC.setEnabled(false);
        btnContinue.setEnabled(false);
        btnFinish.setEnabled(false);
        btnClearShift.setEnabled(false);
        btnPrintPending.setEnabled(false);
    }

    // ================= Delivery Events =================
    private class DeliveryEventsImpl implements DeliveryController.DeliveryEvents {
        @Override public void onStateChanged(DeliveryController.State s) {
            log("État=" + s);
        }

        @Override public void onFlowStarted() { log("Flow START"); }
        @Override public void onFlowStopped() { log("Flow STOP"); }

        @Override
        public void onProgress(DeliveryController.DeliveryProgress p) {
            runOnUiThread(() -> {
                txtLive.setText("STATE=" + p.deliveryState);
                txtQtyNet.setText("NET=" + p.netL);
                txtQtyGross.setText("GROSS=" + p.grossL);

                if (p.deliveryState == LcpDeliveryState.ACTIVE_FLOWING) {
                    btnContinue.setEnabled(false);
                    btnFinish.setEnabled(true);
                } else if (p.deliveryState == LcpDeliveryState.ACTIVE_PAUSED) {
                    btnContinue.setEnabled(true);
                    btnFinish.setEnabled(true);
                }
            });
        }

        @Override public void onTicketNumber(int n) {}
        @Override public void onTicketRequired(int m) {}
        @Override public void onPrinterStatus(LcpLink.MachineStatusEx ms, boolean t) {
            txtPrinterStatus.setText(ms.toString());
        }
        @Override public void onError(String m, Throwable t) { log("ERR " + m); }
        @Override public void onLog(String l) { log(l); }
    }

    // ================= USB Helpers =================
    public void setPort(UsbSerialPort p) {
        port = p;
        log("USB prêt");
    }

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
            p.setDTR(true);
            p.setRTS(true);
            log("Port USB ouvert : " + usbLabel(dev));
            return p;
        } catch (Exception e) {
            log("Open USB failed: " + e.getMessage());
            return null;
        }
    }

    private static String usbLabel(UsbDevice d) {
        return String.format("%s (VID=%04X PID=%04X)",
                d.getProductName(), d.getVendorId(), d.getProductId());
    }

    // ================= Utils =================
    private static int parseHex(EditText e, int def) {
        try {
            String s = e.getText().toString().trim();
            if (s.startsWith("0x")) return Integer.parseInt(s.substring(2), 16);
            return Integer.parseInt(s, 16);
        } catch (Exception ex) { return def; }
    }

    private static int readInt(EditText e, int def) {
        try { return Integer.parseInt(e.getText().toString()); }
        catch (Exception ex) { return def; }
    }

    private static double readDouble(EditText e, double def) {
        try { return Double.parseDouble(e.getText().toString()); }
        catch (Exception ex) { return def; }
    }

    private void log(String s) {
        uiHandler.post(() -> {
            logBuf.append(s).append('\n');
            txtLog.setText(logBuf.toString());
            logScroll.fullScroll(View.FOCUS_DOWN);
        });
    }
}
