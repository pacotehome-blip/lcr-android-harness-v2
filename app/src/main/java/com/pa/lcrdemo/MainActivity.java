
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    // ---------- UI base ----------
    private EditText edtTo, edtFrom, edtProduct, edtPreset;
    private Button btnCopyLog, btnClearLog, btnConnect;
    private Button btnA, btnB, btnC;
    private Button btnContinue, btnFinish;
    private CheckBox switchIoLog;
    private TextView txtLog;
    private ScrollView logScroll;

    // ---------- LIVE (ligne unique) ----------
    private TextView txtLive;

    // ---------- (Optionnel) statut LINK (si ajouté au XML) ----------
    private TextView txtLinkStatus;

    // ---------- UI USB ----------
    private Spinner spnUsbDevices;
    private Button btnScanUsb, btnPingUsb;

    // ---------- UI ticket + printer ----------
    private TextView txtTicketNumber;
    private Spinner spnTicketRequired;
    private Button btnRefreshTicket;
    private Button btnClearShift;
    private TextView txtPrinterStatus;
    private Button btnRefreshPrinter;

    // ---------- UI print pending ----------
    private Button btnPrintPending;

    // ---------- USB backend ----------
    private UsbManager usbManager;
    private final List<UsbDevice> usbList = new ArrayList<>();

    // ---------- LCP backend ----------
    private UsbSerialPort port = null;
    private UsbDevice lastSelectedDevice = null;
    private LcpLink link;
    private DeliveryController ctrl;

    private static final int POLL_MS = 200;
    private static final String ACTION_USB_PERMISSION = "com.pa.lcrdemo.USB_PERMISSION";
    private PendingIntent usbPermissionIntent;

    // ==========================================================
    // UI/LINK state
    // ==========================================================
    private enum LinkState { DISCONNECTED, USB_READY, LCP_READY }
    private volatile LinkState linkState = LinkState.DISCONNECTED;

    // "livraison active" revalidée au resync (via delCode / DELIVERY_ACTIVE)
    private volatile boolean deliveryActiveFlag = false;

    // Auto reconnect à l'attach (comportement terrain)
    private final boolean autoReconnect = true;

    // ==========================================================
    // FIX: anti-spam latches (évite popups répétitifs)
    // ==========================================================
    private boolean recoveryDialogShown = false;
    private boolean printDialogShown = false;

    // ==========================================================
    // USB Permission Receiver
    // ==========================================================
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
                if (p != null) {
                    setPort(p);

                    // Auto init + resync après permission (terrain)
                    if (autoReconnect) {
                        initLcp();
                        doResyncStatus();
                    }
                }
            } else {
                log("Permission USB REFUSÉE: " + usbLabel(device));
                onDisconnected("USB permission refusée");
            }
        }
    };

    // ==========================================================
    // USB attach/detach Receiver (clé terrain)
    // ==========================================================
    private final BroadcastReceiver usbAttachDetachReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String a = intent.getAction();
            if (a == null) return;

            UsbDevice dev = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) {
                if (dev == null) return;

                // Si c’est notre device courant (match VID/PID), on coupe tout
                if (lastSelectedDevice != null &&
                        dev.getVendorId() == lastSelectedDevice.getVendorId() &&
                        dev.getProductId() == lastSelectedDevice.getProductId()) {
                    onDisconnected("USB DETACHED: " + usbLabel(dev));
                } else {
                    // En terrain, si un port était ouvert et n'importe quel detach survient,
                    // tu peux aussi choisir de couper. Ici on reste conservateur.
                    log("[USB] DETACHED (autre): " + usbLabel(dev));
                }
                return;
            }

            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)) {
                log("[USB] ATTACHED: " + (dev != null ? usbLabel(dev) : "(null)"));
                scanUsbDevices();

                if (!autoReconnect) return;

                // Tentative auto sur le dernier device sélectionné
                if (lastSelectedDevice != null) {
                    openDeviceByMatch(lastSelectedDevice);
                }
            }
        }
    };

    // ==========================================================
    // Lifecycle
    // ==========================================================
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

        IntentFilter f = new IntentFilter();
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbAttachDetachReceiver, f);

        // Dumps I/O contrôlés par le switch
        LcpLink.DUMP_TX = switchIoLog.isChecked();
        LcpLink.DUMP_RX = switchIoLog.isChecked();

        txtPrinterStatus.setText("Imprimante: (non connecté / non lu)");
        txtPrinterStatus.setBackgroundColor(0xFFEEEEEE);

        if (txtLive != null) {
            txtLive.setText("LIVE: (en attente)");
        }

        // UI OFF au départ (terrain)
        setUiConnected(false);

        log("Prêt. 1) Choisir USB 2) Ouvrir/Ping 3) Connect (LCP).");
        new android.os.Handler().postDelayed(this::scanUsbDevices, 400);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(usbPermissionReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(usbAttachDetachReceiver); } catch (Exception ignored) {}
        safeClosePort();
    }

    // ==========================================================
    // Bind UI
    // ==========================================================
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

        switchIoLog = findViewById(R.id.switchIoLog);
        txtLog = findViewById(R.id.txtLog);
        logScroll = findViewById(R.id.logScroll);

        // LIVE
        txtLive = findViewById(R.id.txtLive);

        // (Optionnel) si ajouté au layout
        txtLinkStatus = findViewById(R.id.txtLinkStatus);

        btnCopyLog = findViewById(R.id.btnCopyLog);
        btnClearLog = findViewById(R.id.btnClearLog);

        spnUsbDevices = findViewById(R.id.spnUsbDevices);
        btnScanUsb = findViewById(R.id.btnScanUsb);
        btnPingUsb = findViewById(R.id.btnPingUsb);

        txtTicketNumber = findViewById(R.id.txtTicketNumber);
        spnTicketRequired = findViewById(R.id.spnTicketRequired);
        btnRefreshTicket = findViewById(R.id.btnRefreshTicket);
        btnClearShift = findViewById(R.id.btnClearShift);

        txtPrinterStatus = findViewById(R.id.txtPrinterStatus);
        btnRefreshPrinter = findViewById(R.id.btnRefreshPrinter);

        btnPrintPending = findViewById(R.id.btnPrintPending);
    }

    // ==========================================================
    // Defaults
    // ==========================================================
    private void applyDefaultValues() {
        edtTo.setText("0xFA");
        edtFrom.setText("0xFF");
        edtProduct.setText("1");
        edtPreset.setText("50.0");

        switchIoLog.setChecked(true);

        ArrayAdapter<String> ticketReqAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                new String[]{
                        "0 = Oui (ticket requis)",
                        "1 = Non (ticket non requis, imprime si possible)",
                        "2 = Jamais (ne pas imprimer)"
                }
        );
        ticketReqAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spnTicketRequired.setAdapter(ticketReqAdapter);
        spnTicketRequired.setSelection(1);

        txtTicketNumber.setText("-");
    }

    // ==========================================================
    // Handlers
    // ==========================================================
    private void installHandlers() {

        switchIoLog.setOnCheckedChangeListener((btn, checked) -> {
            LcpLink.DUMP_TX = checked;
            LcpLink.DUMP_RX = checked;
            log("[UI] I/O logs " + (checked ? "activés" : "désactivés"));
        });

        btnScanUsb.setOnClickListener(v -> scanUsbDevices());
        btnPingUsb.setOnClickListener(v -> openSelectedUsbDevice());
        btnConnect.setOnClickListener(v -> initLcp());

        btnCopyLog.setOnClickListener(v -> {
            ClipboardManager clip = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clip.setPrimaryClip(ClipData.newPlainText("log", txtLog.getText().toString()));
            log("Log copié.");
        });

        btnClearLog.setOnClickListener(v -> txtLog.setText(""));

        btnA.setOnClickListener(v -> {
            if (ctrl == null) return;
            log("A : END");
            ctrl.endGracefully(20000, POLL_MS);
        });

        btnB.setOnClickListener(v -> {
            if (ctrl == null) return;
            log("PING (#23)");
            ctrl.pingStatus(POLL_MS);
        });

        btnC.setOnClickListener(v -> {
            if (ctrl == null) return;
            int product = readInt(edtProduct, 1);
            double preset = readDouble(edtPreset, 0);
            log("C : Start Delivery (product=" + product + ", preset=" + preset + ")");
            ctrl.startOpenMode(product, preset, 20000, POLL_MS);
        });

        // CONTINUER = Cmd #0
        btnContinue.setOnClickListener(v -> {
            if (ctrl == null) return;
            log("Continuer → Cmd#0 (Start/Resume)");
            ctrl.resumeDelivery(POLL_MS);
        });

        // Terminer (normal)
        btnFinish.setOnClickListener(v -> {
            if (ctrl == null) return;
            log("Terminer → END");
            ctrl.endGracefully(20000, POLL_MS);
        });

        btnRefreshTicket.setOnClickListener(v -> {
            if (ctrl == null) return;
            ctrl.refreshTicketInfo(POLL_MS);
        });

        spnTicketRequired.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            boolean first = true;

            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (first) { first = false; return; }
                if (ctrl != null) ctrl.setTicketRequired(position, POLL_MS);
            }

            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        btnClearShift.setOnClickListener(v -> {
            if (ctrl == null) return;
            log("SHIFT : ClearShift");
            ctrl.clearShiftNow(POLL_MS);
        });

        btnRefreshPrinter.setOnClickListener(v -> {
            if (ctrl == null) return;
            ctrl.refreshPrinterStatus(POLL_MS);
        });

        btnPrintPending.setOnClickListener(v -> {
            if (ctrl == null) return;
            log("PRINT PENDING : Cmd #6");
            ctrl.printPendingTicket(POLL_MS, 25000);
        });
    }

    // ==========================================================
    // UI state (CONNECTED/DISCONNECTED)
    // ==========================================================
    private void setUiConnected(boolean connected) {
        // USB actions restent possibles
        spnUsbDevices.setEnabled(true);
        btnScanUsb.setEnabled(true);
        btnPingUsb.setEnabled(true);

        // Connect LCP seulement si port OK
        btnConnect.setEnabled(connected && port != null);

        boolean lcpOk = connected && (ctrl != null);

        // commandes LCP
        btnA.setEnabled(lcpOk);
        btnB.setEnabled(lcpOk);

        // Start : si pas de livraison active
        btnC.setEnabled(lcpOk && !deliveryActiveFlag);

        // Continue/Finish : si livraison active (revalidée au resync)
        btnContinue.setEnabled(lcpOk && deliveryActiveFlag);
        btnFinish.setEnabled(lcpOk && deliveryActiveFlag);

        // Ticket / printer
        btnRefreshTicket.setEnabled(lcpOk);
        btnRefreshPrinter.setEnabled(lcpOk);
        btnPrintPending.setEnabled(lcpOk);
        spnTicketRequired.setEnabled(lcpOk);
        btnClearShift.setEnabled(lcpOk);

        // Feedback
        if (!connected && txtLive != null) {
            txtLive.setText("LIVE: DISCONNECTED");
        }

        if (txtLinkStatus != null) {
            txtLinkStatus.setText(connected ? "LINK: CONNECTED" : "LINK: DISCONNECTED");
            txtLinkStatus.setBackgroundColor(connected ? 0xFFC8E6C9 : 0xFFFFCDD2);
        }
    }

    // ==========================================================
    // UI state (DeliveryController.State)
    // ==========================================================
    private void setUiForState(DeliveryController.State s) {
        boolean running = (s == DeliveryController.State.RUNNING);
        boolean startingOrPre = (s == DeliveryController.State.STARTING || s == DeliveryController.State.PRESTART);
        boolean ending = (s == DeliveryController.State.ENDING);

        // Start interdit si épisode en cours OU deliveryActiveFlag
        btnC.setEnabled(!(running || startingOrPre || ending) && !deliveryActiveFlag);

        // Finish si épisode en cours OU deliveryActiveFlag
        btnFinish.setEnabled(running || startingOrPre || deliveryActiveFlag);

        // Continue si deliveryActiveFlag et pas RUNNING (ajuste si besoin)
        btnContinue.setEnabled(deliveryActiveFlag && !running);

        btnRefreshPrinter.setEnabled(!running);
        btnPrintPending.setEnabled(!running);
        spnTicketRequired.setEnabled(!running);
        btnClearShift.setEnabled(!running);
    }

    // ==========================================================
    // Init LCP
    // ==========================================================
    private void initLcp() {
        if (port == null) {
            log("ERR: Port USB non initialisé.");
            setUiConnected(false);
            return;
        }

        try {
            int to = parseHex(edtTo, 0xFA);
            int from = parseHex(edtFrom, 0xFF);

            log(String.format("Init LCP → to=0x%02X, from=0x%02X…", to, from));

            link = new LcpLink(port, to, from, true);
            LcpLink.setLogger(line -> log("[IO] " + line));

            ctrl = new DeliveryController(
                    link,
                    new DeliveryEventsImpl(),
                    Executors.newSingleThreadExecutor()
            );

            linkState = LinkState.LCP_READY;
            log("LCP prêt.");

            // UI ON
            runOnUiThread(() -> setUiConnected(true));

            // Resync état (status/printer/ticket)
            doResyncStatus();

        } catch (Exception e) {
            log("Erreur init LCP : " + e.getMessage());
            onDisconnected("Init LCP failed");
        }
    }

    // ==========================================================
    // Resync status (après reconnexion)
    // ==========================================================
    private void doResyncStatus() {
        if (ctrl == null) return;
        log("[LINK] Resync status...");
        ctrl.pingStatus(POLL_MS);
        ctrl.refreshTicketInfo(POLL_MS);
        ctrl.refreshPrinterStatus(POLL_MS);
    }

    // ==========================================================
    // Disconnect handler (gel UI + cleanup)
    // ==========================================================
    private void onDisconnected(String reason) {
        log("[LINK] DISCONNECTED: " + reason);

        linkState = LinkState.DISCONNECTED;
        deliveryActiveFlag = false;

        ctrl = null;
        link = null;

        safeClosePort();

        runOnUiThread(() -> setUiConnected(false));
    }

    private void safeClosePort() {
        try { if (port != null) port.close(); } catch (Exception ignored) {}
        port = null;
    }

    // ==========================================================
    // Logging (TRACE empilée)
    // ==========================================================
    private void log(String s) {
        if (!switchIoLog.isChecked()) return;

        runOnUiThread(() -> {
            txtLog.append(s + "\n");
            if (logScroll != null) {
                logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    // ==========================================================
    // Parse helpers
    // ==========================================================
    private int parseHex(EditText edt, int def) {
        try {
            String t = edt.getText().toString().trim();
            if (t.startsWith("0x") || t.startsWith("0X"))
                return Integer.parseInt(t.substring(2), 16) & 0xFF;
            if (!t.isEmpty())
                return Integer.parseInt(t, 16) & 0xFF;
        } catch (Exception ignored) {}
        return def;
    }

    private int readInt(EditText edt, int def) {
        try { return Integer.parseInt(edt.getText().toString().trim()); }
        catch (Exception ignored) { return def; }
    }

    private double readDouble(EditText edt, double def) {
        try { return Double.parseDouble(edt.getText().toString().trim()); }
        catch (Exception ignored) { return def; }
    }

    // ==========================================================
    // USB helpers
    // ==========================================================
    private static String usbLabel(UsbDevice d) {
        String m = d.getManufacturerName();
        String p = d.getProductName();
        if (m == null) m = "?";
        if (p == null) p = "?";
        return String.format("%s - %s (VID=%04X PID=%04X)",
                m, p, d.getVendorId(), d.getProductId());
    }

    private void scanUsbDevices() {
        usbList.clear();
        if (usbManager == null) return;

        usbList.addAll(usbManager.getDeviceList().values());

        List<String> labels = new ArrayList<>();
        for (UsbDevice d : usbList) labels.add(usbLabel(d));

        spnUsbDevices.setAdapter(new ArrayAdapter<>(
                this, android.R.layout.simple_spinner_item, labels));

        log("Scan USB : " + labels.size() + " périphérique(s).");
    }

    private void openSelectedUsbDevice() {
        int index = spnUsbDevices.getSelectedItemPosition();
        if (index < 0 || index >= usbList.size()) return;

        UsbDevice dev = usbList.get(index);
        lastSelectedDevice = dev;

        if (usbManager != null && !usbManager.hasPermission(dev)) {
            usbManager.requestPermission(dev, usbPermissionIntent);
            return;
        }

        UsbSerialPort p = tryOpenDevice(dev);
        if (p != null) setPort(p);
    }

    // Tentative d'ouverture "par match" (auto-reconnect)
    private void openDeviceByMatch(UsbDevice dev) {
        if (usbManager == null || dev == null) return;

        if (!usbManager.hasPermission(dev)) {
            usbManager.requestPermission(dev, usbPermissionIntent);
            return;
        }

        UsbSerialPort p = tryOpenDevice(dev);
        if (p != null) {
            setPort(p);
            initLcp();
            doResyncStatus();
        }
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
            try { p.close(); } catch (Exception ignored) {}
            log("ERR: Ouverture USB échouée: " + e.getMessage());
            return null;
        }
    }

    public void setPort(UsbSerialPort p) {
        this.port = p;
        linkState = LinkState.USB_READY;

        log("USB prêt.");
        runOnUiThread(() -> setUiConnected(true));
    }

    // ==========================================================
    // FIX: Recovery / Print dialogs (indépendant du flow)
    // ==========================================================
    private void maybeShowRecoveryOrPrintDialogs(int dc, boolean ticketPending) {
        boolean deliveryActive = (dc & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;

        // Reset latches quand l'épisode est terminé
        if (!ticketPending) {
            recoveryDialogShown = false;
            printDialogShown = false;
            return;
        }

        // A) Recovery prioritaire : DELIVERY_ACTIVE && TICKET_PENDING
        if (deliveryActive && ticketPending) {
            if (recoveryDialogShown) return;
            recoveryDialogShown = true;

            runOnUiThread(() -> new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Livraison précédente détectée")
                    .setMessage("Un ticket est en attente.\n\nQue veux-tu faire ?")
                    .setCancelable(false)
                    .setPositiveButton("Continuer (Cmd#0)", (d, w) -> {
                        log("Recovery: Continuer → Cmd#0");
                        if (ctrl != null) ctrl.resumeDelivery(POLL_MS);
                    })
                    .setNegativeButton("Terminer (Cmd#2)", (d, w) -> {
                        log("Recovery: Terminer → Cmd#2 (Recovery)");
                        if (ctrl != null) ctrl.endRecoveryOrExplain(20000, POLL_MS);
                    })
                    .show());
            return;
        }

        // B) Impression possible : !DELIVERY_ACTIVE && TICKET_PENDING
        if (!deliveryActive && ticketPending) {
            if (printDialogShown) return;
            printDialogShown = true;

            runOnUiThread(() -> new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Ticket en attente")
                    .setMessage("La livraison est terminée mais un ticket est encore en attente.\n\nImprimer maintenant ?")
                    .setCancelable(false)
                    .setPositiveButton("Imprimer (Cmd#6)", (d, w) -> {
                        log("TicketPending: Imprimer → Cmd#6");
                        if (ctrl != null) ctrl.printPendingTicket(POLL_MS, 25000);
                    })
                    .setNegativeButton("Plus tard", null)
                    .show());
        }
    }

    // ==========================================================
    // Delivery Events implementation
    // ==========================================================
    private class DeliveryEventsImpl implements DeliveryController.DeliveryEvents {

        @Override
        public void onStateChanged(DeliveryController.State s) {
            log("État = " + s);
            runOnUiThread(() -> setUiForState(s));
        }

        @Override
        public void onFlowStarted() {
            log("Flow START");
            runOnUiThread(() -> {
                btnContinue.setEnabled(false);
                btnFinish.setEnabled(true);
            });
        }

        @Override
        public void onFlowStopped() {
            log("Flow STOP → débit=0");
            runOnUiThread(() -> {
                // Ici, tu continues à autoriser Continue/Finish à la pause
                btnContinue.setEnabled(true);
                btnFinish.setEnabled(true);

                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Débit arrêté")
                        .setMessage("Le débit est à 0.\n\nPause OU réservoir plein.\n\nQue veux-tu faire ?")
                        .setPositiveButton("Continuer", (d, w) -> {
                            log("Popup: Continuer → Cmd#0");
                            if (ctrl != null) ctrl.resumeDelivery(POLL_MS);
                        })
                        .setNegativeButton("Terminer", (d, w) -> {
                            log("Popup: Terminer → END");
                            if (ctrl != null) ctrl.endGracefully(20000, POLL_MS);
                        })
                        .setNeutralButton("Annuler", null)
                        .show();
            });
        }

        @Override
        public void onProgress(DeliveryController.DeliveryProgress p) {
            // ✅ LIVE: une seule ligne (ne s’empile pas)
            boolean flow = (p.dc & LcpLink.LCRSc_FLOW_ACTIVE) != 0;
            final String live = String.format(
                    "t=%dms NET=%.1f (Δ=%.1f) GROSS=%.1f (Δ=%.1f) FLOW=%s dc=%04X",
                    p.tSinceStartMs,
                    p.netL, p.deliveredNetL,
                    p.grossL, p.deliveredGrossL,
                    flow ? "1" : "0",
                    p.dc
            );

            runOnUiThread(() -> {
                if (txtLive != null) txtLive.setText(live);
            });
        }

        @Override
        public void onTicketNumber(int ticketNumber) {
            runOnUiThread(() -> txtTicketNumber.setText(String.valueOf(ticketNumber)));
        }

        @Override
        public void onTicketRequired(int mode) {
            int idx = Math.max(0, Math.min(2, mode));
            runOnUiThread(() -> spnTicketRequired.setSelection(idx));
        }

        @Override
        public void onPrinterStatus(LcpLink.MachineStatusEx ms, boolean ticketPending) {

            // ✅ Revalidation "delivery active" au fil de l’eau (resync)
            boolean da = (ms.delCode & LcpLink.LCRSc_DELIVERY_ACTIVE) != 0;
            deliveryActiveFlag = da;

            // ✅ Re-apply UI connectée selon état réel
            runOnUiThread(() -> setUiConnected(true));

            final String text =
                    "Imprimante: " + ms.printer().summary() +
                            " \n ticketPending=" + ticketPending +
                            " \n ds=0x" + String.format("%04X", ms.delStatus) +
                            " dc=0x" + String.format("%04X", ms.delCode);

            runOnUiThread(() -> {
                txtPrinterStatus.setText(text);

                boolean hardErr =
                        ms.printer().outOfPaper ||
                        ms.printer().noProcessor ||
                        ms.printer().processorError;

                boolean printing = ms.printer().printingStarted;

                int bg;
                if (hardErr) bg = 0xFFFFCDD2;
                else if (printing) bg = 0xFFFFF9C4;
                else bg = 0xFFC8E6C9;

                txtPrinterStatus.setBackgroundColor(bg);
            });

            // ✅ Trigger Recovery/Print indépendamment du FLOW
            maybeShowRecoveryOrPrintDialogs(ms.delCode, ticketPending);
        }

        @Override
        public void onOperatorAlert(DeliveryController.OperatorAlert alert) {
            runOnUiThread(() -> new AlertDialog.Builder(MainActivity.this)
                    .setTitle(alert.title)
                    .setMessage(alert.message + "\n\n---\nDIAGNOSTIC:\n" + alert.diagnostics)
                    .setPositiveButton("OK", null)
                    .setNeutralButton("Copier diagnostic", (d, w) -> {
                        ClipboardManager clip = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        clip.setPrimaryClip(ClipData.newPlainText("diag", alert.diagnostics));
                        log("Diagnostic copié.");
                    })
                    .show());
        }

        @Override
        public void onError(String msg, Throwable t) {
            log("ERR[" + msg + "] → " + (t != null ? t.getMessage() : "(null)"));

            // Heuristique terrain : si erreur IO/USB/Serial, on gèle l’UI
            String m = ((msg != null) ? msg : "") + " " + ((t != null && t.getMessage() != null) ? t.getMessage() : "");
            m = m.toLowerCase();

            if (m.contains("usb") || m.contains("serial") || m.contains("io") || m.contains("device")) {
                onDisconnected("I/O error: " + msg);
            }
        }

        @Override
        public void onLog(String line) {
            log("[LCP] " + line);
        }
    }
}
