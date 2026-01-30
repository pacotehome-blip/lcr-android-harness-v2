
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
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

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

    private LcpLink lcpLink;
    private LcpOps  lcpOps;

    private final StringBuilder logBuf = new StringBuilder(4096);
    private final Object lcpLock = new Object();
    private final ExecutorService lcpExec = Executors.newSingleThreadExecutor();

    // Anti double-connexion / double-RESYNC
    private volatile boolean isConnecting = false;

    // Budget RESYNC par macro (au plus 1)
    private volatile int resyncBudget = 0;

    // Fenêtre qui interdit le RESYNC pendant X ms (post-RUN)
    private volatile long resyncFreezeUntil = 0;
    private void freezeResyncFor(long ms){ resyncFreezeUntil = System.currentTimeMillis() + ms; }
    private boolean canResyncNow(){ return System.currentTimeMillis() > resyncFreezeUntil; }

    // ==== IDs Confirmés par la table des champs ====
    private static final int FIELD_PRODUCT      = 0x00; // #0 ProductNumber
    private static final int FIELD_NET_PRESET   = 0x06; // #6 NetPreset
    private static final int FIELD_GROSS_PRESET = 0x05; // #5 GrossPreset
    private static final int FIELD_GROSS_COUNT  = 0x2C; // #44 GrossCount
    private static final int FIELD_NET_COUNT    = 0x2D; // #45 NetCount
    private static final int FIELD_GROSS_TOTAL  = 0x11; // #17 GrossTotal
    private static final int FIELD_NET_TOTAL    = 0x12; // #18 NetTotal
    private static final int FIELD_DECIMALS     = 0x27; // #39 Decimals

    // ==== Mise à l’échelle LV (implied decimal via #39) ====
    private volatile int    qtyDecimals = 1;  // 0..3 → 2/1/0/3 décimales effectives
    private volatile double qtyDivisor  = 10.0;

    // ==== Snap au départ pour totalizers ====
    private Integer grossTot0 = null;
    private Integer netTot0   = null;

    /* ================================================================
       Receivers USB
       ================================================================ */
    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (granted) {
                append("Permission USB accordée, ouverture...\n");
                connectPort(device);
            } else {
                append("Permission USB refusée\n");
                try { if (serialPort != null) { serialPort.close(); } } catch(Exception ignored){}
                serialPort = null;
                lcpLink = null;
                lcpOps  = null;
                isConnecting = false;
                setButtonsEnabled(true);
            }
        }
    };

    private final BroadcastReceiver usbAttachDetach = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(i.getAction())) {
                append("USB attaché — cliquez 'Connexion USB'\n");
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(i.getAction())) {
                append("USB détaché\n");
                try { if (serialPort != null) serialPort.close(); } catch(Exception ignored){}
                serialPort = null;
                lcpLink = null;
                lcpOps  = null;
            }
        }
    };

    /* ================================================================
       onCreate
       ================================================================ */
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        log        = findViewById(R.id.txtLog);
        edtTo      = findViewById(R.id.edtTo);
        edtFrom    = findViewById(R.id.edtFrom);
        edtProduct = findViewById(R.id.edtProduct);
        edtPreset  = findViewById(R.id.edtPreset);

        ensureDefaultAddresses();

        registerReceiver(usbPermissionReceiver, new IntentFilter(ACTION_USB_PERMISSION));

        IntentFilter f = new IntentFilter();
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbAttachDetach, f);

        CheckBox switchIoLog = findViewById(R.id.switchIoLog);
        LcpLink.setLogger(this::appendAndBuffer);

        if (switchIoLog != null) {
            switchIoLog.setOnCheckedChangeListener((btn, checked) -> {
                LcpLink.DUMP_TX = checked;
                LcpLink.DUMP_RX = checked;
                append("I/O log " + (checked ? "activé" : "désactivé") + "\n");
            });
        }

        findViewById(R.id.btnCopyLog).setOnClickListener(v -> {
            ClipboardManager cb = (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
            if (cb != null) {
                cb.setPrimaryClip(ClipData.newPlainText("lcr_log", logBuf.toString()));
                append("Log copié\n");
            }
        });

        findViewById(R.id.btnClearLog).setOnClickListener(v -> {
            logBuf.setLength(0);
            runOnUiThread(() -> log.setText(""));
        });

        findViewById(R.id.btnConnect).setOnClickListener(v -> requestAndOpenFirstPort());
        findViewById(R.id.btnDiag).setOnClickListener(v -> runLcpTask(this::macroPing28_locked));
        findViewById(R.id.btnA).setOnClickListener(v -> runLcpTask(this::macroReset_locked));
        findViewById(R.id.btnB).setOnClickListener(v -> runLcpTask(this::macroPing28_locked));
        findViewById(R.id.btnC).setOnClickListener(v -> runLcpTask(this::macroStart_locked));
        findViewById(R.id.btnSendHex).setOnClickListener(v -> promptAndSendHex());

        append("Prêt. Branchez le LCR puis cliquez 'Connexion USB'.\n");
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(usbPermissionReceiver); } catch(Exception ignored){}
        try { unregisterReceiver(usbAttachDetach); } catch(Exception ignored){}
        try { if (serialPort != null) serialPort.close(); } catch(Exception ignored){}
        lcpExec.shutdownNow();
    }

    /* ================================================================
       Connexion USB
       ================================================================ */
    private void requestAndOpenFirstPort() {
        if (isConnecting) { append("Connexion déjà en cours...\n"); return; }
        isConnecting = true;
        setButtonsEnabled(false);

        try {
            UsbManager mgr = (UsbManager)getSystemService(Context.USB_SERVICE);
            List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(mgr);
            if (drivers.isEmpty()) { append("Aucun convertisseur USB‑Série détecté\n"); isConnecting=false; setButtonsEnabled(true); return; }

            UsbDevice dev = drivers.get(0).getDevice();

            if (!mgr.hasPermission(dev)) {
                append("Demande de permission USB…\n");
                PendingIntent pi = PendingIntent.getBroadcast(
                        this, 0,
                        new Intent(ACTION_USB_PERMISSION),
                        PendingIntent.FLAG_IMMUTABLE
                );
                mgr.requestPermission(dev, pi);
                return; // on attend le receiver
            }

            connectPort(dev);
        } catch (Exception e) {
            append("ERREUR: " + e.getMessage() + "\n");
            isConnecting = false;
            setButtonsEnabled(true);
        }
    }

    private void connectPort(UsbDevice dev) {
        try {
            if (lcpLink != null || serialPort != null) {
                append("Déjà connecté.\n");
                return;
            }

            UsbManager mgr = (UsbManager)getSystemService(Context.USB_SERVICE);
            UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(dev);
            if (driver == null) { append("Pas de driver compatible\n"); return; }

            UsbDeviceConnection conn = mgr.openDevice(dev);
            if (conn == null) { append("Impossible d’ouvrir le périphérique USB\n"); return; }

            serialPort = driver.getPorts().get(0);
            serialPort.open(conn);
            serialPort.setParameters(19200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);

            serialPort.setRTS(false);
            serialPort.setDTR(false);
            serialPort.purgeHwBuffers(true,true);

            append("Port ouvert 19200 8N1\n");

            int to   = parseIntSafe(safeStr(edtTo.getText()),   0xFA);
            int from = parseIntSafe(safeStr(edtFrom.getText()), 0xFF);

            lcpLink = new LcpLink(serialPort, to, from, true);
            lcpOps  = new LcpOps(lcpLink);

            // RESYNC 0x00 + premier poll 0x28 (best-effort)
            append("[CONNECT] RESYNC 0x00\n");
            try {
                lcpLink.sendRecv(new byte[]{0x00}, 2000);
                Thread.sleep(200);
                int[] dsdc = lcpOps.opDeliveryStatus(3000, 150);
                append(String.format("[CONNECT] First poll DS=0x%04X DC=0x%04X\n", dsdc[0], dsdc[1]));
                append("[CONNECT] RESYNC OK\n");
            } catch(Exception e) {
                append("[CONNECT] RESYNC best-effort: " + e.getMessage() + "\n");
            }

            // === Lire DECIMALS (#39) pour la mise à l’échelle des LV ===
            try {
                byte[] v = lcpOps.opGetField(FIELD_DECIMALS, 3000);
                int code = (v != null && v.length > 0) ? (v[0] & 0xFF) : 1; // défaut=dixièmes
                // 0=Hundredths(2), 1=Tenths(1), 2=Whole(0), 3=Thousandths(3)
                switch (code) {
                    case 0: qtyDecimals = 2; break;
                    case 1: qtyDecimals = 1; break;
                    case 2: qtyDecimals = 0; break;
                    case 3: qtyDecimals = 3; break;
                    default: qtyDecimals = 1;
                }
                qtyDivisor = Math.pow(10.0, qtyDecimals);
                append(String.format("[CONNECT] DECIMALS (#39)=%d → %d décimales (div=%s)\n",
                        code, qtyDecimals, String.format("%.0f", qtyDivisor)));
            } catch(Exception e) {
                qtyDecimals = 1; qtyDivisor = 10.0;
                append("[CONNECT] DECIMALS (#39) indisponible → défaut=1 décimale\n");
            }

        } catch(Exception e){
            append("ERREUR ouverture USB: " + e.getMessage() + "\n");
        } finally {
            isConnecting = false;
            setButtonsEnabled(true);
        }
    }

    /* ================================================================
       Helpers : log, parse, throttle, resync best-effort
       ================================================================ */
    private static String bytesToHex(byte[] b){
        if(b==null) return "(null)";
        StringBuilder sb=new StringBuilder();
        for(byte x:b) sb.append(String.format("%02X ",x));
        return sb.toString().trim();
    }
    private void logTxPayload(String label, byte[] payload){
        append(String.format("[TX] %s payload: %s\n", label, bytesToHex(payload)));
    }
    private String flagsFromDC(int dc){
        boolean t = (dc & 0x0001) != 0; // ticket
        boolean f = (dc & 0x0004) != 0; // flow
        boolean a = (dc & 0x0008) != 0; // delivery
        return String.format("[ACTIVE=%s FLOW=%s TICKET=%s]", a, f, t);
    }
    private void logStatusHuman(String label, int ds, int dc){
        append(String.format("%s DS=0x%04X DC=0x%04X %s\n", label, ds, dc, flagsFromDC(dc)));
        if ((dc & 0x0001) != 0) append("[INFO] Ticket en cours (TICKET_PENDING=1)\n");
    }

    private long lastTxAt = 0;
    private void preSendThrottle(int minMs){
        long now = System.currentTimeMillis();
        long due = lastTxAt + minMs;
        if (now < due) {
            try { Thread.sleep(due - now); } catch(Exception ignored){}
        }
        lastTxAt = System.currentTimeMillis();
    }

    private void purgeAndResyncBestEffort(){
        try { if (serialPort != null) serialPort.purgeHwBuffers(true, true); } catch(Exception ignored){}
        try { preSendThrottle(150); lcpLink.sendRecv(new byte[]{0x00}, 1200); } catch(Exception ignored){}
        try { Thread.sleep(150); } catch(Exception ignored){}
    }

    private boolean needResync(Exception e){
        String s = (e==null? "" : e.getMessage()==null? "" : e.getMessage().toLowerCase());
        return s.contains("timeout") || s.contains("sync");
    }

    /* ============================ Wrappers avec backoff + budget RESYNC ============================ */

    private void issueCommandWithRetry(String label, int code, int timeoutMs, int pauseMs){
        byte[] payload = new byte[]{0x24, (byte)code};
        try {
            preSendThrottle(200);
            logTxPayload(label, payload);
            lcpOps.opIssueCommand(code, timeoutMs, pauseMs);
            return;
        } catch (Exception e1) {
            try { Thread.sleep(150); preSendThrottle(200); lcpOps.opIssueCommand(code, timeoutMs, pauseMs); return; }
            catch(Exception e2) {
                if ((needResync(e2) || needResync(e1)) && resyncBudget > 0 && canResyncNow()) {
                    append("[WARN] " + label + " timeout/sync → PURGE+RESYNC (unique) puis retry\n");
                    resyncBudget--;
                    purgeAndResyncBestEffort();
                    try { preSendThrottle(200); lcpOps.opIssueCommand(code, timeoutMs, pauseMs); return; }
                    catch(Exception e3) {
                        try { Thread.sleep(250); preSendThrottle(200); lcpOps.opIssueCommand(code, timeoutMs, pauseMs); return; }
                        catch(Exception e4) { append("[ERREUR] " + label + " : " + e4.getMessage() + "\n"); }
                    }
                } else append("[ERREUR] " + label + " : " + e2.getMessage() + "\n");
            }
        }
    }

    private int[] deliveryStatusWithRetry(String label, int timeoutMs, int pauseMs){
        byte[] payload = new byte[]{0x28};
        try {
            preSendThrottle(200);
            logTxPayload(label, payload);
            int[] dsdc = lcpOps.opDeliveryStatus(timeoutMs, pauseMs);
            logStatusHuman("[RX] " + label, dsdc[0], dsdc[1]);
            return dsdc;
        } catch (Exception e1) {
            try { Thread.sleep(150); preSendThrottle(200); int[] dsdc = lcpOps.opDeliveryStatus(timeoutMs, pauseMs); logStatusHuman("[RX] " + label, dsdc[0], dsdc[1]); return dsdc; }
            catch(Exception e2) {
                if ((needResync(e2) || needResync(e1)) && resyncBudget > 0 && canResyncNow()) {
                    append("[WARN] " + label + " timeout/sync → PURGE+RESYNC (unique) puis retry\n");
                    resyncBudget--;
                    purgeAndResyncBestEffort();
                    try { preSendThrottle(200); int[] dsdc = lcpOps.opDeliveryStatus(timeoutMs, pauseMs); logStatusHuman("[RX] " + label, dsdc[0], dsdc[1]); return dsdc; }
                    catch(Exception e3) {
                        try { Thread.sleep(250); preSendThrottle(200); int[] dsdc = lcpOps.opDeliveryStatus(timeoutMs, pauseMs); logStatusHuman("[RX] " + label, dsdc[0], dsdc[1]); return dsdc; }
                        catch(Exception e4) { append("[ERREUR] " + label + " : " + e4.getMessage() + "\n"); }
                    }
                } else append("[ERREUR] " + label + " : " + e2.getMessage() + "\n");
            }
        }
        return new int[]{0,0};
    }

    // Wake best-effort (rarement utile en FLOW)
    private int[] machineStatusWithRetry(String label, int timeoutMs, int pauseMs){
        byte[] payload = new byte[]{0x23};
        try {
            preSendThrottle(200);
            logTxPayload(label, payload);
            int[] msd = lcpOps.opMachineStatusFull(timeoutMs, pauseMs);
            append(String.format("[RX] %s MS=0x%04X\n", label, msd[0]));
            logStatusHuman("[RX] " + label, msd[1], msd[2]);
            return msd;
        } catch (Exception e1) {
            try { Thread.sleep(150); preSendThrottle(200); int[] msd = lcpOps.opMachineStatusFull(timeoutMs, pauseMs); append(String.format("[RX] %s MS=0x%04X\n", label, msd[0])); logStatusHuman("[RX] " + label, msd[1], msd[2]); return msd; }
            catch(Exception e2) {
                if ((needResync(e2) || needResync(e1)) && resyncBudget > 0 && canResyncNow()) {
                    append("[WARN] " + label + " timeout/sync → PURGE+RESYNC (unique) puis retry\n");
                    resyncBudget--;
                    purgeAndResyncBestEffort();
                    try { preSendThrottle(200); int[] msd = lcpOps.opMachineStatusFull(timeoutMs, pauseMs); append(String.format("[RX] %s MS=0x%04X\n", label, msd[0])); logStatusHuman("[RX] " + label, msd[1], msd[2]); return msd; }
                    catch(Exception e3) { append("[WARN] " + label + " indisponible: " + e3.getMessage() + "\n"); return new int[]{0,0,0}; }
                } else { append("[WARN] " + label + " indisponible: " + e2.getMessage() + "\n"); return new int[]{0,0,0}; }
            }
        }
    }

    private Integer getFieldI32WithRetry(String label, int fieldId, int timeoutMs){
        logTxPayload(label, new byte[]{0x20, (byte)(fieldId & 0xFF)});
        try {
            preSendThrottle(200);
            byte[] val = lcpOps.opGetField(fieldId, timeoutMs);
            if (val == null || val.length < 4) throw new Exception("valeur <4");
            int v = ((val[0] & 0xFF) << 24) | ((val[1] & 0xFF) << 16) | ((val[2] & 0xFF) << 8) | (val[3] & 0xFF);
            return v;
        } catch(Exception e1){
            try { Thread.sleep(150); preSendThrottle(200);
                byte[] val = lcpOps.opGetField(fieldId, timeoutMs);
                if (val == null || val.length < 4) throw new Exception("valeur <4");
                int v = ((val[0] & 0xFF) << 24) | ((val[1] & 0xFF) << 16) | ((val[2] & 0xFF) << 8) | (val[3] & 0xFF);
                return v;
            } catch(Exception e2){
                if ((needResync(e2) || needResync(e1)) && resyncBudget > 0 && canResyncNow()) {
                    append("[WARN] " + label + " → PURGE+RESYNC (unique) puis retry\n");
                    resyncBudget--;
                    purgeAndResyncBestEffort();
                    try { preSendThrottle(200);
                        byte[] val = lcpOps.opGetField(fieldId, timeoutMs);
                        if (val == null || val.length < 4) throw new Exception("valeur <4");
                        int v = ((val[0] & 0xFF) << 24) | ((val[1] & 0xFF) << 16) | ((val[2] & 0xFF) << 8) | (val[3] & 0xFF);
                        return v;
                    } catch(Exception e3){ append("[ERREUR] " + label + " : " + e3.getMessage() + "\n"); }
                } else append("[ERREUR] " + label + " : " + e2.getMessage() + "\n");
            }
        }
        return null;
    }

    // === Helpers LV avec échelle (#39) ===
    private String fmtLV(Integer raw){
        if (raw == null) return "—";
        return String.format("%,." + qtyDecimals + "f", raw / qtyDivisor);
    }
    private String fmtLVDiff(Integer curr, Integer base){
        if (curr==null || base==null) return "(+—)";
        long d = (long)curr - (long)base;
        if (d < 0) d = 0;
        return String.format("(+%," + qtyDecimals + "." + qtyDecimals + "f)", d / qtyDivisor);
    }

    // --- Helpers COUNT/NETCOUNT ---
    private Integer readNetCountI32Safe() { try { return getFieldI32WithRetry("GET_FIELD #45 (NetCount)", FIELD_NET_COUNT, 3000); } catch(Exception e){ return null; } }
    private int safeDelta(Integer curr, Integer base) {
        if (curr == null || base == null) return 0;
        long d = (long)curr - (long)base;
        if (d < 0) d = 0; if (d > Integer.MAX_VALUE) d = Integer.MAX_VALUE;
        return (int)d;
    }

    /* ================================================================
       Macro A — Send / Clear
       ================================================================ */
    private void macroReset_locked() {
        if (!checkReady()) return;
        synchronized (lcpLock) {
            try {
                resyncBudget = 1;
                append("[A] --- SEND / CLEAR ---\n");

                int[] dsdc0 = deliveryStatusWithRetry("GET_DEL_STATUS", 5000, 150);
                boolean flow   = (dsdc0[1] & LcpOps.LCRSc_FLOW_ACTIVE) != 0;
                boolean active = (dsdc0[1] & LcpOps.LCRSc_DELIVERY_ACTIVE) != 0;
                boolean ticket = (dsdc0[1] & LcpOps.LCRSc_DEL_TICKET_PENDING) != 0;

                if (flow || active) {
                    issueCommandWithRetry("END (#2)", 0x02, 3000, 300);
                    deliveryStatusWithRetry("POLL après END (0x28)", 5000, 250);
                } else append("[A] END ignoré (FLOW=0, DELIVERY=0)\n");

                int[] dsdc1 = deliveryStatusWithRetry("GET_DEL_STATUS", 5000, 250);
                boolean hasTicket = (dsdc1[1] & LcpOps.LCRSc_DEL_TICKET_PENDING) != 0;
                if (hasTicket) {
                    issueCommandWithRetry("CLEAR (#6)", 0x06, 3000, 250);
                    long t0 = System.currentTimeMillis();
                    boolean cleared = false;
                    while (System.currentTimeMillis() - t0 < 8000) {
                        int[] dsdc2 = deliveryStatusWithRetry("POLL ticket (0x28)", 3000, 250);
                        if ( (dsdc2[1] & LcpOps.LCRSc_DEL_TICKET_PENDING) == 0 ) { cleared = true; break; }
                    }
                    if (!cleared) {
                        machineStatusWithRetry("WAKE (GET_MACHINE 0x23)", 5000, 150);
                        long tw = System.currentTimeMillis();
                        while (!cleared && System.currentTimeMillis() - tw < 2000) {
                            int[] dsdcW = deliveryStatusWithRetry("POLL wake (0x28)", 3000, 250);
                            if ( (dsdcW[1] & LcpOps.LCRSc_DEL_TICKET_PENDING) == 0 ) cleared = true;
                        }
                    }
                    if (!cleared) {
                        issueCommandWithRetry("CLEAR retry (#6)", 0x06, 3000, 250);
                        long tR = System.currentTimeMillis();
                        while (System.currentTimeMillis() - tR < 8000) {
                            int[] dsdcR = deliveryStatusWithRetry("POLL retry (0x28)", 3000, 250);
                            if ( (dsdcR[1] & LcpOps.LCRSc_DEL_TICKET_PENDING) == 0 ) { /* ok */ break; }
                        }
                        append("\n[ATTENTION] Ticket toujours en attente — vérifier imprimante locale.\n\n");
                    }
                } else append("[A] CLEAR ignoré (pas de ticket)\n");

                final int MASK_ANY = LcpOps.LCRSc_FLOW_ACTIVE | LcpOps.LCRSc_DELIVERY_ACTIVE | LcpOps.LCRSc_DEL_TICKET_PENDING;
                int stableOk = 0; long tS = System.currentTimeMillis();
                while (System.currentTimeMillis() - tS < 3000) {
                    int[] s = deliveryStatusWithRetry("STABILIZE (0x28)", 3000, 250);
                    boolean clean = ((s[1] & MASK_ANY) == 0);
                    if (clean) { stableOk++; if (stableOk >= 3) break; } else stableOk = 0;
                }

                int[] fin = deliveryStatusWithRetry("FINAL (0x28)", 3000, 150);
                boolean ready = (((fin[1] & MASK_ANY) == 0));
                append(ready ? "[A] READY: FLOW=0, DELIVERY=0, TICKET=0\n" : "[A] NOT READY: voir DS/DC ci-dessus\n");

            } catch(Exception e){
                append("[A] ERREUR: " + e.getMessage() + "\n");
            }
        }
    }

    /* ================================================================
      Macro B — GET_DEL_STATUS
      ================================================================ */
    private void macroPing28_locked() {
        if (!checkReady()) return;
        synchronized (lcpLock) {
            try {
                resyncBudget = 1;
                deliveryStatusWithRetry("GET_DEL_STATUS (B)", 3000, 100);
            } catch(Exception e){
                append("[B] ERREUR: " + e.getMessage() + "\n");
            }
        }
    }

    /* ================================================================
      Macro C — START orienté FLOW (RUN relaxé) + Totaux (départ & live)
      ================================================================ */
    private void macroStart_locked() {
        if (!checkReady()) return;
        synchronized (lcpLock) {
            try {
                resyncBudget = 1;

                int[] dsdc = deliveryStatusWithRetry("GET_DEL_STATUS (pré-START)", 3000, 200);
                if ((dsdc[1] & 0x0001) != 0) {
                    append("[C] Ticket → END+CLEAR\n");
                    issueCommandWithRetry("END (#2)", 0x02, 3000, 300);
                    issueCommandWithRetry("CLEAR (#6)", 0x06, 3000, 300);
                    long t0 = System.currentTimeMillis();
                    while (System.currentTimeMillis() - t0 < 8000) {
                        int[] dsdc2 = deliveryStatusWithRetry("POLL ticket avant START", 3000, 300);
                        if ((dsdc2[1] & 0x0001) == 0) break;
                    }
                }

                append("[C] START…\n");

                int product = parseIntSafe(safeStr(edtProduct.getText()), 1);
                double preset = parseDoubleSafe(safeStr(edtPreset.getText()), 50.0);
                int preset_i32 = (int)Math.round(preset * qtyDivisor); // LV→i32 selon decimals
                final boolean presetEnabled = (preset_i32 > 0);

                // (Re)lire DECIMALS avant
                try {
                    byte[] v = lcpOps.opGetField(FIELD_DECIMALS, 3000);
                    int code = (v != null && v.length > 0) ? (v[0] & 0xFF) : 1;
                    switch (code) { case 0: qtyDecimals=2; break; case 1: qtyDecimals=1; break; case 2: qtyDecimals=0; break; case 3: qtyDecimals=3; break; default: qtyDecimals=1; }
                    qtyDivisor = Math.pow(10.0, qtyDecimals);
                    // recalcul preset_i32 si l’utilisateur a changé la config
                    preset_i32 = (int)Math.round(preset * qtyDivisor);
                } catch(Exception ignore){}

                try {
                    lcpOps.opSetField(FIELD_PRODUCT, new byte[]{ (byte)(product & 0xFF) }, 3000);
                    append(String.format("[C] SET_FIELD #0 (product) = %d\n", product));
                } catch(Exception e) {
                    append("[C] WARN: SET_FIELD #0 (product) ignoré: " + e.getMessage() + "\n");
                }

                if (presetEnabled) {
                    lcpOps.opSetField(FIELD_NET_PRESET,   i32be(preset_i32), 3000);
                    lcpOps.opSetField(FIELD_GROSS_PRESET, i32be(0),           3000);
                    append(String.format("[C] PRÉSET actif : %s (arrêt demandé au seuil). Le gun peut créer un léger dépassement.\n",
                            String.format("%,."+qtyDecimals+"f", preset)));
                } else {
                    append("[C] PRÉSET = 0 → Mode AUTO : aucun arrêt logiciel ne sera imposé.\n");
                }

                issueRunWithGrace();  // <-- version relaxée (pas de 0x7D)

                // Références COUNT
                Integer g0 = getFieldI32WithRetry("GET_FIELD #44 (GrossCount0)", FIELD_GROSS_COUNT, 3000);
                Integer n0 = getFieldI32WithRetry("GET_FIELD #45 (NetCount0)",   FIELD_NET_COUNT,   3000);

                // Snap TOTALIZERS départ
                grossTot0 = getFieldI32WithRetry("GET_FIELD GrossTotal0 (#17)",  FIELD_GROSS_TOTAL, 3000);
                netTot0   = getFieldI32WithRetry("GET_FIELD NetTotal0 (#18)",    FIELD_NET_TOTAL,   3000);
                append(String.format("[C] Totaux départ: Gross=%s  Net=%s\n", fmtLV(grossTot0), fmtLV(netTot0)));

                // Attente FLOW
                append("[C] En attente FLOW (ouvrir le gun) …\n");
                long tStart = System.currentTimeMillis();
                boolean flowSeen = false;
                while (System.currentTimeMillis() - tStart < 8000) {
                    int[] s = deliveryStatusWithRetry("WAIT_FLOW (0x28)", 5000, 200);
                    int dc2 = s[1];
                    boolean flow   = (dc2 & LcpOps.LCRSc_FLOW_ACTIVE) != 0;
                    boolean active = (dc2 & LcpOps.LCRSc_DELIVERY_ACTIVE) != 0;
                    if (flow || active) { flowSeen = true; break; }

                    // Fallback via compteurs (commentable si besoin)
                    Integer n = readNetCountI32Safe();
                    if (safeDelta(n, n0) > 0) { flowSeen = true; break; }

                    try { Thread.sleep(200); } catch(Exception ignore){}
                }

                if (!flowSeen) { append("[C] ERREUR: START_TIMEOUT: FLOW jamais activé (gun non ouvert/interlock?)\n"); return; }
                append("[C] FLOW détecté → livraison en cours.\n");

                if (presetEnabled) {
                    boolean endSent = false;
                    while (true) {
                        int[] s = deliveryStatusWithRetry("CHECK (0x28)", 3000, 250);
                        int dc2 = s[1];
                        boolean flow = (dc2 & LcpOps.LCRSc_FLOW_ACTIVE) != 0;

                        Integer n = readNetCountI32Safe();
                        int delta = safeDelta(n, n0);
                        append(String.format("[C] Livré (net courante) = %s / %s\n",
                                String.format("%,."+qtyDecimals+"f", delta / qtyDivisor),
                                String.format("%,."+qtyDecimals+"f", preset)));

                        // Totaux live
                        Integer gTot = getFieldI32WithRetry("GET_FIELD GrossTotal (#17)", FIELD_GROSS_TOTAL, 3000);
                        Integer nTot = getFieldI32WithRetry("GET_FIELD NetTotal (#18)",   FIELD_NET_TOTAL,   3000);
                        append(String.format("[C] Totaux: Gross=%s %s  Net=%s %s\n",
                                fmtLV(gTot), fmtLVDiff(gTot, grossTot0),
                                fmtLV(nTot), fmtLVDiff(nTot, netTot0)));

                        if (!endSent && delta >= preset_i32) {
                            append("[C] Seuil atteint → demande d’arrêt (END #2)\n");
                            issueCommandWithRetry("END (#2)", 0x02, 3000, 300);
                            endSent = true;
                        }

                        if (endSent && !flow) { append("[C] FLOW retombé → arrêt confirmé par la vanne/gun.\n"); break; }
                        if (!flow && delta < preset_i32) { append("[C] Arrêt précoce par opérateur (avant le seuil).\n"); break; }

                        try { Thread.sleep(250); } catch(Exception ignore){}
                    }
                } else {
                    // Mode AUTO : afficher les totalizers tant que FLOW=1
                    while (true) {
                        int[] s = deliveryStatusWithRetry("CHECK (0x28)", 3000, 250);
                        int dc2 = s[1];
                        boolean flow = (dc2 & LcpOps.LCRSc_FLOW_ACTIVE) != 0;

                        Integer gTot = getFieldI32WithRetry("GET_FIELD GrossTotal (#17)", FIELD_GROSS_TOTAL, 3000);
                        Integer nTot = getFieldI32WithRetry("GET_FIELD NetTotal (#18)",   FIELD_NET_TOTAL,   3000);
                        append(String.format("[C] Totaux: Gross=%s %s  Net=%s %s\n",
                                fmtLV(gTot), fmtLVDiff(gTot, grossTot0),
                                fmtLV(nTot), fmtLVDiff(nTot, netTot0)));

                        if (!flow) { append("[C] FLOW retombé → fin de livraison (mode AUTO)\n"); break; }
                        try { Thread.sleep(300); } catch(Exception ignore){}
                    }
                }

                append("[C] START OK\n");

            } catch(Exception e){
                append("[C] ERREUR: " + e.getMessage() + "\n");
            }
        }
    }

    // ---- i32 BE util (évite d’importer depuis LcpOps) ----
    private static byte[] i32be(int v) {
        return new byte[] {
                (byte)((v >> 24) & 0xFF),
                (byte)((v >> 16) & 0xFF),
                (byte)((v >> 8)  & 0xFF),
                (byte)(v & 0xFF)
        };
    }

    // RUN (#0) gracieux — utilise la version "relaxée" (pas de 0x7D après rc=0x26)
    private void issueRunWithGrace() {
        try {
            int rc = lcpOps.opIssueCommandRelaxed(0x00, 5000, 250);
            if (rc == 0x26) append("[INFO] RUN (#0) queue=1 (rc=0x26) — attente FLOW côté opérateur/vanne\n");
        } catch(Exception e) {
            append("[WARN] RUN (#0) sans ACK exploitable — vérif via 0x28/compteurs\n");
        }
        freezeResyncFor(1500);
        try { Thread.sleep(750); } catch(Exception ignore) {}
    }

    /* ================================================================
       RAW (blocage 0x7D — géré en interne par LcpOps)
       ================================================================ */
    private void promptAndSendHex() {
        if (!checkReady()) return;

        EditText edt = new EditText(this);
        edt.setHint("payload hex (ex: 28, 24 06, 24 02, 23, 20 2C)");

        EditText edtTimeout = new EditText(this);
        edtTimeout.setHint("timeout ms");
        edtTimeout.setText("3000");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(8 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);
        layout.addView(edt);
        layout.addView(edtTimeout);

        new AlertDialog.Builder(this)
                .setTitle("Envoyer payload LCP (RAW)")
                .setView(layout)
                .setPositiveButton("Envoyer",(d,w)->{
                    try{
                        String hex = edt.getText().toString();
                        int to = Integer.parseInt(edtTimeout.getText().toString());
                        byte[] pl = parseHexBytes(hex);

                        if (pl != null && pl.length > 0 && (pl[0] & 0xFF) == 0x7D) {
                            append("[RAW] 0x7D (CHECK_REQUEST) est géré automatiquement par LcpOps — envoi UI bloqué.\n");
                            return;
                        }

                        runLcpTask(() -> sendRawPayload_locked(pl, to));
                    }catch(Exception e){
                        append("[RAW] invalide: " + e.getMessage() + "\n");
                    }
                })
                .setNegativeButton("Annuler",null)
                .show();
    }

    private void sendRawPayload_locked(byte[] payload, int timeout){
        if (!checkReady()) return;
        synchronized(lcpLock){
            try{
                resyncBudget = 1;
                append("[RAW] Envoi payload: " + bytesToHex(payload) + "\n");
                preSendThrottle(200);
                byte[] rsp = lcpLink.sendRecv(payload, timeout);
                append("[RAW] RX size=" + rsp.length + "\n");
            }catch(Exception e){
                append("[RAW] ERREUR: " + e.getMessage() + "\n");
                if (resyncBudget > 0 && canResyncNow()) {
                    append("[RAW] PURGE+RESYNC (unique) puis retry\n");
                    resyncBudget--;
                    purgeAndResyncBestEffort();
                    try { preSendThrottle(200);
                        byte[] rsp = lcpLink.sendRecv(payload, timeout);
                        append("[RAW] RX size=" + rsp.length + " (après RESYNC)\n");
                    } catch(Exception e2){
                        append("[RAW] ERREUR après RESYNC: " + e2.getMessage() + "\n");
                    }
                }
            }
        }
    }

    /* ================================================================
       UTIL
       ================================================================ */
    private boolean checkReady(){
        if (serialPort == null || lcpLink == null || lcpOps == null) {
            append("Port/LCP non prêt.\n");
            return false;
        }
        return true;
    }

    private void runLcpTask(Runnable r){
        setButtonsEnabled(false);
        lcpExec.execute(() -> { try { r.run(); } finally { setButtonsEnabled(true); } });
    }

    private void setButtonsEnabled(boolean enabled){
        runOnUiThread(() -> {
            int[] ids = {
                R.id.btnA, R.id.btnB, R.id.btnC, R.id.btnScan, R.id.btnSendHex,
                R.id.btnTestUsb, R.id.btnConnect, R.id.btnDiag, R.id.btnStart
            };
            for (int id : ids) {
                View v = findViewById(id);
                if (v != null) v.setEnabled(enabled);
            }
        });
    }

    private void ensureDefaultAddresses() {
        runOnUiThread(() -> {
            if (edtTo != null && !"0xFA".equalsIgnoreCase(safeStr(edtTo.getText())))
                edtTo.setText("0xFA");
            if (edtFrom != null && !"0xFF".equalsIgnoreCase(safeStr(edtFrom.getText())))
                edtFrom.setText("0xFF");
        });
    }

    private int parseIntSafe(String s, int def){
        try { return Integer.parseInt(s.replace("0x",""), 16); }
        catch(Exception e){ return def; }
    }

    private double parseDoubleSafe(String s, double def){
        try{ return Double.parseDouble(s); }
        catch(Exception e){ return def; }
    }

    private String safeStr(CharSequence cs){
        return cs==null? "" : cs.toString().trim();
    }

    private byte[] parseHexBytes(String s){
        String c = s.replaceAll("(?i)0x","").replaceAll("[^0-9A-Fa-f]","");
        if (c.length()==0) throw new IllegalArgumentException("aucun hex");
        if (c.length()%2!=0) c = "0"+c;
        int n = c.length()/2;
        byte[] b = new byte[n];
        for (int i=0;i<n;i++) b[i] = (byte)Integer.parseInt(c.substring(2*i,2*i+2),16);
        return b;
    }

    private void append(String s){
        runOnUiThread(() -> log.append(s));
    }

    private void appendAndBuffer(String s){
        logBuf.append(s);
        if(!s.endsWith("\n")) logBuf.append("\n");
        append(s + "\n");
    }
}
