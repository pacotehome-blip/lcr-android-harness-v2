
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

    // Log (par tab)
    private CheckBox cbShowLog, cbTxRx, cbLogTs;
    private View logPanel;
    private TextView txtLog;
    private ScrollView logScroll;
    private Button btnClearLog, btnCopyLog, btnScrollDown;

    private boolean logTsEnabled = false;
    private long logViewSinceMs = 0L;

    // Ticket pending (cache)
    private int ticketPendingFlag = -1; // -1 unknown, 0 NO, 1 YES

    // B2: auto-connect API-like (une seule fois par instance)
    private boolean autoConnectDone = false;

    private UsbManager usbManager;
    private DeliveryLogStore store;
    private LcpLink link;
    private DeliveryController controller;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    // ===== Live polling (comme l’ancien MainActivity) =====
    private static final int LIVE_POLL_MS = 200;
    private static final int STATUS_POLL_MS = 1000;

    private boolean liveTickRunning = false;
    private boolean statusTickRunning = false;

    // “Start in progress” (pour éviter Continue/Finish au début)
    private boolean starting = false;
    private long startingSinceMs = 0L;

    private final Runnable liveTick = new Runnable() {
        @Override public void run() {
            if (controller == null) { liveTickRunning = false; return; }
            DeliveryState st = controller.getState();
            boolean shouldPoll = (st == DeliveryState.RUNNING_FLOWING) || (st == DeliveryState.RUNNING_PAUSED);
            if (!shouldPoll) { liveTickRunning = false; return; }

            controller.requestLiveSample();
            ui.postDelayed(this, LIVE_POLL_MS);
        }
    };

    private final Runnable statusTick = new Runnable() {
        @Override public void run() {
            if (controller == null) { statusTickRunning = false; return; }
            DeliveryState st = controller.getState();
            boolean shouldPoll = (st == DeliveryState.RUNNING_FLOWING) || (st == DeliveryState.RUNNING_PAUSED);
            if (!shouldPoll) { statusTickRunning = false; return; }

            // Le status force souvent le refresh NET/GROSS/decimals (ce que tu observes manuellement)
            controller.requestStatus();
            ui.postDelayed(this, STATUS_POLL_MS);
        }
    };

    private void startLiveTickIfNeeded() {
        if (controller == null) return;
        if (liveTickRunning) return;

        DeliveryState st = controller.getState();
        boolean shouldPoll = (st == DeliveryState.RUNNING_FLOWING) || (st == DeliveryState.RUNNING_PAUSED);
        if (!shouldPoll) return;

        liveTickRunning = true;
        ui.removeCallbacks(liveTick);
        ui.postDelayed(liveTick, LIVE_POLL_MS);
    }

    private void stopLiveTick() {
        liveTickRunning = false;
        ui.removeCallbacks(liveTick);
    }

    private void startStatusTickIfNeeded() {
        if (controller == null) return;
        if (statusTickRunning) return;

        DeliveryState st = controller.getState();
        boolean shouldPoll = (st == DeliveryState.RUNNING_FLOWING) || (st == DeliveryState.RUNNING_PAUSED);
        if (!shouldPoll) return;

        statusTickRunning = true;
        ui.removeCallbacks(statusTick);
        ui.postDelayed(statusTick, STATUS_POLL_MS);
    }

    private void stopStatusTick() {
        statusTickRunning = false;
        ui.removeCallbacks(statusTick);
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        Bundle a = getArguments();
        if (a != null) {
            node = a.getInt(ARG_NODE, 250);
            from = a.getInt(ARG_FROM, 255);
        }
        usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);

        store = new DeliveryLogStore(context.getApplicationContext());
        store.purgeOlderThanDaysAsync(7);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (autoConnectDone) return;
        autoConnectDone = true;
        ui.post(this::autoConnectLikeApi);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        stopLiveTick();
        stopStatusTick();

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
        ticketPendingFlag = -1;

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
        logViewSinceMs = 0L;

        // boutons init (rien connecté)
        updateButtons(null);
    }

    private void wireUi() {
        cbShowLog.setOnCheckedChangeListener((b, checked) -> {
            logPanel.setVisibility(checked ? View.VISIBLE : View.GONE);
            logUi("[UI] Afficher log: " + (checked ? "ON" : "OFF"));
            if (checked) refreshLogView();
        });

        btnScrollDown.setOnClickListener(v -> logScroll.fullScroll(View.FOCUS_DOWN));

        btnClearLog.setOnClickListener(v -> {
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
        });

        cbTxRx.setOnCheckedChangeListener((b, checked) -> {
            if (controller != null) controller.setTxRxLoggingEnabled(checked);
            logUi("[UI] TX/RX: " + (checked ? "ON" : "OFF"));
            refreshLogView();
        });

        btnConnect.setOnClickListener(v -> connectThisRegister());

        btnA.setOnClickListener(v -> {
            if (controller != null) controller.alignOrRecover();
        });

        btnB.setOnClickListener(v -> {
            if (controller != null) controller.requestStatus();
        });

        btnC.setOnClickListener(v -> {
            if (controller == null) return;

            // ✅ Gate ticket_pending
            if (ticketPendingFlag == 1) {
                txtLive.setText("LIVE: ticket_pending — faire Resolve (A)");
                logUi("[UI] C bloqué: ticket_pending=1 (faire Resolve A)");
                updateButtons(controller.getState());
                return;
            }

            // ✅ Start delivery: disable Continue/Finish at beginning
            starting = true;
            startingSinceMs = System.currentTimeMillis();
            updateButtons(controller.getState());
            txtLive.setText("LIVE: RUNNING_FLOWING (flow off - waiting progression)");

            int prod = spnProduct.getSelectedItemPosition() + 1;
            double preset = parseDouble(edtPreset.getText().toString(), 0.0);

            controller.startDelivery(prod, preset);

            // démarrer polling pour avoir Net/Gross et transitions d’état
            startLiveTickIfNeeded();
            startStatusTickIfNeeded();
        });

        btnContinue.setOnClickListener(v -> {
            if (controller != null) controller.resumeIfPaused();
        });

        btnFinish.setOnClickListener(v -> {
            if (controller != null) controller.endDelivery();
        });
    }

    /**
     * Met à jour l'état enabled/disabled des boutons selon le state + ton besoin terrain.
     * - Continue/Finish désactivés au début du start
     * - Finish seulement si paused + stableOff
     */
    private void updateButtons(DeliveryState state) {
        boolean hasController = (controller != null);

        if (!hasController) {
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

        // Boutons toujours utiles
        btnConnect.setEnabled(true);
        btnB.setEnabled(true);

        // A utile quand connecté (et souvent aussi en running, mais on reste conservateur)
        btnA.setEnabled(connected || paused || flowing);

        // C seulement quand registre prêt (CONNECTED) + pas ticket pending
        btnC.setEnabled(connected && ticketPendingFlag != 1);

        // Continue/Finish: jamais au début d’un start
        if (starting) {
            btnContinue.setEnabled(false);
            btnFinish.setEnabled(false);
        } else {
            btnContinue.setEnabled(paused);
            btnFinish.setEnabled(paused && stableOff);
        }
    }

    /**
     * B2: auto-connect "API-like"
     * (Scan USB -> Open/Ping -> Connect LCP -> Validate)
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

        // 2) Port prêt via UsbSession (source de vérité)
        UsbSerialPort p = UsbSession.getPort();
        if (p == null) {
            logApi("[API] Open/Ping USB: 0 - Port non prêt (UsbSession port null).");
            return;
        }
        logApi("[API] Open/Ping USB: 1 - Port prêt");

        // 3) Connect TAB + validate
        connectThisRegister();
    }

    private void connectThisRegister() {
        UsbSerialPort p = UsbSession.getPort();
        if (p == null) {
            logApi("[API] USB non prêt (UsbSession port null)");
            Toast.makeText(requireContext(), "USB non prêt", Toast.LENGTH_SHORT).show();
            return;
        }

        // Stop old controller
        stopLiveTick();
        stopStatusTick();
        try { if (controller != null) controller.shutdown(false); } catch (Exception ignored) {}
        controller = null;
        link = null;

        link = new LcpLink(p, node, from, true);
        controller = new DeliveryController(link);

        // inject DB store
        try { controller.setLogStore(store); } catch (Exception ignored) {}

        // ✅ Partage UI<->API (unicité par node)
        try { RegisterSessionManager.get(requireContext()).setController(node, controller); } catch (Exception ignored) {}

        // Appliquer options log
        controller.setTxRxLoggingEnabled(cbTxRx.isChecked());
        controller.setLogTimestampsEnabled(cbLogTs.isChecked());
        link.setTraceTimestampsEnabled(cbLogTs.isChecked());

        // Listener UI
        controller.setListener(new DeliveryControllerPort.Listener() {

            @Override
            public void onStateChanged(DeliveryState state) {
                ui.post(() -> {
                    // Fin du mode "starting" dès qu’on voit FLOWING
                    if (starting && state == DeliveryState.RUNNING_FLOWING) {
                        starting = false;
                    }
                    // Sécurité: si start dure trop longtemps, autoriser Continue (opérateur)
                    if (starting && (System.currentTimeMillis() - startingSinceMs) > 12000L) {
                        starting = false;
                    }

                    // Live ticks pendant running
                    if (state == DeliveryState.RUNNING_FLOWING || state == DeliveryState.RUNNING_PAUSED) {
                        startLiveTickIfNeeded();
                        startStatusTickIfNeeded();
                    } else {
                        stopLiveTick();
                        stopStatusTick();
                    }

                    updateButtons(state);
                });
            }

            @Override public void onProductsUpdated(List<ProductUiItem> products, int activeIndex0) { }

            @Override public void onLog(String message) {
                logAutoClassify(message);
                if (cbShowLog != null && cbShowLog.isChecked()) refreshLogView();
            }

            @Override public void onError(String context, Throwable error) {
                LogBus.err(node, "[ERR " + context + "] " + (error != null ? error.getMessage() : ""));
                if (cbShowLog != null && cbShowLog.isChecked()) refreshLogView();
            }

            @Override
            public void onLiveQty(double net, double gross) {
                ui.post(() -> {
                    // Ici on affiche tel quel (si l’ordre est mauvais, c’est en amont dans DeliveryController)
                    txtQtyNet.setText(String.format(Locale.ROOT, "NET: %.3f", net));
                    txtQtyGross.setText(String.format(Locale.ROOT, "GROSS: %.3f", gross));
                });
            }

            @Override
            public void onLiveStatus(String liveText) {
                ui.post(() -> {
                    // Pendant start, tu veux voir "RUNNING_FLOWING (flow off - waiting progression)"
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
        });

        controller.initialize();
        logApi("[API] Connect TAB: 1 - CONNECTED node=" + node);

        // Validate header (serial/ticketPending + ticket_no)
        bg.execute(() -> {
            try {
                ApiResult r = controller.api_registerValidate(null, node, null, null, null);
                JSONObject j = r.toJson().optJSONObject("data");
                if (j != null) {
                    String serial = j.optString("serial_id", "");
                    String ticketNo = j.optString("ticket_no", "");
                    int tp = j.optInt("ticketPending", -1);

                    ticketPendingFlag = (tp == 1 ? 1 : (tp == 0 ? 0 : -1));

                    ui.post(() -> {
                        txtSerialId.setText("#Série : " + ((serial == null || serial.isEmpty()) ? "—" : serial));
                        txtTicketPending.setText("Ticket pending : " + (ticketPendingFlag == 1 ? "OUI" : (ticketPendingFlag == 0 ? "NON" : "—")));

                        // si ticket pending, guider immédiatement
                        if (ticketPendingFlag == 1) {
                            txtLive.setText("LIVE: ticket_pending — faire Resolve (A)");
                        }
                        updateButtons(controller != null ? controller.getState() : null);
                    });

                    // Trace DB UI
                    if (store != null && serial != null && !serial.isEmpty() && ticketNo != null && !ticketNo.isEmpty()) {
                        store.upsertSummaryAsync(serial, ticketNo, j.optString("sale_no",""),
                                "TAB_VALIDATE", DeliveryLogStore.SOURCE_UI, null, j.toString(), null);
                    }
                }
            } catch (Exception e) {
                logApi("[API] validate header fail: " + safeMsg(e));
            }
        });
    }

    // ---------- Logging (LogBus) + vue locale filtrée ----------
    private void refreshLogView() {
        if (txtLog == null) return;
        boolean includeIo = (cbTxRx != null && cbTxRx.isChecked());
        String text = LogBus.buildText(LogBus.filterNodeUIIOAPI(node, includeIo, logViewSinceMs), 1200);
        txtLog.setText(text);
    }

    private void logUi(String s) {
        if (s == null) return;
        LogBus.ui(node, maybeUiTimestamp(s));
    }

    private void logApi(String s) {
        if (s == null) return;
        LogBus.api(node, s);
    }

    private void logAutoClassify(String raw) {
        if (raw == null) return;
        String s = raw.trim();
        if (s.startsWith("[API]") || s.startsWith("[API ")) { LogBus.api(node, s); return; }
        if (s.startsWith("[IO ") || s.startsWith("TX:") || s.startsWith("RX:") || s.startsWith("↳")) { LogBus.io(node, s); return; }
        if (s.startsWith("[ERR") || s.startsWith("ERR[")) { LogBus.err(node, s); return; }
        LogBus.ui(node, maybeUiTimestamp(s));
    }

    private String maybeUiTimestamp(String line) {
        if (!logTsEnabled) return line;
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
