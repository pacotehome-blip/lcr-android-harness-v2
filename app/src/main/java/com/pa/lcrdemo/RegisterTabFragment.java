
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
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.pa.lcr.lcp.*;
import com.pa.lcr.lcp.log.LogBus;
import com.pa.lcr.lcp.transport.MediaTransportManager;
import com.pa.lcr.lcp.transport.TransportIo;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * RegisterTabFragment (node-specific)
 *
 * Correctifs intégrés:
 * - ✅ Log tab: refresh toujours sur UI thread (évite "log n'affiche plus")
 * - ✅ Option A: NO auto-scroll du log + préserver scroll du tab (évite le jump vers le haut)
 * - ✅ Anti-crash: bg executor / lifecycle guards (évite RejectedExecutionException après rebuild)
 *
 * ✅ AJOUT: bouton Reprint (last ticket)
 * - TicketNo: on tente d’utiliser celui affiché dans Ticket Number : … (digits). Si vide -> pas de ticket.
 * - Option A: si ticketPending est ON -> bouton désactivé + message "faire Resolve (A)"
 * - Appel direct controller.api_ticketReprintCurrent() (non bloquant UI) il faut que ca marche
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

    // ✅ NEW: Reprint button
    private Button btnReprintTicket;

    // Root scroll (tab)
    private NestedScrollView regRootScroll;

    // Log tab
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

    // Auto-attach lifecycle
    private boolean attemptedAutoAttachOnce = false;
    private boolean uiListenerAttached = false;

    private UsbManager usbManager;

    // Controller partagé UI ↔ API (RegisterSessionManager)
    private DeliveryController controller;

    // ✅ Media-aware TAB (USB/BT)
    private MediaTransportManager mediaMgr;
    private String tabTransportKey = null;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

 // =========================
 // ✅ LIVE Tick loop (cache-only) : UI == API == registre
 // - Appelle controller.api_tickWait(...) (TickBus, sans LCP IO)
 // - Met à jour NET/GROSS + LIVE + ticketPending
 // =========================
 private volatile boolean tickLoopRunning = false;
 private long lastTickSeq = 0L;
 private ExecutorService tickExec = Executors.newSingleThreadExecutor();
 private volatile long lastHeaderRefreshMs = 0L;


    // Start UX
    private boolean starting = false;
    private long startingSinceMs = 0L;

    // Throttle/coalesce log refresh
    private static final int TAB_LOG_MAX_LINES = 400;
    private static final long LOG_REFRESH_MIN_MS = 300;
    private long lastLogRefreshMs = 0L;
    private boolean logRefreshPending = false;

 // ✅ Perf: throttling UI-triggered status reads (réduit lag)
 private volatile long lastUiStatusReqMs = 0L;
 private static final long UI_STATUS_MIN_MS = 1500L;

 // ✅ Tick: keep track of delivery transition for resync (nouvelle livraison)
 private volatile boolean lastTickDeliveryActive = false;

 // ✅ Live sample cadence (quand FLOW_ACTIVE=1) - pour ticks fluides comme l’API
 private volatile long lastLiveSampleReqMs = 0L;
 private static final long LIVE_SAMPLE_MIN_MS = 250L;


    /**
     * ✅ FIX LOG: toujours exécuter la logique de refresh sur UI thread.
     */
    private void scheduleLogRefresh() {
        if (txtLog == null) return;
        if (cbShowLog == null || !cbShowLog.isChecked()) return;

        // Force UI thread
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

    private final DeliveryControllerPort.Listener uiListener = new DeliveryControllerPort.Listener() {

        @Override
        public void onStateChanged(DeliveryState state) {
            ui.post(() -> {
                if (!isAdded() || getView() == null) return;

                if (starting && state == DeliveryState.RUNNING_FLOWING) starting = false;
                if (starting && (System.currentTimeMillis() - startingSinceMs) > 12000L) starting = false;

                updateButtons(state);
                try {
            long now = System.currentTimeMillis();
            if (controller != null && (now - lastUiStatusReqMs) >= UI_STATUS_MIN_MS) {
                lastUiStatusReqMs = now;
                controller.requestStatus();
            }
        } catch (Exception ignored) {}
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
                if (txtQtyNet != null) txtQtyNet.setText(String.format(Locale.ROOT, "NET: %.3f", net));
                if (txtQtyGross != null) txtQtyGross.setText(String.format(Locale.ROOT, "GROSS: %.3f", gross));
            });
        }

        @Override
        public void onLiveStatus(String liveText) {
            ui.post(() -> {
                if (!isAdded() || getView() == null) return;
                if (starting) {
                    if (txtLive != null) txtLive.setText("LIVE: RUNNING_FLOWING (flow off - waiting progression)");
                } else {
                    if (txtLive != null) txtLive.setText(liveText);
                }
            });
        }

        @Override
        public void onTicketInfo(String ticketNo, String deliveryUid) {
            ui.post(() -> {
                if (!isAdded() || getView() == null) return;

                if (txtTicketNo != null) txtTicketNo.setText("Ticket Number : " + (ticketNo == null ? "—" : ticketNo));
                if (txtDeliveryUid != null) txtDeliveryUid.setText("Delivery UID : " + (deliveryUid == null ? "—" : deliveryUid));

                // ✅ Re-evaluate enablement now that ticket number may have changed
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
            // ✅ Media-aware: ignorer USB DETACHED si ce TAB est attaché sur BT
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
        try { mediaMgr = MediaTransportManager.get(context); } catch (Exception ignored) {}
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
 stopTickLoop();
        detachUiListenerSafe();
        LogBus.removeListener(logListener);
        try { requireContext().unregisterReceiver(usbStateReceiver); } catch (Exception ignored) {}
        super.onStop();
    }

    @Override
    public void onResume() {
        super.onResume();
 // ✅ LIVE tick loop
 startTickLoop();
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
 // ✅ Stop LIVE tick loop
 stopTickLoop();
        // ✅ Anti-crash: empêcher des ui.post() (log + attach) de survivre à la destruction de la view
        try { ui.removeCallbacksAndMessages(null); } catch (Exception ignored) {}
        // Stopper executor du fragment
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

        // ✅ NEW
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
        ticketPendingFlag = -1;

        if (txtLive != null) txtLive.setText("LIVE: (en attente)");
        if (txtQtyNet != null) txtQtyNet.setText("NET: 0.0");
        if (txtQtyGross != null) txtQtyGross.setText("GROSS: 0.0");

        if (txtDeliveryUid != null) txtDeliveryUid.setText("Delivery UID : —");

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
                if (checked) scheduleLogRefresh();
            });
        }

        if (btnScrollDown != null && logScroll != null) {
            btnScrollDown.setOnClickListener(v -> logScroll.fullScroll(View.FOCUS_DOWN));
        }

        if (btnClearLog != null) {
            btnClearLog.setOnClickListener(v -> {
                logViewSinceMs = System.currentTimeMillis();
                if (txtLog != null) txtLog.setText("");
                LogBus.ui(node, ts("Clear log (vue locale)"));
                scheduleLogRefresh();
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

        if (btnContinue != null) btnContinue.setOnClickListener(v -> { if (controller != null) controller.resumeIfPaused(); });
        if (btnFinish != null) btnFinish.setOnClickListener(v -> { if (controller != null) controller.endDelivery(); });

        // ✅ NEW: Reprint
        if (btnReprintTicket != null) {
            btnReprintTicket.setOnClickListener(v -> onReprintClicked());
        }
    }

    // =========================================================
    // ✅ Reprint (last ticket) logic
    // =========================================================
    private void onReprintClicked() {
        if (controller == null) return;

        // Option A: si ticketPending ON -> faire Resolve (A)
        if (ticketPendingFlag == 1) {
            if (txtLive != null) txtLive.setText("LIVE: ticket_pending — faire Resolve (A)");
            LogBus.ui(node, ts("Reprint bloqué: ticket_pending=1 -> faire Resolve (A)"));
            scheduleLogRefresh();
            return;
        }

        // TicketNo: digits de "Ticket Number : ..."
        String ticketNo = extractTicketDigits();
        if (ticketNo == null || ticketNo.trim().isEmpty()) {
            LogBus.ui(node, ts("Reprint: aucun ticket_no affiché -> rien à re-imprimer"));
            try { Toast.makeText(requireContext(), "Aucun ticket à re-imprimer", Toast.LENGTH_SHORT).show(); } catch (Exception ignored) {}
            scheduleLogRefresh();
            return;
        }

        // Désactiver pour éviter double click (sera recalculé ensuite)
        if (btnReprintTicket != null) btnReprintTicket.setEnabled(false);

        LogBus.api(node, "[REPRINT] request (ticket_no=" + ticketNo + ")");
        scheduleLogRefresh();

        // Exécuter en background pour ne pas bloquer l’UI
        try {
            if (bg.isShutdown() || bg.isTerminated()) return;
        } catch (Exception ignored) {}

        try {
            bg.execute(() -> {
                ApiResult r;
                try {
                    // Appel direct au controller (reprint last ticket via 0x06, gate ticketPending côté controller)
                    r = controller.api_ticketReprintCurrent();
                } catch (Exception e) {
                    r = ApiResult.fail("Reprint: 0 - Exception", "REPRINT_FAIL");
                }

                final ApiResult rr = r;
                ui.post(() -> {
                    if (!isAdded() || getView() == null) return;

                    // Log résultat
                    try {
                        String err = (rr.err == null) ? "" : rr.err;
                        LogBus.api(node, "[REPRINT] resp code=" + rr.code + " err=" + err + " msg=" + rr.msg);
                    } catch (Exception ignored) {}

                    if (rr.code == 0 && "TICKET_PENDING".equals(rr.err)) {
                        if (txtLive != null) txtLive.setText("LIVE: ticket_pending — faire Resolve (A)");
                    }

                    // Re-enable selon règles
                    updateButtons(controller != null ? controller.getState() : null);
                    scheduleLogRefresh();
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
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
 // ✅ LIVE tick loop (TickBus /v1/tick/wait) — cache-only
 // =========================================================
 private void startTickLoop() {
     if (tickLoopRunning) return;
     tickLoopRunning = true;
     // Réinitialiser executor si nécessaire
     try {
         if (tickExec == null || tickExec.isShutdown() || tickExec.isTerminated()) {
             tickExec = Executors.newSingleThreadExecutor();
         }
     } catch (Exception ignored) {
         tickExec = Executors.newSingleThreadExecutor();
     }
     try {
         tickExec.execute(() -> {
             long since = lastTickSeq;
             while (tickLoopRunning) {
                 try {
                     DeliveryController c = controller;
                     if (c == null) {
                         try { Thread.sleep(250); } catch (InterruptedException ie) { break; }
                         continue;
                     }
                     // ✅ Court wait (<=1000ms) : évite de bloquer le serveur / starvation JobGet
                     ApiResult r = c.api_tickWait(since, 1000);
                     if (!tickLoopRunning) break;
                     if (r != null && r.code == 0 && r.data != null) {
                         JSONObject t = r.data;
                         if (t.optInt("has_tick", 0) == 1) {
                             long seq = t.optLong("seq", since);
                             if (seq > since) since = seq;
                             lastTickSeq = since;
                             applyTickSnapshotToUi(t);
                         }
                     }
                     // ✅ Refresh header (serial/ticket/uid) best-effort, peu fréquent
                     long now = System.currentTimeMillis();
                     if (now - lastHeaderRefreshMs > 5000) {
                         lastHeaderRefreshMs = now;
                         try { c.requestStatus(); } catch (Exception ignored) {}
                     }
                     // Petit sleep pour éviter busy-loop si TIMEOUT
                     try { Thread.sleep(60); } catch (InterruptedException ie) { break; }
                 } catch (Exception e) {
                     try { Thread.sleep(250); } catch (InterruptedException ie) { break; }
                 }
             }
         });
     } catch (Exception ignored) {}
 }

 private void stopTickLoop() {
     tickLoopRunning = false;
     try { if (tickExec != null) tickExec.shutdownNow(); } catch (Exception ignored) {}
 }

 private void applyTickSnapshotToUi(JSONObject tick) {
     if (tick == null) return;
     final double net = tick.optDouble("net", 0.0);
     final double gross = tick.optDouble("gross", 0.0);
     final int delCode = tick.optInt("delCode", 0);
     final boolean flowActive = (delCode & 0x0004) != 0;
     final boolean deliveryActive = (delCode & 0x0008) != 0;
     final boolean ticketPending = (delCode & 0x0001) != 0;
     final String st = tick.optString("state", "");

     // Cache ticketPending pour gating boutons / reprint
     ticketPendingFlag = ticketPending ? 1 : 0;

 // ✅ FIN de livraison: resync complet (permet nouvelle livraison + UI coherent)
 if (!deliveryActive && lastTickDeliveryActive) {
     lastTickSeq = 0L;
     starting = false;
     try { if (controller != null) controller.requestStatus(); } catch (Exception ignored) {}
     try { validateHeaderAsync(); } catch (Exception ignored) {}
 }
 lastTickDeliveryActive = deliveryActive;

     ui.post(() -> {
         if (!isAdded() || getView() == null) return;

         // LIVE Net/Gross (UI == API == registre)
         if (txtQtyNet != null) txtQtyNet.setText(String.format(Locale.CANADA_FRENCH, "NET: %.3f", net));
         if (txtQtyGross != null) txtQtyGross.setText(String.format(Locale.CANADA_FRENCH, "GROSS: %.3f", gross));

         // LIVE status (strict registre)
        if (txtLive != null) {
            if (deliveryActive && flowActive) {
                txtLive.setText("LIVE: RUNNING_FLOWING");
            } else if (deliveryActive) {
                // si on est en START UX, garder le message d'attente uniquement tant que FLOW est OFF
                if (starting) txtLive.setText("LIVE: RUNNING_FLOWING (flow off - waiting progression)");
                else txtLive.setText("LIVE: RUNNING_PAUSED");
            } else {
                txtLive.setText(ticketPending ? "LIVE: CONNECTED - Ticket pending" : "LIVE: CONNECTED - Ready");
            }
        }
        // Ticket pending (affichage tab)

         if (txtTicketPending != null) {
             txtTicketPending.setText("Ticket pending : " + (ticketPending ? "OUI" : "NON"));
         }

         updateButtons(controller != null ? controller.getState() : null);
     });
 }

    // =========================================================
    // ✅ Media-aware: choisir un TransportIo READY pour ce TAB
    // - Préfère BT si disponible, sinon USB
    // =========================================================
    private TransportIo pickTabTransportIo() {
        try {
            MediaTransportManager mgr = mediaMgr;
            if (mgr == null) return null;
            ArrayList<String> preferred = new ArrayList<>();
            // 1) BT d'abord (tous les BT:... connus)
            try {
                List<com.pa.lcr.lcp.transport.TransportSnapshot> snaps = mgr.listSnapshots();
                if (snaps != null) {
                    for (com.pa.lcr.lcp.transport.TransportSnapshot s : snaps) {
                        if (s == null || s.key == null) continue;
                        if (s.key.startsWith("BT:")) preferred.add(s.key);
                    }
                }
            } catch (Exception ignored) {}
            // 2) fallback USB
            preferred.add(MediaTransportManager.KEY_USB);
            TransportIo io = mgr.pickReady(preferred);
            if (io == null) io = mgr.pickReady(null);
            return (io != null && io.isOpen()) ? io : null;
        } catch (Exception ignored) {
            return null;
        }
    }

private void attemptAttachIfPossible(boolean verboseLog) {
        if (uiListenerAttached && controller != null) {
            syncUiFromController();
            return;
        }
        TransportIo io = pickTabTransportIo();
        if (io == null) {
            if (verboseLog) LogBus.api(node, "Auto-attach: aucun média READY (USB/BT) — attendre connexion.");
            return;
        }
        connectThisRegister(false);
    }

    private void connectThisRegister(boolean userInitiated) {
        TransportIo io = pickTabTransportIo();
        if (io == null) {
            if (userInitiated) {
                LogBus.api(node, "Aucun média prêt (USB/BT)");
                Toast.makeText(requireContext(), "Aucun média prêt (USB/BT)", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        String tk = io.getKey(); // "USB" ou "BT:AA:BB:.."
        tabTransportKey = tk;

        RegisterSessionManager sm = RegisterSessionManager.get(requireContext());
        controller = sm.getOrCreate(tk, node, from, io);
        if (controller == null) {
            if (userInitiated) Toast.makeText(requireContext(), "Transport non prêt", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!uiListenerAttached) {
            sm.attachUiListener(tk, node, uiListener);
            uiListenerAttached = true;
        }
        if (cbTxRx != null) controller.setTxRxLoggingEnabled(cbTxRx.isChecked());
        if (cbLogTs != null) controller.setLogTimestampsEnabled(cbLogTs.isChecked());

        lastTickSeq = 0L;
        syncUiFromController();
        validateHeaderAsync();
        if (userInitiated) LogBus.api(node, "Connect TAB: 1 - UI attached (" + tk + ")");
        scheduleLogRefresh();
    }

    private void detachUiListenerSafe() {
        if (!uiListenerAttached) return;
        try {
            RegisterSessionManager sm = RegisterSessionManager.get(requireContext());
            if (tabTransportKey != null && !tabTransportKey.trim().isEmpty()) {
                sm.detachUiListener(tabTransportKey, node, uiListener);
            } else {
                sm.detachUiListener(node, uiListener); // legacy fallback
            }
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

    /**
     * ✅ Anti-crash: protège l'exécution si executor shutdown/terminated après rebuild fragments
     * ✅ Et garde persist=false pour ne PAS écrire SQLite depuis l'UI validate
     */
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
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    private void updateButtons(DeliveryState state) {
        if (btnConnect == null ||
                btnA == null ||
                btnB == null ||
                btnC == null ||
                btnContinue == null ||
                btnFinish == null) return;

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

        // gate C (existant)
        btnC.setEnabled(connected && ticketPendingFlag != 1);

        if (starting) {
            btnContinue.setEnabled(false);
            btnFinish.setEnabled(false);
        } else {
            btnContinue.setEnabled(paused);
            btnFinish.setEnabled(paused && stableOff);
        }

        // ✅ Reprint: activé seulement si connecté-ish + ticket DONE + ticketNo présent
        if (btnReprintTicket != null) {
            boolean connectedish = (connected || paused || flowing);
            boolean ticketDone = (ticketPendingFlag != 1);
            btnReprintTicket.setEnabled(connectedish && ticketDone && hasTicketDigits());
        }
    }

    /**
     * ✅ Option A:
     * - préserver scroll du tab (NestedScrollView root)
     * - ne PAS auto-scroll le panneau log
     */
    private void refreshLogView() {
        if (txtLog == null) return;
        if (cbShowLog == null || !cbShowLog.isChecked()) return;

        // Force UI thread
        if (Looper.myLooper() != Looper.getMainLooper()) {
            ui.post(this::refreshLogView);
            return;
        }

        // Guard lifecycle
        if (!isAdded() || getView() == null) return;

        // ✅ NOTE: ne pas forcer le scroll du tab (évite le "jump" pendant refresh)


        // ✅ Option A: PAS d’auto-scroll du log
        // if (logScroll != null) logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
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
