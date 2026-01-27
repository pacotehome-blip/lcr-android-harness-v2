
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

import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private static final String ACTION_USB_PERMISSION = "com.pa.lcrdemo.USB_PERMISSION";

    private TextView log;
    private EditText edtTo, edtFrom, edtProduct, edtPreset;

    private UsbSerialPort serialPort;
    private UsbDevice currentDevice;

    // Buffer log (pour bouton Copier)
    private final StringBuilder logBuf = new StringBuilder(4096);

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

        // Receivers
        registerReceiver(usbPermissionReceiver, new IntentFilter(ACTION_USB_PERMISSION));
        IntentFilter f = new IntentFilter();
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbAttachDetach, f);

        // === I/O TX/RX Logging ===
        CheckBox switchIoLog = findViewById(R.id.switchIoLog);
        LcpLink.setLogger(this::appendAndBuffer);
        switchIoLog.setOnCheckedChangeListener((btn, checked) -> {
            LcpLink.DUMP_TX = checked;
            LcpLink.DUMP_RX = checked;
            append("I/O log " + (checked ? "activé" : "désactivé") + "\n");
        });

        // === Bouton Copier ===
        findViewById(R.id.btnCopyLog).setOnClickListener(v -> {
            ClipboardManager cb = (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
            if (cb != null) {
                cb.setPrimaryClip(ClipData.newPlainText("lcr_log", logBuf.toString()));
                append("Log copié dans le presse-papiers\n");
            }
        });

        // === Bouton Clear ===
        findViewById(R.id.btnClearLog).setOnClickListener(v -> {
            logBuf.setLength(0);
            runOnUiThread(() -> log.setText(""));
        });

        // === Bouton Connexion ===
        findViewById(R.id.btnConnect).setOnClickListener(v -> requestAndOpenFirstPort());

        // === Bouton DIAG (lecture RX brute 0,5 s) ===
        findViewById(R.id.btnDiag).setOnClickListener(v -> diagRx());

        // === Bouton START FLOW (prestart/start/live/finish) ===
        findViewById(R.id.btnStart).setOnClickListener(v -> startFlow());

        // === NOUVEAUX BOUTONS DIAGNOSTIQUE ===
        Button btnScan    = findViewById(R.id.btnScan);
        Button btnSendHex = findViewById(R.id.btnSendHex);
        Button btnTestUsb = findViewById(R.id.btnTestUsb);

        if (btnScan != null)    btnScan.setOnClickListener(v -> scanNodes());
        if (btnSendHex != null) btnSendHex.setOnClickListener(v -> promptAndSendHex());
        if (btnTestUsb != null) btnTestUsb.setOnClickListener(v -> {
            appendAndBuffer("=== TEST PORT USB ===");
            dumpUsb();
            if (openOrVerifyPort()) {
                testIoSuite();
                testMiniPingLcp(); // ping 0x28
            }
        });

        append("Prêt. Branchez le LCR puis cliquez 'Connexion USB'.\n");
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(usbPermissionReceiver); } catch(Exception ignored){}
        try { unregisterReceiver(usbAttachDetach); } catch(Exception ignored){}
        try { if(serialPort!=null) serialPort.close(); } catch(Exception ignored){}
    }

    /* ================================================================
       USB : Demander permission
       ================================================================ */
    private void requestAndOpenFirstPort() {
        UsbManager mgr = (UsbManager)getSystemService(Context.USB_SERVICE);
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(mgr);
        if (drivers.isEmpty()) { append("Aucun convertisseur USB‑Série détecté\n"); return; }

        UsbDevice dev = drivers.get(0).getDevice();
        currentDevice = dev;

        if (!mgr.hasPermission(dev)) {
            append("Demande de permission USB…\n");
            PendingIntent pi = PendingIntent.getBroadcast(
                    this, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
            mgr.requestPermission(dev, pi);
            return;
        }
        connectPort(dev);
    }

    /* ================================================================
       USB : Connexion au port série
       ================================================================ */
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

            // Pulse RTS/DTR (certains adaptateurs "réveillent" ainsi la ligne RS‑232)
            try { serialPort.setRTS(false); } catch(Exception ignored){}
            try { serialPort.setDTR(false); } catch(Exception ignored){}
            Thread.sleep(100);
            try { serialPort.setRTS(true); } catch(Exception ignored){}
            try { serialPort.setDTR(true); } catch(Exception ignored){}

            serialPort.purgeHwBuffers(true, true);

            append("Port ouvert 19200 8N1 (DTR/RTS pulsed, purge OK)\n");
        } catch(Exception e) {
            append("ERREUR ouverture: " + e.getMessage() + "\n");
        }
    }

    /* ================================================================
       DIAG RX
       ================================================================ */
    private void diagRx() {
        if (serialPort == null) {
            append("Diag: port non ouvert — clique 'Connexion USB'.\n");
            return;
        }
        append("Diag: écoute RX 0,5 s...\n");
        new Thread(() -> {
            try {
                byte[] buf = new byte[64];
                long t0 = System.currentTimeMillis();
                while (System.currentTimeMillis() - t0 < 500) {
                    int n = serialPort.read(buf, 50);
                    if (n > 0) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < n; i++) sb.append(String.format("%02X ", buf[i]));
                        appendAndBuffer("RX: " + sb + "\n");
                    }
                }
            } catch(Exception e) {
                append("Diag RX: " + e.getMessage() + "\n");
            }
        }).start();
    }

    /* ================================================================
       START FLOW
       ================================================================ */
    private void startFlow() {
        try {
            if (serialPort == null) {
                append("Port non prêt — clique 'Connexion USB'.\n");
                return;
            }

            LcrSimpleDeliverV2.Params p = new LcrSimpleDeliverV2.Params();
            p.port    = serialPort;
            p.toAddr  = parseHex(edtTo.getText().toString().trim());
            p.fromAddr= parseHex(edtFrom.getText().toString().trim());
            p.product = Integer.parseInt(edtProduct.getText().toString().trim());
            try { p.preset = Double.parseDouble(edtPreset.getText().toString().trim()); }
            catch(Exception e){ p.preset = 0.0; }
            p.verbose = true; p.startAcceptFlow = true; p.ticketPost = "if-pending";

            append("Go → unlock/prestart/start...\n");

            new Thread(() -> {
                try {
                    LcrSimpleDeliverV2 lcr = new LcrSimpleDeliverV2(p);
                    lcr.unlock();
                    lcr.prestart();
                    lcr.start();
                    Map<String,Object> live = lcr.liveLoop();
                    Map<String,Object> fin  = lcr.finish(live, null);
                    append("FINISH: " + com.pa.lcr.util.SimpleJson.stringify(fin) + "\n");
                } catch (Exception ex) {
                    append("ERREUR (thread): "+ ex.getMessage()+"\n");
                }
            }).start();

        } catch(Exception e){
            append("ERREUR (startFlow): " + e.getMessage() + "\n");
        }
    }

    /* ================================================================
       CONSOLE LCP — Scan, Envoi HEX, Tests port
       ================================================================ */

    // Scanner les nœuds 1..16 via 0x28 (GET_DEL_STATUS)
    private void scanNodes() {
        if (serialPort == null) {
            append("Scan: port non prêt — clique 'Connexion USB'.\n");
            return;
        }
        new Thread(() -> {
            try {
                int from = parseHex(edtFrom.getText().toString().trim());
                if (from == 0) from = 0xF8;
                for (int node = 1; node <= 16; node++) {
                    try {
                        LcpLink link = new LcpLink(serialPort, node, from, true);
                        LcpLink.setLogger(this::appendAndBuffer);
                        byte[] rsp = link.sendRecv(new byte[]{ (byte)0x28 }, 800);
                        appendAndBuffer(String.format("[SCAN] Node=0x%02X → OK (RX %d)", node,
                                (rsp != null ? rsp.length : -1)));
                        final int n = node;
                        runOnUiThread(() -> edtTo.setText(String.format("0x%02X", n)));
                        break;
                    } catch (Exception ignore) {
                        appendAndBuffer(String.format("[SCAN] Node=0x%02X → no reply", node));
                    }
                }
            } catch (Exception e) {
                appendAndBuffer("[SCAN] erreur: " + e.getMessage());
            }
        }).start();
    }

    // Saisie d’un payload HEX et envoi brut LCP
    private void promptAndSendHex() {
        if (serialPort == null) {
            append("RAW: port non prêt — clique 'Connexion USB'.\n");
            return;
        }
        final EditText edt = new EditText(this);
        edt.setHint("ex.: 28  (GET_DEL_STATUS)  ou  23  (GET_MACHINE)  ou  20 00 (GET_FIELD #0)");
        edt.setSingleLine(false);

        final EditText edtTimeout = new EditText(this);
        edtTimeout.setHint("timeout ms (ex.: 1500)");
        edtTimeout.setText("1500");

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
                        int toMs =  Integer.parseInt(edtTimeout.getText().toString().trim());
                        byte[] payload = parseHexBytes(hex);
                        sendRawPayload(payload, Math.max(200, toMs));
                    } catch (Exception e) {
                        appendAndBuffer("[RAW] invalide: " + e.getMessage());
                    }
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void sendRawPayload(byte[] payload, int timeoutMs) {
        if (serialPort == null) {
            append("Port non prêt — clique d’abord 'Connexion USB'.\n");
            return;
        }
        try {
            int to   = parseHex(edtTo.getText().toString().trim());
            int from = parseHex(edtFrom.getText().toString().trim());
            if (from == 0) from = 0xF8; // par défaut hôte

            LcpLink link = new LcpLink(serialPort, to, from, true);
            LcpLink.setLogger(this::appendAndBuffer);
            LcpLink.DUMP_TX = true;
            LcpLink.DUMP_RX = true;

            appendAndBuffer(String.format("[RAW] to=0x%02X from=0x%02X payload=%s",
                    to, from, bytesToHex(payload)));

            byte[] rsp = link.sendRecv(payload, timeoutMs);
            appendAndBuffer("[RAW] OK, RX size=" + (rsp != null ? rsp.length : -1));

        } catch (Exception e) {
            appendAndBuffer("[RAW] ERREUR: " + e.getMessage());
        }
    }

    // Dump USB : devices + drivers + nombre de ports
    private void dumpUsb() {
        UsbManager mgr = (UsbManager)getSystemService(Context.USB_SERVICE);
        java.util.Map<String, UsbDevice> devs = mgr.getDeviceList();
        appendAndBuffer("[USB] --- Topologie USB (Android) ---");
        if (devs.isEmpty()) {
            appendAndBuffer("[USB] Aucun device USB détecté.");
        } else {
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

    // Ouvre le port si nécessaire, configure 19200 8N1 + pulse RTS/DTR
    private boolean openOrVerifyPort() {
        if (serialPort != null) {
            appendAndBuffer("[PORT] Port déjà ouvert (on réutilise).");
            return true;
        }
        try {
            UsbManager mgr = (UsbManager)getSystemService(Context.USB_SERVICE);
            List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(mgr);
            if (drivers.isEmpty()) {
                appendAndBuffer("[PORT] Aucun driver trouvé (USB RS-232 absent ?)");
                return false;
            }
            UsbSerialDriver driver = drivers.get(0);
            UsbDevice dev = driver.getDevice();
            if (!mgr.hasPermission(dev)) {
                appendAndBuffer("[PORT] Permission USB absente. Clique Connexion USB d’abord.");
                return false;
            }
            UsbDeviceConnection conn = mgr.openDevice(dev);
            if (conn == null) {
                appendAndBuffer("[PORT] openDevice=null (permission ?)");
                return false;
            }
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
            appendAndBuffer("[PORT] Ouvert 19200 8N1 (DTR/RTS pulsed, purge OK).");
            return true;
        } catch (Exception e) {
            appendAndBuffer("[PORT] ERREUR open: " + e.getMessage());
            return false;
        }
    }

    // Suite de tests I/O : loopback (si TX<->RX), break, write/read simple
    private void testIoSuite() {
        if (serialPort == null) {
            appendAndBuffer("[I/O] Port non ouvert.");
            return;
        }
        new Thread(() -> {
            try {
                serialPort.purgeHwBuffers(true, true);
                int written = serialPort.write(new byte[]{ (byte)0xAA }, 200);
                appendAndBuffer("[I/O] Write 0xAA -> bytesWritten=" + written);

                byte[] r = new byte[64];
                int n = serialPort.read(r, 150);
                if (n > 0) {
                    StringBuilder sb = new StringBuilder();
                    for (int i=0;i<n;i++) sb.append(String.format("%02X ", r[i]));
                    appendAndBuffer("[I/O] Loopback RX: " + sb);
                } else {
                    appendAndBuffer("[I/O] Loopback RX: aucun octet (si court-circuit TX-RX absent, c’est normal)");
                }

                try {
                    serialPort.setBreak(true);
                    Thread.sleep(50);
                    serialPort.setBreak(false);
                    appendAndBuffer("[I/O] BREAK toggled OK");
                } catch (Exception e) {
                    appendAndBuffer("[I/O] BREAK non supporté: " + e.getMessage());
                }

                serialPort.purgeHwBuffers(true, true);
                written = serialPort.write(new byte[]{ 0x00 }, 200);
                appendAndBuffer("[I/O] Write 0x00 -> bytesWritten=" + written);
                n = serialPort.read(r, 150);
                appendAndBuffer("[I/O] Read after 0x00 -> bytesRead=" + n);

            } catch (Exception e) {
                appendAndBuffer("[I/O] ERREUR tests: " + e.getMessage());
            }
        }).start();
    }

    // Mini ping LCP (0x28) avec DUMP TX/RX
    private void testMiniPingLcp() {
        if (serialPort == null) {
            appendAndBuffer("[LCP] Port non ouvert.");
            return;
        }
        new Thread(() -> {
            try {
                int to   = parseHex(edtTo.getText().toString().trim());
                int from = parseHex(edtFrom.getText().toString().trim());
                if (from == 0) from = 0xF8;
                LcpLink link = new LcpLink(serialPort, to, from, true);
                LcpLink.setLogger(this::appendAndBuffer);
                LcpLink.DUMP_TX = true;
                LcpLink.DUMP_RX = true;
                appendAndBuffer(String.format("[LCP] MiniPing 0x28 -> to=0x%02X from=0x%02X", to, from));
                byte[] rsp = link.sendRecv(new byte[]{ (byte)0x28 }, 1200);
                appendAndBuffer("[LCP] MiniPing OK, RX len=" + (rsp != null ? rsp.length : -1));
            } catch (Exception e) {
                appendAndBuffer("[LCP] MiniPing ERREUR: " + e.getMessage());
            }
        }).start();
    }

    /* ================================================================
       UTIL
       ================================================================ */
    private int parseHex(String s){
        try { return Integer.decode(s); }
        catch(Exception e){ return 0; }
    }

    // Convertit un tableau d’octets en chaîne hexadécimale (ex: "7E 7E 01 F8 ...")
    private static String bytesToHex(byte[] b) {
        if (b == null) return "(null)";
        StringBuilder sb = new StringBuilder(b.length * 3);
        for (byte x : b) sb.append(String.format("%02X ", x));
        return sb.toString().trim();
    }

    private byte[] parseHexBytes(String s) throws IllegalArgumentException {
        if (s == null) throw new IllegalArgumentException("vide");
        // Enlever 0x, espaces, virgules, retours lignes, etc.
        String cleaned = s.replaceAll("(?i)0x", "").replaceAll("[^0-9A-Fa-f]", "");
        if (cleaned.length() == 0) throw new IllegalArgumentException("aucun hex");
        if ((cleaned.length() % 2) != 0) cleaned = "0" + cleaned; // 5 -> 05
        int len = cleaned.length() / 2;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++)
            out[i] = (byte) Integer.parseInt(cleaned.substring(2*i, 2*i+2), 16);
        return out;
    }

    private void append(String s){
        runOnUiThread(() -> log.append(s));
    }

    private void appendAndBuffer(String s){
        if (s == null) return;
        logBuf.append(s);
        if (!s.endsWith("\n")) logBuf.append("\n");
        append(s.endsWith("\n") ? s : s + "\n");
    }
}
