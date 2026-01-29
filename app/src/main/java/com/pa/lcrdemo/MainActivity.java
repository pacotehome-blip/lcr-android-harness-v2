
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
       Macro A — END + CLEAR (garde-fous + polls + wake + retry + message imprimante)
       ================================================================ */
    private void macroReset_locked() {
        if (!checkReady()) return;
        synchronized (lcpLock) {
            try {
                // Etat initial
                int[] dsdc0 = lcpOps.opDeliveryStatus(3000, 150);
                int ds0 = dsdc0[0], dc0 = dsdc0[1];

                boolean flow   = (dc0 & LcpOps.LCRSc_FLOW_ACTIVE) != 0;
                boolean active = (dc0 & LcpOps.LCRSc_DELIVERY_ACTIVE) != 0;
                boolean ticket = (dc0 & LcpOps.LCRSc_DEL_TICKET_PENDING) != 0;

                append(String.format("[A] Etat initial DS=0x%04X DC=0x%04X (flow=%s active=%s ticket=%s)\n",
                        ds0, dc0, flow, active, ticket));

                // 1) END uniquement si flow/delivery actifs
                if (flow || active) {
                    append("[A] END (0x02)\n");
                    lcpOps.opIssueCommand(0x02, 3000, 300); // queued-handling interne
                } else {
                    append("[A] END ignoré (pas de FLOW/DELIVERY)\n");
                }

                // 2) Relecture + CLEAR uniquement si ticket présent
                int[] dsdc1 = lcpOps.opDeliveryStatus(3000, 250);
                boolean hasTicket = (dsdc1[1] & LcpOps.LCRSc_DEL_TICKET_PENDING) != 0;

                if (hasTicket) {
                    append("[A] CLEAR (0x06)\n");
                    lcpOps.opIssueCommand(0x06, 3000, 250); // queued-handling interne

                    boolean cleared = false;

                    // Polls 8s
                    long t0 = System.currentTimeMillis();
                    int polls = 0;
                    while (System.currentTimeMillis() - t0 < 8000) {
                        int[] dsdc2 = lcpOps.opDeliveryStatus(3000, 250);
                        polls++;
                        append(String.format("[A] POLL ticket #%d DS=0x%04X DC=0x%04X\n",
                                polls, dsdc2[0], dsdc2[1]));
                        if ((dsdc2[1] & LcpOps.LCRSc_DEL_TICKET_PENDING) == 0) { cleared = true; break; }
                    }

                    // Wake 0x23 si ça colle encore
                    if (!cleared) {
                        append("[A] Wake (GET_MACHINE 0x23)\n");
                        try { lcpOps.opMachineStatusFull(5000, 150); } catch (Exception ignore) {}

                        long tw = System.currentTimeMillis();
                        while (!cleared && System.currentTimeMillis() - tw < 2000) {
                            int[] dsdcW = lcpOps.opDeliveryStatus(3000, 250);
                            append(String.format("[A] POLL wake DS=0x%04X DC=0x%04X\n", dsdcW[0], dsdcW[1]));
                            if ((dsdcW[1] & LcpOps.LCRSc_DEL_TICKET_PENDING) == 0) cleared = true;
                        }
                    }

                    // Retry CLEAR (1x) si nécessaire
                    if (!cleared) {
                        append("[A] CLEAR retry (0x06)\n");
                        lcpOps.opIssueCommand(0x06, 3000, 250);

                        long tR = System.currentTimeMillis();
                        while (System.currentTimeMillis() - tR < 8000) {
                            int[] dsdcR = lcpOps.opDeliveryStatus(3000, 250);
                            append(String.format("[A] POLL retry DS=0x%04X DC=0x%04X\n", dsdcR[0], dsdcR[1]));
                            if ((dsdcR[1] & LcpOps.LCRSc_DEL_TICKET_PENDING) == 0) { cleared = true; break; }
                        }

                        if (!cleared) {
                            // Message opérateur (imprimante probablement non prête)
                            append("\n[ATTENTION] Ticket toujours en attente.\n" +
                                   "Imprimante locale probablement NON PRÊTE (papier/couvercle/hors-ligne).\n" +
                                   "Corriger la condition imprimante puis relancer CLEAR (bouton A).\n\n");
                        }
                    }
                } else {
                    append("[A] CLEAR ignoré (pas de ticket)\n");
                }

                // 3) Etat final
                int[] dsdcf = lcpOps.opDeliveryStatus(3000, 150);
                append(String.format("[A] FINAL DS=0x%04X DC=0x%04X\n", dsdcf[0], dsdcf[1]));

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
                append("[B] GET_DEL_STATUS\n");
                int[] dsdc = lcpOps.opDeliveryStatus(3000, 100); // queued-handling interne
                append(String.format("[B] DS=0x%04X DC=0x%04X\n", dsdc[0], dsdc[1]));
            } catch(Exception e){
                append("[B] ERREUR: " + e.getMessage() + "\n");
            }
        }
    }

    /* ================================================================
       Macro C — START
       ================================================================ */
    private void macroStart_locked() {
        if (!checkReady()) return;
        synchronized (lcpLock) {
            try {
                // Ticket en attente ? -> END + CLEAR + attente ticket=0
                int[] dsdc = lcpOps.opDeliveryStatus(3000, 200);
                if ((dsdc[1] & 0x0001) != 0) {
                    append("[C] Ticket → END+CLEAR\n");

                    lcpOps.opIssueCommand(0x02, 3000, 300);
                    lcpOps.opIssueCommand(0x06, 3000, 300);

                    lcpOps.opWaitForStatus(0x0001, 0x0000, 8000, 300);
                }

                append("[C] START…\n");

                LcrSimpleDeliverV2.Params p = new LcrSimpleDeliverV2.Params();
                p.port     = serialPort;
                p.toAddr   = parseIntSafe(edtTo.getText().toString(),   0xFA);
                p.fromAddr = parseIntSafe(edtFrom.getText().toString(), 0xFF);
                p.product  = parseIntSafe(edtProduct.getText().toString(), 1);
                p.preset   = parseDoubleSafe(edtPreset.getText().toString(), 50.0);

                LcrSimpleDeliverV2 d = new LcrSimpleDeliverV2(p, lcpOps);
                d.unlock();
                d.prestart();
                d.start();

                append("[C] START OK\n");

            } catch(Exception e){
                append("[C] ERREUR: " + e.getMessage() + "\n");
            }
        }
    }

    /* ================================================================
       RAW (blocage de 0x7D depuis l'UI)
       ================================================================ */
    private void promptAndSendHex() {
        if (!checkReady()) return;

        EditText edt = new EditText(this);
        edt.setHint("payload hex (ex: 28, 20 00)");

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
                .setTitle("Envoyer payload LCP")
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
                append("[RAW] Envoi " + bytesToHex(payload) + "\n");
                byte[] rsp = lcpLink.sendRecv(payload, timeout);
                append("[RAW] RX size=" + rsp.length + "\n");
            }catch(Exception e){
                append("[RAW] ERREUR: " + e.getMessage() + "\n");
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

    private static String bytesToHex(byte[] b){
        if(b==null) return "(null)";
        StringBuilder sb=new StringBuilder();
        for(byte x:b) sb.append(String.format("%02X ",x));
        return sb.toString().trim();
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
