
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
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import com.pa.lcr.lcp.LcpLink;
import com.pa.lcr.lcp.DeliveryController;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String ACTION_USB_PERMISSION = "com.pa.lcrdemo.USB_PERMISSION";

    // UI
    private TextView txtLog;
    private EditText edtTo, edtFrom, edtProduct, edtPreset;
    private Button btnConnect, btnStartOpen /*mapped->btnC*/, btnStartPreset /*mapped->btnStart*/,
            btnEnd /*mapped->btnA*/, btnClear, btnCopy;
    private CheckBox switchIoLog; // mapped to existing R.id.switchIoLog

    // USB & LCP
    private UsbSerialPort serialPort;
    private LcpLink lcpLink;
    private DeliveryController controller;

    // Utils
    private final StringBuilder logBuf = new StringBuilder(16 * 1024);
    private final ExecutorService ioExec = Executors.newSingleThreadExecutor();

    /* ================================================================
       Android lifecycle
       ================================================================ */
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        setDefaults();
        wireEvents();

        registerReceiver(usbPermissionReceiver, new IntentFilter(ACTION_USB_PERMISSION));

        IntentFilter usb = new IntentFilter();
        usb.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        usb.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbAttachDetach, usb);

        LcpLink.setLogger(this::appendAndBuffer);

        append("Prêt. Branchez le LCR puis cliquez 'Connexion USB'.\n");
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(usbPermissionReceiver); } catch(Exception ignored){}
        try { unregisterReceiver(usbAttachDetach); } catch(Exception ignored){}
        try { if (serialPort != null) serialPort.close(); } catch(Exception ignored){}
        ioExec.shutdownNow();
    }

    /* ================================================================
       View wiring
       ================================================================ */
    private void bindViews() {
        txtLog       = findViewById(R.id.txtLog);
        edtTo        = findViewById(R.id.edtTo);
        edtFrom      = findViewById(R.id.edtFrom);
        edtProduct   = findViewById(R.id.edtProduct);
        edtPreset    = findViewById(R.id.edtPreset);

        btnConnect   = findViewById(R.id.btnConnect);
        // ---- MAPPINGS SUR TES IDS EXISTANTS ----
        btnStartOpen   = findViewById(R.id.btnC);      // Start OPEN (preset=0)
        btnStartPreset = findViewById(R.id.btnStart);  // Start PRESET NET
        btnEnd         = findViewById(R.id.btnA);      // END
        // ----------------------------------------
        btnClear     = findViewById(R.id.btnClearLog);
        btnCopy      = findViewById(R.id.btnCopyLog);

        // Toggle I/O log : on réutilise ton ancien ID
        switchIoLog  = findViewById(R.id.switchIoLog);
    }

    private void setDefaults() {
        if (edtTo   != null && isEmpty(edtTo.getText()))   edtTo.setText("0xFA");
        if (edtFrom != null && isEmpty(edtFrom.getText())) edtFrom.setText("0xFF");
        if (edtProduct != null && isEmpty(edtProduct.getText())) edtProduct.setText("1");
        if (edtPreset  != null && isEmpty(edtPreset.getText()))  edtPreset.setText("50.0");

        if (switchIoLog != null) {
            switchIoLog.setChecked(true);
            LcpLink.DUMP_TX = true;
            LcpLink.DUMP_RX = true;
        }
    }

    private void wireEvents() {
        safeSetOnClick(btnConnect, v -> requestAndOpenFirstPort());
        safeSetOnClick(btnStartOpen, v -> startOpenMode());
        safeSetOnClick(btnStartPreset, v -> startPresetNet());
        safeSetOnClick(btnEnd, v -> endGracefully());
        safeSetOnClick(btnClear, v -> { logBuf.setLength(0); runOnUiThread(() -> txtLog.setText("")); });
        safeSetOnClick(btnCopy, v -> copyLog());

        if (switchIoLog != null) {
            switchIoLog.setOnCheckedChangeListener((b, checked) -> {
                LcpLink.DUMP_TX = checked; LcpLink.DUMP_RX = checked;
                append("I/O log " + (checked ? "activé" : "désactivé") + "\n");
            });
        }
    }

    private void safeSetOnClick(View v, View.OnClickListener l){
        if (v != null) v.setOnClickListener(l);
    }

    /* ================================================================
       USB: permission & connect
       ================================================================ */
    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (!granted) {
                append("Permission USB refusée\n");
                return;
            }
            append("Permission USB accordée, ouverture...\n");
            connectPort(device);
        }
    };

    private final BroadcastReceiver usbAttachDetach = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(i.getAction())) {
                append("USB attaché — cliquez 'Connexion USB'\n");
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(i.getAction())) {
                append("USB détaché\n");
                try { if (serialPort != null) serialPort.close(); } catch(Exception ignored){}
                serialPort = null; lcpLink = null; controller = null;
            }
        }
    };

    private void requestAndOpenFirstPort() {
        try {
            UsbManager mgr = (UsbManager)getSystemService(Context.USB_SERVICE);
            List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(mgr);
            if (drivers == null || drivers.isEmpty()) {
                append("Aucun convertisseur USB‑Série détecté\n");
                return;
            }
            UsbDevice dev = drivers.get(0).getDevice();
            if (!mgr.hasPermission(dev)) {
                append("Demande de permission USB…\n");
                PendingIntent pi = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE);
                mgr.requestPermission(dev, pi);
                return;
            }
            connectPort(dev);
        } catch (Exception e) {
            append("ERREUR: " + e.getMessage() + "\n");
        }
    }

    private void connectPort(UsbDevice dev) {
        try {
            if (lcpLink != null && serialPort != null) { append("Déjà connecté.\n"); return; }

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
            serialPort.purgeHwBuffers(true, true);

            append("Port ouvert 19200 8N1\n");

            int to   = parseHexOrDefault(safe(edtTo),   0xFA);
            int from = parseHexOrDefault(safe(edtFrom), 0xFF);

            lcpLink = new LcpLink(serialPort, to, from, true);
            controller = new DeliveryController(
                lcpLink,
                new DeliveryController.DeliveryEvents() {
                    @Override public void onStateChanged(DeliveryController.State s) { append("[SDK] State=" + s + "\n"); }
                    @Override public void onFlowStarted() { append("[SDK] FLOW détecté\n"); }
                    @Override public void onFlowStopped() { append("[SDK] FLOW stoppé\n"); }
                    @Override public void onLiveSample(int ds, int dc, double gL, double nL) {
                        append(String.format("[LIVE] DS=0x%04X DC=0x%04X G=%.1f N=%.1f\n", ds, dc, gL, nL));
                    }
                    @Override public void onGuardReached() { append("[SDK] Guard atteint -> END demandé\n"); }
                    @Override public void onError(String m, Throwable t) { append("[SDK] ERREUR: " + m + (t!=null ? " / "+t.getMessage() : "") + "\n"); }
                },
                null // SingleThread executor par défaut
            );

            // RESYNC best‑effort (0x00)
            try { lcpLink.sendRecv(new byte[]{0x00}, 2000); append("[CONNECT] RESYNC 0x00 OK\n"); }
            catch (Exception e) { append("[CONNECT] RESYNC best-effort: " + e.getMessage() + "\n"); }

        } catch (Exception e) {
            append("ERREUR ouverture USB: " + e.getMessage() + "\n");
            try { if (serialPort != null) serialPort.close(); } catch(Exception ignored){}
            serialPort = null; lcpLink = null; controller = null;
        }
    }

    /* ================================================================
       Actions : Start Open / Start Preset / End
       ================================================================ */
    private void startOpenMode() {
        if (!checkReady()) return;
        int product = parseIntOrDefault(safe(edtProduct), 1);
        append(String.format("[UI] Start OPEN product=%d\n", product));

        // Démarre : set product -> clear presets -> RUN 0x00 -> WAIT_FLOW
        controller.startOpenMode(product, 20_000, 200);

        // Live loop sans guard
        ioExec.execute(() -> {
            try { Thread.sleep(600); } catch(Exception ignored){}
            controller.runLiveLoop(250, false, 0.0);
        });
    }

    private void startPresetNet() {
        if (!checkReady()) return;
        int product = parseIntOrDefault(safe(edtProduct), 1);
        double presetL = parseDoubleOrDefault(safe(edtPreset), 50.0);
        append(String.format("[UI] Start PRESET NET product=%d preset=%.1f L\n", product, presetL));

        // Démarre : set product -> #6 -> RUN 0x00 -> WAIT_FLOW
        controller.startPresetNet(product, presetL, 20_000, 200);

        // Live loop avec guard (END auto au seuil)
        ioExec.execute(() -> {
            try { Thread.sleep(600); } catch(Exception ignored){}
            controller.runLiveLoop(250, true, 0.0); // guardMargin=0.0 L (ajuste si besoin)
        });
    }

    private void endGracefully() {
        if (!checkReady()) return;
        append("[UI] END demandé\n");
        controller.endGracefully(15_000, 200);
    }

    /* ================================================================
       Utils & UI
       ================================================================ */
    private boolean checkReady() {
        if (serialPort == null || lcpLink == null || controller == null) {
            append("USB/LCP non prêt. Connectez d'abord.\n");
            return false;
        }
        return true;
    }

    private void copyLog() {
        ClipboardManager cb = (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
        if (cb == null) { append("Clipboard indisponible\n"); return; }
        cb.setPrimaryClip(ClipData.newPlainText("lcr_log", logBuf.toString()));
        append("Log copié\n");
    }

    private void append(String s) {
        runOnUiThread(() -> txtLog.append(s));
    }

    private void appendAndBuffer(String s) {
        if (s == null) return;
        if (!s.endsWith("\n")) s = s + "\n";
        logBuf.append(s);
        append(s);
    }

    private static boolean isEmpty(CharSequence cs){ return cs == null || cs.toString().trim().isEmpty(); }
    private static String safe(EditText e){ return e == null ? "" : (e.getText()==null ? "" : e.getText().toString().trim()); }
    private static int parseHexOrDefault(String s, int def){
        try { return Integer.parseInt(s.replace("0x","").replace("0X",""), 16) & 0xFF; }
        catch(Exception e){ return def; }
    }
    private static int parseIntOrDefault(String s, int def){
        try { return Integer.parseInt(s); } catch(Exception e){ return def; }
    }
    private static double parseDoubleOrDefault(String s, double def){
        try { return Double.parseDouble(s); } catch(Exception e){ return def; }
    }
}
