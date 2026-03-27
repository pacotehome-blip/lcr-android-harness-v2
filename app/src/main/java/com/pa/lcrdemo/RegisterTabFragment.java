
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

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * RegisterTabFragment (node-specific) — v7 (basé sur ton fichier actuel)
 *
 * Objectifs:
 * - Pas de jump quand on utilise DOWN (scroll log seulement)
 * - Scroll manuel dans le log
 * - Pas de polling status inutile quand READY/TicketPending (Status = B + post-A + post-END)
 * - C (New) gouverné par delCode (0x28) cache-only via api_tickSnapshot()
 * - Format NET/GROSS stable (digits+1) pour réduire l'effet "saut"
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

    // v7: delCode (0x28) cache-only via TickBus snapshot
    private volatile int lastDelCode = 0;
    private volatile long lastDelCodePollMs = 0L;
    private static final long DELCODE_POLL_MIN_MS = 800L;

    // v7: throttle validate header retry (serial #80)
    private volatile long lastHeaderValidateMs = 0L;
    private static final long HEADER_VALIDATE_MIN_MS = 5000L;

    // v7: stop auto-validate once serial acquired
    private volatile boolean headerValidatedOnce = false;

    // UX: suspendre le refresh du log pendant le scroll utilisateur (évite re-layout/jump)
    private volatile boolean logUserScrolling = false;
    private volatile long logUserScrollUntilMs = 0L;

    // Auto-attach lifecycle
    private boolean attemptedAutoAttachOnce = false;
    private boolean uiListenerAttached = false;

    private UsbManager usbManager;

    // Controller partagé UI ↔ API (RegisterSessionManager)
    private DeliveryController controller;

    // clé du transport réellement attaché (USB ou BT:..)
    private String tabTransportKey = null;

    // Start UX
    private boolean starting = false;
    private long startingSinceMs = 0L;

    // Throttle/coalesce log refresh
    private static final int TAB_LOG_MAX_LINES = 400;
    private static final long LOG_REFRESH_MIN_MS = 800; // réduit le jank
    private long lastLogRefreshMs = 0L;
    private boolean logRefreshPending = false;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    /** ✅ Log refresh toujours sur UI thread + suspend pendant scroll utilisateur. */
    private void scheduleLogRefresh() {
        if (txtLog == null) return;
        if (cbShowLog == null || !cbShowLog.isChecked()) return;

        // suspend refresh pendant interaction log (sinon re-layout + jump)
        long now0 = System.currentTimeMillis();
        if (logUserScrolling && now0 < logUserScrollUntilMs) return;

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

    // ---------------------------------------------------------
    // v7: delCode cache-only (TickBus) pour décider A/B/C sans spam LCP
    // ---------------------------------------------------------
    private void refreshDelCodeFromTickSnapshotThrottled() {
        try {
            DeliveryController c = controller;
            if (c == null) return;
            long now = System.currentTimeMillis();
            if (now - lastDelCodePollMs < DELCODE_POLL_MIN_MS) return;
            lastDelCodePollMs = now;

            ApiResult r = c.api_tickSnapshot(); // cache-only
            JSONObject d = (r != null) ? r.data : null;
            if (d != null) lastDelCode = d.optInt("delCode", lastDelCode);
        } catch (Exception ignored) {}
    }

    // ✅ UX: permettre le scroll dans la zone log sans que le NestedScrollView vole le geste
    private void installLogScrollInterceptionFix() {
        try {
            if (logScroll != null) {
                logScroll.setOnTouchListener((v, ev) -> {
                    try {
                        if (regRootScroll != null) regRootScroll.requestDisallowInterceptTouchEvent(true);
                    } catch (Exception ignored) {}

                    // marque l'utilisateur en train de scroller (suspend refresh)
                    int action = ev.getActionMasked();
                    if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                        logUserScrolling = true;
                        logUserScrollUntilMs = System.currentTimeMillis() + 1200L;
                    } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                        logUserScrolling = false;
                        logUserScrollUntilMs = System.currentTimeMillis() + 400L;
                    }
                    return false;
                });
            }

            if (txtLog != null) {
                txtLog.setOnTouchListener((v, ev) -> {
                    try {
                        if (regRootScroll != null) regRootScroll.requestDisallowInterceptTouchEvent(true);
                    } catch (Exception ignored) {}

                    int action = ev.getActionMasked();
                    if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                        logUserScrolling = true;
                        logUserScrollUntilMs = System.currentTimeMillis() + 1200L;
                    } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                        logUserScrolling = false;
                        logUserScrollUntilMs = System.currentTimeMillis() + 400L;
                    }
                    return false;
                });
            }
        } catch (Exception ignored) {}
    }

    // ✅ UX: bouton DOWN ne doit scroller que le log (pas le tab au complet)
    private void scrollLogToBottomOnly() {
        try {
            if (logScroll == null) return;

            final int rootY = (regRootScroll != null) ? regRootScroll.getScrollY() : -1;

            // Bloquer le focus descendant pendant l'action => empêche le jump
            try {
                if (regRootScroll != null) regRootScroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
                if (regRootScroll != null) regRootScroll.requestDisallowInterceptTouchEvent(true);
            } catch (Exception ignored) {}

            // Suspend refresh log pendant ce scroll (évite re-layout)
            logUserScrolling = true;
            logUserScrollUntilMs = System.currentTimeMillis() + 1200L;

            logScroll.post(() -> {
                try {
                    logScroll.requestFocus();
                    logScroll.fullScroll(View.FOCUS_DOWN);

                    // Restaurer le tab si Android l'a bougé
                    if (regRootScroll != null && rootY >= 0) regRootScroll.scrollTo(0, rootY);
                } catch (Exception ignored) {
                } finally {
                    try {
                        if (regRootScroll != null) regRootScroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
                    } catch (Exception ignored) {}
                    logUserScrolling = false;
                    logUserScrollUntilMs = System.currentTimeMillis() + 400L;
                }
            });
        } catch (Exception ignored) {}
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

                refreshDelCodeFromTickSnapshotThrottled();
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

                int d = lastDigits;
                try { if (controller != null) d = controller.getDisplayDigits(); } catch (Exception ignored) {}
                if (d < 0) d = 3;
                if (d > 6) d = 6;
                lastDigits = d;

                // Affiche 1 décimale de plus pour réduire l'effet "saut"
                int show = Math.min(6, Math.max(0, d + 1));
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

                try {
                    if (liveText != null && (liveText.contains("FLOW ON") || liveText.contains("PAUSED") || liveText.contains("confirm"))) {
                        starting = false;
                    }
                } catch (Exception ignored) {}

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
                // si on est attaché sur BT, ignorer le detach USB
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

        // DOWN: scroll bas du LOG seulement (anti-jump)
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

        // A: Resolve + refresh unique (status + validate + delCode)
        if (btnA != null) btnA.setOnClickListener(v -> {
            if (controller == null) return;
            controller.alignOrRecover();
            ui.postDelayed(() -> {
                try { if (controller != null) controller.requestStatus(); } catch (Exception ignored) {}
                try { validateHeaderAsync(); } catch (Exception ignored) {}
                refreshDelCodeFromTickSnapshotThrottled();
                updateButtons(controller != null ? controller.getState() : null);
            }, 900);
        });

        // B: Status manuel seulement
        if (btnB != null) btnB.setOnClickListener(v -> { if (controller != null) controller.requestStatus(); });

        if (btnC != null) {
            btnC.setOnClickListener(v -> {
                if (controller == null) return;

                // C dépend du delCode cache-only
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

            // post-END: refresh unique (status + validate + delCode)
            ui.postDelayed(() -> {
                try { if (controller != null) controller.requestStatus(); } catch (Exception ignored) {}
                try { validateHeaderAsync(); } catch (Exception ignored) {}
                refreshDelCodeFromTickSnapshotThrottled();
                updateButtons(controller != null ? controller.getState() : null);
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

                    try { if (controller != null) controller.requestStatus(); } catch (Exception ignored) {}
                    try { validateHeaderAsync(); } catch (Exception ignored) {}

                    refreshDelCodeFromTickSnapshotThrottled();
                    updateButtons(controller != null ? controller.getState() : null);
                    scheduleLogRefresh();
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {}
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
