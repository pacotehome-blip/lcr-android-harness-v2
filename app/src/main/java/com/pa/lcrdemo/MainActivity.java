
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

        // Par défaut : imposer To=0xFA, From=0xFF (clé du succès chez toi)
        ensureDefaultAddresses();

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
        findViewById(R.id.btnConnect).setOnClickListener(v -> {
            ensureDefaultAddresses();
            requestAndOpenFirstPort();
        });

        // === Bouton DIAG (SEQ COMPLETE : dump USB + open + 0x28) ===
        findViewById(R.id.btnDiag).setOnClickListener(v -> {
            ensureDefaultAddresses();
            diagConnectAndStatus28();
        });

        // === Bouton START FLOW (prestart/start/live/finish) ===
        findViewById(R.id.btnStart).setOnClickListener(v -> {
            ensureDefaultAddresses();
            startFlow();
        });

        // === Console : Scan / SendHex / TestUSB ===
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

        // === A/B/C : on pose AUSSI des listeners, en plus de android:onClick dans le XML ===
        Button btnA = findViewById(R.id.btnA);
        Button btnB = findViewById(R.id.btnB);
        Button btnC = findViewById(R.id.btnC);

        if (btnA != null) btnA.setOnClickListener(this::onClickA);
        if (btnB != null) btnB.setOnClickListener(this::onClickB);
        if (btnC != null) btnC.setOnClickListener(this::onClickC);

        // Log présence des vues (diagnostic)
        appendAndBuffer(String.format(
                "[UI] A=%s B=%s C=%s Scan=%s SendHex=%s TestUsb=%s",
                (findViewById(R.id.btnA)!=null), (findViewById(R.id.btnB)!=null),
                (findViewById(R.id.btnC)!=null), (findViewById(R.id.btnScan)!=null),
                (findViewById(R.id.btnSendHex)!=null), (findViewById(R.id.btnTestUsb)!=null)
        ));

        append("Prêt. Branchez le LCR puis cliquez 'Connexion USB'.\n");
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(usbPermissionReceiver); } catch(Exception ignored){}
        try { unregisterReceiver(usbAttachDetach); } catch(Exception ignored){}
        try { if(serialPort!=null) serialPort.close(); } catch(Exception ignored){}
    }

    /* ================================================================
       HANDLERS XML (android:onClick) pour A / B / C
       ================================================================ */
    public void onClickA(View v) {
        ensureDefaultAddresses();
        macroResetEndClear();
    }

    public void onClickB(View v) {
        ensureDefaultAddresses();
        macroPing28GetMachine23();
    }

    public void onClickC(View v) {
        ensureDefaultAddresses();
        macroStartDelivery();
    }

    /* ================================================================
       FORCER To=0xFA / From=0xFF
       ================================================================ */
    private void ensureDefaultAddresses() {
        runOnUiThread(() -> {
            String to = edtTo.getText() != null ? edtTo.getText().toString().trim() : "";
            String from = edtFrom.getText() != null ? edtFrom.getText().toString().trim() : "";
            boolean changed = false;
            if (!"0xFA".equalsIgnoreCase(to)) {
                edtTo.setText("0xFA");
                changed = true;
            }
            if (!"0xFF".equalsIgnoreCase(from)) {
                edtFrom.setText("0xFF");
                changed = true;
            }
            if (changed) append("Forçage adresses: To=0xFA, From=0xFF\n");
        });
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

    /* ================================================================
       DIAG — Séquence complète : dump USB + open + 0x28 + décodage
       ================================================================ */
    private void diagConnectAndStatus28() {
        appendAndBuffer("=== DIAGNOSTIC COMPLET ===");
        dumpUsb();

        if (!openOrVerifyPort()) {
            appendAndBuffer("[DIAG] Ouverture échouée.");
            return;
        }

        new Thread(() -> {
            try {
                int to   = parseHex(edtTo.getText().toString().trim());
                int from = parseHex(edtFrom.getText().toString().trim());
                if (from == 0) from = 0xFF; // sécurité

                LcpLink link = new LcpLink(serialPort, to, from, true);
                LcpLink.setLogger(this::appendAndBuffer);
                LcpLink.DUMP_TX = true;
                LcpLink.DUMP_RX = true;

                appendAndBuffer(String.format("[DIAG] Ping 0x28 -> to=0x%02X from=0x%02X", to, from));
                byte[] frame = link.sendRecv(new byte[]{ (byte)0x28 }, 1500);
                byte[] p = LcpLink.extractPayload(frame);
                decode28("DIAG", p);

            } catch (Exception e) {
                appendAndBuffer("[DIAG] ERREUR: " + e.getMessage());
            }
        }).start();
    }

    /* ================================================================
       START FLOW (bouton Start existant) — renforcé (adresses forcées)
       ================================================================ */
    private void startFlow() {
        try {
            if (serialPort == null) {
                append("Port non prêt — clique 'Connexion USB'.\n");
                return;
            }

            // Forcer les adresses FA/FF
            edtTo.setText("0xFA");
            edtFrom.setText("0xFF");

            LcrSimpleDeliverV2.Params p = new LcrSimpleDeliverV2.Params();
            p.port    = serialPort;
            p.toAddr  = 0xFA;  // imposé
            p.fromAddr= 0xFF;  // imposé
            p.product = safeParseInt(edtProduct.getText()!=null?edtProduct.getText().toString().trim():"1", 1);
            p.preset  = safeParseDouble(edtPreset.getText()!=null?edtPreset.getText().toString().trim():"0", 0.0);
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
                    append("ERREUR (startFlow thread): "+ ex.getMessage()+"\n");
                }
            }).start();

        } catch(Exception e){
            append("ERREUR (startFlow): " + e.getMessage() + "\n");
        }
    }

    /* ================================================================
       MACROS A / B / C
       ================================================================ */

    // A — Reset : Issue #2 (End) -> petit délai -> Issue #6 (Clear ticket) -> 0x28 (statut)
    private void macroResetEndClear() {
        if (!openOrVerifyPort()) return;
        new Thread(() -> {
            try {
                int to=0xFA, from=0xFF;
                LcpLink link = new LcpLink(serialPort, to, from, true);
                LcpLink.setLogger(this::appendAndBuffer);
                LcpLink.DUMP_TX = true; LcpLink.DUMP_RX = true;

                appendAndBuffer("[A] ISSUE #2 (END/RESET)");
                link.sendRecv(new byte[]{ (byte)0x24, (byte)0x02 }, 1500);
                Thread.sleep(500);

                appendAndBuffer("[A] ISSUE #6 (CLEAR TICKET)");
                link.sendRecv(new byte[]{ (byte)0x24, (byte)0x06 }, 1500);

                appendAndBuffer("[A] GET_DEL_STATUS (0x28)");
                byte[] fr = link.sendRecv(new byte[]{ (byte)0x28 }, 1500);
                decode28("A", LcpLink.extractPayload(fr));

            } catch (Exception e) {
                appendAndBuffer("[A] ERREUR: " + e.getMessage());
            }
        }).start();
    }

    // B — 0x28 puis 0x23 avec décodage
    private void macroPing28GetMachine23() {
        if (!openOrVerifyPort()) return;
        new Thread(() -> {
            try {
                int to=0xFA, from=0xFF;
                LcpLink link = new LcpLink(serialPort, to, from, true);
                LcpLink.setLogger(this::appendAndBuffer);
                LcpLink.DUMP_TX = true; LcpLink.DUMP_RX = true;

                appendAndBuffer("[B] GET_DEL_STATUS (0x28)");
                byte[] fr1 = link.sendRecv(new byte[]{ (byte)0x28 }, 1500);
                decode28("B", LcpLink.extractPayload(fr1));

                appendAndBuffer("[B] GET_MACHINE (0x23)");
                byte[] fr2 = link.sendRecv(new byte[]{ (byte)0x23 }, 1500);
                decode23("B", LcpLink.extractPayload(fr2));

            } catch (Exception e) {
                appendAndBuffer("[B] ERREUR: " + e.getMessage());
            }
        }).start();
    }

    // C — Start Delivery "macro" rapide (unlock -> prestart -> start)
    private void macroStartDelivery() {
        if (!openOrVerifyPort()) return;
        new Thread(() -> {
            try {
                // Forcer adresses FA/FF
                runOnUiThread(() -> { edtTo.setText("0xFA"); edtFrom.setText("0xFF"); });

                LcrSimpleDeliverV2.Params p = new LcrSimpleDeliverV2.Params();
                p.port    = serialPort;
                p.toAddr  = 0xFA;
                p.fromAddr= 0xFF;
                p.product = safeParseInt(edtProduct.getText()!=null?edtProduct.getText().toString().trim():"1", 1);
                p.preset  = safeParseDouble(edtPreset.getText()!=null?edtPreset.getText().toString().trim():"0", 0.0);
                p.verbose = true; p.startAcceptFlow = true; p.ticketPost = "if-pending";

                appendAndBuffer("[C] unlock/prestart/start...");
                LcrSimpleDeliverV2 lcr = new LcrSimpleDeliverV2(p);
                lcr.unlock();
                lcr.prestart();
                lcr.start();
                appendAndBuffer("[C] start() OK — surveillez LIVE dans l’app.");

            } catch (Exception e) {
                appendAndBuffer("[C] ERREUR: " + e.getMessage());
            }
        }).start();
    }

    /* ================================================================
       OUTILS DE DÉCODAGE 0x28 / 0x23
       ================================================================ */
    private void decode28(String tag, byte[] p) {
        // p: [rc, sub, ds_hi, ds_lo, dc_hi, dc_lo]
        if (p == null || p.length < 6) { appendAndBuffer("["+tag+"] 0x28 payload invalide"); return; }
        int rc = p[0] & 0xFF;
        int ds = ((p[2] & 0xFF) << 8) | (p[3] & 0xFF);
        int dc = ((p[4] & 0xFF) << 8) | (p[5] & 0xFF);
        appendAndBuffer(String.format("[%s] 0x28 rc=0x%02X DS=0x%04X DC=0x%04X %s",
                tag, rc, ds, dc, dcBits(dc)));
    }

    private void decode23(String tag, byte[] p) {
        // p: [rc, sub, dev_hi, dev_lo, ds_hi, ds_lo, dc_hi, dc_lo]
        if (p == null || p.length < 8) { appendAndBuffer("["+tag+"] 0x23 payload invalide"); return; }
        int rc = p[0] & 0xFF;
        int dev= ((p[2] & 0xFF) << 8) | (p[3] & 0xFF);
        int ds = ((p[4] & 0xFF) << 8) | (p[5] & 0xFF);
        int dc = ((p[6] & 0xFF) << 8) | (p[7] & 0xFF);
        appendAndBuffer(String.format("[%s] 0x23 rc=0x%02X DEV=0x%04X DS=0x%04X DC=0x%04X %s",
                tag, rc, dev, ds, dc, dcBits(dc)));
    }

    private String dcBits(int dc) {
        boolean ticket = (dc & 0x0001) != 0;
        boolean flow   = (dc & 0x0004) != 0;
        boolean deliv  = (dc & 0x0008) != 0;
        boolean begin  = (dc & 0x0400) != 0;
        return String.format("[ticket=%s flow=%s delivery=%s beginDelivery=%s]",
                ticket, flow, deliv, begin);
    }

    /* ================================================================
       OUTILS : USB dump, open verify, tests I/O, mini ping LCP
       ================================================================ */
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
                appendAndBuffer("[PORT] Permission USB absente. Clique 'Connexion USB' d’abord.");
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

    private void testIoSuite() {
        if (serialPort == null) {
            appendAndBuffer("[I/O] Port non ouvert.");
            return;
        }
        new Thread(() -> {
            try {
                serialPort.purgeHwBuffers(true, true);

                // Test 1: Loopback (si TX<->RX court-circuités côté DB9)
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

                // Test 2: BREAK (si supporté)
                try {
                    serialPort.setBreak(true);
                    Thread.sleep(50);
                    serialPort.setBreak(false);
                    appendAndBuffer("[I/O] BREAK toggled OK");
                } catch (Exception e) {
                    appendAndBuffer("[I/O] BREAK non supporté: " + e.getMessage());
                }

                // Test 3: Écriture/lecture brute “0x00”
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

    private void testMiniPingLcp() {
        if (serialPort == null) {
            appendAndBuffer("[LCP] Port non ouvert.");
            return;
        }
        new Thread(() -> {
            try {
                int to   = parseHex(edtTo.getText().toString().trim());
                int from = parseHex(edtFrom.getText().toString().trim());
                if (from == 0) from = 0xFF;
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
       CONSOLE : Scan, Send HEX
       ================================================================ */
    private void scanNodes() {
        if (serialPort == null) {
            append("Scan: port non prêt — clique 'Connexion USB'.\n");
            return;
        }
        new Thread(() -> {
            try {
                int from = parseHex(edtFrom.getText().toString().trim());
                if (from == 0) from = 0xFF;
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

    private void promptAndSendHex() {
        if (serialPort == null) {
            append("RAW: port non prêt — clique 'Connexion USB'.\n");
            return;
        }
        final EditText edt = new EditText(this);
        edt.setHint("ex.: 28 (GET_DEL_STATUS), 23 (GET_MACHINE), 20 00 (GET_FIELD#0)");
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
            if (from == 0) from = 0xFF; // par défaut hôte FF

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

    /* ================================================================
       UTIL
       ================================================================ */
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