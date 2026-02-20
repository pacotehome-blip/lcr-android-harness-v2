
package com.pa.lcrdemo;

import androidx.appcompat.app.AppCompatActivity;

import android.app.PendingIntent;
import android.content.*;
import android.hardware.usb.*;
import android.os.*;
import android.view.MotionEvent;
import android.view.View;
import android.widget.*;

import com.hoho.android.usbserial.driver.*;
import com.pa.lcr.lcp.*;

import java.util.ArrayList;
import java.util.List;

/**
 * MainActivity
 *
 * UI PURE.
 * Ne dépend QUE de DeliveryControllerPort.
 * AUCUNE logique protocolaire ici.
 */
public class MainActivity extends AppCompatActivity {

    /* ==========================================================
     * UI
     * ========================================================== */

    private Spinner spnUsbDevices;
    private Spinner spnProducts;

    private Button btnScanUsb, btnPingUsb, btnConnect;
    private Button btnA, btnB, btnC, btnContinue, btnFinish;

    private EditText edtTo, edtFrom, edtPreset, edtProduct;

    private TextView txtLive;
    private TextView txtLog;
    private ScrollView logScroll;

    /* ==========================================================
     * USB
     * ========================================================== */

    private UsbManager usbManager;
    private final List<UsbDevice> usbDevices = new ArrayList<>();
    private UsbSerialPort port;

    /* ==========================================================
     * Controller (PORT UNIQUEMENT)
     * ========================================================== */

    private DeliveryControllerPort controller;

    /* ==========================================================
     * UI helpers
     * ========================================================== */

    private boolean suppressProductSelection = false;
    private boolean userTouchedProductSpinner = false;

    private final StringBuilder logBuffer = new StringBuilder(16_000);
    private final Handler ui = new Handler(Looper.getMainLooper());

    private static final String ACTION_USB_PERMISSION = "com.pa.lcrdemo.USB_PERMISSION";

    /* ==========================================================
     * Lifecycle
     * ========================================================== */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindUi();
        wireUi();

