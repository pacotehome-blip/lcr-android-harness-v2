
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String ACTION_USB_PERMISSION = "com.pa.lcrdemo.USB_PERMISSION";

    private TextView log;
    private EditText edtTo, edtFrom, edtProduct, edtPreset;

    private UsbSerialPort serialPort;

    private final StringBuilder logBuf = new StringBuilder(4096);

    // Sérialisation des accès LCP
    private final Object lcpLock = new Object();
    private final ExecutorService lcpExec = Executors.newSingleThreadExecutor();

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

        // Adresses par défaut stables (FA/FF)
        ensureDefaultAddresses();

        // Receivers
        registerReceiver(usbPermissionReceiver, new IntentFilter(ACTION_USB_PERMISSION));
        IntentFilter f = new IntentFilter();
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbAttachDetach, f);

        // I/O log (TX/RX)
        CheckBox switchIoLog = findViewById(R.id.switchIoLog);
        LcpLink.setLogger(this::appendAndBuffer);
        if (switchIoLog != null) {
            switchIoLog.setOnCheckedChangeListener((btn, checked) -> {
                LcpLink.DUMP_TX = checked;
                LcpLink.DUMP_RX = checked;
                append("I/O log " + (checked ? "activé" : "désactivé") + "\n");
            });
        }

        // Copier/Effacer log
        View vCopy = findViewById(R.id.btnCopyLog);
        if (vCopy != null) vCopy.setOnClickListener(v -> {
            ClipboardManager cb = (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
            if (cb != null) {
                cb.setPrimaryClip(ClipData.newPlainText("lcr_log", logBuf.toString()));
                append("Log copié dans le presse-papiers\n");
            }
        });
        View vClear = findViewById(R.id.btnClearLog);
        if (vClear != null) vClear.setOnClickListener(v -> {
            logBuf.setLength(0);
            runOnUiThread(() -> log.setText(""));
        });

        // Connexion USB
        View vConn = findViewById(R.id.btnConnect);
        if (vConn != null) vConn.setOnClickListener(v -> requestAndOpenFirstPort());

        // DIAG : Ping 0x28 simple (profil stable)
        View vDiag = findViewById(R.id.btnDiag);
        if (vDiag != null) vDiag.setOnClickListener(v -> runLcpTask(this::diagPing28_locked));

        // START flow (alias Start)
        View vStart = findViewById(R.id.btnStart);
        if (vStart != null) vStart.setOnClickListener(v -> runLcpTask(this::startFlow_locked));

        // A / B / C
        View vA = findViewById(R.id.btnA);
        if (vA != null) vA.setOnClickListener(v -> runLcpTask(this::macroResetEndClear_locked));

        View vB = findViewById(R.id.btnB);
        if (vB != null) vB.setOnClickListener(v -> runLcpTask(this::macroPing28_locked));

        View vC = findViewById(R.id.btnC);
        if (vC != null) vC.setOnClickListener(v -> runLcpTask(this::macroStartDelivery_locked));

        // Console RAW
        View vRaw = findViewById(R.id.btnSendHex);
        if (vRaw != null) vRaw.setOnClickListener(v -> promptAndSendHex());

        append("Prêt. Branchez le LCR puis cliquez 'Connexion USB'.\n");
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(usbPermissionReceiver); } catch(Exception ignored){}
        try { unregisterReceiver(usbAttachDetach); } catch(Exception ignored){}
        try { if (serialPort!=null) serialPort.close(); } catch(Exception ignored){}
        lcpExec.shutdownNow();
    }

    /* ================================================================
       OUTILS UI
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
            int[] ids = new int[]{ R.id.btnA, R.id.btnB, R.id.btnC, R.id.btnScan, R.id.btnSendHex, R.id.btnTestUsb, R.id.btnConnect, R.id.btnDiag, R.id.btnStart };
            for (int id : ids) {
                View v = findViewById(id);
                if (v != null) v.setEnabled(enabled);
            }
        });
    }

    private void ensureDefaultAddresses() {
        runOnUiThread(() -> {
            if (edtTo   != null && !"0xFA".equalsIgnoreCase(safeStr(edtTo.getText())))   edtTo.setText("0xFA");
            if (edtFrom != null && !"0xFF".equalsIgnoreCase(safeStr(edtFrom.getText()))) edtFrom.setText("0xFF");
        });
    }

    private String safeStr(CharSequence cs){ return cs == null ? "" : cs.toString().trim(); }

    /* ================================================================
       USB OPEN
       ================================================================ */
    private void requestAndOpenFirstPort() {
        UsbManager mgr = (UsbManager)getSystemService(Context.USB_SERVICE);
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(mgr);
        if (drivers.isEmpty()) { append("Aucun convertisseur USB‑Série détecté\n"); return; }

        UsbDevice dev = drivers.get(0).getDevice();

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

            // Pulse DTR/RTS à l'ouverture
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
        requestAndOpenFirstPort();
        return serialPort != null;
    }

    /* ================================================================
       DIAG / PING (profil stable)
       ================================================================ */
    private void diagPing28_locked() {
        if (!openOrVerifyPort()) return;
        synchronized (lcpLock) {
            try {
                serialPort.purgeHwBuffers(true, true);
                int to = 0xFA, from = 0xFF;
                LcpLink link = new LcpLink(serialPort, to, from, true);
                LcpOps ops = new LcpOps(link);
                int[] dsdc = ops.opDeliveryStatus(3000, 200);
                Thread.sleep(120);
                appendAndBuffer(String.format("[DIAG] DS=0x%04X DC=0x%04X %s %s",
                        dsdc[0], dsdc[1], dsBits(dsdc[0]), dcBits(dsdc[1])));
            } catch (Exception e) {
                appendAndBuffer("[DIAG] ERREUR: " + e.getMessage());
            }
        }
    }

    /* ================================================================
       MACRO A — Reset (END + CLEAR + 28)
       ================================================================ */
    private void macroResetEndClear_locked() {
        if (!openOrVerifyPort()) return;
        synchronized (lcpLock) {
            try {
                serialPort.purgeHwBuffers(true, true);

                int to=0xFA, from=0xFF;
                LcpLink link = new LcpLink(serialPort, to, from, true);
                LcpOps  ops  = new LcpOps(link);
                LcpLink.setLogger(this::appendAndBuffer);
                LcpLink.DUMP_TX = true; LcpLink.DUMP_RX = true;

                appendAndBuffer("[A] ISSUE #2 (END/RESET)");
                ops.opIssueCommand(0x02, 3000, 200);
                Thread.sleep(400);

                appendAndBuffer("[A] ISSUE #6 (CLEAR TICKET)");
                ops.opIssueCommand(0x06, 3000, 200);
                Thread.sleep(200);

                // Poll 28 jusqu'à ticket=false, max ~8s
                long t0 = System.currentTimeMillis();
                int[] dsdc;
                while (true) {
                    dsdc = ops.opDeliveryStatus(3000, 200);
                    appendAndBuffer(String.format("[A] POLL DS=0x%04X DC=0x%04X %s %s",
                            dsdc[0], dsdc[1], dsBits(dsdc[0]), dcBits(dsdc[1])));
                    if ((dsdc[1] & 0x0001) == 0) break;
                    if (System.currentTimeMillis() - t0 > 8000) break;
                    Thread.sleep(300);
                }
                appendAndBuffer(String.format("[A] FINAL DS=0x%04X DC=0x%04X %s %s",
                        dsdc[0], dsdc[1], dsBits(dsdc[0]), dcBits(dsdc[1])));

            } catch (Exception e) {
                appendAndBuffer("[A] ERREUR: " + e.getMessage());
            }
        }
    }

    /* ================================================================
       MACRO B — Ping (28)
       ================================================================ */
    private void macroPing28_locked() {
        if (!openOrVerifyPort()) return;
        synchronized (lcpLock) {
            try {
                serialPort.purgeHwBuffers(true, true);

                int to=0xFA, from=0xFF;
                LcpLink link = new LcpLink(serialPort, to, from, true);
                LcpOps  ops  = new LcpOps(link);
                LcpLink.setLogger(this::appendAndBuffer);
                LcpLink.DUMP_TX = true; LcpLink.DUMP_RX = true;

                appendAndBuffer("[B] GET_DEL_STATUS (0x28)");
                int[] dsdc = ops.opDeliveryStatus(3000, 200);
                Thread.sleep(120);
                appendAndBuffer(String.format("[B] DS=0x%04X DC=0x%04X %s %s",
                        dsdc[0], dsdc[1], dsBits(dsdc[0]), dcBits(dsdc[1])));

            } catch (Exception e) {
                appendAndBuffer("[B] ERREUR: " + e.getMessage());
            }
        }
    }

    /* ================================================================
       MACRO C — Start Delivery (aligné Python)
       ================================================================ */
    private void macroStartDelivery_locked() {
        if (!openOrVerifyPort()) return;
        synchronized (lcpLock) {
            try {
                serialPort.purgeHwBuffers(true, true);

                int to=0xFA, from=0xFF;
                LcrSimpleDeliverV2.Params p = new LcrSimpleDeliverV2.Params();
                p.port     = serialPort;
                p.toAddr   = to;
                p.fromAddr = from;
                p.product  = parseIntSafe(safeStr(edtProduct.getText()), 1);
                p.preset   = parseDoubleSafe(safeStr(edtPreset.getText()), 50.0);
                p.verbose  = true;
                p.startAcceptFlow = true;
                p.ticketPost      = "if-pending";
                // NOTE: pas de p.startCmd / p.startTimeoutSec ici (non présents dans ta lib)
                //       start() applique son comportement interne (RUN + attente DC=0x012D)

                appendAndBuffer("[C] unlock/prestart/start...");
                LcrSimpleDeliverV2 lcr = new LcrSimpleDeliverV2(p);
                lcr.unlock();
                lcr.prestart();   // sélection produit, preset net auto si permis, END/CLEAR si nécessaire
                lcr.start();      // RUN + attente DC=0x012D (queued géré)
                appendAndBuffer("[C] start() OK — surveillez LIVE dans l’app.");

            } catch (Exception e) {
                appendAndBuffer("[C] ERREUR: " + e.getMessage());
            }
        }
    }

    private void startFlow_locked() {
        // alias bouton Start existant vers la macro C
        macroStartDelivery_locked();
    }

    /* ================================================================
       CONSOLE RAW
       ================================================================ */
    private void promptAndSendHex() {
        if (!openOrVerifyPort()) { append("RAW: port non prêt — clique 'Connexion USB'.\n"); return; }

        final EditText edt = new EditText(this);
        edt.setHint("ex.: 28 (GET_DEL_STATUS), 23 (GET_MACHINE), 20 00 (GET_FIELD#0)");
        edt.setSingleLine(false);

        final EditText edtTimeout = new EditText(this);
        edtTimeout.setHint("timeout ms (ex.: 3000)");
        edtTimeout.setText("3000");

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
        if (!openOrVerifyPort()) { append("Port non prêt — clique d’abord 'Connexion USB'.\n"); return; }
        synchronized (lcpLock) {
            try {
                serialPort.purgeHwBuffers(true, true);
                int to=0xFA, from=0xFF;
                LcpLink link = new LcpLink(serialPort, to, from, true);
                LcpLink.setLogger(this::appendAndBuffer);
                LcpLink.DUMP_TX = true; LcpLink.DUMP_RX = true;

                appendAndBuffer(String.format("[RAW] to=0x%02X from=0x%02X payload=%s",
                        to, from, bytesToHex(payload)));

                byte[] rsp = link.sendRecv(payload, timeoutMs);
                appendAndBuffer("[RAW] OK, RX size=" + (rsp != null ? rsp.length : -1));

            } catch (Exception e) {
                appendAndBuffer("[RAW] ERREUR: " + e.getMessage());
            }
        }
    }

    /* ================================================================
       UTIL
       ================================================================ */
    private int parseIntSafe(String s, int def){ try { return Integer.parseInt(s); } catch(Exception e){ return def; } }
    private double parseDoubleSafe(String s, double def){ try { return Double.parseDouble(s); } catch(Exception e){ return def; } }

    private String dcBits(int dc) {
        boolean ticket = (dc & 0x0001) != 0;   // TICKET_PENDING
        boolean flow   = (dc & 0x0004) != 0;   // FLOW_ACTIVE
        boolean deliv  = (dc & 0x0008) != 0;   // DELIVERY_ACTIVE
        boolean begin  = (dc & 0x0400) != 0;   // BEGIN_DELIVERY (si mappé)
        return String.format("[ticket=%s flow=%s delivery=%s beginDelivery=%s]",
                ticket, flow, deliv, begin);
    }

    private String dsBits(int ds) {
        boolean begin = (ds & 0x0400) != 0;  // BEGIN_DELIVERY dans DS
        return String.format("[beginDelivery=%s]", begin);
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
