
package com.pa.lcrdemo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.pa.lcr.lcp.*;
import com.pa.lcr.lcp.log.LogBus;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * RegisterTabFragment (node-specific)
 *
 * Correctifs:
 *  - ✅ Pas de polling local (liveTick/statusTick supprimés) -> polling centralisé dans RegisterSessionManager (scheduler par node).
 *  - ✅ UI/API tandem: controller partagé via RegisterSessionManager.getOrCreate(node, from, UsbSession.getPort()).
 *  - ✅ Multi-listener: attach/detach du listener UI via RegisterSessionManager (pas de controller.setListener ici).
 *  - ✅ Auto-attach: retry sur UsbReceiver.ACTION_USB_READY (USB ouvert par UI ou API).
 */
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

    private TextView txtLcrNode, txtFrom, txtSerialId, txtTicketNo, txtTicketPending;
    private TextView txtLive, txtQtyNet, txtQtyGross, txtDeliveryUid;

    private Spinner spnProduct;
    private EditText edtPreset;

    private Button btnConnect, btnA, btnB, btnC, btnContinue, btnFinish;

    // Log tab
    private CheckBox cbShowLog, cbTxRx, cbLogTs;
    private View logPanel;
    private TextView txtLog;
    private ScrollView logScroll;
    private Button btnClearLog, btnCopyLog, btnScrollDown;

    // Options UI
    private boolean logTsEnabled = false;
    private long logViewSinceMs = 0L;

    // Cache ticket pending (utilisé pour gate C)
    private int ticketPendingFlag = -1; // -1 unknown, 0 NO, 1 YES

    // Auto-attach lifecycle
    private boolean attemptedAutoAttachOnce = false;
    private boolean uiListenerAttached = false;

    private UsbManager usbManager;

    // Controller partagé UI ↔ API (RegisterSessionManager)
    private DeliveryController controller;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    // Start UX
    private boolean starting = false;
    private long startingSinceMs = 0L;

    // ----------- Listener UI (multi-listener) -----------
    private final DeliveryControllerPort.Listener uiListener = new DeliveryControllerPort.Listener() {

        @Override
        public void onStateChanged(DeliveryState state) {
            ui.post(() -> {
                // fin "starting" dès qu'on voit FLOWING, ou timeout
                if (starting && state == DeliveryState.RUNNING_FLOWING) starting = false;
                if (starting && (System.currentTimeMillis() - startingSinceMs) > 12000L) starting = false;

                updateButtons(state);

                // refresh rapide (sans polling local)
                try { if (controller != null) controller.requestStatus(); } catch (Exception ignored) {}
            });
        }

        @Override public void onProductsUpdated(List<ProductUiItem> products, int activeIndex0) { }

        @Override
        public void onLog(String message) {
            // Le sink permanent (dans RegisterSessionManager) route déjà vers LogBus.
            // Ici, on rafraîchit seulement la vue si elle est ouverte.
            if (cbShowLog != null && cbShowLog.isChecked()) refreshLogView();
        }

        @Override
        public void onError(String context, Throwable error) {
            // fallback (le sink permanent log déjà)
            LogBus.err(node, "ERR[" + context + "] " + (error != null ? error.getMessage() : ""));
        }

        @Override
        public void onLiveQty(double net, double gross) {
            ui.post(() -> {
                txtQtyNet.setText(String.format(Locale.ROOT, "NET: %.3f", net));
                txtQtyGross.setText(String.format(Locale.ROOT, "GROSS: %.3f", gross));
            });
        }

        @Override
        public void onLiveStatus(String liveText) {
            ui.post(() -> {
                if (starting) {
                    txtLive.setText("LIVE: RUNNING_FLOWING (flow off - waiting progression)");
                } else {
                    txtLive.setText(liveText);
                }
            });
        }

        @Override
        public void onTicketInfo(String ticketNo, String deliveryUid) {
            ui.post(() -> {
                txtTicketNo.setText("Ticket Number : " + (ticketNo == null ? "—" : ticketNo));
                txtDeliveryUid.setText("Delivery UID : " + (deliveryUid == null ? "—" : deliveryUid));
            });
        }
    };

    // Rafraîchit les logs tab quand un event node arrive
    private final LogBus.Listener logListener = e -> {
        if (e == null) return;
        if (e.node == null || e.node != node) return;
        if (cbShowLog != null && cbShowLog.isChecked()) refreshLogView();
    };

    // Retry auto-attach quand USB devient prêt
    private final BroadcastReceiver usbStateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String a = intent.getAction();
            if (a == null) return;

            if (UsbReceiver.ACTION_USB_READY.equals(a)) {
                attemptAttachIfPossible(false);
            } else if (UsbReceiver.ACTION_USB_DETACHED.equals(a)) {
                detachUiListenerSafe();
                controller = null;
                starting = false;
                ticketPendingFlag = -1;
                ui.post(() -> {
                    txtSerialId.setText("#Série : —");
                    txtTicketPending.setText("Ticket pending : —");
                    txtTicketNo.setText("Ticket Number : —");
                    txtDeliveryUid.setText("Delivery UID : —");
                    txtLive.setText("LIVE: (en attente)");
                    txtQtyNet.setText("NET: 0.0");
                    txtQtyGross.setText("GROSS: 0.0");
                    updateButtons(null);
                });
            }
        }
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
    }

    @Override
    public void onStart() {
        super.onStart();
        LogBus.addListener(logListener);

        IntentFilter f = new IntentFilter();
        f.addAction(UsbReceiver.ACTION_USB_READY);
        f.addAction(UsbReceiver.ACTION_USB_DETACHED);
        requireContext().registerReceiver(usbStateReceiver, f);
    }

    @Override
    public void onStop() {
        detachUiListenerSafe();
        LogBus.removeListener(logListener);
        try { requireContext().unregisterReceiver(usbStateReceiver); } catch (Exception ignored) {}
        super.onStop();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (!attemptedAutoAttachOnce) {
            attemptedAutoAttachOnce = true;
            ui.post(() -> attemptAttachIfPossible(true));
        } else {
            ui.post(() -> attemptAttachIfPossible(false));
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        try { bg.shutdownNow(); } catch (Exception ignored) {}
        controller = null; // controller partagé: ne pas shutdown ici
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
        ticketPendingFlag = -1;

        txtLive.setText("LIVE: (en attente)");
        txtQtyNet.setText("NET: 0.0");
        txtQtyGross.setText("GROSS: 0.0");
        txtDeliveryUid.setText("Delivery UID : —");

        edtPreset.setText("50");

        List<String> items = new ArrayList<>();
        for (int i = 1; i <= 16; i++) items.add("Produit " + i);
        ArrayAdapter<String> ad = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, items);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spnProduct.setAdapter(ad);
        spnProduct.setSelection(0);

        cbShowLog.setChecked(false);
        logPanel.setVisibility(View.GONE);
        logViewSinceMs = 0L;

        updateButtons(null);
    }

    private void wireUi() {

        cbShowLog.setOnCheckedChangeListener((b, checked) -> {
            logPanel.setVisibility(checked ? View.VISIBLE : View.GONE);
            LogBus.ui(node, ts("Afficher log: " + (checked ? "ON" : "OFF")));
            if (checked) refreshLogView();
        });

        btnScrollDown.setOnClickListener(v -> logScroll.fullScroll(View.FOCUS_DOWN));

        btnClearLog.setOnClickListener(v -> {
            logViewSinceMs = System.currentTimeMillis();
            if (txtLog != null) txtLog.setText("");
            LogBus.ui(node, ts("Clear log (vue locale)"));
        });

        btnCopyLog.setOnClickListener(v -> {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("log", txtLog.getText()));
            LogBus.ui(node, ts("Log copié"));
        });

        cbLogTs.setOnCheckedChangeListener((b, checked) -> {
            logTsEnabled = checked;
            if (controller != null) controller.setLogTimestampsEnabled(checked);
            LogBus.ui(node, ts("Timestamps: " + (checked ? "ON" : "OFF")));
        });

        cbTxRx.setOnCheckedChangeListener((b, checked) -> {
            if (controller != null) controller.setTxRxLoggingEnabled(checked);
            LogBus.ui(node, ts("TX/RX: " + (checked ? "ON" : "OFF")));
            refreshLogView();
        });

        btnConnect.setOnClickListener(v -> connectThisRegister(true));

        btnA.setOnClickListener(v -> { if (controller != null) controller.alignOrRecover(); });

        btnB.setOnClickListener(v -> { if (controller != null) controller.requestStatus(); });

        btnC.setOnClickListener(v -> {
            if (controller == null) return;

            if (ticketPendingFlag == 1) {
                txtLive.setText("LIVE: ticket_pending — faire Resolve (A)");
                LogBus.ui(node, ts("C bloqué: ticket_pending=1"));
                updateButtons(controller.getState());
                return;
            }

            starting = true;
            startingSinceMs = System.currentTimeMillis();
            updateButtons(controller.getState());
            txtLive.setText("LIVE: RUNNING_FLOWING (flow off - waiting progression)");

            int prod = spnProduct.getSelectedItemPosition() + 1;
            double preset = parseDouble(edtPreset.getText().toString(), 0.0);

            controller.startDelivery(prod, preset);
            // ✅ Pas de tick ici : polling central dans RegisterSessionManager
        });

        btnContinue.setOnClickListener(v -> { if (controller != null) controller.resumeIfPaused(); });

        btnFinish.setOnClickListener(v -> { if (controller != null) controller.endDelivery(); });
    }

    // ---------------------------
    // Attach logic (no local polling)
    // ---------------------------

    private void attemptAttachIfPossible(boolean verboseLog) {
        if (uiListenerAttached && controller != null) {
            syncUiFromController();
            return;
        }

        if (UsbSession.getPort() == null) {
            if (verboseLog) LogBus.api(node, "Auto-attach: USB/Controller pas encore prêt (retry sur USB_READY).");
            return;
        }

        connectThisRegister(false);
    }

    private void connectThisRegister(boolean userInitiated) {
        UsbSerialPort p = UsbSession.getPort();
        if (p == null) {
            if (userInitiated) {
                LogBus.api(node, "USB non prêt (UsbSession port null)");
                Toast.makeText(requireContext(), "USB non prêt", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        RegisterSessionManager sm = RegisterSessionManager.get(requireContext());

        controller = sm.getOrCreate(node, from, p);
        if (controller == null) {
            if (userInitiated) Toast.makeText(requireContext(), "USB non prêt", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!uiListenerAttached) {
            sm.attachUiListener(node, uiListener);
            uiListenerAttached = true;
        }

        controller.setTxRxLoggingEnabled(cbTxRx.isChecked());
        controller.setLogTimestampsEnabled(cbLogTs.isChecked());

        syncUiFromController();
        validateHeaderAsync();

        if (userInitiated) LogBus.api(node, "Connect TAB: 1 - UI attached");
    }

    private void detachUiListenerSafe() {
        if (!uiListenerAttached) return;
        try {
            RegisterSessionManager.get(requireContext()).detachUiListener(node, uiListener);
        } catch (Exception ignored) {}
        uiListenerAttached = false;
    }

    private void syncUiFromController() {
        if (controller == null) return;
        DeliveryState st = controller.getState();
        updateButtons(st);

        // Force un refresh (le scheduler central fera le reste)
        try { controller.requestStatus(); } catch (Exception ignored) {}
    }

    private void validateHeaderAsync() {
        bg.execute(() -> {
            try {
                if (controller == null) return;

                ApiResult r = controller.api_registerValidate(null, node, null, null, null);
                JSONObject j = r.toJson().optJSONObject("data");
                if (j == null) return;

                String serial = j.optString("serial_id", "");
                int tp = j.optInt("ticketPending", -1);
                ticketPendingFlag = (tp == 1 ? 1 : (tp == 0 ? 0 : -1));

                ui.post(() -> {
                    txtSerialId.setText("#Série : " + ((serial == null || serial.isEmpty()) ? "—" : serial));
                    txtTicketPending.setText("Ticket pending : " + (ticketPendingFlag == 1 ? "OUI" : (ticketPendingFlag == 0 ? "NON" : "—")));
                    if (ticketPendingFlag == 1) txtLive.setText("LIVE: ticket_pending — faire Resolve (A)");
                    updateButtons(controller != null ? controller.getState() : null);
                });

            } catch (Exception e) {
                LogBus.api(node, "validate header fail: " + safeMsg(e));
            }
        });
    }

    // ---------------------------
    // UI state (buttons)
    // ---------------------------

    private void updateButtons(DeliveryState state) {
        if (controller == null) {
            btnConnect.setEnabled(true);
            btnA.setEnabled(false);
            btnB.setEnabled(false);
            btnC.setEnabled(false);
            btnContinue.setEnabled(false);
            btnFinish.setEnabled(false);
            return;
        }

        DeliveryState st = (state != null) ? state : controller.getState();
        boolean connected = (st == DeliveryState.CONNECTED);
        boolean paused = (st == DeliveryState.RUNNING_PAUSED);
        boolean flowing = (st == DeliveryState.RUNNING_FLOWING);

        boolean stableOff = false;
        try { stableOff = controller.isFlowOffStable(); } catch (Exception ignored) {}

        btnConnect.setEnabled(true);
        btnB.setEnabled(true);
        btnA.setEnabled(connected || paused || flowing);

        btnC.setEnabled(connected && ticketPendingFlag != 1);

        if (starting) {
            btnContinue.setEnabled(false);
            btnFinish.setEnabled(false);
        } else {
            btnContinue.setEnabled(paused);
            btnFinish.setEnabled(paused && stableOff);
        }
    }

    // ---------------------------
    // Log tab view
    // ---------------------------

    private void refreshLogView() {
        if (txtLog == null) return;
        boolean includeIo = (cbTxRx != null && cbTxRx.isChecked());
        String text = LogBus.buildText(LogBus.filterNodeUIIOAPI(node, includeIo, logViewSinceMs), 1200);
        txtLog.setText(text);
    }

    // ---------------------------
    // Small helpers
    // ---------------------------

    private String ts(String msg) {
        if (!logTsEnabled) return msg;
        return uiTs() + " " + msg;
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
