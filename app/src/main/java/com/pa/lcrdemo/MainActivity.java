
package com.pa.lcrdemo;

import androidx.appcompat.app.AppCompatActivity;

import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
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

    public static final String ACTION_USB_PERMISSION = "com.pa.lcrdemo.USB_PERMISSION";

    private UsbManager usbManager;
    private final List<UsbDevice> usbDevices = new ArrayList<>();
    private UsbSerialPort usbPort;

    private Spinner spnUsbDevices;
    private Button btnScanUsb;
    private Button btnPingUsb;
    private EditText edtTo;
    private EditText edtFrom;
    private TextView txtActiveNode;
    private Button btnConnect;
    private Spinner spnProducts;
    private EditText edtProduct;
    private EditText edtPreset;
    private Button btnA, btnB, btnC;
    private Button btnContinue, btnFinish;
    private TextView txtLive;
    private View liveQtyPanel;
    private TextView txtQtyNet;
    private TextView txtQtyGross;
    private TextView txtLog;
    private ScrollView logScroll;
    private Button btnClearLog;
    private Button btnCopyLog;
    private CheckBox cbTxRx;

    private DeliveryControllerPort controller;
    private boolean suppressProductSelection = false;
    private boolean userTouchedSpinner = false;

    private final StringBuilder logBuf = new StringBuilder(32768);
    private final Handler ui = new Handler(Looper.getMainLooper());

    private boolean liveTickRunning = false;
    private double lastNet = Double.NaN;
    private double lastGross = Double.NaN;
    private Runnable pendingInitRunnable = null;

    private final Runnable liveTick = new Runnable() {
        @Override
        public void run() {
            if (controller == null) { liveTickRunning = false; return; }

            // ✅ IMPORTANT: tick en FLOWING ou PAUSED (pour confirmer Flow OFF après 10s)
            DeliveryState st = controller.getState();
            if (st != DeliveryState.RUNNING_FLOWING && st != DeliveryState.RUNNING_PAUSED) {
                liveTickRunning = false;
                return;
            }

            controller.requestLiveSample();
            ui.postDelayed(this, (st == DeliveryState.RUNNING_FLOWING) ? 300 : 500);
        }
    };

    private void startLiveTickIfNeeded() {
        if (controller == null) return;
        if (liveTickRunning) return;
        liveTickRunning = true;
        ui.removeCallbacks(liveTick);
        ui.postDelayed(liveTick, 300);
    }

    private void stopLiveTick() {
        liveTickRunning = false;
        ui.removeCallbacks(liveTick);
    }

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

    private void bindUi() {
        btnScanUsb = findViewById(R.id.btnScanUsb);
        btnPingUsb = findViewById(R.id.btnPingUsb);
        spnUsbDevices = findViewById(R.id.spnUsbDevices);
        edtTo = findViewById(R.id.edtTo);
        edtFrom = findViewById(R.id.edtFrom);
        txtActiveNode = findViewById(R.id.txtActiveNode);
        btnConnect = findViewById(R.id.btnConnect);
        spnProducts = findViewById(R.id.spnProducts);
        edtProduct = findViewById(R.id.edtProduct);
        edtPreset = findViewById(R.id.edtPreset);
        btnA = findViewById(R.id.btnA);
        btnB = findViewById(R.id.btnB);
        btnC = findViewById(R.id.btnC);
        btnContinue = findViewById(R.id.btnContinue);
        btnFinish = findViewById(R.id.btnFinish);
        txtLive = findViewById(R.id.txtLive);
        liveQtyPanel = findViewById(R.id.liveQtyPanel);
        txtQtyNet = findViewById(R.id.txtQtyNet);
        txtQtyGross = findViewById(R.id.txtQtyGross);
        txtLog = findViewById(R.id.txtLog);
        logScroll = findViewById(R.id.logScroll);
        btnClearLog = findViewById(R.id.btnClearLog);
        btnCopyLog = findViewById(R.id.btnCopyLog);
        cbTxRx = findViewById(R.id.cbTxRx);
    }

    private void initUiDefaults() {
        edtTo.setText("250");
        edtFrom.setText("255");
        txtActiveNode.setText("Node actif : —");
        txtLive.setText("LIVE: (en attente)");
        if (liveQtyPanel != null) liveQtyPanel.setVisibility(View.GONE);
        if (txtQtyNet != null) txtQtyNet.setText("NET: 0.0");
        if (txtQtyGross != null) txtQtyGross.setText("GROSS: 0.0");

        List<ProductUiItem> products = new ArrayList<>();
        for (int i = 1; i <= 16; i++) products.add(new ProductUiItem(i, "Produit " + i));
        ArrayAdapter<ProductUiItem> adapter =
                new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, products);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spnProducts.setAdapter(adapter);
        spnProducts.setSelection(0);

        edtPreset.setText("50");

        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        boolean showTxRx = prefs.getBoolean("log_tx_rx", false);
        if (cbTxRx != null) cbTxRx.setChecked(showTxRx);
    }

    private void wireUi() {
        btnScanUsb.setOnClickListener(v -> scanUsb());
        btnPingUsb.setOnClickListener(v -> openSelectedUsb());
        btnConnect.setOnClickListener(v -> connectLcp());

        spnProducts.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) userTouchedSpinner = true;
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
                edtProduct.setText(String.valueOf(it.product1));
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        btnA.setOnClickListener(v -> {
            if (controller == null) return;
            controller.alignOrRecover();
        });

        btnB.setOnClickListener(v -> {
            if (controller == null) return;
            controller.requestStatus();
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

        btnClearLog.setOnClickListener(v -> {
            logBuf.setLength(0);
            txtLog.setText("");
        });

        btnCopyLog.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("log", txtLog.getText()));
            log("Log copié dans le presse-papiers");
        });

        if (cbTxRx != null) {
            cbTxRx.setOnCheckedChangeListener((buttonView, checked) -> {
                SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
                prefs.edit().putBoolean("log_tx_rx", checked).apply();
                if (controller != null) controller.setTxRxLoggingEnabled(checked);
                log("Option TX/RX: " + (checked ? "ON" : "OFF"));
            });
        }
    }

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
            log(String.format(" - %s - %s (VID=%04X PID=%04X)", m, p, d.getVendorId(), d.getProductId()));
        }

        spnUsbDevices.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels));
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

    public void onUsbPortReady(UsbSerialPort port) {
        if (usbPort != null) return;
        usbPort = port;
        log("USB prêt (receiver)");
    }

    public void onUsbDetached() {
        log("USB détaché");
        if (controller != null) {
            controller.shutdown();
            controller = null;
        }
        stopLiveTick();
        usbPort = null;
        txtActiveNode.setText("Node actif : —");
        txtLive.setText("LIVE: (en attente)");
        if (liveQtyPanel != null) liveQtyPanel.setVisibility(View.GONE);
    }

    private void connectLcp() {
        if (usbPort == null) {
            log("ERR: USB non connecté");
            return;
        }

        int to = parseInt(edtTo.getText().toString(), 250);
        int from = parseInt(edtFrom.getText().toString(), 255);
        edtTo.setText(String.valueOf(to));
        edtFrom.setText(String.valueOf(from));

        txtActiveNode.setText("Node actif : —");

        if (controller != null) {
            controller.shutdown();
            controller = null;
        }
        if (pendingInitRunnable != null) ui.removeCallbacks(pendingInitRunnable);

        LcpLink link = new LcpLink(usbPort, to, from, true);
        controller = new DeliveryController(link);

        SharedPreferences prefs = getSharedPreferences("prefs", MODE_PRIVATE);
        boolean showTxRx = prefs.getBoolean("log_tx_rx", false);
        controller.setTxRxLoggingEnabled(showTxRx);

        controller.setListener(new DeliveryControllerPort.Listener() {

            @Override
            public void onStateChanged(DeliveryState state) {
                ui.post(() -> {
                    boolean stableOff = (controller != null) && controller.isFlowOffStable();

                    txtLive.setText("LIVE: " + state);
                    updateButtons(state, stableOff);

                    if (state == DeliveryState.RUNNING_FLOWING || state == DeliveryState.RUNNING_PAUSED) {
                        startLiveTickIfNeeded();
                        if (controller != null) controller.requestLiveSnapshot();
                        if (liveQtyPanel != null) liveQtyPanel.setVisibility(View.VISIBLE);
                    } else {
                        stopLiveTick();
                        if (liveQtyPanel != null) liveQtyPanel.setVisibility(View.GONE);
                    }
                });
            }

            @Override
            public void onLiveStatus(String liveText) {
                ui.post(() -> txtLive.setText(liveText));
            }

            @Override
            public void onLiveQty(double net, double gross) {
                ui.post(() -> {
                    if (Double.compare(net, lastNet) != 0) {
                        if (txtQtyNet != null) txtQtyNet.setText(String.format("NET: %.3f", net));
                        lastNet = net;
                    }
                    if (Double.compare(gross, lastGross) != 0) {
                        if (txtQtyGross != null) txtQtyGross.setText(String.format("GROSS: %.3f", gross));
                        lastGross = gross;
                    }
                    if (liveQtyPanel != null) liveQtyPanel.setVisibility(View.VISIBLE);
                });
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

        pendingInitRunnable = () -> {
            if (controller != null) controller.initialize();
        };
        ui.postDelayed(pendingInitRunnable, 300);

        log("Connect LCP appliqué");
        stopLiveTick();
        if (controller != null) controller.requestLiveSnapshot();
    }

    private void updateButtons(DeliveryState state, boolean stableOff) {
        boolean connected = (state == DeliveryState.CONNECTED);
        boolean paused = (state == DeliveryState.RUNNING_PAUSED);
        boolean flowing = (state == DeliveryState.RUNNING_FLOWING);

        // A/C actifs selon état (auto enable/disable OK — tu as confirmé que le point (1) concerne seulement l'auto-click)
        btnA.setEnabled(connected);
        btnC.setEnabled(connected);

        // Continuer/Terminer : seulement paused + stableOff
        boolean allow = paused && stableOff && !flowing;
        btnContinue.setEnabled(allow);
        btnFinish.setEnabled(allow);

        btnB.setEnabled(true);
    }

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
