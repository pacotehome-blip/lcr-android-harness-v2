
package com.pa.lcrdemo;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.*;

import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.pa.lcr.lcp.*;

import java.util.List;

/**
 * MainActivity
 *
 * UI PURE :
 *  - dépend UNIQUEMENT de DeliveryControllerPort
 *  - ne connaît PAS le protocole LCP
 *  - ne connaît PAS LcpLink directement
 *
 * Toute interaction registre passe par DeliveryControllerPort.
 */
public class MainActivity extends AppCompatActivity {

    /* ==========================================================
     * UI
     * ========================================================== */

    private Spinner spnProducts;
    private Button btnA, btnC, btnContinue, btnFinish;
    private EditText edtPreset, edtProduct;
    private TextView txtLive, txtLog;
    private ScrollView logScroll;

    /* ==========================================================
     * Controller (PORT UNIQUEMENT)
     * ========================================================== */

    private DeliveryControllerPort controller;

    /* ==========================================================
     * USB
     * ========================================================== */

    private UsbSerialPort usbPort;

    /* ==========================================================
     * Helpers UI
     * ========================================================== */

    private boolean suppressProductSelection = false;
    private boolean userTouchedSpinner = false;

    private final StringBuilder logBuf = new StringBuilder(16_000);
    private final Handler ui = new Handler(Looper.getMainLooper());

    /* ==========================================================
     * Lifecycle
     * ========================================================== */

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindUi();
        wireUi();

        log("UI prête — attente USB");
    }

    /* ==========================================================
     * UI binding
     * ========================================================== */

    private void bindUi() {
        spnProducts  = findViewById(R.id.spnProducts);
        btnA         = findViewById(R.id.btnA);
        btnC         = findViewById(R.id.btnC);
        btnContinue  = findViewById(R.id.btnContinue);
        btnFinish    = findViewById(R.id.btnFinish);

        edtPreset    = findViewById(R.id.edtPreset);
        edtProduct   = findViewById(R.id.edtProduct);

        txtLive      = findViewById(R.id.txtLive);
        txtLog       = findViewById(R.id.txtLog);
        logScroll    = findViewById(R.id.logScroll);
    }

    /* ==========================================================
     * UI wiring
     * ========================================================== */

    private void wireUi() {

        /* ---------- Spinner produits ---------- */

        spnProducts.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                userTouchedSpinner = true;
            }
            return false;
        });

        spnProducts.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (controller == null) return;
                if (suppressProductSelection) return;
                if (!userTouchedSpinner) return;

                userTouchedSpinner = false;

                ProductUiItem item =
                        (ProductUiItem) spnProducts.getSelectedItem();
                if (item != null) {
                    controller.selectProduct(item.product1);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // no-op
            }
        });

        /* ---------- Boutons ---------- */

        btnA.setOnClickListener(v -> {
            if (controller != null) {
                controller.refreshProducts();
            }
        });

        btnC.setOnClickListener(v -> {
            if (controller == null) return;

            int product = readProduct();
            double preset = readPreset();

            controller.startDelivery(product, preset);
        });

        btnContinue.setOnClickListener(v -> {
            if (controller != null) {
                controller.resumeIfPaused();
            }
        });

        btnFinish.setOnClickListener(v -> {
            if (controller != null) {
                controller.endDelivery();
            }
        });
    }

    /* ==========================================================
     * USB callbacks (appelés par UsbReceiver)
     * ========================================================== */

    /**
     * Appelé par UsbReceiver quand le port série est prêt.
     */
    public void onUsbPortReady(UsbSerialPort port) {
        this.usbPort = port;
        log("USB prêt");

        // 1 port = 1 registre (pour l’instant)
        LcpLink link = new LcpLink(port, 0xFA, 0xFF, true);
        controller = new DeliveryController(link);

        controller.setListener(new DeliveryControllerPort.Listener() {

            @Override
            public void onStateChanged(DeliveryState state) {
                ui.post(() -> txtLive.setText("STATE: " + state));
            }

            @Override
            public void onProductsUpdated(
                    List<ProductUiItem> products,
                    int activeIndex0
            ) {
                ui.post(() -> {
                    suppressProductSelection = true;

                    ArrayAdapter<ProductUiItem> adapter =
                            new ArrayAdapter<>(
                                    MainActivity.this,
                                    android.R.layout.simple_spinner_item,
                                    products
                            );
                    adapter.setDropDownViewResource(
                            android.R.layout.simple_spinner_dropdown_item
                    );

                    spnProducts.setAdapter(adapter);
                    spnProducts.setSelection(activeIndex0);

                    suppressProductSelection = false;
                });
            }

            @Override
            public void onLog(String message) {
                log(message);
            }

            @Override
            public void onError(String context, Throwable error) {
                log("ERR[" + context + "] " + error.getMessage());
            }
        });

        controller.initialize();
    }

    /**
     * Appelé par UsbReceiver lors du detach USB.
     */
    public void onUsbDetached() {
        log("USB débranché");

        if (controller != null) {
            controller.shutdown();
            controller = null;
        }

        usbPort = null;
    }

    /* ==========================================================
     * Utils UI
     * ========================================================== */

    private int readProduct() {
        try {
            int v = Integer.parseInt(
                    edtProduct.getText().toString().trim()
            );
            if (v >= 1 && v <= 16) return v;
        } catch (Exception ignore) {
        }

        ProductUiItem it =
                (ProductUiItem) spnProducts.getSelectedItem();
        return it != null ? it.product1 : 1;
    }

    private double readPreset() {
        try {
            return Double.parseDouble(
                    edtPreset.getText().toString().trim()
            );
        } catch (Exception e) {
            return 0.0;
        }
    }

    private void log(String s) {
        ui.post(() -> {
            logBuf.append(s).append('\n');
            txtLog.setText(logBuf.toString());
            logScroll.post(
                    () -> logScroll.fullScroll(View.FOCUS_DOWN)
            );
        });
    }
}
