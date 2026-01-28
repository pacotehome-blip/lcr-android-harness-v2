
package com.pa.lcrdemo;

import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import com.pa.lcr.LcrSimpleDeliverV2;
import com.pa.lcr.lcp.LcpLink;
import com.pa.lcr.lcp.LcpOps;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String ACTION_USB_PERMISSION = "com.pa.lcrdemo.USB_PERMISSION";

    private TextView log;
    private EditText edtTo, edtFrom, edtProduct, edtPreset;

    private UsbSerialPort serialPort;
    private UsbDevice currentDevice;

    // Log buffer
    private final StringBuilder logBuf = new StringBuilder(4096);

    // Sérialisation des accès LCP
    private final Object lcpLock = new Object();
    private final ExecutorService lcpExec = Executors.newSingleThreadExecutor();

    // Références UI
    private Button btnA, btnB, btnC, btnScan, btnSendHex, btnTestUsb, btnConnect, btnDiag, btnStart;

    /* ================================================================
       BROADCAST RECEIVERS
       ================================================================ */
    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            synchronized (this) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                if (granted) {
                    append("Permission USB accordée, ouverture...\n");
                    connectPort(device);
                } else {
                    append("Permission USB refusée\n");
                }
            }
        }
    };

    private final BroadcastReceiver usbAttachDetach = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)) {
                append("USB attached — cliquez 'Connexion USB'\n");
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) {
                append("USB detached\n");
                try { if (serialPort != null) serialPort.close(); } catch(Exception ignored){}
                serialPort = null;
            }
        }
    };

    /* ================================================================
       onCreate()
       ================================================================ */
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        log        = findViewById(R.id.txtLog);
        edtTo      = findViewById(R.id.edtTo);
        edtFrom    = findViewById(R.id.edtFrom);
        edtProduct = findViewById(R.id.edtProduct);
        edtPreset  = findViewById(R.id.edtPreset);

        btnConnect = findViewById(R.id.btnConnect);
        btnDiag    = findViewById(R.id.btnDiag);
        btnStart   = findViewById(R.id.btnStart);
        btnScan    = findViewById(R.id.btnScan);
        btnSendHex = findViewById(R.id.btnSendHex);
        btnTestUsb = findViewById(R.id.btnTestUsb);
        btnA       = findViewById(R.id.btnA);
        btnB       = findViewById(R.id.btnB);
        btnC       = findViewById(R.id.btnC);

        // Forcer To=0xFA, From=0xFF
        ensureDefaultAddresses();

        // Receivers
        registerReceiver(usbPermissionReceiver, new IntentFilter(ACTION_USB_PERMISSION));
        IntentFilter f = new IntentFilter();
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbAttachDetach, f);

        // I/O TX/RX Logging
        CheckBox switchIoLog = findViewById(R.id.switchIoLog);
        LcpLink.setLogger(this::appendAndBuffer);
        switchIoLog.setOnCheckedChangeListener((btn, checked) -> {
            LcpLink.DUMP_TX = checked;
            LcpLink.DUMP_RX = checked;
            append("I/O log " + (checked ? "activé" : "désactivé") + "\n");
        });

        // Copier/Effacer log
        findViewById(R.id.btnCopyLog).setOnClickListener(v -> {
            ClipboardManager cb = (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
            if (cb != null) {
                cb.setPrimaryClip(ClipData.newPlainText("lcr_log", logBuf.toString()));
                append("Log copié dans le presse-papiers\n");
            }
        });
        findViewById(R.id.btnClearLog).setOnClickListener(v -> {
            logBuf.setLength(0);
            runOnUiThread(() -> log.setText(""));
        });

        // Connexion
        if (btnConnect != null) btnConnect.setOnClickListener(v -> {
            ensureDefaultAddresses();
            requestAndOpenFirstPort();
        });

        // DIAG (dump USB + open + 0x28)
        if (btnDiag != null) btnDiag.setOnClickListener(v -> {
            ensureDefaultAddresses();
            runLcpTask(this::diagConnectAndStatus28_locked);
        });

        // START flow
        if (btnStart != null) btnStart.setOnClickListener(v -> {
            ensureDefaultAddresses();
            runLcpTask(this::startFlow_locked);
        });

        // Console : Scan / SendHex / TestUSB
        if (btnScan != null)    btnScan.setOnClickListener(v -> runLcpTask(this::scanNodes_locked));
        if (btnSendHex != null) btnSendHex.setOnClickListener(v -> promptAndSendHex());
        if (btnTestUsb != null) btnTestUsb.setOnClickListener(v -> {
            appendAndBuffer("=== TEST PORT USB ===");
            dumpUsb();
            if (openOrVerifyPort()) {
                testIoSuite(); // brut hors LCP
                runLcpTask(this::testMiniPingLcp_locked);
            }
        });

        // A/B/C : listeners
        if (btnA != null) btnA.setOnClickListener(this::onClickA);
        if (btnB != null) btnB.setOnClickListener(this::onClickB);
        if (btnC != null) btnC.setOnClickListener(this::onClickC);

        appendAndBuffer(String.format(
                "[UI] A=%s B=%s C=%s Scan=%s SendHex=%s TestUsb=%s",
                (btnA!=null), (btnB!=null), (btnC!=null), (btnScan!=null),
                (btnSendHex!=null), (btnTestUsb!=null)
        ));

        append("Prêt. Branchez le LCR puis cliquez 'Connexion USB'.\n");
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(usbPermissionReceiver); } catch(Exception ignored){}
        try { unregisterReceiver(usbAttachDetach); } catch(Exception ignored){}
        try { if(serialPort!=null) serialPort.close(); } catch(Exception ignored){}
        lcpExec.shutdownNow();
    }

    /* ================================================================
       onClick A/B/C
       ================================================================ */
    public void onClickA(View v) { ensureDefaultAddresses(); runLcpTask(this::macroResetEndClear_locked); }
    public void onClickB(View v) { ensureDefaultAddresses(); runLcpTask(this::macroPing28GetMachine23_locked); }
    public void onClickC(View v) { ensureDefaultAddresses(); runLcpTask(this::macroStartDelivery_locked); }

    /* ================================================================
       Sérialisation LCP : queue + lock + disable/enable UI
       ================================================================ */
    private void runLcpTask(Runnable r) {
        setButtonsEnabled(false);
        lcpExec.execute(() -> {
            try { r.run(); }
            finally { setButtonsEnabled(true); }
        });
    }

    private void setButtonsEnabled(final boolean enabled) {
        runOnUiThread(() -> {
            if (btnA!=null) btnA.setEnabled(enabled);
            if (btnB!=null) btnB.setEnabled(enabled);
            if (btnC!=null) btnC.setEnabled(enabled);
            if (btnScan!=null) btnScan.setEnabled(enabled);
            if (btnSendHex!=null) btnSendHex.setEnabled(enabled);
            if (btnTestUsb!=null) btnTestUsb.setEnabled(enabled);
            if (btnConnect!=null) btnConnect.setEnabled(enabled);
            if (btnDiag!=null) btnDiag.setEnabled(enabled);
            if (btnStart!=null) btnStart.setEnabled(enabled);
        });
    }

    /* ================================================================
       FORCER To=0xFA / From=0xFF
       ================================================================ */
    private void ensureDefaultAddresses() {
        runOnUiThread(() -> {
            String to = edtTo.getText() != null ? edtTo.getText().toString().trim() : "";
            String from = edtFrom.getText() != null ? edtFrom.getText().toString().trim() : "";
            boolean changed = false;
            if (!"0xFA".equalsIgnoreCase(to))  { edtTo.setText("0xFA");  changed = true; }
            if (!"0xFF".equalsIgnoreCase(from)){ edtFrom.setText("0xFF"); changed = true; }
            if (changed) append("Forçage adresses: To=0xFA, From=0xFF\n");
        });
    }

    /* ================================================================
       USB : Demander permission / Ouverture
       ================================================================ */
    private void requestAndOpenFirstPort() {
        UsbManager mgr = (UsbManager)getSystemService(Context.USB_SERVICE);
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(mgr);
        if (drivers.isEmpty()) { append("Aucun convertisseur USB‑Série détecté\n"); return; }

        UsbDevice dev = drivers.get(0).getDevice();
        currentDevice = dev;

        if (!mgr.hasPermission(dev)) {
            append("Demande de permission USB…\n");
            PendingIntent pi = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
            mgr.requestPermission(dev, pi);
            return;
        }
        connectPort(dev);
    }

    private void connectPort(UsbDevice dev) {
        try {
            UsbManager mgr = (UsbManager)getSystemService(Context.USB_SERVICE);
            UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(dev);
            if (driver == null) { append("Pas de driver compatible\n"); return; }

            UsbDeviceConnection conn = mgr.openDevice(dev);
            if (conn == null) { append("Impossible d’ouvrir le device USB\n"); return; }

            serialPort = driver.getPorts().get(0);
            serialPort.open(conn);
            serialPort.setParameters(19200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);

            // Pulse RTS/DTR
            try { serialPort.setRTS(false); } catch(Exception ignore){}
            try { serialPort.setDTR(false); } catch(Exception ignore){}
            Thread.sleep(100);
            try { serialPort.setRTS(true); } catch(Exception ignore){}
            try { serialPort.setDTR(true); } catch(Exception ignore){}

            serialPort.purgeHwBuffers(true, true);

            append("Port ouvert 19200 8N1 (DTR/RTS pulsed, purge OK)\n");
        } catch(Exception e) {
            append("ERREUR ouverture: " + e.getMessage() + "\n");
        }
    }

    private boolean openOrVerifyPort() {
        if (serialPort != null) { appendAndBuffer("[PORT] Port déjà ouvert (on réutilise)."); return true; }
        try {
            UsbManager mgr = (UsbManager)getSystemService(Context.USB_SERVICE);
            List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(mgr);
            if (drivers.isEmpty()) { appendAndBuffer("[PORT] Aucun driver trouvé (USB RS-232 absent ?)"); return false; }
            UsbSerialDriver driver = drivers.get(0);
            UsbDevice dev = driver.getDevice();
            if (!mgr.hasPermission(dev)) { appendAndBuffer("[PORT] Permission USB absente. Clique 'Connexion USB' d’abord."); return false; }
            UsbDeviceConnection conn = mgr.openDevice(dev);
            if (conn == null) { appendAndBuffer("[PORT] openDevice=null (permission ?)"); return false; }
            serialPort = driver.getPorts().get(0);
            serialPort.open(conn);
            serialPort.setParameters(19200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);

            try { serialPort.setRTS(false); } catch(Exception ignore){}
            try { serialPort.setDTR(false); } catch(Exception ignore){}
            Thread.sleep(100);
            try { serialPort.setRTS(true); } catch(Exception ignore){}
            try { serialPort.setDTR(true); } catch(Exception ignore){}
            serialPort.purgeHwBuffers(true, true);

            appendAndBuffer("[PORT] Ouvert 19200 8N1 (DTR/RTS pulsed, purge OK).");
            return true;
        } catch (Exception e) {
            appendAndBuffer("[PORT] ERREUR open: " + e.getMessage());
            return false;
        }
    }

    /* ================================================================
       DIAG — COMPLET
       ================================================================ */
    private void diagConnectAndStatus28_locked() {
        appendAndBuffer("=== DIAGNOSTIC COMPLET ===");
        dumpUsb();
        if (!openOrVerifyPort()) { appendAndBuffer("[DIAG] Ouverture échouée."); return; }

        synchronized (lcpLock) {
            try {
                try { serialPort.purgeHwBuffers(true, true); } catch(Exception ignored){}
                int to = 0xFA, from = 0xFF;
                LcpLink link = new LcpLink(serialPort, to, from, true);
                LcpOps ops = new LcpOps(link);
                int[] dsdc = ops.opDeliveryStatus(3500, 250);
                Thread.sleep(120);
                appendAndBuffer(String.format("[DIAG] DS=0x%04X DC=0x%04X %s %s",
                        dsdc[0], dsdc[1], dsBits(dsdc[0]), dcBits(dsdc[1])));
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            } catch (Exception e) {
                appendAndBuffer("[DIAG] ERREUR: " + e.getMessage());
            }
        }
    }

    /* ================================================================
       START FLOW
       ================================================================ */
    private void startFlow_locked() {
        try {
            if (serialPort == null) { append("Port non prêt — clique 'Connexion USB'.\n"); return; }

            runOnUiThread(() -> { edtTo.setText("0xFA"); edtFrom.setText("0xFF"); });

            LcrSimpleDeliverV2.Params p = new LcrSimpleDeliverV2.Params();
            p.port = serialPort; p.toAddr = 0xFA; p.fromAddr = 0xFF;
            p.product = safeParseInt(edtProduct.getText()!=null?edtProduct.getText().toString().trim():"1", 1);
            p.preset  = safeParseDouble(edtPreset.getText()!=null?edtPreset.getText().toString().trim():"0", 0.0);
            p.verbose = true; p.startAcceptFlow = true; p.ticketPost = "if-pending";

            append("Go → unlock/prestart/start...\n");
            LcrSimpleDeliverV2 lcr = new LcrSimpleDeliverV2(p);
            lcr.unlock();   Thread.sleep(120);
            lcr.prestart(); Thread.sleep(200);
            lcr.start();    Thread.sleep(120);
            appendAndBuffer("[C] start() OK — surveillez LIVE dans l’app.");
            try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        } catch (Exception ex) {
            append("ERREUR (startFlow thread): "+ ex.getMessage()+"\n");
        }
    }

    /* ================================================================
       MACROS A / B / C
       ================================================================ */
    private void macroResetEndClear_locked() {
        if (!openOrVerifyPort()) return;
        synchronized (lcpLock) {
            try {
                int to=0xFA, from=0xFF;
                LcpLink link = new LcpLink(serialPort, to, from, true);
                LcpOps  ops  = new LcpOps(link);
                LcpLink.setLogger(this::appendAndBuffer);
                LcpLink.DUMP_TX = true; LcpLink.DUMP_RX = true;

                try { serialPort.purgeHwBuffers(true, true); } catch(Exception ignored){}
                appendAndBuffer("[A] ISSUE #2 (END/RESET)");
                ops.opIssueCommand(0x02, 3500, 250);
                Thread.sleep(500);

                try { serialPort.purgeHwBuffers(true, true); } catch(Exception ignored){}
                appendAndBuffer("[A] ISSUE #6 (CLEAR TICKET)");
                ops.opIssueCommand(0x06, 3500, 250);
                Thread.sleep(200);

                // Attendre ticket=0 (DC & 0x0001), max ~8 s
                long t0 = System.currentTimeMillis();
                int[] dsdc;
                while (true) {
                    try { serialPort.purgeHwBuffers(true, true); } catch(Exception ignored){}
                    dsdc = ops.opDeliveryStatus(3500, 250);
                    appendAndBuffer(String.format("[A] POLL DS=0x%04X DC=0x%04X %s %s",
                            dsdc[0], dsdc[1], dsBits(dsdc[0]), dcBits(dsdc[1])));
                    if ((dsdc[1] & 0x0001) == 0) break;                 // ticket tombé
                    if (System.currentTimeMillis() - t0 > 8000) break;  // délai max
                    Thread.sleep(300);
                }

                appendAndBuffer(String.format("[A] FINAL DS=0x%04X DC=0x%04X %s %s",
                        dsdc[0], dsdc[1], dsBits(dsdc[0]), dcBits(dsdc[1])));
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}

            } catch (Exception e) {
                appendAndBuffer("[A] ERREUR: " + e.getMessage());
            }
        }
    }

    private void macroPing28GetMachine23_locked() {
        if (!openOrVerifyPort()) return;
        synchronized (lcpLock) {
            try {
                int to=0xFA, from=0xFF;
                LcpLink link = new LcpLink(serialPort, to, from, true);
                LcpOps  ops  = new LcpOps(link);
                LcpLink.setLogger(this::appendAndBuffer);
                LcpLink.DUMP_TX = true; LcpLink.DUMP_RX = true;

                try { serialPort.purgeHwBuffers(true, true); } catch(Exception ignored){}
                appendAndBuffer("[B] GET_DEL_STATUS (0x28)");
                int[] dsdc = ops.opDeliveryStatus(3500, 250);
                Thread.sleep(120);
                appendAndBuffer(String.format("[B] DS=0x%04X DC=0x%04X %s %s",
                        dsdc[0], dsdc[1], dsBits(dsdc[0]), dcBits(dsdc[1])));

                try { serialPort.purgeHwBuffers(true, true); } catch(Exception ignored){}
                appendAndBuffer("[B] GET_MACHINE (0x23)");
                int[] dev_ds_dc = ops.opMachineStatusFull(3500, 250);
                Thread.sleep(120);
                appendAndBuffer(String.format("[B] DEV=0x%04X DS=0x%04X DC=0x%04X %s %s",
                        dev_ds_dc[0], dev_ds_dc[1], dev_ds_dc[2], dsBits(dev_ds_dc[1]), dcBits(dev_ds_dc[2])));
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}

            } catch (Exception e) {
                appendAndBuffer("[B] ERREUR: " + e.getMessage());
            }
        }
    }

    private void macroStartDelivery_locked() {
        if (!openOrVerifyPort()) return;
        synchronized (lcpLock) {
            try {
                runOnUiThread(() -> { edtTo.setText("0xFA"); edtFrom.setText("0xFF"); });

                LcrSimpleDeliverV2.Params p = new LcrSimpleDeliverV2.Params();
                p.port = serialPort; p.toAddr = 0xFA; p.fromAddr = 0xFF;
                p.product = safeParseInt(edtProduct.getText()!=null?edtProduct.getText().toString().trim():"1", 1);
                p.preset  = safeParseDouble(edtPreset.getText()!=null?edtPreset.getText().toString().trim():"0", 0.0);
                p.verbose = true; p.startAcceptFlow = true; p.ticketPost = "if-pending";

                appendAndBuffer("[C] unlock/prestart/start...");
                LcrSimpleDeliverV2 lcr = new LcrSimpleDeliverV2(p);
                lcr.unlock();   Thread.sleep(120);
                lcr.prestart(); Thread.sleep(200);
                lcr.start();    Thread.sleep(120);
                appendAndBuffer("[C] start() OK — surveillez LIVE dans l’app.");
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}

            } catch (Exception e) {
                appendAndBuffer("[C] ERREUR: " + e.getMessage());
            }
        }
    }

    /* ================================================================
       SCAN nodes
       ================================================================ */
    private void scanNodes_locked() {
        if (!openOrVerifyPort()) return;

        synchronized (lcpLock) {
            try {
                final int from = 0xFF;
                LcpLink.setLogger(this::appendAndBuffer);
                LcpLink.DUMP_TX = true;
                LcpLink.DUMP_RX = true;

                for (int node = 1; node <= 16; node++) {
                    try {
                        try { serialPort.purgeHwBuffers(true, true); } catch(Exception ignored){}
                        LcpLink link = new LcpLink(serialPort, node, from, true);
                        LcpOps  ops  = new LcpOps(link);

                        int[] dsdc = ops.opDeliveryStatus(3500, 250);
                        Thread.sleep(80);
                        appendAndBuffer(String.format(
                                "[SCAN] Node=0x%02X → OK DS=0x%04X DC=0x%04X %s %s",
                                node, dsdc[0], dsdc[1], dsBits(dsdc[0]), dcBits(dsdc[1])));

                        final int n = node;
                        runOnUiThread(() -> edtTo.setText(String.format("0x%02X", n)));
                        break;
                    } catch (Exception ex) {
                        appendAndBuffer(String.format("[SCAN] Node=0x%02X → no reply (%s)", node, ex.getMessage()));
                    }
                }
            } catch (Exception e) {
                appendAndBuffer("[SCAN] erreur: " + e.getMessage());
            }
        }
    }

    /* ================================================================
       OUTILS : USB dump, tests I/O, mini ping LCP
       ================================================================ */
    private void dumpUsb() {
        UsbManager mgr = (UsbManager)getSystemService(Context.USB_SERVICE);
        java.util.Map<String, UsbDevice> devs = mgr.getDeviceList();
        appendAndBuffer("[USB] --- Topologie USB (Android) ---");
        if (devs.isEmpty()) appendAndBuffer("[USB] Aucun device USB détecté.");
        else {
            for (UsbDevice d : devs.values()) {
                boolean perm = mgr.hasPermission(d);
                appendAndBuffer(String.format(
                        "[USB] Device name=%s vid=0x%04X pid=0x%04X hasPerm=%s",
                        d.getDeviceName(), d.getVendorId(), d.getProductId(), perm));
            }
        }
        try {
            List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(mgr);
            appendAndBuffer("[USB] Drivers trouvés: " + drivers.size());
            for (UsbSerialDriver drv : drivers) {
                UsbDevice dev = drv.getDevice();
                appendAndBuffer(String.format(
                        "[USB] Driver=%s dev=%s vid=0x%04X pid=0x%04X ports=%d",
                        drv.getClass().getSimpleName(),
                        dev.getDeviceName(), dev.getVendorId(), dev.getProductId(),
                        drv.getPorts().size()));
            }
        } catch (Exception e) {
            appendAndBuffer("[USB] Erreur listing drivers: " + e.getMessage());
        }
    }

    private void testIoSuite() {
        if (serialPort == null) { appendAndBuffer("[I/O] Port non ouvert."); return; }
        new Thread(() -> {
            try {
                serialPort.purgeHwBuffers(true, true);
                serialPort.write(new byte[]{ (byte)0xAA }, 200);
                appendAndBuffer("[I/O] Write 0xAA -> requested=1 byte");

                byte[] r = new byte[64];
                int n = serialPort.read(r, 150);
                if (n > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (int i=0;i<n;i++) sb.append(String.format("%02X ", r[i]));
                    appendAndBuffer("[I/O] Loopback RX: " + sb);
                } else {
                    appendAndBuffer("[I/O] Loopback RX: aucun octet (si court-circuit TX-RX absent, c’est normal)");
                }

                try { serialPort.setBreak(true); Thread.sleep(50); serialPort.setBreak(false); appendAndBuffer("[I/O] BREAK toggled OK"); }
                catch (Exception e) { appendAndBuffer("[I/O] BREAK non supporté: " + e.getMessage()); }

                serialPort.purgeHwBuffers(true, true);
                serialPort.write(new byte[]{ 0x00 }, 200);
                appendAndBuffer("[I/O] Write 0x00 -> requested=1 byte");
                n = serialPort.read(r, 150);
                appendAndBuffer("[I/O] Read after 0x00 -> bytesRead=" + n);

            } catch (Exception e) {
                appendAndBuffer("[I/O] ERREUR tests: " + e.getMessage());
            }
        }).start();
    }

    private void testMiniPingLcp_locked() {
        if (serialPort == null) { appendAndBuffer("[LCP] Port non ouvert."); return; }
        synchronized (lcpLock) {
            try {
                try { serialPort.purgeHwBuffers(true, true); } catch(Exception ignored){}
                int to = 0xFA, from = 0xFF;
                LcpLink link = new LcpLink(serialPort, to, from, true);
                LcpOps ops = new LcpOps(link);
                int[] dsdc = ops.opDeliveryStatus(3500, 250);
                Thread.sleep(120);
                appendAndBuffer("[LCP] MiniPing OK, DS=0x" + String.format("%04X", dsdc[0]) +
                        " DC=0x" + String.format("%04X", dsdc[1]) + " " + dsBits(dsdc[0]) + " " + dcBits(dsdc[1]));
                try { Thread.sleep(300); } catch (InterruptedException ignored) {}
            } catch (Exception e) {
                appendAndBuffer("[LCP] MiniPing ERREUR: " + e.getMessage());
            }
        }
    }

    /* ================================================================
       CONSOLE : Send HEX
       ================================================================ */
    private void promptAndSendHex() {
        if (serialPort == null) { append("RAW: port non prêt — clique 'Connexion USB'.\n"); return; }

        final EditText edt = new EditText(this);
        edt.setHint("ex.: 28 (GET_DEL_STATUS), 23 (GET_MACHINE), 20 00 (GET_FIELD#0)");
        edt.setSingleLine(false);

        final EditText edtTimeout = new EditText(this);
        edtTimeout.setHint("timeout ms (ex.: 2500)");
        edtTimeout.setText("2500");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(8 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);
        layout.addView(edt);
        layout.addView(edtTimeout);

        new AlertDialog.Builder(this)
                .setTitle("Envoyer payload LCP (hex)")
                .setView(layout)
                .setPositiveButton("Envoyer", (d, w) -> {
                    try {
                        String hex = edt.getText().toString();
                        int toMs = Integer.parseInt(edtTimeout.getText().toString().trim());
                        byte[] payload = parseHexBytes(hex);
                        runLcpTask(() -> sendRawPayload_locked(payload, Math.max(200, toMs)));
                    } catch (Exception e) {
                        appendAndBuffer("[RAW] invalide: " + e.getMessage());
                    }
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void sendRawPayload_locked(byte[] payload, int timeoutMs) {
        if (serialPort == null) { append("Port non prêt — clique d’abord 'Connexion USB'.\n"); return; }
        synchronized (lcpLock) {
            try {
                try { serialPort.purgeHwBuffers(true, true); } catch(Exception ignored){}
                int to=0xFA, from=0xFF;
                LcpLink link = new LcpLink(serialPort, to, from, true);
                LcpLink.setLogger(this::appendAndBuffer);
                LcpLink.DUMP_TX = true; LcpLink.DUMP_RX = true;

                appendAndBuffer(String.format("[RAW] to=0x%02X from=0x%02X payload=%s",
                        to, from, bytesToHex(payload)));

                byte[] rsp = link.sendRecv(payload, timeoutMs);
                Thread.sleep(120);
                appendAndBuffer("[RAW] OK, RX size=" + (rsp != null ? rsp.length : -1));

            } catch (Exception e) {
                appendAndBuffer("[RAW] ERREUR: " + e.getMessage());
            }
        }
    }

    /* ================================================================
       UTIL
       ================================================================ */
    // Décodage lisible des bits DC (Delivery Code)
    private String dcBits(int dc) {
        boolean ticket = (dc & 0x0001) != 0;   // TICKET_PENDING
        boolean flow   = (dc & 0x0004) != 0;   // FLOW_ACTIVE
        boolean deliv  = (dc & 0x0008) != 0;   // DELIVERY_ACTIVE
        boolean begin  = (dc & 0x0400) != 0;   // BEGIN_DELIVERY (si mappé)
        return String.format("[ticket=%s flow=%s delivery=%s beginDelivery=%s]",
                ticket, flow, deliv, begin);
    }

    // Décodage lisible des bits DS (Delivery Status)
    private String dsBits(int ds) {
        boolean begin = (ds & 0x0400) != 0;  // BEGIN_DELIVERY
        return String.format("[beginDelivery=%s]", begin);
    }

    private int parseHex(String s){
        try { return Integer.decode(s); }
        catch(Exception e){ return 0; }
    }

    private int safeParseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch(Exception e){ return def; }
    }

    private double safeParseDouble(String s, double def) {
        try { return Double.parseDouble(s); } catch(Exception e){ return def; }
    }

    private static String bytesToHex(byte[] b) {
        if (b == null) return "(null)";
        StringBuilder sb = new StringBuilder(b.length * 3);
        for (byte x : b) sb.append(String.format("%02X ", x));
        return sb.toString().trim();
    }

    private byte[] parseHexBytes(String s) throws IllegalArgumentException {
        if (s == null) throw new IllegalArgumentException("vide");
        String cleaned = s.replaceAll("(?i)0x", "").replaceAll("[^0-9A-Fa-f]", "");
        if (cleaned.length() == 0) throw new IllegalArgumentException("aucun hex");
        if ((cleaned.length() % 2) != 0) cleaned = "0" + cleaned;
        int len = cleaned.length() / 2;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) out[i] = (byte) Integer.parseInt(cleaned.substring(2*i, 2*i+2), 16);
        return out;
    }

    private void append(String s){ runOnUiThread(() -> log.append(s)); }

    private void appendAndBuffer(String s){
        if (s == null) return;
        logBuf.append(s);
        if (!s.endsWith("\n")) logBuf.append("\n");
        append(s.endsWith("\n") ? s : s + "\n");
    }
}
