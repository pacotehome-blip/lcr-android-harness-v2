
package com.pa.lcrdemo;

import androidx.appcompat.app.AppCompatActivity;

import android.app.PendingIntent;
import android.content.Intent;
import android.hardware.usb.*;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.widget.*;

import com.hoho.android.usbserial.driver.*;
import com.pa.lcr.lcp.*;

import java.util.ArrayList;
import java.util.List;

/**
 * MainActivity
 *
 * UI complète et stable :
 *  - Scan USB (fabricant + modèle)
 *  - Log VID / PID
 *  - TO / FROM affichés
 *  - Node actif affiché (registre réellement répondant)
 *  - Spinner Produit 1..16 (statique)
 *  - Preset par défaut = 50
 *
 * Dépend UNIQUEMENT de DeliveryControllerPort.
 */
public class MainActivity extends AppCompatActivity {

    /* =========================
     * CONSTANTES TERRAIN
     * ========================= */

    private static final int TO_NODE_DEC = 250;     // 0xFA
    private static final int FROM_NODE_HEX = 0xFF; // Host

    public static final String ACTION_USB_PERMISSION =
            "com.pa.lcrdemo.USB_PERMISSION";

    /* =========================
     * UI USB
     * ========================= */

    private Spinner spnUsbDevices;
    private Button btnScanUsb;
    private Button btnOpenUsb;

    /* =========================
     * UI LIVRAISON
     * ========================= */

    private Spinner spnProducts;
    private Button btnA, btnC, btnContinue, btnFinish;
    private EditText edtPreset, edtProduct;

    private TextView txtToNode;
    private TextView txtFromNode;
    private TextView txtActiveNode;

    private TextView txtLive;
    private TextView txtLog;
    private ScrollView logScroll;

    /* =========================
     * USB
     * ========================= */

    private UsbManager usbManager;
    private final List<UsbDevice> usbDevices = new ArrayList<>();
    private UsbSerialPort usbPort;

    /* =========================
     * CONTROLLER
     * ========================= */

    private DeliveryControllerPort controller;

    /* =========================
     * UI helpers
     * ========================= */

    private boolean suppressProductSelection = false;
    private boolean userTouchedSpinner = false;

    private final StringBuilder logBuf = new StringBuilder(32_000);
    private final Handler ui = new Handler(Looper.getMainLooper());

    /* =========================
     * Lifecycle
     * ========================= */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindUi();
        wireUi();
        initUiDefaults();

        usbManager = (UsbManager) getSystemService(USB_SERVICE);

