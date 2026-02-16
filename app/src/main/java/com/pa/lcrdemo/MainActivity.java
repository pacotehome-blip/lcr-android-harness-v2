
package com.example.lcrharness;

import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity
        implements DeliveryController.ProgressListener {

    // ---------------- UI ----------------
    private TextView txtLive;
    private TextView txtQtyNet;
    private TextView txtQtyGross;

    private Button btnConnect;

    // ---------------- LCP / Delivery ----------------
    private LcpLink lcp;
    private DeliveryController deliveryController;

    // ---------------- Qty display config (from LCR) ----------------
    // Field #38 / #39
    private int qtyUnits = 1;      // default: Litres
    private int qtyDecimals = 0;   // default: 2 decimals

    // ---------------- Lifecycle ----------------
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindUI();
        bindActions();
    }

    private void bindUI() {
        txtLive = findViewById(R.id.txtLive);
        txtQtyNet = findViewById(R.id.txtQtyNet);
        txtQtyGross = findViewById(R.id.txtQtyGross);

        btnConnect = findViewById(R.id.btnConnect);
    }

    private void bindActions() {
        btnConnect.setOnClickListener(v -> connectLcp());
    }

    // ---------------- LCP connection ----------------
    private void connectLcp() {
        if (lcp != null && lcp.isConnected()) {
            return;
        }

        lcp = new LcpLink(/* paramètres existants */);
        lcp.connect(new LcpLink.ConnectListener() {
            @Override
            public void onConnected() {
                onLcpConnected();
            }

            @Override
            public void onDisconnected() {
                onLcpDisconnected();
            }

            @Override
            public void onError(String err) {
                // log / toast si déjà présent dans ton code
            }
        });
    }

    private void onLcpConnected() {
        // Lire la config d’affichage quantité (UNIT + DECIMALS)
        readQtyDisplayConfig();

        // Initialiser le DeliveryController
        deliveryController = new DeliveryController(lcp);
        deliveryController.setProgressListener(this);
    }

    private void onLcpDisconnected() {
        if (deliveryController != null) {
            deliveryController.setProgressListener(null);
            deliveryController = null;
        }
    }

    // ---------------- Field #38 / #39 ----------------
    private void readQtyDisplayConfig() {
        if (lcp == null) return;

        // Field #38 : QtyUnits
        lcp.getField(38, (rc, devStatus, data) -> {
            if (rc == 0 && data != null && data.length >= 1) {
                qtyUnits = data[0] & 0xFF;
            }
        });

        // Field #39 : Decimals
        lcp.getField(39, (rc, devStatus, data) -> {
            if (rc == 0 && data != null && data.length >= 1) {
                qtyDecimals = data[0] & 0xFF;
            }
        });
    }

    // ---------------- Helpers (STRICT champ 38 / 39) ----------------
    private static String qtyUnitLabel(int qtyUnits) {
        switch (qtyUnits) {
            case 0: return "gal";
            case 1: return "L";
            case 2: return "m³";
            case 3: return "lb";
            case 4: return "kg";
            case 5: return "bbl";
            default: return "";
        }
    }

    private static String volumeFormat(int decimals) {
        switch (decimals) {
            case 0: return "%.2f";
            case 1: return "%.1f";
            case 2: return "%.0f";
            case 3: return "%.3f";
            default: return "%.2f";
        }
    }

    // ---------------- LIVE delivery ----------------
    @Override
    public void onProgress(DeliveryController.DeliveryProgress p) {

        final double deliveredNet = p.deliveredNetL;
        final double deliveredGross = p.deliveredGrossL;

        final String unit = qtyUnitLabel(qtyUnits);
        final String fmt = volumeFormat(qtyDecimals);

        // Ligne LIVE technique (overwrite)
        final String liveLine = String.format(
                "NET=%s %s | GROSS=%s %s",
                String.format(fmt, deliveredNet), unit,
                String.format(fmt, deliveredGross), unit
        );

        runOnUiThread(() -> {
            // Debug / technique
            if (txtLive != null) {
                txtLive.setText(liveLine);
            }

            // UI opérateur (big digits)
            if (txtQtyNet != null) {
                txtQtyNet.setText(
                        String.format("LIVRÉ NET: " + fmt + " %s", deliveredNet, unit)
                );
            }

            if (txtQtyGross != null) {
                txtQtyGross.setText(
                        String.format("LIVRÉ GROSS: " + fmt + " %s", deliveredGross, unit)
                );
            }
        });
    }

    // ---------------- Cleanup ----------------
    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (deliveryController != null) {
            deliveryController.setProgressListener(null);
            deliveryController = null;
        }

        if (lcp != null) {
            lcp.disconnect();
            lcp = null;
        }
    }
}
