
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

    private LcpLink lcpLink;
    private LcpOps  lcpOps;

    private final StringBuilder logBuf = new StringBuilder(4096);
    private final Object lcpLock = new Object();
    private final ExecutorService lcpExec = Executors.newSingleThreadExecutor();

    // Anti double-connexion / double-RESYNC
    private volatile boolean isConnecting = false;

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
       Connexion USB — anti double-RESYNC
       ================================================================ */
    private void requestAndOpenFirstPort() {
        if (isConnecting) { append("Connexion déjà en cours...\n"); return; }
        isConnecting = true;
        setButtonsEnabled(false);

        try {
            UsbManager mgr = (UsbManager)getSystemService(Context.USB_SERVICE);
            List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(mgr);
            if (drivers.isEmpty()) { append("Aucun convertisseur USB‑Série détecté\n"); return; }

            UsbDevice dev = drivers.get(0).getDevice();

            if (!mgr.hasPermission(dev)) {
                append("Demande de permission USB…\n");
                PendingIntent pi = PendingIntent.getBroadcast(
                        this, 0,
                        new Intent(ACTION_USB_PERMISSION),
                        PendingIntent.FLAG_IMMUTABLE
                );
                mgr.requestPermission(dev, pi);
                return;
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
            if (lcpLink != null) { append("Déjà connecté.\n"); return; }

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

            // RESYNC 0x00 + respiration + premier poll 0x28
            append("[CONNECT] RESYNC 0x00\n");
            lcpLink.sendRecv(new byte[]{0x00}, 3200);
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            try {
                int[] dsdc = lcpOps.opDeliveryStatus(3000, 150);
                append(String.format("[CONNECT] First poll DS=0x%04X DC=0x%04X\n", dsdc[0], dsdc[1]));
            } catch(Exception ignore) {
                append("[CONNECT] First poll (ignorable) sans réponse\n");
            }
            append("[CONNECT] RESYNC OK\n");

        } catch(Exception e){
            append("ERREUR ouverture USB: " + e.getMessage() + "\n");
        } finally {
            isConnecting = false;
            setButtonsEnabled(true);
        }
    }

    /* ================================================================
       Helpers : log, parse, throttle, purge+resync, retries robustes
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

    // Throttle TX : ≥200 ms entre deux envois
    private long lastTxAt = 0;
    private void preSendThrottle(int minMs){
        long now = System.currentTimeMillis();
        long due = lastTxAt + minMs;
        if (now < due) {
            try { Thread.sleep(due - now); } catch(Exception ignored){}
        }
        lastTxAt = System.currentTimeMillis();
    }

    // Purge buffers + RESYNC propre
    private void purgeAndResync(){
        try {
            if (serialPort != null) serialPort.purgeHwBuffers(true, true);
        } catch(Exception ignored){}
        try {
            lcpLink.sendRecv(new byte[]{0x00}, 3200); // RESYNC 0x00
            Thread.sleep(200);
        } catch(Exception ignored){}
    }

    // Détection des erreurs qui justifient un resync
    private boolean needResync(Exception e){
        String s = (e==null? "" : e.getMessage()==null? "" : e.getMessage().toLowerCase());
        return s.contains("timeout") || s.contains("sync");
    }

    // Wrappers avec backoff : try -> retry -> purge+resync+retry -> dernier retry temporisé
    private void issueCommandWithRetry(String label, int code, int timeoutMs, int pauseMs){
        byte[] payload = new byte[]{0x24, (byte)code};
        try {
            preSendThrottle(200);
            logTxPayload(label, payload);
            lcpOps.opIssueCommand(code, timeoutMs, pauseMs);
            return;
        } catch (Exception e1) {
            try {
                Thread.sleep(150);
                preSendThrottle(200);
                lcpOps.opIssueCommand(code, timeoutMs, pauseMs);
                return;
            } catch(Exception e2) {
                if (needResync(e2) || needResync(e1)) {
                    append("[WARN] " + label + " timeout/sync → PURGE+RESYNC puis retry\n");
                    purgeAndResync();
                    try {
                        preSendThrottle(200);
                        lcpOps.opIssueCommand(code, timeoutMs, pauseMs);
                        return;
                    } catch(Exception e3) {
                        try {
                            Thread.sleep(250);
                            preSendThrottle(200);
                            lcpOps.opIssueCommand(code, timeoutMs, pauseMs);
                            return;
                        } catch(Exception e4) {
                            append("[ERREUR] " + label + " : " + e4.getMessage() + "\n");
                        }
                    }
                } else {
                    append("[ERREUR] " + label + " : " + e2.getMessage() + "\n");
                }
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
            try {
                Thread.sleep(150);
                preSendThrottle(200);
                int[] dsdc = lcpOps.opDeliveryStatus(timeoutMs, pauseMs);
                logStatusHuman("[RX] " + label, dsdc[0], dsdc[1]);
                return dsdc;
            } catch(Exception e2) {
                if (needResync(e2) || needResync(e1)) {
                    append("[WARN] " + label + " timeout/sync → PURGE+RESYNC puis retry\n");
                    purgeAndResync();
                    try {
                        preSendThrottle(200);
                        int[] dsdc = lcpOps.opDeliveryStatus(timeoutMs, pauseMs);
                        logStatusHuman("[RX] " + label, dsdc[0], dsdc[1]);
                        return dsdc;
                    } catch(Exception e3) {
                        try {
                            Thread.sleep(250);
                            preSendThrottle(200);
                            int[] dsdc = lcpOps.opDeliveryStatus(timeoutMs, pauseMs);
                            logStatusHuman("[RX] " + label, dsdc[0], dsdc[1]);
                            return dsdc;
                        } catch(Exception e4) {
                            append("[ERREUR] " + label + " : " + e4.getMessage() + "\n");
                        }
                    }
                } else {
                    append("[ERREUR] " + label + " : " + e2.getMessage() + "\n");
                }
            }
        }
        return new int[]{0,0};
    }

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
            // 1) petit retry direct
            try {
                Thread.sleep(150);
                preSendThrottle(200);
                int[] msd = lcpOps.opMachineStatusFull(timeoutMs, pauseMs);
                append(String.format("[RX] %s MS=0x%04X\n", label, msd[0]));
                logStatusHuman("[RX] " + label, msd[1], msd[2]);
                return msd;
            } catch(Exception e2) {
                // 2) purge + resync + retry
                if (needResync(e2) || needResync(e1)) {
                    append("[WARN] " + label + " timeout/sync → PURGE+RESYNC puis retry\n");
                    purgeAndResync();
                    try {
                        preSendThrottle(200);
                        int[] msd = lcpOps.opMachineStatusFull(timeoutMs, pauseMs);
                        append(String.format("[RX] %s MS=0x%04X\n", label, msd[0]));
                        logStatusHuman("[RX] " + label, msd[1], msd[2]);
                        return msd;
                    } catch(Exception e3) {
                        // 3) dernier essai
                        try {
                            Thread.sleep(250);
                            preSendThrottle(200);
                            int[] msd = lcpOps.opMachineStatusFull(timeoutMs, pauseMs);
                            append(String.format("[RX] %s MS=0x%04X\n", label, msd[0]));
                            logStatusHuman("[RX] " + label, msd[1], msd[2]);
                            return msd;
                        } catch(Exception e4) {
                            // --- GRACIEUX : on n'interrompt pas le flux, on laisse la macro trancher via 0x28 ---
                            append("[WARN] " + label + " indisponible (" + e4.getMessage() + ") — fallback via 0x28\n");
                            return new int[]{0, 0, 0}; // fallback neutre
                        }
                    }
                } else {
                    append("[WARN] " + label + " indisponible (" + e2.getMessage() + ") — fallback via 0x28\n");
                    return new int[]{0, 0, 0};
                }
            }
        }
    }

    /* ================================================================
       Macro A — Send / Clear (payloads visibles + résultat lisible + retry)
       ================================================================ */
    private void macroReset_locked() {
        if (!checkReady()) return;
        synchronized (lcpLock) {
            try {
                append("[A] --- SEND / CLEAR ---\n");

                // 0) STATUT initial
                int[] dsdc0 = deliveryStatusWithRetry("GET_DEL_STATUS", 5000, 150);
                boolean flow   = (dsdc0[1] & LcpOps.LCRSc_FLOW_ACTIVE) != 0;
                boolean active = (dsdc0[1] & LcpOps.LCRSc_DELIVERY_ACTIVE) != 0;
                boolean ticket = (dsdc0[1] & LcpOps.LCRSc_DEL_TICKET_PENDING) != 0;

                // 1) END si nécessaire
                if (flow || active) {
                    issueCommandWithRetry("END (#2)", 0x02, 3000, 300);
                    deliveryStatusWithRetry("POLL après END (0x28)", 5000, 250);
                } else {
                    append("[A] END ignoré (FLOW=0, DELIVERY=0)\n");
                }

                // 2) CLEAR si ticket
                int[] dsdc1 = deliveryStatusWithRetry("GET_DEL_STATUS", 5000, 250);
                boolean hasTicket = (dsdc1[1] & LcpOps.LCRSc_DEL_TICKET_PENDING) != 0;
                if (hasTicket) {
                    issueCommandWithRetry("CLEAR (#6)", 0x06, 3000, 250);

                    boolean cleared = false;
                    long t0 = System.currentTimeMillis();
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
                            if ( (dsdcR[1] & LcpOps.LCRSc_DEL_TICKET_PENDING) == 0 ) { cleared = true; break; }
                        }

                        if (!cleared) {
                            append("\n[ATTENTION] Ticket toujours en attente.\n" +
                                   "Imprimante locale possiblement NON PRÊTE (papier/couvercle/online).\n" +
                                   "Corriger la condition puis relancer CLEAR (A).\n\n");
                        }
                    }
                } else {
                    append("[A] CLEAR ignoré (pas de ticket)\n");
                }

                // 3) Stabilisation (stopper le spam si état propre)
                final int MASK_ANY = LcpOps.LCRSc_FLOW_ACTIVE
                                   | LcpOps.LCRSc_DELIVERY_ACTIVE
                                   | LcpOps.LCRSc_DEL_TICKET_PENDING;
                int stableOk = 0;
                long tS = System.currentTimeMillis();
                while (System.currentTimeMillis() - tS < 3000) { // max 3 s
                    int[] s = deliveryStatusWithRetry("STABILIZE (0x28)", 3000, 250);
                    boolean clean = ( (s[1] & MASK_ANY) == 0 );
                    if (clean) { stableOk++; if (stableOk >= 3) break; }
                    else stableOk = 0;
                }

                // 4) Statut final lisible + READY
                int[] fin = deliveryStatusWithRetry("FINAL (0x28)", 3000, 150);
                boolean ready = ( (fin[1] & (LcpOps.LCRSc_FLOW_ACTIVE |
                                             LcpOps.LCRSc_DELIVERY_ACTIVE |
                                             LcpOps.LCRSc_DEL_TICKET_PENDING)) == 0 );
                append(ready ? "[A] READY: FLOW=0, DELIVERY=0, TICKET=0\n"
                             : "[A] NOT READY: voir DS/DC ci-dessus\n");

            } catch(Exception e){
                append("[A] ERREUR: " + e.getMessage() + "\n");
            }
        }
    }

    /* ================================================================
       Macro B — GET_DEL_STATUS (payload visible + humain)
       ================================================================ */
    private void macroPing28_locked() {
        if (!checkReady()) return;
        synchronized (lcpLock) {
            try {
                deliveryStatusWithRetry("GET_DEL_STATUS (B)", 3000, 100);
            } catch(Exception e){
                append("[B] ERREUR: " + e.getMessage() + "\n");
            }
        }
    }

    /* ================================================================
       Macro C — START (durcie : RUN 0x00 + respiration + 0x23 gracieux + fallback 0x28)
       ================================================================ */
    private void macroStart_locked() {
        if (!checkReady()) return;
        synchronized (lcpLock) {
            try {
                // 0) ticket en attente ? ménage minimal
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

                // 1) Paramètres livraison
                LcrSimpleDeliverV2.Params p = new LcrSimpleDeliverV2.Params();
                p.port     = serialPort;
                p.toAddr   = parseIntSafe(edtTo.getText().toString(),   0xFA);
                p.fromAddr = parseIntSafe(edtFrom.getText().toString(), 0xFF);
                p.product  = parseIntSafe(edtProduct.getText().toString(), 1);
                p.preset   = parseDoubleSafe(edtPreset.getText().toString(), 50.0);

                LcrSimpleDeliverV2 d = new LcrSimpleDeliverV2(p, lcpOps);
                d.unlock();
                d.prestart();

                // 2) RUN 0x00 (retry robuste)
                issueCommandWithRetry("RUN (#0)", 0x00, 3000, 200);

                // 3) micro-respiration
                try { Thread.sleep(250); } catch(Exception ignore){}

                // 4) Attente démarrage : chemin A (0x23) gracieux, puis chemin B (0x28)
                long tStart = System.currentTimeMillis();
                boolean ok = false;
                while (System.currentTimeMillis() - tStart < 5000) {
                    // Chemin A — GET_MACHINE (gracieux, peut fallback via 0x28 plus loin)
                    int[] msd = machineStatusWithRetry("CHECK (0x23)", 3000, 150);
                    int ds = msd[1], dc2 = msd[2];
                    boolean flow   = (dc2 & LcpOps.LCRSc_FLOW_ACTIVE) != 0;
                    boolean active = (dc2 & LcpOps.LCRSc_DELIVERY_ACTIVE) != 0;
                    boolean begin  = (ds  & LcpOps.LCRSc_BEGIN_DELIVERY) != 0;
                    if (flow || active || begin) { ok = true; break; }

                    // Chemin B — fallback GET_DEL_STATUS (0x28)
                    int[] dsdc3 = deliveryStatusWithRetry("CHECK (0x28)", 3000, 150);
                    boolean flow2   = (dsdc3[1] & LcpOps.LCRSc_FLOW_ACTIVE) != 0;
                    boolean active2 = (dsdc3[1] & LcpOps.LCRSc_DELIVERY_ACTIVE) != 0;
                    if (flow2 || active2) { ok = true; break; }

                    try { Thread.sleep(200); } catch(Exception ignore){}
                }

                if (!ok) throw new Exception("START_TIMEOUT: aucun indicateur (FLOW/ACTIVE/BEGIN) dans la fenêtre.");

                append("[C] START OK\n");

            } catch(Exception e){
                append("[C] ERREUR: " + e.getMessage() + "\n");
            }
        }
    }

    /* ================================================================
       RAW (blocage de 0x7D côté UI — 7D géré automatiquement par LcpOps)
       ================================================================ */
    private void promptAndSendHex() {
        if (!checkReady()) return;

        EditText edt = new EditText(this);
        edt.setHint("payload hex (ex: 28, 24 06, 23)");

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

                        // Interdit : 0x7D (CHECK_REQUEST) — géré automatiquement par LcpOps
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
                append("[RAW] Envoi payload: " + bytesToHex(payload) + "\n");
                preSendThrottle(200);
                byte[] rsp = lcpLink.sendRecv(payload, timeout);
                append("[RAW] RX size=" + rsp.length + "\n");
            }catch(Exception e){
                append("[RAW] ERREUR: " + e.getMessage() + "\n");
                try {
                    append("[RAW] PURGE+RESYNC puis retry\n");
                    purgeAndResync();
                    preSendThrottle(200);
                    byte[] rsp = lcpLink.sendRecv(payload, timeout);
                    append("[RAW] RX size=" + rsp.length + " (après RESYNC)\n");
                } catch(Exception e2){
                    append("[RAW] ERREUR après RESYNC: " + e2.getMessage() + "\n");
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
        lcpExec.execute(() -> {
            try { r.run(); }
            finally { setButtonsEnabled(true); }
        });
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
