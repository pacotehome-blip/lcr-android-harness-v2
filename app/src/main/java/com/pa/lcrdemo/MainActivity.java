
package com.pa.lcrdemo;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.widget.TextView;

import com.pa.lcr.lcp.SerialPortController;
import com.pa.lcr.lcp.LcrService;
import com.hoho.android.usbserial.driver.UsbSerialPort;

public class MainActivity extends AppCompatActivity {

    private SerialPortController serial;
    private LcrService lcr;

    private UsbSerialPort port;   // Ton port réel (déjà initialisé ailleurs)
    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        statusView = new TextView(this);
        statusView.setText("Initialisation…");
        setContentView(statusView);

        if (port != null) {
            initializeLcrLayer();
        } else {
            statusView.setText("Port USB non initialisé.");
        }
    }

    /**
     * Initialisation propre de la couche LCR-II
     */
    private void initializeLcrLayer() {
        try {
            // Adapte SerialPortController pour UsbSerialPort.read/write
            serial = new SerialPortController(
                    (buffer, timeout) -> port.read(buffer, timeout),
                    (buffer, timeout) -> port.write(buffer, timeout)
            );

            lcr = new LcrService(serial);

            testPoll();

        } catch (Exception ex) {
            statusView.setText("Erreur init LCR: " + ex.getMessage());
        }
    }

    /**
     * Test minimal : poll 0x28
     */
    private void testPoll() {
        new Thread(() -> {
            try {
                byte[] st = lcr.poll();
                runOnUiThread(() ->
                        statusView.setText("POLL=" + bytesToHex(st)));

            } catch (Exception ex) {
                runOnUiThread(() ->
                        statusView.setText("Erreur poll: " + ex.getMessage()));
            }
        }).start();
    }

    /**
     * Encode un tableau de bytes en hex (debug)
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes)
            sb.append(String.format("%02X ", b));
        return sb.toString().trim();
    }
}
