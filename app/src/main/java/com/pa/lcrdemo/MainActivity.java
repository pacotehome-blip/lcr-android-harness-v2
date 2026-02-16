
package com.pa.lcrdemo;

import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.pa.lcr.lcp.DeliveryController;
import com.pa.lcr.lcp.LcpLink;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MainActivity — intégration minimale et compilable
 * - Aligne correctement DeliveryController + LcpLink
 * - Implémente DeliveryEvents (API réelle)
 * - Reçoit UsbSerialPort via UsbReceiver.setPort()
 * - Ne démarre AUCUNE livraison automatiquement
 */
public class MainActivity extends AppCompatActivity
        implements DeliveryController.DeliveryEvents {

    // ===================== UI =====================
    private TextView txtLive;
    private TextView txtQtyNet;
    private TextView txtQtyGross;
    private Button btnConnect;

    // ===================== LCP =====================
    private UsbSerialPort usbPort;
    private LcpLink lcp;
    private DeliveryController delivery;
    private ExecutorService exec;

    // ===================== Lifecycle =====================
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        txtLive = findViewById(R.id.txtLive);
        txtQtyNet = findViewById(R.id.txtQtyNet);
        txtQtyGross = findViewById(R.id.txtQtyGross);
        btnConnect = findViewById(R.id.btnConnect);

        exec = Executors.newSingleThreadExecutor();

        btnConnect.setOnClickListener(v -> initLcp());

        txtLive.setText("READY");
    }

    // ===================== USB =====================
    /**
     * Appelé par UsbReceiver quand le port USB est disponible.
     */
    public void setPort(UsbSerialPort port) {
        this.usbPort = port;
        runOnUiThread(() -> txtLive.setText("USB PORT READY"));
    }

    // ===================== INIT LCP =====================
    private void initLcp() {
        if (usbPort == null) {
            txtLive.setText("NO USB PORT");
            return;
        }
        if (lcp != null) {
            txtLive.setText("LCP ALREADY INIT");
            return;
        }

        /*
         * Adresses LCR usuelles :
         *   toAddr   = 0x01 (registre)
         *   fromAddr = 0x00 (PC / Android)
         * syncFirst = true (recommandé terrain)
         */
        lcp = new LcpLink(
                usbPort,
                0x01,
                0x00,
                true
        );

        delivery = new DeliveryController(
                lcp,
                this,   // DeliveryEvents
                exec
        );

        txtLive.setText("LCP READY");
    }

    // ===================== DeliveryEvents =====================

    @Override
    public void onStateChanged(DeliveryController.State s) {
        runOnUiThread(() -> txtLive.setText("STATE=" + s));
    }

    @Override
    public void onProgress(DeliveryController.DeliveryProgress p) {
        runOnUiThread(() -> {
            txtQtyNet.setText("NET: " + p.deliveredNetL);
            txtQtyGross.setText("GROSS: " + p.deliveredGrossL);
        });
    }

    @Override
    public void onFlowStarted() {
        // volontairement vide
    }

    @Override
    public void onFlowStopped() {
        // volontairement vide
    }

    @Override
    public void onTicketNumber(int ticketNumber) {
        // volontairement vide
    }

    @Override
    public void onTicketRequired(int mode) {
        // volontairement vide
    }

    @Override
    public void onPrinterStatus(LcpLink.MachineStatusEx ms, boolean ticketPending) {
        // volontairement vide
    }

    @Override
    public void onOperatorAlert(DeliveryController.OperatorAlert alert) {
        runOnUiThread(() ->
                txtLive.setText("ALERT: " + alert.title)
        );
    }

    @Override
    public void onLog(String line) {
        // hook possible vers un log scrollable plus tard
    }

    @Override
    public void onError(String msg, Throwable t) {
        runOnUiThread(() -> txtLive.setText("ERROR: " + msg));
    }

    // ===================== Cleanup =====================
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (exec != null) {
            exec.shutdownNow();
            exec = null;
        }
    }
}
