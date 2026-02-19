
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
import android.os.Looper;
import android.view.View;
import android.widget.*;

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
    private Button btnConnect, btnA, btnB, btnC, btnContinue, btnFinish;
    private Button btnCopyLog, btnClearLog;
    private CheckBox switchIoLog;
    private TextView txtLog, txtLive, txtQtyNet, txtQtyGross;
    private ScrollView logScroll;

    // ================= USB =================
    private Spinner spnUsbDevices;
    private Button btnScanUsb, btnPingUsb;

    // ================= Ticket / Printer =================
    private TextView txtTicketNumber, txtPrinterStatus;
    private Spinner spnTicketRequired;
    private Button btnRefreshTicket, btnClearShift, btnRefreshPrinter, btnPrintPending;

    // ================= Backend =================
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

    // ================= USB Permission Receiver =================
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) piFlags = PendingIntent.FLAG_MUTABLE;
        usbPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), piFlags);
        registerReceiver(usbPermissionReceiver, new IntentFilter(ACTION_USB_PERMISSION));

        log("Prêt. 1) Choisir USB 2) Ouvrir/Ping 3) Connect (LCP).");
        new Handler().postDelayed(this::scanUsbDevices, 300);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(usbPermissionReceiver); } catch (Exception ignored) {}
    }

    // ================= Init LCP (CORRIGÉ) =================
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
            LcpLink.setLogger(s -> log("[IO] " + s));

            // ✅ CORRECTIF CRITIQUE
            link.openPollWindow();

            ctrl = new DeliveryController(
                    link,
                    new DeliveryEventsImpl(),
                    Executors.newSingleThreadExecutor()
            );

            log("LCP prêt.");
            ctrl.recoverActiveDelivery(POLL_MS);

        } catch (Exception e) {
            log("Erreur init LCP : " + e.getMessage());
        }
    }

    // ================= UI wiring =================
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

        txtLog = findViewById(R.id.txtLog);
        logScroll = findViewById(R.id.logScroll);
        txtLive = findViewById(R.id.txtLive);
        txtQtyNet = findViewById(R.id.txtQtyNet);
        txtQtyGross = findViewById(R.id.txtQtyGross);

        spnUsbDevices = findViewById(R.id.spnUsbDevices);
        btnScanUsb = findViewById(R.id.btnScanUsb);
        btnPingUsb = findViewById(R.id.btnPingUsb);

        txtTicketNumber = findViewById(R.id.txtTicketNumber);
        txtPrinterStatus = findViewById(R.id.txtPrinterStatus);
        btnClearShift = findViewById(R.id.btnClearShift);
        btnPrintPending = findViewById(R.id.btnPrintPending);
    }

    private void applyDefaultValues() {
        edtTo.setText("0xFA");
        edtFrom.setText("0xFF");
        edtProduct.setText("1");
        edtPreset.setText("50.0");
    }

    private void installHandlers() {
        btnConnect.setOnClickListener(v -> initLcp());
        btnScanUsb.setOnClickListener(v -> scanUsbDevices());
        btnPingUsb.setOnClickListener(v -> openSelectedUsb());

        btnC.setOnClickListener(v -> {
            if (ctrl == null) return;
            ctrl.startOpenMode(readInt(edtProduct, 1), readDouble(edtPreset, 0), 20000, POLL_MS);
        });

        btnContinue.setOnClickListener(v -> {
            if (ctrl != null) ctrl.resumeDelivery(POLL_MS);
        });

        btnFinish.setOnClickListener(v -> {
            if (ctrl != null) ctrl.endGracefully(20000, POLL_MS);
        });
    }

    // ================= Delivery Events =================
    private class DeliveryEventsImpl implements DeliveryController.DeliveryEvents {
        @Override public void onStateChanged(DeliveryController.State s) { log("État=" + s); }
        @Override public void onFlowStarted() { log("Flow START"); }
        @Override public void onFlowStopped() { log("Flow STOP"); }

        @Override
        public void onProgress(DeliveryController.DeliveryProgress p) {
            runOnUiThread(() -> {
                txtLive.setText("STATE=" + p.deliveryState);
                txtQtyNet.setText("NET=" + p.netL);
                txtQtyGross.setText("GROSS=" + p.grossL);
            });
        }

        @Override public void onTicketNumber(int n) {}
        @Override public void onTicketRequired(int m) {}
        @Override public void onPrinterStatus(LcpLink.MachineStatusEx ms, boolean t) {}
        @Override public void onError(String m, Throwable t) { log("ERR " + m); }
        @Override public void onLog(String l) { log(l); }
    }

    // ================= Helpers =================
    public void setPort(UsbSerialPort p) { port = p; log("USB prêt."); }

    private void scanUsbDevices() {
        usbList.clear();
        usbList.addAll(usbManager.getDeviceList().values());
        List<String> labels = new ArrayList<>();
        for (UsbDevice d : usbList) labels.add(usbLabel(d));
        spnUsbDevices.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels));
        log("Scan USB : " + labels.size() + " périphérique(s).");
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
            log("Port USB ouvert : " + usbLabel(dev));
            return p;
        } catch (Exception e) {
            return null;
        }
    }

    private static String usbLabel(UsbDevice d) {
        return String.format("%s (VID=%04X PID=%04X)",
                d.getProductName(), d.getVendorId(), d.getProductId());
    }

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
