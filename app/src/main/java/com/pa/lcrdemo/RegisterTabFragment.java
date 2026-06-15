package com.pa.lcrdemo;

import com.pa.lcr.lcp.transport.TransportIo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.Fragment;

import com.pa.lcr.lcp.*;
import com.pa.lcr.lcp.log.LogBus;
import com.pa.lcr.lcp.transport.MediaTransportManager;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RegisterTabFragment extends Fragment {

    private static final String ARG_NODE = "node";
    private static final String ARG_FROM = "from";
    private static final String ARG_SERIAL = "serial";
    private static final String ARG_TRANSPORT = "transport";

    public static RegisterTabFragment newInstance(int node, int from) {
        RegisterTabFragment f = new RegisterTabFragment();
        Bundle b = new Bundle();
        b.putInt(ARG_NODE, node);
        b.putInt(ARG_FROM, from);
        f.setArguments(b);
        return f;
    }

    public void onTabActivated() {
            ui.postDelayed(() -> {
            try { runStatusBLikeButton("TAB_ACTIVATED"); } catch (Exception ignored) {}
            }, 300);
    }
    private int node = 250;
    private int from = 255;

    private TextView txtLcrNode, txtFrom, txtSerialId, txtTicketNo, txtTicketPending;
    private TextView txtDeliveryUid;
    private TextView txtLive, txtQtyNet, txtQtyGross;
    private android.widget.AutoCompleteTextView spnProduct;
    private EditText edtPreset;
    private Button btnConnect, btnA, btnB, btnC, btnContinue, btnFinish;
    private Button btnReprintTicket;
    private Button btnRetourWO;
    private NestedScrollView regRootScroll;
    private CheckBox cbShowLog, cbTxRx, cbLogTs;
    private View logPanel;
    private TextView txtLog;
    private ScrollView logScroll;
    private Button btnClearLog, btnCopyLog, btnScrollDown;

    private boolean logTsEnabled = false;
    private long logViewSinceMs = 0L;
    private int ticketPendingFlag = -1;
    private volatile String lastLiveText = null;
    private volatile int lastDigits = 3;
    private volatile int lastDelCode = 0;
    private volatile long lastDelCodePollMs = 0L;
    private static final long DELCODE_POLL_MIN_MS = 800L;
    private volatile long lastHeaderValidateMs = 0L;
    private static final long HEADER_VALIDATE_MIN_MS = 5000L;
    private volatile boolean headerValidatedOnce = false;
    private volatile boolean logUserScrolling = false;
    private volatile long logUserScrollUntilMs = 0L;
    private boolean attemptedAutoAttachOnce = false;
    private boolean uiListenerAttached = false;
    private UsbManager usbManager;
    private DeliveryController controller;
    private String tabTransportKey = null;
    private volatile boolean tabMediaReady = true;
    private volatile boolean pendingReconnect = false;
    private volatile String tabMediaShort = "—";
    private String serialFromArgs = null;
    private String transportFromArgs = null;
    private boolean starting = false;
    private long startingSinceMs = 0L;
    private static final int TAB_LOG_MAX_LINES = 400;
    private static final long LOG_REFRESH_MIN_MS = 800;
    private long lastLogRefreshMs = 0L;
    private boolean logRefreshPending = false;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    private void scheduleLogRefresh() {
        if (txtLog == null) return;
        if (cbShowLog == null || !cbShowLog.isChecked()) return;
        long now0 = System.currentTimeMillis();
        if (logUserScrolling && now0 < logUserScrollUntilMs) return;
        if (Looper.myLooper() != Looper.getMainLooper()) { ui.post(this::scheduleLogRefresh); return; }
        long now = System.currentTimeMillis();
        long dt = now - lastLogRefreshMs;
        if (dt >= LOG_REFRESH_MIN_MS && !logRefreshPending) {
            lastLogRefreshMs = now; refreshLogView(); return;
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

    private void refreshDelCodeFromTickSnapshotThrottled() {
        try {
            DeliveryController c = controller;
            if (c == null) return;
            long now = System.currentTimeMillis();
            if (now - lastDelCodePollMs < DELCODE_POLL_MIN_MS) return;
            lastDelCodePollMs = now;
            ApiResult r = c.api_tickSnapshot();
            JSONObject d = (r != null) ? r.data : null;
            if (d != null) lastDelCode = d.optInt("delCode", lastDelCode);
        } catch (Exception ignored) {}
    }

    private void installLogScrollInterceptionFix() {
        try {
            if (logScroll != null) {
                logScroll.setOnTouchListener((v, ev) -> {
                    try { if (regRootScroll != null) regRootScroll.requestDisallowInterceptTouchEvent(true); } catch (Exception ignored) {}
                    int a = ev.getActionMasked();
                    if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
                        logUserScrolling = true;
                        logUserScrollUntilMs = System.currentTimeMillis() + 1200L;
                    } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                        logUserScrolling = false;
                        logUserScrollUntilMs = System.currentTimeMillis() + 400L;
                    }
                    return false;
                });
            }
            if (txtLog != null) {
                txtLog.setOnTouchListener((v, ev) -> {
                    try { if (regRootScroll != null) regRootScroll.requestDisallowInterceptTouchEvent(true); } catch (Exception ignored) {}
                    int a = ev.getActionMasked();
                    if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE) {
                        logUserScrolling = true;
                        logUserScrollUntilMs = System.currentTimeMillis() + 1200L;
                    } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                        logUserScrolling = false;
                        logUserScrollUntilMs = System.currentTimeMillis() + 400L;
                    }
                    return false;
                });
            }
        } catch (Exception ignored) {}
    }

    private void scrollLogToBottomOnly() {
        try {
            if (logScroll == null) return;
            final int rootY = (regRootScroll != null) ? regRootScroll.getScrollY() : -1;
            try {
                if (regRootScroll != null) regRootScroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
                if (regRootScroll != null) regRootScroll.requestDisallowInterceptTouchEvent(true);
            } catch (Exception ignored) {}
            logUserScrolling = true;
            logUserScrollUntilMs = System.currentTimeMillis() + 1200L;
            logScroll.post(() -> {
                try {
                    logScroll.requestFocus();
                    logScroll.fullScroll(View.FOCUS_DOWN);
                    if (regRootScroll != null && rootY >= 0) regRootScroll.scrollTo(0, rootY);
                } catch (Exception ignored) {
                } finally {
                    try { if (regRootScroll != null) regRootScroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS); } catch (Exception ignored) {}
                    logUserScrolling = false;
                    logUserScrollUntilMs = System.currentTimeMillis() + 400L;
                }
            });
        } catch (Exception ignored) {}
    }

    private final DeliveryControllerPort.Listener uiListener = new DeliveryControllerPort.Listener() {
        @Override
        public void onStateChanged(DeliveryState state) {
            ui.post(() -> {
                if (!isAdded() || getView() == null) return;
                if (starting && state == DeliveryState.RUNNING_FLOWING) starting = false;
                if (starting && (System.currentTimeMillis() - startingSinceMs) > 12000L)
                    starting = false;
                refreshDelCodeFromTickSnapshotThrottled();
                updateButtons(state);
                scheduleLogRefresh();

                // ✅ Retour Field Service quand livraison terminée
                if (state == DeliveryState.ENDED) {
                    notifyDeliveryEndedToMainActivity();
                }
            });
        }

        @Override public void onProductsUpdated(List<ProductUiItem> products, int activeIndex0) {}

        @Override public void onLog(String message) { scheduleLogRefresh(); }

        @Override
        public void onError(String context, Throwable error) {
            LogBus.api(node, "[ERR][" + context + "] " + (error != null ? error.getMessage() : ""));
            scheduleLogRefresh();
        }

        @Override
        public void onLiveQty(double net, double gross) {
            ui.post(() -> {
                if (!isAdded() || getView() == null) return;
                int d = lastDigits;
                try { if (controller != null) d = controller.getDisplayDigits(); } catch (Exception ignored) {}
                if (d < 0) d = 3;
                if (d > 6) d = 6;
                lastDigits = d;
                int show = Math.min(6, Math.max(0, d));
                String fmt = "%." + show + "f";
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
                ensureSerialVisibleThrottled();
                refreshDelCodeFromTickSnapshotThrottled();
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
                ensureSerialVisibleThrottled();
                refreshDelCodeFromTickSnapshotThrottled();
                updateButtons(controller != null ? controller.getState() : null);
            });
        }
    };

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
                    if (txtSerialId != null) txtSerialId.setText("#Série : " + ((serialFromArgs != null && !serialFromArgs.trim().isEmpty()) ? serialFromArgs : "—"));
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
            serialFromArgs = a.getString(ARG_SERIAL, null);
            transportFromArgs = a.getString(ARG_TRANSPORT, null);
            if (transportFromArgs != null && !transportFromArgs.trim().isEmpty()) {
                tabTransportKey = transportFromArgs.trim();
                String up = tabTransportKey.toUpperCase(Locale.ROOT);
                tabMediaShort = up.startsWith("BT:") ? "BT" : (up.startsWith("USB") ? "USB" : tabMediaShort);
            }
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
        attemptedAutoAttachOnce = false;  // ← ajouter cette ligne
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
        installLogScrollInterceptionFix();
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
        if (spnProduct != null) {
            // Liste suggestions 1-16
            String[] items = new String[16];
            for (int i = 0; i < 16; i++) items[i] = String.valueOf(i + 1);
            android.widget.ArrayAdapter<String> ad = new android.widget.ArrayAdapter<>(
                requireContext(), android.R.layout.simple_dropdown_item_1line, items);
            spnProduct.setAdapter(ad);
        }
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
        btnRetourWO      = v.findViewById(R.id.btnRetourWO);
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
        if (txtSerialId != null) txtSerialId.setText("#Série : " + ((serialFromArgs != null && !serialFromArgs.trim().isEmpty()) ? serialFromArgs : "—"));
        if (txtTicketNo != null) txtTicketNo.setText("Ticket Number : —");
        if (txtTicketPending != null) txtTicketPending.setText("Ticket pending : —");
        if (txtDeliveryUid != null) txtDeliveryUid.setText("Delivery UID : —");
        ticketPendingFlag = -1;
        lastDigits = 3;
        if (txtLive != null) txtLive.setText("LIVE: (en attente)");
        if (txtQtyNet != null) txtQtyNet.setText("NET: 0.0");
        if (txtQtyGross != null) txtQtyGross.setText("GROSS: 0.0");
        if (edtPreset != null) edtPreset.setText("50");
        if (spnProduct != null) spnProduct.setText("1", false);
        if (cbShowLog != null) cbShowLog.setChecked(false);
        if (logPanel != null) logPanel.setVisibility(View.GONE);
        logViewSinceMs = 0L;
        if (cbTxRx != null) cbTxRx.setChecked(LogBus.SHOW_IO);
        if (cbLogTs != null) cbLogTs.setChecked(LogBus.SHOW_TS);
        updateButtons(null);
    }

    public void prefillFromDeepLink(String woNum, String produit, String preset) {
        if (edtPreset != null && preset != null && !preset.isEmpty())
            edtPreset.setText(preset);
        if (spnProduct != null && produit != null && !produit.isEmpty())
            spnProduct.setText(produit, false);
        if (txtDeliveryUid != null && woNum != null && !woNum.isEmpty())
            txtDeliveryUid.setText("Delivery UID : " + woNum);
    }

    private void notifyDeliveryEndedToMainActivity() {
        try {
            if (!(getActivity() instanceof MainActivity)) return;
            MainActivity main = (MainActivity) getActivity();

            String ticketNo = "";
            double net      = 0.0;
            double gross    = 0.0;
            String woNum    = "";

            try {
                if (txtTicketNo != null)
                    ticketNo = txtTicketNo.getText().toString()
                                   .replace("Ticket Number : ", "").trim();
                if (txtQtyNet != null)
                    net = Double.parseDouble(
                        txtQtyNet.getText().toString()
                                 .replace("NET: ", "").trim());
                if (txtQtyGross != null)
                    gross = Double.parseDouble(
                        txtQtyGross.getText().toString()
                                   .replace("GROSS: ", "").trim());
                if (txtDeliveryUid != null)
                    woNum = txtDeliveryUid.getText().toString()
                                .replace("Delivery UID : ", "").trim();
            } catch (Exception ignored) {}

            org.json.JSONObject extra = new org.json.JSONObject();
            try {
                if (controller != null) {
                    com.pa.lcr.lcp.ApiResult snap = controller.api_tickSnapshot();
                    if (snap != null && snap.data != null) {
                        extra = snap.data;
                    }
                }
            } catch (Exception ignored) {}

            try {
                extra.put("ticketNo", ticketNo);
                extra.put("netL",     net);
                extra.put("grossL",   gross);
            } catch (Exception ignored) {}

            main.onDeliveryEnded(woNum, extra.toString());

        } catch (Exception ignored) {}
    }

    private void runStatusBLikeButton(String reason) {
        try {
            if (controller == null) return;
            try {
                if (tabTransportKey != null) {
                    MediaTransportManager.get(requireContext()).activateExclusive(tabTransportKey, (reason != null ? reason : "STATUS_B"));
                }
            } catch (Exception ignored) {}
            try {
                controller.requestStatus();
            } catch (Exception e) {
                LogBus.api(node, "Status(B) ERR: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                return;
            }
            ui.postDelayed(() -> {

                  try { if (controller != null) controller.requestLiveSample(); } catch (Exception ignored) {}

                try { if (controller != null) controller.requestLiveSample(); } catch (Exception ignored) {}
            }, 200);
        } catch (Exception ignored) {}
    }

    private void wireUi() {
        if (cbShowLog != null) {
            cbShowLog.setOnCheckedChangeListener((b, checked) -> {
                if (logPanel != null) logPanel.setVisibility(checked ? View.VISIBLE : View.GONE);
                LogBus.ui(node, ts("Afficher log: " + (checked ? "ON" : "OFF")));
                if (checked) { installLogScrollInterceptionFix(); scheduleLogRefresh(); }
            });
        }
        if (btnScrollDown != null && logScroll != null) {
            btnScrollDown.setOnClickListener(v -> scrollLogToBottomOnly());
        }
        if (btnClearLog != null) {
            btnClearLog.setOnClickListener(v -> {
                logViewSinceMs = System.currentTimeMillis();
                if (txtLog != null) txtLog.setText("");
                LogBus.ui(node, ts("Clear log (vue locale)"));
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
        if (btnConnect != null) btnConnect.setOnClickListener(v -> reconnectThisRegister(true));
        if (btnA != null) btnA.setOnClickListener(v -> {
            if (controller == null) return;
            controller.alignOrRecover();
            ui.postDelayed(() -> {
                try {
                    if (tabTransportKey != null) {
                        MediaTransportManager.get(requireContext()).activateExclusive(tabTransportKey, "TAB_A");
                    }
                } catch (Exception ignored) {}
                try { if (controller != null) controller.requestStatus(); } catch (Exception ignored) {}
                try { validateHeaderAsync(); } catch (Exception ignored) {}
                try { if (controller != null) controller.requestLiveSample(); } catch (Exception ignored) {}
                refreshDelCodeFromTickSnapshotThrottled();
                updateButtons(controller != null ? controller.getState() : null);
            }, 900);
        });
        if (btnB != null) btnB.setOnClickListener(v -> {
            if (controller == null) { reconnectThisRegister(true); return; }
            runStatusBLikeButton("STATUS_B");
        });
        if (btnC != null) {
            btnC.setOnClickListener(v -> {
                if (controller == null) return;
                refreshDelCodeFromTickSnapshotThrottled();
                int dc = lastDelCode;
                boolean tp = (dc & 0x0001) != 0;
                boolean flow = (dc & 0x0004) != 0;
                boolean act = (dc & 0x0008) != 0;
                if (tp || flow || act) {
                    if (txtLive != null) txtLive.setText("LIVE: registre non prêt — faire Status (B) / Resolve (A)");
                    LogBus.ui(node, ts("C bloqué: delCode=0x" + Integer.toHexString(dc)));
                    updateButtons(controller.getState());
                    return;
                }
                starting = true;
                startingSinceMs = System.currentTimeMillis();
                updateButtons(controller.getState());
                if (txtLive != null) txtLive.setText("LIVE: RUNNING_FLOWING (flow off - waiting progression)");
                int prod = getPendingProduct();
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
            ui.postDelayed(() -> {
                try { if (controller != null) controller.requestStatus(); } catch (Exception ignored) {}
                try { validateHeaderAsync(); } catch (Exception ignored) {}
                try { if (controller != null) controller.requestLiveSample(); } catch (Exception ignored) {}
                refreshDelCodeFromTickSnapshotThrottled();
                updateButtons(controller != null ? controller.getState() : null);
            }, 1500);
        });

        // ✅ REPRINT: câblage du bouton Reprint (last ticket)
        if (btnReprintTicket != null) {
            btnReprintTicket.setOnClickListener(v -> {
                DeliveryController c = controller;
                if (c == null) return;
                bg.execute(() -> {
                    try {
                        if (tabTransportKey != null) {
                            MediaTransportManager.get(requireContext())
                                    .activateExclusive(tabTransportKey, "REPRINT");
                        }
                        ApiResult r = c.api_ticketReprintCurrent();
                        LogBus.api(node, "[REPRINT] " + (r != null ? r.msg : "null"));
                    } catch (Exception e) {
                        LogBus.api(node, "[REPRINT] ERR: " + safeMsg(e));
                    }
                });
            });
        }

        // ✅ RETOUR WO: câblage du bouton Retour au Work Order (Bloc 5)
        if (btnRetourWO != null) {
            btnRetourWO.setOnClickListener(v -> retournerAuWorkOrder());
        }
    }

    public void onTabMediaStatusChanged(boolean ready, String mediaShort) {
        tabMediaReady = ready;
        tabMediaShort = (mediaShort == null || mediaShort.trim().isEmpty()) ? "—" : mediaShort.trim();
        ui.post(() -> {
            if (!isAdded() || getView() == null) return;
            if (!tabMediaReady) {
                if (txtLive != null) txtLive.setText("LIVE: " + tabMediaShort + "(OFF) — reconnect requis");
                // Ne pas nullifier controller si transport suspendu — garder pour reconnexion rapide
                pendingReconnect = true;
                updateButtons(controller != null ? controller.getState() : null);
                return;
            }
            if (pendingReconnect) { pendingReconnect = false; reconnectThisRegister(false); }
        });
    }

    private void reconnectThisRegister(boolean userInitiated) {
        if (!tabMediaReady) {
            pendingReconnect = true;
            if (userInitiated) {
                try { Toast.makeText(requireContext(), tabMediaShort + "(OFF) — en attente…", Toast.LENGTH_SHORT).show(); } catch (Exception ignored) {}
            }
            return;
        }
        try { detachUiListenerSafe(); } catch (Exception ignored) {}
        controller = null;
        starting = false;
        ticketPendingFlag = -1;
        connectThisRegister(userInitiated);
    }

    private void attemptAttachIfPossible(boolean verboseLog) {
        if (!tabMediaReady && !pendingReconnect) return;
        if (uiListenerAttached && controller != null) { syncUiFromController(); return; }
        connectThisRegister(false);
    }


    private void connectThisRegister(boolean userInitiated) {
        // Si controller existe et transport prêt — pas besoin de recréer
        if (controller != null && tabMediaReady) {
            syncUiFromController();
            validateHeaderAsync();
            ui.postDelayed(() -> runStatusBLikeButton("TAB_REACTIVATED"), 250);
            return;
        }
        RegisterSessionManager sm = RegisterSessionManager.get(requireContext());
        DeliveryController dc = null;

        if (tabTransportKey != null && !tabTransportKey.trim().isEmpty()) {
            final String tkPinned = tabTransportKey.trim();
            try { MediaTransportManager.get(requireContext()).activateExclusive(tkPinned, "TAB_CONNECT"); } catch (Exception ignored) {}
            TransportIo io = null;
            try { io = MediaTransportManager.get(requireContext()).getByKey(tkPinned); } catch (Exception ignored) {}
            if (io == null || !io.isOpen()) {
                tabMediaReady = false;
                pendingReconnect = true;
                String msg = tabMediaShort + "(OFF) — impossible de connecter";
                LogBus.api(node, msg);
                reportMediaOffToApi("CONNECT_CLICK", msg);
                ui.post(() -> {
                    if (!isAdded() || getView() == null) return;
                    if (txtLive != null) txtLive.setText("LIVE: " + tabMediaShort + "(OFF) — reconnect requis");
                    updateButtons(null);
                    if (userInitiated) {
                        try { Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show(); } catch (Exception ignored2) {}
                    }
                });
                return;
            }
            dc = sm.getOrCreate(tkPinned, node, from, io);
        }
        if (dc == null && (tabTransportKey == null || tabTransportKey.trim().isEmpty())) {
            dc = sm.resolveOrCreateForNode(node, from);
        }
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
            // Ne mettre à jour tabTransportKey que si cohérent avec transportFromArgs
            if (tk != null && !tk.trim().isEmpty()) {
                if (transportFromArgs == null || transportFromArgs.trim().isEmpty()) {
                    tabTransportKey = tk.trim();
                } else if (tk.trim().equalsIgnoreCase(transportFromArgs.trim())) {
                    tabTransportKey = tk.trim();
                }
                // Sinon garder tabTransportKey original — ne pas écraser avec mauvais transport
            }
        } catch (Exception ignored) {}

        try {
            if (tabTransportKey != null) MediaTransportManager.get(requireContext()).activateExclusive(tabTransportKey, "TAB_CONNECT_FINAL");
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
        ui.postDelayed(() -> runStatusBLikeButton("AUTO_AFTER_TAB_CREATE"), 250);
        if (userInitiated) LogBus.api(node, "Connect TAB: 1 - UI attached");
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
    }

    private void syncUiFromController() {
        if (controller == null) return;
        DeliveryState st = controller.getState();
        refreshDelCodeFromTickSnapshotThrottled();
        updateButtons(st);
    }

    private void ensureSerialVisibleThrottled() {
        try {
            if (txtSerialId == null) return;
            if (headerValidatedOnce) return;
            String cur = String.valueOf(txtSerialId.getText());
            boolean missing = (cur.contains("—") || cur.trim().endsWith(":") || cur.trim().endsWith(": —"));
            if (!missing) return;
            long now = System.currentTimeMillis();
            if (now - lastHeaderValidateMs < HEADER_VALIDATE_MIN_MS) return;
            lastHeaderValidateMs = now;
            validateHeaderAsync();
        } catch (Exception ignored) {}
    }

    private void validateHeaderAsync() {
        try { if (bg.isShutdown() || bg.isTerminated()) return; } catch (Exception ignored) {}
        try {
            bg.execute(() -> {
                try {
                    DeliveryController c = controller;
                    if (c == null) return;

                    // Si serial déjà connu depuis args, l'utiliser directement sans naviguer Mode 8
                    String serial = (serialFromArgs != null && !serialFromArgs.trim().isEmpty())
                        ? serialFromArgs.trim() : "";
                    int tp = -1;
                    if (serial.isEmpty()) {
                        ApiResult r = c.api_registerValidate(null, node, null, null, null, false);
                        JSONObject j = r.toJson().optJSONObject("data");
                        if (j == null) return;
                        serial = j.optString("serial_id", "");
                        tp = j.optInt("ticketPending", -1);
                    } else {
                        ApiResult r = c.api_registerValidate(null, node, null, null, null, false);
                        JSONObject j = r != null ? r.toJson().optJSONObject("data") : null;
                        if (j != null) tp = j.optInt("ticketPending", -1);
                    }

                    try { RegisterSessionManager.get(requireContext()).bindExpectedSerial(node, serial); } catch (Exception ignored) {}
                    // int tp = j.optInt("ticketPending", -1);
                    ticketPendingFlag = (tp == 1 ? 1 : (tp == 0 ? 0 : -1));

                    final String fSerial = serial;
                    ui.post(() -> {
                        if (!isAdded() || getView() == null) return;
                        if (txtSerialId != null) {
                            txtSerialId.setText("#Série : " + ((fSerial == null || fSerial.isEmpty()) ? "—" : fSerial));
                            if (fSerial != null && !fSerial.trim().isEmpty()) headerValidatedOnce = true;
                        }

                        if (txtTicketPending != null) {
                            txtTicketPending.setText("Ticket pending : " +
                                    (ticketPendingFlag == 1 ? "OUI" : (ticketPendingFlag == 0 ? "NON" : "—")));
                        }
                        refreshDelCodeFromTickSnapshotThrottled();
                        updateButtons(controller != null ? controller.getState() : null);
                        scheduleLogRefresh();
                    });
                } catch (Exception e) {
                    LogBus.api(node, "validate header fail: " + safeMsg(e));
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {}
    }

    private void updateButtons(DeliveryState state) {
        if (btnConnect == null || btnA == null || btnB == null || btnC == null
                || btnContinue == null || btnFinish == null) return;

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
        boolean paused    = (st == DeliveryState.RUNNING_PAUSED);
        boolean flowing   = (st == DeliveryState.RUNNING_FLOWING);
        boolean ending    = (st == DeliveryState.ENDING);

        btnConnect.setEnabled(true);
        btnB.setEnabled(true);
        btnA.setEnabled(connected || paused || flowing);

        int dc = lastDelCode;
        boolean tp   = (dc & 0x0001) != 0;
        boolean flow = (dc & 0x0004) != 0;
        boolean act  = (dc & 0x0008) != 0;
        btnC.setEnabled(connected && !tp && !flow && !act);

        if (starting) {
            btnContinue.setEnabled(false);
            btnFinish.setEnabled(false);
        } else {
            String lt = lastLiveText;
            boolean flowOffPhase = (lt != null && lt.contains("Flow OFF"));
            boolean enable = paused || flowOffPhase;
            btnContinue.setEnabled(enable);
            btnFinish.setEnabled(enable);
        }

        // ✅ REPRINT: actif sur CONNECTED, RUNNING_PAUSED, ENDING
        if (btnReprintTicket != null) {
            btnReprintTicket.setEnabled(connected || paused || ending);
        }

        // ✅ RETOUR WO: visible seulement quand livraison terminée (CONNECTED post-livraison)
        // et qu'on a des données de livraison (ticket non vide)
        if (btnRetourWO != null) {
            boolean hasDeliveryData = false;
            try {
                String ticket = txtTicketNo != null ?
                    txtTicketNo.getText().toString().replace("Ticket Number : ", "").trim() : "";
                hasDeliveryData = !ticket.isEmpty() && !ticket.equals("—");
            } catch (Exception ignored) {}
            btnRetourWO.setVisibility(connected && hasDeliveryData
                ? android.view.View.VISIBLE : android.view.View.GONE);
        }
    }

    // =========================================================
    // ✅ Retour au Work Order — Bloc 5
    // Compile payload complet → SQLite local → MSAL push → deep link FSM
    // =========================================================
    private void retournerAuWorkOrder() {
        if (!(getActivity() instanceof MainActivity)) return;
        MainActivity main = (MainActivity) getActivity();

        // Désactiver le bouton pendant le traitement
        if (btnRetourWO != null) btnRetourWO.setEnabled(false);

        bg.execute(() -> {
            try {
                // 1. Compiler le payload complet
                String ticketNo   = "";
                String saleNo     = "";
                double netL       = 0.0;
                double grossL     = 0.0;
                String woNum      = "";
                String woIdGuid   = "";
                String payloadJson = "{}";

                try {
                    if (txtTicketNo != null)
                        ticketNo = txtTicketNo.getText().toString()
                            .replace("Ticket Number : ", "").trim();
                    if (txtQtyNet != null)
                        netL = Double.parseDouble(
                            txtQtyNet.getText().toString().replace("NET: ", "").trim());
                    if (txtQtyGross != null)
                        grossL = Double.parseDouble(
                            txtQtyGross.getText().toString().replace("GROSS: ", "").trim());
                    if (txtDeliveryUid != null)
                        woNum = txtDeliveryUid.getText().toString()
                            .replace("Delivery UID : ", "").trim();
                } catch (Exception ignored) {}

                // Récupérer snapshot complet du controller
                org.json.JSONObject snap = new org.json.JSONObject();
                try {
                    if (controller != null) {
                        ApiResult sr = controller.api_tickSnapshot();
                        if (sr != null && sr.data != null) {
                            snap = sr.data;
                            // Extraire saleNo depuis snap
                            saleNo = snap.optString("sale_no", "");
                            // Extraire woIdGuid depuis result si disponible
                            org.json.JSONObject result = snap.optJSONObject("result");
                            if (result != null) {
                                if (netL == 0.0)   netL   = result.optDouble("fs_net_l",   netL);
                                if (grossL == 0.0) grossL = result.optDouble("fs_gross_l", grossL);
                                if (ticketNo.isEmpty()) ticketNo = result.optString("ticket_no", "");
                                if (saleNo.isEmpty())   saleNo   = result.optString("sale_no", "");
                            }
                        }
                    }
                } catch (Exception ignored) {}

                // Récupérer woIdGuid depuis ActiveDeliveryStore
                try {
                    com.pa.lcr.lcp.storage.ActiveDeliveryStore ads =
                        new com.pa.lcr.lcp.storage.ActiveDeliveryStore(requireContext());
                    com.pa.lcr.lcp.storage.ActiveDeliveryStore.ActiveDelivery active = ads.load();
                    if (active != null) {
                        if (active.woIdGuid != null && !active.woIdGuid.isEmpty())
                            woIdGuid = active.woIdGuid;
                        if (woNum.isEmpty() && active.woNum != null)
                            woNum = active.woNum;
                    }
                } catch (Exception ignored) {}

                snap.put("ticketNo", ticketNo);
                snap.put("saleNo",   saleNo);
                snap.put("netL",     netL);
                snap.put("grossL",   grossL);
                snap.put("woNum",    woNum);
                snap.put("woIdGuid", woIdGuid);
                payloadJson = snap.toString();

                // 2. Écrire dans LcrDeliveryStatusDb — PENDING
                android.content.ContentValues cv = new android.content.ContentValues();
                cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_WO_NUM,      woNum);
                cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_WO_ID_GUID,  woIdGuid);
                cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_TICKET_NO,   ticketNo);
                cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_SALE_NO,     saleNo);
                cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_NET_L,       netL);
                cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_GROSS_L,     grossL);
                cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_TYPE,
                    com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.TYPE_ORIGINAL);
                cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_SOURCE,      "REGISTRE");
                cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_STOP_TYPE,   "LIVRAISON");
                cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_SYNC_STATUS,
                    com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.SYNC_PENDING);
                cv.put(com.pa.lcr.lcp.storage.LcrDeliveryStatusDb.COL_PAYLOAD_JSON, payloadJson);

                com.pa.lcr.lcp.storage.LcrDeliveryStatusDb lcrDb =
                    new com.pa.lcr.lcp.storage.LcrDeliveryStatusDb(requireContext());
                long localId = lcrDb.insertDelivery(cv);
                android.util.Log.i("RetourWO", "Delivery sauvegardée localId=" + localId
                    + " wo=" + woNum + " net=" + netL + " gross=" + grossL);

                // 3. Tenter push MSAL vers Dataverse
                try {
                    com.pa.lcrdemo.auth.MsalTokenProvider msal =
                        new com.pa.lcrdemo.auth.MsalTokenProvider(requireContext());
                    final String[] tokenHolder = {null};
                    final java.util.concurrent.CountDownLatch latch =
                        new java.util.concurrent.CountDownLatch(1);
                    msal.init(new com.pa.lcrdemo.auth.MsalTokenProvider.InitCallback() {
                        @Override public void onReady() {
                            msal.acquireTokenSilentFromWorker(
                                new com.pa.lcrdemo.auth.MsalTokenProvider.TokenCallback() {
                                    @Override public void onSuccess(String token) {
                                        tokenHolder[0] = token;
                                        latch.countDown();
                                    }
                                    @Override public void onError(Exception e) {
                                        android.util.Log.w("RetourWO", "Token silent ERR: " + e.getMessage());
                                        latch.countDown();
                                    }
                                });
                        }
                        @Override public void onError(Exception e) {
                            android.util.Log.w("RetourWO", "MSAL init ERR: " + e.getMessage());
                            latch.countDown();
                        }
                    });
                    latch.await(8, java.util.concurrent.TimeUnit.SECONDS);
                    if (tokenHolder[0] != null) {
                        com.pa.lcrdemo.dataverse.LcrDeliverySync.pushPending(
                            requireContext(), tokenHolder[0]);
                        android.util.Log.i("RetourWO", "Push Dataverse OK");
                    } else {
                        android.util.Log.w("RetourWO", "Pas de token — données en PENDING local");
                    }
                } catch (Exception e) {
                    android.util.Log.w("RetourWO", "Push Dataverse ERR: " + e.getMessage());
                }

                // 4. Effacer ActiveDeliveryStore
                try {
                    new com.pa.lcr.lcp.storage.ActiveDeliveryStore(requireContext()).clear();
                } catch (Exception ignored) {}

                // 5. Retour FSM via finish() — taskAffinity ramène FSM au premier plan
                final String fWoIdGuid = woIdGuid;
                ui.post(() -> {
                    try {
                        android.util.Log.i("RetourWO", "finish() → FSM via taskAffinity woId=" + fWoIdGuid);
                        if (getActivity() != null) getActivity().finish();
                    } catch (Exception e) {
                        android.util.Log.e("RetourWO", "finish() ERR: " + e.getMessage());
                    } finally {
                        if (btnRetourWO != null) btnRetourWO.setEnabled(true);
                    }
                });

            } catch (Exception e) {
                android.util.Log.e("RetourWO", "retournerAuWorkOrder ERR: " + e.getMessage());
                ui.post(() -> {
                    android.widget.Toast.makeText(requireContext(),
                        "Erreur retour WO: " + e.getMessage(),
                        android.widget.Toast.LENGTH_SHORT).show();
                    if (btnRetourWO != null) btnRetourWO.setEnabled(true);
                });
            }
        });
    }

    private void refreshLogView() {
        if (txtLog == null) return;
        if (cbShowLog == null || !cbShowLog.isChecked()) return;
        long now0 = System.currentTimeMillis();
        if (logUserScrolling && now0 < logUserScrollUntilMs) return;
        if (Looper.myLooper() != Looper.getMainLooper()) { ui.post(this::refreshLogView); return; }
        if (!isAdded() || getView() == null) return;
        List<LogBus.LogEvent> events = LogBus.snapshotForNode(node, TAB_LOG_MAX_LINES);
        if (logViewSinceMs > 0) {
            ArrayList<LogBus.LogEvent> filtered = new ArrayList<>(events.size());
            for (LogBus.LogEvent e : events) { if (e.ts >= logViewSinceMs) filtered.add(e); }
            events = filtered;
        }
        txtLog.setText(LogBus.buildText(events));
    }

    private String ts(String msg) { if (!logTsEnabled) return msg; return uiTs() + " " + msg; }

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

    public static RegisterTabFragment newInstance(int node, int from, String serialId, String transportKey) {
        RegisterTabFragment f = new RegisterTabFragment();
        Bundle b = new Bundle();
        b.putInt(ARG_NODE, node);
        b.putInt(ARG_FROM, from);
        if (serialId != null) b.putString(ARG_SERIAL, serialId);
        if (transportKey != null) b.putString(ARG_TRANSPORT, transportKey);
        f.setArguments(b);
        return f;
    }

    private void reportMediaOffToApi(String origin, String detail) {
        try {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).reportMediaNotReadyFromTab(node, serialFromArgs, tabTransportKey, origin, detail);
            }
        } catch (Exception ignored) {}
    }
    private int getPendingProduct() {
        if (spnProduct != null) {
            String txt = spnProduct.getText().toString().trim();
            if (!txt.isEmpty()) {
                try { return Integer.parseInt(txt); } catch (Exception ignored) {}
            }
        }
        return 1;
    }

    public String getSerialFromArgs() { return serialFromArgs; }

    public int getNodeFromArgs() {
        Bundle a = getArguments();
        return a != null ? a.getInt(ARG_NODE, 250) : 250;
    }
}