        usbManager = (UsbManager) getSystemService(USB_SERVICE);
        scanUsb();
    }

    /* ==========================================================
     * UI wiring
     * ========================================================== */

    private void bindUi() {
        spnUsbDevices = findViewById(R.id.spnUsbDevices);
        spnProducts   = findViewById(R.id.spnProducts);

        btnScanUsb  = findViewById(R.id.btnScanUsb);
        btnPingUsb  = findViewById(R.id.btnPingUsb);
        btnConnect  = findViewById(R.id.btnConnect);

        btnA        = findViewById(R.id.btnA);
        btnB        = findViewById(R.id.btnB);
        btnC        = findViewById(R.id.btnC);
        btnContinue = findViewById(R.id.btnContinue);
        btnFinish   = findViewById(R.id.btnFinish);

        edtTo      = findViewById(R.id.edtTo);
        edtFrom    = findViewById(R.id.edtFrom);
        edtPreset  = findViewById(R.id.edtPreset);
        edtProduct = findViewById(R.id.edtProduct);

        txtLive   = findViewById(R.id.txtLive);
        txtLog    = findViewById(R.id.txtLog);
        logScroll = findViewById(R.id.logScroll);
    }

    private void wireUi() {

        /* ---------- USB ---------- */

        btnScanUsb.setOnClickListener(v -> scanUsb());
        btnPingUsb.setOnClickListener(v -> openSelectedUsb());

        btnConnect.setOnClickListener(v -> connectController());

        /* ---------- Products ---------- */

        spnProducts.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                userTouchedProductSpinner = true;
            }
            return false;
        });

        spnProducts.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                if (suppressProductSelection) return;
                if (!userTouchedProductSpinner) return;
                if (controller == null) return;

                userTouchedProductSpinner = false;

                ProductUiItem item = (ProductUiItem) spnProducts.getSelectedItem();
                controller.selectProduct(item.product1);
            }

            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        /* ---------- Buttons ---------- */

        btnA.setOnClickListener(v -> {
            if (controller != null) controller.refreshProducts();
        });

        btnC.setOnClickListener(v -> {
            if (controller == null) return;

            int product = readProduct();
            double preset = readPreset();

            controller.startDelivery(product, preset);
        });

        btnContinue.setOnClickListener(v -> {
            if (controller != null) controller.resumeIfPaused();
        });

        btnFinish.setOnClickListener(v -> {
            if (controller != null) controller.endDelivery();
        });
    }

    /* ==========================================================
     * Controller creation
     * ========================================================== */

    private void connectController() {
        if (port == null) {
            log("ERR: USB non ouvert");
            return;
        }

        int to   = parseHex(edtTo, 0xFA);
        int from = parseHex(edtFrom, 0xFF);

        LcpLink link = new LcpLink(port, to, from, true);

        controller = new DeliveryController(link);

        controller.setListener(new DeliveryControllerPort.Listener() {
            @Override public void onStateChanged(DeliveryState s) {
                ui.post(() -> txtLive.setText("STATE: " + s));
            }

            @Override
            public void onProductsUpdated(List<ProductUiItem> list, int activeIdx0) {
                ui.post(() -> {
                    suppressProductSelection = true;
                    ArrayAdapter<ProductUiItem> ad =
                            new ArrayAdapter<>(MainActivity.this,
                                    android.R.layout.simple_spinner_item,
                                    list);
                    ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                    spnProducts.setAdapter(ad);
                    spnProducts.setSelection(activeIdx0);
                    suppressProductSelection = false;
                });
            }

            @Override public void onLog(String msg) { log(msg); }

            @Override public void onError(String ctx, Throwable e) {
                log("ERR[" + ctx + "] " + e.getMessage());
            }
        });

        controller.initialize();
        log("Controller initialisé (LCRNode=" + to + ")");
    }

    /* ==========================================================
     * USB helpers
     * ========================================================== */

    private void scanUsb() {
        usbDevices.clear();
        usbDevices.addAll(usbManager.getDeviceList().values());

        List<String> names = new ArrayList<>();
        for (UsbDevice d : usbDevices) {
            names.add(d.getDeviceName());
        }

        spnUsbDevices.setAdapter(new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                names
        ));
    }

    private void openSelectedUsb() {
        int idx = spnUsbDevices.getSelectedItemPosition();
        if (idx < 0 || idx >= usbDevices.size()) return;

        UsbDevice dev = usbDevices.get(idx);

        if (!usbManager.hasPermission(dev)) {
            PendingIntent pi = PendingIntent.getBroadcast(
                    this, 0,
                    new Intent(ACTION_USB_PERMISSION),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
            );
            usbManager.requestPermission(dev, pi);
            return;
        }

        UsbSerialDriver drv = UsbSerialProber.getDefaultProber().probeDevice(dev);
        if (drv == null) return;

        try {
            UsbDeviceConnection conn = usbManager.openDevice(dev);
            port = drv.getPorts().get(0);
            port.open(conn);
            port.setParameters(19200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            log("USB ouvert");
        } catch (Exception e) {
            log("USB ERR: " + e.getMessage());
        }
    }

    /* ==========================================================
     * Utils
     * ========================================================== */

    private int parseHex(EditText e, int def) {
        try {
            String s = e.getText().toString().trim();
            if (s.startsWith("0x")) return Integer.parseInt(s.substring(2), 16);
            return Integer.parseInt(s);
        } catch (Exception ex) {
            return def;
        }
    }

    private int readProduct() {
        try {
            return Integer.parseInt(edtProduct.getText().toString().trim());
        } catch (Exception e) {
            ProductUiItem it = (ProductUiItem) spnProducts.getSelectedItem();
            return it.product1;
        }
    }

    private double readPreset() {
        try {
            return Double.parseDouble(edtPreset.getText().toString().trim());
        } catch (Exception e) {
            return 0.0;
        }
    }

    private void log(String s) {
        ui.post(() -> {
            logBuffer.append(s).append('\n');
            txtLog.setText(logBuffer.toString());
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }
}
