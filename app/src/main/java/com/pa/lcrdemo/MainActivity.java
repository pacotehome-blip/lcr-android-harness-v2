
package com.pa.lcrdemo;

import androidx.appcompat.app.AppCompatActivity;

import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
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

    /* ===================== CONSTANTES ===================== */
    public static final String ACTION_USB_PERMISSION = "com.pa.lcrdemo.USB_PERMISSION";

    /* ===================== USB ===================== */
    private UsbManager usbManager;
    private final List<UsbDevice> usbDevices = new ArrayList<>();
    private UsbSerialPort usbPort;
    private Spinner spnUsbDevices;
    private Button btnScanUsb;
    private Button btnPingUsb;

    /* ===================== LCP (ÉTAT ÉDITABLE) ===================== */
    private EditText edtTo;
    private EditText edtFrom;
    private TextView txtActiveNode;
    private Button btnConnect;

    /* ===================== PRODUIT / PRESET ===================== */
    private Spinner spnProducts;
    private EditText edtProduct;
    private EditText edtPreset;
    private Button btnA, btnB, btnC;
    private Button btnContinue, btnFinish;

    /* ===================== LOG / LIVE ===================== */
    private TextView txtLive;
    private TextView txtLog;
    private ScrollView logScroll;
    private Button btnClearLog;
    private Button btnCopyLog;

    /* ===================== CONTROLLER ===================== */
    private DeliveryControllerPort controller;

    /* ===================== UI helpers ===================== */
    private boolean suppressProductSelection = false;
    private boolean userTouchedSpinner = false;
    private final StringBuilder logBuf = new StringBuilder(32768);
    private final Handler ui = new Handler(Looper.getMainLooper());

    // Debounce init (évite double "LCP prêt..." si on clique Connect rapidement)
    private Runnable pendingInitRunnable = null;

    // LIVE tick: poll léger via controller.requestLiveSample()
    private final Runnable liveTick = new Runnable() {
        @Override public void run() {
            if (controller != null) {
                controller.requestLiveSample();
                ui.postDelayed(this, 300);
            }
        }
    };

    /* ===================== Lifecycle ===================== */
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

    /* ===================== UI ===================== */
    private void bindUi() {
        // USB
        btnScanUsb = findViewById(R.id.btnScanUsb);
        btnPingUsb = findViewById(R.id.btnPingUsb);
        spnUsbDevices = findViewById(R.id.spnUsbDevices);

        // LCP
        edtTo = findViewById(R.id.edtTo);
        edtFrom = findViewById(R.id.edtFrom);
        txtActiveNode = findViewById(R.id.txtActiveNode);
        btnConnect = findViewById(R.id.btnConnect);

        // Produit / preset
        spnProducts = findViewById(R.id.spnProducts);
        edtProduct = findViewById(R.id.edtProduct);
        edtPreset = findViewById(R.id.edtPreset);
        btnA = findViewById(R.id.btnA);
        btnB = findViewById(R.id.btnB);
        btnC = findViewById(R.id.btnC);
        btnContinue = findViewById(R.id.btnContinue);
        btnFinish = findViewById(R.id.btnFinish);

        // Log
        txtLive = findViewById(R.id.txtLive);
        txtLog = findViewById(R.id.txtLog);
        logScroll = findViewById(R.id.logScroll);
        btnClearLog = findViewById(R.id.btnClearLog);
        btnCopyLog = findViewById(R.id.btnCopyLog);
    }

    private void initUiDefaults() {
        edtTo.setText("250");
        edtFrom.setText("255");
        txtActiveNode.setText("Node actif : —");

        // Spinner Produit 1..16 (statique)
        List<ProductUiItem> products = new ArrayList<>();
        for (int i = 1; i <= 16; i++) {
            products.add(new ProductUiItem(i, "Produit " + i));
        }
        ArrayAdapter<ProductUiItem> adapter =
                new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, products);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spnProducts.setAdapter(adapter);
        spnProducts.setSelection(0);

        // Valeur par défaut
        edtPreset.setText("50");
    }

    private void wireUi() {
        /* ---------- USB ---------- */
        btnScanUsb.setOnClickListener(v -> scanUsb());
        btnPingUsb.setOnClickListener(v -> openSelectedUsb());

        /* ---------- LCP ---------- */
        btnConnect.setOnClickListener(v -> connectLcp());

        /* ---------- Produits ---------- */
        spnProducts.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                userTouchedSpinner = true;
            }
            return false;
        });

        spnProducts.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (controller == null) return;
                if (suppressProductSelection) return;
                if (!userTouchedSpinner) return;
                userTouchedSpinner = false;
                ProductUiItem it = (ProductUiItem) spnProducts.getSelectedItem();
                controller.selectProduct(it.product1);
                edtProduct.setText(String.valueOf(it.product1));
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        /* ---------- Actions (mapping verrouillé) ---------- */
        // C = Start (reste ON comme demandé)
        btnC.setOnClickListener(v -> {
            if (controller == null) return;
            controller.startDelivery(readProduct(), readPreset());
            edtPreset.setText(String.valueOf(readPreset()));
        });

        // A = End (Terminer) -> sera OFF quand FLOW_ACTIVE=ON via updateButtons()
        btnA.setOnClickListener(v -> {
            if (controller == null) return;
            controller.endDelivery();
        });

        // B = Status
        btnB.setOnClickListener(v -> {
            if (controller == null) return;
            controller.requestStatus();
        });

        // Continuer = Resume (OFF tant que FLOW_ACTIVE=ON)
        btnContinue.setOnClickListener(v -> {
            if (controller != null) controller.resumeIfPaused();
        });

        // Terminer = End (même action que A)
        btnFinish.setOnClickListener(v -> {
            if (controller != null) controller.endDelivery();
        });

        /* ---------- LOG ---------- */
        btnClearLog.setOnClickListener(v -> {
            logBuf.setLength(0);
            txtLog.setText("");
        });

        btnCopyLog.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("log", txtLog.getText()));
            log("Log copié dans le presse-papiers");
        });
    }

    /* ===================== USB ===================== */
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
            log(String.format(" - %s - %s (VID=%04X PID=%04X)",
                    m, p, d.getVendorId(), d.getProductId()));
        }
        spnUsbDevices.setAdapter(
                new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels)
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
                    this, 0,
                    new Intent(ACTION_USB_PERMISSION),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
            );
            usbManager.requestPermission(dev, pi);
            return;
        }

        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(dev);
        if (driver == null) {
            log("Driver USB série introuvable");
            return;
        }

        try {
            UsbDeviceConnection conn = usbManager.openDevice(dev);
            UsbSerialPort port = driver.getPorts().get(0);
            port.open(conn);
            port.setParameters(19200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            usbPort = port;
            log("USB prêt");
        } catch (Exception e) {
            log("Open USB ERR: " + e.getMessage());
        }
    }

    // Appelé par UsbReceiver (si utilisé)
    public void onUsbPortReady(UsbSerialPort port) {
        if (usbPort != null) return;
        usbPort = port;
        log("USB prêt (receiver)");
    }

    // Appelé par UsbReceiver (si utilisé)
    public void onUsbDetached() {
        log("USB détaché");
        if (controller != null) {
            controller.shutdown();
            controller = null;
        }
        ui.removeCallbacks(liveTick);
        usbPort = null;
        txtActiveNode.setText("Node actif : —");
    }

    /* ===================== LCP ===================== */
    private void connectLcp() {
        if (usbPort == null) {
            log("ERR: USB non connecté");
            return;
        }

        int to = parseInt(edtTo.getText().toString(), 250);
        int from = parseInt(edtFrom.getText().toString(), 255);

        // normalisation -> réécrit les mêmes champs (UX)
        edtTo.setText(String.valueOf(to));
        edtFrom.setText(String.valueOf(from));
        txtActiveNode.setText("Node actif : —");

        if (controller != null) {
            controller.shutdown();
            controller = null;
        }

        // annule un init en attente pour éviter double logs si reconnect rapide
        if (pendingInitRunnable != null) ui.removeCallbacks(pendingInitRunnable);

        // crée le lien + controller
        LcpLink link = new LcpLink(usbPort, to, from, true);
        controller = new DeliveryController(link);

        controller.setListener(new DeliveryControllerPort.Listener() {
            @Override public void onStateChanged(DeliveryState state) {
                ui.post(() -> {
                    boolean flowOn = (state == DeliveryState.RUNNING_FLOWING);
                    txtLive.setText("LIVE: " + state + " | FLOW_ACTIVE: " + (flowOn ? "ON" : "OFF"));
                    updateButtons(state);
                });
            }

            @Override public void onProductsUpdated(List<ProductUiItem> ignored, int idx0) {
                ui.post(() -> {
                    suppressProductSelection = true;
                    spnProducts.setSelection(idx0);
                    suppressProductSelection = false;
                });
            }

            @Override public void onLog(String msg) {
                log(msg);
                if (msg.startsWith("Node actif")) {
                    ui.post(() -> txtActiveNode.setText(msg));
                }
            }

            @Override public void onError(String ctx, Throwable e) {
                log("ERR[" + ctx + "] " + e.getMessage());
            }
        });

        pendingInitRunnable = () -> {
            if (controller != null) controller.initialize();
        };
        ui.postDelayed(pendingInitRunnable, 300);

        log("Connect LCP appliqué");

        // démarre le tick LIVE (poll léger)
        ui.removeCallbacks(liveTick);
        ui.postDelayed(liveTick, 300);
    }

    private void updateButtons(DeliveryState state) {
        boolean flowing = (state == DeliveryState.RUNNING_FLOWING);
        boolean paused  = (state == DeliveryState.RUNNING_PAUSED);

        // Start (C) reste ON
        btnC.setEnabled(true);

        // OFF tant que ça coule; ON quand paused
        btnContinue.setEnabled(paused && !flowing);
        btnFinish.setEnabled(paused && !flowing);
        btnA.setEnabled(paused && !flowing);

        // Status toujours ON
        btnB.setEnabled(true);
    }

    /* ===================== Utils ===================== */
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

    private int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
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
