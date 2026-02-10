
package com.pa.lcrdemo;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.widget.TextView;

import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.pa.lcr.lcp.LcpLink;
import com.pa.lcr.lcp.DeliveryController;

import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private UsbSerialPort port;      // Ton port réel (ouvert via UsbReceiver)
    private LcpLink link;
    private DeliveryController ctrl;

    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        statusView = new TextView(this);
        statusView.setText("Initialisation…");
        setContentView(statusView);

        // ⚠️ Très important :
        // Tu dois avoir initialisé `port` avant d’appeler initLcp().
        // Exemple: ton UsbReceiver détecte l'appareil, puis appelle MainActivity.setPort()
        if (port != null) {
            initLcp();
        } else {
            statusView.setText("Port USB non initialisé (en attente de connexion)");
        }
    }

    // Appelé depuis ton UsbReceiver ou autre code USB existant
    public void setPort(UsbSerialPort p) {
        this.port = p;
        runOnUiThread(() -> statusView.setText("Port détecté, initialisation LCP…"));
        initLcp();
    }

    private void initLcp() {
        try {
            // ✔ Respect strict du protocole LCP
            // to=0xFA, from=0xFF, syncFirst=true
            link = new LcpLink(port, 0xFA, 0xFF, true);

            // DeliveryController utilise ton protocole LCP
            ctrl = new DeliveryController(link, new DeliveryEventsImpl(), Executors.newSingleThreadExecutor());

            runOnUiThread(() -> statusView.setText("LCP prêt — ping…"));

            // Petit test : ping status (#23)
            ctrl.pingStatus();

        } catch (Exception e) {
            statusView.setText("Erreur init LCP: " + e.getMessage());
        }
    }

    // Implémentation minimale des callbacks
    private class DeliveryEventsImpl implements DeliveryController.DeliveryEvents {
        @Override public void onStateChanged(DeliveryController.State s) {
            runOnUiThread(() -> statusView.setText("État: " + s));
        }

        @Override public void onFlowStarted() {}
        @Override public void onFlowStopped() {}

        @Override public void onLiveSample(int ds, int dc, double g, double n) {}

        @Override public void onProgress(DeliveryController.DeliveryProgress p) {}

        @Override public void onGuardReached() {}

        @Override public void onError(String msg, Throwable t) {
            runOnUiThread(() -> statusView.setText("Erreur: " + msg + " → " + t.getMessage()));
        }

        @Override public void onLog(String line) {
            // Tu peux logger si tu veux
            System.out.println("[LCP] " + line);
        }
    }
}
