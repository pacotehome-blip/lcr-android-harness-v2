
package com.pa.lcrdemo;

import android.content.Context;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.widget.*;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.pa.lcr.lcp.*;
import com.pa.lcr.lcp.log.LogBus;
import com.pa.lcr.lcp.storage.DeliveryLogStore;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RegisterTabFragment extends Fragment {

    private static final String ARG_NODE = "node";
    private static final String ARG_FROM = "from";

    public static RegisterTabFragment newInstance(int node, int from) {
        RegisterTabFragment f = new RegisterTabFragment();
        Bundle b = new Bundle();
        b.putInt(ARG_NODE, node);
        b.putInt(ARG_FROM, from);
        f.setArguments(b);
        return f;
    }

    private int node = 250;
    private int from = 255;

    private TextView txtLcrNode, txtFrom, txtSerialId, txtTicketNo, txtTicketPending,
            txtLive, txtQtyNet, txtQtyGross, txtDeliveryUid;

    private Spinner spnProduct;
    private EditText edtPreset;

    private Button btnConnect, btnA, btnB, btnC, btnContinue, btnFinish;

    // Log (vue filtrée par tab)
    private CheckBox cbShowLog, cbTxRx, cbLogTs;
    private View logPanel;
    private TextView txtLog;
    private ScrollView logScroll;
    private Button btnClearLog, btnCopyLog, btnScrollDown;

    private boolean logTsEnabled = false;

    // Clear local view (ne wipe pas le log global)
    private long logViewSinceMs = 0L;

    // B2: auto-connect API-like (une seule fois par instance)
    private boolean autoConnectDone = false;

    private UsbManager usbManager;
    private DeliveryLogStore store;

    private LcpLink link;
    private DeliveryController controller;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    private final LogBus.Listener logListener = e -> {
        // Ne rafraîchir que si notre node est concerné et que le panneau est visible
        if (e == null) return;
        if (e.node == null || e.node != node) return;
        if (cbShowLog != null && !cbShowLog.isChecked()) return;
        refreshLogView();
    };

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        Bundle a = getArguments();
        if (a != null) {
            node = a.getInt(ARG_NODE, 250);
            from = a.getInt(ARG_FROM, 255);
        }
        usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);

        // Store unique (UI) pour tracer aussi des events TAB_AUTO_CONNECT
        store = new DeliveryLogStore(context.getApplicationContext());
        store.purgeOlderThanDaysAsync(7);
    }

    @Override
    public void onStart() {
        super.onStart();
        LogBus.addListener(logListener);
    }

    @Override
    public void onStop() {
        LogBus.removeListener(logListener);
        super.onStop();
    }

    @Override
    public void onResume() {
        super.onResume();
        // B2: auto-connect API-like au moment où le tab devient actif (une seule fois)
        if (autoConnectDone) return;
        autoConnectDone = true;
        ui.post(this::autoConnectLikeApi);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        try { bg.shutdownNow(); } catch (Exception ignored) {}
        try { if (controller != null) controller.shutdown(false); } catch (Exception ignored) {}
        controller = null;
        link = null;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_register_tab, container, false);
        bindUi(v);
        initUi();
        wireUi();
        return v;
    }

    private void bindUi(View v) {
        txtLcrNode = v.findViewById(R.id.txtLcrNode);
        txtFrom = v.findViewById(R.id.txtFrom);
        txtSerialId = v.findViewById(R.id.txtSerialId);
        txtTicketNo = v.findViewById(R.id.txtTicketNo);
        txtTicketPending = v.findViewById(R.id.txtTicketPending);

        spnProduct = v.findViewById(R.id.spnProduct);
        edtPreset = v.findViewById(R.id.edtPreset);

        btnConnect = v.findViewById(R.id.btnConnectTab);
        btnA = v.findViewById(R.id.btnA);
        btnB = v.findViewById(R.id.btnB);
        btnC = v.findViewById(R.id.btnC);
        btnContinue = v.findViewById(R.id.btnContinue);
        btnFinish = v.findViewById(R.id.btnFinish);

        txtLive = v.findViewById(R.id.txtLive);
        txtQtyNet = v.findViewById(R.id.txtQtyNet);
        txtQtyGross = v.findViewById(R.id.txtQtyGross);
        txtDeliveryUid = v.findViewById(R.id.txtDeliveryUid);

        cbShowLog = v.findViewById(R.id.cbShowLog);
        logPanel = v.findViewById(R.id.logPanel);
        txtLog = v.findViewById(R.id.txtLog);
        logScroll = v.findViewById(R.id.logScroll);
        btnClearLog = v.findViewById(R.id.btnClearLog);
        btnCopyLog = v.findViewById(R.id.btnCopyLog);
        btnScrollDown = v.findViewById(R.id.btnScrollDown);
        cbTxRx = v.findViewById(R.id.cbTxRx);
        cbLogTs = v.findViewById(R.id.cbLogTs);
    }

    private void initUi() {
        txtLcrNode.setText(String.format(Locale.ROOT, "LCR Node : %d", node));
        txtFrom.setText(String.format(Locale.ROOT, "From : %d", from));
        txtSerialId.setText("#Série : —");
        txtTicketNo.setText("Ticket Number : —");
        txtTicketPending.setText("Ticket pending : —");
        txtLive.setText("LIVE: (en attente)");
        txtQtyNet.setText("NET: 0.0");
        txtQtyGross.setText("GROSS: 0.0");
        txtDeliveryUid.setText("Delivery UID : —");
        edtPreset.setText("50");

        // produits 1..16
        List<String> items = new ArrayList<>();
        for (int i = 1; i <= 16; i++) items.add("Produit " + i);
        ArrayAdapter<String> ad = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, items);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spnProduct.setAdapter(ad);
        spnProduct.setSelection(0);

        // log caché par défaut
        cbShowLog.setChecked(false);
        logPanel.setVisibility(View.GONE);

        // baseline “clear local view”
        logViewSinceMs = 0L;
    }

    private void wireUi() {
        cbShowLog.setOnCheckedChangeListener((b, checked) -> {
            logPanel.setVisibility(checked ? View.VISIBLE : View.GONE);
            logUi("[UI] Afficher log: " + (checked ? "ON" : "OFF"));
            if (checked) refreshLogView();
        });

        btnScrollDown.setOnClickListener(v -> logScroll.fullScroll(View.FOCUS_DOWN));

        btnClearLog.setOnClickListener(v -> {
            // Clear seulement cette vue (filtre depuis maintenant)
            logViewSinceMs = System.currentTimeMillis();
            if (txtLog != null) txtLog.setText("");
            logUi("[UI] Clear log (vue locale)");
        });

        btnCopyLog.setOnClickListener(v -> {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("log", txtLog.getText()));
            logUi("[UI] Log copié");
        });

        cbLogTs.setOnCheckedChangeListener((b, checked) -> {
            logTsEnabled = checked;
            if (controller != null) controller.setLogTimestampsEnabled(checked);
            if (link != null) link.setTraceTimestampsEnabled(checked);
            logUi("[UI] Timestamps: " + (checked ? "ON" : "OFF"));
            refreshLogView();
        });

        cbTxRx.setOnCheckedChangeListener((b, checked) -> {
            if (controller != null) controller.setTxRxLoggingEnabled(checked);
            logUi("[UI] TX/RX: " + (checked ? "ON" : "OFF"));
            refreshLogView();
        });

        btnConnect.setOnClickListener(v -> connectThisRegister());

        btnA.setOnClickListener(v -> { if (controller != null) controller.alignOrRecover(); });
        btnB.setOnClickListener(v -> { if (controller != null) controller.requestStatus(); });

        btnC.setOnClickListener(v -> {
            if (controller == null) return;
            int prod = spnProduct.getSelectedItemPosition() + 1;
            double preset = parseDouble(edtPreset.getText().toString(), 0.0);
            controller.startDelivery(prod, preset);
        });

        btnContinue.setOnClickListener(v -> { if (controller != null) controller.resumeIfPaused(); });
        btnFinish.setOnClickListener(v -> { if (controller != null) controller.endDelivery(); });
    }

    /**
     * B2: auto-connect "API-like" (Scan USB -> Open/Ping -> Connect LCP -> Validate)
     * Le but est d'avoir les mêmes logs et la même trace DB qu'un appel API.
     */
    private void autoConnectLikeApi() {
        logApi("[API] Auto-connect TAB node=" + node + " start");

        // 1) Scan USB (média présent)
        int devCount = 0;
        try { devCount = (usbManager != null) ? usbManager.getDeviceList().size() : 0; } catch (Exception ignored) {}
        if (devCount <= 0) {
            logApi("[API] Scan USB: 0 - Aucun périphérique USB détecté.");
            return;
        }
        logApi("[API] Scan USB: 1 - USB device présent (" + devCount + ")");

        // 2) Open/Ping USB (port prêt)
        UsbSerialPort p = UsbSession.getPort();
        if (p == null) {
            logApi("[API] Open/Ping USB: 0 - Port non prêt (UsbSession port null).");
            return;
        }
        logApi("[API] Open/Ping USB: 1 - Port prêt");

        // 3) Connect LCP (0x28) best effort
        try {
            LcpLink tmp = new LcpLink(p, node, from, true);
            int[] ds = tmp.opDeliveryStatus();
            int delCode = ds[1];
            boolean ticketPending = (delCode & 0x0001) != 0;
            boolean flowActive = (delCode & 0x0004) != 0;
            boolean deliveryActive = (delCode & 0x0008) != 0;
            logApi("[API] Connect LCP: 1 - CONNECTED " +
                    "deliveryActive=" + (deliveryActive ? 1 : 0) +
                    " flowActive=" + (flowActive ? 1 : 0) +
                    " ticketPending=" + (ticketPending ? 1 : 0));
        } catch (Exception e) {
            logApi("[API] Connect LCP: 0 - Failed: " + safeMsg(e));
            // on continue quand même
        }

        // 4) Connect TAB + validate
        connectThisRegister();
    }

    private void connectThisRegister() {
        UsbSerialPort p = UsbSession.getPort();
        if (p == null) {
            logApi("[API] Open/Ping USB: 0 - USB non prêt (UsbSession port null)");
            Toast.makeText(requireContext(), "USB non prêt", Toast.LENGTH_SHORT).show();
            return;
        }

        // Stop old controller
        try { if (controller != null) controller.shutdown(false); } catch (Exception ignored) {}
        controller = null;
        link = null;

        link = new LcpLink(p, node, from, true);
        controller = new DeliveryController(link);

        // inject DB store (1 store pour tout le tab)
        try { controller.setLogStore(store); } catch (Exception ignored) {}

        // ✅ B2: enregistrer la session pour partager UI <- -> API (unicité par node)
        try {
            RegisterSessionManager.get(requireContext()).setController(node, controller);
        } catch (Exception ignored) {}

        // listener UI
        controller.setListener(new DeliveryControllerPort.Listener() {
            @Override public void onStateChanged(DeliveryState state) {}

            @Override public void onProductsUpdated(List<ProductUiItem> products, int activeIndex0) {}

            @Override public void onLog(String message) {
                // message peut contenir TX/RX / [IO] / etc selon ton impl
                logAutoClassify(message);
            }

            @Override public void onError(String context, Throwable error) {
                LogBus.err(node, "[ERR " + context + "] " + (error != null ? error.getMessage() : ""));
            }

            @Override public void onLiveQty(double net, double gross) {
                ui.post(() -> {
                    txtQtyNet.setText(String.format(Locale.ROOT, "NET: %.3f", net));
                    txtQtyGross.setText(String.format(Locale.ROOT, "GROSS: %.3f", gross));
                });
            }

            @Override public void onLiveStatus(String liveText) {
                ui.post(() -> txtLive.setText(liveText));
            }

            @Override public void onTicketInfo(String ticketNo, String deliveryUid) {
                ui.post(() -> {
                    txtTicketNo.setText("Ticket Number : " + (ticketNo == null ? "—" : ticketNo));
                    txtDeliveryUid.setText("Delivery UID : " + (deliveryUid == null ? "—" : deliveryUid));
                });
            }
        });

        controller.setTxRxLoggingEnabled(cbTxRx.isChecked());
        controller.setLogTimestampsEnabled(cbLogTs.isChecked());
        link.setTraceTimestampsEnabled(cbLogTs.isChecked());

        controller.initialize();
        logApi("[API] Connect TAB: 1 - CONNECTED node=" + node);

        // Validate header (serial/ticketPending + ticket_no) + DB trace UI/API-like
        bg.execute(() -> {
            try {
                ApiResult r = controller.api_registerValidate(null, node, null, null, null);
                JSONObject j = r.toJson().optJSONObject("data");
                if (j != null) {
                    String serial = j.optString("serial_id", "");
                    String ticketNo = j.optString("ticket_no", "");
                    int tp = j.optInt("ticketPending", -1);

                    ui.post(() -> {
                        txtSerialId.setText("#Série : " + ((serial == null || serial.isEmpty()) ? "—" : serial));
                        txtTicketPending.setText("Ticket pending : " + ((tp == 1) ? "OUI" : (tp == 0 ? "NON" : "—")));
                    });

                    // ✅ SQLite: trace UI (API-like) en plus des logs internes du DeliveryController
                    if (store != null &&
                            serial != null && !serial.isEmpty() &&
                            ticketNo != null && !ticketNo.isEmpty()) {

                        store.upsertSummaryAsync(serial, ticketNo, j.optString("sale_no",""),
                                "TAB_AUTO_CONNECT", DeliveryLogStore.SOURCE_UI, null, j.toString(), null);

                        store.openAttemptAsync(serial, ticketNo, DeliveryLogStore.SOURCE_UI, null, attemptId -> {
                            store.addEventAsync(attemptId, DeliveryLogStore.LEVEL_INFO, "TAB_AUTO_CONNECT",
                                    "Auto-connect (API-like) TAB node=" + node, j.toString());
                            store.closeAttemptAsync(attemptId, "OK", j.toString(), null);
                        });
                    }
                }
            } catch (Exception e) {
                logApi("[API] validate header fail: " + safeMsg(e));
            }
        });
    }

    /**
     * Rafraîchit la vue log du tab en appliquant:
     * - node == this.node
     * - UI + API + ERR toujours
     * - IO seulement si cbTxRx checked
     * - baseline "clear local view" via logViewSinceMs
     */
    private void refreshLogView() {
        if (txtLog == null) return;
        boolean includeIo = (cbTxRx != null && cbTxRx.isChecked());
        String text = LogBus.buildText(LogBus.filterNodeUIIOAPI(node, includeIo, logViewSinceMs), 1200);
        txtLog.setText(text);
    }

    // ---------- Logging helpers (centralisés) ----------

    private void logUi(String s) {
        LogBus.ui(node, maybeUiTimestamp(s));
    }

    private void logApi(String s) {
        // API lines gardent leur préfixe, pas de prefix UI timestamp
        LogBus.api(node, s);
    }

    private void logIo(String s) {
        LogBus.io(node, s);
    }

    /**
     * Classe source automatiquement selon préfixes.
     * - [API] ... => API
     * - [IO ...] / TX: / RX: / ↳ => IO
     * - sinon => UI (avec timestamp optionnel)
     */
    private void logAutoClassify(String raw) {
        if (raw == null) return;
        String s = raw.trim();
        if (s.startsWith("[API]") || s.startsWith("[API ")) {
            LogBus.api(node, s);
            return;
        }
        if (s.startsWith("[IO ") || s.startsWith("TX:") || s.startsWith("RX:") || s.startsWith("↳")) {
            LogBus.io(node, s);
            return;
        }
        if (s.startsWith("[ERR") || s.startsWith("ERR[")) {
            LogBus.err(node, s);
            return;
        }
        LogBus.ui(node, maybeUiTimestamp(s));
    }

    private String maybeUiTimestamp(String line) {
        if (!logTsEnabled) return line;
        // évite de re-timestamp les lignes IO/API
        if (line.startsWith("[IO ") || line.startsWith("[API ")) return line;
        return "[UI " + uiTs() + "] " + line;
    }

    private String uiTs() {
        return new SimpleDateFormat("HH:mm:ss.SSS", Locale.CANADA_FRENCH)
                .format(new Date(System.currentTimeMillis()));
    }

    private static double parseDouble(String s, double def) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return def; }
    }

    private static String safeMsg(Exception e) {
        if (e == null) return "";
        String m = e.getMessage();
        return (m == null) ? e.getClass().getSimpleName() : m;
    }
}