        log("UI prête — Scan USB requis");
    }

    /* =========================
     * UI binding
     * ========================= */

    private void bindUi() {
        spnUsbDevices = findViewById(R.id.spnUsbDevices);
        btnScanUsb    = findViewById(R.id.btnScanUsb);
        btnOpenUsb    = findViewById(R.id.btnPingUsb);

        spnProducts   = findViewById(R.id.spnProducts);
        btnA          = findViewById(R.id.btnA);
        btnC          = findViewById(R.id.btnC);
        btnContinue   = findViewById(R.id.btnContinue);
        btnFinish     = findViewById(R.id.btnFinish);

        edtPreset     = findViewById(R.id.edtPreset);
        edtProduct    = findViewById(R.id.edtProduct);

        txtToNode     = findViewById(R.id.txtToNode);
        txtFromNode   = findViewById(R.id.txtFromNode);
        txtActiveNode = findViewById(R.id.txtActiveNode);

        txtLive       = findViewById(R.id.txtLive);
        txtLog        = findViewById(R.id.txtLog);
        logScroll     = findViewById(R.id.logScroll);
    }

    /* =========================
     * Initialisation UI
     * ========================= */

    private void initUiDefaults() {
        txtToNode.setText("TO : 250 (0xFA)");
        txtFromNode.setText("FROM : 0xFF");
        txtActiveNode.setText("Node actif : —");

        // Spinner Produit 1..16 (STATIQUE)
        List<ProductUiItem> products = new ArrayList<>();
        for (int i = 1; i <= 16; i++) {
            products.add(new ProductUiItem(i, "Produit " + i));
        }

        ArrayAdapter<ProductUiItem> adapter =
                new ArrayAdapter<>(
                        this,
                        android.R.layout.simple_spinner_item,
                        products
                );
        adapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item
        );

        spnProducts.setAdapter(adapter);
        spnProducts.setSelection(0);

        edtPreset.setText("50");
        edtProduct.setText("");
    }

    /* =========================
     * UI wiring
     * ========================= */

    private void wireUi() {

        btnScanUsb.setOnClickListener(v -> scanUsb());
        btnOpenUsb.setOnClickListener(v -> openSelectedUsb());

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

                ProductUiItem item =
                        (ProductUiItem) spnProducts.getSelectedItem();
                if (item != null) {
                    controller.selectProduct(item.product1);
                }
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

    /* =========================
     * USB UI
     * ========================= */

    private void scanUsb() {
        usbDevices.clear();
        usbDevices.addAll(usbManager.getDeviceList().values());

        List<String> labels = new ArrayList<>();

        log("Scan USB: " + usbDevices.size() + " périphérique(s)");

        for (UsbDevice d : usbDevices) {
            String m = d.getManufacturerName();
            String p = d.getProductName();
            if (m == null) m = "Unknown manufacturer";
            if (p == null) p = "Unknown device";

            labels.add(m + " - " + p);

            log(String.format(
                    " - %s - %s (VID=%04X PID=%04X)",
                    m, p, d.getVendorId(), d.getProductId()
            ));
        }

        spnUsbDevices.setAdapter(
                new ArrayAdapter<>(
                        this,
                        android.R.layout.simple_spinner_item,
                        labels
                )
        );
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
                    this,
                    0,
                    new Intent(ACTION_USB_PERMISSION),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
            );
            usbManager.requestPermission(dev, pi);
            log("Demande permission USB");
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
            port.setParameters(
                    19200,
                    8,
                    UsbSerialPort.STOPBITS_1,
                    UsbSerialPort.PARITY_NONE
            );

            onUsbPortReady(port);

        } catch (Exception e) {
            log("Open USB ERR: " + e.getMessage());
        }
    }

    /* =========================
     * USB callbacks
     * ========================= */

    public void onUsbPortReady(UsbSerialPort port) {

        if (this.usbPort != null) {
            log("USB déjà ouvert — ignore");
            return;
        }

        this.usbPort = port;
        log("USB prêt");

        LcpLink link = new LcpLink(port, TO_NODE_DEC, FROM_NODE_HEX, true);
        controller = new DeliveryController(link);

        controller.setListener(new DeliveryControllerPort.Listener() {

            @Override
            public void onStateChanged(DeliveryState state) {
                ui.post(() -> txtLive.setText("STATE: " + state));
            }

            @Override
            public void onProductsUpdated(List<ProductUiItem> ignored, int activeIndex0) {
                ui.post(() -> {
                    suppressProductSelection = true;
                    spnProducts.setSelection(activeIndex0);
                    suppressProductSelection = false;
                });
            }

            @Override
            public void onLog(String message) {
                log(message);
                if (message.startsWith("Node actif confirmé")) {
                    ui.post(() -> txtActiveNode.setText(message));
                }
            }

            @Override
            public void onError(String context, Throwable error) {
                log("ERR[" + context + "] " + error.getMessage());
            }
        });

        ui.postDelayed(() -> controller.initialize(), 800);
    }

    public void onUsbDetached() {
        log("USB débranché");

        if (controller != null) {
            controller.shutdown();
            controller = null;
        }

        usbPort = null;
        txtActiveNode.setText("Node actif : —");
    }

    /* =========================
     * Utils
     * ========================= */

    private int readProduct() {
        try {
            int v = Integer.parseInt(edtProduct.getText().toString().trim());
            if (v >= 1 && v <= 16) return v;
        } catch (Exception ignore) {}

        ProductUiItem it =
                (ProductUiItem) spnProducts.getSelectedItem();
        return it != null ? it.product1 : 1;
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
            logBuf.append(s).append('\n');
            txtLog.setText(logBuf.toString());
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }
}
