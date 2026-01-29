
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
importPort;
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

    // *** AJOUT ***
    private LcpLink lcpLink;   // INSTANCE UNIQUE
    private LcpOps  lcpOps;    // API métier, liée à la même session

    private final StringBuilder logBuf = new StringBuilder(4096);

    private final Object lcpLock = new Object();
    private final ExecutorService lcpExec = Executors.newSingleThreadExecutor();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        log        = findViewById(R.id.txtLog);
        edtTo      = findViewById(R.id.edtTo);
        edtFrom    = findViewById(R.id.edtFrom);
        edtProduct = findViewById(R.id.edtProduct);
        edtPreset  = findViewById(R.id.edtPreset);

        ensureDefaultAddresses();

        // Receivers USB
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
        findViewById(R.id.btnDiag).setOnClickListener(v -> runLcpTask(this::diagPing28_locked));
        findViewById(R.id.btnA).setOnClickListener(v -> runLcpTask(this::macroReset_locked));
        findViewById(R.id.btnB).setOnClickListener(v -> runLcpTask(this::macroPing28_locked));
        findViewById(R.id.btnC).setOnClickListener(v -> runLcpTask(this::macroStart_locked));
        findViewById(R.id.btnSendHex).setOnClickListener(v -> promptAndSendHex());

        append("Prêt. Branchez le LCR puis 'Connexion USB'.\n");
    }

    /* ================================================================
       OUVERTURE USB + SESSION LCP UNIQUE
       ================================================================ */
    private void connectPort(UsbDevice dev) {
        try {
            UsbManager mgr = (UsbManager)getSystemService(Context.USB_SERVICE);
            UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(dev);

            UsbDeviceConnection conn = mgr.openDevice(dev);
            serialPort = driver.getPorts().get(0);
            serialPort.open(conn);
            serialPort.setParameters(19200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);

            serialPort.setRTS(false);
            serialPort.setDTR(false);
            serialPort.purgeHwBuffers(true, true);

            append("Port ouvert OK\n");

            int to   = parseIntSafe(safeStr(edtTo.getText()),   0xFA);
            int from = parseIntSafe(safeStr(edtFrom.getText()), 0xFF);

            // *** INSTANCES UNIQUES !! ***
            lcpLink = new LcpLink(serialPort, to, from, true);
            lcpOps  = new LcpOps(lcpLink);

            append("[CONNECT] RESYNC 0x00 (ProductID)\n");
            byte[] fr = lcpLink.sendRecv(new byte[]{0x00}, 3200);

            append("[CONNECT] RESYNC OK — session initialisée\n");

        } catch (Exception e) {
            append("ERREUR ouverture: " + e.getMessage() + "\n");
        }
    }

    /* ================================================================
       MACRO A — END + CLEAR + POLL
       ================================================================ */
    private void macroReset_locked() {
        if (!checkReady()) return;
        synchronized (lcpLock) {
            try {
                append("[A] END (02)\n");
                lcpOps.opIssueCommand(0x02, 3000, 300);

                append("[A] CLEAR (06)\n");
                lcpOps.opIssueCommand(0x06, 3000, 200);

                long t0 = System.currentTimeMillis();
                while (true) {
                    int[] dsdc = lcpOps.opDeliveryStatus(3000, 200);
                    append(String.format("[A] DS=0x%04X DC=0x%04X\n", dsdc[0], dsdc[1]));
                    if ((dsdc[1] & 0x0001) == 0) break;  // ticket cleared
                    if (System.currentTimeMillis() - t0 > 8000) break;
                }

            } catch (Exception e) {
                append("[A] ERREUR: " + e.getMessage() + "\n");
            }
        }
    }

    /* ================================================================
       MACRO B — PING 0x28
       ================================================================ */
    private void macroPing28_locked() {
        if (!checkReady()) return;
        synchronized (lcpLock) {
            try {
                append("[B] GET_DEL_STATUS\n");
                int[] dsdc = lcpOps.opDeliveryStatus(3000, 100);
                append(String.format("[B] DS=0x%04X DC=0x%04X\n", dsdc[0], dsdc[1]));
            } catch(Exception e){
                append("[B] ERREUR: " + e.getMessage() + "\n");
            }
        }
    }

    private void diagPing28_locked() {
        macroPing28_locked();
    }

    /* ================================================================
       MACRO C — START (simple flux Python)
       ================================================================ */
    private void macroStart_locked() {
        if (!checkReady()) return;
        synchronized (lcpLock) {
            try {

                // UNLOCK : si ticket
                int[] dsdc = lcpOps.opDeliveryStatus(3000, 200);
                if ((dsdc[1] & 0x0001) != 0) {
                    append("[C] Ticket présent → END + CLEAR\n");

                    lcpOps.opIssueCommand(0x02, 3000, 300);
                    lcpOps.opIssueCommand(0x06, 3000, 300);

                    // attendre ticket = clear
                    lcpOps.opWaitForStatus(0x0001, 0x0000, 8000, 300);
                }

                // DÉBUT DÉLIVRANCE
                append("[C] Start (commande 0x00 via LcrSimpleDeliverV2)\n");

                LcrSimpleDeliverV2.Params p = new LcrSimpleDeliverV2.Params();
                p.port     = serialPort;
                p.toAddr   = parseIntSafe(edtTo.getText().toString(),   0xFA);
                p.fromAddr = parseIntSafe(edtFrom.getText().toString(), 0xFF);
                p.product  = parseIntSafe(edtProduct.getText().toString(), 1);
                p.preset   = parseDoubleSafe(edtPreset.getText().toString(), 50.0);
                p.verbose  = true;

                LcrSimpleDeliverV2 deliver = new LcrSimpleDeliverV2(p);
                deliver.unlock();
                deliver.prestart();
                deliver.start();

                append("[C] START OK\n");

            } catch(Exception e){
                append("[C] ERREUR: " + e.getMessage() + "\n");
            }
        }
    }

    /* ================================================================
       RAW (texte)
       ================================================================ */
    private void sendRawPayload_locked(byte[] payload, int timeoutMs) {
        if (!checkReady()) return;
        synchronized (lcpLock) {
            try {
                append("[RAW] Envoi...\n");
                byte[] rsp = lcpLink.sendRecv(payload, timeoutMs);
                append("[RAW] OK, RX=" + rsp.length + "\n");
            } catch(Exception e){
                append("[RAW] ERREUR: " + e.getMessage() + "\n");
            }
        }
    }

    /* ================================================================
       UTIL
       ================================================================ */

    private boolean checkReady() {
        if (serialPort == null || lcpLink == null || lcpOps == null) {
            append("Port/LCP non prêt — cliquez 'Connexion USB'.\n");
            return false;
        }
        return true;
    }
}
