
package com.pa.lcrdemo;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.widget.TextView;

import com.pa.lcr.lcp.SerialPortController;
import com.pa.lcr.lcp.LcrService;

// ⚠️ Mets ici ton vrai type de port USB
// Exemple : import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialPort;

public class MainActivity extends AppCompatActivity {

    private SerialPortController serial;
    private LcrService lcr;

    private UsbSerialPort port;    // ton port USB existant
    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusView = findViewById(R.id.statusView);

        // ⚠️ Ici ton code EXISTANT ouvre déjà le port USB (ne pas toucher)
        // Donc port devrait être déjà initialisé quelque part avant testPoll()

        if (port != null) {
            initializeLcrLayer();
        } else {
            statusView.setText("Port USB non initialisé");
        }
    }

    private void initializeLcrLayer() {
        try {
            serial = new SerialPortController(
                    port.getInputStream(),
                    port.getOutputStream()
            );

            lcr = new LcrService(serial);

            testPoll();

        } catch (Exception ex) {
            statusView.setText("Erreur init LCR: " + ex.getMessage());
        }
    }

    private void testPoll() {
        new Thread(() -> {
            try {
                byte[] st = lcr.poll();
                runOnUiThread(() -> statusView.setText("POLL=" + bytesToHex(st)));

            } catch (Exception ex) {
                runOnUiThread(() -> statusView.setText("Erreur LCR: " + ex.getMessage()));
            }
        }).start();
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes)
            sb.append(String.format("%02X ", b));
        return sb.toString().trim();
    }
}
