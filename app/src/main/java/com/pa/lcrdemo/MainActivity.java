
package com.pa.lcrdemo;

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
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import com.pa.lcr.lcp.LcpLink;
import com.pa.lcr.lcp.DeliveryController;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String ACTION_USB_PERMISSION = "com.pa.lcrdemo.USB_PERMISSION";

    // UI
    private TextView txtLog;
    private EditText edtTo, edtFrom, edtProduct, edtPreset;
    private Button btnConnect, btnStartOpen, btnEnd;
    private Button btnClear, btnCopy;
    private Button btnPing, btnContinue, btnFinish;
    private CheckBox switchIoLog;

    // USB & SDK
    private UsbSerialPort serialPort;
    private LcpLink lcpLink;               // UNIQUEMENT passé au SDK
    private DeliveryController controller;

    // Utils
    private final StringBuilder logBuf = new StringBuilder(64 * 1024);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Impression/fin
    private volatile boolean shouldPrintAtEnd = false;

    // Derniers totaux (pour ticket)
    private volatile double lastGrossL = 0.0, lastNetL = 0.0;

    // USB detach
    private volatile Integer currentUsbDeviceId = null;
    private volatile long lastDetachHandledAt = 0L;
    private static final long DETACH_DEBOUNCE_MS = 1500L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews(); setDefaults(); wireEvents();

        // Echo des TX/RX (diagnostic) — aucune I/O UI
        LcpLink.setLogger(this::appendAndBuffer);

        registerReceiver(usbPermissionReceiver, new IntentFilter(ACTION_USB_PERMISSION));
        IntentFilter usb = new IntentFilter();
        usb.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        usb.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(usbAttachDetach, usb);

        append("Prêt. Branchez le LCR puis cliquez 'Connexion USB'.\n");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(usbPermissionReceiver); } catch(Exception ignored){}
        try { unregisterReceiver(usbAttachDetach); } catch(Exception ignored){}
        try { if (serialPort != null) serialPort.close(); } catch(Exception ignored){}
    }

    /* ============================== Views ============================== */

    private void bindViews() {
        txtLog       = findViewById(R.id.txtLog);
        edtTo        = findViewById(R.id.edtTo);
        edtFrom      = findViewById(R.id.edtFrom);
        edtProduct   = findViewById(R.id.edtProduct);
        edtPreset    = findViewById(R.id.edtPreset);

        btnConnect   = findViewById(R.id.btnConnect);
        btnStartOpen = findViewById(R.id.btnC);
        btnEnd       = findViewById(R.id.btnA);

        btnClear     = findViewById(R.id.btnClearLog);
        btnCopy      = findViewById(R.id.btnCopyLog);
        switchIoLog  = findViewById(R.id.switchIoLog);

        btnPing      = findViewById(R.id.btnB);
        btnContinue  = findViewById(R.id.btnContinue);
        btnFinish    = findViewById(R.id.btnFinish);

        try { txtLog.setTextIsSelectable(true); } catch(Exception ignored) {}
        if (btnContinue != null) btnContinue.setEnabled(false);
        if (btnFinish   != null) btnFinish.setEnabled(false);
    }

    private void setDefaults() {
        if (edtTo != null && isEmpty(edtTo.getText()))     edtTo.setText("0xFA");
        if (edtFrom != null && isEmpty(edtFrom.getText())) edtFrom.setText("0xFF");
        if (edtProduct != null && isEmpty(edtProduct.getText())) edtProduct.setText("1");
        if (edtPreset  != null && isEmpty(edtPreset.getText()))  edtPreset.setText("50.0");

        if (switchIoLog != null) {
            switchIoLog.setChecked(true);
            LcpLink.DUMP_TX = true; LcpLink.DUMP_RX = true;
        }
    }

    private void wireEvents() {
        safeSetOnClick(btnConnect, v -> requestAndOpenFirstPort());
        safeSetOnClick(btnStartOpen, v -> startOpenMode());
        safeSetOnClick(btnEnd,       v -> endGracefully());
        safeSetOnClick(btnClear,     v -> { logBuf.setLength(0); runOnUiThread(() -> txtLog.setText("")); });
        safeSetOnClick(btnCopy,      v -> copyLog());

        safeSetOnClick(btnPing,      v -> { if (controller != null) controller.pingStatus(); });
        safeSetOnClick(btnContinue,  v -> continueDelivery());
        safeSetOnClick(btnFinish,    v -> finishDelivery());

        if (switchIoLog != null) {
            switchIoLog.setOnCheckedChangeListener((b, checked) -> {
                LcpLink.DUMP_TX = checked; LcpLink.DUMP_RX = checked;
                append("I/O log " + (checked ? "activé" : "désactivé") + "\n");
            });
        }
    }

    private void safeSetOnClick(View v, View.OnClickListener l){ if (v != null) v.setOnClickListener(l); }

    /* ============================== USB ============================== */

    private final BroadcastReceiver usbPermissionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (!granted) { append("Permission USB refusée\n"); return; }
            append("Permission USB accordée, ouverture...\n");
            connectPort(device);
        }
    };

    private final BroadcastReceiver usbAttachDetach = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String action = i.getAction();
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                append("USB attaché — cliquez 'Connexion USB'\n");
                return;
            }
            if (!UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) return;

            UsbDevice det = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            int detId = (det != null ? det.getDeviceId() : -1);
            append(String.format("USB DETACHED reçu (devId=%d)\n", detId));

            Integer curId = currentUsbDeviceId;
            if (curId == null || det == null || detId != curId) return;

            long now = System.currentTimeMillis();
            if (now - lastDetachHandledAt < DETACH_DEBOUNCE_MS) return;
            lastDetachHandledAt = now;

            append("DETACHED : arrêt SDK + fermeture port\n");
            try { if (controller != null) controller.requestStop("usb detached"); } catch(Exception ignored){}
            cleanupUsb();
        }
    };

    private void requestAndOpenFirstPort() {
        try {
            UsbManager mgr = (UsbManager)getSystemService(Context.USB_SERVICE);
            List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(mgr);
            if (drivers == null || drivers.isEmpty()) { append("Aucun convertisseur USB‑Série détecté\n"); return; }
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
            UsbManager mgr = (UsbManager)getSystemService(Context.USB_SERVICE);
            UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(dev);
            if (driver == null) { append("Pas de driver USB‑Série compatible\n"); return; }

            UsbDeviceConnection conn = mgr.openDevice(dev);
            if (conn == null) { append("Impossible d’ouvrir le périphérique USB\n"); return; }

            serialPort = driver.getPorts().get(0);
            serialPort.open(conn);
            serialPort.setParameters(19200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
            serialPort.setRTS(false); serialPort.setDTR(false);
            serialPort.purgeHwBuffers(true, true);

            currentUsbDeviceId = (driver.getDevice() != null ? driver.getDevice().getDeviceId() : dev.getDeviceId());
            append("Port ouvert 19200 8N1\n");

            int to   = parseHexOrDefault(safe(edtTo),   0xFA);
            int from = parseHexOrDefault(safe(edtFrom), 0xFF);

            // Créer le LcpLink → DONNER AU SDK UNIQUEMENT
            lcpLink = new LcpLink(serialPort, to, from, true);

            // Instancier le SDK
            controller = new DeliveryController(
                    lcpLink,
                    new DeliveryController.DeliveryEvents() {
                        @Override public void onStateChanged(DeliveryController.State s) {
                            append("[SDK] State=" + s + "\n");
                            runOnUiThread(() -> {
                                if (s == DeliveryController.State.ENDED || s == DeliveryController.State.ERROR) {
                                    if (btnFinish   != null) btnFinish.setEnabled(false);
                                    if (btnContinue != null) btnContinue.setEnabled(false);
                                    if (s == DeliveryController.State.ENDED && shouldPrintAtEnd) {
                                        shouldPrintAtEnd = false;
                                        controller.printTicketText(buildBasicTicket(), 90, 5000);
                                    }
                                }
                            });
                        }

                        @Override public void onFlowStarted() { append("[SDK] FLOW détecté\n"); }
                        @Override public void onFlowStopped() { append("[SDK] FLOW stoppé\n"); }

                        @Override public void onLiveSample(int ds, int dc, double gL, double nL) {
                            lastGrossL = gL; lastNetL = nL;
                            append(String.format("[LIVE] DS=0x%04X DC=0x%04X G=%.3f N=%.3f\n", ds, dc, gL, nL));
                        }

                        @Override public void onProgress(DeliveryController.DeliveryProgress p) {
                            append(String.format(
                                    "[PROG] t=%.1fs G=%.3f(+%.3f) N=%.3f(+%.3f) flow=%s stalled=%s DS=0x%04X DC=0x%04X\n",
                                    p.tSinceStartMs/1000.0,
                                    p.grossL, p.dGrossL, p.netL, p.dNetL,
                                    p.flowActive ? "ON" : "OFF",
                                    p.stalled   ? "YES": "NO",
                                    p.ds, p.dc
                            ));
                            runOnUiThread(() -> {
                                if (p.stalled) {
                                    if (btnContinue != null) btnContinue.setEnabled(true);
                                    if (btnFinish   != null) btnFinish.setEnabled(true);
                                } else {
                                    if (btnContinue != null) btnContinue.setEnabled(false);
                                    if (btnFinish   != null) btnFinish.setEnabled(true);
                                }
                            });
                        }

                        @Override public void onGuardReached() { append("[SDK] Guard atteint\n"); }

                        @Override public void onError(String m, Throwable t) {
                            append("[SDK] ERREUR: " + m + (t != null ? (" / " + t.getMessage()) : "") + "\n");
                            runOnUiThread(() -> {
                                if (btnFinish   != null) btnFinish.setEnabled(false);
                                if (btnContinue != null) btnContinue.setEnabled(false);
                            });
                        }

                        @Override public void onLog(String line) {
                            append("[SDK] " + line + "\n");
                        }
                    },
                    Executors.newSingleThreadExecutor()
            );

            // ✨ RESYNC via SDK (UI ne fait AUCUN sendRecv ici)
            controller.resyncGetProductId();

        } catch (Exception e) {
            append("ERREUR ouverture USB: " + e.getMessage() + "\n");
            try { if (serialPort != null) serialPort.close(); } catch(Exception ignored){}
            serialPort = null; lcpLink = null; controller = null;
            currentUsbDeviceId = null;
        }
    }

    private void cleanupUsb() {
        try { if (serialPort != null) serialPort.close(); } catch(Exception ignored){}
        serialPort = null; lcpLink = null; controller = null;
        currentUsbDeviceId = null;
        append("USB nettoyé (port fermé)\n");
    }

    /* ============================== Actions UI ============================== */

    private void startOpenMode() {
        if (!checkReady()) return;
        int product = parseIntOrDefault(safe(edtProduct), 1);
        append(String.format("[UI] Start OPEN product=%d\n", product));
        controller.startOpenMode(product, 20_000, 200); // le SDK démarre sa live-loop
    }

    private void endGracefully() {
        if (!checkReady()) return;
        append("[UI] END demandé (A)\n");
        shouldPrintAtEnd = false;                // "A" n'imprime pas
        controller.endGracefully(15_000, 200);   // SDK gère 0x24 et le poll de fin
    }

    private void continueDelivery() {
        append("[UI] Continuer (pas d'impression)\n");
        if (btnContinue != null) btnContinue.setEnabled(false);
        // Rien d'autre : la live-loop continue côté SDK
    }

    private void finishDelivery() {
        if (!checkReady()) return;
        append("[UI] Terminé demandé\n");
        shouldPrintAtEnd = true;                 // imprimera après ENDED
        controller.endGracefully(15_000, 200);
        if (btnContinue != null) btnContinue.setEnabled(false);
        if (btnFinish   != null) btnFinish.setEnabled(false);
    }

    /* ============================== Ticket ============================== */

    private String buildBasicTicket() {
        String ts = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(new Date());
        StringBuilder sb = new StringBuilder();
        sb.append("Delivery Ticket\n\n");
        sb.append("Time: ").append(ts).append('\n');
        sb.append(String.format("GrossTotal: %.3f\n", lastGrossL));
        sb.append(String.format("NetTotal  : %.3f\n", lastNetL));
        return sb.toString();
    }

    /* ============================== Utils ============================== */

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
        runOnUiThread(() -> {
            boolean hasSelection = false;
            try {
                int selStart = txtLog.getSelectionStart();
                int selEnd   = txtLog.getSelectionEnd();
                hasSelection = (selStart != selEnd);
            } catch (Exception ignored) {}
            txtLog.append(s);
            if (!hasSelection) {
                ScrollView sv = findViewById(R.id.logScroll);
                if (sv != null) sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));
            }
        });
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
}
