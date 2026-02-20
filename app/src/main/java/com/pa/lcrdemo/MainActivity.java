
package com.pa.lcrdemo;

import androidx.appcompat.app.AppCompatActivity;

import android.app.PendingIntent;
import android.content.Intent;
import android.hardware.usb.*;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.*;

import com.hoho.android.usbserial.driver.*;
import com.pa.lcr.lcp.*;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int TO_NODE_DEC = 250;     // 0xFA
    private static final int FROM_NODE_HEX = 0xFF;

    public static final String ACTION_USB_PERMISSION =
            "com.pa.lcrdemo.USB_PERMISSION";

    /* ================= USB ================= */

    private Spinner spnUsbDevices;
    private Button btnScanUsb;
    private Button btnPingUsb;

    private UsbManager usbManager;
    private final List<UsbDevice> usbDevices = new ArrayList<>();
    private UsbSerialPort usbPort;

    /* ================= LCP / LIVRAISON ================= */

    private Spinner spnProducts;
    private EditText edtProduct;
    private EditText edtPreset;

    private Button btnA, btnB, btnC;
    private Button btnContinue, btnFinish;

    private TextView txtToNode;
    private TextView txtFromNode;
    private TextView txtActiveNode;
    private TextView txtLive;

    private TextView txtLog;
    private ScrollView logScroll;

    private DeliveryControllerPort controller;

    private boolean suppressProductSelection = false;
    private boolean userTouchedSpinner = false;

    private final StringBuilder logBuf = new StringBuilder(32768);
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        usbManager = (UsbManager) getSystemService(USB_SERVICE);

        bindUi();
        wireUi();
        initUiDefaults();

        log("UI prête — Scan USB requis");
    }

    /* ================= UI ================= */

    private void bindUi() {
        spnUsbDevices = findViewById(R.id.spnUsbDevices);
        btnScanUsb    = findViewById(R.id.btnScanUsb);
        btnPingUsb    = findViewById(R.id.btnPingUsb);

        spnProducts   = findViewById(R.id.spnProducts);
        edtProduct    = findViewById(R.id.edtProduct);
        edtPreset     = findViewById(R.id.edtPreset);

        btnA          = findViewById(R.id.btnA);
        btnB          = findViewById(R.id.btnB);
        btnC          = findViewById(R.id.btnC);
        btnContinue   = findViewById(R.id.btnContinue);
        btnFinish     = findViewById(R.id.btnFinish);

        txtToNode     = findViewById(R.id.txtToNode);
        txtFromNode   = findViewById(R.id.txtFromNode);
        txtActiveNode = findViewById(R.id.txtActiveNode);
        txtLive       = findViewById(R.id.txtLive);

        txtLog        = findViewById(R.id.txtLog);
        logScroll     = findViewById(R.id.logScroll);
    }

    private void initUiDefaults() {
        txtToNode.setText("TO : 250 (0xFA)");
        txtFromNode.setText("FROM : 0xFF");
        txtActiveNode.setText("Node actif : —");

        List<ProductUiItem> products = new ArrayList<>();
        for (int i = 1; i <= 16; i++) {
            products.add(new ProductUiItem(i, "Produit " + i));
        }

        ArrayAdapter<ProductUiItem> adapter =
                new ArrayAdapter<>(this,
                        android.R.layout.simple_spinner_item,
                        products);

        adapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);

        spnProducts.setAdapter(adapter);
        spnProducts.setSelection(0);

        edtPreset.setText("50");
    }

    private void wireUi() {

        btnScanUsb.setOnClickListener(v -> scanUsb());
        btnPingUsb.setOnClickListener(v -> openSelectedUsb());

        spnProducts.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                userTouchedSpinner = true;
            }
            return false;
        });

        spnProducts.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (controller == null) return;
                if (suppressProductSelection) return;
                if (!userTouchedSpinner) return;

                userTouchedSpinner = false;
                ProductUiItem it = (ProductUiItem) spnProducts.getSelectedItem();
                controller.selectProduct(it.product1);
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        btnA.setOnClickListener(v -> {
            if (controller != null) controller.refreshProducts();
        });

        btnC.setOnClickListener(v -> {
            if (controller == null) return;
            controller.startDelivery(readProduct(), readPreset());
        });

        btnContinue.setOnClickListener(v -> {
            if (controller != null) controller.resumeIfPaused();
        });

        btnFinish.setOnClickListener(v -> {
            if (controller != null) controller.endDelivery();
        });
    }

    /* ================= USB ================= */

    private void scanUsb() {
        usbDevices.clear();
        usbDevices.addAll(usbManager.getDeviceList().values());

        log("Scan USB: " + usbDevices.size() + " périphérique(s)");

        List<String> labels = new ArrayList<>();
        for (UsbDevice d : usbDevices) {
            String m = d.getManufacturerName();
            String p = d.getProductName();
            if (m == null) m = "Unknown";
            if (p == null) p = "Device";

            labels.add(m + " - " + p);
            log(String.format(
                    " - %s - %s (VID=%04X PID=%04X)",
                    m, p, d.getVendorId(), d.getProductId()
            ));
        }

        spnUsbDevices.setAdapter(
                new ArrayAdapter<>(this,
                        android.R.layout.simple_spinner_item,
                        labels));
    }

    private void openSelectedUsb() {
        int idx = spnUsbDevices.getSelectedItemPosition();
        if (idx < 0 || idx >= usbDevices.size()) {
            log("Aucun périphérique USB sélectionné");
            return;
        }

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

        UsbSerialDriver driver =
                UsbSerialProber.getDefaultProber().probeDevice(dev);
        if (driver == null) {
            log("Driver USB série introuvable");
            return;
        }

        try {
            UsbDeviceConnection conn = usbManager.openDevice(dev);
            UsbSerialPort port = driver.getPorts().get(0);

            port.open(conn);
            port.setParameters(19200, 8,
                    UsbSerialPort.STOPBITS_1,
                    UsbSerialPort.PARITY_NONE);

            onUsbPortReady(port);

        } catch (Exception e) {
            log("Open USB ERR: " + e.getMessage());
        }
    }

    public void onUsbPortReady(UsbSerialPort port) {
        if (usbPort != null) return;

        usbPort = port;
        log("USB prêt");

        LcpLink link = new LcpLink(port, TO_NODE_DEC, FROM_NODE_HEX, true);
        controller = new DeliveryController(link);

        controller.setListener(new DeliveryControllerPort.Listener() {

            @Override
            public void onStateChanged(DeliveryState state) {
                ui.post(() -> txtLive.setText("LIVE: " + state));
            }

            @Override
            public void onProductsUpdated(List<ProductUiItem> ignored, int idx0) {
                ui.post(() -> {
                    suppressProductSelection = true;
                    spnProducts.setSelection(idx0);
                    suppressProductSelection = false;
                });
            }

            @Override
            public void onLog(String msg) {
                log(msg);
                if (msg.startsWith("Node actif")) {
                    ui.post(() -> txtActiveNode.setText(msg));
                }
            }

            @Override
            public void onError(String ctx, Throwable e) {
                log("ERR[" + ctx + "] " + e.getMessage());
            }
        });

        ui.postDelayed(() -> controller.initialize(), 800);
    }

    /* ================= UTILS ================= */

    private int readProduct() {
        try {
            int v = Integer.parseInt(edtProduct.getText().toString());
            if (v >= 1 && v <= 16) return v;
        } catch (Exception ignore) {}
        ProductUiItem it = (ProductUiItem) spnProducts.getSelectedItem();
        return it.product1;
    }

    private double readPreset() {
        try {
            return Double.parseDouble(edtPreset.getText().toString());
        } catch (Exception e) {
            return 0.0;
        }
    }

    private void log(String s) {
        ui.post(() -> {
            logBuf.append(s).append('\n');
            txtLog.setText(logBuf.toString());
            logScroll.post(() ->
                    logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }
}
