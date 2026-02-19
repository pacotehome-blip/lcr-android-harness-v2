
package com.pa.lcrdemo;

import androidx.appcompat.app.AlertDialog;
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
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

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
    private Button btnConnect;
    private Button btnA, btnB, btnC;
    private Button btnContinue, btnFinish;
    private Button btnCopyLog, btnClearLog;

    private CheckBox switchIoLog;
    private TextView txtLog;
    private ScrollView logScroll;

    private TextView txtLive;
    private TextView txtQtyNet, txtQtyGross;

    // ================= USB =================
    private Spinner spnUsbDevices;
    private Button btnScanUsb, btnPingUsb;

    // ================= Ticket / Printer =================
    private TextView txtTicketNumber;
    private Spinner spnTicketRequired;
    private Button btnRefreshTicket;
    private Button btnClearShift;
    private TextView txtPrinterStatus;
    private Button btnRefreshPrinter;
    private Button btnPrintPending;

    // ================= Backend =================
    private UsbManager usbManager;
    private final List<UsbDevice> usbList = new ArrayList<>();

    private UsbSerialPort port = null;
    private LcpLink link;
    private DeliveryController ctrl;

    private static final int POLL_MS = 200;

    private static final String ACTION_USB_PERMISSION = "com.pa.lcrdemo.USB_PERMISSION";
    private PendingIntent usbPermissionIntent;

    // ================= Log batching =================
    private final Object logLock = new Object();
    private final StringBuilder logBuf = new StringBuilder(8192);
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean flushScheduled = false;
    private static final int LOG_FLUSH_MS = 250;
    private static final int LOG_MAX_CHARS = 20000;

    // ================= USB permission receiver =================
    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags = PendingIntent.FLAG_MUTABLE;
        }
        usbPermissionIntent = PendingIntent.getBroadcast(
                this, 0, new Intent(ACTION_USB_PERMISSION), piFlags
        );
        registerReceiver(usbPermissionReceiver, new IntentFilter(ACTION_USB_PERMISSION));

        LcpLink.DUMP_TX = switchIoLog.isChecked();
        LcpLink.DUMP_RX = switchIoLog.isChecked();

        txtPrinterStatus.setText("Imprimante: (non connecté / non lu)");
        txtPrinterStatus.setBackgroundColor(0xFFEEEEEE);

        txtLive.setText("LIVE: (en attente)");
        txtQtyNet.setText("NET: 0.0");
        txtQtyGross.setText("GROSS: 0.0");

        btnContinue.setEnabled(false);
        btnFinish.setEnabled(false);

        log("Prêt. 1) Choisir USB 2) Ouvrir/Ping 3) Connect (LCP).");
        new Handler().postDelayed(this::scanUsbDevices, 400);
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
            LcpLink.setLogger(line -> log("[IO] " + line));

            // ✅ CORRECTIF CRITIQUE : ouvrir la PollWindow
            link.openPollWindow();

            ctrl = new DeliveryController(
                    link,
                    new DeliveryEventsImpl(),
                    Executors.newSingleThreadExecutor()
            );

            log("LCP prêt.");

            ctrl.ensureDefaultTicketRequiredIs1(POLL_MS);
            ctrl.refreshTicketInfo(POLL_MS);
            ctrl.refreshPrinterStatus(POLL_MS);
            ctrl.recoverActiveDelivery(POLL_MS);

        } catch (Exception e) {
            log("Erreur init LCP : " + e.getMessage());
        }
    }

    // ================= PUBLIC (UsbReceiver) =================
    public void setPort(UsbSerialPort p) {
        this.port = p;
        log("USB prêt.");
    }

    // ================= Handlers =================
    private void installHandlers() {
        btnConnect.setOnClickListener(v -> initLcp());

        btnC.setOnClickListener(v -> {
            if (ctrl == null) return;
            int product = readInt(edtProduct, 1);
            double preset = readDouble(edtPreset, 0);
            log("C : Start Delivery (product=" + product + ", preset=" + preset + ")");
            ctrl.startOpenMode(product, preset, 20000, POLL_MS);
        });

        btnContinue.setOnClickListener(v -> {
            if (ctrl == null) return;
            log("Continuer → Cmd#0");
            ctrl.resumeDelivery(POLL_MS);
        });

        btnFinish.setOnClickListener(v -> {
            if (ctrl == null) return;
            log("Terminer → END");
            ctrl.endGracefully(20000, POLL_MS);
        });

        btnB.setOnClickListener(v -> {
            if (ctrl == null) return;
            log("PING");
            ctrl.pingStatus(POLL_MS);
        });

        btnClearShift.setOnClickListener(v -> {
            if (ctrl == null) return;
            log("SHIFT : ClearShift");
            ctrl.clearShiftNow(POLL_MS);
        });

        btnPrintPending.setOnClickListener(v -> {
            if (ctrl == null) return;
            log("PRINT PENDING");
            ctrl.printPendingTicket(POLL_MS, 25000);
        });
    }

    // ================= Delivery Events =================
    private class DeliveryEventsImpl implements DeliveryController.DeliveryEvents {

        @Override
        public void onStateChanged(DeliveryController.State s) {
            log("État = " + s);
        }

        @Override
        public void onFlowStarted() { log("Flow START"); }

        @Override
        public void onFlowStopped() { log("Flow STOP"); }

        @Override
        public void onProgress(DeliveryController.DeliveryProgress p) {
            final String live = String.format(
                    "STATE=%s NET=%.1f GROSS=%.1f FLOW=%s",
                    p.deliveryState, p.netL, p.grossL,
                    p.flowActive ? "1" : "0"
            );

            runOnUiThread(() -> {
                txtLive.setText(live);
                txtQtyNet.setText(String.format("NET: %.1f", p.netL));
                txtQtyGross.setText(String.format("GROSS: %.1f", p.grossL));

                if (p.deliveryState == LcpDeliveryState.ACTIVE_FLOWING) {
                    btnContinue.setEnabled(false);
                    btnFinish.setEnabled(true);
                } else if (p.deliveryState == LcpDeliveryState.ACTIVE_PAUSED) {
                    btnContinue.setEnabled(true);
                    btnFinish.setEnabled(true);
                } else {
                    btnContinue.setEnabled(false);
                    btnFinish.setEnabled(false);
                }
            });
        }

        @Override public void onTicketNumber(int n) {
            runOnUiThread(() -> txtTicketNumber.setText(String.valueOf(n)));
        }

        @Override public void onTicketRequired(int mode) {
            runOnUiThread(() -> spnTicketRequired.setSelection(mode));
        }

        @Override
        public void onPrinterStatus(LcpLink.MachineStatusEx ms, boolean ticketPending) {
            runOnUiThread(() -> txtPrinterStatus.setText(ms.toString()));
        }

        @Override
        public void onError(String msg, Throwable t) {
            log("ERR[" + msg + "] " + (t != null ? t.getMessage() : ""));
        }

        @Override
        public void onLog(String line) {
            log("[LCP] " + line);
        }
    }

    // ================= Helpers (USB / Parse / Log) =================
    // … (inchangés, identiques à ta version précédente)

}
