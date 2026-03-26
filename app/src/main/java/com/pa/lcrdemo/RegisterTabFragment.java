
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
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;

import com.pa.lcr.lcp.*;
import com.pa.lcr.lcp.log.LogBus;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * RegisterTabFragment (node-specific) — v7
 *
 * v7:
 * - 1 seul média attaché par registre (node + serial #80) => resolveOrCreateForNode()
 * - le TAB = log global filtré par node
 * - LIVE/NET/GROSS pilotés par DeliveryController (requestLiveSample cadence gérée ailleurs)
 * - Fixes:
 *   (1) Format décimal NET/GROSS selon #39 (via DeliveryController.getDisplayDigits())
 *   (2) Après Finish/print: refresh Status + ValidateHeader
 *   (3) #Série toujours affiché (retry throttlé)
 *   (4) Bouton DOWN = scroll bas du log seulement, sans refresh tab
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

    // Header UI
    private TextView txtLcrNode, txtFrom, txtSerialId, txtTicketNo, txtTicketPending;
    private TextView txtDeliveryUid;

    // Live UI
    private TextView txtLive, txtQtyNet, txtQtyGross;

    // Controls
    private Spinner spnProduct;
    private EditText edtPreset;
    private Button btnConnect, btnA, btnB, btnC, btnContinue, btnFinish;
    private Button btnReprintTicket;

    // Root scroll (tab)
    private NestedScrollView regRootScroll;

    // Log tab (node-filtered global log)
    private CheckBox cbShowLog, cbTxRx, cbLogTs;
    private View logPanel;
    private TextView txtLog;
    private ScrollView logScroll;
    private Button btnClearLog, btnCopyLog, btnScrollDown;

    // Options UI
    private boolean logTsEnabled = false;
    private long logViewSinceMs = 0L;

    // Cache ticket pending (utilisé pour gate C + Reprint)
    private int ticketPendingFlag = -1; // -1 unknown, 0 NO, 1 YES

    // v7: dernier liveText reçu (source de vérité UI)
    private volatile String lastLiveText = null;

    // v7: digits (#39) pour formatage (fallback=3)
    private volatile int lastDigits = 3;

    // v7: throttle validate header retry (serial #80)
    private volatile long lastHeaderValidateMs = 0L;
    private static final long HEADER_VALIDATE_MIN_MS = 5000L;

    // Auto-attach lifecycle
    private boolean attemptedAutoAttachOnce = false;
    private boolean uiListenerAttached = false;

    private UsbManager usbManager;

    // Controller partagé UI ↔ API (RegisterSessionManager)
    private DeliveryController controller;

    // v7: clé du transport réellement attaché (USB ou BT:..)
    private String tabTransportKey = null;

    // Start UX
    private boolean starting = false;
    private long startingSinceMs = 0L;

    // Throttle/coalesce log refresh
    private static final int TAB_LOG_MAX_LINES = 400;
    private static final long LOG_REFRESH_MIN_MS = 600; // v7: réduit le jank (refresh moins fréquent)
    private long lastLogRefreshMs = 0L;
    private boolean logRefreshPending = false;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    /** ✅ Log refresh toujours sur UI thread. */
    private void scheduleLogRefresh() {
        if (txtLog == null) return;
        if (cbShowLog == null || !cbShowLog.isChecked()) return;

        if (Looper.myLooper() != Looper.getMainLooper()) {
            ui.post(this::scheduleLogRefresh);
            return;
        }

        long now = System.currentTimeMillis();
        long dt = now - lastLogRefreshMs;

        if (dt >= LOG_REFRESH_MIN_MS && !logRefreshPending) {
            lastLogRefreshMs = now;
            refreshLogView();
            return;
        }

        if (logRefreshPending) return;
        logRefreshPending = true;

        long delay = Math.max(0L, LOG_REFRESH_MIN_MS - dt);
        ui.postDelayed(() -> {
            logRefreshPending = false;
            lastLogRefreshMs = System.currentTimeMillis();
            refreshLogView();
        }, delay);
    }

    // =========================
    // Delivery listener (UI)
    // =========================
    private final DeliveryControllerPort.Listener uiListener = new DeliveryControllerPort.Listener() {

        @Override
        public void onStateChanged(DeliveryState state) {
            ui.post(() -> {
                if (!isAdded() || getView() == null) return;

                if (starting && state == DeliveryState.RUNNING_FLOWING) starting = false;
                if (starting && (System.currentTimeMillis() - startingSinceMs) > 12000L) starting = false;

                updateButtons(state);
                scheduleLogRefresh();
            });
        }

        @Override public void onProductsUpdated(List<ProductUiItem> products, int activeIndex0) { }

        @Override
        public void onLog(String message) {
            scheduleLogRefresh();
        }

        @Override
        public void onError(String context, Throwable error) {
            LogBus.api(node, "[ERR][" + context + "] " + (error != null ? error.getMessage() : ""));
            scheduleLogRefresh();
        }

        @Override
        public void onLiveQty(double net, double gross) {
            ui.post(() -> {
                if (!isAdded() || getView() == null) return;

                // ✅ digits dynamique depuis le controller (scale #39 déjà appliqué côté controller)
                int d = lastDigits;
                try {
                    if (controller != null) d = controller.getDisplayDigits(); // getter v7 (2 lignes dans DeliveryController)
                } catch (Exception ignored) {}
                if (d < 0) d = 3;
                if (d > 6) d = 6; // garde-fou
                lastDigits = d;

                String fmt = "%." + d + "f";
                if (txtQtyNet != null) txtQtyNet.setText("NET: " + String.format(Locale.ROOT, fmt, net));
                if (txtQtyGross != null) txtQtyGross.setText("GROSS: " + String.format(Locale.ROOT, fmt, gross));
            });
        }

        @Override
        public void onLiveStatus(String liveText) {
            ui.post(() -> {
                if (!isAdded() || getView() == null) return;

                lastLiveText = liveText;
                if (txtLive != null) txtLive.setText(liveText);

                // Sortie du mode starting dès qu'un état réel arrive
                try {
                    if (liveText != null && (liveText.contains("FLOW ON") || liveText.contains("PAUSED") || liveText.contains("confirm"))) {
                        starting = false;
                    }
                } catch (Exception ignored) {}

                // ✅ si serial absent, retenter validate (throttlé)
                ensureSerialVisibleThrottled();

                updateButtons(controller != null ? controller.getState() : null);
                scheduleLogRefresh();
            });
        }

        @Override
        public void onTicketInfo(String ticketNo, String deliveryUid) {
            ui.post(() -> {
                if (!isAdded() || getView() == null) return;

                if (txtTicketNo != null) txtTicketNo.setText("Ticket Number : " + (ticketNo == null ? "—" : ticketNo));
                if (txtDeliveryUid != null) txtDeliveryUid.setText("Delivery UID : " + (deliveryUid == null ? "—" : deliveryUid));

                // ✅ si serial absent, retenter validate (throttlé)
                ensureSerialVisibleThrottled();

                updateButtons(controller != null ? controller.getState() : null);
            });
        }
    };

    // LogBus listener -> refresh tab log for this node
    private final LogBus.Listener logListener = e -> {
        if (e == null) return;
        if (e.node != node) return;
        scheduleLogRefresh();
    };

    private final BroadcastReceiver usbStateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String a = intent.getAction();
            if (a == null) return;

            if (UsbReceiver.ACTION_USB_READY.equals(a)) {
                attemptAttachIfPossible(false);
            } else if (UsbReceiver.ACTION_USB_DETACHED.equals(a)) {
                try {
                    if (tabTransportKey != null && tabTransportKey.toUpperCase(Locale.ROOT).startsWith("BT:")) {
                        LogBus.api(node, "USB detached ignored (TAB sur " + tabTransportKey + ")");
                        return;
                    }
                } catch (Exception ignored) {}

                detachUiListenerSafe();
                controller = null;
                starting = false;
                ticketPendingFlag = -1;

                ui.post(() -> {
                    if (!isAdded() || getView() == null) return;
                    if (txtSerialId != null) txtSerialId.setText("#Série : —");
                    if (txtTicketPending != null) txtTicketPending.setText("Ticket pending : —");
                    if (txtTicketNo != null) txtTicketNo.setText("Ticket Number : —");
                    if (txtDeliveryUid != null) txtDeliveryUid.setText("Delivery UID : —");
                    if (txtLive != null) txtLive.setText("LIVE: (en attente)");
                    if (txtQtyNet != null) txtQtyNet.setText("NET: 0.0");
                    if (txtQtyGross != null) txtQtyGross.setText("GROSS: 0.0");
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
        try { ui.removeCallbacksAndMessages(null); } catch (Exception ignored) {}
        try { bg.shutdownNow(); } catch (Exception ignored) {}
        controller = null;
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
        regRootScroll = v.findViewById(R.id.regRootScroll);

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

        btnReprintTicket = v.findViewById(R.id.btnReprintTicket);

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
        if (txtLcrNode != null) txtLcrNode.setText(String.format(Locale.ROOT, "LCR Node : %d", node));
        if (txtFrom != null) txtFrom.setText(String.format(Locale.ROOT, "From : %d", from));

        if (txtSerialId != null) txtSerialId.setText("#Série : —");
        if (txtTicketNo != null) txtTicketNo.setText("Ticket Number : —");
        if (txtTicketPending != null) txtTicketPending.setText("Ticket pending : —");
        if (txtDeliveryUid != null) txtDeliveryUid.setText("Delivery UID : —");

        ticketPendingFlag = -1;
        lastDigits = 3;

        if (txtLive != null) txtLive.setText("LIVE: (en attente)");
        if (txtQtyNet != null) txtQtyNet.setText("NET: 0.0");
        if (txtQtyGross != null) txtQtyGross.setText("GROSS: 0.0");

        if (edtPreset != null) edtPreset.setText("50");

        List<String> items = new ArrayList<>();
        for (int i = 1; i <= 16; i++) items.add("Produit " + i);
        ArrayAdapter<String> ad = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, items);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        if (spnProduct != null) {
            spnProduct.setAdapter(ad);
            spnProduct.setSelection(0);
        }

        if (cbShowLog != null) cbShowLog.setChecked(false);
        if (logPanel != null) logPanel.setVisibility(View.GONE);

        logViewSinceMs = 0L;

        if (cbTxRx != null) cbTxRx.setChecked(LogBus.SHOW_IO);
        if (cbLogTs != null) cbLogTs.setChecked(LogBus.SHOW_TS);

        updateButtons(null);
    }

    

 // ✅ UX: bouton DOWN ne doit scroller que le log (pas le tab au complet)
 private void scrollLogToBottomOnly() {
     try {
         if (logScroll == null) return;
         final int rootY = (regRootScroll != null) ? regRootScroll.getScrollY() : -1;
         logScroll.post(() -> {
             try {
                 if (regRootScroll != null) regRootScroll.requestDisallowInterceptTouchEvent(true);
                 logScroll.fullScroll(View.FOCUS_DOWN);
                 if (regRootScroll != null && rootY >= 0) regRootScroll.scrollTo(0, rootY);
             } catch (Exception ignored) {}
         });
     } catch (Exception ignored) {}
 }

 // ✅ UX: permettre le scroll dans la zone log sans que le NestedScrollView "vole" le geste
 private void installLogScrollInterceptionFix() {
     try {
         if (logScroll != null) {
             logScroll.setOnTouchListener((v, ev) -> {
                 try { if (regRootScroll != null) regRootScroll.requestDisallowInterceptTouchEvent(true); } catch (Exception ignored) {}
                 return false;
             });
         }
         if (txtLog != null) {
             txtLog.setOnTouchListener((v, ev) -> {
                 try { if (regRootScroll != null) regRootScroll.requestDisallowInterceptTouchEvent(true); } catch (Exception ignored) {}
                 return false;
             });
         }
     } catch (Exception ignored) {}
 }
private void wireUi() {
        if (cbShowLog != null) {
            cbShowLog.setOnCheckedChangeListener((b, checked) -> {
                if (logPanel != null) logPanel.setVisibility(checked ? View.VISIBLE : View.GONE);
                LogBus.ui(node, ts("Afficher log: " + (checked ? "ON" : "OFF")));
                if (checked) {
 installLogScrollInterceptionFix();
 scheduleLogRefresh();
 }
            });
        }

        // ✅ DOWN: scroll bas du LOG seulement, sans refresh tab
        if (btnScrollDown != null && logScroll != null) {
            btnScrollDown.setOnClickListener(v -> scrollLogToBottomOnly());
        }

        if (btnClearLog != null) {
            btnClearLog.setOnClickListener(v -> {
                logViewSinceMs = System.currentTimeMillis();
                if (txtLog != null) txtLog.setText("");
                LogBus.ui(node, ts("Clear log (vue locale)"));
                // pas de scroll forcé
            });
        }

        if (btnCopyLog != null) {
            btnCopyLog.setOnClickListener(v -> {
                android.content.ClipboardManager cm =
                        (android.content.ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("log", txtLog != null ? txtLog.getText() : ""));
                LogBus.ui(node, ts("Log copié"));
            });
        }

        if (cbLogTs != null) {
            cbLogTs.setOnCheckedChangeListener((b, checked) -> {
                logTsEnabled = checked;
                LogBus.SHOW_TS = checked;
                if (controller != null) controller.setLogTimestampsEnabled(checked);
                LogBus.ui(node, ts("Timestamps: " + (checked ? "ON" : "OFF")));
                scheduleLogRefresh();
            });
        }

        if (cbTxRx != null) {
            cbTxRx.setOnCheckedChangeListener((b, checked) -> {
                LogBus.SHOW_IO = checked;
                if (controller != null) controller.setTxRxLoggingEnabled(checked);
                LogBus.ui(node, ts("TX/RX: " + (checked ? "ON" : "OFF")));
                scheduleLogRefresh();
            });
        }

        if (btnConnect != null) btnConnect.setOnClickListener(v -> connectThisRegister(true));
        if (btnA != null) btnA.setOnClickListener(v -> { if (controller != null) controller.alignOrRecover(); });
        if (btnB != null) btnB.setOnClickListener(v -> { if (controller != null) controller.requestStatus(); });

        if (btnC != null) {
            btnC.setOnClickListener(v -> {
                if (controller == null) return;

                if (ticketPendingFlag == 1) {
                    if (txtLive != null) txtLive.setText("LIVE: ticket_pending — faire Resolve (A)");
                    LogBus.ui(node, ts("C bloqué: ticket_pending=1"));
                    updateButtons(controller.getState());
                    return;
                }

                starting = true;
                startingSinceMs = System.currentTimeMillis();
                updateButtons(controller.getState());

                if (txtLive != null) txtLive.setText("LIVE: RUNNING_FLOWING (flow off - waiting progression)");

                int prod = (spnProduct != null ? (spnProduct.getSelectedItemPosition() + 1) : 1);
                double preset = parseDouble(edtPreset != null ? edtPreset.getText().toString() : "0", 0.0);

                controller.startDelivery(prod, preset);
            });
        }

        if (btnContinue != null) btnContinue.setOnClickListener(v -> {
            if (controller == null) return;
            if (controller.getState() != DeliveryState.RUNNING_PAUSED) {
                try { controller.requestLiveSample(); } catch (Exception ignored) {}
                try { Toast.makeText(requireContext(), "Attendre confirmation FLOW OFF", Toast.LENGTH_SHORT).show(); } catch (Exception ignored) {}
 LogBus.ui(node, ts("Continue ignoré: FLOW OFF pas encore confirmé"));
                return;
            }
            controller.resumeIfPaused();
        });

        if (btnFinish != null) btnFinish.setOnClickListener(v -> {
            if (controller == null) return;

            boolean stableOff2 = false;
            try { stableOff2 = controller.isFlowOffStable(); } catch (Exception ignored) {}
            if (!stableOff2) {
                try { controller.requestLiveSample(); } catch (Exception ignored) {}
                try { Toast.makeText(requireContext(), "FLOW OFF en confirmation...", Toast.LENGTH_SHORT).show(); } catch (Exception ignored) {}
                return;
            }

            controller.endDelivery();

            // ✅ v7: après END + impression, forcer un refresh status + validate
            ui.postDelayed(() -> {
                try { if (controller != null) controller.requestStatus(); } catch (Exception ignored) {}
                try { validateHeaderAsync(); } catch (Exception ignored) {}
            }, 1500);
        });

        if (btnReprintTicket != null) {
            btnReprintTicket.setOnClickListener(v -> onReprintClicked());
        }
    }

    // =========================================================
    // Reprint (last ticket)
    // =========================================================
    private void onReprintClicked() {
        if (controller == null) return;

        if (ticketPendingFlag == 1) {
            if (txtLive != null) txtLive.setText("LIVE: ticket_pending — faire Resolve (A)");
            LogBus.ui(node, ts("Reprint bloqué: ticket_pending=1 -> faire Resolve (A)"));
            scheduleLogRefresh();
            return;
        }

        String ticketNo = extractTicketDigits();
        if (ticketNo == null || ticketNo.trim().isEmpty()) {
            LogBus.ui(node, ts("Reprint: aucun ticket_no affiché"));
            try { Toast.makeText(requireContext(), "Aucun ticket à re-imprimer", Toast.LENGTH_SHORT).show(); } catch (Exception ignored) {}
            scheduleLogRefresh();
            return;
        }

        if (btnReprintTicket != null) btnReprintTicket.setEnabled(false);
        LogBus.api(node, "[REPRINT] request (ticket_no=" + ticketNo + ")");
        scheduleLogRefresh();

        try {
            if (bg.isShutdown() || bg.isTerminated()) return;
        } catch (Exception ignored) {}

        try {
            bg.execute(() -> {
                ApiResult r;
                try {
                    r = controller.api_ticketReprintCurrent();
                } catch (Exception e) {
                    r = ApiResult.fail("Reprint: 0 - Exception", "REPRINT_FAIL");
                }
                final ApiResult rr = r;

                ui.post(() -> {
                    if (!isAdded() || getView() == null) return;

                    try {
                        String err = (rr.err == null) ? "" : rr.err;
                        LogBus.api(node, "[REPRINT] resp code=" + rr.code + " err=" + err + " msg=" + rr.msg);
                    } catch (Exception ignored) {}

                    // refresh header après reprint
                    try { if (controller != null) controller.requestStatus(); } catch (Exception ignored) {}
                    try { validateHeaderAsync(); } catch (Exception ignored) {}

                    updateButtons(controller != null ? controller.getState() : null);
                    scheduleLogRefresh();
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) { }
    }

    private String extractTicketDigits() {
        if (txtTicketNo == null) return "";
        String t = String.valueOf(txtTicketNo.getText());
        String digits = t.replaceAll("[^0-9]", "");
        return digits == null ? "" : digits.trim();
    }

    private boolean hasTicketDigits() {
        String d = extractTicketDigits();
        return d != null && !d.trim().isEmpty();
    }

    // =========================================================
    // v7: attach / detach / validate header
    // =========================================================
    private void attemptAttachIfPossible(boolean verboseLog) {
        if (uiListenerAttached && controller != null) {
            syncUiFromController();
            return;
        }
        connectThisRegister(false);
    }

    private void connectThisRegister(boolean userInitiated) {
        RegisterSessionManager sm = RegisterSessionManager.get(requireContext());
        DeliveryController dc = sm.resolveOrCreateForNode(node, from);
        if (dc == null) {
            if (userInitiated) {
                LogBus.api(node, "Aucun média prêt / registre introuvable pour ce node");
                Toast.makeText(requireContext(), "Aucun média prêt (USB/BT)", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        controller = dc;

        try {
            String tk = sm.findTransportKeyForController(controller);
            if (tk != null) tabTransportKey = tk;
        } catch (Exception ignored) {}

        if (!uiListenerAttached) {
            try {
                if (tabTransportKey != null) sm.attachUiListener(tabTransportKey, node, uiListener);
                else sm.attachUiListener(node, uiListener);
            } catch (Exception ignored) {}
            uiListenerAttached = true;
        }

        if (cbTxRx != null) controller.setTxRxLoggingEnabled(cbTxRx.isChecked());
        if (cbLogTs != null) controller.setLogTimestampsEnabled(cbLogTs.isChecked());

        syncUiFromController();
        validateHeaderAsync();
        if (userInitiated) LogBus.api(node, "Connect TAB: 1 - UI attached (v7)");
        scheduleLogRefresh();
    }

    private void detachUiListenerSafe() {
        if (!uiListenerAttached) return;
        try {
            RegisterSessionManager sm = RegisterSessionManager.get(requireContext());
            if (tabTransportKey != null) sm.detachUiListener(tabTransportKey, node, uiListener);
            else sm.detachUiListener(node, uiListener);
        } catch (Exception ignored) {}
        uiListenerAttached = false;
        tabTransportKey = null;
    }

    private void syncUiFromController() {
        if (controller == null) return;
        DeliveryState st = controller.getState();
        updateButtons(st);
        try { controller.requestStatus(); } catch (Exception ignored) {}
    }

    /** Throttlé : si #Série est encore —, retenter validateHeaderAsync. */
    private void ensureSerialVisibleThrottled() {
        try {
            if (txtSerialId == null) return;
            String cur = String.valueOf(txtSerialId.getText());
            boolean missing = (cur.contains("—") || cur.trim().endsWith(":") || cur.trim().endsWith(": —"));
            if (!missing) return;

            long now = System.currentTimeMillis();
            if (now - lastHeaderValidateMs < HEADER_VALIDATE_MIN_MS) return;
            lastHeaderValidateMs = now;

            validateHeaderAsync();
        } catch (Exception ignored) {}
    }

    /** validate header -> met à jour serial/ticketPending et bindExpectedSerial(node, serial) */
    private void validateHeaderAsync() {
        try {
            if (bg.isShutdown() || bg.isTerminated()) return;
        } catch (Exception ignored) {}

        try {
            bg.execute(() -> {
                try {
                    DeliveryController c = controller;
                    if (c == null) return;

                    ApiResult r = c.api_registerValidate(null, node, null, null, null, false);
                    JSONObject j = r.toJson().optJSONObject("data");
                    if (j == null) return;

                    String serial = j.optString("serial_id", "");
                    try { RegisterSessionManager.get(requireContext()).bindExpectedSerial(node, serial); } catch (Exception ignored) {}

                    int tp = j.optInt("ticketPending", -1);
                    ticketPendingFlag = (tp == 1 ? 1 : (tp == 0 ? 0 : -1));

                    ui.post(() -> {
                        if (!isAdded() || getView() == null) return;

                        if (txtSerialId != null) {
                            txtSerialId.setText("#Série : " + ((serial == null || serial.isEmpty()) ? "—" : serial));
                        }

                        if (txtTicketPending != null) {
                            txtTicketPending.setText("Ticket pending : " +
                                    (ticketPendingFlag == 1 ? "OUI" : (ticketPendingFlag == 0 ? "NON" : "—")));
                        }

                        if (ticketPendingFlag == 1 && txtLive != null) {
                            txtLive.setText("LIVE: ticket_pending — faire Resolve (A)");
                        }

                        updateButtons(controller != null ? controller.getState() : null);
                        scheduleLogRefresh();
                    });

                } catch (Exception e) {
                    LogBus.api(node, "validate header fail: " + safeMsg(e));
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) { }
    }

    // =========================================================
    // Buttons logic
    // =========================================================
    private void updateButtons(DeliveryState state) {
        if (btnConnect == null || btnA == null || btnB == null || btnC == null || btnContinue == null || btnFinish == null)
            return;

        if (controller == null) {
            btnConnect.setEnabled(true);
            btnA.setEnabled(false);
            btnB.setEnabled(false);
            btnC.setEnabled(false);
            btnContinue.setEnabled(false);
            btnFinish.setEnabled(false);
            if (btnReprintTicket != null) btnReprintTicket.setEnabled(false);
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
            String lt = lastLiveText;
            boolean flowOffPhase = (lt != null && lt.contains("Flow OFF"));
            boolean enable = paused || flowOffPhase;

            // Continue + Terminer actifs en phase Flow OFF; Terminer sera sécurisé par stableOff
            btnContinue.setEnabled(enable);
            btnFinish.setEnabled(enable);
        }

        if (btnReprintTicket != null) {
            boolean connectedish = (connected || paused || flowing);
            boolean ticketDone = (ticketPendingFlag != 1);
            btnReprintTicket.setEnabled(connectedish && ticketDone && hasTicketDigits());
        }
    }

    /** Log view: log global filtré par node. */
    private void refreshLogView() {
        if (txtLog == null) return;
        if (cbShowLog == null || !cbShowLog.isChecked()) return;

        if (Looper.myLooper() != Looper.getMainLooper()) {
            ui.post(this::refreshLogView);
            return;
        }

        if (!isAdded() || getView() == null) return;

        List<LogBus.LogEvent> events = LogBus.snapshotForNode(node, TAB_LOG_MAX_LINES); // filtre node
        if (logViewSinceMs > 0) {
            ArrayList<LogBus.LogEvent> filtered = new ArrayList<>(events.size());
            for (LogBus.LogEvent e : events) {
                if (e.ts >= logViewSinceMs) filtered.add(e);
            }
            events = filtered;
        }

        txtLog.setText(LogBus.buildText(events)); // format unique LogBus
    }

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
