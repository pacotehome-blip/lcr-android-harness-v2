
package com.pa.lcrdemo;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.pa.lcr.lcp.LcpLink;
import com.pa.lcr.lcp.DeliveryController;

import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    // UI
    private EditText edtTo, edtFrom, edtProduct, edtPreset;
    private Button btnCopyLog, btnClearLog, btnConnect;
    private Button btnA, btnB, btnC;
    private Button btnContinue, btnFinish;
    private CheckBox switchIoLog;

    private TextView txtLog;
    private ScrollView logScroll;

    // LCP
    private UsbSerialPort port = null;   // Injecté via UsbReceiver
    private LcpLink link;
    private DeliveryController ctrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindUI();
        installHandlers();

        switchIoLog.setChecked(true);
        log("Prêt. En attente du port USB…");
    }

    /* ======================================================
       USBReceiver appellera ceci quand le port est ouvert
       ====================================================== */
    public void setPort(UsbSerialPort p) {
        this.port = p;
        log("USB détecté: port ouvert");
    }

    /* ======================================================
       Trouve tous les contrôles du XML
       ====================================================== */
    private void bindUI() {
        edtTo = findViewById(R.id.edtTo);
        edtFrom = findViewById(R.id.edtFrom);
        edtProduct = findViewById(R.id.edtProduct);
        edtPreset = findViewById(R.id.edtPreset);

        btnCopyLog = findViewById(R.id.btnCopyLog);
        btnClearLog = findViewById(R.id.btnClearLog);
        btnConnect = findViewById(R.id.btnConnect);

        btnA = findViewById(R.id.btnA);
        btnB = findViewById(R.id.btnB);
        btnC = findViewById(R.id.btnC);

        btnContinue = findViewById(R.id.btnContinue);
        btnFinish = findViewById(R.id.btnFinish);

        switchIoLog = findViewById(R.id.switchIoLog);

        txtLog = findViewById(R.id.txtLog);
        logScroll = findViewById(R.id.logScroll);
    }

    /* ======================================================
       Attache les évènements UI
       ====================================================== */
    private void installHandlers() {

        // 1) Copier log
        btnCopyLog.setOnClickListener(v -> {
            ClipboardManager clip =
                    (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData data = ClipData.newPlainText("log", txtLog.getText().toString());
            clip.setPrimaryClip(data);
            log("Log copié.");
        });

        // 2) Effacer log
        btnClearLog.setOnClickListener(v -> txtLog.setText(""));

        // 3) Connecter USB + init LCP
        btnConnect.setOnClickListener(v -> initLcp());

        // 4) Bouton A : RESET / END DELIVERY
        btnA.setOnClickListener(v -> {
            if (ctrl == null) { log("Pas connecté."); return; }
            log("A: END (reset)");
            ctrl.endGracefully(5000, 200);
        });

        // 5) Bouton B : PING (#23)
        btnB.setOnClickListener(v -> {
            if (ctrl == null) { log("Pas connecté."); return; }
            log("PING (#23)");
            ctrl.pingStatus();
        });

        // 6) Bouton C : START DELIVERY
        btnC.setOnClickListener(v -> {
            if (ctrl == null) { log("Pas connecté."); return; }
            int product = readInt(edtProduct, 1);
            double preset = readDouble(edtPreset, 0.0);
            log("C: Start Delivery product=" + product + " preset=" + preset);
            ctrl.startOpenMode(product, 5000, 200);
            enableLiveButtons(true);
        });

        // 7) Continuer (reactiver loop)
        btnContinue.setOnClickListener(v -> {
            if (ctrl == null) return;
            log("Continuer...");
            ctrl.startLiveLoop(200);
        });

        // 8) Terminé (fin de session)
        btnFinish.setOnClickListener(v -> {
            if (ctrl == null) return;
            log("Terminé.");
            ctrl.endGracefully(5000, 200);
            enableLiveButtons(false);
        });
    }

    /* ======================================================
       Init du protocole LCP
       ====================================================== */
    private void initLcp() {
        if (port == null) {
            log("ERR: Port USB non initialisé.");
            return;
        }

        try {
            int to = parseHex(edtTo, 0xFA);
            int from = parseHex(edtFrom, 0xFF);

            log(String.format("Init LCP to=0x%02X from=0x%02X", to, from));

            link = new LcpLink(port, to, from, true);
            ctrl = new DeliveryController(link, new DeliveryEventsImpl(),
                    Executors.newSingleThreadExecutor());

            log("LCP prêt.");

            ctrl.pingStatus();

        } catch (Exception e) {
            log("Erreur init LCP: " + e.getMessage());
        }
    }

    /* ======================================================
       Helpers
       ====================================================== */

    private void log(String s) {
        if (!switchIoLog.isChecked()) return;

        runOnUiThread(() -> {
            txtLog.append(s + "\n");
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void enableLiveButtons(boolean en) {
        btnContinue.setEnabled(en);
        btnFinish.setEnabled(en);
    }

    private int parseHex(EditText edt, int def) {
        try {
            String t = edt.getText().toString().trim();
            if (t.startsWith("0x") || t.startsWith("0X"))
                return Integer.parseInt(t.substring(2), 16) & 0xFF;
            if (t.length() > 0)
                return Integer.parseInt(t, 16) & 0xFF;
        } catch (Exception ignored) {}
        return def;
    }

    private int readInt(EditText edt, int def) {
        try { return Integer.parseInt(edt.getText().toString().trim()); }
        catch(Exception ignored){ return def; }
    }

    private double readDouble(EditText edt, double def) {
        try { return Double.parseDouble(edt.getText().toString().trim()); }
        catch(Exception ignored){ return def; }
    }

    /* ======================================================
       EVENTS
       ====================================================== */
    private class DeliveryEventsImpl implements DeliveryController.DeliveryEvents {

        @Override public void onStateChanged(DeliveryController.State s) {
            log("État=" + s);
        }

        @Override public void onFlowStarted() { log("Flow START"); }
        @Override public void onFlowStopped() { log("Flow STOP"); }

        @Override
        public void onLiveSample(int ds, int dc, double g, double n) {
            log(String.format("LIVE ds=%04X dc=%04X G=%.1f N=%.1f", ds, dc, g, n));
        }

        @Override
        public void onProgress(DeliveryController.DeliveryProgress p) {
            log(String.format("PROG t=%dms G=%.1f N=%.1f dG=%.1f dN=%.1f",
                    p.tSinceStartMs, p.grossL, p.netL, p.dGrossL, p.dNetL));
        }

        @Override public void onGuardReached() { log("GUARD REACHED"); }

        @Override
        public void onError(String msg, Throwable t) {
            log("ERR[" + msg + "] " + t.getMessage());
        }

        @Override
        public void onLog(String line) {
            log("[LCP] " + line);
        }
    }
}
