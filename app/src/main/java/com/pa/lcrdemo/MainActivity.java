
package com.pa.lcrdemo;

import androidx.appcompat.app.AppCompatActivity;

import android.app.PendingIntent;
import android.content.*;
import android.hardware.usb.*;
import android.os.*;
import android.widget.*;

import com.hoho.android.usbserial.driver.*;
import com.pa.lcr.lcp.DeliveryController;
import com.pa.lcr.lcp.LcpLink;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private Spinner spnUsbDevices, spnProducts;
    private EditText edtTo, edtFrom, edtProduct, edtPreset;
    private Button btnScanUsb, btnPingUsb, btnConnect, btnC, btnClearLog, btnCopyLog;
    private TextView txtLog;
    private ScrollView logScroll;

    private UsbManager usbManager;
    private final List<UsbDevice> usbList = new ArrayList<>();
    private UsbSerialPort port;
    private UsbDevice currentDevice;

    private LcpLink link;
    private DeliveryController ctrl;

    private static final int POLL_MS = 200;
    private static final String ACTION_USB_PERMISSION = "com.pa.lcrdemo.USB_PERMISSION";
    private PendingIntent usbPermissionIntent;

    private final StringBuilder logBuf = new StringBuilder(16384);
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (device == null) { log("Permission USB: device=null (ignored)"); return; }
            if (granted && port == null) {
                UsbSerialPort p = tryOpenDevice(device);
                if (p != null) setPort(p, device);
            }
        }
    };

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

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindUI();

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = PendingIntent.FLAG_MUTABLE;

        usbPermissionIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), flags);
        registerReceiver(usbPermissionReceiver, new IntentFilter(ACTION_USB_PERMISSION));
        registerReceiver(usbDetachReceiver, new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED));

        log("Prêt. 1) Choisir USB 2) Ouvrir USB 3) Connect (LCP)");
        new Handler().postDelayed(this::scanUsbDevices, 250);

        btnScanUsb.setOnClickListener(v -> scanUsbDevices());
        btnPingUsb.setOnClickListener(v -> openSelectedUsb());
        btnConnect.setOnClickListener(v -> initLcp());
        btnClearLog.setOnClickListener(v -> clearLog());
        btnCopyLog.setOnClickListener(v -> copyLog());

        btnC.setOnClickListener(v -> {
            if (ctrl == null) { log("ERR: LCP non initialisé"); return; }

            // priorité opérateur si edtProduct rempli (1..16), sinon spinner
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
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(usbPermissionReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(usbDetachReceiver); } catch (Exception ignored) {}
    }

    private void initLcp() {
        if (port == null) { log("ERR: Port USB non initialisé"); return; }
        if (link != null) { log("LCP déjà initialisé"); return; }

        int to = parseHex(edtTo, 0xFA);
        int from = parseHex(edtFrom, 0xFF);
        log("Init LCP → LCRNode=" + fmtNode(to) + ", Host=" + fmtNode(from));

        link = new LcpLink(port, to, from, true);

        // Active les dumps hex si tu veux: tu peux aussi le piloter via UI
        LcpLink.DUMP_TX = true;
        LcpLink.DUMP_RX = true;

        LcpLink.setLogger(s -> log("[IO] " + s));
        link.openPollWindow();
        log("LCP prêt — connecté au LCRNode " + fmtNode(to));

        ctrl = new DeliveryController(link, new DeliveryEventsImpl(), Executors.newSingleThreadExecutor());

        // produit par défaut = product-get-active
        ctrl.refreshProductsUi();
    }

    private void onUsbDetached() {
        runOnUiThread(() -> {
            if (ctrl != null) { ctrl.shutdown(); ctrl = null; }
            if (link != null) { try { link.closePollWindow(); } catch (Exception ignored) {} link = null; }
            try { if (port != null) port.close(); } catch (Exception ignored) {}
            port = null;
            currentDevice = null;
            log("USB débranché → arrêt LCP");
        });
    }

    private final class DeliveryEventsImpl implements DeliveryController.DeliveryEvents {
        @Override public void onStateChanged(DeliveryController.State s) { log("État=" + s); }
        @Override public void onError(String msg, Throwable t) { log("ERR[" + msg + "] " + (t != null ? t.getMessage() : "")); }
        @Override public void onLog(String line) { log(line); }

        @Override
        public void onProducts(List<DeliveryController.ProductUiItem> items, int selectedIndex0) {
            runOnUiThread(() -> {
                ArrayAdapter<DeliveryController.ProductUiItem> adapter =
                        new ArrayAdapter<>(MainActivity.this,
                                android.R.layout.simple_spinner_item,
                                items);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                spnProducts.setAdapter(adapter);
                if (selectedIndex0 >= 0 && selectedIndex0 < items.size()) {
                    spnProducts.setSelection(selectedIndex0);
                }
            });
        }
    }

    private void bindUI() {
        spnUsbDevices = findViewById(R.id.spnUsbDevices);
        spnProducts = findViewById(R.id.spnProducts);

        edtTo = findViewById(R.id.edtTo);
        edtFrom = findViewById(R.id.edtFrom);
        edtProduct = findViewById(R.id.edtProduct);
        edtPreset = findViewById(R.id.edtPreset);

        btnScanUsb = findViewById(R.id.btnScanUsb);
        btnPingUsb = findViewById(R.id.btnPingUsb);
        btnConnect = findViewById(R.id.btnConnect);
        btnC = findViewById(R.id.btnC);

        btnClearLog = findViewById(R.id.btnClearLog);
        btnCopyLog = findViewById(R.id.btnCopyLog);

        txtLog = findViewById(R.id.txtLog);
        logScroll = findViewById(R.id.logScroll);

        edtTo.setText("0xFA");
        edtFrom.setText("0xFF");
        edtPreset.setText("50.0");

        // liste init 1..16
        List<DeliveryController.ProductUiItem> init = new ArrayList<>();
        for (int i = 1; i <= 16; i++) init.add(new DeliveryController.ProductUiItem(i, "Produit " + i));
        ArrayAdapter<DeliveryController.ProductUiItem> adapter =
                new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, init);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spnProducts.setAdapter(adapter);
    }

    // ---------------- USB helpers ----------------
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

    // ---------------- utils ----------------
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

    // ---------------- log ----------------
    private void log(String s) {
        uiHandler.post(() -> {
            logBuf.append(s).append('\n');
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
