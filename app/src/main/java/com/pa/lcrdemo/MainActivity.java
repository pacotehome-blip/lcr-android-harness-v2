
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

/**
 * MainActivity
 *
 * UI complète :
 *  - Scan USB / Open USB (manuel)
 *  - Support UsbReceiver (hotplug)
 *  - Dépend UNIQUEMENT de DeliveryControllerPort
 *
 * Aucune logique protocolaire ici.
 */
public class MainActivity extends AppCompatActivity {

    /* ==========================================================
     * UI USB
     * ========================================================== */

    private Spinner spnUsbDevices;
    private Button btnScanUsb;
    private Button btnOpenUsb;

    /* ==========================================================
     * UI Livraison
     * ========================================================== */

    private Spinner spnProducts;
    private Button btnA, btnC, btnContinue, btnFinish;
    private EditText edtPreset, edtProduct;
    private TextView txtLive, txtLog;
    private ScrollView logScroll;

    /* ==========================================================
     * USB
     * ========================================================== */

    private UsbManager usbManager;
    private final List<UsbDevice> usbDevices = new ArrayList<>();
    private UsbSerialPort usbPort;

    /* ==========================================================
     * Controller (PORT UNIQUEMENT)
     * ========================================================== */

    private DeliveryControllerPort controller;

    /* ==========================================================
     * Helpers UI
     * ========================================================== */

    private boolean suppressProductSelection = false;
    private boolean userTouchedSpinner = false;

    private final StringBuilder logBuf = new StringBuilder(32_000);
    private final Handler ui = new Handler(Looper.getMainLooper());

    public static final String ACTION_USB_PERMISSION =
            "com.pa.lcrdemo.USB_PERMISSION";

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

        log("UI prête — Scan USB requis");
    }

    /* ==========================================================
     * UI binding
     * ========================================================== */

    private void bindUi() {
        // USB
        spnUsbDevices = findViewById(R.id.spnUsbDevices);
        btnScanUsb    = findViewById(R.id.btnScanUsb);
        btnOpenUsb    = findViewById(R.id.btnPingUsb); // bouton Ouvrir/Ping

        // Livraison
        spnProducts   = findViewById(R.id.spnProducts);
        btnA          = findViewById(R.id.btnA);
        btnC          = findViewById(R.id.btnC);
        btnContinue   = findViewById(R.id.btnContinue);
        btnFinish     = findViewById(R.id.btnFinish);

        edtPreset     = findViewById(R.id.edtPreset);
        edtProduct    = findViewById(R.id.edtProduct);

        txtLive       = findViewById(R.id.txtLive);
        txtLog        = findViewById(R.id.txtLog);
        logScroll     = findViewById(R.id.logScroll);
    }

    /* ==========================================================
     * UI wiring
     * ========================================================== */

    private void wireUi() {

        /* ---------- USB ---------- */

        btnScanUsb.setOnClickListener(v -> scanUsb());
        btnOpenUsb.setOnClickListener(v -> openSelectedUsb());

        /* ---------- Spinner produits ---------- */

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

        /* ---------- Boutons ---------- */

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
     * USB UI MANUELLE
     * ========================================================== */

    private void scanUsb() {
        usbDevices.clear();
        usbDevices.addAll(usbManager.getDeviceList().values());

        List<String> labels = new ArrayList<>();
        for (UsbDevice d : usbDevices) {
            labels.add(String.format(
                    "VID=%04X PID=%04X %s",
                    d.getVendorId(),
                    d.getProductId(),
                    d.getDeviceName()
            ));
        }

        spnUsbDevices.setAdapter(
                new ArrayAdapter<>(
                        this,
                        android.R.layout.simple_spinner_item,
                        labels
                )
        );

        log("Scan USB: " + labels.size() + " périphérique(s)");
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

    /* ==========================================================
     * USB callbacks (appelés aussi par UsbReceiver)
     * ========================================================== */

    public void onUsbPortReady(UsbSerialPort port) {
        this.usbPort = port;
        log("USB prêt");

        // 1 port = 1 registre (pour l’instant)
        LcpLink link = new LcpLink(port, 0xFA, 0xFF, true);
        controller = new DeliveryController(link);

        controller.setListener(new DeliveryControllerPort.Listener() {

            @Override
            public void onStateChanged(DeliveryState state) {
                ui.post(() -> txtLive.setText("STATE: " + state));
            }

            @Override
            public void onProductsUpdated(
                    List<ProductUiItem> products,
                    int activeIndex0
            ) {
                ui.post(() -> {
                    suppressProductSelection = true;

                    ArrayAdapter<ProductUiItem> adapter =
                            new ArrayAdapter<>(
                                    MainActivity.this,
                                    android.R.layout.simple_spinner_item,
                                    products
                            );
                    adapter.setDropDownViewResource(
                            android.R.layout.simple_spinner_dropdown_item
                    );

                    spnProducts.setAdapter(adapter);
                    spnProducts.setSelection(activeIndex0);

                    suppressProductSelection = false;
                });
            }

            @Override
            public void onLog(String message) {
                log(message);
            }

            @Override
            public void onError(String context, Throwable error) {
                log("ERR[" + context + "] " + error.getMessage());
            }
        });

        controller.initialize();
    }

    public void onUsbDetached() {
        log("USB débranché");

        if (controller != null) {
            controller.shutdown();
            controller = null;
        }

        usbPort = null;
    }

    /* ==========================================================
     * Utils UI
     * ========================================================== */

    private int readProduct() {
        try {
            int v = Integer.parseInt(
                    edtProduct.getText().toString().trim()
            );
            if (v >= 1 && v <= 16) return v;
        } catch (Exception ignore) {}

        ProductUiItem it =
                (ProductUiItem) spnProducts.getSelectedItem();
        return it != null ? it.product1 : 1;
    }

    private double readPreset() {
        try {
            return Double.parseDouble(
                    edtPreset.getText().toString().trim()
            );
        } catch (Exception e) {
            return 0.0;
        }
    }

    private void log(String s) {
        ui.post(() -> {
            logBuf.append(s).append('\n');
            txtLog.setText(logBuf.toString());
            logScroll.post(
                    () -> logScroll.fullScroll(View.FOCUS_DOWN)
            );
        });
    }
}
